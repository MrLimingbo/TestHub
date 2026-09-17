import { createHash } from 'node:crypto'
import { mkdir, readFile, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { chromium, firefox, webkit } from 'playwright'
import { createTargetProxy } from './target-proxy.js'
import { CANCEL_CONSUMER_GROUP, CANCEL_STREAM, TASK_CONSUMER_GROUP, TASK_STREAM, EVENT_STREAM, STEP_RESULT_STREAM, HEARTBEAT_MEMBERS_KEY, artifactRequest, createEvent, createHeartbeat, createStepResult, decodeCancellation, decodeTask, heartbeatKey, runtimeRequest, validateRuntime } from './protocol.js'

function toFields(value) { return Object.entries(value).flatMap(([key, item]) => [key, String(item)]) }
function entries(reply) { const streams = Array.isArray(reply) ? reply : reply && typeof reply === 'object' ? Object.entries(reply) : []; return streams.flatMap(([, values]) => Array.isArray(values) ? values.map(([id, raw]) => ({ id, fields: Object.fromEntries(raw.reduce((all, item, index) => index % 2 === 0 ? [...all, [item, raw[index + 1]]] : all, [])) })) : []) }
function text(error) { return String(error?.message ?? error).replace(/[\r\n]+/g, ' ').slice(0, 420) }
function interpolate(value, variables) { return String(value ?? '').replace(/\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}/g, (_, key) => { if (!(key in variables)) throw new Error(`变量未定义：${key}`); return variables[key] }) }
function target(raw, base, allowed) { const url = new URL(raw, base); if (!allowed.includes(url.origin)) throw new Error('导航目标不在 Runner Origin 白名单内'); return url.toString() }

export class PlaywrightRunner {
  constructor({ commandClient, readClient, cancelClient, config, logger = console, fetcher = globalThis.fetch, now = () => new Date() }) {
    this.commandClient = commandClient; this.readClient = readClient; this.cancelClient = cancelClient; this.config = config; this.logger = logger; this.fetcher = fetcher; this.now = now
    this.startedAt = now(); this.running = new Map(); this.cancelled = new Map(); this.stopping = false
  }
  async start() {
    await this.commandClient.connect(); await this.readClient.connect(); await this.cancelClient.connect(); await this.group(); await this.heartbeat()
    this.timer = setInterval(() => void this.heartbeat(), this.config.heartbeatIntervalSeconds * 1000)
    this.logger.info(this.config.executionEnabled ? `Playwright Runner 已启用：${this.config.runnerId}` : `Playwright Runner 已连接，执行保持禁用：${this.config.runnerId}`)
    void this.loop(); void this.cancellationLoop()
  }
  async stop() { this.stopping = true; clearInterval(this.timer); await Promise.allSettled([...this.running.values()].map((active) => this.closeActive(active))); await this.commandClient.multi().del(heartbeatKey(this.config.runnerId)).sRem(HEARTBEAT_MEMBERS_KEY, this.config.runnerId).exec(); await Promise.allSettled([this.commandClient.quit(), this.readClient.quit(), this.cancelClient.quit()]) }
  async group() { await this.groupFor(TASK_STREAM, TASK_CONSUMER_GROUP); await this.groupFor(CANCEL_STREAM, CANCEL_CONSUMER_GROUP) }
  async groupFor(stream, group) { try { await this.commandClient.sendCommand(['XGROUP', 'CREATE', stream, group, '$', 'MKSTREAM']) } catch (error) { if (!String(error.message).includes('BUSYGROUP')) throw error } }
  async heartbeat() { for (const [taskId, expiresAt] of this.cancelled) if (expiresAt <= Date.now()) this.cancelled.delete(taskId); await this.commandClient.multi().set(heartbeatKey(this.config.runnerId), JSON.stringify(createHeartbeat(this.config, this.startedAt, this.running.size, this.now())), { EX: this.config.heartbeatTtlSeconds }).sAdd(HEARTBEAT_MEMBERS_KEY, this.config.runnerId).exec() }
  async loop() {
    while (!this.stopping) try {
      if (this.running.size >= this.config.maxConcurrentTasks) { await new Promise((resolve) => setTimeout(resolve, 100)); continue }
      for (const entry of entries(await this.readClient.sendCommand(['XREADGROUP', 'GROUP', TASK_CONSUMER_GROUP, this.config.runnerId, 'COUNT', '1', 'BLOCK', '5000', 'STREAMS', TASK_STREAM, '>']))) {
        void this.handle(entry).catch((error) => this.logger.error('Playwright Runner 执行任务失败', text(error)))
      }
    } catch (error) { if (!this.stopping) { this.logger.error('Playwright Runner 读取任务流失败', text(error)); await new Promise((resolve) => setTimeout(resolve, 1000)) } }
  }
  async cancellationLoop() { while (!this.stopping) try { for (const entry of entries(await this.cancelClient.sendCommand(['XREADGROUP', 'GROUP', CANCEL_CONSUMER_GROUP, this.config.runnerId, 'COUNT', '10', 'BLOCK', '5000', 'STREAMS', CANCEL_STREAM, '>']))) await this.cancel(entry) } catch (error) { if (!this.stopping) { this.logger.error('Playwright Runner 读取取消流失败', text(error)); await new Promise((resolve) => setTimeout(resolve, 1000)) } } }
  async cancel(entry) {
    let command; try { command = decodeCancellation(entry.fields) } catch { await this.cancelAck(entry.id); return }
    const active = this.running.get(command.taskId)
    this.cancelled.set(command.taskId, Date.now() + 5 * 60 * 1000)
    if (active && active.task.executionId === command.executionId) { active.cancelled = true; await this.closeActive(active) }
    await this.cancelAck(entry.id)
  }
  async handle(entry) {
    let task; try { task = decodeTask(entry.fields) } catch { await this.ack(entry.id); return }
    if (!this.config.executionEnabled) { await this.event(createEvent(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'Playwright task transport accepted the immutable manifest reference.', this.now())); await this.event(createEvent(task, this.config.runnerId, 'REJECTED', 'PLAYWRIGHT_EXECUTION_DISABLED', 'Playwright execution is intentionally disabled until an isolated Runner is configured.', this.now())); await this.ack(entry.id); return }
    if (this.running.size >= this.config.maxConcurrentTasks) { await this.event(createEvent(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'Playwright task transport accepted the immutable manifest reference.', this.now())); await this.event(createEvent(task, this.config.runnerId, 'REJECTED', 'RUNNER_CAPACITY_EXHAUSTED', 'Playwright Runner has no free execution slot.', this.now())); await this.ack(entry.id); return }
    const active = { task, browser: null, context: null, cancelled: this.cancelled.has(task.taskId) }
    this.running.set(task.taskId, active); void this.heartbeat()
    try {
      await this.event(createEvent(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'Playwright task transport accepted the immutable manifest reference.', this.now()))
      await this.run(task, active)
      if (active.cancelled || this.cancelled.has(task.taskId)) await this.event(createEvent(task, this.config.runnerId, 'CANCELLED', 'PLAYWRIGHT_CANCELLATION_CONFIRMED', 'Playwright browser task was cancelled in the isolated Runner.', this.now()))
      else await this.event(createEvent(task, this.config.runnerId, 'SUCCEEDED', 'PLAYWRIGHT_EXECUTION_SUCCEEDED', 'Playwright scenario completed.', this.now()))
    } catch (error) {
      if (active.cancelled || this.cancelled.has(task.taskId)) await this.event(createEvent(task, this.config.runnerId, 'CANCELLED', 'PLAYWRIGHT_CANCELLATION_CONFIRMED', 'Playwright browser task was cancelled in the isolated Runner.', this.now()))
      else await this.event(createEvent(task, this.config.runnerId, 'FAILED', 'PLAYWRIGHT_EXECUTION_FAILED', text(error), this.now()))
    } finally {
      this.running.delete(task.taskId); this.cancelled.delete(task.taskId); void this.heartbeat(); await this.ack(entry.id)
    }
  }
  async run(task, active) {
    this.assertActive(active)
    const runtime = await this.runtime(task)
    this.assertActive(active)
    const root = join(this.config.workDirectory, task.executionId); await rm(root, { recursive: true, force: true }); await mkdir(root, { recursive: true, mode: 0o700 })
    active.root = root
    let failed = false, runError
    try {
      const browser = { chromium, firefox, webkit }[runtime.browser.browser]
      active.proxy = await createTargetProxy(this.config.allowedTargetOrigins)
      const instance = await browser.launch({ headless: true, proxy: { server: active.proxy.server } }); active.browser = instance
      this.assertActive(active)
      const context = await instance.newContext({ viewport: { width: runtime.browser.viewportWidth, height: runtime.browser.viewportHeight }, recordVideo: { dir: root }, serviceWorkers: 'block', proxy: { server: active.proxy.server } }); active.context = context
      this.assertActive(active)
      await context.routeWebSocket('**/*', socket => {
        const url = new URL(socket.url()); url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:'
        if (this.config.allowedTargetOrigins.includes(url.origin)) socket.connectToServer()
        else socket.close()
      })
      const page = await context.newPage(); await context.tracing.start({ screenshots: true, snapshots: true, sources: true })
      this.assertActive(active)
      await this.event(createEvent(task, this.config.runnerId, 'RUNNING', 'PLAYWRIGHT_BROWSER_STARTED', 'Isolated browser context started.', this.now()))
      for (const [index, step] of runtime.scenario.steps.entries()) {
        this.assertActive(active)
        const started = Date.now(); let error = ''
        try {
          if (step.type === 'SCREENSHOT') await page.screenshot({ path: join(root, `step-${index + 1}.png`), fullPage: true })
          else await this.step(page, step, runtime.scenario.variables ?? {}, runtime.browser.baseUrl)
        } catch (reason) { error = text(reason); failed = true }
        await this.stepResult(createStepResult(task, this.config.runnerId, step, index + 1, error ? 'FAILED' : 'PASSED', Math.min(Date.now() - started, 120000), error, this.now()))
        if (error) { await page.screenshot({ path: join(root, `failure-${index + 1}.png`), fullPage: true }).catch(() => {}); if (runtime.scenario.failureStrategy !== 'CONTINUE') break }
      }
    } catch (error) { runError = error }
    finally { await this.closeActive(active) }
    // Cancellation must still preserve the trace/video captured before the browser closed.
    try { for (const artifact of await this.artifacts(root)) await this.archive(task, artifact) }
    finally { await rm(root, { recursive: true, force: true }) }
    if (runError) throw runError
    this.assertActive(active)
    if (failed) throw new Error('PLAYWRIGHT_ASSERTION_FAILED: One or more declarative browser steps failed.')
  }

  async runtime(task) { const request = runtimeRequest(this.config, task); const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, { method: 'POST', headers: request.headers, body: request.body, signal: AbortSignal.timeout(5000) }); if (!response.ok) throw new Error(`运行时配置请求失败：HTTP ${response.status}`); return validateRuntime(task, (await response.json())?.data, this.config.allowedTargetOrigins) }
  async step(page, step, variables, base) {
    const timeout = step.timeoutMillis ?? 30000, selector = interpolate(step.selector, variables)
    if (step.type === 'NAVIGATE') { const response = await page.goto(target(interpolate(step.url, variables), base, this.config.allowedTargetOrigins), { waitUntil: 'domcontentloaded', timeout }); target(page.url(), base, this.config.allowedTargetOrigins); return response }
    if (step.type === 'WAIT') return page.waitForTimeout(timeout)
    const locator = page.locator(selector)
    if (step.type === 'FILL') return locator.fill(interpolate(step.value, variables), { timeout })
    if (step.type === 'CLICK') return locator.click({ timeout })
    if (step.type === 'ASSERT_VISIBLE') return locator.waitFor({ state: 'visible', timeout })
    if (step.type === 'ASSERT_TEXT') {
      const expected = interpolate(step.expectedText, variables), deadline = Date.now() + timeout
      do {
        const actual = await locator.textContent({ timeout: Math.max(1, deadline - Date.now()) })
        if (String(actual ?? '').includes(expected)) return
        if (Date.now() >= deadline) break
        await page.waitForTimeout(Math.min(100, deadline - Date.now()))
      } while (Date.now() < deadline)
      throw new Error('文本断言未通过')
    }
  }
  async artifacts(root) { const names = await (await import('node:fs/promises')).readdir(root); const list = []
    for (const name of names) { const path = join(root, name); const info = await stat(path); if (!info.isFile() || info.size > this.config.maxArtifactBytes) continue; const type = name === 'trace.zip' ? 'TRACE' : name.endsWith('.webm') ? 'VIDEO' : name.endsWith('.png') ? 'SCREENSHOT' : undefined; if (type) list.push({ type, path, size: info.size, contentType: type === 'TRACE' ? 'application/zip' : type === 'VIDEO' ? 'video/webm' : 'image/png' }) }
    return list
  }
  async archive(task, artifact) { const bytes = await readFile(artifact.path); const request = artifactRequest(this.config, task, { ...artifact, checksum: createHash('sha256').update(bytes).digest('hex') }); const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, { method: 'POST', headers: request.headers, body: bytes, signal: AbortSignal.timeout(10000) }); if (!response.ok) this.logger.warn(`Playwright 工件归档失败：${artifact.type} HTTP ${response.status}`) }
  assertActive(active) { if (active.cancelled || this.cancelled.has(active.task.taskId)) { const error = new Error('Playwright task cancelled'); error.code = 'CANCELLED'; throw error } }
  async closeActive(active) {
    if (active.closing) return active.closing
    const context = active.context, browser = active.browser
    if (!context && !browser && !active.proxy) return
    active.closing = (async () => {
      if (context && active.root) await context.tracing.stop({ path: join(active.root, 'trace.zip') }).catch(error => this.logger.warn?.('Playwright Trace 保存失败', text(error)))
      await context?.close().catch(error => this.logger.warn?.('Playwright Context 关闭失败', text(error)))
      await browser?.close().catch(error => this.logger.warn?.('Playwright Browser 关闭失败', text(error)))
      if (active.context === context) active.context = null
      if (active.browser === browser) active.browser = null
      await active.proxy?.close(); active.proxy = null
    })()
    try { await active.closing } finally { active.closing = null }
  }
  async event(value) { await this.commandClient.sendCommand(['XADD', EVENT_STREAM, 'MAXLEN', '~', '10000', '*', ...toFields(value)]) }
  async stepResult(value) { await this.commandClient.sendCommand(['XADD', STEP_RESULT_STREAM, 'MAXLEN', '~', '50000', '*', ...toFields(value)]) }
  async ack(id) { await this.commandClient.sendCommand(['XACK', TASK_STREAM, TASK_CONSUMER_GROUP, id]) }
  async cancelAck(id) { await this.commandClient.sendCommand(['XACK', CANCEL_STREAM, CANCEL_CONSUMER_GROUP, id]) }
}
