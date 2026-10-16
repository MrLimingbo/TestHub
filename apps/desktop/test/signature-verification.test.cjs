'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const { macSignature, windowsSignatures } = require('../build/signature-policy.cjs')
const inspector = import('../../../scripts/desktop/verify-signed-artifacts.mjs')
const teamId = 'AB1234CDEF', certificate = 'a'.repeat(64)
const metadata = [
  'Identifier=io.testhub.desktop', 'TeamIdentifier=' + teamId,
  'Authority=Developer ID Application: Test Fixture (' + teamId + ')', 'Authority=Developer ID Certification Authority',
  'CodeDirectory v=20500 size=1 flags=0x10000(runtime) hashes=1+1 location=embedded',
  'Timestamp=Oct 16, 2026 at 00:00:00',
].join('\n')
const records = () => ['application','installer'].map(role => ({ role, status: 'Valid', signatureType: 'Authenticode', certificateSha256: certificate, codeSigningEku: true, timestampPresent: true }))
async function directory(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-signature-unit-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  return root
}
async function macFixture(t) {
  const root = await directory(t), app = path.join(root, 'TestHub.app'), installer = path.join(root, 'TestHub.dmg')
  await fs.mkdir(path.join(app, 'Contents/MacOS'), { recursive: true }); await fs.mkdir(path.join(app, 'Contents/Resources'))
  await fs.writeFile(path.join(app, 'Contents/MacOS/TestHub'), 'unit executable'); await fs.writeFile(path.join(app, 'Contents/Resources/app.asar'), 'unit asar')
  await fs.writeFile(installer, 'unit image; native operations are substituted in unit tests')
  return { platform: 'darwin', arch: 'arm64', app, installer, teamId }
}
async function windowsFixture(t) {
  const root = await directory(t), app = path.join(root, 'TestHub.exe'), installer = path.join(root, 'installer.exe')
  await fs.writeFile(app, 'unit executable'); await fs.writeFile(installer, 'unit installer')
  return { platform: 'win32', arch: 'x64', app, installer }
}
function macCommands(options, alter = async () => {}) {
  const calls = [], state = { mount: null }
  return { calls, state, command: async (file, args) => {
    calls.push([file, args])
    if (args[0] === 'attach') {
      state.mount = args[args.indexOf('-mountpoint') + 1]
      await fs.cp(options.app, path.join(state.mount, 'TestHub.app'), { recursive: true })
      await alter(state.mount)
    }
    if (args[0] === 'detach') await fs.rm(path.join(state.mount, 'TestHub.app'), { recursive: true })
    return { stdout: '', stderr: args[0] === '--display' ? metadata : '' }
  } }
}

test('macOS policy accepts complete Developer ID metadata without exposing signer names', () => {
  const value = macSignature(metadata, teamId)
  assert.equal(value.hardenedRuntime, true); assert.equal(value.secureTimestampPresent, true)
  assert.ok(!JSON.stringify(value).includes('Test Fixture')); assert.equal(value.expectedTeamMatched, true)
  assert.ok(!JSON.stringify(value).includes(teamId))
  assert.deepEqual(macSignature(metadata.replaceAll('\n', '\r\n'), teamId), value)
})
test('macOS policy rejects missing or mismatched application and team identifiers', () => {
  for (const text of [metadata.replace('io.testhub.desktop', 'io.other.app'), metadata.replace('TeamIdentifier=' + teamId, 'TeamIdentifier=OTHER12345'), metadata.replace('Identifier=io.testhub.desktop\n', ''), metadata + '\nIdentifier=io.testhub.desktop'])
    assert.throws(() => macSignature(text, teamId), /不匹配|不完整/)
  for (const value of [undefined, '', 'short', 'ab1234cdef']) assert.throws(() => macSignature(metadata, value), /Team ID/)
})
test('macOS policy rejects ad hoc, developer-only, unhardened and untimestamped signatures', () => {
  for (const text of [metadata + '\nSignature=adhoc', metadata.replace('Developer ID Application: ', 'Apple Development: '), metadata.replace('0x10000(runtime)', '0x2(adhoc)'), metadata.replace(/Timestamp=.*/, 'Timestamp=none'), metadata.replace(/Timestamp=.*/, '')])
    assert.throws(() => macSignature(text, teamId))
})
test('Windows policy requires matching native embedded signatures and emits only sanitized metadata', () => {
  const input = records(); input[0].subject = 'private unit fixture'; input[1].certificateSha256 = certificate.toUpperCase()
  const value = windowsSignatures(input)
  assert.equal(value.matchingSigner, true); assert.equal(value.certificateSha256, certificate)
  assert.ok(!JSON.stringify(value).includes('private unit fixture'))
})
test('Windows policy rejects every non-valid trust status, catalog signatures and missing roles', () => {
  for (const status of ['NotSigned','HashMismatch','NotTrusted','UnknownError','NotSupportedFileFormat','Incompatible']) {
    const input = records(); input[1].status = status; assert.throws(() => windowsSignatures(input), /签名无效/)
  }
  const catalog = records(); catalog[0].signatureType = 'Catalog'; assert.throws(() => windowsSignatures(catalog), /签名无效/)
  for (const input of [null, [], records().slice(0, 1), [records()[0],records()[0]], [records()[0],{ role: 'other' }]])
    assert.throws(() => windowsSignatures(input))
})
test('Windows policy rejects missing timestamps, wrong certificate usage, malformed or differing certificates', () => {
  for (const patch of [{ timestampPresent: false },{ timestampPresent: 'true' },{ codeSigningEku: false },{ certificateSha256: '' },{ certificateSha256: 'b'.repeat(64) }]) {
    const input = records(); Object.assign(input[1], patch); assert.throws(() => windowsSignatures(input))
  }
})
test('macOS orchestration checks final read-only DMG payload and removes its temporary mount directory', async t => {
  const options = await macFixture(t), mock = macCommands(options), { inspectArtifacts } = await inspector
  const result = await inspectArtifacts(options, mock.command)
  assert.equal(result.signature.finalDmgPayloadVerified, true); assert.equal(result.signature.diskImageGatekeeperAssessed, false)
  assert.equal(mock.calls.filter(([file]) => file.endsWith('/spctl')).length, 2)
  const attach = mock.calls.find(([,args]) => args[0] === 'attach')[1]
  for (const flag of ['-readonly','-noautoopen','-nobrowse']) assert.ok(attach.includes(flag))
  await assert.rejects(fs.lstat(path.dirname(mock.state.mount)), { code: 'ENOENT' })
})
test('macOS orchestration rejects a substituted final payload and still detaches and cleans its mount', async t => {
  const options = await macFixture(t), mock = macCommands(options, mount => fs.appendFile(path.join(mount, 'TestHub.app/Contents/Resources/app.asar'), 'substitution'))
  const { inspectArtifacts } = await inspector
  await assert.rejects(inspectArtifacts(options, mock.command), /不一致/)
  assert.ok(mock.calls.some(([,args]) => args[0] === 'detach'))
  await assert.rejects(fs.lstat(path.dirname(mock.state.mount)), { code: 'ENOENT' })
})
test('failed macOS ticket or Gatekeeper verification never reaches image upload approval', async t => {
  for (const blocked of ['/usr/bin/xcrun','/usr/sbin/spctl']) {
    const options = await macFixture(t), mock = macCommands(options), { inspectArtifacts } = await inspector
    await assert.rejects(inspectArtifacts(options, async (file, args) => { if (file === blocked) throw new Error('unit native rejection'); return mock.command(file, args) }), /unit native rejection/)
    assert.equal(mock.state.mount, null)
  }
})
test('a failed macOS detach preserves its owned mount instead of recursively deleting mounted content', async t => {
  const options = await macFixture(t), mock = macCommands(options), { inspectArtifacts } = await inspector
  await assert.rejects(inspectArtifacts(options, async (file, args) => {
    if (args[0] === 'detach') throw new Error('unit detach failed')
    return mock.command(file, args)
  }), /unit detach failed/)
  assert.equal((await fs.stat(mock.state.mount)).isDirectory(), true)
  // This substituted mount is an ordinary directory, not a real attached image.
  t.after(() => fs.rm(path.dirname(mock.state.mount), { recursive: true, force: true }))
})
test('Windows orchestration uses fixed PowerShell source and literal file paths in environment variables', async t => {
  const options = await windowsFixture(t), { inspectArtifacts } = await inspector
  const result = await inspectArtifacts(options, async (_file, args, _label, settings) => {
    assert.ok(args.includes('-NonInteractive')); assert.ok(args.includes('-File')); assert.ok(!args.includes('-Command'))
    assert.equal(settings.env.TESTHUB_SIGNED_APPLICATION, options.app); assert.equal(settings.env.TESTHUB_SIGNED_INSTALLER, options.installer)
    assert.ok(!args.includes(options.installer))
    return { stdout: '\uFEFF' + JSON.stringify(records()) }
  })
  assert.equal(result.signature.authenticodeValid, true); assert.match(result.installer.sha256, /^[a-f0-9]{64}$/)
})
test('Windows orchestration rejects malformed metadata and file changes during verification', async t => {
  const { inspectArtifacts } = await inspector
  const options = await windowsFixture(t)
  await assert.rejects(inspectArtifacts(options, async () => ({ stdout: 'not-json' })), /有效元数据/)
  for (const file of [options.app, options.installer]) await assert.rejects(inspectArtifacts(options, async () => {
    await fs.appendFile(file, 'changed'); return { stdout: JSON.stringify(records()) }
  }), /发生变化/)
})
test('artifact validation rejects unsupported targets, wrong extensions and same paths before native tools', async t => {
  const options = await windowsFixture(t), { inspectArtifacts } = await inspector
  const noCommands = () => { throw new Error('native command should not run') }
  for (const patch of [{ platform: 'linux' },{ arch: 'arm64' },{ installer: options.app },{ app: options.app + '.json' }])
    await assert.rejects(inspectArtifacts({ ...options, ...patch }, noCommands), /无效|同一文件|不匹配/)
})
test('summary reservation preserves old files and writes no credential or native-tool output on failure', async t => {
  const root = await directory(t), { verifyAndWrite } = await inspector
  const options = { platform: process.platform, arch: process.arch, teamId, revision: 'a'.repeat(40), output: path.join(root, 'summary.json') }
  await assert.rejects(verifyAndWrite(options, async () => { throw new Error('private fixture credential') }), /private fixture/)
  const failed = await fs.readFile(options.output, 'utf8'); assert.equal(JSON.parse(failed).passed, false); assert.ok(!failed.includes('private fixture'))
  await assert.rejects(verifyAndWrite(options, async () => ({ signature: {} })), { code: 'EEXIST' })
  assert.equal(await fs.readFile(options.output, 'utf8'), failed)
})
test('summary writes tested revision only after inspection and rejects foreign native targets before output', async t => {
  const root = await directory(t), { verifyAndWrite } = await inspector
  const options = { platform: process.platform, arch: process.arch, teamId, revision: 'b'.repeat(40), output: path.join(root, 'summary.json') }
  const result = await verifyAndWrite(options, async () => ({ signature: { unitInspectionOnly: true } }))
  assert.equal(result.passed, true); assert.equal(JSON.parse(await fs.readFile(options.output, 'utf8')).revision, options.revision)
  await assert.rejects(verifyAndWrite(options, async () => { throw new Error('should not inspect a reserved summary') }), { code: 'EEXIST' })
  assert.equal(JSON.parse(await fs.readFile(options.output, 'utf8')).passed, true)
  for (const patch of [{ platform: process.platform === 'darwin' ? 'win32' : 'darwin' },{ arch: 'other' },{ revision: 'main' }])
    await assert.rejects(verifyAndWrite({ ...options, ...patch, output: path.join(root, 'unused.json') }), /原生|摘要/)
  await assert.rejects(fs.lstat(path.join(root, 'unused.json')), { code: 'ENOENT' })
})
test('native command failures are bounded and do not reveal raw stdout or stderr', async () => {
  const { nativeCommand } = await inspector
  await assert.rejects(nativeCommand(process.execPath, ['-e','process.stderr.write("private-unit-fixture");process.exit(1)'], 'Native unit check'), error => {
    assert.match(error.message, /Native unit check失败/); assert.ok(!error.message.includes('private-unit-fixture')); return true
  })
})
