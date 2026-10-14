'use strict'
const fs = require('node:fs/promises')
const { createReadStream } = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')

async function digest(file) {
  const hash = crypto.createHash('sha256')
  for await (const bytes of createReadStream(file)) hash.update(bytes)
  return hash.digest('hex')
}

async function inventory(directory) {
  const root = await fs.realpath(directory), files = Object.create(null)
  async function walk(relative, ancestors) {
    const absolute = path.join(root, relative), real = await fs.realpath(absolute)
    if (real !== root && !real.startsWith(root + path.sep)) throw new Error('运行组件包含指向包外的链接。')
    const stat = await fs.stat(absolute)
    if (stat.isDirectory()) {
      if (ancestors.has(real)) throw new Error('运行组件包含循环目录链接。')
      const next = new Set([...ancestors, real])
      for (const name of (await fs.readdir(absolute)).sort()) {
        if (!relative && name === 'runtime.json') continue
        await walk(path.join(relative, name), next)
      }
    } else if (stat.isFile()) {
      files[relative.split(path.sep).join('/')] = { size: stat.size, sha256: await digest(absolute) }
    } else throw new Error('运行组件包含不支持的文件类型。')
  }
  await walk('', new Set())
  return { version: 1, files }
}

async function verifyIntegrity(directory, manifest) {
  const expected = manifest.integrity
  if (!expected || expected.version !== 1 || !expected.files || Array.isArray(expected.files)
    || typeof expected.files !== 'object' || Object.keys(expected.files).length === 0) throw new Error('运行组件缺少完整性清单，请重新准备构建。')
  for (const [file, value] of Object.entries(expected.files)) {
    if (!file || file.includes('\\') || file.startsWith('/') || file.split('/').some(p => !p || p === '.' || p === '..')
      || /^[A-Za-z]:/.test(file) || file === 'runtime.json' || !value || !Number.isSafeInteger(value.size) || value.size < 0
      || !/^[a-f0-9]{64}$/.test(value.sha256 || '')) throw new Error('运行组件完整性清单字段无效。')
  }
  const actual = await inventory(directory)
  const keys = Object.keys(actual.files), declared = Object.keys(expected.files)
  if (keys.length !== declared.length || keys.some(file => !Object.hasOwn(expected.files, file)
    || actual.files[file].size !== expected.files[file].size || actual.files[file].sha256 !== expected.files[file].sha256))
    throw new Error('运行组件文件缺失、增加或内容已变更，请重新构建。')
  if (manifest.backendSha256 !== actual.files[manifest.backend]?.sha256) throw new Error('后端构建摘要与运行组件不一致。')
  return actual
}
module.exports = { digest, inventory, verifyIntegrity }
