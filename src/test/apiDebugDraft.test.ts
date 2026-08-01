import { describe, expect, it } from 'vitest'
import {
  apiDebugDraftFromHistory,
  apiDebugDraftFromQuery,
  buildApiDebugCurlCommand,
  consumeApiDebugRouteDraft,
  isRedactedApiDebugUrl,
  joinApiDebugUrl,
  normalizeApiDebugHeaders,
  readApiDebugRouteDraft,
  removeApiDebugRouteDraft,
  storeApiDebugRouteDraft,
} from '@/utils/apiDebugDraft'

describe('API debug route draft', () => {
  it('normalizes a direct HTTP request draft', () => {
    expect(apiDebugDraftFromQuery({
      method: 'post',
      url: 'https://api.example.com/orders',
      body: '{"name":"示例"}',
      headers: 'Content-Type: application/json\nX-Trace: {{$randomUUID}}',
      environment: 'staging',
    })).toEqual({
      method: 'POST',
      url: 'https://api.example.com/orders',
      body: '{"name":"示例"}',
      headers: 'Content-Type: application/json\nX-Trace: {{$randomUUID}}',
      environment: 'staging',
    })
  })

  it('builds a URL from an environment base URL and interface path', () => {
    expect(joinApiDebugUrl('{{API_BASE_URL}}/', '/api/v1/orders/{orderId}'))
      .toBe('{{API_BASE_URL}}/api/v1/orders/{orderId}')
    expect(apiDebugDraftFromQuery({
      method: 'GET',
      baseUrl: '{{API_BASE_URL}}/',
      path: 'api/v1/orders',
    })?.url).toBe('{{API_BASE_URL}}/api/v1/orders')
  })

  it('accepts structured header arrays and maps parameter examples to values', () => {
    const headers = JSON.stringify([
      { name: 'Authorization', example: 'Bearer {{API_TOKEN}}' },
      { name: 'X-Trace', value: 'trace-001' },
      { name: '', value: 'ignored' },
    ])
    expect(normalizeApiDebugHeaders(headers))
      .toBe('Authorization: Bearer {{API_TOKEN}}\nX-Trace: trace-001')
  })

  it('accepts header objects and ignores unsupported method and protocol values', () => {
    expect(apiDebugDraftFromQuery({
      method: 'CONNECT',
      protocol: 'UNKNOWN',
      headers: JSON.stringify({ Accept: 'application/json', 'X-Retry': 2 }),
    })).toEqual({
      headers: 'Accept: application/json\nX-Retry: 2',
    })
  })

  it('uses the first value when Vue Router supplies repeated query parameters', () => {
    expect(apiDebugDraftFromQuery({
      method: ['PATCH', 'DELETE'],
      protocol: ['http', 'soap'],
      body: ['', '{"ignored":true}'],
      environment: ['test', 'prod'],
    })).toEqual({
      method: 'PATCH',
      protocol: 'HTTP',
      body: '',
      environment: 'test',
    })
  })

  it('transfers a sensitive draft through one-time session storage', () => {
    const key = storeApiDebugRouteDraft(window.sessionStorage, {
      method: 'POST',
      url: 'https://api.example.com/orders',
      body: '{"password":"secret"}',
      headers: 'Authorization: Bearer secret',
      protocol: 'HTTP',
    })

    expect(consumeApiDebugRouteDraft(window.sessionStorage, key)).toEqual({
      method: 'POST',
      url: 'https://api.example.com/orders',
      body: '{"password":"secret"}',
      headers: 'Authorization: Bearer secret',
      protocol: 'HTTP',
    })
    expect(consumeApiDebugRouteDraft(window.sessionStorage, key)).toBeNull()
  })

  it('keeps a stored draft until the destination explicitly removes it', () => {
    const key = storeApiDebugRouteDraft(window.sessionStorage, {
      method: 'PATCH',
      url: 'https://api.example.com/orders/1',
      body: '{"status":"paid"}',
    })

    expect(readApiDebugRouteDraft(window.sessionStorage, key)).toEqual({
      method: 'PATCH',
      url: 'https://api.example.com/orders/1',
      body: '{"status":"paid"}',
    })
    expect(readApiDebugRouteDraft(window.sessionStorage, key)).not.toBeNull()

    removeApiDebugRouteDraft(window.sessionStorage, key)
    expect(readApiDebugRouteDraft(window.sessionStorage, key)).toBeNull()
  })

  it('builds a cURL import command without persisting literal credentials', () => {
    const result = buildApiDebugCurlCommand({
      method: 'post',
      url: 'https://user:password@api.example.com/orders?accessToken=plain&locale=zh-CN',
      headers: [
        { name: 'Content-Type', value: 'application/json' },
        { name: 'Authorization', value: 'Bearer plain-token' },
      ],
      body: '{"name":"示例","password":"plain-password"}',
      auth: {
        type: 'BEARER',
        token: 'plain-token',
        username: '',
        password: '',
        key: 'X-API-Key',
        value: '',
        placement: 'HEADER',
      },
    })

    expect(result.secretsRemoved).toBe(true)
    expect(result.command).toContain("curl -X 'POST'")
    expect(result.command).toContain('https://api.example.com/orders?accessToken=%7B%7BACCESS_TOKEN%7D%7D&locale=zh-CN')
    expect(result.command).toContain('Authorization: Bearer {{AUTHORIZATION}}')
    expect(result.command).toContain('"password": "{{PASSWORD}}"')
    expect(result.command).not.toContain('plain-token')
    expect(result.command).not.toContain('plain-password')
    expect(result.command).not.toContain('user:password')
  })

  it('preserves variable references when building a cURL import command', () => {
    const result = buildApiDebugCurlCommand({
      method: 'GET',
      url: 'https://api.example.com/orders',
      headers: [{ name: 'X-API-Key', value: '{{API_KEY}}' }],
      body: '',
      auth: {
        type: 'NONE',
        token: '',
        username: '',
        password: '',
        key: '',
        value: '',
        placement: 'HEADER',
      },
    })

    expect(result.secretsRemoved).toBe(false)
    expect(result.command).toContain('X-API-Key: {{API_KEY}}')
  })

  it('redacts custom API key names and credentials in template URLs', () => {
    const result = buildApiDebugCurlCommand({
      method: 'GET',
      url: 'https://{{USER}}:{{PASSWORD}}@{{HOST}}:{{PORT}}/orders?token=plain-token',
      headers: [],
      body: '',
      auth: {
        type: 'API_KEY',
        token: '',
        username: '',
        password: '',
        key: 'partner-id',
        value: 'partner-secret',
        placement: 'QUERY',
      },
    })

    expect(result.secretsRemoved).toBe(true)
    expect(result.command).toContain('https://{{HOST}}:{{PORT}}/orders?token=%7B%7BTOKEN%7D%7D&partner-id=%7B%7BPARTNER_ID%7D%7D')
    expect(result.command).not.toContain('plain-token')
    expect(result.command).not.toContain('partner-secret')
    expect(result.command).not.toContain('{{USER}}:{{PASSWORD}}@')
  })

  it('maps only HTTP history summaries into replay drafts', () => {
    expect(apiDebugDraftFromHistory({
      method: 'patch',
      requestUrl: 'https://api.example.com/orders/1',
    })).toEqual({
      method: 'PATCH',
      url: 'https://api.example.com/orders/1',
      protocol: 'HTTP',
    })
    expect(apiDebugDraftFromHistory({ method: 'GRPC', requestUrl: 'grpc.example.com:443' })).toBeNull()
    expect(isRedactedApiDebugUrl('https://api.example.com/orders?token=***')).toBe(true)
  })
})
