'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { activateRestore, recoverInterruptedRestore, stageRestore } = require('../app/src/workspace-restore.cjs')
const { exportWorkspace } = require('../app/src/workspace-archive.cjs')
async function fixture(t) {
  const parent = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-restore-'))
  t.after(() => fs.rm(parent, { recursive: true, force: true }))
  const current = path.join(parent, 'local-workspace'), candidate = path.join(parent, 'workspace-restore-' + crypto.randomUUID())
  for (const [dir, value] of [[current, 'original'], [candidate, 'restored']]) { await fs.mkdir(dir); await fs.writeFile(path.join(dir, 'data'), value) }
  return { parent, current, candidate }
}
test('successful restore preserves the old workspace until explicitly removed', async t => {
  const data = await fixture(t)
  const previous = await activateRestore({ ...data, activate: async () => assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'restored') })
  assert.equal(await fs.readFile(path.join(previous, 'data'), 'utf8'), 'original')
  assert.equal(await recoverInterruptedRestore(data.parent), false)
})
test('failed startup stops the candidate before rolling back and preserves candidate evidence', async t => {
  const data = await fixture(t); let stopped = false
  await assert.rejects(activateRestore({ ...data, activate: async () => { throw new Error('startup failed') }, deactivate: async () => {
    assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'restored'); stopped = true
  } }), /startup failed/)
  assert.equal(stopped, true)
  assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'original')
  const saved = (await fs.readdir(data.parent)).find(name => name.startsWith('workspace-unconfirmed-'))
  assert.equal(await fs.readFile(path.join(data.parent, saved, 'data'), 'utf8'), 'restored')
})
for (const stage of ['journal', 'old-moved', 'new-moved']) test(`restart recovers interrupted restore after ${stage}`, async t => {
  const data = await fixture(t), previous = 'workspace-before-restore-' + crypto.randomUUID()
  await fs.writeFile(path.join(data.parent, 'workspace-restore.json'), JSON.stringify({ version: 1, previous }))
  if (stage !== 'journal') await fs.rename(data.current, path.join(data.parent, previous))
  if (stage === 'new-moved') await fs.rename(data.candidate, data.current)
  await recoverInterruptedRestore(data.parent)
  assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'original')
})
test('an invalid journal cannot move data outside the workspace', async t => {
  const data = await fixture(t)
  await fs.writeFile(path.join(data.parent, 'workspace-restore.json'), JSON.stringify({ version: 1, previous: '../outside' }))
  await assert.rejects(recoverInterruptedRestore(data.parent), /无效/)
  assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'original')
})
test('failed shutdown leaves both workspaces and recovery journal untouched until next start', async t => {
  const data = await fixture(t)
  await assert.rejects(activateRestore({ ...data, activate: async () => { throw new Error('startup failed') },
    deactivate: async () => { throw new Error('still running') } }), error => error.restoreRecoveryFailed === true)
  assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'restored')
  const saved = (await fs.readdir(data.parent)).find(name => name.startsWith('workspace-before-restore-'))
  assert.equal(await fs.readFile(path.join(data.parent, saved, 'data'), 'utf8'), 'original')
  assert.equal(await recoverInterruptedRestore(data.parent), true)
  assert.equal(await fs.readFile(path.join(data.current, 'data'), 'utf8'), 'original')
})
test('locked destination key store removes staged plaintext and preserves the source backup', async t => {
  const data = await fixture(t), password = 'portable-fixture-passphrase', source = path.join(data.parent, 'source.testhub-backup')
  await fs.writeFile(path.join(data.current, 'workspace.mv.db'), 'database fixture')
  await exportWorkspace({ directory: data.current, destination: source, password, databaseVersion: '4',
    secrets: { version: 1, databasePassword: 'b'.repeat(64), encryptionKey: 'Y'.repeat(43) + '=' } })
  const before = await fs.readFile(source)
  await assert.rejects(stageRestore({ parent: data.parent, source, password, bundle: {}, tool: async () => ({}),
    codec: { encrypt: async () => { throw new Error('key store locked') } } }), /key store locked/)
  const candidates = (await fs.readdir(data.parent)).filter(name => name.startsWith('workspace-restore-'))
  assert.deepEqual(candidates, [path.basename(data.candidate)])
  assert.deepEqual(await fs.readFile(source), before)
  assert.equal(await fs.readFile(path.join(data.current, 'workspace.mv.db'), 'utf8'), 'database fixture')
})
