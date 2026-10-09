import assert from 'node:assert/strict'
import test from 'node:test'
import { loadConfig } from '../src/config.js'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

test('native paths accept spaces and Unicode, reject controls and require an absolute Java home', () => {
  const home = join(tmpdir(), 'TestHub 中文 用户')
  const config = loadConfig({ JMETER_JAVA_BIN: join(home, 'java'), JMETER_HOME: home, RUNNER_WORK_DIRECTORY: join(home, 'work') })
  assert.equal(config.javaBin, join(home, 'java'))
  assert.equal(config.workDirectory, join(home, 'work'))
  assert.throws(() => loadConfig({ JMETER_JAVA_BIN: 'relative-java', JMETER_HOME: home }), /绝对路径/)
  assert.throws(() => loadConfig({ RUNNER_WORK_DIRECTORY: 'bad\npath' }), /格式不合法/)
})
test('desktop disables redirects and enterprise configuration retains defaults', () => {
  assert.equal(loadConfig({}).followRedirects, true)
  assert.equal(loadConfig({ TESTHUB_DESKTOP_BROKER_URL: 'http://127.0.0.1:1234' }).followRedirects, false)
})
