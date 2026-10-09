import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { createRunnerClient } from './client.mjs'

const token = 'a'.repeat(64)
const stream = 'testhub:runner:api:tasks:v1', group = 'testhub-api-runner-v1'
async function server(t, handler) {
  const requests = []
  const http = createServer(async (request, response) => {
    let body = ''; for await (const chunk of request) body += chunk
    const value = JSON.parse(body); requests.push(value)
    assert.equal(request.headers['x-testhub-desktop-token'], token)
    assert.equal(request.url, '/internal/desktop-broker/v1/command')
    assert.equal(value.engine, 'api'); assert.equal(value.runnerId, 'local')
    const result = await handler(value, request, response)
    if (!response.destroyed && !response.writableEnded) { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ result })) }
  })
  http.listen(0, '127.0.0.1'); await once(http, 'listening')
  t.after(() => { http.closeAllConnections(); http.close() })
  const client = createRunnerClient({ engine: 'api', runnerId: 'local', env: {
    TESTHUB_DESKTOP_BROKER_URL: `http://127.0.0.1:${http.address().port}`, TESTHUB_DESKTOP_BROKER_TOKEN: token,
  } })
  t.after(() => client.quit())
  return { client, requests }
}
const read = (offset = '>') => ['XREADGROUP', 'GROUP', group, 'local', 'COUNT', '1', 'STREAMS', stream, offset]

test('default transport keeps the original Redis factory and configuration', () => {
  const sentinel = {}; let options
  assert.equal(createRunnerClient({ redisFactory: value => { options = value; return sentinel }, redisUrl: 'redis://example', env: {} }), sentinel)
  assert.deepEqual(options, { url: 'redis://example' })
})
test('rejects remote origins, credentials, extra URL parts and missing token', () => {
  for (const url of ['http://example.com', 'http://localhost', 'https://127.0.0.1', 'http://user@127.0.0.1', 'http://127.0.0.1/path', 'http://127.0.0.1/?x=1'])
    assert.throws(() => createRunnerClient({ env: { TESTHUB_DESKTOP_BROKER_URL: url, TESTHUB_DESKTOP_BROKER_TOKEN: token } }))
  assert.throws(() => createRunnerClient({ env: { TESTHUB_DESKTOP_BROKER_URL: 'http://127.0.0.1' } }))
})
test('maps heartbeat atomically and prevents arbitrary Redis operations', async t => {
  const { client, requests } = await server(t, value => value.operation === 'ping' ? 'PONG' : 'OK')
  await client.connect()
  await client.multi().set('testhub:runner:api:heartbeat:v1:local', '{}', { EX: 30 }).sAdd('testhub:runner:api:members:v1', 'local').exec()
  assert.deepEqual(requests.at(-1), { engine: 'api', runnerId: 'local', operation: 'heartbeat', body: '{}', ttlSeconds: 30 })
  await assert.rejects(client.sendCommand(['FLUSHALL']), /Unsupported/)
  await assert.rejects(client.multi().set('other', '{}', { EX: 30 }).sAdd('testhub:runner:api:members:v1', 'local').exec(), /Invalid/)
})
test('recovers a lost read response without re-delivering a task already running in this process', async t => {
  let failed = false
  const { client } = await server(t, (value, request) => {
    if (value.operation === 'ack') return 1
    if (value.offset === '>') {
      if (!failed) { failed = true; request.socket.destroy(); return [] }
      return []
    }
    if (!failed) return []
    if (value.offset === '0') return [['1-0', ['taskId', 'first']]]
    if (value.offset === '1-0') return [['2-0', ['taskId', 'second']]]
    return []
  })
  await assert.rejects(client.sendCommand(read()))
  assert.equal((await client.sendCommand(read()))[0][1][0][0], '1-0')
  assert.equal((await client.sendCommand(read()))[0][1][0][0], '2-0')
  assert.equal(await client.sendCommand(read()), null)
  assert.equal(await client.duplicate().sendCommand(['XACK', stream, group, '1-0']), 1)
})
test('quit interrupts a blocking read promptly', async t => {
  const { client } = await server(t, () => [])
  const readResult = client.sendCommand(['XREADGROUP', 'GROUP', group, 'local', 'COUNT', '1', 'BLOCK', '5000', 'STREAMS', stream, '>'])
  await client.quit()
  await assert.rejects(readResult)
})
test('never follows a redirect carrying the local credential', async t => {
  const { client } = await server(t, (value, request, response) => {
    response.writeHead(302, { Location: 'http://127.0.0.1:1/' }); response.end()
  })
  await assert.rejects(client.connect())
})
