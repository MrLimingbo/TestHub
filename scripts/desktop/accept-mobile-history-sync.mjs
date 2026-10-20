// Native packaged acceptance. Every process, account, request target and workspace belongs to this run.
import { Buffer } from 'node:buffer'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import http from 'node:http'
import { createRequire } from 'node:module'
import { setTimeout as delay } from 'node:timers/promises'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
import { offscreenMac } from './offscreen-mac-qa.mjs'
import { mobileProtocolFixture } from './qa-mobile-protocol.mjs'
import { performanceAccount } from './qa-performance-account.mjs'
const require = createRequire(import.meta.url)
const { loadBundle } = require('../../apps/desktop/app/src/local-runtime.cjs')
const { LocalBrowsers } = require('../../apps/desktop/app/src/local-browsers.cjs')
const args = process.argv.slice(2), argument = key => args[args.indexOf(key) + 1]
for (const key of ['--app','--output','--browser-cache','--chromium']) if (!args.includes(key)) throw new Error('Required argument: ' + key)
const appPath = await fs.realpath(argument('--app')), output = path.resolve(argument('--output'))
await fs.mkdir(path.dirname(output), {recursive:true})
const reservation = await fs.open(output,'wx',0o600); await reservation.close()
const owned = await fs.mkdtemp(path.resolve('.testhub/mobile-history-qa-'))
const directory = path.join(owned,'desktop'), accountDirectory = path.join(owned,'account'), backup = path.join(owned,'isolated.testhub-backup')
await fs.mkdir(directory); await fs.mkdir(accountDirectory)
const name = 'TestHub-QA-' + crypto.randomUUID()
await fs.writeFile(path.join(owned,'.qa-owner.json'),JSON.stringify({name}),{mode:0o600,flag:'wx'})
const bundle = await loadBundle(path.join(appPath,'Contents/Resources/local-runtime/darwin-arm64'))
const asar = path.join(appPath,'Contents/Resources/app.asar'), sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const originalAsarHash = sha(await fs.readFile(asar))
const checks = [], windows = [], summaries = [], pass = message => { checks.push(message); console.log('PASS ' + message) }
let app, browser, accountServer, outcome, failure, hits = 0
const target=http.createServer((_req,res)=>{hits++;res.end('Owned fixture target; no device requests expected')})
async function read(page, route) { return page.evaluate(async route => { const response = await globalThis.fetch(route), body = await response.json(); if (!response.ok) throw new Error(body.message);return body.data },route) }
async function send(page, route, body, method='POST') { return page.evaluate(async ({route,body,method}) => {
  const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data
  const response = await globalThis.fetch(route,{method,headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(body)})
  if(response.status===204)return;const result=await response.json();if(!response.ok)throw new Error(result.message);return result.data
},{route,body,method}) }
const project = async page => { const value = await read(page,'/api/v1/access/context');return value.projects[value.organizations[0].id][0].id }
async function until(probe, seconds=120) { const end=Date.now()+seconds*1000;while(Date.now()<end){const value=await probe();if(value)return value;await delay(150)}throw new Error('Mobile history acceptance timed out') }
async function terminal(page, base, id) { return until(async()=>{const value=(await read(page,base+'/executions/'+id+'/report')).execution;return ['SUCCEEDED','FAILED','TIMED_OUT','CANCELLED','REJECTED'].includes(value.status)&&value}) }
async function localPage() {
  const find=async()=>{for(const page of app.pages().filter(p=>p.url().startsWith('http://127.0.0.1:'))){try{if(await page.getByText('本地工作区',{exact:true}).first().count())return page}catch{/* Switching closes the previous renderer. */}}return false}
  const existing=await find();if(existing)return existing
  await app.evaluate(({Menu})=>Menu.getApplicationMenu().items[0].submenu.items.find(v=>v.label==='本地工作区').click())
  const page=await until(find)
  await until(()=>app.evaluate(({BrowserWindow})=>!BrowserWindow.getAllWindows().some(w=>w.webContents.getURL().startsWith('testhub-desktop://setup/'))))
  return page
}
async function native(label,filename) {
  await app.evaluate(({Menu},label)=>Menu.getApplicationMenu().items[0].submenu.items.find(v=>v.label===label).click(),label)
  const page=await app.pageFor('testhub-desktop://setup/'+filename+'.html');await page.locator(filename==='backup'?'#export':'#refresh:not([disabled])').waitFor()
  if(filename==='mobile-automation-artifact-sync')await until(async()=>{
    if(!await page.locator('#bind-form').isVisible()||await page.locator('#source option').count()>1)return true
    const error=await page.locator('#error').textContent()
    if(error&&!error.includes('其他工作区操作正在进行'))throw new Error(error)
    if(await page.locator('#refresh').isEnabled())await page.locator('#refresh').click()
    return false
  })
  return page
}
async function attest() { windows.push(await app.snapshot()) }
async function configureDialogs() {
  await app.evaluate(({dialog},backup)=>{
    const state=globalThis.__testhubBackground
    const unexpected=()=>{state.unexpectedDialogs++;throw new Error('Unexpected QA confirmation')}
    dialog.showMessageBox=async(_window,options)=>{
      if(!['启用移动端执行历史同步','解除移动端执行历史同步绑定','启用移动端报告产物同步','解除移动端报告产物同步绑定','恢复本地工作区'].includes(options.title)||options.defaultId!==0)return unexpected()
      state.confirmations.push({title:options.title,defaultId:options.defaultId});return {response:1}
    }
    dialog.showSaveDialog=async(_window,options)=>{if(options.title!=='保存加密工作区备份')return unexpected();return {canceled:false,filePath:backup}}
    dialog.showOpenDialog=async(_window,options)=>{if(options.title!=='选择要恢复的工作区备份')return unexpected();return {canceled:false,filePaths:[backup]}}
  },backup)
}
async function snapshot(page,base){const actor=await read(page,'/api/v1/auth/me');return read(page,base+'/artifacts/sync?expectedUserId='+actor.id)}
async function bytes(page,base,entry){return page.evaluate(async({route,entry})=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('Raw download failed');const data=await r.arrayBuffer(),digest=await globalThis.crypto.subtle.digest('SHA-256',data),actual=[...new Uint8Array(digest)].map(n=>n.toString(16).padStart(2,'0')).join('');if(actual!==entry.checksum||data.byteLength!==entry.sizeBytes||r.headers.get('X-TestHub-Artifact-SHA256')!==actual)throw new Error('Raw byte integrity mismatch');return {type:entry.artifactType,size:data.byteLength,sha256:actual}}, {route:base+'/artifacts/'+entry.key+'/download',entry})}
async function fileReport(page,base,execution){return until(async()=>{const r=await read(page,base+'/executions/'+execution.id+'/report'),artifacts=await read(page,base+'/executions/'+execution.id+'/artifacts');return r.steps.length===5&&artifacts.length===2&&{...r,artifacts}})}
async function localFixture(command,payload){return app.evaluate(async(_electron,{factory,command,payload})=>{
  const runtime=globalThis.__testhubQaLocalRuntime
  if(!runtime?.secrets||runtime.children.has('appium-runner'))throw new Error('Isolated runtime unavailable or real Appium unexpectedly started')
  const create=(0,eval)('('+factory+')'),fixture=create({origin:runtime.url,brokerKey:runtime.brokerKey,heartbeatKey:runtime.secrets.heartbeatKey,crypto:globalThis.__testhubQaRequire('node:crypto')})
  return fixture[command](payload)
},{factory:mobileProtocolFixture.toString(),command,payload})}
const pngFixtures=["iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg=="]

async function createScenario(page,base,platform){
  const device=await send(page,base+'/devices',{name:'同名设备',platform,udid:'raw-private-udid-'+platform,automationName:platform==='ANDROID'?'UiAutomator2':'XCUITest',capabilities:{'appium:deviceName':'private-capability'},enabled:true})
  const application=await send(page,base+'/applications',{name:'同名应用',platform,appIdentifier:'raw.private.application',versionName:'private-version',appFileId:null,enabled:true})
  const element=await send(page,base+'/elements',{name:'输入元素',platform,locatorStrategy:'ACCESSIBILITY_ID',locatorValue:'#private-input',description:''})
  return send(page,base+'/scenarios',{deviceId:device.id,applicationId:application.id,name:'同名来源场景',description:'Owned protocol fixture; no real device',failureStrategy:'STOP',variables:{privateFixture:'raw-private-variable'},steps:[{id:'launch',type:'LAUNCH',name:'启动',timeoutMillis:30000},{id:'input',type:'INPUT',name:'填写',elementId:element.id,value:'raw-private-input',timeoutMillis:30000},{id:'shot1',type:'SCREENSHOT',name:'第一张截图',timeoutMillis:30000},{id:'wait',type:'WAIT',name:'等待',timeoutMillis:1},{id:'shot2',type:'SCREENSHOT',name:'第二张截图',timeoutMillis:30000}]})
}
async function seedExecution(page,base,scenario,fixture){
 const execution=await send(page,base+'/scenarios/'+scenario.id+'/executions',{timeoutSeconds:120}),ids={taskId:execution.taskId,executionId:execution.id}
 await fixture('claim',ids)
 for(const [i,step] of scenario.steps.entries())await fixture('step',{...ids,stepId:step.id,stepSequence:i+1,stepType:step.type,stepName:step.name})
 for(const content of pngFixtures)await fixture('screenshot',{...ids,content})
 await fixture('finish',ids);assert.equal((await terminal(page,base,execution.id)).status,'SUCCEEDED');return execution
}
async function bindHistory(localId,remoteId){const p=await native('移动端执行历史同步','mobile-automation-history-sync');const preview=await p.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewMobileAutomationHistorySync({sourceId,targetId}),{sourceId:localId,targetId:remoteId});await p.evaluate(token=>globalThis.testhubDesktop.bindMobileAutomationHistorySync(token),preview.token)}
async function invokeHistory(){const p=await native('移动端执行历史同步','mobile-automation-history-sync');await p.evaluate(()=>globalThis.testhubDesktop.runMobileAutomationHistorySync())}
async function raw(){return native('移动端报告产物同步','mobile-automation-artifact-sync')}
async function installFaults(){await app.evaluate(({session})=>{
  const prototype=Object.getPrototypeOf(session.defaultSession),original=prototype.fetch
  globalThis.__rawFault={corruptOnce:false,dropPostOnce:false,corrupted:0,dropped:0}
  prototype.fetch=async function(input,options){const url=typeof input==='string'?input:input?.url,r=await original.call(this,input,options),state=globalThis.__rawFault
    if(state.corruptOnce&&/\/mobile-automation\/artifacts\/[^/]+\/download$/.test(url)){state.corruptOnce=false;state.corrupted++;await r.arrayBuffer();return {ok:true,status:200,body:(async function*(){yield globalThis.__testhubQaRequire('node:buffer').Buffer.from('owned corrupt wire bytes')})()}}
    if(state.dropPostOnce&&options?.method==='POST'&&url.endsWith('/mobile-automation/artifacts/sync')){state.dropPostOnce=false;state.dropped++;await r.arrayBuffer();throw new Error('Owned QA response loss after commit')}
    return r
  }
})}
try {
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+target.address().port
  const manager=new LocalBrowsers({directory:path.join(directory,'browser-engines'),bundle}),plan=await manager.plan('chromium'),cache=plan.cache
  await fs.mkdir(cache,{recursive:true});const sourceCache=await fs.realpath(argument('--browser-cache'))
  for(const entry of [...plan.components.map(c=>c.directory),'.ready-chromium.json'])await fs.cp(path.join(sourceCache,entry),path.join(cache,entry),{recursive:true,verbatimSymlinks:true})
  assert.equal(await manager.installed('chromium'),cache)
  const chromiumRelative=path.relative(sourceCache,await fs.realpath(argument('--chromium')));assert.ok(!chromiumRelative.startsWith('..'))
  app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,observeRuntime:true,diagnosticLog:path.join(path.dirname(output),'flow-native-private.log')});await configureDialogs();await installFaults();let local=app.local
  const localId=await project(local),baseLocal=`/api/v1/projects/${localId}/mobile-automation`
  const localScene=await createScenario(local,baseLocal,'ANDROID')
  const localExecution=await seedExecution(local,baseLocal,localScene,localFixture),localReport=await fileReport(local,baseLocal,localExecution)
  assert.equal(localReport.artifacts.length,2)
  accountServer=await performanceAccount({bundle,directory:accountDirectory,origin});const server=accountServer.server
  await local.evaluate(()=>globalThis.testhubDesktop.connectAccount());const setup=await app.pageFor('testhub-desktop://setup/setup.html');await setup.locator('#server-url').fill(server);await setup.locator('#connect').click()
  const remote=await app.pageFor(server),account='mobilehistory'+Date.now(),password=crypto.randomBytes(20).toString('base64url')
  await send(remote,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离移动端同步验收',password})
  const remoteId=await project(remote),baseRemote=`/api/v1/projects/${remoteId}/mobile-automation`
  browser=await chromium.launch({headless:true,executablePath:path.join(cache,chromiumRelative)});const web=await browser.newPage();await web.goto(server+'/login');await send(web,'/api/v1/auth/login',{account,password})
  const remoteFixture=accountServer.mobileFixture(),remoteScene=await createScenario(web,baseRemote,'IOS')
  const remoteExecution=await seedExecution(web,baseRemote,remoteScene,(command,payload)=>remoteFixture[command](payload));await fileReport(web,baseRemote,remoteExecution)
  local=await localPage();const localOriginal=(await snapshot(local,baseLocal)).entries,remoteOriginal=(await snapshot(web,baseRemote)).entries
  assert.equal(localOriginal.length,2);assert.equal(remoteOriginal.length,2);const initialHits=hits
  pass('independent local Android and account iOS protocol fixtures archive five steps and two PNGs each without Appium, simulators or devices')
  let page=await raw();assert.equal(await page.locator('#raw-files').isChecked(),false)
  await assert.rejects(page.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewMobileAutomationArtifactSync({sourceId,targetId}),{sourceId:localId,targetId:remoteId}),/单独勾选/)
  const blocked=await page.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewMobileAutomationArtifactSync({sourceId,targetId,rawFilesConfirmed:true}),{sourceId:localId,targetId:remoteId});assert.equal(blocked.upload,0);assert.equal(blocked.download,0);assert.equal(blocked.conflictCount,4)
  await bindHistory(localId,remoteId);local=await localPage();assert.equal((await snapshot(web,baseRemote)).entries.filter(e=>!e.nativeRecord).length,0)
  pass('raw sync is separately opted in and missing counterpart histories block all four files; report binding alone transfers no raw bytes')
  console.log('QA opening raw binding');page=await raw();await page.locator('#source').selectOption(localId);await page.locator('#target').selectOption(remoteId);await page.locator('#raw-files').check();await page.locator('#prepare').click();await page.locator('#bind:not([disabled])').waitFor();assert.match(await page.locator('#preview').textContent(),/首次上传 2 个原始工件、下载 2/)
  const dashboardBefore=await read(web,`/api/v1/projects/${remoteId}/dashboard?days=7`)
  console.log('QA arming controlled wire corruption');await app.evaluate(()=>{globalThis.__rawFault.corruptOnce=true});await page.locator('#bind').click();await page.locator('#run:not([disabled])').waitFor()
  console.log('QA reading first raw receipt');let status=await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus());assert.equal(status.conflicts.length,1);assert.equal((await snapshot(web,baseRemote)).entries.length,3)
  await page.evaluate(()=>globalThis.testhubDesktop.runMobileAutomationArtifactSync());assert.equal((await snapshot(web,baseRemote)).entries.length,4);assert.equal(hits,initialHits)
  assert.equal((await app.evaluate(()=>globalThis.__rawFault)).corrupted,1)
  for(const entry of [...localOriginal,...remoteOriginal]){await bytes(web,baseRemote,entry);await bytes(local,baseLocal,entry)}
  const dashboardAfter=await read(web,`/api/v1/projects/${remoteId}/dashboard?days=7`);for(const k of ['summary','trend','distribution'])assert.deepEqual(dashboardBefore[k],dashboardAfter[k])
  assert.equal((await read(local,baseLocal)).executions.length,1);assert.equal((await read(web,baseRemote)).executions.length,1)
  pass('a corrupted wire file pauses independently; other files continue, then retry yields byte-identical bidirectional originals without tasks or statistical duplication')
  const originalHistory=await read(web,baseRemote+'/history/'+localId+'.'+localExecution.id)
  for(const hidden of ['raw-private-input','raw-private-variable','raw-private-udid','raw.private.application','#private-input','runnerId','manifestRef'])assert.ok(!JSON.stringify(originalHistory).includes(hidden))
  await web.goto(server+'/mobile-automation');const historySection=web.locator('section[aria-label="移动端同步历史"]');await historySection.getByRole('button',{name:'查看历史',exact:true}).click()
  const originalSection=web.locator('section[aria-label="移动端原始报告产物"]');await originalSection.getByRole('link',{name:'下载截图',exact:true}).first().waitFor()
  assert.equal(await originalSection.getByRole('link',{name:'下载截图',exact:true}).count(),2)
  const expected=localOriginal.filter(e=>e.artifactType==='SCREENSHOT')[0],link=originalSection.getByRole('link',{name:'下载截图',exact:true}).first()
  const [download]=await Promise.all([web.waitForEvent('download'),link.click()]);assert.equal(download.suggestedFilename(),'screenshot.png');assert.equal(sha(await fs.readFile(await download.path())),expected.checksum);await download.delete()
  await originalSection.getByRole('button',{name:'预览截图',exact:true}).first().click();await originalSection.locator('img').first().waitFor();await until(()=>originalSection.locator('img').first().evaluate(img=>img.complete&&img.naturalWidth>0))
  await originalSection.getByRole('button',{name:'关闭预览',exact:true}).click()
  await web.locator('.arco-drawer').filter({hasText:'只读执行历史'}).locator('.arco-drawer-close-btn').click()
  pass('headless web shows frozen source versions and steps, keeps both protocol-fixture PNGs distinct, explicitly previews and downloads byte-identical screenshots')
  const nativeLargePending=await app.evaluate(async({safeStorage},{directory,state})=>{
    const require=globalThis.__testhubQaRequire,fs=require('node:fs/promises'),path=require('node:path'),crypto=require('node:crypto'),{Buffer}=require('node:buffer')
    const {MobileAutomationArtifactSyncStore}=require(path.join(process.resourcesPath,'app.asar/src/mobile-automation-artifact-sync.cjs'))
    const owned=path.join(directory,'large-pending-qa');await fs.mkdir(owned,{recursive:false})
    try{
      const data=Buffer.alloc(25*1024*1024,120),entry={key:state.binding.source.id+'.'+'Z'.repeat(26)+'.'+'Y'.repeat(26),sourceArtifactId:'Y'.repeat(26),artifactType:'SCREENSHOT',originalName:'screenshot.png',contentType:'image/png',sizeBytes:data.length,checksum:crypto.createHash('sha256').update(data).digest('hex'),archivedAt:new Date().toISOString(),nativeRecord:true}
      const value={...state,pending:{side:'remote',entry,request:{requestKey:crypto.randomUUID(),expectedUserId:state.binding.actor.id,expectedRevision:'a'.repeat(64),rawFilesConfirmed:true,artifact:{...entry,nativeRecord:false},content:data.toString('base64')}}}
      delete value.error
      const store=new MobileAutomationArtifactSyncStore(owned,{encrypt:v=>safeStorage.encryptStringAsync(v),decrypt:async v=>(await safeStorage.decryptStringAsync(v)).result})
      await store.write(value);const restored=await store.read();if(restored.pending.request.content!==value.pending.request.content)throw new Error('Native large pending bytes changed')
      const file=await fs.readFile(store.file);if(file.includes(Buffer.from(value.pending.request.content.slice(0,128))))throw new Error('Raw bytes leaked into unencrypted state')
      return {passed:true,bytes:data.length,privateFile:((await fs.stat(store.file)).mode&0o777)===0o600}
    }finally{await fs.rm(owned,{recursive:true,force:true})}
  },{directory,state:await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus())})
  assert.equal(nativeLargePending.passed,true);assert.equal(nativeLargePending.privateFile,true)
  pass('native safeStorage encrypts and restores a full 25 MiB pending file without exposing bytes in UI status or leaving its probe data')

  page=await raw();await page.evaluate(()=>globalThis.testhubDesktop.enableMobileAutomationArtifactSync(false));
  const edited=await send(local,baseLocal+'/scenarios/'+localScene.id,{expectedVersion:1,scenario:{deviceId:localScene.deviceId,applicationId:localScene.applicationId,name:localScene.name,description:'version two after original fixture',failureStrategy:localScene.failureStrategy,variables:localScene.variables,steps:localScene.steps}},'PUT')
  assert.equal(edited.version,2);assert.deepEqual(await read(web,baseRemote+'/history/'+localId+'.'+localExecution.id),originalHistory)
  const second=await seedExecution(local,baseLocal,edited,localFixture);await fileReport(local,baseLocal,second)
  const finalHits=hits
  await invokeHistory();page=await raw();await page.evaluate(()=>globalThis.testhubDesktop.runMobileAutomationArtifactSync());assert.equal((await snapshot(web,baseRemote)).entries.length,4)
  await app.evaluate(()=>{globalThis.__rawFault.dropPostOnce=true});await page.evaluate(()=>globalThis.testhubDesktop.enableMobileAutomationArtifactSync(true));await assert.rejects(page.evaluate(()=>globalThis.testhubDesktop.runMobileAutomationArtifactSync()),/同步服务暂时不可达/)
  status=await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus());assert.ok(status.pending);assert.equal(status.pending.request,undefined);assert.equal((await snapshot(web,baseRemote)).entries.length,5)
  const pendingKey=status.pending.key;assert.equal((await app.evaluate(()=>globalThis.__rawFault)).dropped,1);await attest();await app.close();app=undefined
  app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,observeRuntime:true,diagnosticLog:path.join(path.dirname(output),'flow-native-private.log')});await configureDialogs();local=app.local;page=await raw();status=await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus());assert.equal(status.pending.key,pendingKey)
  await assert.rejects(page.evaluate(()=>globalThis.testhubDesktop.runMobileAutomationArtifactSync()),/同步会话已失效/)
  assert.equal((await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus())).pending.key,pendingKey)
  await local.evaluate(()=>globalThis.testhubDesktop.connectAccount());const loginSetup=await app.pageFor('testhub-desktop://setup/setup.html');await loginSetup.locator('#server-url').fill(server);await loginSetup.locator('#connect').click()
  const loggedIn=await app.pageFor(server);await send(loggedIn,'/api/v1/auth/login',{account,password});local=await localPage();page=await raw()
  await page.evaluate(()=>globalThis.testhubDesktop.runMobileAutomationArtifactSync());assert.equal((await snapshot(web,baseRemote)).entries.length,6);assert.equal((await snapshot(local,baseLocal)).entries.length,6);assert.equal(hits,finalHits)
  pass('pause blocks new files; lost committed response remains encrypted across restart, rejects expired login, then retries the durable request after same-account login without duplicate artifacts or tasks')
  await page.evaluate(()=>globalThis.testhubDesktop.unbindMobileAutomationArtifactSync());const preview=await page.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewMobileAutomationArtifactSync({sourceId,targetId,rawFilesConfirmed:true}),{sourceId:localId,targetId:remoteId});assert.equal(preview.upload,0);assert.equal(preview.download,0);await page.evaluate(token=>globalThis.testhubDesktop.bindMobileAutomationArtifactSync(token),preview.token)
  const encrypted=await fs.readFile(path.join(directory,'mobile-automation-artifact-sync.bin'));assert.ok(!encrypted.includes(Buffer.from(localOriginal[0].key)));assert.equal((await fs.stat(path.join(directory,'mobile-automation-artifact-sync.bin'))).mode&0o777,0o600)
  pass('unbind and rebind preserve six immutable files and state remains encrypted with private permissions')
  const backupPage=await native('本地备份与恢复','backup'),backupPassword=crypto.randomBytes(20).toString('base64url')
  await backupPage.evaluate(password=>globalThis.testhubDesktop.runBackup({action:'export',password}),backupPassword);await backupPage.evaluate(password=>globalThis.testhubDesktop.runBackup({action:'restore',password}),backupPassword)
  local=await localPage();for(const entry of remoteOriginal)await bytes(local,baseLocal,entry)
  page=await raw();status=await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus());assert.equal(status.binding.enabled,false);assert.equal(status.binding.requiresRebind,true);await assert.rejects(page.evaluate(()=>globalThis.testhubDesktop.enableMobileAutomationArtifactSync(true)),/恢复过备份/)
  await attest();await app.close();app=undefined;app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,observeRuntime:true,diagnosticLog:path.join(path.dirname(output),'flow-native-private.log')});await configureDialogs();local=app.local;page=await raw();status=await page.evaluate(()=>globalThis.testhubDesktop.mobileAutomationArtifactSyncStatus());assert.equal(status.binding.requiresRebind,true);assert.equal(status.binding.enabled,false);for(const entry of remoteOriginal)await bytes(local,baseLocal,entry);await attest()
  pass('real backup restore retains imported raw bytes and persists mandatory raw-binding suspension across another packaged restart')
  const historyPage=await native('移动端执行历史同步','mobile-automation-history-sync'),historyStatus=await historyPage.evaluate(()=>globalThis.testhubDesktop.mobileAutomationHistorySyncStatus());assert.equal(historyStatus.binding.requiresRebind,true);assert.equal(historyStatus.binding.enabled,false)
  for(const entry of (await snapshot(web,baseRemote)).entries)summaries.push(await bytes(web,baseRemote,entry))
  const tasks=await read(web,`/api/v1/projects/${remoteId}/tasks/executions`);assert.equal(tasks.filter(e=>e.module==='MOBILE_HISTORY').length,2);assert.equal(tasks.filter(e=>e.module==='MOBILE').length,1)
  assert.equal(sha(await fs.readFile(asar)),originalAsarHash);assert.equal(hits,0)
  await app.evaluate(()=>{if(globalThis.__testhubQaLocalRuntime.children.has('appium-runner'))throw new Error('Unexpected real Appium process')})
  outcome={passed:true,checks,background:windows,files:summaries,nativeLargePending,localProtocolFixtures:2,accountProtocolFixtures:1,realMobileDeviceExecutions:0,appiumStarted:false,targetRequests:hits,asarUnchanged:true,schemaVersion:46}
}catch(error){failure=error;outcome={passed:false,checks,error:error.message,processState:app?.processState()}}
finally{
  let cleanupError
  for(const operation of [()=>browser?.close(),()=>app?.close(),()=>accountServer?.stop()])try{await operation()}catch(error){cleanupError||=error}
  target.closeAllConnections();await new Promise(resolve=>target.close(resolve));await fs.rm(owned,{recursive:true,force:true})
  const {execFile}=await import('node:child_process'),{promisify}=await import('node:util'),run=promisify(execFile)
  try{await run('/usr/bin/security',['delete-generic-password','-s',name+' Safe Storage','-a',name+' Key'])}catch(error){if(error.code!==44)cleanupError||=error}
  try{await run('/usr/bin/security',['find-generic-password','-s',name+' Safe Storage']);cleanupError||=new Error('Owned QA key remains')}catch(error){if(error.code!==44)cleanupError||=error}
  outcome.ownedDataAndProcessesRemoved=!cleanupError;outcome.ownedQaKeychainAbsent=!cleanupError;if(cleanupError){outcome.passed=false;failure||=cleanupError}
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
console.log('PASS Mobile history/artifact module '+checks.length+' checks')
