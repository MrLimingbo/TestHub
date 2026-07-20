import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import test from 'node:test'
import {
  EVENT_CONTRACT,
  HTTP_RESULT_CONTRACT,
  HTTP_RESULT_STREAM,
  SCRIPT_RESULT_CONTRACT,
  SCRIPT_RESULT_STREAM,
  STEP_RESULT_CONTRACT,
  STEP_RESULT_STREAM,
  CANCEL_CONTRACT,
  CAPABILITY_DISCOVERY_PATH,
  RUNTIME_LEASE_PATH,
  RUNTIME_LEASE_INSPECTION_PATH,
  RUNTIME_CONFIGURATION_PATH,
  HEARTBEAT_KEY_PREFIX,
  TASK_CONTRACT,
  createEvent,
  createHttpResult,
  createScriptResult,
  createStepResult,
  createRunnerLog,
  decodeCancelCommand,
  createHeartbeat,
  createCapabilityDiscoveryRequest,
  createRuntimeLeaseRequest,
  createRuntimeLeaseInspectionRequest,
  createRuntimeConfigurationRequest,
  decodeTask,
  fieldsFromPairs,
  heartbeatKey,
  signHeartbeat,
  signInternalRequest,
  runtimeLeaseFingerprint,
  streamEntries,
} from '../src/protocol.js'

const ids = {
  taskId: '01J00000000000000000000001', executionId: '01J00000000000000000000002',
  organizationId: '01J00000000000000000000003', projectId: '01J00000000000000000000004',
  scenarioId: '01J00000000000000000000005',
}

function task(overrides = {}) {
  return {
    contract: TASK_CONTRACT, protocolVersion: '1', ...ids, scenarioVersion: '2', attempt: '1',
    requestedAt: '2026-07-14T08:00:00.000Z', deadlineAt: '2026-07-14T08:05:00.000Z',
    manifestRef: 'api-automation/manifests/01J00000000000000000000005/v2.json', traceId: 'request-01J00000000000000000000006',
    ...overrides,
  }
}

test('decodes a bounded task that contains no credential-bearing fields', () => {
  const result = decodeTask(task())
  assert.equal(result.scenarioVersion, 2)
  assert.equal(result.attempt, 1)
  assert.equal(result.manifestRef, 'api-automation/manifests/01J00000000000000000000005/v2.json')
})

test('rejects additional secret or script fields before a task reaches an executor', () => {
  assert.throws(() => decodeTask(task({ authorization: 'Bearer should-not-be-here' })), /不得传递密钥、令牌或脚本内容/)
  assert.throws(() => decodeTask(task({ script: 'process.exit(1)' })), /不得传递密钥、令牌或脚本内容/)
})

test('decodes a fixed cancellation command without user-controlled content', () => {
  const command = decodeCancelCommand({
    contract: CANCEL_CONTRACT, protocolVersion: '1', commandId: '01J00000000000000000000006',
    taskId: ids.taskId, executionId: ids.executionId, requestedAt: '2026-07-14T08:00:01.000Z', reasonCode: 'USER_REQUESTED',
  })
  assert.equal(command.reasonCode, 'USER_REQUESTED')
  assert.throws(() => decodeCancelCommand({ ...command, message: 'not-permitted' }), /不受支持的字段/)
})

test('uses a stable heartbeat payload and event envelope', () => {
  const startedAt = new Date('2026-07-14T08:00:00.000Z')
  const heartbeat = createHeartbeat({ runnerId: 'api-runner-test', runnerVersion: '0.1.0-test' }, startedAt, new Date('2026-07-14T08:00:10.000Z'))
  assert.deepEqual(heartbeat, {
    runnerId: 'api-runner-test', runnerType: 'api', protocolVersion: '1', runnerVersion: '0.1.0-test',
    executionEnabled: false, startedAt: '2026-07-14T08:00:00.000Z', heartbeatAt: '2026-07-14T08:00:10.000Z',
  })
  assert.equal(heartbeatKey('api-runner-test'), `${HEARTBEAT_KEY_PREFIX}api-runner-test`)
  const event = createEvent(decodeTask(task()), 'api-runner-test', 'REJECTED', 'EXECUTION_DISABLED', 'disabled', startedAt)
  assert.equal(event.contract, EVENT_CONTRACT)
  assert.equal(event.executionId, ids.executionId)
  const runnerLog = createRunnerLog(decodeTask(task()), 'api-runner-test', 'TASK_RECEIVED', startedAt)
  assert.equal(runnerLog.message, 'Task accepted for safety precheck.')
})

test('creates a redacted HTTP result without URL, body or response content', () => {
  const result = createHttpResult(decodeTask(task()), 'api-runner-test', {
    stepId: 'request-order', method: 'GET', url: 'https://api.example.test/private', body: 'never-persist', contentType: 'text/plain',
  }, 1, 2, { status: 204, ok: true }, 36, new Date('2026-07-14T08:00:10.000Z'))
  assert.equal(result.contract, HTTP_RESULT_CONTRACT)
  assert.equal(result.stepId, 'request-order')
  assert.equal(result.attemptNumber, 2)
  assert.equal(result.statusCode, 204)
  assert.equal(result.durationMillis, 36)
  assert.equal(Object.hasOwn(result, 'url'), false)
  assert.equal(Object.hasOwn(result, 'body'), false)
  assert.equal(Object.hasOwn(result, 'response'), false)
  assert.equal(HTTP_RESULT_STREAM, 'testhub:runner:api:http-results:v2')
})

test('creates a fixed status-code assertion result without values or response content', () => {
  const result = createStepResult(decodeTask(task()), 'api-runner-test', { stepId: 'assert-order', type: 'ASSERT' },
    2, 'PASSED', 204, new Date('2026-07-14T08:00:10.000Z'))
  assert.equal(result.contract, STEP_RESULT_CONTRACT)
  assert.equal(result.observedStatusCode, 204)
  assert.equal(Object.hasOwn(result, 'expectedStatusCode'), false)
  assert.equal(Object.hasOwn(result, 'response'), false)
  assert.equal(STEP_RESULT_STREAM, 'testhub:runner:api:step-results:v1')
})

test('creates only aggregate script assertion counts without names, values or response content', () => {
  const result = createScriptResult(decodeTask(task()), 'api-runner-test', 'POST_RESPONSE',
    { testCount: 3, passedCount: 2, failedCount: 1 }, new Date('2026-07-14T08:00:10.000Z'))
  assert.equal(result.contract, SCRIPT_RESULT_CONTRACT)
  assert.deepEqual(result, {
    contract: SCRIPT_RESULT_CONTRACT, protocolVersion: '1', taskId: ids.taskId, executionId: ids.executionId,
    runnerId: 'api-runner-test', phase: 'POST_RESPONSE', testCount: 3, passedCount: 2, failedCount: 1,
    occurredAt: '2026-07-14T08:00:10.000Z',
  })
  assert.equal(SCRIPT_RESULT_STREAM, 'testhub:runner:api:script-results:v1')
  assert.throws(() => createScriptResult(decodeTask(task()), 'api-runner-test', 'POST_RESPONSE',
    { testCount: 3, passedCount: 3, failedCount: 1 }), /超出协议范围/)
})

test('signs only the bounded heartbeat fields when a runtime signing key is configured', () => {
  const signingKey = randomBytes(32).toString('hex')
  const heartbeat = createHeartbeat({ runnerId: 'api-runner-test', runnerVersion: '0.1.0-test', heartbeatSigningKey: signingKey },
    new Date('2026-07-14T08:00:00.000Z'), new Date('2026-07-14T08:00:10.000Z'))
  assert.equal(heartbeat.trustVersion, '1')
  assert.match(heartbeat.signature, /^[0-9a-f]{64}$/)
  const { signature, trustVersion, ...unsigned } = heartbeat
  assert.equal(signature, signHeartbeat(unsigned, signingKey))
  assert.equal(trustVersion, '1')
})

test('signs a fixed, nonce-bound capability discovery request without task or secret content', () => {
  const signingKey = randomBytes(32).toString('hex')
  const discovery = createCapabilityDiscoveryRequest({ runnerId: 'api-runner-test', heartbeatSigningKey: signingKey },
    new Date('2026-07-14T08:00:10.000Z'), 'nonce_0123456789abcdef')
  assert.equal(discovery.path, CAPABILITY_DISCOVERY_PATH)
  assert.equal(discovery.headers['X-TestHub-Runner-Id'], 'api-runner-test')
  assert.equal(discovery.headers['X-TestHub-Runner-Timestamp'], '2026-07-14T08:00:10.000Z')
  assert.equal(discovery.headers['X-TestHub-Runner-Nonce'], 'nonce_0123456789abcdef')
  assert.equal(discovery.headers['X-TestHub-Runner-Signature'], signInternalRequest({
    method: 'GET', path: CAPABILITY_DISCOVERY_PATH, runnerId: 'api-runner-test',
    timestamp: '2026-07-14T08:00:10.000Z', nonce: 'nonce_0123456789abcdef',
  }, signingKey))
})

test('binds a runtime lease request to exactly one task and execution without adding configuration content', () => {
  const signingKey = randomBytes(32).toString('hex')
  const lease = createRuntimeLeaseRequest({ runnerId: 'api-runner-test', heartbeatSigningKey: signingKey }, task(),
    new Date('2026-07-14T08:00:10.000Z'), 'nonce_runtime_0123456789')
  assert.equal(lease.path, RUNTIME_LEASE_PATH)
  assert.deepEqual(lease.body, { taskId: ids.taskId, executionId: ids.executionId })
  assert.equal(lease.headers['Content-Type'], 'application/json')
  assert.equal(lease.headers['X-TestHub-Runner-Signature'], signInternalRequest({
    method: 'POST', path: RUNTIME_LEASE_PATH, runnerId: 'api-runner-test', timestamp: '2026-07-14T08:00:10.000Z',
    nonce: 'nonce_runtime_0123456789', bindings: [ids.taskId, ids.executionId],
  }, signingKey))
})

test('binds lease inspection to a lease fingerprint while sending no variable or secret data', () => {
  const signingKey = randomBytes(32).toString('hex')
  const leaseValue = randomBytes(32).toString('base64url')
  const inspection = createRuntimeLeaseInspectionRequest({ runnerId: 'api-runner-test', heartbeatSigningKey: signingKey }, task(),
    leaseValue, new Date('2026-07-14T08:00:10.000Z'), 'nonce_inspect_012345678')
  const fingerprint = runtimeLeaseFingerprint(leaseValue)
  assert.equal(inspection.path, RUNTIME_LEASE_INSPECTION_PATH)
  assert.deepEqual(inspection.body, { taskId: ids.taskId, executionId: ids.executionId, lease: leaseValue })
  assert.equal(inspection.headers['X-TestHub-Runner-Signature'], signInternalRequest({
    method: 'POST', path: RUNTIME_LEASE_INSPECTION_PATH, runnerId: 'api-runner-test', timestamp: '2026-07-14T08:00:10.000Z',
    nonce: 'nonce_inspect_012345678', bindings: [ids.taskId, ids.executionId, fingerprint],
  }, signingKey))
  assert.throws(() => runtimeLeaseFingerprint('not-a-runtime-lease'), /格式不合法/)
})

test('binds a runtime configuration request to the task, execution and lease fingerprint', () => {
  const signingKey = randomBytes(32).toString('hex')
  const leaseValue = randomBytes(32).toString('base64url')
  const configuration = createRuntimeConfigurationRequest({ runnerId: 'api-runner-test', heartbeatSigningKey: signingKey }, task(),
    leaseValue, new Date('2026-07-14T08:00:10.000Z'), 'nonce_configuration_01234')
  const fingerprint = runtimeLeaseFingerprint(leaseValue)
  assert.equal(configuration.path, RUNTIME_CONFIGURATION_PATH)
  assert.deepEqual(configuration.body, { taskId: ids.taskId, executionId: ids.executionId, lease: leaseValue })
  assert.equal(configuration.headers['X-TestHub-Runner-Signature'], signInternalRequest({
    method: 'POST', path: RUNTIME_CONFIGURATION_PATH, runnerId: 'api-runner-test', timestamp: '2026-07-14T08:00:10.000Z',
    nonce: 'nonce_configuration_01234', bindings: [ids.taskId, ids.executionId, fingerprint],
  }, signingKey))
})

test('preserves stream entry boundaries and rejects duplicate fields', () => {
  const entries = streamEntries([['testhub:runner:api:tasks:v1', [['1710000000000-0', Object.entries(task()).flat()]]]])
  assert.equal(entries.length, 1)
  assert.equal(entries[0].id, '1710000000000-0')
  assert.equal(entries[0].fields.taskId, ids.taskId)
  const objectEntries = streamEntries({ 'testhub:runner:api:tasks:v1': [['1710000000000-1', Object.entries(task()).flat()]] })
  assert.equal(objectEntries.length, 1)
  assert.equal(objectEntries[0].id, '1710000000000-1')
  assert.throws(() => fieldsFromPairs(['taskId', 'first', 'taskId', 'second']), /重复字段/)
})
