'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalDefectLinkSync, FunctionalDefectLinkSyncStore, keyOf, empty, validateState } = require('../app/src/functional-defect-link-sync.cjs')
const { installFunctionalDefectLinkSync } = require('../app/src/functional-defect-link-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, requirements = [], cases = []) => ({kind:'DEFECT', name, definition: {requirements: [...requirements].sort(), cases: [...cases].sort()}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false, unavailable:{local:[],remote:[]} }
  const rows = { local: [item('BUG-1'),item('BUG-2')], remote: [item('BUG-1'),item('BUG-2')], refs: {local: ['REQ-A','REQ-B','REQ-C','CASE-A'],remote:['REQ-A','REQ-B','REQ-C','CASE-A']} }
  const identityOverrides = { local: {}, remote: {} }
  const identityFor = side => ({referenceIds: Object.fromEntries([...rows[side].map(i=>i.kind+'\n'+i.name.toLowerCase()), ...rows.refs[side].map(code=>(code.startsWith('CASE-')?'CASE':'REQUIREMENT')+'\n'+code.toLowerCase())].map(key=>[key,identityOverrides[side][key] || crypto.createHash('sha256').update(key).digest('hex').slice(0,26).toUpperCase()]))})
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side],rows.refs[side],identityFor(side)])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_DEFECT_LINK_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), unavailable:copy(state.unavailable[side]), items: copy(rows[side]), ...identityFor(side) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const e = new Error('revision or actor changed'); e.status = 409; throw e }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== stamp(side)) conflict()
      const next = copy(rows[side])
      result = { requestKey: body.requestKey, ...identityFor(side), results: body.operations.map(op => {
        const index = next.findIndex(item => keyOf(item) === keyOf(op.item)); if (index < 0) next.push(copy(op.item)); else next[index] = copy(op.item)
        return { operationId: op.operationId, item: copy(op.item) }
      }) }; rows[side] = next; receipts[side].set(body.requestKey, copy(result))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async b => { if (b.server !== state.server) throw new Error('server changed') } }
  const client = new FunctionalDefectLinkSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.name === key); assert.ok(item); item.definition.requirements = [...value].sort() }
  return { state, rows, identityOverrides, store, client, options, bind, edit }
}
test('encrypted store hides asset content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-functional-defect-link-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new FunctionalDefectLinkSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('BUG-1'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const bad = copy(f.client.state); bad.bases.push(copy(bad.bases[0])); await assert.rejects(store.write(bad), /基线重复/)
    const unsafe = copy(f.client.state); unsafe.bases[0].executionHistory = 'must not persist'; await assert.rejects(store.write(unsafe), /资料无效/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('native frame remains isolated and absent metadata does not start local services', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-performance-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
    const c = installFunctionalDefectLinkSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:functional-defect-link-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})
test('preview never writes; initial differing sets need a deliberate choice',async()=>{
 const f=fixture();f.edit('local','BUG-1',['REQ-A']);await f.client.run();const p=await f.client.preview(sourceId,targetId,server);assert.equal(p.conflictCount,1);assert.equal(p.upload,0);assert.equal(f.state.writes.length,0)
 await f.client.bind(p.token);await f.client.run();await f.client.resolve(keyOf(item('BUG-1')),'local');assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-A']);const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count)
})
test('offline edits reconcile both directions including clearing an established set',async()=>{
 const f=fixture();await f.bind();f.state.offline=true;f.edit('local','BUG-1',['REQ-A']);await assert.rejects(f.client.run(),/offline/);f.edit('remote','BUG-2',['REQ-B']);f.state.offline=false;await f.client.run();assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-A']);assert.deepEqual(f.rows.local[1].definition.requirements,['REQ-B']);f.edit('local','BUG-1',[]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.requirements,[])
})
test('requirement and case sets sync independently under the same defect',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);f.rows.local[0].definition.cases=['CASE-A'];await f.client.run();assert.deepEqual(f.rows.remote[0].definition,{requirements:['REQ-A'],cases:['CASE-A']});f.edit('local','BUG-1',[]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition,{requirements:[],cases:['CASE-A']})
})
test('missing defects or references pause only their associations and never create references',async()=>{
 const f=fixture();await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.name!=='BUG-1');f.edit('local','BUG-1',['REQ-A']);f.edit('local','BUG-2',['REQ-B']);await f.client.run();assert.equal(f.rows.remote.length,1);assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-B']);assert.equal(f.client.state.conflicts[0].canResolve,false)
 const g=fixture();await g.bind();g.rows.refs.remote=['REQ-B','REQ-C'];g.edit('local','BUG-1',['REQ-A']);g.edit('local','BUG-2',['REQ-B']);await g.client.run();assert.deepEqual(g.rows.remote[0].definition.requirements,[]);assert.deepEqual(g.rows.remote[1].definition.requirements,['REQ-B']);assert.equal(g.client.state.conflicts[0].canResolve,false)
})
test('deleted or renamed references never silently propagate a clear operation',async()=>{
 for(const renamed of [false,true]){const f=fixture();f.edit('local','BUG-1',['REQ-A']);f.edit('remote','BUG-1',['REQ-A']);await f.bind();f.rows.refs.local=renamed?['REQ-B','REQ-C','REQ-NEW']:['REQ-B','REQ-C'];f.edit('local','BUG-1',renamed?['REQ-NEW']:[]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-A']);assert.equal(f.client.state.conflicts[0].canResolve,false);await assert.rejects(f.client.resolve(f.client.state.conflicts[0].key,'local'))}
})
test('two-sided edits and late changes reject obsolete human choices',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);f.edit('remote','BUG-1',['REQ-B']);await f.client.run();f.edit('remote','BUG-1',['REQ-C']);await assert.rejects(f.client.resolve(keyOf(item('BUG-1')),'local'),/已变化/);await f.client.run();await f.client.resolve(keyOf(item('BUG-1')),'remote');assert.deepEqual(f.rows.local[0].definition.requirements,['REQ-C'])
})
test('commit response loss survives restart using the exact durable request',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);const pending=copy(f.client.state.pending);f.state.loseResponse=false;f.edit('remote','BUG-1',['REQ-B']);const next=new FunctionalDefectLinkSync(f.options);await next.run();assert.equal(next.state.pending,null);assert.deepEqual(f.rows.local[0].definition.requirements,['REQ-B']);const attempts=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1])
})
test('revision changes stop the complete batch and retain concurrent choices for review',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);f.edit('local','BUG-2',['REQ-B']);f.state.onCsrf=()=>{f.edit('remote','BUG-1',['REQ-C']);f.state.onCsrf=undefined};await assert.rejects(f.client.run(),/revision/);assert.equal(f.client.state.pending,null);assert.deepEqual(f.rows.remote[1].definition.requirements,[]);await f.client.run();assert.deepEqual(f.rows.remote[1].definition.requirements,['REQ-B']);assert.equal(f.client.state.conflicts.length,1)
})
test('account server csrf races and disk errors prevent unconfirmed edits',async()=>{
 for(const change of [f=>{f.state.actor='E'.repeat(26)},f=>{f.state.server='https://other.test'},f=>{f.state.onCsrf=()=>{f.state.actor='E'.repeat(26)}},f=>{f.state.failSave=true}]){const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);change(f);await assert.rejects(f.client.run());assert.deepEqual(f.rows.remote[0].definition.requirements,[])}
})
test('pause restore and unbind preserve data and require preview again',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);await f.client.enabled(false);const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count);await f.client.enabled(true);await f.client.suspendForRestore();await f.client.run();assert.equal(f.state.writes.length,count);await assert.rejects(f.client.enabled(true),/恢复过备份/);await f.client.unbind();assert.equal(f.client.state.binding,null);assert.deepEqual(f.rows.local[0].definition.requirements,['REQ-A'])
})
test('preview expires on asset or dictionary drift and missing baselines count toward budget',async()=>{
 const f=fixture();const p=await f.client.preview(sourceId,targetId,server);f.rows.refs.remote.push('REQ-D');await assert.rejects(f.client.bind(p.token),/已变化/);await f.bind();for(let n=0;n<98;n++){f.rows.local.push(item('N-'+n));f.rows.remote.push(item('N-'+n))}await f.client.run();f.rows.local=f.rows.local.filter(i=>i.name!=='N-0');f.rows.remote=f.rows.remote.filter(i=>i.name!=='N-0');f.rows.local.push(item('N-NEW'));const count=f.state.writes.length;await assert.rejects(f.client.run(),/缺失资料/);assert.equal(f.state.writes.length,count)
})
test('strict schema sorted unique names and scoped references fail before sending',async()=>{
 for(const row of [item('bug-1'),{...item('BUG-1'),definition:{cases:[],requirements:['REQ-B','REQ-A']}},{...item('BUG-1'),definition:{cases:[],requirements:['REQ-A','REQ-A']}},{...item('BUG-1'),definition:{cases:[],requirements:['REQ-A','req-a']}},{...item('BUG-1'),definition:{cases:[],requirements:['REQ-MISSING']}},{...item('BUG-1'),definition:{cases:[],requirements:[],history:'private'}}]){const f=fixture();f.rows.local=[row];await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)}
})
test('response byte cap is enforced independently of reference counts',async()=>{
 await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(4*1024*1024+1)),'https://example.test',{}, {label:'缺陷关联',limit:4*1024*1024}),/超过 4 MiB/)
})
test('restoring a deleted reference does not reinterpret deletion as an ordinary clearing edit',async()=>{
 const f=fixture();f.edit('local','BUG-1',['REQ-A']);f.edit('remote','BUG-1',['REQ-A']);await f.bind();f.rows.refs.remote=['REQ-B','REQ-C'];f.edit('remote','BUG-1',[]);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);f.rows.refs.remote=['REQ-A','REQ-B','REQ-C','CASE-A'];await f.client.run();assert.deepEqual(f.rows.local[0].definition.requirements,['REQ-A']);assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(f.client.state.conflicts[0].key,'local');assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-A'])
})
test('deleting and recreating an defect or reference between polls still requires explicit review',async()=>{
 for(const dependency of ['REQUIREMENT\nreq-a','DEFECT\nbug-1','CASE\ncase-a']){const f=fixture();f.rows.local[0].definition.cases=['CASE-A'];f.rows.remote[0].definition.cases=['CASE-A'];f.edit('local','BUG-1',['REQ-A']);f.edit('remote','BUG-1',['REQ-A']);await f.bind();f.identityOverrides.remote[dependency]='Z'.repeat(26);f.edit('remote','BUG-1',[]);await f.client.run();assert.deepEqual(f.rows.local[0].definition.requirements,['REQ-A']);assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(f.client.state.conflicts[0].key,'local');assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-A'])}
})

test('receipt caches committed IDs across a lost response and subsequent recreation',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);f.state.loseResponse=false;f.identityOverrides.remote['REQUIREMENT\nreq-a']='Z'.repeat(26);f.edit('remote','BUG-1',[]);const next=new FunctionalDefectLinkSync(f.options);await next.run();assert.deepEqual(f.rows.local[0].definition.requirements,['REQ-A']);assert.equal(next.state.conflicts[0].canResolve,true);await next.run();assert.equal(next.state.conflicts[0].canResolve,true)
})
test('discard unused removed references while retaining dependencies of unresolved conflicts',async()=>{
 const f=fixture();await f.bind();for(let n=0;n<350;n++){f.rows.refs.local=['REQ-'+n];f.rows.refs.remote=['REQ-'+n];await f.client.run()}assert.equal(Object.keys(f.client.state.references.local).length,3);assert.ok(f.client.state.references.local['REQUIREMENT\nreq-349']);validateState(f.client.state)
})
test('receipt with missing committed reference IDs keeps the durable request',async()=>{
 const f=fixture();await f.bind();f.edit('local','BUG-1',['REQ-A']);const remote=f.client.remote;f.client.remote=async(...args)=>{const value=await remote(...args);if(args[1]?.method==='POST')delete value.referenceIds['REQUIREMENT\nreq-a'];return value};await assert.rejects(f.client.run(),/回执引用标识/);assert.ok(f.client.state.pending)
})

test('legacy dangling links block only their defect without deleting old data',async()=>{
 const f=fixture();await f.bind();f.state.unavailable.remote=['BUG-1'];f.edit('local','BUG-1',['REQ-A']);f.edit('local','BUG-2',['REQ-B']);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.requirements,[]);assert.deepEqual(f.rows.remote[1].definition.requirements,['REQ-B']);assert.equal(f.client.state.conflicts[0].canResolve,false);await assert.rejects(f.client.resolve(f.client.state.conflicts[0].key,'local'));f.state.unavailable.remote=[];await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(f.client.state.conflicts[0].key,'local');assert.deepEqual(f.rows.remote[0].definition.requirements,['REQ-A'])
})

test('multiple missing-reference waves retain all unresolved baseline identities within the bounded store',async()=>{
 const f=fixture();f.rows.local.push(item('BUG-3'));f.rows.remote.push(item('BUG-3'));const codes=prefix=>Array.from({length:100},(_,n)=>'REQ-'+prefix+'-'+String(n).padStart(3,'0'));
 for(const side of ['local','remote']){f.rows.refs[side]=codes('A');f.edit(side,'BUG-1',codes('A'))}await f.bind();
 for(const side of ['local','remote']){f.rows.refs[side]=codes('B');f.edit(side,'BUG-1',[]);f.edit(side,'BUG-2',codes('B'))}await f.client.run();
 for(const side of ['local','remote']){f.rows.refs[side]=codes('C');f.edit(side,'BUG-2',[]);f.edit(side,'BUG-3',codes('C'))}await f.client.run();assert.equal(f.client.state.conflicts.length,2);assert.ok(f.client.state.conflicts.every(c=>!c.canResolve));for(const prefix of ['a','b','c'])assert.ok(f.client.state.references.remote['REQUIREMENT\nreq-'+prefix+'-000']);assert.equal(f.state.writes.length,0);validateState(f.client.state)
})
