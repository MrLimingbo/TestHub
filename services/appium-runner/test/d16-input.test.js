import test from 'node:test'
import assert from 'node:assert/strict'
import { runtime } from '../src/protocol.js'
import { AppiumRunner } from '../src/runner.js'

// D16 protocol cases: valid integer bounds/defaults; invalid numbers and types;
// text and variable type/length; reject before session creation.
const task = { contract: 'testhub.appium-runner.task', protocolVersion: '1', taskId: '01KX8XJTSHD5QG60TYT3JV48TW', executionId: '01KX8XJTSHD5QG60TYT3JV48TX', organizationId: '01KX8XJTSHD5QG60TYT3JV48TY', projectId: '01KX8XJTSHD5QG60TYT3JV48TZ', scenarioId: '01KX8XJTSHD5QG60TYT3JV48TV', scenarioVersion: '1', requestedAt: '2026-07-24T00:00:00.000Z', deadlineAt: '2026-07-24T00:05:00.000Z', manifestRef: 'mobile-automation/manifests/01KX8XJTSHD5QG60TYT3JV48TX/v1.json', traceId: '01KX8XJTSHD5QG60TYT3JV48TW' }
const make = () => ({ contract: 'testhub.appium-runner.runtime-configuration', version: 1, taskId: task.taskId, executionId: task.executionId, deadlineAt: '2099-01-01T00:00:00Z', manifest: { device: {}, application: {}, scenario: { variables: { v: '001' }, steps: [{ type: 'LAUNCH', timeoutMillis: 30000, value: '', expectedText: null }] } } })
const invalid = v => assert.throws(() => runtime(task, v), { code: 'INVALID_APPIUM_PROTOCOL' })

test('D16 mobile runtime accepts step timeout bounds, null and absent defaults', () => {
  for (const timeoutMillis of [1, 60000, 30000, null, undefined]) { const v = make(); v.manifest.scenario.steps[0].timeoutMillis = timeoutMillis; assert.equal(runtime(task, v), v) }
})
test('D16 mobile runtime rejects invalid step timeout before operation', () => {
  for (const timeoutMillis of [0, 60001, 1.5, -0.1, '10', true, [], {}, Number.MAX_SAFE_INTEGER + 1]) { const v = make(); v.manifest.scenario.steps[0].timeoutMillis = timeoutMillis; invalid(v) }
})
test('D16 mobile runtime enforces string variables without converting null', () => {
  for (const entry of [null, 1, false, [], {}, 'x'.repeat(4097)]) { const v = make(); v.manifest.scenario.variables = { key: entry }; invalid(v) }
  for (const variables of [null, {}, { key: '', flag: 'false', value: '{{name}}' }]) { const v = make(); v.manifest.scenario.variables = variables; runtime(task, v) }
})
test('D16 mobile runtime enforces step text types and maximum length', () => {
  for (const field of ['value', 'expectedText']) for (const text of [1, true, [], {}, 'x'.repeat(4097)]) { const v = make(); v.manifest.scenario.steps[0][field] = text; invalid(v) }
  for (const text of [null, '', '001', '{{key}}']) { const v = make(); v.manifest.scenario.steps[0].value = text; runtime(task, v) }
})
test('D16 invalid runtime fails before Appium session creation', async () => {
  const calls = [], events = [], config = { runnerId: 'test', executionEnabled: true, maxConcurrentTasks: 1, controlPlaneUrl: 'https://control.test', heartbeatSigningKey: 'test', heartbeatIntervalSeconds: 60, heartbeatTtlSeconds: 120 }
  const v = make(); v.manifest.scenario.steps[0].timeoutMillis = 1.5
  const runner = new AppiumRunner({ config, commandClient: {}, fetcher: async url => { calls.push(url); return globalThis.Response.json({ data: v }) } })
  runner.publish = async e => events.push(e); runner.ack = async () => {}; runner.sendHeartbeat = async () => {}
  await runner.handle({ id: '1-0', fields: task })
  assert.equal(calls.length, 1); assert.match(calls[0], /runtime-configuration/); assert.equal(events.at(-1).type, 'FAILED')
})
