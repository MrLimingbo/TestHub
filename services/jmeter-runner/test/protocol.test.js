import assert from 'node:assert/strict'
import test from 'node:test'
import { ARTIFACT_UPLOAD_PATH, CANCEL_CONTRACT, ERROR_CONTRACT, EVENT_CONTRACT, HEARTBEAT_KEY_PREFIX, RESOURCE_CONTRACT, TASK_CONTRACT, autoClaimEntries, createArtifactUploadRequest,
  createError, createEvent, createHeartbeat, createMetric, createResource, decodeCancelCommand, decodeTask, heartbeatKey, streamEntries, validateRuntimeConfiguration } from '../src/protocol.js'

const ids = { taskId: '01J00000000000000000000001', executionId: '01J00000000000000000000002',
  organizationId: '01J00000000000000000000003', projectId: '01J00000000000000000000004', planId: '01J00000000000000000000005' }
const task = (overrides = {}) => ({ contract: TASK_CONTRACT, protocolVersion: '1', ...ids, planVersion: '2',
  requestedAt: '2026-07-19T08:00:00.000Z', deadlineAt: '2026-07-19T08:05:00.000Z',
  manifestRef: 'performance-test/manifests/01J00000000000000000000002/v1.json', traceId: 'performance-01J00000000000000000000002', ...overrides })

test('accepts a bounded JMeter task that excludes target and credential values', () => {
  const decoded = decodeTask(task())
  assert.equal(decoded.planVersion, 2)
  assert.equal(Object.hasOwn(decoded, 'targetUrl'), false)
  assert.throws(() => decodeTask(task({ targetUrl: 'https://never-pass-through-queue.test' })), /不受支持的字段/)
})

test('uses signed JMeter heartbeats and fixed cancellation envelopes', () => {
  const heartbeat = createHeartbeat({ runnerId: 'jmeter-runner-test', runnerVersion: '0.1.0-test',
    heartbeatSigningKey: 'a'.repeat(32), jmeterExecutionEnabled: false }, new Date('2026-07-19T08:00:00.000Z'),
  new Date('2026-07-19T08:00:10.000Z'))
  assert.equal(heartbeat.runnerType, 'jmeter')
  assert.equal(heartbeat.maxConcurrentTasks, 1)
  assert.equal(heartbeat.activeTaskCount, 0)
  assert.match(heartbeat.signature, /^[0-9a-f]{64}$/)
  assert.equal(heartbeatKey('jmeter-runner-test'), `${HEARTBEAT_KEY_PREFIX}jmeter-runner-test`)
  const command = decodeCancelCommand({ contract: CANCEL_CONTRACT, protocolVersion: '1', commandId: '01J00000000000000000000006',
    taskId: ids.taskId, executionId: ids.executionId, requestedAt: '2026-07-19T08:00:01.000Z', reasonCode: 'USER_REQUESTED' })
  assert.equal(command.reasonCode, 'USER_REQUESTED')
  const event = createEvent(decodedTask(), 'jmeter-runner-test', 'REJECTED', 'JMETER_EXECUTION_DISABLED', 'disabled')
  assert.equal(event.contract, EVENT_CONTRACT)
})

test('decodes both raw Redis Stream reply shapes', () => {
  const fields = Object.entries(task()).flat()
  const arrayReply = [['testhub:runner:jmeter:tasks:v1', [['1710000000000-0', fields]]]]
  const objectReply = { 'testhub:runner:jmeter:tasks:v1': [['1710000000000-0', fields]] }
  assert.deepEqual(streamEntries(objectReply), streamEntries(arrayReply))
  assert.equal(streamEntries(objectReply)[0].fields.taskId, ids.taskId)
  assert.equal(autoClaimEntries(['0-0', [['1710000000000-1', fields]]])[0].fields.executionId, ids.executionId)
})

test('validates runtime artifacts against the Runner target-origin allow list', () => {
  const artifact = { contract: 'testhub.jmeter-runner.runtime-configuration', artifactVersion: 5,
    executionId: ids.executionId, taskId: ids.taskId, deadlineAt: '2026-07-19T08:05:00.000Z',
    threadGroups: [{ name: '订单', targetUrl: 'http://127.0.0.1:18081/orders/${orderId}', loadModel: 'RAMP_UP',
      threadCount: 2, rampUpSeconds: 1, durationSeconds: 3, targetTps: null, requestMethod: 'POST',
      requestHeaders: { 'Content-Type': 'application/json', 'X-Trace': 'runtime-test' }, requestBody: '{"run":true}',
      queryParameters: { order: '${orderId}', page: '1' }, expectedStatusCode: 200, responseContains: '${orderId}', dataSetCsv: 'orderId,traceId\nA-100,runtime-test', rendezvousUsers: 2 }] }
  const validated = validateRuntimeConfiguration(decodedTask(), artifact, ['http://127.0.0.1:18081'])
  assert.equal(validated.threadGroups[0].threadCount, 2)
  assert.equal(validated.threadGroups[0].requestMethod, 'POST')
  assert.equal(validated.threadGroups[0].requestHeaders['X-Trace'], 'runtime-test')
  assert.equal(validated.threadGroups[0].queryParameters.order, '${orderId}')
  assert.equal(validated.threadGroups[0].expectedStatusCode, 200)
  assert.equal(validated.threadGroups[0].dataSetCsv, 'orderId,traceId\nA-100,runtime-test')
  assert.equal(validated.threadGroups[0].rendezvousUsers, 2)
  assert.throws(() => validateRuntimeConfiguration(decodedTask(), artifact, ['https://approved.example']), /白名单/)
  assert.throws(() => validateRuntimeConfiguration(decodedTask(), { ...artifact, artifactVersion: 2,
    threadGroups: [{ ...artifact.threadGroups[0], requestHeaders: { Authorization: 'Bearer not-allowed' } }] }, ['http://127.0.0.1:18081']), /请求头/)
  assert.throws(() => validateRuntimeConfiguration(decodedTask(), { ...artifact,
    threadGroups: [{ ...artifact.threadGroups[0], rendezvousUsers: 3 }] }, ['http://127.0.0.1:18081']), /集合点/)
  assert.throws(() => validateRuntimeConfiguration(decodedTask(), { ...artifact,
    threadGroups: [{ ...artifact.threadGroups[0], queryParameters: { order: '${unknown}' } }] }, ['http://127.0.0.1:18081']), /变量/)
  const metric = createMetric(decodedTask(), 'jmeter-runner-test', { threadGroupName: '__TOTAL__', sampleCount: 2,
    successCount: 2, errorCount: 0, activeThreads: 1, throughputPerSecond: 1.25, averageLatencyMillis: 20,
    p95LatencyMillis: 30, minLatencyMillis: 10, maxLatencyMillis: 30 })
  assert.equal(metric.throughputPerSecond, '1.2500')
  const resource = createResource(decodedTask(), 'jmeter-runner-test', { cpuPercent: 12.5, residentMemoryBytes: 4_194_304 })
  assert.equal(resource.contract, RESOURCE_CONTRACT)
  assert.equal(resource.cpuPercent, '12.5000')
  assert.throws(() => createResource(decodedTask(), 'jmeter-runner-test', { cpuPercent: -1, residentMemoryBytes: 0 }), /资源指标/)
  const error = createError(decodedTask(), 'jmeter-runner-test', { threadGroupName: '订单', responseCode: '503', errorCount: 2 })
  assert.equal(error.contract, ERROR_CONTRACT)
  assert.equal(error.errorCount, 2)
  assert.throws(() => createError(decodedTask(), 'jmeter-runner-test', { threadGroupName: '订单', responseCode: 'error detail', errorCount: 2 }), /错误归因/)
})

test('validates bounded stepped ramp artifacts and rejects ambiguous stage settings', () => {
  const artifact = { contract: 'testhub.jmeter-runner.runtime-configuration', artifactVersion: 6,
    executionId: ids.executionId, taskId: ids.taskId, deadlineAt: '2026-07-19T08:05:00.000Z',
    threadGroups: [{ name: '阶梯订单', targetUrl: 'http://127.0.0.1:18081/orders', loadModel: 'STEPPED_RAMP',
      threadCount: 12, rampUpSeconds: 0, stepCount: 3, stepIntervalSeconds: 10, durationSeconds: 30, targetTps: 30,
      requestMethod: 'GET', requestHeaders: {}, queryParameters: {}, requestBody: null, expectedStatusCode: 200,
      responseContains: null, dataSetCsv: null, rendezvousUsers: null }] }
  const validated = validateRuntimeConfiguration(decodedTask(), artifact, ['http://127.0.0.1:18081'])
  assert.equal(validated.threadGroups[0].stepCount, 3)
  assert.equal(validated.threadGroups[0].stepIntervalSeconds, 10)
  assert.throws(() => validateRuntimeConfiguration(decodedTask(), { ...artifact,
    threadGroups: [{ ...artifact.threadGroups[0], targetTps: 2 }] }, ['http://127.0.0.1:18081']), /目标 TPS/)
  assert.throws(() => validateRuntimeConfiguration(decodedTask(), { ...artifact,
    threadGroups: [{ ...artifact.threadGroups[0], rendezvousUsers: 2 }] }, ['http://127.0.0.1:18081']), /阶梯加压/)
})

test('binds JMeter archive requests to the task, artifact type, length and checksum', () => {
  const request = createArtifactUploadRequest({ runnerId: 'jmeter-runner-test', heartbeatSigningKey: 'a'.repeat(32) }, decodedTask(),
    { artifactType: 'JMETER_JTL', sizeBytes: 91, checksum: 'f'.repeat(64) }, new Date('2026-07-19T08:00:00.000Z'),
    'nonce_archive_0123456789')
  assert.equal(request.path, ARTIFACT_UPLOAD_PATH)
  assert.equal(request.headers['Content-Type'], 'text/csv')
  assert.equal(request.headers['X-TestHub-Artifact-Type'], 'JMETER_JTL')
  assert.equal(request.headers['X-TestHub-Artifact-Size'], '91')
  assert.equal(request.headers['X-TestHub-Task-Id'], ids.taskId)
  assert.match(request.headers['X-TestHub-Runner-Signature'], /^[0-9a-f]{64}$/)
})

function decodedTask() { return decodeTask(task()) }
