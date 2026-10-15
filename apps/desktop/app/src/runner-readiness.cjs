'use strict'
const { setTimeout: delay } = require('node:timers/promises')

async function waitForRunnerStatus({ origin, sessionKey, since, statusKey, label, checkRunning }) {
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    checkRunning()
    const signal = globalThis.AbortSignal.timeout(Math.max(1, Math.min(3000, deadline - Date.now())))
    let response
    // A transient loopback failure must not bypass the overall readiness deadline.
    try {
      response = await globalThis.fetch(origin + '/internal/desktop/v1/status?startedAfter=' + encodeURIComponent(since), {
        headers: { 'X-TestHub-Desktop-Session': sessionKey }, redirect: 'error', signal })
    } catch (error) {
      if (error.name !== 'TimeoutError' && error.name !== 'TypeError') throw error
    }
    if (response) {
      if (!response.ok) throw new Error(`${label}状态检查失败（HTTP ${response.status}）。`)
      let value
      try { value = await response.json() }
      catch (error) { if (!signal.aborted || signal.reason?.name !== 'TimeoutError') throw error }
      if (value !== undefined && value.data?.[statusKey] === true) return true
    }
    await delay(Math.max(0, Math.min(200, deadline - Date.now())))
  }
  return false
}
module.exports = { waitForRunnerStatus }
