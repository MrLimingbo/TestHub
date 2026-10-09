import { describe, expect, it } from 'vitest'
import { prepareDesktopHttpRequest } from '@/utils/desktopApiDebug'

describe('desktop API debug request preparation', () => {
  it('resolves an intranet base URL selected from the project environment', () => {
    const request = prepareDesktopHttpRequest({
      name: '内网健康检查',
      method: 'get',
      url: '{{BASE_URL}}/health',
      headers: [{ name: 'X-Environment', value: '{{ENV_NAME}}' }],
      body: '',
      environmentCode: 'test',
      protocol: { type: 'HTTP' },
    }, { BASE_URL: 'http://172.27.15.153:8888', ENV_NAME: 'test' })

    expect(request.url).toBe('http://172.27.15.153:8888/health')
    expect(request.headers).toEqual([{ name: 'X-Environment', value: 'test' }])
    expect(request.environmentCode).toBe('test')
  })

  it('materializes GraphQL and bearer authentication before local execution', () => {
    const request = prepareDesktopHttpRequest({
      method: 'POST',
      url: 'http://10.20.30.40:9000/graphql',
      headers: [],
      protocol: { type: 'GRAPHQL', query: 'query Ping { ping }', variables: '{}', operationName: 'Ping' },
      auth: { type: 'BEARER', token: 'desktop-token' },
    })

    expect(JSON.parse(request.body ?? '')).toEqual({ query: 'query Ping { ping }', variables: {}, operationName: 'Ping' })
    expect(request.headers).toEqual(expect.arrayContaining([
      { name: 'Content-Type', value: 'application/json' },
      { name: 'Authorization', value: 'Bearer desktop-token' },
    ]))
    expect(request.protocol).toEqual({ type: 'GRAPHQL' })
    expect(request.auth).toEqual({ type: 'NONE', placement: 'HEADER' })
  })

  it('does not expose or silently ignore an unavailable project secret', () => {
    expect(() => prepareDesktopHttpRequest({
      method: 'GET',
      url: '{{PRIVATE_SECRET_URL}}/health',
      headers: [],
    })).toThrow(/无法解析变量：PRIVATE_SECRET_URL/)
  })
})
