// Small, dependency-free checks shared by the shell entry point and its tests.
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import net from 'node:net'
import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { fileURLToPath, URL } from 'node:url'

export function dependencyFingerprint(root) {
  const files = ['package.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', '.npmrc']
  // These are the three workspace globs declared in pnpm-workspace.yaml.
  for (const directory of ['apps', 'services', 'packages']) {
    if (!fs.existsSync(path.join(root, directory))) continue
    for (const entry of fs.readdirSync(path.join(root, directory), { withFileTypes: true })) {
      if (entry.isDirectory()) files.push(`${directory}/${entry.name}/package.json`)
    }
  }
  const hash = createHash('sha256').update(`pnpm@10.13.1/node${process.versions.node.split('.')[0]}/${process.platform}/${process.arch}\0`)
  for (const file of files.sort()) {
    hash.update(file).update('\0')
    hash.update(fs.existsSync(path.join(root, file)) ? fs.readFileSync(path.join(root, file)) : '<missing>')
    hash.update('\0')
  }
  return hash.digest('hex')
}

export function configFingerprint(args, env = process.env) {
  const ignored = new Set(['TESTHUB_REGISTRATION_ENABLED', 'SERVER_ADDRESS', 'SERVER_PORT'])
  const keys = Object.keys(env).filter(key => /^(TESTHUB_|SPRING_|SERVER_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|MAVEN_OPTS$|COMPOSE_)/.test(key)
    && !key.startsWith('TESTHUB_START_') && !ignored.has(key)).sort()
  // Persist only a digest, never environment values or credentials.
  return createHash('sha256').update(JSON.stringify([args, keys.map(key => [key, env[key]])])).digest('hex')
}

export function validCsrf(body) {
  try {
    const { data } = JSON.parse(body)
    return typeof data?.headerName === 'string' && data.headerName.length > 0
      && typeof data?.token === 'string' && data.token.length > 0
  } catch { return false }
}

export function imagePlan(config) {
  return ['mysql', 'redis', 'minio', 'api-runner'].flatMap(name => {
    const service = config.services?.[name]
    if (!service) return []
    const image = service.image || `${config.name}-${name}`
    if (!/^[a-zA-Z0-9][a-zA-Z0-9/_.:@-]*$/.test(image)) throw new Error('Invalid Compose image reference')
    return [`${name}\t${image}\t${Boolean(service.build)}`]
  }).join('\n')
}

function socketCheck(host, port, probe) {
  return new Promise(resolve => {
    const socket = net.createConnection({ host, port: Number(port) })
    const finish = ok => { socket.destroy(); resolve(ok) }
    socket.setTimeout(2000)
    socket.on('error', () => finish(false))
    socket.on('timeout', () => finish(false))
    socket.on('end', () => finish(false))
    probe(socket, finish)
  })
}

export async function infrastructureReady(env = process.env) {
  const db = new URL((env.TESTHUB_DB_URL || 'jdbc:mysql://127.0.0.1:13307/testhub').replace(/^jdbc:/, ''))
  if (db.protocol !== 'mysql:') throw new Error('Web startup requires MySQL; use the desktop launcher for a local workspace')
  const mysql = socketCheck(db.hostname, db.port || 3306, (socket, finish) => {
    let response = Buffer.alloc(0)
    socket.on('data', data => {
      response = Buffer.concat([response, data])
      // MySQL's initial greeting has sequence 0 and protocol version 10.
      if (response.length >= 5) finish(response[3] === 0 && response[4] === 10)
    })
  })
  const redis = socketCheck(env.TESTHUB_REDIS_HOST || '127.0.0.1', env.TESTHUB_REDIS_PORT || 6380, (socket, finish) => {
    const password = env.TESTHUB_REDIS_PASSWORD ?? 'testhub_redis_password'
    const auth = password ? `*2\r\n$4\r\nAUTH\r\n$${Buffer.byteLength(password)}\r\n${password}\r\n` : ''
    let response = ''
    socket.on('connect', () => socket.write(`${auth}*1\r\n$4\r\nPING\r\n`))
    socket.on('data', data => {
      response += data.toString('utf8')
      if (/(^|\r\n)-/.test(response)) finish(false)
      else if (response.includes('+PONG\r\n')) finish(true)
    })
  })
  const minio = env.TESTHUB_STORAGE_TYPE === 'filesystem' ? Boolean(env.TESTHUB_STORAGE_DIRECTORY) : globalThis.fetch(new URL('/minio/health/live', env.TESTHUB_MINIO_ENDPOINT || 'http://127.0.0.1:9000'), {
    signal: globalThis.AbortSignal.timeout(2000), redirect: 'error',
  }).then(async response => { await response.body?.cancel(); return response.ok }).catch(() => false)
  return (await Promise.all([mysql, redis, minio])).every(Boolean)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [command, ...args] = process.argv.slice(2)
    let input = ''
    if (command === 'csrf' || command === 'images') {
      for await (const chunk of process.stdin) input += chunk.toString('utf8')
    }
    switch (command) {
      case 'dependencies': process.stdout.write(dependencyFingerprint(process.cwd())); break
      case 'config': process.stdout.write(configFingerprint(args)); break
      case 'csrf': process.exitCode = validCsrf(input) ? 0 : 1; break
      case 'images': process.stdout.write(imagePlan(JSON.parse(input))); break
      case 'infrastructure': process.exitCode = await infrastructureReady() ? 0 : 1; break
      default: throw new Error('Unknown startup metadata command')
    }
  } catch {
    process.stderr.write('Startup check failed; verify configuration and service availability.\n')
    process.exitCode = 1
  }
}
