/* global fetch, AbortSignal */
// Native JVM acceptance in a new filesystem workspace; no Electron window or user workspace is used.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import process from 'node:process'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
const exec = promisify(execFile), root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'output/qa-20261029/p1-fixes-r1'), directory = await fs.mkdtemp(path.join(root, '.testhub/p1-http-qa-'))
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
const bridge = `
import java.sql.*; import java.nio.file.*; import java.time.*;
class P1SqlBridge {
 public static void main(String[] args) throws Exception {
  try(var c=DriverManager.getConnection("jdbc:h2:file:"+Path.of(System.getenv("TESTHUB_DESKTOP_DATA_DIRECTORY")).resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE","sa",System.getenv("TESTHUB_LOCAL_DB_PASSWORD"))) {
   if(args[0].equals("seed")) {
    try(var s=c.prepareStatement("INSERT INTO membership(id,user_id,organization_id,role_code) VALUES('p1adminmembership',?,?,'SYSTEM_ADMIN')")){s.setString(1,args[1]);s.setString(2,args[3]);s.executeUpdate();}
    for(String id:new String[]{"p1-legacy","p1-managed-expired","p1-managed-active"}) {
     boolean active=id.endsWith("active"), managed=!id.equals("p1-legacy"); Instant date=active?Instant.now():Instant.parse("2020-01-01T00:00:00Z");
     try(var s=c.prepareStatement("INSERT INTO api_debug_history(id,organization_id,project_id,method,request_url,duration_ms,response_size,result,created_by,created_at,replay_draft_ciphertext,replay_expires_at,replay_retention_managed) VALUES(?,?,?,'GET','https://example.test/p1',1,0,'COMPLETED',?,?,?,?,?)")) {
      s.setString(1,id);s.setString(2,args[3]);s.setString(3,args[4]);s.setString(4,args[2]);s.setTimestamp(5,Timestamp.from(date));s.setString(6,"sealed-fixture-"+id);
      if(managed)s.setTimestamp(7,Timestamp.from(date.plus(Duration.ofDays(30))));else s.setNull(7,Types.TIMESTAMP);s.setBoolean(8,managed);s.executeUpdate();
     }
    }
   } else {
    int count=0;
    try(var s=c.createStatement();var rows=s.executeQuery("SELECT id,replay_draft_ciphertext FROM api_debug_history WHERE id LIKE 'p1-%'")) {
     while(rows.next()){count++;String id=rows.getString(1),cipher=rows.getString(2);if(id.equals("p1-managed-expired")?cipher!=null:!("sealed-fixture-"+id).equals(cipher))throw new AssertionError("retention boundary failed");}
    }
    if(count!=3)throw new AssertionError("summary rows changed");
   }
  }
 }
}`
try {
  await fs.mkdir(out, { recursive: true })
  const entries = (await exec('unzip', ['-Z1', jar])).stdout.split('\n'), h2 = entries.find(value => /^BOOT-INF\/lib\/h2-[\d.]+\.jar$/.test(value))
  assert.ok(h2, 'Packaged H2 dependency missing')
  const name = path.basename(h2), h2Version = name.slice(3, -4), h2Jar = path.join(os.homedir(), '.m2/repository/com/h2database/h2', h2Version, name)
  const source = path.join(directory, 'P1SqlBridge.java'); await fs.writeFile(source, bridge)
  const sql = (...args) => exec(java, ['--class-path', h2Jar, source, ...args], { env, maxBuffer: 1_000_000 })
  await start()
  const admin = browser(), one = browser(), two = browser(), password = crypto.randomBytes(24).toString('hex'), account = 'qa-' + crypto.randomUUID()
  const adminResult = await request(admin, '/api/v1/auth/register', 'POST', { account: 'qa-' + crypto.randomUUID(), email: crypto.randomUUID() + '@example.test', displayName: 'P1 QA管理员', password })
  const target = await request(one, '/api/v1/auth/register', 'POST', { account, email: crypto.randomUUID() + '@example.test', displayName: 'P1 QA目标', password })
  assert.equal(adminResult.status, 201); assert.equal(target.status, 201)
  await step('P1-HTTP-01', '两个真实浏览器会话可读取账号及桌面就绪', async () => {
    assert.equal((await request(two, '/api/v1/auth/login', 'POST', { account, password })).status, 200)
    assert.equal((await request(one, '/api/v1/auth/me')).status, 200); assert.equal((await request(two, '/api/v1/auth/me')).status, 200)
  })
  const access = (await request(one, '/api/v1/access/context')).body.data, org = access.organizations[0].id, project = access.projects[org][0].id
  await stop(); await sql('seed', adminResult.body.data.id, target.body.data.id, org, project); await start()
  await step('P1-HTTP-02', 'JVM重启后合法持久会话仍可用且桌面不要求Redis', async () => {
    assert.equal((await request(one, '/api/v1/auth/me')).status, 200); assert.equal((await request(two, '/api/v1/auth/me')).status, 200)
    assert.equal((await request(one, '/actuator/health/readiness')).status, 200)
  })
  await step('P1-HTTP-03', 'ApplicationReadyEvent补偿清理仅清新策略到期密文', async () => {
    const history = (await request(one, '/api/v1/projects/' + project + '/api-debug/history')).body.data
    assert.equal(history.length, 3)
    for (const id of ['p1-legacy', 'p1-managed-expired']) { const row = history.find(value => value.id === id); assert.equal(row.replayStatus, 'EXPIRED'); assert.equal(row.replayDraft, null) }
  })
  const nextPassword = crypto.randomBytes(24).toString('hex')
  await step('P1-HTTP-04', '管理员真实HTTP重置密码成功且自身保持登录', async () => {
    assert.equal((await request(admin, '/api/v1/accounts/' + target.body.data.id + '/password', 'PUT', { password: nextPassword })).status, 204)
    assert.equal((await request(admin, '/api/v1/auth/me')).status, 200)
  })
  // Do not touch either target cookie until after restart: proves persisted version invalidation, not client-side cookie removal.
  await stop(); await sql('verify'); await start()
  await step('P1-HTTP-05', '重置后重启仍拒绝两个旧Cookie', async () => {
    assert.equal((await request(one, '/api/v1/auth/me')).status, 401); assert.equal((await request(two, '/api/v1/auth/me')).status, 401)
  })
  await step('P1-HTTP-06', '旧密码不能新登录，新密码可登录', async () => {
    const fresh = browser(); assert.equal((await request(fresh, '/api/v1/auth/login', 'POST', { account, password })).status, 401)
    assert.equal((await request(fresh, '/api/v1/auth/login', 'POST', { account, password: nextPassword })).status, 200)
    assert.equal((await request(fresh, '/api/v1/auth/me')).status, 200)
  })
  await stop(); await step('P1-HTTP-07', '重复启动清理幂等，旧密文及未到期密文字节不变、摘要三行保留', () => sql('verify'))
} catch (error) { result.cases.push({ id: 'P1-HTTP-ERROR', status: 'FAIL', error: error.message }); process.exitCode = 1 }
finally {
  try { await stop() } catch (error) { result.cleanupError = error.message; process.exitCode = 1 }
  await fs.rm(directory, { recursive: true, force: true }); result.cleanup = true
  result.passed = result.cases.filter(row => row.status === 'PASS').length; result.failed = result.cases.filter(row => row.status === 'FAIL').length
  result.status = result.passed === 7 && result.failed === 0 && !result.cleanupError ? 'PASS' : 'FAIL'
  await fs.writeFile(path.join(out, 'desktop-http-results.json'), JSON.stringify(result, null, 2) + '\n')
}
