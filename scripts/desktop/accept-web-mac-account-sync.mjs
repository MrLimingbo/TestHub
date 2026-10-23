// Verify the running Web service and the installed Mac app without touching the real desktop profile.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {setTimeout as delay} from 'node:timers/promises'
import {chromium} from '../../services/playwright-runner/node_modules/playwright/index.mjs'
import {offscreenMac} from './offscreen-mac-qa.mjs'
import {ownedAccountDatabase, objectInventory} from './qa-owned-account-database.mjs'
import {ownedAccountSessions} from './qa-owned-account-sessions.mjs'

const run = promisify(execFile), args = process.argv.slice(2)
function argument(key) {
  const index = args.indexOf(key)
  assert.ok(index >= 0 && args[index + 1] && !args[index + 1].startsWith('--'), 'Required argument: ' + key)
  return args[index + 1]
}
assert.equal(process.platform, 'darwin')
const appPath = await fs.realpath(argument('--app')), origin = new URL(argument('--web')).origin
assert.equal(new URL(origin).hostname, '127.0.0.1', 'Only the local Web service is supported')
const executablePath = await fs.realpath(argument('--chromium')), output = path.resolve(argument('--output'))
const database = await ownedAccountDatabase({container:argument('--mysql-container'), database:argument('--database'), user:argument('--database-user'), password:process.env.TESTHUB_QA_MYSQL_PASSWORD})
const minio = argument('--minio-container'), bucket = argument('--bucket')
const sessions = ownedAccountSessions({container:argument('--redis-container'), password:process.env.TESTHUB_QA_REDIS_PASSWORD, origin})
await fs.mkdir(path.dirname(output), {recursive:true}); await (await fs.open(output, 'wx', 0o600)).close()
await fs.mkdir('.testhub', {recursive:true})
const owned = await fs.mkdtemp(path.resolve('.testhub/web-mac-account-qa-')), directory = path.join(owned, 'desktop')
await fs.mkdir(directory)
const name = 'TestHub-QA-' + crypto.randomUUID(), account = 'thqa-' + crypto.randomUUID().replaceAll('-', '')
const email = account + '@example.test', password = crypto.randomBytes(24).toString('base64url')
const sanitize = value => String(value).replaceAll(password,'[REDACTED]').replaceAll(email,'[QA email]').replaceAll(account,'[QA account]')
await fs.writeFile(path.join(owned, '.qa-owner.json'), JSON.stringify({name, account, email, password, origin}), {mode:0o600, flag:'wx'})
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const asar = path.join(appPath, 'Contents/Resources/app.asar'), asarBefore = sha(await fs.readFile(asar))
async function profileFingerprint(root) {
  const hash = crypto.createHash('sha256')
  async function visit(folder) {
    for (const entry of (await fs.readdir(folder, {withFileTypes:true})).sort((a,b) => a.name.localeCompare(b.name))) {
      const file = path.join(folder,entry.name), relative = path.relative(root,file)
      hash.update(relative + '\0')
      if (entry.isDirectory()) await visit(file)
      else if (entry.isSymbolicLink()) hash.update(await fs.readlink(file))
      else if (entry.isFile()) hash.update(await fs.readFile(file))
    }
  }
  await visit(root); return hash.digest('hex')
}
const realProfile = path.join(process.env.HOME, 'Library/Application Support/testhub-desktop-app')
const profileBefore = await profileFingerprint(realProfile), databaseBefore = await database.fingerprint(), objectsBefore = await objectInventory(minio,bucket)
const sessionsBefore = await sessions.fingerprint()
await fs.writeFile(path.join(owned,'.qa-baseline.json'),JSON.stringify({profileBefore,databaseBefore,objectsBefore,sessionsBefore,asarBefore}),{mode:0o600,flag:'wx'})
const checks = [], background = [], pass = message => { checks.push(message); console.log('PASS ' + message) }
const modules = [
  {label:'项目配置同步',file:'settings-sync',status:'settingsSyncStatus',enable:'enableSettingsSync'},
  {label:'项目文件同步',file:'project-file-sync',status:'projectFileSyncStatus',enable:'enableProjectFileSync'},
  {label:'接口设计资料同步',file:'api-design-sync',status:'apiDesignSyncStatus',enable:'enableApiDesignSync'},
  {label:'功能测试资料同步',file:'functional-sync',status:'functionalSyncStatus',enable:'enableFunctionalSync'},
]
let browser, web, app, failure, outcome = {passed:false, checks, background}, localId, remoteId
async function read(page, route) {
  return page.evaluate(async route => {
    const r = await globalThis.fetch(route), value = await r.json()
    if (!r.ok) throw new Error('QA read failed: HTTP ' + r.status)
    return value.data
  }, route)
}
async function send(page, route, body, method = 'POST') {
  return page.evaluate(async ({route,body,method}) => {
    const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data
    const r = await globalThis.fetch(route, {method, headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:body === undefined ? undefined : JSON.stringify(body)})
    if (r.status === 204) return null
    const value = await r.json()
    if (!r.ok) throw new Error('QA write failed: HTTP ' + r.status + ' ' + value.message)
    return value.data
  }, {route,body,method})
}
async function until(probe, message, timeout = 90000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { const value = await probe(); if (value) return value; await delay(200) }
  throw new Error('Timed out: ' + message)
}
async function project(page) {
  const context = await read(page,'/api/v1/access/context')
  assert.equal(context.organizations.length,1)
  const projects = context.projects[context.organizations[0].id]
  assert.equal(projects.length,1); return projects[0].id
}
async function login(page, identifier) {
  await page.getByPlaceholder('请输入账号或邮箱',{exact:true}).fill(identifier)
  await page.getByPlaceholder('请输入密码',{exact:true}).fill(password)
  await page.getByRole('button',{name:'进入工作空间',exact:true}).click()
  await page.waitForURL('**/dashboard')
}
async function configure() {
  await app.evaluate(({dialog}, titles) => {
    dialog.showMessageBox = async (_window, options) => {
      const state = globalThis.__testhubBackground
      if (!titles.includes(options.title) || options.defaultId !== 0 || options.cancelId !== 0) {
        state.unexpectedDialogs++; throw new Error('Unapproved QA native dialog')
      }
      state.confirmations.push({title:options.title,defaultId:options.defaultId})
      return {response:1}
    }
  }, modules.flatMap(module => ['启用'+module.label, '处理'+module.label+'冲突']))
}
async function native(module) {
  const prefix='testhub-desktop://setup/'+module.file+'.html'
  // The app intentionally keeps the current setup page while an automatic sync owns the workspace.
  const page=await until(async()=>{
    await app.evaluate(({Menu}, label) => Menu.getApplicationMenu().items[0].submenu.items.find(item => item.label === label).click(),module.label)
    return app.pages().find(page=>page.url().startsWith(prefix))
  },'native setup page: '+module.file)
  await page.locator('#refresh:not([disabled])').waitFor()
  await until(async () => {
    if (!await page.locator('#bind-form').isVisible() || await page.locator('#source option').count() > 1) return true
    const error = await page.locator('#error').textContent()
    if (error && !error.includes('其他工作区操作正在进行')) throw new Error(error)
    if (await page.locator('#refresh').isEnabled()) await page.locator('#refresh').click()
    return false
  }, 'native project selection')
  return page
}
async function status(page,module) { return page.evaluate(method => globalThis.testhubDesktop[method](),module.status) }
async function sync(module) {
  const page = await native(module)
  await page.locator('#run').click(); await page.locator('#run:not([disabled])').waitFor()
  const value = await status(page,module)
  assert.equal(value.error,''); assert.equal(value.pending,false)
  return {page,value}
}
async function pause(module, enabled) {
  const page = await native(module)
  await until(async () => {
    try { await page.evaluate(({method,enabled}) => globalThis.testhubDesktop[method](enabled), {method:module.enable,enabled}); return true }
    catch (error) { if (!error.message.includes('其他工作区操作正在进行')) throw error; return false }
  }, 'pause/resume sync')
}
async function localPage() {
  const find = async () => {
    for (const page of app.pages().filter(page => page.url().startsWith('http://127.0.0.1:') && !page.url().startsWith(origin+'/'))) {
      try { if (await page.getByText('本地工作区',{exact:true}).first().count()) return page } catch { /* The old renderer closes during workspace switching. */ }
    }
    return false
  }
  const existing = await find(); if (existing) return existing
  await app.evaluate(({Menu}) => Menu.getApplicationMenu().items[0].submenu.items.find(item => item.label === '本地工作区').click())
  return until(find,'local workspace')
}
async function connect() {
  const local = await localPage()
  await local.evaluate(() => globalThis.testhubDesktop.connectAccount())
  const setup = await app.pageFor('testhub-desktop://setup/setup.html')
  await setup.locator('#server-url').fill(origin); await setup.locator('#connect').click()
  return app.pageFor(origin+'/')
}
const base = id => '/api/v1/projects/'+id
async function parameter(page,id,key,value) { await send(page,base(id)+'/parameters',{environmentCode:'test',key,value}) }
async function parameterValue(page,id,key) { return (await read(page,base(id)+'/parameters')).parameters.find(row => row.key === key)?.value }
async function api(page,id,side) {
  const root = base(id)+'/api-design', catalog = await send(page,root+'/catalogs',{name:'QA '+side,parentId:null,sortOrder:0})
  return send(page,root+'/definitions',{catalogId:catalog.id,method:'GET',path:'/qa/'+side,name:'QA API '+side,versionName:'1.0.0',summary:'QA',description:'',headers:[],query:[],pathParameters:[],requestBody:'',responseBody:'{}',exampleResponse:{}})
}
const apiRows = (page,id) => read(page,base(id)+'/api-design/definitions')
async function editApi(page,id,side,suffix) {
  const row = (await apiRows(page,id)).find(item => item.path === '/qa/'+side)
  assert.ok(row)
  await send(page,base(id)+'/api-design/definitions/'+row.id,{catalogId:row.catalogId,method:row.method,path:row.path,name:'QA API '+side+suffix},'PUT')
}
async function functional(page,id,side) {
  const root = base(id)+'/functional', catalog = await send(page,root+'/catalogs',{name:'QA '+side,parentId:null,sortOrder:0,scope:'CASES'})
  return send(page,root+'/cases',{catalogId:catalog.id,requirementId:null,caseCode:('QA-'+side).toUpperCase(),title:'QA case '+side,priority:'P1',status:'READY',caseType:'FUNCTIONAL',precondition:'QA',steps:'Read the isolated fixture',expectedResult:'QA only'})
}
const caseRows = (page,id) => read(page,base(id)+'/functional/cases')
async function editCase(page,id,side,suffix) {
  const row = (await caseRows(page,id)).find(item => item.caseCode === ('QA-'+side).toUpperCase())
  assert.ok(row)
  await send(page,base(id)+'/functional/cases/'+row.id,{catalogId:row.catalogId,requirementId:null,caseCode:row.caseCode,title:'QA case '+side+suffix,priority:'P1',status:'READY',caseType:'FUNCTIONAL',precondition:'QA',steps:'Read the updated isolated fixture',expectedResult:'QA only'},'PUT')
}
async function upload(page,id,filename,content) {
  return page.evaluate(async ({route,filename,content}) => {
    const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data, body = new globalThis.FormData()
    body.append('file',new globalThis.File([content],filename,{type:'text/plain'}))
    const r = await globalThis.fetch(route,{method:'POST',headers:{[csrf.headerName]:csrf.token},body}), value = await r.json()
    if (!r.ok) throw new Error('QA file upload failed: HTTP '+r.status)
    return value.data
  }, {route:base(id)+'/files',filename,content})
}
async function fileBytes(page,id,filename) {
  const row = (await read(page,base(id)+'/files')).find(item => item.originalName === filename)
  if (!row) return undefined
  return page.evaluate(async route => { const r=await globalThis.fetch(route); if(!r.ok)throw new Error('QA download failed'); return r.text() },base(id)+'/files/'+row.id+'/download')
}
async function nativeLogout() {
  await app.evaluate(async ({session},origin) => {
    const crypto=globalThis.__testhubQaRequire('node:crypto'), partition='persist:testhub-server-'+crypto.createHash('sha256').update(origin).digest('hex').slice(0,24), remote=session.fromPartition(partition)
    const current=await remote.fetch(origin+'/api/v1/auth/me')
    if(current.status===401)return
    if(!current.ok)throw new Error('Cannot verify QA native session')
    const csrf=(await(await remote.fetch(origin+'/api/v1/auth/csrf')).json()).data
    const response=await remote.fetch(origin+'/api/v1/auth/logout',{method:'POST',headers:{[csrf.headerName]:csrf.token}})
    if(response.status!==204)throw new Error('QA native logout failed')
    const check=await remote.fetch(origin+'/api/v1/auth/me');if(check.status!==401)throw new Error('Native session remains authenticated')
  },origin)
}

try {
  await run('/usr/bin/codesign',['--verify','--deep','--strict',appPath])
  browser = await chromium.launch({headless:true,executablePath})
  web = await browser.newPage({viewport:{width:1440,height:1000}})
  await web.goto(origin+'/login')
  assert.equal((await read(web,'/api/v1/auth/capabilities')).registrationEnabled,true)
  await web.getByText('注册个人账号',{exact:true}).click()
  await web.getByPlaceholder('3–64 位字母、数字、点、下划线或短横线',{exact:true}).fill(account)
  await web.getByPlaceholder('请输入邮箱',{exact:true}).fill(email)
  await web.getByPlaceholder('选填，默认使用账号名称',{exact:true}).fill('后台联调验收')
  await web.getByPlaceholder('请输入密码',{exact:true}).fill(password)
  await web.getByPlaceholder('请再次输入密码（至少 8 个字符）',{exact:true}).fill(password)
  await web.getByRole('button',{name:'注册并进入工作区',exact:true}).click()
  await web.waitForURL('**/dashboard')
  const user = await read(web,'/api/v1/auth/me'), identity = await database.owned(account,email)
  remoteId = await project(web)
  assert.equal(user.id,identity.uid); assert.equal(remoteId,identity.pid)
  pass('Web registration creates a working session and an isolated personal project on the running service')

  app = await offscreenMac(appPath,directory,{name,cleanKeychain:false,observeRuntime:true,diagnosticLog:path.join(owned,'native-private.log'),startupDiagnostics:path.join(owned,'startup-private.json')})
  await configure()
  let local = app.local
  localId = await project(local); assert.notEqual(localId,remoteId)
  await parameter(local,localId,'QA_LOCAL','local-v1'); await parameter(web,remoteId,'QA_WEB','web-v1')
  await send(local,base(localId)+'/secrets',{environmentCode:'test',key:'QA_LOCAL_SECRET',value:crypto.randomBytes(12).toString('hex')})
  await send(web,base(remoteId)+'/secrets',{environmentCode:'test',key:'QA_WEB_SECRET',value:crypto.randomBytes(12).toString('hex')})
  await api(local,localId,'local'); await api(web,remoteId,'web')
  await functional(local,localId,'local'); await functional(web,remoteId,'web')
  await upload(local,localId,'qa-local.txt','local file v1\n'); await upload(web,remoteId,'qa-web.txt','web file v1\n')
  const remote = await connect()
  await login(remote,account)
  assert.equal((await read(remote,'/api/v1/auth/me')).id,user.id)
  assert.equal(await project(remote),remoteId)
  pass('Installed Mac app logs in with the registered Web account and sees the same personal project')
  local = await localPage()

  for (const module of modules) {
    const page = await native(module)
    await page.locator('#source').selectOption(localId); await page.locator('#target').selectOption(remoteId)
    if(module.file==='project-file-sync') { await page.locator('#load-files').click(); await page.locator('#prepare:not([disabled])').waitFor() }
    await page.locator('#prepare').click(); await page.locator('#bind:not([disabled])').waitFor()
    await page.locator('#bind').click(); await page.locator('#run:not([disabled])').waitFor()
    let state = await status(page,module)
    assert.equal(state.binding.enabled,true); assert.equal(state.error,'')
    if(module.file==='settings-sync') {
      for(let index=0;index<5 && state.conflicts.length;index++) {
        await page.locator('#conflicts [data-conflict][data-choice="remote"]').first().click()
        await page.locator('#run:not([disabled])').waitFor(); state=await status(page,module)
      }
    }
    assert.equal(state.conflicts.length,0)
    await pause(module,false)
    pass(module.label+': explicit project binding and initial two-way copy pass')
  }
  assert.equal(await parameterValue(web,remoteId,'QA_LOCAL'),'local-v1')
  assert.equal(await parameterValue(local,localId,'QA_WEB'),'web-v1')
  assert.equal((await apiRows(web,remoteId)).length,2); assert.equal((await apiRows(local,localId)).length,2)
  assert.equal((await caseRows(web,remoteId)).length,2); assert.equal((await caseRows(local,localId)).length,2)
  assert.equal(await fileBytes(web,remoteId,'qa-local.txt'),'local file v1\n')
  assert.equal(await fileBytes(local,localId,'qa-web.txt'),'web file v1\n')
  const localSecrets=await read(local,base(localId)+'/secrets'), remoteSecrets=await read(web,base(remoteId)+'/secrets')
  assert.ok(!JSON.stringify(localSecrets).includes('QA_WEB_SECRET')); assert.ok(!JSON.stringify(remoteSecrets).includes('QA_LOCAL_SECRET'))
  pass('Cross-side reads verify exact parameter values, API/case counts, file bytes, and exclusion of secrets')

  await parameter(local,localId,'QA_LOCAL','local-v2'); await parameter(web,remoteId,'QA_WEB','web-v2')
  await editApi(local,localId,'local',' updated'); await editApi(web,remoteId,'web',' updated')
  await editCase(local,localId,'local',' updated'); await editCase(web,remoteId,'web',' updated')
  await upload(local,localId,'qa-local-new.txt','local file v2\n'); await upload(web,remoteId,'qa-web-new.txt','web file v2\n')
  for (const module of modules) await pause(module,true)
  await until(async () => await parameterValue(web,remoteId,'QA_LOCAL')==='local-v2' && await parameterValue(local,localId,'QA_WEB')==='web-v2','automatic parameter sync')
  await until(async () => (await apiRows(web,remoteId)).some(row=>row.name==='QA API local updated') && (await apiRows(local,localId)).some(row=>row.name==='QA API web updated'),'automatic API sync')
  await until(async () => (await caseRows(web,remoteId)).some(row=>row.title==='QA case local updated') && (await caseRows(local,localId)).some(row=>row.title==='QA case web updated'),'automatic case sync')
  await until(async () => await fileBytes(web,remoteId,'qa-local-new.txt')==='local file v2\n' && await fileBytes(local,localId,'qa-web-new.txt')==='web file v2\n','automatic file sync')
  pass('Background timers propagate edits to parameters, API metadata and cases, plus newly uploaded files, in both directions without manual sync')
  for(const module of modules) { const {value}=await sync(module); assert.equal(value.conflicts.length,0); await pause(module,false) }
  assert.equal((await read(web,base(remoteId)+'/files')).length,4)
  assert.equal((await read(local,base(localId)+'/files')).length,4)
  pass('Repeated synchronization is idempotent: no duplicate APIs, cases or files')

  await parameter(local,localId,'QA_LOCAL','local-conflict'); await parameter(web,remoteId,'QA_LOCAL','web-conflict')
  await pause(modules[0],true)
  const conflict = await sync(modules[0])
  assert.equal(conflict.value.conflicts.length,1)
  assert.equal(await parameterValue(local,localId,'QA_LOCAL'),'local-conflict')
  assert.equal(await parameterValue(web,remoteId,'QA_LOCAL'),'web-conflict')
  await conflict.page.locator('#conflicts [data-conflict][data-choice="local"]').click()
  await conflict.page.locator('#run:not([disabled])').waitFor()
  assert.equal((await status(conflict.page,modules[0])).conflicts.length,0)
  assert.equal(await parameterValue(web,remoteId,'QA_LOCAL'),'local-conflict')
  pass('Concurrent edits pause as a visible conflict and only the confirmed version replaces its counterpart')
  for(const module of modules) await pause(module,true)
  const sessionCookie = await app.evaluate(async ({session},origin) => {
    const crypto=globalThis.__testhubQaRequire('node:crypto'), remote=session.fromPartition('persist:testhub-server-'+crypto.createHash('sha256').update(origin).digest('hex').slice(0,24))
    const cookies=await remote.cookies.get({url:origin,name:'TESTHUB_SESSION'})
    return {count:cookies.length,sessionOnly:cookies.every(cookie=>cookie.session)}
  },origin)
  assert.deepEqual(sessionCookie,{count:1,sessionOnly:true})
  background.push(await app.snapshot()); await app.close(); app=undefined

  app = await offscreenMac(appPath,directory,{name,cleanKeychain:false,observeRuntime:true,diagnosticLog:path.join(owned,'native-private.log'),startupDiagnostics:path.join(owned,'startup-private.json')})
  await configure(); local=app.local
  assert.equal(await project(local),localId)
  const restoredRemote=await connect()
  await restoredRemote.waitForURL('**/login')
  assert.equal(await restoredRemote.evaluate(async()=> (await globalThis.fetch('/api/v1/auth/me')).status),401)
  local=await localPage()
  await parameter(web,remoteId,'QA_WEB','while-logged-out')
  const guarded=await native(modules[0])
  await until(async()=>(await status(guarded,modules[0])).error.includes('同步会话已失效'),'logged-out sync protection')
  assert.equal(await parameterValue(local,localId,'QA_WEB'),'web-v2')
  pass('Session-only login cookies expire on Mac exit; unauthenticated automatic synchronization stops without changing local data')
  await login(await connect(),account)
  const authenticated=await app.pageFor(origin+'/')
  assert.equal((await read(authenticated,'/api/v1/auth/me')).id,user.id)
  local=await localPage()
  for(const module of modules) {
    const page=await native(module), value=await status(page,module)
    assert.equal(value.binding.enabled,true); assert.equal(value.binding.source.id,localId); assert.equal(value.binding.target.id,remoteId)
  }
  pass('Mac restart preserves the local workspace and all four explicit sync bindings; the same account can sign in again')
  for(const module of modules) await pause(module,false)
  await parameter(web,remoteId,'QA_WEB','after-restart')
  await pause(modules[0],true)
  await until(async()=>await parameterValue(local,localId,'QA_WEB')==='after-restart','automatic sync after restart')
  await pause(modules[0],false)
  pass('Automatic Web-to-local synchronization resumes after restart and reauthentication')

  await nativeLogout()
  const emailLogin=await connect()
  await emailLogin.goto(origin+'/login'); await login(emailLogin,email)
  assert.equal((await read(emailLogin,'/api/v1/auth/me')).id,user.id)
  pass('The Mac client also accepts the registered email; logout invalidates the previous server session')
  local=await localPage()
  await web.goto(origin+'/functional/cases'); await web.getByText('QA case local updated',{exact:true}).first().waitFor()
  await local.goto(new URL('/functional/cases',local.url()).href); await local.getByText('QA case web updated',{exact:true}).first().waitFor()
  pass('Web and Mac functional pages render the data synchronized from the opposite side')
  background.push(await app.snapshot())
  outcome={...outcome,passed:true}
} catch(error) {
  failure=error; outcome.error=sanitize(error.message); outcome.processState=app?.processState()
} finally {
  const cleanupErrors=[]
  let remoteFilesRemoved=false, sessionsRemoved=false
  async function cleanupStep(label,operation) { try { await operation() } catch(error) { cleanupErrors.push(label+': '+sanitize(error.message.split('\n')[0])) } }
  await cleanupStep('native session and process',async()=>{if(app){await nativeLogout();background.push(await app.snapshot());await app.close();app=undefined}})
  // Even on an earlier assertion failure, stop the owned app before deleting its remote data.
  await cleanupStep('native process fallback',async()=>{if(app){await app.close();app=undefined}})
  await cleanupStep('remote files and browser session',async()=>{
    const identity=await database.owned(account,email)
    if(identity){
      assert.ok(web && !web.isClosed(),'QA browser is required for physical object removal')
      assert.equal((await read(web,'/api/v1/auth/me')).id,identity.uid)
      for(const file of await read(web,base(identity.pid)+'/files')) await send(web,base(identity.pid)+'/files/'+file.id,undefined,'DELETE')
      assert.equal((await read(web,base(identity.pid)+'/files')).length,0)
      await send(web,'/api/v1/auth/logout')
      assert.equal(await web.evaluate(async()=> (await globalThis.fetch('/api/v1/auth/me')).status),401)
    }
    remoteFilesRemoved=true
  })
  await cleanupStep('browser process',async()=>{await browser?.close();browser=undefined})
  await cleanupStep('owned server sessions',async()=>{outcome.sessionCleanup=await sessions.cleanup(account);sessionsRemoved=true})
  await cleanupStep('owned account database',async()=>{
    assert.ok(remoteFilesRemoved && sessionsRemoved && !app,'Keep the account and recovery manifest until its files, sessions and native process are cleaned')
    outcome.accountCleanup=await database.cleanup(account,email)
  })
  await cleanupStep('data preservation',async()=>{
    assert.equal(await database.fingerprint(),databaseBefore,'Existing database rows changed')
    assert.deepEqual(await objectInventory(minio,bucket),objectsBefore,'Existing object inventory changed')
    assert.equal(await sessions.fingerprint(),sessionsBefore,'Preexisting Redis sessions changed')
    assert.equal(await profileFingerprint(realProfile),profileBefore,'Real desktop profile changed')
    assert.equal(sha(await fs.readFile(asar)),asarBefore,'Installed app changed')
    await run('/usr/bin/codesign',['--verify','--deep','--strict',appPath])
    outcome.originalDatabaseUnchanged=true;outcome.originalObjectsUnchanged=true;outcome.originalSessionsUnchanged=true;outcome.realDesktopProfileUnchanged=true;outcome.installedAppUnchanged=true
  })
  await cleanupStep('QA Keychain',async()=>{
    try{await run('/usr/bin/security',['delete-generic-password','-s',name+' Safe Storage','-a',name+' Key'])}catch(error){if(error.code!==44)throw error}
    try{await run('/usr/bin/security',['find-generic-password','-s',name+' Safe Storage','-a',name+' Key']);throw new Error('QA key remains')}catch(error){if(error.code!==44)throw error}
  })
  // Retain the private recovery manifest if cleanup fails; never discard the identifiers needed to finish it.
  if(!cleanupErrors.length) await fs.rm(owned,{recursive:true,force:true})
  outcome.cleanupComplete=cleanupErrors.length===0;outcome.cleanupErrors=cleanupErrors
  if(cleanupErrors.length){outcome.passed=false;failure ||= new Error('QA cleanup requires attention: '+cleanupErrors.join(', '))}
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw new Error(sanitize(failure.message))
console.log('PASS live Web / installed Mac account synchronization: '+checks.length+' checks; owned test data removed')
