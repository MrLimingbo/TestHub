// Launch the unmodified packaged executable paused at its real entry, before any window or user-data access.
import fs from 'node:fs/promises'
import http from 'node:http'
import { createRequire } from 'node:module'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import { setTimeout as timer, clearTimeout } from 'node:timers'
import { chromium } from '../../services/playwright-runner/node_modules/playwright/index.mjs'
const run = promisify(execFile)
const args = process.argv.slice(2), value = key => args[args.indexOf(key) + 1]
if (!args.includes('--app') || !args.includes('--output')) throw new Error('Usage: accept-first-downloads.mjs --app <TestHub.app> --output <new-summary.json>')
if (process.platform !== 'darwin') throw new Error('First-download packaged acceptance currently requires a native Mac')
const appPath = await fs.realpath(path.resolve(value('--app'))), output = path.resolve(value('--output'))
const executable = process.platform === 'darwin' ? path.join(appPath, 'Contents/MacOS/TestHub') : appPath
await fs.access(executable)
const resources = process.platform === 'darwin' ? path.join(appPath, 'Contents/Resources') : path.join(path.dirname(appPath), 'resources')
const manifest = JSON.parse(await fs.readFile(path.join(resources, 'local-runtime', `${process.platform}-${process.arch}`, 'runtime.json'), 'utf8'))
if (!Number.isSafeInteger(manifest.desktopSchemaVersion) || manifest.desktopSchemaVersion < 1) throw new Error('Packaged runtime does not declare its desktop schema version')
const asar = path.join(resources, 'app.asar'), originalAsarHash = crypto.createHash('sha256').update(await fs.readFile(asar)).digest('hex')
await fs.mkdir(path.dirname(output), { recursive: true })
const reservation = await fs.open(output, 'wx', 0o600)
try { await reservation.writeFile(JSON.stringify({passed:false,state:'in-progress'}) + '\n') }
finally { await reservation.close() }
const directory = await fs.mkdtemp(path.resolve('.testhub/first-download-qa-'))
const qaName = 'TestHub-QA-' + crypto.randomUUID(), sessionName = qaName, checks = [], launches = [], networkSnapshots = []
await fs.writeFile(path.join(directory, '.first-download-qa-owner.json'), JSON.stringify({qaName}), {flag:'wx',mode:0o600})
let networkMode = 'online'
let child, finished, inspector, browser, transcript = ''
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
function bootstrap(directory, qaName, nativeDirectory, cleanRunnerDefaults, initialNetworkMode) {
  const electron = require('electron'), Module = require('node:module')
  // QA-only transport observation/faults; successful downloads retain the original HTTPS response bytes.
  const downloadState = globalThis.__testhubDownloadQA = { mode: initialNetworkMode, requests: [] }
  const componentHosts = new Set(['downloads.apache.org', 'archive.apache.org', 'cdn.playwright.dev', 'playwright.download.prss.microsoft.com'])
  const monitorFetch = original => async function (input, options) {
    const address = typeof input === 'string' ? input : input?.url
    let url; try { url = new URL(address) } catch { return original.call(this, input, options) }
    if (url.protocol !== 'https:' || !componentHosts.has(url.hostname)) return original.call(this, input, options)
    const record = { url: url.origin + url.pathname, mode: downloadState.mode, receivedBytes: 0, complete: false }
    downloadState.requests.push(record)
    if (record.mode === 'offline') { record.injectedOffline = true; throw new Error('QA component network offline') }
    let response
    try { response = await original.call(this, input, options) }
    catch (error) { record.errorName = error.name; throw error }
    record.status = response.status
    if (!response.ok || !response.body) return response
    const hash256 = require('node:crypto').createHash('sha256'), hash512 = require('node:crypto').createHash('sha512')
    const body = {
      cancel: () => response.body.cancel(),
      async *[Symbol.asyncIterator]() {
        try {
          for await (const bytes of response.body) {
            record.receivedBytes += bytes.length; hash256.update(bytes); hash512.update(bytes)
            if (record.mode === 'interrupt' && record.receivedBytes >= 128 * 1024) { record.injectedInterruption = true; throw new Error('QA component stream interrupted') }
            if (record.mode === 'cancel') await new Promise(resolve => globalThis.setTimeout(resolve, 30))
            yield bytes
          }
          record.complete = true; record.sha256 = hash256.digest('hex'); record.sha512 = hash512.digest('hex')
        } catch (error) { record.errorName = error.name; throw error }
      },
    }
    return { ok: response.ok, status: response.status, headers: response.headers, url: response.url, body }
  }

  if (!electron.app.isPackaged) throw new Error('Acceptance must use the packaged executable')
  if (!cleanRunnerDefaults) electron.app.setName(qaName)
  if (nativeDirectory) {
    if (electron.app.getPath('userData') !== directory || electron.app.getPath('sessionData') !== directory) throw new Error('Native default application data directory does not match the owned QA directory')
  } else {
    electron.app.setPath('userData', directory); electron.app.setPath('sessionData', directory)
  }
  if (process.platform === 'darwin') electron.app.setActivationPolicy('prohibited')
  globalThis.__testhubPackagedQA = { isPackaged: electron.app.isPackaged, electronVersion: process.versions.electron, resources: process.resourcesPath, executable: process.execPath, data: electron.app.getPath('userData'), created: 0, visibleViolations: 0, unexpectedDialogs: 0 }
  globalThis.__testhubPackagedQA.failedLocalRequests = []
  const traceFetch = (original, transport) => async function (...args) {
    const started = Date.now()
    try { return await original.apply(this, args) }
    catch (error) {
      const address = typeof args[0] === 'string' ? args[0] : args[0]?.url
      const route = /^http:\/\/127\.0\.0\.1:\d+(\/actuator\/health|\/internal\/desktop\/v1\/(?:status|session))(?:[?#]|$)/.exec(address || '')?.[1]
      if (route) {
        const requests = globalThis.__testhubPackagedQA.failedLocalRequests
        requests.push({ transport, path: route, error: error.name, elapsedMilliseconds: Date.now() - started })
        if (requests.length > 8) requests.shift()
      }
      throw error
    }
  }
  globalThis.fetch = monitorFetch(traceFetch(globalThis.fetch, 'main'))
  const Original = electron.BrowserWindow
  for (const method of ['show', 'showInactive', 'focus', 'restore']) Original.prototype[method] = function () {}
  const Hidden = new Proxy(Original, { construct(target, args) { const options = args[0] || {}; return Reflect.construct(target, [{ ...options, show: false, focusable: false, webPreferences: { ...options.webPreferences, offscreen: true, backgroundThrottling: false } }], target) } })
  const quiet = new Proxy(electron, { get(target, name, receiver) { return name === 'BrowserWindow' ? Hidden : Reflect.get(target, name, receiver) } })
  const load = Module._load
  Module._load = function (request, parent, main) { return request === 'electron' ? quiet : load.call(this, request, parent, main) }
  for (const method of ['showMessageBox', 'showMessageBoxSync', 'showOpenDialog', 'showOpenDialogSync', 'showSaveDialog', 'showSaveDialogSync', 'showErrorBox']) electron.dialog[method] = function () { globalThis.__testhubPackagedQA.unexpectedDialogs++; throw new Error('Unexpected native dialog in packaged acceptance') }
  electron.app.on('browser-window-created', (_event, window) => { globalThis.__testhubPackagedQA.created++; if (window.isVisible()) { globalThis.__testhubPackagedQA.visibleViolations++; throw new Error('Visible packaged QA window') } })
  electron.app.whenReady().then(() => {
    if (process.platform === 'darwin') { electron.app.hide(); electron.app.dock?.hide() }
    const prototype = Object.getPrototypeOf(electron.session.defaultSession)
    globalThis.__testhubPackagedQA.sessionFetchTracing = typeof prototype.fetch === 'function'
    if (typeof prototype.fetch === 'function') prototype.fetch = monitorFetch(traceFetch(prototype.fetch, 'session'))
  })
  globalThis.__testhubQaElectron = electron
  return globalThis.__testhubPackagedQA
}
function safeDiagnostic(value) {
  return String(value).replace(/(?:password|secret|authorization|token|credential)\s*[:=]\s*[^\s,;]+/gi, '[redacted]')
    .replace(/[a-f0-9]{32,}|[A-Za-z0-9+/_=-]{48,}/gi, '[redacted]').slice(0, 2000)
}
async function startupDiagnostics() {
  const result = { pages: browser?.contexts()[0]?.pages().map(p => p.url().split(/[?#]/)[0]) || [], errors: [] }
  try { Object.assign(result, await inspector.evaluate('({failedLocalRequests:globalThis.__testhubPackagedQA.failedLocalRequests,sessionFetchTracing:globalThis.__testhubPackagedQA.sessionFetchTracing})')) }
  catch (error) { result.requestTraceError = safeDiagnostic(error.message) }
  const logDirectory = path.join(directory, 'local-workspace/logs')
  try {
    for (const file of (await fs.readdir(logDirectory)).filter(name => name.startsWith('backend-')).slice(-1)) {
      const log = await fs.readFile(path.join(logDirectory, file), 'utf8')
      result.errors = log.split('\n').filter(line => /ERROR|Exception|Caused by:|APPLICATION FAILED|Description:|Reason:/.test(line)).slice(-10).map(safeDiagnostic)
    }
  } catch (error) { if (error.code !== 'ENOENT') result.logReadError = error.code }
  return result
}
async function launchPackaged() {
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
  const installed = await inspector.send('Debugger.evaluateOnCallFrame', { callFrameId: frame.callFrameId, expression: `(${bootstrap.toString()})(${JSON.stringify(directory)},${JSON.stringify(qaName)},false,false,${JSON.stringify(networkMode)})`, returnByValue: true })
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
const require = createRequire(import.meta.url)
const { loadBundle } = require('../../apps/desktop/app/src/local-runtime.cjs')
const { writeExecutionOrigins } = require('../../apps/desktop/app/src/local-execution.cjs')
const { LocalBrowsers } = require('../../apps/desktop/app/src/local-browsers.cjs')
const { JMETER } = require('../../apps/desktop/app/src/local-engines.cjs')
const bundle = await loadBundle(path.join(resources, 'local-runtime', `${process.platform}-${process.arch}`))
const componentManager = new LocalBrowsers({ directory: path.join(directory, 'browser-engines'), bundle })
const plans = Object.fromEntries(await Promise.all(['chromium', 'firefox', 'webkit'].map(async type => [type, await componentManager.plan(type)])))
const engines = path.join(directory, 'engines'), cache = plans.chromium.cache
const reports = [], progress = [], queued = []
let page, projectId, owner, outcome, failure, targetHits = 0, forbiddenHits = 0
const forbidden = http.createServer((_req, res) => { forbiddenHits++; res.end('forbidden') })
const target = http.createServer((_req, res) => { targetHits++; res.setHeader('content-type', 'text/html; charset=utf-8'); res.end('<!doctype html><title>TestHub first download</title><p id="ready">首次组件验收 local-component-ok</p>') })
await new Promise(resolve => forbidden.listen(0, '127.0.0.1', resolve))
await new Promise(resolve => target.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${target.address().port}`, forbiddenOrigin = `http://127.0.0.1:${forbidden.address().port}`
await writeExecutionOrigins(directory, [origin])
const base = () => `/api/v1/projects/${projectId}`
async function read(route) { return page.evaluate(async route => {
  const response = await globalThis.fetch(route), value = await response.json()
  if (!response.ok) throw new Error(value.message || 'Read failed'); return value.data
}, route) }
async function mutate(route, body, method = 'POST') { return page.evaluate(async ({route, body, method}) => {
  const csrf = (await (await globalThis.fetch('/api/v1/auth/csrf')).json()).data
  const response = await globalThis.fetch(route, {method, headers:{'content-type':'application/json', [csrf.headerName]:csrf.token}, body:JSON.stringify(body)})
  const value = await response.json(); if (!response.ok) throw new Error(value.message || 'Mutation failed'); return value.data
}, {route, body, method}) }
async function until(probe, seconds = 30) {
  const deadline = Date.now() + seconds * 1000
  while (Date.now() < deadline) { const result = await probe(); if (result) return result; await delay(100) }
  throw new Error('First-download acceptance condition timed out')
}
const netState = () => inspector.evaluate('globalThis.__testhubDownloadQA')
const mode = value => inspector.evaluate(`globalThis.__testhubDownloadQA.mode=${JSON.stringify(value)}; true`)
const status = kind => page.evaluate(kind => kind === 'browser' ? globalThis.testhubDesktop.browserStatus() : globalThis.testhubDesktop.jmeterStatus(), kind)
async function launch() { page = await launchPackaged(); return page }
async function shutdown() {
  if (!child || child.exitCode !== null) return
  await hidden(); networkSnapshots.push(await netState()); await close()
}
async function emptyPartialDirectories() {
  assert.deepEqual(await fs.readdir(cache), [])
  assert.deepEqual(await fs.readdir(engines), [])
}
async function scenario(type) {
  const api = base() + '/ui-automation'
  const profile = await mutate(api + '/browser-profiles', {name:'first-use-'+type, browser:type, baseUrl:origin, viewportWidth:800, viewportHeight:600, enabled:true})
  return mutate(api + '/scenarios', {browserProfileId:profile.id, name:'first-use-'+type, description:'Owned loopback first-download QA', failureStrategy:'STOP', variables:{}, steps:[
    {id:'open', type:'NAVIGATE', name:'打开', url:'/', timeoutMillis:10000},
    {id:'assert', type:'ASSERT_TEXT', name:'断言', selector:'#ready', expectedText:'首次组件验收', timeoutMillis:10000},
    {id:'capture', type:'SCREENSHOT', name:'截图', timeoutMillis:10000},
  ]})
}
async function checkReport(module, execution, label) {
  const api = base() + '/' + module
  const report = await until(async () => {
    const value = await read(api + `/executions/${execution.id}/report`), state = value.execution.status
    if (['FAILED', 'CANCELLED', 'TIMED_OUT', 'REJECTED'].includes(state)) throw new Error(label + ' unexpected terminal execution state ' + state)
    return state === 'SUCCEEDED' ? value : null
  }, 240)
  const artifacts = module === 'performance' ? await read(api + `/executions/${execution.id}/artifacts`) : report.artifacts
  const kinds = [...new Set(artifacts.map(a => a.artifactType))].sort()
  assert.deepEqual(kinds, module === 'performance' ? ['JMETER_JTL', 'JMETER_LOG'] : ['SCREENSHOT', 'TRACE', 'VIDEO'])
  for (const artifact of artifacts) {
    // Use the actual Electron session and its desktop authorization hook, not a separate APIRequestContext.
    const bytes = globalThis.Buffer.from(await page.evaluate(async route => {
      const response = await globalThis.fetch(route)
      if (!response.ok) throw new Error('Artifact request rejected: HTTP ' + response.status)
      return Array.from(new Uint8Array(await response.arrayBuffer()))
    }, api + `/executions/${execution.id}/artifacts/${artifact.id}/download`))
    assert.equal(bytes.length, artifact.sizeBytes); assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), artifact.checksum)
  }
  if (module === 'performance') { assert.equal(report.summary.errorCount, 0); assert.ok(report.summary.sampleCount > 0) }
  else assert.equal(report.summary.passedSteps, 3)
  reports.push({label, summary:report.summary, artifactTypes:kinds, artifactChecksumsMatched:true})
  pass(label + ': real execution and report artifact integrity')
}
async function ensure(kind, type = 'chromium') {
  return page.evaluate(async ({kind, type, origin}) => {
    try {
      if (kind === 'browser') await globalThis.testhubDesktop.ensureBrowser({browser:type, origin})
      else await globalThis.testhubDesktop.ensureJmeter([origin])
      return {ok:true}
    } catch(error) { return {ok:false, message:error.message} }
  }, {kind, type, origin})
}
async function submitUi(kind) {
  if (kind === 'browser') {
    await page.goto(new URL('/ui-automation', page.url()).href)
    await page.getByRole('button', {name:'执行场景', exact:true}).click()
    await page.getByRole('button', {name:'确定', exact:true}).click()
  } else {
    await page.goto(new URL('/performance', page.url()).href)
    await page.getByRole('button', {name:'提交压测', exact:true}).first().click()
  }
}
async function assertNoExecution(kind) {
  assert.equal((await read(base() + (kind === 'browser' ? '/ui-automation/executions' : '/performance/executions'))).length, kind === 'browser' ? 0 : queued.filter(q=>q.module==='performance').length)
}
async function failedUiPreparation(kind, fault) {
  const before = (await netState()).requests.length
  await mode(fault); await submitUi(kind)
  await until(async () => (await netState()).requests.length > before)
  await until(async () => (await status(kind)).phase === 'error')
  await page.getByText(kind === 'browser' ? /浏览器准备失败/ : /JMeter 准备失败/).first().waitFor()
  if (kind === 'browser') await page.getByRole('button', {name:'取消', exact:true}).click()
  else await page.getByRole('button', {name:'取消准备', exact:true}).waitFor({state:'hidden'})
  await assertNoExecution(kind)
  assert.deepEqual(await fs.readdir(kind === 'browser' ? cache : engines), [])
  pass(kind + ': ' + fault + ' is visible, queues no execution and removes partial downloads')
}
async function cancelUiPreparation(kind) {
  await mode('cancel'); await submitUi(kind)
  await until(async () => { const state = await status(kind); return state.phase === 'downloading' && state.receivedBytes > 0 ? state : null }, 90)
  await page.getByRole('button', {name:kind === 'browser' ? '取消' : '取消准备', exact:true}).click()
  await until(async () => (await status(kind)).phase === 'error')
  if (kind !== 'browser') await page.getByRole('button', {name:'取消准备', exact:true}).waitFor({state:'hidden'})
  await assertNoExecution(kind)
  assert.deepEqual(await fs.readdir(kind === 'browser' ? cache : engines), [])
  pass(kind + ': actual network bytes cancelled through UI with no execution or partial install')
}
async function completeUiPreparation(kind, fixture) {
  await mode('online'); await submitUi(kind)
  const execution = await until(async () => {
    const state = await status(kind)
    if (state.phase === 'error') throw new Error(kind + ': ' + state.message)
    if (state.phase === 'downloading' && state.receivedBytes > 0 && !progress.some(p=>p.kind===kind)) progress.push({kind,receivedBytes:state.receivedBytes,totalBytes:state.totalBytes})
    return (await read(base() + (kind === 'browser' ? '/ui-automation/executions' : '/performance/executions'))).find(row=>kind==='browser'?row.scenarioId===fixture.id:row.planId===fixture.id)
  }, 900)
  queued.push({module:kind==='browser'?'ui-automation':'performance', id:execution.id})
  assert.ok(progress.some(p=>p.kind===kind), 'fresh download must expose nonzero byte progress')
  await checkReport(kind==='browser'?'ui-automation':'performance', execution, kind+' first UI submission')
}
try {
  await assert.rejects(fs.access(engines), {code:'ENOENT'})
  await assert.rejects(fs.access(path.join(directory,'browser-engines')), {code:'ENOENT'})
  await launch(); owner = (await read('/api/v1/auth/me')).id
  const context = await read('/api/v1/access/context'); projectId = context.projects[context.organizations[0].id][0].id
  const rejected = await page.evaluate(async origin => {try{await globalThis.testhubDesktop.ensureBrowser({browser:'chromium',origin});return false}catch{return true}}, forbiddenOrigin)
  assert.equal(rejected,true); assert.equal((await netState()).requests.length,0)
  pass('final packaged application starts in an isolated empty cache and rejects unauthorized component requests')
  await fs.mkdir(cache,{recursive:true}); await fs.mkdir(engines)
  for(const [kind, destination] of [['browser',cache],['jmeter',engines]]) {
    await fs.chmod(destination,0o500)
    try { const result=await ensure(kind); assert.equal(result.ok,false); assert.match(result.message,/EACCES|EPERM|permission/i); assert.equal((await status(kind)).phase,'error') }
    finally { await fs.chmod(destination,0o700) }
  }
  await emptyPartialDirectories(); pass('read-only empty browser and JMeter caches fail without data loss; permission restoration allows retry')
  const chrome = await scenario('chromium')
  await failedUiPreparation('browser','offline')
  await failedUiPreparation('browser','interrupt')
  await cancelUiPreparation('browser')
  await completeUiPreparation('browser',chrome)
  for(const type of ['firefox','webkit']) {
    assert.equal(await componentManager.installed(type),undefined)
    const item=await scenario(type),api=base()+'/ui-automation'
    let execution
    if(type==='webkit') {
      const when=new Date(Date.now()+12000)
      const schedule=await mutate(api+'/schedules',{name:'fresh-webkit-once',scenarioId:item.id,cronExpression:`${when.getUTCSeconds()} ${when.getUTCMinutes()} ${when.getUTCHours()} ${when.getUTCDate()} ${when.getUTCMonth()+1} ? ${when.getUTCFullYear()}`,timezone:'UTC',timeoutSeconds:900,maxRetryAttempts:0,retryBackoffSeconds:5,enabled:true})
      execution=await until(async()=>(await read(api+'/executions')).find(row=>row.scheduleId===schedule.id),40)
      await mutate(api+`/schedules/${schedule.id}`,{...schedule,enabled:false},'PUT')
    } else execution=await mutate(api+`/scenarios/${item.id}/executions`,{timeoutSeconds:900})
    queued.push({module:'ui-automation',id:execution.id})
    await checkReport('ui-automation',execution,type+' fresh '+(type==='webkit'?'scheduled':'API')+' download through Runner IPC')
    assert.ok(await componentManager.installed(type))
  }
  const plan=await mutate(base()+'/performance/plans',{name:'first-use-jmeter',description:'Owned loopback only',status:'READY',threadGroups:[{name:'native-first-use',targetUrl:origin+'/',loadModel:'STEADY',threadCount:1,rampUpSeconds:0,durationSeconds:10,targetTps:3,requestMethod:'GET',expectedStatusCode:200,responseContains:'local-component-ok',errorThresholdPercent:0}]})
  await failedUiPreparation('jmeter','offline')
  await failedUiPreparation('jmeter','interrupt')
  await cancelUiPreparation('jmeter')
  await completeUiPreparation('jmeter',plan)
  const online=await netState(), complete=online.requests.filter(r=>r.mode==='online'&&r.complete)
  for(const component of [...new Map(Object.values(plans).flatMap(p=>p.components).map(c=>[c.name,c])).values()]) {
    const downloads=complete.filter(r=>component.urls.includes(r.url))
    assert.equal(downloads.length,1,component.name+' must be downloaded once, not copied from cache')
    assert.equal(downloads[0].receivedBytes,component.bytes);assert.equal(downloads[0].sha256,component.sha256)
  }
  const jmeter=complete.filter(r=>r.url===JMETER.url)
  assert.equal(jmeter.length,1);assert.equal(jmeter[0].sha512,JMETER.sha512)
  pass('all four browser/video archives and JMeter were freshly downloaded from pinned official URLs and matched their digests')
  await mode('offline')
  for(const [kind,home] of [['browser',path.join(cache,plans.chromium.components[0].directory)],['jmeter',path.join(engines,JMETER.root,JMETER.root)]]) {
    const altered=path.join(home,'.testhub-qa-extra-file');await fs.writeFile(altered,'owned QA integrity negative',{flag:'wx'})
    try{const value=await ensure(kind);assert.equal(value.ok,false);assert.match(value.message,/校验失败/);assert.equal(await fs.readFile(altered,'utf8'),'owned QA integrity negative')}
    finally{await fs.rm(altered)}
    assert.equal((await ensure(kind)).ok,true)
  }
  pass('changed caches fail closed without deleting their content; restoring only QA changes permits offline reuse')
  const ownerBefore=owner, priorUi=(await read(base()+'/ui-automation/executions')).length
  await shutdown(); networkMode='offline'; await launch()
  assert.equal((await read('/api/v1/auth/me')).id,ownerBefore)
  assert.equal((await read(base()+'/ui-automation/executions')).length,priorUi)
  for(const type of ['chromium','firefox','webkit']) {
    assert.equal((await ensure('browser',type)).ok,true)
    const item=(await read(base()+'/ui-automation/scenarios')).find(s=>s.name==='first-use-'+type)
    const execution=await mutate(base()+`/ui-automation/scenarios/${item.id}/executions`,{timeoutSeconds:120})
    await checkReport('ui-automation',execution,type+' offline restart')
  }
  assert.equal((await ensure('jmeter')).ok,true)
  const replay=await mutate(base()+`/performance/plans/${plan.id}/executions`,{timeoutSeconds:120})
  await checkReport('performance',replay,'JMeter offline restart')
  assert.equal((await netState()).requests.length,0)
  assert.equal(forbiddenHits,0);assert.ok(targetHits>0)
  pass('restart with component network blocked preserves identity and executes all browsers and JMeter without any download request')
  await shutdown()
  outcome={passed:true,checks,isPackaged:true,asarUnchanged:crypto.createHash('sha256').update(await fs.readFile(asar)).digest('hex')===originalAsarHash,backgroundWindows:launches.map(({created,visibleViolations,unexpectedDialogs,windows})=>({created,visibleViolations,unexpectedDialogs,windows})),networkSnapshots,progress,reports,forbiddenHits,targetHits,cacheCopied:false,scope:{mobileRealDevice:false,externalNotificationTransport:false,firstUseDownloads:true,nativePlatform:process.platform,nativeArch:process.arch}}
  assert.equal(outcome.asarUnchanged,true)
} catch(error) { failure=error; outcome={passed:false,checks,failure:safeDiagnostic(error.message),backgroundWindows:launches.map(({created,visibleViolations,unexpectedDialogs,windows})=>({created,visibleViolations,unexpectedDialogs,windows}))} }
finally {
  let cleanupError
  try { await shutdown() } catch(error) { cleanupError=error }
  if(child&&child.exitCode===null){child.kill('SIGTERM');await finished}
  for(const server of [target,forbidden]){server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
  await fs.rm(directory,{recursive:true,force:true})
  try{await run('/usr/bin/security',['delete-generic-password','-s',sessionName+' Safe Storage','-a',sessionName])}catch(error){if(error.code!==44)cleanupError||=error}
  if(cleanupError){outcome.passed=false;outcome.cleanupFailure=true;failure||=cleanupError}
  outcome.ownedDataAndProcessesRemoved=!cleanupError
  if(!outcome.networkSnapshots)outcome.networkSnapshots=networkSnapshots
  await fs.writeFile(output,JSON.stringify(outcome,null,2)+'\n',{mode:0o600})
}
if(failure)throw failure
console.log('PASS first downloads module '+checks.length+' checks')
