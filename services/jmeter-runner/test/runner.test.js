import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { JMeterRunner } from '../src/runner.js'
import { CANCEL_CONTRACT, ERROR_STREAM, RESOURCE_STREAM, TASK_CONTRACT } from '../src/protocol.js'

const task = { contract: TASK_CONTRACT, protocolVersion: '1', taskId: '01J00000000000000000000001', executionId: '01J00000000000000000000002',
  organizationId: '01J00000000000000000000003', projectId: '01J00000000000000000000004', planId: '01J00000000000000000000005',
  planVersion: '1', requestedAt: '2026-07-19T08:00:00.000Z', deadlineAt: '2026-07-19T08:05:00.000Z',
  manifestRef: 'performance-test/manifests/01J00000000000000000000002/v1.json', traceId: 'performance-1' }

test('acknowledges a disabled task only after accepted and rejected status events', async () => {
  const commands = []
  const runner = new JMeterRunner({ commandClient: { sendCommand: async (command) => commands.push(command) }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30, jmeterExecutionEnabled: false },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z') })
  await runner.handleEntry({ id: '1710000000000-0', fields: task })
  assert.deepEqual(commands.map((command) => command[0]), ['XADD', 'XADD', 'XACK'])
  assert.equal(commands[0].includes('ACCEPTED'), true)
  assert.equal(commands[1].includes('JMETER_EXECUTION_DISABLED'), true)
})

test('cancellation emits one terminal event and suppresses later task acceptance', async () => {
  const commands = []
  const runner = new JMeterRunner({ commandClient: { sendCommand: async (command) => commands.push(command) }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30, jmeterExecutionEnabled: false },
    logger: { info() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z') })
  await runner.handleCancellationEntry({ id: '1710000000000-1', fields: { contract: CANCEL_CONTRACT, protocolVersion: '1',
    commandId: '01J00000000000000000000006', taskId: task.taskId, executionId: task.executionId,
    requestedAt: '2026-07-19T08:00:01.000Z', reasonCode: 'USER_REQUESTED' } })
  await runner.handleEntry({ id: '1710000000000-2', fields: task })
  assert.equal(commands.filter((command) => command.includes('CANCELLED')).length, 2)
  assert.equal(commands.some((command) => command.includes('JMETER_EXECUTION_DISABLED')), false)
})

test('defers a task before acceptance when this Runner has no free execution slot', async () => {
  const commands = []
  const runner = new JMeterRunner({ commandClient: { sendCommand: async (command) => commands.push(command) }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30, maxConcurrentTasks: 1,
      jmeterExecutionEnabled: false }, logger: { info() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z') })
  runner.runningTasks.set('01J00000000000000000000009', {})
  await runner.handleEntry({ id: '1710000000000-2', fields: task })
  assert.equal(commands.length, 2)
  assert.equal(commands[0].includes('DEFERRED'), true)
  assert.equal(commands[0].includes('RUNNER_CAPACITY_EXHAUSTED'), true)
  assert.equal(commands[1][0], 'XACK')
})

test('claims an abandoned Redis task before consuming newer entries', async () => {
  const commands = []
  const runner = new JMeterRunner({ commandClient: { sendCommand: async (command) => {
    commands.push(command)
    return command[0] === 'XAUTOCLAIM' ? ['0-0', [['1710000000000-8', Object.entries(task).flat()]]] : undefined
  } }, readClient: {}, cancelReadClient: {},
  config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30, taskClaimIdleSeconds: 45,
    jmeterExecutionEnabled: false }, logger: { info() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z') })
  await runner.recoverAbandonedTasks()
  assert.equal(commands[0][0], 'XAUTOCLAIM')
  assert.equal(commands.some((command) => command.includes('JMETER_EXECUTION_DISABLED')), true)
  assert.equal(commands.at(-1)[0], 'XACK')
})

test('runs an allowed runtime artifact through the isolated JMeter process lifecycle', async () => {
  const commands = []; const requests = []; const spawnCalls = []
  const workDirectory = await mkdtemp(join(tmpdir(), 'testhub-jmeter-runner-'))
  const child = new EventEmitter()
  child.stdout = new EventEmitter(); child.stderr = new EventEmitter(); child.exitCode = null; child.killed = false; child.pid = 4812
  child.kill = () => { child.killed = true; return true }
  const runner = new JMeterRunner({
    commandClient: { sendCommand: async (command) => commands.push(command) }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30,
      heartbeatSigningKey: 'a'.repeat(32), jmeterExecutionEnabled: true, controlPlaneUrl: 'http://platform.test',
      allowedTargetOrigins: ['http://127.0.0.1:18081'], jmeterBin: 'jmeter', workDirectory },
    logger: { info() {}, warn() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z'),
    fetcher: async (url, options) => {
      requests.push({ url, options })
      return { ok: true, json: async () => ({ data: { contract: 'testhub.jmeter-runner.runtime-configuration', artifactVersion: 1,
        taskId: task.taskId, executionId: task.executionId, deadlineAt: task.deadlineAt,
        threadGroups: [{ name: '本地健康检查', targetUrl: 'http://127.0.0.1:18081/health', loadModel: 'STEADY',
          threadCount: 1, rampUpSeconds: 0, durationSeconds: 1, targetTps: null, requestMethod: 'GET', requestHeaders: {},
          requestBody: null, expectedStatusCode: 200, responseContains: 'UP' }] } }) }
    },
    spawn: (...args) => { spawnCalls.push(args); return child },
    resourceSampler: async (pid) => { assert.equal(pid, 4812); return { cpuPercent: 12.5, residentMemoryBytes: 4_194_304 } },
  })
  await runner.handleEntry({ id: '1710000000000-3', fields: task })
  child.exitCode = 0; child.emit('close', 0)
  await new Promise((resolve) => setTimeout(resolve, 25))
  const recorded = commands.map((command) => command.join('|'))
  assert.equal(requests[0].url, 'http://platform.test/internal/performance-runner/v1/runtime-configuration')
  assert.equal(spawnCalls[0][0], 'jmeter')
  assert.equal(spawnCalls[0][2].env.JVM_ARGS, '-Xms64m -Xmx512m -Djava.awt.headless=true -Djava.net.useSystemProxies=false -Dhttp.proxyHost= -Dhttps.proxyHost= -DsocksProxyHost=')
  assert.equal(Object.hasOwn(spawnCalls[0][2].env, 'HTTP_PROXY'), false)
  assert.equal(Object.hasOwn(spawnCalls[0][2].env, 'HTTPS_PROXY'), false)
  assert.equal(recorded.some((value) => value.includes('JMETER_PROCESS_STARTED')), true)
  assert.equal(recorded.some((value) => value.startsWith(`XADD|${RESOURCE_STREAM}|`)), true)
  assert.equal(recorded.some((value) => value.includes('JMETER_PROCESS_SUCCEEDED')), true)
  assert.equal(recorded.some((value) => value.startsWith('XACK|')), true)
})

test('archives bounded JTL and JMeter logs through signed internal requests before cleanup', async () => {
  const requests = []; const workDirectory = await mkdtemp(join(tmpdir(), 'testhub-jmeter-artifacts-'))
  const resultsPath = join(workDirectory, 'results.jtl'); const logPath = join(workDirectory, 'jmeter.log')
  await writeFile(resultsPath, 'timeStamp,elapsed\n1710000000000,10\n')
  await writeFile(logPath, 'JMeter log output\n')
  const runner = new JMeterRunner({ commandClient: {}, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30,
      heartbeatSigningKey: 'a'.repeat(32), controlPlaneUrl: 'http://platform.test', maxArchiveBytes: 1024 },
    logger: { info() {}, warn() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z'),
    fetcher: async (url, options) => { requests.push({ url, options }); return { ok: true } }, spawn: () => { throw new Error('not used') },
  })
  await runner.archiveWorkloadArtifacts({ task: task, workload: { resultsPath, logPath } })
  assert.equal(requests.length, 2)
  assert.equal(requests[0].url, 'http://platform.test/internal/performance-runner/v1/artifacts')
  assert.equal(requests[0].options.headers['X-TestHub-Artifact-Type'], 'JMETER_JTL')
  assert.equal(requests[1].options.headers['X-TestHub-Artifact-Type'], 'JMETER_LOG')
})

test('publishes bounded JMeter error attribution before the terminal event', async () => {
  const commands = []; const workDirectory = await mkdtemp(join(tmpdir(), 'testhub-jmeter-error-summary-'))
  const runner = new JMeterRunner({ commandClient: { sendCommand: async (command) => commands.push(command) }, readClient: {}, cancelReadClient: {},
    config: { runnerId: 'jmeter-runner-test', heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30 },
    logger: { info() {}, warn() {}, error() {} }, now: () => new Date('2026-07-19T08:00:00.000Z'),
    spawn: () => { throw new Error('not used') },
  })
  await runner.finishTask({ task, workload: { root: workDirectory, resultsPath: join(workDirectory, 'results.jtl'), logPath: join(workDirectory, 'jmeter.log') },
    metrics: { refresh: async () => [], errorSummaries: () => [{ threadGroupName: '订单主链路', responseCode: '503', errorCount: 2 }] },
    cancelled: false, finalized: false, metricTimer: undefined }, 0, undefined)
  const errorIndex = commands.findIndex((command) => command[1] === ERROR_STREAM)
  const terminalIndex = commands.findIndex((command) => command.includes('JMETER_PROCESS_SUCCEEDED'))
  assert.ok(errorIndex >= 0)
  assert.ok(terminalIndex > errorIndex)
  assert.equal(commands[errorIndex].includes('503'), true)
})

test('cancellation kills a launcher and its child, escalating when SIGTERM is ignored', { skip: process.platform === 'win32', timeout: 12000 }, async () => {
  const { spawn } = await import('node:child_process')
  const child = spawn('/bin/sh', ['-c', "trap '' TERM; sleep 60 & echo ready; wait"], { detached: true, stdio: ['ignore', 'pipe', 'pipe'] })
  await new Promise(resolve => child.stdout.once('data', resolve))
  const runner = new JMeterRunner({ commandClient: {}, readClient: {}, cancelReadClient: {}, config: {} })
  const running = { child, finalized: false }
  const closed = new Promise(resolve => child.once('close', (code, signal) => { running.finalized = true; resolve(signal) }))
  try {
    runner.stopProcess(running)
    assert.equal(await closed, 'SIGKILL')
  } finally {
    try { process.kill(-child.pid, 'SIGKILL') } catch (error) { assert.equal(error.code, 'ESRCH') }
  }
})

test('shutdown during runtime configuration download cannot spawn a late JVM', async () => {
  let finishDownload
  const runner = new JMeterRunner({ commandClient: {}, readClient: {}, cancelReadClient: {}, config: {},
    spawn: () => { throw new Error('must not spawn after shutdown') } })
  runner.requestRuntimeConfiguration = () => new Promise(resolve => { finishDownload = resolve })
  const starting = runner.startJmeterTask(task)
  runner.stopping = true
  finishDownload({ threadGroups: [] })
  await starting
  assert.equal(runner.runningTasks.size, 0)
  await runner.handleEntry({ id: 'unread', fields: task })
})

test('shutdown waits for report archival even after the JVM has exited', async () => {
  const closed = [], workDirectory = await mkdtemp(join(tmpdir(), 'testhub-jmeter-drain-'))
  const transaction = { set() { return this }, sAdd() { return this }, del() { return this }, sRem() { return this }, exec: async () => [] }
  const child = new EventEmitter(); child.stdout = new EventEmitter(); child.stderr = new EventEmitter(); child.exitCode = null
  const runner = new JMeterRunner({ commandClient: { sendCommand: async () => {}, multi: () => transaction, quit: async () => closed.push('command') },
    readClient: { quit: async () => closed.push('read') }, cancelReadClient: { quit: async () => closed.push('cancel') },
    config: { runnerId: 'archive-drain', heartbeatSigningKey: 'a'.repeat(32), heartbeatIntervalSeconds: 10, heartbeatTtlSeconds: 30, workDirectory, jmeterBin: 'jmeter' },
    spawn: () => child, logger: { info() {}, warn() {}, error() {} }, resourceSampler: async () => ({ cpuPercent: 0, residentMemoryBytes: 0 }) })
  runner.requestRuntimeConfiguration = async () => ({ threadGroups: [] })
  let beginArchive, finishArchive
  const archiving = new Promise(resolve => { beginArchive = resolve })
  runner.archiveWorkloadArtifacts = () => { beginArchive(); return new Promise(resolve => { finishArchive = resolve }) }
  await runner.startJmeterTask(task)
  child.exitCode = 0; child.emit('close', 0)
  await archiving
  const stopping = runner.stop()
  await new Promise(resolve => setTimeout(resolve, 10))
  assert.deepEqual(closed, [], 'transport must stay open while the result is being saved')
  finishArchive(); await stopping
  assert.equal(closed.length, 3)
})
