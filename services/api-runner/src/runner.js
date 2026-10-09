import {
  CANCEL_CONSUMER_GROUP,
  CANCEL_STREAM,
  DEAD_LETTER_STREAM,
  EVENT_STREAM,
  HEARTBEAT_MEMBERS_KEY,
  TASK_STREAM,
  TASK_CONSUMER_GROUP,
  createEvent,
  createHttpResult,
  createScriptResult,
  createStepResult,
  createCapabilityDiscoveryRequest,
  createRunnerLog,
  createRuntimeConfigurationRequest,
  createRuntimeLeaseRequest,
  createHeartbeat,
  decodeCancelCommand,
  decodeTask,
  heartbeatKey,
  streamEntries,
  toRedisFields,
  RUNNER_LOG_STREAM,
  HTTP_RESULT_STREAM,
  SCRIPT_RESULT_STREAM,
  STEP_RESULT_STREAM,
} from './protocol.js'
import { executeHttpRequest } from './http-executor.js'
import { executeIsolatedScript, IsolatedScriptError, validateIsolatedScript } from './script-executor.js'

const EVENT_MAX_LENGTH = '10000'
const CANCELLED_TASK_TTL_MILLISECONDS = 90 * 60 * 1000
const RETRY_BACKOFF_MILLISECONDS = 250
const RETRYABLE_HTTP_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function message(error) {
  return error instanceof Error ? error.message : '未知 Runner 错误'
}

export class ApiRunner {
  constructor({ commandClient, readClient, cancelReadClient, config, logger = console, now = () => new Date(),
    fetcher = globalThis.fetch, httpExecutor = executeHttpRequest }) {
    this.commandClient = commandClient
    this.readClient = readClient
    this.cancelReadClient = cancelReadClient
    this.config = config
    this.logger = logger
    this.now = now
    this.fetcher = fetcher
    this.httpExecutor = httpExecutor
    this.startedAt = this.now()
    this.stopping = false
    this.heartbeatTimer = undefined
    this.cancelledTasks = new Map()
    this.runtimeConfiguration = 'DISABLED'
  }

  async start() {
    await this.commandClient.connect()
    await this.readClient.connect()
    await this.cancelReadClient.connect()
    await this.ensureConsumerGroup(TASK_STREAM, TASK_CONSUMER_GROUP)
    await this.ensureConsumerGroup(CANCEL_STREAM, CANCEL_CONSUMER_GROUP)
    await this.writeHeartbeat()
    await this.discoverCapabilities().catch((error) => this.logger.error('Runner 签名能力发现失败', message(error)))
    this.heartbeatTimer = setInterval(() => this.writeHeartbeat().catch((error) => this.logger.error('Runner 心跳失败', message(error))),
      this.config.heartbeatIntervalSeconds * 1000)
    this.logger.info(this.config.httpExecutionEnabled
      ? `API Runner 已连接，受限 HTTP 执行已启用：${this.config.runnerId}`
      : `API Runner 已连接，任务执行保持禁用：${this.config.runnerId}`)
    void this.consumeTaskLoop()
    void this.consumeCancellationLoop()
  }

  async stop() {
    this.stopping = true
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    await this.commandClient.multi()
      .del(heartbeatKey(this.config.runnerId))
      .sRem(HEARTBEAT_MEMBERS_KEY, this.config.runnerId)
      .exec()
    await Promise.allSettled([this.cancelReadClient.quit(), this.readClient.quit(), this.commandClient.quit()])
  }

  async ensureConsumerGroup(stream, group) {
    try {
      await this.commandClient.sendCommand(['XGROUP', 'CREATE', stream, group, '$', 'MKSTREAM'])
    } catch (error) {
      if (!String(error?.message ?? error).includes('BUSYGROUP')) throw error
    }
  }

  async writeHeartbeat() {
    const body = JSON.stringify(createHeartbeat(this.config, this.startedAt, this.now()))
    await this.commandClient.multi()
      .set(heartbeatKey(this.config.runnerId), body, { EX: this.config.heartbeatTtlSeconds })
      .sAdd(HEARTBEAT_MEMBERS_KEY, this.config.runnerId)
      .exec()
  }

  async discoverCapabilities() {
    if (!this.config.controlPlaneUrl || !this.config.heartbeatSigningKey) return
    if (typeof this.fetcher !== 'function') throw new Error('当前 Node.js 运行环境不支持 fetch')
    const discovery = createCapabilityDiscoveryRequest(this.config, this.now())
    const response = await this.fetcher(`${this.config.controlPlaneUrl}${discovery.path}`, {
      method: 'GET', headers: discovery.headers, signal: globalThis.AbortSignal.timeout(5000),
    })
    if (!response?.ok) throw new Error(`控制面能力发现返回 HTTP ${response?.status ?? 'unknown'}`)
    const body = await response.json()
    const runtimeConfiguration = body?.data?.runtimeConfiguration
    const executionEnabled = this.config.httpExecutionEnabled === true
    const expectedRuntimeConfiguration = executionEnabled ? 'HTTP_FLOW_CONTROL_SCRIPTS_V2' : 'DISABLED'
    if (body?.data?.runnerId !== this.config.runnerId || body?.data?.trusted !== true
      || runtimeConfiguration !== expectedRuntimeConfiguration || body?.data?.executionEnabled !== executionEnabled) {
      throw new Error('控制面能力发现响应不兼容')
    }
    this.runtimeConfiguration = runtimeConfiguration
    this.logger.info(`API Runner 已完成签名能力发现：${this.config.runnerId}`)
  }

  async consumeTaskLoop() {
    while (!this.stopping) {
      try {
        const reply = await this.readClient.sendCommand([
          'XREADGROUP', 'GROUP', TASK_CONSUMER_GROUP, this.config.runnerId, 'COUNT', '1', 'BLOCK', '5000',
          'STREAMS', TASK_STREAM, '>',
        ])
        for (const entry of streamEntries(reply)) await this.handleEntry(entry)
      } catch (error) {
        if (!this.stopping) {
          this.logger.error('Runner 读取任务流失败', message(error))
          await delay(1000)
        }
      }
    }
  }

  async consumeCancellationLoop() {
    while (!this.stopping) {
      try {
        const reply = await this.cancelReadClient.sendCommand([
          'XREADGROUP', 'GROUP', CANCEL_CONSUMER_GROUP, this.config.runnerId, 'COUNT', '1', 'BLOCK', '5000',
          'STREAMS', CANCEL_STREAM, '>',
        ])
        for (const entry of streamEntries(reply)) await this.handleCancellationEntry(entry)
      } catch (error) {
        if (!this.stopping) {
          this.logger.error('Runner 读取取消指令失败', message(error))
          await delay(1000)
        }
      }
    }
  }

  async handleEntry(entry) {
    let task
    try {
      task = decodeTask(entry.fields)
    } catch (error) {
      await this.deadLetter(entry, error)
      await this.acknowledgeTask(entry.id)
      return
    }
    await this.publishLog(createRunnerLog(task, this.config.runnerId, 'TASK_RECEIVED', this.now()))
    await this.publishLog(createRunnerLog(task, this.config.runnerId, 'PRECHECK_WAITING', this.now()))
    if (this.isCancelled(task)) {
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'TASK_CANCELLED', this.now()))
      await this.acknowledgeTask(entry.id)
      return
    }
    await delay(this.config.precheckDelayMilliseconds ?? 0)
    if (this.isCancelled(task)) {
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'TASK_CANCELLED', this.now()))
      await this.acknowledgeTask(entry.id)
      return
    }
    if (this.config.httpExecutionEnabled && this.runtimeConfiguration === 'DISABLED') {
      await this.discoverCapabilities().catch((error) => this.logger.error('Runner 签名能力发现失败', message(error)))
    }
    if (this.config.httpExecutionEnabled && this.runtimeConfiguration === 'HTTP_FLOW_CONTROL_SCRIPTS_V2') {
      await this.executeHttpTask(task)
    } else {
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'EXECUTION_DISABLED', this.now()))
      await this.publishEvent(createEvent(task, this.config.runnerId, 'REJECTED', 'EXECUTION_DISABLED',
        'The API Runner execution engine is not enabled in this release.', this.now()))
    }
    await this.acknowledgeTask(entry.id)
  }

  async executeHttpTask(task) {
    try {
      const lease = await this.requestRuntimeLease(task)
      const artifact = await this.requestRuntimeConfiguration(task, lease)
      this.validateArtifact(task, artifact)
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'RUNTIME_ARTIFACT_RECEIVED', this.now()))
      await this.publishEvent(createEvent(task, this.config.runnerId, 'ACCEPTED', 'RUNTIME_ARTIFACT_RECEIVED',
        'A bounded HTTP runtime artifact was received.', this.now()))
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'HTTP_EXECUTION_STARTED', this.now()))
      await this.publishEvent(createEvent(task, this.config.runnerId, 'RUNNING', 'HTTP_EXECUTION_STARTED',
        'Bounded HTTP request execution started.', this.now()))
      const localVariables = new Map(Object.entries(artifact.variables))
      const requestStates = []
      let lastRequest
      let requestSequence = 0
      let skipNextRequest = false
      let continuedFailure = false
      for (const [index, step] of artifact.steps.entries()) {
        if (this.isCancelled(task)) return
        if (step.type === 'PARALLEL') {
          const firstRequestSequence = requestSequence + 1
          requestSequence += step.parallelRequests.length
          await this.publishLog(createRunnerLog(task, this.config.runnerId, 'PARALLEL_REQUESTS_STARTED', this.now()))
          const outcomes = await Promise.allSettled(step.parallelRequests.map((request, branchIndex) =>
            this.executeParallelRequest(task, step.stepId, request, firstRequestSequence + branchIndex, localVariables)))
          if (this.isCancelled(task)) return
          if (outcomes.some((outcome) => outcome.status !== 'fulfilled' || outcome.value === null)) {
            throw new Error('并行请求组未能完成')
          }
          requestStates.push(...outcomes.map((outcome) => outcome.value))
          lastRequest = undefined
          await this.publishLog(createRunnerLog(task, this.config.runnerId, 'PARALLEL_REQUESTS_COMPLETED', this.now()))
          continue
        }
        if (step.type === 'WAIT') {
          if (!await this.waitForStep(task, step.delayMillis)) return
          await this.publishLog(createRunnerLog(task, this.config.runnerId, 'WAIT_COMPLETED', this.now()))
          continue
        }
        if (step.type === 'CONDITION') {
          if (!lastRequest) throw new Error('条件步骤前没有可用 HTTP 状态')
          const matched = step.conditionOperator === 'EQUALS'
            ? lastRequest.status === step.expectedStatusCode
            : lastRequest.status !== step.expectedStatusCode
          skipNextRequest = !matched
          await this.publishLog(createRunnerLog(task, this.config.runnerId,
            matched ? 'CONDITION_PASSED' : 'CONDITION_SKIPPED', this.now()))
          continue
        }
        if (step.type === 'LOOP') {
          if (!lastRequest?.template) throw new Error('循环步骤前没有可重复的请求')
          for (let run = 1; run < step.loopTotalRuns; run += 1) {
            if (this.isCancelled(task)) return
            lastRequest = await this.executeRequest(task, lastRequest.template, ++requestSequence, localVariables, artifact.scripts,
              artifact.failureStrategy ?? 'STOP')
            if (!lastRequest) return
            requestStates.push(lastRequest)
            continuedFailure ||= lastRequest.continuedFailure === true
          }
          await this.publishLog(createRunnerLog(task, this.config.runnerId, 'LOOP_COMPLETED', this.now()))
          continue
        }
        if (step.type === 'EXTRACT') {
          if (!lastRequest) throw new Error('数据提取前没有可用 HTTP 状态')
          localVariables.set(step.variableName, String(lastRequest.status))
          await this.publishStepResult(createStepResult(task, this.config.runnerId, step, index + 1,
            'COMPLETED', lastRequest.status, this.now()))
          continue
        }
        if (step.type === 'ASSERT') {
          if (!lastRequest) throw new Error('断言前没有可用 HTTP 状态')
          const outcome = lastRequest.status === step.expectedStatusCode ? 'PASSED' : 'FAILED'
          lastRequest.asserted = true
          await this.publishStepResult(createStepResult(task, this.config.runnerId, step, index + 1,
            outcome, lastRequest.status, this.now()))
          if (outcome === 'FAILED') {
            if ((artifact.failureStrategy ?? 'STOP') !== 'CONTINUE') throw new Error('HTTP 状态码断言未通过')
            continuedFailure = true
            await this.publishLog(createRunnerLog(task, this.config.runnerId, 'FAILURE_CONTINUED', this.now()))
          }
          continue
        }
        if (skipNextRequest) {
          skipNextRequest = false
          lastRequest = undefined
          continue
        }
        lastRequest = await this.executeRequest(task, step, ++requestSequence, localVariables, artifact.scripts,
          artifact.failureStrategy ?? 'STOP')
        if (!lastRequest) return
        requestStates.push(lastRequest)
        continuedFailure ||= lastRequest.continuedFailure === true
      }
      if (continuedFailure || requestStates.some((request) => (request.status < 200 || request.status >= 300) && !request.asserted)) {
        throw new Error('HTTP 非成功响应未被状态码断言覆盖')
      }
      if (this.isCancelled(task)) return
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'HTTP_EXECUTION_SUCCEEDED', this.now()))
      await this.publishEvent(createEvent(task, this.config.runnerId, 'SUCCEEDED', 'HTTP_EXECUTION_SUCCEEDED',
        'Bounded HTTP request execution completed.', this.now()))
    } catch {
      await this.publishLog(createRunnerLog(task, this.config.runnerId, 'HTTP_EXECUTION_FAILED', this.now()))
      await this.publishEvent(createEvent(task, this.config.runnerId, 'FAILED', 'HTTP_EXECUTION_FAILED',
        'Bounded HTTP request execution failed without persisting request or response content.', this.now()))
    }
  }

  async executeRequest(task, template, requestSequence, localVariables, scripts, failureStrategy) {
    if (this.isCancelled(task)) return null
    await this.executeScript(task, 'PRE_REQUEST', scripts.preRequestScript, localVariables, null)
    const request = this.resolveRequest(template, localVariables)
    const maxAttempts = Number.isInteger(template.maxAttempts) ? template.maxAttempts : 1
    for (let attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber += 1) {
      const startedAt = this.now().getTime()
      let result
      try {
        result = await this.httpExecutor(request, { allowedHttpOrigins: this.config.allowedHttpOrigins, allowPrivateNetwork: this.config.allowPrivateNetwork === true, deadlineAt: task.deadlineAt })
      } catch {
        result = { status: 0, ok: false }
      }
      await this.publishHttpResult(createHttpResult(task, this.config.runnerId, request, requestSequence, attemptNumber, result,
        Math.min(20_000, Math.max(0, this.now().getTime() - startedAt)), this.now()))
      if (this.shouldRetry(request, result, attemptNumber, maxAttempts) && this.canRetry(task)) {
        await this.publishLog(createRunnerLog(task, this.config.runnerId, 'HTTP_RETRY_SCHEDULED', this.now()))
        if (!await this.waitForRetry(task)) return null
        continue
      }
      if (!result?.status) throw new Error('HTTP 请求未完成')
      let continuedFailure = false
      try {
        await this.executeScript(task, 'POST_RESPONSE', scripts.postResponseScript, localVariables, { status: result.status })
      } catch {
        if (failureStrategy !== 'CONTINUE') throw new Error('POST_RESPONSE_SCRIPT_FAILED')
        continuedFailure = true
        await this.publishLog(createRunnerLog(task, this.config.runnerId, 'FAILURE_CONTINUED', this.now()))
      }
      return { status: result.status, asserted: false, template, continuedFailure }
    }
    throw new Error('HTTP 请求未完成')
  }

  async executeParallelRequest(task, stepId, template, requestSequence, localVariables) {
    if (this.isCancelled(task)) return null
    const request = this.resolveRequest({ ...template, stepId }, localVariables)
    const maxAttempts = Number.isInteger(template.maxAttempts) ? template.maxAttempts : 1
    for (let attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber += 1) {
      const startedAt = this.now().getTime()
      let result
      try {
        result = await this.httpExecutor(request, { allowedHttpOrigins: this.config.allowedHttpOrigins, allowPrivateNetwork: this.config.allowPrivateNetwork === true, deadlineAt: task.deadlineAt })
      } catch {
        result = { status: 0, ok: false }
      }
      await this.publishHttpResult(createHttpResult(task, this.config.runnerId, request, requestSequence, attemptNumber, result,
        Math.min(20_000, Math.max(0, this.now().getTime() - startedAt)), this.now()))
      if (this.shouldRetry(request, result, attemptNumber, maxAttempts) && this.canRetry(task)) {
        await this.publishLog(createRunnerLog(task, this.config.runnerId, 'HTTP_RETRY_SCHEDULED', this.now()))
        if (!await this.waitForRetry(task)) return null
        continue
      }
      if (!result?.status) throw new Error('HTTP 请求未完成')
      return { status: result.status, asserted: false, template: null, continuedFailure: false }
    }
    throw new Error('HTTP 请求未完成')
  }

  shouldRetry(request, result, attemptNumber, maxAttempts) {
    const status = Number.isInteger(result?.status) ? result.status : 0
    return attemptNumber < maxAttempts && RETRYABLE_HTTP_METHODS.has(request.method)
      && (status === 0 || status === 408 || status === 429 || (status >= 500 && status <= 599))
  }

  canRetry(task) {
    const deadlineAt = Date.parse(task.deadlineAt)
    return Number.isFinite(deadlineAt) && this.now().getTime() + RETRY_BACKOFF_MILLISECONDS < deadlineAt && !this.isCancelled(task)
  }

  async waitForRetry(task) {
    let remaining = RETRY_BACKOFF_MILLISECONDS
    while (remaining > 0) {
      const interval = Math.min(remaining, 50)
      await delay(interval)
      if (this.isCancelled(task) || !this.canRetry(task)) return false
      remaining -= interval
    }
    return true
  }

  async executeScript(task, phase, source, localVariables, response) {
    if (!source) return
    try {
      const result = executeIsolatedScript({ phase, source, variables: Object.fromEntries(localVariables), response })
      localVariables.clear()
      for (const [name, value] of Object.entries(result.variables)) localVariables.set(name, value)
      if (result.testSummary.testCount > 0) {
        await this.publishScriptResult(createScriptResult(task, this.config.runnerId, phase, result.testSummary, this.now()))
      }
      if (result.testSummary.failedCount > 0) throw new IsolatedScriptError('SCRIPT_TEST_FAILED')
      await this.publishLog(createRunnerLog(task, this.config.runnerId, `${phase}_SCRIPT_COMPLETED`, this.now()))
    } catch (error) {
      await this.publishLog(createRunnerLog(task, this.config.runnerId, `${phase}_SCRIPT_FAILED`, this.now()))
      if (error instanceof IsolatedScriptError) throw new Error(`${phase}_SCRIPT_FAILED`)
      throw error
    }
  }

  async waitForStep(task, delayMillis) {
    const deadlineAt = Date.parse(task.deadlineAt)
    if (!Number.isInteger(delayMillis) || delayMillis < 0 || delayMillis > 600_000 || this.now().getTime() + delayMillis >= deadlineAt) {
      throw new Error('等待步骤超过任务截止时间')
    }
    let remaining = delayMillis
    while (remaining > 0) {
      const interval = Math.min(remaining, 100)
      await delay(interval)
      if (this.isCancelled(task)) return false
      if (this.now().getTime() >= deadlineAt) throw new Error('等待步骤超过任务截止时间')
      remaining -= interval
    }
    return true
  }

  async requestRuntimeLease(task) {
    const request = createRuntimeLeaseRequest(this.config, task, this.now())
    const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, {
      method: 'POST', headers: request.headers, body: JSON.stringify(request.body), signal: globalThis.AbortSignal.timeout(5000),
    })
    const body = await response?.json?.()
    if (!response?.ok || body?.data?.executionId !== task.executionId || body?.data?.taskId !== task.taskId
      || typeof body?.data?.lease !== 'string' || body.data.purpose !== 'HTTP_REQUEST_EXECUTION') {
      throw new Error('Runner 运行时租约响应不兼容')
    }
    return body.data.lease
  }

  async requestRuntimeConfiguration(task, lease) {
    const request = createRuntimeConfigurationRequest(this.config, task, lease, this.now())
    const response = await this.fetcher(`${this.config.controlPlaneUrl}${request.path}`, {
      method: 'POST', headers: request.headers, body: JSON.stringify(request.body), signal: globalThis.AbortSignal.timeout(5000),
    })
    const body = await response?.json?.()
    if (!response?.ok) throw new Error('Runner 运行时工件请求失败')
    return body?.data
  }

  validateArtifact(task, artifact) {
    if (artifact?.contract !== 'testhub.api-runner.runtime-configuration' || artifact?.artifactVersion !== 5
      || artifact?.taskId !== task.taskId || artifact?.executionId !== task.executionId || !Array.isArray(artifact.steps)
      || artifact.steps.length < 1 || artifact.steps.length > 300) throw new Error('Runner 运行时工件不兼容')
    try {
      if (artifact.variables === null || typeof artifact.variables !== 'object' || Array.isArray(artifact.variables)
        || Object.keys(artifact.variables).length > 100 || !artifact.scripts || typeof artifact.scripts !== 'object') {
        throw new Error('invalid')
      }
      if (artifact.failureStrategy !== undefined && !['STOP', 'CONTINUE'].includes(artifact.failureStrategy)) throw new Error('invalid')
      for (const [name, value] of Object.entries(artifact.variables)) {
        if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(name) || typeof value !== 'string' || value.length > 10_000) throw new Error('invalid')
      }
      validateIsolatedScript(artifact.scripts.preRequestScript)
      validateIsolatedScript(artifact.scripts.postResponseScript)
      if (artifact.steps.some((step) => step?.type === 'PARALLEL')
        && (artifact.scripts.preRequestScript || artifact.scripts.postResponseScript)) throw new Error('invalid')
    } catch {
      throw new Error('Runner 运行时工件不安全')
    }
    const validateRequest = (request, requiresStepId) => {
      if (!request || (requiresStepId && (typeof request.stepId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.stepId)))
        || typeof request.method !== 'string' || typeof request.url !== 'string' || typeof request.body !== 'string'
        || typeof request.contentType !== 'string' || request.url.length > 2048 || request.body.length > 65_536
        || !['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].includes(request.method)) {
        throw new Error('Runner 运行时工件不安全')
      }
      const maxAttempts = request.maxAttempts === undefined ? 1 : request.maxAttempts
      if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 3
        || (maxAttempts > 1 && !RETRYABLE_HTTP_METHODS.has(request.method))) {
        throw new Error('Runner 运行时工件不安全')
      }
    }
    let requestSeen = false
    let latestRequestIsSingle = false
    let expandedRequestCount = 0
    for (const [index, step] of artifact.steps.entries()) {
      if (!step || typeof step.stepId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(step.stepId)) throw new Error('Runner 运行时工件不安全')
      if (step.type === 'REQUEST') {
        validateRequest(step, false)
        requestSeen = true
        latestRequestIsSingle = true
        expandedRequestCount += 1
      } else if (step.type === 'PARALLEL') {
        if (!Array.isArray(step.parallelRequests) || step.parallelRequests.length < 2 || step.parallelRequests.length > 5) {
          throw new Error('Runner 运行时工件不安全')
        }
        for (const request of step.parallelRequests) validateRequest(request, false)
        requestSeen = true
        latestRequestIsSingle = false
        expandedRequestCount += step.parallelRequests.length
      } else if (step.type === 'EXTRACT') {
        if (!latestRequestIsSingle || typeof step.variableName !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(step.variableName)) {
          throw new Error('Runner 运行时工件不安全')
        }
      } else if (step.type === 'ASSERT') {
        if (!latestRequestIsSingle || !Number.isInteger(step.expectedStatusCode) || step.expectedStatusCode < 100 || step.expectedStatusCode > 599) {
          throw new Error('Runner 运行时工件不安全')
        }
      } else if (step.type === 'WAIT') {
        if (!Number.isInteger(step.delayMillis) || step.delayMillis < 0 || step.delayMillis > 600_000) {
          throw new Error('Runner 运行时工件不安全')
        }
      } else if (step.type === 'CONDITION') {
        if (!latestRequestIsSingle || !Number.isInteger(step.expectedStatusCode) || step.expectedStatusCode < 100 || step.expectedStatusCode > 599
          || !['EQUALS', 'NOT_EQUALS'].includes(step.conditionOperator) || artifact.steps[index + 1]?.type !== 'REQUEST'
          || artifact.steps[index + 2]?.type === 'LOOP') {
          throw new Error('Runner 运行时工件不安全')
        }
      } else if (step.type === 'LOOP') {
        if (artifact.steps[index - 1]?.type !== 'REQUEST' || !Number.isInteger(step.loopTotalRuns)
          || step.loopTotalRuns < 1 || step.loopTotalRuns > 10) {
          throw new Error('Runner 运行时工件不安全')
        }
        expandedRequestCount += step.loopTotalRuns - 1
      } else throw new Error('Runner 运行时工件不安全')
    }
    if (!requestSeen || expandedRequestCount > 300) throw new Error('Runner 运行时工件不安全')
  }

  resolveRequest(step, localVariables) {
    const resolve = (value) => value.replace(/\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}/g, (_reference, name) => {
      const result = localVariables.get(name)
      if (result === undefined) throw new Error('请求引用了尚未提取的受限变量')
      return result
    })
    const request = { ...step, url: resolve(step.url), body: resolve(step.body) }
    if (request.url.includes('{{') || !this.config.allowedHttpOrigins.includes(new URL(request.url).origin)) {
      throw new Error('Runner 运行时工件不安全')
    }
    return request
  }

  async handleCancellationEntry(entry) {
    let command
    try {
      command = decodeCancelCommand(entry.fields)
    } catch (error) {
      await this.deadLetter(entry, error)
      await this.acknowledgeCancellation(entry.id)
      return
    }
    this.rememberCancellation(command)
    await this.publishLog(createRunnerLog(command, this.config.runnerId, 'CANCELLATION_ACCEPTED', this.now()))
    await this.publishEvent(createEvent(command, this.config.runnerId, 'CANCELLED', command.reasonCode,
      'The Runner accepted the cancellation command without executing user content.', this.now()))
    await this.acknowledgeCancellation(entry.id)
  }

  async deadLetter(entry, error) {
    await this.commandClient.sendCommand([
      'XADD', DEAD_LETTER_STREAM, 'MAXLEN', '~', EVENT_MAX_LENGTH, '*',
      'sourceMessageId', entry.id,
      'reasonCode', error?.code ?? 'INVALID_TASK_CONTRACT',
      'receivedAt', this.now().toISOString(),
      'fieldNames', Object.keys(entry.fields).sort().join(','),
    ])
  }

  async publishEvent(event) {
    await this.commandClient.sendCommand(['XADD', EVENT_STREAM, 'MAXLEN', '~', EVENT_MAX_LENGTH, '*', ...toRedisFields(event)])
  }

  async publishLog(log) {
    await this.commandClient.sendCommand(['XADD', RUNNER_LOG_STREAM, 'MAXLEN', '~', EVENT_MAX_LENGTH, '*', ...toRedisFields(log)])
  }

  async publishHttpResult(result) {
    await this.commandClient.sendCommand(['XADD', HTTP_RESULT_STREAM, 'MAXLEN', '~', EVENT_MAX_LENGTH, '*', ...toRedisFields(result)])
  }

  async publishStepResult(result) {
    await this.commandClient.sendCommand(['XADD', STEP_RESULT_STREAM, 'MAXLEN', '~', EVENT_MAX_LENGTH, '*', ...toRedisFields(result)])
  }

  async publishScriptResult(result) {
    await this.commandClient.sendCommand(['XADD', SCRIPT_RESULT_STREAM, 'MAXLEN', '~', EVENT_MAX_LENGTH, '*', ...toRedisFields(result)])
  }

  rememberCancellation(command) {
    const now = this.now().getTime()
    for (const [key, expiresAt] of this.cancelledTasks) if (expiresAt <= now) this.cancelledTasks.delete(key)
    this.cancelledTasks.set(this.taskKey(command), now + CANCELLED_TASK_TTL_MILLISECONDS)
  }

  isCancelled(task) {
    const key = this.taskKey(task)
    const expiresAt = this.cancelledTasks.get(key)
    if (!expiresAt) return false
    if (expiresAt <= this.now().getTime()) {
      this.cancelledTasks.delete(key)
      return false
    }
    return true
  }

  taskKey(task) {
    return `${task.taskId}:${task.executionId}`
  }

  async acknowledgeTask(entryId) {
    await this.commandClient.sendCommand(['XACK', TASK_STREAM, TASK_CONSUMER_GROUP, entryId])
  }

  async acknowledgeCancellation(entryId) {
    await this.commandClient.sendCommand(['XACK', CANCEL_STREAM, CANCEL_CONSUMER_GROUP, entryId])
  }
}
