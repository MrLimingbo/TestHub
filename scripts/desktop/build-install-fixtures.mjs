// Build two real installers from the same shell with the previous and current backend binaries.
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
const currentRuntime = path.resolve('.testhub/desktop-runtime', target)
const manifest = JSON.parse(await fs.readFile(path.join(currentRuntime, 'runtime.json'), 'utf8'))
await verifyIntegrity(currentRuntime, manifest)
if (await digest(baseline) === manifest.backendSha256) throw new Error('A distinct previous backend is required')
const baselineRoot = path.join(output, 'baseline-runtime'), copied = path.join(baselineRoot, target)
await fs.mkdir(baselineRoot); await fs.cp(currentRuntime, copied, { recursive: true, verbatimSymlinks: true })
await fs.copyFile(baseline, path.join(copied, manifest.backend))
const old = { ...manifest, backendSha256: await digest(baseline), distributionReady: false }
old.integrity = await inventory(copied)
await fs.writeFile(path.join(copied, 'runtime.json'), JSON.stringify(old, null, 2))
await verifyIntegrity(copied, old)
const baseConfig = JSON.parse(await fs.readFile(path.join(root, 'apps/desktop/package.json'), 'utf8')).build
const fixture = {}
const externalCandidate = args.includes('--candidate-installer') ? await fs.realpath(value('--candidate-installer')) : null
for (const [label, runtime] of [['baseline', baselineRoot], ...(!externalCandidate ? [['candidate', path.dirname(currentRuntime)]] : [])]) {
  const destination = path.join(output, label)
  const filename = windows ? 'TestHub-install-acceptance.exe' : 'TestHub-install-acceptance.dmg'
  const configuration = { ...globalThis.structuredClone(baseConfig), directories: { ...baseConfig.directories, app: path.join(root, 'apps/desktop/app'), buildResources: path.join(root, 'apps/desktop/build'), output: destination }, [windows ? 'win' : 'mac']: { ...baseConfig[windows ? 'win' : 'mac'],
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
await fs.writeFile(path.join(output, 'fixtures.json'), JSON.stringify({ ...fixture, baselineBackendSha256: old.backendSha256, candidateBackendSha256: manifest.backendSha256, distributionReady: false }, null, 2))
console.log(JSON.stringify(fixture))
