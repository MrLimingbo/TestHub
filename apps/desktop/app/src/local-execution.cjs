'use strict'
const fs = require('node:fs/promises')
const path = require('node:path')
const crypto = require('node:crypto')

function normalizeOrigins(value) {
  if (!Array.isArray(value) || value.length > 50) throw new Error('最多可以设置 50 个目标地址。')
  const origins = value.map(item => {
    if (typeof item !== 'string' || item.length > 2048) throw new Error('目标地址格式无效。')
    let url
    try { url = new URL(item.trim()) } catch { throw new Error('请填写完整的 HTTP(S) 地址，例如 http://localhost:3000。') }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash
      || url.pathname !== '/' || url.hostname.includes('*')) throw new Error('目标地址只能包含协议、主机和端口，不能包含账号、路径、查询参数或通配符。')
    return url.origin
  })
  return [...new Set(origins)]
}
function normalizeAppiumServer(value = '') {
  if (typeof value !== 'string' || value.length > 2048) throw new Error('Appium 服务地址格式无效。')
  if (!value.trim()) return ''
  let url
  try { url = new URL(value.trim()) } catch { throw new Error('请填写完整的 Appium HTTP(S) 地址。') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash
    || (url.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
    throw new Error('Appium 地址只能使用无凭据的 HTTPS 或本机 HTTP，不能包含查询参数。')
  return url.toString().replace(/\/$/, '')
}
async function readExecutionSettings(userDataPath) {
  let content
  try { content = await fs.readFile(path.join(userDataPath, 'local-execution.json'), 'utf8') }
  catch (error) { if (error.code === 'ENOENT') return { allowedOrigins: [], appiumServerUrl: '' }; throw error }
  const configuration = JSON.parse(content)
  if (configuration.version !== 1) throw new Error('本地执行设置版本不兼容。')
  return { allowedOrigins: normalizeOrigins(configuration.allowedOrigins), appiumServerUrl: normalizeAppiumServer(configuration.appiumServerUrl) }
}
async function readExecutionOrigins(userDataPath) { return (await readExecutionSettings(userDataPath)).allowedOrigins }
async function writeExecutionOrigins(userDataPath, value) {
  const configuration = await readExecutionSettings(userDataPath)
  return (await writeExecutionSettings(userDataPath, { ...configuration, allowedOrigins: value })).allowedOrigins
}
async function writeExecutionSettings(userDataPath, value) {
  const allowedOrigins = normalizeOrigins(value?.allowedOrigins), appiumServerUrl = normalizeAppiumServer(value?.appiumServerUrl)
  await fs.mkdir(userDataPath, { recursive: true, mode: 0o700 })
  const file = path.join(userDataPath, 'local-execution.json')
  const temporary = `${file}.${crypto.randomUUID()}.tmp`
  try {
    await fs.writeFile(temporary, JSON.stringify({ version: 1, allowedOrigins, appiumServerUrl }) + '\n', { flag: 'wx', mode: 0o600 })
    await fs.rename(temporary, file)
  } finally { await fs.rm(temporary, { force: true }) }
  return { allowedOrigins, appiumServerUrl }
}
module.exports = { normalizeOrigins, normalizeAppiumServer, readExecutionOrigins, readExecutionSettings, writeExecutionOrigins, writeExecutionSettings }
