import { encodeApiUrlComponent } from './apiUrlTemplate'

export interface QuickRequestKeyValueRow {
  id: string
  enabled: boolean
  name: string
  value: string
  description: string
}

export interface QuickRequestHeader {
  name: string
  value: string
}

function rowId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export function createQuickRequestRow(value: Partial<Omit<QuickRequestKeyValueRow, 'id'>> = {}): QuickRequestKeyValueRow {
  return {
    id: rowId(),
    enabled: value.enabled ?? true,
    name: value.name ?? '',
    value: value.value ?? '',
    description: value.description ?? '',
  }
}

function decodeQueryPart(value: string) {
  try {
    return decodeURIComponent(value.replaceAll('+', ' '))
  } catch {
    return value
  }
}

export function parseQuickRequestQuery(rawUrl: string): QuickRequestKeyValueRow[] {
  const queryStart = rawUrl.indexOf('?')
  const hashStart = rawUrl.indexOf('#')
  if (queryStart < 0 || (hashStart >= 0 && hashStart < queryStart)) return []
  const query = rawUrl.slice(queryStart + 1, hashStart < 0 ? undefined : hashStart)
  if (!query) return []
  return query.split('&').filter(Boolean).map((item) => {
    const separator = item.indexOf('=')
    return createQuickRequestRow({
      name: decodeQueryPart(separator < 0 ? item : item.slice(0, separator)),
      value: decodeQueryPart(separator < 0 ? '' : item.slice(separator + 1)),
    })
  })
}

export function applyQuickRequestQuery(rawUrl: string, rows: QuickRequestKeyValueRow[]): string {
  const hashStart = rawUrl.indexOf('#')
  const hash = hashStart < 0 ? '' : rawUrl.slice(hashStart)
  const withoutHash = hashStart < 0 ? rawUrl : rawUrl.slice(0, hashStart)
  const queryStart = withoutHash.indexOf('?')
  const base = queryStart < 0 ? withoutHash : withoutHash.slice(0, queryStart)
  const query = rows
    .filter((row) => row.enabled && row.name.trim())
    .map((row) => `${encodeApiUrlComponent(row.name.trim())}=${encodeApiUrlComponent(row.value)}`)
    .join('&')
  return `${base}${query ? `?${query}` : ''}${hash}`
}

export function parseQuickRequestHeaders(text: string): QuickRequestKeyValueRow[] {
  return text.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const separator = line.indexOf(':')
    return createQuickRequestRow({
      name: separator < 0 ? line : line.slice(0, separator).trim(),
      value: separator < 0 ? '' : line.slice(separator + 1).trim(),
    })
  })
}

export function splitQuickRequestCookies(rows: QuickRequestKeyValueRow[]) {
  const headerRows: QuickRequestKeyValueRow[] = []
  const cookieRows: QuickRequestKeyValueRow[] = []
  rows.forEach((row) => {
    if (row.name.trim().toLowerCase() !== 'cookie') {
      headerRows.push(row)
      return
    }
    row.value.split(';').map((item) => item.trim()).filter(Boolean).forEach((item) => {
      const separator = item.indexOf('=')
      cookieRows.push(createQuickRequestRow({
        enabled: row.enabled,
        name: separator < 0 ? item : item.slice(0, separator).trim(),
        value: separator < 0 ? '' : item.slice(separator + 1).trim(),
      }))
    })
  })
  return { headerRows, cookieRows }
}

export function serializeQuickRequestHeaders(rows: QuickRequestKeyValueRow[]): QuickRequestHeader[] {
  return rows
    .filter((row) => row.enabled && row.name.trim())
    .map((row) => ({ name: row.name.trim(), value: row.value }))
}

export function serializeQuickRequestCookies(rows: QuickRequestKeyValueRow[]): string {
  return rows
    .filter((row) => row.enabled && row.name.trim())
    .map((row) => `${row.name.trim()}=${row.value}`)
    .join('; ')
}

export function serializeUrlEncodedRows(rows: QuickRequestKeyValueRow[]): string {
  return rows
    .filter((row) => row.enabled && row.name.trim())
    .map((row) => `${encodeApiUrlComponent(row.name.trim())}=${encodeApiUrlComponent(row.value)}`)
    .join('&')
}

export function serializeMultipartRows(rows: QuickRequestKeyValueRow[], boundary: string): string {
  const fields = rows.filter((row) => row.enabled && row.name.trim())
  if (!fields.length) return ''
  return `${fields.map((row) => [
    `--${boundary}`,
    `Content-Disposition: form-data; name="${row.name.trim().replaceAll('"', '\\"')}"`,
    '',
    row.value,
  ].join('\r\n')).join('\r\n')}\r\n--${boundary}--\r\n`
}

export function parseMultipartRows(body: string, contentType: string): QuickRequestKeyValueRow[] | null {
  const boundary = contentType.match(/(?:^|;)\s*boundary=(?:"([^"]+)"|([^;\s]+))/i)
  if (!boundary) return null
  const delimiter = `--${boundary[1] ?? boundary[2]}`
  if (!body.startsWith(`${delimiter}\r\n`)) return null
  const parts = body.slice(delimiter.length + 2).split(`\r\n${delimiter}`)
  const closing = parts.pop()
  if (closing !== '--\r\n' && closing !== '--') return null
  const rows: QuickRequestKeyValueRow[] = []
  for (const [index, part] of parts.entries()) {
    if (index > 0 && !part.startsWith('\r\n')) return null
    const content = index > 0 ? part.slice(2) : part
    const separator = content.indexOf('\r\n\r\n')
    if (separator < 0) return null
    // Only hydrate text fields supported by this editor. Other multipart bodies
    // remain raw so replay never silently discards files or per-part headers.
    const header = content.slice(0, separator)
    const name = header.match(/^Content-Disposition: form-data; name="((?:\\.|[^"\\])*)"$/i)
    if (!name) return null
    rows.push(createQuickRequestRow({
      name: name[1].replace(/\\(["\\])/g, '$1'),
      value: content.slice(separator + 4),
    }))
  }
  return rows
}
