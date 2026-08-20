'use strict'

const fs = require('node:fs/promises')
const path = require('node:path')

const PREFERENCES_FILE = 'desktop-preferences.json'
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1'])
const HTTP_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'])
const RESTRICTED_HEADERS = new Set(['host', 'content-length', 'connection', 'expect', 'upgrade'])

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

function normalizeHttpDebugRequest(value) {
  if (!value || typeof value !== 'object') throw new Error('桌面调试请求不能为空。')
  const method = String(value.method ?? '').trim().toUpperCase()
  if (!HTTP_METHODS.has(method)) throw new Error('桌面调试请求使用了不支持的 HTTP 方法。')

  let target
  try {
    target = new URL(String(value.url ?? '').trim())
  } catch {
    throw new Error('桌面调试 URL 格式不合法。')
  }
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || String(value.url ?? '').length > 2000) {
    throw new Error('桌面调试仅支持不含用户信息的 HTTP(S) URL。')
  }

  const headers = Array.isArray(value.headers) ? value.headers : []
  if (headers.length > 48) throw new Error('桌面调试请求头不能超过 48 项。')
  const normalizedHeaders = headers.map((header) => {
    const name = String(header?.name ?? '').trim()
    const headerValue = String(header?.value ?? '')
    if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,200}$/.test(name) || headerValue.length > 16000 || /[\r\n]/.test(headerValue)) {
      throw new Error('桌面调试请求头格式不合法。')
    }
    if (RESTRICTED_HEADERS.has(name.toLowerCase())) throw new Error(`桌面调试不允许设置请求头：${name}`)
    return { name, value: headerValue }
  })

  const body = String(value.body ?? '')
  if (body.length > 100000) throw new Error('桌面调试请求体不能超过 100000 字符。')
  const timeoutMs = Number(value.timeoutMs ?? 10000)
  if (!Number.isInteger(timeoutMs) || timeoutMs < 500 || timeoutMs > 10000) {
    throw new Error('桌面调试超时时间应在 500 到 10000 毫秒之间。')
  }

  return {
    name: typeof value.name === 'string' ? value.name.slice(0, 128) : undefined,
    method,
    url: target.toString(),
    headers: normalizedHeaders,
    body,
    timeoutMs,
    environmentCode: typeof value.environmentCode === 'string' ? value.environmentCode : null,
    auth: { type: 'NONE', placement: 'HEADER' },
    protocol: { type: 'HTTP' },
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
  normalizeHttpDebugRequest,
  preferencesPath,
  readPreferences,
  writePreferences,
}
