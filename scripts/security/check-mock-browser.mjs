// Uses a fresh H2 workspace and a headless browser; never connects to an existing service.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'
import { createRequire } from 'node:module'
import { randomBytes } from 'node:crypto'
import process from 'node:process'
import { setTimeout as delay } from 'node:timers/promises'
import { setTimeout, clearTimeout } from 'node:timers'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new globalThis.URL('../..', import.meta.url)))
const require = createRequire(join(root, 'services/playwright-runner/package.json'))
const { chromium } = require('playwright')
assert.ok(process.env.JAVA_HOME, 'JAVA_HOME must point to JDK 21')
const directory = await mkdtemp(join(tmpdir(), 'testhub-security-browser-'))
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(TESTHUB_|SPRING_|SERVER_|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS)/.test(key)))
Object.assign(env, { SPRING_PROFILES_ACTIVE: 'desktop', SERVER_PORT: '0',
  TESTHUB_DESKTOP_DATA_DIRECTORY: directory, TESTHUB_LOCAL_DB_PASSWORD: randomBytes(32).toString('hex'),
  TESTHUB_ENCRYPTION_KEY: randomBytes(32).toString('base64') })
let browser, launchError, log = ''
const api = spawn(join(process.env.JAVA_HOME, 'bin/java'), ['-jar', join(root, 'services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar'),
  '--testhub.bootstrap.demo-enabled=true'], { env, stdio: ['ignore', 'pipe', 'pipe'] })
const stopped = new Promise(resolve => api.once('close', resolve))
api.on('error', error => { launchError = error })
api.stdout.on('data', data => { log = (log + data).slice(-100000) })
api.stderr.on('data', data => { log = (log + data).slice(-100000) })
try {
  let port
  for (let n = 0; n < 300; n++) {
    if (launchError) throw launchError
    port = log.match(/Tomcat started on port (\d+)/)?.[1]
    if (port && log.includes('Started TestHubApplication')) break
    if (api.exitCode !== null) throw new Error('Isolated API exited before readiness')
    await delay(200)
  }
  assert.ok(port, 'Isolated API did not start within 60 seconds')
  const baseURL = `http://127.0.0.1:${port}`
  browser = await chromium.launch({ headless: true, executablePath: process.env.TESTHUB_QA_BROWSER || undefined })
  async function session(account) {
    const context = await browser.newContext({ baseURL, acceptDownloads: true })
    const token = (await (await context.request.get('/api/v1/auth/csrf')).json()).data
    const response = await context.request.post('/api/v1/auth/login', {
      headers: { [token.headerName]: token.token }, data: { account, password: 'TestHub2026!' },
    })
    assert.equal(response.status(), 200)
    return context
  }
  const attacker = await session('tester'), victim = await session('admin')
  const access = (await (await attacker.request.get('/api/v1/access/context')).json()).data
  const project = Object.values(access.projects).flat()[0].id
  async function create(path, data) {
    const csrf = (await (await attacker.request.get('/api/v1/auth/csrf')).json()).data
    const response = await attacker.request.post(path, { headers: { [csrf.headerName]: csrf.token }, data })
    assert.equal(response.status(), 200)
    return (await response.json()).data
  }
  const server = await create(`/api/v1/projects/${project}/mock/servers`, { name: 'Security browser regression' })
  const publicPath = `/api/v1/mock/${server.publicKey}`
  for (const [path, type, body] of [
    ['/payload.js', 'application/javascript', 'window.mockExecuted = true'],
    ['/page', 'text/html', `<script src="${publicPath}/payload.js"></script>`],
  ]) await create(`/api/v1/projects/${project}/mock/servers/${server.id}/rules`, {
    name: path, path, httpMethod: 'GET', responseStatus: 200, responseBody: body,
    responseHeaders: { 'Content-Type': type, 'Content-Security-Policy': "default-src * 'unsafe-inline'" },
  })
  const page = await victim.newPage()
  await page.goto(`${baseURL}/api/v1/auth/me`)
  const blocked = await page.evaluate(src => new Promise(resolve => {
    const script = globalThis.document.createElement('script')
    script.onload = () => resolve(false); script.onerror = () => resolve(true)
    script.src = src; globalThis.document.body.append(script)
  }), `${baseURL}${publicPath}/payload.js`)
  assert.equal(blocked, true)
  assert.equal(await page.evaluate(() => globalThis.mockExecuted), undefined)
  const download = page.waitForEvent('download')
  await page.goto(`${baseURL}${publicPath}/page`).catch(() => {})
  await (await download).cancel()
  assert.equal(await page.evaluate(() => globalThis.mockExecuted), undefined)
  const identity = await victim.request.get('/api/v1/auth/me')
  assert.equal(identity.status(), 200)
  assert.equal((await identity.json()).data.account, 'admin')
  globalThis.console.log(JSON.stringify({ mockScriptBlocked: true, mockHtmlDownloaded: true, victimSessionIntact: true }))
} finally {
  await browser?.close()
  api.kill('SIGTERM')
  const forcedStop = setTimeout(() => api.kill('SIGKILL'), 10000)
  await stopped
  clearTimeout(forcedStop)
  await rm(directory, { recursive: true, force: true })
}
