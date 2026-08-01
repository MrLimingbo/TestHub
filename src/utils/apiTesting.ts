export interface ApiParameterDefinition {
  name: string
  type: string
  required: boolean
  example: string
  description: string
}

export interface ApiRequestValue extends ApiParameterDefinition {
  enabled: boolean
  value: string
}

export interface ApiRunAuth {
  type: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY'
  token: string
  username: string
  password: string
  key: string
  value: string
  placement: 'HEADER' | 'QUERY'
}

export interface ApiRunSnapshot {
  baseUrl: string
  pathValues: ApiRequestValue[]
  queryValues: ApiRequestValue[]
  headerValues: ApiRequestValue[]
  body: string
  auth: ApiRunAuth
}

export interface ApiVersionSource {
  requestHeaders: string
  requestQuery: string
  requestPath: string
  requestBody: string
  responseBody: string
  exampleResponse: string
}

export interface ApiDefinitionSource {
  method: string
  path: string
}

export interface ContractCheck {
  key: string
  label: string
  passed: boolean
  detail: string
}

export interface ContractValidationOptions {
  expectedStatus?: number | null
  validateBody?: boolean
}

export interface PersistableSnapshot {
  snapshot: ApiRunSnapshot
  secretsRemoved: boolean
}

const SENSITIVE_FIELD = /(authorization|cookie|api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|password|passwd|session|jwt)/i
const VARIABLE_REFERENCE = /^(?:(?:Bearer|Basic)\s+)?\{\{[A-Za-z_][A-Za-z0-9_.-]*\}\}$/i

export function parseApiParameters(value: string): ApiParameterDefinition[] {
  try {
    const parsed = JSON.parse(value)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object')
      .map((item) => ({
        name: String(item.name ?? ''),
        type: String(item.type ?? 'string'),
        required: Boolean(item.required),
        example: String(item.example ?? ''),
        description: String(item.description ?? ''),
      }))
      .filter((item) => item.name)
  } catch {
    return []
  }
}

export function formatApiParameterLines(value: string): string {
  return parseApiParameters(value)
    .map((item) => JSON.stringify([item.name, item.type, item.required, item.example, item.description]))
    .join('\n')
}

export function parseApiParameterLines(value: string): ApiParameterDefinition[] {
  return value.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    try {
      const parsed = JSON.parse(line)
      if (Array.isArray(parsed) && parsed.length >= 2) {
        return {
          name: String(parsed[0] ?? '').trim(),
          type: String(parsed[1] ?? 'string').trim() || 'string',
          required: Boolean(parsed[2]),
          example: String(parsed[3] ?? ''),
          description: String(parsed[4] ?? ''),
        }
      }
    } catch {
      // Accept the legacy name:type:required:description format for compatibility.
    }
    const [name, type = 'string', requiredText = '', ...description] = line.split(':')
    const required = ['必填', 'true', '是'].includes(requiredText.trim().toLowerCase())
    return {
      name: name.trim(),
      type: type.trim() || 'string',
      required: required || !['可选', 'false', '否'].includes(requiredText.trim().toLowerCase()),
      example: '',
      description: description.join(':').trim(),
    }
  }).filter((item) => item.name)
}

function requestValues(value: string): ApiRequestValue[] {
  return parseApiParameters(value).map((item) => ({
    ...item,
    enabled: item.required || Boolean(item.example),
    value: item.example,
  }))
}

export function createApiRunSnapshot(version: ApiVersionSource, baseUrl = 'https://api.example.com'): ApiRunSnapshot {
  return {
    baseUrl,
    pathValues: requestValues(version.requestPath),
    queryValues: requestValues(version.requestQuery),
    headerValues: requestValues(version.requestHeaders),
    body: version.requestBody || '',
    auth: {
      type: 'NONE',
      token: '',
      username: '',
      password: '',
      key: 'X-API-Key',
      value: '',
      placement: 'HEADER',
    },
  }
}

export function cloneApiRunSnapshot(value: ApiRunSnapshot): ApiRunSnapshot {
  return JSON.parse(JSON.stringify(value)) as ApiRunSnapshot
}

export function createPersistableApiRunSnapshot(value: ApiRunSnapshot): PersistableSnapshot {
  const snapshot = cloneApiRunSnapshot(value)
  let secretsRemoved = false
  const persistableSecret = (secret: string) => {
    const normalized = secret.trim()
    if (!normalized || VARIABLE_REFERENCE.test(normalized)) return normalized
    secretsRemoved = true
    return ''
  }

  snapshot.auth.token = persistableSecret(snapshot.auth.token)
  snapshot.auth.password = persistableSecret(snapshot.auth.password)
  snapshot.auth.value = persistableSecret(snapshot.auth.value)
  snapshot.headerValues.forEach((header) => {
    if (SENSITIVE_FIELD.test(header.name)) header.value = persistableSecret(header.value)
  })
  snapshot.queryValues.forEach((query) => {
    if (SENSITIVE_FIELD.test(query.name)) query.value = persistableSecret(query.value)
  })

  const authority = snapshot.baseUrl.match(/^([a-z][a-z0-9+.-]*:\/\/)([^/?#]*)(.*)$/i)
  if (authority?.[2].includes('@')) {
    snapshot.baseUrl = `${authority[1]}${authority[2].slice(authority[2].lastIndexOf('@') + 1)}${authority[3]}`
    secretsRemoved = true
  }
  const queryStart = snapshot.baseUrl.indexOf('?')
  if (queryStart >= 0) {
    const fragmentStart = snapshot.baseUrl.indexOf('#', queryStart)
    const prefix = snapshot.baseUrl.slice(0, queryStart + 1)
    const query = snapshot.baseUrl.slice(queryStart + 1, fragmentStart < 0 ? undefined : fragmentStart)
    const fragment = fragmentStart < 0 ? '' : snapshot.baseUrl.slice(fragmentStart)
    const sanitizedQuery = query.split('&').map((part) => {
      const separator = part.indexOf('=')
      const rawName = separator < 0 ? part : part.slice(0, separator)
      const rawValue = separator < 0 ? '' : part.slice(separator + 1)
      let name = rawName
      let value = rawValue
      try {
        name = decodeURIComponent(rawName.replace(/\+/g, ' '))
        value = decodeURIComponent(rawValue.replace(/\+/g, ' '))
      } catch {
        // Keep malformed URL components unchanged unless the raw name itself is recognizably sensitive.
      }
      if (!SENSITIVE_FIELD.test(name) && !SENSITIVE_FIELD.test(rawName)) return part
      const safeValue = persistableSecret(value)
      return separator < 0 ? rawName : `${rawName}=${safeValue ? rawValue : ''}`
    }).join('&')
    snapshot.baseUrl = `${prefix}${sanitizedQuery}${fragment}`
  }

  try {
    const url = new URL(snapshot.baseUrl)
    if (url.username || url.password) {
      url.username = ''
      url.password = ''
      snapshot.baseUrl = url.toString().replace(/\/$/, '')
      secretsRemoved = true
    }
  } catch {
    // Template-based base URLs are resolved at execution time after the structural checks above.
  }

  return { snapshot, secretsRemoved }
}

export function mergeApiRunSnapshot(version: ApiVersionSource, saved: unknown): ApiRunSnapshot {
  const candidate = saved && typeof saved === 'object' ? saved as Partial<ApiRunSnapshot> : {}
  const baseUrl = typeof candidate.baseUrl === 'string' ? candidate.baseUrl : 'https://api.example.com'
  const current = createApiRunSnapshot(version, baseUrl)
  const mergeValues = (latest: ApiRequestValue[], previous: ApiRequestValue[]) => {
    const previousByName = new Map(previous.map((item) => [item.name, item]))
    return latest.map((item) => {
      const savedValue = previousByName.get(item.name)
      return savedValue ? { ...item, enabled: item.required || savedValue.enabled, value: savedValue.value } : item
    })
  }
  current.pathValues = mergeValues(current.pathValues, Array.isArray(candidate.pathValues) ? candidate.pathValues : [])
  current.queryValues = mergeValues(current.queryValues, Array.isArray(candidate.queryValues) ? candidate.queryValues : [])
  current.headerValues = mergeValues(current.headerValues, Array.isArray(candidate.headerValues) ? candidate.headerValues : [])
  current.body = typeof candidate.body === 'string' ? candidate.body : current.body
  current.auth = candidate.auth && typeof candidate.auth === 'object'
    ? { ...current.auth, ...candidate.auth }
    : current.auth
  return current
}

export function buildApiRunUrl(definition: ApiDefinitionSource, snapshot: ApiRunSnapshot): string {
  const missingPath = snapshot.pathValues.find((item) => item.required && !item.value.trim())
  if (missingPath) throw new Error(`请填写路径参数：${missingPath.name}`)
  let path = definition.path
  snapshot.pathValues.forEach((item) => {
    path = path.replaceAll(`{${item.name}}`, encodeURIComponent(item.value))
  })
  const missingTemplate = path.match(/\{([^}]+)\}/)
  if (missingTemplate) throw new Error(`请填写路径参数：${missingTemplate[1]}`)
  const query = snapshot.queryValues
    .filter((item) => item.enabled && item.value !== '')
    .map((item) => `${encodeURIComponent(item.name)}=${encodeURIComponent(item.value)}`)
    .join('&')
  return `${snapshot.baseUrl.trim().replace(/\/+$/, '')}${path}${query ? `?${query}` : ''}`
}

export function requestHeaders(snapshot: ApiRunSnapshot): Array<{ name: string; value: string }> {
  return snapshot.headerValues
    .filter((item) => item.enabled && item.value !== '')
    .map((item) => ({ name: item.name, value: item.value }))
}

function expectedStatus(version: ApiVersionSource): number | null {
  try {
    const value = Number((JSON.parse(version.exampleResponse || '{}') as { statusCode?: unknown }).statusCode)
    return Number.isInteger(value) && value >= 100 && value <= 599 ? value : null
  } catch {
    return null
  }
}

function jsonKind(value: unknown): string {
  if (Array.isArray(value)) return 'array'
  if (value === null) return 'null'
  return typeof value
}

function compareJsonShape(expected: unknown, actual: unknown, path = '$'): string[] {
  if (jsonKind(expected) !== jsonKind(actual)) {
    return [`${path} 期望 ${jsonKind(expected)}，实际 ${jsonKind(actual)}`]
  }
  if (Array.isArray(expected) && Array.isArray(actual)) {
    return expected.length && actual.length ? compareJsonShape(expected[0], actual[0], `${path}[0]`) : []
  }
  if (expected && actual && typeof expected === 'object' && typeof actual === 'object') {
    return Object.entries(expected as Record<string, unknown>).flatMap(([key, value]) => {
      if (!(key in (actual as Record<string, unknown>))) return [`${path}.${key} 缺失`]
      return compareJsonShape(value, (actual as Record<string, unknown>)[key], `${path}.${key}`)
    })
  }
  return []
}

export function validateApiContract(
  version: ApiVersionSource,
  statusCode: number | null,
  responseBody: string,
  options: ContractValidationOptions = {},
): ContractCheck[] {
  const usesCaseExpectation = options.expectedStatus !== undefined
  const status = options.expectedStatus === undefined ? expectedStatus(version) : options.expectedStatus
  const checks: ContractCheck[] = []
  if (status !== null) {
    checks.push({
      key: 'status',
      label: '响应状态码',
      passed: statusCode === status,
      detail: statusCode === status
        ? `符合${usesCaseExpectation ? '用例期望' : '文档定义'} ${status}`
        : `${usesCaseExpectation ? '用例期望' : '文档定义'} ${status}，实际 ${statusCode ?? '无响应'}`,
    })
  }
  if (options.validateBody !== false && version.responseBody.trim()) {
    try {
      const expected = JSON.parse(version.responseBody)
      const actual = JSON.parse(responseBody)
      const differences = compareJsonShape(expected, actual)
      checks.push({
        key: 'shape',
        label: '响应 JSON 结构',
        passed: differences.length === 0,
        detail: differences.length ? differences.slice(0, 5).join('；') : '字段与基础类型符合文档示例',
      })
    } catch {
      checks.push({
        key: 'format',
        label: '响应格式',
        passed: false,
        detail: '文档响应或实际响应不是合法 JSON，无法进行结构校验',
      })
    }
  }
  return checks
}
