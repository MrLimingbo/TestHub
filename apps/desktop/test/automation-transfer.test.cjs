'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const { AutomationTransfer, automationTransferResponseBudget } = require('../app/src/automation-transfer.cjs')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26)
function fixture() {
  const state = { actor: 'alice', endpoint: 'server', now: 1, writes: [], fail: false,
    capabilities: { version: 1, scope: 'API_AUTOMATION', maxBytes: 32 * 1024 * 1024 } }
  const context = id => ({ organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id, name: '项目' }] } })
  const snapshot = { version: 1, sourceProjectId: sourceId, reusableSteps: [], scenarios: [{ id: sourceId, revisions: [] }],
    dataSets: [], executionProfiles: [], schedules: [] }
  const client = new AutomationTransfer({ now: () => state.now, current: () => state.endpoint,
    local: async route => route.endsWith('/context') ? context(sourceId) : snapshot,
    remote: async (route, options) => {
      if (route.endsWith('/me')) return { id: state.actor, account: state.actor }
      if (route.endsWith('/context')) return context(targetId)
      if (route.endsWith('/capabilities')) return state.capabilities
      if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'csrf' } }
      state.writes.push(JSON.parse(options.body))
      if (state.fail) throw new Error('response lost')
      return { id: 'receipt', reusableSteps: 0, scenarios: 1, dataSets: 0, dataSetSourceFiles: 0,
        executionProfiles: 0, schedules: 0 }
    } })
  return { state, snapshot, client }
}

test('preview remains read-only and commit freezes source snapshot', async () => {
  const { state, snapshot, client } = fixture()
  const preview = await client.prepare(sourceId, targetId)
  assert.deepEqual(preview.counts, { reusableSteps: 0, scenarios: 1, dataSets: 0,
    executionProfiles: 0, schedules: 0, dataSetSourceFiles: 0 })
  assert.equal(preview.snapshot, undefined)
  assert.deepEqual(state.writes, [])
  snapshot.scenarios.push({ id: targetId, revisions: [] })
  await client.commit(preview.token)
  assert.equal(state.writes[0].snapshot.scenarios.length, 1)
  assert.equal(state.writes[0].expectedUserId, 'alice')
})

test('capability, scope and size mismatches stop before upload', async () => {
  for (const patch of [{ version: 2 }, { scope: 'API_DEFINITIONS' }, { maxBytes: 0 }, { maxBytes: undefined }]) {
    const { state, client } = fixture(); Object.assign(state.capabilities, patch)
    await assert.rejects(client.prepare(sourceId, targetId), /不支持/)
    assert.deepEqual(state.writes, [])
  }
  for (const patch of [{ version: 2 }, { sourceProjectId: targetId }, { scenarios: null }, { extra: true }, { scenarios: [] }]) {
    const { snapshot, state, client } = fixture(); Object.assign(snapshot, patch)
    await assert.rejects(client.prepare(sourceId, targetId))
    assert.deepEqual(state.writes, [])
  }
  const { state, client } = fixture(); state.capabilities.maxBytes = 10
  await assert.rejects(client.prepare(sourceId, targetId), /大小限制/)
})

test('account/server changes, expiry and uncertain retry preserve idempotency', async () => {
  for (const change of [s => { s.actor = 'bob' }, s => { s.endpoint = 'other' }, s => { s.now += 600000 },
    s => { s.onCsrf = () => { s.endpoint = 'other' } }]) {
    const { state, client } = fixture(), preview = await client.prepare(sourceId, targetId)
    change(state); await assert.rejects(client.commit(preview.token)); assert.deepEqual(state.writes, [])
  }
  const { state, client } = fixture(), preview = await client.prepare(sourceId, targetId)
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token)
  assert.deepEqual(state.writes[0], state.writes[1])
  await assert.rejects(client.commit(preview.token), /预览已失效/)
})

test('larger response budget applies only to exact automation snapshot route', () => {
  const route = `/api/v1/projects/${sourceId}/api-automation/transfer`
  assert.equal(automationTransferResponseBudget(route), 33 * 1024 * 1024)
  for (const value of [`${route}/capabilities`, `${route}?extra=1`, '/api/v1/auth/me',
    `/api/v1/projects/${sourceId}/api-design/transfer`]) assert.equal(automationTransferResponseBudget(value), 9 * 1024 * 1024)
})

test('native handlers require exact trusted page and confirmation before upload', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../app/src/main.cjs'), 'utf8')
  const handlers = new Map(), calls = []
  const setupWindow = { isDestroyed: () => false, loadURL: async url => { calls.push(url) }, webContents: {} }
  setupWindow.webContents.mainFrame = { url: 'testhub-desktop://setup/transfer.html' }
  const mainWindow = { isDestroyed: () => false, webContents: { mainFrame: { url: 'https://server.test/' } } }
  const { state, client } = fixture(), preview = await client.prepare(sourceId, targetId)
  const context = { setupWindow, mainWindow, SETUP_ORIGIN: 'testhub-desktop://setup', workspaceMode: 'server',
    activeServerUrl: 'https://server.test', backupOperation: undefined, transferOperation: undefined,
    savingExecutionSettings: false, enteringLocalWorkspace: undefined,
    isTrustedRemoteSender: event => event.senderFrame.url.startsWith('https://server.test/'),
    runApiTransfer: operation => operation(), getAutomationTransferClient: async () => client,
    openSetupWindow: () => {}, showLocalError: () => {},
    ipcMain: { handle: (name, handler) => handlers.set(name, handler) },
    dialog: { showMessageBox: async () => ({ response: 0 }) },
  }
  vm.createContext(context)
  vm.runInContext(source.slice(source.indexOf('function requireAutomationTransferSender'), source.indexOf('function updateBackup')), context)
  context.getAutomationTransferClient = async () => client
  const event = contents => ({ sender: contents, senderFrame: contents.mainFrame })
  const open = handlers.get('testhub-desktop:automation-transfer:open')
  await open(event(setupWindow.webContents))
  assert.equal(calls[0], 'testhub-desktop://setup/automation-transfer.html')
  const commit = handlers.get('testhub-desktop:automation-transfer:commit')
  assert.throws(() => commit(event(setupWindow.webContents), preview.token), /不受信任/)
  setupWindow.webContents.mainFrame.url = 'testhub-desktop://setup/automation-transfer.html'
  let confirmation
  context.dialog.showMessageBox = async (_window, options) => { confirmation = options; return { response: 0 } }
  assert.equal((await commit(event(setupWindow.webContents), preview.token)).cancelled, true)
  assert.deepEqual(state.writes, [])
  assert.equal(confirmation.defaultId, 0)
  for (const value of ['敏感值', '计划一律停用', 'Webhook 密钥', '本地原件不动'])
    assert.ok(confirmation.detail.includes(value))
  context.dialog.showMessageBox = async () => ({ response: 1 })
  await commit(event(setupWindow.webContents), preview.token)
  assert.equal(state.writes.length, 1)
})
