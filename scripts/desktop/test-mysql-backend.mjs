// The complete MySQL suite must own its infrastructure: acceptance fixtures write and delete data.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
const execute = promisify(execFile)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
await fs.mkdir(path.join(root, '.testhub'), { recursive: true })
const directory = await fs.mkdtemp(path.join(root, '.testhub/backend-mysql-qa-'))
const project = 'testhub-qa-' + crypto.randomUUID()
const password = crypto.randomBytes(24).toString('hex')
const file = path.join(directory, 'compose.json')
const services = {
  mysql: {
    image: 'mysql:8.4', environment: { MYSQL_DATABASE: 'testhub', MYSQL_ROOT_PASSWORD: password, TZ: 'Asia/Shanghai' },
    command: ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci', '--transaction-isolation=READ-COMMITTED'],
    ports: ['127.0.0.1::3306'], volumes: ['mysql:/var/lib/mysql'],
    healthcheck: { test: ['CMD-SHELL', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -h localhost -u root --silent'], interval: '2s', timeout: '3s', retries: 60 },
  },
  redis: {
    image: 'redis:7.4-alpine', command: ['redis-server', '--requirepass', password], ports: ['127.0.0.1::6379'],
    healthcheck: { test: ['CMD', 'redis-cli', '-a', password, 'ping'], interval: '2s', timeout: '3s', retries: 30 },
  },
  minio: {
    image: 'testhub/minio:RELEASE.2025-04-22T22-12-26Z',
    build: { context: root, dockerfile: 'infra/minio/Dockerfile', target: 'server',
      ...(process.env.TESTHUB_QA_GOPROXY ? { args: { GOPROXY: process.env.TESTHUB_QA_GOPROXY } } : {}) },
    environment: { MINIO_ROOT_USER: 'testhub-qa', MINIO_ROOT_PASSWORD: password }, command: ['server', '/data'],
    ports: ['127.0.0.1::9000'], volumes: ['minio:/data'],
    healthcheck: { test: ['CMD', 'curl', '-f', 'http://localhost:9000/minio/health/live'], interval: '2s', timeout: '3s', retries: 30 },
  },
}
const compose = (...args) => execute('docker', ['compose', '--project-name', project, '--file', file, ...args], { cwd: root, maxBuffer: 20 * 1024 * 1024 })
const env = { ...process.env }
for (const name of Object.keys(env)) if (name.startsWith('SPRING_') || name.startsWith('TESTHUB_')) delete env[name]
let testCode, child
const stop = () => child?.kill('SIGTERM')
process.on('SIGINT', stop); process.on('SIGTERM', stop)
try {
  await fs.writeFile(file, JSON.stringify({ services, volumes: { mysql: {}, minio: {} } }), { mode: 0o600 })
  console.log('Starting exclusively owned MySQL, Redis and MinIO acceptance infrastructure')
  await compose('pull', 'mysql', 'redis')
  await compose('build', 'minio')
  await compose('up', '-d', '--wait', '--wait-timeout', '180', 'mysql', 'redis', 'minio')
  const port = async (service, internal) => {
    const { stdout } = await compose('port', service, String(internal))
    const match = /^127\.0\.0\.1:(\d+)\s*$/.exec(stdout)
    if (!match) throw new Error('QA service is not exclusively bound to loopback: ' + service)
    return match[1]
  }
  const mysqlUrl = `jdbc:mysql://127.0.0.1:${await port('mysql', 3306)}/testhub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false`
  Object.assign(env, {
    SPRING_PROFILES_ACTIVE: 'mysql-qa', TESTHUB_DB_URL: mysqlUrl, TESTHUB_DB_USERNAME: 'root', TESTHUB_DB_PASSWORD: password,
    TESTHUB_TEST_MYSQL_URL: mysqlUrl, TESTHUB_TEST_MYSQL_PASSWORD: password,
    TESTHUB_REDIS_HOST: '127.0.0.1', TESTHUB_REDIS_PORT: await port('redis', 6379), TESTHUB_REDIS_PASSWORD: password,
    TESTHUB_STORAGE_TYPE: 'minio', TESTHUB_MINIO_ENDPOINT: `http://127.0.0.1:${await port('minio', 9000)}`,
    TESTHUB_MINIO_ACCESS_KEY: 'testhub-qa', TESTHUB_MINIO_SECRET_KEY: password, TESTHUB_MINIO_BUCKET: 'testhub-qa-files',
    TESTHUB_DESKTOP_DATA_DIRECTORY: path.join(directory, 'desktop'), TESTHUB_LOCAL_DB_PASSWORD: password,
    TESTHUB_DESKTOP_ALLOWED_ORIGINS: 'https://ui.example.test',
  })
  await fs.mkdir(env.TESTHUB_DESKTOP_DATA_DIRECTORY)
  console.log('Running the complete backend suite against isolated MySQL/Redis/S3')
  const windows = process.platform === 'win32'
  const executable = windows ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe') : path.join(root, 'mvnw')
  if (windows) env.TESTHUB_BACKEND_QA_MAVEN_WRAPPER = path.join(root, 'mvnw.cmd')
  const args = windows ? ['-NoProfile', '-NonInteractive', '-Command', '& $env:TESTHUB_BACKEND_QA_MAVEN_WRAPPER -pl services/platform-api -am -Dtesthub.bootstrap.demo-enabled=true test; exit $LASTEXITCODE']
    : ['-pl', 'services/platform-api', '-am', '-Dtesthub.bootstrap.demo-enabled=true', 'test']
  child = spawn(executable, args, { cwd: root, env, stdio: 'inherit' })
  testCode = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve(code ?? 1)) })
  process.exitCode = testCode
} finally {
  process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop)
  // Only the random project and directory exclusively created above may be removed.
  try { await compose('down', '--volumes', '--remove-orphans'); console.log('Removed owned QA containers, network and volumes') }
  finally { await fs.rm(directory, { recursive: true, force: true }); console.log('Removed isolated MySQL acceptance files') }
}
