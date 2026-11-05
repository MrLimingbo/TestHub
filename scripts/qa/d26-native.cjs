'use strict'
/* global require, process, performance, fetch, AbortController, AbortSignal, setTimeout, clearTimeout */
/* eslint @typescript-eslint/no-require-imports: off */
// Owned QA entry: install offscreen restrictions before loading the frozen, otherwise unmodified desktop entry.
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const { app, BrowserWindow, dialog } = require('electron')
const config = JSON.parse(fs.readFileSync(process.argv[process.argv.indexOf('--config') + 1], 'utf8'))
if (!/^TestHub-QA-[a-f0-9-]{36}$/.test(config.qaName) || !path.isAbsolute(config.userData)) throw new Error('Owned QA configuration required')
app.setName(config.qaName); app.setPath('userData', config.userData); app.setPath('sessionData', config.userData); app.setAppPath(config.app)
if (process.platform === 'darwin') app.setActivationPolicy('prohibited')
process.env.TESTHUB_DESKTOP_USER_DATA = config.userData
process.env.TESTHUB_DESKTOP_RUNTIME_DIR = config.runtime
delete process.env.TESTHUB_DESKTOP_SERVER_URL
const safety = { created: 0, visibleViolations: 0, unexpectedDialogs: 0, suppressedActivations: 0 }
for (const method of ['show', 'showInactive', 'focus', 'restore']) BrowserWindow.prototype[method] = () => { safety.suppressedActivations++ }
const Hidden = new Proxy(BrowserWindow, { construct(target, args) {
  const options = args[0] || {}
  return Reflect.construct(target, [{ ...options, show: false, focusable: false, webPreferences: { ...options.webPreferences, offscreen: true, backgroundThrottling: false } }], target)
} })
const electron = require('electron'), quiet = new Proxy(electron, { get: (target, name, receiver) => name === 'BrowserWindow' ? Hidden : Reflect.get(target, name, receiver) })
const load = Module._load
Module._load = function (request, parent, main) { return request === 'electron' ? quiet : load.call(this, request, parent, main) }
for (const method of ['showMessageBox', 'showMessageBoxSync', 'showOpenDialog', 'showOpenDialogSync', 'showSaveDialog', 'showSaveDialogSync', 'showErrorBox']) dialog[method] = () => {
  safety.unexpectedDialogs++; throw new Error('Unexpected native dialog in owned D26 QA')
}
app.on('browser-window-created', (_event, window) => { safety.created++; window.on('show', () => { safety.visibleViolations++ }); if (window.isVisible() || window.isFocused()) safety.visibleViolations++ })
app.whenReady().then(() => { if (process.platform === 'darwin') { app.hide(); app.dock?.hide() } })
let runtime
const { LocalRuntime } = require(path.join(config.app, 'src/local-runtime.cjs'))
const start = LocalRuntime.prototype.start
// Retain the real runtime instance for process ownership; the production startup remains unchanged.
// eslint-disable-next-line @typescript-eslint/no-this-alias
LocalRuntime.prototype.start = function (...args) { runtime = this; return start.apply(this, args) }
function window() { return BrowserWindow.getAllWindows().find(w => !w.isDestroyed() && w.webContents.getURL().startsWith('http://127.0.0.1:')) }
function metadata() {
  const w = window()
  return { ready: runtime?.phase === 'ready' && Boolean(w) && !w.webContents.isLoading(), pid: process.pid,
    javaPid: runtime?.children.get('backend')?.process.pid, runnerPid: runtime?.children.get('api-runner')?.process.pid,
    versions: { electron: process.versions.electron, node: process.versions.node, chrome: process.versions.chrome }, safety: { ...safety },
    processes: app.getAppMetrics().map(p => ({ pid: p.pid, type: p.type, name: p.name, creationTime: p.creationTime })),
    windows: BrowserWindow.getAllWindows().map(w => ({ visible: w.isVisible(), focused: w.isFocused(), offscreen: w.webContents.isOffscreen() })) }
}
// This function runs in the real renderer. It returns only counts, timings and owned IDs, never credentials or response bodies.
async function renderer(action) {
  const g = globalThis
  g.__d26 ||= { createdPlans: [], sequence: 0 }
  const s = g.__d26
  async function read(method, route, body) {
    const headers = method === 'GET' ? {} : { [s.csrf.headerName]: s.csrf.token }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 10000), began = performance.now()
    let status = null, value, error = null
    try {
      const response = await fetch(route, { method, headers, credentials: 'include', redirect: 'error', signal: controller.signal, body: body === undefined ? undefined : JSON.stringify(body) })
      status = response.status
      value = status === 204 ? null : await response.json()
      if (!response.ok) error = typeof value?.code === 'string' ? value.code : 'HTTP_ERROR'
    } catch (reason) { error = reason.name === 'AbortError' ? 'CLIENT_TIMEOUT' : reason.name }
    finally { clearTimeout(timeout) }
    return { status, value, error, elapsedMs: performance.now() - began, timeoutLimitMs: 10000 }
  }
  const api = async (method, route, body) => {
    const result = await read(method, route, body)
    if (result.error || ![200, 201, 204].includes(result.status)) throw new Error('Owned D26 preparation request failed: ' + result.status + '/' + result.error)
    return result.value?.data
  }
  if (action.command === 'PREPARE' || action.command === 'SEED') {
    const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'include' }); s.csrf = (await csrfResponse.json()).data
    const context = await api('GET', '/api/v1/access/context'); s.project = Object.values(context.projects).flat()[0].id
    s.base = '/api/v1/projects/' + s.project
    if (action.command === 'SEED') {
      const catalog = await api('POST', s.base + '/functional/catalogs', { name: 'D26固定用例', sortOrder: 0, scope: 'CASES' }); s.caseIds = []
      for (let i = 0; i < 200; i++) {
        const c = await api('POST', s.base + '/functional/cases', { catalogId: catalog.id, caseCode: 'D26-CASE-' + String(i).padStart(3, '0'), title: 'D26自有用例' + i, priority: 'P1', status: 'READY', caseType: 'FUNCTIONAL', precondition: '', steps: '受控步骤', expectedResult: '受控结果' }); s.caseIds.push(c.id)
        await api('POST', s.base + '/tasks', { taskType: 'OTHER', name: 'D26自有任务' + String(i).padStart(3, '0') })
      }
      s.planIds = []
      for (let i = 0; i < 20; i++) {
        const p = await api('POST', s.base + '/functional/plans', { planCode: 'D26-PLAN-' + String(i).padStart(2, '0'), name: 'D26基准计划' + i, status: 'READY', description: 'D26隔离基线', caseIds: s.caseIds.slice(i * 10, i * 10 + 10) }); s.planIds.push(p.id)
      }
    } else { s.caseIds = action.seed.caseIds; s.planIds = action.seed.planIds }
    s.tasks = await api('GET', s.base + '/tasks'); s.createdPlans = []; s.sequence = 0
    const plans = await api('GET', s.base + '/functional/plans')
    if (s.tasks.length !== 200 || plans.length !== 20 || s.caseIds.length !== 200) throw new Error('D26 initial dataset mismatch')
    return { projectId: s.project, taskCount: s.tasks.length, planCount: plans.length, caseCount: s.caseIds.length, caseIds: s.caseIds, planIds: s.planIds }
  }
  if (action.command === 'COUNTS') return { tasks: (await api('GET', s.base + '/tasks')).length, plans: (await api('GET', s.base + '/functional/plans')).length, pendingPlans: s.createdPlans.length }
  const n = action.index || 0, cycle = action.cycle || 0, operation = action.operation
  let route, method = 'GET', body, expected = 200, task
  if (operation === 'taskList') route = s.base + '/tasks'
  else if (operation === 'planDetail') route = s.base + '/functional/plans/' + s.planIds[n % 20]
  else if (operation === 'qualityReport') route = '/api/v1/reports/quality?projectIds=' + encodeURIComponent(s.project) + '&from=2026-11-04&to=2026-11-04'
  else if (operation === 'taskUpdate') {
    task = s.tasks[(cycle * 10 + n) % s.tasks.length]; route = s.base + '/tasks/' + task.id; method = 'PUT'
    body = { name: task.name, status: 'RUNNING', progress: (cycle + n) % 100, expectedVersion: task.version }
  } else if (operation === 'planCreate') {
    route = s.base + '/functional/plans'; method = 'POST'
    body = { planCode: 'D26-TEMP-' + action.tag + '-' + cycle + '-' + n, name: 'D26临时计划', status: 'READY', description: '成对清理的隔离采集夹具', caseIds: s.caseIds.slice((n % 20) * 10, (n % 20) * 10 + 10) }
  } else if (operation === 'planDelete') {
    const id = s.createdPlans.shift()
    if (!id) return { status: null, error: 'PAIRED_PLAN_MISSING', elapsedMs: 0, timeoutLimitMs: 10000, expectedStatus: 204 }
    route = s.base + '/functional/plans/' + id; method = 'DELETE'; expected = 204
  } else throw new Error('Unknown D26 operation')
  const result = await read(method, route, body)
  if (result.status !== expected && !result.error) result.error = 'UNEXPECTED_STATUS'
  if (!result.error) {
    const data = result.value?.data
    if (operation === 'taskList' && data.length !== 200) result.error = 'TASK_COUNT_CHANGED'
    if (operation === 'planDetail' && data.caseCount !== 10) result.error = 'PLAN_CASE_COUNT_CHANGED'
    if (operation === 'qualityReport' && (data.projectCount !== 1 || data.summary.functionalCaseCount !== 200)) result.error = 'REPORT_DATASET_CHANGED'
    if (operation === 'taskUpdate') Object.assign(task, data)
    if (operation === 'planCreate') s.createdPlans.push(data.id)
  }
  return { status: result.status, error: result.error, elapsedMs: result.elapsedMs, timeoutLimitMs: result.timeoutLimitMs, expectedStatus: expected, operation }
}
async function handle(message) {
  if (message.command === 'META') return metadata()
  if (message.command === 'PARK') { await window().loadURL(runtime.url + '/system-settings/accounts'); return { parked: true } }
  if (message.command === 'STOP') { app.quit(); return { stopping: true } }
  if (message.command === 'PROBE') {
    const start = performance.now()
    try { const response = await fetch(runtime.url + message.route, { signal: AbortSignal.timeout(10000), redirect: 'error' }); await response.arrayBuffer(); return { status: response.status, elapsedMs: performance.now() - start, error: response.ok ? null : 'HTTP_ERROR' } }
    catch (error) { return { status: null, elapsedMs: performance.now() - start, error: error.name } }
  }
  if (!window()) throw new Error('Owned renderer unavailable')
  return window().webContents.executeJavaScript('(' + renderer.toString() + ')(' + JSON.stringify(message) + ')')
}
process.on('message', message => {
  void handle(message).then(value => { if (process.connected) process.send({ id: message.id, ok: true, value }) }, error => {
    if (process.connected) process.send({ id: message.id, ok: false, error: { name: error.name, message: String(error.message).replace(/[a-f0-9]{32,}|[A-Za-z0-9+/=_-]{48,}/gi, '[redacted]').slice(0, 300) } })
  })
})
require(path.join(config.app, 'src/main.cjs'))
