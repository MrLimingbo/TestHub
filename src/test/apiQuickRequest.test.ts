import { describe, expect, it } from 'vitest'
import {
  applyQuickRequestQuery,
  createQuickRequestRow,
  parseQuickRequestHeaders,
  parseQuickRequestQuery,
  parseMultipartRows,
  serializeMultipartRows,
  serializeQuickRequestCookies,
  serializeQuickRequestHeaders,
  serializeUrlEncodedRows,
  splitQuickRequestCookies,
} from '@/utils/apiQuickRequest'

describe('API quick request editor', () => {
  it('restores multipart text fields, including repeated names and multiline values', () => {
    const rows = [
      createQuickRequestRow({ name: 'marker', value: 'QA-R2-表单回放\r\n第二行\r\n' }),
      createQuickRequestRow({ name: 'marker', value: 'second' }),
      createQuickRequestRow({ name: 'quote"name', value: '' }),
    ]
    const body = serializeMultipartRows(rows, 'CaseSensitiveBoundary')
    const parsed = parseMultipartRows(body, 'multipart/form-data; boundary="CaseSensitiveBoundary"')
    expect(parsed?.map(({ name, value }) => ({ name, value })))
      .toEqual(rows.map(({ name, value }) => ({ name, value })))
    expect(serializeMultipartRows(parsed!, 'CaseSensitiveBoundary')).toBe(body)
  })

  it('keeps unsupported or incomplete multipart content available for raw replay', () => {
    expect(parseMultipartRows('--x\r\nContent-Disposition: form-data; name="file"; filename="a.txt"\r\n\r\na\r\n--x--\r\n', 'multipart/form-data; boundary=x')).toBeNull()
    expect(parseMultipartRows('--x\r\nContent-Disposition: form-data; name="a"\r\n\r\nb', 'multipart/form-data; boundary=x')).toBeNull()
    expect(parseMultipartRows('unchanged', 'multipart/form-data')).toBeNull()
  })

  it('preserves runtime variables while encoding surrounding query text', () => {
    expect(applyQuickRequestQuery('https://api.example.com/get', [
      createQuickRequestRow({ name: 'trace', value: '上海 {{$timestamp}}' }),
    ])).toBe('https://api.example.com/get?trace=%E4%B8%8A%E6%B5%B7%20{{$timestamp}}')
    expect(serializeUrlEncodedRows([
      createQuickRequestRow({ name: 'trace', value: '上海 {{$timestamp}}' }),
    ])).toBe('trace=%E4%B8%8A%E6%B5%B7%20{{$timestamp}}')
  })

  it('does not interpret a question mark inside a fragment as a query', () => {
    const url = 'https://api.example.com/get#section?example=1'
    expect(parseQuickRequestQuery(url)).toEqual([])
    expect(applyQuickRequestQuery(url, parseQuickRequestQuery(url))).toBe(url)
  })

  it('round-trips repeated query parameters without changing URL fragments', () => {
    const rows = parseQuickRequestQuery('https://api.example.com/orders?status=paid&status=pending#result')
    expect(rows.map((row) => [row.name, row.value])).toEqual([
      ['status', 'paid'],
      ['status', 'pending'],
    ])
    rows[0].enabled = false
    expect(applyQuickRequestQuery('https://api.example.com/orders?old=value#result', rows))
      .toBe('https://api.example.com/orders?status=pending#result')
  })

  it('supports template URLs and safely encodes edited parameter values', () => {
    expect(applyQuickRequestQuery('{{BASE_URL}}/orders', [
      createQuickRequestRow({ name: 'keyword', value: '上海 门店' }),
    ])).toBe('{{BASE_URL}}/orders?keyword=%E4%B8%8A%E6%B5%B7%20%E9%97%A8%E5%BA%97')
  })

  it('separates Cookie from regular headers and serializes enabled rows only', () => {
    const parsed = parseQuickRequestHeaders('Accept: application/json\nCookie: session=abc; locale=zh-CN\nX-Trace: trace-1')
    const { headerRows, cookieRows } = splitQuickRequestCookies(parsed)
    headerRows[1].enabled = false

    expect(serializeQuickRequestHeaders(headerRows)).toEqual([{ name: 'Accept', value: 'application/json' }])
    expect(serializeQuickRequestCookies(cookieRows)).toBe('session=abc; locale=zh-CN')
  })

  it('serializes urlencoded and text-only multipart request bodies', () => {
    const rows = [
      createQuickRequestRow({ name: 'name', value: '测试订单' }),
      createQuickRequestRow({ name: 'ignored', value: 'value', enabled: false }),
    ]
    expect(serializeUrlEncodedRows(rows)).toBe('name=%E6%B5%8B%E8%AF%95%E8%AE%A2%E5%8D%95')
    expect(serializeMultipartRows(rows, 'test-boundary')).toContain('Content-Disposition: form-data; name="name"')
    expect(serializeMultipartRows(rows, 'test-boundary')).toContain('测试订单')
    expect(serializeMultipartRows(rows, 'test-boundary')).not.toContain('ignored')
  })
})
