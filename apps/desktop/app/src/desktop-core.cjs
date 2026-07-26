'use strict'

const fs = require('node:fs/promises')
const path = require('node:path')

const PREFERENCES_FILE = 'desktop-preferences.json'
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1'])

function normalizeServerUrl(value) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error('请输入 TestHub 服务地址。')
  }

  let parsed
  try {
    parsed = new URL(value.trim())
  } catch {
    throw new Error('服务地址必须是完整的 HTTP(S) URL。')
  }

  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) {
    throw new Error('服务地址必须是没有凭据的 HTTP(S) URL。')
  }
  if (parsed.search || parsed.hash || (parsed.pathname !== '' && parsed.pathname !== '/')) {
    throw new Error('服务地址只能指向 TestHub Web 根地址，不能包含路径、查询参数或片段。')
  }
  if (parsed.protocol !== 'https:' && !LOCAL_HOSTS.has(parsed.hostname.toLowerCase())) {
    throw new Error('非本机 TestHub 服务必须使用 HTTPS。')
  }

  return parsed.origin
}

function isTrustedServerUrl(candidate, configuredServerUrl) {
  try {
    return new URL(candidate).origin === normalizeServerUrl(configuredServerUrl)
  } catch {
    return false
  }
}

function preferencesPath(userDataPath) {
  return path.join(userDataPath, PREFERENCES_FILE)
}

async function readPreferences(userDataPath) {
  try {
    const content = await fs.readFile(preferencesPath(userDataPath), 'utf8')
    const parsed = JSON.parse(content)
    return { serverUrl: normalizeServerUrl(parsed.serverUrl) }
  } catch {
    return { serverUrl: null }
  }
}

async function writePreferences(userDataPath, serverUrl) {
  const normalized = normalizeServerUrl(serverUrl)
  await fs.mkdir(userDataPath, { recursive: true, mode: 0o700 })
  const filePath = preferencesPath(userDataPath)
  const temporaryPath = `${filePath}.${process.pid}.tmp`
  await fs.writeFile(temporaryPath, `${JSON.stringify({ serverUrl: normalized }, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
  await fs.chmod(temporaryPath, 0o600)
  await fs.rename(temporaryPath, filePath)
  return normalized
}

module.exports = {
  normalizeServerUrl,
  isTrustedServerUrl,
  preferencesPath,
  readPreferences,
  writePreferences,
}
