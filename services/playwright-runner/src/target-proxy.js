import http from 'node:http'
import net from 'node:net'

// Enforce the origin boundary at the network hop, including native redirects.
export async function createTargetProxy(origins) {
  const allowed = new Set(origins), sockets = new Set()
  const server = http.createServer((request, response) => {
    let target
    try { target = new URL(request.url) } catch { response.writeHead(400).end(); return }
    if (target.protocol !== 'http:' || !allowed.has(target.origin)) { response.writeHead(403).end('Target origin is not allowed'); return }
    const headers = { ...request.headers }; delete headers['proxy-connection']; delete headers['proxy-authorization']
    const upstream = http.request(target, { method: request.method, headers }, incoming => {
      response.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(response)
    })
    upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end() })
    response.on('close', () => upstream.destroy())
    request.pipe(upstream)
  })
  server.on('upgrade', (request, client, head) => {
    let target
    try { target = new URL(request.url); if (target.protocol === 'ws:') target.protocol = 'http:' } catch { client.destroy(); return }
    if (target.protocol !== 'http:' || !allowed.has(target.origin)) { client.end('HTTP/1.1 403 Forbidden\r\n\r\n'); return }
    const headers = { ...request.headers }; delete headers['proxy-authorization']; delete headers['proxy-connection']
    const upstream = http.request(target, { method: request.method, headers })
    upstream.on('upgrade', (response, socket, responseHead) => {
      sockets.add(socket); socket.on('close', () => sockets.delete(socket)); socket.on('error', () => client.destroy())
      client.write(`HTTP/1.1 ${response.statusCode} ${response.statusMessage}\r\n${response.rawHeaders.reduce((lines, value, index) => index % 2 === 0 ? lines + value + ': ' + response.rawHeaders[index + 1] + '\r\n' : lines, '')}\r\n`)
      if (head.length) socket.write(head); if (responseHead.length) client.write(responseHead)
      client.pipe(socket); socket.pipe(client); client.on('close', () => socket.destroy())
    })
    upstream.on('response', () => { upstream.destroy(); client.destroy() })
    upstream.on('error', () => client.destroy()); client.on('error', () => upstream.destroy()); upstream.end()
  })
  server.on('connect', (request, client, head) => {
    let target
    try { target = new URL(`https://${request.url}`) } catch { client.end('HTTP/1.1 400 Bad Request\r\n\r\n'); return }
    if (!allowed.has(target.origin)) { client.end('HTTP/1.1 403 Forbidden\r\n\r\n'); return }
    const upstream = net.connect(Number(target.port || 443), target.hostname, () => {
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n'); if (head.length) upstream.write(head)
      client.pipe(upstream); upstream.pipe(client)
    })
    sockets.add(upstream); upstream.on('close', () => sockets.delete(upstream))
    upstream.on('error', () => client.destroy()); client.on('error', () => upstream.destroy()); client.on('close', () => upstream.destroy())
  })
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); socket.on('error', () => {}) })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  return {
    server: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise(resolve => { for (const socket of sockets) socket.destroy(); server.close(resolve) }),
  }
}
