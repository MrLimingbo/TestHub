import { createHmac, randomBytes } from 'node:crypto'

export const PROTOCOL_VERSION = '1'
export const RUNNER_TYPE = 'jmeter'
export const TASK_CONTRACT = 'testhub.jmeter-runner.task'
export const CANCEL_CONTRACT = 'testhub.jmeter-runner.cancel'
export const EVENT_CONTRACT = 'testhub.jmeter-runner.event'
export const METRIC_CONTRACT = 'testhub.jmeter-runner.metric'
export const RESOURCE_CONTRACT = 'testhub.jmeter-runner.resource'
export const ERROR_CONTRACT = 'testhub.jmeter-runner.error'
export const TASK_STREAM = 'testhub:runner:jmeter:tasks:v1'
export const CANCEL_STREAM = 'testhub:runner:jmeter:cancellations:v1'
export const EVENT_STREAM = 'testhub:runner:jmeter:events:v1'
export const METRIC_STREAM = 'testhub:runner:jmeter:metrics:v1'
export const RESOURCE_STREAM = 'testhub:runner:jmeter:resources:v1'
export const ERROR_STREAM = 'testhub:runner:jmeter:errors:v1'
export const DEAD_LETTER_STREAM = 'testhub:runner:jmeter:dead-letter:v1'
export const HEARTBEAT_MEMBERS_KEY = 'testhub:runner:jmeter:members:v1'
export const HEARTBEAT_KEY_PREFIX = 'testhub:runner:jmeter:heartbeat:v1:'
export const TASK_CONSUMER_GROUP = 'testhub-jmeter-runner-v1'
export const CANCEL_CONSUMER_GROUP = 'testhub-jmeter-runner-cancellations-v1'
export const RUNTIME_CONFIGURATION_PATH = '/internal/performance-runner/v1/runtime-configuration'
export const ARTIFACT_UPLOAD_PATH = '/internal/performance-runner/v1/artifacts'

const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/
const TASK_FIELDS = ['contract', 'protocolVersion', 'taskId', 'executionId', 'organizationId', 'projectId', 'planId', 'planVersion', 'requestedAt', 'deadlineAt', 'manifestRef', 'traceId']
const CANCEL_FIELDS = ['contract', 'protocolVersion', 'commandId', 'taskId', 'executionId', 'requestedAt', 'reasonCode']
const EVENT_TYPES = new Set(['ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'DEFERRED'])
const CANCEL_REASONS = new Set(['USER_REQUESTED', 'EXECUTION_TIMEOUT'])
const RUNTIME_CONFIGURATION_CONTRACT = 'testhub.jmeter-runner.runtime-configuration'
const ARCHIVE_CONTENT_TYPES = Object.freeze({ JMETER_JTL: 'text/csv', JMETER_LOG: 'text/plain' })
const REQUEST_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'])
const RESTRICTED_HEADERS = new Set(['authorization', 'cookie', 'host', 'content-length', 'connection', 'transfer-encoding', 'proxy-authorization', 'proxy-connection', 'upgrade'])
const DATA_SET_VARIABLE = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/
const QUERY_PARAMETER_NAME = /^[A-Za-z0-9._~-]{1,128}$/
const VARIABLE_EXPRESSION = /\$\{([^}]*)}/g

function invalid(message) { const error = new Error(message); error.code = 'INVALID_TASK_CONTRACT'; return error }
function text(value, name) { if (typeof value !== 'string' || value.length === 0 || value.length > 4096) throw invalid(`${name} 缺失或过长`); return value }
function ulid(value, name) { if (!ULID.test(text(value, name))) throw invalid(`${name} 必须是 ULID`); return value }
function integer(value, name, maximum = 1_000_000) { if (!/^[1-9][0-9]*$/.test(text(value, name))) throw invalid(`${name} 必须为正整数`); const result = Number.parseInt(value, 10); if (!Number.isSafeInteger(result) || result > maximum) throw invalid(`${name} 超出范围`); return result }
function time(value, name) { const parsed = Date.parse(text(value, name)); if (!Number.isFinite(parsed)) throw invalid(`${name} 必须是 ISO-8601 时间`); return { text: value, value: parsed } }
function exact(fields, expected, label) { if (fields === null || typeof fields !== 'object' || Array.isArray(fields)) throw invalid(`${label}字段必须是对象`); const keys = Object.keys(fields).sort(); if (keys.length !== expected.length || keys.some((key, index) => key !== [...expected].sort()[index])) throw invalid(`${label}包含不受支持的字段`) }

export function decodeTask(fields) {
  exact(fields, TASK_FIELDS, '任务')
  if (fields.contract !== TASK_CONTRACT || fields.protocolVersion !== PROTOCOL_VERSION) throw invalid('JMeter 任务协议不匹配')
  const requestedAt = time(fields.requestedAt, 'requestedAt'); const deadlineAt = time(fields.deadlineAt, 'deadlineAt')
  if (deadlineAt.value <= requestedAt.value) throw invalid('deadlineAt 必须晚于 requestedAt')
  const manifestRef = text(fields.manifestRef, 'manifestRef')
  if (!/^performance-test\/manifests\/[0-9A-HJKMNP-TV-Z]{26}\/v1\.json$/.test(manifestRef)) throw invalid('manifestRef 不合法')
  return Object.freeze({ contract: TASK_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: ulid(fields.taskId, 'taskId'), executionId: ulid(fields.executionId, 'executionId'), organizationId: ulid(fields.organizationId, 'organizationId'), projectId: ulid(fields.projectId, 'projectId'), planId: ulid(fields.planId, 'planId'), planVersion: integer(fields.planVersion, 'planVersion'), requestedAt: requestedAt.text, deadlineAt: deadlineAt.text, manifestRef, traceId: text(fields.traceId, 'traceId') })
}

export function decodeCancelCommand(fields) {
  exact(fields, CANCEL_FIELDS, '取消指令')
  if (fields.contract !== CANCEL_CONTRACT || fields.protocolVersion !== PROTOCOL_VERSION) throw invalid('JMeter 取消指令协议不匹配')
  const reasonCode = text(fields.reasonCode, 'reasonCode'); if (!CANCEL_REASONS.has(reasonCode)) throw invalid('取消指令 reasonCode 不受支持')
  return Object.freeze({ contract: CANCEL_CONTRACT, protocolVersion: PROTOCOL_VERSION, commandId: ulid(fields.commandId, 'commandId'), taskId: ulid(fields.taskId, 'taskId'), executionId: ulid(fields.executionId, 'executionId'), requestedAt: time(fields.requestedAt, 'requestedAt').text, reasonCode })
}

export function createEvent(task, runnerId, type, code, message, now = new Date()) {
  if (!EVENT_TYPES.has(type) || !/^[A-Z0-9_]{1,80}$/.test(code)) throw invalid('JMeter Runner 事件超出协议范围')
  return Object.freeze({ contract: EVENT_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: task.taskId, executionId: task.executionId, runnerId, type, code, message, occurredAt: now.toISOString() })
}

export function createMetric(task, runnerId, metric, now = new Date()) {
  const count = (value, name) => {
    if (!Number.isSafeInteger(value) || value < 0 || value > 9_000_000_000_000_000) throw invalid(`${name} 不合法`)
    return value
  }
  const latency = (value, name) => {
    if (!Number.isSafeInteger(value) || value < 0 || value > 86_400_000) throw invalid(`${name} 不合法`)
    return value
  }
  const sampleCount = count(metric.sampleCount, 'sampleCount')
  const successCount = count(metric.successCount, 'successCount')
  const errorCount = count(metric.errorCount, 'errorCount')
  const activeThreads = latency(metric.activeThreads, 'activeThreads')
  const averageLatencyMillis = latency(metric.averageLatencyMillis, 'averageLatencyMillis')
  const p95LatencyMillis = latency(metric.p95LatencyMillis, 'p95LatencyMillis')
  const minLatencyMillis = latency(metric.minLatencyMillis, 'minLatencyMillis')
  const maxLatencyMillis = latency(metric.maxLatencyMillis, 'maxLatencyMillis')
  const throughput = Number(metric.throughputPerSecond)
  if (typeof metric.threadGroupName !== 'string' || metric.threadGroupName.length === 0 || metric.threadGroupName.length > 160
    || successCount + errorCount !== sampleCount || !Number.isFinite(throughput) || throughput < 0 || throughput > 1_000_000
    || minLatencyMillis > maxLatencyMillis || averageLatencyMillis > maxLatencyMillis || p95LatencyMillis > maxLatencyMillis) {
    throw invalid('JMeter 指标范围不合法')
  }
  return Object.freeze({ contract: METRIC_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: task.taskId,
    executionId: task.executionId, runnerId, threadGroupName: metric.threadGroupName, sampleCount, successCount,
    errorCount, activeThreads, throughputPerSecond: throughput.toFixed(4), averageLatencyMillis, p95LatencyMillis,
    minLatencyMillis, maxLatencyMillis, occurredAt: now.toISOString() })
}

export function createResource(task, runnerId, resource, now = new Date()) {
  const cpuPercent = Number(resource?.cpuPercent)
  const residentMemoryBytes = resource?.residentMemoryBytes
  if (!Number.isFinite(cpuPercent) || cpuPercent < 0 || cpuPercent > 10_000
    || !Number.isSafeInteger(residentMemoryBytes) || residentMemoryBytes < 0 || residentMemoryBytes > 1_099_511_627_776) {
    throw invalid('JMeter 运行资源指标范围不合法')
  }
  return Object.freeze({ contract: RESOURCE_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: task.taskId,
    executionId: task.executionId, runnerId, cpuPercent: cpuPercent.toFixed(4), residentMemoryBytes,
    occurredAt: now.toISOString() })
}

export function createError(task, runnerId, error, now = new Date()) {
  if (typeof error?.threadGroupName !== 'string' || error.threadGroupName.length === 0 || error.threadGroupName.length > 160
    || !/^(?:[0-9]{3}|NON_HTTP_ERROR)$/.test(error.responseCode) || !Number.isSafeInteger(error.errorCount)
    || error.errorCount < 1 || error.errorCount > 9_000_000_000_000_000) {
    throw invalid('JMeter 错误归因范围不合法')
  }
  return Object.freeze({ contract: ERROR_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: task.taskId,
    executionId: task.executionId, runnerId, threadGroupName: error.threadGroupName, responseCode: error.responseCode,
    errorCount: error.errorCount, occurredAt: now.toISOString() })
}

export function heartbeatKey(runnerId) { return `${HEARTBEAT_KEY_PREFIX}${runnerId}` }
export function heartbeatPayload(heartbeat) { return [heartbeat.runnerId, heartbeat.runnerType, heartbeat.protocolVersion, heartbeat.runnerVersion, String(heartbeat.executionEnabled), heartbeat.startedAt, heartbeat.heartbeatAt, String(heartbeat.maxConcurrentTasks), String(heartbeat.activeTaskCount)].join('\n') }
export function createHeartbeat(config, startedAt, now = new Date(), activeTaskCount = 0) {
  const maxConcurrentTasks = config.maxConcurrentTasks ?? 1
  if (!Number.isSafeInteger(maxConcurrentTasks) || maxConcurrentTasks < 1 || maxConcurrentTasks > 8
    || !Number.isSafeInteger(activeTaskCount) || activeTaskCount < 0 || activeTaskCount > maxConcurrentTasks) throw invalid('JMeter Runner 槽位状态不合法')
  const heartbeat = { runnerId: config.runnerId, runnerType: RUNNER_TYPE, protocolVersion: PROTOCOL_VERSION, runnerVersion: config.runnerVersion,
    executionEnabled: config.jmeterExecutionEnabled === true, startedAt: startedAt.toISOString(), heartbeatAt: now.toISOString(),
    maxConcurrentTasks, activeTaskCount }
  return Object.freeze({ ...heartbeat, trustVersion: '1', signature: createHmac('sha256', config.heartbeatSigningKey).update(heartbeatPayload(heartbeat), 'utf8').digest('hex') })
}
export function internalRequestSigningPayload({ method, path, runnerId, timestamp, nonce, bindings = [] }) {
  return ['testhub.performance-runner.internal-auth.v1', method, path, runnerId, timestamp, nonce, ...bindings].join('\n')
}
export function createRuntimeConfigurationRequest(config, task, now = new Date(), nonce = randomBytes(24).toString('base64url')) {
  const request = { method: 'POST', path: RUNTIME_CONFIGURATION_PATH, runnerId: config.runnerId, timestamp: now.toISOString(), nonce,
    bindings: [task.taskId, task.executionId] }
  const signature = createHmac('sha256', config.heartbeatSigningKey).update(internalRequestSigningPayload(request), 'utf8').digest('hex')
  return Object.freeze({ path: RUNTIME_CONFIGURATION_PATH, body: Object.freeze({ taskId: task.taskId, executionId: task.executionId }),
    headers: Object.freeze({ 'Content-Type': 'application/json', 'X-TestHub-Runner-Id': request.runnerId,
      'X-TestHub-Runner-Timestamp': request.timestamp, 'X-TestHub-Runner-Nonce': request.nonce,
      'X-TestHub-Runner-Signature': signature }) })
}
export function createArtifactUploadRequest(config, task, artifact, now = new Date(), nonce = randomBytes(24).toString('base64url')) {
  const contentType = ARCHIVE_CONTENT_TYPES[artifact?.artifactType]
  if (!contentType || !Number.isSafeInteger(artifact.sizeBytes) || artifact.sizeBytes < 0 || artifact.sizeBytes > 20 * 1024 * 1024
    || typeof artifact.checksum !== 'string' || !/^[0-9a-f]{64}$/.test(artifact.checksum)) throw invalid('JMeter 工件归档元数据不合法')
  const request = { method: 'POST', path: ARTIFACT_UPLOAD_PATH, runnerId: config.runnerId, timestamp: now.toISOString(), nonce,
    bindings: [task.taskId, task.executionId, artifact.artifactType, String(artifact.sizeBytes), artifact.checksum] }
  const signature = createHmac('sha256', config.heartbeatSigningKey).update(internalRequestSigningPayload(request), 'utf8').digest('hex')
  return Object.freeze({ path: ARTIFACT_UPLOAD_PATH, headers: Object.freeze({ 'Content-Type': contentType,
    'Content-Length': String(artifact.sizeBytes), 'X-TestHub-Runner-Id': request.runnerId,
    'X-TestHub-Runner-Timestamp': request.timestamp, 'X-TestHub-Runner-Nonce': request.nonce,
    'X-TestHub-Runner-Signature': signature, 'X-TestHub-Task-Id': task.taskId,
    'X-TestHub-Execution-Id': task.executionId, 'X-TestHub-Artifact-Type': artifact.artifactType,
    'X-TestHub-Artifact-Size': String(artifact.sizeBytes), 'X-TestHub-Artifact-SHA256': artifact.checksum }) })
}

function runtimeHeaders(value) {
  if (value === null || value === undefined) return Object.freeze({})
  if (typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length > 16) throw invalid('JMeter 请求头工件不合法')
  const headers = {}
  const names = new Set()
  for (const [name, headerValue] of Object.entries(value)) {
    if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(name)) throw invalid('JMeter 请求头名称不合法')
    const normalized = name.toLowerCase()
    if (names.has(normalized) || RESTRICTED_HEADERS.has(normalized) || typeof headerValue !== 'string'
      || headerValue.length > 1024 || /[\r\n]/.test(headerValue)) throw invalid('JMeter 请求头工件不合法')
    names.add(normalized); headers[name] = headerValue
  }
  return Object.freeze(headers)
}

function runtimeQueryParameters(value) {
  if (value === null || value === undefined) return Object.freeze({})
  if (typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length > 16) throw invalid('JMeter 查询参数工件不合法')
  const parameters = {}
  for (const [name, parameterValue] of Object.entries(value)) {
    if (!QUERY_PARAMETER_NAME.test(name) || typeof parameterValue !== 'string' || parameterValue.length > 1024
      || /[\r\n]/.test(parameterValue)) throw invalid('JMeter 查询参数工件不合法')
    parameters[name] = parameterValue
  }
  return Object.freeze(parameters)
}

function csvLine(source) {
  const cells = []; let value = ''; let quoted = false; let quoteClosed = false
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index]
    if (character === '"') {
      if (quoted && source[index + 1] === '"') { value += character; index += 1 } else if (quoted) { quoted = false; quoteClosed = true } else if (value.length === 0) quoted = true
      else throw invalid('JMeter CSV 数据集引号格式不合法')
    } else if (character === ',' && !quoted) { cells.push(value); value = ''; quoteClosed = false } else {
      if (quoteClosed) throw invalid('JMeter CSV 数据集引号格式不合法')
      value += character
    }
  }
  if (quoted) throw invalid('JMeter CSV 数据集存在未闭合引号')
  cells.push(value); return cells
}

function runtimeDataSet(value) {
  if (value === null || value === undefined) return Object.freeze({ csv: null, variables: new Set() })
  if (typeof value !== 'string' || value.length === 0 || value.length > 65536) throw invalid('JMeter CSV 数据集工件不合法')
  const csv = value.replaceAll('\r\n', '\n').replaceAll('\r', '\n')
  const rows = csv.split('\n').filter((line, index, all) => line.length > 0 || index < all.length - 1)
  if (rows.length < 2 || rows.length > 501) throw invalid('JMeter CSV 数据集行数不合法')
  const headers = csvLine(rows[0])
  if (headers.length < 1 || headers.length > 20) throw invalid('JMeter CSV 数据集列数不合法')
  const variables = new Set()
  for (const header of headers) {
    if (!DATA_SET_VARIABLE.test(header) || variables.has(header)) throw invalid('JMeter CSV 数据集变量不合法')
    variables.add(header)
  }
  for (const row of rows.slice(1)) {
    const cells = csvLine(row)
    if (cells.length !== headers.length || cells.some((cell) => cell.length > 4096)) throw invalid('JMeter CSV 数据集行不合法')
  }
  return Object.freeze({ csv, variables })
}

function templates(value, variables) {
  if (value === null || value === undefined || !value.includes('${')) return
  let count = 0
  for (const match of value.matchAll(VARIABLE_EXPRESSION)) {
    count += 1
    if (!DATA_SET_VARIABLE.test(match[1]) || !variables.has(match[1])) throw invalid('JMeter 请求变量未在 CSV 数据集中声明')
  }
  if (count === 0 || value.replaceAll(VARIABLE_EXPRESSION, '').includes('${')) throw invalid('JMeter 请求变量表达式不合法')
}

function runtimeRequest(group, variables) {
  const requestMethod = group.requestMethod ?? 'GET'
  const requestHeaders = runtimeHeaders(group.requestHeaders)
  const queryParameters = runtimeQueryParameters(group.queryParameters)
  const requestBody = group.requestBody ?? null
  const expectedStatusCode = group.expectedStatusCode ?? null
  const responseContains = group.responseContains ?? null
  if (!REQUEST_METHODS.has(requestMethod) || (requestBody !== null && (typeof requestBody !== 'string' || requestBody.length > 65536))
    || (requestBody && ['GET', 'HEAD'].includes(requestMethod)) || (expectedStatusCode !== null
      && (!Number.isSafeInteger(expectedStatusCode) || expectedStatusCode < 100 || expectedStatusCode > 599))
    || (responseContains !== null && (typeof responseContains !== 'string' || responseContains.length === 0 || responseContains.length > 1000))) {
    throw invalid('JMeter 请求或断言工件不合法')
  }
  Object.values(requestHeaders).forEach((value) => templates(value, variables))
  Object.values(queryParameters).forEach((value) => templates(value, variables))
  templates(requestBody, variables)
  templates(responseContains, variables)
  return Object.freeze({ requestMethod, requestHeaders, queryParameters, requestBody, expectedStatusCode, responseContains })
}

function rendezvousUsers(value, threadCount) {
  if (value === null || value === undefined) return null
  if (!Number.isSafeInteger(value) || value < 2 || value > threadCount) throw invalid('JMeter 集合点工件不合法')
  return value
}

function runtimeSteppedRamp(group) {
  if (group.loadModel !== 'STEPPED_RAMP') {
    if ((group.stepCount ?? null) !== null || (group.stepIntervalSeconds ?? null) !== null) {
      throw invalid('非阶梯 JMeter 线程组不能包含阶梯配置')
    }
    return Object.freeze({ stepCount: null, stepIntervalSeconds: null })
  }
  if (group.rampUpSeconds !== 0 || group.rendezvousUsers !== null && group.rendezvousUsers !== undefined
    || !Number.isSafeInteger(group.stepCount) || !Number.isSafeInteger(group.stepIntervalSeconds)
    || group.stepCount < 2 || group.stepCount > Math.min(group.threadCount, 20)
    || group.stepIntervalSeconds < 1 || group.stepIntervalSeconds > 86400
    || group.durationSeconds + (group.stepCount - 1) * group.stepIntervalSeconds > 86400) {
    throw invalid('JMeter 阶梯加压工件不合法')
  }
  return Object.freeze({ stepCount: group.stepCount, stepIntervalSeconds: group.stepIntervalSeconds })
}

function runtimeTarget(value, variables) {
  if (typeof value !== 'string') throw invalid('JMeter 目标 URL 不合法')
  templates(value, variables)
  const tokens = []
  const prepared = value.replace(/\$\{([A-Za-z_][A-Za-z0-9_]{0,63})}/g, (_, variable) => {
    const token = `__testhub_data_${tokens.length}__`
    tokens.push([token, `\${${variable}}`])
    return token
  })
  let target
  try { target = new URL(prepared) } catch { throw invalid('JMeter 目标 URL 不合法') }
  if (target.hostname.includes('__testhub_data_') || target.port.includes('__testhub_data_')) throw invalid('JMeter 目标 URL 不允许在主机或端口使用变量')
  const restored = tokens.reduce((result, [token, expression]) => result.replaceAll(token, expression), target.toString())
  return Object.freeze({ target, targetUrl: restored })
}

export function validateRuntimeConfiguration(task, artifact, allowedTargetOrigins) {
  if (artifact?.contract !== RUNTIME_CONFIGURATION_CONTRACT || ![1, 2, 3, 4, 5, 6].includes(artifact?.artifactVersion) || artifact?.taskId !== task.taskId
    || artifact?.executionId !== task.executionId || !Array.isArray(artifact.threadGroups) || artifact.threadGroups.length < 1
    || artifact.threadGroups.length > 10 || !Number.isFinite(Date.parse(artifact.deadlineAt))) {
    throw invalid('JMeter 运行时工件不兼容')
  }
  const groups = artifact.threadGroups.map((group) => {
    if (group === null || typeof group !== 'object' || typeof group.name !== 'string' || group.name.length === 0 || group.name.length > 160
      || !['RAMP_UP', 'STEADY', 'STEPPED_RAMP'].includes(group.loadModel) || !Number.isSafeInteger(group.threadCount)
      || !Number.isSafeInteger(group.rampUpSeconds) || !Number.isSafeInteger(group.durationSeconds)
      || group.threadCount < 1 || group.threadCount > 100000 || group.rampUpSeconds < 0 || group.rampUpSeconds > 86400
      || group.durationSeconds < 1 || group.durationSeconds > 86400) throw invalid('JMeter 线程组工件不合法')
    const steppedRamp = runtimeSteppedRamp(group)
    const dataSet = runtimeDataSet(group.dataSetCsv)
    const target = runtimeTarget(group.targetUrl, dataSet.variables)
    if (!['http:', 'https:'].includes(target.target.protocol) || target.target.username || target.target.password || !allowedTargetOrigins.includes(target.target.origin)) {
      throw invalid('JMeter 目标地址不在 Runner 网络白名单内')
    }
    if (group.targetTps !== null && group.targetTps !== undefined && (!Number.isSafeInteger(group.targetTps) || group.targetTps < 1 || group.targetTps > 1000000)) {
      throw invalid('JMeter 目标 TPS 不合法')
    }
    if (group.loadModel === 'STEPPED_RAMP' && group.targetTps !== null && group.targetTps !== undefined
      && group.targetTps < steppedRamp.stepCount) throw invalid('JMeter 阶梯加压目标 TPS 不能小于阶段数')
    const request = runtimeRequest(group, dataSet.variables)
    return Object.freeze({ name: group.name, targetUrl: target.targetUrl, loadModel: group.loadModel, threadCount: group.threadCount,
      rampUpSeconds: group.rampUpSeconds, stepCount: steppedRamp.stepCount, stepIntervalSeconds: steppedRamp.stepIntervalSeconds,
      durationSeconds: group.durationSeconds, targetTps: group.targetTps ?? null,
      dataSetCsv: dataSet.csv, rendezvousUsers: rendezvousUsers(group.rendezvousUsers, group.threadCount), ...request })
  })
  return Object.freeze({ ...artifact, deadlineAt: new Date(artifact.deadlineAt).toISOString(), threadGroups: groups })
}
export function streamEntries(reply) {
  const streams = Array.isArray(reply)
    ? reply
    : Object.entries(reply ?? {})
  return streams.flatMap((stream) => {
    if (!Array.isArray(stream) || stream.length !== 2 || !Array.isArray(stream[1])) return []
    return stream[1].map(([id, pairs]) => ({ id, fields: fieldsFromPairs(pairs) }))
  })
}
export function autoClaimEntries(reply) {
  if (!Array.isArray(reply) || !Array.isArray(reply[1])) return []
  return reply[1].map(([id, pairs]) => ({ id, fields: fieldsFromPairs(pairs) }))
}
export function fieldsFromPairs(pairs) { if (!Array.isArray(pairs) || pairs.length % 2 !== 0) throw invalid('Redis Stream 字段格式不正确'); const result = {}; for (let index = 0; index < pairs.length; index += 2) { const key = String(pairs[index]); if (Object.hasOwn(result, key)) throw invalid(`Redis Stream 包含重复字段：${key}`); result[key] = String(pairs[index + 1]) } return result }
export function toRedisFields(value) { return Object.entries(value).flatMap(([key, entry]) => [key, String(entry)]) }
