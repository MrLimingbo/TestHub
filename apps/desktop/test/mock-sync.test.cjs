'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { MockSync, MockSyncStore, keyOf, empty, validateState } = require('../app/src/mock-sync.cjs')
const { installMockSync } = require('../app/src/mock-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const parent = (serverName = 'QA') => ({ serverName, ruleName: null, rule: null })
const item = (name, body, serverName = 'QA') => ({ serverName, ruleName: name, rule: { name, httpMethod: 'GET', path: '/orders', priority: 0, requestQuery: {}, requestHeaders: {}, requestBodyMatch: 'ANY', requestBody: '', responseStatus: 200, responseHeaders: { 'Content-Type': 'application/json; charset=utf-8' }, responseBody: body } })
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [parent(), item('LOCAL', '本地原值')], remote: [parent(), item('REMOTE', '网页原值')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify(rows[side])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'MOCK_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), excludedPublicKeys: 0, excludedShares: 0 }
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
  const client = new MockSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.ruleName === key); assert.ok(item); item.rule.responseBody = value }
  return { state, rows, store, client, options, bind, edit }
}
test('preview is read only, matching names merge once and later identical rounds do not write', async () => {
  const f = fixture(); await f.client.run(); assert.equal(f.state.writes.length, 0)
  const p = await f.client.preview(sourceId, targetId, server); assert.equal(p.upload, 1); assert.equal(p.download, 1); assert.equal(f.state.writes.length, 0)
  await f.client.bind(p.token); await f.client.run(); assert.equal(f.rows.local.length, 3); assert.equal(f.rows.remote.length, 3)
  const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count)
})
test('same name different content requires choice and two-sided edits reject stale human decisions', async () => {
  const f = fixture(); f.rows.remote.push(item('LOCAL', '网页版本')); await f.bind()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, true)
  await f.client.resolve(keyOf(item('LOCAL', '')),  'local'); assert.equal(f.rows.remote.find(i => i.ruleName === 'LOCAL').rule.responseBody, '本地原值')
  f.edit('local', 'LOCAL', '本地冲突'); f.edit('remote', 'LOCAL', '网页冲突'); await f.client.run(); f.edit('remote', 'LOCAL', '网页再次修改')
  await assert.rejects(f.client.resolve(keyOf(item('LOCAL', '')),  'local'), /已变化/); await f.client.run(); await f.client.resolve(keyOf(item('LOCAL', '')),  'remote')
  assert.equal(f.rows.local.find(i => i.ruleName === 'LOCAL').rule.responseBody, '网页再次修改')
})
test('offline editing and independent web changes reconcile both ways on reconnect', async () => {
  const f = fixture(); await f.bind(); f.state.offline = true; f.edit('local', 'LOCAL', '离线正文'); f.rows.local.push(item('NEW', '离线新增'))
  await assert.rejects(f.client.run(), /offline/); f.edit('remote', 'REMOTE', '网页正文'); f.rows.remote.push(item('WEB', '网页新增'))
  f.state.offline = false; await f.client.run(); assert.equal(f.rows.remote.find(i => i.ruleName === 'LOCAL').rule.responseBody, '离线正文')
  assert.equal(f.rows.local.find(i => i.ruleName === 'REMOTE').rule.responseBody, '网页正文'); assert.ok(f.rows.local.some(i => i.ruleName === 'WEB')); assert.ok(f.rows.remote.some(i => i.ruleName === 'NEW'))
})
test('a committed response lost across restart retries the exact encrypted durable request once', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('NEW', '新增')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false; const next = new MockSync(f.options); await next.run(); assert.equal(f.rows.remote.length, count); assert.equal(next.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('target revision rejects the complete batch and a later round shows concurrent changes', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'LOCAL', '本地修改'); f.rows.local.push(item('NEW', '新增'))
  f.state.onCsrf = () => { f.edit('remote', 'LOCAL', '网页并发'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /revision/); assert.equal(f.client.state.pending, null); assert.ok(!f.rows.remote.some(i => i.ruleName === 'NEW'))
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.ok(f.rows.remote.some(i => i.ruleName === 'NEW'))
})
test('case differences on rule and server names never silently overwrite definitions', async () => {
  for (const [field, value] of [['ruleName', 'local'], ['serverName', 'qa']]) {
    const f = fixture(); const other = item('LOCAL', '网页版本'); other[field] = value
    if (field === 'ruleName') other.rule.name = value
    if (field === 'serverName') f.rows.remote[0].serverName = value
    f.rows.remote.push(other); if (field === 'serverName') f.rows.remote[1].serverName = value
    await f.bind(); const c = f.client.state.conflicts.find(c => c.key === keyOf(item('LOCAL', '')))
    assert.equal(c.canResolve, false); await assert.rejects(f.client.resolve(c.key, 'local'))
  }
})
test('deletion and rename preserve the other side without silently recreating or deleting', async () => {
  const f = fixture(); await f.bind(); f.rows.remote = f.rows.remote.filter(i => i.ruleName !== 'LOCAL'); await f.client.run()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, false); assert.ok(f.rows.local.some(i => i.ruleName === 'LOCAL'))
  assert.ok(!f.rows.remote.some(i => i.ruleName === 'LOCAL'))
})
test('account, server, csrf races and failed durable writes prevent outgoing edits', async () => {
  for (const change of [f => { f.state.actor = 'E'.repeat(26) }, f => { f.state.server = 'https://other.test' }, f => { f.state.onCsrf = () => { f.state.actor = 'E'.repeat(26) } }, f => { f.state.failSave = true }]) {
    const f = fixture(); await f.bind(); f.rows.local.push(item('PRIVATE', '不得发送')); change(f); await assert.rejects(f.client.run()); assert.ok(!f.rows.remote.some(i => i.ruleName === 'PRIVATE'))
  }
})
test('pause, restore and unbind preserve business data and require a fresh binding after restore', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('PRIVATE', '尚未发送')); await f.client.enabled(false); const count = f.state.writes.length
  await f.client.run(); assert.equal(f.state.writes.length, count); await f.client.enabled(true); await f.client.suspendForRestore(); await f.client.run()
  assert.equal(f.state.writes.length, count); await assert.rejects(f.client.enabled(true), /恢复过备份/); await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 4)
})
test('preview revision and merged count/byte budgets reject before any POST including missing baselines', async () => {
  const f = fixture(); const p = await f.client.preview(sourceId, targetId, server); f.edit('local', 'LOCAL', '预览后修改'); await assert.rejects(f.client.bind(p.token), /已变化/)
  await f.bind(); f.rows.local.push(...Array.from({ length: 198 }, (_, n) => item('N' + n, 'x'))); await f.client.run()
  f.rows.local = f.rows.local.filter(i => !/^N[0-9]$/.test(i.ruleName || '')); f.rows.remote = f.rows.remote.filter(i => !/^N[0-9]$/.test(i.ruleName || '')); f.rows.local.push(item('OVER', '超额'))
  const count = f.state.writes.length; await assert.rejects(f.client.run(), /缺失资料/); assert.equal(f.state.writes.length, count)
  const g = fixture(); g.rows.local.push(...Array.from({ length: 7 }, (_, n) => item('B' + n, 'x'.repeat(524288)))); await assert.rejects(g.client.preview(sourceId, targetId, server), /3 MiB/)
  const h = fixture(); h.rows.local.push(...Array.from({ length: 20 }, (_, n) => parent('server' + n))); await assert.rejects(h.client.preview(sourceId, targetId, server), /20 个服务/)
})
test('encrypted store hides template content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-mock-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new MockSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('本地原值'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const bad = copy(f.client.state); bad.bases.push(copy(bad.bases[0])); await assert.rejects(store.write(bad), /基线重复/)
    const unsafe = copy(f.client.state); unsafe.bases[0].publicKey = 'must not persist'; await assert.rejects(store.write(unsafe), /资料无效/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('native frame remains isolated and absent metadata does not start local services', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-mock-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/notification-sync.html' } } }
    const c = installMockSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:mock-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})
test('mock response bound and safe errors do not expose server internals', async () => {
  const profile = { label: 'Mock 资料', limit: 4 * 1024 * 1024 }
  await assert.rejects(syncRequest(async () => new globalThis.Response(JSON.stringify({ data: 'x'.repeat(profile.limit) })), server, {}, profile), /4 MiB/)
  await assert.rejects(syncRequest(async () => new globalThis.Response('private detail', { status: 409 }), server, {}, profile), e => e.status === 409 && !e.message.includes('private detail'))
})

test('different parent spelling prevents even one-sided new rules from entering an inconsistent server', async () => {
  const f=fixture();for(const i of f.rows.remote)i.serverName='qa'
  await f.bind();assert.equal(f.state.writes.length,0);assert.ok(f.client.state.conflicts.every(c=>!c.canResolve));assert.equal(f.client.state.pending,null)
})

test('deleting a baseline parent also blocks newly created child rules from recreating that parent', async () => {
  const f=fixture();await f.bind();f.rows.local=[];f.rows.remote.push(item('NEW','只在网页'))
  const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count);assert.equal(f.rows.local.length,0)
  assert.ok(f.client.state.conflicts.some(c=>c.key===keyOf(item('NEW',''))&&!c.canResolve))
})

test('map key order changes alone never produce a content conflict or outgoing update',async()=>{
  const f=fixture();const r=f.rows.local[1].rule;r.requestQuery={a:'1',b:'2'};r.responseHeaders={'Content-Type':'text/plain','X-First':'1','X-Second':'2'}
  await f.bind();const remote=f.rows.remote.find(i=>i.ruleName==='LOCAL').rule;remote.requestQuery={b:'2',a:'1'};remote.responseHeaders={'X-Second':'2','X-First':'1','Content-Type':'text/plain'}
  const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count);assert.equal(f.client.state.conflicts.length,0)
})
