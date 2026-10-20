// Build real installers with the previous immutable shell/backend and the current candidate.
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
const run = promisify(execFile), require = createRequire(import.meta.url)
const { digest, inventory, verifyIntegrity } = require('../../apps/desktop/build/runtime-integrity.cjs')
const args = process.argv.slice(2), value = key => args[args.indexOf(key) + 1]
if (!args.includes('--baseline-backend') || !args.includes('--output')) throw new Error('Usage: build-install-fixtures.mjs --baseline-backend <previous.jar> --output <new fixture directory>')
const root = process.cwd(), output = path.resolve(value('--output')), baseline = await fs.realpath(value('--baseline-backend'))
const target = `${process.platform}-${process.arch}`, windows = process.platform === 'win32'
if (!windows && process.platform !== 'darwin') throw new Error('Installer fixtures require a native Mac or Windows')
await fs.mkdir(output, { recursive: false })
const metadata = JSON.parse(await fs.readFile(path.join(path.dirname(baseline), 'baseline.json'), 'utf8'))
if (!Number.isSafeInteger(metadata.desktopSchemaVersion) || metadata.desktopSchemaVersion < 1)
  throw new Error('Previous backend schema version is missing; rebuild the immutable installation baseline')
const shellArchive = path.join(path.dirname(baseline), 'desktop-app.tar')
if (metadata.commit !== '3932ef152604244438f007b3e61ed4867a23b443' || metadata.shellSource !== metadata.commit + ':apps/desktop/app'
  || metadata.backendSha256 !== await digest(baseline) || !/^[a-f0-9]{64}$/.test(metadata.shellArchiveSha256 || '')
  || metadata.shellArchiveSha256 !== await digest(shellArchive) || metadata.isolatedAcceptancePassed !== true) throw new Error('Previous shell/backend provenance is invalid')
const baselineApp = path.join(output, 'baseline-app'); await fs.mkdir(baselineApp)
await run('tar', ['-xf', shellArchive, '-C', baselineApp])
await fs.access(path.join(baselineApp, 'src/main.cjs'))
if (JSON.parse(await fs.readFile(path.join(baselineApp, 'package.json'), 'utf8')).name !== 'testhub-desktop-app') throw new Error('Previous shell application identity changed')
const currentRuntime = path.resolve('.testhub/desktop-runtime', target)
const manifest = JSON.parse(await fs.readFile(path.join(currentRuntime, 'runtime.json'), 'utf8'))
await verifyIntegrity(currentRuntime, manifest)
if (await digest(baseline) === manifest.backendSha256) throw new Error('A distinct previous backend is required')
const baselineRoot = path.join(output, 'baseline-runtime'), copied = path.join(baselineRoot, target)
await fs.mkdir(baselineRoot); await fs.cp(currentRuntime, copied, { recursive: true, verbatimSymlinks: true })
await fs.copyFile(baseline, path.join(copied, manifest.backend))
if (metadata.desktopSchemaVersion > manifest.desktopSchemaVersion) throw new Error('Installation baseline cannot use a newer database schema than the candidate')
const old = { ...manifest, desktopSchemaVersion: metadata.desktopSchemaVersion, backendSha256: await digest(baseline), distributionReady: false }
old.integrity = await inventory(copied)
await fs.writeFile(path.join(copied, 'runtime.json'), JSON.stringify(old, null, 2))
await verifyIntegrity(copied, old)
const baseConfig = windows ? JSON.parse(await fs.readFile(path.join(root, 'apps/desktop/package.json'), 'utf8')).build
  : require('../../apps/desktop/build/mac-preview.cjs')
const fixture = {}
const externalCandidate = args.includes('--candidate-installer') ? await fs.realpath(value('--candidate-installer')) : null
for (const [label, runtime] of [['baseline', baselineRoot], ...(!externalCandidate ? [['candidate', path.dirname(currentRuntime)]] : [])]) {
  const destination = path.join(output, label)
  const filename = windows ? 'TestHub-install-acceptance.exe' : 'TestHub-install-acceptance.dmg'
  const configuration = { ...globalThis.structuredClone(baseConfig), directories: { ...baseConfig.directories, app: label === 'baseline' ? baselineApp : path.join(root, 'apps/desktop/app'), buildResources: path.join(root, 'apps/desktop/build'), output: destination }, [windows ? 'win' : 'mac']: { ...baseConfig[windows ? 'win' : 'mac'],
    artifactName: filename, extraResources: [{ from: runtime, to: 'local-runtime', filter: [target + '/**'] }],
  } }
  const file = path.join(output, label + '.json'); await fs.writeFile(file, JSON.stringify(configuration))
  const parameters = ['pnpm@10.13.1', '--filter', '@testhub/desktop', 'exec', 'electron-builder', windows ? '--win' : '--mac', windows ? 'nsis' : 'dmg', '--' + process.arch, '--publish', 'never', '--config', file]
  const executable = windows ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe') : 'npx'
  // On Windows invoke the fixed command with paths in environment variables, never shell-interpolated paths.
  const command = windows ? ['-NoProfile', '-NonInteractive', '-Command', '& npx.cmd pnpm@10.13.1 --filter @testhub/desktop exec electron-builder --win nsis --x64 --publish never --config $env:TESTHUB_INSTALL_FIXTURE_CONFIG; exit $LASTEXITCODE'] : parameters
  const result = await run(executable, command, { cwd: root, env: { ...process.env, TESTHUB_INSTALL_FIXTURE_CONFIG: file, TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE: '1', CSC_IDENTITY_AUTO_DISCOVERY: 'false' }, timeout: 900000, maxBuffer: 8 * 1024 * 1024 })
  await fs.writeFile(path.join(output, label + '-build.log'), result.stdout + result.stderr)
  fixture[label] = path.join(destination, filename)
  await fs.access(fixture[label])
}
if (externalCandidate) fixture.candidate = externalCandidate
await fs.writeFile(path.join(output, 'fixtures.json'), JSON.stringify({ ...fixture, baselineCommit: metadata.commit, baselineSchemaVersion: old.desktopSchemaVersion, candidateSchemaVersion: manifest.desktopSchemaVersion, baselineShellArchiveSha256: metadata.shellArchiveSha256, baselineBackendSha256: old.backendSha256, candidateBackendSha256: manifest.backendSha256, distributionReady: false }, null, 2))
console.log(JSON.stringify(fixture))
