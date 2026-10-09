'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { LocalEngines, JMETER, inventory } = require('../app/src/local-engines.cjs')

async function fixture(t, fetcher) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-engine-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  return new LocalEngines({ directory, bundle: {}, fetcher })
}
async function installedFixture(manager) {
  const container = path.join(manager.directory, JMETER.root), home = path.join(container, JMETER.root)
  await fs.mkdir(path.join(home, 'bin'), { recursive: true })
  await fs.writeFile(path.join(home, 'bin/ApacheJMeter.jar'), 'fixture jar')
  await fs.writeFile(path.join(home, 'bin/jmeter.properties'), 'fixture properties')
  await fs.writeFile(path.join(container, 'installed.json'), JSON.stringify({ sha512: JMETER.sha512, files: await inventory(home) }))
  return home
}
test('verified installed engine is reused without network and without changing workspace files', async t => {
  const manager = await fixture(t, () => { throw new Error('offline') })
  const home = await installedFixture(manager)
  assert.equal(await manager.ensure(), home)
  assert.equal(manager.status.phase, 'ready')
})
test('modified or additional engine libraries fail closed', async t => {
  const manager = await fixture(t, () => { throw new Error('network must not run') })
  const home = await installedFixture(manager)
  await fs.writeFile(path.join(home, 'bin/extra.jar'), 'unexpected code')
  await assert.rejects(manager.ensure(), /校验失败/)
  assert.equal(await fs.readFile(path.join(home, 'bin/extra.jar'), 'utf8'), 'unexpected code')
})
test('bad download checksum never extracts or installs and can retry', async t => {
  let calls = 0
  const manager = await fixture(t, async () => { calls++; return new globalThis.Response('corrupt zip') })
  manager.extract = () => { throw new Error('must never extract an unverified download') }
  await assert.rejects(manager.ensure(), /SHA-512/)
  assert.deepEqual(await fs.readdir(manager.directory), [])
  await assert.rejects(manager.ensure(), /SHA-512/)
  assert.equal(calls, 2)
})
test('download cancellation is shared by simultaneous callers and cleans partial files', async t => {
  let begin
  const started = new Promise(resolve => { begin = resolve })
  const manager = await fixture(t, async (_url, { signal }) => {
    begin()
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))
  })
  const first = manager.ensure(), second = manager.ensure()
  assert.equal(first, second)
  const rejected = assert.rejects(first, /取消/)
  await started
  await manager.stop()
  await rejected
  assert.deepEqual(await fs.readdir(manager.directory), [])
})
test('oversized downloads are rejected before extraction', async t => {
  const manager = await fixture(t, async () => new globalThis.Response('oversized', { headers: { 'content-length': '200000000' } }))
  await assert.rejects(manager.ensure(), /大小限制/)
  assert.deepEqual(await fs.readdir(manager.directory), [])
})
