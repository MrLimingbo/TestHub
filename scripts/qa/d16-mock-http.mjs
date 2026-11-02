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
const out = path.join(root, 'output/qa-20261102/d16-mock-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/d16-mock-http-qa-'))
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
const rule = (field, token) => JSON.stringify({ name: crypto.randomUUID(), httpMethod: 'GET', path: '/qa', priority: 10, requestQuery: {}, requestHeaders: {}, requestBodyMatch: 'ANY', requestBody: '', responseStatus: 200, responseHeaders: {}, responseBody: '{"ok":true}', [field]: 'RAW_TOKEN' }).replace('"RAW_TOKEN"', token)
try {
  await start()
  const client = browser(), account = 'd16-' + crypto.randomUUID(), password = crypto.randomUUID() + 'Aa1!'
  assert.equal((await request(client, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'D16隔离验收', password })).status, 201)
  const project = Object.values((await request(client, '/api/v1/access/context')).body.data.projects).flat()[0].id
  const base = '/api/v1/projects/' + project + '/mock/servers'
  const server = await request(client, base, 'POST', { name: 'D16 QA' }); assert.equal(server.status, 200)
  const rules = base + '/' + server.body.data.id + '/rules', shares = base + '/' + server.body.data.id + '/shares'
  let created
  await step('TC-D16MOCK-01/12', '真实HTTP接受精确等值整数写法并规范化', async () => {
    for (const value of ['10', '10.0', '1e1']) { const r = await rawRequest(client, rules, 'POST', rule('priority', value)); assert.equal(r.status, 200); assert.equal(r.body.data.priority, 10); created = r.body.data }
    const r = await rawRequest(client, rules, 'POST', rule('responseStatus', '2e2')); assert.equal(r.status, 200); assert.equal(r.body.data.responseStatus, 200)
  })
  await step('TC-D16MOCK-04/05/06/07/14/20/21/39', '原始JSON小数溢出错误类型及文本隐式转换均400且无新资产', async () => {
    const before = (await request(client, rules)).body.data.length
    for (const value of ['1.5', '1.0000000000000001', '18446744073709551616', '1e10000', '"10"', 'true', '[]', '{}', 'NaN', 'Infinity']) {
      const r = await rawRequest(client, rules, 'POST', rule('priority', value)); assert.equal(r.status, 400)
    }
    for (const token of ['200.5', '200.0000000000000001']) assert.equal((await rawRequest(client, rules, 'POST', rule('responseStatus', token))).status, 400)
    for (const field of ['requestQuery', 'requestHeaders', 'responseHeaders']) {
      const r = await rawRequest(client, rules, 'POST', rule(field, '{"synthetic-private-key":true}')); assert.equal(r.status, 400)
      assert.ok(JSON.stringify(r.body).includes(field)); assert.ok(!JSON.stringify(r.body).includes('synthetic-private-key'))
    }
    for (const field of ['requestBody', 'responseBody']) assert.equal((await rawRequest(client, rules, 'POST', rule(field, '123'))).status, 400)
    assert.equal((await request(client, rules)).body.data.length, before)
  })
  await step('TC-D16MOCK-08/10/11/26', '有效期严格校验且永久分享原语义不变', async () => {
    const before = (await request(client, shares)).body.data.length
    for (const token of ['1.5', '1.0000000000000001', '0', '366', '"10"']) assert.equal((await rawRequest(client, shares, 'POST', '{"name":"QA","expiresInDays":' + token + '}')).status, 400)
    assert.equal((await request(client, shares)).body.data.length, before)
    for (const token of ['10.0', '1e1', 'null']) { const r = await rawRequest(client, shares, 'POST', '{"name":"QA","expiresInDays":' + token + '}'); assert.equal(r.status, 200); assert.equal(r.body.data.expiresAt === null, token === 'null') }
    const r = await request(client, shares, 'POST', { name: 'QA' }); assert.equal(r.status, 200); assert.equal(r.body.data.expiresAt, null)
  })
  await step('TC-D16MOCK-17/19/24', '字符串文本原样保留且非法更新不改原记录', async () => {
    const r = await rawRequest(client, rules, 'POST', rule('requestQuery', '{"id":"001","value":"false"}')); assert.equal(r.status, 200); assert.equal(r.body.data.requestQuery.id, '001'); assert.equal(r.body.data.responseBody, '{"ok":true}')
    assert.equal((await rawRequest(client, rules + '/' + created.id, 'PUT', rule('priority', '1.0000000000000001'))).status, 400)
    const saved = (await request(client, rules)).body.data.find(r => r.id === created.id); assert.equal(saved.priority, 10); assert.equal(saved.name, created.name)
  })
  await stop(); await start()
  await step('TC-D16MOCK-31', '自有JVM重启后合法记录和登录会话保持（非升级复验）', async () => {
    assert.equal((await request(client, '/api/v1/auth/me')).status, 200)
    const saved = (await request(client, rules)).body.data.find(r => r.id === created.id); assert.equal(saved.priority, 10); assert.equal(saved.responseStatus, 200)
  })
} catch (error) { result.error = error.message; process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  result.passed = result.cases.length; result.failed = result.error ? 1 : 0
  await fs.mkdir(out, { recursive: true }); await fs.writeFile(path.join(out, 'native-http.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n')
}
