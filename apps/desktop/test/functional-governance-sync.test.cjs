'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalGovernanceSync, FunctionalGovernanceSyncStore, keyOf, empty, validateState } = require('../app/src/functional-governance-sync.cjs')
const { installFunctionalGovernanceSync } = require('../app/src/functional-governance-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, description) => ({kind:'FIELD_PLAN',name,definition:{title:description,fieldType:'TEXT',required:false,options:[]}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('local', '本地原值')], remote: [item('remote', '网页原值')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify(rows[side])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_GOVERNANCE_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), excludedBindings: 0 }
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
  const client = new FunctionalGovernanceSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.name === key); assert.ok(item); item.definition.title = value }
  return { state, rows, store, client, options, bind, edit }
}
test('preview is read only, matching names merge once and later identical rounds do not write', async () => {
  const f = fixture(); await f.client.run(); assert.equal(f.state.writes.length, 0)
  const p = await f.client.preview(sourceId, targetId, server); assert.equal(p.upload, 1); assert.equal(p.download, 1); assert.equal(f.state.writes.length, 0)
  await f.client.bind(p.token); await f.client.run(); assert.equal(f.rows.local.length, 2); assert.equal(f.rows.remote.length, 2)
  const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count)
})
test('same name different content requires choice and two-sided edits reject stale human decisions', async () => {
  const f = fixture(); f.rows.remote.push(item('local', '网页版本')); await f.bind()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, true)
  await f.client.resolve(keyOf(item('local','')), 'local'); assert.equal(f.rows.remote.find(i => i.name === 'local').definition.title, '本地原值')
  f.edit('local', 'local', '本地冲突'); f.edit('remote', 'local', '网页冲突'); await f.client.run(); f.edit('remote', 'local', '网页再次修改')
  await assert.rejects(f.client.resolve(keyOf(item('local','')), 'local'), /已变化/); await f.client.run(); await f.client.resolve(keyOf(item('local','')), 'remote')
  assert.equal(f.rows.local.find(i => i.name === 'local').definition.title, '网页再次修改')
})
test('offline editing and independent web changes reconcile both ways on reconnect', async () => {
  const f = fixture(); await f.bind(); f.state.offline = true; f.edit('local', 'local', '离线正文'); f.rows.local.push(item('new', '离线新增'))
  await assert.rejects(f.client.run(), /offline/); f.edit('remote', 'remote', '网页正文'); f.rows.remote.push(item('web', '网页新增'))
  f.state.offline = false; await f.client.run(); assert.equal(f.rows.remote.find(i => i.name === 'local').definition.title, '离线正文')
  assert.equal(f.rows.local.find(i => i.name === 'remote').definition.title, '网页正文'); assert.ok(f.rows.local.some(i => i.name === 'web')); assert.ok(f.rows.remote.some(i => i.name === 'new'))
})
test('a committed response lost across restart retries the exact encrypted durable request once', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('new', '新增')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false; const next = new FunctionalGovernanceSync(f.options); await next.run(); assert.equal(f.rows.remote.length, count); assert.equal(next.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('target revision rejects the complete batch and a later round shows concurrent changes', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'local', '本地修改'); f.rows.local.push(item('new', '新增'))
  f.state.onCsrf = () => { f.edit('remote', 'local', '网页并发'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /revision/); assert.equal(f.client.state.pending, null); assert.ok(!f.rows.remote.some(i => i.name === 'new'))
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.ok(f.rows.remote.some(i => i.name === 'new'))
})
test('catalog path spelling differences require manual correction', async () => {
  const f=fixture();f.rows.local.push(catalog('GOVERNANCE',['Name'],1));f.rows.remote.push(catalog('GOVERNANCE',['name'],2));await f.bind()
  const c=f.client.state.conflicts.find(c=>c.local?.kind==='CATALOG_GOVERNANCE');assert.equal(c.canResolve,false)
  await assert.rejects(f.client.resolve(c.key,'local'))
})
test('deletion and rename preserve the other side without silently recreating or deleting', async () => {
  const f = fixture(); await f.bind(); f.rows.remote = f.rows.remote.filter(i => i.name !== 'local'); await f.client.run()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, false); assert.ok(f.rows.local.some(i => i.name === 'local'))
  assert.ok(!f.rows.remote.some(i => i.name === 'local'))
})
test('account, server, csrf races and failed durable writes prevent outgoing edits', async () => {
  for (const change of [f => { f.state.actor = 'E'.repeat(26) }, f => { f.state.server = 'https://other.test' }, f => { f.state.onCsrf = () => { f.state.actor = 'E'.repeat(26) } }, f => { f.state.failSave = true }]) {
    const f = fixture(); await f.bind(); f.rows.local.push(item('private', '不得发送')); change(f); await assert.rejects(f.client.run()); assert.ok(!f.rows.remote.some(i => i.name === 'private'))
  }
})
test('pause, restore and unbind preserve business data and require a fresh binding after restore', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('private', '尚未发送')); await f.client.enabled(false); const count = f.state.writes.length
  await f.client.run(); assert.equal(f.state.writes.length, count); await f.client.enabled(true); await f.client.suspendForRestore(); await f.client.run()
  assert.equal(f.state.writes.length, count); await assert.rejects(f.client.enabled(true), /恢复过备份/); await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 3)
})
test('merged count and missing baseline budgets reject before any POST', async () => {
  const f = fixture(); const p = await f.client.preview(sourceId, targetId, server); f.edit('local', 'local', '预览后修改'); await assert.rejects(f.client.bind(p.token), /已变化/)
  await f.bind(); f.rows.local.push(...Array.from({ length: 98 }, (_, n) => item('nn' + n, 'x'))); await f.client.run()
  f.rows.local = f.rows.local.filter(i => !/^nn[0-9]$/.test(i.name)); f.rows.remote = f.rows.remote.filter(i => !/^nn[0-9]$/.test(i.name)); f.rows.local.push(item('over', '超额'))
  const count = f.state.writes.length; await assert.rejects(f.client.run(), /缺失资料/); assert.equal(f.state.writes.length, count)
})
test('encrypted store hides asset content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-functional-governance-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new FunctionalGovernanceSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('本地原值'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const bad = copy(f.client.state); bad.bases.push(copy(bad.bases[0])); await assert.rejects(store.write(bad), /基线重复/)
    const unsafe = copy(f.client.state); unsafe.bases[0].executionHistory = 'must not persist'; await assert.rejects(store.write(unsafe), /资料无效/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('native frame remains isolated and absent metadata does not start local services', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-performance-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
    const c = installFunctionalGovernanceSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:functional-governance-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})

const catalog=(scope,parts,sortOrder=0)=>({kind:'CATALOG_'+scope,name:JSON.stringify(parts),definition:{sortOrder}})

const field=(type,key,title='字段',options=['交易','会员'])=>({kind:'FIELD_'+type,name:key,definition:{title,fieldType:'SELECT',required:true,options}})
const tag=(name,color='arcoblue')=>({kind:'TAG',name,definition:{color}})
test('same field keys are isolated by asset type and select option ordering is significant',async()=>{
 const f=fixture();f.rows.local.push(field('REQUIREMENT','business_domain'),field('CASE','business_domain'),field('PLAN','business_domain'));await f.bind()
 assert.equal(f.rows.remote.filter(i=>i.name==='business_domain').length,3)
 f.rows.local.find(i=>i.kind==='FIELD_CASE').definition.options.reverse();await f.client.run();assert.deepEqual(f.rows.remote.find(i=>i.kind==='FIELD_CASE').definition.options,['会员','交易'])
 assert.deepEqual(f.rows.remote.find(i=>i.kind==='FIELD_REQUIREMENT').definition.options,['交易','会员'])
})
test('tag color editing and field type changes sync independently of governance folder conflicts',async()=>{
 const f=fixture();f.rows.local.push(catalog('GOVERNANCE',['治理'],1),tag('核心','#336699'),field('CASE','domain'));f.rows.remote.push(catalog('GOVERNANCE',['治理'],2));await f.bind()
 assert.ok(f.rows.remote.some(i=>i.kind==='TAG'));assert.ok(f.rows.remote.some(i=>i.kind==='FIELD_CASE'))
 f.rows.local.find(i=>i.kind==='TAG').definition.color='green';Object.assign(f.rows.local.find(i=>i.kind==='FIELD_CASE').definition,{fieldType:'TEXT',options:[],required:false});await f.client.run()
 assert.equal(f.rows.remote.find(i=>i.kind==='TAG').definition.color,'green');assert.equal(f.rows.remote.find(i=>i.kind==='FIELD_CASE').definition.fieldType,'TEXT')
})
test('conflicting parent pauses descendants until review without blocking dictionary entries',async()=>{
 const f=fixture();f.rows.local.push(catalog('GOVERNANCE',['治理'],1),catalog('GOVERNANCE',['治理','子目录']),tag('核心'));f.rows.remote.push(catalog('GOVERNANCE',['治理'],2));await f.bind()
 assert.equal(f.rows.remote.some(i=>i.name==='["治理","子目录"]'),false);assert.ok(f.rows.remote.some(i=>i.kind==='TAG'))
 const c=f.client.state.conflicts.find(c=>c.local?.name==='["治理"]');await f.client.resolve(c.key,'local');await f.client.run();assert.ok(f.rows.remote.some(i=>i.name==='["治理","子目录"]'))
})
test('deleted parent is never recreated by later descendants',async()=>{
 const f=fixture();f.rows.local.push(catalog('GOVERNANCE',['治理']));await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.kind!=='CATALOG_GOVERNANCE');f.rows.local.push(catalog('GOVERNANCE',['治理','子目录']));await f.client.run();assert.equal(f.rows.remote.some(i=>i.kind==='CATALOG_GOVERNANCE'),false)
})
test('invalid field definitions and noncanonical or dangling directories fail before sending',async()=>{
 const invalid=[field('CASE','UPPER'),field('CASE','domain','字段',['重复','重复']),field('CASE','domain','字段',[]),field('CASE','domain','字段',Array.from({length:101},(_,n)=>'选项'+n)),tag('标签','UPPER'),catalog('GOVERNANCE',Array(17).fill('目录')),{kind:'CATALOG_GOVERNANCE',name:'[ "目录" ]',definition:{sortOrder:0}},catalog('GOVERNANCE',['缺父','子'])]
 const unknown=field('CASE','unknown');unknown.definition.filledValues=['private'];invalid.push(unknown)
 for(const row of invalid){const f=fixture();f.rows.local.push(row);await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)}
})
test('project links pause only affected field definitions and never send local references',async()=>{
 const f=fixture();f.rows.local.push(field('CASE','linked','字段',['/api/v1/projects/x/files/x']),tag('无关'));await f.bind()
 assert.ok(f.rows.remote.some(i=>i.kind==='TAG'));assert.equal(f.rows.remote.some(i=>i.name==='linked'),false);assert.equal(f.state.writes.some(w=>JSON.stringify(w.body).includes('/files/')),false)
})
test('governance response limit and HTTP failures keep backend internals private',async()=>{
 const profile={label:'治理定义',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private backend trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'))
 await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})
test('UTF-8 merge size is bounded independently of per-kind counts and character limits',async()=>{
 const f=fixture();f.rows.local=[];f.rows.remote=[];const options=Array.from({length:100},(_,n)=>String(n).padStart(3,'0')+'字'.repeat(60))
 for(const type of ['REQUIREMENT','CASE','PLAN'])for(let n=0;n<60;n++)f.rows[n<30?'local':'remote'].push(field(type,'large_'+n,'大定义',options))
 await assert.rejects(f.client.preview(sourceId,targetId,server),/3 MiB/);assert.equal(f.state.writes.length,0)
})
