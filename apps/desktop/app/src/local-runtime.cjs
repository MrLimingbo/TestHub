'use strict'

const fs = require('node:fs/promises')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const { setTimeout: delay } = require('node:timers/promises')
const { prepareUpgrade, commitUpgrade, recoverUpgrade } = require('./workspace-upgrade.cjs')

async function privateDirectory(directory) {
  await fs.mkdir(directory, { recursive: true, mode: 0o700 })
  if ((await fs.lstat(directory)).isSymbolicLink()) throw new Error('本地工作区目录不能是符号链接。')
  if (process.platform !== 'win32') await fs.chmod(directory, 0o700)
}

async function loadSecrets(directory, codec) {
  await privateDirectory(directory)
  const filename = path.join(directory, 'workspace-secrets.enc')
  let value
  try {
    if (!(await fs.lstat(filename)).isFile()) throw new Error('本地工作区密钥文件无效。')
    value = JSON.parse(await codec.decrypt(await fs.readFile(filename)))
  } catch (error) {
    if (error.code !== 'ENOENT') throw new Error('无法解密本地工作区密钥；请解锁系统钥匙串或恢复工作区备份，原数据未修改。')
    try { await fs.access(path.join(directory, 'workspace.mv.db')); throw new Error('已有本地数据库但密钥文件缺失；请恢复原密钥，原数据未修改。') }
    catch (missing) { if (missing.code !== 'ENOENT') throw missing }
    value = { version: 1, databasePassword: crypto.randomBytes(32).toString('hex'), encryptionKey: crypto.randomBytes(32).toString('base64'), heartbeatKey: crypto.randomBytes(32).toString('hex') }
    const encrypted = await codec.encrypt(JSON.stringify(value))
    await fs.writeFile(filename, encrypted, { flag: 'wx', mode: 0o600 })
  }
  if (value?.version !== 1 || !/^[0-9a-f]{64}$/.test(value.databasePassword ?? '')
    || !/^[0-9a-f]{64}$/.test(value.heartbeatKey ?? '') || !/^[A-Za-z0-9+/]{43}=$/.test(value.encryptionKey ?? '')) throw new Error('本地工作区密钥格式不兼容。')
  return value
}

async function loadBundle(directory) {
  const root = await fs.realpath(directory)
  const manifest = JSON.parse(await fs.readFile(path.join(root, 'runtime.json'), 'utf8'))
  if (manifest.version !== 1 || manifest.platform !== process.platform || manifest.arch !== process.arch) throw new Error('本地运行组件与当前系统架构不匹配。')
  const result = {}
  for (const key of ['java', 'node', 'backend', 'frontend', 'apiRunner', 'jmeterRunner', 'jmeterWatchdog', 'playwrightRunner', 'playwrightTool']) {
    const relative = manifest[key]
    if (typeof relative !== 'string' || path.isAbsolute(relative)) throw new Error('本地运行组件清单无效。')
    const filename = await fs.realpath(path.resolve(root, relative))
    if (!filename.startsWith(root + path.sep)) throw new Error('本地运行组件超出安装目录。')
    const stat = await fs.stat(filename)
    if (key === 'frontend' ? !stat.isDirectory() : !stat.isFile()) throw new Error('本地运行组件不完整。')
    result[key] = filename
  }
  if (manifest.appiumRunner !== undefined) {
    if (typeof manifest.appiumRunner !== 'string' || path.isAbsolute(manifest.appiumRunner)) throw new Error('移动端运行组件清单无效。')
    const file = await fs.realpath(path.resolve(root, manifest.appiumRunner))
    if (!file.startsWith(root + path.sep) || !(await fs.stat(file)).isFile()) throw new Error('移动端运行组件超出安装目录或不完整。')
    result.appiumRunner = file
  }
  await fs.access(path.join(result.frontend, 'index.html'))
  return result
}

function childEnvironment() {
  // Do not let a developer's Spring/JVM/Node options redirect this private installation to other services.
  const allowed = /^(PATH|HOME|USERPROFILE|APPDATA|LOCALAPPDATA|SystemRoot|SYSTEMROOT|WINDIR|COMSPEC|TEMP|TMP|TMPDIR|LANG|LC_.*)$/
  return Object.fromEntries(Object.entries(process.env).filter(([key]) => allowed.test(key)))
}

class LocalRuntime {
  constructor({ bundleDirectory, dataDirectory, codec, onStatus = () => {}, onReady = async () => {}, allowedOrigins = [], appiumServerUrl = '', getJmeterHome = async () => undefined, getBrowserCache = async () => undefined, prepareBrowser = async () => {} }) {
    this.bundleDirectory = bundleDirectory; this.dataDirectory = dataDirectory; this.codec = codec
    this.prepareBrowser = prepareBrowser; this.getBrowserCache = getBrowserCache; this.optionalStarting = new Map(); this.getJmeterHome = getJmeterHome; this.onStatus = onStatus; this.onReady = onReady; this.allowedOrigins = allowedOrigins
    this.appiumServerUrl = appiumServerUrl; this.children = new Map(); this.phase = 'stopped'; this.generation = 0; this.restarts = []
  }
  status(phase, message) { this.phase = phase; this.message = message; this.onStatus({ phase, message }) }
  async start() {
    if (this.phase === 'ready') return this.url
    if (this.starting) return this.starting
    this.starting = this.launch().catch(async error => {
      await this.stopChildren()
      try { await recoverUpgrade(this.dataDirectory) }
      catch { error = new Error('本地工作区启动失败且升级回退未完成，请保留数据目录并重新打开 TestHub。') }
      this.status('error', error.message); throw error
    }).finally(() => { this.starting = undefined })
    return this.starting
  }
  async launch() {
    const generation = ++this.generation
    this.status('starting', '正在启动本地工作区…')
    const bundle = await loadBundle(this.bundleDirectory).catch(error => {
      if (error.code === 'ENOENT') throw new Error('本地运行组件不完整，请重新安装包含本地工作区的 TestHub 版本。')
      throw error
    })
    const upgrade = await prepareUpgrade(this.dataDirectory, bundle.backend)
    const secrets = await loadSecrets(this.dataDirectory, this.codec)
    if (generation !== this.generation) throw new Error('本地工作区启动已取消。')
    await privateDirectory(path.join(this.dataDirectory, 'logs'))
    const runDirectory = await fs.mkdtemp(path.join(this.dataDirectory, 'process-'))
    this.runDirectory = runDirectory
    const instance = crypto.randomUUID(), readyFile = path.join(runDirectory, 'ready.json')
    const brokerKey = crypto.randomBytes(32).toString('hex')
    this.bundle = bundle; this.secrets = secrets; this.brokerKey = brokerKey
    this.sessionKey = crypto.randomBytes(32).toString('hex')
    const environment = { ...childEnvironment(), TESTHUB_DESKTOP_DATA_DIRECTORY: this.dataDirectory,
      TESTHUB_LOCAL_DB_PASSWORD: secrets.databasePassword, TESTHUB_ENCRYPTION_KEY: secrets.encryptionKey,
      TESTHUB_DESKTOP_SESSION_KEY: this.sessionKey, TESTHUB_DESKTOP_BROKER_KEY: brokerKey,
      TESTHUB_DESKTOP_READY_FILE: readyFile, TESTHUB_DESKTOP_INSTANCE: instance, TESTHUB_DESKTOP_WATCH_PARENT: 'true',
      TESTHUB_DESKTOP_FRONTEND_DIRECTORY: bundle.frontend, TESTHUB_RUNNER_HEARTBEAT_KEY: secrets.heartbeatKey,
      TESTHUB_PERFORMANCE_RUNNER_HEARTBEAT_KEY: secrets.heartbeatKey, TESTHUB_UI_AUTOMATION_RUNNER_HEARTBEAT_KEY: secrets.heartbeatKey,
      TESTHUB_MOBILE_AUTOMATION_RUNNER_HEARTBEAT_KEY: secrets.heartbeatKey, TESTHUB_BOOTSTRAP_DEMO_ENABLED: 'false',
      TESTHUB_REGISTRATION_ENABLED: 'false', TESTHUB_API_RUNNER_HTTP_EXECUTION_ENABLED: String(this.allowedOrigins.length > 0),
      TESTHUB_DESKTOP_ALLOWED_ORIGINS: this.allowedOrigins.join(','), TESTHUB_CORS_ALLOWED_ORIGINS: '' }
    const backend = this.spawn('backend', bundle.java, ['-Xms64m', '-Xmx768m', '-jar', bundle.backend, '--spring.profiles.active=desktop', '--server.address=127.0.0.1', '--server.port=0'], environment)
    const deadline = Date.now() + 90000
    let origin
    while (Date.now() < deadline) {
      if (generation !== this.generation) throw new Error('本地工作区启动已取消。')
      if (backend.exited) throw new Error('本地服务启动失败，请查看工作区日志；原数据未修改。')
      try {
        const ready = JSON.parse(await fs.readFile(readyFile, 'utf8'))
        if (ready.instance !== instance || !Number.isInteger(ready.port) || ready.port < 1 || ready.port > 65535) throw new Error('本地服务启动握手无效。')
        origin = `http://127.0.0.1:${ready.port}`
        const health = await globalThis.fetch(origin + '/actuator/health', { redirect: 'error', signal: globalThis.AbortSignal.timeout(1500) })
        if (health.ok) break
      } catch (error) {
        if (error.message === '本地服务启动握手无效。') throw error
        if (error.code !== 'ENOENT' && !(error instanceof SyntaxError) && error.name !== 'TimeoutError' && error.name !== 'TypeError') throw error
      }
      origin = undefined
      await delay(200)
    }
    if (!origin) throw new Error('本地服务未能在 90 秒内就绪，请检查工作区日志。')
    this.url = origin
    const runnerId = 'desktop-api'
    const runnerStartedAfter = new Date().toISOString()
    const runner = this.spawn('api-runner', bundle.node, [bundle.apiRunner], { ...childEnvironment(),
      TESTHUB_DESKTOP_BROKER_URL: origin, TESTHUB_DESKTOP_BROKER_TOKEN: crypto.createHmac('sha256', brokerKey).update(`testhub.desktop-broker.v1:api:${runnerId}`).digest('hex'),
      TESTHUB_DESKTOP_PARENT_WATCHDOG: '1', RUNNER_ID: runnerId, RUNNER_HEARTBEAT_KEY: secrets.heartbeatKey,
      TESTHUB_CONTROL_PLANE_URL: origin, RUNNER_HTTP_EXECUTION_ENABLED: String(this.allowedOrigins.length > 0),
      RUNNER_DESKTOP_NETWORK_ACCESS: String(this.allowedOrigins.length > 0),
      RUNNER_ALLOWED_ORIGINS: this.allowedOrigins.join(','), RUNNER_PRECHECK_DELAY_MILLISECONDS: '0' })
    const runnerDeadline = Date.now() + 15000
    let runnerReady = false
    while (Date.now() < runnerDeadline) {
      if (generation !== this.generation) throw new Error('本地工作区启动已取消。')
      if (runner.exited || backend.exited) throw new Error('本地执行组件启动失败，请检查工作区日志。')
      const response = await globalThis.fetch(origin + '/internal/desktop/v1/status?startedAfter=' + encodeURIComponent(runnerStartedAfter), {
        headers: { 'X-TestHub-Desktop-Session': this.sessionKey }, redirect: 'error', signal: globalThis.AbortSignal.timeout(3000),
      })
      if (!response.ok) throw new Error(`本地执行组件状态检查失败（HTTP ${response.status}）。`)
      if ((await response.json()).data?.apiRunnerReady === true) { runnerReady = true; break }
      await delay(200)
    }
    if (!runnerReady) throw new Error('本地执行组件未能在 15 秒内就绪，请检查工作区日志。')
    const jmeterHome = await this.getJmeterHome()
    if (jmeterHome && this.allowedOrigins.length > 0) await this.startJmeter(jmeterHome, true)
    const browserCache = await this.getBrowserCache()
    if (browserCache && this.allowedOrigins.length > 0) await this.startPlaywright(browserCache, true)
    await commitUpgrade(upgrade)
    await this.onReady(origin)
    if (generation !== this.generation) throw new Error('本地工作区启动已取消。')
    if ([...this.children.values()].some(child => child.exited)) throw new Error('本地运行进程已退出，请检查日志。')
    this.status('ready', '本地工作区已就绪')
    return origin
  }
  startJmeter(home, launching = false) {
    return this.startOptionalRunner({ role: 'jmeter-runner', engine: 'jmeter', runnerId: 'desktop-jmeter', label: 'JMeter',
      script: this.bundle?.jmeterRunner, statusKey: 'jmeterRunnerReady', heartbeatKey: 'PERFORMANCE_RUNNER_HEARTBEAT_KEY',
      executionKey: 'RUNNER_JMETER_EXECUTION_ENABLED', environment: {
        JMETER_JAVA_BIN: this.bundle?.java, JMETER_HOME: home, JMETER_WATCHDOG_JAR: this.bundle?.jmeterWatchdog,
      } }, launching)
  }
  startPlaywright(cache, launching = false) {
    return this.startOptionalRunner({ role: 'playwright-runner', engine: 'playwright', runnerId: 'desktop-playwright', label: '浏览器',
      script: this.bundle?.playwrightRunner, statusKey: 'playwrightRunnerReady', heartbeatKey: 'UI_AUTOMATION_RUNNER_HEARTBEAT_KEY',
      executionKey: 'RUNNER_PLAYWRIGHT_EXECUTION_ENABLED', environment: { PLAYWRIGHT_BROWSERS_PATH: cache, TESTHUB_DESKTOP_BROWSER_PREPARATION: 'ipc' } }, launching)
  }
  async startAppium() {
    if (!this.appiumServerUrl) throw new Error('请在本地执行设置中配置 Appium 服务，连接设备或模拟器后重试。')
    if (!this.bundle?.appiumRunner) throw new Error('当前安装包缺少移动端运行器，请更新 TestHub。')
    const response = await globalThis.fetch(this.appiumServerUrl + '/status', { redirect: 'error', signal: globalThis.AbortSignal.timeout(5000) })
    if (!response.ok || (await response.json()).value?.ready !== true) throw new Error('Appium 服务未就绪，请检查服务地址及设备驱动。')
    return this.startOptionalRunner({ role: 'appium-runner', engine: 'appium', runnerId: 'desktop-appium', label: 'Appium',
      script: this.bundle.appiumRunner, statusKey: 'appiumRunnerReady', heartbeatKey: 'MOBILE_AUTOMATION_RUNNER_HEARTBEAT_KEY',
      executionKey: 'RUNNER_APPIUM_EXECUTION_ENABLED', environment: { RUNNER_APPIUM_SERVER_URL: this.appiumServerUrl,
        RUNNER_APPIUM_ALLOWED_SERVER_URLS: new URL(this.appiumServerUrl).origin } }, false)
  }
  async startOptionalRunner(options, launching) {
    if ((!launching && this.phase !== 'ready') || !this.url || (options.engine === 'appium' ? !this.appiumServerUrl : !this.allowedOrigins.length))
      throw new Error('请先在本地执行设置中授权测试目标，并等待工作区就绪。')
    const { role } = options
    if (this.optionalStarting.has(role)) return this.optionalStarting.get(role)
    if (this.children.get(role) && !this.children.get(role).exited) return
    const starting = this.launchOptionalRunner(options).finally(() => { this.optionalStarting.delete(role) })
    this.optionalStarting.set(role, starting)
    return starting
  }
  async launchOptionalRunner({ role, engine, runnerId, label, script, statusKey, heartbeatKey, executionKey, environment }) {
    const generation = this.generation, origin = this.url, since = new Date().toISOString()
    const workDirectory = path.join(this.dataDirectory, `${engine}-work`)
    await privateDirectory(workDirectory)
    if (generation !== this.generation) throw new Error('本地运行组件启动已取消。')
    const runner = this.spawn(role, this.bundle.node, [script], { ...childEnvironment(),
      TESTHUB_DESKTOP_BROKER_URL: origin, TESTHUB_DESKTOP_BROKER_TOKEN: crypto.createHmac('sha256', this.brokerKey)
        .update(`testhub.desktop-broker.v1:${engine}:${runnerId}`).digest('hex'),
      TESTHUB_DESKTOP_PARENT_WATCHDOG: '1', RUNNER_ID: runnerId, [heartbeatKey]: this.secrets.heartbeatKey,
      TESTHUB_CONTROL_PLANE_URL: origin, [executionKey]: 'true',
      RUNNER_ALLOWED_TARGET_ORIGINS: this.allowedOrigins.join(','), ...environment,
      RUNNER_WORK_DIRECTORY: workDirectory, RUNNER_HEARTBEAT_INTERVAL_SECONDS: '2', RUNNER_HEARTBEAT_TTL_SECONDS: '6' })
    try {
      const deadline = Date.now() + 15000
      while (Date.now() < deadline) {
        if (generation !== this.generation || runner.exited) throw new Error(`${label}运行组件已停止，请等待工作区恢复后重试。`)
        const response = await globalThis.fetch(origin + '/internal/desktop/v1/status?startedAfter=' + encodeURIComponent(since), {
          headers: { 'X-TestHub-Desktop-Session': this.sessionKey }, redirect: 'error', signal: globalThis.AbortSignal.timeout(3000) })
        if (!response.ok) throw new Error(`${label}状态检查失败（HTTP ${response.status}）。`)
        if ((await response.json()).data?.[statusKey] === true) return
        await delay(200)
      }
      throw new Error(`${label}运行组件未能在 15 秒内就绪。`)
    } catch (error) {
      runner.expectedExit = true
      if (!runner.exited) { runner.process.stdin.end(); runner.process.kill('SIGTERM'); await runner.done }
      if (this.children.get(role) === runner) this.children.delete(role)
      throw error
    }
  }
  spawn(role, executable, args, env) {
    const process = spawn(executable, args, { env, windowsHide: true, stdio: role === 'playwright-runner' ? ['pipe', 'pipe', 'pipe', 'ipc'] : ['pipe', 'pipe', 'pipe'] })
    const child = { process, exited: false }; this.children.set(role, child)
    if (role === 'playwright-runner') {
      let preparing = false
      process.on('message', async message => {
        if (message?.type !== 'prepare-browser' || !/^[a-f0-9-]{36}$/.test(message.id ?? '') ||
          !['chromium', 'firefox', 'webkit'].includes(message.browser)) return
        if (preparing) {
          if (process.connected) process.send({ type: 'browser-prepared', id: message.id, error: '正在准备浏览器，请稍后重试。' }, () => {})
          return
        }
        const generation = this.generation
        let error
        try {
          if (child.expectedExit || child.exited) throw new Error('浏览器运行组件正在关闭或准备中。')
          preparing = true
          await this.prepareBrowser(message.browser)
          if (generation !== this.generation) throw new Error('本地工作区已切换。')
        } catch (reason) { error = reason.message }
        finally { preparing = false }
        if (process.connected) process.send({ type: 'browser-prepared', id: message.id, error }, () => {})
      })
    }
    let logged = 0
    const filename = path.join(this.dataDirectory, 'logs', `${role}-${Date.now()}.log`)
    let writing = Promise.resolve()
    for (const stream of [process.stdout, process.stderr]) stream.on('data', bytes => {
      if (logged > 10 * 1024 * 1024) return
      logged += bytes.length
      writing = writing.then(() => fs.appendFile(filename, bytes, { mode: 0o600 })).catch(() => { /* Logging must not block process cleanup. */ })
    })
    child.done = new Promise(resolve => {
      const exited = () => {
        if (child.exited) return
        child.exited = true; resolve()
        if (this.phase === 'ready' && !child.expectedExit) {
          this.status('recovering', '本地运行进程意外退出，正在恢复…')
          void this.recover().catch(error => this.status('error', error.message))
        }
      }
      process.once('error', exited); process.once('exit', exited)
    })
    return child
  }
  async recover() {
    for (let attempt = 0; attempt < 3; attempt++) {
      const now = Date.now(); this.restarts = this.restarts.filter(time => now - time < 60000)
      if (this.restarts.length >= 3) break
      this.restarts.push(now)
      const generation = this.generation
      await this.stopChildren()
      if (generation !== this.generation) return
      await delay(500 * (attempt + 1))
      if (generation !== this.generation) return
      try { await this.start(); return } catch { /* Bounded recovery; retain the original database and keys. */ }
    }
    await this.stopChildren()
    this.status('error', '本地运行进程反复退出，已停止自动重试。请检查工作区日志后重新打开本地工作区。')
  }
  async authorizeSession(fetcher) {
    if (!this.url || !this.sessionKey) throw new Error('本地工作区尚未就绪。')
    const response = await fetcher(this.url + '/internal/desktop/v1/session', { method: 'POST', credentials: 'include', redirect: 'error',
      headers: { 'X-TestHub-Desktop-Session': this.sessionKey }, signal: globalThis.AbortSignal.timeout(10000) })
    if (!response.ok) throw new Error(`本地工作区会话建立失败（HTTP ${response.status}）。`)
    return response
  }
  async stopChildren() {
    for (const role of ['appium-runner', 'playwright-runner', 'jmeter-runner', 'api-runner', 'backend']) {
      const child = this.children.get(role)
      if (!child || child.exited) continue
      child.expectedExit = true
      child.process.stdin.end()
      await Promise.race([child.done, delay(10000, undefined, { ref: false })])
      if (!child.exited) { child.process.kill('SIGKILL'); await child.done }
    }
    this.children.clear()
    if (this.runDirectory) { await fs.rm(this.runDirectory, { recursive: true, force: true }); this.runDirectory = undefined }
    this.url = undefined; this.sessionKey = undefined; this.brokerKey = undefined; this.secrets = undefined
  }
  async stop() { ++this.generation; this.status('stopping', '正在关闭本地工作区…'); await this.stopChildren(); this.status('stopped', '本地工作区已关闭') }
}
module.exports = { LocalRuntime, loadSecrets, loadBundle, childEnvironment }
