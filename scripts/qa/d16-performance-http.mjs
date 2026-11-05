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
const out = path.join(root, 'output/qa-20261102/d16-performance-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/d16-performance-http-qa-'))
const java = path.join(process.env.JAVA_HOME, 'bin/java'), jar = path.join(root, 'services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar')
const result = { headless: true, realPackagedJvm: true, desktopProfile: true, electronUpgradeTest: false, realTargetLoad: false, cases: [], cleanup: false }
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
const integers = ['threadCount', 'rampUpSeconds', 'durationSeconds', 'stepCount', 'stepIntervalSeconds', 'targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers']
const definition = (field, token) => {
  const stepped = ['stepCount', 'stepIntervalSeconds'].includes(field)
  const group = { name: 'QA', targetUrl: 'https://qa.example.test/qa', loadModel: stepped ? 'STEPPED_RAMP' : 'RAMP_UP', threadCount: 10, rampUpSeconds: 0, durationSeconds: 10, stepCount: stepped ? 2 : null, stepIntervalSeconds: stepped ? 1 : null, targetTps: 10, p95ThresholdMillis: 500, errorThresholdPercent: 2.5, expectedStatusCode: 200, rendezvousUsers: null, requestMethod: 'POST', requestHeaders: {}, queryParameters: {}, requestBody: '{"n":1}', responseContains: '001', dataSetCsv: null, [field]: 'RAW_TOKEN' }
  return JSON.stringify({ name: crypto.randomUUID(), description: '', status: 'READY', threadGroups: [group] }).replace('"RAW_TOKEN"', token)
}
try {
  await start()
  const client = browser(), account = 'd16-' + crypto.randomUUID(), password = crypto.randomUUID() + 'Aa1!'
  assert.equal((await request(client, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'D16隔离验收', password })).status, 201)
  const me = (await request(client, '/api/v1/auth/me')).body.data
  const project = Object.values((await request(client, '/api/v1/access/context')).body.data.projects).flat()[0].id
  const rootPath = '/api/v1/projects/' + project + '/performance', plans = rootPath + '/plans'
  let created
  await step('TC-D16PERF-01/02/03/04/05/06/07/08/09', '真实HTTP九字段等值整数规范化', async () => {
    for (const field of integers) {
      const value = field === 'expectedStatusCode' ? '200' : '10'
      for (const token of [value, value + '.0', value + 'e0']) { const r = await rawRequest(client, plans, 'POST', definition(field, token)); assert.equal(r.status, 200); assert.equal(r.body.data.threadGroups[0][field], Number(value)); if (field === 'threadCount') created = r.body.data }
    }
  })
  await step('TC-D16PERF-19/20/21/22/40/41/56', '小数错误类型和文本值拒绝且字段错误不泄露', async () => {
    const before = (await request(client, plans)).body.data.length
    for (const field of integers) for (const token of ['1.5', '1.0000000000000001', '"10"', 'true', '1e10000', 'NaN', 'Infinity']) assert.equal((await rawRequest(client, plans, 'POST', definition(field, token))).status, 400)
    for (const field of ['requestHeaders', 'queryParameters']) {
      const r = await rawRequest(client, plans, 'POST', definition(field, '{"D16_PRIVATE_KEY":true}')); assert.equal(r.status, 400); assert.ok(JSON.stringify(r.body).includes(field)); assert.ok(!JSON.stringify(r.body).includes('D16_PRIVATE_KEY'))
    }
    for (const field of ['requestBody', 'responseContains', 'dataSetCsv']) assert.equal((await rawRequest(client, plans, 'POST', definition(field, '123'))).status, 400)
    assert.equal((await request(client, plans)).body.data.length, before)
  })
  await step('TC-D16PERF-30/31/32/33/34', '错误率真小数和精度语义保持', async () => {
    const r = await rawRequest(client, plans, 'POST', definition('errorThresholdPercent', '2.50')); assert.equal(r.status, 200); assert.equal(r.body.data.threadGroups[0].errorThresholdPercent, 2.5)
    const before = (await request(client, plans)).body.data.length
    for (const token of ['2.501', '2.500', '100.01', '-0.01', '"2.50"']) assert.equal((await rawRequest(client, plans, 'POST', definition('errorThresholdPercent', token))).status, 400)
    assert.equal((await request(client, plans)).body.data.length, before)
    const empty = await rawRequest(client, plans, 'POST', definition('errorThresholdPercent', 'null')); assert.equal(empty.status, 200); assert.equal(empty.body.data.threadGroups[0].errorThresholdPercent, null)
  })
  await step('TC-D16PERF-35/36/37/44', '字符串保持且非法更新不改版本', async () => {
    const r = await rawRequest(client, plans, 'POST', definition('queryParameters', '{"id":"001","flag":"false","literal":"null"}')); assert.equal(r.status, 200); assert.equal(r.body.data.threadGroups[0].queryParameters.id, '001'); assert.equal(r.body.data.threadGroups[0].requestBody, '{"n":1}')
    const header = await rawRequest(client, plans, 'POST', definition('requestHeaders', '{"X-Code":"001"}')); assert.equal(header.status, 200); assert.equal(header.body.data.threadGroups[0].requestHeaders['X-Code'], '001')
    assert.equal((await rawRequest(client, plans + '/' + created.id, 'PUT', '{"expectedVersion":1,"plan":' + definition('threadCount', '1.5') + '}')).status, 400)
    const kept = (await request(client, plans + '/' + created.id)).body.data; assert.equal(kept.version, 1); assert.equal(kept.threadGroups[0].threadCount, 10)
  })
  await step('TC-D16PERF-47/49', '原始HTTP迁移及同步不能绕过', async () => {
    const before = (await request(client, plans)).body.data.length
    const snapshot = (await request(client, rootPath + '/transfer')).body.data
    const imported = JSON.stringify({ requestKey: crypto.randomUUID(), expectedUserId: me.id, snapshot }).replace('"threadCount":10', '"threadCount":1.5')
    assert.equal((await rawRequest(client, rootPath + '/transfer', 'POST', imported)).status, 400)
    const sync = (await request(client, rootPath + '/sync?expectedUserId=' + me.id)).body.data
    const synced = JSON.stringify({ requestKey: crypto.randomUUID(), expectedUserId: me.id, expectedRevision: sync.revision, operations: [{ operationId: crypto.randomUUID(), item: sync.items[0] }] }).replace(/"threadCount":\d+/, '"threadCount":1.5')
    assert.equal((await rawRequest(client, rootPath + '/sync', 'POST', synced)).status, 400)
    assert.equal((await request(client, plans)).body.data.length, before)
  })
  await stop(); await start()
  await step('TC-D16PERF-50', '自有JVM重启记录会话保持（非升级复验）', async () => {
    assert.equal((await request(client, '/api/v1/auth/me')).status, 200)
    const kept = (await request(client, plans + '/' + created.id)).body.data; assert.equal(kept.threadGroups[0].threadCount, 10); assert.equal(kept.threadGroups[0].errorThresholdPercent, 2.5)
  })
} catch (error) { result.error = error.message; process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  result.passed = result.cases.length; result.failed = result.error ? 1 : 0
  await fs.mkdir(out, { recursive: true }); await fs.writeFile(path.join(out, 'native-http.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n')
}
