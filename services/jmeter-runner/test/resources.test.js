import assert from 'node:assert/strict'
import test from 'node:test'
import { createProcessResourceSampler } from '../src/resources.js'

test('samples only a validated JMeter child PID through fixed ps arguments', async () => {
  const calls = []
  const sampler = createProcessResourceSampler({ execFile: (file, args, options, callback) => {
    calls.push({ file, args, options })
    callback(null, { stdout: '12.5 2048\n' })
  } })
  assert.deepEqual(await sampler(4812), { cpuPercent: 12.5, residentMemoryBytes: 2_097_152 })
  assert.deepEqual(calls[0].file, '/bin/ps')
  assert.deepEqual(calls[0].args, ['-o', '%cpu=,rss=', '-p', '4812'])
  await assert.rejects(sampler('4812'), /PID/)
})
