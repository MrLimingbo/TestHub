import { describe, expect, it } from 'vitest'
import {
  API_INTERCHANGE_MAX_BYTES,
  apiInterchangeDocumentSizeError,
  apiInterchangeSizeError,
  apiInterchangeUtf8Bytes,
} from '@/utils/apiInterchange'

describe('API interchange document limits', () => {
  it('counts ASCII content as one UTF-8 byte per character', () => {
    expect(apiInterchangeUtf8Bytes('Postman')).toBe(7)
    expect(apiInterchangeDocumentSizeError('Postman')).toBeNull()
  })

  it('counts Chinese content by its UTF-8 representation', () => {
    expect(apiInterchangeUtf8Bytes('接口')).toBe(6)
    expect(apiInterchangeDocumentSizeError('接口')).toBeNull()
  })

  it('accepts the 10 MiB boundary and rejects one byte beyond it', () => {
    expect(apiInterchangeSizeError(API_INTERCHANGE_MAX_BYTES)).toBeNull()
    expect(apiInterchangeSizeError(API_INTERCHANGE_MAX_BYTES + 1)).toBe('接口定义文档不能超过 10 MiB')
  })
})
