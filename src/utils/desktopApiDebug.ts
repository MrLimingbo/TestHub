export interface ApiDebugHeader {
  name: string
  value: string
}

export interface ApiDebugAuth {
  type?: string
  token?: string
  username?: string
  password?: string
  key?: string
  value?: string
  placement?: string
}

export interface ApiDebugProtocol {
  type?: string
  query?: string
  variables?: string
  operationName?: string
  envelope?: string
  soapAction?: string
}

export interface ApiDebugRequestPayload {
  name?: string
  method: string
  url: string
  headers?: ApiDebugHeader[]
  body?: string
  timeoutMs?: number
  environmentCode?: string | null
  auth?: ApiDebugAuth
  protocol?: ApiDebugProtocol
}

export interface ApiDebugResult {
  historyId: string
  success: boolean
  statusCode: number | null
  statusText: string | null
  durationMs: number
  responseHeaders: ApiDebugHeader[]
  body: string
  truncated: boolean
  errorMessage: string | null
  historyWarning?: string | null
}

interface DesktopDebugBridge {
  runtime: 'desktop'
  executeHttpRequest: (value: { projectId: string; request: ApiDebugRequestPayload }) => Promise<ApiDebugResult>
}

type DesktopWindow = Window & { testhubDesktop?: Partial<DesktopDebugBridge> }

class VariableResolver {
  private readonly executionTime = Date.now()
  private randomUuid: string | null = null

  constructor(private readonly variables: ReadonlyMap<string, string>) {}

  resolve(value: string | null | undefined): string {
    return (value ?? '').replace(/\{\{\s*([^{}\s]+)\s*}}/g, (_match, key: string) => {
      if (key === '$timestamp') return String(this.executionTime)
      if (key === '$isoTimestamp') return new Date(this.executionTime).toISOString()
      if (key === '$randomUUID' || key === '$uuid') {
        this.randomUuid ??= crypto.randomUUID()
        return this.randomUuid
      }
      const replacement = this.variables.get(key)
      if (replacement === undefined) {
        throw new Error(`桌面本地执行无法解析变量：${key}。请确认已选择环境；项目密钥不会暴露给桌面页面，请在认证区域填写本次调试值。`)
      }
      return replacement
    })
  }
}

function setHeader(headers: ApiDebugHeader[], name: string, value: string, replace = false) {
  const existing = headers.findIndex((item) => item.name.toLowerCase() === name.toLowerCase())
  if (existing >= 0) {
    if (!replace) throw new Error(`认证配置与手工请求头重复：${name}`)
    headers.splice(existing, 1)
  }
  headers.push({ name, value })
}

function basicCredentials(username: string, password: string) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function required(value: string, label: string) {
  if (!value.trim()) throw new Error(`${label}不能为空`)
  return value
}

export function isDesktopHttpDebugAvailable(): boolean {
  const bridge = (window as DesktopWindow).testhubDesktop
  return bridge?.runtime === 'desktop' && typeof bridge.executeHttpRequest === 'function'
}

export function prepareDesktopHttpRequest(
  input: ApiDebugRequestPayload,
  variables: Readonly<Record<string, string>> = {},
): ApiDebugRequestPayload {
  const resolver = new VariableResolver(new Map(Object.entries(variables)))
  const protocolType = (input.protocol?.type || 'HTTP').trim().toUpperCase()
  const method = input.method.trim().toUpperCase()
  let body = resolver.resolve(input.body)
  const headers = (input.headers ?? []).map((header) => ({
    name: resolver.resolve(header.name).trim(),
    value: resolver.resolve(header.value),
  }))

  if (protocolType === 'GRAPHQL') {
    const query = required(resolver.resolve(input.protocol?.query), 'GraphQL 查询')
    let variableValues: unknown
    try {
      variableValues = JSON.parse(resolver.resolve(input.protocol?.variables) || '{}')
    } catch {
      throw new Error('GraphQL variables 必须是有效 JSON')
    }
    if (!variableValues || Array.isArray(variableValues) || typeof variableValues !== 'object') {
      throw new Error('GraphQL variables 必须是 JSON 对象')
    }
    const graphQlBody: Record<string, unknown> = { query, variables: variableValues }
    const operationName = resolver.resolve(input.protocol?.operationName)
    if (operationName) graphQlBody.operationName = operationName
    body = JSON.stringify(graphQlBody)
    setHeader(headers, 'Content-Type', 'application/json', true)
  } else if (protocolType === 'SOAP') {
    body = required(resolver.resolve(input.protocol?.envelope), 'SOAP Envelope')
    if (!body.trim().startsWith('<') || !body.includes('Envelope')) throw new Error('SOAP Envelope 格式不合法')
    setHeader(headers, 'Content-Type', 'text/xml; charset=utf-8', true)
    const soapAction = resolver.resolve(input.protocol?.soapAction)
    if (soapAction) setHeader(headers, 'SOAPAction', soapAction, true)
  } else if (protocolType !== 'HTTP') {
    throw new Error('桌面本地执行仅支持 HTTP、GraphQL 和 SOAP')
  }

  let url = resolver.resolve(input.url)
  const auth = input.auth ?? {}
  const authType = (auth.type || 'NONE').trim().toUpperCase()
  if (authType === 'BEARER') {
    setHeader(headers, 'Authorization', `Bearer ${required(resolver.resolve(auth.token), 'Bearer Token')}`)
  } else if (authType === 'BASIC') {
    setHeader(headers, 'Authorization', `Basic ${basicCredentials(
      required(resolver.resolve(auth.username), '用户名'),
      required(resolver.resolve(auth.password), '密码'),
    )}`)
  } else if (authType === 'API_KEY') {
    const key = required(resolver.resolve(auth.key), 'API Key 名称')
    const value = required(resolver.resolve(auth.value), 'API Key 值')
    if ((auth.placement || 'HEADER').trim().toUpperCase() === 'QUERY') {
      const target = new URL(url)
      target.searchParams.append(key, value)
      url = target.toString()
    } else {
      setHeader(headers, key, value)
    }
  } else if (authType !== 'NONE') {
    throw new Error('不支持的认证类型')
  }

  return {
    name: input.name,
    method,
    url,
    headers,
    body,
    timeoutMs: input.timeoutMs ?? 10_000,
    environmentCode: input.environmentCode ?? null,
    auth: { type: 'NONE', placement: 'HEADER' },
    protocol: { type: protocolType },
  }
}

export async function executeDesktopHttpRequest(
  projectId: string,
  input: ApiDebugRequestPayload,
  variables: Readonly<Record<string, string>> = {},
): Promise<ApiDebugResult> {
  const bridge = (window as DesktopWindow).testhubDesktop
  if (bridge?.runtime !== 'desktop' || typeof bridge.executeHttpRequest !== 'function') {
    throw new Error('当前页面未连接 TestHub 桌面本地执行器')
  }
  return bridge.executeHttpRequest({ projectId, request: prepareDesktopHttpRequest(input, variables) })
}
