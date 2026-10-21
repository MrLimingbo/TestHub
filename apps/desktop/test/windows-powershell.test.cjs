const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')

test('native PowerShell checks isolate inherited PS7 modules without changing the parent environment', async () => {
  const { windowsPowerShellEnvironment } = await import('../../../scripts/desktop/windows-powershell.mjs')
  const inherited = { SystemRoot: 'C:\\Windows', PSModulePath: 'C:\\Program Files\\PowerShell\\7\\Modules', psmodulepath: 'D:\\other', TEMP: 'D:\\Temp' }
  const original = { ...inherited }, env = windowsPowerShellEnvironment(inherited)
  assert.equal(env.PSModulePath, 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\Modules')
  assert.equal(env.psmodulepath, undefined)
  assert.equal(env.TEMP, original.TEMP)
  assert.deepEqual(inherited, original)
  assert.throws(() => windowsPowerShellEnvironment({}), /SystemRoot/)
})

test('Windows PowerShell loads signature and installation cmdlets under a PS7 parent', { skip: process.platform !== 'win32' }, async () => {
  const { windowsPowerShellEnvironment } = await import('../../../scripts/desktop/windows-powershell.mjs')
  const executable = path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe')
  const source = { ...process.env, PSModulePath: path.win32.join(process.env.ProgramFiles, 'PowerShell/7/Modules') }
  const { stdout } = await promisify(execFile)(executable, ['-NoProfile', '-NonInteractive', '-Command', "$ErrorActionPreference='Stop'; $signature=Get-AuthenticodeSignature -LiteralPath $env:TESTHUB_PS_EXECUTABLE; @{status=$signature.Status.ToString(); pid=(Get-Process -Id $PID).Id; registry=(Test-Path 'HKCU:\\Software')} | ConvertTo-Json -Compress"], {
    windowsHide: true, timeout: 30000, env: { ...windowsPowerShellEnvironment(source), TESTHUB_PS_EXECUTABLE: executable },
  })
  const result = JSON.parse(stdout)
  assert.equal(result.status, 'Valid')
  assert.ok(result.pid > 0)
  assert.equal(result.registry, true)
})
