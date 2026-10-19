'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { inventory, digest } = require('../build/runtime-integrity.cjs')
const inspector = import('../../../scripts/desktop/verify-mac-preview.mjs')
const metadata = 'Identifier=io.testhub.desktop\nSignature=adhoc\nTeamIdentifier=not set\n'
async function fixture(t, arch = 'arm64') {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-preview-unit-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const app = path.join(root, 'TestHub.app'), installer = path.join(root, 'TestHub.dmg')
  const runtime = path.join(app, 'Contents/Resources/local-runtime/darwin-' + arch)
  await fs.mkdir(runtime, { recursive: true }); await fs.mkdir(path.join(app, 'Contents/MacOS'))
  await fs.writeFile(path.join(app, 'Contents/MacOS/TestHub'), 'unit executable')
  await fs.writeFile(path.join(app, 'Contents/Resources/app.asar'), 'unit asar')
  await fs.writeFile(installer, 'substituted native unit image')
  await fs.mkdir(path.join(runtime, 'frontend')); await fs.writeFile(path.join(runtime, 'frontend/index.html'), 'unit frontend')
  const manifest = { version: 1, platform: 'darwin', arch, distributionReady: false, frontend: 'frontend' }
  for (const key of ['java', 'node', 'backend', 'apiRunner', 'jmeterRunner', 'jmeterWatchdog', 'playwrightRunner', 'playwrightTool', 'appiumRunner']) {
    manifest[key] = key; await fs.writeFile(path.join(runtime, key), 'unit ' + key)
  }
  manifest.backendSha256 = await digest(path.join(runtime, 'backend')); manifest.integrity = await inventory(runtime)
  await fs.writeFile(path.join(runtime, 'runtime.json'), JSON.stringify(manifest))
  return { arch, app, installer, runtime }
}
function commands(options, alter = async () => {}) {
  const calls = [], state = { mount: null }
  return { calls, state, command: async (file, args) => {
    calls.push([file, args])
    if (args[0] === 'attach') {
      state.mount = args[args.indexOf('-mountpoint') + 1]
      await fs.cp(options.app, path.join(state.mount, 'TestHub.app'), { recursive: true }); await alter(state.mount)
    }
    if (args[0] === 'detach') await fs.rm(path.join(state.mount, 'TestHub.app'), { recursive: true })
    return { stdout: '', stderr: args[0] === '--display' ? metadata : '' }
  } }
}
test('preview builder retains runtime hooks, app identity and all resources without changing formal policy', () => {
  const base = require('../package.json').build, preview = require('../build/mac-preview.cjs')
  for (const key of Object.keys(base).filter(key => key !== 'mac')) assert.deepEqual(preview[key], base[key])
  for (const key of ['extraResources', 'category', 'icon', 'target']) assert.deepEqual(preview.mac[key], base.mac[key])
  assert.equal(preview.mac.identity, '-'); assert.equal(preview.mac.hardenedRuntime, false); assert.equal(preview.mac.notarize, false)
  assert.deepEqual(preview.mac.signIgnore, ['/Contents/Resources/local-runtime/'])
  assert.equal(base.mac.identity, undefined); assert.equal(base.mac.notarize, undefined)
})
test('preview verifies both resource seals and runtime inventories in a hidden read-only final DMG', async t => {
  const options = await fixture(t), mock = commands(options), { inspectPreview } = await inspector
  const result = await inspectPreview(options, mock.command)
  assert.equal(result.strictResourceSealVerified, true); assert.equal(result.finalDmgPayloadVerified, true)
  assert.equal(result.runtimeFileCount, 10); assert.equal(result.developerIdVerified, false); assert.equal(result.notarizationVerified, false)
  assert.equal(mock.calls.filter(([file, args]) => file.endsWith('/codesign') && args[0] === '--verify' && args.includes('--deep') && args.includes('--strict')).length, 2)
  const attach = mock.calls.find(([,args]) => args[0] === 'attach')[1]
  for (const flag of ['-readonly', '-noautoopen', '-nobrowse']) assert.ok(attach.includes(flag))
  assert.ok(!mock.calls.some(([file]) => /spctl|xcrun/.test(file)))
  await assert.rejects(fs.lstat(path.dirname(mock.state.mount)), { code: 'ENOENT' })
})
test('missing resource seals fail even when metadata says adhoc', async t => {
  const options = await fixture(t), mock = commands(options), { inspectPreview } = await inspector
  await assert.rejects(inspectPreview(options, (file, args) => {
    if (file.endsWith('/codesign') && args[0] === '--verify') throw new Error('missing seal')
    return mock.command(file, args)
  }), /missing seal/)
  assert.equal(mock.state.mount, null)
})
test('wrong identifiers or certificate signatures cannot be approved as this preview', async t => {
  const options = await fixture(t), { inspectPreview } = await inspector
  for (const text of [metadata.replace('io.testhub.desktop', 'io.other'), metadata + 'Identifier=io.testhub.desktop\n', metadata.replace('Signature=adhoc', ''), metadata + 'Authority=Developer ID Application: fixture\n']) {
    const mock = commands(options)
    await assert.rejects(inspectPreview(options, async (file, args) => args[0] === '--display' ? { stderr: text } : mock.command(file, args)), /不匹配/)
    assert.equal(mock.state.mount, null)
  }
})
test('Intel previews use the native x86_64 architecture name and reject a failed architecture check', async t => {
  const options = await fixture(t, 'x64'), mock = commands(options), { inspectPreview } = await inspector
  await inspectPreview(options, mock.command)
  assert.ok(mock.calls.some(([file, args]) => file.endsWith('/lipo') && args[0] === path.join(options.app, 'Contents/MacOS/TestHub') && args[1] === '-verify_arch' && args[2] === 'x86_64'))
  await assert.rejects(inspectPreview(options, (file, args) => {
    if (file.endsWith('/lipo')) throw new Error('wrong architecture')
    return mock.command(file, args)
  }), /wrong architecture/)
})
test('preview architecture invocation works with the real native lipo and a native executable', { skip: process.platform !== 'darwin' }, async t => {
  const options = await fixture(t, process.arch), mock = commands(options), { inspectPreview } = await inspector
  const { nativeCommand } = await import('../../../scripts/desktop/verify-signed-artifacts.mjs')
  await fs.copyFile(process.execPath, path.join(options.app, 'Contents/MacOS/TestHub'))
  const result = await inspectPreview(options, (file, args, label) => file.endsWith('/lipo') ? nativeCommand(file, args, label) : mock.command(file, args))
  assert.equal(result.finalDmgPayloadVerified, true)
})
test('changed runtime bytes in the final image are rejected and detached', async t => {
  const options = await fixture(t), { inspectPreview } = await inspector
  const mock = commands(options, mount => fs.appendFile(path.join(mount, 'TestHub.app/Contents/Resources/local-runtime/darwin-arm64/backend'), 'changed'))
  await assert.rejects(inspectPreview(options, mock.command), /变更/)
  assert.ok(mock.calls.some(([,args]) => args[0] === 'detach'))
  await assert.rejects(fs.lstat(path.dirname(mock.state.mount)), { code: 'ENOENT' })
})
test('substituted ASAR or self-consistent but different runtime manifests are rejected', async t => {
  for (const variant of ['asar', 'manifest']) {
    const options = await fixture(t), { inspectPreview } = await inspector
    const mock = commands(options, async mount => {
      const resources = path.join(mount, 'TestHub.app/Contents/Resources')
      if (variant === 'asar') await fs.appendFile(path.join(resources, 'app.asar'), 'changed')
      else {
        const file = path.join(resources, 'local-runtime/darwin-arm64/runtime.json'), value = JSON.parse(await fs.readFile(file, 'utf8'))
        value.preparedAt = 'different'; await fs.writeFile(file, JSON.stringify(value))
      }
    })
    await assert.rejects(inspectPreview(options, mock.command), /不一致/)
  }
})
test('failed detach preserves the owned mount rather than recursively removing its contents', async t => {
  const options = await fixture(t), mock = commands(options), { inspectPreview } = await inspector
  await assert.rejects(inspectPreview(options, (file, args) => {
    if (args[0] === 'detach') throw new Error('detach failed')
    return mock.command(file, args)
  }), /detach failed/)
  assert.ok((await fs.stat(mock.state.mount)).isDirectory())
  // Substituted native operation: this is an ordinary fixture directory, not a mounted image.
  t.after(() => fs.rm(path.dirname(mock.state.mount), { recursive: true, force: true }))
})
test('invalid targets and missing runtime inventories fail before approval', async t => {
  const options = await fixture(t), { inspectPreview } = await inspector
  for (const patch of [{ arch: 'universal' }, { installer: options.app }, { app: options.installer }])
    await assert.rejects(inspectPreview({ ...options, ...patch }), /无效/)
  const file = path.join(options.runtime, 'runtime.json'), manifest = JSON.parse(await fs.readFile(file, 'utf8'))
  delete manifest.integrity; await fs.writeFile(file, JSON.stringify(manifest))
  await assert.rejects(inspectPreview(options, commands(options).command), /完整性清单/)
})
test('native preview summaries are exclusive, fail closed and omit private failure details', { skip: process.platform !== 'darwin' }, async t => {
  const options = await fixture(t, process.arch), { verifyPreviewAndWrite } = await inspector
  const settings = { ...options, revision: 'a'.repeat(40), output: path.join(path.dirname(options.installer), 'summary.json') }
  await assert.rejects(verifyPreviewAndWrite(settings, async () => { throw new Error('private fixture output') }), /private fixture/)
  const saved = await fs.readFile(settings.output, 'utf8')
  assert.equal(JSON.parse(saved).passed, false); assert.ok(!saved.includes('private fixture'))
  await assert.rejects(verifyPreviewAndWrite(settings, async () => ({})), { code: 'EEXIST' })
  const fresh = { ...settings, output: settings.output + '.new' }
  await assert.rejects(verifyPreviewAndWrite({ ...fresh, arch: 'other' }), /原生/)
  await assert.rejects(verifyPreviewAndWrite({ ...fresh, revision: 'main' }), /摘要/)
  const result = await verifyPreviewAndWrite(fresh, async () => ({ finalDmgPayloadVerified: true }))
  assert.equal(result.passed, true); assert.equal(result.distribution, 'ad-hoc-preview')
})
