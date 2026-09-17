import { describe, expect, it } from 'vitest'
import { parseCurlCommand } from '@/utils/apiAutomation'

describe('API automation imports', () => {
  it('parses a multiline cURL command into a request step', () => {
    expect(parseCurlCommand(`curl --request POST 'https://api.example.com/orders' \\
      --header 'Content-Type: application/json' \\
      --data-raw '{"name":"demo"}'`)).toEqual({
      method: 'POST',
      url: 'https://api.example.com/orders',
      body: '{\n  "name": "demo"\n}',
      contentType: 'application/json',
      externalizedSecrets: [],
    })
  })

  it('externalizes common credential fields instead of persisting literals', () => {
    const parsed = parseCurlCommand(`curl 'https://user:pass@api.example.com/orders?access_token=plain&token=also-plain' \\
      -H 'Authorization: Bearer plain' \\
      -d '{"password":"plain","name":"demo"}'`)

    expect(parsed.method).toBe('POST')
    expect(parsed.url).not.toContain('user:pass')
    expect(parsed.url).not.toContain('access_token=plain')
    expect(parsed.url).not.toContain('token=also-plain')
    expect(parsed.url).toContain('access_token={{ACCESS_TOKEN}}')
    expect(parsed.url).toContain('token={{TOKEN}}')
    expect(parsed.body).toContain('{{PASSWORD}}')
    expect(parsed.externalizedSecrets).toEqual(expect.arrayContaining(['URL_CREDENTIAL', 'access_token', 'token', 'Authorization', 'password']))
  })

  it('rejects non-cURL input and unsupported methods', () => {
    expect(() => parseCurlCommand('wget https://api.example.com')).toThrow('curl')
    expect(() => parseCurlCommand('curl -X TRACE https://api.example.com')).toThrow('TRACE')
  })
})

describe('cURL request fidelity', () => {
  it('preserves HEAD, GET query data and repeated form fields', () => {
    expect(parseCurlCommand('curl -I https://example.com').method).toBe('HEAD')
    expect(parseCurlCommand("curl -G https://example.com -d a=1 --data-urlencode 'q=a b'")).toMatchObject({ method: 'GET', url: 'https://example.com?a=1&q=a%20b', body: '' })
    expect(parseCurlCommand('curl https://example.com -da=1 -d b=2')).toMatchObject({ method: 'POST', body: 'a=1&b=2' })
    expect(parseCurlCommand('curl -XPOST https://example.com').method).toBe('POST')
  })
  it('redacts literal query secrets in templated base URLs', () => {
    const result = parseCurlCommand("curl '{{BASE_URL}}/orders?token=literal&access_token={{ACCESS_TOKEN}}'")
    expect(result.url).toBe('{{BASE_URL}}/orders?token={{TOKEN}}&access_token={{ACCESS_TOKEN}}')
    expect(result.externalizedSecrets).toEqual(['token'])
  })
  it('rejects file-backed data instead of sending a filename as the body', () => {
    expect(() => parseCurlCommand('curl https://example.com --data @request.json')).toThrow('文件数据')
  })
})
