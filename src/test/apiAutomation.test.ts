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
