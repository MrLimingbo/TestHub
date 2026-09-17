import assert from 'node:assert/strict'
import { test } from 'node:test'
import http from 'node:http'
import net from 'node:net'
import { createTargetProxy } from '../src/target-proxy.js'

async function fixture(t) {
  let hits = 0
  const target = http.createServer((req, res) => { hits++; res.end('target') })
  await new Promise(resolve => target.listen(0, '127.0.0.1', resolve))
  const origin = `http://127.0.0.1:${target.address().port}`
  const proxy = await createTargetProxy([origin])
  t.after(async () => { await proxy.close(); await new Promise(resolve => target.close(resolve)) })
  return { proxy, origin, hits: () => hits }
}
function request(proxy, url) {
  return new Promise((resolve, reject) => {
    http.get(proxy.server, { path: url }, response => {
      let body = ''; response.on('data', chunk => { body += chunk }); response.on('end', () => resolve({ status: response.statusCode, body }))
    }).on('error', reject)
  })
}
test('proxy forwards permitted HTTP requests', async t => {
  const f = await fixture(t)
  assert.deepEqual(await request(f.proxy, f.origin + '/ok'), { status: 200, body: 'target' })
  assert.equal(f.hits(), 1)
})
test('proxy blocks a different origin before the target receives it', async t => {
  const f = await fixture(t)
  assert.equal((await request(f.proxy, f.origin.replace('127.0.0.1', 'localhost') + '/blocked')).status, 403)
  assert.equal(f.hits(), 0)
})
test('proxy rejects malformed request targets', async t => {
  const f = await fixture(t)
  assert.equal((await request(f.proxy, '/relative')).status, 400)
  assert.equal(f.hits(), 0)
})
test('proxy rejects CONNECT outside the HTTPS allowlist', async t => {
  const f = await fixture(t), url = new URL(f.proxy.server)
  const result = await new Promise((resolve, reject) => {
    const socket = net.connect(Number(url.port), url.hostname, () => socket.write('CONNECT localhost:443 HTTP/1.1\r\nHost: localhost:443\r\n\r\n'))
    let body = ''; socket.on('data', chunk => { body += chunk }); socket.on('end', () => resolve(body)); socket.on('error', reject)
  })
  assert.match(result, /403 Forbidden/); assert.equal(f.hits(), 0)
})
test('proxy tunnels only a permitted HTTPS authority', async t => {
  const target = net.createServer(socket => socket.on('data', bytes => socket.write(bytes)))
  await new Promise(resolve => target.listen(0, '127.0.0.1', resolve))
  const authority = `127.0.0.1:${target.address().port}`, proxy = await createTargetProxy([`https://${authority}`])
  t.after(async () => { await proxy.close(); await new Promise(resolve => target.close(resolve)) })
  const url = new URL(proxy.server)
  await new Promise((resolve, reject) => {
    const socket = net.connect(Number(url.port), url.hostname, () => socket.write(`CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n\r\n`))
    let body = '', sent = false
    socket.on('data', chunk => {
      body += chunk
      if (!sent && body.includes('\r\n\r\n')) { assert.match(body, /200 Connection Established/); sent = true; socket.write('tunnel-proof') }
      if (body.includes('tunnel-proof')) { socket.destroy(); resolve() }
    }); socket.on('error', reject)
  })
})
