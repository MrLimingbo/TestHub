import assert from 'node:assert/strict'
import test from 'node:test'
import { ApiRunner } from '../src/runner.js'
const task = { taskId: 'task-qa', executionId: 'execution-qa', deadlineAt: '2026-11-02T00:15:00Z' }
function setup(value) {
  let calls = 0
  const runner = new ApiRunner({ commandClient: {}, readClient: {}, now: () => new Date('2026-11-02T00:00:00Z'), config: { allowedHttpOrigins: ['https://8.8.8.8'] }, fetcher: async () => { calls++; throw new Error('Unexpected target request') }, logger: { info() {}, error() {} } })
  const artifact = { contract: 'testhub.api-runner.runtime-configuration', artifactVersion: 5, taskId: task.taskId, executionId: task.executionId, variables: {}, scripts: { preRequestScript: '', postResponseScript: '' }, steps: [{ stepId: 'request', type: 'REQUEST', method: 'GET', url: 'https://8.8.8.8/qa', body: '', contentType: 'application/json' }, { stepId: 'wait', type: 'WAIT', delayMillis: value }] }
  return { runner, artifact, calls: () => calls }
}
test('TC-D16API-22: Runner precheck accepts bounded integral WAIT without target traffic', async () => {
  for (const delay of [0, 10, 10.0, 1e1, 600000]) { const q = setup(delay); assert.doesNotThrow(() => q.runner.validateArtifact(task, q.artifact)); assert.equal(q.calls(), 0); if (delay <= 10) assert.equal(await q.runner.waitForStep(task, delay), true) }
})
test('TC-D16API-23: Runner rejects invalid WAIT before any target call', () => {
  for (const delay of [-1, 600001, 1.5, '10', NaN, Infinity, true, null, undefined]) { const q = setup(delay); assert.throws(() => q.runner.validateArtifact(task, q.artifact)); assert.equal(q.calls(), 0) }
})
