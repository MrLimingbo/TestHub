import assert from 'node:assert/strict'
import test from 'node:test'
import { createProcessResourceSampler } from '../src/resources.js'

test('samples the isolated JMeter process group including the Java child', async () => {
  const calls = []
  const sampler = createProcessResourceSampler({ execFile: (file, args, options, callback) => {
    calls.push({ file, args, options })
    callback(null, { stdout: '4812 0.5 1024\n4812 12.0 1024\n9999 99 9999\n' })
  } })
  assert.deepEqual(await sampler(4812), { cpuPercent: 12.5, residentMemoryBytes: 2_097_152 })
  assert.deepEqual(calls[0].file, '/bin/ps')
  assert.deepEqual(calls[0].args, ['-axo', 'pgid=,%cpu=,rss='])
  await assert.rejects(sampler('4812'), /PID/)
})
