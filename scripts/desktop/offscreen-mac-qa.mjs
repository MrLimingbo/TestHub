// Acceptance-only harness: pause the real packaged executable before its entry can open user data or a window.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import { setTimeout as timer, clearTimeout } from 'node:timers'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const run = promisify(execFile)
class Inspector {
  constructor(socket) {
    this.socket = socket; this.id = 0; this.pending = new Map(); this.events = new Map(); this.scripts = new Map()
    socket.addEventListener('close', () => { for (const call of this.pending.values()) { clearTimeout(call.timeout); call.reject(new Error('Inspector disconnected')) }; this.pending.clear() })
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
function bootstrap(directory, name, observeRuntime) {
  const electron = require('electron'), Module = require('node:module')
  if (!electron.app.isPackaged) throw new Error('A real packaged executable is required')
  electron.app.setName(name); electron.app.setPath('userData', directory); electron.app.setPath('sessionData', directory)
  electron.app.setActivationPolicy('prohibited')
  const state = globalThis.__testhubBackground = { isPackaged: true, created: 0, visibleViolations: 0, unexpectedDialogs: 0, confirmations: [] }
  const Original = electron.BrowserWindow
  for (const method of ['show', 'showInactive', 'focus', 'restore']) Original.prototype[method] = function () {}
  const Hidden = new Proxy(Original, { construct(target, args) { const options = args[0] || {}; return Reflect.construct(target, [{ ...options, show: false, focusable: false, webPreferences: { ...options.webPreferences, offscreen: true, backgroundThrottling: false } }], target) } })
  const quiet = new Proxy(electron, { get(target, name, receiver) { return name === 'BrowserWindow' ? Hidden : Reflect.get(target, name, receiver) } })
  if (observeRuntime) {
    const { LocalRuntime } = require(require('node:path').join(process.resourcesPath, 'app.asar/src/local-runtime.cjs'))
    const start = LocalRuntime.prototype.start
    LocalRuntime.prototype.start = async function (...args) { const value = await start.apply(this, args); globalThis.__testhubQaLocalRuntime = this; return value }
  }
  const load = Module._load
  Module._load = function (request, parent, main) { return request === 'electron' ? quiet : load.call(this, request, parent, main) }
  for (const method of ['showMessageBox', 'showMessageBoxSync', 'showOpenDialog', 'showOpenDialogSync', 'showSaveDialog', 'showSaveDialogSync', 'showErrorBox']) electron.dialog[method] = function () { state.unexpectedDialogs++; throw new Error('Unexpected native dialog during offscreen acceptance: ' + method) }
  electron.app.on('browser-window-created', (_event, window) => { state.created++; if (window.isVisible()) { state.visibleViolations++; throw new Error('Visible acceptance window') } })
  electron.app.whenReady().then(() => { electron.app.hide(); electron.app.dock?.hide() })
  globalThis.__testhubQaElectron = electron
  globalThis.__testhubQaRequire = require
  return state
}
export async function offscreenMac(appPath, directory, options = {}) {
  if (process.platform !== 'darwin') throw new Error('Native macOS required')
  const executable = path.join(await fs.realpath(appPath), 'Contents/MacOS/TestHub')
  const name = options.name || 'TestHub-QA-' + crypto.randomUUID()
  if (!/^TestHub-QA-[a-f0-9-]{36}$/.test(name)) throw new Error('Only isolated QA Keychain names are permitted')
  let child, done, inspector, browser, transcript = ''
  async function evaluate(fn, value) {
    return inspector.evaluate(`(${fn.toString()})(globalThis.__testhubQaElectron,${JSON.stringify(value) ?? 'undefined'})`)
  }
  const pages = () => browser.contexts()[0].pages()
  async function pageFor(prefix) {
    for (let n = 0; n < 900; n++) { const page = pages().find(p => p.url().startsWith(prefix)); if (page) return page; if ((child.exitCode !== null || child.signalCode !== null)) throw new Error('Packaged executable exited'); await delay(100) }
    throw new Error('Offscreen page unavailable: ' + prefix)
  }
  async function snapshot() {
    const value = await evaluate(({ BrowserWindow }) => ({ ...globalThis.__testhubBackground, windows: BrowserWindow.getAllWindows().map(w => ({ visible: w.isVisible(), focused: w.isFocused(), offscreen: w.webContents.isOffscreen() })) }))
    assert.equal(value.visibleViolations, 0); assert.equal(value.unexpectedDialogs, 0)
    assert.ok(value.windows.every(w => !w.visible && !w.focused && w.offscreen)); return value
  }
  async function close() {
    let failure
    try {
      if (child && child.exitCode === null && child.signalCode === null) {
        await inspector?.evaluate('globalThis.__testhubQaElectron.app.quit(); true').catch(() => {})
        inspector?.close(); inspector = undefined
        const completed = await Promise.race([done.then(code => ({ code })), delay(30000).then(() => null)])
        if (!completed) { child.kill('SIGTERM'); await done; throw new Error('Offscreen app shutdown exceeded 30 seconds') }
        assert.equal(completed.code, 0)
      }
    } catch (error) { failure = error }
    finally {
      if (child && child.exitCode === null && child.signalCode === null) { child.kill('SIGTERM'); await done }
      try { if (options.cleanKeychain !== false) await run('/usr/bin/security', ['delete-generic-password', '-s', name + ' Safe Storage', '-a', name + ' Key']) } catch (error) { if (error.code !== 44) failure ||= error }
    }
    if (failure) throw failure
  }
  try {
    const env = { ...process.env }
    for (const key of Object.keys(env)) if (key.startsWith('TESTHUB_') || ['NODE_OPTIONS','ELECTRON_RUN_AS_NODE'].includes(key)) delete env[key]
    env.PATH = '/usr/bin:/bin:/usr/sbin:/sbin'
    child = spawn(executable, ['--inspect-brk=0', '--remote-debugging-port=0'], { env, cwd: directory, stdio: ['ignore','pipe','pipe'] })
    done = new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve) })
    for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { transcript = (transcript + bytes.toString()).slice(-100000); if (options.diagnosticLog) void fs.appendFile(options.diagnosticLog, bytes, {mode:0o600}).catch(() => {}) })
    let endpoint
    for (let n = 0; n < 300; n++) { endpoint = /Debugger listening on (ws:\/\/[^\s]+)/.exec(transcript)?.[1]; if (endpoint) break; if ((child.exitCode !== null || child.signalCode !== null)) throw new Error('Inspector startup failed'); await delay(100) }
    if (!endpoint) throw new Error('Packaged Inspector unavailable')
    const socket = new globalThis.WebSocket(endpoint)
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, {once:true}); socket.addEventListener('error', reject, {once:true}) })
    inspector = new Inspector(socket)
    await inspector.send('Debugger.enable'); await inspector.send('Runtime.enable')
    const paused = inspector.event('Debugger.paused'); await inspector.send('Runtime.runIfWaitingForDebugger')
    let pause = await paused
    const entry = f => /app[.]asar[\\/]src[\\/]main[.]cjs$/.test(inspector.scripts.get(f.location.scriptId) || f.url)
    if (!pause.callFrames.some(entry)) {
      await inspector.send('Debugger.setBreakpointByUrl', {lineNumber:0,urlRegex:'app[.]asar[\\\\/]src[\\\\/]main[.]cjs$'})
      const next = inspector.event('Debugger.paused'); await inspector.send('Debugger.resume'); pause = await next
    }
    const frame = pause.callFrames.find(entry)
    if (!frame) throw new Error('Could not pause before packaged main entry')
    const initialized = await inspector.send('Debugger.evaluateOnCallFrame', {callFrameId:frame.callFrameId,expression:`(${bootstrap.toString()})(${JSON.stringify(directory)},${JSON.stringify(name)},${options.observeRuntime === true})`,returnByValue:true})
    if (initialized.exceptionDetails || !initialized.result?.value?.isPackaged) throw new Error('Offscreen isolation failed before entry')
    await inspector.send('Debugger.resume')
    let endpointChrome
    for (let n = 0; n < 900; n++) { endpointChrome = /DevTools listening on (ws:\/\/[^\s]+)/.exec(transcript)?.[1]; if (endpointChrome) break; if ((child.exitCode !== null || child.signalCode !== null)) throw new Error('Packaged browser startup failed'); await delay(100) }
    if (!endpointChrome) throw new Error('Packaged browser unavailable')
    browser = await chromium.connectOverCDP(endpointChrome)
    const local = await pageFor('http://127.0.0.1:')
    await local.getByText('本地工作区',{exact:true}).first().waitFor()
    await snapshot()
    return { evaluate, pageFor, pages, snapshot, close, local, processState: () => ({exitCode:child.exitCode,signalCode:child.signalCode}) }
  } catch (error) { await close(); throw error }
}
