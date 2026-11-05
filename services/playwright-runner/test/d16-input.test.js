import assert from 'node:assert/strict'
import test from 'node:test'
import { validateRuntime } from '../src/protocol.js'
import { PlaywrightRunner } from '../src/runner.js'
const task = { taskId: '01J00000000000000000000001', executionId: '01J00000000000000000000002' }
const profile = { browser: 'chromium', baseUrl: 'http://127.0.0.1:18081', viewportWidth: 800, viewportHeight: 600 }
const step = { id: 'fill', type: 'FILL', name: 'Fill', selector: '#input', value: '001', timeoutMillis: 1000 }
const artifact = (browser = profile, steps = [step], variables = {}) => ({ contract: 'testhub.playwright-runner.runtime-configuration', version: 1, ...task, browser, scenario: { failureStrategy: 'STOP', variables, steps } })
const validate = value => validateRuntime(task, value, ['http://127.0.0.1:18081'])
test('TC-D16WEB-52: viewport type and range fail pure preflight', () => {
  for (const field of ['viewportWidth', 'viewportHeight']) for (const value of [800.5, '800', true, [], {}, null, 319, field === 'viewportWidth' ? 3841 : 2161]) assert.throws(() => validate(artifact({ ...profile, [field]: value })))
})
test('TC-D16WEB-53: WAIT and action timeouts have distinct required and default rules', () => {
  for (const type of ['WAIT', 'FILL']) for (const value of [1000.5, '1000', true, [], {}, 60001, type === 'WAIT' ? 0 : 99]) assert.throws(() => validate(artifact(profile, [{ ...step, type, timeoutMillis: value }])))
  assert.throws(() => validate(artifact(profile, [{ ...step, type: 'WAIT', timeoutMillis: null }])))
  assert.doesNotThrow(() => validate(artifact(profile, [{ ...step, timeoutMillis: null }])))
})
test('TC-D16WEB-54: variables and selected text fields require strings', () => {
  for (const value of [1, true, [], {}]) {
    assert.throws(() => validate(artifact(profile, [step], { id: value })))
    for (const field of ['value', 'expectedText']) assert.throws(() => validate(artifact(profile, [{ ...step, [field]: value }])))
  }
  assert.doesNotThrow(() => validate(artifact(profile, [step], { id: null })))
})
test('TC-D16WEB-55: canonical exact numbers and original optional defaults remain', () => {
  for (const raw of ['800', '800.0', '8e2']) { const r = validate(artifact({ ...profile, viewportWidth: JSON.parse(raw) }, [{ ...step, timeoutMillis: null }], { id: '001', literal: 'null' })); assert.equal(r.browser.viewportWidth, 800); assert.equal(r.scenario.variables.id, '001'); assert.equal(r.scenario.steps[0].timeoutMillis, null) }
})
test('TC-D16WEB-56: run rejects bad runtime before preparing a browser', async () => {
  let prepared = 0
  const runner = new PlaywrightRunner({ commandClient: {}, readClient: {}, cancelClient: {}, config: { runnerId: 'qa', controlPlaneUrl: 'http://127.0.0.1:18081', heartbeatSigningKey: 'fixture-key', allowedTargetOrigins: ['http://127.0.0.1:18081'] }, fetcher: async () => new globalThis.Response(JSON.stringify({ data: artifact({ ...profile, viewportWidth: 800.5 }) })), prepareBrowser: async () => { prepared++ }, logger: { info() {}, warn() {}, error() {} } })
  const active = { task, controller: new globalThis.AbortController(), browser: null, context: null, cancelled: false }
  runner.prepareBrowser = async () => { prepared++ }
  await assert.rejects(() => runner.run(task, active)); assert.equal(prepared, 0); assert.equal(active.root, undefined)
})
