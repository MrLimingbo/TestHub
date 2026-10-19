import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { nativeCommand, sha256 } from './verify-signed-artifacts.mjs'

const require = createRequire(import.meta.url)
const { verifyRuntime } = require('../../apps/desktop/build/skip-runtime-dependency-copy.cjs')

async function regular(file, directory = false) {
  const stat = await fs.lstat(file)
  if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile())) throw new Error('预览验收输入必须是普通文件或应用目录。')
}
async function inspectApp(app, arch, command) {
  await regular(app, true)
  // Metadata alone cannot detect an absent resource seal.
  await command('/usr/bin/codesign', ['--verify', '--deep', '--strict', app], '预览应用完整资源封装校验')
  const details = await command('/usr/bin/codesign', ['--display', '--verbose=4', app], '预览应用标识读取')
  const lines = details.stderr.split(/\r?\n/)
  if (lines.filter(line => line === 'Identifier=io.testhub.desktop').length !== 1
    || lines.filter(line => line.startsWith('Identifier=')).length !== 1
    || !lines.includes('Signature=adhoc') || lines.some(line => line.startsWith('Authority=')))
    throw new Error('预览应用标识或 ad-hoc 封装类型不匹配。')
  const executable = path.join(app, 'Contents/MacOS/TestHub')
  await command('/usr/bin/lipo', [executable, '-verify_arch', arch === 'x64' ? 'x86_64' : arch], '预览应用架构校验')
  const runtimes = path.join(app, 'Contents/Resources/local-runtime')
  await verifyRuntime(runtimes, 'darwin', arch, true)
  const manifestPath = path.join(runtimes, 'darwin-' + arch, 'runtime.json')
  const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'))
  // Experimental fixtures must also carry a complete byte inventory.
  if (!manifest.integrity?.files || Object.keys(manifest.integrity.files).length === 0) throw new Error('预览应用缺少运行时完整性清单。')
  return {
    executableSha256: await sha256(executable),
    asarSha256: await sha256(path.join(app, 'Contents/Resources/app.asar')),
    runtimeManifestSha256: await sha256(manifestPath),
    runtimeFileCount: Object.keys(manifest.integrity.files).length,
    backendSha256: manifest.backendSha256,
  }
}
export async function inspectPreview({ arch, app, installer }, command = nativeCommand) {
  if (!['arm64', 'x64'].includes(arch) || !app.endsWith('.app') || !installer.endsWith('.dmg')) throw new Error('Mac 预览验收架构或文件类型无效。')
  await regular(installer)
  const installerSha256 = await sha256(installer)
  const built = await inspectApp(app, arch, command)
  await command('/usr/bin/hdiutil', ['verify', installer], '预览磁盘映像完整性校验')
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-preview-image-')), mount = path.join(directory, 'mount')
  await fs.mkdir(mount)
  let attached = false, payload
  try {
    await command('/usr/bin/hdiutil', ['attach', '-readonly', '-noautoopen', '-nobrowse', '-mountpoint', mount, installer], '预览映像只读挂载')
    attached = true
    const applications = (await fs.readdir(mount)).filter(name => name.endsWith('.app'))
    if (applications.length !== 1 || applications[0] !== 'TestHub.app') throw new Error('预览映像必须只包含预期 TestHub 应用。')
    payload = await inspectApp(path.join(mount, 'TestHub.app'), arch, command)
    if (Object.keys(built).some(key => built[key] !== payload[key])) throw new Error('最终 DMG 应用与构建应用不一致。')
  } finally {
    // A failed detach must leave the mount intact, never recursively remove mounted contents.
    if (attached) await command('/usr/bin/hdiutil', ['detach', mount], '预览映像卸载')
    await fs.rmdir(mount)
    await fs.rmdir(directory)
  }
  if (installerSha256 !== await sha256(installer)) throw new Error('验收期间预览安装器发生变化。')
  return { ...payload, installerSha256, strictResourceSealVerified: true, runtimeIntegrityVerified: true,
    finalDmgPayloadVerified: true, developerIdVerified: false, notarizationVerified: false, gatekeeperAssessed: false }
}
export async function verifyPreviewAndWrite(options, inspect = inspectPreview) {
  if (process.platform !== 'darwin' || options.arch !== process.arch) throw new Error('Mac 预览验收必须使用匹配的原生系统与架构。')
  if (!/^[a-f0-9]{40}$/.test(options.revision || '')) throw new Error('预览验收需要完整源码提交摘要。')
  await fs.mkdir(path.dirname(options.output), { recursive: true })
  const output = await fs.open(options.output, 'wx', 0o600)
  const report = { version: 1, passed: false, platform: 'darwin', arch: options.arch, revision: options.revision, distribution: 'ad-hoc-preview' }
  try {
    Object.assign(report, await inspect(options), { passed: true })
    return report
  } catch (error) {
    report.failure = 'Mac preview verification did not complete; no preview upload approval'
    throw error
  } finally {
    try { await output.writeFile(JSON.stringify(report, null, 2) + '\n'); await output.sync() }
    finally { await output.close() }
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2), allowed = new Set(['--arch', '--app', '--installer', '--output', '--revision']), values = {}
    for (let index = 0; index < args.length; index += 2) {
      const key = args[index]
      if (!allowed.has(key) || !args[index + 1] || Object.hasOwn(values, key)) throw new Error('预览验收参数无效。')
      values[key] = args[index + 1]
    }
    if ([...allowed].some(key => !values[key])) throw new Error('Usage: verify-mac-preview.mjs --arch <arm64|x64> --app <TestHub.app> --installer <final.dmg> --output <new-summary.json> --revision <full-commit>')
    await verifyPreviewAndWrite({ arch: values['--arch'], app: path.resolve(values['--app']), installer: path.resolve(values['--installer']), output: path.resolve(values['--output']), revision: values['--revision'] })
    console.log('PASS Mac preview resource seals, runtime inventory and final DMG; no application launched')
  } catch (error) {
    console.error(error.code ? '预览验收文件操作失败（' + error.code + '）。' : error.message)
    process.exitCode = 1
  }
}
