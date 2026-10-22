import assert from 'node:assert/strict'
import { test } from 'node:test'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import process from 'node:process'
import net from 'node:net'
import http from 'node:http'
import { execFile, spawn } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { dependencyFingerprint, configFingerprint, validCsrf, imagePlan, infrastructureReady } from './start-metadata.mjs'

const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const listen = async server => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return server.address().port
}
const close = async server => {
  server.closeAllConnections?.()
  await new Promise(resolve => server.close(resolve))
}
const freePort = async () => {
  const server = net.createServer()
  const port = await listen(server)
  await close(server)
  return port
}
const exists = async file => fs.stat(file).then(() => true, () => false)

const fixtureServer = `#!/usr/bin/env node
import http from 'node:http'
import fs from 'node:fs'
const web = process.argv[1].endsWith('/vite')
if (web && fs.existsSync('.testhub/fail-web')) process.exit(12)
const port = web ? Number(process.argv[process.argv.indexOf('--port') + 1]) : Number(process.env.SERVER_PORT)
const host = web ? process.argv[process.argv.indexOf('--host') + 1] : process.env.SERVER_ADDRESS
fs.appendFileSync('.testhub/launches', JSON.stringify({ web, port, host, registration: process.env.TESTHUB_REGISTRATION_ENABLED }) + '\\n')
http.createServer(async (req, res) => {
  if (web && req.url.startsWith('/api/')) {
    const response = await fetch(process.env.TESTHUB_VITE_API_ORIGIN + req.url)
    res.end(await response.text()); return
  }
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify(req.url === '/api/v1/auth/csrf' ? { data: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } } : { status: 'UP' }))
}).listen(port, host)
`

async function fixture(t) {
  // Copy only the launcher; real process/port operations run in a disposable checkout.
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub startup '))
  for (const folder of ['scripts/dev', 'node_modules/.bin', '.testhub', 'bin', 'jdk/bin']) {
    await fs.mkdir(path.join(directory, folder), { recursive: true })
  }
  for (const file of ['start.sh', 'scripts/dev/start-metadata.mjs']) await fs.copyFile(path.join(root, file), path.join(directory, file))
  const write = async (file, content) => fs.writeFile(path.join(directory, file), content, { mode: 0o700 })
  await write('package.json', '{"type":"module"}')
  await write('pnpm-lock.yaml', 'lockfileVersion: 9\n')
  await write('pnpm-workspace.yaml', 'packages: []\n')
  await write('api-fixture.mjs', fixtureServer)
  await write('mvnw', '#!/usr/bin/env bash\nexec node ./api-fixture.mjs "$@"\n')
  await write('node_modules/.bin/vite', fixtureServer)
  await write('jdk/bin/java', '#!/usr/bin/env bash\nprintf \'openjdk version "21.0.1"\\n\' >&2\n')
  await write('jdk/bin/javac', '#!/usr/bin/env bash\nexit 0\n')
  await write('bin/npx', '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> .testhub/installs\n[[ "${QA_INSTALL_FAIL:-}" != 1 ]]\n')
  await write('bin/docker', `#!/usr/bin/env node
import fs from 'node:fs'
const args = process.argv.slice(2)
fs.appendFileSync('.testhub/docker-calls', JSON.stringify(args) + '\\n')
if (args[0] === 'info' && process.env.QA_DOCKER_DOWN) process.exit(1)
if (args.includes('config') && args.includes('--format')) console.log(JSON.stringify({ name: 'qa', services: {
  mysql: { image: 'mysql:8.4' }, redis: { image: 'redis:7.4-alpine' }, minio: { image: 'qa/minio', build: {} }, 'api-runner': { build: {} }
} }))
if (args[0] === 'image' && process.env.QA_MISSING_IMAGE) process.exit(1)
if (args[0] === 'buildx' && process.env.QA_NO_BUILDX) process.exit(1)
if (args.includes('ps')) console.log(fs.existsSync('.testhub/compose-attempt') ? 'existing-container\\nnew-container' : 'existing-container')
if (args.includes('up') && process.env.QA_COMPOSE_FAIL) { fs.writeFileSync('.testhub/compose-attempt', '1'); process.exit(1) }
`)
  const mysql = net.createServer(socket => { socket.on('error', () => {}); socket.end(new Uint8Array([1, 0, 0, 0, 10])) })
  const redis = net.createServer(socket => { socket.on('error', () => {}); socket.on('data', () => socket.write('+OK\r\n+PONG\r\n')) })
  const minio = http.createServer((req, res) => res.end('OK'))
  const env = { ...process.env }
  for (const key of Object.keys(env)) if (/^(TESTHUB_|SPRING_|SERVER_|COMPOSE_)/.test(key)) delete env[key]
  Object.assign(env, {
    PATH: `${directory}/bin:${process.env.PATH}`, JAVA_HOME: `${directory}/jdk`,
    COMPOSE_FILE: 'compose-qa.json',
    TESTHUB_START_API_PORT: String(await freePort()), TESTHUB_START_WEB_PORT: String(await freePort()),
    TESTHUB_DB_URL: `jdbc:mysql://127.0.0.1:${await listen(mysql)}/testhub`,
    TESTHUB_REDIS_HOST: '127.0.0.1', TESTHUB_REDIS_PORT: String(await listen(redis)),
    TESTHUB_MINIO_ENDPOINT: `http://127.0.0.1:${await listen(minio)}`,
  })
  const run = async (args = [], extra = {}) => {
    try {
      const result = await execute('bash', ['./start.sh', ...args], { cwd: directory, env: { ...env, ...extra }, timeout: 60000 })
      return { ...result, code: 0 }
    } catch (error) {
      if (error.killed) throw error
      return { code: error.code, stdout: error.stdout, stderr: error.stderr }
    }
  }
  t.after(async () => {
    const stopped = await run(['stop'])
    assert.equal(stopped.code, 0, stopped.stderr)
    for (const server of [mysql, redis, minio]) await close(server)
    await fs.rm(directory, { recursive: true, force: true })
  })
  const pids = async () => Promise.all(['platform-api', 'web'].map(name => fs.readFile(`${directory}/.testhub/${name}.pid`, 'utf8')))
  return { directory, env, run, write, pids }
}

const passed = result => assert.equal(result.code, 0, result.stdout + result.stderr)

test('dependency fingerprints track workspace manifests and lockfile; config never returns secrets', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-metadata-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  await fs.mkdir(path.join(directory, 'services/example'), { recursive: true })
  const before = dependencyFingerprint(directory)
  await fs.writeFile(path.join(directory, 'services/example/package.json'), '{}')
  assert.notEqual(dependencyFingerprint(directory), before)
  const manifest = dependencyFingerprint(directory)
  await fs.writeFile(path.join(directory, 'pnpm-lock.yaml'), 'changed')
  assert.notEqual(dependencyFingerprint(directory), manifest)
  const config = configFingerprint(['true'], { TESTHUB_DB_PASSWORD: 'private-value' })
  assert.match(config, /^[0-9a-f]{64}$/)
  assert.notEqual(config, configFingerprint(['true'], { TESTHUB_DB_PASSWORD: 'changed' }))
})

test('CSRF validation rejects HTML, fake keys, empty tokens and malformed JSON', () => {
  for (const body of ['<h1>"headerName"</h1>', '{}', '{', '{"data":{"headerName":"x","token":""}}']) assert.equal(validCsrf(body), false)
  assert.equal(validCsrf('{"data":{"headerName":"x","token":"value"}}'), true)
  assert.match(imagePlan({ name: 'qa', services: { 'api-runner': { build: {} } } }), /api-runner\tqa-api-runner\ttrue/)
})

test('piped JSON waits for a delayed producer instead of reading incomplete stdin', async () => {
  const child = spawn(process.execPath, [path.join(root, 'scripts/dev/start-metadata.mjs'), 'csrf'], { stdio: ['pipe', 'ignore', 'pipe'] })
  const done = new Promise(resolve => child.once('close', resolve))
  child.stdin.on('error', () => {})
  child.stdin.write('{"data":')
  await delay(200)
  child.stdin.end('{"headerName":"x","token":"value"}}')
  assert.equal(await done, 0)
})

test('startup lifecycle, ownership, preflight and rollback', { timeout: 240000 }, async t => {
  const f = await fixture(t)
  passed(await f.run(['--help']))
  await t.test('doctor is read-only; status reports stopped without requiring Java or Docker', async () => {
    passed(await f.run(['doctor', '--with-registration']))
    assert.equal(await exists(`${f.directory}/.testhub/installs`), false)
    assert.equal(await exists(`${f.directory}/.testhub/start.lock`), false)
    assert.equal((await f.run(['status'], { JAVA_HOME: '/missing' })).code, 1)
  })
  await t.test('legacy invocation starts both services, validates proxy, and stays on loopback', async () => {
    passed(await f.run(['--with-registration']))
    passed(await f.run(['status']))
    const launches = (await fs.readFile(`${f.directory}/.testhub/launches`, 'utf8')).trim().split('\n').map(JSON.parse)
    assert.equal(launches.length, 2)
    assert.ok(launches.every(launch => launch.host === '127.0.0.1'))
    assert.match(await fs.readFile(`${f.directory}/.testhub/installs`, 'utf8'), /pnpm@10.13.1 install --frozen-lockfile/)
  })
  await t.test('healthy repeated start reuses PIDs and does not reinstall', async () => {
    const before = await f.pids()
    passed(await f.run(['start', '--with-registration']))
    assert.deepEqual(await f.pids(), before)
    assert.equal((await fs.readFile(`${f.directory}/.testhub/installs`, 'utf8')).trim().split('\n').length, 1)
    assert.equal((await f.run(['start'])).code, 1, 'changed registration requires explicit restart')
    assert.deepEqual(await f.pids(), before)
  })
  await t.test('changed lockfile and failing install leave both existing processes alive', async () => {
    const before = await f.pids()
    await fs.appendFile(`${f.directory}/pnpm-lock.yaml`, '# changed\n')
    assert.equal((await f.run(['--with-registration'])).code, 1)
    assert.equal((await f.run(['restart', '--with-registration'], { QA_INSTALL_FAIL: '1' })).code, 1)
    assert.deepEqual(await f.pids(), before)
    passed(await f.run(['status']))
    passed(await f.run(['restart', '--with-registration']))
    assert.notDeepEqual(await f.pids(), before)
  })
  await t.test('Docker and Buildx failures happen before stopping a healthy API/Web', async () => {
    const before = await f.pids()
    const unavailable = { TESTHUB_MINIO_ENDPOINT: `http://127.0.0.1:${await freePort()}` }
    assert.equal((await f.run(['restart', '--with-registration'], { ...unavailable, QA_DOCKER_DOWN: '1' })).code, 1)
    const buildx = await f.run(['restart', '--with-registration'], { ...unavailable, QA_MISSING_IMAGE: '1', QA_NO_BUILDX: '1' })
    assert.equal(buildx.code, 1)
    assert.match(buildx.stderr, /Buildx/)
    assert.deepEqual(await f.pids(), before)
    passed(await f.run(['status']))
  })
  await t.test('partial Compose failure stops only newly started containers and keeps volumes', async () => {
    const before = await f.pids()
    const failed = await f.run(['restart', '--with-registration'], {
      TESTHUB_MINIO_ENDPOINT: `http://127.0.0.1:${await freePort()}`, QA_COMPOSE_FAIL: '1',
    })
    assert.equal(failed.code, 1)
    const calls = (await fs.readFile(`${f.directory}/.testhub/docker-calls`, 'utf8')).trim().split('\n').map(JSON.parse)
    assert.ok(calls.some(call => call.join(' ') === 'stop new-container'), failed.stdout + failed.stderr)
    assert.ok(!calls.some(call => call.join(' ') === 'stop existing-container' || call.includes('down')))
    assert.deepEqual(await f.pids(), before)
  })
  await t.test('foreign web listener prevents restart before the API is touched', async () => {
    const before = await f.pids()
    const foreign = http.createServer((req, res) => res.end('other app'))
    try {
      const port = await listen(foreign)
      const result = await f.run(['restart', '--with-registration'], { TESTHUB_START_WEB_PORT: String(port) })
      assert.equal(result.code, 1)
      assert.match(result.stderr, /unrelated PID/)
      assert.deepEqual(await f.pids(), before)
      assert.ok(foreign.listening)
    } finally { await close(foreign) }
  })
  await t.test('a reused PID belonging to another process is never stopped', async () => {
    const before = await f.pids()
    try {
      await f.write('.testhub/web.pid', String(process.pid))
      const failed = await f.run(['restart', '--with-registration'])
      assert.equal(failed.code, 1)
      assert.match(failed.stderr, /PID file refers to unrelated PID/)
    } finally { await f.write('.testhub/web.pid', before[1]) }
    passed(await f.run(['status']))
    assert.deepEqual(await f.pids(), before)
  })
  await t.test('failed new Web process preserves a reused API', async () => {
    const [api, web] = await f.pids()
    process.kill(Number(web), 'SIGTERM')
    await execute('sleep', ['0.2'])
    await f.write('.testhub/fail-web', '1')
    const failed = await f.run(['--with-registration'])
    assert.equal(failed.code, 1)
    assert.equal(await fs.readFile(`${f.directory}/.testhub/platform-api.pid`, 'utf8'), api)
    assert.equal(await exists(`${f.directory}/.testhub/web.pid`), false)
    assert.equal(await exists(`${f.directory}/.testhub/start.lock`), false)
    await fs.rm(`${f.directory}/.testhub/fail-web`)
    passed(await f.run(['--with-registration']))
  })
  await t.test('failed fresh launch removes new API and Web, then allows retry', async () => {
    passed(await f.run(['stop']))
    await f.write('.testhub/fail-web', '1')
    const failed = await f.run(['--with-registration'])
    assert.equal(failed.code, 1)
    assert.equal(await exists(`${f.directory}/.testhub/platform-api.pid`), false)
    assert.equal(await exists(`${f.directory}/.testhub/web.pid`), false)
    assert.equal(await exists(`${f.directory}/.testhub/start.lock`), false)
    await fs.rm(`${f.directory}/.testhub/fail-web`)
    passed(await f.run(['--with-registration']))
  })
  await t.test('live lifecycle lock refuses mutation; stale lock recovers', async () => {
    await fs.mkdir(`${f.directory}/.testhub/start.lock`)
    await f.write('.testhub/start.lock/pid', String(process.pid))
    assert.equal((await f.run(['stop'])).code, 1)
    await f.write('.testhub/start.lock/pid', '99999999')
    passed(await f.run(['stop']))
    assert.equal(await exists(`${f.directory}/.testhub/start.lock`), false)
    assert.equal(await infrastructureReady(f.env), true)
  })
})
