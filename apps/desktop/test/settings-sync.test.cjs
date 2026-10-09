'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { SettingsSync, SettingsSyncStore, keyOf, empty, validateState } = require('../app/src/settings-sync.cjs')
const { installSettingsSync } = require('../app/src/settings-sync-ipc.cjs')
const { syncRequest, installProjectSync, ensureSyncSession } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const env = (code = 'test', name = '测试环境') => ({ kind: 'ENVIRONMENT', environmentCode: code, key: null, name, baseUrl: 'https://example.test', description: '', value: null })
const param = (key, value, code = 'test') => ({ kind: 'PARAMETER', environmentCode: code, key, name: null, baseUrl: null, description: null, value })
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [env(), param('LOCAL', '本地原值')], remote: [env(), param('REMOTE', '网页原值')] }, blocked = { local: [], remote: [] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side], blocked[side]])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'PROJECT_SETTINGS_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), blockedKeys: copy(blocked[side]), excludedSecrets: blocked[side].length }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const e = new Error('revision or actor changed'); e.status = 409; throw e }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== stamp(side) || body.operations.some(op => blocked[side].includes(keyOf(op.item)))) conflict()
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
  const client = new SettingsSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.key === key); assert.ok(item); item.value = value }
  return { state, rows, blocked, store, client, options, bind, edit }
}
test('preview makes no writes; identical natural keys merge once and changes reuse the key', async () => {
  const f = fixture(); await f.client.run(); assert.equal(f.state.writes.length, 0)
  const preview = await f.client.preview(sourceId, targetId, server); assert.equal(preview.upload, 1); assert.equal(preview.download, 1); assert.equal(preview.conflictCount, 0)
  assert.equal(f.state.writes.length, 0); await f.client.bind(preview.token); await f.client.run()
  assert.equal(f.rows.local.length, 3); assert.equal(f.rows.remote.length, 3); assert.equal(f.client.state.bases.length, 3)
  const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count)
})
test('first bind preserves different same-key configurations as an explicit conflict', async () => {
  const f = fixture(); f.rows.remote.push(param('LOCAL', '网页不同值'))
  const p = await f.client.preview(sourceId, targetId, server); assert.equal(p.conflictCount, 1); assert.equal(p.upload, 0)
  await f.client.bind(p.token); await f.client.run(); assert.equal(f.rows.remote.find(i => i.key === 'LOCAL').value, '网页不同值')
  assert.equal(f.rows.local.find(i => i.key === 'LOCAL').value, '本地原值'); await f.client.resolve('P:test:local', 'local')
  assert.equal(f.rows.remote.find(i => i.key === 'LOCAL').value, '本地原值'); assert.equal(f.client.state.conflicts.length, 0)
})
test('offline edits, new dependent environment and independent web changes reconcile both ways', async () => {
  const f = fixture(); await f.bind(); f.state.offline = true; f.edit('local', 'LOCAL', '离线新值')
  f.rows.local.push(param('MODE', '离线新参数', 'offline'), env('offline', '离线环境')); await assert.rejects(f.client.run(), /offline/)
  f.edit('remote', 'REMOTE', '网页新值'); f.rows.remote.push(param('WEB', '网页新增')); f.state.offline = false; await f.client.run()
  assert.equal(f.rows.remote.find(i => i.key === 'MODE').value, '离线新参数'); assert.ok(f.rows.remote.some(i => i.kind === 'ENVIRONMENT' && i.environmentCode === 'offline'))
  assert.equal(f.rows.local.find(i => i.key === 'REMOTE').value, '网页新值'); assert.ok(f.rows.local.some(i => i.key === 'WEB'))
})
test('two-sided edits keep both versions and reject stale human decisions', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'LOCAL', '本地冲突'); f.edit('remote', 'LOCAL', '网页冲突')
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); f.edit('remote', 'LOCAL', '网页再次修改')
  const count = f.state.writes.length; await assert.rejects(f.client.resolve('P:test:local', 'local'), /已变化/); assert.equal(f.state.writes.length, count)
  await f.client.run(); await f.client.resolve('P:test:local', 'remote'); assert.equal(f.rows.local.find(i => i.key === 'LOCAL').value, '网页再次修改')
})
test('a committed response lost across restart retries the exact durable request once', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(param('NEW', '新增')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const p = copy(f.client.state.pending), count = f.rows.remote.length
  assert.ok(p); f.state.loseResponse = false; const next = new SettingsSync(f.options); await next.run(); assert.equal(f.rows.remote.length, count); assert.equal(next.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === p.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('target revision conflict rejects a whole pending batch and exposes later two-sided conflict', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'LOCAL', '本地变化'); f.rows.local.push(param('NEW', '整批新增'))
  f.state.onCsrf = () => { f.edit('remote', 'LOCAL', '并发网页'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /revision/); assert.equal(f.client.state.pending, null); assert.ok(!f.rows.remote.some(i => i.key === 'NEW'))
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.ok(f.rows.remote.some(i => i.key === 'NEW'))
})
test('same-key secrets, secret BASE_URL and case variants never get overwritten', async () => {
  const f = fixture(); f.blocked.remote.push('P:test:local'); f.rows.local.push(env('private', '私有环境'), param('MODE', '依赖普通参数', 'private')); f.blocked.remote.push('E:private')
  f.rows.remote.push(param('local', '网页大小写变体')); await f.bind()
  assert.equal(f.rows.remote.find(i => i.key === 'local').value, '网页大小写变体'); assert.ok(!f.rows.remote.some(i => i.environmentCode === 'private'))
  assert.ok(f.client.state.conflicts.every(c => !c.canResolve)); assert.equal(f.client.state.pending, null)
  await assert.rejects(f.client.resolve('P:test:local', 'local'))
  const g = fixture(); g.rows.remote.push(param('local', '仅大小写差异')); await g.bind()
  assert.equal(g.client.state.conflicts.find(c => c.key === 'P:test:local').canResolve, false)
})
test('deletion or secret conversion after binding preserves the other side instead of recreating', async () => {
  const f = fixture(); await f.bind(); f.rows.remote = f.rows.remote.filter(i => i.key !== 'LOCAL'); f.blocked.remote.push('P:test:local')
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, false)
  assert.ok(f.rows.local.some(i => i.key === 'LOCAL')); assert.ok(!f.rows.remote.some(i => i.key === 'LOCAL')); assert.equal(f.client.state.pending, null)
})
test('account/service/CSRF switches and failed durable writes prevent sending local changes', async () => {
  for (const change of [f => { f.state.actor = 'E'.repeat(26) }, f => { f.state.server = 'https://other.test' }, f => { f.state.onCsrf = () => { f.state.actor = 'E'.repeat(26) } }, f => { f.state.failSave = true }]) {
    const f = fixture(); await f.bind(); f.rows.local.push(param('PRIVATE', '不得发送')); change(f); await assert.rejects(f.client.run()); assert.ok(!f.rows.remote.some(i => i.key === 'PRIVATE'))
  }
})
test('pause, backup restore and unbind preserve business data and cannot replay old pending work', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(param('PRIVATE', '未发出')); await f.client.enabled(false)
  const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count); await f.client.enabled(true)
  await f.client.suspendForRestore(); await f.client.run(); assert.equal(f.state.writes.length, count); await assert.rejects(f.client.enabled(true), /恢复过备份/)
  await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 4); assert.equal(f.rows.remote.length, 3)
})
test('preview changes and merge capacity are rejected before binding or persisting a POST', async () => {
  const f = fixture(); const p = await f.client.preview(sourceId, targetId, server); f.edit('local', 'LOCAL', '预览后变化')
  await assert.rejects(f.client.bind(p.token), /已变化/); assert.equal(f.state.writes.length, 0)
  await f.bind(); f.rows.local.push(...Array.from({ length: 510 }, (_, i) => param('L' + i, 'x'.repeat(4000)))); f.rows.remote.push(...Array.from({ length: 510 }, (_, i) => param('R' + i, 'y')))
  await assert.rejects(f.client.run(), /超过/); assert.equal(f.client.state.pending, null)
  const g = fixture(); g.rows.local.push(...Array.from({ length: 160 }, (_, i) => param('L' + i, 'x'.repeat(20000)))); await assert.rejects(g.client.preview(sourceId, targetId, server), /3 MiB/)
})
test('encrypted store roundtrip keeps ordinary values private and rejects corruption and invalid mappings', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-settings-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new SettingsSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('本地原值'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const invalid = copy(f.client.state); invalid.bases.push(copy(invalid.bases[0])); await assert.rejects(store.write(invalid), /基线重复/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('deleted baseline entries still consume capacity before any new request is sent', async () => {
  const f = fixture(); f.rows.local.push(...Array.from({ length: 997 }, (_, i) => param('OLD' + i, '原值'))); await f.bind()
  for (const side of ['local', 'remote']) f.rows[side] = f.rows[side].filter(i => !/^OLD(?:[0-9]|[1-4][0-9])$/.test(i.key ?? ''))
  f.rows.local.push(...Array.from({ length: 50 }, (_, i) => param('NEW' + i, '新值')))
  const count = f.state.writes.length; await assert.rejects(f.client.run(), /待核对的缺失配置/)
  assert.equal(f.state.writes.length, count); assert.equal(f.client.state.pending, null)
})
test('settings transport has a separate response bound and keeps redirects and errors safe', async () => {
  const profile = { label: '项目配置', limit: 4 * 1024 * 1024 }
  assert.ok(await syncRequest(async () => new globalThis.Response(JSON.stringify({ data: 'x'.repeat(2 * 1024 * 1024) })), server, {}, profile))
  await assert.rejects(syncRequest(async () => new globalThis.Response(JSON.stringify({ data: 'x'.repeat(profile.limit) })), server, {}, profile), /4 MiB/)
  await assert.rejects(syncRequest(async () => new globalThis.Response('private detail', { status: 409 }), server, {}, profile), e => e.status === 409 && !e.message.includes('private detail'))
})
test('settings IPC rejects the task-sync frame and missing saved state never starts local runtime', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-settings-ipc-'))
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/task-sync.html' } } }; let starts = 0
    const controller = installSettingsSync({ ipcMain: { handle: (name, handler) => handlers.set(name, handler) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    const status = handlers.get('testhub-desktop:settings-sync:status')
    assert.throws(() => status({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await controller.tick(); assert.equal(starts, 0); controller.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})
test('loaded status stays readable during an exclusive write without restarting runtime or widening frame access', async () => {
  const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
  let locked = false, starts = 0
  class Client {
    async load() { this.state = { binding: { enabled: true } } }
    async status() { return copy(this.state) }
  }
  installProjectSync({ ipcMain: { handle: (name, handler) => handlers.set(name, handler) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory: '/unused',
    runtime: async () => { starts++; return { codec: {}, url: 'http://localhost:8080' } }, exclusive: async operation => {
      if (locked) throw new Error('busy'); locked = true; try { return await operation() } finally { locked = false }
    } }, { Client, Store: class {}, channel: 'settings-sync', page: 'settings-sync.html', title: '项目配置同步' })
  const status = handlers.get('testhub-desktop:settings-sync:status'), event = { sender: setup.webContents, senderFrame: setup.webContents.mainFrame }
  assert.equal((await status(event)).binding.enabled, true); locked = true
  assert.equal((await status(event)).binding.enabled, true); assert.equal(starts, 1)
  assert.throws(() => status({ sender: {}, senderFrame: setup.webContents.mainFrame }), /不受信任/)
})
test('sync keeps an authenticated local session and establishes one only for an anonymous response', async () => {
  let rotations = 0, options
  const runtime = { url: 'http://127.0.0.1:12345', authorizeSession: async () => { rotations++ } }
  await ensureSyncSession(runtime, async (url, init) => { assert.equal(url, runtime.url + '/api/v1/auth/me'); options = init; return new globalThis.Response('{}') })
  assert.equal(rotations, 0); assert.equal(options.credentials, 'include'); assert.equal(options.redirect, 'error')
  await ensureSyncSession(runtime, async () => new globalThis.Response('{}', { status: 401 })); assert.equal(rotations, 1)
  await assert.rejects(ensureSyncSession(runtime, async () => new globalThis.Response('private detail', { status: 403 })), /403/); assert.equal(rotations, 1)
})
