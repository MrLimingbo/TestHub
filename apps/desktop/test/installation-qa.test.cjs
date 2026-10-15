'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const os = require('node:os')
const fs = require('node:fs/promises')
const path = require('node:path')
const { appData, sessionDirectory, createSession, verifySession, removeSession } = require('../build/installation-qa.cjs')
test('native app data uses the OS location, including spaces and Unicode', () => {
  assert.equal(appData('darwin', {}, '/Users/验收 user'), '/Users/验收 user/Library/Application Support')
  assert.equal(appData('win32', { APPDATA: 'C:\\Users\\验收 user\\AppData\\Roaming' }), 'C:\\Users\\验收 user\\AppData\\Roaming')
  assert.throws(() => appData('win32', { APPDATA: '../user-data' }), /native/)
})
test('installation sessions cannot address an existing application name or a traversal path', () => {
  for (const qaName of ['testhub-desktop-app', '../TestHub', 'TestHub-QA-../user']) assert.throws(() => sessionDirectory({ version: 1, qaName }), /Invalid/)
})
test('exact defaults require a clean hosted runner and refuse existing application data', async () => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'installation-default-test-'))
  const options = { platform: 'darwin', env: { GITHUB_ACTIONS: 'true', RUNNER_ENVIRONMENT: 'github-hosted' }, home }
  const directory = path.join(appData('darwin', {}, home), 'testhub-desktop-app')
  try {
    await assert.rejects(createSession(true, { ...options, env: {} }), /fresh hosted runner/)
    await fs.mkdir(directory, { recursive: true })
    await fs.writeFile(path.join(directory, 'preserve.txt'), 'existing user data')
    await assert.rejects(createSession(true, options), { code: 'EEXIST' })
    assert.equal(await fs.readFile(path.join(directory, 'preserve.txt'), 'utf8'), 'existing user data')
  } finally { await fs.rm(home, { recursive: true }) }
})
test('cleanup refuses data without this session ownership token', async () => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'installation-qa-test-'))
  const options = { platform: 'darwin', env: {}, home }
  await fs.mkdir(appData('darwin', {}, home), { recursive: true })
  const record = { version: 1, qaName: 'TestHub-QA-' + crypto.randomUUID(), token: crypto.randomBytes(32).toString('hex'), cleanRunnerDefaults: false }
  const directory = sessionDirectory(record, options)
  await fs.mkdir(directory, { mode: 0o700 })
  try {
    await fs.writeFile(path.join(directory, '.testhub-install-qa-owner'), 'another acceptance session')
    await fs.writeFile(path.join(directory, 'preserve.txt'), 'must survive')
    await assert.rejects(verifySession(record, options), /not owned/)
    await assert.rejects(removeSession(record, options), /not owned/)
    assert.equal(await fs.readFile(path.join(directory, 'preserve.txt'), 'utf8'), 'must survive')
  } finally { await fs.rm(home, { recursive: true }) }
})
