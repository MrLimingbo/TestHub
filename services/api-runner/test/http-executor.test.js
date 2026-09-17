import assert from 'node:assert/strict'
import test from 'node:test'
import { executeHttpRequest } from '../src/http-executor.js'

const deadlineAt = new Date(Date.now() + 60_000).toISOString()

test('rejects a non-whitelisted HTTP origin before any network request', async () => {
  await assert.rejects(() => executeHttpRequest({ method: 'GET', url: 'https://other.example.test/orders', body: '', contentType: '' }, {
    allowedHttpOrigins: ['https://api.example.test'], deadlineAt,
  }), /白名单/)
})

test('rejects a loopback target even if it is listed as an allowed origin', async () => {
  await assert.rejects(() => executeHttpRequest({ method: 'GET', url: 'http://127.0.0.1:8080/private', body: '', contentType: '' }, {
    allowedHttpOrigins: ['http://127.0.0.1:8080'], deadlineAt,
  }), /网络边界校验/)
})

test('supplies the validated address in the shape requested by Node DNS lookup', async (t) => {
  const https = (await import('node:https')).default
  const { EventEmitter } = await import('node:events')
  t.mock.method(https, 'request', (options, respond) => {
    options.lookup('93.184.216.34', { all: true }, (error, addresses) => {
      assert.equal(error, null)
      assert.deepEqual(addresses, [{ address: '93.184.216.34', family: 4 }])
    })
    options.lookup('93.184.216.34', { all: false }, (error, address, family) => {
      assert.equal(error, null)
      assert.equal(address, '93.184.216.34')
      assert.equal(family, 4)
    })
    const request = new EventEmitter()
    request.setTimeout = () => {}
    request.end = () => respond({ statusCode: 200, resume() {}, destroy() {} })
    return request
  })
  assert.deepEqual(await executeHttpRequest({ method: 'GET', url: 'https://93.184.216.34', body: '' }, {
    allowedHttpOrigins: ['https://93.184.216.34'], deadlineAt,
  }), { status: 200, ok: true })
})
