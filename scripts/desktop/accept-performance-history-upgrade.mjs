// Upgrade an exclusively owned V42 database using the backend from the final V43 package.
import { Buffer } from 'node:buffer'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { setTimeout as delay } from 'node:timers/promises'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const require=createRequire(import.meta.url),{loadBundle,childEnvironment}=require('../../apps/desktop/app/src/local-runtime.cjs')
const {databaseTool}=require('../../apps/desktop/app/src/workspace-restore.cjs')
const args=process.argv.slice(2),argument=key=>args[args.indexOf(key)+1]
for(const key of ['--app','--baseline-backend','--chromium','--output'])if(!args.includes(key))throw new Error('Required argument: '+key)
const output=path.resolve(argument('--output'));await fs.mkdir(path.dirname(output),{recursive:true});await(await fs.open(output,'wx',0o600)).close()
const owned=await fs.mkdtemp(path.resolve('.testhub/performance-history-upgrade-')),seed=path.join(owned,'v42'),candidate=path.join(owned,'candidate')
await fs.mkdir(seed)
const bundle=await loadBundle(path.join(await fs.realpath(argument('--app')),'Contents/Resources/local-runtime/darwin-arm64'))
const databasePassword=crypto.randomBytes(32).toString('hex'),encryptionKey=crypto.randomBytes(32).toString('base64')
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex')
let browser,child,done,log,outcome,failure
async function stop(){if(child){child.stdin.end();child.kill('SIGTERM');await done;await log.close();child=undefined}}
async function start(directory,backend){
  const ready=path.join(directory,'ready.json');await fs.rm(ready,{force:true})
  log=await fs.open(path.join(directory,'backend-private.log'),'w',0o600)
  child=spawn(bundle.java,['-Xmx768m','-jar',backend,'--spring.profiles.active=desktop','--server.port=0'],{stdio:['pipe',log.fd,log.fd],env:{...childEnvironment(),TESTHUB_DESKTOP_DATA_DIRECTORY:directory,TESTHUB_LOCAL_DB_PASSWORD:databasePassword,TESTHUB_ENCRYPTION_KEY:encryptionKey,TESTHUB_REGISTRATION_ENABLED:'true',TESTHUB_DESKTOP_READY_FILE:ready,TESTHUB_DESKTOP_FRONTEND_DIRECTORY:bundle.frontend,TESTHUB_DESKTOP_INSTANCE:crypto.randomUUID(),TESTHUB_DESKTOP_SESSION_KEY:crypto.randomBytes(32).toString('hex')}})
  done=new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',resolve)})
  for(let n=0;n<900;n++){if(child.exitCode!==null)throw new Error('Upgrade QA backend exited');try{return 'http://127.0.0.1:'+JSON.parse(await fs.readFile(ready,'utf8')).port}catch{await delay(100)}}throw new Error('Upgrade QA backend startup timed out')
}
async function read(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route),v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},route)}
async function send(page,route,body,method='POST'){return page.evaluate(async({route,body,method})=>{const csrf=(await(await globalThis.fetch('/api/v1/auth/csrf')).json()).data,r=await globalThis.fetch(route,{method,headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(body)});if(r.status===204)return;const v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},{route,body,method})}
async function fileHash(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('Upgrade file unavailable');const bytes=await r.arrayBuffer(),digest=await globalThis.crypto.subtle.digest('SHA-256',bytes);return{bytes:bytes.byteLength,sha256:[...new Uint8Array(digest)].map(n=>n.toString(16).padStart(2,'0')).join('')}},route)}
try{
  browser=await chromium.launch({headless:true,executablePath:await fs.realpath(argument('--chromium'))})
  const page=await browser.newPage(),server=await start(seed,await fs.realpath(argument('--baseline-backend')))
  await page.goto(server+'/login')
  const account='perfupgrade'+Date.now(),password=crypto.randomBytes(20).toString('base64url')
  await send(page,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离升级验收',password})
  const actor=await read(page,'/api/v1/auth/me'),context=await read(page,'/api/v1/access/context'),project=context.projects[context.organizations[0].id][0].id,base='/api/v1/projects/'+project
  const plan=await send(page,base+'/performance/plans',{name:'V42 原计划',description:'保留原始数据',status:'READY',threadGroups:[{name:'原线程组',targetUrl:'https://example.test/unchanged',loadModel:'STEADY',threadCount:1,rampUpSeconds:0,durationSeconds:10,requestMethod:'GET',requestHeaders:{'X-Fixture':'private-fixture'},expectedStatusCode:200}]})
  const execution=await send(page,base+`/performance/plans/${plan.id}/executions`,{timeoutSeconds:60});await send(page,base+'/performance/executions/'+execution.id,undefined,'DELETE')
  await page.goto(server+'/project-settings/files');await page.locator('input[type=file]').setInputFiles({name:'升级保留.txt',mimeType:'text/plain',buffer:Buffer.from('V42 unchanged file bytes')});await page.getByText('升级保留.txt',{exact:true}).first().waitFor()
  const file=(await read(page,base+'/files')).find(v=>v.originalName==='升级保留.txt'),fileRoute=base+'/files/'+file.id+'/download',bytes=await fileHash(page,fileRoute)
  const routes=[base+'/performance/plans/'+plan.id,base+'/performance/executions/'+execution.id,base+'/files']
  const before=Object.fromEntries(await Promise.all(routes.map(async route=>[route,await read(page,route)])))
  await stop();assert.equal((await databaseTool(bundle,seed,{version:1,databasePassword,encryptionKey})).databaseVersion,'42');const originalDatabase=hash(await fs.readFile(path.join(seed,'workspace.mv.db')))
  await fs.cp(seed,candidate,{recursive:true,verbatimSymlinks:true})
  const upgraded=await start(candidate,bundle.backend);await page.goto(upgraded+'/login');await send(page,'/api/v1/auth/login',{account,password})
  assert.equal((await read(page,'/api/v1/auth/me')).id,actor.id)
  for(const route of routes)assert.deepEqual(await read(page,route),before[route])
  assert.deepEqual(await fileHash(page,fileRoute),bytes)
  assert.equal(hash(await fs.readFile(path.join(seed,'workspace.mv.db'))),originalDatabase)
  const inventory=await read(page,base+'/performance/history');assert.equal(inventory.entries.length,1);assert.equal(inventory.entries[0].nativeRecord,true)
  const report=await read(page,base+'/performance/history/'+project+'.'+execution.id);assert.equal(report.report.planVersion,1);assert.equal(report.report.status,'CANCELLED')
  await stop();assert.equal((await databaseTool(bundle,candidate,{version:1,databasePassword,encryptionKey})).databaseVersion,'43')
  outcome={passed:true,from:42,to:43,headless:true,checks:['V42 plan, private request configuration, cancelled execution and file metadata preserve IDs and content','download bytes remain identical','source V42 database stays untouched','V43 exposes the preserved native performance history without a new execution']}
  console.log('PASS V42 to V43 database and file preservation')
}catch(error){failure=error;outcome={passed:false,error:error.message}}
finally{await browser?.close();await stop();await fs.rm(owned,{recursive:true,force:true});outcome.ownedDataAndProcessesRemoved=true;await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})}
if(failure)throw failure
