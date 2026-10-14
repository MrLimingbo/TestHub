'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalAssetTagSync, FunctionalAssetTagSyncStore, keyOf, empty, validateState } = require('../app/src/functional-asset-tag-sync.cjs')
const { installFunctionalAssetTagSync } = require('../app/src/functional-asset-tag-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, tags = [], kind = 'CASE') => ({kind, name, definition: {tags: [...tags].sort()}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('CASE-1'),item('CASE-2')], remote: [item('CASE-1'),item('CASE-2')], tags: {local: ['A','B','C'],remote:['A','B','C']} }
  const identityOverrides = { local: {}, remote: {} }
  const identityFor = side => ({assetIds: Object.fromEntries(rows[side].map(i=>[i.kind+'\n'+i.name.toLowerCase(),identityOverrides[side][i.kind+'\n'+i.name] || crypto.createHash('sha256').update(i.kind+'\n'+i.name).digest('hex').slice(0,26).toUpperCase()])),tagIds: Object.fromEntries(rows.tags[side].map(t=>[t, identityOverrides[side][t] || crypto.createHash('sha256').update(t).digest('hex').slice(0,26).toUpperCase()]))})
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side],rows.tags[side],identityFor(side)])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_ASSET_TAG_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), availableTags: copy(rows.tags[side]), ...identityFor(side) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const e = new Error('revision or actor changed'); e.status = 409; throw e }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== stamp(side)) conflict()
      const next = copy(rows[side])
      result = { requestKey: body.requestKey, results: body.operations.map(op => {
        const index = next.findIndex(item => keyOf(item) === keyOf(op.item)); if (index < 0) next.push(copy(op.item)); else next[index] = copy(op.item)
        return { operationId: op.operationId, item: copy(op.item) }
      }) }; rows[side] = next; receipts[side].set(body.requestKey, copy(result))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async b => { if (b.server !== state.server) throw new Error('server changed') } }
  const client = new FunctionalAssetTagSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.name === key); assert.ok(item); item.definition.tags = [...value].sort() }
  return { state, rows, identityOverrides, store, client, options, bind, edit }
}
test('encrypted store hides asset content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-functional-asset-tag-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new FunctionalAssetTagSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('CASE-1'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const bad = copy(f.client.state); bad.bases.push(copy(bad.bases[0])); await assert.rejects(store.write(bad), /基线重复/)
    const unsafe = copy(f.client.state); unsafe.bases[0].executionHistory = 'must not persist'; await assert.rejects(store.write(unsafe), /资料无效/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('native frame remains isolated and absent metadata does not start local services', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-performance-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
    const c = installFunctionalAssetTagSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:functional-asset-tag-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})
test('preview never writes; initial differing sets need a deliberate choice',async()=>{
 const f=fixture();f.edit('local','CASE-1',['A']);await f.client.run();const p=await f.client.preview(sourceId,targetId,server);assert.equal(p.conflictCount,1);assert.equal(p.upload,0);assert.equal(f.state.writes.length,0)
 await f.client.bind(p.token);await f.client.run();await f.client.resolve(keyOf(item('CASE-1')),'local');assert.deepEqual(f.rows.remote[0].definition.tags,['A']);const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count)
})
test('offline edits reconcile both directions including clearing an established set',async()=>{
 const f=fixture();await f.bind();f.state.offline=true;f.edit('local','CASE-1',['A']);await assert.rejects(f.client.run(),/offline/);f.edit('remote','CASE-2',['B']);f.state.offline=false;await f.client.run();assert.deepEqual(f.rows.remote[0].definition.tags,['A']);assert.deepEqual(f.rows.local[1].definition.tags,['B']);f.edit('local','CASE-1',[]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.tags,[])
})
test('same code is isolated by asset type',async()=>{
 const f=fixture();for(const kind of ['REQUIREMENT','PLAN']){f.rows.local.push(item('CASE-1',[],kind));f.rows.remote.push(item('CASE-1',[],kind))}await f.bind();f.edit('local','CASE-1',['A']);await f.client.run();assert.ok(f.rows.remote.filter(i=>i.kind!=='CASE').every(i=>!i.definition.tags.length))
})
test('missing assets or tags pause only their associations and never create references',async()=>{
 const f=fixture();await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.name!=='CASE-1');f.edit('local','CASE-1',['A']);f.edit('local','CASE-2',['B']);await f.client.run();assert.equal(f.rows.remote.length,1);assert.deepEqual(f.rows.remote[0].definition.tags,['B']);assert.equal(f.client.state.conflicts[0].canResolve,false)
 const g=fixture();await g.bind();g.rows.tags.remote=['B','C'];g.edit('local','CASE-1',['A']);g.edit('local','CASE-2',['B']);await g.client.run();assert.deepEqual(g.rows.remote[0].definition.tags,[]);assert.deepEqual(g.rows.remote[1].definition.tags,['B']);assert.equal(g.client.state.conflicts[0].canResolve,false)
})
test('deleted or renamed dictionary entries never silently propagate a clear operation',async()=>{
 for(const renamed of [false,true]){const f=fixture();f.edit('local','CASE-1',['A']);f.edit('remote','CASE-1',['A']);await f.bind();f.rows.tags.local=renamed?['B','C','NEW']:['B','C'];f.edit('local','CASE-1',renamed?['NEW']:[]);await f.client.run();assert.deepEqual(f.rows.remote[0].definition.tags,['A']);assert.equal(f.client.state.conflicts[0].canResolve,false);await assert.rejects(f.client.resolve(f.client.state.conflicts[0].key,'local'))}
})
test('two-sided edits and late changes reject obsolete human choices',async()=>{
 const f=fixture();await f.bind();f.edit('local','CASE-1',['A']);f.edit('remote','CASE-1',['B']);await f.client.run();f.edit('remote','CASE-1',['C']);await assert.rejects(f.client.resolve(keyOf(item('CASE-1')),'local'),/已变化/);await f.client.run();await f.client.resolve(keyOf(item('CASE-1')),'remote');assert.deepEqual(f.rows.local[0].definition.tags,['C'])
})
test('commit response loss survives restart using the exact durable request',async()=>{
 const f=fixture();await f.bind();f.edit('local','CASE-1',['A']);f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);const pending=copy(f.client.state.pending);f.state.loseResponse=false;f.edit('remote','CASE-1',['B']);const next=new FunctionalAssetTagSync(f.options);await next.run();assert.equal(next.state.pending,null);assert.deepEqual(f.rows.local[0].definition.tags,['B']);const attempts=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(attempts.length,2);assert.deepEqual(attempts[0],attempts[1])
})
test('revision changes stop the complete batch and retain concurrent choices for review',async()=>{
 const f=fixture();await f.bind();f.edit('local','CASE-1',['A']);f.edit('local','CASE-2',['B']);f.state.onCsrf=()=>{f.edit('remote','CASE-1',['C']);f.state.onCsrf=undefined};await assert.rejects(f.client.run(),/revision/);assert.equal(f.client.state.pending,null);assert.deepEqual(f.rows.remote[1].definition.tags,[]);await f.client.run();assert.deepEqual(f.rows.remote[1].definition.tags,['B']);assert.equal(f.client.state.conflicts.length,1)
})
test('account server csrf races and disk errors prevent unconfirmed edits',async()=>{
 for(const change of [f=>{f.state.actor='E'.repeat(26)},f=>{f.state.server='https://other.test'},f=>{f.state.onCsrf=()=>{f.state.actor='E'.repeat(26)}},f=>{f.state.failSave=true}]){const f=fixture();await f.bind();f.edit('local','CASE-1',['A']);change(f);await assert.rejects(f.client.run());assert.deepEqual(f.rows.remote[0].definition.tags,[])}
})
test('pause restore and unbind preserve data and require preview again',async()=>{
 const f=fixture();await f.bind();f.edit('local','CASE-1',['A']);await f.client.enabled(false);const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count);await f.client.enabled(true);await f.client.suspendForRestore();await f.client.run();assert.equal(f.state.writes.length,count);await assert.rejects(f.client.enabled(true),/恢复过备份/);await f.client.unbind();assert.equal(f.client.state.binding,null);assert.deepEqual(f.rows.local[0].definition.tags,['A'])
})
test('preview expires on asset or dictionary drift and missing baselines count toward budget',async()=>{
 const f=fixture();const p=await f.client.preview(sourceId,targetId,server);f.rows.tags.remote.push('D');await assert.rejects(f.client.bind(p.token),/已变化/);await f.bind();for(let n=0;n<98;n++){f.rows.local.push(item('N-'+n));f.rows.remote.push(item('N-'+n))}await f.client.run();f.rows.local=f.rows.local.filter(i=>i.name!=='N-0');f.rows.remote=f.rows.remote.filter(i=>i.name!=='N-0');f.rows.local.push(item('N-NEW'));const count=f.state.writes.length;await assert.rejects(f.client.run(),/缺失资料/);assert.equal(f.state.writes.length,count)
})
test('strict schema sorted unique names and scoped references fail before sending',async()=>{
 for(const row of [item('case-1'),{...item('CASE-1'),definition:{tags:['B','A']}},{...item('CASE-1'),definition:{tags:['A','A']}},{...item('CASE-1'),definition:{tags:['A','a']}},{...item('CASE-1'),definition:{tags:['MISSING']}},{...item('CASE-1'),definition:{tags:[],history:'private'}}]){const f=fixture();f.rows.local=[row];await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)}
})
test('UTF8 merge budget and response byte cap are enforced separately from counts',async()=>{
 const f=fixture();const tags=Array.from({length:100},(_,n)=>String(n).padStart(3,'0')+'字'.repeat(60));f.rows.tags={local:tags,remote:tags};f.rows.local=[];f.rows.remote=[];for(const kind of ['REQUIREMENT','CASE','PLAN'])for(let n=0;n<100;n++){f.rows.local.push(item('N-'+n,n<50?tags:[],kind));f.rows.remote.push(item('N-'+n,n>=50?tags:[],kind))}await assert.rejects(f.client.preview(sourceId,targetId,server),/3 MiB/);assert.equal(f.state.writes.length,0);await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(4*1024*1024+1)),'https://example.test',{}, {label:'标签关联',limit:4*1024*1024}),/超过 4 MiB/)
})
test('restoring a deleted dictionary does not reinterpret deletion as an ordinary clearing edit',async()=>{
 const f=fixture();f.edit('local','CASE-1',['A']);f.edit('remote','CASE-1',['A']);await f.bind();f.rows.tags.remote=['B','C'];f.edit('remote','CASE-1',[]);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);f.rows.tags.remote=['A','B','C'];await f.client.run();assert.deepEqual(f.rows.local[0].definition.tags,['A']);assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(f.client.state.conflicts[0].key,'local');assert.deepEqual(f.rows.remote[0].definition.tags,['A'])
})
test('deleting and recreating an asset or tag between polls still requires explicit review',async()=>{
 for(const dependency of ['A','CASE\nCASE-1']){const f=fixture();f.edit('local','CASE-1',['A']);f.edit('remote','CASE-1',['A']);await f.bind();f.identityOverrides.remote[dependency]='Z'.repeat(26);f.edit('remote','CASE-1',[]);await f.client.run();assert.deepEqual(f.rows.local[0].definition.tags,['A']);assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(f.client.state.conflicts[0].key,'local');assert.deepEqual(f.rows.remote[0].definition.tags,['A'])}
})
