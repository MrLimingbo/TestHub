import { reactive } from 'vue'
import { describe, expect, it } from 'vitest'
import {
  apiCaseName,
  apiCasePersistence,
  apiCaseType,
  buildApiRunUrl,
  cloneApiRunSnapshot,
  createApiRunSnapshot,
  createPersistableApiRunSnapshot,
  formatApiParameterLines,
  mergeApiRunSnapshot,
  parseApiParameterLines,
  parseApiParameters,
  validateApiContract,
} from '@/utils/apiTesting'

const version = {
  requestHeaders: '[{"name":"X-Trace","type":"string","required":false,"example":"trace-1","description":"链路"}]',
  requestQuery: '[{"name":"verbose","type":"boolean","required":false,"example":"true","description":"详情"}]',
  requestPath: '[{"name":"orderId","type":"string","required":true,"example":"OD 1","description":"订单"}]',
  requestBody: '{"name":"示例"}',
  responseBody: '{"code":0,"data":{"id":"OD-1"}}',
  exampleResponse: '{"statusCode":200}',
}

describe('API testing request mapping', () => {
  it('preserves runtime variables in path and query values', () => {
    const snapshot = createApiRunSnapshot(version, '{{BASE_URL}}')
    snapshot.pathValues[0].value = '{{ORDER_ID}}'
    snapshot.queryValues[0].value = 'prefix {{$timestamp}}'
    expect(buildApiRunUrl({ method: 'GET', path: '/orders/{orderId}' }, snapshot))
      .toBe('{{BASE_URL}}/orders/{{ORDER_ID}}?verbose=prefix%20{{$timestamp}}')
  })

  it('maps an API definition into an isolated request snapshot', () => {
    const snapshot = createApiRunSnapshot(version)
    expect(snapshot.pathValues[0].value).toBe('OD 1')
    expect(snapshot.headerValues[0].enabled).toBe(true)
    expect(snapshot.body).toBe('{"name":"示例"}')
  })

  it('serializes a Vue reactive snapshot before persistence', () => {
    const source = reactive(createApiRunSnapshot(version))
    source.baseUrl = 'https://postman-echo.com'

    const persisted = cloneApiRunSnapshot(source)

    expect(persisted.baseUrl).toBe('https://postman-echo.com')
    expect(persisted).not.toBe(source)
  })

  it('removes literal credentials while preserving environment references', () => {
    const source = createApiRunSnapshot(version, 'https://user:password@api.example.com?accessToken=plain&locale=zh-CN')
    source.auth.type = 'BEARER'
    source.auth.token = 'plain-token'
    source.auth.password = '{{API_PASSWORD}}'
    source.headerValues.push({
      name: 'X-API-Key',
      type: 'string',
      required: false,
      example: '',
      description: '',
      enabled: true,
      value: 'plain-key',
    })
    source.queryValues.push({
      name: 'refreshToken',
      type: 'string',
      required: false,
      example: '',
      description: '',
      enabled: true,
      value: 'query-secret',
    })

    const persisted = createPersistableApiRunSnapshot(source)

    expect(persisted.secretsRemoved).toBe(true)
    expect(persisted.snapshot.baseUrl).toBe('https://api.example.com?accessToken=&locale=zh-CN')
    expect(persisted.snapshot.auth.token).toBe('')
    expect(persisted.snapshot.auth.password).toBe('{{API_PASSWORD}}')
    expect(persisted.snapshot.headerValues.at(-1)?.value).toBe('')
    expect(persisted.snapshot.queryValues.at(-1)?.value).toBe('')
  })

  it('removes embedded credentials even when a base URL contains variables', () => {
    const source = createApiRunSnapshot(version, 'https://{{USER}}:{{PASSWORD}}@api.example.com?jwt=literal&token={{API_TOKEN}}')

    const persisted = createPersistableApiRunSnapshot(source)

    expect(persisted.secretsRemoved).toBe(true)
    expect(persisted.snapshot.baseUrl).toBe('https://api.example.com?jwt=&token={{API_TOKEN}}')
  })

  it('merges saved values with parameters added by the current interface version', () => {
    const saved = createApiRunSnapshot(version)
    saved.queryValues[0].value = 'false'
    const currentVersion = {
      ...version,
      requestQuery: JSON.stringify([
        ...JSON.parse(version.requestQuery),
        { name: 'locale', type: 'string', required: true, example: 'zh-CN', description: '语言' },
      ]),
    }

    const merged = mergeApiRunSnapshot(currentVersion, saved)

    expect(merged.queryValues).toEqual([
      expect.objectContaining({ name: 'verbose', value: 'false' }),
      expect.objectContaining({ name: 'locale', value: 'zh-CN', required: true }),
    ])
  })

  it('round-trips parameter examples and descriptions containing colons', () => {
    const source = JSON.stringify([{
      name: 'callback',
      type: 'string',
      required: false,
      example: 'https://api.example.com/callback',
      description: '协议说明: HTTPS',
    }])
    expect(parseApiParameterLines(formatApiParameterLines(source))).toEqual(JSON.parse(source))
  })

  it('repairs malformed legacy snapshots before applying them', () => {
    expect(mergeApiRunSnapshot(version, {
      baseUrl: 123,
      pathValues: null,
      queryValues: null,
      headerValues: null,
      auth: null,
    })).toEqual(expect.objectContaining({
      baseUrl: 'https://api.example.com',
      pathValues: expect.any(Array),
      queryValues: expect.any(Array),
      headerValues: expect.any(Array),
    }))
  })

  it('builds an encoded URL without changing the API definition', () => {
    const snapshot = createApiRunSnapshot(version, 'https://api.example.com/')
    expect(buildApiRunUrl({ method: 'GET', path: '/orders/{orderId}' }, snapshot))
      .toBe('https://api.example.com/orders/OD%201?verbose=true')
  })

  it('rejects a missing required path value', () => {
    const snapshot = createApiRunSnapshot(version)
    snapshot.pathValues[0].value = ''
    expect(() => buildApiRunUrl({ method: 'GET', path: '/orders/{orderId}' }, snapshot))
      .toThrow('请填写路径参数：orderId')
  })

  it('validates status and basic JSON response shape', () => {
    const checks = validateApiContract(version, 200, '{"code":0,"data":{"id":"OD-2","extra":true}}')
    expect(checks.every((check) => check.passed)).toBe(true)
    expect(validateApiContract(version, 404, '{"code":"0","data":{}}').some((check) => !check.passed)).toBe(true)
  })

  it('uses the saved case expectation when rerunning a debug case', () => {
    expect(validateApiContract(version, 202, 'not-json', {
      expectedStatus: 202,
      validateBody: false,
    })).toEqual([{
      key: 'status',
      label: '响应状态码',
      passed: true,
      detail: '符合用例期望 202',
    }])
  })

  it('maps Apifox-style debug and test cases to the persisted case contract', () => {
    expect(apiCaseName('查询订单', '记录不存在')).toBe('查询订单（记录不存在）')
    expect(apiCaseType('')).toBe('DEBUG')
    expect(apiCaseType(null)).toBe('DEBUG')
    expect(apiCaseType('CONTRACT')).toBe('TEST')
    expect(apiCasePersistence('DEBUG', 200, false, '{"code":0}')).toEqual({
      expectedStatus: null,
      responseValidation: '',
      savedResponseBody: '',
    })
    expect(apiCasePersistence('TEST', 404, true, '{"code":404}')).toEqual({
      expectedStatus: 404,
      responseValidation: 'CONTRACT',
      savedResponseBody: '{"code":404}',
    })
  })

  it('ignores malformed parameter payloads safely', () => {
    expect(parseApiParameters('not-json')).toEqual([])
  })
})
