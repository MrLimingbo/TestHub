// Native preview evidence only: never treats an unsigned installer as a signed release.
import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { sha256 } from './verify-signed-artifacts.mjs'
import { windowsPowerShellEnvironment } from './windows-powershell.mjs'
const run = promisify(execFile), require = createRequire(import.meta.url)
const { verifyRuntime } = require('../../apps/desktop/build/skip-runtime-dependency-copy.cjs')
const args = process.argv.slice(2), values = {}
const allowed = new Set(['--app', '--installer', '--output', '--revision'])
for (let i = 0; i < args.length; i += 2) {
  if (!allowed.has(args[i]) || !args[i + 1] || Object.hasOwn(values, args[i])) throw new Error('Invalid Windows preview arguments')
  values[args[i]] = args[i + 1]
}
assert.ok([...allowed].every(k => values[k]))
assert.equal(process.platform, 'win32'); assert.equal(process.arch, 'x64')
assert.match(values['--revision'], /^[a-f0-9]{40}$/)
const app = await fs.realpath(values['--app']), installer = await fs.realpath(values['--installer'])
for (const file of [app, installer]) assert.ok((await fs.lstat(file)).isFile() && file.toLowerCase().endsWith('.exe'))
const resources = path.join(path.dirname(app), 'resources'), runtime = path.join(resources, 'local-runtime')
await fs.mkdir(path.dirname(values['--output']), { recursive: true })
const output = await fs.open(values['--output'], 'wx', 0o600)
const report = { version: 1, passed: false, platform: 'win32', arch: 'x64', revision: values['--revision'], distribution: 'unsigned-preview', publisherSigned: false, smartScreenAssessed: false, distributionReady: false }
try {
  const signature = await run(path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'), ['-NoProfile', '-NonInteractive', '-Command', "$ErrorActionPreference='Stop'; @($env:TESTHUB_PREVIEW_APP,$env:TESTHUB_PREVIEW_INSTALLER) | ForEach-Object { (Get-AuthenticodeSignature -LiteralPath $_).Status.ToString() }"], { windowsHide: true, env: { ...windowsPowerShellEnvironment(), TESTHUB_PREVIEW_APP: app, TESTHUB_PREVIEW_INSTALLER: installer } })
  assert.deepEqual(signature.stdout.trim().split(/\r?\n/), ['NotSigned', 'NotSigned'])
  const executable = await fs.readFile(app), header = executable.readUInt32LE(0x3c)
  assert.equal(executable.readUInt16LE(0), 0x5a4d); assert.equal(executable.readUInt32LE(header), 0x00004550); assert.equal(executable.readUInt16LE(header + 4), 0x8664)
  await verifyRuntime(runtime, 'win32', 'x64', true)
  const manifestPath = path.join(runtime, 'win32-x64/runtime.json'), manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'))
  assert.ok(Object.keys(manifest.integrity.files).length > 0)
  Object.assign(report, { passed: true, applicationAndInstallerUnsigned: true, nativeX64Executable: true, runtimeIntegrityVerified: true,
    installerSha256: await sha256(installer), installerBytes: (await fs.stat(installer)).size,
    executableSha256: await sha256(app), asarSha256: await sha256(path.join(resources, 'app.asar')),
    runtimeManifestSha256: await sha256(manifestPath), runtimeFileCount: Object.keys(manifest.integrity.files).length,
    backendSha256: manifest.backendSha256, schemaVersion: manifest.desktopSchemaVersion })
} finally {
  try { await output.writeFile(JSON.stringify(report, null, 2) + '\n'); await output.sync() }
  finally { await output.close() }
}
console.log('PASS unsigned Windows preview, x64 executable and bundled runtime inventory')
