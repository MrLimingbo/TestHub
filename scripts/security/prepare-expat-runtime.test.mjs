import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { access, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { fileURLToPath, URL } from 'node:url'
import { promisify } from 'node:util'
import test from 'node:test'

const execute = promisify(execFile)

async function attempt(t, library, architecture, downloadExit = 0) {
  const script = fileURLToPath(new URL(`./prepare-${library}-runtime.sh`, import.meta.url))
  const directory = await mkdtemp(join(tmpdir(), 'testhub-expat-integrity-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  await writeFile(join(directory, 'curl'), `#!/usr/bin/env node
const fs = require('node:fs');
fs.writeFileSync(process.env.QA_DOWNLOAD_MARKER, 'called');
if (process.env.QA_DOWNLOAD_EXIT !== '0') process.exit(Number(process.env.QA_DOWNLOAD_EXIT));
const args = process.argv.slice(2);
fs.writeFileSync(args[args.indexOf('--output') + 1], 'tampered package contents');
`, { mode: 0o700 })
  await writeFile(join(directory, 'dpkg-deb'), `#!/usr/bin/env node
require('node:fs').writeFileSync(process.env.QA_EXTRACT_MARKER, 'called');
process.exit(99);
`, { mode: 0o700 })
  const output = join(directory, 'output')
  const extraction = join(directory, 'extraction-called')
  const download = join(directory, 'download-called')
  const result = await execute('sh', [script, architecture, output, directory], { env: {
    ...process.env, PATH: `${directory}:${process.env.PATH}`, QA_DOWNLOAD_MARKER: download,
    QA_EXTRACT_MARKER: extraction, QA_DOWNLOAD_EXIT: String(downloadExit),
  } }).then(value => ({ ...value, code: 0 }), error => ({ stderr: error.stderr, code: error.code }))
  await assert.rejects(access(extraction), 'Rejected packages must never reach dpkg-deb')
  await assert.rejects(access(output), 'Rejected packages must not create an overlay')
  return { ...result, download }
}

for (const library of ['expat', 'freetype', 'gcc']) {
  test(`${library}: unsupported architecture is rejected before any download`, async t => {
    const result = await attempt(t, library, 'unsupported')
    assert.equal(result.code, 1)
    assert.match(result.stderr, /Unsupported .* target architecture/)
    await assert.rejects(access(result.download))
  })

  test(`${library}: a corrupted package fails its real SHA-256 check before extraction`, async t => {
    const result = await attempt(t, library, 'arm64')
    await access(result.download)
    assert.equal(result.code, 1)
    assert.match(result.stderr, /checksum did NOT match|checksum mismatch/i)
  })

  test(`${library}: a failed download preserves failure and never extracts a package`, async t => {
    const result = await attempt(t, library, 'amd64', 42)
    assert.equal(result.code, 42)
  })
}

test('a changed upstream patch is rejected before downloading or compiling source', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'testhub-expat-patch-test-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const patch = join(directory, 'changed.patch')
  await writeFile(patch, 'changed upstream patch')
  const script = fileURLToPath(new URL('./backport-expat-runtime.sh', import.meta.url))
  const output = join(directory, 'overlay')
  const result = await execute('sh', [script, output, patch]).then(
    value => ({ ...value, code: 0 }), error => ({ stderr: error.stderr, code: error.code }),
  )
  assert.equal(result.code, 1)
  assert.match(result.stderr, /checksum did NOT match/)
  await assert.rejects(access(output))
})
