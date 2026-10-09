import http from 'node:http'
import https from 'node:https'
import { lookup } from 'node:dns/promises'
import net from 'node:net'

function privateAddress(address) {
  if (net.isIP(address) === 4) {
    const [a, b] = address.split('.').map(Number)
    return a === 0 || a === 10 || a === 127 || (a === 100 && b >= 64 && b <= 127)
      || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
      || (a === 198 && (b === 18 || b === 19)) || a >= 224
  }
  const normalized = new URL(`http://[${address}]`).hostname.slice(1, -1).toLowerCase()
  if (normalized.startsWith('::ffff:')) {
    const words = normalized.slice(7).split(':').map(word => Number.parseInt(word, 16))
    if (words.length !== 2) return true
    return privateAddress(`${words[0] >> 8}.${words[0] & 255}.${words[1] >> 8}.${words[1] & 255}`)
  }
  return normalized.startsWith('::') || normalized.startsWith('fc') || normalized.startsWith('fd')
    || /^fe[89ab]/.test(normalized) || normalized.startsWith('ff')
}

async function targetAddresses(hostname, allowPrivateNetwork) {
  const normalizedHostname = hostname.replace(/^\[|\]$/g, '')
  if (net.isIP(normalizedHostname)) {
    if (!allowPrivateNetwork && privateAddress(normalizedHostname)) throw new Error('HTTP 地址未通过网络边界校验')
    return [{ address: normalizedHostname, family: net.isIP(normalizedHostname) }]
  }
  const addresses = await lookup(normalizedHostname, { all: true, verbatim: true })
  if (!addresses.length || (!allowPrivateNetwork && addresses.some((entry) => privateAddress(entry.address)))) {
    throw new Error('HTTP 地址未通过网络边界校验')
  }
  return addresses
}

function requestWithPinnedAddress(request, url, addresses, timeoutMilliseconds) {
  return new Promise((resolve, reject) => {
    const transport = url.protocol === 'https:' ? https : http
    const headers = request.contentType ? { 'Content-Type': request.contentType } : undefined
    const clientRequest = transport.request({
      protocol: url.protocol,
      hostname: url.hostname.replace(/^\[|\]$/g, ''),
      port: url.port || undefined,
      path: `${url.pathname}${url.search}`,
      method: request.method,
      headers,
      lookup(_hostname, options, callback) {
        const selected = addresses[0]
        if (options.all) callback(null, [selected])
        else callback(null, selected.address, selected.family)
      },
    }, (response) => {
      const status = response.statusCode ?? 0
      response.resume()
      response.destroy()
      resolve({ status, ok: status >= 200 && status < 300 })
    })
    clientRequest.setTimeout(timeoutMilliseconds, () => clientRequest.destroy(new Error('HTTP 请求超时')))
    clientRequest.once('error', reject)
    if (request.body) clientRequest.write(request.body, 'utf8')
    clientRequest.end()
  })
}

export async function executeHttpRequest(request, config) {
  const url = new URL(request.url)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('HTTP 请求地址不合法')
  if (!config.allowedHttpOrigins.includes(url.origin)) throw new Error('HTTP 地址不在 Runner 白名单中')
  const addresses = await targetAddresses(url.hostname, config.allowPrivateNetwork === true)
  const remaining = Math.max(1, Math.min(10_000, new Date(config.deadlineAt).getTime() - Date.now()))
  return requestWithPinnedAddress(request, url, addresses, remaining)
}
