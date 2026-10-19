// Genuine V43 JMeter artifacts must survive a V44 backend upgrade byte-for-byte.
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
const require=createRequire(import.meta.url),{loadBundle}=require('../../apps/desktop/app/src/local-runtime.cjs'),{databaseTool}=require('../../apps/desktop/app/src/workspace-restore.cjs')
const args=process.argv.slice(2),argument=key=>args[args.indexOf(key)+1]
for(const key of ['--app','--baseline-backend','--chromium','--jmeter-cache','--output'])if(!args.includes(key))throw new Error('Required argument: '+key)
const output=path.resolve(argument('--output'));await fs.mkdir(path.dirname(output),{recursive:true});await(await fs.open(output,'wx',0o600)).close()
const owned=await fs.mkdtemp(path.resolve('.testhub/performance-artifact-upgrade-')),seed=path.join(owned,'v43'),candidate=path.join(owned,'candidate'),engines=path.join(owned,'engines')
const bundle=await loadBundle(path.join(await fs.realpath(argument('--app')),'Contents/Resources/local-runtime/darwin-arm64'))
const databasePassword=crypto.randomBytes(32).toString('hex'),encryptionKey=crypto.randomBytes(32).toString('base64'),secrets={version:1,databasePassword,encryptionKey}
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex')
let browser,backend,outcome,failure,hits=0
const target=http.createServer((_req,res)=>{hits++;res.end('{"ok":true}')})
async function read(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route),v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},route)}
async function send(page,route,body,method='POST'){return page.evaluate(async({route,body,method})=>{const csrf=(await(await globalThis.fetch('/api/v1/auth/csrf')).json()).data,r=await globalThis.fetch(route,{method,headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(body)});if(r.status===204)return;const v=await r.json();if(!r.ok)throw new Error(v.message);return v.data},{route,body,method})}
async function fileHash(page,route){return page.evaluate(async route=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('Upgrade file unavailable');const bytes=await r.arrayBuffer(),digest=await globalThis.crypto.subtle.digest('SHA-256',bytes);return{bytes:bytes.byteLength,sha256:[...new Uint8Array(digest)].map(n=>n.toString(16).padStart(2,'0')).join('')}},route)}
async function until(probe){for(let n=0;n<800;n++){const value=await probe();if(value)return value;await delay(150)}throw new Error('Upgrade execution timed out')}
try {
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+target.address().port
  await fs.mkdir(engines);await fs.cp(await fs.realpath(argument('--jmeter-cache')),path.join(engines,'apache-jmeter-5.6.3'),{recursive:true})
  const home=path.join(engines,'apache-jmeter-5.6.3/apache-jmeter-5.6.3')
  browser=await chromium.launch({headless:true,executablePath:await fs.realpath(argument('--chromium'))});const page=await browser.newPage()
  backend=await performanceAccount({bundle,directory:seed,origin,backend:await fs.realpath(argument('--baseline-backend')),databasePassword,encryptionKey})
  await page.goto(backend.server+'/login');const account='rawupgrade'+Date.now(),password=crypto.randomBytes(20).toString('base64url')
  await send(page,'/api/v1/auth/register',{account,email:account+'@example.test',displayName:'隔离 V43 工件升级',password})
  const actor=await read(page,'/api/v1/auth/me'),context=await read(page,'/api/v1/access/context'),project=context.projects[context.organizations[0].id][0].id,base='/api/v1/projects/'+project
  await backend.startJmeter(home)
  const plan=await send(page,base+'/performance/plans',{name:'V43 原始工件计划',description:'保留所有原始字节',status:'READY',threadGroups:[{name:'原始线程组',targetUrl:origin+'/probe',loadModel:'STEADY',threadCount:1,rampUpSeconds:0,durationSeconds:10,targetTps:2,requestMethod:'GET',requestHeaders:{'X-Fixture':'private-fixture'},expectedStatusCode:200}]})
  const execution=await send(page,base+`/performance/plans/${plan.id}/executions`,{timeoutSeconds:120})
  const report=await until(async()=>{const r=await read(page,base+'/performance/executions/'+execution.id+'/report');return r.execution.status==='SUCCEEDED'&&r.artifacts.length===2&&r.summary.sampleCount>0&&r})
  const originalArtifacts=Object.fromEntries(await Promise.all(report.artifacts.map(async a=>[a.artifactType,await fileHash(page,base+`/performance/executions/${execution.id}/artifacts/${a.id}/download`)])))
  await page.goto(backend.server+'/project-settings/files');await page.locator('input[type=file]').setInputFiles({name:'升级保留.txt',mimeType:'text/plain',buffer:Buffer.from('V43 unchanged file bytes')});await page.getByText('升级保留.txt',{exact:true}).first().waitFor()
  const file=(await read(page,base+'/files')).find(v=>v.originalName==='升级保留.txt'),fileRoute=base+'/files/'+file.id+'/download',fileBytes=await fileHash(page,fileRoute)
  const routes=[base+'/performance/plans/'+plan.id,base+'/performance/executions/'+execution.id,base+'/performance/executions/'+execution.id+'/artifacts',base+'/files']
  const before=Object.fromEntries(await Promise.all(routes.map(async route=>[route,await read(page,route)])))
  await backend.stop();backend=undefined;assert.equal((await databaseTool(bundle,seed,secrets)).databaseVersion,'43');const originalDatabase=hash(await fs.readFile(path.join(seed,'workspace.mv.db')))
  await fs.cp(seed,candidate,{recursive:true,verbatimSymlinks:true});backend=await performanceAccount({bundle,directory:candidate,origin,databasePassword,encryptionKey})
  await page.goto(backend.server+'/login');await send(page,'/api/v1/auth/login',{account,password});assert.equal((await read(page,'/api/v1/auth/me')).id,actor.id)
  for(const route of routes)assert.deepEqual(await read(page,route),before[route]);assert.deepEqual(await fileHash(page,fileRoute),fileBytes)
  const artifacts=await read(page,base+'/performance/history/'+project+'.'+execution.id+'/artifacts');assert.equal(artifacts.length,2)
  for(const entry of artifacts){const actual=await fileHash(page,base+'/performance/artifacts/'+entry.key+'/download');assert.deepEqual(actual,originalArtifacts[entry.artifactType]);assert.equal(actual.sha256,entry.checksum);assert.equal(entry.nativeRecord,true)}
  assert.equal(hash(await fs.readFile(path.join(seed,'workspace.mv.db'))),originalDatabase)
  await backend.stop();backend=undefined;assert.equal((await databaseTool(bundle,candidate,secrets)).databaseVersion,'44')
  outcome={passed:true,from:43,to:44,headless:true,targetRequests:hits,originalSamples:report.summary.sampleCount,checks:['V43 genuine JMeter JTL/log metadata and original download bytes survive V44 migration','plan, execution, request configuration, owner and uploaded file preserve identity and content','V44 raw history download exposes each original artifact with unchanged SHA-256','seed V43 database stays untouched after the candidate copy'],originalArtifacts}
  console.log('PASS genuine V43 JTL/log bytes, data and owner survive V44')
}catch(error){failure=error;outcome={passed:false,error:error.message}}
finally{await browser?.close();await backend?.stop();target.closeAllConnections();await new Promise(resolve=>target.close(resolve));await fs.rm(owned,{recursive:true,force:true});outcome.ownedDataAndProcessesRemoved=true;await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})}
if(failure)throw failure
