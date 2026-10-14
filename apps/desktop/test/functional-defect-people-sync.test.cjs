'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalDefectPeopleSync, FunctionalDefectPeopleSyncStore, keyOf, empty, validateState } = require('../app/src/functional-defect-people-sync.cjs')
const { installFunctionalDefectPeopleSync } = require('../app/src/functional-defect-people-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const peer = 'E'.repeat(26), otherPeer = 'F'.repeat(26), remotePeer = 'G'.repeat(26), remoteOther = 'H'.repeat(26)
const member=(id,account)=>({id,account,email:account+'@example.test',name:'同名成员'})
const localMembers=[member(localActor,'local-owner'),member(peer,'local-peer'),member(otherPeer,'local-other')]
const remoteMembers=[member(actor,'web-owner'),member(remotePeer,'web-peer'),member(remoteOther,'web-other')]
const pairs=localMembers.map((m,n)=>({localId:m.id,remoteId:remoteMembers[n].id}))
const item=(name,assigneeId=null,ccUserIds=[])=>({kind:'DEFECT',name,definition:{assigneeId,ccUserIds:[...ccUserIds].sort()}})
function fixture() {
  const state = { actor, server, members:{local:copy(localMembers),remote:copy(remoteMembers)}, unavailable:{local:[],remote:[]}, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('BUG-1'),item('BUG-2')], remote: [item('BUG-1'),item('BUG-2')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const identity = (side,i) => state.identities?.[side]?.[keyOf(i)] || crypto.createHash('sha256').update(side+keyOf(i)).digest('hex').slice(0,26).toUpperCase()
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side],state.members[side],state.unavailable[side]])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_DEFECT_PEOPLE_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      members:copy(state.members[side]),unavailable:copy(state.unavailable[side]),revision: stamp(side), items: copy(rows[side]), itemIds: Object.fromEntries(rows[side].map(i => [i.kind+'\n'+i.name.toLowerCase(), identity(side,i)])) }
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
  const client = new FunctionalDefectPeopleSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server,pairs); await client.bind(p.token); await client.run() }
  const edit = (side,key,value,cc=[]) => {const row=rows[side].find(i=>i.name===key);assert.ok(row);row.definition={assigneeId:value,ccUserIds:[...cc].sort()}}
  return { state, rows, store, client, options, bind, edit }
}
test('member preview is read only and manually paired equal assignments establish bases',async()=>{
 const f=fixture();const members=await f.client.members(sourceId,targetId,server);assert.deepEqual(members.local,localMembers);await f.bind();assert.equal(f.state.writes.length,0);assert.equal(f.client.state.bases.length,2)
})
test('one-to-one pairing rejects duplicate local or remote members and unknown ids',async()=>{
 for(const value of [[pairs[0],pairs[0]],[pairs[0],{localId:peer,remoteId:actor}],[{localId:'Z'.repeat(26),remoteId:actor}],[]]){const f=fixture();await assert.rejects(f.client.preview(sourceId,targetId,server,value));assert.equal(f.state.writes.length,0)}
})
test('local ids translate to remote member ids and remote ids translate back',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',peer,[localActor,otherPeer]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition,{assigneeId:remotePeer,ccUserIds:[actor,remoteOther].sort()});f.edit('remote','BUG-2',remoteOther,[remotePeer]);await f.client.run();assert.deepEqual(f.rows.local[1].definition,{assigneeId:otherPeer,ccUserIds:[peer]})
})
test('explicit null and empty cc propagate as complete user changes',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',localActor,[peer]);await f.client.run();f.edit('remote','BUG-1',null,[]);await f.client.run();assert.deepEqual(f.rows.local[0].definition,{assigneeId:null,ccUserIds:[]})
})
test('offline local and independent web edits reconcile in both directions',async()=>{
 const f=fixture();await f.bind();f.state.offline=true;f.edit('local','BUG-1',peer);await assert.rejects(f.client.run(),/offline/);f.edit('remote','BUG-2',remoteOther,[actor]);f.state.offline=false;await f.client.run();assert.equal(f.rows.remote[0].definition.assigneeId,remotePeer);assert.equal(f.rows.local[1].definition.assigneeId,otherPeer)
})
test('whole people set conflict requires human review and stale choice is rejected',async()=>{
 const f=fixture();f.edit('remote','BUG-1',actor);await f.bind();assert.equal(f.client.state.conflicts.length,1);await f.client.resolve(keyOf(item('BUG-1')),'local');await f.client.run();f.edit('local','BUG-1',peer,[localActor]);f.edit('remote','BUG-1',remoteOther,[remotePeer]);await f.client.run();f.edit('remote','BUG-1',actor);await assert.rejects(f.client.resolve(keyOf(item('BUG-1')),'local'),/已变化/);await f.client.run();await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.equal(f.rows.local[0].definition.assigneeId,localActor)
})
test('unmapped people block their defects while mapped independent defects still sync',async()=>{
 const f=fixture();const p=await f.client.preview(sourceId,targetId,server,[pairs[0]]);await f.client.bind(p.token);await f.client.run();f.edit('local','BUG-1',peer);f.edit('local','BUG-2',localActor);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.equal(f.rows.remote[0].definition.assigneeId,null);assert.equal(f.rows.remote[1].definition.assigneeId,actor)
})
test('removed member blocks even a clear by checking previous baseline dependencies',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',peer);await f.client.run();f.state.members.remote=f.state.members.remote.filter(m=>m.id!==remotePeer);f.edit('local','BUG-1',null);f.edit('remote','BUG-1',null);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.equal(f.client.state.bases[0].definition.assigneeId,peer)
})
test('account rename identity change or replacement id cannot inherit a pairing',async()=>{
 for(const mutate of [m=>{m.account='renamed'},m=>{m.email='changed@example.test'},m=>{m.id='Z'.repeat(26)}]){const f=fixture();await f.bind();f.edit('local','BUG-1',peer);mutate(f.state.members.remote[1]);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.equal(f.rows.remote[0].definition.assigneeId,null)}
})
test('display name change alone keeps member identity and unavailable legacy reference pauses',async()=>{
 const f=fixture();await f.bind();f.state.members.remote[1].name='新显示名';f.edit('local','BUG-1',peer);await f.client.run();assert.equal(f.rows.remote[0].definition.assigneeId,remotePeer);f.state.unavailable.remote=['DEFECT\nbug-1'];f.edit('local','BUG-1',null);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.equal(f.rows.remote[0].definition.assigneeId,remotePeer)
})
test('missing and recreated defects require renewed review without automatic creation',async()=>{
 const f=fixture();await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.name!=='BUG-1');await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);f.rows.remote.push(item('BUG-1',actor));await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.equal(f.rows.local[0].definition.assigneeId,localActor)
})
test('same-content conflict choice is invalidated by recreated object identity',async()=>{
 const f=fixture();f.edit('remote','BUG-1',actor);await f.bind();f.state.identities={remote:{[keyOf(item('BUG-1'))]:'Z'.repeat(26)}};await assert.rejects(f.client.resolve(keyOf(item('BUG-1')),'local'),/已变化/);assert.equal(f.state.writes.length,0)
})
test('lost response restart replays exact raw mapped request and keeps later edits',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',peer);f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);const pending=copy(f.client.state.pending);assert.equal(pending.request.operations[0].item.definition.assigneeId,remotePeer);f.edit('remote','BUG-1',actor);f.state.loseResponse=false;const next=new FunctionalDefectPeopleSync(f.options);await next.run();assert.equal(next.state.pending,null);assert.equal(f.rows.local[0].definition.assigneeId,localActor);const attempts=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1])
})
test('membership changes during CSRF invalidate whole batch and clear stale pending',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',peer);f.edit('local','BUG-2',otherPeer);f.state.onCsrf=()=>{f.state.members.remote.pop();f.state.onCsrf=undefined};await assert.rejects(f.client.run(),/revision/);assert.equal(f.client.state.pending,null);assert.equal(f.rows.remote[0].definition.assigneeId,null)
})
test('account server and disk races prevent outgoing people writes',async()=>{
 for(const change of [f=>{f.state.actor='Z'.repeat(26)},f=>{f.state.server='https://other.test'},f=>{f.state.onCsrf=()=>{f.state.actor='Z'.repeat(26)}},f=>{f.state.failSave=true}]){const f=fixture();await f.bind();f.edit('local','BUG-1',peer);change(f);await assert.rejects(f.client.run());assert.equal(f.rows.remote[0].definition.assigneeId,null)}
})
test('pause restore and unbind keep assignments and require renewed pairing',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',peer);await f.client.enabled(false);await f.client.run();assert.equal(f.state.writes.length,0);await f.client.suspendForRestore();await assert.rejects(f.client.enabled(true),/恢复过备份/);await f.client.unbind();assert.equal(f.rows.local[0].definition.assigneeId,peer)
})
test('changed preview and union limit including missing baselines prevent POST',async()=>{
 const f=fixture();const p=await f.client.preview(sourceId,targetId,server,pairs);f.state.members.remote[0].account='renamed';await assert.rejects(f.client.bind(p.token),/已变化/);f.state.members.remote[0]=copy(remoteMembers[0]);await f.bind();for(let n=0;n<98;n++){f.rows.local.push(item('B-'+n));f.rows.remote.push(item('B-'+n))}await f.client.run();f.rows.local=f.rows.local.filter(i=>i.name!=='B-0');f.rows.remote=f.rows.remote.filter(i=>i.name!=='B-0');f.rows.local.push(item('B-OVER'));await assert.rejects(f.client.run(),/缺失资料/);assert.equal(f.state.writes.length,0)
})
test('protected workflow history unknown fields invalid IDs and duplicate cc are rejected',async()=>{
 for(const change of [d=>{d.status='OPEN'},d=>{d.assignedBy=actor},d=>{d.title='private'},d=>{delete d.assigneeId},d=>{d.assigneeId='invalid'},d=>{d.ccUserIds=[peer,peer]},d=>{d.ccUserIds=[peer,localActor]}]){const f=fixture();change(f.rows.local[0].definition);await assert.rejects(f.client.preview(sourceId,targetId,server,pairs));assert.equal(f.state.writes.length,0)}
})
test('OS encrypted store detects corruption and refuses duplicate bases without overwriting data',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-defect-people-sync-')),key=crypto.randomBytes(32),codec={encrypt:async value=>{const iv=crypto.randomBytes(12),c=crypto.createCipheriv('aes-256-gcm',key,iv);return Buffer.concat([iv,c.update(value,'utf8'),c.final(),c.getAuthTag()])},decrypt:async bytes=>{const c=crypto.createDecipheriv('aes-256-gcm',key,bytes.subarray(0,12));c.setAuthTag(bytes.subarray(-16));return Buffer.concat([c.update(bytes.subarray(12,-16)),c.final()]).toString()}}
 try{const f=fixture();await f.bind();const store=new FunctionalDefectPeopleSyncStore(directory,codec);await store.write(f.client.state);assert.deepEqual(await store.read(),f.client.state);const bytes=await fs.readFile(store.file);assert.ok(!bytes.includes(Buffer.from('BUG-1')));assert.equal((await fs.stat(store.file)).mode&0o777,0o600);const bad=copy(f.client.state);bad.bases.push(copy(bad.bases[0]));await assert.rejects(store.write(bad),/基线重复/);assert.deepEqual(await fs.readFile(store.file),bytes);bytes[20]^=1;await fs.writeFile(store.file,bytes);await assert.rejects(store.read(),/无法解密/)}finally{key.fill(0);await fs.rm(directory,{recursive:true,force:true})}
})
test('native IPC rejects untrusted frames and absent metadata never starts a backend',async()=>{
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-people-ipc-'));let starts=0
 try{const handlers=new Map(),setup={webContents:{mainFrame:{url:'testhub-desktop://setup/settings-sync.html'}}},c=installFunctionalDefectPeopleSync({ipcMain:{handle:(n,h)=>handlers.set(n,h)},windows:()=>({setup}),origin:'testhub-desktop://setup',directory,runtime:async()=>{starts++;throw Error('must not start')},available:()=>true});assert.throws(()=>handlers.get('testhub-desktop:functional-defect-people-sync:status')({sender:setup.webContents,senderFrame:setup.webContents.mainFrame}),/不受信任/);await c.tick();assert.equal(starts,0);c.stop()}finally{await fs.rm(directory,{recursive:true,force:true})}
})
test('HTTP transport hides raw traces and enforces four MiB response limit',async()=>{
 const profile={label:'缺陷人员',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'));await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})
test('trusted preload exposes people controls while remote pages can only open the native tool',async()=>{
 const vm=require('node:vm');for(const [file,count]of [['setup-preload.cjs',9],['remote-preload.cjs',1]]){let bridge;vm.runInNewContext(await fs.readFile(path.join(__dirname,'../app/src',file),'utf8'),{require:name=>{assert.equal(name,'electron');return {contextBridge:{exposeInMainWorld:(name,v)=>{assert.equal(name,'testhubDesktop');bridge=v}},ipcRenderer:{invoke:(...args)=>args}}},process:{env:{},argv:[]}});const names=Object.keys(bridge).filter(n=>/FunctionalDefectPeopleSync|functionalDefectPeopleSync/.test(n));assert.equal(names.length,count);assert.deepEqual(Array.from(bridge.openFunctionalDefectPeopleSync()),['testhub-desktop:functional-defect-people-sync:open']);if(count===1)assert.equal(bridge.runFunctionalDefectPeopleSync,undefined)}
})
