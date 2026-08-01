export type ApiDebugProtocol = 'HTTP' | 'GRAPHQL' | 'SOAP' | 'WEBSOCKET' | 'GRPC'

export interface ApiDebugRouteDraft {
  method?: string
  url?: string
  body?: string
  headers?: string
  environment?: string
  protocol?: ApiDebugProtocol
}

export interface ApiDebugRequestHeader {
  name: string
  value: string
}

export interface ApiDebugRequestAuth {
  type: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY'
  token: string
  username: string
  password: string
  key: string
  value: string
  placement: 'HEADER' | 'QUERY'
}

export interface ApiDebugCurlRequest {
  method: string
  url: string
  headers: ApiDebugRequestHeader[]
  body: string
  auth: ApiDebugRequestAuth
}

export interface ApiDebugCurlCommand {
  command: string
  secretsRemoved: boolean
}

const HTTP_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'])
const PROTOCOLS = new Set<ApiDebugProtocol>(['HTTP', 'GRAPHQL', 'SOAP', 'WEBSOCKET', 'GRPC'])
const STORAGE_PREFIX = 'testhub:api-debug-draft:'
const SENSITIVE_FIELD = /(authorization|cookie|api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd|session|jwt)/i
const VARIABLE_REFERENCE = /^(?:(?:Bearer|Basic)\s+)?\{\{[A-Za-z_][A-Za-z0-9_.-]*\}\}$/i

function firstText(value: unknown): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value
  return typeof candidate === 'string' ? candidate : undefined
}

function nonBlankText(value: unknown): string | undefined {
  const text = firstText(value)?.trim()
  return text ? text : undefined
}

function headerValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

function parsedHeaders(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    return value
      .map((item) => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) return ''
        const record = item as Record<string, unknown>
        const name = headerValue(record.name).trim()
        if (!name) return ''
        return `${name}: ${headerValue(record.value ?? record.example)}`
      })
      .filter(Boolean)
      .join('\n')
  }
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([name, item]) => `${name}: ${headerValue(item)}`)
      .join('\n')
  }
  return typeof value === 'string' ? value : undefined
}

export function normalizeApiDebugHeaders(value: unknown): string | undefined {
  const raw = firstText(value)
  if (raw === undefined || raw === '') return raw
  try {
    return parsedHeaders(JSON.parse(raw)) ?? raw
  } catch {
    return raw
  }
}

export function joinApiDebugUrl(baseUrl: string, path: string): string {
  const normalizedBaseUrl = baseUrl.trim().replace(/\/+$/, '')
  const normalizedPath = path.trim()
  if (!normalizedBaseUrl) return normalizedPath
  if (!normalizedPath) return normalizedBaseUrl
  return `${normalizedBaseUrl}${normalizedPath.startsWith('/') ? '' : '/'}${normalizedPath}`
}

export function apiDebugDraftFromQuery(query: Record<string, unknown>): ApiDebugRouteDraft | null {
  const draft: ApiDebugRouteDraft = {}
  const method = nonBlankText(query.method)?.toUpperCase()
  const protocol = nonBlankText(query.protocol)?.toUpperCase() as ApiDebugProtocol | undefined
  const directUrl = nonBlankText(query.url)
  const baseUrl = nonBlankText(query.baseUrl)
  const path = nonBlankText(query.path)
  const body = firstText(query.body)
  const headers = normalizeApiDebugHeaders(query.headers)
  const environment = firstText(query.environment)

  if (method && HTTP_METHODS.has(method)) draft.method = method
  if (protocol && PROTOCOLS.has(protocol)) draft.protocol = protocol
  if (directUrl) draft.url = directUrl
  else if (baseUrl || path) draft.url = joinApiDebugUrl(baseUrl ?? '', path ?? '')
  if (body !== undefined) draft.body = body
  if (headers !== undefined) draft.headers = headers
  if (environment !== undefined) draft.environment = environment.trim()

  return Object.keys(draft).length ? draft : null
}

export function storeApiDebugRouteDraft(storage: Storage, draft: ApiDebugRouteDraft): string {
  const key = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  storage.setItem(`${STORAGE_PREFIX}${key}`, JSON.stringify(draft))
  return key
}

export function readApiDebugRouteDraft(storage: Storage, key: unknown): ApiDebugRouteDraft | null {
  const normalizedKey = nonBlankText(key)
  if (!normalizedKey) return null
  const raw = storage.getItem(`${STORAGE_PREFIX}${normalizedKey}`)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? apiDebugDraftFromQuery(parsed as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

export function removeApiDebugRouteDraft(storage: Storage, key: unknown) {
  const normalizedKey = nonBlankText(key)
  if (normalizedKey) storage.removeItem(`${STORAGE_PREFIX}${normalizedKey}`)
}

export function consumeApiDebugRouteDraft(storage: Storage, key: unknown): ApiDebugRouteDraft | null {
  const draft = readApiDebugRouteDraft(storage, key)
  removeApiDebugRouteDraft(storage, key)
  return draft
}

function variablePlaceholder(name: string): string {
  const variable = name
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toUpperCase()
  return `{{${variable || 'SECRET'}}}`
}

function persistableSecret(name: string, value: string, onRemoved: () => void): string {
  const normalized = value.trim()
  if (!normalized || VARIABLE_REFERENCE.test(normalized)) return normalized
  onRemoved()
  const scheme = normalized.match(/^(Bearer|Basic)\s+/i)?.[1]
  return `${scheme ? `${scheme[0].toUpperCase()}${scheme.slice(1).toLowerCase()} ` : ''}${variablePlaceholder(name)}`
}

function sanitizeUrl(rawUrl: string, onRemoved: () => void): string {
  const structuralFallback = () => {
    let value = rawUrl.trim()
    const authority = value.match(/^([a-z][a-z0-9+.-]*:\/\/)([^/?#]*)(.*)$/i)
    if (authority?.[2].includes('@')) {
      value = `${authority[1]}${authority[2].slice(authority[2].lastIndexOf('@') + 1)}${authority[3]}`
      onRemoved()
    }
    const queryStart = value.indexOf('?')
    if (queryStart < 0) return value
    const fragmentStart = value.indexOf('#', queryStart)
    const prefix = value.slice(0, queryStart + 1)
    const query = value.slice(queryStart + 1, fragmentStart < 0 ? undefined : fragmentStart)
    const fragment = fragmentStart < 0 ? '' : value.slice(fragmentStart)
    const sanitizedQuery = query.split('&').map((part) => {
      const separator = part.indexOf('=')
      const rawName = separator < 0 ? part : part.slice(0, separator)
      const rawValue = separator < 0 ? '' : part.slice(separator + 1)
      let name = rawName
      let queryValue = rawValue
      try {
        name = decodeURIComponent(rawName.replace(/\+/g, ' '))
        queryValue = decodeURIComponent(rawValue.replace(/\+/g, ' '))
      } catch {
        // Raw names are still checked below so malformed URL components do not bypass redaction.
      }
      if (!SENSITIVE_FIELD.test(name) && !SENSITIVE_FIELD.test(rawName)) return part
      const safeValue = persistableSecret(name || rawName, queryValue, onRemoved)
      return separator < 0 ? rawName : `${rawName}=${encodeURIComponent(safeValue)}`
    }).join('&')
    return `${prefix}${sanitizedQuery}${fragment}`
  }

  try {
    const parsed = new URL(rawUrl.trim())
    if (parsed.username || parsed.password) {
      parsed.username = ''
      parsed.password = ''
      onRemoved()
    }
    parsed.searchParams.forEach((value, name) => {
      if (SENSITIVE_FIELD.test(name)) {
        parsed.searchParams.set(name, persistableSecret(name, value, onRemoved))
      }
    })
    return parsed.toString()
  } catch {
    return structuralFallback()
  }
}

function sanitizeJsonBody(rawBody: string, onRemoved: () => void): string {
  if (!rawBody.trim()) return rawBody
  try {
    const sanitize = (value: unknown, fieldName = ''): unknown => {
      if (fieldName && SENSITIVE_FIELD.test(fieldName)) {
        if (typeof value === 'string') return persistableSecret(fieldName, value, onRemoved)
        onRemoved()
        return variablePlaceholder(fieldName)
      }
      if (Array.isArray(value)) return value.map((item) => sanitize(item))
      if (value && typeof value === 'object') {
        return Object.fromEntries(
          Object.entries(value as Record<string, unknown>).map(([name, item]) => [name, sanitize(item, name)]),
        )
      }
      return value
    }
    const parsed = JSON.parse(rawBody) as unknown
    const sanitized = sanitize(parsed)
    return JSON.stringify(sanitized, null, 2)
  } catch {
    return rawBody
  }
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", "'\\''")}'`
}

export function buildApiDebugCurlCommand(request: ApiDebugCurlRequest): ApiDebugCurlCommand {
  let secretsRemoved = false
  const markRemoved = () => { secretsRemoved = true }
  let requestUrl = request.url.trim()
  const requestHeaders = request.headers.map((header) => ({ ...header }))
  const authType = request.auth.type

  if (authType === 'BEARER') {
    const token = request.auth.token.trim() || variablePlaceholder('BEARER_TOKEN')
    const index = requestHeaders.findIndex((header) => header.name.toLowerCase() === 'authorization')
    const authorization = { name: 'Authorization', value: `Bearer ${token}` }
    if (index >= 0) requestHeaders[index] = authorization
    else requestHeaders.push(authorization)
  } else if (authType === 'BASIC') {
    if ((request.auth.username && !VARIABLE_REFERENCE.test(request.auth.username))
      || (request.auth.password && !VARIABLE_REFERENCE.test(request.auth.password))) markRemoved()
    const index = requestHeaders.findIndex((header) => header.name.toLowerCase() === 'authorization')
    const authorization = { name: 'Authorization', value: `Basic ${variablePlaceholder('BASIC_AUTH')}` }
    if (index >= 0) requestHeaders[index] = authorization
    else requestHeaders.push(authorization)
  } else if (authType === 'API_KEY') {
    const key = request.auth.key.trim() || 'X-API-Key'
    const value = request.auth.value.trim()
      ? persistableSecret(key, request.auth.value, markRemoved)
      : variablePlaceholder('API_KEY')
    if (request.auth.placement === 'QUERY') {
      try {
        const parsed = new URL(requestUrl)
        parsed.searchParams.set(key, value)
        requestUrl = parsed.toString()
      } catch {
        requestUrl += `${requestUrl.includes('?') ? '&' : '?'}${encodeURIComponent(key)}=${encodeURIComponent(value)}`
      }
    } else {
      const index = requestHeaders.findIndex((header) => header.name.toLowerCase() === key.toLowerCase())
      const apiKey = { name: key, value }
      if (index >= 0) requestHeaders[index] = apiKey
      else requestHeaders.push(apiKey)
    }
  }

  const sanitizedUrl = sanitizeUrl(requestUrl, markRemoved)
  const sanitizedHeaders = requestHeaders.map((header) => ({
    name: header.name.trim(),
    value: SENSITIVE_FIELD.test(header.name)
      ? persistableSecret(header.name, header.value, markRemoved)
      : header.value,
  }))
  const sanitizedBody = sanitizeJsonBody(request.body, markRemoved)
  const parts = ['curl', '-X', shellQuote(request.method.trim().toUpperCase()), shellQuote(sanitizedUrl)]
  sanitizedHeaders.forEach((header) => {
    parts.push('-H', shellQuote(`${header.name}: ${header.value}`))
  })
  if (sanitizedBody) parts.push('--data-raw', shellQuote(sanitizedBody))
  return { command: parts.join(' '), secretsRemoved }
}

export function apiDebugDraftFromHistory(value: { method?: unknown; requestUrl?: unknown }): ApiDebugRouteDraft | null {
  const method = nonBlankText(value.method)?.toUpperCase()
  const requestUrl = nonBlankText(value.requestUrl)
  if (!method || !HTTP_METHODS.has(method) || !requestUrl) return null
  return { method, url: requestUrl, protocol: 'HTTP' }
}

export function isRedactedApiDebugUrl(value: string): boolean {
  return value.includes('***')
}
