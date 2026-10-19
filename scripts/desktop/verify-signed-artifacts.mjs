import fs from 'node:fs/promises'
import { createReadStream } from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import os from 'node:os'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import policy from '../../apps/desktop/build/signature-policy.cjs'

const execute = promisify(execFile)
export async function nativeCommand(command, args, label, options = {}) {
  try { return await execute(command, args, { timeout: 120000, maxBuffer: 2 * 1024 * 1024, windowsHide: true, ...options }) }
  catch { throw new Error(label + '失败；未记录原始工具输出，请在受控签名环境核对。') }
}
export async function sha256(file) {
  const hash = crypto.createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}
async function regular(file, directory = false) {
  const stat = await fs.lstat(file)
  if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile())) throw new Error('签名验收输入必须是普通文件或应用目录。')
}
async function inspectMac(app, arch, teamId, command) {
  await regular(app, true)
  await command('/usr/bin/codesign', ['--verify', '--deep', '--strict', '--verbose=2', app], 'macOS 完整代码签名校验')
  const details = await command('/usr/bin/codesign', ['--display', '--verbose=4', app], 'macOS 签名元数据读取')
  const signature = policy.macSignature(details.stderr, teamId)
  await command('/usr/bin/lipo', [path.join(app, 'Contents/MacOS/TestHub'), '-verify_arch', arch === 'x64' ? 'x86_64' : arch], 'macOS 应用架构校验')
  await command('/usr/bin/xcrun', ['stapler', 'validate', app], 'macOS 应用公证票据校验')
  await command('/usr/sbin/spctl', ['--assess', '--type', 'execute', '--verbose=4', app], 'macOS 应用 Gatekeeper 校验')
  return { ...signature, codesignVerified: true, architectureVerified: true, stapledAppTicketValid: true, appGatekeeperAccepted: true,
    executableSha256: await sha256(path.join(app, 'Contents/MacOS/TestHub')), asarSha256: await sha256(path.join(app, 'Contents/Resources/app.asar')) }
}
export async function inspectArtifacts({ platform, arch, app, installer, teamId }, command = nativeCommand) {
  if (!['darwin', 'win32'].includes(platform) || !['arm64', 'x64'].includes(arch) || (platform === 'win32' && arch !== 'x64'))
    throw new Error('签名验收平台或架构无效。')
  await regular(installer)
  if (!installer.endsWith(platform === 'darwin' ? '.dmg' : '.exe') || !app.endsWith(platform === 'darwin' ? '.app' : '.exe'))
    throw new Error('签名验收文件类型不匹配。')
  if (path.resolve(app) === path.resolve(installer)) throw new Error('应用与安装器不能是同一文件。')
  const installerSha256 = await sha256(installer)
  let signature
  if (platform === 'darwin') {
    const built = await inspectMac(app, arch, teamId, command)
    await command('/usr/bin/hdiutil', ['verify', installer], 'macOS 磁盘映像完整性校验')
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-signature-image-')), mount = path.join(directory, 'mount')
    await fs.mkdir(mount)
    let attached = false
    try {
      await command('/usr/bin/hdiutil', ['attach', '-readonly', '-noautoopen', '-nobrowse', '-mountpoint', mount, installer], 'macOS 验收映像只读挂载')
      attached = true
      const applications = (await fs.readdir(mount)).filter(name => name.endsWith('.app'))
      if (applications.length !== 1 || applications[0] !== 'TestHub.app') throw new Error('安装映像必须只包含预期 TestHub 应用。')
      const payload = await inspectMac(path.join(mount, 'TestHub.app'), arch, teamId, command)
      if (built.executableSha256 !== payload.executableSha256 || built.asarSha256 !== payload.asarSha256)
        throw new Error('安装映像中的应用与已验收签名应用不一致。')
      signature = { ...payload, finalDmgPayloadVerified: true, diskImageGatekeeperAssessed: false }
    } finally {
      // Do not recursively remove a directory if detaching its read-only mount fails.
      if (attached) await command('/usr/bin/hdiutil', ['detach', mount], 'macOS 验收映像卸载')
      await fs.rmdir(mount)
      await fs.rmdir(directory)
    }
  } else {
    await regular(app)
    const executableSha256 = await sha256(app)
    const result = await command(path.join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
      ['-NoProfile', '-NonInteractive', '-File', fileURLToPath(new URL('./inspect-authenticode.ps1', import.meta.url))], 'Windows Authenticode 校验',
      { env: { ...process.env, TESTHUB_SIGNED_APPLICATION: path.resolve(app), TESTHUB_SIGNED_INSTALLER: path.resolve(installer) } })
    let records
    try { records = JSON.parse(result.stdout.replace(/^\uFEFF/, '')) } catch { throw new Error('Windows 签名检查未返回有效元数据。') }
    signature = { ...policy.windowsSignatures(records), executableSha256, installerPayloadExtracted: false }
    if (executableSha256 !== await sha256(app)) throw new Error('签名检查期间应用文件发生变化。')
  }
  if (installerSha256 !== await sha256(installer)) throw new Error('签名检查期间安装器文件发生变化。')
  return { signature, installer: { fileName: path.basename(installer), sha256: installerSha256, bytes: (await fs.stat(installer)).size } }
}
export async function verifyAndWrite(options, inspect = inspectArtifacts) {
  if (options.platform !== process.platform || options.arch !== process.arch) throw new Error('签名验收必须在匹配的原生系统与架构执行。')
  if (!/^[a-f0-9]{40}$/.test(options.revision || '')) throw new Error('签名验收需要完整源码提交摘要。')
  if (options.platform === 'darwin' && !/^[A-Z0-9]{10}$/.test(options.teamId || '')) throw new Error('缺少有效的 Apple Team ID。')
  await fs.mkdir(path.dirname(options.output), { recursive: true })
  // Reserve exclusively: a stale successful summary can never be overwritten or reused by this invocation.
  const output = await fs.open(options.output, 'wx', 0o600)
  const report = { version: 1, passed: false, platform: options.platform, arch: options.arch, revision: options.revision }
  try {
    Object.assign(report, await inspect(options), { passed: true })
    return report
  } catch (error) {
    report.failure = 'Native signature verification did not complete; no release approval'
    throw error
  } finally {
    try { await output.writeFile(JSON.stringify(report, null, 2) + '\n'); await output.sync() }
    finally { await output.close() }
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2)
    const allowed = new Set(['--platform','--arch','--app','--installer','--output','--revision'])
    const values = {}
    for (let index = 0; index < args.length; index += 2) {
      const key = args[index]
      if (!allowed.has(key) || !args[index + 1] || Object.hasOwn(values, key)) throw new Error('签名验收参数无效。')
      values[key] = args[index + 1]
    }
    if ([...allowed].some(key => !values[key])) throw new Error('Usage: verify-signed-artifacts.mjs --platform <darwin|win32> --arch <arm64|x64> --app <application> --installer <DMG|EXE> --output <new-summary.json> --revision <full-commit>')
    await verifyAndWrite({ platform: values['--platform'], arch: values['--arch'], app: path.resolve(values['--app']), installer: path.resolve(values['--installer']), output: path.resolve(values['--output']), revision: values['--revision'], teamId: process.env.APPLE_TEAM_ID })
    console.log('PASS native signature gate; verified scope is recorded in the artifact summary; no application launched')
  } catch (error) {
    console.error(error.code ? '签名验收文件操作失败（' + error.code + '）。' : error.message)
    process.exitCode = 1
  }
}
