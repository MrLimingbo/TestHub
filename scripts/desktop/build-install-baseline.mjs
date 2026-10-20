// Immutable, previously accepted standalone build used exclusively for upgrade acceptance.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFile, spawn } from 'node:child_process'
import { promisify } from 'node:util'
const run = promisify(execFile), args = process.argv.slice(2)
if (!args.includes('--output')) throw new Error('Usage: build-install-baseline.mjs --output <new baseline artifact directory>')
const output = path.resolve(args[args.indexOf('--output') + 1])
const commit = '3932ef152604244438f007b3e61ed4867a23b443'
try { await run('git', ['cat-file', '-e', commit + '^{commit}']) }
catch { await run('git', ['fetch', '--no-tags', '--depth=1', 'origin', commit]) }
await fs.mkdir('.testhub', { recursive: true })
const temporary = await fs.mkdtemp(path.resolve('.testhub/installation-baseline-'))
try {
  const archive = await run('git', ['archive', '--format=tar', commit], { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })
  const file = path.join(temporary, 'source.tar'); await fs.writeFile(file, archive.stdout)
  const source = path.join(temporary, 'source'); await fs.mkdir(source)
  await run('tar', ['-xf', file, '-C', source])
  const child = spawn(process.execPath, ['scripts/desktop/test-local-backend.mjs'], { cwd: source, env: process.env, stdio: 'inherit' })
  const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve) })
  if (code !== 0) throw new Error('Previous backend isolated acceptance failed')
  const jar = await fs.readFile(path.join(source, 'services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar'))
  const migrations = (await fs.readdir(path.join(source, 'services/platform-api/src/main/resources/db/desktop')))
    .map(name => /^V(\d+)__.*\.sql$/.exec(name)).filter(Boolean).map(match => Number(match[1]))
  const desktopSchemaVersion = Math.max(...migrations)
  if (!Number.isSafeInteger(desktopSchemaVersion) || desktopSchemaVersion < 1 || new Set(migrations).size !== desktopSchemaVersion)
    throw new Error('Previous backend desktop migrations are incomplete')
  await fs.mkdir(path.dirname(output), { recursive: true })
  await fs.mkdir(output, { recursive: false })
  await fs.writeFile(path.join(output, 'platform-api.jar'), jar, { flag: 'wx' })
  // Preserve the actual previous shell when a native acceptance exposes a bug in the current shell.
  const shell = await run('git', ['archive', '--format=tar', commit + ':apps/desktop/app'], { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 })
  await fs.writeFile(path.join(output, 'desktop-app.tar'), shell.stdout, { flag: 'wx' })
  await fs.writeFile(path.join(output, 'baseline.json'), JSON.stringify({ commit, desktopSchemaVersion, backendSha256: crypto.createHash('sha256').update(jar).digest('hex'), shellArchiveSha256: crypto.createHash('sha256').update(shell.stdout).digest('hex'), shellSource: commit + ':apps/desktop/app', isolatedAcceptancePassed: true, distributionReady: false }, null, 2), { flag: 'wx' })
} finally { await fs.rm(temporary, { recursive: true, force: true }) }
