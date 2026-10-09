import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { JmeterMetrics, createJmx, jmeterArguments, prepareWorkDirectory } from '../src/jmeter.js'

const artifact = { threadGroups: [{ name: '订单主链路', targetUrl: 'https://perf.example.test/orders/${orderId}',
  loadModel: 'RAMP_UP', threadCount: 3, rampUpSeconds: 2, durationSeconds: 5, targetTps: 7,
  requestMethod: 'POST', requestHeaders: { 'Content-Type': 'application/json', 'X-Trace': 'jmeter-test' },
  queryParameters: { coupon: '${orderId}', page: '1' },
  requestBody: '{"sku":"${orderId}","quantity":${quantity}}', expectedStatusCode: 201, responseContains: 'order-${orderId}',
  dataSetCsv: 'orderId,quantity\nA-100,1\nB-200,2', rendezvousUsers: 3 },
  { name: '阶梯检索', targetUrl: 'https://perf.example.test/orders/search', loadModel: 'STEPPED_RAMP', threadCount: 7,
    rampUpSeconds: 0, stepCount: 3, stepIntervalSeconds: 10, durationSeconds: 5, targetTps: 9,
    requestMethod: 'GET', requestHeaders: {}, queryParameters: {}, requestBody: null, expectedStatusCode: 200,
    responseContains: null, dataSetCsv: null, rendezvousUsers: null }] }

test('generates a non-GUI JMeter plan with bounded thread group settings', () => {
  const jmx = createJmx(artifact)
  assert.match(jmx, /ThreadGroup.num_threads">3/)
  assert.match(jmx, /HTTPSampler.domain">perf.example.test/)
  assert.match(jmx, /HTTPsampler.Arguments/)
  assert.match(jmx, /HTTPSampler.method">POST/)
  assert.match(jmx, /HTTPSampler.postBodyRaw">true/)
  assert.match(jmx, /HeaderManager/)
  assert.match(jmx, /Header.name">Content-Type/)
  assert.match(jmx, /Assertion.response_code/)
  assert.match(jmx, /Assertion.response_data/)
  assert.match(jmx, /orderId/)
  assert.match(jmx, /CSVDataSet/)
  assert.match(jmx, /filename">dataset-1\.csv/)
  assert.match(jmx, /HTTPSampler.path">\/orders\/\$\{orderId}/)
  assert.match(jmx, /HTTPSampler.path">\/orders\/\$\{orderId}\?coupon=\$\{orderId}&amp;page=1/)
  assert.match(jmx, /testclass="SyncTimer"/)
  assert.match(jmx, /groupSize">3/)
  assert.match(jmx, /timeoutInMs">30000/)
  assert.match(jmx, /testname="阶梯检索 · 阶段 1\/3"/)
  assert.match(jmx, /testname="阶梯检索 · 阶段 3\/3"/)
  assert.match(jmx, /ThreadGroup.num_threads">3/)
  assert.match(jmx, /ThreadGroup.num_threads">2/)
  assert.match(jmx, /ThreadGroup.delay">10/)
  assert.match(jmx, /ThreadGroup.delay">20/)
  assert.match(jmx, /ThreadGroup.duration">25/)
  assert.match(jmx, /ThreadGroup.duration">15/)
  assert.match(jmx, /HTTPSampler.contentEncoding/)
  assert.match(jmx, /throughput<\/name><value>420/)
  assert.match(jmx, /<intProp name="calcMode">2<\/intProp>/)
  assert.deepEqual(jmeterArguments({ planPath: '/tmp/plan.jmx', resultsPath: '/tmp/results.jtl', logPath: '/tmp/jmeter.log' }).slice(0, 6),
    ['-n', '-t', '/tmp/plan.jmx', '-l', '/tmp/results.jtl', '-j'])
})

test('writes each inline CSV data set into the isolated execution directory', async () => {
  const root = await mkdtemp(join(tmpdir(), 'testhub-jmeter-data-set-'))
  try {
    const workload = await prepareWorkDirectory({ workDirectory: root }, { executionId: '01J00000000000000000000001' }, artifact)
    assert.equal(await readFile(join(workload.root, 'dataset-1.csv'), 'utf8'), artifact.threadGroups[0].dataSetCsv)
    assert.match(await readFile(workload.planPath, 'utf8'), /CSVDataSet/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('aggregates JTL records into total and thread-group metric snapshots', async () => {
  const root = await mkdtemp(join(tmpdir(), 'testhub-jmeter-metrics-'))
  const resultsPath = join(root, 'results.jtl')
  await writeFile(resultsPath, 'timeStamp,elapsed,label,responseCode,success,allThreads\n1710000000000,10,订单主链路,200,true,2\n1710000000100,30,订单主链路,500,false,2\n')
  const metrics = new JmeterMetrics({ resultsPath, startedAt: 1710000000000 })
  const snapshots = await metrics.refresh()
  const total = snapshots.find((item) => item.threadGroupName === '__TOTAL__')
  assert.deepEqual({ sampleCount: total.sampleCount, successCount: total.successCount, errorCount: total.errorCount,
    averageLatencyMillis: total.averageLatencyMillis, p95LatencyMillis: total.p95LatencyMillis, minLatencyMillis: total.minLatencyMillis,
    maxLatencyMillis: total.maxLatencyMillis }, { sampleCount: 2, successCount: 1, errorCount: 1, averageLatencyMillis: 20,
    p95LatencyMillis: 30, minLatencyMillis: 10, maxLatencyMillis: 30 })
  assert.deepEqual(metrics.errorSummaries(), [{ threadGroupName: '订单主链路', responseCode: '500', errorCount: 1 }])
})

test('desktop plans do not follow redirects while enterprise plans preserve their behavior', () => {
  assert.match(createJmx(artifact), /HTTPSampler.follow_redirects">true/)
  const local = createJmx(artifact, { followRedirects: false })
  assert.doesNotMatch(local, /HTTPSampler.follow_redirects">true/)
  assert.match(local, /HTTPSampler.follow_redirects">false/)
})
