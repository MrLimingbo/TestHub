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
const out = path.join(root, 'output/qa-20261102/d16-web-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/d16-web-http-qa-'))
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
const profile = (field, token) => JSON.stringify({ name: crypto.randomUUID(), browser: 'chromium', baseUrl: 'https://ui.example.test', viewportWidth: 800, viewportHeight: 600, enabled: true, [field]: 'RAW' }).replace('"RAW"', token)
const scene = (profileId, type, field, token) => {
  const step = { id: 'one', type, name: type, selector: type === 'FILL' || type === 'ASSERT_TEXT' ? '#input' : null, value: type === 'FILL' ? '001' : null, expectedText: type === 'ASSERT_TEXT' ? '001' : null, timeoutMillis: 1000 }
  const data = { name: crypto.randomUUID(), description: '', browserProfileId: profileId, failureStrategy: 'STOP', variables: { id: '001' }, steps: [step] }
  if (field === 'variables') data.variables = 'RAW'; else step[field] = 'RAW'
  return JSON.stringify(data).replace('"RAW"', token)
}
try {
  await start()
  const client = browser(), account = 'd16-' + crypto.randomUUID(), password = crypto.randomUUID() + 'Aa1!'
  assert.equal((await request(client, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'D16隔离验收', password })).status, 201)
  const me = (await request(client, '/api/v1/auth/me')).body.data
  const project = Object.values((await request(client, '/api/v1/access/context')).body.data.projects).flat()[0].id
  const base = '/api/v1/projects/' + project + '/ui-automation'
  const p = await rawRequest(client, base + '/browser-profiles', 'POST', profile('viewportWidth', '800')); assert.equal(p.status, 200)
  let created
  await step('TC-D16WEB-01/02/05/06/07', '真实HTTP视口精确整数类型与默认', async () => {
    for (const field of ['viewportWidth', 'viewportHeight']) for (const token of ['800', '800.0', '8e2']) { const r = await rawRequest(client, base + '/browser-profiles', 'POST', profile(field, token)); assert.equal(r.status, 200); assert.equal(r.body.data[field], 800) }
    const before = (await request(client, base + '/browser-profiles')).body.data.length
    for (const token of ['800.5', '800.0000000000000001', '"800"', 'true', '[]', '1e10000', 'NaN', 'Infinity']) assert.equal((await rawRequest(client, base + '/browser-profiles', 'POST', profile('viewportWidth', token))).status, 400)
    assert.equal((await request(client, base + '/browser-profiles')).body.data.length, before)
    const defaults = await request(client, base + '/browser-profiles', 'POST', { name: crypto.randomUUID(), browser: 'chromium', baseUrl: 'https://ui.example.test', enabled: true }); assert.equal(defaults.status, 200); assert.equal(defaults.body.data.viewportWidth, 1440); assert.equal(defaults.body.data.viewportHeight, 900)
  })
  await step('TC-D16WEB-08/10/12/13/15/16', 'WAIT和其他步骤保持不同超时规则', async () => {
    for (const type of ['WAIT', 'FILL']) for (const token of ['1000', '1000.0', '1e3']) { const r = await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, type, 'timeoutMillis', token)); assert.equal(r.status, 200); assert.equal(r.body.data.steps[0].timeoutMillis, 1000); created = r.body.data }
    const before = (await request(client, base + '/scenarios')).body.data.length
    for (const type of ['WAIT', 'FILL']) for (const token of ['1000.5', '1000.0000000000000001', '"1000"', 'true']) assert.equal((await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, type, 'timeoutMillis', token))).status, 400)
    assert.equal((await request(client, base + '/scenarios')).body.data.length, before)
    assert.equal((await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, 'WAIT', 'timeoutMillis', 'null'))).status, 400)
    const optional = await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, 'FILL', 'timeoutMillis', 'null')); assert.equal(optional.status, 200); assert.equal(optional.body.data.steps[0].timeoutMillis, null)
  })
  await step('TC-D16WEB-17/19/20', '执行超时原始JSON拒绝及默认300', async () => {
    const route = base + '/scenarios/' + created.id + '/executions'
    for (const token of ['300', '300.0', '3e2']) assert.equal((await rawRequest(client, route, 'POST', '{"timeoutSeconds":' + token + '}')).status, 200)
    const before = (await request(client, base + '/executions')).body.data.length
    for (const token of ['300.5', '300.0000000000000001', '"300"', 'true', 'NaN']) assert.equal((await rawRequest(client, route, 'POST', '{"timeoutSeconds":' + token + '}')).status, 400)
    assert.equal((await request(client, base + '/executions')).body.data.length, before)
    const empty = await request(client, route, 'POST', {}); assert.equal(empty.status, 200); assert.equal(Date.parse(empty.body.data.deadlineAt) - Date.parse(empty.body.data.requestedAt), 300000)
  })
  await step('TC-D16WEB-22/23/24/25/26/43', '显式文本保真及字段错误隐私', async () => {
    const good = await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, 'FILL', 'variables', '{"id":"001","flag":"false","literal":"null"}')); assert.equal(good.status, 200); assert.equal(good.body.data.variables.id, '001'); assert.equal(good.body.data.steps[0].value, '001')
    for (const field of ['value', 'expectedText']) { const r = await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, field === 'value' ? 'FILL' : 'ASSERT_TEXT', field, '{"D16_PRIVATE_MARKER":true}')); assert.equal(r.status, 400); assert.ok(JSON.stringify(r.body).includes(field)); assert.ok(!JSON.stringify(r.body).includes('D16_PRIVATE_MARKER')) }
    for (const token of ['1', 'true', '[]', '{}']) assert.equal((await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, 'FILL', 'variables', '{"id":' + token + '}'))).status, 400)
    const nullable = await rawRequest(client, base + '/scenarios', 'POST', scene(p.body.data.id, 'FILL', 'variables', '{"id":null}')); assert.equal(nullable.status, 200); assert.equal(nullable.body.data.variables.id, '')
  })
  await step('TC-D16WEB-29/30', '非法编辑不改配置和场景版本', async () => {
    assert.equal((await rawRequest(client, base + '/browser-profiles/' + p.body.data.id, 'PUT', profile('viewportWidth', '800.5'))).status, 400)
    assert.equal((await rawRequest(client, base + '/scenarios/' + created.id, 'PUT', scene(p.body.data.id, 'FILL', 'timeoutMillis', '1000.5'))).status, 400)
    assert.equal((await request(client, base + '/browser-profiles')).body.data.find(x => x.id === p.body.data.id).version, 1)
    assert.equal((await request(client, base + '/scenarios')).body.data.find(x => x.id === created.id).version, 1)
  })
  await step('TC-D16WEB-33/34/35', '迁移拒绝且完整同步只允许精确等值数字', async () => {
    const snapshot = (await request(client, base + '/transfer')).body.data
    const imported = JSON.stringify({ requestKey: crypto.randomUUID(), expectedUserId: me.id, snapshot }).replace(/"viewportWidth":\d+/, '"viewportWidth":800.5'); assert.equal((await rawRequest(client, base + '/transfer', 'POST', imported)).status, 400)
    let sync = (await request(client, base + '/sync?expectedUserId=' + me.id)).body.data
    const body = () => JSON.stringify({ requestKey: crypto.randomUUID(), expectedUserId: me.id, expectedRevision: sync.revision, operations: sync.items.map(item => ({ operationId: crypto.randomUUID(), item })) })
    const exact = body().replace(/("(?:viewportWidth|viewportHeight|timeoutMillis)":)(\d+)/g, '$1$2.0'); assert.equal((await rawRequest(client, base + '/sync', 'POST', exact)).status, 200)
    sync = (await request(client, base + '/sync?expectedUserId=' + me.id)).body.data
    const before = sync.revision; const invalid = body().replace(/"viewportWidth":\d+/, '"viewportWidth":800.0000000000000001'); assert.equal((await rawRequest(client, base + '/sync', 'POST', invalid)).status, 400)
    assert.equal((await request(client, base + '/sync?expectedUserId=' + me.id)).body.data.revision, before)
  })
  await stop(); await start()
  await step('TC-D16WEB-39', '自有JVM重启后记录会话保持（非升级复验）', async () => { assert.equal((await request(client, '/api/v1/auth/me')).status, 200); const kept = (await request(client, base + '/scenarios')).body.data.find(x => x.id === created.id); assert.equal(kept.steps[0].value, '001'); assert.equal(kept.steps[0].timeoutMillis, 1000) })
} catch (error) { result.error = error.message; process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  result.passed = result.cases.length; result.failed = result.error ? 1 : 0
  await fs.mkdir(out, { recursive: true }); await fs.writeFile(path.join(out, 'native-http.json'), JSON.stringify(result, null, 2) + '\n'); process.stdout.write(JSON.stringify(result) + '\n')
}
