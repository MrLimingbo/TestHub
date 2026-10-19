// Genuine V44 Web UI reports and artifacts must survive a V45 backend upgrade byte-for-byte.
import { Buffer } from 'node:buffer'
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
const owned=await fs.mkdtemp(path.resolve('.testhub/ui-history-upgrade-')),seed=path.join(owned,'v44'),candidate=path.join(owned,'candidate'),engines=path.join(owned,'engines')
const bundle=await loadBundle(path.join(await fs.realpath(argument('--app')),'Contents/Resources/local-runtime/darwin-arm64'))
const databasePassword=crypto.randomBytes(32).toString('hex'),encryptionKey=crypto.randomBytes(32).toString('base64'),secrets={version:1,databasePassword,encryptionKey}
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex')
let browser,backend,outcome,failure,hits=0
const target=http.createServer((_req,res)=>{hits++;res.setHeader('content-type','text/html');res.end('<!doctype html><title>V44 UI fixture</title><p id="ready">preserved</p>')})
async function read(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route),v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},route)}
async function send(page,route,body,method='POST'){return page.evaluate(async({route,body,method})=>{const csrf=(await(await globalThis.fetch('/api/v1/auth/csrf')).json()).data,r=await globalThis.fetch(route,{method,headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(body)});if(r.status===204)return;const v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},{route,body,method})}
async function fileHash(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('Upgrade file unavailable');const bytes=await r.arrayBuffer(),digest=await globalThis.crypto.subtle.digest('SHA-256',bytes);return{bytes:bytes.byteLength,sha256:[...new Uint8Array(digest)].map(n=>n.toString(16).padStart(2,'0')).join('')}},route)}
async function until(probe){for(let n=0;n<800;n++){const value=await probe();if(value)return value;await delay(150)}throw new Error('Upgrade execution timed out')}
try {
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+target.address().port
  const manager=new LocalBrowsers({directory:engines,bundle}),plan=await manager.plan('chromium'),cache=plan.cache;await fs.mkdir(cache,{recursive:true})
  const sourceCache=await fs.realpath(argument('--browser-cache'));for(const entry of [...plan.components.map(c=>c.directory),'.ready-chromium.json'])await fs.cp(path.join(sourceCache,entry),path.join(cache,entry),{recursive:true,verbatimSymlinks:true});assert.equal(await manager.installed('chromium'),cache)
  const relative=path.relative(sourceCache,await fs.realpath(argument('--chromium')));assert.ok(!relative.startsWith('..'))
  browser=await chromium.launch({headless:true,executablePath:path.join(cache,relative)});const page=await browser.newPage()
  backend=await performanceAccount({bundle,directory:seed,origin,backend:await fs.realpath(argument('--baseline-backend')),databasePassword,encryptionKey})
  await page.goto(backend.server+'/login');const account='uiupgrade'+Date.now(),password=crypto.randomBytes(20).toString('base64url')
  await send(page,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离 V44 Web UI 升级',password})
  const actor=await read(page,'/api/v1/auth/me'),context=await read(page,'/api/v1/access/context'),project=context.projects[context.organizations[0].id][0].id,base='/api/v1/projects/'+project
  await backend.startPlaywright(cache)
  const profile=await send(page,base+'/ui-automation/browser-profiles',{name:'旧版浏览器',browser:'chromium',baseUrl:origin,viewportWidth:960,viewportHeight:640,enabled:true})
  const scene=await send(page,base+'/ui-automation/scenarios',{browserProfileId:profile.id,name:'V44 原始场景',description:'保留页面及产物',failureStrategy:'STOP',variables:{},steps:[{id:'open',type:'NAVIGATE',name:'打开页面',url:'/fixture',timeoutMillis:30000},{id:'shot',type:'SCREENSHOT',name:'保留截图',timeoutMillis:30000},{id:'assert',type:'ASSERT_TEXT',name:'检查',selector:'#ready',expectedText:'preserved',timeoutMillis:30000}]})
  const execution=await send(page,base+`/ui-automation/scenarios/${scene.id}/executions`,{timeoutSeconds:120})
  const report=await until(async()=>{const r=await read(page,base+'/ui-automation/executions/'+execution.id+'/report');return r.execution.status==='SUCCEEDED'&&r.artifacts.length===3&&r.steps.length===3&&r})
  const originalArtifacts=Object.fromEntries(await Promise.all(report.artifacts.map(async a=>[a.id,{type:a.artifactType,...await fileHash(page,base+`/ui-automation/executions/${execution.id}/artifacts/${a.id}/download`)}])))
  await page.goto(backend.server+'/project-settings/files');await page.locator('input[type=file]').setInputFiles({name:'升级保留.txt',mimeType:'text/plain',buffer:Buffer.from('V44 unchanged file bytes')});await page.getByText('升级保留.txt',{exact:true}).first().waitFor()
  const file=(await read(page,base+'/files')).find(v=>v.originalName==='升级保留.txt'),fileRoute=base+'/files/'+file.id+'/download',fileBytes=await fileHash(page,fileRoute)
  const routes=[base+'/ui-automation/scenarios',base+'/ui-automation/browser-profiles',base+'/ui-automation/executions/'+execution.id+'/report',base+'/ui-automation/executions/'+execution.id+'/artifacts',base+'/files']
  const before=Object.fromEntries(await Promise.all(routes.map(async route=>[route,await read(page,route)])))
  await backend.stop();backend=undefined;assert.equal((await databaseTool(bundle,seed,secrets)).databaseVersion,'44');const originalDatabase=hash(await fs.readFile(path.join(seed,'workspace.mv.db')))
  await fs.cp(seed,candidate,{recursive:true,verbatimSymlinks:true});backend=await performanceAccount({bundle,directory:candidate,origin,databasePassword,encryptionKey})
  await page.goto(backend.server+'/login');await send(page,'/api/v1/auth/login',{account,password});assert.equal((await read(page,'/api/v1/auth/me')).id,actor.id)
  for(const route of routes)assert.deepEqual(await read(page,route),before[route]);assert.deepEqual(await fileHash(page,fileRoute),fileBytes)
  const history=await read(page,base+'/ui-automation/history/'+project+'.'+execution.id);assert.equal(history.report.summary.totalSteps,3);assert.equal(history.report.scenarioVersion,1)
  const artifacts=await read(page,base+'/ui-automation/history/'+project+'.'+execution.id+'/artifacts');assert.equal(artifacts.length,3)
  for(const entry of artifacts){const actual=await fileHash(page,base+'/ui-automation/artifacts/'+entry.key+'/download');assert.deepEqual({type:entry.artifactType,...actual},originalArtifacts[entry.sourceArtifactId]);assert.equal(actual.sha256,entry.checksum);assert.equal(entry.nativeRecord,true)}
  assert.equal(hash(await fs.readFile(path.join(seed,'workspace.mv.db'))),originalDatabase)
  await backend.stop();backend=undefined;assert.equal((await databaseTool(bundle,candidate,secrets)).databaseVersion,'45')
  outcome={passed:true,from:44,to:45,headless:true,targetRequests:hits,checks:['V44 genuine Chromium PNG, Trace and video keep their IDs and exact bytes after V45 migration','native step report, source scenario and browser configuration, owner and uploaded project file stay unchanged','V45 history exposes the original scene version and three independent raw artifact downloads','seed V44 database stays unchanged after upgrading its separate candidate copy'],originalArtifacts}
  console.log('PASS genuine V44 Web UI bytes, report, configuration and owner survive V45')
}catch(error){failure=error;outcome={passed:false,error:error.message}}
finally{
  let cleanupError
  for(const stop of [()=>browser?.close(),()=>backend?.stop()])try{await stop()}catch(error){cleanupError||=error}
  target.closeAllConnections();await new Promise(resolve=>target.close(resolve));await fs.rm(owned,{recursive:true,force:true})
  outcome.ownedDataAndProcessesRemoved=!cleanupError;if(cleanupError){outcome.passed=false;failure||=cleanupError}
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
