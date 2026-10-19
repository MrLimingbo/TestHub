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
import { performanceAccount } from './qa-performance-account.mjs'
const require = createRequire(import.meta.url)
const { loadBundle } = require('../../apps/desktop/app/src/local-runtime.cjs')
const { writeExecutionOrigins } = require('../../apps/desktop/app/src/local-execution.cjs')
const args = process.argv.slice(2), argument = key => args[args.indexOf(key) + 1]
for (const key of ['--app','--output','--jmeter-cache','--chromium']) if (!args.includes(key)) throw new Error('Required argument: ' + key)
const appPath = await fs.realpath(argument('--app')), output = path.resolve(argument('--output'))
await fs.mkdir(path.dirname(output), {recursive:true})
const reservation = await fs.open(output,'wx',0o600); await reservation.close()
const owned = await fs.mkdtemp(path.resolve('.testhub/performance-artifact-qa-'))
const directory = path.join(owned,'desktop'), accountDirectory = path.join(owned,'account'), backup = path.join(owned,'isolated.testhub-backup')
await fs.mkdir(directory); await fs.mkdir(accountDirectory)
const name = 'TestHub-QA-' + crypto.randomUUID()
await fs.writeFile(path.join(owned,'.qa-owner.json'),JSON.stringify({name}),{mode:0o600,flag:'wx'})
const bundle = await loadBundle(path.join(appPath,'Contents/Resources/local-runtime/darwin-arm64'))
const asar = path.join(appPath,'Contents/Resources/app.asar'), sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const originalAsarHash = sha(await fs.readFile(asar))
const checks = [], windows = [], summaries = [], pass = message => { checks.push(message); console.log('PASS ' + message) }
let app, browser, accountServer, outcome, failure, hits = 0
const target = http.createServer((_req,res) => { hits++; res.setHeader('content-type','application/json');res.end('{"ok":true,"privateResponse":"withheld-response"}') })
async function read(page, route) { return page.evaluate(async route => { const response = await globalThis.fetch(route), body = await response.json(); if (!response.ok) throw new Error(body.message);return body.data },route) }
async function send(page, route, body, method='POST') { return page.evaluate(async ({route,body,method}) => {
  const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data
  const response = await globalThis.fetch(route,{method,headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(body)})
  if(response.status===204)return;const result=await response.json();if(!response.ok)throw new Error(result.message);return result.data
},{route,body,method}) }
const project = async page => { const value = await read(page,'/api/v1/access/context');return value.projects[value.organizations[0].id][0].id }
async function until(probe, seconds=120) { const end=Date.now()+seconds*1000;while(Date.now()<end){const value=await probe();if(value)return value;await delay(150)}throw new Error('Performance history acceptance timed out') }
async function terminal(page, base, id) { return until(async()=>{const value=await read(page,base+'/executions/'+id);return ['SUCCEEDED','FAILED','TIMED_OUT','CANCELLED','REJECTED'].includes(value.status)&&value}) }
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
  if(filename==='performance-artifact-sync')await until(async()=>{
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
      if(!['启用性能执行历史同步','解除性能执行历史同步绑定','启用原始 JTL／日志同步','解除原始 JTL／日志同步绑定','恢复本地工作区'].includes(options.title)||options.defaultId!==0)return unexpected()
      state.confirmations.push({title:options.title,defaultId:options.defaultId});return {response:1}
    }
    dialog.showSaveDialog=async(_window,options)=>{if(options.title!=='保存加密工作区备份')return unexpected();return {canceled:false,filePath:backup}}
    dialog.showOpenDialog=async(_window,options)=>{if(options.title!=='选择要恢复的工作区备份')return unexpected();return {canceled:false,filePaths:[backup]}}
  },backup)
}
async function snapshot(page,base){const actor=await read(page,'/api/v1/auth/me');return read(page,base+'/artifacts/sync?expectedUserId='+actor.id)}
async function bytes(page,base,entry){return page.evaluate(async({route,entry})=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('Raw download failed');const data=await r.arrayBuffer(),digest=await globalThis.crypto.subtle.digest('SHA-256',data),actual=[...new Uint8Array(digest)].map(n=>n.toString(16).padStart(2,'0')).join('');if(actual!==entry.checksum||data.byteLength!==entry.sizeBytes||r.headers.get('X-TestHub-Artifact-SHA256')!==actual)throw new Error('Raw byte integrity mismatch');return {type:entry.artifactType,size:data.byteLength,sha256:actual}}, {route:base+'/artifacts/'+entry.key+'/download',entry})}
async function fileReport(page,base,execution){return until(async()=>{const r=await read(page,base+'/executions/'+execution.id+'/report');return r.summary.sampleCount>0&&r.artifacts.length===2&&r})}
async function bindHistory(localId,remoteId){const p=await native('性能执行历史同步','performance-history-sync');const preview=await p.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewPerformanceHistorySync({sourceId,targetId}),{sourceId:localId,targetId:remoteId});await p.evaluate(token=>globalThis.testhubDesktop.bindPerformanceHistorySync(token),preview.token)}
async function invokeHistory(){const p=await native('性能执行历史同步','performance-history-sync');await p.evaluate(()=>globalThis.testhubDesktop.runPerformanceHistorySync())}
async function raw(){return native('原始 JTL／日志同步','performance-artifact-sync')}
async function installFaults(){await app.evaluate(({session})=>{
  const prototype=Object.getPrototypeOf(session.defaultSession),original=prototype.fetch
  globalThis.__rawFault={corruptOnce:false,dropPostOnce:false,corrupted:0,dropped:0}
  prototype.fetch=async function(input,options){const url=typeof input==='string'?input:input?.url,r=await original.call(this,input,options),state=globalThis.__rawFault
    if(state.corruptOnce&&/\/performance\/artifacts\/[^/]+\/download$/.test(url)){state.corruptOnce=false;state.corrupted++;await r.arrayBuffer();return {ok:true,status:200,body:(async function*(){yield globalThis.__testhubQaRequire('node:buffer').Buffer.from('owned corrupt wire bytes')})()}}
    if(state.dropPostOnce&&options?.method==='POST'&&url.endsWith('/performance/artifacts/sync')){state.dropPostOnce=false;state.dropped++;await r.arrayBuffer();throw new Error('Owned QA response loss after commit')}
    return r
  }
})}
try {
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+target.address().port
  await writeExecutionOrigins(directory,[origin]);await fs.mkdir(path.join(directory,'engines'))
  await fs.cp(await fs.realpath(argument('--jmeter-cache')),path.join(directory,'engines/apache-jmeter-5.6.3'),{recursive:true})
  const home=path.join(directory,'engines/apache-jmeter-5.6.3/apache-jmeter-5.6.3');await fs.access(path.join(home,'bin/ApacheJMeter.jar'))
  app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,diagnosticLog:path.join(path.dirname(output),'flow-native-private.log')});await configureDialogs();await installFaults();let local=app.local
  const localId=await project(local),baseLocal=`/api/v1/projects/${localId}/performance`
  const planInput={name:'原始工件同名来源',description:'Owned loopback QA',status:'READY',threadGroups:[{name:'原始样本组',targetUrl:origin+'/private-target',loadModel:'STEADY',threadCount:1,rampUpSeconds:0,durationSeconds:10,targetTps:3,requestMethod:'GET',requestHeaders:{'X-Fixture':'raw-private-header'},expectedStatusCode:200,responseContains:'"ok":true',errorThresholdPercent:0}]}
  const localPlan=await send(local,baseLocal+'/plans',planInput);await local.evaluate(origin=>globalThis.testhubDesktop.ensureJmeter([origin]),origin)
  const localExecution=await send(local,baseLocal+`/plans/${localPlan.id}/executions`,{timeoutSeconds:120});assert.equal((await terminal(local,baseLocal,localExecution.id)).status,'SUCCEEDED');await fileReport(local,baseLocal,localExecution)
  accountServer=await performanceAccount({bundle,directory:accountDirectory,origin});const server=accountServer.server
  await local.evaluate(()=>globalThis.testhubDesktop.connectAccount());const setup=await app.pageFor('testhub-desktop://setup/setup.html');await setup.locator('#server-url').fill(server);await setup.locator('#connect').click()
  const remote=await app.pageFor(server),account='rawartifacts'+Date.now(),password=crypto.randomBytes(20).toString('base64url')
  await send(remote,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离原始工件验收',password})
  const remoteId=await project(remote),baseRemote=`/api/v1/projects/${remoteId}/performance`
  browser=await chromium.launch({headless:true,executablePath:await fs.realpath(argument('--chromium'))});const web=await browser.newPage();await web.goto(server+'/login');await send(web,'/api/v1/auth/login',{account,password})
  await accountServer.startJmeter(home)
  const remotePlan=await send(web,baseRemote+'/plans',planInput),remoteExecution=await send(web,baseRemote+`/plans/${remotePlan.id}/executions`,{timeoutSeconds:120});assert.equal((await terminal(web,baseRemote,remoteExecution.id)).status,'SUCCEEDED');await fileReport(web,baseRemote,remoteExecution)
  const localOriginal=(await snapshot(await localPage(),baseLocal)).entries,remoteOriginal=(await snapshot(web,baseRemote)).entries
  assert.equal(localOriginal.length,2);assert.equal(remoteOriginal.length,2);assert.equal(hits,60)
  pass('independent local and account backends each execute real JMeter and archive native JTL/log pairs')
  let page=await raw();assert.equal(await page.locator('#raw-files').isChecked(),false)
  await assert.rejects(page.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewPerformanceArtifactSync({sourceId,targetId}),{sourceId:localId,targetId:remoteId}),/单独勾选/)
  const blocked=await page.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewPerformanceArtifactSync({sourceId,targetId,rawFilesConfirmed:true}),{sourceId:localId,targetId:remoteId});assert.equal(blocked.upload,0);assert.equal(blocked.download,0);assert.equal(blocked.conflictCount,4)
  await bindHistory(localId,remoteId);local=await localPage();assert.equal((await snapshot(web,baseRemote)).entries.filter(e=>!e.nativeRecord).length,0)
  pass('raw sync is separately opted in and missing counterpart histories block all four files; report binding alone transfers no raw bytes')
  console.log('QA opening raw binding');page=await raw();await page.locator('#source').selectOption(localId);await page.locator('#target').selectOption(remoteId);await page.locator('#raw-files').check();await page.locator('#prepare').click();await page.locator('#bind:not([disabled])').waitFor();assert.match(await page.locator('#preview').textContent(),/首次上传 2 个原始工件、下载 2/)
  const dashboardBefore=await read(web,`/api/v1/projects/${remoteId}/dashboard?days=7`)
  console.log('QA arming controlled wire corruption');await app.evaluate(()=>{globalThis.__rawFault.corruptOnce=true});await page.locator('#bind').click();await page.locator('#run:not([disabled])').waitFor()
  console.log('QA reading first raw receipt');let status=await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus());assert.equal(status.conflicts.length,1);assert.equal((await snapshot(web,baseRemote)).entries.length,3)
  await page.evaluate(()=>globalThis.testhubDesktop.runPerformanceArtifactSync());assert.equal((await snapshot(web,baseRemote)).entries.length,4);assert.equal(hits,60)
  assert.equal((await app.evaluate(()=>globalThis.__rawFault)).corrupted,1)
  for(const entry of [...localOriginal,...remoteOriginal]){await bytes(web,baseRemote,entry);await bytes(local,baseLocal,entry)}
  const dashboardAfter=await read(web,`/api/v1/projects/${remoteId}/dashboard?days=7`);for(const k of ['summary','trend','distribution'])assert.deepEqual(dashboardBefore[k],dashboardAfter[k])
  assert.equal((await read(local,baseLocal+'/executions')).length,1);assert.equal((await read(web,baseRemote+'/executions')).length,1)
  pass('a corrupted wire file pauses independently; other files continue, then retry yields byte-identical bidirectional originals without tasks or statistical duplication')
  await web.goto(server+'/performance');await web.locator('section[aria-label="性能同步历史"]').getByRole('button',{name:'查看历史',exact:true}).click()
  const originalSection=web.locator('section[aria-label="原始 JTL／日志"]');await originalSection.getByRole('link',{name:'下载原始 JTL',exact:true}).waitFor()
  const link=originalSection.getByRole('link',{name:'下载原始 JTL',exact:true}),expected=localOriginal.find(e=>e.artifactType==='JMETER_JTL')
  const [download]=await Promise.all([web.waitForEvent('download'),link.click()]);assert.equal(download.suggestedFilename(),'results.jtl');assert.equal(sha(await fs.readFile(await download.path())),expected.checksum);await download.delete()
  await web.locator('.arco-drawer').filter({hasText:'只读执行历史'}).locator('.arco-drawer-close-btn').click()
  pass('headless web history displays original file size and digest and its actual attachment link downloads the unchanged JTL')
  const nativeLargePending=await app.evaluate(async({safeStorage},{directory,state})=>{
    const require=globalThis.__testhubQaRequire,fs=require('node:fs/promises'),path=require('node:path'),crypto=require('node:crypto'),{Buffer}=require('node:buffer')
    const {PerformanceArtifactSyncStore}=require(path.join(process.resourcesPath,'app.asar/src/performance-artifact-sync.cjs'))
    const owned=path.join(directory,'large-pending-qa');await fs.mkdir(owned,{recursive:false})
    try{
      const data=Buffer.alloc(20*1024*1024,120),entry={key:state.binding.source.id+'.'+'Z'.repeat(26)+'.JMETER_LOG',sourceArtifactId:'Y'.repeat(26),artifactType:'JMETER_LOG',originalName:'jmeter.log',contentType:'text/plain',sizeBytes:data.length,checksum:crypto.createHash('sha256').update(data).digest('hex'),archivedAt:new Date().toISOString(),nativeRecord:true}
      const value={...state,pending:{side:'remote',entry,request:{requestKey:crypto.randomUUID(),expectedUserId:state.binding.actor.id,expectedRevision:'a'.repeat(64),rawFilesConfirmed:true,artifact:{...entry,nativeRecord:false},content:data.toString('base64')}}}
      delete value.error
      const store=new PerformanceArtifactSyncStore(owned,{encrypt:v=>safeStorage.encryptStringAsync(v),decrypt:async v=>(await safeStorage.decryptStringAsync(v)).result})
      await store.write(value);const restored=await store.read();if(restored.pending.request.content!==value.pending.request.content)throw new Error('Native large pending bytes changed')
      const file=await fs.readFile(store.file);if(file.includes(Buffer.from(value.pending.request.content.slice(0,128))))throw new Error('Raw bytes leaked into unencrypted state')
      return {passed:true,bytes:data.length,privateFile:((await fs.stat(store.file)).mode&0o777)===0o600}
    }finally{await fs.rm(owned,{recursive:true,force:true})}
  },{directory,state:await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus())})
  assert.equal(nativeLargePending.passed,true);assert.equal(nativeLargePending.privateFile,true)
  pass('native safeStorage encrypts and restores a full 20 MiB pending file without exposing bytes in UI status or leaving its probe data')

  page=await raw();await page.evaluate(()=>globalThis.testhubDesktop.enablePerformanceArtifactSync(false));
  const second=await send(local,baseLocal+`/plans/${localPlan.id}/executions`,{timeoutSeconds:120});assert.equal((await terminal(local,baseLocal,second.id)).status,'SUCCEEDED');await fileReport(local,baseLocal,second)
  await invokeHistory();page=await raw();await page.evaluate(()=>globalThis.testhubDesktop.runPerformanceArtifactSync());assert.equal((await snapshot(web,baseRemote)).entries.length,4)
  await app.evaluate(()=>{globalThis.__rawFault.dropPostOnce=true});await page.evaluate(()=>globalThis.testhubDesktop.enablePerformanceArtifactSync(true));await assert.rejects(page.evaluate(()=>globalThis.testhubDesktop.runPerformanceArtifactSync()),/同步服务暂时不可达/)
  status=await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus());assert.ok(status.pending);assert.equal(status.pending.request,undefined);assert.equal((await snapshot(web,baseRemote)).entries.length,5)
  const pendingKey=status.pending.key;assert.equal((await app.evaluate(()=>globalThis.__rawFault)).dropped,1);await attest();await app.close();app=undefined
  app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,diagnosticLog:path.join(path.dirname(output),'flow-native-private.log')});await configureDialogs();local=app.local;page=await raw();status=await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus());assert.equal(status.pending.key,pendingKey)
  await assert.rejects(page.evaluate(()=>globalThis.testhubDesktop.runPerformanceArtifactSync()),/同步会话已失效/)
  assert.equal((await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus())).pending.key,pendingKey)
  await local.evaluate(()=>globalThis.testhubDesktop.connectAccount());const loginSetup=await app.pageFor('testhub-desktop://setup/setup.html');await loginSetup.locator('#server-url').fill(server);await loginSetup.locator('#connect').click()
  const loggedIn=await app.pageFor(server);await send(loggedIn,'/api/v1/auth/login',{account,password});local=await localPage();page=await raw()
  await page.evaluate(()=>globalThis.testhubDesktop.runPerformanceArtifactSync());assert.equal((await snapshot(web,baseRemote)).entries.length,6);assert.equal((await snapshot(local,baseLocal)).entries.length,6);assert.equal(hits,90)
  pass('pause blocks new files; lost committed response remains encrypted across restart, rejects expired login, then retries the durable request after same-account login without duplicate artifacts or tasks')
  await page.evaluate(()=>globalThis.testhubDesktop.unbindPerformanceArtifactSync());const preview=await page.evaluate(({sourceId,targetId})=>globalThis.testhubDesktop.previewPerformanceArtifactSync({sourceId,targetId,rawFilesConfirmed:true}),{sourceId:localId,targetId:remoteId});assert.equal(preview.upload,0);assert.equal(preview.download,0);await page.evaluate(token=>globalThis.testhubDesktop.bindPerformanceArtifactSync(token),preview.token)
  const encrypted=await fs.readFile(path.join(directory,'performance-artifact-sync.bin'));assert.ok(!encrypted.includes(Buffer.from(localOriginal[0].key)));assert.equal((await fs.stat(path.join(directory,'performance-artifact-sync.bin'))).mode&0o777,0o600)
  pass('unbind and rebind preserve six immutable origins and state remains encrypted with private permissions')
  const backupPage=await native('本地备份与恢复','backup'),backupPassword=crypto.randomBytes(20).toString('base64url')
  await backupPage.evaluate(password=>globalThis.testhubDesktop.runBackup({action:'export',password}),backupPassword);await backupPage.evaluate(password=>globalThis.testhubDesktop.runBackup({action:'restore',password}),backupPassword)
  local=await localPage();for(const entry of remoteOriginal)await bytes(local,baseLocal,entry)
  page=await raw();status=await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus());assert.equal(status.binding.enabled,false);assert.equal(status.binding.requiresRebind,true);await assert.rejects(page.evaluate(()=>globalThis.testhubDesktop.enablePerformanceArtifactSync(true)),/恢复过备份/)
  await attest();await app.close();app=undefined;app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,diagnosticLog:path.join(path.dirname(output),'flow-native-private.log')});await configureDialogs();local=app.local;page=await raw();status=await page.evaluate(()=>globalThis.testhubDesktop.performanceArtifactSyncStatus());assert.equal(status.binding.requiresRebind,true);assert.equal(status.binding.enabled,false);for(const entry of remoteOriginal)await bytes(local,baseLocal,entry);await attest()
  pass('real backup restore retains imported raw bytes and persists mandatory raw-binding suspension across another packaged restart')
  for(const entry of (await snapshot(web,baseRemote)).entries)summaries.push(await bytes(web,baseRemote,entry))
  assert.equal(sha(await fs.readFile(asar)),originalAsarHash)
  outcome={passed:true,checks,background:windows,files:summaries,nativeLargePending,realLocalJmeterExecutions:2,realAccountJmeterExecutions:1,targetRequests:hits,asarUnchanged:true,schemaVersion:44}
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
console.log('PASS raw artifact module '+checks.length+' checks')
