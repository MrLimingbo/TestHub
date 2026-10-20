// V45 native mobile protocol-fixture histories and PNGs must survive the V46 migration.
// No Appium, emulator or real device is started.
import {Buffer} from 'node:buffer'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import http from 'node:http'
import {createRequire} from 'node:module'
import {setTimeout as delay} from 'node:timers/promises'
import {chromium} from '../../services/playwright-runner/node_modules/playwright/index.mjs'
import {performanceAccount} from './qa-performance-account.mjs'
const require=createRequire(import.meta.url),{loadBundle}=require('../../apps/desktop/app/src/local-runtime.cjs'),{databaseTool}=require('../../apps/desktop/app/src/workspace-restore.cjs'),{LocalBrowsers}=require('../../apps/desktop/app/src/local-browsers.cjs')
const args=process.argv.slice(2),argument=key=>args[args.indexOf(key)+1]
for(const key of ['--app','--baseline-backend','--chromium','--browser-cache','--output'])if(!args.includes(key))throw new Error('Required argument: '+key)
const output=path.resolve(argument('--output'));await fs.mkdir(path.dirname(output),{recursive:true});await(await fs.open(output,'wx',0o600)).close()
const owned=await fs.mkdtemp(path.resolve('.testhub/mobile-history-upgrade-')),seed=path.join(owned,'v45'),candidate=path.join(owned,'candidate'),engines=path.join(owned,'engines')
const bundle=await loadBundle(path.join(await fs.realpath(argument('--app')),'Contents/Resources/local-runtime/darwin-arm64'))
const databasePassword=crypto.randomBytes(32).toString('hex'),encryptionKey=crypto.randomBytes(32).toString('base64'),secrets={version:1,databasePassword,encryptionKey}
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex')
let browser,backend,outcome,failure,hits=0
const target=http.createServer((_req,res)=>{hits++;res.end('Owned target; no device requests expected')})
async function read(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route),v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},route)}
async function send(page,route,body,method='POST'){return page.evaluate(async({route,body,method})=>{const csrf=(await(await globalThis.fetch('/api/v1/auth/csrf')).json()).data,r=await globalThis.fetch(route,{method,headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(body)});if(r.status===204)return;const v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},{route,body,method})}
async function fileHash(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('Upgrade file unavailable');const bytes=await r.arrayBuffer(),digest=await globalThis.crypto.subtle.digest('SHA-256',bytes);return{bytes:bytes.byteLength,sha256:[...new Uint8Array(digest)].map(n=>n.toString(16).padStart(2,'0')).join('')}},route)}
async function until(probe){for(let n=0;n<800;n++){const value=await probe();if(value)return value;await delay(150)}throw new Error('Upgrade protocol fixture timed out')}
try {
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+target.address().port
  const manager=new LocalBrowsers({directory:engines,bundle}),plan=await manager.plan('chromium'),cache=plan.cache;await fs.mkdir(cache,{recursive:true})
  const sourceCache=await fs.realpath(argument('--browser-cache'));for(const entry of [...plan.components.map(c=>c.directory),'.ready-chromium.json'])await fs.cp(path.join(sourceCache,entry),path.join(cache,entry),{recursive:true,verbatimSymlinks:true});assert.equal(await manager.installed('chromium'),cache)
  const relative=path.relative(sourceCache,await fs.realpath(argument('--chromium')));assert.ok(!relative.startsWith('..'))
  browser=await chromium.launch({headless:true,executablePath:path.join(cache,relative)});const page=await browser.newPage()
  backend=await performanceAccount({bundle,directory:seed,origin,backend:await fs.realpath(argument('--baseline-backend')),databasePassword,encryptionKey})
  await page.goto(backend.server+'/login');const account='mobileupgrade'+Date.now(),password=crypto.randomBytes(20).toString('base64url')
  await send(page,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离 V45 移动端升级',password})
  const actor=await read(page,'/api/v1/auth/me'),context=await read(page,'/api/v1/access/context'),project=context.projects[context.organizations[0].id][0].id,base='/api/v1/projects/'+project,mobile=base+'/mobile-automation'
  const fixture=backend.mobileFixture()
  const device=await send(page,mobile+'/devices',{name:'旧版设备',platform:'ANDROID',udid:'owned-upgrade-udid',automationName:'UiAutomator2',capabilities:{},enabled:true})
  const application=await send(page,mobile+'/applications',{name:'旧版应用',platform:'ANDROID',appIdentifier:'owned.upgrade.app',versionName:'v45',appFileId:null,enabled:true})
  const scene=await send(page,mobile+'/scenarios',{deviceId:device.id,applicationId:application.id,name:'V45 原始场景',description:'保留原报告与截图',failureStrategy:'STOP',variables:{},steps:[{id:'launch',type:'LAUNCH',name:'启动应用',timeoutMillis:30000},{id:'shot',type:'SCREENSHOT',name:'截图',timeoutMillis:30000}]})
  const execution=await send(page,mobile+'/scenarios/'+scene.id+'/executions',{timeoutSeconds:120}),ids={taskId:execution.taskId,executionId:execution.id}
  await fixture.claim(ids);for(const [n,step] of scene.steps.entries())await fixture.step({...ids,stepId:step.id,stepSequence:n+1,stepType:step.type,stepName:step.name})
  const pngs=["iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg=="]
  for(const content of pngs)await fixture.screenshot({...ids,content});await fixture.finish(ids)
  const report=await until(async()=>{const r=await read(page,mobile+'/executions/'+execution.id+'/report');return r.execution.status==='SUCCEEDED'&&r.steps.length===2&&r})
  const nativeArtifacts=await read(page,mobile+'/executions/'+execution.id+'/artifacts');assert.equal(nativeArtifacts.length,2)
  const originalArtifacts=Object.fromEntries(await Promise.all(nativeArtifacts.map(async a=>[a.id,{type:a.artifactType,...await fileHash(page,mobile+`/executions/${execution.id}/artifacts/${a.id}/download`)}])))
  await page.goto(backend.server+'/project-settings/files');await page.locator('input[type=file]').setInputFiles({name:'升级保留.txt',mimeType:'text/plain',buffer:Buffer.from('V45 unchanged file bytes')});await page.getByText('升级保留.txt',{exact:true}).first().waitFor()
  const file=(await read(page,base+'/files')).find(v=>v.originalName==='升级保留.txt'),fileRoute=base+'/files/'+file.id+'/download',fileBytes=await fileHash(page,fileRoute)
  const routes=[mobile,mobile+'/executions/'+execution.id+'/report',mobile+'/executions/'+execution.id+'/artifacts',base+'/files']
  const before=Object.fromEntries(await Promise.all(routes.map(async route=>[route,await read(page,route)])))
  await backend.stop();backend=undefined;assert.equal((await databaseTool(bundle,seed,secrets)).databaseVersion,'45');const originalDatabase=hash(await fs.readFile(path.join(seed,'workspace.mv.db')))
  await fs.cp(seed,candidate,{recursive:true,verbatimSymlinks:true});backend=await performanceAccount({bundle,directory:candidate,origin,databasePassword,encryptionKey})
  await page.goto(backend.server+'/login');await send(page,'/api/v1/auth/login',{account,password});assert.equal((await read(page,'/api/v1/auth/me')).id,actor.id)
  for(const route of routes)assert.deepEqual(await read(page,route),before[route]);assert.deepEqual(await fileHash(page,fileRoute),fileBytes)
  const history=await read(page,mobile+'/history/'+project+'.'+execution.id);assert.equal(history.report.summary.totalSteps,2);assert.equal(history.report.scenarioVersion,1)
  const artifacts=await read(page,mobile+'/history/'+project+'.'+execution.id+'/artifacts');assert.equal(artifacts.length,2)
  for(const entry of artifacts){const actual=await fileHash(page,mobile+'/artifacts/'+entry.key+'/download');assert.deepEqual({type:entry.artifactType,...actual},originalArtifacts[entry.sourceArtifactId]);assert.equal(actual.sha256,entry.checksum);assert.equal(entry.nativeRecord,true)}
  assert.equal(hash(await fs.readFile(path.join(seed,'workspace.mv.db'))),originalDatabase);assert.equal(hits,0)
  await backend.stop();backend=undefined;assert.equal((await databaseTool(bundle,candidate,secrets)).databaseVersion,'46')
  outcome={passed:true,from:45,to:46,headless:true,targetRequests:hits,realMobileDeviceExecutions:0,appiumStarted:false,checks:['V45 authenticated mobile protocol-fixture PNGs keep IDs and exact bytes after V46 migration','native step report, device/application/scenario configurations, owner and project file stay unchanged','V46 history exposes the original scene version and two independent PNG downloads','seed V45 database stays unchanged after upgrading its separate candidate copy'],originalArtifacts,originalStepCount:report.steps.length}
  console.log('PASS V45 mobile protocol-fixture bytes, report, configuration and owner survive V46')
}catch(error){failure=error;outcome={passed:false,error:error.message}}
finally{
  let cleanupError
  for(const stop of [()=>browser?.close(),()=>backend?.stop()])try{await stop()}catch(error){cleanupError||=error}
  target.closeAllConnections();await new Promise(resolve=>target.close(resolve));await fs.rm(owned,{recursive:true,force:true})
  outcome.ownedDataAndProcessesRemoved=!cleanupError;if(cleanupError){outcome.passed=false;failure||=cleanupError}
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
