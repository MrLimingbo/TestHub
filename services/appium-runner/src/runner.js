import { Buffer } from 'node:buffer'
import { CANCEL_GROUP, CANCEL_STREAM, EVENT_STREAM, GROUP, STEP_STREAM, TASK_STREAM, artifactRequest, decodeCancellation, decodeTask, event, heartbeat, runtime, runtimeRequest, stepResult } from './protocol.js'
function fields(value) { return Object.entries(value).flatMap(([key, item]) => [key, String(item)]) }
function entries(reply) { const values = Array.isArray(reply) ? reply : reply && typeof reply === 'object' ? Object.entries(reply) : []; return values.flatMap(([, streams]) => Array.isArray(streams) ? streams.map(([id, raw]) => ({ id, fields: Object.fromEntries(raw.reduce((all, item, index) => index % 2 === 0 ? [...all, [item, raw[index + 1]]] : all, [])) })) : []) }
function message(error) { return String(error?.message ?? error).slice(0, 512) }
function interpolate(value, variables) { return String(value ?? '').replace(/{{([A-Za-z_][A-Za-z0-9_]*)}}/g, (_, name) => { if (!(name in variables)) throw error('VARIABLE_UNDEFINED', `变量未定义：${name}`); return variables[name] }) }
function error(code, text) { const value = new Error(text); value.code = code; return value }
const LOCATORS = { ACCESSIBILITY_ID: 'accessibility id', ID: 'id', XPATH: 'xpath', ANDROID_UIAUTOMATOR: '-android uiautomator', IOS_PREDICATE: '-ios predicate string' }
const SAFE_CAPABILITIES = new Set(['appium:platformVersion', 'appium:deviceName', 'appium:noReset', 'appium:fullReset', 'appium:newCommandTimeout', 'appium:autoGrantPermissions', 'appium:language', 'appium:locale', 'appium:appActivity', 'appium:appWaitActivity'])

function capability(key, value) {
  if (['appium:noReset', 'appium:fullReset', 'appium:autoGrantPermissions'].includes(key)) {
    if (value === 'true' || value === true) return true
    if (value === 'false' || value === false) return false
    throw error('CAPABILITY_INVALID', `${key} 必须是 true 或 false`)
  }
  if (key === 'appium:newCommandTimeout') {
    if (!/^\d+$/.test(String(value)) || !Number.isSafeInteger(Number(value))) throw error('CAPABILITY_INVALID', `${key} 必须是非负整数秒数`)
    return Number(value)
  }
  return value
}
export class AppiumRunner {
  constructor({ commandClient, readClient, cancelClient, config, logger = console, fetcher = globalThis.fetch, now = () => new Date() }) { this.commandClient = commandClient; this.readClient = readClient; this.cancelClient = cancelClient; this.config = config; this.logger = logger; this.fetcher = fetcher; this.now = now; this.startedAt = now(); this.running = new Map(); this.cancelled = new Map(); this.stopping = false }
  async start() { await this.commandClient.connect(); await this.readClient.connect(); await this.cancelClient.connect(); await this.group(TASK_STREAM, GROUP); await this.group(CANCEL_STREAM, CANCEL_GROUP); this.timer = setInterval(() => void this.sendHeartbeat(), this.config.heartbeatIntervalSeconds * 1000); await this.sendHeartbeat(); void this.loop(); void this.cancellationLoop() }
  async stop() { this.stopping = true; clearInterval(this.timer); await Promise.allSettled([...this.running.values()].map((active) => this.closeSession(active))); await this.commandClient.multi().del(`testhub:runner:appium:heartbeat:v1:${this.config.runnerId}`).sRem('testhub:runner:appium:members:v1', this.config.runnerId).exec(); await Promise.allSettled([this.commandClient.quit(), this.readClient.quit(), this.cancelClient.quit()]) }
  async group(stream, group) { try { await this.commandClient.sendCommand(['XGROUP', 'CREATE', stream, group, '$', 'MKSTREAM']) } catch (value) { if (!String(value.message).includes('BUSYGROUP')) throw value } }
  async sendHeartbeat() { for (const [taskId, expiry] of this.cancelled) if (expiry <= Date.now()) this.cancelled.delete(taskId); await this.commandClient.multi().set(`testhub:runner:appium:heartbeat:v1:${this.config.runnerId}`, JSON.stringify(heartbeat(this.config, this.running.size, this.startedAt, this.now())), { EX: this.config.heartbeatTtlSeconds }).sAdd('testhub:runner:appium:members:v1', this.config.runnerId).exec() }
  async loop() { while (!this.stopping) try { if (this.running.size >= this.config.maxConcurrentTasks) { await new Promise((resolve) => setTimeout(resolve, 100)); continue } for (const entry of entries(await this.readClient.sendCommand(['XREADGROUP', 'GROUP', GROUP, this.config.runnerId, 'COUNT', '1', 'BLOCK', '5000', 'STREAMS', TASK_STREAM, '>']))) void this.handle(entry) } catch (value) { if (!this.stopping) { this.logger.error('Appium Runner 读取任务失败', message(value)); await new Promise((resolve) => setTimeout(resolve, 1000)) } } }
  async cancellationLoop() { while (!this.stopping) try { for (const entry of entries(await this.cancelClient.sendCommand(['XREADGROUP', 'GROUP', CANCEL_GROUP, this.config.runnerId, 'COUNT', '10', 'BLOCK', '5000', 'STREAMS', CANCEL_STREAM, '>']))) await this.cancel(entry) } catch (value) { if (!this.stopping) { this.logger.error('Appium Runner 读取取消命令失败', message(value)); await new Promise((resolve) => setTimeout(resolve, 1000)) } } }
  async cancel(entry) { let command; try { command = decodeCancellation(entry.fields) } catch { await this.cancelAck(entry.id); return } this.cancelled.set(command.taskId, Date.now() + 5 * 60 * 1000); const active = this.running.get(command.taskId); if (active?.task.executionId === command.executionId) { active.cancelled = true; await this.closeSession(active) } await this.cancelAck(entry.id) }
  async handle(entry) {
    let task; try { task = decodeTask(entry.fields) } catch { await this.ack(entry.id); return }
    if (!this.config.executionEnabled) { await this.publish(event(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'Appium task transport accepted the immutable manifest reference.', this.now())); await this.publish(event(task, this.config.runnerId, 'REJECTED', 'APPIUM_EXECUTION_DISABLED', 'Appium execution is disabled until a configured device farm allow list is explicitly enabled.', this.now())); await this.ack(entry.id); return }
    if (this.running.size >= this.config.maxConcurrentTasks) { await this.publish(event(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'Appium task transport accepted the immutable manifest reference.', this.now())); await this.publish(event(task, this.config.runnerId, 'REJECTED', 'RUNNER_CAPACITY_EXHAUSTED', 'Appium Runner has no free execution slot.', this.now())); await this.ack(entry.id); return }
    const active = { task, sessionId: '', cancelled: this.cancelled.has(task.taskId) }; this.running.set(task.taskId, active)
    try {
      await this.publish(event(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'Appium task accepted by isolated Runner.', this.now()))
      this.assertActive(active); const configuration = await this.runtime(task); this.assertDeadline(active, configuration.deadlineAt); active.deadlineAt = configuration.deadlineAt; active.sessionId = await this.startSession(active, configuration); this.assertActive(active); await this.publish(event(task, this.config.runnerId, 'RUNNING', 'APPIUM_SESSION_STARTED', 'Isolated Appium session started.', this.now()))
      await this.execute(active, configuration)
      this.assertActive(active); this.assertDeadline(active, configuration.deadlineAt); await this.publish(event(task, this.config.runnerId, 'SUCCEEDED', 'APPIUM_EXECUTION_SUCCEEDED', 'Appium scenario completed.', this.now()))
    } catch (value) {
      const code = value?.code; if (active.cancelled || this.cancelled.has(task.taskId) || code === 'CANCELLED') await this.publish(event(task, this.config.runnerId, 'CANCELLED', 'APPIUM_CANCELLATION_CONFIRMED', 'Appium device session was cancelled and released.', this.now()))
      else if (code === 'TIMED_OUT') await this.publish(event(task, this.config.runnerId, 'TIMED_OUT', 'APPIUM_EXECUTION_TIMED_OUT', 'Appium execution exceeded its immutable deadline.', this.now()))
      else await this.publish(event(task, this.config.runnerId, 'FAILED', 'APPIUM_EXECUTION_FAILED', message(value), this.now()))
    } finally { await this.closeSession(active); this.running.delete(task.taskId); this.cancelled.delete(task.taskId); void this.sendHeartbeat(); await this.ack(entry.id) }
  }
  async runtime(task) { const request = runtimeRequest(this.config, task); const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, { method: 'POST', headers: request.headers, body: request.body, redirect: 'error', signal: AbortSignal.timeout(5000) }); if (!response.ok) throw error('RUNTIME_CONFIGURATION_FAILED', `运行时配置请求失败：HTTP ${response.status}`); return runtime(task, (await response.json())?.data) }
  async startSession(active, configuration) { const device = configuration.manifest.device, application = configuration.manifest.application; const capabilities = { platformName: device.platform === 'ANDROID' ? 'Android' : 'iOS', 'appium:automationName': device.automationName, 'appium:udid': device.udid }
    for (const [key, value] of Object.entries(device.capabilities ?? {})) if (SAFE_CAPABILITIES.has(key)) capabilities[key] = capability(key, value)
    if (capabilities['appium:noReset'] && capabilities['appium:fullReset']) throw error('CAPABILITY_INVALID', 'noReset 与 fullReset 不能同时启用')
    if (device.platform === 'ANDROID') capabilities['appium:appPackage'] = application.appIdentifier; else capabilities['appium:bundleId'] = application.appIdentifier
    const value = await this.webdriver('POST', '/session', { capabilities: { alwaysMatch: capabilities, firstMatch: [{}] } }, 30000); const sessionId = value?.sessionId ?? value?.value?.sessionId; if (typeof sessionId !== 'string' || !sessionId) throw error('APPIUM_SESSION_INVALID', '设备农场未返回有效 Appium session'); return sessionId }
  async execute(active, configuration) { const scenario = configuration.manifest.scenario; let failed = false; for (const [index, step] of scenario.steps.entries()) { const started = Date.now(); let failure = ''
      try { this.assertActive(active); this.assertDeadline(active, configuration.deadlineAt); await this.step(active, step, scenario.variables ?? {}); this.assertActive(active); this.assertDeadline(active, configuration.deadlineAt) }
      catch (value) { if (['CANCELLED', 'TIMED_OUT'].includes(value?.code)) throw value; failure = message(value); failed = true }
      await this.publishStep(stepResult(active.task, this.config.runnerId, step, index + 1, failure ? 'FAILED' : 'PASSED', Date.now() - started, failure, this.now()))
      if (failure && scenario.failureStrategy !== 'CONTINUE') throw error(failure.includes('deadline') ? 'TIMED_OUT' : 'STEP_FAILED', failure)
    } if (failed) throw error('STEP_FAILED', 'One or more mobile steps failed'); }
  async step(active, step, variables) { const timeout = Math.min(Math.max(Number(step.timeoutMillis) || 30000, 1), 60000); if (step.type === 'LAUNCH') return
    if (step.type === 'WAIT') {
      const end = Date.now() + timeout
      while (Date.now() < end) { this.assertActive(active); if (active.deadlineAt) this.assertDeadline(active, active.deadlineAt); await new Promise(resolve => setTimeout(resolve, Math.min(100, end - Date.now()))) }
      return
    }
    if (step.type === 'SCREENSHOT') { const shot = await this.webdriver('GET', `/session/${encodeURIComponent(active.sessionId)}/screenshot`, undefined, timeout); const base64 = typeof shot === 'string' ? shot : shot?.value; if (typeof base64 !== 'string') throw error('SCREENSHOT_FAILED', '设备农场未返回 PNG 截图'); await this.archive(active.task, Buffer.from(base64, 'base64')); return }
    const element = await this.element(active, step, timeout); if (step.type === 'TAP') return this.webdriver('POST', `/session/${encodeURIComponent(active.sessionId)}/element/${encodeURIComponent(element)}/click`, {}, timeout)
    if (step.type === 'INPUT') { const value = interpolate(step.value, variables); return this.webdriver('POST', `/session/${encodeURIComponent(active.sessionId)}/element/${encodeURIComponent(element)}/value`, { text: value, value: [...value] }, timeout) }
    if (step.type === 'ASSERT_VISIBLE') { const visible = await this.webdriver('GET', `/session/${encodeURIComponent(active.sessionId)}/element/${encodeURIComponent(element)}/displayed`, undefined, timeout); if (visible !== true && visible?.value !== true) throw error('ASSERT_VISIBLE_FAILED', '元素不可见'); return }
    if (step.type === 'ASSERT_TEXT') { const actual = await this.webdriver('GET', `/session/${encodeURIComponent(active.sessionId)}/element/${encodeURIComponent(element)}/text`, undefined, timeout); const text = typeof actual === 'string' ? actual : actual?.value; if (!String(text ?? '').includes(interpolate(step.expectedText, variables))) throw error('ASSERT_TEXT_FAILED', '文本断言未通过'); return }
    throw error('STEP_TYPE_UNSUPPORTED', `不支持的移动端步骤：${step.type}`) }
  async element(active, step, timeout) { const using = LOCATORS[step.locatorStrategy]; if (!using || !step.locatorValue) throw error('LOCATOR_INVALID', '移动端元素定位器不完整'); const value = await this.webdriver('POST', `/session/${encodeURIComponent(active.sessionId)}/element`, { using, value: step.locatorValue }, timeout); const element = value?.['element-6066-11e4-a52e-4f735466cecf'] ?? value?.ELEMENT ?? value?.value?.['element-6066-11e4-a52e-4f735466cecf'] ?? value?.value?.ELEMENT; if (typeof element !== 'string') throw error('ELEMENT_NOT_FOUND', '设备农场未返回元素标识'); return element }
  async webdriver(method, path, body, timeout) { const response = await this.fetcher(`${this.config.appiumServerUrl}${path}`, { method, headers: body === undefined ? undefined : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error', signal: AbortSignal.timeout(timeout) }); const payload = await response.json().catch(() => ({})); if (!response.ok || payload?.value?.error) throw error('WEBDRIVER_REQUEST_FAILED', payload?.value?.message || `Appium HTTP ${response.status}`); return payload?.value ?? payload }
  async archive(task, bytes) { if (bytes.length > 25 * 1024 * 1024) throw error('SCREENSHOT_TOO_LARGE', '移动端截图超过 25MB'); const request = artifactRequest(this.config, task, bytes); const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, { method: 'POST', headers: request.headers, body: request.body, redirect: 'error', signal: AbortSignal.timeout(10000) }); if (!response.ok) throw error('SCREENSHOT_ARCHIVE_FAILED', `截图归档失败：HTTP ${response.status}`) }
  assertActive(active) { if (active.cancelled || this.cancelled.has(active.task.taskId)) throw error('CANCELLED', 'Appium task cancelled') }
  assertDeadline(active, deadlineAt) { if (Date.parse(deadlineAt) <= Date.now()) throw error('TIMED_OUT', 'Appium task deadline exceeded'); this.assertActive(active) }
  async closeSession(active) { if (!active.sessionId) return; const id = active.sessionId; active.sessionId = ''; try { await this.webdriver('DELETE', `/session/${encodeURIComponent(id)}`, undefined, 5000) } catch (value) { this.logger.warn('Appium session 释放失败', message(value)) } }
  async publish(value) { await this.commandClient.sendCommand(['XADD', EVENT_STREAM, 'MAXLEN', '~', '10000', '*', ...fields(value)]) }
  async publishStep(value) { await this.commandClient.sendCommand(['XADD', STEP_STREAM, 'MAXLEN', '~', '10000', '*', ...fields(value)]) }
  async ack(id) { await this.commandClient.sendCommand(['XACK', TASK_STREAM, GROUP, id]) }
  async cancelAck(id) { await this.commandClient.sendCommand(['XACK', CANCEL_STREAM, CANCEL_GROUP, id]) }
}
