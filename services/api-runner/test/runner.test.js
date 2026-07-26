import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiRunner } from '../src/runner.js'
import { CANCEL_CONTRACT, TASK_CONTRACT } from '../src/protocol.js'

const task = {
  contract: TASK_CONTRACT, protocolVersion: '1', taskId: '01J00000000000000000000001', executionId: '01J00000000000000000000002',
  organizationId: '01J00000000000000000000003', projectId: '01J00000000000000000000004', scenarioId: '01J00000000000000000000005',
  scenarioVersion: '1', attempt: '1', requestedAt: '2026-07-14T08:00:00.000Z', deadlineAt: '2026-07-14T08:05:00.000Z',
  manifestRef: 'api-automation/manifests/01J00000000000000000000005/v1.json', traceId: 'request-1',
}

test('valid work is acknowledged only after an execution-disabled event is emitted', async () => {
  const commands = []
  const commandClient = { sendCommand: async (command) => { commands.push(command) } }
  const runner = new ApiRunner({
    commandClient, readClient: {}, config: { runnerId: 'runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
  })
  await runner.handleEntry({ id: '1710000000000-0', fields: task })
  assert.deepEqual(commands.map((command) => command[0]), ['XADD', 'XADD', 'XADD', 'XADD', 'XACK'])
  assert.equal(commands[2].includes('EXECUTION_DISABLED'), true)
  assert.equal(commands[3].includes('REJECTED'), true)
})

test('a cancellation command emits one cancellation event and prevents precheck rejection', async () => {
  const commands = []
  const commandClient = { sendCommand: async (command) => { commands.push(command) } }
  const runner = new ApiRunner({
    commandClient, readClient: {}, config: { runnerId: 'runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
  })
  await runner.handleCancellationEntry({ id: '1710000000000-2', fields: {
    contract: CANCEL_CONTRACT, protocolVersion: '1', commandId: '01J00000000000000000000006', taskId: task.taskId,
    executionId: task.executionId, requestedAt: '2026-07-14T08:00:01.000Z', reasonCode: 'USER_REQUESTED',
  } })
  await runner.handleEntry({ id: '1710000000000-3', fields: task })
  assert.deepEqual(commands.map((command) => command[0]), ['XADD', 'XADD', 'XACK', 'XADD', 'XADD', 'XADD', 'XACK'])
  assert.equal(commands[1].includes('CANCELLED'), true)
  assert.equal(commands.some((command) => command.includes('EXECUTION_DISABLED')), false)
})

test('malformed work is dead-lettered without persisting untrusted field values', async () => {
  const commands = []
  const commandClient = { sendCommand: async (command) => { commands.push(command) } }
  const runner = new ApiRunner({
    commandClient, readClient: {}, config: { runnerId: 'runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
  })
  await runner.handleEntry({ id: '1710000000000-1', fields: { ...task, authorization: 'never-record-this-value' } })
  assert.deepEqual(commands.map((command) => command[0]), ['XADD', 'XACK'])
  assert.equal(commands[0].includes('never-record-this-value'), false)
  assert.equal(commands[0].some((value) => String(value).includes('authorization')), true)
})

test('capability discovery accepts only a trusted, configuration-disabled response', async () => {
  const messages = []
  const runner = new ApiRunner({
    commandClient: {}, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'http://platform.test' },
    logger: { info(value) { messages.push(value) }, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url, options) => {
      assert.equal(url, 'http://platform.test/internal/api-runner/v1/capabilities')
      assert.equal(options.method, 'GET')
      assert.equal(options.headers['X-TestHub-Runner-Id'], 'runner-test')
      assert.match(options.headers['X-TestHub-Runner-Signature'], /^[0-9a-f]{64}$/)
      return { ok: true, json: async () => ({ data: { runnerId: 'runner-test', trusted: true, executionEnabled: false, runtimeConfiguration: 'DISABLED' } }) }
    },
  })
  await runner.discoverCapabilities()
  assert.deepEqual(messages, ['API Runner 已完成签名能力发现：runner-test'])
})

test('an enabled Runner receives and executes only a bounded HTTP artifact', async () => {
  const commands = []
  const signingKey = 'a'.repeat(32)
  const leaseValue = 'A'.repeat(43)
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: signingKey, controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => {
      if (url.endsWith('/runtime-leases')) return { ok: true, json: async () => ({ data: { taskId: task.taskId,
        executionId: task.executionId, lease: leaseValue, purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      if (url.endsWith('/runtime-configuration')) return { ok: true, json: async () => ({ data: {
        contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId,
        executionId: task.executionId, variables: {}, scripts: { preRequestScript: '', postResponseScript: '' },
        steps: [{ stepId: 'request-1', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders', body: '', contentType: '' }],
      } }) }
      throw new Error(`unexpected URL ${url}`)
    },
    httpExecutor: async (request, limits) => {
      assert.equal(request.url, 'https://api.example.test/orders')
      assert.deepEqual(limits.allowedHttpOrigins, ['https://api.example.test'])
      return { status: 200, ok: true }
    },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-4', fields: task })
  assert.equal(commands.some((command) => command.includes('HTTP_EXECUTION_SUCCEEDED')), true)
  assert.equal(commands.some((command) => command.includes('SUCCEEDED')), true)
  assert.equal(commands.some((command) => command.includes('testhub:runner:api:http-results:v2')), true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/orders')), false)
  assert.equal(commands.some((command) => command.includes(leaseValue)), false)
})

test('a non-success HTTP result is reported as a fixed failure without response content', async () => {
  const commands = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => {
      if (url.endsWith('/runtime-leases')) return { ok: true, json: async () => ({ data: {
        taskId: task.taskId, executionId: task.executionId, lease: 'B'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION',
      } }) }
      return { ok: true, json: async () => ({ data: {
        contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId,
        executionId: task.executionId, variables: {}, scripts: { preRequestScript: '', postResponseScript: '' },
        steps: [{ stepId: 'request-1', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders', body: '', contentType: '' }],
      } }) }
    },
    httpExecutor: async () => ({ status: 503, ok: false }),
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-5', fields: task })
  assert.equal(commands.some((command) => command.includes('HTTP_EXECUTION_FAILED')), true)
  assert.equal(commands.some((command) => command.includes('statusCode') && command.includes('503')), true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/orders')), false)
})

test('a transient GET response is retried with a stable request sequence and redacted attempt number', async () => {
  const commands = []
  let calls = 0
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'R'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [
          { stepId: 'request-retry', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders', body: '', contentType: '', maxAttempts: 3 },
        ] } }) },
    httpExecutor: async () => (++calls === 1 ? { status: 503, ok: false } : { status: 204, ok: true }),
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-5-retry', fields: task })
  const httpResults = commands.filter((command) => command.includes('testhub:runner:api:http-results:v2'))
  assert.equal(calls, 2)
  assert.equal(httpResults.length, 2)
  assert.equal(httpResults.every((command) => command.includes('requestSequence') && command.includes('1')), true)
  assert.equal(httpResults.some((command) => command.includes('attemptNumber') && command.includes('2')), true)
  assert.equal(commands.some((command) => command.includes('HTTP_RETRY_SCHEDULED')), true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/orders')), false)
})

test('a CONTINUE scenario records a failed assertion and runs later bounded requests before failing the execution', async () => {
  const commands = []
  const requests = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'S'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        failureStrategy: 'CONTINUE', variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [
          { stepId: 'request-first', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/first', body: '', contentType: '' },
          { stepId: 'assert-first', type: 'ASSERT', expectedStatusCode: 200 },
          { stepId: 'request-later', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/later', body: '', contentType: '' },
        ] } }) },
    httpExecutor: async (request) => { requests.push(request.url); return request.url.endsWith('/first') ? { status: 503, ok: false } : { status: 204, ok: true } },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-continue', fields: task })
  const secondHttpResult = commands.findIndex((command) => command.includes('testhub:runner:api:http-results:v2') && command.includes('request-later'))
  const failureEvent = commands.findIndex((command) => command.includes('HTTP_EXECUTION_FAILED'))
  assert.deepEqual(requests, ['https://api.example.test/first', 'https://api.example.test/later'])
  assert.equal(commands.some((command) => command.includes('testhub:runner:api:step-results:v1') && command.includes('FAILED')), true)
  assert.equal(commands.some((command) => command.includes('FAILURE_CONTINUED')), true)
  assert.equal(secondHttpResult >= 0 && secondHttpResult < failureEvent, true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/first') || command.includes('https://api.example.test/later')), false)
})

test('a CONTINUE scenario runs later requests after a failed post-response script and still fails safely', async () => {
  const commands = []
  const requests = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'T'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        failureStrategy: 'CONTINUE', variables: {}, scripts: { preRequestScript: '',
          postResponseScript: "if (pm.response.code === 201) { pm.test('private-check', () => pm.response.to.have.status(200)); }" }, steps: [
          { stepId: 'request-script-first', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/first', body: '', contentType: '' },
          { stepId: 'request-script-later', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/later', body: '', contentType: '' },
        ] } }) },
    httpExecutor: async (request) => { requests.push(request.url); return request.url.endsWith('/first') ? { status: 201, ok: true } : { status: 204, ok: true } },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-continue-script', fields: task })
  assert.deepEqual(requests, ['https://api.example.test/first', 'https://api.example.test/later'])
  assert.equal(commands.some((command) => command.includes('POST_RESPONSE_SCRIPT_FAILED')), true)
  assert.equal(commands.some((command) => command.includes('FAILURE_CONTINUED')), true)
  assert.equal(commands.some((command) => command.includes('HTTP_EXECUTION_FAILED')), true)
  assert.equal(commands.some((command) => command.includes('private-check') || command.includes('https://api.example.test/first')), false)
})

test('an extracted status can be used by a later request and an exact assertion is reported without values', async () => {
  const commands = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'C'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [
        { stepId: 'request-first', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders', body: '', contentType: '' },
        { stepId: 'extract-status', type: 'EXTRACT', variableName: 'orderStatus' },
        { stepId: 'assert-status', type: 'ASSERT', expectedStatusCode: 201 },
        { stepId: 'request-next', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders/{{orderStatus}}', body: '', contentType: '' },
      ] } }) },
    httpExecutor: async (request) => request.url.endsWith('/orders') ? { status: 201, ok: true } : { status: 204, ok: true },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-6', fields: task })
  assert.equal(commands.some((command) => command.includes('testhub:runner:api:step-results:v1')), true)
  assert.equal(commands.some((command) => command.includes('PASSED')), true)
  assert.equal(commands.some((command) => command.includes('orderStatus')), false)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/orders/201')), false)
})

test('a bounded flow artifact waits, controls one request and repeats only its preceding request', async () => {
  const commands = []
  const requests = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'D'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [
        { stepId: 'request-first', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/first', body: '', contentType: '' },
        { stepId: 'wait-stable', type: 'WAIT', delayMillis: 1 },
        { stepId: 'condition-next', type: 'CONDITION', conditionOperator: 'EQUALS', expectedStatusCode: 200 },
        { stepId: 'request-conditional', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/conditional', body: '', contentType: '' },
        { stepId: 'request-repeat', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/retry', body: '', contentType: '' },
        { stepId: 'loop-repeat', type: 'LOOP', loopTotalRuns: 3 },
      ] } }) },
    httpExecutor: async (request) => { requests.push(request.url); return { status: 200, ok: true } },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-7', fields: task })
  assert.deepEqual(requests, [
    'https://api.example.test/first', 'https://api.example.test/conditional',
    'https://api.example.test/retry', 'https://api.example.test/retry', 'https://api.example.test/retry',
  ])
  assert.equal(commands.filter((command) => command.includes('testhub:runner:api:http-results:v2')).length, 5)
  assert.equal(commands.some((command) => command.includes('WAIT_COMPLETED')), true)
  assert.equal(commands.some((command) => command.includes('CONDITION_PASSED')), true)
  assert.equal(commands.some((command) => command.includes('LOOP_COMPLETED')), true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/retry')), false)
})

test('a false fixed condition skips exactly its following request without persisting it', async () => {
  const commands = []
  const requests = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'E'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [
        { stepId: 'request-first', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/first', body: '', contentType: '' },
        { stepId: 'condition-skip', type: 'CONDITION', conditionOperator: 'EQUALS', expectedStatusCode: 201 },
        { stepId: 'request-skipped', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/never', body: '', contentType: '' },
      ] } }) },
    httpExecutor: async (request) => { requests.push(request.url); return { status: 200, ok: true } },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-8', fields: task })
  assert.deepEqual(requests, ['https://api.example.test/first'])
  assert.equal(commands.some((command) => command.includes('CONDITION_SKIPPED')), true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/never')), false)
})

test('scripts mutate only the ephemeral Runner variable map and publish redacted aggregate assertion counts', async () => {
  const commands = []
  const requests = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'F'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: { orderId: 'OD-1' }, scripts: { preRequestScript: "pm.environment.set('orderId', pm.variables.get('orderId') + '-pre')", postResponseScript: "pm.test('created', function () { pm.response.to.have.status(201); pm.expect(pm.response.code).to.eql(201); });" },
        steps: [{ stepId: 'request-scripted', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders/{{orderId}}', body: '', contentType: '' }] } }) },
    httpExecutor: async (request) => { requests.push(request.url); return { status: 201, ok: true } },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-9', fields: task })
  assert.deepEqual(requests, ['https://api.example.test/orders/OD-1-pre'])
  assert.equal(commands.some((command) => command.includes('PRE_REQUEST_SCRIPT_COMPLETED')), true)
  assert.equal(commands.some((command) => command.includes('POST_RESPONSE_SCRIPT_COMPLETED')), true)
  assert.equal(commands.some((command) => command.includes('testhub:runner:api:script-results:v1')), true)
  assert.equal(commands.some((command) => command.includes('testCount') && command.includes('passedCount')), true)
  assert.equal(commands.some((command) => command.includes('OD-1-pre')), false)
  assert.equal(commands.some((command) => command.includes('lastStatus')), false)
  assert.equal(commands.some((command) => command.includes('created')), false)
})

test('a failed pm test publishes aggregate counts before the fixed execution failure', async () => {
  const commands = []
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'G'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: {}, scripts: { preRequestScript: '', postResponseScript: "pm.test('private assertion name', () => pm.response.to.have.status(200));" },
        steps: [{ stepId: 'request-scripted', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/orders', body: '', contentType: '' }] } }) },
    httpExecutor: async () => ({ status: 201, ok: true }),
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  await runner.handleEntry({ id: '1710000000000-10', fields: task })
  const resultIndex = commands.findIndex((command) => command.includes('testhub:runner:api:script-results:v1'))
  const failureIndex = commands.findIndex((command) => command.includes('HTTP_EXECUTION_FAILED'))
  assert.equal(resultIndex >= 0 && resultIndex < failureIndex, true)
  assert.equal(commands[resultIndex].includes('failedCount') && commands[resultIndex].includes('1'), true)
  assert.equal(commands.some((command) => command.includes('private assertion name')), false)
})

test('a bounded parallel request group starts independent branches together and publishes only parent-step results', async () => {
  const commands = []
  const started = new Set()
  let markBothStarted
  let releaseBranches
  const bothStarted = new Promise((resolve) => { markBothStarted = resolve })
  const branchesReleased = new Promise((resolve) => { releaseBranches = resolve })
  const runner = new ApiRunner({
    commandClient: { sendCommand: async (command) => { commands.push(command) } }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'runner-test', heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'https://platform.test',
      httpExecutionEnabled: true, allowedHttpOrigins: ['https://api.example.test'], precheckDelayMilliseconds: 0 },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-14T08:00:00.000Z'),
    fetcher: async (url) => url.endsWith('/runtime-leases')
      ? { ok: true, json: async () => ({ data: { taskId: task.taskId, executionId: task.executionId, lease: 'H'.repeat(43), purpose: 'HTTP_REQUEST_EXECUTION' } }) }
      : { ok: true, json: async () => ({ data: { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
        variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [
          { stepId: 'parallel-group', type: 'PARALLEL', parallelRequests: [
            { method: 'GET', url: 'https://api.example.test/health', body: '', contentType: '', maxAttempts: 1 },
            { method: 'GET', url: 'https://api.example.test/version', body: '', contentType: '', maxAttempts: 1 },
          ] },
          { stepId: 'request-after', type: 'REQUEST', method: 'GET', url: 'https://api.example.test/after', body: '', contentType: '' },
        ] } }) },
    httpExecutor: async (request) => {
      if (request.url.endsWith('/health') || request.url.endsWith('/version')) {
        started.add(request.url)
        if (started.size === 2) markBothStarted()
        await branchesReleased
      }
      return { status: 204, ok: true }
    },
  })
  runner.runtimeConfiguration = 'HTTP_FLOW_CONTROL_SCRIPTS_V2'
  const handling = runner.handleEntry({ id: '1710000000000-parallel', fields: task })
  await Promise.race([
    bothStarted,
    new Promise((_resolve, reject) => setTimeout(() => reject(new Error('parallel branches did not start together')), 100)),
  ])
  releaseBranches()
  await handling
  const httpResults = commands.filter((command) => command.includes('testhub:runner:api:http-results:v2'))
  assert.deepEqual([...started].sort(), ['https://api.example.test/health', 'https://api.example.test/version'])
  assert.equal(httpResults.filter((command) => command.includes('parallel-group')).length, 2)
  assert.equal(httpResults.some((command) => command.includes('requestSequence') && command.includes('1')), true)
  assert.equal(httpResults.some((command) => command.includes('requestSequence') && command.includes('2')), true)
  assert.equal(httpResults.some((command) => command.includes('requestSequence') && command.includes('3')), true)
  assert.equal(commands.some((command) => command.includes('PARALLEL_REQUESTS_STARTED')), true)
  assert.equal(commands.some((command) => command.includes('PARALLEL_REQUESTS_COMPLETED')), true)
  assert.equal(commands.some((command) => command.includes('https://api.example.test/health') || command.includes('https://api.example.test/version')), false)
})

test('a parallel request group rejects scripts and dependent assertion steps before it can execute', () => {
  const runner = new ApiRunner({ commandClient: {}, readClient: {}, cancelReadClient: {}, config: { allowedHttpOrigins: [] } })
  const parallelStep = { stepId: 'parallel-group', type: 'PARALLEL', parallelRequests: [
    { method: 'GET', url: 'https://api.example.test/health', body: '', contentType: '' },
    { method: 'GET', url: 'https://api.example.test/version', body: '', contentType: '' },
  ] }
  const artifact = (steps, scripts = { preRequestScript: '', postResponseScript: '' }) => ({
    contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId,
    variables: {}, scripts, steps,
  })
  assert.throws(() => runner.validateArtifact(task, artifact([parallelStep], { preRequestScript: 'pm.test(\'x\', () => {})', postResponseScript: '' })),
    /Runner 运行时工件不安全/)
  assert.throws(() => runner.validateArtifact(task, artifact([parallelStep, { stepId: 'assert-group', type: 'ASSERT', expectedStatusCode: 200 }])),
    /Runner 运行时工件不安全/)
})
