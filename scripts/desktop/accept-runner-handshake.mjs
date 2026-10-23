// Real packaged Mac cold starts and restarts, with no visible windows and no shared workspace writes.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import {setTimeout as delay} from 'node:timers/promises'
import {offscreenMac} from './offscreen-mac-qa.mjs'

const run=promisify(execFile),args=process.argv.slice(2)
function argument(key) { const index=args.indexOf(key);assert.ok(index>=0 && args[index+1] && !args[index+1].startsWith('--'),'Required argument: '+key);return args[index+1] }
assert.equal(process.platform,'darwin')
const appPath=await fs.realpath(argument('--app')),output=path.resolve(argument('--output'))
await fs.mkdir(path.dirname(output),{recursive:true});await(await fs.open(output,'wx',0o600)).close()
const owned=await fs.mkdtemp(path.resolve('.testhub/runner-handshake-qa-')),runs=[],names=[]
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex')
const asar=path.join(appPath,'Contents/Resources/app.asar'),asarBefore=sha(await fs.readFile(asar))
const backend=path.join(appPath,'Contents/Resources/local-runtime/darwin-arm64/platform-api.jar'),backendBefore=sha(await fs.readFile(backend))
let app,failure,outcome={passed:false,runs}
async function read(page,route) { return page.evaluate(async route=>{const r=await globalThis.fetch(route);if(!r.ok)throw new Error('QA read failed: HTTP '+r.status);return(await r.json()).data},route) }
async function marker(page,id,value) {
  return page.evaluate(async({id,value})=>{
    const csrf=(await(await globalThis.fetch('/api/v1/auth/csrf')).json()).data
    const r=await globalThis.fetch('/api/v1/projects/'+id+'/parameters',{method:'POST',headers:{'content-type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify({environmentCode:'test',key:'QA_STARTUP_MARKER',value})})
    if(r.status!==204)throw new Error('QA marker write failed: HTTP '+r.status)
  },{id,value})
}
async function discoveryLog(directory) {
  const root=path.join(directory,'local-workspace/logs'),files=(await fs.readdir(root)).filter(file=>/^api-runner-.*[.]log$/.test(file)).sort()
  const logs=await Promise.all(files.map(file=>fs.readFile(path.join(root,file),'utf8')))
  assert.ok(logs.every(log=>!log.includes('Runner 签名能力发现失败')&&!/HTTP 401\b/.test(log)),'Runner authentication failed during a real startup')
  return logs
}
try {
  await run('/usr/bin/codesign',['--verify','--deep','--strict',appPath])
  for(let cold=0;cold<12;cold++) {
    const directory=path.join(owned,'workspace-'+cold),name='TestHub-QA-'+crypto.randomUUID(),value=crypto.randomUUID()
    names.push(name);await fs.writeFile(path.join(owned,'.qa-owner.json'),JSON.stringify({names}),{mode:0o600});await fs.mkdir(directory)
    let projectId
    // Twelve independent first launches, plus two restarts of each of the first three workspaces.
    for(let restart=0;restart<(cold<3?3:1);restart++) {
      const started=Date.now()
      app=await offscreenMac(appPath,directory,{name,cleanKeychain:false,observeRuntime:true,diagnosticLog:path.join(owned,'native-private.log'),startupDiagnostics:path.join(owned,'startup-private.json')})
      const page=app.local,context=await read(page,'/api/v1/access/context')
      assert.equal(context.organizations.length,1)
      const projects=context.projects[context.organizations[0].id];assert.equal(projects.length,1)
      if(restart===0) {projectId=projects[0].id;await marker(page,projectId,value)}
      else assert.equal(projects[0].id,projectId)
      const parameters=(await read(page,'/api/v1/projects/'+projectId+'/parameters')).parameters
      assert.equal(parameters.find(row=>row.key==='QA_STARTUP_MARKER')?.value,value)
      const status=(await read(page,'/api/v1/projects/'+projectId+'/api-automation/runner-status')).find(row=>row.runnerId==='desktop-api')
      assert.ok(status?.trusted);assert.equal(status.trustStatus,'VERIFIED');assert.equal(status.executionEnabled,false)
      let logs
      const deadline=Date.now()+10000
      do {logs=await discoveryLog(directory);if(logs.length===restart+1 && logs.every(log=>log.includes('API Runner 已完成签名能力发现')))break;await delay(100)}while(Date.now()<deadline)
      assert.equal(logs.length,restart+1);assert.ok(logs.every(log=>log.includes('API Runner 已完成签名能力发现')),'Signed capability discovery did not finish')
      const native=await app.snapshot(),runtime=await app.evaluate(()=>({phase:globalThis.__testhubQaLocalRuntime.phase,backend:globalThis.__testhubQaLocalRuntime.bundle.backend}))
      assert.equal(runtime.phase,'ready');assert.equal(sha(await fs.readFile(runtime.backend)),backendBefore)
      runs.push({kind:restart===0?'cold':'restart',workspace:cold+1,ready:true,heartbeatVerified:true,capabilityDiscoveryVerified:true,executionEnabled:false,elapsedMs:Date.now()-started,visibleViolations:native.visibleViolations,unexpectedDialogs:native.unexpectedDialogs})
      await app.close();app=undefined
      console.log('PASS packaged Runner handshake '+runs.length+'/18 ('+(restart===0?'cold':'restart')+')')
    }
    await fs.rm(directory,{recursive:true,force:true})
  }
  assert.equal(sha(await fs.readFile(asar)),asarBefore);assert.equal(sha(await fs.readFile(backend)),backendBefore)
  outcome.passed=true;outcome.coldStarts=12;outcome.restarts=6;outcome.appUnchanged=true
}catch(error){failure=error;outcome.error=error.message}
finally {
  const cleanup=[]
  try{await app?.close()}catch{cleanup.push('native process')}
  for(const name of names) {
    try{await run('/usr/bin/security',['delete-generic-password','-s',name+' Safe Storage','-a',name+' Key'])}catch(error){if(error.code!==44)cleanup.push('QA Keychain deletion')}
    try{await run('/usr/bin/security',['find-generic-password','-s',name+' Safe Storage','-a',name+' Key']);cleanup.push('QA Keychain remains')}catch(error){if(error.code!==44)cleanup.push('QA Keychain verification')}
  }
  if(!cleanup.length)await fs.rm(owned,{recursive:true,force:true})
  outcome.cleanupComplete=cleanup.length===0;outcome.cleanupErrors=cleanup
  if(cleanup.length){outcome.passed=false;failure||=new Error('Owned QA cleanup failed')}
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
