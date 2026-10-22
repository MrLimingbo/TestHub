// Exercise the real launcher with an exclusively owned Compose project and data.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import net from 'node:net'
import crypto from 'node:crypto'
import { Buffer } from 'node:buffer'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { infrastructureReady } from './start-metadata.mjs'

const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
await fs.mkdir(path.join(root, '.testhub'), { recursive: true })
const directory = await fs.mkdtemp(path.join(root, '.testhub/startup-compose-qa-'))
const project = `testhub-startup-qa-${crypto.randomBytes(6).toString('hex')}`
const password = crypto.randomBytes(24).toString('hex')
const signingKey = crypto.randomBytes(32).toString('hex')
const composeFile = path.join(directory, 'compose.json')
const state = path.join(directory, 'state')
const imageNames = [`${project}/minio:qa`, `${project}/api-runner:qa`]
const summaryPath = process.env.TESTHUB_START_COMPOSE_QA_SUMMARY || path.join(root, '.testhub/startup-compose-acceptance.json')
const summary = { passed: false, background: true, isolated: true, checks: [], cleaned: false }
const env = { ...process.env }
for (const key of Object.keys(env)) if (/^(TESTHUB_|SPRING_|SERVER_|COMPOSE_)/.test(key)) delete env[key]
Object.assign(env, { COMPOSE_FILE: composeFile, COMPOSE_PROJECT_NAME: project, TESTHUB_START_STATE_DIR: state, BUILDX_BUILDER: project })
let activeLauncher, cleanupPromise, builderCreated = false
let originalContainers = [], originalVolumes = []
const cookies = new Map()
const privateValues = [password, signingKey]
const sanitize = value => String(value).replaceAll(directory, '<owned-qa-directory>')
  .replace(/(https?:\/\/)[^/\s:@]+:[^/\s@]+@/g, '$1***@')
const redact = value => privateValues.reduce((text, secret) => secret ? text.replaceAll(secret, '<redacted>') : text, sanitize(value))
const port = async () => {
  const server = net.createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const value = server.address().port
  await new Promise(resolve => server.close(resolve))
  return value
}
const docker = args => execute('docker', args, { cwd: root, env, timeout: 120000, maxBuffer: 5 * 1024 * 1024 })
const compose = args => docker(['compose', '--profile', 'runner', ...args])
async function launch(args, expected = 0) {
  const log = await fs.open(path.join(directory, 'launcher.log'), 'a', 0o600)
  try {
    activeLauncher = spawn('bash', ['./start.sh', ...args], { cwd: root, env, stdio: ['ignore', log.fd, log.fd] })
    const code = await new Promise((resolve, reject) => { activeLauncher.once('error', reject); activeLauncher.once('close', resolve) })
    activeLauncher = undefined
    assert.equal(code, expected, `launcher ${args[0] || 'start'} exited ${code}; see isolated launcher.log`)
  } finally { await log.close() }
}
async function request(route, options = {}) {
  const response = await globalThis.fetch(`http://127.0.0.1:${env.TESTHUB_START_WEB_PORT}${route}`, {
    ...options, signal: globalThis.AbortSignal.timeout(15000), headers: {
      Cookie: [...cookies].map(([name, value]) => `${name}=${value}`).join('; '), ...options.headers,
    },
  })
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';')[0], at = pair.indexOf('=')
    cookies.set(pair.slice(0, at), pair.slice(at + 1))
  }
  assert.ok(response.ok, `${route} returned ${response.status}`)
  return response
}
const data = async route => (await (await request(route)).json()).data
async function post(route, body, json = true) {
  const csrf = await data('/api/v1/auth/csrf')
  return request(route, { method: 'POST', headers: { [csrf.headerName]: csrf.token, ...(json ? { 'Content-Type': 'application/json' } : {}) },
    body: json ? JSON.stringify(body) : body })
}
const pids = async () => Promise.all(['platform-api', 'web'].map(name => fs.readFile(path.join(state, `${name}.pid`), 'utf8')))
const runningIds = async () => (await compose(['ps', '--status', 'running', '-q'])).stdout.trim().split(/\s+/).filter(Boolean).sort()
async function cleanup() {
  if (cleanupPromise) return cleanupPromise
  cleanupPromise = (async () => {
    if (activeLauncher) {
      const child = activeLauncher
      const done = new Promise(resolve => child.once('close', resolve))
      child.kill('SIGTERM')
      await done
    }
    await launch(['stop'])
    // Only the random QA project is removed. No global Docker prune commands.
    await compose(['down', '--volumes', '--remove-orphans'])
    if (builderCreated) await docker(['buildx', 'rm', '--force', project])
    for (const image of imageNames) {
      try { await docker(['image', 'inspect', image]); await docker(['image', 'rm', image]) }
      catch (error) { if (!String(error.stderr || '').includes('No such image')) throw error }
    }
    const volumes = await docker(['volume', 'ls', '--filter', `label=com.docker.compose.project=${project}`, '-q'])
    const containers = await docker(['ps', '-aq', '--filter', `label=com.docker.compose.project=${project}`])
    assert.equal(volumes.stdout.trim(), '', 'owned volumes removed')
    assert.equal(containers.stdout.trim(), '', 'owned containers removed')
    const retainedContainers = (await docker(['ps', '-aq'])).stdout.trim().split(/\s+/)
    const retainedVolumes = (await docker(['volume', 'ls', '-q'])).stdout.trim().split(/\s+/)
    assert.ok(originalContainers.every(id => retainedContainers.includes(id)), 'original containers retained')
    assert.ok(originalVolumes.every(name => retainedVolumes.includes(name)), 'original volumes retained')
    await fs.rm(directory, { recursive: true, force: true })
    summary.cleaned = true
  })()
  return cleanupPromise
}
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
  void cleanup().then(() => { process.exitCode = 130 }).catch(() => { process.exitCode = 1 })
})
try {
  originalContainers = (await docker(['ps', '-aq'])).stdout.trim().split(/\s+/).filter(Boolean)
  originalVolumes = (await docker(['volume', 'ls', '-q'])).stdout.trim().split(/\s+/).filter(Boolean)
  const daemon = JSON.parse((await docker(['info', '--format', '{{json .}}'])).stdout)
  const proxyArgs = Object.fromEntries([['HTTP_PROXY', daemon.HttpProxy], ['HTTPS_PROXY', daemon.HttpsProxy], ['NO_PROXY', daemon.NoProxy]].filter(([, value]) => value))
  privateValues.push(...Object.values(proxyArgs))
  const driverOptions = Object.entries(proxyArgs).flatMap(([key, value]) => ['--driver-opt', `env.${key}=${value}`])
  await docker(['buildx', 'create', '--name', project, '--driver', 'docker-container', '--driver-opt', 'default-load=true', ...driverOptions])
  builderCreated = true
  const mysqlPort = await port(), redisPort = await port(), minioPort = await port()
  const runnerId = `${project}-api`
  const mysql = {
    image: 'mysql:8.4', environment: { MYSQL_DATABASE: 'startup_qa', MYSQL_ROOT_PASSWORD: password },
    command: ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci', '--transaction-isolation=READ-COMMITTED'],
    ports: [`127.0.0.1:${mysqlPort}:3306`], volumes: ['mysql:/var/lib/mysql'],
    healthcheck: { test: ['CMD-SHELL', 'MYSQL_PWD="$$MYSQL_ROOT_PASSWORD" mysqladmin ping -h localhost -u root --silent'], interval: '2s', timeout: '3s', retries: 60 },
  }
  const redis = {
    image: 'redis:7.4-alpine', command: ['redis-server', '--requirepass', password, '--appendonly', 'yes'],
    ports: [`127.0.0.1:${redisPort}:6379`], volumes: ['redis:/data'],
    healthcheck: { test: ['CMD', 'redis-cli', '-a', password, 'ping'], interval: '2s', timeout: '3s', retries: 30 },
  }
  const minio = {
    image: imageNames[0], build: { context: root, dockerfile: 'infra/minio/Dockerfile', target: 'server',
      args: { ...proxyArgs, ...(process.env.TESTHUB_QA_GOPROXY ? { GOPROXY: process.env.TESTHUB_QA_GOPROXY } : {}) } },
    environment: { MINIO_ROOT_USER: 'testhub-qa', MINIO_ROOT_PASSWORD: password }, command: ['server', '/data'],
    ports: [`127.0.0.1:${minioPort}:9000`], volumes: ['minio:/data'],
    healthcheck: { test: ['CMD', 'curl', '-f', 'http://localhost:9000/minio/health/live'], interval: '2s', timeout: '3s', retries: 30 },
  }
  const runner = {
    profiles: ['runner'], image: imageNames[1], build: { context: root, dockerfile: 'services/api-runner/Dockerfile', args: proxyArgs },
    environment: { TESTHUB_REDIS_URL: `redis://:${password}@redis:6379/0`, RUNNER_ID: runnerId, RUNNER_VERSION: 'startup-qa',
      RUNNER_HEARTBEAT_INTERVAL_SECONDS: '2', RUNNER_HEARTBEAT_TTL_SECONDS: '12', RUNNER_HEARTBEAT_KEY: signingKey,
      RUNNER_HTTP_EXECUTION_ENABLED: 'false' },
    depends_on: { redis: { condition: 'service_healthy' } }, user: 'node', read_only: true,
    tmpfs: ['/tmp:noexec,nosuid,size=64m'], cap_drop: ['ALL'], security_opt: ['no-new-privileges:true'], pids_limit: 64,
  }
  await fs.writeFile(composeFile, JSON.stringify({ services: { mysql, redis, minio, 'api-runner': runner }, volumes: { mysql: {}, redis: {}, minio: {} } }), { mode: 0o600 })
  Object.assign(env, {
    TESTHUB_START_API_PORT: String(await port()), TESTHUB_START_WEB_PORT: String(await port()),
    TESTHUB_DB_URL: `jdbc:mysql://127.0.0.1:${mysqlPort}/startup_qa?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false`,
    TESTHUB_DB_USERNAME: 'root', TESTHUB_DB_PASSWORD: password,
    TESTHUB_REDIS_HOST: '127.0.0.1', TESTHUB_REDIS_PORT: String(redisPort), TESTHUB_REDIS_PASSWORD: password,
    TESTHUB_STORAGE_TYPE: 'minio', TESTHUB_MINIO_ENDPOINT: `http://127.0.0.1:${minioPort}`,
    TESTHUB_MINIO_ACCESS_KEY: 'testhub-qa', TESTHUB_MINIO_SECRET_KEY: password, TESTHUB_MINIO_BUCKET: 'startup-qa-files',
    TESTHUB_ENCRYPTION_KEY: crypto.randomBytes(32).toString('base64'),
    SPRING_APPLICATION_JSON: JSON.stringify({ testhub: { runner: { 'heartbeat-key': signingKey }, bootstrap: { 'demo-enabled': false } } }),
  })
  process.stdout.write(`Starting isolated Compose acceptance (${project}); logs remain in the owned directory until cleanup.\n`)
  await launch(['doctor', '--with-registration', '--with-api-runner'])
  summary.checks.push('doctor-buildx-preflight')
  await launch(['--with-registration', '--with-api-runner'])
  await launch(['status'])
  assert.equal((await runningIds()).length, 4)
  const labels = JSON.parse((await docker(['image', 'inspect', '--format', '{{json .Config.Labels}}', imageNames[0]])).stdout)
  assert.equal(labels['org.opencontainers.image.revision'], '0d7408fc9969caf07de6a8c3a84f9fbb10a6739e')
  summary.checks.push('cold-image-build-core-and-runner-start')
  assert.equal((await data('/api/v1/auth/capabilities')).registrationEnabled, true)
  const account = `composeqa${Date.now()}`
  const registered = await (await post('/api/v1/auth/register', { account, email: `${account}@example.test`, displayName: 'Compose QA', password })).json()
  const context = await data('/api/v1/access/context')
  const projectId = Object.values(context.projects).flat()[0].id
  const route = `/api/v1/projects/${projectId}`
  const content = Buffer.from('Owned startup acceptance object\n')
  const form = new globalThis.FormData()
  form.append('file', new globalThis.Blob([content], { type: 'text/plain' }), 'startup.txt')
  const uploaded = (await (await post(`${route}/files`, form, false)).json()).data
  assert.deepEqual(Buffer.from(await (await request(`${route}/files/${uploaded.id}/download`)).arrayBuffer()), content)
  summary.checks.push('registration-session-minio-upload-download')
  let runnerStatus
  for (let attempt = 0; attempt < 30; attempt++) {
    runnerStatus = (await data(`${route}/api-automation/runner-status`)).find(item => item.runnerId === runnerId)
    if (runnerStatus?.trusted) break
    await delay(1000)
  }
  assert.equal(runnerStatus?.trustStatus, 'VERIFIED')
  assert.equal(runnerStatus.executionEnabled, false)
  summary.checks.push('signed-runner-heartbeat-verified-execution-disabled')
  const initialPids = await pids(), initialContainers = await runningIds()
  await launch(['--with-registration', '--with-api-runner'])
  assert.deepEqual(await pids(), initialPids)
  assert.deepEqual(await runningIds(), initialContainers)
  summary.checks.push('healthy-start-reuses-apps-and-containers')
  await launch(['restart', '--with-registration', '--with-api-runner'])
  assert.notDeepEqual(await pids(), initialPids)
  assert.deepEqual(await runningIds(), initialContainers)
  assert.equal((await data('/api/v1/auth/me')).id, registered.data.id)
  assert.deepEqual(Buffer.from(await (await request(`${route}/files/${uploaded.id}/download`)).arrayBuffer()), content)
  summary.checks.push('app-restart-preserves-session-account-object-runner')
  await launch(['stop'])
  assert.equal(await infrastructureReady(env), true)
  assert.deepEqual(await runningIds(), initialContainers)
  // Restart only the exclusively owned containers to verify persisted volumes.
  await compose(['restart', 'mysql', 'redis', 'minio', 'api-runner'])
  await launch(['--with-registration', '--with-api-runner'])
  cookies.clear()
  await post('/api/v1/auth/login', { account, password })
  assert.equal((await data('/api/v1/auth/me')).id, registered.data.id)
  assert.deepEqual(Buffer.from(await (await request(`${route}/files/${uploaded.id}/download`)).arrayBuffer()), content)
  summary.checks.push('container-restart-preserves-mysql-redis-minio-volumes')
  summary.passed = true
} catch (error) {
  summary.failure = redact(error.message)
  summary.diagnostics = {}
  for (const file of ['launcher.log', 'state/docker-compose.log', 'state/platform-api.log', 'state/web.log']) {
    try {
      const contents = await fs.readFile(path.join(directory, file), 'utf8')
      summary.diagnostics[file] = redact(contents.split('\n').slice(-24).join('\n'))
    } catch (readError) { if (readError.code !== 'ENOENT') throw readError }
  }
  process.stderr.write(JSON.stringify({ failure: summary.failure, diagnostics: summary.diagnostics }, null, 2) + '\n')
  throw new Error(summary.failure)
} finally {
  try { await cleanup() }
  finally {
    await fs.writeFile(summaryPath, JSON.stringify(summary, null, 2) + '\n', { mode: 0o600 })
    process.stdout.write(summary.cleaned ? 'Owned Compose containers, volumes, images, credentials, logs and test workspace removed.\n'
      : `Cleanup incomplete; isolated diagnostic state retained at ${directory}\n`)
  }
}
