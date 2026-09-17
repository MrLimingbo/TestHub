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
  value = value.replace(/([?&])([^=&#]+)=([^&#]*)/g, (match, prefix: string, name: string, current: string) => {
    const decodedName = new URLSearchParams(`${name}=`).keys().next().value ?? name
    const decodedCurrent = new URLSearchParams(`value=${current}`).get('value') ?? current
    if (!SENSITIVE_NAME.test(decodedName) || /^\{\{[^}]+\}\}$/.test(decodedCurrent)) return match
    secrets.add(decodedName)
    return `${prefix}${name}=${placeholder(decodedName)}`
  })
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
  let hasData = false
  let queryData = false
  let head = false
  let contentType = ''
  const secrets = new Set<string>()
  const flagsWithIgnoredValue = new Set(['-u', '--user', '-A', '--user-agent', '-o', '--output', '-x', '--proxy', '--connect-timeout', '--max-time'])

  for (let index = 1; index < tokens.length; index++) {
    const token = tokens[index]
    if (token === '-X' || token === '--request') method = String(tokens[++index] ?? '').toUpperCase()
    else if (/^-X.+/.test(token)) method = token.slice(2).toUpperCase()
    else if (token.startsWith('--request=')) method = token.slice('--request='.length).toUpperCase()
    else if (token === '--url') url = tokens[++index] ?? ''
    else if (token.startsWith('--url=')) url = token.slice('--url='.length)
    else if (token === '-I' || token === '--head') head = true
    else if (token === '-G' || token === '--get') queryData = true
    else if (['-d', '--data', '--data-raw', '--data-binary', '--data-urlencode'].includes(token)
      || /^--data(?:-raw|-binary|-urlencode)?=/.test(token) || /^-d.+/.test(token)) {
      const flag = token.startsWith('-d') ? '-d' : token.split('=')[0]
      let part = /^-d.+/.test(token) ? token.slice(2) : token.includes('=') ? token.slice(token.indexOf('=') + 1) : tokens[++index]
      if (part === undefined) throw new Error('cURL 数据参数缺少值')
      if (flag !== '--data-raw' && (part.startsWith('@') || (flag === '--data-urlencode' && /^[^=]+@/.test(part)))) {
        throw new Error('暂不支持 cURL 文件数据，请直接填写请求内容')
      }
      if (flag === '--data-urlencode') {
        const separator = part.indexOf('=')
        part = separator < 0 ? encodeURIComponent(part) : `${part.slice(0, separator + 1)}${encodeURIComponent(part.slice(separator + 1))}`
      }
      body += `${hasData ? '&' : ''}${part}`
      hasData = true
    }
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
  if (queryData && hasData) {
    const hashIndex = url.indexOf('#')
    const fragment = hashIndex < 0 ? '' : url.slice(hashIndex)
    const address = hashIndex < 0 ? url : url.slice(0, hashIndex)
    url = `${address}${address.includes('?') ? '&' : '?'}${body}${fragment}`
    body = ''
  }
  if (!method) method = head ? 'HEAD' : queryData ? 'GET' : hasData ? 'POST' : 'GET'
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
