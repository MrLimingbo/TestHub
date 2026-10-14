'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const { inventory, digest, verifyIntegrity } = require('../build/runtime-integrity.cjs')
const { verifyRuntime } = require('../build/skip-runtime-dependency-copy.cjs')
async function fixture(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-integrity-'))
  t.after(() => fs.rm(root, { recursive: true, force: true }))
  const directory = path.join(root, 'darwin-arm64'); await fs.mkdir(directory)
  const manifest = { version: 1, platform: 'darwin', arch: 'arm64', distributionReady: false,
    java: 'java', node: 'node', backend: 'backend.jar', frontend: 'web', apiRunner: 'api.js', jmeterRunner: 'jmeter.js',
    jmeterWatchdog: 'watchdog.jar', appiumRunner: 'appium.js', playwrightRunner: 'browser.js', playwrightTool: 'tool.mjs' }
  for (const file of ['java', 'node', 'backend.jar', 'api.js', 'jmeter.js', 'watchdog.jar', 'appium.js', 'browser.js', 'tool.mjs']) await fs.writeFile(path.join(directory, file), file)
  await fs.mkdir(path.join(directory, 'web')); await fs.writeFile(path.join(directory, 'web/index.html'), 'frontend')
  manifest.backendSha256 = await digest(path.join(directory, manifest.backend))
  manifest.integrity = await inventory(directory)
  await fs.writeFile(path.join(directory, 'runtime.json'), JSON.stringify(manifest))
  return { root, directory, manifest }
}
test('whole runtime verifies and source frontend or Runner corruption cannot pass packaging', async t => {
  for (const file of ['backend.jar', 'web/index.html', 'browser.js', 'appium.js']) {
    const f = await fixture(t); await verifyRuntime(f.root, 'darwin', 'arm64', true)
    await fs.appendFile(path.join(f.directory, file), 'changed')
    await assert.rejects(verifyRuntime(f.root, 'darwin', 'arm64', true), /变更/)
  }
})
test('removed and undeclared files both fail integrity validation', async t => {
  const f = await fixture(t); await fs.rm(path.join(f.directory, 'tool.mjs'))
  await assert.rejects(verifyIntegrity(f.directory, f.manifest), /文件缺失/)
  await fs.writeFile(path.join(f.directory, 'tool.mjs'), 'tool.mjs'); await fs.writeFile(path.join(f.directory, 'extra.js'), 'extra')
  await assert.rejects(verifyIntegrity(f.directory, f.manifest), /文件缺失/)
})
test('backend digest is checked separately from the inventory', async t => {
  const f = await fixture(t); f.manifest.backendSha256 = '0'.repeat(64)
  await assert.rejects(verifyIntegrity(f.directory, f.manifest), /后端构建摘要/)
})
test('unsafe manifest paths cannot be used to verify another workspace', async t => {
  const f = await fixture(t)
  for (const file of ['../outside', '/absolute', 'web/../outside', 'C:/outside', 'web\\file', 'runtime.json'])
    await assert.rejects(verifyIntegrity(f.directory, { ...f.manifest, integrity: { version: 1, files: { [file]: { size: 1, sha256: '0'.repeat(64) } } } }), /字段无效/)
})
test('internal license symlinks verify but outside and circular links fail', { skip: process.platform === 'win32' }, async t => {
  const f = await fixture(t); await fs.symlink('backend.jar', path.join(f.directory, 'license-link'))
  f.manifest.integrity = await inventory(f.directory); await verifyIntegrity(f.directory, f.manifest)
  await fs.symlink('../', path.join(f.directory, 'outside'))
  await assert.rejects(inventory(f.directory), /包外/)
  await fs.rm(path.join(f.directory, 'outside')); await fs.symlink('.', path.join(f.directory, 'loop'))
  await assert.rejects(inventory(f.directory), /循环/)
})
test('new runtime inventories require Appium and formal packaging still rejects an unvalidated build', async t => {
  const f = await fixture(t); await assert.rejects(verifyRuntime(f.root, 'darwin', 'arm64'), /分发验证/)
  delete f.manifest.appiumRunner; await fs.writeFile(path.join(f.directory, 'runtime.json'), JSON.stringify(f.manifest))
  await assert.rejects(verifyRuntime(f.root, 'darwin', 'arm64', true), /移动端 Runner/)
})
test('a distribution-ready flag alone cannot make an old bundle pass formal packaging', async t => {
  const f = await fixture(t); f.manifest.distributionReady = true; delete f.manifest.integrity
  await fs.writeFile(path.join(f.directory, 'runtime.json'), JSON.stringify(f.manifest))
  await assert.rejects(verifyRuntime(f.root, 'darwin', 'arm64'), /完整性清单/)
})
test('packed macOS resources are verified and missing universal counterpart fails', async t => {
  const f = await fixture(t), out = path.join(f.root, 'out'), runtime = path.join(out, 'TestHub.app/Contents/Resources/local-runtime')
  await fs.mkdir(runtime, { recursive: true }); await fs.cp(f.directory, path.join(runtime, 'darwin-arm64'), { recursive: true })
  const { verifyPackaged } = require('../build/verify-packaged-runtime.cjs')
  const context = { appOutDir: out, arch: 3, packager: { platform: { nodeName: 'darwin' }, appInfo: { productFilename: 'TestHub' } } }
  await verifyPackaged(context, true)
  await assert.rejects(verifyPackaged({ ...context, arch: 4 }, true), /缺少 darwin-x64/)
  await fs.appendFile(path.join(runtime, 'darwin-arm64/web/index.html'), 'corrupted copy')
  await assert.rejects(verifyPackaged(context, true), /变更/)
})
test('Windows packager uses resources and rejects missing copied runtime and unknown architectures', async t => {
  const f = await fixture(t), out = path.join(f.root, 'win-unpacked'), runtime = path.join(out, 'resources/local-runtime')
  const { verifyPackaged } = require('../build/verify-packaged-runtime.cjs')
  const context = { appOutDir: out, arch: 1, packager: { platform: { nodeName: 'win32' } } }
  await assert.rejects(verifyPackaged(context, true), /缺少 win32-x64/)
  await fs.mkdir(runtime, { recursive: true }); const destination = path.join(runtime, 'win32-x64')
  await fs.cp(f.directory, destination, { recursive: true }); f.manifest.platform = 'win32'; f.manifest.arch = 'x64'
  await fs.writeFile(path.join(destination, 'runtime.json'), JSON.stringify(f.manifest)); await verifyPackaged(context, true)
  await assert.rejects(verifyPackaged({ ...context, arch: 99 }, true), /未支持/)
})
test('a runtime directory cannot be a link to a different bundle', { skip: process.platform === 'win32' }, async t => {
  const f = await fixture(t), other = await fixture(t)
  await fs.rm(f.directory, { recursive: true }); await fs.symlink(other.directory, f.directory)
  await assert.rejects(verifyRuntime(f.root, 'darwin', 'arm64', true), /目录指向安装包之外/)
})
