'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const Response = globalThis.Response
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { ProjectFileSync, ProjectFileSyncStore, empty, validateState } = require('../app/src/project-file-sync.cjs')
const { installProjectFileSync } = require('../app/src/project-file-sync-ipc.cjs')
const { syncBinary } = require('../app/src/project-sync-ipc.cjs')
const localProject = 'A'.repeat(26), remoteProject = 'B'.repeat(26), localActor = 'C'.repeat(26), remoteActor = 'D'.repeat(26)
const localId = 'L'.repeat(26), remoteId = 'R'.repeat(26), server = 'https://sync.example.test'
const clone = value => globalThis.structuredClone(value)
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const item = (id, bytes, name = '同名.txt', key = id) => ({ id, key, name, type: 'text/plain', size: bytes.length, sha256: digest(bytes) })
function fixture() {
  const state = { server, actor: remoteActor, offline: false, loseResponse: false, failSave: false, writes: [], onCsrf: null }
  const rows = { local: [item(localId, Buffer.from('same'))], remote: [item(remoteId, Buffer.from('same'))] }
  const bytes = { local: new Map([[localId, Buffer.from('same')]]), remote: new Map([[remoteId, Buffer.from('same')]]) }
  const receipts = { local: new Map(), remote: new Map() }
  let persisted
  const revision = side => digest(Buffer.from(JSON.stringify(rows[side])))
  const store = { read: async () => clone(persisted ?? empty()), write: async value => {
    validateState(value); if (state.failSave && value.pending) throw Error('disk full'); persisted = clone(value)
  } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '文件账号', account: 'fileqa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: 'QA' }], projects: { org: [{ id: side === 'local' ? localProject : remoteProject, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'PROJECT_FILE_SYNC', projectId: side === 'local' ? localProject : remoteProject,
      actorId: side === 'local' ? localActor : state.actor, revision: revision(side), items: clone(rows[side]) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const error = Error('revision changed'); error.status = 409; throw error }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let receipt = receipts[side].get(body.requestKey)
    if (!receipt) {
      if (body.expectedRevision !== revision(side) || rows[side].some(value => value.key === body.sourceKey)) conflict()
      const content = Buffer.from(body.content, 'base64')
      if (content.length !== body.size || digest(content) !== body.sha256) throw Error('invalid content')
      const id = crypto.randomBytes(13).toString('hex').toUpperCase()
      rows[side].push(item(id, content, body.name, body.sourceKey)); bytes[side].set(id, content)
      receipt = { requestKey: body.requestKey, sourceKey: body.sourceKey, fileId: id }; receipts[side].set(body.requestKey, receipt)
    }
    if (state.loseResponse) throw Error('response lost')
    return clone(receipt)
  }
  const binary = side => async (route, size, sha) => {
    if (side === 'remote' && state.offline) throw Error('offline')
    const id = route.split('/').at(-2), content = bytes[side].get(id)
    if (!content || content.length !== size || digest(content) !== sha) throw Error('file changed')
    return content
  }
  const options = { store, local: request('local'), remote: request('remote'), localBinary: binary('local'), remoteBinary: binary('remote'),
    guard: async b => { if (b.server !== state.server) throw Error('server changed') } }
  const client = new ProjectFileSync(options)
  const bind = async pairs => { const plan = await client.preview(localProject, remoteProject, server, pairs); await client.bind(plan.token); await client.run() }
  const pair = () => [{ localFileId: localId, remoteFileId: remoteId }]
  const add = (side, id, content, name = '同名.txt') => { const buffer = Buffer.from(content); rows[side].push(item(id, buffer, name)); bytes[side].set(id, buffer) }
  return { state, rows, bytes, receipts, store, options, client, bind, pair, add }
}

test('preview is read-only; manual pair keeps both physical file IDs', async () => {
  const f = fixture(), listed = await f.client.references(localProject, remoteProject, server)
  assert.equal(listed.local[0].id, localId); assert.equal(listed.remote[0].id, remoteId)
  const plan = await f.client.preview(localProject, remoteProject, server, f.pair())
  assert.equal(plan.upload, 0); assert.equal(plan.download, 0); assert.equal(f.state.writes.length, 0)
  await f.client.bind(plan.token); await f.client.run()
  assert.equal(f.rows.local.length, 1); assert.equal(f.rows.remote.length, 1)
  assert.deepEqual(f.client.state.links, [{ key: localId, localId, remoteId }])
})
test('file link context exposes only intact mappings for the same actor and project scope', async () => {
  const f = fixture(); await f.bind(f.pair())
  const b = f.client.state.binding
  assert.deepEqual((await f.client.linkContext(b)).links, [{ key: localId, localId, remoteId }])
  assert.equal(await f.client.linkContext({ ...b, target: { id: 'Z'.repeat(26) } }), null)
  f.rows.remote[0] = item(remoteId, Buffer.from('changed'), '同名.txt')
  assert.deepEqual((await f.client.linkContext(b)).links, [])
  f.rows.remote = []
  assert.deepEqual((await f.client.linkContext(b)).links, [])
  f.state.actor = 'Z'.repeat(26)
  await assert.rejects(f.client.linkContext(b), /账号/)
})
test('unpaired same-name files remain distinct and new files continue both ways', async () => {
  const f = fixture(); f.rows.remote[0] = item(remoteId, Buffer.from('different')); f.bytes.remote.set(remoteId, Buffer.from('different'))
  const plan = await f.client.preview(localProject, remoteProject, server)
  assert.equal(plan.upload, 1); assert.equal(plan.download, 1)
  await f.client.bind(plan.token); await f.client.run()
  assert.equal(f.rows.local.length, 2); assert.equal(f.rows.remote.length, 2)
  assert.equal(f.rows.local.filter(v => v.name === '同名.txt').length, 2)
  f.add('local', 'N'.repeat(26), 'after offline', '新文件.txt'); await f.client.run()
  assert.equal(f.rows.remote.find(v => v.key === 'N'.repeat(26)).name, '新文件.txt')
})
test('different bytes and repeated target IDs cannot be manually paired', async () => {
  const f = fixture(); f.rows.remote[0] = item(remoteId, Buffer.from('different'))
  await assert.rejects(f.client.preview(localProject, remoteProject, server, f.pair()), /摘要不同/)
  f.rows.remote[0] = item(remoteId, Buffer.from('same'))
  await assert.rejects(f.client.preview(localProject, remoteProject, server, [...f.pair(), ...f.pair()]), /配对无效/)
  assert.equal(f.state.writes.length, 0)
})
test('deleted synchronized file pauses only that file; unrelated new file still copies', async () => {
  const f = fixture(); await f.bind(f.pair())
  f.rows.remote = []; f.bytes.remote.delete(remoteId)
  f.add('local', 'N'.repeat(26), 'new')
  await f.client.run()
  assert.equal(f.rows.remote.some(v => v.key === localId), false)
  assert.equal(f.rows.remote.some(v => v.key === 'N'.repeat(26)), true)
  assert.match(f.client.state.conflicts[0].reason, /删除或重建/)
})
test('lost response replays durable exact file request after restart without duplicate', async () => {
  const f = fixture(); await f.bind(f.pair()); f.add('local', 'N'.repeat(26), 'new')
  f.state.loseResponse = true; await assert.rejects(f.client.run(), /response lost/)
  const pending = clone(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false
  const restarted = new ProjectFileSync(f.options); await restarted.run()
  assert.equal(restarted.state.pending, null); assert.equal(f.rows.remote.length, count)
  const writes = f.state.writes.filter(value => value.body.requestKey === pending.request.requestKey)
  assert.equal(writes.length, 2); assert.deepEqual(writes[0], writes[1])
})
test('revision race, account switch and failed encrypted write do not copy stale bytes', async () => {
  const f = fixture(); await f.bind(f.pair()); f.add('local', 'N'.repeat(26), 'new')
  f.state.onCsrf = () => { f.add('remote', 'E'.repeat(26), 'competing'); f.state.onCsrf = null }
  await assert.rejects(f.client.run(), /revision changed/)
  assert.equal(f.client.state.pending, null); assert.equal(f.rows.remote.some(v => v.key === 'N'.repeat(26)), false)
  f.state.actor = 'Z'.repeat(26); await assert.rejects(f.client.run(), /账号/)
  f.state.actor = remoteActor; f.state.failSave = true; await assert.rejects(f.client.run(), /disk full/)
  assert.equal(f.rows.remote.some(v => v.key === 'N'.repeat(26)), false)
})
test('backup restore, pause and unbind retain file bytes', async () => {
  const f = fixture(); await f.bind(f.pair()); f.add('local', 'N'.repeat(26), 'new')
  await f.client.enabled(false); await f.client.run(); assert.equal(f.rows.remote.length, 1)
  await f.client.enabled(true); await f.client.suspendForRestore()
  await assert.rejects(f.client.enabled(true), /恢复备份/)
  await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 2)
})
test('encrypted store protects pending binary and rejects corrupt state', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-file-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(f.pair()); f.add('local', 'N'.repeat(26), 'private content')
    f.state.loseResponse = true; await assert.rejects(f.client.run())
    const store = new ProjectFileSyncStore(directory, codec); await store.write(f.client.state)
    assert.deepEqual(await store.read(), f.client.state)
    const encrypted = await fs.readFile(store.file); assert.ok(!encrypted.includes(Buffer.from('private content')))
    assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    encrypted[20] ^= 1; await fs.writeFile(store.file, encrypted); await assert.rejects(store.read(), /无法解密/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('untrusted native frame cannot inspect file sync and unused tick starts no runtime', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-file-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
    const installed = installProjectFileSync({ ipcMain: { handle: (name, fn) => handlers.set(name, fn) }, windows: () => ({ setup }),
      origin: 'testhub-desktop://setup', directory, runtime: async () => { starts++; throw Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:project-file-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await installed.tick(); assert.equal(starts, 0); installed.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})
test('binary transport enforces actual bytes and SHA-256', async () => {
  const data = Buffer.from('actual bytes'), hash = digest(data)
  assert.deepEqual(await syncBinary(async () => new Response(data), 'https://sync.example.test/file', data.length, hash), data)
  await assert.rejects(syncBinary(async () => new Response(Buffer.from('changed')), 'https://sync.example.test/file', data.length, hash), /SHA-256/)
  await assert.rejects(syncBinary(async () => new Response(Buffer.from('extra content')), 'https://sync.example.test/file', data.length, hash), /超过记录大小/)
})
