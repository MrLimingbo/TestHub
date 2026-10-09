'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const test = require('node:test')
const { normalizeOrigins, readExecutionOrigins, writeExecutionOrigins } = require('../app/src/local-execution.cjs')
test('normalizes explicit public, intranet and loopback origins without widening scope', () => {
  assert.deepEqual(normalizeOrigins(['HTTPS://Api.Example.com:443/', 'https://api.example.com', 'http://localhost:3000', 'http://[::1]:3000', 'http://192.168.1.2:8080']),
    ['https://api.example.com', 'http://localhost:3000', 'http://[::1]:3000', 'http://192.168.1.2:8080'])
  assert.deepEqual(normalizeOrigins([]), [])
  for (const value of ['file:///etc/passwd','https://user:pass@example.test','https://example.test/path','https://example.test?token=a','https://*.example.test','https://example.test#x']) assert.throws(()=>normalizeOrigins([value]))
  assert.throws(()=>normalizeOrigins(Array(51).fill('https://example.test')))
})
test('execution configuration persists independently and invalid updates retain prior authorization', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-execution-'))
  t.after(()=>fs.rm(directory,{recursive:true,force:true}))
  assert.deepEqual(await readExecutionOrigins(directory), [])
  await fs.writeFile(path.join(directory,'desktop-preferences.json'),'existing-server-configuration')
  await writeExecutionOrigins(directory,['http://localhost:3000'])
  await assert.rejects(writeExecutionOrigins(directory,['*']))
  assert.deepEqual(await readExecutionOrigins(directory),['http://localhost:3000'])
  assert.equal(await fs.readFile(path.join(directory,'desktop-preferences.json'),'utf8'),'existing-server-configuration')
  await writeExecutionOrigins(directory,[])
  assert.deepEqual(await readExecutionOrigins(directory),[])
  await fs.writeFile(path.join(directory,'local-execution.json'),'broken')
  await assert.rejects(readExecutionOrigins(directory))
})

test('Appium authorization is independent, bounded and preserved by legacy origin changes', async t => {
  const { normalizeAppiumServer, readExecutionSettings, writeExecutionSettings } = require('../app/src/local-execution.cjs')
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-appium-settings-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  assert.deepEqual(await readExecutionSettings(directory), { allowedOrigins: [], appiumServerUrl: '' })
  for (const value of ['http://192.168.1.1:4723', 'https://user:pass@farm.test', 'https://farm.test?token=x', 'file:///tmp']) assert.throws(() => normalizeAppiumServer(value))
  assert.equal(normalizeAppiumServer('http://[::1]:4723/wd/hub/'), 'http://[::1]:4723/wd/hub')
  await writeExecutionSettings(directory, { allowedOrigins: [], appiumServerUrl: 'http://localhost:4723/wd/hub' })
  await writeExecutionOrigins(directory, ['https://api.example.test'])
  assert.deepEqual(await readExecutionSettings(directory), { allowedOrigins: ['https://api.example.test'], appiumServerUrl: 'http://localhost:4723/wd/hub' })
  await assert.rejects(writeExecutionSettings(directory, { allowedOrigins: [], appiumServerUrl: 'http://unsafe.test' }))
  assert.equal((await readExecutionSettings(directory)).appiumServerUrl, 'http://localhost:4723/wd/hub')
})

test('native combined settings use their own lock and can restart without a transfer self-deadlock', async () => {
  const vm = require('node:vm'), handlers = new Map(), source = await fs.readFile(path.join(__dirname, '../app/src/main.cjs'), 'utf8')
  const sender = { mainFrame: { url: 'testhub-desktop://setup/execution.html' } }, calls = []
  const context = { setupWindow: { webContents: sender }, isExecutionSettingsSender: event => event.senderFrame === sender.mainFrame,
    ipcMain: { handle: (name, handler) => handlers.set(name, handler) }, app: { getPath: () => '/qa/user-data' },
    backupOperation: undefined, transferOperation: undefined, savingExecutionSettings: false, preparingJmeter: false,
    preparingBrowser: false, localBrowsers: undefined, enteringLocalWorkspace: undefined,
    normalizeOrigins, normalizeAppiumServer: require('../app/src/local-execution.cjs').normalizeAppiumServer,
    writeExecutionSettings: async (_, value) => calls.push(value), readExecutionSettings: async () => ({}),
    localRuntime: { phase: 'ready', stop: async () => { calls.push('stop') } } }
  context.enterLocalWorkspace = async () => { assert.equal(context.transferOperation, undefined); assert.equal(context.savingExecutionSettings, true); calls.push('restart') }
  vm.createContext(context)
  vm.runInContext(source.slice(source.indexOf("ipcMain.handle('testhub-desktop:execution:settings'"), source.indexOf('function requireBackupSender')), context)
  const save = handlers.get('testhub-desktop:execution:save-settings'), event = { sender, senderFrame: sender.mainFrame }
  await save(event, { allowedOrigins: [], appiumServerUrl: 'http://localhost:4723' })
  assert.equal(calls.length, 3); assert.equal(calls[2], 'restart'); assert.equal(context.savingExecutionSettings, false)
  assert.equal(context.localRuntime.appiumServerUrl, 'http://localhost:4723')
  context.transferOperation = Promise.resolve()
  await assert.rejects(save(event, { allowedOrigins: [] })); assert.equal(calls.length, 3)
  await assert.rejects(save({ sender: {}, senderFrame: sender.mainFrame }, { allowedOrigins: [] }))
})

test('account settings wait for startup to finish and revalidate the requesting window', async () => {
  const vm = require('node:vm'), handlers = new Map(), source = await fs.readFile(path.join(__dirname, '../app/src/main.cjs'), 'utf8')
  const sender = { mainFrame: {} }; let finishStartup, opened = 0, trusted = true
  const context = { ipcMain: { handle: (name, handler) => handlers.set(name, handler) },
    isTrustedRemoteSender: () => trusted, openSetupWindow: () => { opened++ },
    enteringLocalWorkspace: new Promise(resolve => { finishStartup = resolve }) }
  vm.createContext(context)
  vm.runInContext(source.slice(source.indexOf("ipcMain.handle('testhub-desktop:workspace:connect'"), source.indexOf('async function getLocalEngines')), context)
  const connect = handlers.get('testhub-desktop:workspace:connect'), event = { sender, senderFrame: sender.mainFrame }
  const pending = connect(event); assert.equal(opened, 0); finishStartup(); await pending; assert.equal(opened, 1)
  context.enteringLocalWorkspace = new Promise(resolve => { finishStartup = resolve })
  const switched = connect(event); trusted = false; finishStartup(); await assert.rejects(switched, /工作区已切换/); assert.equal(opened, 1)
})
