'use strict'
const crypto = require('node:crypto')
const fs = require('node:fs/promises')
const path = require('node:path')
const { Buffer } = require('node:buffer')

class EncryptedSyncStore {
  constructor(directory, codec, { filename, validate, empty, label, maxPlain = 6 * 1024 * 1024, maxEncrypted = 8 * 1024 * 1024 }) {
    Object.assign(this, { file: path.join(directory, filename), codec, validate, empty, label, maxPlain, maxEncrypted })
  }
  async read() {
    let bytes, handle
    try {
      handle = await fs.open(this.file, 'r'); const stat = await handle.stat()
      if (!stat.isFile() || stat.size > this.maxEncrypted) throw new Error(`${this.label}同步记录超过大小限制或类型无效。`)
      bytes = await handle.readFile()
    } catch (error) { if (error.code === 'ENOENT') return this.empty(); throw error } finally { await handle?.close() }
    try { return this.validate(JSON.parse(await this.codec.decrypt(bytes))) }
    catch { throw new Error(`无法解密${this.label}同步记录，请解锁系统钥匙串；不要删除现有数据。`) }
  }
  async write(value) {
    this.validate(value)
    const encoded = JSON.stringify(value)
    if (Buffer.byteLength(encoded) > this.maxPlain) throw new Error(`${this.label}同步记录超过大小限制。`)
    const bytes = await this.codec.encrypt(encoded), temporary = this.file + '.partial-' + crypto.randomUUID()
    let file
    try {
      file = await fs.open(temporary, 'wx', 0o600); await file.writeFile(bytes); await file.sync(); await file.close(); file = undefined
      await fs.rename(temporary, this.file)
      if (process.platform !== 'win32') {
        const directory = await fs.open(path.dirname(this.file), 'r'); try { await directory.sync() } finally { await directory.close() }
      }
    } finally { await file?.close(); await fs.rm(temporary, { force: true }) }
  }
}
module.exports = { EncryptedSyncStore }
