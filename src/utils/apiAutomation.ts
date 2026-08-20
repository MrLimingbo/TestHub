export interface ParsedCurlRequest {
  method: string
  url: string
  body: string
  contentType: string
  externalizedSecrets: string[]
}

const SENSITIVE_NAME = /(?:authorization|api[-_]?key|(?:access[-_]?|refresh[-_]?)?token|password|passwd|secret|cookie)/i

function tokenize(command: string): string[] {
  const tokens: string[] = []
  let token = ''
  let quote = ''
  let escaped = false
  for (const character of command.replace(/\\\r?\n/g, ' ')) {
    if (escaped) {
      token += character
      escaped = false
      continue
    }
    if (character === '\\' && quote !== "'") {
      escaped = true
      continue
    }
    if (quote) {
      if (character === quote) quote = ''
      else token += character
      continue
    }
    if (character === "'" || character === '"') {
      quote = character
      continue
    }
    if (/\s/.test(character)) {
      if (token) tokens.push(token)
      token = ''
      continue
    }
    token += character
  }
  if (quote) throw new Error('cURL 命令存在未闭合的引号')
  if (escaped) token += '\\'
  if (token) tokens.push(token)
  return tokens
}

function placeholder(name: string): string {
  return `{{${name.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_+|_+$/g, '').toUpperCase() || 'SECRET'}}}`
}

function sanitizeUrl(rawUrl: string, secrets: Set<string>): string {
  let value = rawUrl.replace(/^(https?:\/\/)[^/@\s]+@/i, '$1')
  if (value !== rawUrl) secrets.add('URL_CREDENTIAL')
  try {
    const url = new URL(value.replace(/\{\{[^}]+\}\}/g, 'variable'))
    for (const [name, current] of url.searchParams.entries()) {
      if (!SENSITIVE_NAME.test(name) || /^\{\{[^}]+\}\}$/.test(current)) continue
      url.searchParams.set(name, placeholder(name))
      secrets.add(name)
    }
    value = value.includes('{{')
      ? value.replace(/([?&])([^=&]+)=([^&]*)/g, (match, prefix: string, name: string, current: string) => {
          const decodedName = decodeURIComponent(name)
          const decodedCurrent = decodeURIComponent(current)
          if (!SENSITIVE_NAME.test(decodedName) || /^\{\{[^}]+\}\}$/.test(decodedCurrent)) return match
          secrets.add(decodedName)
          return `${prefix}${name}=${placeholder(decodedName)}`
        })
      : url.toString().replace(/%7B%7B([A-Z0-9_]+)%7D%7D/gi, '{{$1}}')
  } catch {
    // Template URLs such as {{baseUrl}}/orders are valid TestHub inputs even though URL cannot parse them.
  }
  return value
}

function sanitizeBody(rawBody: string, secrets: Set<string>): string {
  if (!rawBody.trim()) return ''
  try {
    const parsed = JSON.parse(rawBody) as unknown
    const visit = (value: unknown): unknown => {
      if (Array.isArray(value)) return value.map(visit)
      if (!value || typeof value !== 'object') return value
      return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, current]) => {
        if (SENSITIVE_NAME.test(key) && typeof current === 'string' && !/^\{\{[^}]+\}\}$/.test(current)) {
          secrets.add(key)
          return [key, placeholder(key)]
        }
        return [key, visit(current)]
      }))
    }
    return JSON.stringify(visit(parsed), null, 2)
  } catch {
    return rawBody.replace(/(^|&)([^=&]+)=([^&]*)/g, (match, prefix: string, name: string, current: string) => {
      const decodedName = decodeURIComponent(name)
      if (!SENSITIVE_NAME.test(decodedName) || /^\{\{[^}]+\}\}$/.test(decodeURIComponent(current))) return match
      secrets.add(decodedName)
      return `${prefix}${name}=${encodeURIComponent(placeholder(decodedName))}`
    })
  }
}

export function parseCurlCommand(command: string): ParsedCurlRequest {
  const tokens = tokenize(command.trim())
  if (!tokens.length || !/(^|\/)curl(?:\.exe)?$/i.test(tokens[0])) throw new Error('请输入以 curl 开头的命令')
  let method = ''
  let url = ''
  let body = ''
  let contentType = ''
  const secrets = new Set<string>()
  const flagsWithIgnoredValue = new Set(['-u', '--user', '-A', '--user-agent', '-o', '--output', '-x', '--proxy', '--connect-timeout', '--max-time'])

  for (let index = 1; index < tokens.length; index++) {
    const token = tokens[index]
    if (token === '-X' || token === '--request') method = String(tokens[++index] ?? '').toUpperCase()
    else if (token.startsWith('--request=')) method = token.slice('--request='.length).toUpperCase()
    else if (token === '--url') url = tokens[++index] ?? ''
    else if (token.startsWith('--url=')) url = token.slice('--url='.length)
    else if (['-d', '--data', '--data-raw', '--data-binary', '--data-urlencode'].includes(token)) body = tokens[++index] ?? ''
    else if (/^--data(?:-raw|-binary|-urlencode)?=/.test(token)) body = token.slice(token.indexOf('=') + 1)
    else if (token === '-H' || token === '--header') {
      const header = tokens[++index] ?? ''
      const separator = header.indexOf(':')
      const name = separator >= 0 ? header.slice(0, separator).trim() : header.trim()
      const value = separator >= 0 ? header.slice(separator + 1).trim() : ''
      if (/^content-type$/i.test(name)) contentType = value
      if (SENSITIVE_NAME.test(name) && value && !/^\{\{[^}]+\}\}$/.test(value)) secrets.add(name)
    } else if (token.startsWith('--header=')) {
      const header = token.slice('--header='.length)
      const separator = header.indexOf(':')
      const name = separator >= 0 ? header.slice(0, separator).trim() : header.trim()
      const value = separator >= 0 ? header.slice(separator + 1).trim() : ''
      if (/^content-type$/i.test(name)) contentType = value
      if (SENSITIVE_NAME.test(name) && value && !/^\{\{[^}]+\}\}$/.test(value)) secrets.add(name)
    } else if (flagsWithIgnoredValue.has(token)) {
      const value = tokens[++index] ?? ''
      if (token === '-u' || token === '--user') if (value && !/^\{\{[^}]+\}\}$/.test(value)) secrets.add('BASIC_AUTH')
    } else if (!token.startsWith('-') && !url) url = token
  }

  if (!url) throw new Error('cURL 命令缺少请求地址')
  if (!method) method = body ? 'POST' : 'GET'
  if (!['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].includes(method)) throw new Error(`暂不支持 ${method} 请求方法`)
  const safeBody = sanitizeBody(body, secrets)
  return {
    method,
    url: sanitizeUrl(url, secrets),
    body: safeBody,
    contentType: contentType || (safeBody.trim().startsWith('{') || safeBody.trim().startsWith('[') ? 'application/json' : ''),
    externalizedSecrets: [...secrets],
  }
}
