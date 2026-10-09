'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const test = require('node:test')
const { loadSecrets, loadBundle } = require('../app/src/local-runtime.cjs')
const { readPreferences, writePreferences, selectLocalWorkspace } = require('../app/src/desktop-core.cjs')
const key = crypto.randomBytes(32)
const codec = {
  encrypt(value) {
    const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
    return Buffer.concat([iv, cipher.update(value, 'utf8'), cipher.final(), cipher.getAuthTag()])
  },
  decrypt(bytes) {
    const cipher = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12))
    cipher.setAuthTag(bytes.subarray(-16))
    return Buffer.concat([cipher.update(bytes.subarray(12, -16)), cipher.final()]).toString('utf8')
  },
}
async function temp(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-runtime-unit-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  return directory
}
test('encrypted workspace keys survive reopening without plaintext storage', async t => {
  const directory = await temp(t), first = await loadSecrets(directory, codec)
  const saved = await fs.readFile(path.join(directory, 'workspace-secrets.enc'))
  assert.equal(saved.includes(first.databasePassword), false)
  assert.equal(saved.includes(first.encryptionKey), false)
  assert.deepEqual(await loadSecrets(directory, codec), first)
  if (process.platform !== 'win32') assert.equal((await fs.stat(path.join(directory, 'workspace-secrets.enc'))).mode & 0o777, 0o600)
})
test('missing or corrupt keys never overwrite an existing database or regenerate identity', async t => {
  const directory = await temp(t), database = path.join(directory, 'workspace.mv.db')
  await fs.writeFile(database, 'original-data')
  await assert.rejects(loadSecrets(directory, codec), /密钥文件缺失/)
  await fs.writeFile(path.join(directory, 'workspace-secrets.enc'), 'corrupt-key')
  await assert.rejects(loadSecrets(directory, codec), /无法解密/)
  assert.equal(await fs.readFile(database, 'utf8'), 'original-data')
  assert.equal(await fs.readFile(path.join(directory, 'workspace-secrets.enc'), 'utf8'), 'corrupt-key')
})
test('unavailable system encryption does not create a plaintext fallback', async t => {
  const directory = await temp(t)
  await assert.rejects(loadSecrets(directory, { encrypt: () => { throw new Error('locked') } }), /locked/)
  assert.deepEqual(await fs.readdir(directory), [])
})
test('runtime manifest rejects other architectures and paths outside the bundle', async t => {
  const directory = await temp(t)
  await fs.writeFile(path.join(directory, 'runtime.json'), JSON.stringify({ version: 1, platform: 'wrong', arch: process.arch }))
  await assert.rejects(loadBundle(directory), /架构不匹配/)
  await fs.writeFile(path.join(directory, 'runtime.json'), JSON.stringify({ version: 1, platform: process.platform, arch: process.arch, java: '../' }))
  await assert.rejects(loadBundle(directory), /超出安装目录/)
})
test('selecting local keeps a legacy server address and selecting server exits local mode', async t => {
  const directory = await temp(t)
  await writePreferences(directory, 'https://example.test')
  await selectLocalWorkspace(directory)
  assert.deepEqual(await readPreferences(directory), { mode: 'local', serverUrl: 'https://example.test' })
  await writePreferences(directory, 'https://next.example.test')
  assert.deepEqual(await readPreferences(directory), { serverUrl: 'https://next.example.test' })
})

test('release packaging refuses missing and unvalidated runtime bundles', async t => {
  const { verifyRuntime } = require('../build/skip-runtime-dependency-copy.cjs')
  const root = await temp(t)
  await assert.rejects(verifyRuntime(root, 'darwin', 'arm64'), /缺少/)
  const directory = path.join(root, 'darwin-arm64'); await fs.mkdir(directory)
  const manifest = { version: 1, platform: 'darwin', arch: 'arm64', distributionReady: false,
    java: 'java', node: 'node', backend: 'backend.jar', apiRunner: 'api.js', jmeterRunner: 'jmeter.js', jmeterWatchdog: 'watchdog.jar', playwrightRunner: 'browser.js', playwrightTool: 'tool.mjs', frontend: 'web' }
  await fs.writeFile(path.join(directory, 'runtime.json'), JSON.stringify(manifest))
  await assert.rejects(verifyRuntime(root, 'darwin', 'arm64'), /分发验证/)
  for (const file of ['java', 'node', 'backend.jar', 'api.js', 'jmeter.js', 'watchdog.jar', 'browser.js', 'tool.mjs']) await fs.writeFile(path.join(directory, file), 'test fixture')
  await fs.mkdir(path.join(directory, 'web'));await fs.writeFile(path.join(directory, 'web/index.html'), 'test fixture')
  await verifyRuntime(root, 'darwin', 'arm64', true)
  await assert.rejects(verifyRuntime(root, 'darwin', 'x64', true), /缺少/)
})
