'use strict'
const fs = require('node:fs/promises')
const { createReadStream } = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { promisify } = require('node:util')
const derive = promisify(crypto.scrypt)
const MAGIC = Buffer.from('THBK0001'), HEADER = 36, TAG = 16
const MAX_BYTES = 64 * 1024 ** 3, MAX_FILES = 100000, MAX_MANIFEST = 16 * 1024 ** 2
function passwordCheck(password) {
  if (typeof password !== 'string' || [...password].length < 12 || Buffer.byteLength(password) > 1024)
    throw new Error('备份密码至少需要 12 个字符，且不能超过 1024 字节。')
}
async function keyFor(password, salt) { passwordCheck(password); return derive(password, salt, 32, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 ** 2 }) }
function validName(name) {
  if (typeof name !== 'string' || name.length > 1500 || (name !== 'workspace.mv.db' && !name.startsWith('objects/'))
    || /[\\:]/.test(name) || [...name].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) return false
  return name.split('/').every(part => part && part !== '.' && part !== '..' && !/[. ]$/.test(part)
    && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part) && Buffer.byteLength(part) <= 255)
}
function validateSecrets(value) {
  if (value?.version !== 1 || !/^[a-f0-9]{64}$/.test(value.databasePassword ?? '') || !/^[A-Za-z0-9+/]{43}=$/.test(value.encryptionKey ?? ''))
    throw new Error('备份中的工作区密钥无效。')
}
function validateManifest(value) {
  if (value?.format !== 'testhub-workspace' || value.version !== 1 || !Number.isFinite(Date.parse(value.createdAt))
    || typeof value.databaseVersion !== 'string' || value.databaseVersion.length > 40 || !Array.isArray(value.files)
    || value.files.length < 1 || value.files.length > MAX_FILES) throw new Error('不支持或损坏的工作区备份。')
  validateSecrets(value.secrets)
  const names = new Set(); let total = 0
  for (const item of value.files) {
    const name = item?.name
    if (!validName(name) || names.has(name.toLowerCase()) || !Number.isSafeInteger(item.bytes) || item.bytes < 0 || !/^[a-f0-9]{64}$/.test(item.sha256 ?? '')) throw new Error('备份文件清单无效。')
    names.add(name.toLowerCase()); total += item.bytes
    if (total > MAX_BYTES) throw new Error('备份超过 64 GiB 限制。')
  }
  if (!names.has('workspace.mv.db')) throw new Error('备份缺少工作区数据库。')
  return total
}
async function inventory(directory) {
  const files = []; let total = 0
  async function add(relative) {
    if (!validName(relative)) throw new Error('工作区包含不可迁移的文件名。')
    const filename = path.join(directory, relative), stat = await fs.lstat(filename)
    if (!stat.isFile()) throw new Error('工作区数据不能包含符号链接或特殊文件。')
    total += stat.size
    if (++files.length > MAX_FILES || total > MAX_BYTES) throw new Error('工作区超过备份大小或文件数量限制。')
    const hash = crypto.createHash('sha256')
    for await (const bytes of createReadStream(filename)) hash.update(bytes)
    files[files.length - 1] = { name: relative, bytes: stat.size, sha256: hash.digest('hex') }
  }
  async function walk(relative) {
    const filename = path.join(directory, relative), stat = await fs.lstat(filename)
    if (!stat.isDirectory()) throw new Error('工作区数据目录不能是符号链接。')
    for (const entry of (await fs.readdir(filename, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const next = relative + '/' + entry.name
      if (entry.isDirectory()) await walk(next); else await add(next)
    }
  }
  if (!(await fs.lstat(directory)).isDirectory()) throw new Error('工作区目录无效。')
  await add('workspace.mv.db')
  try { await walk('objects') } catch (error) { if (error.code !== 'ENOENT' || error.path !== path.join(directory, 'objects')) throw error }
  return files
}
// The caller must stop every workspace writer before invoking exportWorkspace.
async function exportWorkspace({ directory, destination, password, secrets, databaseVersion, signal, progress = () => {} }) {
  passwordCheck(password); validateSecrets(secrets); signal?.throwIfAborted()
  const files = await inventory(directory)
  const manifest = { format: 'testhub-workspace', version: 1, createdAt: new Date().toISOString(), databaseVersion,
    secrets: { version: 1, databasePassword: secrets.databasePassword, encryptionKey: secrets.encryptionKey }, files }
  const total = validateManifest(manifest), encoded = Buffer.from(JSON.stringify(manifest))
  if (encoded.length > MAX_MANIFEST) throw new Error('备份文件清单过大。')
  const header = Buffer.concat([MAGIC, crypto.randomBytes(16), crypto.randomBytes(12)])
  const key = await keyFor(password, header.subarray(8, 24)), temporary = destination + '.partial-' + crypto.randomUUID()
  let output
  try {
    output = await fs.open(temporary, 'wx', 0o600)
    const cipher = crypto.createCipheriv('aes-256-gcm', key, header.subarray(24)); cipher.setAAD(header)
    await output.writeFile(header)
    const length = Buffer.alloc(4); length.writeUInt32BE(encoded.length)
    await output.writeFile(cipher.update(length)); await output.writeFile(cipher.update(encoded))
    let completed = 0
    for (const entry of files) {
      signal?.throwIfAborted()
      const hash = crypto.createHash('sha256'); let size = 0
      for await (const bytes of createReadStream(path.join(directory, entry.name))) {
        signal?.throwIfAborted(); size += bytes.length; hash.update(bytes)
        await output.writeFile(cipher.update(bytes)); completed += bytes.length; progress(completed, total)
      }
      if (size !== entry.bytes || hash.digest('hex') !== entry.sha256) throw new Error('备份期间工作区文件发生变化，请停止写入后重试。')
    }
    await output.writeFile(cipher.final()); await output.writeFile(cipher.getAuthTag()); await output.sync(); await output.close(); output = undefined
    signal?.throwIfAborted()
    // Exclusive copy works on removable filesystems without hard-link support and never overwrites an older backup.
    await fs.copyFile(temporary, destination, require('node:fs').constants.COPYFILE_EXCL)
    const published = await fs.open(destination, 'r'); try { await published.sync() } finally { await published.close() }
    return { createdAt: manifest.createdAt, fileCount: files.length, bytes: total, databaseVersion }
  } finally { key.fill(0); encoded.fill(0); await output?.close(); await fs.rm(temporary, { force: true }) }
}
// A new private staging directory is required. Nothing is activated until both GCM and every file hash pass.
async function importWorkspace({ source, directory, password, signal, progress = () => {} }) {
  passwordCheck(password); signal?.throwIfAborted()
  const input = await fs.open(source, 'r')
  let key, file, created = false
  try {
    const info = await input.stat()
    if (!info.isFile() || info.size < HEADER + TAG + 4 || info.size > MAX_BYTES + MAX_MANIFEST + 128) throw new Error('备份文件大小无效。')
    const header = Buffer.alloc(HEADER), tag = Buffer.alloc(TAG)
    await input.read(header, 0, HEADER, 0); await input.read(tag, 0, TAG, info.size - TAG)
    if (!header.subarray(0, 8).equals(MAGIC)) throw new Error('不是受支持的 TestHub 备份文件。')
    key = await keyFor(password, header.subarray(8, 24))
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, header.subarray(24)); decipher.setAAD(header); decipher.setAuthTag(tag)
    await fs.mkdir(directory, { mode: 0o700 }); created = true
    let pending = Buffer.alloc(0), manifestLength, manifest, index = 0, remaining = 0, hash, written = 0, total
    async function consume(chunk) {
      if (!manifest) {
        pending = Buffer.concat([pending, chunk]); chunk = Buffer.alloc(0)
        if (manifestLength === undefined && pending.length >= 4) {
          manifestLength = pending.readUInt32BE(0); pending = pending.subarray(4)
          if (manifestLength < 1 || manifestLength > MAX_MANIFEST) throw new Error('备份密码错误或备份已损坏。')
        }
        if (manifestLength === undefined || pending.length < manifestLength) return
        try { manifest = JSON.parse(pending.subarray(0, manifestLength).toString('utf8')) } catch { throw new Error('备份密码错误或备份已损坏。') }
        total = validateManifest(manifest); chunk = pending.subarray(manifestLength); pending = Buffer.alloc(0)
        if (HEADER + TAG + 4 + manifestLength + total !== info.size) throw new Error('备份数据长度不一致。')
      }
      while (index < manifest.files.length) {
        const entry = manifest.files[index]
        if (!file) {
          const destination = path.join(directory, entry.name)
          await fs.mkdir(path.dirname(destination), { recursive: true, mode: 0o700 })
          file = await fs.open(destination, 'wx', 0o600); remaining = entry.bytes; hash = crypto.createHash('sha256')
        }
        const count = Math.min(remaining, chunk.length), bytes = chunk.subarray(0, count)
        await file.writeFile(bytes); hash.update(bytes); remaining -= count; written += count; chunk = chunk.subarray(count); progress(written, total)
        if (remaining) break
        await file.sync(); await file.close(); file = undefined
        if (hash.digest('hex') !== entry.sha256) throw new Error('备份密码错误或文件校验失败。')
        index++
        if (!chunk.length && manifest.files[index]?.bytes !== 0) break
      }
      if (chunk.length) throw new Error('备份包含多余的数据。')
    }
    for await (const bytes of input.createReadStream({ start: HEADER, end: info.size - TAG - 1, autoClose: false })) {
      signal?.throwIfAborted(); await consume(decipher.update(bytes))
    }
    try { await consume(decipher.final()) } catch { throw new Error('备份密码错误或备份已损坏。') }
    if (!manifest || file || index !== manifest.files.length) throw new Error('备份内容不完整。')
    return manifest
  } catch (error) {
    await file?.close(); file = undefined
    if (created) await fs.rm(directory, { recursive: true, force: true })
    throw error
  } finally { key?.fill(0); await input.close() }
}
module.exports = { exportWorkspace, importWorkspace, inventory, passwordCheck, validateSecrets }
