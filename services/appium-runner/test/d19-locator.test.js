import test from 'node:test'
import assert from 'node:assert/strict'
import { runtime } from '../src/protocol.js'
import { AppiumRunner } from '../src/runner.js'

const task = { contract: 'testhub.appium-runner.task', protocolVersion: '1', taskId: '01KX8XJTSHD5QG60TYT3JV48TW', executionId: '01KX8XJTSHD5QG60TYT3JV48TX', organizationId: '01KX8XJTSHD5QG60TYT3JV48TY', projectId: '01KX8XJTSHD5QG60TYT3JV48TZ', scenarioId: '01KX8XJTSHD5QG60TYT3JV48TV', scenarioVersion: '1', requestedAt: '2026-07-24T00:00:00.000Z', deadlineAt: '2026-07-24T00:05:00.000Z', manifestRef: 'mobile-automation/manifests/01KX8XJTSHD5QG60TYT3JV48TX/v1.json', traceId: '01KX8XJTSHD5QG60TYT3JV48TW' }
const make = (platform, locatorStrategy) => ({ contract: 'testhub.appium-runner.runtime-configuration', version: 1, taskId: task.taskId, executionId: task.executionId, deadlineAt: '2099-01-01T00:00:00Z', manifest: { device: { platform }, application: {}, scenario: { variables: {}, steps: [{ id: 'tap', type: 'TAP', name: 'tap', locatorStrategy, locatorValue: 'PRIVATE_LOCATOR', timeoutMillis: 1000 }] } } })
test('R01 accepts eight platform/locator combinations', () => {
  for (const platform of ['ANDROID', 'IOS']) for (const strategy of ['ACCESSIBILITY_ID', 'ID', 'XPATH', platform === 'ANDROID' ? 'ANDROID_UIAUTOMATOR' : 'IOS_PREDICATE']) assert.equal(runtime(task, make(platform, strategy)).manifest.device.platform, platform)
})
test('R02 rejects invalid platform and locator before session operations', () => {
  for (const platform of ['ANDROID', 'IOS']) for (const strategy of [platform === 'ANDROID' ? 'IOS_PREDICATE' : 'ANDROID_UIAUTOMATOR', 'UNKNOWN', '', null, undefined]) assert.throws(() => runtime(task, make(platform, strategy)), { code: 'INVALID_APPIUM_PROTOCOL' })
  for (const platform of ['', 'UNKNOWN', null, undefined]) assert.throws(() => runtime(task, make(platform, 'ID')), { code: 'INVALID_APPIUM_PROTOCOL' })
  for (const value of ['', null, undefined, {}]) { const v = make('ANDROID', 'ID'); v.manifest.scenario.steps[0].locatorValue = value; assert.throws(() => runtime(task, v), { code: 'INVALID_APPIUM_PROTOCOL' }) }
})
test('R03 keeps non-locator steps and D16 defaults unchanged', () => {
  for (const type of ['LAUNCH', 'WAIT', 'SCREENSHOT']) { const v = make('ANDROID', 'ID'); v.manifest.device = {}; v.manifest.scenario.steps = [{ type, timeoutMillis: null }]; assert.equal(runtime(task, v), v) }
})
test('R04 actual handle rejects crossed locators without Appium session requests', async () => {
  for (const platform of ['ANDROID', 'IOS']) {
    const calls = [], events = [], config = { runnerId: 'qa', executionEnabled: true, maxConcurrentTasks: 1, controlPlaneUrl: 'https://control.test', heartbeatSigningKey: 'qa-fixture', heartbeatIntervalSeconds: 60, heartbeatTtlSeconds: 120 }
    const body = make(platform, platform === 'ANDROID' ? 'IOS_PREDICATE' : 'ANDROID_UIAUTOMATOR')
    const runner = new AppiumRunner({ config, commandClient: {}, fetcher: async url => { calls.push(url); return globalThis.Response.json({ data: body }) } })
    runner.publish = async e => events.push(e); runner.ack = async () => {}; runner.sendHeartbeat = async () => {}
    await runner.handle({ id: '1-0', fields: task }); assert.equal(calls.length, 1); assert.match(calls[0], /runtime-configuration/); assert.equal(events.at(-1).type, 'FAILED'); assert.equal(events.at(-1).code, 'APPIUM_EXECUTION_FAILED'); assert.ok(!events.at(-1).message.includes('PRIVATE_LOCATOR'))
  }
})
