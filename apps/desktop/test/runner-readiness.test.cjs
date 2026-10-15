'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const http = require('node:http')
const { waitForRunnerStatus } = require('../app/src/runner-readiness.cjs')

async function fixture(t, respond) {
  let requests = 0
  const since = '2026-10-15T00:00:00.000Z'
  const server = http.createServer((request, response) => {
    requests++
    assert.equal(new URL(request.url, 'http://localhost').searchParams.get('startedAfter'), since)
    assert.equal(request.headers['x-testhub-desktop-session'], 'isolated-readiness-session')
    respond(request, response, requests)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)) })
  return { options: { origin: 'http://127.0.0.1:' + server.address().port, sessionKey: 'isolated-readiness-session', since,
    statusKey: 'apiRunnerReady', label: '本地执行组件', checkRunning: () => {} }, requests: () => requests }
}
test('a timed out first status request can become ready within the original startup deadline', async t => {
  const f = await fixture(t, (_request, response, count) => { if (count > 1) response.end(JSON.stringify({ data: { apiRunnerReady: true } })) })
  const started = Date.now()
  assert.equal(await waitForRunnerStatus(f.options), true)
  assert.equal(f.requests(), 2)
  assert.ok(Date.now() - started >= 3000 && Date.now() - started < 15000)
})
test('persistent stalled requests stop within the original fifteen second deadline', async t => {
  const f = await fixture(t, () => {}), started = Date.now()
  assert.equal(await waitForRunnerStatus(f.options), false)
  assert.ok(Date.now() - started >= 15000 && Date.now() - started < 17000)
})
test('a stalled response body can recover within the same readiness budget', async t => {
  const f = await fixture(t, (_request, response, count) => {
    if (count === 1) { response.writeHead(200, { 'Content-Type': 'application/json' }); response.flushHeaders() }
    else response.end(JSON.stringify({ data: { apiRunnerReady: true } }))
  })
  assert.equal(await waitForRunnerStatus(f.options), true)
  assert.equal(f.requests(), 2)
})
test('HTTP authorization failures are not retried or treated as pending readiness', async t => {
  const f = await fixture(t, (_request, response) => { response.writeHead(403); response.end('{}') })
  await assert.rejects(waitForRunnerStatus(f.options), /HTTP 403/)
  assert.equal(f.requests(), 1)
})
test('malformed status JSON fails immediately without retry', async t => {
  const f = await fixture(t, (_request, response) => response.end('invalid json'))
  await assert.rejects(waitForRunnerStatus(f.options), SyntaxError)
  assert.equal(f.requests(), 1)
})
test('a null status payload fails immediately without retry', async t => {
  const f = await fixture(t, (_request, response) => response.end('null'))
  await assert.rejects(waitForRunnerStatus(f.options), TypeError)
  assert.equal(f.requests(), 1)
})
test('stopped workspaces do not make another status request', async t => {
  const f = await fixture(t, () => {})
  await assert.rejects(waitForRunnerStatus({ ...f.options, checkRunning: () => { throw new Error('workspace stopped') } }), /workspace stopped/)
  assert.equal(f.requests(), 0)
})
