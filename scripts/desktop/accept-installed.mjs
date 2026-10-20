// Runs only against an exclusively owned installation; the existing user installation is never adopted.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import installationQA from '../../apps/desktop/build/installation-qa.cjs'
const run = promisify(execFile), args = process.argv.slice(2)
const value = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined
const fixtures = value('--fixtures') ? JSON.parse(await fs.readFile(value('--fixtures'), 'utf8')) : null
if ((!fixtures && (!value('--candidate') || !value('--baseline'))) || !value('--output')) throw new Error('Usage: accept-installed.mjs --baseline <old DMG|EXE> --candidate <new DMG|EXE> --output <summary.json> [--clean-runner-defaults]')
const candidate = await fs.realpath(fixtures?.candidate || value('--candidate')), baseline = await fs.realpath(fixtures?.baseline || value('--baseline'))
const clean = args.includes('--clean-runner-defaults')
if (clean && (process.env.GITHUB_ACTIONS !== 'true' || process.env.RUNNER_ENVIRONMENT !== 'github-hosted')) throw new Error('Exact defaults are restricted to fresh hosted runners')
if (!['darwin', 'win32'].includes(process.platform) || (process.platform === 'win32' && !clean)) throw new Error('Windows installation acceptance requires a fresh native hosted runner')
await fs.mkdir('.testhub', { recursive: true })
const directory = await fs.mkdtemp(path.resolve('.testhub/installed-acceptance-'))
const stateFile = path.join(directory, 'private-state.json'), output = path.resolve(value('--output'))
const name = 'TestHub-QA-' + crypto.randomUUID(), windows = process.platform === 'win32'
const installation = windows ? path.join(process.env.LOCALAPPDATA, 'Programs/TestHub') : clean ? '/Applications/TestHub.app' : path.join('/Applications', name, 'TestHub.app')
const app = windows ? path.join(installation, 'TestHub.exe') : installation
const checks = [], phases = [], installerHashes = {}
let ownsInstallation = false, summary, cleanupError
const hash = async file => crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex')
async function absent(file) { try { await fs.lstat(file); return false } catch (error) { if (error.code === 'ENOENT') return true; throw error } }
async function windowsState(resolveTargets = false) {
  // Never open a shortcut through COM while the copied NSIS uninstaller may be deleting it.
  const command = [
    "$ErrorActionPreference='Stop';",
    resolveTargets ? '$w=New-Object -ComObject WScript.Shell;' : '',
    "$folders=@(@{location='desktop';path=[Environment]::GetFolderPath('Desktop')},@{location='start-menu';path=[Environment]::GetFolderPath('Programs')});",
    "$links=@($folders | ForEach-Object { $p=Join-Path $_.path 'TestHub.lnk'; if(Test-Path -LiteralPath $p){ $item=@{location=$_.location};",
    resolveTargets ? '$shortcut=$w.CreateShortcut($p); try { $item.target=$shortcut.TargetPath } finally { [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut) };' : '',
    '$item } });',
    resolveTargets ? '[void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($w);' : '',
    "$keys=@(Get-ItemProperty 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*' -ErrorAction SilentlyContinue | Where-Object {$_.DisplayName -match '^TestHub( [0-9].*)?$'});",
    "@{shortcuts=@($links);registryEntries=$keys.Count;processes=@(Get-Process TestHub -ErrorAction SilentlyContinue).Count} | ConvertTo-Json -Depth 4 -Compress",
  ].join(' ')
  const result = await run(path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'), ['-NoProfile', '-NonInteractive', '-Command', command])
  return JSON.parse(result.stdout)
}
// Refuse installation before running any executable or installer when the destination already exists.
assert.equal(await absent(installation), true, 'Refusing a pre-existing installed application')
if (clean) assert.equal(await absent(path.join(installationQA.appData(), 'testhub-desktop-app')), true, 'Refusing existing default application data')
if (windows) {
  const before = await windowsState()
  assert.equal(before.registryEntries, 0); assert.equal(before.shortcuts.length, 0); assert.equal(before.processes, 0)
}
if (!clean && !windows) await fs.mkdir(path.dirname(installation), { recursive: false })
ownsInstallation = true
async function install(image) {
  installerHashes[image === baseline ? 'baseline' : 'candidate'] = await hash(image)
  if (windows) {
    // Assisted NSIS /S neither starts the app nor displays the wizard; no force-run flag is supplied.
    await run(image, ['/S'], { timeout: 300000, maxBuffer: 2 * 1024 * 1024 })
    assert.equal(await absent(app), false)
    const installed = await windowsState(true)
    assert.equal(installed.registryEntries, 1); assert.equal(installed.shortcuts.length, 2)
    assert.ok(installed.shortcuts.every(link => path.normalize(link.target).toLowerCase() === path.normalize(app).toLowerCase()))
    assert.equal(installed.processes, 0, 'Silent installation must not launch an unguarded visible application')
  } else {
    await run('hdiutil', ['verify', image], { timeout: 120000 })
    const mount = path.join(directory, 'mount'); await fs.mkdir(mount)
    let attached = false
    try {
      await run('hdiutil', ['attach', '-readonly', '-noautoopen', '-nobrowse', '-mountpoint', mount, image], { timeout: 60000 }); attached = true
      assert.equal((await fs.readdir(mount)).filter(n => n.endsWith('.app')).length, 1)
      const staged = path.join(directory, 'staged.app')
      await fs.cp(path.join(mount, 'TestHub.app'), staged, { recursive: true, verbatimSymlinks: true })
      // Verify the actual extracted payload before it replaces even an owned QA installation.
      await run('/usr/bin/codesign', ['--verify', '--deep', '--strict', staged], { timeout: 120000 })
      if (!await absent(installation)) await fs.rm(installation, { recursive: true })
      await fs.rename(staged, installation)
    } finally {
      if (attached) await run('hdiutil', ['detach', mount], { timeout: 60000 })
      await fs.rmdir(mount)
    }
  }
}
async function smoke(stage, label) {
  const report = path.join(directory, label + '.json')
  await run(process.execPath, ['scripts/desktop/smoke-packaged.mjs', '--app', app, '--output', report,
    '--lifecycle-state', stateFile, '--lifecycle-stage', stage, ...(clean ? ['--clean-runner-defaults'] : [])], { timeout: 420000, maxBuffer: 2 * 1024 * 1024 })
  const result = JSON.parse(await fs.readFile(report, 'utf8'))
  assert.equal(result.passed, true); assert.equal(result.asarUnchanged, true)
  assert.equal(result.nativeDefaultUserData, true); assert.equal(result.defaultUserDataRedirectedForQA, false)
  assert.equal(result.exactApplicationDefaults, clean)
  assert.match(result.asarSha256, /^[a-f0-9]{64}$/)
  const phase = { label, backendSha256: result.backendSha256, asarSha256: result.asarSha256, checks: result.checks.length, launches: result.launches }
  phases.push(phase)
  console.log('PASS installed lifecycle phase: ' + label)
  return phase
}
async function uninstall() {
  if (windows) {
    const files = await fs.readdir(installation), uninstaller = files.find(n => /^Uninstall .+\.exe$/.test(n))
    assert.ok(uninstaller, 'Installed NSIS uninstaller is required')
    await run(path.join(installation, uninstaller), ['/S'], { timeout: 180000 })
    // NSIS removes the directory before its copied uninstaller clears shortcuts and registry keys.
    const deadline = Date.now() + 60000
    let removed
    do {
      removed = await windowsState()
      if (await absent(installation) && removed.registryEntries === 0 && removed.shortcuts.length === 0 && removed.processes === 0) break
      await delay(100)
    } while (Date.now() < deadline)
    assert.equal(await absent(installation), true, 'Uninstaller must remove its installed directory')
    assert.equal(removed.registryEntries, 0, 'Uninstaller must remove its registry entries')
    assert.equal(removed.shortcuts.length, 0, 'Uninstaller must remove both shortcuts: ' + JSON.stringify(removed.shortcuts))
    assert.equal(removed.processes, 0, 'Uninstall must leave no application process')
  } else await fs.rm(installation, { recursive: true })
}
async function removeOwnedWorkspace() {
  if (await absent(stateFile)) return
  const record = JSON.parse(await fs.readFile(stateFile, 'utf8'))
  if (record.ownership) {
    try { await installationQA.removeSession(record.ownership) } catch (error) { if (error.code !== 'ENOENT') throw error }
    if (!windows) {
      const service = record.ownership.cleanRunnerDefaults ? 'testhub-desktop-app' : record.ownership.qaName
      try { await run('/usr/bin/security', ['delete-generic-password', '-s', service + ' Safe Storage', '-a', service + ' Key']) }
      catch (error) { if (error.code !== 44) throw error }
    }
  }
  await fs.rm(stateFile)
}
try {
  await install(candidate); const fresh = await smoke('seed', 'fresh-current'); await uninstall()
  if (fixtures) assert.equal(fresh.backendSha256, fixtures.candidateBackendSha256)
  checks.push('current installer creates a fresh native default workspace and preserves its new data on a second genuine launch')
  // The fresh-current fixture is owned; clear it before creating the immutable legacy upgrade fixture.
  await removeOwnedWorkspace()
  await install(baseline); const previous = await smoke('seed', 'baseline')
  if (fixtures) assert.equal(previous.backendSha256, fixtures.baselineBackendSha256)
  checks.push('real installer copied/extracted to owned Applications or NSIS default installation location; first launch and restart passed')
  const old = JSON.parse(await fs.readFile(stateFile, 'utf8'))
  await install(candidate); const upgraded = await smoke('verify', 'upgraded')
  if (fixtures) assert.equal(upgraded.backendSha256, fixtures.candidateBackendSha256)
  assert.notEqual(previous.backendSha256, upgraded.backendSha256, 'Upgrade requires distinct actual backend binaries')
  if (fixtures?.baselineShellArchiveSha256) assert.notEqual(previous.asarSha256, upgraded.asarSha256, 'Current shell repair must be tested against the actual previous shell')
  assert.equal(fresh.backendSha256, upgraded.backendSha256); assert.equal(fresh.asarSha256, upgraded.asarSha256)
  checks.push('replacement installation preserves exact business record, identity and encrypted secret bytes; new runtime marker and pre-upgrade snapshot committed')
  const current = JSON.parse(await fs.readFile(stateFile, 'utf8'))
  const data = await installationQA.verifySession(current.ownership)
  const dbHash = await hash(path.join(data, 'local-workspace/workspace.mv.db'))
  await uninstall()
  assert.equal(await hash(path.join(data, 'local-workspace/workspace.mv.db')), dbHash)
  assert.equal(await hash(path.join(data, 'local-workspace/workspace-secrets.enc')), old.secretSha256)
  checks.push('uninstall removes application while retaining exact database and encrypted identity data')
  await install(candidate); const reinstalled = await smoke('verify', 'reinstalled'); await uninstall()
  assert.equal(reinstalled.backendSha256, upgraded.backendSha256); assert.equal(reinstalled.asarSha256, upgraded.asarSha256)
  checks.push('reinstall after uninstall reopens retained identity and exact business record')
  summary = { passed: true, platform: process.platform, arch: process.arch, checks, phases, installerHashes, baselineCommit: fixtures?.baselineCommit, baselineShellArchiveSha256: fixtures?.baselineShellArchiveSha256,
    exactInstallationAndDataDefaults: clean, applicationsRootUsed: !windows, isolatedApplicationNameForLocalQA: !clean,
    workspacePathOverridden: false, finderOrInstallerWizardOpened: false, userInstalledApplicationChanged: false,
    formalSigningOrGatekeeperValidated: false, freshCurrentDefaultWorkspacePassed: true }
} finally {
  // Only an installation created after the exclusive absent check above may be cleaned.
  if (ownsInstallation && !await absent(installation)) {
    try { if (windows) await uninstall(); else await fs.rm(installation, { recursive: true }) }
    catch (error) { cleanupError = error }
  }
  if (!clean && !windows) await fs.rmdir(path.dirname(installation))
  try { await removeOwnedWorkspace() } catch (error) { cleanupError ||= error }
  await fs.rm(directory, { recursive: true, force: true })
}
if (cleanupError) throw cleanupError
await fs.mkdir(path.dirname(output), { recursive: true })
await fs.writeFile(output, JSON.stringify({ ...summary, ownedInstallationDataKeychainAndProcessesRemoved: true }, null, 2) + '\n')
console.log('PASS installed application upgrade, retained data on uninstall and reinstall; owned resources removed')
