'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const { Buffer } = require('node:buffer')
const crypto = require('node:crypto')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const { ApiTransfer, apiTransferResponseBudget } = require('../app/src/api-transfer.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), MiB = 1024 * 1024
function fixture() {
  const context = id => ({ organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id, name: '项目' }] } })
  const state = { user: 'alice', endpoint: 'server', now: 1, writes: [], reads: [], fail: false,
    capabilities: { version: 1, scope: 'API_DEFINITIONS', versions: [1], maxBytes: 8 * MiB, fileSnapshotMaxBytes: 32 * MiB } }
  const snapshot = { version: 1, sourceProjectId: sourceId, catalogs: [{ id: 'catalog' }], definitions: [], versions: [], cases: [] }
  const client = new ApiTransfer({ current: () => state.endpoint, now: () => state.now,
    local: async route => { state.reads.push(route); return route.endsWith('/context') ? context(sourceId) : snapshot },
    remote: async (route, options) => {
      if (route.endsWith('/me')) return { id: state.user, account: state.user }
      if (route.endsWith('/context')) return context(targetId)
      if (route.endsWith('/capabilities')) return state.capabilities
      if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'csrf' } }
      state.writes.push(JSON.parse(options.body))
      if (state.fail) throw new Error('response lost')
      return { id: 'receipt', catalogs: 1, definitions: 0, versions: 0, cases: 0, rootId: 'root' }
    } })
  return { state, client, snapshot }
}
function file(size = 3) {
  const bytes = Buffer.alloc(size, 1)
  return { id: 'file', size, content: bytes.toString('base64'), sha256: crypto.createHash('sha256').update(bytes).digest('hex') }
}
test('preview is read-only, defaults files off, reports all counts and hides payload', async () => {
  const { client, state } = fixture(), result = await client.prepare(sourceId, targetId)
  assert.deepEqual(result.counts, { catalogs: 1, definitions: 0, versions: 0, cases: 0 })
  assert.equal(result.snapshot, undefined); assert.deepEqual(state.writes, [])
  assert.ok(state.reads.at(-1).endsWith('?includeFiles=false'))
  await client.commit(result.token); assert.equal(state.writes[0].expectedUserId, 'alice')
  assert.equal(client.plan, undefined)
})
test('capability mismatches stop before export without target writes', async () => {
  for (const patch of [{ version: 4 }, { scope: 'FUNCTIONAL_DEFINITIONS' }, { versions: [] }, { versions: undefined }, { maxBytes: 0 }, { fileSnapshotMaxBytes: undefined }]) {
    const { client, state } = fixture(); Object.assign(state.capabilities, patch)
    await assert.rejects(client.prepare(sourceId, targetId, true), /不支持/)
    assert.ok(state.reads.every(route => route.endsWith('/context'))); assert.deepEqual(state.writes, [])
  }
})
test('invalid scope, unknown projects and malformed snapshots clear prior preview', async () => {
  for (const patch of [{ version: 2 }, { sourceProjectId: targetId }, { catalogs: null }, { definitions: [null] }, { versions: {} }, { cases: [1] }, { history: [] }, { catalogs: [] }, { files: { projectId: sourceId, items: [] } }]) {
    const { client, state, snapshot } = fixture(), preview = await client.prepare(sourceId, targetId)
    Object.assign(snapshot, patch)
    await assert.rejects(client.prepare(sourceId, targetId))
    await assert.rejects(client.commit(preview.token)); assert.deepEqual(state.writes, [])
  }
  const { client } = fixture()
  await assert.rejects(client.prepare('../bad', targetId))
  await assert.rejects(client.prepare(sourceId, 'C'.repeat(26)), /权限/)
  await assert.rejects(client.prepare(sourceId, targetId, 'true'), /范围/)
})
test('account/server changes and ten minute expiry invalidate without posting', async () => {
  for (const change of [s => { s.user = 'bob' }, s => { s.endpoint = 'other' }, s => { s.now += 600000 }, s => { s.onCsrf = () => { s.endpoint = 'other' } }]) {
    const { client, state } = fixture(), preview = await client.prepare(sourceId, targetId)
    change(state); await assert.rejects(client.commit(preview.token)); assert.deepEqual(state.writes, [])
    state.user = 'alice'; state.endpoint = 'server'; state.now = 1
    await assert.rejects(client.commit(preview.token))
  }
})
test('uncertain retry uses same request key and frozen snapshot; concurrent commit is blocked', async () => {
  const { client, state, snapshot } = fixture(), preview = await client.prepare(sourceId, targetId)
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  snapshot.catalogs.push({ id: 'later-edit' }); state.fail = false
  const pending = client.commit(preview.token)
  await assert.rejects(client.commit(preview.token), /正在提交/); await pending
  assert.deepEqual(state.writes[0], state.writes[1]); assert.equal(state.writes[1].snapshot.catalogs.length, 1)
})
test('file selection verifies content, size and hash and reports byte counts', async () => {
  const { client, state, snapshot } = fixture()
  snapshot.files = { projectId: sourceId, items: [file()] }
  const preview = await client.prepare(sourceId, targetId, true)
  assert.deepEqual(preview.counts.files, { items: 1, bytes: 3 })
  assert.ok(state.reads.at(-1).endsWith('?includeFiles=true'))
  assert.deepEqual(state.writes, []); await client.commit(preview.token)
  assert.deepEqual(state.writes[0].snapshot.files, snapshot.files)
  for (const bad of [null, { ...file(), size: 4 }, { ...file(), content: 'AQEB=' }, { ...file(), sha256: 'bad' }, { ...file(), size: 20 * MiB + 1 }]) {
    snapshot.files.items = [bad]; await assert.rejects(client.prepare(sourceId, targetId, true), /项目文件/)
  }
})
test('file budgets enforce 100 items, 20 MiB total and source project identity', async () => {
  const { client, snapshot } = fixture()
  for (const files of [undefined, { projectId: targetId, items: [] }, { projectId: sourceId, items: Array(101).fill(file()) }, { projectId: sourceId, items: [file(10 * MiB), file(10 * MiB + 1)] }]) {
    snapshot.files = files; await assert.rejects(client.prepare(sourceId, targetId, true), /项目文件/)
  }
  snapshot.files = { projectId: sourceId, items: Array(100).fill(file()) }
  assert.equal((await client.prepare(sourceId, targetId, true)).counts.files.items, 100)
})
test('8/32 MiB snapshot caps and lower advertised server budget are enforced', async () => {
  for (const includeFiles of [false, true]) {
    const { client, snapshot } = fixture()
    if (includeFiles) snapshot.files = { projectId: sourceId, items: [] }
    snapshot.definitions = [{ body: 'x'.repeat((includeFiles ? 32 : 8) * MiB) }]
    await assert.rejects(client.prepare(sourceId, targetId, includeFiles), /大小限制/)
  }
  const { client, state } = fixture(); state.capabilities.maxBytes = 10
  await assert.rejects(client.prepare(sourceId, targetId), /大小限制/)
})
test('large response allowance is confined to exact API file export route', () => {
  const route = `/api/v1/projects/${sourceId}/api-design/transfer`
  assert.equal(apiTransferResponseBudget(`${route}?includeFiles=true`), 33 * MiB)
  for (const other of [route, `${route}?includeFiles=false`, `${route}/capabilities?includeFiles=true`, '/api/v1/auth/me?includeFiles=true', `${route}?includeFiles=true&extra=1`]) assert.equal(apiTransferResponseBudget(other), 9 * MiB)
})
// Run the actual main-process integration block with inert adapters, never Electron.
function mainHarness() {
  const source = fs.readFileSync(path.join(__dirname, '../app/src/main.cjs'), 'utf8')
  const handlers = new Map(), calls = []
  const remoteSession = {}, localSession = {}
  const mainWindow = { isDestroyed: () => false, webContents: { id: 12, session: remoteSession } }
  mainWindow.webContents.mainFrame = { url: 'https://server.test/' }
  const setupWindow = { isDestroyed: () => false, loadURL: async url => { calls.push(['navigate', url]) }, webContents: {} }
  setupWindow.webContents.mainFrame = { url: 'testhub-desktop://setup/transfer.html' }
  const context = { URL, ApiTransfer, apiTransferResponseBudget, mainWindow, setupWindow, workspaceMode: 'server', activeServerUrl: 'https://server.test',
    SETUP_ORIGIN: 'testhub-desktop://setup', LOCAL_PARTITION: 'local', localRuntime: undefined,
    apiTransferClient: undefined, apiTransferContextKey: undefined, backupOperation: undefined, transferOperation: undefined, savingExecutionSettings: false, enteringLocalWorkspace: undefined,
    session: { fromPartition: () => localSession }, ipcMain: { handle: (name, handler) => handlers.set(name, handler) },
    isTrustedRemoteSender: event => event.senderFrame.url.startsWith('https://server.test/'),
    jsonRequest: async (_fetch, url) => {
      calls.push(['request', url])
      if (url.endsWith('/me')) { if (context.signedOut) throw new Error('login required'); return { id: context.actor || 'alice' } }
      return { organizations: [], projects: {} }
    },
    initializeLocalRuntime: async () => {
      calls.push(['initialize'])
      context.localRuntime ||= { url: undefined, start: async () => { calls.push(['start']); context.localRuntime.url = 'http://127.0.0.1:1234' }, authorizeSession: async () => { calls.push(['authorize']) } }
    },
    openSetupWindow: () => {}, showLocalError: () => {}, dialog: { showMessageBox: async () => ({ response: 0 }) },
  }
  vm.createContext(context)
  vm.runInContext(source.slice(source.indexOf('function requireApiTransferSender'), source.indexOf('function updateBackup')), context)
  const event = contents => ({ sender: contents, senderFrame: contents.mainFrame })
  return { context, handlers, calls, event, mainWindow, setupWindow }
}
test('cold context starts and authorizes local source before capturing its URL; repeated client stays stable', async () => {
  const { context, calls } = mainHarness()
  const client = await context.getApiTransferClient()
  await client.context()
  assert.equal(await context.getApiTransferClient(), client)
  assert.ok(calls.findIndex(call => call[0] === 'start') < calls.findIndex(call => call[0] === 'authorize'))
  assert.ok(calls.some(call => call[1] === 'http://127.0.0.1:1234/api/v1/access/context'))
  context.actor = 'bob'; assert.notEqual(await context.getApiTransferClient(), client)
})
test('native entry authenticates server mode and rejects untrusted, subframe and busy requests', async () => {
  const h = mainHarness(), open = h.handlers.get('testhub-desktop:api-transfer:open')
  h.context.signedOut = true
  await assert.rejects(open(h.event(h.mainWindow.webContents)), /login/)
  assert.ok(!h.calls.some(call => call[0] === 'navigate' || call[0] === 'start'))
  h.context.signedOut = false; h.context.workspaceMode = 'local'
  await assert.rejects(open(h.event(h.setupWindow.webContents)), /登录/)
  h.context.workspaceMode = 'server'
  assert.throws(() => open({ sender: {}, senderFrame: {} }), /不受信任/)
  assert.throws(() => open({ sender: h.mainWindow.webContents, senderFrame: { url: 'https://server.test/' } }), /不受信任/)
  h.context.backupOperation = Promise.resolve()
  assert.throws(() => open(h.event(h.setupWindow.webContents)), /其他工作区操作/)
  h.context.backupOperation = undefined
  await open(h.event(h.setupWindow.webContents))
  assert.equal(h.calls.at(-1)[1], 'testhub-desktop://setup/api-transfer.html')
})
test('privileged API handlers require exact setup window/page and share operation lock', async () => {
  const h = mainHarness(), handler = h.handlers.get('testhub-desktop:api-transfer:context')
  assert.throws(() => handler(h.event(h.mainWindow.webContents)), /不受信任/)
  assert.throws(() => handler(h.event(h.setupWindow.webContents)), /不受信任/)
  h.setupWindow.webContents.mainFrame.url = 'testhub-desktop://setup/api-transfer.html'
  const pending = handler(h.event(h.setupWindow.webContents))
  assert.throws(() => handler(h.event(h.setupWindow.webContents)), /其他工作区操作/)
  await pending; assert.equal(h.context.transferOperation, undefined)
})
test('native confirmation defaults to cancel, explains publication/sensitive data and retains preview', async () => {
  const h = mainHarness(), { client, state } = fixture(), preview = await client.prepare(sourceId, targetId)
  h.setupWindow.webContents.mainFrame.url = 'testhub-desktop://setup/api-transfer.html'
  h.context.getApiTransferClient = async () => client
  let confirmation
  h.context.dialog.showMessageBox = async (_window, options) => { confirmation = options; return { response: 0 } }
  const commit = h.handlers.get('testhub-desktop:api-transfer:commit')
  const result = await commit(h.event(h.setupWindow.webContents), preview.token)
  assert.equal(result.cancelled, true); assert.deepEqual(state.writes, []); assert.equal(client.plan.token, preview.token)
  assert.equal(confirmation.defaultId, 0); assert.equal(confirmation.cancelId, 0)
  for (const text of ['敏感值', 'Mock', '历史发布时间', '不保留原发布人', '本地原件不动', '快捷请求副本']) assert.ok(confirmation.detail.includes(text))
  h.context.dialog.showMessageBox = async () => ({ response: 1 })
  await commit(h.event(h.setupWindow.webContents), preview.token)
  assert.equal(state.writes.length, 1)
})
test('capability negotiation/source export account changes cannot leave a usable preview', async () => {
  const { client, state } = fixture(), local = client.local
  client.local = async route => {
    const result = await local(route)
    if (route.includes('/transfer?')) state.user = 'bob'
    return result
  }
  await assert.rejects(client.prepare(sourceId, targetId), /账号或服务已变化/)
  assert.equal(client.plan, undefined); assert.deepEqual(state.writes, [])
})

test('gRPC opt-in negotiates V2, freezes descriptors and reports selected count', async () => {
  const {client, state, snapshot} = fixture()
  state.capabilities.version = 2; state.capabilities.versions = [1, 2]
  await client.prepare(sourceId, targetId)
  snapshot.version = 2; snapshot.grpcDescriptors = [file()]
  const preview = await client.prepare(sourceId, targetId, false, true)
  assert.equal(preview.counts.grpcDescriptors, 1)
  assert.ok(state.reads.at(-1).endsWith('?includeFiles=false&includeGrpc=true'))
  snapshot.grpcDescriptors[0].content = 'changed'
  await client.commit(preview.token)
  assert.equal(state.writes[0].snapshot.grpcDescriptors[0].content, file().content)
})
test('gRPC omitted scope, old target and corrupt descriptors reject before writes', async () => {
  const {client, state, snapshot} = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, true), /不支持/)
  state.capabilities.version = 2; state.capabilities.versions = [1, 2]
  snapshot.grpcDescriptors = []
  await assert.rejects(client.prepare(sourceId, targetId), /范围/)
  snapshot.version = 2; snapshot.grpcDescriptors = [{...file(), sha256: 'bad'}]
  await assert.rejects(client.prepare(sourceId, targetId, false, true), /SHA/)
  assert.deepEqual(state.writes, [])
  assert.equal(apiTransferResponseBudget(`/api/v1/projects/${sourceId}/api-design/transfer?includeFiles=true&includeGrpc=true`), 33 * MiB)
})

test('history opt-in negotiates V3 and cannot silently include replay credentials', async () => {
  const {client, state, snapshot} = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, false, true), /不支持/)
  state.capabilities.versions = [1, 2, 3]
  snapshot.version = 3; snapshot.debugHistory = [{id: 'history', replayDraft: {protocol: 'HTTP', request: {}}}]
  const preview = await client.prepare(sourceId, targetId, false, false, true)
  assert.equal(preview.counts.debugHistory, 1)
  assert.ok(state.reads.at(-1).endsWith('?includeFiles=false&includeHistory=true'))
  await client.commit(preview.token); assert.equal(state.writes[0].snapshot.version, 3)
  snapshot.version = 1
  await assert.rejects(client.prepare(sourceId, targetId), /范围/)
  assert.equal(apiTransferResponseBudget(`/api/v1/projects/${sourceId}/api-design/transfer?includeFiles=true&includeGrpc=true&includeHistory=true`), 33 * MiB)
})
