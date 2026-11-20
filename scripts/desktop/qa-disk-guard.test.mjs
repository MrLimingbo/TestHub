import assert from 'node:assert/strict'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { assertQaDiskSpace, watchQaDiskSpace, QA_START_FREE_BYTES, QA_STOP_FREE_BYTES } from './qa-disk-guard.mjs'

// The production monitor uses an unref'ed timer. Hold the test open until its first check.
async function waitForCheck(check) {
  const controller = new globalThis.AbortController()
  try {
    const deadline = delay(2000, undefined, { signal: controller.signal }).then(() => {
      throw new Error('Disk monitor did not run before the test deadline')
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

test('disk exhaustion stops only the registered QA job once', { timeout: 2000 }, async t => {
  let calls = 0
  let failed
  const failure = new Promise(resolve => { failed = resolve })
  const stop = watchQaDiskSpace('.', error => { calls++; failed(error) },
    { intervalMs: 5, readFreeBytes: async () => QA_STOP_FREE_BYTES - 1 })
  t.after(stop)
  assert.match((await waitForCheck(failure)).message, /free disk space/)
  await delay(30)
  assert.equal(calls, 1)
})

test('a stopped monitor cannot abort cleanup when an in-flight check completes', async t => {
  let finish, started, calls = 0
  const ready = new Promise(resolve => { started = resolve })
  const stop = watchQaDiskSpace('.', () => { calls++ }, { intervalMs: 5,
    readFreeBytes: () => new Promise(resolve => { finish = resolve; started() }) })
  t.after(stop)
  await waitForCheck(ready)
  stop()
  finish(0)
  await delay(20)
  assert.equal(calls, 0)
})

test('an unreadable filesystem also stops the QA job', { timeout: 2000 }, async t => {
  let failed
  const failure = new Promise(resolve => { failed = resolve })
  const stop = watchQaDiskSpace('.', failed, { intervalMs: 5, readFreeBytes: async () => { throw new Error('filesystem unavailable') } })
  t.after(stop)
  assert.match((await waitForCheck(failure)).message, /filesystem unavailable/)
})
