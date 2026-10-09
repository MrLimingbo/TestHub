'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')

async function verifyRuntime(root, platform, arch, allowExperimental = false) {
  const directory = path.join(root, `${platform}-${arch}`)
  let manifest
  try { manifest = JSON.parse(await fs.readFile(path.join(directory, 'runtime.json'), 'utf8')) }
  catch (error) { throw new Error(`缺少 ${platform}-${arch} 本地运行组件，不能生成安装包：${error.message}`) }
  if (manifest.version !== 1 || manifest.platform !== platform || manifest.arch !== arch) throw new Error('本地运行组件架构或版本不匹配。')
  if (manifest.distributionReady !== true && !allowExperimental) throw new Error('本地运行组件尚未完成分发验证。仅内部验收可设置 TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE=1。')
  const realDirectory = await fs.realpath(directory)
  for (const key of ['java', 'node', 'backend', 'frontend', 'apiRunner', 'jmeterRunner', 'jmeterWatchdog', 'playwrightRunner', 'playwrightTool']) {
    if (typeof manifest[key] !== 'string' || path.isAbsolute(manifest[key])) throw new Error('本地运行组件清单无效。')
    const file = await fs.realpath(path.join(directory, manifest[key]))
    if (!file.startsWith(realDirectory + path.sep)) throw new Error('本地运行组件超出安装目录。')
    const stat = await fs.stat(file)
    if (key === 'frontend' ? !stat.isDirectory() : !stat.isFile()) throw new Error('本地运行组件不完整。')
  }
  await fs.access(path.join(directory, manifest.frontend, 'index.html'))
}
exports.verifyRuntime = verifyRuntime
exports.default = async context => {
  const platform = context.platform.nodeName
  const architectures = context.arch === 'universal' ? ['arm64', 'x64'] : [context.arch]
  const root = path.resolve(context.appDir, '../../../.testhub/desktop-runtime')
  for (const arch of architectures) await verifyRuntime(root, platform, arch, process.env.TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE === '1')
  // The Electron shell uses only built-ins; do not hoist unrelated monorepo dependencies.
  return false
}
