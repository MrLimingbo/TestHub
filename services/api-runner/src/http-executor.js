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
  const normalized = address.toLowerCase()
  return normalized === '::1' || normalized.startsWith('fc') || normalized.startsWith('fd')
    || normalized.startsWith('fe80:') || normalized.startsWith('::ffff:127.')
}

async function publicAddresses(hostname) {
  const normalizedHostname = hostname.replace(/^\[|\]$/g, '')
  if (net.isIP(normalizedHostname)) {
    if (privateAddress(normalizedHostname)) throw new Error('HTTP 地址未通过网络边界校验')
    return [{ address: normalizedHostname, family: net.isIP(normalizedHostname) }]
  }
  const addresses = await lookup(normalizedHostname, { all: true, verbatim: true })
  if (!addresses.length || addresses.some((entry) => privateAddress(entry.address))) {
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
      hostname: url.hostname,
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
  if (!config.allowedHttpOrigins.includes(url.origin)) throw new Error('HTTP 地址不在 Runner 白名单中')
  const addresses = await publicAddresses(url.hostname)
  const remaining = Math.max(1, Math.min(10_000, new Date(config.deadlineAt).getTime() - Date.now()))
  return requestWithPinnedAddress(request, url, addresses, remaining)
}
