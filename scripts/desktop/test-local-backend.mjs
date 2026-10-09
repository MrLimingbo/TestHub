// Always isolate the full backend suite: many acceptance fixtures write and clean up demo data.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const directory = path.join(root, '.testhub', 'backend-qa-' + crypto.randomUUID())
await fs.mkdir(path.dirname(directory), { recursive: true })
await fs.mkdir(directory, { recursive: false })
const env = { ...process.env }
for (const name of Object.keys(env)) {
  if (name.startsWith('SPRING_') || name.startsWith('TESTHUB_')) delete env[name]
}
Object.assign(env, { SPRING_PROFILES_ACTIVE: 'desktop', TESTHUB_DESKTOP_ALLOWED_ORIGINS: 'https://ui.example.test', TESTHUB_DESKTOP_DATA_DIRECTORY: directory, TESTHUB_LOCAL_DB_PASSWORD: crypto.randomBytes(32).toString('hex') })
console.log('Isolated backend acceptance directory:', directory)
const child = spawn(path.join(root, 'mvnw'), ['-pl', 'services/platform-api', '-am', '-Dtesthub.bootstrap.demo-enabled=true', 'package'], { cwd: root, env, stdio: 'inherit' })
child.once('error', error => { console.error(error.message); process.exitCode = 1 })
child.once('exit', code => { process.exitCode = code ?? 1 })
