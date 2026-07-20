import { createHash, createHmac, randomBytes } from 'node:crypto'

export const PROTOCOL_VERSION = '1'
export const HTTP_RESULT_PROTOCOL_VERSION = '2'
export const TASK_CONTRACT = 'testhub.api-runner.task'
export const CANCEL_CONTRACT = 'testhub.api-runner.cancel'
export const EVENT_CONTRACT = 'testhub.api-runner.event'
export const RUNNER_LOG_CONTRACT = 'testhub.api-runner.log'
export const HTTP_RESULT_CONTRACT = 'testhub.api-runner.http-result'
export const STEP_RESULT_CONTRACT = 'testhub.api-runner.step-result'
export const SCRIPT_RESULT_CONTRACT = 'testhub.api-runner.script-result'
export const RUNNER_TYPE = 'api'
export const TASK_STREAM = 'testhub:runner:api:tasks:v1'
export const CANCEL_STREAM = 'testhub:runner:api:cancellations:v1'
export const EVENT_STREAM = 'testhub:runner:api:events:v1'
export const RUNNER_LOG_STREAM = 'testhub:runner:api:logs:v1'
export const HTTP_RESULT_STREAM = 'testhub:runner:api:http-results:v2'
export const STEP_RESULT_STREAM = 'testhub:runner:api:step-results:v1'
export const SCRIPT_RESULT_STREAM = 'testhub:runner:api:script-results:v1'
export const DEAD_LETTER_STREAM = 'testhub:runner:api:dead-letter:v1'
export const HEARTBEAT_MEMBERS_KEY = 'testhub:runner:api:members:v1'
export const HEARTBEAT_KEY_PREFIX = 'testhub:runner:api:heartbeat:v1:'
export const TASK_CONSUMER_GROUP = 'testhub-api-runner-v1'
export const CANCEL_CONSUMER_GROUP = 'testhub-api-runner-cancellations-v1'
export const CAPABILITY_DISCOVERY_PATH = '/internal/api-runner/v1/capabilities'
export const RUNTIME_LEASE_PATH = '/internal/api-runner/v1/runtime-leases'
export const RUNTIME_LEASE_INSPECTION_PATH = '/internal/api-runner/v1/runtime-leases/inspect'
export const RUNTIME_CONFIGURATION_PATH = '/internal/api-runner/v1/runtime-configuration'

const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/
const TASK_FIELDS = [
  'contract', 'protocolVersion', 'taskId', 'executionId', 'organizationId', 'projectId', 'scenarioId',
  'scenarioVersion', 'attempt', 'requestedAt', 'deadlineAt', 'manifestRef', 'traceId',
]
const CANCEL_FIELDS = ['contract', 'protocolVersion', 'commandId', 'taskId', 'executionId', 'requestedAt', 'reasonCode']
const EVENT_TYPES = new Set(['ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'])
const HTTP_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'])
const STEP_RESULT_TYPES = new Set(['EXTRACT', 'ASSERT'])
const SCRIPT_PHASES = new Set(['PRE_REQUEST', 'POST_RESPONSE'])
const CANCEL_REASONS = new Set(['USER_REQUESTED', 'EXECUTION_TIMEOUT'])
const LOG_DEFINITIONS = Object.freeze({
  TASK_RECEIVED: Object.freeze({ level: 'INFO', message: 'Task accepted for safety precheck.' }),
  PRECHECK_WAITING: Object.freeze({ level: 'INFO', message: 'Waiting for the safety precheck window to close.' }),
  CANCELLATION_ACCEPTED: Object.freeze({ level: 'INFO', message: 'Cancellation command accepted without executing user content.' }),
  TASK_CANCELLED: Object.freeze({ level: 'INFO', message: 'Task acknowledgement stopped because cancellation is active.' }),
  EXECUTION_DISABLED: Object.freeze({ level: 'WARN', message: 'Execution engine remains disabled; user content was not run.' }),
  RUNTIME_ARTIFACT_RECEIVED: Object.freeze({ level: 'INFO', message: 'A bounded HTTP runtime artifact was received.' }),
  HTTP_EXECUTION_STARTED: Object.freeze({ level: 'INFO', message: 'Bounded HTTP request execution started.' }),
  PARALLEL_REQUESTS_STARTED: Object.freeze({ level: 'INFO', message: 'A bounded parallel request group started without shared scripts or variables.' }),
  PARALLEL_REQUESTS_COMPLETED: Object.freeze({ level: 'INFO', message: 'A bounded parallel request group completed without exposing request content.' }),
  WAIT_COMPLETED: Object.freeze({ level: 'INFO', message: 'A bounded wait step completed without executing user code.' }),
  CONDITION_PASSED: Object.freeze({ level: 'INFO', message: 'A fixed status-code condition allowed its next request.' }),
  CONDITION_SKIPPED: Object.freeze({ level: 'INFO', message: 'A fixed status-code condition skipped its next request.' }),
  LOOP_COMPLETED: Object.freeze({ level: 'INFO', message: 'A bounded request loop completed without dynamic code.' }),
  HTTP_RETRY_SCHEDULED: Object.freeze({ level: 'INFO', message: 'A bounded retry was scheduled for a transient safe-method HTTP outcome.' }),
  FAILURE_CONTINUED: Object.freeze({ level: 'WARN', message: 'The configured continue strategy allowed later bounded steps to run after a failure.' }),
  PRE_REQUEST_SCRIPT_COMPLETED: Object.freeze({ level: 'INFO', message: 'The isolated pre-request script completed without exposing variables.' }),
  POST_RESPONSE_SCRIPT_COMPLETED: Object.freeze({ level: 'INFO', message: 'The isolated post-response script completed without exposing variables or response content.' }),
  PRE_REQUEST_SCRIPT_FAILED: Object.freeze({ level: 'WARN', message: 'The isolated pre-request script failed; no script output was persisted.' }),
  POST_RESPONSE_SCRIPT_FAILED: Object.freeze({ level: 'WARN', message: 'The isolated post-response script failed; no script output was persisted.' }),
  HTTP_EXECUTION_SUCCEEDED: Object.freeze({ level: 'INFO', message: 'Bounded HTTP request execution completed.' }),
  HTTP_EXECUTION_FAILED: Object.freeze({ level: 'WARN', message: 'Bounded HTTP request execution failed without persisting request or response content.' }),
})

function protocolError(message) {
  const error = new Error(message)
  error.code = 'INVALID_TASK_CONTRACT'
  return error
}

function field(value, name) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 4096) throw protocolError(`${name} 缺失或过长`)
  return value
}

function ulid(value, name) {
  if (!ULID.test(field(value, name))) throw protocolError(`${name} 必须是 ULID`)
  return value
}

function positiveInteger(value, name, maximum = 1_000_000) {
  if (!/^[1-9][0-9]*$/.test(field(value, name))) throw protocolError(`${name} 必须为正整数`)
  const result = Number.parseInt(value, 10)
  if (!Number.isSafeInteger(result) || result > maximum) throw protocolError(`${name} 超出范围`)
  return result
}

function instant(value, name) {
  const parsed = Date.parse(field(value, name))
  if (!Number.isFinite(parsed)) throw protocolError(`${name} 必须是 ISO-8601 时间`)
  return { text: value, timestamp: parsed }
}

function checkFields(fields) {
  if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw protocolError('任务字段必须是对象')
  const keys = Object.keys(fields).sort()
  if (keys.length !== TASK_FIELDS.length || keys.some((key, index) => key !== [...TASK_FIELDS].sort()[index])) {
    throw protocolError('任务包含不受支持的字段；队列中不得传递密钥、令牌或脚本内容')
  }
}

export function decodeTask(fields) {
  checkFields(fields)
  if (fields.contract !== TASK_CONTRACT) throw protocolError('contract 不匹配')
  if (fields.protocolVersion !== PROTOCOL_VERSION) throw protocolError('protocolVersion 不受支持')
  const requestedAt = instant(fields.requestedAt, 'requestedAt')
  const deadlineAt = instant(fields.deadlineAt, 'deadlineAt')
  if (deadlineAt.timestamp <= requestedAt.timestamp) throw protocolError('deadlineAt 必须晚于 requestedAt')
  const manifestRef = field(fields.manifestRef, 'manifestRef')
  if (!/^api-automation\/manifests\/[0-9A-HJKMNP-TV-Z]{26}\/v[1-9][0-9]*\.json$/.test(manifestRef)) {
    throw protocolError('manifestRef 不合法')
  }
  return Object.freeze({
    contract: TASK_CONTRACT,
    protocolVersion: PROTOCOL_VERSION,
    taskId: ulid(fields.taskId, 'taskId'),
    executionId: ulid(fields.executionId, 'executionId'),
    organizationId: ulid(fields.organizationId, 'organizationId'),
    projectId: ulid(fields.projectId, 'projectId'),
    scenarioId: ulid(fields.scenarioId, 'scenarioId'),
    scenarioVersion: positiveInteger(fields.scenarioVersion, 'scenarioVersion'),
    attempt: positiveInteger(fields.attempt, 'attempt', 100),
    requestedAt: requestedAt.text,
    deadlineAt: deadlineAt.text,
    manifestRef,
    traceId: field(fields.traceId, 'traceId'),
  })
}

export function decodeCancelCommand(fields) {
  if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw protocolError('取消指令字段必须是对象')
  const keys = Object.keys(fields).sort()
  if (keys.length !== CANCEL_FIELDS.length || keys.some((key, index) => key !== [...CANCEL_FIELDS].sort()[index])) {
    throw protocolError('取消指令包含不受支持的字段')
  }
  if (fields.contract !== CANCEL_CONTRACT) throw protocolError('取消指令 contract 不匹配')
  if (fields.protocolVersion !== PROTOCOL_VERSION) throw protocolError('取消指令 protocolVersion 不受支持')
  const requestedAt = instant(fields.requestedAt, 'requestedAt')
  const reasonCode = field(fields.reasonCode, 'reasonCode')
  if (!CANCEL_REASONS.has(reasonCode)) throw protocolError('取消指令 reasonCode 不受支持')
  return Object.freeze({
    contract: CANCEL_CONTRACT,
    protocolVersion: PROTOCOL_VERSION,
    commandId: ulid(fields.commandId, 'commandId'),
    taskId: ulid(fields.taskId, 'taskId'),
    executionId: ulid(fields.executionId, 'executionId'),
    requestedAt: requestedAt.text,
    reasonCode,
  })
}

export function heartbeatKey(runnerId) {
  return `${HEARTBEAT_KEY_PREFIX}${runnerId}`
}

export function heartbeatSigningPayload(heartbeat) {
  return [heartbeat.runnerId, heartbeat.runnerType, heartbeat.protocolVersion, heartbeat.runnerVersion,
    String(heartbeat.executionEnabled), heartbeat.startedAt, heartbeat.heartbeatAt].join('\n')
}

export function signHeartbeat(heartbeat, signingKey) {
  return createHmac('sha256', signingKey).update(heartbeatSigningPayload(heartbeat), 'utf8').digest('hex')
}

export function internalRequestSigningPayload({ method, path, runnerId, timestamp, nonce, bindings = [] }) {
  return ['testhub.api-runner.internal-auth.v1', method, path, runnerId, timestamp, nonce, ...bindings].join('\n')
}

export function signInternalRequest(request, signingKey) {
  return createHmac('sha256', signingKey).update(internalRequestSigningPayload(request), 'utf8').digest('hex')
}

export function createCapabilityDiscoveryRequest(config, now = new Date(), nonce = randomBytes(24).toString('base64url')) {
  const request = {
    method: 'GET', path: CAPABILITY_DISCOVERY_PATH, runnerId: config.runnerId, timestamp: now.toISOString(), nonce,
  }
  return Object.freeze({
    path: CAPABILITY_DISCOVERY_PATH,
    headers: Object.freeze({
      'X-TestHub-Runner-Id': request.runnerId,
      'X-TestHub-Runner-Timestamp': request.timestamp,
      'X-TestHub-Runner-Nonce': request.nonce,
      'X-TestHub-Runner-Signature': signInternalRequest(request, config.heartbeatSigningKey),
    }),
  })
}

export function createRuntimeLeaseRequest(config, task, now = new Date(), nonce = randomBytes(24).toString('base64url')) {
  const request = {
    method: 'POST', path: RUNTIME_LEASE_PATH, runnerId: config.runnerId, timestamp: now.toISOString(), nonce,
    bindings: [task.taskId, task.executionId],
  }
  return Object.freeze({
    path: RUNTIME_LEASE_PATH,
    body: Object.freeze({ taskId: task.taskId, executionId: task.executionId }),
    headers: Object.freeze({
      'Content-Type': 'application/json',
      'X-TestHub-Runner-Id': request.runnerId,
      'X-TestHub-Runner-Timestamp': request.timestamp,
      'X-TestHub-Runner-Nonce': request.nonce,
      'X-TestHub-Runner-Signature': signInternalRequest(request, config.heartbeatSigningKey),
    }),
  })
}

export function runtimeLeaseFingerprint(lease) {
  if (typeof lease !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(lease)) throw new Error('运行时租约格式不合法')
  return createHash('sha256').update(lease, 'utf8').digest('hex')
}

export function createRuntimeLeaseInspectionRequest(config, task, lease, now = new Date(), nonce = randomBytes(24).toString('base64url')) {
  const fingerprint = runtimeLeaseFingerprint(lease)
  const request = {
    method: 'POST', path: RUNTIME_LEASE_INSPECTION_PATH, runnerId: config.runnerId, timestamp: now.toISOString(), nonce,
    bindings: [task.taskId, task.executionId, fingerprint],
  }
  return Object.freeze({
    path: RUNTIME_LEASE_INSPECTION_PATH,
    body: Object.freeze({ taskId: task.taskId, executionId: task.executionId, lease }),
    headers: Object.freeze({
      'Content-Type': 'application/json',
      'X-TestHub-Runner-Id': request.runnerId,
      'X-TestHub-Runner-Timestamp': request.timestamp,
      'X-TestHub-Runner-Nonce': request.nonce,
      'X-TestHub-Runner-Signature': signInternalRequest(request, config.heartbeatSigningKey),
    }),
  })
}

export function createRuntimeConfigurationRequest(config, task, lease, now = new Date(), nonce = randomBytes(24).toString('base64url')) {
  const fingerprint = runtimeLeaseFingerprint(lease)
  const request = {
    method: 'POST', path: RUNTIME_CONFIGURATION_PATH, runnerId: config.runnerId, timestamp: now.toISOString(), nonce,
    bindings: [task.taskId, task.executionId, fingerprint],
  }
  return Object.freeze({
    path: RUNTIME_CONFIGURATION_PATH,
    body: Object.freeze({ taskId: task.taskId, executionId: task.executionId, lease }),
    headers: Object.freeze({
      'Content-Type': 'application/json',
      'X-TestHub-Runner-Id': request.runnerId,
      'X-TestHub-Runner-Timestamp': request.timestamp,
      'X-TestHub-Runner-Nonce': request.nonce,
      'X-TestHub-Runner-Signature': signInternalRequest(request, config.heartbeatSigningKey),
    }),
  })
}

export function createHeartbeat(config, startedAt, now = new Date()) {
  const heartbeat = {
    runnerId: config.runnerId,
    runnerType: RUNNER_TYPE,
    protocolVersion: PROTOCOL_VERSION,
    runnerVersion: config.runnerVersion,
    executionEnabled: config.httpExecutionEnabled === true,
    startedAt: startedAt.toISOString(),
    heartbeatAt: now.toISOString(),
  }
  if (config.heartbeatSigningKey) {
    return Object.freeze({ ...heartbeat, trustVersion: '1', signature: signHeartbeat(heartbeat, config.heartbeatSigningKey) })
  }
  return Object.freeze(heartbeat)
}

export function createEvent(task, runnerId, type, code, message, now = new Date()) {
  if (!EVENT_TYPES.has(type)) throw new Error(`不支持的 Runner 事件类型：${type}`)
  return Object.freeze({
    contract: EVENT_CONTRACT,
    protocolVersion: PROTOCOL_VERSION,
    taskId: task.taskId,
    executionId: task.executionId,
    runnerId,
    type,
    code,
    message,
    occurredAt: now.toISOString(),
  })
}

export function createRunnerLog(subject, runnerId, code, now = new Date()) {
  const definition = LOG_DEFINITIONS[code]
  if (!definition) throw new Error(`不支持的 Runner 日志代码：${code}`)
  return Object.freeze({
    contract: RUNNER_LOG_CONTRACT,
    protocolVersion: PROTOCOL_VERSION,
    taskId: subject.taskId,
    executionId: subject.executionId,
    runnerId,
    level: definition.level,
    code,
    message: definition.message,
    occurredAt: now.toISOString(),
  })
}

export function createHttpResult(task, runnerId, request, requestSequence, attemptNumber, result, durationMillis, now = new Date()) {
  if (!HTTP_METHODS.has(request?.method) || typeof request?.stepId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(request.stepId)) {
    throw new Error('HTTP 执行结果缺少受限步骤元数据')
  }
  if (!Number.isInteger(requestSequence) || requestSequence < 1 || requestSequence > 300
    || !Number.isInteger(attemptNumber) || attemptNumber < 1 || attemptNumber > 3
    || !Number.isInteger(durationMillis) || durationMillis < 0 || durationMillis > 20_000) {
    throw new Error('HTTP 执行结果超出协议范围')
  }
  const statusCode = Number.isInteger(result?.status) && result.status >= 0 && result.status <= 599 ? result.status : 0
  const outcome = result?.ok === true ? 'SUCCEEDED' : 'FAILED'
  if ((outcome === 'SUCCEEDED' && (statusCode < 200 || statusCode >= 300))
    || (outcome === 'FAILED' && statusCode >= 200 && statusCode < 300)) {
    throw new Error('HTTP 执行结果状态不一致')
  }
  return Object.freeze({
    contract: HTTP_RESULT_CONTRACT,
    protocolVersion: HTTP_RESULT_PROTOCOL_VERSION,
    taskId: task.taskId,
    executionId: task.executionId,
    runnerId,
    stepId: request.stepId,
    requestSequence,
    attemptNumber,
    method: request.method,
    outcome,
    statusCode,
    durationMillis,
    occurredAt: now.toISOString(),
  })
}

export function createStepResult(task, runnerId, step, stepSequence, outcome, observedStatusCode, now = new Date()) {
  if (!STEP_RESULT_TYPES.has(step?.type) || typeof step?.stepId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(step.stepId)
    || !Number.isInteger(stepSequence) || stepSequence < 1 || stepSequence > 300
    || !Number.isInteger(observedStatusCode) || observedStatusCode < 100 || observedStatusCode > 599) {
    throw new Error('步骤执行结果缺少受限步骤元数据')
  }
  const outcomes = step.type === 'ASSERT' ? new Set(['PASSED', 'FAILED']) : new Set(['COMPLETED'])
  if (!outcomes.has(outcome)) throw new Error('步骤执行结果状态不一致')
  return Object.freeze({
    contract: STEP_RESULT_CONTRACT,
    protocolVersion: PROTOCOL_VERSION,
    taskId: task.taskId,
    executionId: task.executionId,
    runnerId,
    stepId: step.stepId,
    stepSequence,
    type: step.type,
    outcome,
    observedStatusCode,
    occurredAt: now.toISOString(),
  })
}

export function createScriptResult(task, runnerId, phase, summary, now = new Date()) {
  if (!SCRIPT_PHASES.has(phase) || !Number.isInteger(summary?.testCount) || !Number.isInteger(summary?.passedCount)
    || !Number.isInteger(summary?.failedCount) || summary.testCount < 1 || summary.testCount > 100
    || summary.passedCount < 0 || summary.failedCount < 0 || summary.passedCount + summary.failedCount !== summary.testCount) {
    throw new Error('脚本断言结果超出协议范围')
  }
  return Object.freeze({
    contract: SCRIPT_RESULT_CONTRACT,
    protocolVersion: PROTOCOL_VERSION,
    taskId: task.taskId,
    executionId: task.executionId,
    runnerId,
    phase,
    testCount: summary.testCount,
    passedCount: summary.passedCount,
    failedCount: summary.failedCount,
    occurredAt: now.toISOString(),
  })
}

export function toRedisFields(record) {
  return Object.entries(record).flatMap(([key, value]) => [key, String(value)])
}

export function fieldsFromPairs(pairs) {
  if (!Array.isArray(pairs) || pairs.length % 2 !== 0) throw protocolError('Redis Stream 字段格式不正确')
  const result = {}
  for (let index = 0; index < pairs.length; index += 2) {
    const key = String(pairs[index])
    if (Object.hasOwn(result, key)) throw protocolError(`Redis Stream 包含重复字段：${key}`)
    result[key] = String(pairs[index + 1])
  }
  return result
}

function entriesFromMessages(messages) {
  if (!Array.isArray(messages)) return []
  return messages.flatMap((entry) => Array.isArray(entry) && entry.length === 2
    ? [{ id: String(entry[0]), fields: fieldsFromPairs(entry[1]) }] : [])
}

function entriesFromStream(stream) {
  if (Array.isArray(stream)) {
    if (stream.length === 2 && typeof stream[0] === 'string') return entriesFromMessages(stream[1])
    return entriesFromMessages(stream)
  }
  if (stream && typeof stream === 'object') {
    return stream.messages?.map((entry) => ({
      id: String(entry.id), fields: Object.fromEntries(Object.entries(entry.message).map(([key, value]) => [key, String(value)])),
    })) ?? []
  }
  return []
}

export function streamEntries(reply) {
  if (!reply) return []
  if (Array.isArray(reply)) return reply.flatMap(entriesFromStream)
  if (typeof reply === 'object') return Object.values(reply).flatMap(entriesFromStream)
  return []
}
