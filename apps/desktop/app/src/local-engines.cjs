'use strict'

const fs = require('node:fs/promises')
const { createReadStream } = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const execute = promisify(execFile)
const { childEnvironment } = require('./local-runtime.cjs')

// Pinned official binary: https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.6.3.zip.sha512
const JMETER = Object.freeze({ version: '5.6.3', root: 'apache-jmeter-5.6.3',
  url: 'https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.6.3.zip',
  sha512: '387fadca903ee0aa30e3f2115fdfedb3898b102e6b9fe7cc3942703094bd2e65b235df2b0c6d0d3248e74c9a7950a36e42625fd74425368342c12e40b0163076' })
const MAX_DOWNLOAD = 120 * 1024 * 1024

async function digest(file, algorithm = 'sha256') {
  const hash = crypto.createHash(algorithm)
  for await (const bytes of createReadStream(file)) hash.update(bytes)
  return hash.digest('hex')
}
async function inventory(root) {
  const files = {}
  let total = 0, count = 0
  async function walk(directory) {
    if (!(await fs.lstat(directory)).isDirectory()) throw new Error('执行组件目录无效。')
    for (const name of (await fs.readdir(directory)).sort()) {
      const file = path.join(directory, name), stat = await fs.lstat(file)
      if (++count > 4096 || stat.isSymbolicLink()) throw new Error('执行组件包含无效文件。')
      if (stat.isDirectory()) await walk(file)
      else if (stat.isFile()) {
        total += stat.size
        if (total > 512 * 1024 * 1024) throw new Error('执行组件超过大小限制。')
        files[path.relative(root, file).split(path.sep).join('/')] = await digest(file)
      } else throw new Error('执行组件包含无效文件。')
    }
  }
  await walk(root)
  if (!files['bin/ApacheJMeter.jar'] || !files['bin/jmeter.properties']) throw new Error('JMeter 组件不完整。')
  return files
}

async function extractArchive(bundle, archive, destination, signal) {
  await execute(bundle.java, ['-Xmx256m', '-Dloader.main=io.testhub.platform.desktop.DesktopEngineArchiveTool',
    '-cp', bundle.backend, 'org.springframework.boot.loader.launch.PropertiesLauncher', archive, destination, JMETER.root],
  { env: childEnvironment(), signal, timeout: 120000, windowsHide: true, maxBuffer: 1024 * 1024 })
}
async function verifyEngine(bundle, home, signal) {
  const { stdout } = await execute(bundle.java, ['-Xms32m', '-Xmx256m', '-Djava.awt.headless=true',
    '-jar', path.join(home, 'bin/ApacheJMeter.jar'), '-v', '-j', path.join(path.dirname(home), 'verify.log')],
  { env: childEnvironment(), signal, timeout: 30000, windowsHide: true, maxBuffer: 1024 * 1024 })
  if (!stdout.includes(JMETER.version)) throw new Error('JMeter 组件版本验证失败。')
}

class LocalEngines {
  constructor({ directory, bundle, fetcher = globalThis.fetch, extract = extractArchive, verify = verifyEngine }) {
    this.directory = directory; this.bundle = bundle; this.fetcher = fetcher; this.extract = extract; this.verify = verify
    this.status = { phase: 'idle', message: '', receivedBytes: 0, totalBytes: 0 }
  }
  update(phase, message, receivedBytes = 0, totalBytes = 0) { this.status = { phase, message, receivedBytes, totalBytes } }
  async installed() {
    const container = path.join(this.directory, JMETER.root)
    try {
      if (!(await fs.lstat(container)).isDirectory()) throw new Error('JMeter 组件目录无效。')
      const marker = path.join(container, 'installed.json'), stat = await fs.lstat(marker)
      if (!stat.isFile() || stat.size > 1024 * 1024) throw new Error('JMeter 安装记录无效。')
      const saved = JSON.parse(await fs.readFile(marker, 'utf8'))
      const home = path.join(container, JMETER.root)
      if (saved.sha512 !== JMETER.sha512 || JSON.stringify(saved.files) !== JSON.stringify(await inventory(home)))
        throw new Error('JMeter 文件校验失败。请恢复组件目录或重新安装组件；工作区数据未修改。')
      return home
    } catch (error) { if (error.code === 'ENOENT' && error.path === container) return undefined; throw error }
  }
  ensure() {
    if (!this.pending) this.pending = this.install().catch(error => {
      const cancelled = this.controller?.signal.aborted
      this.controller?.abort()
      this.update('error', cancelled ? '组件准备已取消，可重新提交压测重试。' : `JMeter 准备失败：${error.message}`)
      throw new Error(this.status.message)
    }).finally(() => { this.pending = undefined; this.controller = undefined })
    return this.pending
  }
  async install() {
    this.controller = new globalThis.AbortController()
    const signal = globalThis.AbortSignal.any([this.controller.signal, globalThis.AbortSignal.timeout(10 * 60 * 1000)])
    this.update('checking', '正在校验本地 JMeter 组件…')
    await fs.mkdir(this.directory, { recursive: true, mode: 0o700 })
    if (!(await fs.lstat(this.directory)).isDirectory()) throw new Error('执行组件目录无效。')
    const existing = await this.installed()
    signal.throwIfAborted()
    if (existing) { this.update('ready', 'JMeter 已就绪'); return existing }
    const staging = await fs.mkdtemp(path.join(this.directory, '.jmeter-download-'))
    try {
      this.update('downloading', '首次使用，正在下载 JMeter 5.6.3（约 86 MB）…')
      const response = await this.fetcher(JMETER.url, { signal, redirect: 'error', credentials: 'omit' })
      if (!response.ok || !response.body) throw new Error(`下载失败（HTTP ${response.status}），请检查网络后重试。`)
      const total = Number(response.headers.get('content-length')) || 0
      if (total > MAX_DOWNLOAD) throw new Error('下载文件超过大小限制。')
      const archive = path.join(staging, 'jmeter.zip'), hash = crypto.createHash('sha512')
      let received = 0
      const file = await fs.open(archive, 'wx', 0o600)
      try {
        for await (const bytes of response.body) {
          signal.throwIfAborted()
          received += bytes.length
          if (received > MAX_DOWNLOAD) throw new Error('下载文件超过大小限制。')
          hash.update(bytes); await file.writeFile(bytes)
          this.update('downloading', '正在下载 JMeter…', received, total)
        }
      } finally { await file.close() }
      if (hash.digest('hex') !== JMETER.sha512) throw new Error('下载文件 SHA-512 校验失败，请重试。')
      this.update('installing', '下载校验通过，正在安装并验证 JMeter…')
      const destination = path.join(staging, 'extracted')
      await this.extract(this.bundle, archive, destination, signal)
      const home = path.join(destination, JMETER.root)
      await this.verify(this.bundle, home, signal)
      const files = await inventory(home)
      await fs.writeFile(path.join(destination, 'installed.json'), JSON.stringify({ sha512: JMETER.sha512, files }), { flag: 'wx', mode: 0o600 })
      signal.throwIfAborted()
      await fs.rename(destination, path.join(this.directory, JMETER.root))
      this.update('ready', 'JMeter 已就绪')
      return path.join(this.directory, JMETER.root, JMETER.root)
    } finally { await fs.rm(staging, { recursive: true, force: true }) }
  }
  cancel() { this.controller?.abort() }
  async stop() { this.cancel(); await this.pending?.catch(() => {}) }
}
module.exports = { LocalEngines, JMETER, inventory }
