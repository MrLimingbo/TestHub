import { join } from 'node:path'
import { spawn as nodeSpawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFile, rm, stat } from 'node:fs/promises'
import { JmeterMetrics, jmeterArguments, prepareWorkDirectory } from './jmeter.js'
import { sampleProcessResources } from './resources.js'
import { CANCEL_CONSUMER_GROUP, CANCEL_STREAM, DEAD_LETTER_STREAM, EVENT_STREAM, HEARTBEAT_MEMBERS_KEY,
  ERROR_STREAM, METRIC_STREAM, RESOURCE_STREAM, TASK_CONSUMER_GROUP, TASK_STREAM, createError, createEvent, createHeartbeat, createMetric, createResource,
  createArtifactUploadRequest, createRuntimeConfigurationRequest, decodeCancelCommand, decodeTask, heartbeatKey, streamEntries,
  autoClaimEntries, toRedisFields, validateRuntimeConfiguration } from './protocol.js'

const CANCELLED_TASK_TTL_MILLISECONDS = 90 * 60 * 1000
const METRIC_INTERVAL_MILLISECONDS = 1000
const KILL_GRACE_MILLISECONDS = 5000
const DEFAULT_MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const message = (error) => error instanceof Error ? error.message : '未知 JMeter Runner 错误'
const PROXY_ENVIRONMENT_KEYS = ['ALL_PROXY', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'all_proxy', 'http_proxy', 'https_proxy', 'no_proxy']

function isolatedJmeterEnvironment() {
  const environment = {
    ...process.env,
    JVM_ARGS: '-Xms64m -Xmx512m -Djava.awt.headless=true -Djava.net.useSystemProxies=false -Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost=',
  }
  for (const key of [...PROXY_ENVIRONMENT_KEYS, 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']) delete environment[key]
  return environment
}

export class JMeterRunner {
  constructor({ commandClient, readClient, cancelReadClient, config, logger = console, now = () => new Date(),
    fetcher = globalThis.fetch, spawn = nodeSpawn, resourceSampler = sampleProcessResources }) {
    this.commandClient = commandClient; this.readClient = readClient; this.cancelReadClient = cancelReadClient
    this.config = config; this.logger = logger; this.now = now; this.fetcher = fetcher; this.spawn = spawn; this.resourceSampler = resourceSampler
    this.startedAt = this.now(); this.stopping = false; this.heartbeatTimer = undefined; this.claimRecoveryTimer = undefined; this.cancelledTasks = new Map()
    this.runningTasks = new Map(); this.taskCompletions = new Set()
  }

  async start() {
    await this.commandClient.connect(); await this.readClient.connect(); await this.cancelReadClient.connect()
    await this.ensureGroup(TASK_STREAM, TASK_CONSUMER_GROUP); await this.ensureGroup(CANCEL_STREAM, CANCEL_CONSUMER_GROUP)
    await this.recoverPending(this.cancelReadClient, CANCEL_STREAM, CANCEL_CONSUMER_GROUP, (entry) => this.handleCancellationEntry(entry))
    await this.recoverPending(this.readClient, TASK_STREAM, TASK_CONSUMER_GROUP, (entry) => this.handleEntry(entry))
    await this.recoverAbandonedTasks()
    await this.writeHeartbeat()
    this.heartbeatTimer = setInterval(() => this.writeHeartbeat().catch((error) => this.logger.error('JMeter Runner 心跳失败', message(error))), this.config.heartbeatIntervalSeconds * 1000)
    this.claimRecoveryTimer = setInterval(() => this.recoverAbandonedTasks().catch((error) => this.logger.warn?.(`JMeter Runner 认领遗留任务失败：${message(error)}`)),
      this.taskClaimIdleMilliseconds())
    this.logger.info(this.config.jmeterExecutionEnabled ? `JMeter Runner 已连接并启用执行：${this.config.runnerId}` : `JMeter Runner 已连接，JMeter 执行暂未启用：${this.config.runnerId}`)
    void this.consumeTaskLoop(); void this.consumeCancellationLoop()
  }

  async stop() {
    this.stopping = true
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    if (this.claimRecoveryTimer) clearInterval(this.claimRecoveryTimer)
    const running = [...this.runningTasks.values()]
    for (const task of running) this.stopProcess(task)
    // Keep transport clients alive until each owned JVM exits and archives its final result.
    await Promise.all(this.taskCompletions)
    await this.commandClient.multi().del(heartbeatKey(this.config.runnerId)).sRem(HEARTBEAT_MEMBERS_KEY, this.config.runnerId).exec()
    await Promise.allSettled([this.cancelReadClient.quit(), this.readClient.quit(), this.commandClient.quit()])
  }

  async ensureGroup(stream, group) {
    try { await this.commandClient.sendCommand(['XGROUP', 'CREATE', stream, group, '$', 'MKSTREAM']) } catch (error) {
      if (!String(error?.message ?? error).includes('BUSYGROUP')) throw error
    }
  }

  async recoverPending(client, stream, group, handle) {
    for (let batch = 0; batch < 20; batch += 1) {
      const reply = await client.sendCommand(['XREADGROUP', 'GROUP', group, this.config.runnerId, 'COUNT', '50', 'STREAMS', stream, '0'])
      const entries = streamEntries(reply)
      for (const entry of entries) await handle(entry)
      if (entries.length < 50) return
    }
  }

  async recoverAbandonedTasks() {
    let cursor = '0-0'
    for (let batch = 0; batch < 20 && !this.stopping; batch += 1) {
      const reply = await this.commandClient.sendCommand(['XAUTOCLAIM', TASK_STREAM, TASK_CONSUMER_GROUP, this.config.runnerId,
        String(this.taskClaimIdleMilliseconds()), cursor, 'COUNT', '20'])
      const entries = autoClaimEntries(reply)
      for (const entry of entries) await this.handleEntry(entry)
      cursor = Array.isArray(reply) && typeof reply[0] === 'string' ? reply[0] : '0-0'
      if (entries.length === 0 || cursor === '0-0') return
    }
  }

  taskClaimIdleMilliseconds() { return (this.config.taskClaimIdleSeconds ?? 45) * 1000 }

  async writeHeartbeat() {
    const body = JSON.stringify(createHeartbeat(this.config, this.startedAt, this.now(), this.runningTasks.size))
    await this.commandClient.multi().set(heartbeatKey(this.config.runnerId), body, { EX: this.config.heartbeatTtlSeconds })
      .sAdd(HEARTBEAT_MEMBERS_KEY, this.config.runnerId).exec()
  }

  async consumeTaskLoop() {
    while (!this.stopping) {
      try {
        const reply = await this.readClient.sendCommand(['XREADGROUP', 'GROUP', TASK_CONSUMER_GROUP, this.config.runnerId, 'COUNT', '1', 'BLOCK', '5000', 'STREAMS', TASK_STREAM, '>'])
        for (const entry of streamEntries(reply)) await this.handleEntry(entry)
      } catch (error) {
        if (!this.stopping) { this.logger.error('JMeter Runner 读取任务流失败', message(error)); await delay(1000) }
      }
    }
  }

  async consumeCancellationLoop() {
    while (!this.stopping) {
      try {
        const reply = await this.cancelReadClient.sendCommand(['XREADGROUP', 'GROUP', CANCEL_CONSUMER_GROUP, this.config.runnerId, 'COUNT', '1', 'BLOCK', '5000', 'STREAMS', CANCEL_STREAM, '>'])
        for (const entry of streamEntries(reply)) await this.handleCancellationEntry(entry)
      } catch (error) {
        if (!this.stopping) { this.logger.error('JMeter Runner 读取取消指令失败', message(error)); await delay(1000) }
      }
    }
  }

  async handleEntry(entry) {
    if (this.stopping) return
    let task
    try { task = decodeTask(entry.fields) } catch (error) { await this.deadLetter(entry, error); await this.acknowledgeTask(entry.id); return }
    if (this.isCancelled(task)) {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'CANCELLED', 'TASK_CANCELLED', 'JMeter task was cancelled before execution.', this.now()))
      await this.acknowledgeTask(entry.id); return
    }
    if (this.runningTasks.size >= (this.config.maxConcurrentTasks ?? 1)) {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'DEFERRED', 'RUNNER_CAPACITY_EXHAUSTED',
        'JMeter Runner has no free execution slot; the control plane may route this task to another Runner.', this.now()))
      await this.acknowledgeTask(entry.id); return
    }
    await this.publishEvent(createEvent(task, this.config.runnerId, 'ACCEPTED', 'TASK_ACCEPTED', 'JMeter task transport accepted the immutable manifest reference.', this.now()))
    if (!this.config.jmeterExecutionEnabled) {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'REJECTED', 'JMETER_EXECUTION_DISABLED', 'JMeter execution is intentionally disabled until the executor node is installed.', this.now()))
      await this.acknowledgeTask(entry.id); return
    }
    try {
      await this.startJmeterTask(task)
    } catch (error) {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'FAILED', 'JMETER_START_FAILED', `Unable to start isolated JMeter task: ${message(error).slice(0, 420)}`, this.now()))
    }
    await this.acknowledgeTask(entry.id)
  }

  async startJmeterTask(task) {
    const artifact = await this.requestRuntimeConfiguration(task)
    if (this.stopping) return
    if (this.isCancelled(task)) {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'CANCELLED', 'TASK_CANCELLED', 'JMeter task was cancelled before process creation.', this.now()))
      return
    }
    const workload = await prepareWorkDirectory(this.config, task, artifact)
    if (this.stopping) return
    const args = jmeterArguments(workload)
    if (this.config.javaBin) args.unshift('-Xms64m', '-Xmx512m', '-Djava.awt.headless=true', '-Djava.net.useSystemProxies=false',
      '-Dhttp.proxyHost=', '-Dhttps.proxyHost=', '-DsocksProxyHost=', '-jar', join(this.config.jmeterHome, 'bin/ApacheJMeter.jar'))
    if (this.config.watchdogJar) args.unshift(`-javaagent:${this.config.watchdogJar}`)
    const child = this.spawn(this.config.javaBin ?? this.config.jmeterBin, args, {
      // Keep the isolated process bounded, and disable both inherited and native
      // system proxies: a proxy would bypass the Runner target-origin allow list.
      cwd: workload.root, env: isolatedJmeterEnvironment(),
      stdio: [this.config.watchdogJar ? 'pipe' : 'ignore', 'pipe', 'pipe'], windowsHide: true, shell: false, detached: process.platform !== 'win32',
    })
    const running = { task, workload, child, cancelled: false, finalized: false,
      metrics: new JmeterMetrics({ resultsPath: workload.resultsPath, startedAt: this.now().getTime() }), metricTimer: undefined, resourceTimer: undefined }
    this.runningTasks.set(task.taskId, running)
    void this.writeHeartbeat().catch((error) => this.logger.warn?.(`JMeter Runner 槽位心跳刷新失败：${message(error)}`))
    child.stdout?.on('data', (data) => this.logger.info(`JMeter ${task.taskId}: ${String(data).trim().slice(0, 500)}`))
    child.stderr?.on('data', (data) => this.logger.warn?.(`JMeter ${task.taskId}: ${String(data).trim().slice(0, 500)}`))
    running.done = new Promise(resolve => {
      let finishing = false
      const finish = async (code, error) => {
        if (finishing) return
        finishing = true
        try { await this.finishTask(running, code, error) }
        catch (failure) { this.logger.error('JMeter 任务收尾失败', message(failure)) }
        finally { resolve() }
      }
      child.once('error', error => void finish(undefined, error))
      child.once('close', code => void finish(code, undefined))
    })
    this.taskCompletions.add(running.done)
    void running.done.then(() => this.taskCompletions.delete(running.done))
    running.metricTimer = setInterval(() => void this.emitMetrics(running), METRIC_INTERVAL_MILLISECONDS)
    running.resourceTimer = setInterval(() => void this.emitResource(running), METRIC_INTERVAL_MILLISECONDS)
    await this.publishEvent(createEvent(task, this.config.runnerId, 'RUNNING', 'JMETER_PROCESS_STARTED', 'JMeter non-GUI process started in the isolated Runner.', this.now()))
    await this.emitMetrics(running)
    await this.emitResource(running)
  }

  async requestRuntimeConfiguration(task) {
    const request = createRuntimeConfigurationRequest(this.config, task, this.now())
    const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, {
      method: 'POST', headers: request.headers, body: JSON.stringify(request.body), signal: globalThis.AbortSignal.timeout(5000),
    })
    if (!response.ok) throw new Error(`运行时工件请求失败：HTTP ${response.status}`)
    const body = await response.json()
    return validateRuntimeConfiguration(task, body?.data, this.config.allowedTargetOrigins)
  }

  async emitMetrics(running) {
    if (running.finalized) return
    try {
      for (const metric of await running.metrics.refresh()) await this.publishMetric(createMetric(running.task, this.config.runnerId, metric, this.now()))
    } catch (error) {
      this.logger.warn?.(`JMeter 指标读取失败：${message(error)}`)
    }
  }

  async emitResource(running) {
    if (running.finalized) return
    try {
      const resource = await this.resourceSampler(running.child?.pid)
      await this.publishResource(createResource(running.task, this.config.runnerId, resource, this.now()))
    } catch (error) {
      this.logger.warn?.(`JMeter 运行资源读取失败：${message(error)}`)
    }
  }

  async finishTask(running, exitCode, processError) {
    if (running.finalized) return
    if (running.metricTimer) clearInterval(running.metricTimer)
    if (running.resourceTimer) clearInterval(running.resourceTimer)
    await this.emitMetrics(running)
    await this.emitResource(running)
    for (const error of running.metrics.errorSummaries()) await this.publishError(createError(running.task, this.config.runnerId, error, this.now()))
    running.finalized = true
    this.runningTasks.delete(running.task.taskId)
    void this.writeHeartbeat().catch((error) => this.logger.warn?.(`JMeter Runner 槽位心跳刷新失败：${message(error)}`))
    const task = running.task
    await this.archiveWorkloadArtifacts(running)
    if (running.cancelled || this.isCancelled(task)) {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'CANCELLED', 'JMETER_PROCESS_CANCELLED', 'JMeter process stopped after a cancellation request.', this.now()))
    } else if (processError || exitCode !== 0) {
      const detail = processError ? message(processError) : `JMeter exited with code ${exitCode}`
      await this.publishEvent(createEvent(task, this.config.runnerId, 'FAILED', 'JMETER_PROCESS_FAILED', detail.slice(0, 512), this.now()))
    } else {
      await this.publishEvent(createEvent(task, this.config.runnerId, 'SUCCEEDED', 'JMETER_PROCESS_SUCCEEDED', 'JMeter process completed and its metrics were collected.', this.now()))
    }
    await rm(running.workload.root, { recursive: true, force: true }).catch(() => {})
  }

  async archiveWorkloadArtifacts(running) {
    const artifacts = [
      { artifactType: 'JMETER_JTL', path: running.workload.resultsPath },
      { artifactType: 'JMETER_LOG', path: running.workload.logPath },
    ]
    for (const artifact of artifacts) {
      try {
        const metadata = await stat(artifact.path)
        if (!metadata.isFile() || metadata.size > (this.config.maxArchiveBytes ?? DEFAULT_MAX_ARCHIVE_BYTES)) {
          this.logger.warn?.(`跳过 JMeter 工件归档：${artifact.artifactType} 超出受限大小或不是普通文件`)
          continue
        }
        const content = await readFile(artifact.path)
        const checksum = createHash('sha256').update(content).digest('hex')
        const request = createArtifactUploadRequest(this.config, running.task,
          { artifactType: artifact.artifactType, sizeBytes: content.byteLength, checksum }, this.now())
        const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, {
          method: 'POST', headers: request.headers, body: content, signal: globalThis.AbortSignal.timeout(10_000),
        })
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        this.logger.info(`JMeter 工件已归档：${artifact.artifactType} · ${content.byteLength} bytes`)
      } catch (error) {
        if (error?.code === 'ENOENT') this.logger.warn?.(`JMeter 工件不存在，已跳过归档：${artifact.artifactType}`)
        else this.logger.warn?.(`JMeter 工件归档失败：${artifact.artifactType}：${message(error)}`)
      }
    }
  }

  async handleCancellationEntry(entry) {
    let command
    try { command = decodeCancelCommand(entry.fields) } catch (error) { await this.deadLetter(entry, error); await this.acknowledgeCancellation(entry.id); return }
    this.cancelledTasks.set(command.taskId, this.now().getTime() + CANCELLED_TASK_TTL_MILLISECONDS)
    const running = this.runningTasks.get(command.taskId)
    if (running) {
      running.cancelled = true
      this.stopProcess(running)
    } else {
      await this.publishEvent(createEvent(command, this.config.runnerId, 'CANCELLED', 'CANCEL_COMMAND_ACCEPTED', 'JMeter task cancellation command accepted before process start.', this.now()))
    }
    await this.acknowledgeCancellation(entry.id)
  }

  stopProcess(running) {
    if (running.finalized || running.stopRequested) return
    running.stopRequested = true
    const signal = (name) => {
      try {
        // The JMeter launcher is a shell script; its Java child must stop too.
        if (process.platform !== 'win32' && running.child.pid) process.kill(-running.child.pid, name)
        else if (running.child.exitCode === null) running.child.kill(name)
      } catch (error) {
        if (error?.code !== 'ESRCH') throw error
      }
    }
    signal('SIGTERM')
    setTimeout(() => { if (!running.finalized) signal('SIGKILL') }, KILL_GRACE_MILLISECONDS).unref?.()
  }

  isCancelled(task) {
    const until = this.cancelledTasks.get(task.taskId)
    if (!until) return false
    if (until < this.now().getTime()) { this.cancelledTasks.delete(task.taskId); return false }
    return true
  }

  async publishEvent(event) { await this.commandClient.sendCommand(['XADD', EVENT_STREAM, 'MAXLEN', '~', '10000', '*', ...toRedisFields(event)]) }
  async publishMetric(metric) { await this.commandClient.sendCommand(['XADD', METRIC_STREAM, 'MAXLEN', '~', '50000', '*', ...toRedisFields(metric)]) }
  async publishResource(resource) { await this.commandClient.sendCommand(['XADD', RESOURCE_STREAM, 'MAXLEN', '~', '50000', '*', ...toRedisFields(resource)]) }
  async publishError(error) { await this.commandClient.sendCommand(['XADD', ERROR_STREAM, 'MAXLEN', '~', '10000', '*', ...toRedisFields(error)]) }
  async deadLetter(entry, error) { await this.commandClient.sendCommand(['XADD', DEAD_LETTER_STREAM, 'MAXLEN', '~', '1000', '*', 'streamId', String(entry.id), 'code', error?.code ?? 'RUNNER_ERROR']) }
  async acknowledgeTask(id) { await this.commandClient.sendCommand(['XACK', TASK_STREAM, TASK_CONSUMER_GROUP, id]) }
  async acknowledgeCancellation(id) { await this.commandClient.sendCommand(['XACK', CANCEL_STREAM, CANCEL_CONSUMER_GROUP, id]) }
}
