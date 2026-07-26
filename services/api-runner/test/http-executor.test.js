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
