import test from 'node:test'
import assert from 'node:assert/strict'
import { CANCEL_CONTRACT, createHeartbeat, createStepResult, decodeCancellation, decodeTask } from '../src/protocol.js'
import { loadConfig } from '../src/config.js'
import { PlaywrightRunner } from '../src/runner.js'

const task = { contract: 'testhub.playwright-runner.task', protocolVersion: '1', taskId: '01KX8XJTSHD5QG60TYT3JV48TW', executionId: '01KX8XJTSHD5QG60TYT3JV48TX', organizationId: '01KX8XJTSHD5QG60TYT3JV48TY', projectId: '01KX8XJTSHD5QG60TYT3JV48TZ', scenarioId: '01KX8XJTSHD5QG60TYT3JV48TV', scenarioVersion: '1', requestedAt: '2026-07-24T00:00:00.000Z', deadlineAt: '2026-07-24T00:05:00.000Z', manifestRef: 'ui-automation/manifests/01KX8XJTSHD5QG60TYT3JV48TX/v1.json', traceId: '01KX8XJTSHD5QG60TYT3JV48TW' }
test('decodes immutable task contract', () => assert.equal(decodeTask(task).executionId, task.executionId))
test('rejects unknown task fields', () => assert.throws(() => decodeTask({ ...task, unknown: 'x' })))
test('decodes cancellation command with exact field shape', () => {
  const command = { contract: CANCEL_CONTRACT, protocolVersion: '1', commandId: '01KX8XJTSHD5QG60TYT3JV48TQ', taskId: task.taskId, executionId: task.executionId, requestedAt: task.requestedAt, reasonCode: 'USER_REQUESTED' }
  assert.equal(decodeCancellation(command).executionId, task.executionId)
  assert.throws(() => decodeCancellation({ ...command, reasonCode: 'bad reason' }))
})
test('creates signed heartbeat and bounded step result', () => {
  const config = { runnerId: 'runner-1', runnerVersion: '1.0.0', executionEnabled: true, heartbeatSigningKey: 'x'.repeat(32), maxConcurrentTasks: 1 }
  assert.match(createHeartbeat(config, new Date('2026-01-01T00:00:00Z'), 0).signature, /^[0-9a-f]{64}$/)
  assert.equal(createStepResult(task, 'runner-1', { id: 'one', type: 'NAVIGATE', name: 'open' }, 1, 'PASSED', 20).outcome, 'PASSED')
})
test('allows at most two explicitly configured isolated browser slots', () => {
  assert.equal(loadConfig({ RUNNER_MAX_CONCURRENT_TASKS: '2' }).maxConcurrentTasks, 2)
  assert.throws(() => loadConfig({ RUNNER_MAX_CONCURRENT_TASKS: '3' }))
})
test('reserves two slots before running tasks concurrently and releases both after completion', async () => {
  const events = []; const acknowledgements = []; let release
  const gate = new Promise((resolve) => { release = resolve })
  const runner = new PlaywrightRunner({ commandClient: {}, readClient: {}, cancelClient: {}, config: { ...loadConfig({ RUNNER_MAX_CONCURRENT_TASKS: '2' }), executionEnabled: true, runnerId: 'runner-1' }, logger: { info() {}, error() {} } })
  runner.heartbeat = async () => {}; runner.event = async (event) => { events.push(event) }; runner.ack = async (id) => { acknowledgements.push(id) }
  const started = []; runner.run = async (value) => { started.push(value.taskId); await gate }
  const second = { ...task, taskId: '01KX8XJTSHD5QG60TYT3JV48TQ', executionId: '01KX8XJTSHD5QG60TYT3JV48TR' }
  const firstPromise = runner.handle({ id: '1-0', fields: task }); const secondPromise = runner.handle({ id: '2-0', fields: second })
  await Promise.resolve()
  assert.deepEqual(started.sort(), [task.taskId, second.taskId].sort())
  assert.equal(runner.running.size, 2)
  release(); await Promise.all([firstPromise, secondPromise])
  assert.equal(runner.running.size, 0)
  assert.deepEqual(events.filter((event) => event.type === 'SUCCEEDED').map((event) => event.taskId).sort(), [task.taskId, second.taskId].sort())
  assert.deepEqual(acknowledgements.sort(), ['1-0', '2-0'])
})

test('consumes Redis RESP3 task stream replies and acknowledges completed transport', async () => {
  const commands = []
  const runner = new PlaywrightRunner({ commandClient: { sendCommand: async command => { commands.push(command) } },
    readClient: { sendCommand: async () => { runner.stopping = true; return { stream: [['1-0', Object.entries(task).flat()]] } } },
    cancelClient: {}, config: { runnerId: 'test', maxConcurrentTasks: 1, executionEnabled: false } })
  await runner.loop(); await new Promise(resolve => setTimeout(resolve, 0))
  assert.ok(commands.some(command => command.includes('PLAYWRIGHT_EXECUTION_DISABLED')))
  assert.ok(commands.some(command => command[0] === 'XACK'))
})

test('consumes Redis RESP3 cancellation stream replies', async () => {
  const commands = []
  const command = { contract: CANCEL_CONTRACT, protocolVersion: '1', commandId: '01KX8XJTSHD5QG60TYT3JV48TQ', taskId: task.taskId, executionId: task.executionId, requestedAt: task.requestedAt, reasonCode: 'USER_REQUESTED' }
  const runner = new PlaywrightRunner({ commandClient: { sendCommand: async command => { commands.push(command) } }, readClient: {},
    cancelClient: { sendCommand: async () => { runner.stopping = true; return { stream: [['2-0', Object.entries(command).flat()]] } } }, config: {} })
  await runner.cancellationLoop()
  assert.ok(runner.cancelled.has(task.taskId))
  assert.ok(commands.some(command => command[0] === 'XACK'))
})
