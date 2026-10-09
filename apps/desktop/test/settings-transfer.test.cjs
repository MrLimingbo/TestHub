'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const { SettingsTransfer, settingsTransferResponseBudget } = require('../app/src/settings-transfer.cjs')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26)
function fixture() {
  const state = { actor: 'alice', endpoint: 'server', now: 1, writes: [], fail: false,
    capabilities: { version: 1, scope: 'PROJECT_SETTINGS', maxBytes: 32 * 1024 * 1024 } }
  const context = id => ({ organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id, name: '项目' }] } })
  const snapshot = { version: 1, sourceProjectId: sourceId, environments: [{ id: sourceId, name: '原环境', environmentCode: 'test' }], parameters: [{ id: targetId, key: 'MODE', value: 'fixture' }], excludedSecrets: 2 }
  const client = new SettingsTransfer({ now: () => state.now, current: () => state.endpoint,
    local: async route => route.endsWith('/context') ? context(sourceId) : snapshot,
    remote: async (route, options) => {
      if (route.endsWith('/me')) return { id: state.actor, account: state.actor }
      if (route.endsWith('/context')) return context(targetId)
      if (route.endsWith('/capabilities')) return state.capabilities
      if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'csrf' } }
      state.writes.push(JSON.parse(options.body))
      if (state.fail) throw new Error('response lost')
      return { id: 'receipt', createdEnvironments: 1, reusedEnvironments: 0, createdParameters: 1, reusedParameters: 0, excludedSecrets: 2 }
    } })
  return { state, snapshot, client }
}
test('read-only preview counts environment and ordinary parameter scope, excluded secrets and freezes snapshot', async () => {
  const { state, snapshot, client } = fixture(), preview = await client.prepare(sourceId, targetId)
  assert.deepEqual(preview.counts, { environments: 1, parameters: 1, excludedSecrets: 2 })
  assert.equal(preview.snapshot, undefined); assert.deepEqual(state.writes, [])
  snapshot.environments[0].name = 'changed'
  await client.commit(preview.token)
  assert.equal(state.writes[0].snapshot.environments[0].name, '原环境')
  assert.equal(state.writes[0].expectedUserId, 'alice')
})
test('capability, payload scope, project and size mismatches prevent upload', async () => {
  for (const patch of [{ version: 2 }, { scope: 'OTHER' }, { maxBytes: 0 }]) {
    const { state, client } = fixture(); Object.assign(state.capabilities, patch)
    await assert.rejects(client.prepare(sourceId, targetId), /不支持/); assert.deepEqual(state.writes, [])
  }
  for (const patch of [{ version: 2 }, { sourceProjectId: targetId }, { parameters: null }, { history: [] }, { environments: [], parameters: [] }]) {
    const { state, snapshot, client } = fixture(); Object.assign(snapshot, patch)
    await assert.rejects(client.prepare(sourceId, targetId)); assert.deepEqual(state.writes, [])
  }
  const { state, client } = fixture(); state.capabilities.maxBytes = 10
  await assert.rejects(client.prepare(sourceId, targetId), /大小限制/)
})
test('account/server changes and expiry reject, uncertain retry keeps identical UUID and data', async () => {
  for (const change of [s => { s.actor = 'bob' }, s => { s.endpoint = 'other' }, s => { s.now += 600000 },
    s => { s.onCsrf = () => { s.endpoint = 'other' } }]) {
    const { state, client } = fixture(), preview = await client.prepare(sourceId, targetId)
    change(state); await assert.rejects(client.commit(preview.token)); assert.deepEqual(state.writes, [])
  }
  const { state, client } = fixture(), preview = await client.prepare(sourceId, targetId)
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token)
  assert.deepEqual(state.writes[0], state.writes[1]); await assert.rejects(client.commit(preview.token), /预览已失效/)
})
test('large response budget is restricted to exact settings snapshot route', () => {
  const route = `/api/v1/projects/${sourceId}/project-settings/transfer`
  assert.equal(settingsTransferResponseBudget(route), 33 * 1024 * 1024)
  for (const value of [`${route}/capabilities`, `${route}?extra=1`, '/api/v1/auth/me'])
    assert.equal(settingsTransferResponseBudget(value), 9 * 1024 * 1024)
})
test('native commit checks sender and defaults confirmation to cancel with excluded secrets and target preservation boundaries', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../app/src/main.cjs'), 'utf8'), handlers = new Map(), calls = []
  const setupWindow = { isDestroyed: () => false, loadURL: async url => { calls.push(url) }, webContents: {} }
  setupWindow.webContents.mainFrame = { url: 'testhub-desktop://setup/transfer.html' }
  const mainWindow = { isDestroyed: () => false, webContents: { mainFrame: { url: 'https://server.test/' } } }
  const { state, client } = fixture(), preview = await client.prepare(sourceId, targetId)
  const context = { setupWindow, mainWindow, SETUP_ORIGIN: 'testhub-desktop://setup', workspaceMode: 'server',
    activeServerUrl: 'https://server.test', runApiTransfer: operation => operation(),
    isTrustedRemoteSender: event => event.senderFrame.url.startsWith('https://server.test/'),
    openSetupWindow: () => {}, showLocalError: () => {},
    ipcMain: { handle: (name, handler) => handlers.set(name, handler) }, dialog: { showMessageBox: async () => ({ response: 0 }) } }
  vm.createContext(context)
  vm.runInContext(source.slice(source.indexOf('function requireSettingsTransferSender'), source.indexOf('function updateBackup')), context)
  context.getSettingsTransferClient = async () => client
  const event = contents => ({ sender: contents, senderFrame: contents.mainFrame })
  await handlers.get('testhub-desktop:settings-transfer:open')(event(setupWindow.webContents))
  assert.equal(calls[0], 'testhub-desktop://setup/settings-transfer.html')
  const commit = handlers.get('testhub-desktop:settings-transfer:commit')
  assert.throws(() => commit(event(setupWindow.webContents), preview.token), /不受信任/)
  setupWindow.webContents.mainFrame.url = 'testhub-desktop://setup/settings-transfer.html'
  let confirmation
  context.dialog.showMessageBox = async (_window, options) => { confirmation = options; return { response: 0 } }
  assert.equal((await commit(event(setupWindow.webContents), preview.token)).cancelled, true)
  assert.deepEqual(state.writes, []); assert.equal(confirmation.defaultId, 0)
  for (const text of ['排除 2 个密钥', '保留目标默认环境', '不同配置报冲突', '成员、令牌和旧日志', '本地原件不动']) assert.ok(confirmation.detail.includes(text))
  context.dialog.showMessageBox = async () => ({ response: 1 })
  await commit(event(setupWindow.webContents), preview.token); assert.equal(state.writes.length, 1)
})
