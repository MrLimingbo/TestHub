import { describe, expect, it } from 'vitest'
import {
  DEFAULT_REPRODUCTION_TEMPLATE,
  richTextForEditor,
  richTextPlainText,
  sanitizeRichText,
} from '@/utils/richText'

describe('rich text helpers', () => {
  it('keeps supported formatting and removes unsafe markup', () => {
    const sanitized = sanitizeRichText(
      '<p onclick="alert(1)"><strong>步骤</strong><script>alert(1)</script></p>'
      + '<a href="javascript:alert(1)">危险链接</a><img src="https://example.com/a.png" onerror="alert(1)">',
    )

    expect(sanitized).toContain('<strong>步骤</strong>')
    expect(sanitized).toContain('<img src="https://example.com/a.png" alt="">')
    expect(sanitized).not.toContain('<script')
    expect(sanitized).not.toContain('onclick')
    expect(sanitized).not.toContain('onerror')
    expect(sanitized).not.toContain('javascript:')
  })

  it('converts legacy plain text into editable paragraphs', () => {
    expect(richTextForEditor('第一步\n\n期望结果')).toBe(
      '<p>第一步</p><p><br></p><p>期望结果</p>',
    )
  })

  it('detects visually empty content and keeps the default reproduction labels', () => {
    expect(richTextPlainText('<p><br></p>')).toBe('')
    expect(richTextPlainText(DEFAULT_REPRODUCTION_TEMPLATE)).toBe('[步骤][结果][期望]')
  })
})
