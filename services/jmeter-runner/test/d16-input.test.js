import assert from 'node:assert/strict'
import test from 'node:test'
import { validateRuntimeConfiguration } from '../src/protocol.js'
const task = { taskId: '01J00000000000000000000001', executionId: '01J00000000000000000000002' }
const group = { name: 'QA', targetUrl: 'http://127.0.0.1:18081/qa', loadModel: 'RAMP_UP', threadCount: 10, rampUpSeconds: 0, durationSeconds: 10, targetTps: 10, requestMethod: 'POST', requestHeaders: { 'X-Code': '001' }, queryParameters: { id: '001' }, requestBody: '{"n":1}', expectedStatusCode: 200, responseContains: '001', dataSetCsv: null, rendezvousUsers: 2 }
const artifact = g => ({ contract: 'testhub.jmeter-runner.runtime-configuration', artifactVersion: 6, ...task, deadlineAt: '2026-11-02T00:00:00Z', threadGroups: [g] })
const validate = g => validateRuntimeConfiguration(task, artifact(g), ['http://127.0.0.1:18081'])
test('TC-D16PERF-62: invalid input fails pure preflight before JMeter or target requests', () => {
  for (const field of ['threadCount', 'rampUpSeconds', 'durationSeconds', 'targetTps', 'expectedStatusCode', 'rendezvousUsers']) for (const value of [1.5, '10', true, [], {}]) assert.throws(() => validate({ ...group, [field]: value }))
  for (const field of ['stepCount', 'stepIntervalSeconds']) for (const value of [2.5, '2', true, []]) assert.throws(() => validate({ ...group, loadModel: 'STEPPED_RAMP', rendezvousUsers: null, stepCount: 2, stepIntervalSeconds: 1, [field]: value }))
  for (const field of ['requestHeaders', 'queryParameters']) for (const value of [1, true, [], {}]) assert.throws(() => validate({ ...group, [field]: { 'X-Code': value } }))
  for (const field of ['requestBody', 'responseContains', 'dataSetCsv']) for (const value of [1, true, {}]) assert.throws(() => validate({ ...group, [field]: value }))
})
test('TC-D16PERF-63: existing normalized and short internal protocol values stay readable', () => {
  for (const raw of ['10', '10.0', '1e1']) { const g = { ...group, threadCount: JSON.parse(raw) }; const result = validate(g).threadGroups[0]; assert.equal(result.threadCount, 10); assert.equal(result.queryParameters.id, '001') }
  assert.equal(validate({ ...group, durationSeconds: 3 }).threadGroups[0].durationSeconds, 3)
})
