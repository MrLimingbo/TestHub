/* global fetch, AbortSignal */
// Native JVM acceptance in a new filesystem workspace; no Electron window or user workspace is used.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import process from 'node:process'
import os from 'node:os'
import { promisify } from 'node:util'
import { spawn, execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261030/p2-round2-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/p2r2-http-qa-'))
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
const exec = promisify(execFile)
// This bridge writes only the fresh owned QA database while its JVM is stopped.
const bridge = `
import java.sql.*; import java.nio.file.*; import java.time.*;
class P2R2SqlBridge {
 public static void main(String[] args) throws Exception {
  try(var c=DriverManager.getConnection("jdbc:h2:file:"+Path.of(System.getenv("TESTHUB_DESKTOP_DATA_DIRECTORY")).resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE","sa",System.getenv("TESTHUB_LOCAL_DB_PASSWORD"))) {
   try(var s=c.prepareStatement("INSERT INTO membership(id,user_id,organization_id,role_code) VALUES('p2r2-admin',?,?,'SYSTEM_ADMIN')")){s.setString(1,args[0]);s.setString(2,args[1]);s.executeUpdate();}
   for(int n=0;n<207;n++) {
    String action=n==0?"QA_OLDER_THAN_200":n==206?"QA_OTHER_PROJECT":"QA_RECENT";
    try(var s=c.prepareStatement("INSERT INTO audit_log(id,user_id,organization_id,project_id,action,resource_type,outcome,request_id,detail,created_at) VALUES(?,?,?,?,?,'QA','SUCCESS','qa-request',JSON '{}',?)")) {
     s.setString(1,"p2r2-event-"+n);s.setString(2,args[0]);s.setString(3,args[1]);s.setString(4,n==206?args[3]:args[2]);s.setString(5,action);s.setTimestamp(6,Timestamp.from(n==0?Instant.parse("2020-01-01T00:00:00Z"):Instant.now()));s.executeUpdate();
    }
   }
  }
 }
}`
try {
  await start()
  const client = browser(), account = 'p2r2-' + crypto.randomUUID(), password = crypto.randomUUID() + 'Aa1!'
  assert.equal((await request(client, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'P2R2隔离验收', password })).status, 201)
  const me = (await request(client, '/api/v1/auth/me')).body.data
  const context = (await request(client, '/api/v1/access/context')).body.data
  const organization = context.organizations[0].id, project = Object.values(context.projects).flat()[0].id
  const other = browser()
  assert.equal((await request(other, '/api/v1/auth/register', 'POST', { account: 'other-' + crypto.randomUUID(), email: crypto.randomUUID() + '@example.test', displayName: '隔离其他项目', password })).status, 201)
  const otherProject = Object.values((await request(other, '/api/v1/access/context')).body.data.projects).flat()[0].id
  await step('TC-P2R2-D04-04', '普通账号403、匿名401，元数据沿用既有管理员权限', async () => {
    assert.equal((await request(client, '/api/v1/audit-logs/actions')).status, 403)
    assert.equal((await request(browser(), '/api/v1/audit-logs/actions')).status, 401)
  })
  await stop()
  const entries = (await exec('unzip', ['-Z1', jar])).stdout.split('\n'), h2 = entries.find(value => /^BOOT-INF\/lib\/h2-[\d.]+\.jar$/.test(value))
  assert.ok(h2)
  const name = path.basename(h2), h2Jar = path.join(os.homedir(), '.m2/repository/com/h2database/h2', name.slice(3, -4), name)
  const source = path.join(directory, 'P2R2SqlBridge.java'); await fs.writeFile(source, bridge)
  await exec(java, ['--class-path', h2Jar, source, me.id, organization, project, otherProject], { env, maxBuffer: 1_000_000 })
  await start()
  assert.equal((await request(client, '/api/v1/auth/me')).status, 200)
  const base = '/api/v1/audit-logs', scoped = '?projectId=' + project
  await step('TC-P2R2-D04-01', '真实HTTP动作筛选不收缩独立动作集合', async () => {
    const all = await request(client, base + '/actions' + scoped)
    assert.equal(all.status, 200)
    assert.deepEqual((await request(client, base + '/actions' + scoped + '&action=QA_RECENT&limit=1')).body.data, all.body.data)
  })
  await step('TC-P2R2-D04-02', '真实持久化稀有动作超出200条仍在元数据中', async () => {
    const response = await request(client, base + scoped + '&limit=200'); assert.equal(response.status, 200)
    const rows = response.body.data
    assert.equal(rows.length, 200); assert.ok(!rows.some(row => row.action === 'QA_OLDER_THAN_200'))
    assert.ok((await request(client, base + '/actions' + scoped)).body.data.includes('QA_OLDER_THAN_200'))
  })
  await step('TC-P2R2-D04-03', '空筛选不影响去重排序动作集合', async () => {
    assert.deepEqual((await request(client, base + scoped + '&action=QA_ABSENT')).body.data, [])
    const actions = (await request(client, base + '/actions' + scoped)).body.data
    assert.deepEqual(actions, [...new Set(actions)].sort())
    assert.ok(!actions.includes('QA_OTHER_PROJECT'))
  })
  await step('TC-P2R2-D04-06', '元数据只返回动作字符串，原列表limit仍生效', async () => {
    assert.ok((await request(client, base + '/actions' + scoped)).body.data.every(value => typeof value === 'string'))
    assert.equal((await request(client, base + scoped + '&limit=1')).body.data.length, 1)
  })
} catch (error) { result.error = error.message; process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  await fs.mkdir(out, { recursive: true }); await fs.writeFile(path.join(out, 'native-http.json'), JSON.stringify(result, null, 2) + '\n')
  process.stdout.write(JSON.stringify(result) + '\n')
}
