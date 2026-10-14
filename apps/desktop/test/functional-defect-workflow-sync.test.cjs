'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalDefectWorkflowSync, FunctionalDefectWorkflowSyncStore, keyOf, empty, validateState } = require('../app/src/functional-defect-workflow-sync.cjs')
const { installFunctionalDefectWorkflowSync } = require('../app/src/functional-defect-workflow-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, status='OPEN', confirmed=false, resolution=null) => ({kind:'DEFECT',name,definition:{status,confirmed,resolution}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('BUG-1'),item('BUG-2')], remote: [item('BUG-1'),item('BUG-2')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const identity = (side,i) => state.identities?.[side]?.[keyOf(i)] || crypto.createHash('sha256').update(side+keyOf(i)).digest('hex').slice(0,26).toUpperCase()
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify(rows[side])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_DEFECT_WORKFLOW_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), itemIds: Object.fromEntries(rows[side].map(i => [i.kind+'\n'+i.name.toLowerCase(), identity(side,i)])) }
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
  const client = new FunctionalDefectWorkflowSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.name === key); assert.ok(item); item.definition.status = value; item.definition.resolution = ['RESOLVED','CLOSED'].includes(value) ? 'FIXED' : null }
  return { state, rows, store, client, options, bind, edit }
}
test('read-only preview and equal workflow establish bases without business writes',async()=>{
 const f=fixture();await f.client.run();const p=await f.client.preview(sourceId,targetId,server);assert.deepEqual([p.upload,p.download,p.conflictCount],[0,0,0]);assert.equal(f.state.writes.length,0);await f.client.bind(p.token);await f.client.run();await f.client.run();assert.equal(f.state.writes.length,0);assert.equal(f.client.state.bases.length,2)
})
test('initial and two-sided differences require whole-workflow review and reject stale choices',async()=>{
 const f=fixture();f.edit('remote','BUG-1','CLOSED');await f.bind();assert.equal(f.client.state.conflicts.length,1);await f.client.resolve(keyOf(item('BUG-1')),'local');await f.client.run();f.edit('local','BUG-1','IN_PROGRESS');f.edit('remote','BUG-1','RESOLVED');await f.client.run();f.edit('remote','BUG-1','CLOSED');await assert.rejects(f.client.resolve(keyOf(item('BUG-1')),'local'),/已变化/);await f.client.run();await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.equal(f.rows.local[0].definition.status,'CLOSED')
})
test('offline state transitions and independent web edits reconcile both directions',async()=>{
 const f=fixture();await f.bind();f.state.offline=true;f.edit('local','BUG-1','IN_PROGRESS');await assert.rejects(f.client.run(),/offline/);f.edit('remote','BUG-2','RESOLVED');f.state.offline=false;await f.client.run();assert.equal(f.rows.remote[0].definition.status,'IN_PROGRESS');assert.equal(f.rows.local[1].definition.status,'RESOLVED')
})
test('confirmed=false and resolution clearing propagate without reverting the status bundle',async()=>{
 const f=fixture();await f.bind();f.rows.local[0].definition={status:'CLOSED',confirmed:true,resolution:'DUPLICATE'};await f.client.run();assert.deepEqual(f.rows.remote[0].definition,f.rows.local[0].definition);f.rows.local[0].definition={status:'OPEN',confirmed:false,resolution:null};await f.client.run();assert.deepEqual(f.rows.remote[0].definition,f.rows.local[0].definition)
})
test('missing defects block even at first bind and do not create or delete either endpoint',async()=>{
 const f=fixture();f.rows.remote=f.rows.remote.filter(i=>i.name!=='BUG-1');await f.bind();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.equal(f.rows.remote.length,1);assert.equal(f.rows.local.length,2);assert.equal(f.state.writes.length,0)
})
test('observed missing and restored differing workflow require persistent renewed review',async()=>{
 const f=fixture();await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.name!=='BUG-1');await f.client.run();f.rows.remote.push(item('BUG-1','CLOSED',true,'EXTERNAL'));await f.client.run();await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true);assert.equal(f.rows.local[0].definition.status,'OPEN');await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.equal(f.rows.local[0].definition.resolution,'EXTERNAL')
})
test('same-code recreation between polls is detected and unaffected defects still sync',async()=>{
 const f=fixture();await f.bind();f.state.identities={remote:{[keyOf(item('BUG-1'))]:'E'.repeat(26)}};f.edit('remote','BUG-1','CLOSED');f.edit('local','BUG-2','RESOLVED');await f.client.run();await f.client.run();assert.equal(f.rows.local[0].definition.status,'OPEN');assert.equal(f.rows.remote[1].definition.status,'RESOLVED');assert.equal(f.client.state.conflicts[0].canResolve,true)
})
test('identical workflows after recreation safely refresh identity and do not churn metadata',async()=>{
 const f=fixture();await f.bind();f.state.identities={remote:{[keyOf(item('BUG-1'))]:'E'.repeat(26)}};await f.client.run();assert.equal(f.client.state.conflicts.length,0);assert.equal(f.client.state.identities.remote['DEFECT\nbug-1'],'E'.repeat(26));assert.equal(f.state.writes.length,0)
})
test('response loss and restart replay exact durable receipt without undoing later changes',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1','RESOLVED');f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);const pending=copy(f.client.state.pending);f.edit('remote','BUG-1','CLOSED');f.state.loseResponse=false;const next=new FunctionalDefectWorkflowSync(f.options);await next.run();assert.equal(next.state.pending,null);assert.equal(f.rows.local[0].definition.status,'CLOSED');const attempts=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1])
})
test('receipt committed identity survives target recreation before retry',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1','RESOLVED');f.state.loseResponse=true;await assert.rejects(f.client.run());f.state.loseResponse=false;f.state.identities={remote:{[keyOf(item('BUG-1'))]:'F'.repeat(26)}};f.edit('remote','BUG-1','CLOSED');const next=new FunctionalDefectWorkflowSync(f.options);await next.run();assert.equal(f.rows.local[0].definition.status,'RESOLVED');assert.equal(next.state.conflicts[0].canResolve,true)
})
test('concurrent target edit rejects complete batch and clears obsolete pending request',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1','RESOLVED');f.edit('local','BUG-2','IN_PROGRESS');f.state.onCsrf=()=>{f.edit('remote','BUG-1','CLOSED');f.state.onCsrf=undefined};await assert.rejects(f.client.run(),/revision/);assert.equal(f.client.state.pending,null);assert.equal(f.rows.remote[1].definition.status,'OPEN');await f.client.run();assert.equal(f.client.state.conflicts.length,1);assert.equal(f.rows.remote[1].definition.status,'IN_PROGRESS')
})
test('account server CSRF race and durable disk failure prevent private outgoing transitions',async()=>{
 for(const change of [f=>{f.state.actor='E'.repeat(26)},f=>{f.state.server='https://other.test'},f=>{f.state.onCsrf=()=>{f.state.actor='E'.repeat(26)}},f=>{f.state.failSave=true}]){const f=fixture();await f.bind();f.edit('local','BUG-1','RESOLVED');change(f);await assert.rejects(f.client.run());assert.equal(f.rows.remote[0].definition.status,'OPEN')}
})
test('pause restore and unbind keep data and require renewed preview',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1','CLOSED');await f.client.enabled(false);await f.client.run();assert.equal(f.state.writes.length,0);await f.client.suspendForRestore();await assert.rejects(f.client.enabled(true),/恢复过备份/);await f.client.unbind();assert.equal(f.client.state.binding,null);assert.equal(f.rows.local[0].definition.status,'CLOSED')
})
test('changed preview and union limit including missing baselines reject before POST',async()=>{
 const f=fixture();const p=await f.client.preview(sourceId,targetId,server);f.edit('local','BUG-1','RESOLVED');await assert.rejects(f.client.bind(p.token),/已变化/);await f.bind();for(let n=0;n<98;n++){f.rows.local.push(item('B-'+n));f.rows.remote.push(item('B-'+n))}await f.client.run();f.rows.local=f.rows.local.filter(i=>i.name!=='B-0');f.rows.remote=f.rows.remote.filter(i=>i.name!=='B-0');f.rows.local.push(item('B-OVER'));const count=f.state.writes.length;await assert.rejects(f.client.run(),/缺失资料/);assert.equal(f.state.writes.length,count)
})
test('unknown people history links basic fields and malformed workflow are refused',async()=>{
 for(const change of [d=>{d.assigneeId=actor},d=>{d.ccUserIds=[]},d=>{d.resolvedAt='2026-01-01'},d=>{d.title='private'},d=>{d.status='open'},d=>{d.confirmed='false'},d=>{delete d.confirmed},d=>{d.resolution='FIXED'},d=>{d.status='CLOSED'},d=>{d.status='CLOSED';d.resolution='UNKNOWN'}]){const f=fixture();change(f.rows.local[0].definition);await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)}
 const f=fixture();f.rows.local[0].name='bug-1';await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)
})
test('OS encrypted store detects corruption and refuses duplicate bases without overwriting data',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-defect-workflow-sync-')),key=crypto.randomBytes(32),codec={encrypt:async value=>{const iv=crypto.randomBytes(12),c=crypto.createCipheriv('aes-256-gcm',key,iv);return Buffer.concat([iv,c.update(value,'utf8'),c.final(),c.getAuthTag()])},decrypt:async bytes=>{const c=crypto.createDecipheriv('aes-256-gcm',key,bytes.subarray(0,12));c.setAuthTag(bytes.subarray(-16));return Buffer.concat([c.update(bytes.subarray(12,-16)),c.final()]).toString()}}
 try{const f=fixture();await f.bind();const store=new FunctionalDefectWorkflowSyncStore(directory,codec);await store.write(f.client.state);assert.deepEqual(await store.read(),f.client.state);const bytes=await fs.readFile(store.file);assert.ok(!bytes.includes(Buffer.from('BUG-1')));assert.equal((await fs.stat(store.file)).mode&0o777,0o600);const bad=copy(f.client.state);bad.bases.push(copy(bad.bases[0]));await assert.rejects(store.write(bad),/基线重复/);assert.deepEqual(await fs.readFile(store.file),bytes);bytes[20]^=1;await fs.writeFile(store.file,bytes);await assert.rejects(store.read(),/无法解密/)}finally{key.fill(0);await fs.rm(directory,{recursive:true,force:true})}
})
test('native IPC rejects untrusted frames and absent metadata never starts a backend',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-workflow-ipc-'));let starts=0
 try{const handlers=new Map(),setup={webContents:{mainFrame:{url:'testhub-desktop://setup/settings-sync.html'}}},c=installFunctionalDefectWorkflowSync({ipcMain:{handle:(n,h)=>handlers.set(n,h)},windows:()=>({setup}),origin:'testhub-desktop://setup',directory,runtime:async()=>{starts++;throw Error('must not start')},available:()=>true});assert.throws(()=>handlers.get('testhub-desktop:functional-defect-workflow-sync:status')({sender:setup.webContents,senderFrame:setup.webContents.mainFrame}),/不受信任/);await c.tick();assert.equal(starts,0);c.stop()}finally{await fs.rm(directory,{recursive:true,force:true})}
})
test('HTTP transport hides raw traces and enforces four MiB response limit',async()=>{
 const profile={label:'缺陷工作流',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'));await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})
test('human choice is invalidated when same-content conflict refers to a recreated object',async()=>{
 const f=fixture();f.edit('remote','BUG-1','CLOSED');await f.bind();const c=f.client.state.conflicts[0];f.state.identities={remote:{[keyOf(item('BUG-1'))]:'E'.repeat(26)}};await assert.rejects(f.client.resolve(c.key,'local'),/已变化/);assert.equal(f.state.writes.length,0);await f.client.run();await f.client.resolve(c.key,'local');assert.equal(f.rows.remote[0].definition.status,'OPEN')
})
test('trusted preload exposes workflow controls while remote pages can only open the native tool',async()=>{
 const vm=require('node:vm');for(const [file,count]of [['setup-preload.cjs',9],['remote-preload.cjs',1]]){let bridge;vm.runInNewContext(await fs.readFile(path.join(__dirname,'../app/src',file),'utf8'),{require:name=>{assert.equal(name,'electron');return {contextBridge:{exposeInMainWorld:(name,v)=>{assert.equal(name,'testhubDesktop');bridge=v}},ipcRenderer:{invoke:(...args)=>args}}},process:{env:{},argv:[]}});const names=Object.keys(bridge).filter(n=>/FunctionalDefectWorkflowSync|functionalDefectWorkflowSync/.test(n));assert.equal(names.length,count);assert.deepEqual(Array.from(bridge.openFunctionalDefectWorkflowSync()),['testhub-desktop:functional-defect-workflow-sync:open']);if(count===1)assert.equal(bridge.runFunctionalDefectWorkflowSync,undefined)}
})
