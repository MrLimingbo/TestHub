export const API_INTERCHANGE_MAX_BYTES = 10 * 1024 * 1024
export const API_INTERCHANGE_MAX_LABEL = '10 MiB'

const utf8Encoder = new TextEncoder()

export function apiInterchangeUtf8Bytes(document: string): number {
  return utf8Encoder.encode(document).byteLength
}

export function apiInterchangeSizeError(byteLength: number): string | null {
  return byteLength > API_INTERCHANGE_MAX_BYTES
    ? `接口定义文档不能超过 ${API_INTERCHANGE_MAX_LABEL}`
    : null
}

export function apiInterchangeDocumentSizeError(document: string): string | null {
  return apiInterchangeSizeError(apiInterchangeUtf8Bytes(document))
}
