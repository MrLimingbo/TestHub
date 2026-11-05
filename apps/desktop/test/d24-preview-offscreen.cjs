'use strict'
// Isolated Electron acceptance of the production preview coordinator, IPC, preload and native page.
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const assert = require('node:assert/strict')
const { setTimeout: delay } = require('node:timers/promises')
const root = path.resolve(__dirname, '../../..')

async function launch() {
  const { spawn } = require('node:child_process')
  const output = process.argv[2]
  if (!output) throw new Error('Provide an explicit sanitized summary path')
  const owned = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-d24-electron-'))
  const electron = require(path.join(root, 'apps/desktop/node_modules/electron'))
  const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE
  let child, transcript = ''
  try {
    child = spawn(electron, [module.filename, '--native', owned], { env, stdio: ['ignore', 'pipe', 'pipe'] })
    for (const stream of [child.stdout, child.stderr]) stream.on('data', value => { transcript = (transcript + value).slice(-200000) })
    const timeout = globalThis.setTimeout(() => child.kill('SIGTERM'), 45000)
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve) }).finally(() => globalThis.clearTimeout(timeout))
    if (code !== 0) throw new Error('Offscreen acceptance failed: ' + transcript.slice(-5000))
    const summary = JSON.parse(await fs.readFile(path.join(owned, 'summary.json'), 'utf8'))
    assert.equal(summary.passed, 4)
    await fs.writeFile(output, JSON.stringify({ ...summary, ownedWorkspaceRemoved: true }, null, 2) + '\n')
    console.log(JSON.stringify({ passed: summary.passed, failed: 0, visibleViolations: summary.visibleViolations, cases: summary.cases }))
  } finally {
    if (child && child.exitCode === null && child.signalCode === null) { child.kill('SIGTERM'); await new Promise(resolve => child.once('exit', resolve)) }
    await fs.rm(owned, { recursive: true, force: true })
  }
}
async function native() {
  const { app, BrowserWindow, dialog, ipcMain, protocol, net, session } = require('electron')
  const { pathToFileURL } = require('node:url')
  const http = require('node:http')
  const { ReadOperations } = require(path.join(root, 'apps/desktop/app/src/read-operation.cjs'))
  const { installProjectSync } = require(path.join(root, 'apps/desktop/app/src/project-sync-ipc.cjs'))
  const owned = process.argv[3]
  app.setPath('userData', owned); app.setPath('sessionData', owned)
  app.on('window-all-closed', () => {})
  if (process.platform === 'darwin') app.setActivationPolicy('prohibited')
  const windows = [], cases = [], clients = []; let visibleViolations = 0, unexpectedDialogs = 0, writes = 0, setup, locked = false, mode = 'blocked', releaseLate
  for (const name of ['show', 'showInactive', 'focus', 'restore']) BrowserWindow.prototype[name] = () => { visibleViolations++; throw new Error('Unexpected window activation') }
  for (const name of ['showMessageBox', 'showMessageBoxSync', 'showOpenDialog', 'showSaveDialog', 'showErrorBox']) dialog[name] = () => { unexpectedDialogs++; throw new Error('Unexpected native dialog') }
  app.on('browser-window-created', (_event, window) => { windows.push(window); if (window.isVisible() || window.isFocused()) visibleViolations++ })
  protocol.registerSchemesAsPrivileged([{ scheme: 'testhub-desktop', privileges: { standard: true, secure: true, supportFetchAPI: true } }])
  const context = { organizations: [{ id: 'org', name: 'QA' }], projects: { org: [{ id: 'src', name: 'Source' }, { id: 'dst', name: 'Target' }] } }
  const actor = { id: 'actor', account: 'qa', displayName: 'QA' }
  const server = http.createServer((request, response) => {
    if (request.method !== 'GET') writes++
    if (mode === 'blocked') return
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ data: request.url.endsWith('/me') ? actor : context }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const endpoint = 'http://127.0.0.1:' + server.address().port
  const manager = new ReadOperations({ trusted: event => event.sender === setup?.webContents && event.senderFrame.url.startsWith('testhub-desktop://setup/') })
  const exclusive = async operation => { if (locked) throw new Error('busy'); locked = true; try { return await operation() } finally { locked = false } }
  class Client {
    constructor(options) {
      Object.assign(this, options); clients.push(this)
      for (const side of ['local', 'remote']) {
        const original = this[side]
        this[side] = async (...args) => { if (mode === 'uncooperative') { await delay(6500); return args[0].endsWith('/me') ? actor : context } return original(...args) }
      }
    }
    async load() { this.state = { binding: null, conflicts: [] } }
    async status() { return this.state }
    async preview() {
      const late = mode === 'late'; if (late) await new Promise(resolve => { releaseLate = resolve })
      this.plan = { token: late ? 'late-preview' : 'fresh-preview' }
      return { token: this.plan.token, upload: 0, download: 0, conflictCount: 0, excludedExecutions: 0, excludedFiles: 0 }
    }
  }
  ipcMain.handle('testhub-desktop:read-operation:status', event => manager.status(event))
  ipcMain.handle('testhub-desktop:read-operation:cancel', (event, id) => manager.cancel(event, id))
  installProjectSync({ ipcMain, dialog, session, directory: owned, origin: 'testhub-desktop://setup', windows: () => ({ setup }), runtime: async () => ({ codec: {}, url: endpoint }), server: async () => endpoint, exclusive, readOperations: manager }, { Client, Store: class {}, channel: 'functional-sync', page: 'functional-sync.html', title: 'QA同步' })
  const wait = async (condition, limit = 10000) => { const start = Date.now(); while (!await condition()) { if (Date.now() - start > limit) throw new Error('Offscreen condition timed out'); await delay(25) } }
  const js = (window, code) => window.webContents.executeJavaScript(code)
  const create = async () => {
    const window = new BrowserWindow({ show: false, focusable: false, webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, offscreen: true, backgroundThrottling: false, preload: path.join(root, 'apps/desktop/app/src/setup-preload.cjs') } })
    setup = window
    window.on('close', event => { if (locked) { event.preventDefault(); manager.closeAfterCancellation(window) } })
    await window.loadURL('testhub-desktop://setup/functional-sync.html')
    assert.equal(window.isVisible(), false); assert.equal(window.isFocused(), false); assert.equal(window.webContents.isOffscreen(), true)
    return window
  }
  const eventFor = window => ({ sender: window.webContents, senderFrame: window.webContents.mainFrame })
  const running = window => wait(async () => {
    const id = manager.status(eventFor(window)).id
    return js(window, "Boolean(document.querySelector('#read-operation button') && !document.querySelector('#read-operation button').disabled && !document.querySelector('#read-operation').hidden && document.querySelector('#read-operation').dataset.operationId === " + JSON.stringify(id) + ')')
  })
  const cancel = async window => {
    const start = Date.now(); await js(window, "document.querySelector('#read-operation button').click()")
    await wait(async () => js(window, "document.querySelector('#read-operation').textContent.includes('正在取消')"), 1000)
    await wait(() => ['CANCELLING', 'WAITING', 'CANCELLED'].includes(manager.status(eventFor(window)).state), 1000)
    return { start, feedbackMilliseconds: Date.now() - start }
  }
  try {
    await app.whenReady(); if (process.platform === 'darwin') { app.hide(); app.dock?.hide() }
    protocol.handle('testhub-desktop', request => {
      const candidate = path.resolve(root, 'apps/desktop/app/src/ui', new URL(request.url).pathname.slice(1))
      if (!candidate.startsWith(path.join(root, 'apps/desktop/app/src/ui') + path.sep)) throw new Error('Invalid QA asset')
      return net.fetch(pathToFileURL(candidate).href)
    })
    let window = await create(); await running(window)
    const timing = await cancel(window); await wait(() => !locked, 5000)
    const terminationMilliseconds = Date.now() - timing.start; assert.ok(timing.feedbackMilliseconds <= 1000 && terminationMilliseconds <= 5000)
    await wait(async () => js(window, "document.querySelector('#read-operation').textContent.includes('预览已取消')"))
    cases.push({ id: 'E01', status: 'PASS', feedbackMilliseconds: timing.feedbackMilliseconds, terminationMilliseconds }); window.close(); await wait(() => window.isDestroyed())

    window = await create(); await running(window); const closeStart = Date.now(); window.close(); await wait(() => window.isDestroyed(), 5000)
    assert.equal(locked, false); cases.push({ id: 'E02', status: 'PASS', closeMilliseconds: Date.now() - closeStart })

    mode = 'uncooperative'; window = await create(); await running(window); const slow = await cancel(window)
    await wait(async () => js(window, "document.querySelector('#read-operation').textContent.includes('终止未确认')"), 6000)
    assert.equal(locked, true); const waitingMilliseconds = Date.now() - slow.start; assert.ok(waitingMilliseconds >= 5000)
    window.close(); assert.equal(window.isDestroyed(), false); await wait(() => window.isDestroyed(), 3000); assert.equal(locked, false)
    cases.push({ id: 'E03', status: 'PASS', feedbackMilliseconds: slow.feedbackMilliseconds, waitingMilliseconds, terminationMilliseconds: Date.now() - slow.start })

    mode = 'fast'; window = await create(); await wait(async () => js(window, "document.querySelector('#account').textContent.includes('QA') && !document.querySelector('#prepare').disabled"))
    mode = 'late'; await js(window, "window.pendingPreview = window.testhubDesktop.previewFunctionalSync({sourceId:'src',targetId:'dst'}).then(v=>({value:v}),e=>({error:e.message})); void 0")
    await running(window); await cancel(window)
    const previous = window, newer = await create(); await js(newer, "document.querySelector('#preview').textContent='新窗口草稿'")
    releaseLate()
    await wait(() => !locked); await delay(100); assert.equal(clients[0].plan, undefined)
    assert.equal(await js(newer, "document.querySelector('#preview').textContent"), '新窗口草稿')
    assert.ok((await js(previous, 'window.pendingPreview')).error.includes('预览已取消'))
    await assert.rejects(js(newer, "window.testhubDesktop.bindFunctionalSync('late-preview')"), /预览已失效/)
    mode = 'fast'; const fresh = await js(newer, "window.testhubDesktop.previewFunctionalSync({sourceId:'src',targetId:'dst'})"); assert.equal(fresh.token, 'fresh-preview')
    cases.push({ id: 'E04', status: 'PASS', latePlanDiscarded: true, newWindowDraftPreserved: true, freshTokenValid: true }); previous.close(); newer.close()
    assert.equal(writes, 0); assert.equal(visibleViolations, 0); assert.equal(unexpectedDialogs, 0)
    await fs.writeFile(path.join(owned, 'summary.json'), JSON.stringify({ passed: 4, failed: 0, cases, windowsCreated: windows.length, visibleViolations, unexpectedDialogs, businessWrites: writes, scope: 'Production helper + IPC + preload + page in real offscreen Electron, controlled transports; not an installed app upgrade' }))
  } finally {
    for (const window of windows) if (!window.isDestroyed()) window.close()
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
    app.quit()
  }
}
;(process.versions.electron ? native() : launch()).catch(error => { console.error(error); process.exitCode = 1; if (process.versions.electron) require('electron').app.exit(1) })
