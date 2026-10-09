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

test('desktop authorization reaches only approved loopback origins and never follows redirects', async t => {
  const http = (await import('node:http')).default
  let hits = 0
  const target = http.createServer((_request, response) => { hits++;response.writeHead(200);response.end('ok') })
  await new Promise(resolve=>target.listen(0,'127.0.0.1',resolve))
  t.after(()=>new Promise(resolve=>target.close(resolve)))
  const origin = `http://127.0.0.1:${target.address().port}`
  const limits = { allowedHttpOrigins: [origin], allowPrivateNetwork: true, deadlineAt: new Date(Date.now()+10000).toISOString() }
  const request = { method:'GET',url:origin,body:'' }
  assert.deepEqual(await executeHttpRequest(request,limits),{status:200,ok:true})
  await assert.rejects(executeHttpRequest(request,{...limits,allowedHttpOrigins:[]}),/白名单/)
  await assert.rejects(executeHttpRequest(request,{...limits,allowPrivateNetwork:false}),/网络边界/)
  const redirect = http.createServer((_request,response)=>{response.writeHead(302,{Location:origin});response.end()})
  await new Promise(resolve=>redirect.listen(0,'127.0.0.1',resolve))
  t.after(()=>new Promise(resolve=>redirect.close(resolve)))
  const redirectOrigin=`http://127.0.0.1:${redirect.address().port}`
  assert.deepEqual(await executeHttpRequest({...request,url:redirectOrigin},{...limits,allowedHttpOrigins:[redirectOrigin]}),{status:302,ok:false})
  assert.equal(hits,1)
})

test('enterprise mode blocks private IPv4-mapped IPv6 and link-local representations', async () => {
  for (const address of ['::ffff:127.0.0.1','::ffff:10.0.0.1','::ffff:192.168.1.1','::','fe90::1']) {
    const url = new URL(`http://[${address}]:3000`)
    await assert.rejects(executeHttpRequest({method:'GET',url:url.href,body:''},{allowedHttpOrigins:[url.origin],deadlineAt}),/网络边界/)
  }
})
