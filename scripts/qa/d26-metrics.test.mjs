import test from 'node:test'
import assert from 'node:assert/strict'
import { cpuSeconds, parsePs, ownedTree, intervalCpu, distribution, trend, completeness, sequence, QuietGate } from './d26-metrics.mjs'
test('macOS cumulative CPU formats and interval percentages', () => {
  assert.equal(cpuSeconds('2:03.50'), 123.5); assert.equal(cpuSeconds('1:02:03'), 3723); assert.equal(cpuSeconds('2-01:02:03.5'), 176523.5)
  assert.equal(intervalCpu({ cpuSeconds: 110 }, { cpuSeconds: 100 }, 5), 200)
  assert.equal(intervalCpu({ cpuSeconds: 1 }, { cpuSeconds: 2 }, 5), null)
  assert.throws(() => cpuSeconds('bad'))
})
test('process tree excludes unrelated processes and tolerates executable spaces', () => {
  const rows = parsePs('1 0 20 0:00.01 /app main\n2 1 30 0:00.02 /child\n3 0 40 0:00.03 /unrelated')
  assert.deepEqual(ownedTree(rows, 1).map(x => x.pid), [1, 2])
})
test('nearest-rank tails retain errors and expose insufficient samples without compliance', () => {
  const d = distribution([1, 2, 3, 10000]); assert.equal(d.p95, 10000); assert.equal(d.p99SampleSufficient, false); assert.equal(d.complianceAssessed, false)
  assert.equal(distribution(Array(100).fill(1)).p95SampleSufficient, true)
})
test('slope is per minute and missing mandatory slots cannot be filled by duplicates', () => {
  assert.equal(trend([[0, 1], [60, 3], [120, 5]]).slopePerMinute, 2)
  const all = Array.from({ length: 1800 }, (_, i) => ({ elapsedSeconds: (i + 1) * 5, mandatoryValid: true }))
  assert.equal(completeness(all, 9000).valid, true)
  const gap = all.filter(x => x.elapsedSeconds < 100 || x.elapsedSeconds >= 160)
  assert.equal(completeness(gap, 9000).valid, false)
  assert.equal(completeness([...gap, ...gap], 9000).valid, false)
})
test('approved 100-op cycle remains bounded with five paired plan creates/deletes', () => {
  assert.equal(sequence.length, 100); let plans = 20
  for (const op of sequence) { if (op === 'planCreate') plans++; if (op === 'planDelete') plans--; assert.ok(plans >= 20 && plans <= 25) }
  assert.equal(plans, 20); assert.equal(sequence.filter(x => x === 'taskUpdate').length, 10)
})
test('quiet admission requires continuous 120 seconds below the unchanged 25 percent threshold', () => {
  const gate = new QuietGate()
  for (let at = 0; at < 120; at += 5) assert.equal(gate.observe(at, 24.9).ready, false)
  assert.equal(gate.observe(120, 24.9).ready, true)
  assert.equal(gate.observe(125, 25).ready, false)
  assert.equal(gate.observe(130, 1).quietSeconds, 0)
  assert.equal(gate.observe(190, 1).quietSeconds, 0)
  assert.equal(gate.observe(195, null).quietSeconds, 0)
})
