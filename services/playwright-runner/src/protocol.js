import { createHmac, randomBytes } from 'node:crypto'

export const PROTOCOL_VERSION = '1'
export const RUNNER_TYPE = 'playwright'
export const TASK_CONTRACT = 'testhub.playwright-runner.task'
export const CANCEL_CONTRACT = 'testhub.playwright-runner.cancel'
export const EVENT_CONTRACT = 'testhub.playwright-runner.event'
export const STEP_RESULT_CONTRACT = 'testhub.playwright-runner.step-result'
export const TASK_STREAM = 'testhub:runner:playwright:tasks:v1'
export const CANCEL_STREAM = 'testhub:runner:playwright:cancellations:v1'
export const EVENT_STREAM = 'testhub:runner:playwright:events:v1'
export const STEP_RESULT_STREAM = 'testhub:runner:playwright:step-results:v1'
export const HEARTBEAT_MEMBERS_KEY = 'testhub:runner:playwright:members:v1'
export const HEARTBEAT_KEY_PREFIX = 'testhub:runner:playwright:heartbeat:v1:'
export const TASK_CONSUMER_GROUP = 'testhub-playwright-runner-v1'
export const CANCEL_CONSUMER_GROUP = 'testhub-playwright-runner-cancellations-v1'
export const RUNTIME_CONFIGURATION_PATH = '/internal/ui-automation-runner/v1/runtime-configuration'
export const ARTIFACT_UPLOAD_PATH = '/internal/ui-automation-runner/v1/artifacts'

const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/
const TASK_FIELDS = ['contract', 'protocolVersion', 'taskId', 'executionId', 'organizationId', 'projectId', 'scenarioId', 'scenarioVersion', 'requestedAt', 'deadlineAt', 'manifestRef', 'traceId']
const CANCEL_FIELDS = ['contract', 'protocolVersion', 'commandId', 'taskId', 'executionId', 'requestedAt', 'reasonCode']
const EVENT_TYPES = new Set(['ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED'])
const STEP_TYPES = new Set(['NAVIGATE', 'FILL', 'CLICK', 'ASSERT_VISIBLE', 'ASSERT_TEXT', 'WAIT', 'SCREENSHOT'])
function invalid(message) { const error = new Error(message); error.code = 'INVALID_TASK_CONTRACT'; return error }
function exact(value, expected, name) { const keys = value && typeof value === 'object' && !Array.isArray(value) ? Object.keys(value).sort() : []; if (keys.length !== expected.length || keys.some((key, index) => key !== [...expected].sort()[index])) throw invalid(`${name} 字段不兼容`) }
function text(value, name, maximum = 4096) { if (typeof value !== 'string' || value.length === 0 || value.length > maximum) throw invalid(`${name} 缺失或过长`); return value }
function ulid(value, name) { if (!ULID.test(text(value, name))) throw invalid(`${name} 必须为 ULID`); return value }
function instant(value, name) { if (!Number.isFinite(Date.parse(text(value, name)))) throw invalid(`${name} 必须为 ISO-8601 时间`); return value }
export function decodeTask(fields) {
  exact(fields, TASK_FIELDS, '任务')
  if (fields.contract !== TASK_CONTRACT || fields.protocolVersion !== PROTOCOL_VERSION) throw invalid('Playwright 任务协议不匹配')
  const requestedAt = instant(fields.requestedAt, 'requestedAt'), deadlineAt = instant(fields.deadlineAt, 'deadlineAt')
  if (Date.parse(deadlineAt) <= Date.parse(requestedAt) || !/^ui-automation\/manifests\/[0-9A-HJKMNP-TV-Z]{26}\/v1\.json$/.test(text(fields.manifestRef, 'manifestRef'))) throw invalid('Playwright 任务时间或清单引用不合法')
  if (!/^[1-9][0-9]*$/.test(text(fields.scenarioVersion, 'scenarioVersion'))) throw invalid('scenarioVersion 不合法')
  return Object.freeze({ ...fields, taskId: ulid(fields.taskId, 'taskId'), executionId: ulid(fields.executionId, 'executionId'), organizationId: ulid(fields.organizationId, 'organizationId'), projectId: ulid(fields.projectId, 'projectId'), scenarioId: ulid(fields.scenarioId, 'scenarioId') })
}
export function decodeCancellation(fields) {
  exact(fields, CANCEL_FIELDS, '取消命令')
  if (fields.contract !== CANCEL_CONTRACT || fields.protocolVersion !== PROTOCOL_VERSION) throw invalid('Playwright 取消协议不匹配')
  if (!/^[A-Z0-9_]{1,80}$/.test(text(fields.reasonCode, 'reasonCode', 80))) throw invalid('reasonCode 不合法')
  return Object.freeze({ ...fields, commandId: ulid(fields.commandId, 'commandId'), taskId: ulid(fields.taskId, 'taskId'), executionId: ulid(fields.executionId, 'executionId'), requestedAt: instant(fields.requestedAt, 'requestedAt') })
}
export function createEvent(task, runnerId, type, code, message, now = new Date()) {
  if (!EVENT_TYPES.has(type) || !/^[A-Z0-9_]{1,80}$/.test(code)) throw invalid('Runner 事件超出协议范围')
  return Object.freeze({ contract: EVENT_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: task.taskId, executionId: task.executionId, runnerId, type, code, message: String(message).slice(0, 512), occurredAt: now.toISOString() })
}
export function createStepResult(task, runnerId, step, sequence, outcome, durationMillis, errorMessage = '', now = new Date()) {
  if (!STEP_TYPES.has(step.type) || !['PASSED', 'FAILED', 'SKIPPED'].includes(outcome) || !Number.isInteger(sequence) || sequence < 1 || sequence > 300 || !Number.isInteger(durationMillis) || durationMillis < 0 || durationMillis > 120000) throw invalid('步骤结果超出协议范围')
  return Object.freeze({ contract: STEP_RESULT_CONTRACT, protocolVersion: PROTOCOL_VERSION, taskId: task.taskId, executionId: task.executionId, runnerId, stepId: text(step.id, 'stepId', 64), stepSequence: String(sequence), stepType: step.type, stepName: text(step.name, 'stepName', 160), outcome, durationMillis: String(durationMillis), errorMessage: String(errorMessage).slice(0, 512), occurredAt: now.toISOString() })
}
export function heartbeatKey(id) { return `${HEARTBEAT_KEY_PREFIX}${id}` }
export function createHeartbeat(config, startedAt, activeTaskCount, now = new Date()) {
  const body = { runnerId: config.runnerId, runnerType: RUNNER_TYPE, protocolVersion: PROTOCOL_VERSION, runnerVersion: config.runnerVersion, executionEnabled: config.executionEnabled === true, startedAt: startedAt.toISOString(), heartbeatAt: now.toISOString(), maxConcurrentTasks: config.maxConcurrentTasks, activeTaskCount }
  const text = [body.runnerId, body.runnerType, body.protocolVersion, body.runnerVersion, String(body.executionEnabled), body.startedAt, body.heartbeatAt, String(body.maxConcurrentTasks), String(body.activeTaskCount)].join('\n')
  return Object.freeze({ ...body, trustVersion: '1', signature: createHmac('sha256', config.heartbeatSigningKey).update(text, 'utf8').digest('hex') })
}
function signature(config, path, bindings, now, nonce) { return createHmac('sha256', config.heartbeatSigningKey).update(['testhub.ui-automation-runner.internal-auth.v1', 'POST', path, config.runnerId, now.toISOString(), nonce, ...bindings].join('\n'), 'utf8').digest('hex') }
function headers(config, path, bindings, now = new Date(), nonce = randomBytes(24).toString('base64url')) { return { 'X-TestHub-Runner-Id': config.runnerId, 'X-TestHub-Runner-Timestamp': now.toISOString(), 'X-TestHub-Runner-Nonce': nonce, 'X-TestHub-Runner-Signature': signature(config, path, bindings, now, nonce) } }
export function runtimeRequest(config, task) { return { path: RUNTIME_CONFIGURATION_PATH, headers: { 'Content-Type': 'application/json', ...headers(config, RUNTIME_CONFIGURATION_PATH, [task.taskId, task.executionId]) }, body: JSON.stringify({ taskId: task.taskId, executionId: task.executionId }) } }
export function artifactRequest(config, task, artifact) {
  if (!['SCREENSHOT', 'TRACE', 'VIDEO'].includes(artifact.type) || !Number.isSafeInteger(artifact.size) || artifact.size < 0 || artifact.size > config.maxArtifactBytes || !/^[0-9a-f]{64}$/.test(artifact.checksum)) throw invalid('工件元数据不合法')
  const base = headers(config, ARTIFACT_UPLOAD_PATH, [task.taskId, task.executionId, artifact.type, String(artifact.size), artifact.checksum])
  return { path: ARTIFACT_UPLOAD_PATH, headers: { 'Content-Type': artifact.contentType, 'Content-Length': String(artifact.size), ...base, 'X-TestHub-Task-Id': task.taskId, 'X-TestHub-Execution-Id': task.executionId, 'X-TestHub-Artifact-Type': artifact.type, 'X-TestHub-Artifact-Size': String(artifact.size), 'X-TestHub-Artifact-SHA256': artifact.checksum } }
}
export function validateRuntime(task, value, allowedOrigins) {
  if (!value || value.contract !== 'testhub.playwright-runner.runtime-configuration' || value.version !== 1 || value.executionId !== task.executionId || value.taskId !== task.taskId || !value.browser || !value.scenario) throw invalid('运行时配置不兼容')
  const base = new URL(value.browser.baseUrl)
  if (!allowedOrigins.includes(base.origin) || !['chromium', 'firefox', 'webkit'].includes(value.browser.browser) || !Array.isArray(value.scenario.steps) || value.scenario.steps.length === 0 || value.scenario.steps.length > 300) throw invalid('运行时配置超出 Runner 安全边界')
  return Object.freeze(value)
}
