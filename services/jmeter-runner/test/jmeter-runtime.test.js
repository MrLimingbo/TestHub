import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { clearTimeout } from 'node:timers'
import { jmeterArguments, prepareWorkDirectory, JmeterMetrics } from '../src/jmeter.js'

test('real JMeter loads TPS, CSV and assertions and records successful HTTP samples', { skip: !process.env.JMETER_BIN, timeout: 30000 }, async () => {
  const requests = []
  const server = createServer((req, res) => { requests.push(req.url); res.end('ok A-1') })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const root = await mkdtemp(join(tmpdir(), 'testhub-jmeter-runtime-'))
  try {
    const workload = await prepareWorkDirectory({ workDirectory: root }, { executionId: '01J00000000000000000000001' }, {
      threadGroups: [{ name: 'Runtime TPS', targetUrl: `http://127.0.0.1:${server.address().port}/item/\${id}`,
        loadModel: 'STEADY', threadCount: 1, rampUpSeconds: 0, durationSeconds: 3, targetTps: 10,
        dataSetCsv: 'id\nA-1', expectedStatusCode: 200, responseContains: 'ok A-1' },
        { name: 'Independent TPS', targetUrl: `http://127.0.0.1:${server.address().port}/item/A-1`,
          loadModel: 'STEADY', threadCount: 1, rampUpSeconds: 0, durationSeconds: 3, targetTps: 10, expectedStatusCode: 200 }],
    })
    const code = await new Promise((resolve, reject) => {
      const child = spawn(process.env.JMETER_BIN, jmeterArguments(workload), { cwd: workload.root,
        env: { ...process.env, JVM_ARGS: '-Xms64m -Xmx256m -Djava.awt.headless=true' }, stdio: 'ignore' })
      child.once('error', reject); child.once('exit', resolve)
    })
    assert.equal(code, 0, await readFile(workload.logPath, 'utf8'))
    assert.ok(requests.length >= 45, `Both groups should sustain independent TPS, received ${requests.length} samples`)
    assert.ok(requests.every(url => url === '/item/A-1'))
    const snapshots = await new JmeterMetrics({ resultsPath: workload.resultsPath, startedAt: Date.now() - 2000 }).refresh()
    const total = snapshots.find(metric => metric.threadGroupName === '__TOTAL__')
    assert.equal(total.errorCount, 0)
    assert.equal(total.sampleCount, requests.length)
  } finally {
    await new Promise(resolve => server.close(resolve))
    await rm(root, { recursive: true, force: true })
  }
})

test('real JMeter never follows an approved target to another origin', { skip: !process.env.JMETER_BIN, timeout: 30000 }, async () => {
  let targetHits = 0, sourceHits = 0
  const target = createServer((_req, res) => { targetHits++; res.end('must not be reached') })
  await new Promise(resolve => target.listen(0, '127.0.0.1', resolve))
  const source = createServer((_req, res) => { sourceHits++; res.writeHead(302, { Location: `http://127.0.0.1:${target.address().port}/private` }); res.end() })
  await new Promise(resolve => source.listen(0, '127.0.0.1', resolve))
  const root = await mkdtemp(join(tmpdir(), 'testhub-jmeter-redirect-'))
  try {
    const workload = await prepareWorkDirectory({ workDirectory: root, followRedirects: true }, { executionId: '01J00000000000000000000001' }, {
      threadGroups: [{ name: 'Redirect boundary', targetUrl: `http://127.0.0.1:${source.address().port}/start`,
        loadModel: 'STEADY', threadCount: 1, rampUpSeconds: 0, durationSeconds: 1, targetTps: 2 }],
    })
    const code = await new Promise((resolve, reject) => {
      const child = spawn(process.env.JMETER_BIN, jmeterArguments(workload), { cwd: workload.root,
        env: { ...process.env, JVM_ARGS: '-Xms64m -Xmx256m -Djava.awt.headless=true' }, stdio: 'ignore' })
      const timer = setTimeout(() => child.kill('SIGKILL'), 25000)
      child.once('error', error => { clearTimeout(timer); reject(error) })
      child.once('exit', value => { clearTimeout(timer); resolve(value) })
    })
    assert.equal(code, 0)
    assert.ok(sourceHits > 0)
    assert.equal(targetHits, 0)
  } finally {
    await new Promise(resolve => source.close(resolve)); await new Promise(resolve => target.close(resolve))
    await rm(root, { recursive: true, force: true })
  }
})
