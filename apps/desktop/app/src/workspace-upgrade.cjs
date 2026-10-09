'use strict'
const fs = require('node:fs/promises')
const { createReadStream } = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { inventory } = require('./workspace-archive.cjs')
const MARKER = 'workspace-runtime.json'
async function exists(file) { try { await fs.lstat(file); return true } catch (error) { if (error.code === 'ENOENT') return false; throw error } }
async function digest(file) {
  const hash = crypto.createHash('sha256')
  for await (const bytes of createReadStream(file)) hash.update(bytes)
  return hash.digest('hex')
}
async function syncDirectory(directory) {
  if (process.platform === 'win32') return
  const handle = await fs.open(directory, 'r'); try { await handle.sync() } finally { await handle.close() }
}
async function writeMarker(directory, backendHash) {
  const temporary = path.join(directory, '.runtime-' + crypto.randomUUID())
  try {
    const handle = await fs.open(temporary, 'wx', 0o600)
    try { await handle.writeFile(JSON.stringify({ version: 1, backendHash })); await handle.sync() } finally { await handle.close() }
    await fs.rename(temporary, path.join(directory, MARKER)); await syncDirectory(directory)
  } finally { await fs.rm(temporary, { force: true }) }
}
function journalFor(directory) { return directory + '.upgrade.json' }
async function recoverUpgrade(directory) {
  const journal = journalFor(directory)
  if (!await exists(journal)) return false
  const stat = await fs.lstat(journal)
  if (!stat.isFile() || stat.size > 4096) throw new Error('升级恢复记录无效，请保留工作区目录。')
  const record = JSON.parse(await fs.readFile(journal, 'utf8'))
  if (record.version !== 1 || !/^workspace-before-upgrade-[a-f0-9-]{36}$/.test(record.snapshot ?? '')
    || !/^workspace-failed-upgrade-[a-f0-9-]{36}$/.test(record.failed ?? '')) throw new Error('升级恢复路径无效。')
  const parent = path.dirname(directory), snapshot = path.join(parent, record.snapshot), failed = path.join(parent, record.failed)
  if (await exists(snapshot)) {
    if (!(await fs.lstat(snapshot)).isDirectory()) throw new Error('升级前快照目录无效。')
    // The fixed failed name also makes recovery itself restartable after either rename.
    if (await exists(directory)) {
      if (await exists(failed)) throw new Error('升级回退目录冲突，请保留所有目录并联系支持。')
      await fs.rename(directory, failed); await syncDirectory(parent)
    }
    await fs.rename(snapshot, directory); await syncDirectory(parent)
  } else if (!await exists(directory) || !await exists(failed)) throw new Error('升级前快照缺失，请保留现有目录。')
  await fs.rm(journal); await syncDirectory(parent)
  return true
}
// Called with all workspace processes stopped, before the new backend can migrate the database.
async function prepareUpgrade(directory, backend) {
  await recoverUpgrade(directory)
  const backendHash = await digest(backend)
  if (!await exists(path.join(directory, 'workspace.mv.db'))) return { directory, backendHash }
  const marker = path.join(directory, MARKER)
  if (await exists(marker)) {
    const stat = await fs.lstat(marker)
    if (!stat.isFile() || stat.size > 4096) throw new Error('工作区运行版本记录无效。')
    const current = JSON.parse(await fs.readFile(marker, 'utf8'))
    if (current.version === 1 && current.backendHash === backendHash) return { directory, backendHash }
  }
  const parent = path.dirname(directory), name = 'workspace-before-upgrade-' + crypto.randomUUID(), snapshot = path.join(parent, name)
  const files = await inventory(directory)
  const checksums = new Map(files.map(item => [item.name, item.sha256]))
  await fs.mkdir(snapshot, { mode: 0o700 })
  let journalCreated = false
  try {
    for (const name of [...files.map(item => item.name), 'workspace-secrets.enc', ...(await exists(marker) ? [MARKER] : [])]) {
      const source = path.join(directory, name), target = path.join(snapshot, name)
      if (!(await fs.lstat(source)).isFile()) throw new Error('升级前数据文件无效。')
      await fs.mkdir(path.dirname(target), { recursive: true, mode: 0o700 })
      await fs.copyFile(source, target, require('node:fs').constants.COPYFILE_EXCL)
      const handle = await fs.open(target, 'r'); try { await handle.sync() } finally { await handle.close() }
      const expected = checksums.get(name)
      if (expected && await digest(target) !== expected) throw new Error('升级快照校验失败，未启动新版本。')
      for (let parent = path.dirname(target); parent !== snapshot; parent = path.dirname(parent)) await syncDirectory(parent)
    }
    await syncDirectory(snapshot); await syncDirectory(parent)
    const journal = await fs.open(journalFor(directory), 'wx', 0o600); journalCreated = true
    try { await journal.writeFile(JSON.stringify({ version: 1, snapshot: name, failed: 'workspace-failed-upgrade-' + crypto.randomUUID() })); await journal.sync() }
    finally { await journal.close() }
    await syncDirectory(parent)
    return { directory, backendHash, snapshot }
  } catch (error) {
    // Once journal creation begins, retain the snapshot for conservative manual/automatic recovery.
    if (!journalCreated) await fs.rm(snapshot, { recursive: true, force: true })
    throw error
  }
}
async function commitUpgrade(upgrade) {
  await writeMarker(upgrade.directory, upgrade.backendHash)
  if (upgrade.snapshot) { await fs.rm(journalFor(upgrade.directory)); await syncDirectory(path.dirname(upgrade.directory)) }
}
module.exports = { prepareUpgrade, commitUpgrade, recoverUpgrade, writeMarker, digest }
