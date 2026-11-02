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
const out = path.join(root, 'output/qa-20261030/p2-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/p2-http-qa-'))
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
try {
  await start()
  const client = browser(), account = 'p2-' + crypto.randomUUID(), password = crypto.randomUUID() + 'Aa1!'
  assert.equal((await request(client, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'P2隔离验收', password })).status, 201)
  const context = (await request(client, '/api/v1/access/context')).body.data
  const project = Object.values(context.projects).flat()[0].id, api = '/api/v1/projects/' + project
  await step('TC-P2-D01-03', '真实HTTP拒绝未定义环境普通参数', async () => {
    assert.equal((await request(client, api + '/parameters', 'POST', { environmentCode: 'missing', key: 'KEY', value: 'blocked' })).status, 400)
  })
  const root = (await request(client, api + '/api-design/catalogs', 'POST', { name: '源目录', sortOrder: 0 })).body.data
  assert.ok(root.id)
  const source = (await request(client, api + '/api-design/definitions', 'POST', { catalogId: root.id, method: 'GET', path: '/receipt', name: '回执接口', versionName: '1.0.0', headers: [], query: [], pathParameters: [], requestBody: '', responseBody: '{"kept":true}', exampleResponse: { statusCode: 201 } })).body.data
  assert.ok(source.id)
  const copyPath = api + '/api-design/catalogs/' + root.id
  const preview = await request(client, copyPath + '/copy-preview'); assert.equal(preview.status, 200)
  const payload = { requestKey: crypto.randomUUID(), expectedSourceDigest: preview.body.data.sourceDigest }
  const copied = await request(client, copyPath + '/copy', 'POST', payload); assert.equal(copied.status, 200)
  const before = (await request(client, api + '/api-design/definitions')).body.data.length
  await stop(); await start()
  await step('TC-P2-D10-07', '真实JVM重启后同Key返回已持久化回执且不再复制', async () => {
    assert.equal((await request(client, '/api/v1/auth/me')).status, 200)
    const replay = await request(client, copyPath + '/copy', 'POST', payload)
    assert.equal(replay.status, 200); assert.deepEqual(replay.body.data, copied.body.data)
    assert.equal((await request(client, api + '/api-design/definitions')).body.data.length, before)
    assert.equal((await request(client, copyPath + '/copy', 'POST', { ...payload, expectedSourceDigest: 'b'.repeat(64) })).status, 409)
  })
  await step('TC-P2-D09-08', '真实HTTP导出保留当前201响应', async () => {
    const exported = await request(client, api + '/api-design/interchange/openapi/export')
    assert.equal(exported.status, 200); assert.ok(exported.body.paths['/receipt'].get.responses['201'])
  })
  await step('TC-P2-D22-02', '真实HTTP版本0可保存、缺失428、旧版409', async () => {
    const path = '/api/v1/reports/quality/schedules'
    const input = { name: '版本计划', projectIds: [project], cronExpression: '0 0 0 1 1 ? 2099', timezone: 'UTC', periodDays: 7, enabled: false }
    const created = await request(client, path, 'POST', input); assert.equal(created.status, 200); assert.equal(created.body.data.version, 0)
    const target = path + '/' + created.body.data.id
    assert.equal((await request(client, target, 'PUT', input)).status, 428)
    const saved = await request(client, target, 'PUT', { ...input, expectedVersion: 0 }); assert.equal(saved.status, 200); assert.equal(saved.body.data.version, 1)
    assert.equal((await request(client, target, 'PUT', { ...input, expectedVersion: 0 })).status, 409)
    assert.equal((await request(client, target, 'PUT', { ...input, expectedVersion: 1.5 })).status, 400)
  })
} catch (error) { result.error = error.message; process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  await fs.mkdir(out, { recursive: true }); await fs.writeFile(path.join(out, 'native-http.json'), JSON.stringify(result, null, 2) + '\n')
  process.stdout.write(JSON.stringify(result) + '\n')
}
