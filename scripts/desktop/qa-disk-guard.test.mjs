import assert from 'node:assert/strict'
import test from 'node:test'
import { setImmediate, setTimeout as delay } from 'node:timers/promises'
import { assertQaDiskSpace, watchQaDiskSpace, QA_START_FREE_BYTES, QA_STOP_FREE_BYTES } from './qa-disk-guard.mjs'

async function waitForCheck(check, description) {
  const controller = new globalThis.AbortController()
  try {
    // The monitor unrefs its timer, so the test needs a referenced deadline.
    const deadline = delay(2000, undefined, { ref: true, signal: controller.signal }).then(() => {
      throw new Error(`Timed out waiting for ${description}`)
    })
    return await Promise.race([check, deadline])
  } finally {
    controller.abort()
  }
}

test('refuses acceptance before writes when free space is below the start reserve', async () => {
  await assert.rejects(assertQaDiskSpace('.', undefined, async () => QA_START_FREE_BYTES - 1), /free disk space/)
  await assert.doesNotReject(assertQaDiskSpace('.', undefined, async () => QA_START_FREE_BYTES))
})

test('disk exhaustion stops only the registered QA job once', async t => {
  let calls = 0
  let failed
  const failure = new Promise(resolve => { failed = resolve })
  const stop = watchQaDiskSpace('.', error => { calls++; failed(error) },
    { intervalMs: 5, readFreeBytes: async () => QA_STOP_FREE_BYTES - 1 })
  t.after(stop)
  assert.match((await waitForCheck(failure, 'the low disk space callback')).message, /free disk space/)
  await delay(30)
  assert.equal(calls, 1)
})

test('a stopped monitor cannot abort cleanup when an in-flight check completes', async t => {
  let finish, started, calls = 0
  const ready = new Promise(resolve => { started = resolve })
  const stop = watchQaDiskSpace('.', () => { calls++ }, { intervalMs: 5,
    readFreeBytes: () => new Promise(resolve => { finish = resolve; started() }) })
  t.after(stop)
  await waitForCheck(ready, 'the disk space read to start')
  stop()
  finish(0)
  await setImmediate()
  assert.equal(calls, 0)
})

test('an unreadable filesystem also stops the QA job', async t => {
  let failed
  const failure = new Promise(resolve => { failed = resolve })
  const stop = watchQaDiskSpace('.', failed, { intervalMs: 5, readFreeBytes: async () => { throw new Error('filesystem unavailable') } })
  t.after(stop)
  assert.match((await waitForCheck(failure, 'the filesystem error callback')).message, /filesystem unavailable/)
})
