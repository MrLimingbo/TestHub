'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalDefectExecutionSync, FunctionalDefectExecutionSyncStore, keyOf, empty, validateState } = require('../app/src/functional-defect-execution-sync.cjs')
const { installFunctionalDefectExecutionSync } = require('../app/src/functional-defect-execution-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const peer = 'E'.repeat(26), otherPeer = 'F'.repeat(26), remotePeer = 'G'.repeat(26), remoteOther = 'H'.repeat(26)
const lr='I'.repeat(26), rr='J'.repeat(26)
const reference=(id,runId,caseCode,side)=>({id,runId,planId:(side==='local'?'K':'L').repeat(26),caseId:(caseCode==='CASE-1'?'M':'N').repeat(26),planCode:'PLAN-1',caseCode,runName:'同名批次',runStatus:'NOT_STARTED',startedAt:null,finishedAt:null})
const localReferences=[reference(peer,lr,'CASE-1','local'),reference(otherPeer,lr,'CASE-2','local')]
const remoteReferences=[reference(remotePeer,rr,'CASE-1','remote'),reference(remoteOther,rr,'CASE-2','remote')]
const pairs=[{localRunId:lr,remoteRunId:rr}]
const item=(name,runCaseIds=[])=>({kind:'DEFECT',name,definition:{runCaseIds:[...runCaseIds].sort()}})
function fixture() {
  const state = { actor, server, references:{local:copy(localReferences),remote:copy(remoteReferences)}, unavailable:{local:[],remote:[]}, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('BUG-1'),item('BUG-2')], remote: [item('BUG-1'),item('BUG-2')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const identity = (side,i) => state.identities?.[side]?.[keyOf(i)] || crypto.createHash('sha256').update(side+keyOf(i)).digest('hex').slice(0,26).toUpperCase()
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side],state.references[side],state.unavailable[side]])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_DEFECT_EXECUTION_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      references:copy(state.references[side]),unavailable:copy(state.unavailable[side]),revision: stamp(side), items: copy(rows[side]), itemIds: Object.fromEntries(rows[side].map(i => [i.kind+'\n'+i.name.toLowerCase(), identity(side,i)])) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const e = new Error('revision or actor changed'); e.status = 409; throw e }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== stamp(side)) conflict()
      const next = copy(rows[side])
      result = { requestKey: body.requestKey, results: body.operations.map(op => {
        const index = next.findIndex(item => keyOf(item) === keyOf(op.item)); if (index < 0) next.push(copy(op.item)); else next[index] = copy(op.item)
        return { operationId: op.operationId, item: copy(op.item), itemId: identity(side,op.item) }
      }) }; rows[side] = next; receipts[side].set(body.requestKey, copy(result))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async b => { if (b.server !== state.server) throw new Error('server changed') } }
  const client = new FunctionalDefectExecutionSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server,pairs); await client.bind(p.token); await client.run() }
  const edit = (side,key,value=[]) => {const row=rows[side].find(i=>i.name===key);assert.ok(row);row.definition={runCaseIds:[...value].sort()}}
  return { state, rows, store, client, options, bind, edit }
}
test('batch listing and explicit pairing are read only and equal empty sets establish bases',async()=>{
 const f=fixture();const value=await f.client.references(sourceId,targetId,server);assert.equal(value.local[0].id,lr);assert.deepEqual(value.local[0].caseCodes,['CASE-1','CASE-2']);await f.bind();assert.equal(f.state.writes.length,0);assert.equal(f.client.state.bases.length,2)
})
test('one-to-one pairing rejects duplicate batches unknown IDs plan mismatch and empty selection',async()=>{
 for(const value of [[pairs[0],pairs[0]],[{localRunId:'Z'.repeat(26),remoteRunId:rr}],[]]){const f=fixture();await assert.rejects(f.client.preview(sourceId,targetId,server,value));assert.equal(f.state.writes.length,0)}const f=fixture();f.state.references.remote.forEach(r=>r.planCode='PLAN-OTHER');await assert.rejects(f.client.preview(sourceId,targetId,server,pairs),/计划编号/)
})
test('same-name batches require explicit IDs and match case codes only within chosen batch',async()=>{
 const f=fixture(),second='O'.repeat(26),secondCase='P'.repeat(26);f.state.references.remote.push(reference(secondCase,second,'CASE-1','remote'));const p=await f.client.preview(sourceId,targetId,server,[{localRunId:lr,remoteRunId:second}]);await f.client.bind(p.token);await f.client.run();f.edit('local','BUG-1',[peer]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[secondCase]);assert.equal(f.client.state.binding.references.length,1)
})
test('local IDs translate to actual target execution IDs and web changes translate back',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',[peer,otherPeer]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition,{runCaseIds:[remotePeer,remoteOther].sort()});f.edit('remote','BUG-2',[remoteOther]);await f.client.run();assert.deepEqual(f.rows.local[1].definition,{runCaseIds:[otherPeer]})
})
test('explicit empty association set propagates complete clear',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);await f.client.run();f.edit('remote','BUG-1',[]);await f.client.run();assert.deepEqual(f.rows.local[0].definition,{runCaseIds:[]})
})
test('offline local and independent web edits reconcile both directions',async()=>{
 const f=fixture();await f.bind();f.state.offline=true;f.edit('local','BUG-1',[peer]);await assert.rejects(f.client.run(),/offline/);f.edit('remote','BUG-2',[remoteOther]);f.state.offline=false;await f.client.run();assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[remotePeer]);assert.deepEqual(f.rows.local[1].definition.runCaseIds,[otherPeer])
})
test('whole set conflicts require human review and stale choices are rejected',async()=>{
 const f=fixture();f.edit('remote','BUG-1',[remotePeer]);await f.bind();assert.equal(f.client.state.conflicts.length,1);await f.client.resolve(keyOf(item('BUG-1')),'local');await f.client.run();f.edit('local','BUG-1',[peer]);f.edit('remote','BUG-1',[remoteOther]);await f.client.run();f.edit('remote','BUG-1',[remotePeer,remoteOther]);await assert.rejects(f.client.resolve(keyOf(item('BUG-1')),'local'),/已变化/);await f.client.run();await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.deepEqual(f.rows.local[0].definition.runCaseIds,[peer,otherPeer].sort())
})
test('unmatched case code blocks only affected defect without taking case from another batch',async()=>{
 const f=fixture();f.state.references.remote[1].caseCode='CASE-OTHER';await f.bind();f.edit('local','BUG-1',[otherPeer]);f.edit('local','BUG-2',[peer]);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[]);assert.deepEqual(f.rows.remote[1].definition.runCaseIds,[remotePeer])
})
test('removed execution blocks baseline-dependent clear even when both sides now empty',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);await f.client.run();f.state.references.remote=f.state.references.remote.filter(r=>r.id!==remotePeer);f.edit('local','BUG-1',[]);f.edit('remote','BUG-1',[]);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.deepEqual(f.client.state.bases[0].definition.runCaseIds,[peer])
})
test('same-code execution case plan or batch recreation never inherits pairing',async()=>{
 for(const field of ['id','runId','planId','caseId']){const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);if(field==='planId'||field==='runId')f.state.references.remote.forEach(r=>r[field]='Z'.repeat(26));else f.state.references.remote[0][field]='Z'.repeat(26);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[])}
})
test('batch display name and status changes keep identity while legacy dangling link pauses',async()=>{
 const f=fixture();await f.bind();f.state.references.remote.forEach(r=>{r.runName='新名称';r.runStatus='COMPLETED';r.startedAt='2026-10-12T01:00:00Z';r.finishedAt='2026-10-12T02:00:00Z'});f.edit('local','BUG-1',[peer]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[remotePeer]);f.state.unavailable.remote=['DEFECT\nbug-1'];f.edit('local','BUG-1',[]);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false)
})
test('missing and recreated defect requires review without creating it',async()=>{
 const f=fixture();await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.name!=='BUG-1');await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);f.rows.remote.push(item('BUG-1',[remotePeer]));await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.deepEqual(f.rows.local[0].definition.runCaseIds,[peer])
})
test('same-content conflict choice is invalidated by recreated defect ID',async()=>{
 const f=fixture();f.edit('remote','BUG-1',[remotePeer]);await f.bind();f.state.identities={remote:{[keyOf(item('BUG-1'))]:'Z'.repeat(26)}};await assert.rejects(f.client.resolve(keyOf(item('BUG-1')),'local'),/已变化/);assert.equal(f.state.writes.length,0)
})
test('lost response restart replays exact raw target IDs and retains later edits',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);const pending=copy(f.client.state.pending);assert.deepEqual(pending.request.operations[0].item.definition.runCaseIds,[remotePeer]);f.edit('remote','BUG-1',[remoteOther]);f.state.loseResponse=false;const next=new FunctionalDefectExecutionSync(f.options);await next.run();assert.equal(next.state.pending,null);assert.deepEqual(f.rows.local[0].definition.runCaseIds,[otherPeer]);const attempts=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1])
})
test('execution deletion during CSRF invalidates whole batch and clears stale pending',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);f.edit('local','BUG-2',[otherPeer]);f.state.onCsrf=()=>{f.state.references.remote.pop();f.state.onCsrf=undefined};await assert.rejects(f.client.run(),/revision/);assert.equal(f.client.state.pending,null);assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[])
})
test('account server disk and CSRF races prevent outgoing links',async()=>{
 for(const change of [f=>{f.state.actor='Z'.repeat(26)},f=>{f.state.server='https://other.test'},f=>{f.state.onCsrf=()=>{f.state.actor='Z'.repeat(26)}},f=>{f.state.failSave=true}]){const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);change(f);await assert.rejects(f.client.run());assert.deepEqual(f.rows.remote[0].definition.runCaseIds,[])}
})
test('pause restore and unbind preserve endpoint sets and require renewed pairing',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',[peer]);await f.client.enabled(false);await f.client.run();assert.equal(f.state.writes.length,0);await f.client.suspendForRestore();await assert.rejects(f.client.enabled(true),/恢复过备份/);await f.client.unbind();assert.deepEqual(f.rows.local[0].definition.runCaseIds,[peer])
})
test('changed preview and missing-baseline union capacity prevent POST',async()=>{
 const f=fixture();const p=await f.client.preview(sourceId,targetId,server,pairs);f.state.references.remote[0].id='Z'.repeat(26);await assert.rejects(f.client.bind(p.token),/已变化/);f.state.references.remote[0]=copy(remoteReferences[0]);await f.bind();for(let n=0;n<98;n++){f.rows.local.push(item('B-'+n));f.rows.remote.push(item('B-'+n))}await f.client.run();f.rows.local=f.rows.local.filter(i=>i.name!=='B-0');f.rows.remote=f.rows.remote.filter(i=>i.name!=='B-0');f.rows.local.push(item('B-OVER'));await assert.rejects(f.client.run(),/缺失资料/);assert.equal(f.state.writes.length,0)
})
test('unknown protected fields malformed IDs duplicate and unsorted sets reject before POST',async()=>{
 for(const change of [d=>{d.status='OPEN'},d=>{d.note='private'},d=>{d.assigneeId=actor},d=>{delete d.runCaseIds},d=>{d.runCaseIds=['invalid']},d=>{d.runCaseIds=[peer,peer]},d=>{d.runCaseIds=[otherPeer,peer]}]){const f=fixture();change(f.rows.local[0].definition);await assert.rejects(f.client.preview(sourceId,targetId,server,pairs));assert.equal(f.state.writes.length,0)}
})
test('OS encrypted store detects corruption and refuses duplicate bases without overwriting data',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-defect-execution-sync-')),key=crypto.randomBytes(32),codec={encrypt:async value=>{const iv=crypto.randomBytes(12),c=crypto.createCipheriv('aes-256-gcm',key,iv);return Buffer.concat([iv,c.update(value,'utf8'),c.final(),c.getAuthTag()])},decrypt:async bytes=>{const c=crypto.createDecipheriv('aes-256-gcm',key,bytes.subarray(0,12));c.setAuthTag(bytes.subarray(-16));return Buffer.concat([c.update(bytes.subarray(12,-16)),c.final()]).toString()}}
 try{const f=fixture();await f.bind();const store=new FunctionalDefectExecutionSyncStore(directory,codec);await store.write(f.client.state);assert.deepEqual(await store.read(),f.client.state);const bytes=await fs.readFile(store.file);assert.ok(!bytes.includes(Buffer.from('BUG-1')));assert.equal((await fs.stat(store.file)).mode&0o777,0o600);const bad=copy(f.client.state);bad.bases.push(copy(bad.bases[0]));await assert.rejects(store.write(bad),/基线重复/);assert.deepEqual(await fs.readFile(store.file),bytes);bytes[20]^=1;await fs.writeFile(store.file,bytes);await assert.rejects(store.read(),/无法解密/)}finally{key.fill(0);await fs.rm(directory,{recursive:true,force:true})}
})
test('native IPC rejects untrusted frames and absent metadata never starts a backend',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-execution-ipc-'));let starts=0
 try{const handlers=new Map(),setup={webContents:{mainFrame:{url:'testhub-desktop://setup/settings-sync.html'}}},c=installFunctionalDefectExecutionSync({ipcMain:{handle:(n,h)=>handlers.set(n,h)},windows:()=>({setup}),origin:'testhub-desktop://setup',directory,runtime:async()=>{starts++;throw Error('must not start')},available:()=>true});assert.throws(()=>handlers.get('testhub-desktop:functional-defect-execution-sync:status')({sender:setup.webContents,senderFrame:setup.webContents.mainFrame}),/不受信任/);await c.tick();assert.equal(starts,0);c.stop()}finally{await fs.rm(directory,{recursive:true,force:true})}
})
test('HTTP transport hides raw traces and enforces four MiB response limit',async()=>{
 const profile={label:'缺陷人员',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'));await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})
test('trusted preload exposes execution controls while remote pages can only open the native tool',async()=>{
 const vm=require('node:vm');for(const [file,count]of [['setup-preload.cjs',9],['remote-preload.cjs',1]]){let bridge;vm.runInNewContext(await fs.readFile(path.join(__dirname,'../app/src',file),'utf8'),{require:name=>{assert.equal(name,'electron');return {contextBridge:{exposeInMainWorld:(name,v)=>{assert.equal(name,'testhubDesktop');bridge=v}},ipcRenderer:{invoke:(...args)=>args}}},process:{env:{},argv:[]}});const names=Object.keys(bridge).filter(n=>/FunctionalDefectExecutionSync|functionalDefectExecutionSync/.test(n));assert.equal(names.length,count);assert.deepEqual(Array.from(bridge.openFunctionalDefectExecutionSync()),['testhub-desktop:functional-defect-execution-sync:open']);if(count===1)assert.equal(bridge.runFunctionalDefectExecutionSync,undefined)}
})
