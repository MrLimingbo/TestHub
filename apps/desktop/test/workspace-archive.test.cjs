'use strict'
const { Buffer } = require('node:buffer')
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { exportWorkspace, importWorkspace } = require('../app/src/workspace-archive.cjs')
const password = 'fixture portable backup password'
const secrets = { version: 1, databasePassword: 'b'.repeat(64), encryptionKey: 'Y'.repeat(43) + '=', heartbeatKey: 'c'.repeat(64) }
async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-backup-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const directory = path.join(root, 'source'), destination = path.join(root, 'copy.testhub-backup')
  await fs.mkdir(path.join(directory, 'objects', 'attachments'), { recursive: true })
  await fs.writeFile(path.join(directory, 'workspace.mv.db'), crypto.randomBytes(200000))
  await fs.writeFile(path.join(directory, 'objects', 'attachments', '附件.txt'), 'persisted attachment')
  await fs.writeFile(path.join(directory, 'objects', 'empty'), '')
  await fs.writeFile(path.join(directory, 'workspace-secrets.enc'), 'machine-specific ciphertext')
  return { root, directory, destination, password, secrets, databaseVersion: '3' }
}
test('encrypted workspace archive round trips large chunks, Unicode names, empty files and portable secrets', async t => {
  const options = await fixture(t)
  const result = await exportWorkspace(options)
  assert.equal(result.fileCount, 3)
  const archive = await fs.readFile(options.destination)
  for (const value of [secrets.databasePassword, secrets.encryptionKey, 'persisted attachment', 'machine-specific ciphertext']) assert.equal(archive.includes(value), false)
  const target = path.join(options.root, 'restored')
  const manifest = await importWorkspace({ source: options.destination, directory: target, password })
  assert.equal(manifest.secrets.databasePassword, secrets.databasePassword)
  assert.equal(manifest.secrets.heartbeatKey, undefined)
  for (const file of manifest.files) assert.deepEqual(await fs.readFile(path.join(target, file.name)), await fs.readFile(path.join(options.directory, file.name)))
  await assert.rejects(fs.access(path.join(target, 'workspace-secrets.enc')), { code: 'ENOENT' })
})
test('wrong password, truncation and ciphertext mutation leave no staged workspace', async t => {
  const options = await fixture(t); await exportWorkspace(options)
  const original = await fs.readFile(options.destination)
  for (const kind of ['password', 'truncated', 'changed', 'tag']) {
    const source = path.join(options.root, kind), bytes = Buffer.from(original)
    if (kind === 'changed') bytes[bytes.length - 100] ^= 1
    if (kind === 'tag') bytes[bytes.length - 1] ^= 1
    await fs.writeFile(source, kind === 'truncated' ? bytes.subarray(0, bytes.length - 12) : bytes)
    const directory = path.join(options.root, kind + '-restored')
    await assert.rejects(importWorkspace({ source, directory, password: kind === 'password' ? 'incorrect backup password' : password }))
    await assert.rejects(fs.access(directory), { code: 'ENOENT' })
  }
})
test('backup never overwrites an existing export or restore directory', async t => {
  const options = await fixture(t); await exportWorkspace(options)
  const original = await fs.readFile(options.destination)
  await assert.rejects(exportWorkspace(options), { code: 'EEXIST' })
  assert.deepEqual(await fs.readFile(options.destination), original)
  await assert.rejects(importWorkspace({ source: options.destination, directory: options.directory, password }), { code: 'EEXIST' })
  assert.equal(await fs.readFile(path.join(options.directory, 'objects/attachments/附件.txt'), 'utf8'), 'persisted attachment')
})
test('cancelled encryption does not publish a partial backup', async t => {
  const options = await fixture(t), controller = new globalThis.AbortController()
  await assert.rejects(exportWorkspace({ ...options, signal: controller.signal, progress: () => controller.abort() }))
  assert.deepEqual(await fs.readdir(options.root), ['source'])
})
test('symlinks to files outside the workspace are not exported', { skip: process.platform === 'win32' }, async t => {
  const options = await fixture(t)
  await fs.symlink('/etc/hosts', path.join(options.directory, 'objects', 'outside'))
  await assert.rejects(exportWorkspace(options), /符号链接/)
})
test('authenticated archives cannot traverse paths or reuse case-insensitive filenames', async t => {
  const options = await fixture(t), outside = path.join(options.root, 'outside')
  await fs.writeFile(outside, 'preserve outside data')
  for (const [index, names] of [['../outside'], ['objects/../../outside'], ['objects/a', 'objects/A'], ['objects/CON']].entries()) {
    const header = Buffer.concat([Buffer.from('THBK0001'), crypto.randomBytes(16), crypto.randomBytes(12)])
    const key = crypto.scryptSync(password, header.subarray(8, 24), 32, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 ** 2 })
    const bytes = Buffer.from('fixture'), sha256 = crypto.createHash('sha256').update(bytes).digest('hex')
    const files = ['workspace.mv.db', ...names].map(name => ({ name, bytes: bytes.length, sha256 }))
    const manifest = Buffer.from(JSON.stringify({ format: 'testhub-workspace', version: 1, createdAt: new Date().toISOString(), databaseVersion: '4', secrets, files }))
    const length = Buffer.alloc(4); length.writeUInt32BE(manifest.length)
    const cipher = crypto.createCipheriv('aes-256-gcm', key, header.subarray(24)); cipher.setAAD(header)
    const encrypted = Buffer.concat([cipher.update(Buffer.concat([length, manifest, ...files.map(() => bytes)])), cipher.final()])
    const source = path.join(options.root, 'invalid-' + index), directory = source + '-restored'
    await fs.writeFile(source, Buffer.concat([header, encrypted, cipher.getAuthTag()]))
    await assert.rejects(importWorkspace({ source, directory, password }), /清单无效/)
    await assert.rejects(fs.access(directory), { code: 'ENOENT' })
    assert.equal(await fs.readFile(outside, 'utf8'), 'preserve outside data')
  }
})
