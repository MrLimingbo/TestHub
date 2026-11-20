import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import process from 'node:process'
import { promisify } from 'node:util'
import test from 'node:test'

const execute = promisify(execFile)
const script = fileURLToPath(new URL('./scan-image.sh', import.meta.url))

async function run(t, overrides = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'testhub-security-scan-test-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const capture = join(directory, 'calls.jsonl')
  await writeFile(join(directory, 'docker'), `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.QA_DOCKER_CALLS, JSON.stringify({ args, proxy: process.env.HTTPS_PROXY }) + '\\n');
process.exit(args[0] === 'run' ? Number(process.env.QA_SCANNER_EXIT || 0) : 0);
`, { mode: 0o700 })
  const env = { ...process.env, PATH: `${directory}:${process.env.PATH}`, QA_DOCKER_CALLS: capture }
  for (const key of ['TESTHUB_TRIVY_DB_REPOSITORY', 'TESTHUB_TRIVY_JAVA_DB_REPOSITORY', 'TESTHUB_SECURITY_HTTPS_PROXY', 'TESTHUB_TRIVY_TIMEOUT']) delete env[key]
  Object.assign(env, overrides)
  const result = await execute('bash', [script, 'testhub/qa:fixture'], { env }).then(
    value => ({ ...value, code: 0 }), error => ({ stdout: error.stdout, stderr: error.stderr, code: error.code }),
  )
  const calls = (await readFile(capture, 'utf8')).trim().split('\n').map(line => JSON.parse(line))
  const scan = calls.find(call => call.args[0] === 'run')
  assert.ok(scan, 'The gate must reach the scanner before its result is evaluated')
  return { ...result, scan }
}

test('default scan retains all official mirrors and the existing blocking threshold', async t => {
  const result = await run(t)
  assert.equal(result.code, 0)
  assert.equal(result.scan.args.filter(arg => arg === '--db-repository').length, 3)
  assert.equal(result.scan.args.filter(arg => arg === '--java-db-repository').length, 3)
  assert.ok(result.scan.args.includes('HIGH,CRITICAL'))
  assert.equal(result.scan.args[result.scan.args.indexOf('--exit-code') + 1], '1')
  assert.equal(result.scan.args[result.scan.args.indexOf('--timeout') + 1], '5m')
  assert.match(result.stdout, /Image scan passed/)
})

test('configured database sources and proxy reach the scanner without shell interpolation', async t => {
  const repository = 'registry.example.test/trivy-db:2'
  const proxy = 'http://proxy.example.test:3128'
  const result = await run(t, { TESTHUB_TRIVY_DB_REPOSITORY: repository,
    TESTHUB_TRIVY_JAVA_DB_REPOSITORY: 'ghcr.io/aquasecurity/trivy-java-db:1', TESTHUB_SECURITY_HTTPS_PROXY: proxy, TESTHUB_TRIVY_TIMEOUT: '20m' })
  assert.equal(result.code, 0)
  assert.equal(result.scan.args.filter(arg => arg === '--db-repository').length, 1)
  assert.ok(result.scan.args.includes(repository))
  assert.ok(result.scan.args.includes('ghcr.io/aquasecurity/trivy-java-db:1'))
  assert.ok(result.scan.args.includes('HTTPS_PROXY'))
  assert.equal(result.scan.proxy, proxy)
  assert.equal(result.scan.args[result.scan.args.indexOf('--timeout') + 1], '20m')
  assert.ok(!result.scan.args.some(arg => arg.includes(proxy)))
  assert.ok(!result.stdout.includes(proxy))
})

for (const code of [1, 2]) test(`scanner exit ${code} fails the gate without reporting success`, async t => {
  const result = await run(t, { QA_SCANNER_EXIT: String(code) })
  assert.equal(result.code, code)
  assert.doesNotMatch(result.stdout, /Image scan passed/)
})
