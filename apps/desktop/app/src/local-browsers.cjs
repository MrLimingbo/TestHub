'use strict'

const fs = require('node:fs/promises')
const { createReadStream } = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const { childEnvironment } = require('./local-runtime.cjs')
const execute = promisify(execFile)
const MARKER = '.testhub-installed.json'
const TYPES = ['chromium', 'firefox', 'webkit']

async function fingerprint(root) {
  const base = await fs.realpath(root), files = {}
  let count = 0, total = 0
  async function walk(directory) {
    if (!(await fs.lstat(directory)).isDirectory()) throw new Error('浏览器组件目录无效。')
    for (const name of (await fs.readdir(directory)).sort()) {
      if (directory === root && name === MARKER) continue
      const file = path.join(directory, name), stat = await fs.lstat(file)
      // Playwright creates this empty host-validation marker on first launch and refreshes its mtime later.
      if (directory === root && name === 'DEPENDENCIES_VALIDATED' && stat.isFile() && stat.size === 0) continue
      const relative = path.relative(root, file).split(path.sep).join('/')
      if (++count > 20000) throw new Error('浏览器组件文件数量超出限制。')
      if (stat.isSymbolicLink()) {
        const target = await fs.readlink(file), resolved = await fs.realpath(file)
        if (path.isAbsolute(target) || (resolved !== base && !resolved.startsWith(base + path.sep))) throw new Error('浏览器组件包含越界链接。')
        files[relative] = { link: target }
      } else if (stat.isDirectory()) await walk(file)
      else if (stat.isFile()) {
        total += stat.size
        if (total > 2 * 1024 ** 3) throw new Error('浏览器组件大小超出限制。')
        const hash = crypto.createHash('sha256')
        for await (const bytes of createReadStream(file)) hash.update(bytes)
        files[relative] = { sha256: hash.digest('hex') }
      } else throw new Error('浏览器组件包含无效文件。')
    }
  }
  await walk(root)
  return files
}

async function browserTool(bundle, args, { signal, cache, timeout = 120000 } = {}) {
  signal?.throwIfAborted()
  const call = execute(bundle.node, [bundle.playwrightTool, ...args], {
    env: { ...childEnvironment(), TESTHUB_DESKTOP_PARENT_WATCHDOG: '1', ...(cache ? { PLAYWRIGHT_BROWSERS_PATH: cache } : {}) },
    signal, timeout, windowsHide: true, maxBuffer: 2 * 1024 * 1024,
  })
  try { return JSON.parse((await call).stdout.trim()) }
  catch (error) {
    // Abort rejects execFile before close. Wait for extraction/probe handles to release before deleting staging files.
    const child = call.child
    if (child?.pid && child.exitCode === null && child.signalCode === null) await new Promise(resolve => {
      const timer = globalThis.setTimeout(() => child.kill('SIGKILL'), 5000)
      child.once('close', () => { globalThis.clearTimeout(timer); resolve() })
    })
    throw error
  }
}

class LocalBrowsers {
  constructor({ directory, bundle, fetcher = globalThis.fetch, tool = browserTool }) {
    this.directory = directory; this.bundle = bundle; this.fetcher = fetcher; this.tool = tool
    this.status = { phase: 'idle', message: '', receivedBytes: 0, totalBytes: 0 }
    this.plans = new Map()
  }
  update(phase, message, receivedBytes = 0, totalBytes = 0) { this.status = { phase, message, receivedBytes, totalBytes } }
  async plan(browser) {
    if (!TYPES.includes(browser)) throw new Error('不支持的浏览器类型。')
    if (!this.plans.has(browser)) this.plans.set(browser, await this.tool(this.bundle, ['describe', browser]))
    const plan = this.plans.get(browser)
    return { ...plan, cache: path.join(this.directory, `playwright-${plan.playwrightVersion}`, plan.platform) }
  }
  async componentInstalled(cache, component) {
    const root = path.join(cache, component.directory)
    try {
      if (!(await fs.lstat(root)).isDirectory()) throw new Error('浏览器组件目录无效。')
      const marker = path.join(root, MARKER), stat = await fs.lstat(marker)
      if (!stat.isFile() || stat.size > 8 * 1024 * 1024) throw new Error('浏览器安装记录无效。')
      const record = JSON.parse(await fs.readFile(marker, 'utf8'))
      if (record.archiveSha256 !== component.sha256 || JSON.stringify(record.files) !== JSON.stringify(await fingerprint(root)))
        throw new Error('浏览器组件校验失败，请恢复组件或重新安装组件；工作区数据未修改。')
      return true
    } catch (error) { if (error.code === 'ENOENT' && error.path === root) return false; throw error }
  }
  async installed(browser) {
    const plan = await this.plan(browser), ready = path.join(plan.cache, `.ready-${browser}.json`)
    let record
    try { record = JSON.parse(await fs.readFile(ready, 'utf8')) }
    catch (error) { if (error.code === 'ENOENT') return undefined; throw error }
    if (record.components !== plan.components.map(item => item.sha256).join(':')) return undefined
    for (const component of plan.components) if (!await this.componentInstalled(plan.cache, component)) return undefined
    return plan.cache
  }
  async available() {
    for (const browser of TYPES) {
      try { const cache = await this.installed(browser); if (cache) return cache }
      catch (error) { this.update('error', error.message) }
    }
    return undefined
  }
  ensure(browser) {
    if (this.pending && this.preparing !== browser) return Promise.reject(new Error('正在准备另一种浏览器，请完成后重试。'))
    if (!this.pending) {
      this.preparing = browser
      this.pending = this.install(browser).catch(error => {
        const cancelled = this.controller?.signal.aborted
        this.controller?.abort()
        this.update('error', cancelled ? '浏览器准备已取消，可以重新执行重试。' : `浏览器准备失败：${error.message}`)
        throw new Error(this.status.message)
      }).finally(() => { this.pending = undefined; this.controller = undefined; this.preparing = undefined })
    }
    return this.pending
  }
  async install(browser) {
    this.controller = new globalThis.AbortController()
    const signal = globalThis.AbortSignal.any([this.controller.signal, globalThis.AbortSignal.timeout(15 * 60 * 1000)])
    this.update('checking', '正在检查浏览器组件…')
    const plan = await this.plan(browser)
    await fs.mkdir(plan.cache, { recursive: true, mode: 0o700 })
    if (!(await fs.lstat(plan.cache)).isDirectory()) throw new Error('浏览器缓存目录无效。')
    const ready = await this.installed(browser)
    signal.throwIfAborted()
    if (ready) { this.update('ready', '浏览器已就绪'); return ready }
    for (const component of plan.components) {
      if (await this.componentInstalled(plan.cache, component)) continue
      const staging = await fs.mkdtemp(path.join(plan.cache, '.download-'))
      try {
        const archive = path.join(staging, 'component.zip')
        const label = component.name === 'ffmpeg' ? '录屏组件' : component.name === 'winldd' ? '浏览器运行依赖' : browser
        await this.download(component, archive, label, signal)
        this.update('installing', `下载校验通过，正在安装 ${label}…`)
        const destination = path.join(staging, component.directory)
        await this.tool(this.bundle, ['extract', component.name, archive, destination], { signal, cache: plan.cache })
        const files = await fingerprint(destination)
        await fs.writeFile(path.join(destination, MARKER), JSON.stringify({ archiveSha256: component.sha256, files }), { flag: 'wx', mode: 0o600 })
        signal.throwIfAborted()
        await fs.rename(destination, path.join(plan.cache, component.directory))
      } finally { await fs.rm(staging, { recursive: true, force: true }) }
    }
    this.update('verifying', '正在验证浏览器启动、截图和录屏…')
    await this.tool(this.bundle, ['verify', browser, plan.cache], { signal, cache: plan.cache, timeout: 60000 })
    signal.throwIfAborted()
    await fs.writeFile(path.join(plan.cache, `.ready-${browser}.json`), JSON.stringify({ components: plan.components.map(item => item.sha256).join(':') }), { mode: 0o600 })
    this.update('ready', '浏览器已就绪')
    return plan.cache
  }
  async download(component, archive, label, signal) {
    if (!Number.isInteger(component.bytes) || component.bytes < 1 || component.bytes > 400 * 1024 * 1024) throw new Error('浏览器组件大小无效。')
    let lastError
    for (const url of component.urls) {
      signal.throwIfAborted()
      const attempt = globalThis.AbortSignal.any([signal, globalThis.AbortSignal.timeout(120000)])
      try {
        this.update('downloading', `首次使用，正在下载 ${label}…`, 0, component.bytes)
        const response = await this.fetcher(url, { signal: attempt, redirect: 'follow', credentials: 'omit' })
        if (!response.ok || !response.body || (response.url && !response.url.startsWith('https://'))) {
          await response.body?.cancel()
          throw new Error(`组件下载失败（HTTP ${response.status}），请检查网络后重试。`)
        }
        const file = await fs.open(archive, 'w', 0o600), hash = crypto.createHash('sha256')
        let received = 0
        try {
          for await (const bytes of response.body) {
            attempt.throwIfAborted(); received += bytes.length
            if (received > component.bytes) throw new Error('浏览器下载大小与固定版本不符。')
            hash.update(bytes); await file.writeFile(bytes)
            this.update('downloading', `正在下载 ${label}…`, received, component.bytes)
          }
        } finally { await file.close() }
        if (received !== component.bytes || hash.digest('hex') !== component.sha256) throw new Error('浏览器下载文件 SHA-256 校验失败，请重试。')
        return
      } catch (error) { signal.throwIfAborted(); lastError = error }
    }
    throw lastError ?? new Error('浏览器下载地址未配置。')
  }
  cancel() { this.controller?.abort() }
  async stop() { this.cancel(); await this.pending?.catch(() => {}) }
}
module.exports = { LocalBrowsers, fingerprint, browserTool }
