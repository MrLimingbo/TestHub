// Native, isolated acceptance for the Web launcher. Never connects to a user's DB.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import process from 'node:process'
import net from 'node:net'
import crypto from 'node:crypto'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { infrastructureReady } from './start-metadata.mjs'

const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const basedir = process.env.TESTHUB_QA_MYSQL_BASE_DIR
const redisBinary = process.env.TESTHUB_QA_REDIS_SERVER
if (!basedir || !redisBinary) throw new Error('Set TESTHUB_QA_MYSQL_BASE_DIR and TESTHUB_QA_REDIS_SERVER to native MySQL 8.4 / Redis 7.4 installations')
await fs.access(path.join(basedir, 'bin/mysqld'))
await fs.access(redisBinary)
const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-startup-qa-'))
const socket = path.join(directory, 'mysql.sock')
const password = crypto.randomBytes(24).toString('hex')
const owned = [], handles = []
const summary = { passed: false, background: true, isolated: true, storage: 'filesystem', checks: [], cleaned: false }
const summaryPath = process.env.TESTHUB_START_QA_SUMMARY || path.join(root, '.testhub/startup-acceptance.json')
const env = { ...process.env }
for (const key of Object.keys(env)) if (/^(TESTHUB_|SPRING_|SERVER_|COMPOSE_)/.test(key)) delete env[key]
const port = async () => {
  const server = net.createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const value = server.address().port
  await new Promise(resolve => server.close(resolve))
  return value
}
async function service(command, args, name) {
  const handle = await fs.open(path.join(directory, name), 'w', 0o600)
  handles.push(handle)
  const child = spawn(command, args, { cwd: root, stdio: ['ignore', handle.fd, handle.fd] })
  child.done = new Promise(resolve => { child.once('close', resolve); child.once('error', () => resolve(1)) })
  owned.push(child)
  return child
}
async function command(executable, args, commandEnv = env) {
  try { return await execute(executable, args, { cwd: root, env: commandEnv, timeout: 300000, maxBuffer: 1024 * 1024 }) }
  catch (error) {
    await fs.writeFile(path.join(directory, 'command-error.log'), `${error.stdout || ''}\n${error.stderr || ''}`, { mode: 0o600 })
    throw new Error(`${path.basename(executable)} failed (exit ${error.code}); stage: ${summary.checks.at(-1) || 'initialization'}`)
  }
}
const launch = async args => {
  const result = await command('bash', ['./start.sh', ...args])
  process.stdout.write(result.stdout)
}
const pids = async () => Promise.all(['platform-api', 'web'].map(name => fs.readFile(path.join(directory, 'state', `${name}.pid`), 'utf8')))
let stopping = false
async function cleanup() {
  if (stopping) return
  stopping = true
  // Startup creates only this state's API/Web; infrastructure is independently owned.
  let stopError
  try { await command('bash', ['./start.sh', 'stop']) } catch (error) { stopError = error }
  for (const child of [...owned].reverse()) {
    if (child.exitCode === null) {
      child.kill('SIGTERM')
      await Promise.race([child.done, delay(15000, undefined, { ref: false })])
      if (child.exitCode === null) child.kill('SIGKILL')
    }
    await child.done
  }
  for (const handle of handles) await handle.close()
  // Retain diagnostic state if API/Web shutdown failed; never remove files
  // underneath an unconfirmed live process.
  if (stopError) throw stopError
  await fs.rm(directory, { recursive: true, force: true })
  summary.cleaned = true
}
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await cleanup(); process.exit(130) })
try {
  process.stdout.write('Initializing fresh native MySQL/Redis with random loopback ports and credentials...\n')
  const mysqlPort = await port(), redisPort = await port()
  const data = path.join(directory, 'mysql')
  await fs.mkdir(data)
  Object.assign(env, {
    TESTHUB_START_STATE_DIR: path.join(directory, 'state'),
    TESTHUB_START_API_PORT: String(await port()), TESTHUB_START_WEB_PORT: String(await port()),
    TESTHUB_DB_URL: `jdbc:mysql://127.0.0.1:${mysqlPort}/startup_qa?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`,
    TESTHUB_DB_USERNAME: 'root', TESTHUB_DB_PASSWORD: password,
    TESTHUB_REDIS_HOST: '127.0.0.1', TESTHUB_REDIS_PORT: String(redisPort), TESTHUB_REDIS_PASSWORD: password,
    TESTHUB_STORAGE_TYPE: 'filesystem', TESTHUB_STORAGE_DIRECTORY: path.join(directory, 'files'),
    TESTHUB_DESKTOP_DATA_DIRECTORY: path.join(directory, 'desktop'), TESTHUB_LOCAL_DB_PASSWORD: password,
    TESTHUB_BOOTSTRAP_DEMO_ENABLED: 'false', TESTHUB_ENCRYPTION_KEY: crypto.randomBytes(32).toString('base64'),
  })
  await command(path.join(basedir, 'bin/mysqld'), ['--no-defaults', '--initialize-insecure', `--basedir=${basedir}`, `--datadir=${data}`, `--log-error=${directory}/mysql-init.log`])
  const initFile = path.join(directory, 'init.sql')
  await fs.writeFile(initFile, `ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}';\nCREATE DATABASE startup_qa;\n`, { mode: 0o600 })
  await service(path.join(basedir, 'bin/mysqld'), ['--no-defaults', `--basedir=${basedir}`, `--datadir=${data}`, `--socket=${socket}`, `--port=${mysqlPort}`,
    '--bind-address=127.0.0.1', '--mysqlx=OFF', '--default-time-zone=+00:00', `--pid-file=${directory}/mysql.pid`, `--init-file=${initFile}`, `--log-error=${directory}/mysql-error.log`], 'mysql-stdout.log')
  const redisConfig = path.join(directory, 'redis.conf')
  await fs.writeFile(redisConfig, `bind 127.0.0.1\nport ${redisPort}\nrequirepass ${password}\nsave ""\nappendonly no\ndir ${directory}\n`, { mode: 0o600 })
  await service(redisBinary, [redisConfig], 'redis.log')
  let ready = false
  for (let attempt = 0; attempt < 120; attempt++) {
    if (owned.some(child => child.exitCode !== null)) throw new Error('Owned QA infrastructure exited')
    if (await infrastructureReady(env)) { ready = true; break }
    await delay(500)
  }
  assert.ok(ready, 'owned infrastructure ready')
  await fs.rm(initFile)
  summary.checks.push('isolated-infrastructure')
  await launch(['doctor', '--with-registration'])
  await launch(['--with-registration'])
  await launch(['status'])
  summary.checks.push('real-api-vite-health-csrf')
  const web = `http://127.0.0.1:${env.TESTHUB_START_WEB_PORT}`
  const cookies = new Map()
  async function request(url, options = {}) {
    const response = await globalThis.fetch(web + url, { ...options, signal: globalThis.AbortSignal.timeout(10000), headers: {
      Cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join('; '), ...options.headers,
    } })
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';')[0], split = pair.indexOf('=')
      cookies.set(pair.slice(0, split), pair.slice(split + 1))
    }
    assert.ok(response.ok, `${url} returned ${response.status}`)
    return response
  }
  const getData = async url => (await (await request(url)).json()).data
  assert.equal((await getData('/api/v1/auth/capabilities')).registrationEnabled, true)
  const csrf = await getData('/api/v1/auth/csrf')
  const account = `startupqa${Date.now()}`
  const created = await (await request('/api/v1/auth/register', { method: 'POST', headers: {
    'Content-Type': 'application/json', [csrf.headerName]: csrf.token,
  }, body: JSON.stringify({ account, email: `${account}@example.test`, displayName: 'Startup QA', password }) })).json()
  assert.equal((await getData('/api/v1/auth/me')).id, created.data.id)
  assert.ok((await (await request('/login')).text()).includes('/src/main.ts'))
  summary.checks.push('registration-session-web-page')
  const initial = await pids()
  await launch(['--with-registration'])
  assert.deepEqual(await pids(), initial)
  summary.checks.push('healthy-start-reuses-processes')
  await launch(['restart', '--with-registration'])
  assert.notDeepEqual(await pids(), initial)
  assert.equal((await getData('/api/v1/auth/me')).id, created.data.id)
  // A fresh login after restart verifies the account was persisted in MySQL.
  cookies.clear()
  const loginCsrf = await getData('/api/v1/auth/csrf')
  await request('/api/v1/auth/login', { method: 'POST', headers: {
    'Content-Type': 'application/json', [loginCsrf.headerName]: loginCsrf.token,
  }, body: JSON.stringify({ account, password }) })
  assert.equal((await getData('/api/v1/auth/me')).id, created.data.id)
  summary.checks.push('restart-preserves-session-and-account')
  await launch(['stop'])
  assert.equal(await infrastructureReady(env), true)
  summary.checks.push('stop-preserves-infrastructure')
  summary.passed = true
} finally {
  try { await cleanup() }
  finally {
    await fs.mkdir(path.dirname(summaryPath), { recursive: true })
    await fs.writeFile(summaryPath, JSON.stringify(summary, null, 2) + '\n', { mode: 0o600 })
    process.stdout.write(summary.cleaned ? 'Stopped owned services and removed the isolated database, Redis, files, credentials and logs.\n'
      : `Cleanup incomplete; isolated diagnostic state retained at ${directory}\n`)
  }
}
