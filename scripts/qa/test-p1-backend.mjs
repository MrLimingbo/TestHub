import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const selected = process.argv[2] || 'P1*Tests'
if (!/^[A-Za-z0-9_,*]+$/.test(selected) || !selected.startsWith('P1')) throw new Error('Only P1 test classes are accepted')
const phase = process.argv[3] || 'test'
if (!['test', 'package'].includes(phase)) throw new Error('Only test or package phases are accepted')
const directory = path.join(root, '.testhub', 'p1-backend-qa-' + crypto.randomUUID())
await fs.mkdir(path.dirname(directory), { recursive: true }); await fs.mkdir(directory)
const env = { ...process.env }
for (const key of Object.keys(env)) if (key.startsWith('SPRING_') || key.startsWith('TESTHUB_')) delete env[key]
Object.assign(env, { SPRING_PROFILES_ACTIVE: 'desktop', TESTHUB_DESKTOP_DATA_DIRECTORY: directory,
  TESTHUB_LOCAL_DB_PASSWORD: crypto.randomBytes(32).toString('hex'), TESTHUB_DESKTOP_ALLOWED_ORIGINS: 'https://ui.example.test' })
try {
  const child = spawn(path.join(root, 'mvnw'), ['-pl', 'services/platform-api', '-am', '-Dtest=' + selected,
    '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtesthub.bootstrap.demo-enabled=false', phase], { cwd: root, env, stdio: 'inherit' })
  process.exitCode = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve(code ?? 1)) })
} finally { await fs.rm(directory, { recursive: true, force: true }) }
