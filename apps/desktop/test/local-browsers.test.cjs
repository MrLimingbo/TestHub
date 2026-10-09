'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { LocalBrowsers, fingerprint } = require('../app/src/local-browsers.cjs')
const contents = 'verified fixture archive'
const sha256 = crypto.createHash('sha256').update(contents).digest('hex')
const components = ['chromium-headless-shell', 'ffmpeg'].map(name => ({ name, directory: name + '-fixture',
  executable: 'engine', urls: ['https://example.test/' + name], bytes: contents.length, sha256 }))
async function fixture(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-browser-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const calls = []
  const tool = async (_bundle, [command, browser, , destination]) => {
    calls.push(command)
    if (command === 'describe') return { playwrightVersion: 'fixture', platform: 'fixture', browser, components }
    if (command === 'extract') { await fs.mkdir(destination); await fs.writeFile(path.join(destination, 'engine'), 'fixture executable'); return { extracted: true } }
    return { verified: true }
  }
  let downloads = 0
  const manager = new LocalBrowsers({ directory, bundle: {}, tool, fetcher: async () => { downloads++; return new globalThis.Response(contents) } })
  return { manager, calls, downloads: () => downloads }
}
test('browser and video components are verified once then reused offline', async t => {
  const { manager, calls, downloads } = await fixture(t)
  const cache = await manager.ensure('chromium')
  assert.equal(downloads(), 2)
  assert.ok(calls.includes('verify'))
  manager.fetcher = () => { throw new Error('offline') }
  assert.equal(await manager.ensure('chromium'), cache)
  assert.equal(await manager.available(), cache)
})
test('bad checksum cannot reach the extractor or publish a ready browser', async t => {
  const { manager, calls } = await fixture(t)
  manager.fetcher = async () => new globalThis.Response('bad download')
  await assert.rejects(manager.ensure('chromium'), /SHA-256/)
  assert.equal(calls.includes('extract'), false)
  assert.equal(await manager.installed('chromium'), undefined)
  assert.deepEqual(await fs.readdir((await manager.plan('chromium')).cache), [])
})
test('failed video dependency retries without redownloading the verified browser', async t => {
  const { manager } = await fixture(t)
  let downloads = 0
  manager.fetcher = async () => { if (++downloads === 2) throw new Error('connection lost'); return new globalThis.Response(contents) }
  await assert.rejects(manager.ensure('chromium'), /connection lost/)
  assert.equal(await manager.installed('chromium'), undefined)
  await manager.ensure('chromium')
  assert.equal(downloads, 3)
})
test('a failed browser launch cannot mark the engine ready and a retry reuses verified files', async t => {
  const { manager, downloads } = await fixture(t), original = manager.tool
  manager.tool = async (bundle, args, options) => { if (args[0] === 'verify') throw new Error('launch failed'); return original(bundle, args, options) }
  await assert.rejects(manager.ensure('chromium'), /launch failed/)
  assert.equal(await manager.installed('chromium'), undefined)
  manager.tool = original
  await manager.ensure('chromium')
  assert.equal(downloads(), 2)
})
test('unexpected executable changes fail closed without deleting the cache', async t => {
  const { manager } = await fixture(t)
  const cache = await manager.ensure('chromium'), file = path.join(cache, components[0].directory, 'engine')
  await fs.writeFile(file, 'changed executable')
  await assert.rejects(manager.ensure('chromium'), /校验失败/)
  assert.equal(await fs.readFile(file, 'utf8'), 'changed executable')
})
test('component fingerprints reject links escaping the private cache', { skip: process.platform === 'win32' }, async t => {
  const { manager } = await fixture(t)
  const root = path.join(manager.directory, 'links'); await fs.mkdir(root)
  await fs.symlink(os.tmpdir(), path.join(root, 'outside'))
  await assert.rejects(fingerprint(root), /越界链接/)
})

test('empty Playwright host-validation markers do not invalidate a verified browser', async t => {
  const { manager } = await fixture(t)
  const cache = await manager.ensure('chromium')
  const marker = path.join(cache, components[0].directory, 'DEPENDENCIES_VALIDATED')
  await fs.writeFile(marker, '')
  assert.equal(await manager.ensure('chromium'), cache)
  await fs.writeFile(marker, 'unexpected contents')
  await assert.rejects(manager.ensure('chromium'), /校验失败/)
})

test('a failing official mirror falls back and still verifies the fixed checksum', async t => {
  const { manager } = await fixture(t)
  const plan = await manager.plan('chromium')
  manager.plans.set('chromium', { ...plan, components: plan.components.map(component => ({ ...component, urls: ['https://primary.test', 'https://backup.test'] })) })
  const urls = []
  manager.fetcher = async url => { urls.push(url); if (url.includes('primary')) throw new Error('network'); return new globalThis.Response(contents) }
  await manager.ensure('chromium')
  assert.deepEqual(urls, ['https://primary.test', 'https://backup.test', 'https://primary.test', 'https://backup.test'])
})
test('cancellation stops download, clears staging and allows retry', async t => {
  const { manager } = await fixture(t)
  let started
  const downloading = new Promise(resolve => { started = resolve })
  manager.fetcher = (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true }); started()
  })
  const pending = manager.ensure('chromium')
  assert.equal(manager.ensure('chromium'), pending)
  await downloading; manager.cancel()
  await assert.rejects(pending, /已取消/)
  assert.deepEqual(await fs.readdir((await manager.plan('chromium')).cache), [])
  manager.fetcher = async () => new globalThis.Response(contents)
  await manager.ensure('chromium')
})
