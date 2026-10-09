import assert from 'node:assert/strict'
import test from 'node:test'
import { loadConfig } from '../src/config.js'

test('requires a heartbeat signing key before a Runner can call the control plane', () => {
  assert.throws(() => loadConfig({ TESTHUB_CONTROL_PLANE_URL: 'http://platform.test' }), /RUNNER_HEARTBEAT_KEY/)
  const config = loadConfig({
    TESTHUB_CONTROL_PLANE_URL: 'https://platform.test', RUNNER_HEARTBEAT_KEY: 'a'.repeat(32),
  })
  assert.equal(config.controlPlaneUrl, 'https://platform.test')
  assert.equal(config.heartbeatSigningKey, 'a'.repeat(32))
})

test('requires explicit network origins before bounded HTTP execution can be enabled', () => {
  assert.throws(() => loadConfig({
    TESTHUB_CONTROL_PLANE_URL: 'https://platform.test', RUNNER_HEARTBEAT_KEY: 'a'.repeat(32),
    RUNNER_HTTP_EXECUTION_ENABLED: 'true',
  }), /RUNNER_ALLOWED_ORIGINS/)
  const config = loadConfig({
    TESTHUB_CONTROL_PLANE_URL: 'https://platform.test', RUNNER_HEARTBEAT_KEY: 'a'.repeat(32),
    RUNNER_HTTP_EXECUTION_ENABLED: 'true', RUNNER_ALLOWED_ORIGINS: 'https://api.example.test',
  })
  assert.equal(config.httpExecutionEnabled, true)
  assert.deepEqual(config.allowedHttpOrigins, ['https://api.example.test'])
})

test('private network access is opt-in and requires desktop transport credentials', () => {
  assert.equal(loadConfig({}).allowPrivateNetwork, false)
  assert.throws(() => loadConfig({ RUNNER_DESKTOP_NETWORK_ACCESS: 'true' }), /桌面/)
  assert.equal(loadConfig({ RUNNER_DESKTOP_NETWORK_ACCESS: 'true', TESTHUB_DESKTOP_BROKER_URL: 'http://127.0.0.1:3000', TESTHUB_DESKTOP_BROKER_TOKEN: 'test-only' }).allowPrivateNetwork, true)
})
