'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { TaskSync, TaskSyncStore } = require('../app/src/task-sync.cjs')
const { syncRequest, installTaskSync } = require('../app/src/task-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26)
const server = 'https://sync.example.test', copy = value => globalThis.structuredClone(value)
let sequence = 1
const task = name => ({ id: String(sequence++).padStart(26, '0'), version: 1, taskType: 'API', name, status: 'QUEUED', progress: 0 })
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [task('本地原登记')], remote: [task('网页原登记')] }, receipts = { local: new Map(), remote: new Map() }
  let persisted
  const store = { read: async () => copy(persisted ?? { version: 1, binding: null, pairs: [], conflicts: [], pending: null, lastSyncedAt: null }),
    write: async value => { if (state.failSave && value.pending) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'TASK_REGISTRATION', projectId: side === 'local' ? sourceId : targetId,
      actorId: side === 'local' ? localActor : state.actor, maxTasks: 1000, tasks: copy(rows[side]) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) { const error = new Error('actor changed'); error.status = 409; throw error }
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      for (const op of body.operations) if (op.taskId && rows[side].find(t => t.id === op.taskId)?.version !== op.expectedVersion) {
        const error = new Error('version changed'); error.status = 409; throw error
      }
      result = { requestKey: body.requestKey, results: body.operations.map(op => {
        const existing = rows[side].find(t => t.id === op.taskId), t = existing ?? task(op.name)
        Object.assign(t, { taskType: op.taskType, name: op.name, status: op.status, progress: op.progress, version: existing ? t.version + 1 : 1 })
        if (!existing) rows[side].push(t)
        return { operationId: op.operationId, task: copy(t) }
      }) }; receipts[side].set(body.requestKey, copy(result))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async binding => {
    if (binding.server !== state.server) throw new Error('server changed')
  } }
  const client = new TaskSync(options)
  async function bind() { const preview = await client.preview(sourceId, targetId, server); await client.bind(preview.token); await client.run() }
  const edit = (side, id, name) => { const t = rows[side].find(t => t.id === id); t.name = name; t.version++ }
  return { state, rows, client, options, store, bind, edit }
}
test('explicit binding merges both projects once, preserves manual active status, and maps same names independently', async () => {
  const f = fixture(); f.rows.local[0].name = f.rows.remote[0].name = '同名登记'; Object.assign(f.rows.local[0], { status: 'RUNNING', progress: 43 })
  await f.client.load(); await f.client.run(); assert.equal(f.state.writes.length, 0)
  const preview = await f.client.preview(sourceId, targetId, server); assert.equal(preview.upload, 1); assert.equal(preview.download, 1)
  assert.equal(f.state.writes.length, 0); await f.client.bind(preview.token); await f.client.run()
  assert.equal(f.rows.local.length, 2); assert.equal(f.rows.remote.length, 2); assert.equal(f.client.state.pairs.length, 2)
  assert.ok(f.rows.remote.some(t => t.status === 'RUNNING' && t.progress === 43))
  const writes = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, writes)
})
test('offline edits survive and reconnect updates both directions using exact versions', async () => {
  const f = fixture(); await f.bind(); const [a, b] = f.client.state.pairs
  f.state.offline = true; f.edit('local', a.localId, '断网修改'); await assert.rejects(f.client.run(), /offline/)
  assert.equal(f.rows.local.find(t => t.id === a.localId).name, '断网修改')
  f.edit('remote', b.remoteId, '网页修改'); f.state.offline = false; await f.client.run()
  assert.equal(f.rows.remote.find(t => t.id === a.remoteId).name, '断网修改'); assert.equal(f.rows.local.find(t => t.id === b.localId).name, '网页修改')
})
test('lost responses and app restart retry the identical persisted request without duplicate creates', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(task('新登记')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending); const count = f.rows.remote.length
  assert.ok(pending); f.state.loseResponse = false; const restarted = new TaskSync(f.options); await restarted.run()
  assert.equal(f.rows.remote.length, count); assert.equal(restarted.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2)
  assert.deepEqual(attempts[0], attempts[1]); await restarted.run(); assert.equal(f.rows.remote.length, count)
})
test('two-sided edits preserve both versions until an explicit current conflict choice', async () => {
  const f = fixture(); await f.bind(); const pair = f.client.state.pairs[0]
  f.edit('local', pair.localId, '本地选择'); f.edit('remote', pair.remoteId, '网页选择'); const count = f.state.writes.length
  await f.client.run(); assert.equal(f.state.writes.length, count); assert.equal(f.client.state.conflicts.length, 1)
  await f.client.resolve(pair.localId, 'remote'); assert.equal(f.rows.local.find(t => t.id === pair.localId).name, '网页选择')
  assert.equal(f.client.state.conflicts.length, 0); await f.client.run(); assert.equal(f.client.state.conflicts.length, 0)
})
test('a conflict changed during review cannot be overwritten using the old choice', async () => {
  const f = fixture(); await f.bind(); const p = f.client.state.pairs[0]
  f.edit('local', p.localId, '本地'); f.edit('remote', p.remoteId, '网页'); await f.client.run(); f.edit('remote', p.remoteId, '网页又修改')
  const count = f.state.writes.length; await assert.rejects(f.client.resolve(p.localId, 'local'), /已变化/); assert.equal(f.state.writes.length, count)
})
test('account and server switches, including changes during CSRF, prevent uploads', async () => {
  for (const change of [f => { f.state.actor = 'E'.repeat(26) }, f => { f.state.server = 'https://other.test' }, f => { f.state.onCsrf = () => { f.state.actor = 'E'.repeat(26) } }]) {
    const f = fixture(); await f.bind(); f.rows.local.push(task('私有登记')); change(f)
    await assert.rejects(f.client.run()); assert.ok(!f.rows.remote.some(t => t.name === '私有登记'))
  }
})
test('a concurrent target edit returns 409, clears only rejected pending batch and reveals conflict on refresh', async () => {
  const f = fixture(); await f.bind(); const pair = f.client.state.pairs[0]; f.edit('local', pair.localId, '本地编辑')
  f.state.onCsrf = () => { f.edit('remote', pair.remoteId, '网页并发编辑'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /version changed/); assert.equal(f.client.state.pending, null)
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.rows.remote.find(t => t.id === pair.remoteId).name, '网页并发编辑')
})
test('pause, restore and missing records never replay or delete data', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(task('未发出'))
  await f.client.enabled(false); const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count)
  await f.client.enabled(true); await f.client.suspendForRestore(); await f.client.run(); assert.equal(f.state.writes.length, count)
  assert.equal(f.client.state.binding.requiresRebind, true)
  await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 3); assert.equal(f.rows.remote.length, 2)
  const g = fixture(); await g.bind(); const p = g.client.state.pairs[0]; g.rows.remote = g.rows.remote.filter(t => t.id !== p.remoteId)
  await g.client.run(); assert.equal(g.client.state.conflicts.length, 1); assert.ok(g.rows.local.some(t => t.id === p.localId))
})
test('changed preview and failed durable pending write prevent network mutations', async () => {
  const f = fixture(); const preview = await f.client.preview(sourceId, targetId, server); f.rows.local.push(task('新登记'))
  await assert.rejects(f.client.bind(preview.token), /已变化/); assert.equal(f.state.writes.length, 0)
  await f.bind(); const count = f.state.writes.length; f.rows.local.push(task('不可发送')); f.state.failSave = true
  await assert.rejects(f.client.run(), /disk full/); assert.equal(f.state.writes.length, count); assert.equal(f.client.state.pending, null)
})
test('encrypted state has private file permissions, survives reload and rejects tampering', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-task-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
    return Buffer.concat([iv, cipher.update(value, 'utf8'), cipher.final(), cipher.getAuthTag()]) },
  decrypt: async bytes => { const decipher = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); decipher.setAuthTag(bytes.subarray(-16))
    return Buffer.concat([decipher.update(bytes.subarray(12, -16)), decipher.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new TaskSyncStore(directory, codec); await store.write(f.client.state)
    assert.deepEqual(await store.read(), f.client.state); const bytes = await fs.readFile(store.file)
    assert.ok(!bytes.includes(Buffer.from('本地原登记'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('transport prohibits redirects, bounds response data and retains meaningful HTTP status for retries', async () => {
  const response = value => new globalThis.Response(JSON.stringify({ data: value }))
  let options; assert.deepEqual(await syncRequest(async (_, init) => { options = init; return response({ id: 'qa' }) }, server), { id: 'qa' })
  assert.equal(options.redirect, 'error'); assert.equal(options.credentials, 'include')
  await assert.rejects(syncRequest(async () => new globalThis.Response('{}', { status: 409 }), server), e => e.status === 409)
  await assert.rejects(syncRequest(async () => response('x'.repeat(1024 * 1024)), server), /超过/)
  await assert.rejects(syncRequest(async () => { throw new Error('private-network-detail') }, server), /暂时不可达/)
})
test('native handlers reject another window or subframe before reading any local state', async () => {
  const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/task-sync.html' } } }
  installTaskSync({ ipcMain: { handle: (name, handler) => handlers.set(name, handler) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup' })
  const status = handlers.get('testhub-desktop:task-sync:status')
  assert.throws(() => status({ sender: {}, senderFrame: setup.webContents.mainFrame }), /不受信任/)
  assert.throws(() => status({ sender: setup.webContents, senderFrame: { url: setup.webContents.mainFrame.url } }), /不受信任/)
  assert.throws(() => handlers.get('testhub-desktop:task-sync:bind')({ sender: {}, senderFrame: setup.webContents.mainFrame }), /不受信任/)
})
