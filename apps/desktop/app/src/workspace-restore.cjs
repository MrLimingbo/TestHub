'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFile } = require('node:child_process')
const { childEnvironment } = require('./local-runtime.cjs')
const { importWorkspace, validateSecrets } = require('./workspace-archive.cjs')
const { writeMarker, digest } = require('./workspace-upgrade.cjs')
// Wait for close even on timeout: staged files must never be removed under a live JVM.
function execute(file, args, options) {
  return new Promise((resolve, reject) => {
    let outcome
    const child = execFile(file, args, options, (error, stdout) => { outcome = { error, stdout } })
    child.once('close', () => outcome?.error ? reject(outcome.error) : resolve({ stdout: outcome?.stdout ?? '' }))
  })
}
const JOURNAL = 'workspace-restore.json'
async function exists(file) { try { await fs.lstat(file); return true } catch (error) { if (error.code === 'ENOENT') return false; throw error } }
async function syncDirectory(directory) {
  if (process.platform === 'win32') return
  const file = await fs.open(directory, 'r'); try { await file.sync() } finally { await file.close() }
}
async function databaseTool(bundle, directory, secrets, restore = false) {
  validateSecrets(secrets)
  const result = await execute(bundle.java, ['-Xmx512m', '-Dloader.main=io.testhub.platform.desktop.DesktopBackupTool', '-cp', bundle.backend,
    'org.springframework.boot.loader.launch.PropertiesLauncher', restore ? 'prepare-restore' : 'inspect', directory], {
    env: { ...childEnvironment(), TESTHUB_LOCAL_DB_PASSWORD: secrets.databasePassword }, timeout: 120000, windowsHide: true, maxBuffer: 1024 * 1024,
  }).catch(() => { throw new Error('备份数据库校验失败，请确认数据库未被使用，且 TestHub 版本兼容。') })
  const line = result.stdout.split(/\r?\n/).findLast(line => line.startsWith('TESTHUB_BACKUP_RESULT='))
  if (!line) throw new Error('备份数据库检查未返回有效结果。')
  return JSON.parse(line.slice('TESTHUB_BACKUP_RESULT='.length))
}
async function stageRestore({ parent, source, password, codec, bundle, tool = databaseTool, progress }) {
  const directory = path.join(parent, 'workspace-restore-' + crypto.randomUUID())
  try {
    const manifest = await importWorkspace({ source, directory, password, progress })
    const secrets = { ...manifest.secrets, heartbeatKey: crypto.randomBytes(32).toString('hex') }
    const report = await tool(bundle, directory, secrets, true)
    const encrypted = await codec.encrypt(JSON.stringify(secrets))
    const key = await fs.open(path.join(directory, 'workspace-secrets.enc'), 'wx', 0o600)
    try { await key.writeFile(encrypted); await key.sync() } finally { await key.close() }
    await writeMarker(directory, await digest(bundle.backend))
    await syncDirectory(directory)
    return { directory, report, createdAt: manifest.createdAt, fileCount: manifest.files.length }
  } catch (error) { await fs.rm(directory, { recursive: true, force: true }); throw error }
}
async function recoverInterruptedRestore(parent) {
  const journal = path.join(parent, JOURNAL)
  if (!await exists(journal)) return false
  if (!(await fs.lstat(journal)).isFile() || (await fs.stat(journal)).size > 4096) throw new Error('工作区恢复记录无效，请保留原目录并联系支持。')
  const record = JSON.parse(await fs.readFile(journal, 'utf8'))
  if (record.version !== 1 || !/^workspace-before-restore-[a-f0-9-]{36}$/.test(record.previous ?? '')) throw new Error('工作区恢复记录无效，未移动任何数据。')
  const current = path.join(parent, 'local-workspace'), previous = path.join(parent, record.previous)
  if (await exists(previous)) {
    if (!(await fs.lstat(previous)).isDirectory()) throw new Error('原工作区快照目录无效。')
    if (await exists(current)) await fs.rename(current, path.join(parent, 'workspace-unconfirmed-' + crypto.randomUUID()))
    await fs.rename(previous, current)
  } else if (!await exists(current)) throw new Error('工作区恢复中断且原快照缺失，请恢复原目录。')
  await fs.rm(journal); await syncDirectory(parent)
  return true
}
async function activateRestore({ parent, candidate, activate, deactivate = async () => {} }) {
  const current = path.join(parent, 'local-workspace')
  if (path.dirname(candidate) !== parent || !/^workspace-restore-[a-f0-9-]{36}$/.test(path.basename(candidate))) throw new Error('恢复候选工作区路径无效。')
  for (const directory of [current, candidate]) if (!(await fs.lstat(directory)).isDirectory()) throw new Error('恢复目录不能是符号链接。')
  const previous = 'workspace-before-restore-' + crypto.randomUUID(), journal = path.join(parent, JOURNAL)
  const handle = await fs.open(journal, 'wx', 0o600)
  try { await handle.writeFile(JSON.stringify({ version: 1, previous })); await handle.sync() } finally { await handle.close() }
  await syncDirectory(parent)
  let committed = false
  try {
    await fs.rename(current, path.join(parent, previous)); await syncDirectory(parent)
    await fs.rename(candidate, current); await syncDirectory(parent)
    await activate()
    await fs.rm(journal); committed = true
    await syncDirectory(parent)
    return path.join(parent, previous)
  } catch (error) {
    if (committed) { error.restoreCommitted = true; throw error }
    try { await deactivate(); await recoverInterruptedRestore(parent) }
    catch (recoveryError) { recoveryError.restoreRecoveryFailed = true; throw recoveryError }
    throw error
  }
}
module.exports = { databaseTool, stageRestore, activateRestore, recoverInterruptedRestore }
