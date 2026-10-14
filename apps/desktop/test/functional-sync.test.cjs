'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalSync, FunctionalSyncStore, keyOf, empty, validateState } = require('../app/src/functional-sync.cjs')
const { installFunctionalSync } = require('../app/src/functional-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, description) => ({kind:'PLAN',name,definition:{catalogPath:null,title:'测试计划',status:'DRAFT',description,caseCodes:[]}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('LOCAL', '本地原值')], remote: [item('REMOTE', '网页原值')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify(rows[side])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), excludedFiles: 0, excludedExecutions: 0 }
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
  const client = new FunctionalSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.name === key); assert.ok(item); item.definition.description = value }
  return { state, rows, store, client, options, bind, edit }
}
test('preview is read only, matching names merge once and later identical rounds do not write', async () => {
  const f = fixture(); await f.client.run(); assert.equal(f.state.writes.length, 0)
  const p = await f.client.preview(sourceId, targetId, server); assert.equal(p.upload, 1); assert.equal(p.download, 1); assert.equal(f.state.writes.length, 0)
  await f.client.bind(p.token); await f.client.run(); assert.equal(f.rows.local.length, 2); assert.equal(f.rows.remote.length, 2)
  const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count)
})
test('same name different content requires choice and two-sided edits reject stale human decisions', async () => {
  const f = fixture(); f.rows.remote.push(item('LOCAL', '网页版本')); await f.bind()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, true)
  await f.client.resolve(keyOf(item('LOCAL','')), 'local'); assert.equal(f.rows.remote.find(i => i.name === 'LOCAL').definition.description, '本地原值')
  f.edit('local', 'LOCAL', '本地冲突'); f.edit('remote', 'LOCAL', '网页冲突'); await f.client.run(); f.edit('remote', 'LOCAL', '网页再次修改')
  await assert.rejects(f.client.resolve(keyOf(item('LOCAL','')), 'local'), /已变化/); await f.client.run(); await f.client.resolve(keyOf(item('LOCAL','')), 'remote')
  assert.equal(f.rows.local.find(i => i.name === 'LOCAL').definition.description, '网页再次修改')
})
test('offline editing and independent web changes reconcile both ways on reconnect', async () => {
  const f = fixture(); await f.bind(); f.state.offline = true; f.edit('local', 'LOCAL', '离线正文'); f.rows.local.push(item('NEW', '离线新增'))
  await assert.rejects(f.client.run(), /offline/); f.edit('remote', 'REMOTE', '网页正文'); f.rows.remote.push(item('WEB', '网页新增'))
  f.state.offline = false; await f.client.run(); assert.equal(f.rows.remote.find(i => i.name === 'LOCAL').definition.description, '离线正文')
  assert.equal(f.rows.local.find(i => i.name === 'REMOTE').definition.description, '网页正文'); assert.ok(f.rows.local.some(i => i.name === 'WEB')); assert.ok(f.rows.remote.some(i => i.name === 'NEW'))
})
test('a committed response lost across restart retries the exact encrypted durable request once', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('NEW', '新增')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false; const next = new FunctionalSync(f.options); await next.run(); assert.equal(f.rows.remote.length, count); assert.equal(next.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('target revision rejects the complete batch and a later round shows concurrent changes', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'LOCAL', '本地修改'); f.rows.local.push(item('NEW', '新增'))
  f.state.onCsrf = () => { f.edit('remote', 'LOCAL', '网页并发'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /revision/); assert.equal(f.client.state.pending, null); assert.ok(!f.rows.remote.some(i => i.name === 'NEW'))
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.ok(f.rows.remote.some(i => i.name === 'NEW'))
})
test('catalog path spelling differences require manual correction', async () => {
  const f=fixture();f.rows.local.push(catalog('REQUIREMENTS',['Name'],1));f.rows.remote.push(catalog('REQUIREMENTS',['name'],2));await f.bind()
  const c=f.client.state.conflicts.find(c=>c.local?.kind==='CATALOG_REQUIREMENTS');assert.equal(c.canResolve,false)
  await assert.rejects(f.client.resolve(c.key,'local'))
})
test('deletion and rename preserve the other side without silently recreating or deleting', async () => {
  const f = fixture(); await f.bind(); f.rows.remote = f.rows.remote.filter(i => i.name !== 'LOCAL'); await f.client.run()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, false); assert.ok(f.rows.local.some(i => i.name === 'LOCAL'))
  assert.ok(!f.rows.remote.some(i => i.name === 'LOCAL'))
})
test('account, server, csrf races and failed durable writes prevent outgoing edits', async () => {
  for (const change of [f => { f.state.actor = 'E'.repeat(26) }, f => { f.state.server = 'https://other.test' }, f => { f.state.onCsrf = () => { f.state.actor = 'E'.repeat(26) } }, f => { f.state.failSave = true }]) {
    const f = fixture(); await f.bind(); f.rows.local.push(item('PRIVATE', '不得发送')); change(f); await assert.rejects(f.client.run()); assert.ok(!f.rows.remote.some(i => i.name === 'PRIVATE'))
  }
})
test('pause, restore and unbind preserve business data and require a fresh binding after restore', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('PRIVATE', '尚未发送')); await f.client.enabled(false); const count = f.state.writes.length
  await f.client.run(); assert.equal(f.state.writes.length, count); await f.client.enabled(true); await f.client.suspendForRestore(); await f.client.run()
  assert.equal(f.state.writes.length, count); await assert.rejects(f.client.enabled(true), /恢复过备份/); await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 3)
})
test('merged count and missing baseline budgets reject before any POST', async () => {
  const f = fixture(); const p = await f.client.preview(sourceId, targetId, server); f.edit('local', 'LOCAL', '预览后修改'); await assert.rejects(f.client.bind(p.token), /已变化/)
  await f.bind(); f.rows.local.push(...Array.from({ length: 98 }, (_, n) => item('N' + n, 'x'))); await f.client.run()
  f.rows.local = f.rows.local.filter(i => !/^N[0-9]$/.test(i.name)); f.rows.remote = f.rows.remote.filter(i => !/^N[0-9]$/.test(i.name)); f.rows.local.push(item('OVER', '超额'))
  const count = f.state.writes.length; await assert.rejects(f.client.run(), /缺失资料/); assert.equal(f.state.writes.length, count)
})
test('encrypted store hides asset content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-functional-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new FunctionalSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
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
    const c = installFunctionalSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:functional-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})

const catalog=(scope,parts,sortOrder=0)=>({kind:'CATALOG_'+scope,name:JSON.stringify(parts),definition:{sortOrder}})
const requirement=(title='需求')=>({kind:'REQUIREMENT',name:'REQ-1',definition:{catalogPath:'["需求","订单"]',title,priority:'P0',status:'APPROVED',description:''}})
const testcase=(name='CASE-1')=>({kind:'CASE',name,definition:{catalogPath:'["用例"]',requirementCode:'REQ-1',title:'用例',priority:'P1',status:'READY',caseType:'SECURITY',precondition:'',steps:'1. 登录\n2. 下单',expectedResult:'成功'}})
const plan=()=>({kind:'PLAN',name:'PLAN-1',definition:{catalogPath:'["计划"]',title:'回归',status:'READY',description:'',caseCodes:['CASE-2','CASE-1']}})
const graph=()=>[plan(),testcase(),testcase('CASE-2'),requirement(),catalog('REQUIREMENTS',['需求','订单']),catalog('REQUIREMENTS',['需求']),catalog('CASES',['用例']),catalog('PLANS',['计划'])]
test('nested portable graph preserves ordered associations and title edits under stable codes',async()=>{
 const f=fixture();f.rows.local.push(...graph());await f.bind();assert.deepEqual(f.rows.remote.find(i=>i.name==='PLAN-1').definition.caseCodes,['CASE-2','CASE-1'])
 f.rows.local.find(i=>i.name==='REQ-1').definition.title='改标题';await f.client.run();assert.equal(f.rows.remote.find(i=>i.name==='REQ-1').definition.title,'改标题')
 f.rows.local.find(i=>i.name==='PLAN-1').definition.caseCodes.reverse();await f.client.run();assert.deepEqual(f.rows.remote.find(i=>i.name==='PLAN-1').definition.caseCodes,['CASE-1','CASE-2'])
})
test('conflicting parent folder pauses nested folders requirements cases and plans until reviewed',async()=>{
 const f=fixture();f.rows.local.push(...graph());f.rows.remote.push(catalog('REQUIREMENTS',['需求'],5));await f.bind()
 for(const kind of ['REQUIREMENT','CASE'])assert.equal(f.rows.remote.some(i=>i.kind===kind),false)
 assert.equal(f.rows.remote.some(i=>i.name==='PLAN-1'),false);const c=f.client.state.conflicts.find(c=>c.local?.name==='["需求"]');await f.client.resolve(c.key,'local');await f.client.run();assert.ok(f.rows.remote.some(i=>i.name==='PLAN-1'))
})
test('project links pause only relevant assets and all their dependents without sending file references',async()=>{
 const f=fixture();f.rows.local.push(...graph());f.rows.local.find(i=>i.kind==='REQUIREMENT').definition.description='/api/v1/projects/'+sourceId+'/files/'+actor+'/download';await f.bind()
 assert.ok(f.rows.remote.some(i=>i.name==='LOCAL'));assert.equal(f.rows.remote.some(i=>['REQUIREMENT','CASE'].includes(i.kind)||i.name==='PLAN-1'),false)
 assert.ok(f.client.state.conflicts.some(c=>/文件链接/.test(c.reason)));assert.equal(f.state.writes.some(w=>JSON.stringify(w.body).includes('/files/')),false)
})
test('paired project file links sync in both directions and pause after their file mapping disappears', async () => {
  const f = fixture(), localFile = 'L'.repeat(26), remoteFile = 'R'.repeat(26), key = 'K'.repeat(26)
  const localPath = `/api/v1/projects/${sourceId}/files/${localFile}/download`
  const remotePath = `/api/v1/projects/${targetId}/files/${remoteFile}/download`
  const context = { sourceProject: sourceId, targetProject: targetId, links: [{ key, localId: localFile, remoteId: remoteFile }] }
  f.client.fileLinkContext = async () => context
  f.rows.local.push(item('LINKED', `附件 ${localPath}`))
  await f.bind()
  assert.equal(f.rows.remote.find(i => i.name === 'LINKED').definition.description, `附件 ${remotePath}`)
  const writes = f.state.writes.length
  await f.client.run(); assert.equal(f.state.writes.length, writes)
  f.edit('remote', 'LINKED', `已核对 ${remotePath}`)
  await f.client.run()
  assert.equal(f.rows.local.find(i => i.name === 'LINKED').definition.description, `已核对 ${localPath}`)
  context.links = []
  await f.client.run()
  assert.equal(f.state.writes.length, writes + 1)
  assert.equal(f.client.state.conflicts.find(c => c.local?.name === 'LINKED').canResolve, false)
})
test('same file links on both sides never establish an automatically writable baseline',async()=>{
 const f=fixture();const row=item('LINKED','/api/v1/projects/'+sourceId+'/files/'+actor+'/download');f.rows.local.push(row);f.rows.remote.push(copy(row));await f.bind()
 assert.equal(f.client.state.bases.some(i=>i.name==='LINKED'),false);assert.equal(f.client.state.conflicts.find(c=>c.local?.name==='LINKED').canResolve,false)
})
test('deleted directory cannot be recreated by later children',async()=>{
 const f=fixture();f.rows.local.push(catalog('REQUIREMENTS',['需求']));await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.kind!=='CATALOG_REQUIREMENTS');f.rows.local.push(catalog('REQUIREMENTS',['需求','订单']),requirement());await f.client.run()
 assert.equal(f.rows.remote.some(i=>i.kind==='REQUIREMENT'),false);assert.equal(f.rows.remote.some(i=>i.kind==='CATALOG_REQUIREMENTS'),false)
})
test('invalid numbering unknown fields duplicate or missing plan links and noncanonical folders fail before POST',async()=>{
 for(const mutate of [rows=>rows.push({...item('lower','x')}),rows=>{rows.push(...graph());rows.find(i=>i.name==='PLAN-1').definition.caseCodes=['CASE-1','CASE-1']},rows=>rows.push(requirement()),rows=>rows.push(catalog('CASES',Array(17).fill('目录'))),rows=>{rows.push(item('NEW','x'));rows.at(-1).definition.createdBy=actor},rows=>rows.push({kind:'CATALOG_CASES',name:'[ "目录" ]',definition:{sortOrder:0}})]){
 const f=fixture();mutate(f.rows.local);await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)
 }
})
test('functional response bound and HTTP failure keep raw internals out of errors',async()=>{
 const profile={label:'功能测试资料',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private backend trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'))
 await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})
