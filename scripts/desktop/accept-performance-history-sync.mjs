// Native packaged acceptance. Every process, account, request target and workspace belongs to this run.
import { Buffer } from 'node:buffer'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import http from 'node:http'
import { createRequire } from 'node:module'
import { spawn } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
import { offscreenMac } from './offscreen-mac-qa.mjs'
const require = createRequire(import.meta.url)
const { loadBundle, childEnvironment } = require('../../apps/desktop/app/src/local-runtime.cjs')
const { writeExecutionOrigins } = require('../../apps/desktop/app/src/local-execution.cjs')
const args = process.argv.slice(2), argument = key => args[args.indexOf(key) + 1]
for (const key of ['--app','--output','--jmeter-cache','--chromium']) if (!args.includes(key)) throw new Error('Required argument: ' + key)
const appPath = await fs.realpath(argument('--app')), output = path.resolve(argument('--output'))
await fs.mkdir(path.dirname(output), {recursive:true})
const reservation = await fs.open(output,'wx',0o600); await reservation.close()
const owned = await fs.mkdtemp(path.resolve('.testhub/performance-history-qa-'))
const directory = path.join(owned,'desktop'), accountDirectory = path.join(owned,'account'), backup = path.join(owned,'isolated.testhub-backup')
await fs.mkdir(directory); await fs.mkdir(accountDirectory)
const name = 'TestHub-QA-' + crypto.randomUUID()
await fs.writeFile(path.join(owned,'.qa-owner.json'),JSON.stringify({name}),{mode:0o600,flag:'wx'})
const bundle = await loadBundle(path.join(appPath,'Contents/Resources/local-runtime/darwin-arm64'))
const asar = path.join(appPath,'Contents/Resources/app.asar'), sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const originalAsarHash = sha(await fs.readFile(asar))
const checks = [], windows = [], summaries = [], pass = message => { checks.push(message); console.log('PASS ' + message) }
let app, browser, child, childDone, log, outcome, failure, hits = 0
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
  await app.evaluate(({Menu})=>Menu.getApplicationMenu().items[0].submenu.items.find(v=>v.label==='本地工作区').click())
  return until(async()=>{for(const page of app.pages().filter(p=>p.url().startsWith('http://127.0.0.1:'))){try{if(await page.getByText('本地工作区',{exact:true}).first().count())return page}catch{/* Switching closes the previous renderer. */}}return false})
}
async function native(label,filename) {
  await app.evaluate(({Menu},label)=>Menu.getApplicationMenu().items[0].submenu.items.find(v=>v.label===label).click(),label)
  const page=await app.pageFor('testhub-desktop://setup/'+filename+'.html');await page.locator(filename==='backup'?'#export':'#refresh:not([disabled])').waitFor();return page
}
async function attest() { windows.push(await app.snapshot()) }
async function configureDialogs() {
  await app.evaluate(({dialog},backup)=>{
    const state=globalThis.__testhubBackground
    const unexpected=()=>{state.unexpectedDialogs++;throw new Error('Unexpected QA confirmation')}
    dialog.showMessageBox=async(_window,options)=>{
      if(!['启用性能执行历史同步','解除性能执行历史同步绑定','恢复本地工作区'].includes(options.title)||options.defaultId!==0)return unexpected()
      state.confirmations.push({title:options.title,defaultId:options.defaultId});return {response:1}
    }
    dialog.showSaveDialog=async(_window,options)=>{if(options.title!=='保存加密工作区备份')return unexpected();return {canceled:false,filePath:backup}}
    dialog.showOpenDialog=async(_window,options)=>{if(options.title!=='选择要恢复的工作区备份')return unexpected();return {canceled:false,filePaths:[backup]}}
  },backup)
}
try {
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve))
  const origin='http://127.0.0.1:'+target.address().port
  await writeExecutionOrigins(directory,[origin])
  await fs.mkdir(path.join(directory,'engines'))
  await fs.cp(await fs.realpath(argument('--jmeter-cache')),path.join(directory,'engines/apache-jmeter-5.6.3'),{recursive:true})
  app=await offscreenMac(appPath,directory,{name,cleanKeychain:false});await configureDialogs()
  let local=app.local
  const localId=await project(local), baseLocal=`/api/v1/projects/${localId}/performance`
  const planInput={name:'同名来源压测',description:'Owned loopback QA',status:'READY',threadGroups:[{name:'数值组',targetUrl:origin+'/private-target',loadModel:'STEADY',threadCount:1,rampUpSeconds:0,durationSeconds:10,targetTps:3,errorThresholdPercent:0,p95ThresholdMillis:5000,requestMethod:'GET',requestHeaders:{'X-Private':'withheld-header'},expectedStatusCode:200,responseContains:'"ok":true'}]}
  const plan=await send(local,baseLocal+'/plans',planInput)
  await local.evaluate(origin=>globalThis.testhubDesktop.ensureJmeter([origin]),origin)
  await local.goto(new URL('/performance',local.url()).href)
  const [queuedResponse]=await Promise.all([local.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/executions')),local.getByRole('button',{name:'提交压测',exact:true}).first().click()])
  const execution=(await queuedResponse.json()).data
  assert.equal((await terminal(local,baseLocal,execution.id)).status,'SUCCEEDED')
  const localKey=localId+'.'+execution.id
  // Runner messages can arrive after the terminal event. Wait for the existing report to contain its final measurements.
  await until(async()=>{const r=await read(local,baseLocal+'/executions/'+execution.id+'/report');return r.summary.sampleCount>0&&r.resources.length>0&&r.artifacts.length===2&&r})
  const original=await read(local,baseLocal+'/history/'+localKey)
  assert.ok(original.report.summary.sampleCount>0);assert.equal(original.report.summary.errorCount,0);assert.equal(original.report.quality.status,'PASSED')
  assert.equal(sha(Buffer.from(JSON.stringify(original.report))),original.sha256)
  for(const hidden of [origin,'private-target','withheld-header','withheld-response','manifestRef','requestedBy','runnerId','requestBody','dataSetCsv'])assert.ok(!JSON.stringify(original).includes(hidden))
  const full=await read(local,baseLocal+'/executions/'+execution.id+'/report')
  for(const artifact of full.artifacts){const bytes=Buffer.from(await local.evaluate(async route=>Array.from(new Uint8Array(await(await globalThis.fetch(route)).arrayBuffer())),baseLocal+`/executions/${execution.id}/artifacts/${artifact.id}/download`));assert.equal(sha(bytes),artifact.checksum);assert.equal(bytes.length,artifact.sizeBytes)}
  summaries.push({status:original.report.status,planVersion:original.report.planVersion,samples:original.report.summary.sampleCount,metrics:original.report.metrics.length,resources:original.report.resources.length,quality:original.report.quality.status,originalArtifacts:full.artifacts.length})
  pass('packaged UI executes real JMeter; numeric history has canonical digest, quality and resources while original JTL/log downloads remain intact')
  log=await fs.open(path.join(accountDirectory,'server-private.log'),'w',0o600)
  const ready=path.join(accountDirectory,'ready.json')
  child=spawn(bundle.java,['-Xmx768m','-jar',bundle.backend,'--spring.profiles.active=desktop','--server.port=0'],{stdio:['pipe',log.fd,log.fd],env:{...childEnvironment(),TESTHUB_DESKTOP_DATA_DIRECTORY:accountDirectory,TESTHUB_LOCAL_DB_PASSWORD:crypto.randomBytes(32).toString('hex'),TESTHUB_ENCRYPTION_KEY:crypto.randomBytes(32).toString('base64'),TESTHUB_REGISTRATION_ENABLED:'true',TESTHUB_DESKTOP_READY_FILE:ready,TESTHUB_DESKTOP_FRONTEND_DIRECTORY:bundle.frontend,TESTHUB_DESKTOP_INSTANCE:crypto.randomUUID(),TESTHUB_DESKTOP_SESSION_KEY:crypto.randomBytes(32).toString('hex')}})
  childDone=new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',resolve)})
  const server=await until(async()=>{if(child.exitCode!==null)throw new Error('Owned account backend exited');try{return 'http://127.0.0.1:'+JSON.parse(await fs.readFile(ready,'utf8')).port}catch{return false}})
  await local.evaluate(()=>globalThis.testhubDesktop.connectAccount())
  const setup=await app.pageFor('testhub-desktop://setup/setup.html');await setup.locator('#server-url').fill(server);await setup.locator('#connect').click()
  const remote=await app.pageFor(server), account='perfhistory'+Date.now(),password=crypto.randomBytes(18).toString('base64url')
  await send(remote,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离性能历史验收',password})
  const remoteId=await project(remote),baseRemote=`/api/v1/projects/${remoteId}/performance`
  browser=await chromium.launch({headless:true,executablePath:await fs.realpath(argument('--chromium'))})
  const web=await browser.newPage();await web.goto(server+'/login');await send(web,'/api/v1/auth/login',{account,password})
  const remotePlan=await send(web,baseRemote+'/plans',planInput)
  const remoteQueued=await send(web,baseRemote+`/plans/${remotePlan.id}/executions`,{timeoutSeconds:60})
  await send(web,baseRemote+'/executions/'+remoteQueued.id,undefined,'DELETE')
  assert.equal((await terminal(web,baseRemote,remoteQueued.id)).status,'CANCELLED')
  const remoteKey=remoteId+'.'+remoteQueued.id,remoteOriginal=await read(web,baseRemote+'/history/'+remoteKey)
  const hitsBefore=hits, dashboardBefore=await read(web,`/api/v1/projects/${remoteId}/dashboard?days=7`)
  pass('independent account workspace creates a genuine cancelled execution without running another request target')
  let sync=await native('性能执行历史同步','performance-history-sync')
  await sync.locator('#source').selectOption(localId);await sync.locator('#target').selectOption(remoteId)
  await sync.locator('#prepare').click();await sync.locator('#bind:not([disabled])').waitFor()
  assert.match(await sync.locator('#preview').textContent(),/首次上传 1 条历史报告、下载 1 条/)
  await sync.locator('#bind').click();await sync.locator('#run:not([disabled])').waitFor();assert.equal(await sync.locator('#error').isVisible(),false)
  local=await localPage();await local.goto(new URL('/performance',local.url()).href)
  await local.getByRole('button',{name:'历史同步',exact:true}).click()
  sync=await app.pageFor('testhub-desktop://setup/performance-history-sync.html');await sync.locator('#run:not([disabled])').waitFor()
  const invoke=(method,value)=>sync.evaluate(({method,value})=>globalThis.testhubDesktop[method](value),{method,value})
  await invoke('runPerformanceHistorySync')
  assert.deepEqual(await read(web,baseRemote+'/history/'+localKey),original);assert.deepEqual(await read(local,baseLocal+'/history/'+remoteKey),remoteOriginal)
  assert.equal((await read(web,baseRemote+'/executions')).length,1);assert.equal((await read(local,baseLocal+'/executions')).length,1);assert.equal(hits,hitsBefore)
  const dashboardAfter=await read(web,`/api/v1/projects/${remoteId}/dashboard?days=7`)
  for(const key of ['summary','trend','distribution'])assert.deepEqual(dashboardAfter[key],dashboardBefore[key])
  pass('same-named plans synchronize two distinct terminal origins bidirectionally without queuing, cancelling or repeating executions')
  await web.goto(server+'/performance')
  const history=web.locator('section[aria-label="性能同步历史"]')
  await history.getByRole('button',{name:'查看历史',exact:true}).click();await web.getByText('报告内容 SHA-256',{exact:true}).waitFor()
  const drawer=web.locator('.arco-drawer').filter({hasText:'只读执行历史'})
  assert.ok((await drawer.textContent()).includes('v1'));assert.ok((await drawer.textContent()).includes('PASSED'))
  const link=web.getByRole('link',{name:'下载脱敏历史报告',exact:true})
  assert.equal(await link.getAttribute('href'),baseRemote+'/history/'+localKey+'/export')
  const exported=await web.evaluate(async route=>{const r=await globalThis.fetch(route),bytes=await r.arrayBuffer(),hash=await globalThis.crypto.subtle.digest('SHA-256',bytes);return {status:r.status,declared:r.headers.get('X-TestHub-Report-SHA256'),actual:[...new Uint8Array(hash)].map(n=>n.toString(16).padStart(2,'0')).join(''),report:JSON.parse(new globalThis.TextDecoder().decode(bytes))}},baseRemote+'/history/'+localKey+'/export')
  assert.equal(exported.status,200);assert.equal(exported.actual,exported.declared);assert.deepEqual(exported.report,original)
  await drawer.locator('.arco-drawer-close-btn').click()
  const unified=await read(web,`/api/v1/projects/${remoteId}/tasks/executions`)
  assert.equal(unified.filter(v=>v.module==='PERFORMANCE_HISTORY').length,1);assert.equal(unified.find(v=>v.module==='PERFORMANCE_HISTORY').id,localKey)
  pass('ordinary headless web browser renders read-only charts and origin version, verifies export SHA-256 and lists imported history separately')
  const changed={...planInput,name:'当前计划第二版'}
  await send(local,baseLocal+'/plans/'+plan.id,{expectedVersion:plan.version,plan:changed},'PUT')
  await invoke('runPerformanceHistorySync');assert.deepEqual(await read(web,baseRemote+'/history/'+localKey),original)
  await invoke('enablePerformanceHistorySync',false)
  const second=await send(local,baseLocal+`/plans/${plan.id}/executions`,{timeoutSeconds:120});assert.equal((await terminal(local,baseLocal,second.id)).status,'SUCCEEDED')
  await invoke('runPerformanceHistorySync');assert.ok(!(await read(web,baseRemote+'/history')).entries.some(v=>v.key===localId+'.'+second.id))
  await invoke('enablePerformanceHistorySync',true);await invoke('runPerformanceHistorySync')
  const copied=await read(web,baseRemote+'/history/'+localId+'.'+second.id);assert.equal(copied.report.planVersion,2);assert.equal(copied.report.planName,changed.name)
  assert.equal((await read(web,baseRemote+'/executions')).length,1)
  pass('plan edits preserve prior report facts; pause blocks new reports and resume copies the next real execution with its new version')
  await invoke('unbindPerformanceHistorySync');const preview=await invoke('previewPerformanceHistorySync',{sourceId:localId,targetId:remoteId})
  assert.equal(preview.upload,0);assert.equal(preview.download,0);await invoke('bindPerformanceHistorySync',preview.token)
  assert.equal((await read(web,baseRemote+'/history')).entries.length,3)
  const encrypted=await fs.readFile(path.join(directory,'performance-history-sync.bin'))
  assert.ok(!encrypted.includes(Buffer.from(localKey)));assert.equal((await fs.stat(path.join(directory,'performance-history-sync.bin'))).mode&0o777,0o600)
  pass('unbind and rebind retain the same three origins and durable synchronization state is encrypted with private permissions')
  const backupPage=await native('本地备份与恢复','backup')
  const backupPassword=crypto.randomBytes(20).toString('base64url')
  await backupPage.evaluate(password=>globalThis.testhubDesktop.runBackup({action:'export',password}),backupPassword)
  await backupPage.evaluate(password=>globalThis.testhubDesktop.runBackup({action:'restore',password}),backupPassword)
  local=await localPage();assert.equal((await read(local,baseLocal+'/history')).entries.length,3)
  sync=await native('性能执行历史同步','performance-history-sync')
  const suspended=await invoke('performanceHistorySyncStatus');assert.equal(suspended.binding.enabled,false);assert.equal(suspended.binding.requiresRebind,true)
  await assert.rejects(invoke('enablePerformanceHistorySync',true),/恢复过备份/)
  await attest();await app.close();app=undefined
  app=await offscreenMac(appPath,directory,{name});await configureDialogs();local=app.local
  sync=await native('性能执行历史同步','performance-history-sync')
  const persisted=await invoke('performanceHistorySyncStatus');assert.equal(persisted.binding.requiresRebind,true);assert.equal(persisted.binding.enabled,false)
  assert.equal((await read(local,baseLocal+'/history')).entries.length,3)
  await attest()
  pass('real encrypted backup restore preserves reports, suspends binding and persists mandatory rebind across packaged restart')
  assert.equal(sha(await fs.readFile(asar)),originalAsarHash)
  outcome={passed:true,checks,background:windows,reports:summaries,realLocalExecutions:2,remoteCancelledExecutions:1,targetRequests:hits,asarUnchanged:true,schemaVersion:43}
} catch(error) {failure=error;outcome={passed:false,checks,error:error.message}}
finally {
  let cleanupError
  try{await browser?.close();await app?.close()}catch(error){cleanupError=error}
  if(child){child.stdin.end();child.kill('SIGTERM');await childDone;await log.close()}
  target.closeAllConnections();await new Promise(resolve=>target.close(resolve))
  await fs.rm(owned,{recursive:true,force:true})
  // If startup or the first launch failed, remove only the uniquely named QA key.
  const {execFile}=await import('node:child_process');const {promisify}=await import('node:util')
  try{await promisify(execFile)('/usr/bin/security',['delete-generic-password','-s',name+' Safe Storage','-a',name+' Key'])}catch(error){if(error.code!==44)cleanupError||=error}
  outcome.ownedDataAndProcessesRemoved=!cleanupError
  if(cleanupError){outcome.passed=false;failure||=cleanupError}
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
console.log('PASS performance history module '+checks.length+' checks')
