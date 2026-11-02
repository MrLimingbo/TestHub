/* global fetch, AbortSignal */
// Native JVM acceptance in a new filesystem workspace; no Electron window or user workspace is used.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import process from 'node:process'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261102/d16-api-automation-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/d16-api-http-qa-'))
const java = path.join(process.env.JAVA_HOME, 'bin/java'), jar = path.join(root, 'services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar')
const result = { headless: true, realPackagedJvm: true, desktopProfile: true, electronUpgradeTest: false, cases: [], cleanup: false }
const env = { ...process.env }
for (const key of Object.keys(env)) if (key.startsWith('TESTHUB_') || key.startsWith('SPRING_')) delete env[key]
Object.assign(env, { SPRING_PROFILES_ACTIVE: 'desktop', TESTHUB_DESKTOP_DATA_DIRECTORY: directory, TESTHUB_LOCAL_DB_PASSWORD: crypto.randomBytes(24).toString('hex'),
  TESTHUB_DESKTOP_ALLOWED_ORIGINS: 'https://ui.example.test', TESTHUB_REGISTRATION_ENABLED: 'true' })
let child, origin, logHandle, launch = 0
async function start() {
  const file = path.join(directory, `backend-${++launch}.log`); logHandle = await fs.open(file, 'w')
  child = spawn(java, ['-jar', jar, '--server.port=0', '--testhub.registration.enabled=true', '--testhub.bootstrap.demo-enabled=false'], { cwd: root, env, stdio: ['ignore', logHandle.fd, logHandle.fd] })
  child.on('error', error => { result.launchError = error.message })
  for (let n = 0; n < 300; n++) {
    if (child.exitCode !== null || child.signalCode !== null || result.launchError) throw new Error('Owned QA JVM failed to start')
    const match = /Tomcat started on port (\d+)/.exec(await fs.readFile(file, 'utf8'))
    if (match) {
      origin = 'http://127.0.0.1:' + match[1]
      try { if ((await fetch(origin + '/actuator/health/readiness', { signal: AbortSignal.timeout(5_500) })).status === 200) return } catch { /* Wait for ApplicationReadyEvent. */ }
    }
    await delay(250)
  }
  throw new Error('Owned QA JVM did not become ready within 75 seconds')
}
async function stop() {
  if (!child) return
  const running = child; child = null
  if (running.exitCode === null && running.signalCode === null) {
    running.kill('SIGTERM')
    for (let n = 0; n < 150 && running.exitCode === null && running.signalCode === null; n++) await delay(100)
    if (running.exitCode === null && running.signalCode === null) { running.kill('SIGKILL'); await new Promise(resolve => running.once('exit', resolve)); throw new Error('Owned JVM failed to stop within 15 seconds') }
  }
  await logHandle?.close(); logHandle = null
}
function browser() { return { cookies: new Map() } }
async function request(client, url, method = 'GET', payload) {
  const headers = { Cookie: [...client.cookies].map(([key, value]) => key + '=' + value).join('; ') }
  if (payload !== undefined) {
    const csrf = await request(client, '/api/v1/auth/csrf')
    assert.equal(csrf.status, 200); headers[csrf.body.data.headerName] = csrf.body.data.token
    headers.Cookie = [...client.cookies].map(([key, value]) => key + '=' + value).join('; '); headers['Content-Type'] = 'application/json'
  }
  const response = await fetch(origin + url, { method, headers, body: payload === undefined ? undefined : JSON.stringify(payload), signal: AbortSignal.timeout(8_000) })
  for (const cookie of response.headers.getSetCookie()) {
    const [pair] = cookie.split(';'), equals = pair.indexOf('='); client.cookies.set(pair.slice(0, equals), pair.slice(equals + 1))
  }
  return { status: response.status, body: response.status === 204 ? null : await response.json() }
}
async function step(id, title, operation) { await operation(); result.cases.push({ id, title, status: 'PASS' }) }
async function rawRequest(client, url, method, body) {
  const csrf = await request(client, '/api/v1/auth/csrf'); assert.equal(csrf.status, 200)
  const headers = { Cookie: [...client.cookies].map(([k,v]) => k + '=' + v).join('; '), 'Content-Type': 'application/json', [csrf.body.data.headerName]: csrf.body.data.token }
  const response = await fetch(origin + url, { method, headers, body, signal: AbortSignal.timeout(8_000) })
  return { status: response.status, body: await response.json() }
}
const definition = (value, name = crypto.randomUUID()) => JSON.stringify({ name, description: '', environmentCode: 'qa', failureStrategy: 'STOP', variables: {}, preRequestScript: '', postResponseScript: '', steps: [{ id: 'request', type: 'REQUEST', name: 'HTTP', config: { method: 'GET', url: 'https://8.8.8.8/qa' } }, { id: 'wait', type: 'WAIT', name: 'WAIT', config: { delayMillis: 'RAW_TOKEN' } }] }).replace('"RAW_TOKEN"', value)
try {
  await start()
  const client = browser(), account = 'd16-' + crypto.randomUUID(), password = crypto.randomUUID() + 'Aa1!'
  assert.equal((await request(client, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'D16隔离验收', password })).status, 201)
  const project = Object.values((await request(client, '/api/v1/access/context')).body.data.projects).flat()[0].id
  const base = '/api/v1/projects/' + project, path = base + '/api-automation/scenarios'
  assert.equal((await request(client, base + '/environments', 'POST', { name: 'QA', environmentCode: 'qa', baseUrl: 'https://8.8.8.8', description: '', enabled: true, defaultEnvironment: false })).status, 200)
  let created
  await step('TC-D16API-01', '真实HTTP接受精确等值整数写法并规范化', async () => {
    for (const value of ['10', '10.0', '1e1']) { const r = await rawRequest(client, path, 'POST', definition(value)); assert.equal(r.status, 200); assert.equal(r.body.data.steps[1].config.delayMillis, 10); created = r.body.data }
  })
  await step('TC-D16API-04/05/06/07/08/09/10', '原始小数、微小小数、溢出、非有限JSON和错误类型均400且无新资产', async () => {
    const before = (await request(client, path)).body.data.length
    for (const value of ['1.5', '1.0000000000000001', '18446744073709551616', '1e10000', '"10"', '" 10 "', 'true', '[]', '{}', 'null', 'NaN', 'Infinity', '-Infinity']) assert.equal((await rawRequest(client, path, 'POST', definition(value))).status, 400, 'Raw payload class rejected')
    assert.equal((await request(client, path)).body.data.length, before)
  })
  await step('TC-D16API-12', '真实HTTP非法更新不改版本或WAIT', async () => {
    const r = await rawRequest(client, path + '/' + created.id, 'PUT', '{"expectedVersion":1,"scenario":' + definition('1.0000000000000001', created.name) + '}'); assert.equal(r.status, 400)
    const saved = (await request(client, path + '/' + created.id)).body.data; assert.equal(saved.version, 1); assert.equal(saved.steps[1].config.delayMillis, 10)
  })
  await stop(); await start()
  await step('TC-D16API-19', '自有JVM重启后已存合法整数及登录会话保持', async () => {
    assert.equal((await request(client, '/api/v1/auth/me')).status, 200)
    const saved = (await request(client, path + '/' + created.id)).body.data; assert.equal(saved.steps[1].config.delayMillis, 10)
  })
} catch (error) { result.error = error.message; process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  await fs.mkdir(out, { recursive: true }); await fs.writeFile(path.join(out, 'native-http.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n')
}
