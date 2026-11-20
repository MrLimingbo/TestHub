import { statfs } from 'node:fs/promises'
import { setInterval, clearInterval } from 'node:timers'

export const QA_START_FREE_BYTES = 8 * 1024 ** 3
export const QA_STOP_FREE_BYTES = 2 * 1024 ** 3

async function freeBytes(directory) {
  const stats = await statfs(directory, { bigint: true })
  return Number(stats.bavail * stats.bsize)
}

export async function assertQaDiskSpace(directory, minimum = QA_START_FREE_BYTES, readFreeBytes = freeBytes) {
  const available = await readFreeBytes(directory)
  if (!Number.isFinite(available) || available < minimum) {
    throw new Error(`Isolated acceptance requires at least ${minimum / 1024 ** 3} GiB free disk space`)
  }
}

// Abort only the caller's QA work before host exhaustion can affect other services.
export function watchQaDiskSpace(directory, onFailure, { intervalMs = 30000, readFreeBytes = freeBytes } = {}) {
  let stopped = false, checking = false
  const stop = () => { stopped = true; clearInterval(timer) }
  const timer = setInterval(async () => {
    if (stopped || checking) return
    checking = true
    try {
      await assertQaDiskSpace(directory, QA_STOP_FREE_BYTES, readFreeBytes)
    } catch (error) {
      if (!stopped) { stop(); onFailure(error) }
    } finally { checking = false }
  }, intervalMs)
  timer.unref()
  return stop
}
