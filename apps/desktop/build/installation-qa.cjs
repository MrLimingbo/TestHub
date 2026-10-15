'use strict'
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')

const OWNER = '.testhub-install-qa-owner'
function appData(platform = process.platform, env = process.env, home = os.homedir()) {
  if (platform === 'darwin') return path.posix.join(home, 'Library/Application Support')
  if (platform === 'win32' && env.APPDATA && path.win32.isAbsolute(env.APPDATA)) return env.APPDATA
  throw new Error('Installation acceptance requires native macOS or Windows app data')
}
function sessionDirectory(record, options = {}) {
  if (record.version !== 1 || !/^TestHub-QA-[a-f0-9-]{36}$/.test(record.qaName || '')) throw new Error('Invalid owned installation session')
  if (record.cleanRunnerDefaults && ((options.env || process.env).GITHUB_ACTIONS !== 'true' || (options.env || process.env).RUNNER_ENVIRONMENT !== 'github-hosted')) throw new Error('Exact application defaults require a fresh hosted runner')
  return path.join(appData(options.platform, options.env, options.home), record.cleanRunnerDefaults ? 'testhub-desktop-app' : record.qaName)
}
async function createSession(cleanRunnerDefaults = false, options = {}) {
  const record = { version: 1, qaName: 'TestHub-QA-' + crypto.randomUUID(), cleanRunnerDefaults, token: crypto.randomBytes(32).toString('hex') }
  const directory = sessionDirectory(record, options)
  // Never adopt, replace or delete a pre-existing user data directory.
  await fs.mkdir(directory, { recursive: false, mode: 0o700 })
  try { await fs.writeFile(path.join(directory, OWNER), record.token, { flag: 'wx', mode: 0o600 }) }
  catch (error) { await fs.rm(directory, { recursive: true, force: true }); throw error }
  return record
}
async function verifySession(record, options = {}) {
  const directory = sessionDirectory(record, options)
  if (!/^[a-f0-9]{64}$/.test(record.token || '') || (await fs.lstat(directory)).isSymbolicLink()
    || await fs.readFile(path.join(directory, OWNER), 'utf8') !== record.token) throw new Error('Installation data is not owned by this acceptance session')
  return directory
}
async function removeSession(record, options = {}) {
  const directory = await verifySession(record, options)
  await fs.rm(directory, { recursive: true, force: false })
}
module.exports = { appData, sessionDirectory, createSession, verifySession, removeSession }
