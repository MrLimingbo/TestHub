'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const { MobileAutomationTransfer, mobileAutomationTransferResponseBudget } = require('../app/src/mobile-automation-transfer.cjs')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26)
function fixture() {
  const state = { actor: 'alice', endpoint: 'server', now: 1, writes: [], fail: false,
    capabilities: { version: 1, scope: 'MOBILE_AUTOMATION', maxBytes: 32 * 1024 * 1024 } }
  const context = id => ({ organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id, name: '项目' }] } })
  const snapshot = { version: 1, sourceProjectId: sourceId, devices: [{ id: sourceId, name: '设备' }], applications: [{ id: sourceId, name: '应用', hasAppFile: true }],
    elements: [{ id: targetId, name: '按钮' }], scenarios: [{ id: sourceId, name: '订单', version: 2,
      steps: [{ id: 'step', value: 'secret-fixture' }] }] }
  const client = new MobileAutomationTransfer({ now: () => state.now, current: () => state.endpoint,
    local: async route => route.endsWith('/context') ? context(sourceId) : snapshot,
    remote: async (route, options) => {
      if (route.endsWith('/me')) return { id: state.actor, account: state.actor }
      if (route.endsWith('/context')) return context(targetId)
      if (route.endsWith('/capabilities')) return state.capabilities
      if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'csrf' } }
      state.writes.push(JSON.parse(options.body))
      if (state.fail) throw new Error('response lost')
      return { id: 'receipt', devices: 1, applications: 1, elements: 1, scenarios: 1, steps: 1, detachedFiles: 1 }
    } })
  return { state, snapshot, client }
}
test('read-only preview counts mobile assets and freezes submitted snapshot', async () => {
  const { state, snapshot, client } = fixture(), preview = await client.prepare(sourceId, targetId)
  assert.deepEqual(preview.counts, { devices: 1, applications: 1, elements: 1, scenarios: 1, steps: 1, detachedFiles: 1 })
  assert.equal(preview.snapshot, undefined); assert.deepEqual(state.writes, [])
  snapshot.scenarios[0].steps[0].value = 'changed'
  await client.commit(preview.token)
  assert.equal(state.writes[0].snapshot.scenarios[0].steps[0].value, 'secret-fixture')
  assert.equal(state.writes[0].expectedUserId, 'alice')
})
test('capability, payload scope, project and size mismatches prevent upload', async () => {
  for (const patch of [{ version: 2 }, { scope: 'OTHER' }, { maxBytes: 0 }]) {
    const { state, client } = fixture(); Object.assign(state.capabilities, patch)
    await assert.rejects(client.prepare(sourceId, targetId), /不支持/); assert.deepEqual(state.writes, [])
  }
  for (const patch of [{ version: 2 }, { sourceProjectId: targetId }, { scenarios: null }, { history: [] }, { devices: [], applications: [], elements: [], scenarios: [] }]) {
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
test('large response budget is restricted to exact performance snapshot route', () => {
  const route = `/api/v1/projects/${sourceId}/mobile-automation/transfer`
  assert.equal(mobileAutomationTransferResponseBudget(route), 33 * 1024 * 1024)
  for (const value of [`${route}/capabilities`, `${route}?extra=1`, '/api/v1/auth/me'])
    assert.equal(mobileAutomationTransferResponseBudget(value), 9 * 1024 * 1024)
})
test('native commit checks sender and defaults confirmation to cancel with disabled profiles/schedules and history boundaries', async () => {
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
  vm.runInContext(source.slice(source.indexOf('function requireMobileAutomationTransferSender'), source.indexOf('function updateBackup')), context)
  context.getMobileAutomationTransferClient = async () => client
  const event = contents => ({ sender: contents, senderFrame: contents.mainFrame })
  await handlers.get('testhub-desktop:mobile-automation-transfer:open')(event(setupWindow.webContents))
  assert.equal(calls[0], 'testhub-desktop://setup/mobile-automation-transfer.html')
  const commit = handlers.get('testhub-desktop:mobile-automation-transfer:commit')
  assert.throws(() => commit(event(setupWindow.webContents), preview.token), /不受信任/)
  setupWindow.webContents.mainFrame.url = 'testhub-desktop://setup/mobile-automation-transfer.html'
  let confirmation
  context.dialog.showMessageBox = async (_window, options) => { confirmation = options; return { response: 0 } }
  assert.equal((await commit(event(setupWindow.webContents), preview.token)).cancelled, true)
  assert.deepEqual(state.writes, []); assert.equal(confirmation.defaultId, 0)
  for (const text of ['敏感值', '全部停用', 'APK/IPA', '执行历史', '本地原件不动', '设备', 'Appium']) assert.ok(confirmation.detail.includes(text))
  context.dialog.showMessageBox = async () => ({ response: 1 })
  await commit(event(setupWindow.webContents), preview.token); assert.equal(state.writes.length, 1)
})
