// Launch the unmodified packaged executable paused at its real entry, before any window or user-data access.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import installationQA from '../../apps/desktop/build/installation-qa.cjs'
import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import { setTimeout as timer, clearTimeout } from 'node:timers'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const run = promisify(execFile)
const args = process.argv.slice(2), value = key => args[args.indexOf(key) + 1]
if (!args.includes('--app') || !args.includes('--output')) throw new Error('Usage: smoke-packaged.mjs --app <TestHub.app|TestHub.exe> --output <summary.json>')
const appPath = await fs.realpath(path.resolve(value('--app'))), output = path.resolve(value('--output'))
const executable = process.platform === 'darwin' ? path.join(appPath, 'Contents/MacOS/TestHub') : appPath
await fs.access(executable)
const resources = process.platform === 'darwin' ? path.join(appPath, 'Contents/Resources') : path.join(path.dirname(appPath), 'resources')
const manifest = JSON.parse(await fs.readFile(path.join(resources, 'local-runtime', `${process.platform}-${process.arch}`, 'runtime.json'), 'utf8'))
if (!Number.isSafeInteger(manifest.desktopSchemaVersion) || manifest.desktopSchemaVersion < 1) throw new Error('Packaged runtime does not declare its desktop schema version')
const asar = path.join(resources, 'app.asar'), originalAsarHash = crypto.createHash('sha256').update(await fs.readFile(asar)).digest('hex')
const statePath = args.includes('--lifecycle-state') ? path.resolve(value('--lifecycle-state')) : null
const lifecycleStage = statePath ? value('--lifecycle-stage') : null
if (statePath && !['seed', 'verify'].includes(lifecycleStage)) throw new Error('Lifecycle acceptance requires seed or verify stage')
if (statePath && lifecycleStage === 'seed') await fs.writeFile(statePath, '{}', { flag: 'wx', mode: 0o600 })
let state = statePath && lifecycleStage === 'verify' ? JSON.parse(await fs.readFile(statePath, 'utf8')) : null
if (lifecycleStage === 'verify' && !state?.ownership) throw new Error('Missing owned installation lifecycle state')
let ownership = state?.ownership
try { ownership ||= statePath ? await installationQA.createSession(args.includes('--clean-runner-defaults')) : null }
catch (error) { if (lifecycleStage === 'seed') await fs.rm(statePath); throw error }
const directory = ownership ? await installationQA.verifySession(ownership) : await fs.mkdtemp(path.resolve('.testhub/packaged-smoke-'))
const qaName = ownership?.qaName || 'TestHub-QA-' + crypto.randomUUID(), checks = [], launches = []
const sessionName = ownership?.cleanRunnerDefaults ? 'testhub-desktop-app' : qaName
if (statePath && lifecycleStage === 'seed') {
  // Persist ownership before starting processes, so an interrupted acceptance can clean up safely.
  try { await fs.writeFile(statePath, JSON.stringify({ ownership }), { mode: 0o600 }) }
  catch (error) { await installationQA.removeSession(ownership); await fs.rm(statePath); throw error }
}
let child, finished, inspector, browser, transcript = '', summary, cleanupError
const pass = message => { checks.push(message); console.log('PASS ' + message) }
class Inspector {
  constructor(socket) {
    this.socket = socket; this.id = 0; this.pending = new Map(); this.events = new Map(); this.scripts = new Map()
    socket.addEventListener('message', event => {
      const message = JSON.parse(String(event.data))
      if (message.id) { const call = this.pending.get(message.id); if (!call) return; this.pending.delete(message.id); clearTimeout(call.timeout); if (message.error) call.reject(new Error('Inspector command failed: ' + message.error.message)); else call.resolve(message.result) }
      else {
        if (message.method === 'Debugger.scriptParsed') this.scripts.set(message.params.scriptId, message.params.url)
        const waiting = this.events.get(message.method)
        if (waiting) { this.events.delete(message.method); clearTimeout(waiting.timeout); waiting.resolve(message.params) }
        else if (message.method === 'Debugger.paused') this.paused = message.params
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      const timeout = timer(() => { this.pending.delete(id); reject(new Error('Inspector timeout: ' + method)) }, 30000)
      this.pending.set(id, { resolve, reject, timeout }); this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  event(method) {
    if (method === 'Debugger.paused' && this.paused) { const value = this.paused; this.paused = undefined; return Promise.resolve(value) }
    return new Promise((resolve, reject) => { const timeout = timer(() => { this.events.delete(method); reject(new Error('Inspector event timeout: ' + method)) }, 30000); this.events.set(method, { resolve, timeout }) })
  }
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { expression, includeCommandLineAPI: true, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error('Packaged main-process verification failed')
    return result.result?.value
  }
  close() { this.socket.close(); for (const call of this.pending.values()) { clearTimeout(call.timeout); call.reject(new Error('Inspector closed')) }; for (const event of this.events.values()) clearTimeout(event.timeout) }
}
function bootstrap(directory, qaName, nativeDirectory, cleanRunnerDefaults) {
  const electron = require('electron'), Module = require('node:module')
  if (!electron.app.isPackaged) throw new Error('Acceptance must use the packaged executable')
  if (!cleanRunnerDefaults) electron.app.setName(qaName)
  if (nativeDirectory) {
    if (electron.app.getPath('userData') !== directory || electron.app.getPath('sessionData') !== directory) throw new Error('Native default application data directory does not match the owned QA directory')
  } else {
    electron.app.setPath('userData', directory); electron.app.setPath('sessionData', directory)
  }
  if (process.platform === 'darwin') electron.app.setActivationPolicy('prohibited')
  globalThis.__testhubPackagedQA = { isPackaged: electron.app.isPackaged, electronVersion: process.versions.electron, resources: process.resourcesPath, executable: process.execPath, data: electron.app.getPath('userData'), created: 0, visibleViolations: 0, unexpectedDialogs: 0 }
  const Original = electron.BrowserWindow
  for (const method of ['show', 'showInactive', 'focus', 'restore']) Original.prototype[method] = function () {}
  const Hidden = new Proxy(Original, { construct(target, args) { const options = args[0] || {}; return Reflect.construct(target, [{ ...options, show: false, focusable: false, webPreferences: { ...options.webPreferences, offscreen: true, backgroundThrottling: false } }], target) } })
  const quiet = new Proxy(electron, { get(target, name, receiver) { return name === 'BrowserWindow' ? Hidden : Reflect.get(target, name, receiver) } })
  const load = Module._load
  Module._load = function (request, parent, main) { return request === 'electron' ? quiet : load.call(this, request, parent, main) }
  for (const method of ['showMessageBox', 'showMessageBoxSync', 'showOpenDialog', 'showOpenDialogSync', 'showSaveDialog', 'showSaveDialogSync', 'showErrorBox']) electron.dialog[method] = function () { globalThis.__testhubPackagedQA.unexpectedDialogs++; throw new Error('Unexpected native dialog in packaged acceptance') }
  electron.app.on('browser-window-created', (_event, window) => { globalThis.__testhubPackagedQA.created++; if (window.isVisible()) { globalThis.__testhubPackagedQA.visibleViolations++; throw new Error('Visible packaged QA window') } })
  electron.app.whenReady().then(() => { if (process.platform === 'darwin') { electron.app.hide(); electron.app.dock?.hide() } })
  globalThis.__testhubQaElectron = electron
  return globalThis.__testhubPackagedQA
}
function safeDiagnostic(value) {
  return String(value).replace(/(?:password|secret|authorization|token|credential)\s*[:=]\s*[^\s,;]+/gi, '[redacted]')
    .replace(/[a-f0-9]{32,}|[A-Za-z0-9+/_=-]{48,}/gi, '[redacted]').slice(0, 2000)
}
async function startupDiagnostics() {
  const result = { pages: browser?.contexts()[0]?.pages().map(p => p.url().split(/[?#]/)[0]) || [], errors: [] }
  const logDirectory = path.join(directory, 'local-workspace/logs')
  try {
    for (const file of (await fs.readdir(logDirectory)).filter(name => name.startsWith('backend-')).slice(-1)) {
      const log = await fs.readFile(path.join(logDirectory, file), 'utf8')
      result.errors = log.split('\n').filter(line => /ERROR|Exception|Caused by:|APPLICATION FAILED|Description:|Reason:/.test(line)).slice(-10).map(safeDiagnostic)
    }
  } catch (error) { if (error.code !== 'ENOENT') result.logReadError = error.code }
  return result
}
async function launch() {
  transcript = ''
  const env = { ...process.env }
  for (const key of Object.keys(env)) if (key.startsWith('TESTHUB_') || ['NODE_OPTIONS', 'ELECTRON_RUN_AS_NODE'].includes(key)) delete env[key]
  env.PATH = process.platform === 'win32' ? path.join(env.SystemRoot || 'C:\\Windows', 'System32') : '/usr/bin:/bin:/usr/sbin:/sbin'
  child = spawn(executable, ['--inspect-brk=0', '--remote-debugging-port=0'], { env, cwd: directory, stdio: ['ignore', 'pipe', 'pipe'] })
  finished = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve) })
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { transcript = (transcript + bytes.toString()).slice(-100000) })
  let endpoint
  for (let n = 0; n < 300; n++) { endpoint = /Debugger listening on (ws:\/\/[^\s]+)/.exec(transcript)?.[1]; if (endpoint) break; if (child.exitCode !== null) throw new Error('Packaged executable exited before Inspector'); await delay(100) }
  if (!endpoint) throw new Error('Packaged Inspector unavailable; Node CLI inspect fuse may be disabled')
  const socket = new globalThis.WebSocket(endpoint)
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  inspector = new Inspector(socket)
  await inspector.send('Debugger.enable'); await inspector.send('Runtime.enable')
  const paused = inspector.event('Debugger.paused'); await inspector.send('Runtime.runIfWaitingForDebugger')
  let pause = await paused
  const entry = frame => /app[.]asar[\\/]src[\\/]main[.]cjs$/.test(inspector.scripts.get(frame.location.scriptId) || frame.url)
  if (!pause.callFrames.some(entry)) {
    await inspector.send('Debugger.setBreakpointByUrl', { lineNumber: 0, urlRegex: 'app[.]asar[\\\\/]src[\\\\/]main[.]cjs$' })
    const next = inspector.event('Debugger.paused'); await inspector.send('Debugger.resume'); pause = await next
  }
  const frame = pause.callFrames.find(entry)
  if (!frame) throw new Error('Did not stop before the unmodified packaged main entry')
  const installed = await inspector.send('Debugger.evaluateOnCallFrame', { callFrameId: frame.callFrameId, expression: `(${bootstrap.toString()})(${JSON.stringify(directory)},${JSON.stringify(qaName)},${Boolean(ownership)},${Boolean(ownership?.cleanRunnerDefaults)})`, returnByValue: true })
  if (installed.exceptionDetails || !installed.result?.value?.isPackaged) throw new Error('Could not isolate packaged launch before entry')
  await inspector.send('Debugger.resume')
  let chrome
  for (let n = 0; n < 900; n++) { chrome = /DevTools listening on (ws:\/\/[^\s]+)/.exec(transcript)?.[1]; if (chrome) break; if (child.exitCode !== null) throw new Error('Packaged executable exited during startup'); await delay(100) }
  if (!chrome) throw new Error('Packaged browser unavailable')
  browser = await chromium.connectOverCDP(chrome)
  const workspaceDeadline = Date.now() + 90000
  for (let n = 0; Date.now() < workspaceDeadline; n++) {
    const page = browser.contexts()[0]?.pages().find(p => p.url().startsWith('http://127.0.0.1:'))
    if (page) { await page.getByText('本地工作区', { exact: true }).first().waitFor(); return page }
    if (n % 10 === 0) {
      const setup = browser.contexts()[0]?.pages().find(p => p.url().startsWith('testhub-desktop://setup/'))
      if (setup) {
        // The setup page may disappear as the main window opens. This diagnostic never replaces the startup assertion.
        const status = await Promise.race([setup.evaluate(async () => (await globalThis.testhubDesktop.loadPreferences()).localStatus).catch(error => ({ probeError: error.name })), delay(1500).then(() => null)])
        if (status?.phase === 'error') throw new Error('Packaged workspace failed: ' + safeDiagnostic(status.message) + ' ' + JSON.stringify(await startupDiagnostics()))
      }
    }
    if (child.exitCode !== null) throw new Error('Packaged executable exited during backend startup')
    await delay(100)
  }
  throw new Error('Packaged local workspace startup exceeded 90 seconds: ' + JSON.stringify(await startupDiagnostics()))
}
async function close() {
  if (!child || child.exitCode !== null) return
  await inspector?.evaluate("globalThis.__testhubQaElectron.app.quit(); true").catch(() => {})
  inspector?.close(); inspector = undefined
  browser = undefined
  const completed = await Promise.race([finished.then(code => ({ code })), delay(30000).then(() => null)])
  if (!completed) { child.kill('SIGTERM'); await finished; throw new Error('Packaged shutdown exceeded 30 seconds') }
  assert.equal(completed.code, 0)
  child = undefined
}
async function hidden() {
  const result = await inspector.evaluate(`({...globalThis.__testhubPackagedQA, windows: globalThis.__testhubQaElectron.BrowserWindow.getAllWindows().map(w=>({visible:w.isVisible(),focused:w.isFocused(),offscreen:w.webContents.isOffscreen()}))})`)
  assert.equal(result.isPackaged, true); assert.equal(result.data, directory); assert.equal(result.visibleViolations, 0); assert.equal(result.unexpectedDialogs, 0)
  assert.equal(result.electronVersion, manifest.electronVersion)
  assert.equal(await fs.realpath(result.resources), await fs.realpath(resources)); assert.equal(await fs.realpath(result.executable), await fs.realpath(executable))
  assert.ok(result.windows.every(w => !w.visible && !w.focused && w.offscreen)); launches.push(result)
}
async function request(page, route, body) {
  return page.evaluate(async ({ route, body }) => {
    let options
    if (body) { const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data; options = { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(body) } }
    const response = await globalThis.fetch(route, options), value = await response.json()
    if (!response.ok) throw new Error(value.message); return value.data
  }, { route, body })
}
try {
  let page = await launch()
  const identity = await request(page, '/api/v1/auth/me'), context = await request(page, '/api/v1/access/context'), project = context.projects[context.organizations[0].id][0].id
  assert.equal((await request(page, '/api/v1/operations/overview')).dataProtection.migrationVersion, 'V' + manifest.desktopSchemaVersion)
  const base = `/api/v1/projects/${project}/functional`
  let requirement
  if (lifecycleStage === 'verify') {
    assert.equal(identity.id, state.identityId)
    assert.equal(base, state.base)
    requirement = (await request(page, base + '/requirements')).find(row => row.id === state.requirement.id)
    assert.deepEqual(requirement, state.requirement)
    const encrypted = await fs.readFile(path.join(directory, 'local-workspace/workspace-secrets.enc'))
    assert.equal(crypto.createHash('sha256').update(encrypted).digest('hex'), state.secretSha256)
    if (state.backendSha256 !== manifest.backendSha256) {
      const marker = JSON.parse(await fs.readFile(path.join(directory, 'local-workspace/workspace-runtime.json'), 'utf8'))
      assert.equal(marker.backendHash, manifest.backendSha256)
      assert.ok((await fs.readdir(directory)).some(name => name.startsWith('workspace-before-upgrade-')))
      await assert.rejects(fs.access(path.join(directory, 'local-workspace.upgrade.json')), { code: 'ENOENT' })
    }
  } else {
    const folder = await request(page, base + '/catalogs', { scope: 'REQUIREMENTS', name: '包首启验收', parentId: null, sortOrder: 0 })
    requirement = await request(page, base + '/requirements', { catalogId: folder.id, requirementCode: 'REQ-PACKAGED', title: '真实包持久化', priority: 'P1', status: 'APPROVED', description: '离线本地数据\n升级验收 UTF-8' })
  }
  assert.ok(identity.id); await hidden(); pass(`unmodified packaged executable uses isPackaged resources and starts native V${manifest.desktopSchemaVersion} workspace with no external development commands`)
  await close(); page = await launch()
  assert.equal((await request(page, '/api/v1/auth/me')).id, identity.id)
  assert.deepEqual((await request(page, base + '/requirements')).find(row => row.id === requirement.id), requirement)
  await hidden(); pass('second genuine packaged launch preserves encrypted identity and exact business record')
  await close(); pass('both genuine packaged launches stay hidden offscreen unfocused and shut down owned runtime processes')
  const sanitized = launches.map(launch => { const result = { ...launch }; delete result.resources; delete result.executable; delete result.data; return result })
  assert.equal(crypto.createHash('sha256').update(await fs.readFile(asar)).digest('hex'), originalAsarHash)
  if (statePath) {
    const secret = await fs.readFile(path.join(directory, 'local-workspace/workspace-secrets.enc'))
    state = { ownership, identityId: identity.id, base, requirement, secretSha256: crypto.createHash('sha256').update(secret).digest('hex'), backendSha256: manifest.backendSha256 }
    await fs.writeFile(statePath, JSON.stringify(state), { mode: 0o600 })
  }
  summary = { passed: true, platform: process.platform, arch: process.arch, isPackaged: true, checks, launches: sanitized, asarUnchanged: true, asarSha256: originalAsarHash, backendSha256: manifest.backendSha256,
    externalCommandsExcluded: ['java', 'node', 'mvn', 'pnpm'], defaultUserDataRedirectedForQA: !ownership, applicationNameChangedForQA: !ownership?.cleanRunnerDefaults, nativeDefaultUserData: Boolean(ownership), exactApplicationDefaults: Boolean(ownership?.cleanRunnerDefaults), installed: false }
} finally {
  await close().catch(() => {})
  if (child && child.exitCode === null) { child.kill('SIGTERM'); await finished }
  const retain = statePath && summary?.passed
  if (!retain) {
    if (ownership) await installationQA.removeSession(ownership)
    else await fs.rm(directory, { recursive: true, force: true })
  }
  if (process.platform === 'darwin' && !retain) {
    try { await run('/usr/bin/security', ['delete-generic-password', '-s', sessionName + ' Safe Storage', '-a', sessionName]) }
    catch (error) { if (error.code !== 44) cleanupError = new Error('Could not remove owned QA Keychain entry') }
  }
}
if (cleanupError) throw cleanupError
await fs.mkdir(path.dirname(output), { recursive: true })
await fs.writeFile(output, JSON.stringify({ ...summary, ownedDataRemoved: !statePath, retainedForInstallationLifecycle: Boolean(statePath) }, null, 2) + '\n')
