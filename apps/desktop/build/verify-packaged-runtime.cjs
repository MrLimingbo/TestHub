'use strict'
const path = require('node:path')
const { verifyRuntime } = require('./skip-runtime-dependency-copy.cjs')
async function verifyPackaged(context, allowExperimental = false) {
  const platform = context.packager.platform.nodeName
  const arch = typeof context.arch === 'string' ? context.arch : ({ 1: 'x64', 3: 'arm64', 4: 'universal' })[context.arch]
  if (!arch) throw new Error('未支持的安装包架构。')
  const resources = platform === 'darwin' ? path.join(context.appOutDir, context.packager.appInfo.productFilename + '.app/Contents/Resources')
    : path.join(context.appOutDir, 'resources')
  for (const native of arch === 'universal' ? ['arm64', 'x64'] : [arch])
    await verifyRuntime(path.join(resources, 'local-runtime'), platform, native, allowExperimental)
}
exports.verifyPackaged = verifyPackaged
exports.default = context => verifyPackaged(context, process.env.TESTHUB_DESKTOP_EXPERIMENTAL_PACKAGE === '1')
