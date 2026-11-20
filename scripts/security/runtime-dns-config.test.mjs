import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { promisify } from 'node:util'
import test from 'node:test'

const execute = promisify(execFile)
const root = fileURLToPath(new URL('../../', import.meta.url))

async function environment(localDomain) {
  const env = Object.fromEntries(Object.entries(process.env)
    .filter(([key]) => !key.startsWith('TESTHUB_') && key !== 'LOCALDOMAIN'))
  if (localDomain !== undefined) env.LOCALDOMAIN = localDomain
  const { stdout } = await execute('docker', ['compose', '--env-file', '.env.production.example',
    '-f', 'compose.production.yaml', 'config', '--format', 'json'], { cwd: root, env })
  return JSON.parse(stdout).services.api.environment
}

test('production Compose inherits the resolver search list when no override is configured', async () => {
  const values = await environment(undefined)
  // Compose retains an unresolved mapping as null in its rendered model and
  // omits it from the container environment.
  assert.equal(values.LOCALDOMAIN, null)
})

test('production Compose passes the operator DNS search list to the runtime guard', async () => {
  const search = 'team.svc.cluster.local svc.cluster.local cluster.local'
  assert.equal((await environment(search)).LOCALDOMAIN, search)
})
