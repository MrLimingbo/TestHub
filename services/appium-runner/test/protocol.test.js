import test from 'node:test'
import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { loadConfig } from '../src/config.js'
import { decodeTask, event, heartbeat } from '../src/protocol.js'
import { AppiumRunner } from '../src/runner.js'
const task = { contract: 'testhub.appium-runner.task', protocolVersion: '1', taskId: '01KX8XJTSHD5QG60TYT3JV48TW', executionId: '01KX8XJTSHD5QG60TYT3JV48TX', organizationId: '01KX8XJTSHD5QG60TYT3JV48TY', projectId: '01KX8XJTSHD5QG60TYT3JV48TZ', scenarioId: '01KX8XJTSHD5QG60TYT3JV48TV', scenarioVersion: '1', requestedAt: '2026-07-24T00:00:00.000Z', deadlineAt: '2026-07-24T00:05:00.000Z', manifestRef: 'mobile-automation/manifests/01KX8XJTSHD5QG60TYT3JV48TX/v1.json', traceId: '01KX8XJTSHD5QG60TYT3JV48TW' }
test('decodes a bounded mobile immutable task', () => assert.equal(decodeTask(task).executionId, task.executionId))
test('rejects task shape drift', () => assert.throws(() => decodeTask({ ...task, appiumUrl: 'http://unsafe.example' })))
test('keeps runner capacity between one and two slots', () => { assert.equal(loadConfig({ RUNNER_MAX_CONCURRENT_TASKS: '2' }).maxConcurrentTasks, 2); assert.throws(() => loadConfig({ RUNNER_MAX_CONCURRENT_TASKS: '3' })) })
test('emits bounded Appium runner event and heartbeat', () => { const config = loadConfig({ RUNNER_ID: 'appium-test' }); assert.equal(event(task, config.runnerId, 'REJECTED', 'APPIUM_EXECUTION_DISABLED', 'safe').type, 'REJECTED'); assert.equal(heartbeat(config, 0, new Date('2026-01-01T00:00:00Z')).runnerType, 'appium') })
test('uses only the configured device farm and archives a screenshot through the control plane', async () => {
  const commands = []; const client = { sendCommand: async (value) => { commands.push(value); return null }, multi: () => ({ set() { return this }, sAdd() { return this }, exec: async () => null }) }; const config = loadConfig({ RUNNER_ID: 'appium-driver-test', RUNNER_APPIUM_EXECUTION_ENABLED: 'true', TESTHUB_CONTROL_PLANE_URL: 'https://control.example.test', RUNNER_APPIUM_SERVER_URL: 'https://farm.example.test/wd/hub', RUNNER_APPIUM_ALLOWED_SERVER_URLS: 'https://farm.example.test' })
  const runtime = { data: { contract: 'testhub.appium-runner.runtime-configuration', version: 1, taskId: task.taskId, executionId: task.executionId, deadlineAt: '2099-01-01T00:00:00.000Z', manifest: { device: { platform: 'ANDROID', automationName: 'UiAutomator2', udid: 'emulator-5554', capabilities: { 'appium:platformVersion': '15' } }, application: { appIdentifier: 'io.testhub.demo' }, scenario: { failureStrategy: 'STOP', variables: {}, steps: [{ id: 'launch', type: 'LAUNCH', name: '启动', timeoutMillis: 1000 }, { id: 'shot', type: 'SCREENSHOT', name: '截图', timeoutMillis: 1000 }] } } } }
  const calls = []; const fetcher = async (url, init = {}) => { calls.push({ url, init }); if (url.includes('/runtime-configuration')) return globalThis.Response.json(runtime); if (url.endsWith('/session')) return globalThis.Response.json({ value: { sessionId: 'session-1' } }); if (url.includes('/screenshot')) return globalThis.Response.json({ value: Buffer.from('png').toString('base64') }); return globalThis.Response.json({ value: null }) }
  const runner = new AppiumRunner({ commandClient: client, readClient: client, cancelClient: client, config, fetcher, now: () => new Date('2026-01-01T00:00:00Z') })
  await runner.handle({ id: '1-0', fields: task })
  assert.ok(calls.every((call) => call.url.startsWith('https://control.example.test') || call.url.startsWith('https://farm.example.test/wd/hub')))
  assert.ok(calls.some((call) => call.url.endsWith('/artifacts'))); assert.ok(commands.some((command) => command[0] === 'XACK'))
})
