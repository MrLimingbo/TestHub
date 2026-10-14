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
try {
  const windows = process.platform === 'win32'
  const executable = windows ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe') : path.join(root, 'mvnw')
  // The wrapper path is data in an environment variable, never interpolated into shell code.
  if (windows) env.TESTHUB_BACKEND_QA_MAVEN_WRAPPER = path.join(root, 'mvnw.cmd')
  const args = windows ? ['-NoProfile', '-NonInteractive', '-Command', '& $env:TESTHUB_BACKEND_QA_MAVEN_WRAPPER -pl services/platform-api -am -Dtesthub.bootstrap.demo-enabled=true package; exit $LASTEXITCODE']
    : ['-pl', 'services/platform-api', '-am', '-Dtesthub.bootstrap.demo-enabled=true', 'package']
  const child = spawn(executable, args, { cwd: root, env, stdio: 'inherit' })
  process.exitCode = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', code => resolve(code ?? 1)) })
} finally {
  // This directory was exclusively created above; never remove an existing workspace.
  await fs.rm(directory, { recursive: true, force: true })
  console.log('Removed isolated backend acceptance data:', directory)
}
