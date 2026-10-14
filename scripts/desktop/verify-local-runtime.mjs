import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
const run = promisify(execFile), root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(import.meta.url)
const { verifyRuntime } = require(path.join(root, 'apps/desktop/build/skip-runtime-dependency-copy.cjs'))
const { verifyIntegrity } = require(path.join(root, 'apps/desktop/build/runtime-integrity.cjs'))
const directory = path.join(root, '.testhub/desktop-runtime', `${process.platform}-${process.arch}`)
await verifyRuntime(path.dirname(directory), process.platform, process.arch, true)
const manifest = JSON.parse(await fs.readFile(path.join(directory, 'runtime.json'), 'utf8'))
if (!Number.isSafeInteger(manifest.desktopSchemaVersion) || manifest.desktopSchemaVersion < 1) throw new Error('缺少桌面数据库构建版本，请重新准备运行组件。')
const integrity = await verifyIntegrity(directory, manifest)
const [java, node] = await Promise.all([
  run(path.join(directory, manifest.java), ['-XshowSettings:properties', '-version'], { timeout: 30000 }),
  run(path.join(directory, manifest.node), ['-p', 'JSON.stringify({platform:process.platform,arch:process.arch})'], { timeout: 30000 }),
])
const javaArch = /os.arch\s*=\s*(\S+)/.exec(java.stderr)?.[1]
const arches = { aarch64: 'arm64', amd64: 'x64', x86_64: 'x64' }
const actualNode = JSON.parse(node.stdout)
if (!/version "21[.]/.test(java.stderr) || arches[javaArch] !== process.arch
  || actualNode.platform !== process.platform || actualNode.arch !== process.arch) throw new Error('内置 Java／Node 与目标系统架构不一致。')
for (const required of ['node/LICENSE', 'jre/legal/java.base/LICENSE', 'services/playwright-runner/node_modules/playwright/LICENSE',
  'services/playwright-runner/node_modules/playwright-core/LICENSE', 'licenses/electron/LICENSE', 'licenses/electron/LICENSES.chromium.html'])
  if (!Object.hasOwn(integrity.files, required)) throw new Error(`缺少运行时许可文件：${required}`)
console.log(`Verified ${process.platform}-${process.arch}: ${Object.keys(integrity.files).length} runtime files; Java and Node native; distributionReady=${manifest.distributionReady}`)
