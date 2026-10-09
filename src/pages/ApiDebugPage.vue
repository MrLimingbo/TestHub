<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRoute, useRouter } from 'vue-router'
import CodeEditor from '@/components/CodeEditor.vue'
import PageHeader from '@/components/PageHeader.vue'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'
import { isLocalDesktop } from '@/utils/desktopWorkspace'
import { executeDesktopHttpRequest, isDesktopHttpDebugAvailable } from '@/utils/desktopApiDebug'
import {
  apiDebugDraftFromHistory,
  apiDebugDraftFromQuery,
  buildApiDebugCurlCommand,
  isRedactedApiDebugUrl,
  readApiDebugRouteDraft,
  removeApiDebugRouteDraft,
  type ApiDebugRequestAuth,
  type ApiDebugRouteDraft,
} from '@/utils/apiDebugDraft'
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
  type QuickRequestKeyValueRow,
} from '@/utils/apiQuickRequest'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Header { name: string; value: string }
interface DebugResult { historyId: string; success: boolean; statusCode: number | null; statusText: string | null; durationMs: number; responseHeaders: Header[]; body: string; truncated: boolean; errorMessage: string | null; historyWarning?: string | null }
interface History { id: string; projectId: string; method: string; protocol: string; requestUrl: string; responseStatus: number | null; durationMs: number; responseSize: number; result: string; errorMessage: string | null; replayDraft: string | null; createdAt: string }
interface Parameter { id: string; environmentCode: string; key: string; value: string }
interface Secret { id: string; environmentCode: string; key: string }
interface ParameterCollection { environments: string[]; parameters: Parameter[] }
interface SecretCollection { environments: string[]; secrets: Secret[] }
interface ProjectEnvironment { id: string; name: string; environmentCode: string; enabled: boolean; defaultEnvironment: boolean }
interface Catalog { id: string; parentId: string | null; name: string; sortOrder: number }
interface FlatCatalog extends Catalog { depth: number }
interface ImportResult { format: string; importedCount: number; items: string[] }
interface WebSocketSession { id: string; status: 'CONNECTING' | 'OPEN' | 'RECONNECTING' | 'CLOSED' | 'ERROR'; target: string; messageCount: number; connectedAt: string; closedAt: string | null; closeReason: string | null; autoReconnect: boolean; reconnectAttempts: number; maxReconnectAttempts: number; selectedSubprotocol: string }
interface WebSocketMessage { id: string; direction: 'IN' | 'OUT'; payload: string; truncated: boolean; createdAt: string }
interface GrpcResult { historyId: string; success: boolean; status: string; durationMs: number; body: string; truncated: boolean; errorMessage: string | null }
interface GrpcMethodSummary { name: string; callType: 'UNARY' | 'CLIENT_STREAMING' | 'SERVER_STREAMING' | 'BIDI_STREAMING' }
interface GrpcDiscoveryResult { services: string[]; methods: GrpcMethodSummary[]; truncated: boolean }
interface GrpcDescriptor { id: string; name: string; sourceFormat: 'PROTO3' | 'DESCRIPTOR_SET'; sizeBytes: number; serviceCount: number; sourceCount: number; createdAt: string }
interface GrpcStreamSession { id: string; status: 'OPEN' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'LIMIT_REACHED'; target: string; service: string; method: string; tls: boolean; environmentCode: string | null; authType: string; metadataCount: number; messageCount: number; responseBytes: number; createdAt: string; closedAt: string | null; closeReason: string | null }
interface GrpcStreamMessage { id: string; payload: string; truncated: boolean; sizeBytes: number; createdAt: string }
interface GrpcClientStreamSession { id: string; status: 'OPEN' | 'HALF_CLOSED' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'LIMIT_REACHED'; target: string; service: string; method: string; tls: boolean; environmentCode: string | null; authType: string; metadataCount: number; sentMessageCount: number; sentBytes: number; responseBytes: number; responseBody: string; responseTruncated: boolean; createdAt: string; closedAt: string | null; closeReason: string | null }
interface GrpcBidiStreamSession { id: string; status: 'OPEN' | 'HALF_CLOSED' | 'COMPLETED' | 'CANCELLED' | 'FAILED' | 'LIMIT_REACHED'; target: string; service: string; method: string; tls: boolean; environmentCode: string | null; authType: string; metadataCount: number; sentMessageCount: number; sentBytes: number; receivedMessageCount: number; responseBytes: number; createdAt: string; closedAt: string | null; closeReason: string | null }

const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const emit = defineEmits<{
  historyChange: []
  projectChange: [projectId: string]
}>()

const context = ref<Context | null>(null)
const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const projectId = computed({ get: () => sessionStore.projectId, set: (value: string) => sessionStore.selectProject(value) })
const method = ref('POST')
const url = ref('https://postman-echo.com/post')
const body = ref('{\n  "source": "testhub-api-debug"\n}')
const headersText = ref('Content-Type: application/json\nX-TestHub-Debug: browser-preview')
const protocolType = ref<'HTTP' | 'GRAPHQL' | 'SOAP' | 'WEBSOCKET' | 'GRPC'>('HTTP')
const graphql = reactive({ query: 'query Health { health }', variables: '{\n  "source": "testhub"\n}', operationName: '' })
const soap = reactive({ envelope: '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\n  <soapenv:Header/>\n  <soapenv:Body>\n    <Ping/>\n  </soapenv:Body>\n</soapenv:Envelope>', action: '' })
const grpc = reactive({ tls: false, service: 'main.GrpcbinService', method: 'Unary', requestJson: '{\n  "message": "Hello from TestHub"\n}', timeoutMs: 10_000 })
const grpcMetadataText = ref('x-testhub-trace: browser-preview')
const grpcAuth = reactive({ type: 'NONE', token: '', username: '', password: '', key: 'x-api-key', value: '' })
const grpcDiscovery = reactive({ loading: false, services: [] as string[], methods: [] as GrpcMethodSummary[] })
const grpcDescriptor = reactive({ selectedId: '', name: '', files: [] as File[], importing: false, items: [] as GrpcDescriptor[] })
const grpcStream = reactive<{ session: GrpcStreamSession | null; messages: GrpcStreamMessage[] }>({ session: null, messages: [] })
const grpcClientStream = reactive<{ session: GrpcClientStreamSession | null }>({ session: null })
const grpcBidiStream = reactive<{ session: GrpcBidiStreamSession | null; messages: GrpcStreamMessage[] }>({ session: null, messages: [] })
const webSocket = reactive<{ session: WebSocketSession | null; message: string; messages: WebSocketMessage[]; subscriptionDraft: string; subscriptions: string[]; subprotocolsText: string; autoReconnect: boolean; maxReconnectAttempts: number; reconnectDelayMs: number }>({
  session: null, message: 'Hello from TestHub', messages: [], subscriptionDraft: '{"type":"subscribe","topic":"orders"}', subscriptions: [], subprotocolsText: '', autoReconnect: true, maxReconnectAttempts: 3, reconnectDelayMs: 1000,
})
const environment = ref('')
const environments = ref<string[]>([])
const projectEnvironments = ref<ProjectEnvironment[]>([])
const parameters = ref<Parameter[]>([])
const secrets = ref<Secret[]>([])
const auth = reactive<ApiDebugRequestAuth>({ type: 'NONE', token: '', username: '', password: '', key: 'X-API-Key', value: '', placement: 'HEADER' })
type QuickBodyType = 'NONE' | 'FORM_DATA' | 'URLENCODED' | 'JSON' | 'XML' | 'TEXT'

const requestTab = ref('params')
const responseTab = ref('body')
const quickBodyType = ref<QuickBodyType>('JSON')
const queryRows = ref<QuickRequestKeyValueRow[]>([createQuickRequestRow()])
const headerRows = ref<QuickRequestKeyValueRow[]>([createQuickRequestRow()])
const cookieRows = ref<QuickRequestKeyValueRow[]>([createQuickRequestRow()])
const formBodyRows = ref<QuickRequestKeyValueRow[]>([createQuickRequestRow()])
const timeoutMs = ref(10_000)
const injectRequestId = ref(false)
const expectedStatus = ref<number | undefined>()
const expectedBodyContains = ref('')
const savingDraft = ref(false)
const quickRequestName = ref('快捷请求')
const quickRequestNameDraft = ref('快捷请求')
const editingQuickRequestName = ref(false)
const quickRequestNameInput = ref<{ focus: () => void } | null>(null)
const generatedCodeDialog = ref(false)
const generatedCode = ref('')
const loading = ref(true)
const sending = ref(false)
const history = ref<History[]>([])
const catalogs = ref<Catalog[]>([])
const result = ref<DebugResult | null>(null)
const responseBody = ref('发送一次请求后，在这里查看服务的真实响应。')
const saveInterfaceDialog = ref(false)
const savingInterface = ref(false)
const replayingHistoryId = ref('')
const saveInterfaceForm = reactive({ catalogId: '', name: '' })
const saveInterfaceErrors = reactive<Record<string, string>>({})
let webSocketPollTimer: number | undefined
let grpcStreamPollTimer: number | undefined
let grpcClientStreamPollTimer: number | undefined
let grpcBidiStreamPollTimer: number | undefined
let routeDraftApplied = false

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const statusColor = computed(() => !result.value ? 'gray' : result.value.success && (result.value.statusCode ?? 500) < 400 ? 'green' : 'red')
const statusText = computed(() => !result.value ? '等待发送' : result.value.statusText?.startsWith('gRPC') ? result.value.statusText : result.value.success ? `${result.value.statusCode} ${result.value.statusText ?? ''}` : '连接失败')
const responseHeadersText = computed(() => result.value?.responseHeaders.map((header) => `${header.name}: ${header.value}`).join('\n') || '暂无响应头')
const webSocketStatusColor = computed(() => ({ OPEN: 'green', CONNECTING: 'arcoblue', RECONNECTING: 'orange', CLOSED: 'gray', ERROR: 'red' }[webSocket.session?.status ?? 'CLOSED']))
const grpcSelectedCallType = computed<GrpcMethodSummary['callType']>(() => grpcDiscovery.methods.find((item) => item.name === grpc.method)?.callType ?? 'UNARY')
const grpcStreamStatusColor = computed(() => ({ OPEN: 'green', COMPLETED: 'arcoblue', CANCELLED: 'gray', FAILED: 'red', LIMIT_REACHED: 'orange' }[grpcStream.session?.status ?? 'CANCELLED']))
const grpcClientStreamStatusColor = computed(() => ({ OPEN: 'green', HALF_CLOSED: 'orange', COMPLETED: 'arcoblue', CANCELLED: 'gray', FAILED: 'red', LIMIT_REACHED: 'orange' }[grpcClientStream.session?.status ?? 'CANCELLED']))
const grpcBidiStreamStatusColor = computed(() => ({ OPEN: 'green', HALF_CLOSED: 'orange', COMPLETED: 'arcoblue', CANCELLED: 'gray', FAILED: 'red', LIMIT_REACHED: 'orange' }[grpcBidiStream.session?.status ?? 'CANCELLED']))
const variableCaption = computed(() => protocolType.value === 'WEBSOCKET'
  ? 'WebSocket 会解析当前环境变量和环境密钥；密钥只在服务端握手、订阅帧或消息生成时使用，不会回显或写入历史。'
  : protocolType.value === 'GRPC'
    ? 'gRPC Unary、服务端流、客户端流与双向流会解析当前环境变量和环境密钥；请求、响应 JSON、Metadata 与认证值只用于本次调用，不会写入历史。'
    : '使用 {{VARIABLE_NAME}} 引用当前环境变量。动态值支持 {{$timestamp}}、{{$isoTimestamp}} 和 {{$randomUUID}}。')
const networkPolicyDescription = computed(() => protocolType.value === 'WEBSOCKET'
  ? isLocalDesktop() ? 'WebSocket 可访问公网；本机或内网地址需在本地执行设置中授权，WS/WSS 分别对应 HTTP/HTTPS，并匹配主机与端口。链路本地、组播和保留地址仍禁止。' : 'WebSocket 目标必须为公开可访问的 WS/WSS 地址；内网、回环、链路本地地址和手工握手头均会被拦截。'
  : protocolType.value === 'GRPC'
    ? isLocalDesktop() ? 'gRPC 可访问公网；本机或内网地址需在本地执行设置中授权相同主机与端口，启用 TLS 对应 HTTPS，否则对应 HTTP。链路本地、组播和保留地址仍禁止。流会话仅保存在当前进程内存中。' : 'gRPC 目标必须为公开可访问的主机:端口；内网、回环、链路本地地址会被拦截。在线反射描述符仅在本次调用内存中存在；导入的离线描述符是项目级、已校验的编译资产，调用时不会请求远端反射。服务端流、客户端流与双向流数据仅保存在当前控制面内存会话；每个方向限制为 100 条消息、总计 512 KB，达到上限会自动取消。双向流完成发送后仍会继续接收响应。JSON、ASCII Metadata、认证值与响应均不写入历史。'
    : isDesktopHttpDebugAvailable()
      ? '桌面端 HTTP/HTTPS 请求直接从当前电脑发出，可访问接口地址或环境变量配置的任意内网地址；每个目标 Origin 首次访问需确认，且不会自动跟随重定向。'
      : '在线 HTTP/HTTPS 默认阻止内网访问；管理员可在系统配置启用 api-debug.allow-configured-private-targets，允许接口地址或环境变量使用任意私网 IP/端口，也可通过 api-debug.allowed-private-origins 精确放行。回环、链路本地、云元数据、组播、保留地址与重定向始终禁止。')
const variables = computed(() => [
  ...parameters.value.map((parameter) => ({ key: parameter.key, secret: false, preview: parameter.value })),
  ...secrets.value.map((secret) => ({ key: secret.key, secret: true, preview: '已加密，仅执行时解析' })),
])
const catalogOptions = computed<FlatCatalog[]>(() => {
  const result: FlatCatalog[] = []
  const visited = new Set<string>()
  const visit = (parentId: string | null, depth: number) => {
    catalogs.value
      .filter((catalog) => catalog.parentId === parentId && !visited.has(catalog.id))
      .sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name))
      .forEach((catalog) => {
        visited.add(catalog.id)
        result.push({ ...catalog, depth })
        visit(catalog.id, depth + 1)
      })
  }
  visit(null, 0)
  catalogs.value
    .filter((catalog) => !visited.has(catalog.id))
    .forEach((catalog) => result.push({ ...catalog, depth: 0 }))
  return result
})
const activeQueryCount = computed(() => queryRows.value.filter((row) => row.enabled && row.name.trim()).length)
const activeCookieCount = computed(() => cookieRows.value.filter((row) => row.enabled && row.name.trim()).length)
const activeHeaderCount = computed(() => headerRows.value.filter((row) => row.enabled && row.name.trim()).length + (activeCookieCount.value ? 1 : 0))
const postOperationChecks = computed(() => {
  if (!result.value) return []
  const checks: Array<{ label: string; passed: boolean; detail: string }> = []
  if (expectedStatus.value !== undefined) checks.push({
    label: '响应状态码',
    passed: result.value.statusCode === expectedStatus.value,
    detail: `期望 ${expectedStatus.value}，实际 ${result.value.statusCode ?? '无响应'}`,
  })
  if (expectedBodyContains.value.trim()) checks.push({
    label: '响应正文包含',
    passed: result.value.body.includes(expectedBodyContains.value),
    detail: expectedBodyContains.value,
  })
  return checks
})
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value))

async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: 'include', ...init })
  const envelope = await response.json().catch(() => ({})) as ApiEnvelope<T> & { code?: string; message?: string; details?: Record<string, unknown> }
  if (!response.ok) throw toApiRequestError(response, envelope)
  return envelope.data
}

async function mutate<T>(path: string, bodyValue: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(bodyValue) })
}

async function mutateForm<T>(path: string, form: FormData): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  const response = await fetch(path, { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token }, body: form })
  const envelope = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(envelope.message ?? '请求失败，请稍后重试')
  return envelope.data
}

function parseHeaderText() {
  return headersText.value.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const separator = line.indexOf(':')
    if (separator <= 0) throw new Error('请求头每行需使用“名称: 值”格式')
    return { name: line.slice(0, separator).trim(), value: line.slice(separator + 1).trim() }
  })
}

function withBlankRow(rows: QuickRequestKeyValueRow[]) {
  return [...rows.filter((row) => row.name || row.value || row.description), createQuickRequestRow()]
}

function hydrateQuickHttpEditor() {
  queryRows.value = withBlankRow(parseQuickRequestQuery(url.value))
  const split = splitQuickRequestCookies(parseQuickRequestHeaders(headersText.value))
  headerRows.value = withBlankRow(split.headerRows)
  cookieRows.value = withBlankRow(split.cookieRows)
  const rawContentType = split.headerRows.find((row) => row.name.toLowerCase() === 'content-type')?.value ?? ''
  const contentType = rawContentType.toLowerCase()
  if (!body.value.trim()) quickBodyType.value = 'NONE'
  else if (contentType.includes('x-www-form-urlencoded')) quickBodyType.value = 'URLENCODED'
  else if (contentType.includes('multipart/form-data')) quickBodyType.value = 'FORM_DATA'
  else if (contentType.includes('xml')) quickBodyType.value = 'XML'
  else if (contentType.startsWith('text/')) quickBodyType.value = 'TEXT'
  else quickBodyType.value = 'JSON'
  if (quickBodyType.value === 'URLENCODED') formBodyRows.value = withBlankRow(parseQuickRequestQuery(`?${body.value}`))
  else if (quickBodyType.value === 'FORM_DATA') {
    const rows = parseMultipartRows(body.value, rawContentType)
    if (rows) formBodyRows.value = withBlankRow(rows)
    else {
      quickBodyType.value = 'TEXT'
      formBodyRows.value = [createQuickRequestRow()]
    }
  }
  else formBodyRows.value = [createQuickRequestRow()]
}

function resetNewQuickRequest() {
  quickRequestName.value = '快捷请求'
  quickRequestNameDraft.value = '快捷请求'
  editingQuickRequestName.value = false
  protocolType.value = 'HTTP'
  method.value = 'GET'
  url.value = ''
  body.value = ''
  headersText.value = ''
  auth.type = 'NONE'
  requestTab.value = 'params'
  result.value = null
  responseBody.value = '发送请求后将在这里展示响应正文。'
}

function syncQueryFromUrl() {
  queryRows.value = withBlankRow(parseQuickRequestQuery(url.value))
}

function syncUrlFromQuery() {
  url.value = applyQuickRequestQuery(url.value, queryRows.value)
}

function addQuickRow(rows: QuickRequestKeyValueRow[]) {
  rows.push(createQuickRequestRow())
}

function removeQuickRow(rows: QuickRequestKeyValueRow[], index: number) {
  rows.splice(index, 1)
  if (!rows.length) rows.push(createQuickRequestRow())
  if (rows === queryRows.value) syncUrlFromQuery()
}

function quickRequestBody() {
  if (quickBodyType.value === 'NONE') return ''
  if (quickBodyType.value === 'URLENCODED') return serializeUrlEncodedRows(formBodyRows.value)
  if (quickBodyType.value === 'FORM_DATA') return serializeMultipartRows(formBodyRows.value, '----TestHubQuickRequestBoundary')
  return body.value
}

function quickRequestHeaders() {
  const values = serializeQuickRequestHeaders(headerRows.value)
  const cookie = serializeQuickRequestCookies(cookieRows.value)
  if (cookie) values.push({ name: 'Cookie', value: cookie })
  const contentTypes: Record<Exclude<QuickBodyType, 'NONE'>, string> = {
    FORM_DATA: 'multipart/form-data; boundary=----TestHubQuickRequestBoundary',
    URLENCODED: 'application/x-www-form-urlencoded',
    JSON: 'application/json',
    XML: 'application/xml',
    TEXT: 'text/plain; charset=UTF-8',
  }
  if (quickBodyType.value === 'FORM_DATA') {
    const existing = values.find((header) => header.name.toLowerCase() === 'content-type')
    if (existing) existing.value = contentTypes.FORM_DATA
  }
  if (quickBodyType.value !== 'NONE' && !values.some((header) => header.name.toLowerCase() === 'content-type')) {
    values.push({ name: 'Content-Type', value: contentTypes[quickBodyType.value] })
  }
  if (injectRequestId.value && !values.some((header) => header.name.toLowerCase() === 'x-request-id')) {
    values.push({ name: 'X-Request-ID', value: '{{$randomUUID}}' })
  }
  return values
}

function headers() {
  return protocolType.value === 'HTTP' ? quickRequestHeaders() : parseHeaderText()
}

function quickRequestPayload() {
  syncUrlFromQuery()
  return {
    name: quickRequestName.value.trim() || '快捷请求',
    method: method.value,
    url: url.value,
    headers: headers(),
    body: quickRequestBody(),
    timeoutMs: timeoutMs.value,
    environmentCode: environment.value || null,
    auth: { ...auth },
    protocol: {
      type: protocolType.value,
      query: graphql.query,
      variables: graphql.variables,
      operationName: graphql.operationName,
      envelope: soap.envelope,
      soapAction: soap.action,
    },
  }
}

function grpcMetadata() {
  return grpcMetadataText.value.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const separator = line.indexOf(':')
    if (separator <= 0) throw new Error('Metadata 每行需使用“名称: 值”格式')
    return { key: line.slice(0, separator).trim(), value: line.slice(separator + 1).trim() }
  })
}

async function loadHistory() {
  if (!projectId.value) return
  history.value = await read<History[]>(`/api/v1/projects/${projectId.value}/api-debug/history?limit=20`)
  emit('historyChange')
}

async function loadCatalogs() {
  if (!projectId.value) return
  catalogs.value = await read<Catalog[]>(`/api/v1/projects/${projectId.value}/api-design/catalogs`)
}

async function loadEnvironment(preferredEnvironment?: string) {
  if (!projectId.value) return
  const [environmentData, parameterData, secretData] = await Promise.all([
    read<ProjectEnvironment[]>(`/api/v1/projects/${projectId.value}/environments`),
    read<ParameterCollection>(`/api/v1/projects/${projectId.value}/parameters`),
    read<SecretCollection>(`/api/v1/projects/${projectId.value}/secrets`),
  ])
  projectEnvironments.value = environmentData
  environments.value = environmentData.map((item) => item.environmentCode)
  if (preferredEnvironment !== undefined) environment.value = preferredEnvironment.trim()
  if (environment.value && !environments.value.includes(environment.value)) environment.value = ''
  if (!environment.value && environments.value.length) {
    environment.value = environmentData.find((item) => item.enabled && item.defaultEnvironment)?.environmentCode
      ?? environmentData.find((item) => item.enabled)?.environmentCode
      ?? environments.value[0]
  }
  parameters.value = parameterData.parameters.filter((parameter) => parameter.environmentCode === environment.value)
  secrets.value = secretData.secrets.filter((secret) => secret.environmentCode === environment.value)
}

async function loadGrpcDescriptors() {
  if (!projectId.value) return
  grpcDescriptor.items = await read<GrpcDescriptor[]>(`/api/v1/projects/${projectId.value}/api-debug/grpc/descriptors`)
  if (grpcDescriptor.selectedId && !grpcDescriptor.items.some((item) => item.id === grpcDescriptor.selectedId)) grpcDescriptor.selectedId = ''
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = sessionStore.resolveProjectId(projects.value)
    const routeDraft = readApiDebugRouteDraft(window.sessionStorage, route.query.draft)
      ?? apiDebugDraftFromQuery(route.query as Record<string, unknown>)
    await loadEnvironment(routeDraft?.environment)
    if (!routeDraft && props.embedded) resetNewQuickRequest()
    else applyRouteDraft(routeDraft)
    if (protocolType.value === 'HTTP') hydrateQuickHttpEditor()
    removeApiDebugRouteDraft(window.sessionStorage, route.query.draft)
    await Promise.all([loadHistory(), loadGrpcDescriptors(), loadCatalogs()])
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载快捷请求工作台失败') } finally { loading.value = false }
}

function applyRouteDraft(draft: ApiDebugRouteDraft | null) {
  if (routeDraftApplied) return
  routeDraftApplied = true
  if (!draft) return
  if (draft.replayDraft && applyStoredReplayDraft(draft.replayDraft)) return
  if (draft.name) quickRequestName.value = draft.name
  if (draft.protocol) {
    protocolType.value = draft.protocol
    changeProtocol()
  }
  if (draft.method) method.value = draft.method
  if (draft.url) url.value = draft.url
  if (draft.environment !== undefined) environment.value = draft.environment
  if (draft.headers !== undefined) {
    if (protocolType.value === 'GRPC') grpcMetadataText.value = draft.headers
    else headersText.value = draft.headers
  }
  if (draft.body !== undefined) {
    if (protocolType.value === 'GRAPHQL') graphql.query = draft.body
    else if (protocolType.value === 'SOAP') soap.envelope = draft.body
    else if (protocolType.value === 'WEBSOCKET') webSocket.message = draft.body
    else if (protocolType.value === 'GRPC') grpc.requestJson = draft.body
    else body.value = draft.body
  }
}

async function changeProject() {
  stopWebSocketPolling()
  stopGrpcStreamPolling()
  stopGrpcClientStreamPolling()
  stopGrpcBidiStreamPolling()
  webSocket.session = null
  webSocket.messages = []
  grpcStream.session = null
  grpcStream.messages = []
  grpcClientStream.session = null
  grpcBidiStream.session = null
  grpcBidiStream.messages = []
  grpcDiscovery.services = []
  grpcDiscovery.methods = []
  grpcDescriptor.selectedId = ''
  grpcDescriptor.items = []
  grpcDescriptor.files = []
  result.value = null
  responseBody.value = '已切换项目，请发送新的请求。'
  environment.value = ''
  catalogs.value = []
  saveInterfaceDialog.value = false
  try {
    await Promise.all([loadEnvironment(), loadHistory(), loadGrpcDescriptors(), loadCatalogs()])
    emit('projectChange', projectId.value)
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载项目调试配置失败') }
}

async function changeEnvironment() {
  grpcDiscovery.services = []
  grpcDiscovery.methods = []
  try { await loadEnvironment() } catch (error) { Message.error(error instanceof Error ? error.message : '加载环境变量失败') }
}

function changeProtocol() {
  stopGrpcStreamPolling()
  stopGrpcClientStreamPolling()
  stopGrpcBidiStreamPolling()
  requestTab.value = protocolType.value === 'HTTP' ? 'params' : 'body'
  if (protocolType.value === 'HTTP') hydrateQuickHttpEditor()
  if (protocolType.value === 'WEBSOCKET') {
    method.value = 'GET'
    if (url.value === 'https://postman-echo.com/post') url.value = 'wss://ws.postman-echo.com/raw'
    result.value = null
    responseBody.value = 'WebSocket 消息会显示在请求编辑器中，不写入 HTTP 执行历史。'
    return
  }
  if (protocolType.value === 'GRPC') {
    method.value = 'POST'
    if (url.value === 'https://postman-echo.com/post' || url.value.startsWith('ws')) url.value = 'grpcb.in:9000'
    result.value = null
    responseBody.value = '输入 gRPC 服务、方法与 JSON 请求体后调用。Unary、服务端流、客户端流或双向流数据仅在本次调用中保留。'
    return
  }
  if (protocolType.value !== 'HTTP') method.value = 'POST'
}

async function send() {
  if (protocolType.value === 'WEBSOCKET') {
    await connectWebSocket()
    return
  }
  if (protocolType.value === 'GRPC') {
    if (grpcSelectedCallType.value === 'SERVER_STREAMING') await sendGrpcServerStream()
    else if (grpcSelectedCallType.value === 'CLIENT_STREAMING') await startGrpcClientStream()
    else if (grpcSelectedCallType.value === 'BIDI_STREAMING') await startGrpcBidiStream()
    else await sendGrpc()
    return
  }
  sending.value = true
  result.value = null
  try {
    const payload = quickRequestPayload()
    const response = isDesktopHttpDebugAvailable()
      ? await executeDesktopHttpRequest(projectId.value, payload,
          Object.fromEntries(parameters.value.map((parameter) => [parameter.key, parameter.value])))
      : await mutate<DebugResult>(`/api/v1/projects/${projectId.value}/api-debug/execute`, payload)
    result.value = response
    responseBody.value = response.success ? (response.body || '(目标服务未返回响应体)') : (response.errorMessage ?? '连接目标服务失败')
    await loadHistory()
    if (response.historyWarning) Message.warning(`请求已完成，但${response.historyWarning}`)
    else if (postOperationChecks.value.some((check) => !check.passed)) Message.warning('请求已完成，但后置校验存在失败项')
    else if (response.success) Message.success(`请求完成 · ${response.statusCode} · ${response.durationMs} ms`)
    else Message.warning(response.errorMessage ?? '请求未能连接到目标服务')
  } catch (error) {
    responseBody.value = error instanceof Error ? error.message : '发送请求失败'
    Message.error(responseBody.value)
  } finally { sending.value = false }
}

function grpcCallPayload() {
  return {
    endpoint: url.value, tls: grpc.tls, service: grpc.service, method: grpc.method, requestJson: grpc.requestJson,
    timeoutMs: grpc.timeoutMs, environmentCode: environment.value || null, metadata: grpcMetadata(), auth: { ...grpcAuth },
    descriptorId: grpcDescriptor.selectedId || null,
  }
}

async function sendGrpc() {
  if (!projectId.value) return
  sending.value = true
  result.value = null
  try {
    const response = await mutate<GrpcResult>(`/api/v1/projects/${projectId.value}/api-debug/grpc/unary`, grpcCallPayload())
    result.value = {
      historyId: response.historyId, success: response.success, statusCode: response.success ? 0 : null,
      statusText: `gRPC ${response.status}`, durationMs: response.durationMs, responseHeaders: [], body: response.body,
      truncated: response.truncated, errorMessage: response.errorMessage,
    }
    responseBody.value = response.success ? (response.body || '(目标服务未返回响应体)') : (response.errorMessage ?? '目标 gRPC 服务不可用')
    await loadHistory()
    if (response.success) Message.success(`gRPC Unary 调用完成 · ${response.durationMs} ms`)
    else Message.warning(response.errorMessage ?? 'gRPC Unary 调用失败')
  } catch (error) {
    responseBody.value = error instanceof Error ? error.message : 'gRPC Unary 调用失败'
    Message.error(responseBody.value)
  } finally { sending.value = false }
}

function stopGrpcStreamPolling() {
  if (grpcStreamPollTimer !== undefined) window.clearInterval(grpcStreamPollTimer)
  grpcStreamPollTimer = undefined
}

function startGrpcStreamPolling() {
  stopGrpcStreamPolling()
  grpcStreamPollTimer = window.setInterval(() => { void loadGrpcStreamMessages() }, 1000)
}

async function loadGrpcStreamMessages() {
  if (!projectId.value || !grpcStream.session?.id) return
  try {
    const [session, messages] = await Promise.all([
      read<GrpcStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/streams/${grpcStream.session.id}`),
      read<GrpcStreamMessage[]>(`/api/v1/projects/${projectId.value}/api-debug/grpc/streams/${grpcStream.session.id}/messages`),
    ])
    grpcStream.session = session
    grpcStream.messages = messages
    if (session.status !== 'OPEN') stopGrpcStreamPolling()
  } catch (error) {
    stopGrpcStreamPolling()
    Message.error(error instanceof Error ? error.message : '读取 gRPC 流式消息失败')
  }
}

async function sendGrpcServerStream() {
  if (!projectId.value) return
  if (grpcStream.session?.status === 'OPEN') {
    await loadGrpcStreamMessages()
    Message.info('当前服务端流仍在运行，已刷新消息')
    return
  }
  sending.value = true
  result.value = null
  grpcStream.messages = []
  try {
    grpcStream.session = await mutate<GrpcStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/server-stream`, grpcCallPayload())
    await loadGrpcStreamMessages()
    if (grpcStream.session?.status === 'OPEN') startGrpcStreamPolling()
    responseBody.value = '服务端流已启动，请在上方流式消息面板查看实时响应。'
    Message.success('gRPC 服务端流已启动')
  } catch (error) {
    responseBody.value = error instanceof Error ? error.message : 'gRPC 服务端流启动失败'
    Message.error(responseBody.value)
  } finally { sending.value = false }
}

async function cancelGrpcServerStream() {
  if (!projectId.value || !grpcStream.session || grpcStream.session.status !== 'OPEN') return
  sending.value = true
  try {
    grpcStream.session = await mutate<GrpcStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/streams/${grpcStream.session.id}/cancel`, {})
    await loadGrpcStreamMessages()
    stopGrpcStreamPolling()
    Message.success('gRPC 服务端流已取消')
  } catch (error) { Message.error(error instanceof Error ? error.message : '取消 gRPC 服务端流失败') } finally { sending.value = false }
}

function stopGrpcClientStreamPolling() {
  if (grpcClientStreamPollTimer !== undefined) window.clearInterval(grpcClientStreamPollTimer)
  grpcClientStreamPollTimer = undefined
}

function startGrpcClientStreamPolling() {
  stopGrpcClientStreamPolling()
  grpcClientStreamPollTimer = window.setInterval(() => { void loadGrpcClientStream() }, 1000)
}

async function loadGrpcClientStream() {
  if (!projectId.value || !grpcClientStream.session?.id) return
  try {
    const session = await read<GrpcClientStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/client-streams/${grpcClientStream.session.id}`)
    grpcClientStream.session = session
    if (!['OPEN', 'HALF_CLOSED'].includes(session.status)) stopGrpcClientStreamPolling()
  } catch (error) {
    stopGrpcClientStreamPolling()
    Message.error(error instanceof Error ? error.message : '读取 gRPC 客户端流状态失败')
  }
}

function grpcClientStreamStartPayload() {
  return {
    endpoint: url.value, tls: grpc.tls, service: grpc.service, method: grpc.method, timeoutMs: grpc.timeoutMs,
    environmentCode: environment.value || null, metadata: grpcMetadata(), auth: { ...grpcAuth }, descriptorId: grpcDescriptor.selectedId || null,
  }
}

async function startGrpcClientStream() {
  if (!projectId.value) return
  if (['OPEN', 'HALF_CLOSED'].includes(grpcClientStream.session?.status ?? '')) {
    await loadGrpcClientStream()
    Message.info('当前客户端流仍在运行，已刷新状态')
    return
  }
  sending.value = true
  result.value = null
  try {
    grpcClientStream.session = await mutate<GrpcClientStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/client-streams`, grpcClientStreamStartPayload())
    startGrpcClientStreamPolling()
    responseBody.value = '客户端流已建立。请在下方提交 JSON 消息，完成发送后查看服务端响应。'
    Message.success('gRPC 客户端流已建立')
  } catch (error) {
    responseBody.value = error instanceof Error ? error.message : 'gRPC 客户端流建立失败'
    Message.error(responseBody.value)
  } finally { sending.value = false }
}

async function sendGrpcClientStreamMessage() {
  if (!projectId.value || grpcClientStream.session?.status !== 'OPEN') return
  sending.value = true
  try {
    grpcClientStream.session = await mutate<GrpcClientStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/client-streams/${grpcClientStream.session.id}/messages`, { requestJson: grpc.requestJson })
    Message.success(`已提交第 ${grpcClientStream.session.sentMessageCount} 条客户端流消息`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '提交 gRPC 客户端流消息失败') } finally { sending.value = false }
}

async function completeGrpcClientStream() {
  if (!projectId.value || grpcClientStream.session?.status !== 'OPEN') return
  sending.value = true
  try {
    grpcClientStream.session = await mutate<GrpcClientStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/client-streams/${grpcClientStream.session.id}/complete`, {})
    await loadGrpcClientStream()
    Message.success('已完成客户端消息发送，正在等待服务端响应')
  } catch (error) { Message.error(error instanceof Error ? error.message : '完成 gRPC 客户端流失败') } finally { sending.value = false }
}

async function cancelGrpcClientStream() {
  if (!projectId.value || !grpcClientStream.session || !['OPEN', 'HALF_CLOSED'].includes(grpcClientStream.session.status)) return
  sending.value = true
  try {
    grpcClientStream.session = await mutate<GrpcClientStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/client-streams/${grpcClientStream.session.id}/cancel`, {})
    stopGrpcClientStreamPolling()
    Message.success('gRPC 客户端流已取消')
  } catch (error) { Message.error(error instanceof Error ? error.message : '取消 gRPC 客户端流失败') } finally { sending.value = false }
}

function stopGrpcBidiStreamPolling() {
  if (grpcBidiStreamPollTimer !== undefined) window.clearInterval(grpcBidiStreamPollTimer)
  grpcBidiStreamPollTimer = undefined
}

function startGrpcBidiStreamPolling() {
  stopGrpcBidiStreamPolling()
  grpcBidiStreamPollTimer = window.setInterval(() => { void loadGrpcBidiStream() }, 1000)
}

async function loadGrpcBidiStream() {
  if (!projectId.value || !grpcBidiStream.session?.id) return
  try {
    const [session, messages] = await Promise.all([
      read<GrpcBidiStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/bidi-streams/${grpcBidiStream.session.id}`),
      read<GrpcStreamMessage[]>(`/api/v1/projects/${projectId.value}/api-debug/grpc/bidi-streams/${grpcBidiStream.session.id}/messages`),
    ])
    grpcBidiStream.session = session
    grpcBidiStream.messages = messages
    if (!['OPEN', 'HALF_CLOSED'].includes(session.status)) stopGrpcBidiStreamPolling()
  } catch (error) {
    stopGrpcBidiStreamPolling()
    Message.error(error instanceof Error ? error.message : '读取 gRPC 双向流状态失败')
  }
}

function grpcBidiStreamStartPayload() {
  return {
    endpoint: url.value, tls: grpc.tls, service: grpc.service, method: grpc.method, timeoutMs: grpc.timeoutMs,
    environmentCode: environment.value || null, metadata: grpcMetadata(), auth: { ...grpcAuth }, descriptorId: grpcDescriptor.selectedId || null,
  }
}

async function startGrpcBidiStream() {
  if (!projectId.value) return
  if (['OPEN', 'HALF_CLOSED'].includes(grpcBidiStream.session?.status ?? '')) {
    await loadGrpcBidiStream()
    Message.info('当前 gRPC 双向流仍在运行，已刷新状态')
    return
  }
  sending.value = true
  result.value = null
  grpcBidiStream.messages = []
  try {
    grpcBidiStream.session = await mutate<GrpcBidiStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/bidi-streams`, grpcBidiStreamStartPayload())
    startGrpcBidiStreamPolling()
    responseBody.value = '双向流已建立。请在下方反复提交 JSON 消息并查看实时响应；完成发送后仍会继续接收服务端消息。'
    Message.success('gRPC 双向流已建立')
  } catch (error) {
    responseBody.value = error instanceof Error ? error.message : 'gRPC 双向流建立失败'
    Message.error(responseBody.value)
  } finally { sending.value = false }
}

async function sendGrpcBidiStreamMessage() {
  if (!projectId.value || grpcBidiStream.session?.status !== 'OPEN') return
  sending.value = true
  try {
    grpcBidiStream.session = await mutate<GrpcBidiStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/bidi-streams/${grpcBidiStream.session.id}/messages`, { requestJson: grpc.requestJson })
    Message.success(`已提交第 ${grpcBidiStream.session.sentMessageCount} 条双向流消息`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '提交 gRPC 双向流消息失败') } finally { sending.value = false }
}

async function completeGrpcBidiStream() {
  if (!projectId.value || grpcBidiStream.session?.status !== 'OPEN') return
  sending.value = true
  try {
    grpcBidiStream.session = await mutate<GrpcBidiStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/bidi-streams/${grpcBidiStream.session.id}/complete`, {})
    await loadGrpcBidiStream()
    Message.success('已完成双向流客户端消息发送，仍可接收服务端消息')
  } catch (error) { Message.error(error instanceof Error ? error.message : '完成 gRPC 双向流失败') } finally { sending.value = false }
}

async function cancelGrpcBidiStream() {
  if (!projectId.value || !grpcBidiStream.session || !['OPEN', 'HALF_CLOSED'].includes(grpcBidiStream.session.status)) return
  sending.value = true
  try {
    grpcBidiStream.session = await mutate<GrpcBidiStreamSession>(`/api/v1/projects/${projectId.value}/api-debug/grpc/bidi-streams/${grpcBidiStream.session.id}/cancel`, {})
    stopGrpcBidiStreamPolling()
    Message.success('gRPC 双向流已取消')
  } catch (error) { Message.error(error instanceof Error ? error.message : '取消 gRPC 双向流失败') } finally { sending.value = false }
}

function grpcDiscoveryPayload(service: string | null) {
  return {
    endpoint: url.value, tls: grpc.tls, service, timeoutMs: grpc.timeoutMs, environmentCode: environment.value || null,
    metadata: grpcMetadata(), auth: { ...grpcAuth },
  }
}

async function discoverGrpcServices() {
  if (!projectId.value) return
  grpcDiscovery.loading = true
  try {
    const response = grpcDescriptor.selectedId
      ? await read<GrpcDiscoveryResult>(`/api/v1/projects/${projectId.value}/api-debug/grpc/descriptors/${grpcDescriptor.selectedId}/discovery`)
      : await mutate<GrpcDiscoveryResult>(`/api/v1/projects/${projectId.value}/api-debug/grpc/discovery`, grpcDiscoveryPayload(null))
    grpcDiscovery.services = response.services
    grpcDiscovery.methods = []
    if (!response.services.length) Message.info('目标服务未返回可发现的 gRPC 服务')
    else Message.success(`已发现 ${response.services.length}${response.truncated ? '+' : ''} 个服务`)
    if (response.truncated) Message.warning('发现结果已限制为前 500 项')
  } catch (error) { Message.error(error instanceof Error ? error.message : 'gRPC 服务发现失败') } finally { grpcDiscovery.loading = false }
}

async function discoverGrpcMethods() {
  if (!projectId.value) return
  if (!grpc.service.trim()) { Message.warning('请先填写或选择服务名称'); return }
  grpcDiscovery.loading = true
  try {
    const response = grpcDescriptor.selectedId
      ? await read<GrpcDiscoveryResult>(`/api/v1/projects/${projectId.value}/api-debug/grpc/descriptors/${grpcDescriptor.selectedId}/discovery?service=${encodeURIComponent(grpc.service)}`)
      : await mutate<GrpcDiscoveryResult>(`/api/v1/projects/${projectId.value}/api-debug/grpc/discovery`, grpcDiscoveryPayload(grpc.service))
    grpcDiscovery.methods = response.methods
    if (!response.methods.length) Message.info('目标服务未返回可发现的方法')
    else Message.success(`已发现 ${response.methods.length}${response.truncated ? '+' : ''} 个方法`)
    if (response.truncated) Message.warning('发现结果已限制为前 500 项')
  } catch (error) { Message.error(error instanceof Error ? error.message : 'gRPC 方法发现失败') } finally { grpcDiscovery.loading = false }
}

function grpcCallType(value: GrpcMethodSummary['callType']) {
  return ({ UNARY: 'Unary', CLIENT_STREAMING: '客户端流', SERVER_STREAMING: '服务端流', BIDI_STREAMING: '双向流' } as const)[value]
}

function selectGrpcFiles(event: Event) {
  const input = event.target as HTMLInputElement
  grpcDescriptor.files = Array.from(input.files ?? [])
  if (!grpcDescriptor.name && grpcDescriptor.files.length) grpcDescriptor.name = grpcDescriptor.files[0].name.replace(/\.(proto|desc|pb|bin)$/i, '')
}

async function importGrpcDescriptor() {
  if (!projectId.value) return
  if (!grpcDescriptor.name.trim()) { Message.warning('请填写描述符名称'); return }
  if (!grpcDescriptor.files.length) { Message.warning('请选择 .proto 或 FileDescriptorSet 文件'); return }
  grpcDescriptor.importing = true
  try {
    const form = new FormData()
    form.append('name', grpcDescriptor.name.trim())
    grpcDescriptor.files.forEach((file) => form.append('files', file))
    const imported = await mutateForm<GrpcDescriptor>(`/api/v1/projects/${projectId.value}/api-debug/grpc/descriptors`, form)
    grpcDescriptor.items = [imported, ...grpcDescriptor.items.filter((item) => item.id !== imported.id)]
    grpcDescriptor.selectedId = imported.id
    grpcDescriptor.files = []
    grpcDescriptor.name = ''
    grpcDiscovery.services = []
    grpcDiscovery.methods = []
    await discoverGrpcServices()
    Message.success(`已导入离线描述符：${imported.name}`)
  } catch (error) { Message.error(error instanceof Error ? error.message : 'Proto 导入失败') } finally { grpcDescriptor.importing = false }
}

async function deleteGrpcDescriptor() {
  if (!projectId.value || !grpcDescriptor.selectedId) return
  const target = grpcDescriptor.items.find((item) => item.id === grpcDescriptor.selectedId)
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    await read<unknown>(`/api/v1/projects/${projectId.value}/api-debug/grpc/descriptors/${grpcDescriptor.selectedId}`, { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } })
    grpcDescriptor.items = grpcDescriptor.items.filter((item) => item.id !== grpcDescriptor.selectedId)
    grpcDescriptor.selectedId = ''
    grpcDiscovery.services = []
    grpcDiscovery.methods = []
    Message.success(`已删除离线描述符：${target?.name ?? ''}`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除离线描述符失败') }
}

async function changeGrpcDescriptor() {
  grpcDiscovery.services = []
  grpcDiscovery.methods = []
  if (grpcDescriptor.selectedId) await discoverGrpcServices()
}

function stopWebSocketPolling() {
  if (webSocketPollTimer !== undefined) window.clearInterval(webSocketPollTimer)
  webSocketPollTimer = undefined
}

function startWebSocketPolling() {
  stopWebSocketPolling()
  webSocketPollTimer = window.setInterval(() => { void loadWebSocketMessages() }, 2000)
}

async function loadWebSocketMessages() {
  if (!projectId.value || !webSocket.session?.id) return
  try {
    const [session, messages] = await Promise.all([
      read<WebSocketSession>(`/api/v1/projects/${projectId.value}/api-debug/websocket/sessions/${webSocket.session.id}`),
      read<WebSocketMessage[]>(`/api/v1/projects/${projectId.value}/api-debug/websocket/sessions/${webSocket.session.id}/messages`),
    ])
    webSocket.session = session
    webSocket.messages = messages
    if (session.status === 'CLOSED' || session.status === 'ERROR') stopWebSocketPolling()
  } catch (error) {
    stopWebSocketPolling()
    Message.error(error instanceof Error ? error.message : '读取 WebSocket 消息失败')
  }
}

async function connectWebSocket() {
  if (!projectId.value) return
  if (['OPEN', 'CONNECTING', 'RECONNECTING'].includes(webSocket.session?.status ?? '')) {
    await loadWebSocketMessages()
    Message.info(webSocket.session?.status === 'OPEN' ? 'WebSocket 已连接，已刷新消息' : 'WebSocket 正在连接或重连')
    return
  }
  sending.value = true
  try {
    webSocket.session = await mutate<WebSocketSession>(`/api/v1/projects/${projectId.value}/api-debug/websocket/connect`, {
      url: url.value,
      headers: headers(),
      environmentCode: environment.value || null,
      auth: { ...auth },
      subprotocols: webSocket.subprotocolsText.split(',').map((item) => item.trim()).filter(Boolean),
      subscriptions: webSocket.subscriptions,
      reconnect: { enabled: webSocket.autoReconnect, maxAttempts: webSocket.maxReconnectAttempts, delayMs: webSocket.reconnectDelayMs },
    })
    await loadWebSocketMessages()
    await loadHistory()
    startWebSocketPolling()
    Message.success('WebSocket 已连接')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '连接 WebSocket 失败')
  } finally { sending.value = false }
}

async function sendWebSocketMessage() {
  if (!projectId.value || !webSocket.session) return
  if (webSocket.session.status !== 'OPEN') { Message.warning('请先建立 WebSocket 连接'); return }
  sending.value = true
  try {
    webSocket.session = await mutate<WebSocketSession>(`/api/v1/projects/${projectId.value}/api-debug/websocket/sessions/${webSocket.session.id}/messages`, { message: webSocket.message })
    await loadWebSocketMessages()
  } catch (error) { Message.error(error instanceof Error ? error.message : '发送 WebSocket 消息失败') } finally { sending.value = false }
}

async function closeWebSocket() {
  if (!projectId.value || !webSocket.session) return
  sending.value = true
  try {
    webSocket.session = await mutate<WebSocketSession>(`/api/v1/projects/${projectId.value}/api-debug/websocket/sessions/${webSocket.session.id}/close`, {})
    await loadWebSocketMessages()
    stopWebSocketPolling()
    Message.success('WebSocket 已关闭')
  } catch (error) { Message.error(error instanceof Error ? error.message : '关闭 WebSocket 失败') } finally { sending.value = false }
}

function addSubscription() {
  const value = webSocket.subscriptionDraft.trim()
  if (!value) { Message.warning('请输入订阅帧内容'); return }
  if (webSocket.subscriptions.length >= 12) { Message.warning('当前会话最多保存 12 条订阅帧'); return }
  if (value.length > 32000) { Message.warning('单条订阅帧不能超过 32000 字符'); return }
  webSocket.subscriptions.push(value)
  webSocket.subscriptionDraft = ''
}

function removeSubscription(index: number) {
  webSocket.subscriptions.splice(index, 1)
}

function suggestedInterfaceName() {
  try {
    const parsed = new URL(url.value)
    const parts = parsed.pathname.split('/').filter(Boolean)
    const resource = decodeURIComponent(parts.at(-1) ?? parsed.hostname)
    return `${method.value.toUpperCase()} ${resource}`
  } catch {
    return `${method.value.toUpperCase()} ${url.value.trim()}`.slice(0, 200)
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

function loadAuth(source: unknown, target: { type: string; token: string; username: string; password: string; key: string; value: string; placement?: string }) {
  const value = asRecord(source)
  if (!value) return
  Object.assign(target, {
    type: ['NONE', 'BEARER', 'BASIC', 'API_KEY'].includes(text(value.type)) ? text(value.type) : 'NONE',
    token: text(value.token), username: text(value.username), password: text(value.password), key: text(value.key, target.key),
    value: text(value.value), ...(target.placement !== undefined ? { placement: text(value.placement) === 'QUERY' ? 'QUERY' : 'HEADER' } : {}),
  })
}

function loadHeaders(source: unknown): string {
  return Array.isArray(source) ? source.map((item) => {
    const header = asRecord(item)
    return header ? `${text(header.name)}: ${text(header.value)}` : ''
  }).filter(Boolean).join('\n') : ''
}

function applyStoredReplayDraft(serialized: string): boolean {
  try {
    const saved = asRecord(JSON.parse(serialized))
    const request = saved && asRecord(saved.request)
    const type = text(saved?.protocol).toUpperCase()
    if (!request || !['HTTP', 'GRAPHQL', 'SOAP', 'WEBSOCKET', 'GRPC'].includes(type)) return false
    quickRequestName.value = text(request.name, '快捷请求') || '快捷请求'
    protocolType.value = type as typeof protocolType.value
    method.value = text(request.method, type === 'HTTP' ? 'GET' : 'POST')
    environment.value = text(request.environmentCode)
    if (type === 'GRPC') {
      url.value = text(request.endpoint)
      grpc.tls = Boolean(request.tls)
      grpc.service = text(request.service)
      grpc.method = text(request.method)
      grpc.requestJson = text(request.requestJson, '{}')
      grpc.timeoutMs = Number(request.timeoutMs) || 10_000
      grpcMetadataText.value = loadHeaders(request.metadata).replaceAll(': ', ': ')
      loadAuth(request.auth, grpcAuth)
    } else if (type === 'WEBSOCKET') {
      url.value = text(request.url)
      headersText.value = loadHeaders(request.headers)
      loadAuth(request.auth, auth)
      webSocket.subprotocolsText = Array.isArray(request.subprotocols) ? request.subprotocols.filter((item): item is string => typeof item === 'string').join(', ') : ''
      webSocket.subscriptions = Array.isArray(request.subscriptions) ? request.subscriptions.filter((item): item is string => typeof item === 'string') : []
      const reconnect = asRecord(request.reconnect)
      webSocket.autoReconnect = Boolean(reconnect?.enabled)
      webSocket.maxReconnectAttempts = Number(reconnect?.maxAttempts) || 3
      webSocket.reconnectDelayMs = Number(reconnect?.delayMs) || 1000
    } else {
      url.value = text(request.url)
      headersText.value = loadHeaders(request.headers)
      body.value = text(request.body)
      const savedTimeout = Number(request.timeoutMs)
      timeoutMs.value = Number.isInteger(savedTimeout) && savedTimeout >= 500 && savedTimeout <= 10_000 ? savedTimeout : 10_000
      loadAuth(request.auth, auth)
      const protocol = asRecord(request.protocol)
      if (type === 'GRAPHQL') {
        graphql.query = text(protocol?.query)
        graphql.variables = text(protocol?.variables, '{}')
        graphql.operationName = text(protocol?.operationName)
      }
      if (type === 'SOAP') {
        soap.envelope = text(protocol?.envelope)
        soap.action = text(protocol?.soapAction)
      }
    }
    changeProtocol()
    if (type === 'HTTP') hydrateQuickHttpEditor()
    return true
  } catch {
    return false
  }
}

function loadHistoryRequest(item: History, notify = true): boolean {
  const draft = apiDebugDraftFromHistory(item)
  if (draft?.replayDraft && applyStoredReplayDraft(draft.replayDraft)) {
    result.value = null
    responseBody.value = '已载入加密保存的请求配置，可检查参数后再次发送。'
    if (notify) Message.success('已载入历史请求，可检查参数后再次发送')
    return true
  }
  if (!draft) {
    if (notify) Message.warning('该历史记录没有可回放的请求配置')
    return false
  }
  protocolType.value = 'HTTP'
  quickRequestName.value = '快捷请求'
  changeProtocol()
  method.value = draft.method ?? 'GET'
  url.value = draft.url ?? ''
  requestTab.value = 'body'
  result.value = null
  hydrateQuickHttpEditor()
  responseBody.value = '已载入历史请求。历史仅保存脱敏的 Method 与 URL，重发会沿用当前 Header、Auth 与 Body。'
  if (notify) Message.success('已载入历史请求，可检查参数后再次发送')
  return true
}

async function replayHistoryRequest(item: History) {
  if (!loadHistoryRequest(item, false)) {
    Message.warning('该历史记录没有可回放的请求配置')
    return
  }
  if (isRedactedApiDebugUrl(item.requestUrl)) {
    Message.warning('该历史 URL 含已脱敏参数，请补充真实参数后再发送')
    return
  }
  replayingHistoryId.value = item.id
  try {
    await send()
  } finally {
    replayingHistoryId.value = ''
  }
}

async function saveQuickRequest() {
  if (!projectId.value || !url.value.trim()) {
    Message.warning('请先输入完整的请求 URL')
    return
  }
  savingDraft.value = true
  try {
    await mutate<History>(`/api/v1/projects/${projectId.value}/api-debug/drafts`, quickRequestPayload())
    await loadHistory()
    Message.success('快捷请求草稿已加密保存')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '保存快捷请求失败')
  } finally {
    savingDraft.value = false
  }
}

function startQuickRequestNameEdit() {
  quickRequestNameDraft.value = quickRequestName.value
  editingQuickRequestName.value = true
  void nextTick(() => quickRequestNameInput.value?.focus())
}

function commitQuickRequestName() {
  if (!editingQuickRequestName.value) return
  const normalized = quickRequestNameDraft.value.trim().slice(0, 128)
  quickRequestName.value = normalized || quickRequestName.value || '快捷请求'
  quickRequestNameDraft.value = quickRequestName.value
  editingQuickRequestName.value = false
}

function cancelQuickRequestNameEdit() {
  quickRequestNameDraft.value = quickRequestName.value
  editingQuickRequestName.value = false
}

async function copyRequestCode() {
  try {
    const curl = buildApiDebugCurlCommand({ method: method.value, url: applyQuickRequestQuery(url.value, queryRows.value), headers: headers(), body: quickRequestBody(), auth: { ...auth } })
    generatedCode.value = curl.command
    generatedCodeDialog.value = true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '生成 cURL 失败')
  }
}

async function copyGeneratedCode() {
  if (!navigator.clipboard) return
  try { await navigator.clipboard.writeText(generatedCode.value); Message.success('cURL 已复制') } catch { Message.warning('浏览器未授予剪贴板权限') }
}

async function openSaveInterface() {
  if (protocolType.value !== 'HTTP') {
    Message.warning('当前仅支持将 HTTP 快捷请求保存为正式接口')
    return
  }
  if (!catalogs.value.length) {
    try {
      await loadCatalogs()
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '加载接口目录失败')
      return
    }
  }
  if (!catalogOptions.value.length) {
    Message.warning('请先在接口管理中创建接口目录')
    return
  }
  clearFormErrors(saveInterfaceErrors)
  saveInterfaceForm.catalogId = catalogOptions.value[0].id
  saveInterfaceForm.name = suggestedInterfaceName()
  saveInterfaceDialog.value = true
}

async function saveAsInterface() {
  clearFormErrors(saveInterfaceErrors)
  if (!saveInterfaceForm.catalogId) saveInterfaceErrors.catalogId = '请选择接口目录'
  if (!saveInterfaceForm.name.trim()) saveInterfaceErrors.name = '请输入接口名称'
  if (!url.value.trim()) saveInterfaceErrors.form = '请输入请求 URL'
  if (Object.keys(saveInterfaceErrors).length) return false

  let requestHeaders: Header[]
  try {
    requestHeaders = headers()
  } catch (error) {
    saveInterfaceErrors.form = error instanceof Error ? error.message : '请求头格式不合法'
    return false
  }

  savingInterface.value = true
  try {
    const curl = buildApiDebugCurlCommand({
      method: method.value,
      url: url.value,
      headers: requestHeaders,
      body: quickRequestBody(),
      auth: { ...auth },
    })
    const imported = await mutate<ImportResult>(
      `/api/v1/projects/${projectId.value}/api-design/interchange/curl/import`,
      { catalogId: saveInterfaceForm.catalogId, name: saveInterfaceForm.name.trim(), command: curl.command },
    )
    saveInterfaceDialog.value = false
    Message.success(`已保存为正式接口：${imported.items[0] ?? saveInterfaceForm.name.trim()}`)
    if (curl.secretsRemoved) Message.info('敏感值已替换为环境变量占位符，未写入接口资产')
    return true
  } catch (error) {
    Object.assign(saveInterfaceErrors, formErrorsFrom(error))
    if (!Object.keys(saveInterfaceErrors).length) saveInterfaceErrors.form = error instanceof Error ? error.message : '保存正式接口失败'
    return false
  } finally {
    savingInterface.value = false
  }
}

function clearResponse() {
  result.value = null
  responseBody.value = '响应已清空。'
}

async function copyResponse() {
  if (!result.value || !navigator.clipboard) return
  try { await navigator.clipboard.writeText(responseBody.value); Message.success('响应已复制') } catch { Message.warning('浏览器未授予剪贴板权限') }
}

onMounted(load)
onBeforeUnmount(() => {
  stopWebSocketPolling()
  stopGrpcStreamPolling()
  stopGrpcClientStreamPolling()
  stopGrpcBidiStreamPolling()
})
</script>

<template>
  <div class="page api-debug-page" :class="{ 'api-debug-page--embedded': props.embedded }">
    <nav v-if="!props.embedded" class="quick-workspace-tabbar" aria-label="接口测试工作区标签">
      <router-link to="/api-testing/interfaces" class="quick-workspace-tab"><icon-book /><span>接口管理</span></router-link>
      <span class="quick-workspace-tab active"><icon-thunderbolt /><span>快捷请求</span></span>
    </nav>
    <PageHeader v-if="!props.embedded" eyebrow="API TESTING WORKBENCH" title="快捷请求与高级调试" description="作为接口测试模块的临时请求工作台，可从正式接口载入运行草稿，并继续调试 HTTP、GraphQL、SOAP、WebSocket 与 gRPC 请求。" action="清空响应" @action="clearResponse" />
    <section v-if="!props.embedded" class="debug-summary panel">
      <div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div>
      <a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select>
      <div><span>安全策略</span><strong>受限网络</strong><small>HTTP 内网按 Origin 放行</small></div>
      <div><span>请求历史</span><strong>{{ history.length }}</strong><small>仅保存脱敏摘要</small></div>
    </section>
    <a-spin :loading="loading">
      <section class="debug-workspace" :class="{ 'debug-workspace--embedded': props.embedded }">
        <aside v-if="!props.embedded" class="request-tree">
          <div class="tree-title"><div><strong>最近请求</strong><small>项目级执行历史</small></div><a-button size="mini" aria-label="刷新最近请求" @click="loadHistory"><icon-refresh /></a-button></div>
          <div v-if="history.length" class="history-list">
            <div v-for="item in history" :key="item.id" class="history-item">
              <div><a-tag size="small" :color="item.method === 'GET' ? 'green' : item.method === 'POST' ? 'arcoblue' : 'orange'">{{ item.method }}</a-tag><a-tag size="small" :color="item.result === 'DRAFT' ? 'orange' : item.result === 'COMPLETED' && ((item.responseStatus ?? 500) < 400 || item.protocol === 'WEBSOCKET') ? 'green' : 'red'">{{ item.result === 'DRAFT' ? '草稿' : item.protocol === 'WEBSOCKET' && item.result === 'COMPLETED' ? '已连接' : item.responseStatus ?? '失败' }}</a-tag></div>
              <code :title="item.requestUrl">{{ item.requestUrl }}</code>
              <small>{{ item.durationMs }} ms · {{ date(item.createdAt) }}</small>
              <div class="history-actions">
                <a-button size="mini" :disabled="sending" @click="loadHistoryRequest(item)">载入</a-button>
                <a-button size="mini" type="primary" :loading="replayingHistoryId === item.id" :disabled="sending" @click="replayHistoryRequest(item)">重发</a-button>
              </div>
            </div>
          </div>
          <a-empty v-else description="还没有调试记录" />
        </aside>
        <div class="request-editor">
          <header v-if="props.embedded" class="embedded-request-tabbar">
            <div class="request-title">
              <icon-thunderbolt />
              <a-input
                v-if="editingQuickRequestName"
                ref="quickRequestNameInput"
                v-model="quickRequestNameDraft"
                class="quick-request-name-editor"
                size="small"
                :max-length="128"
                aria-label="快捷请求名称"
                @press-enter="commitQuickRequestName"
                @blur="commitQuickRequestName"
                @keydown.esc.stop.prevent="cancelQuickRequestNameEdit"
              />
              <button
                v-else
                type="button"
                class="quick-request-name"
                title="双击修改快捷请求名称"
                aria-label="快捷请求名称，双击修改"
                @dblclick="startQuickRequestNameEdit"
                @keydown.enter.prevent="startQuickRequestNameEdit"
              >{{ quickRequestName }}</button>
            </div>
            <div><a-button size="small" type="text" aria-label="生成 cURL" @click="copyRequestCode"><icon-code /></a-button><a-button size="small" type="text" aria-label="清空响应" @click="clearResponse"><icon-delete /></a-button></div>
          </header>
          <div class="request-bar" :class="{ 'request-bar--saveable': protocolType === 'HTTP' }">
            <a-select v-if="protocolType !== 'WEBSOCKET' && protocolType !== 'GRPC'" v-model="method" :style="{ width: '108px' }"><a-option v-for="item in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']" :key="item">{{ item }}</a-option></a-select>
            <a-tag v-else :color="protocolType === 'GRPC' ? 'orange' : 'purple'" class="protocol-method">{{ protocolType === 'GRPC' ? 'gRPC' : 'WS' }}</a-tag>
            <a-input v-model="url" :placeholder="protocolType === 'WEBSOCKET' ? 'wss://ws.postman-echo.com/raw' : protocolType === 'GRPC' ? 'grpc.example.com:443' : '输入 http 或 https 开头的完整 URL'" @blur="syncQueryFromUrl" />
            <div class="send-actions"><a-button type="primary" :loading="sending" @click="send">{{ protocolType === 'WEBSOCKET' ? '连接' : protocolType === 'GRPC' ? '调用' : '发送' }} <icon-send /></a-button><a-dropdown v-if="protocolType === 'HTTP'"><a-button type="primary" aria-label="更多发送操作"><icon-down /></a-button><template #content><a-doption @click="send">发送请求</a-doption><a-doption @click="saveQuickRequest">仅保存草稿</a-doption></template></a-dropdown></div>
            <div v-if="protocolType === 'HTTP'" class="save-actions">
              <a-button :loading="savingDraft" :disabled="sending" @click="saveQuickRequest"><icon-save /> 保存</a-button>
              <a-button :disabled="sending" @click="openSaveInterface">保存为接口</a-button>
            </div>
          </div>
          <div class="environment-bar">
            <a-select v-model="protocolType" size="small" :style="{ width: '128px' }" @change="changeProtocol"><a-option value="HTTP">HTTP</a-option><a-option value="GRAPHQL">GraphQL</a-option><a-option value="SOAP">SOAP</a-option><a-option value="WEBSOCKET">WebSocket</a-option><a-option value="GRPC">gRPC</a-option></a-select>
            <a-select v-model="environment" size="small" allow-clear placeholder="不使用环境" :style="{ width: '210px' }" @change="changeEnvironment"><a-option v-for="item in environments" :key="item" :value="item" :disabled="projectEnvironments.find((candidate) => candidate.environmentCode === item)?.enabled === false">{{ projectEnvironments.find((candidate) => candidate.environmentCode === item)?.name ?? item }} · {{ item }}</a-option></a-select>
            <a-tag color="arcoblue">{{ environment || '无环境' }}</a-tag><span>变量 {{ variables.length }}</span><span>密钥 {{ secrets.length }}</span><span>{{ protocolType === 'WEBSOCKET' ? '消息上限 32 KB' : protocolType === 'GRPC' ? grpcSelectedCallType === 'SERVER_STREAMING' ? '服务端流 · 100 条 / 512 KB' : grpcSelectedCallType === 'CLIENT_STREAMING' ? '客户端流 · 100 条 / 512 KB' : grpcSelectedCallType === 'BIDI_STREAMING' ? '双向流 · 双向各 100 条 / 512 KB' : 'Unary · 响应上限 512 KB' : `超时 ${timeoutMs / 1000} 秒` }}</span>
            <a-button type="text" size="small" @click="router.push('/system-settings/environments')"><icon-settings /> 管理环境</a-button>
            <a-button type="text" size="small" @click="Message.info('当前 gRPC 支持在线反射或离线 Proto/DescriptorSet 发现、Unary、服务端流、客户端流与双向流；流式调用仅保留当前内存会话，每个方向均受 100 条消息与 512 KB 上限约束。')">查看边界</a-button>
          </div>
          <a-tabs v-if="protocolType === 'HTTP'" v-model:active-key="requestTab" class="request-tabs quick-http-tabs">
            <a-tab-pane key="params" :title="`Params ${activeQueryCount || ''}`">
              <div class="quick-table-title">Query 参数 <small>修改后自动同步到 URL</small></div>
              <div class="quick-kv-table">
                <div class="quick-kv-row quick-kv-head"><span></span><strong>参数名</strong><strong>参数值</strong><strong>说明</strong><span></span></div>
                <div v-for="(row, index) in queryRows" :key="row.id" class="quick-kv-row"><a-checkbox v-model="row.enabled" @change="syncUrlFromQuery" /><a-input v-model="row.name" placeholder="添加参数" @blur="syncUrlFromQuery" /><a-input v-model="row.value" placeholder="参数值" @blur="syncUrlFromQuery" /><a-input v-model="row.description" placeholder="说明（可选）" /><a-button type="text" status="danger" aria-label="删除参数" @click="removeQuickRow(queryRows, index)"><icon-delete /></a-button></div>
              </div>
              <a-button size="small" type="text" @click="addQuickRow(queryRows)"><icon-plus /> 添加参数</a-button>
            </a-tab-pane>
            <a-tab-pane key="body" :title="quickBodyType === 'NONE' ? 'Body' : 'Body 1'">
              <div class="body-type-tabs"><a-radio-group v-model="quickBodyType" type="button"><a-radio value="NONE">none</a-radio><a-radio value="FORM_DATA">form-data</a-radio><a-radio value="URLENCODED">x-www-form-urlencoded</a-radio><a-radio value="JSON">JSON</a-radio><a-radio value="XML">XML</a-radio><a-radio value="TEXT">Text</a-radio></a-radio-group></div>
              <a-empty v-if="quickBodyType === 'NONE'" description="当前请求不发送请求体" />
              <template v-else-if="quickBodyType === 'FORM_DATA' || quickBodyType === 'URLENCODED'">
                <div class="quick-kv-table"><div class="quick-kv-row quick-kv-head"><span></span><strong>参数名</strong><strong>参数值</strong><strong>说明</strong><span></span></div><div v-for="(row, index) in formBodyRows" :key="row.id" class="quick-kv-row"><a-checkbox v-model="row.enabled" /><a-input v-model="row.name" placeholder="字段名" /><a-input v-model="row.value" placeholder="字段值" /><a-input v-model="row.description" placeholder="说明（可选）" /><a-button type="text" status="danger" aria-label="删除 Body 参数" @click="removeQuickRow(formBodyRows, index)"><icon-delete /></a-button></div></div>
                <a-button size="small" type="text" @click="addQuickRow(formBodyRows)"><icon-plus /> 添加字段</a-button>
              </template>
              <CodeEditor v-else v-model="body" :language="quickBodyType === 'XML' ? 'xml' : quickBodyType === 'TEXT' ? 'plaintext' : 'json'" :height="245" />
            </a-tab-pane>
            <a-tab-pane key="headers" :title="`Headers ${activeHeaderCount || ''}`"><div class="quick-table-title">请求头 <small>Content-Type 会根据 Body 类型自动补充</small></div><div class="quick-kv-table"><div class="quick-kv-row quick-kv-head"><span></span><strong>Header 名</strong><strong>Header 值</strong><strong>说明</strong><span></span></div><div v-for="(row, index) in headerRows" :key="row.id" class="quick-kv-row"><a-checkbox v-model="row.enabled" /><a-input v-model="row.name" placeholder="Header 名" /><a-input v-model="row.value" placeholder="Header 值" /><a-input v-model="row.description" placeholder="说明（可选）" /><a-button type="text" status="danger" aria-label="删除 Header" @click="removeQuickRow(headerRows, index)"><icon-delete /></a-button></div></div><a-button size="small" type="text" @click="addQuickRow(headerRows)"><icon-plus /> 添加 Header</a-button></a-tab-pane>
            <a-tab-pane key="cookies" :title="`Cookies ${activeCookieCount || ''}`"><div class="quick-table-title">Cookie <small>启用项会合并为 Cookie 请求头</small></div><div class="quick-kv-table"><div class="quick-kv-row quick-kv-head"><span></span><strong>名称</strong><strong>值</strong><strong>说明</strong><span></span></div><div v-for="(row, index) in cookieRows" :key="row.id" class="quick-kv-row"><a-checkbox v-model="row.enabled" /><a-input v-model="row.name" placeholder="Cookie 名称" /><a-input v-model="row.value" placeholder="Cookie 值" /><a-input v-model="row.description" placeholder="说明（可选）" /><a-button type="text" status="danger" aria-label="删除 Cookie" @click="removeQuickRow(cookieRows, index)"><icon-delete /></a-button></div></div><a-button size="small" type="text" @click="addQuickRow(cookieRows)"><icon-plus /> 添加 Cookie</a-button></a-tab-pane>
            <a-tab-pane key="auth" title="Auth"><div class="editor-caption">认证值只在服务端执行期解析，不会回显；使用 <code>&#123;&#123;API_TOKEN&#125;&#125;</code> 可引用环境密钥。</div><a-form :model="auth" layout="vertical" class="auth-form"><a-form-item label="认证类型"><a-select v-model="auth.type"><a-option value="NONE">无认证</a-option><a-option value="BEARER">Bearer Token</a-option><a-option value="BASIC">Basic Auth</a-option><a-option value="API_KEY">API Key</a-option></a-select></a-form-item><a-form-item v-if="auth.type === 'BEARER'" label="Token"><a-input-password v-model="auth.token" placeholder="{{API_TOKEN}}" /></a-form-item><template v-if="auth.type === 'BASIC'"><div class="auth-grid"><a-form-item label="用户名"><a-input v-model="auth.username" /></a-form-item><a-form-item label="密码"><a-input-password v-model="auth.password" /></a-form-item></div></template><template v-if="auth.type === 'API_KEY'"><div class="auth-grid"><a-form-item label="参数名称"><a-input v-model="auth.key" /></a-form-item><a-form-item label="放置位置"><a-select v-model="auth.placement"><a-option value="HEADER">Header</a-option><a-option value="QUERY">Query</a-option></a-select></a-form-item></div><a-form-item label="参数值"><a-input-password v-model="auth.value" /></a-form-item></template></a-form></a-tab-pane>
            <a-tab-pane key="pre" title="前置操作"><div class="operation-card"><div><strong>动态请求 ID</strong><span>发送前自动注入 <code>X-Request-ID: &#123;&#123;$randomUUID&#125;&#125;</code>，方便跨服务追踪。</span></div><a-switch v-model="injectRequestId" /></div><a-alert type="info">为保证企业私有化环境安全，本节点提供声明式前置操作，不在控制面执行用户脚本。</a-alert></a-tab-pane>
            <a-tab-pane key="post" title="后置操作"><div class="post-form"><a-form-item label="期望状态码"><a-input-number v-model="expectedStatus" allow-clear :min="100" :max="599" placeholder="例如：200" /></a-form-item><a-form-item label="响应正文包含"><a-input v-model="expectedBodyContains" placeholder="例如：success" /></a-form-item></div><div v-if="postOperationChecks.length" class="post-checks"><article v-for="check in postOperationChecks" :key="check.label" :class="{ failed: !check.passed }"><icon-check-circle v-if="check.passed" /><icon-close-circle v-else /><div><strong>{{ check.label }}</strong><span>{{ check.detail }}</span></div></article></div><a-empty v-else description="发送后将在此展示校验结果" /></a-tab-pane>
            <a-tab-pane key="settings" title="设置"><a-form :model="{ timeoutMs }" layout="vertical" class="settings-form"><a-form-item label="请求超时（毫秒）"><a-input-number v-model="timeoutMs" :min="500" :max="10000" :step="500" /><template #extra>允许范围 500–10000 ms</template></a-form-item><a-form-item label="重定向"><a-switch :model-value="false" disabled /><template #extra>安全策略固定禁止自动重定向，避免目标绕过网络边界。</template></a-form-item><a-form-item label="最大响应"><span>512 KB，超过后自动截断</span></a-form-item></a-form></a-tab-pane>
          </a-tabs>
          <a-tabs v-else v-model:active-key="requestTab" class="request-tabs">
            <a-tab-pane key="body" :title="protocolType === 'GRAPHQL' ? 'GraphQL' : protocolType === 'SOAP' ? 'SOAP' : protocolType === 'WEBSOCKET' ? 'WebSocket' : protocolType === 'GRPC' ? `gRPC ${grpcSelectedCallType === 'SERVER_STREAMING' ? '服务端流' : grpcSelectedCallType === 'CLIENT_STREAMING' ? '客户端流' : grpcSelectedCallType === 'BIDI_STREAMING' ? '双向流' : 'Unary'}` : 'Body'">
              <template v-if="protocolType === 'GRAPHQL'"><div class="editor-caption">GraphQL 以 JSON 请求体发送，系统自动补充 <code>Content-Type: application/json</code>。GraphQL 仅支持 POST。</div><a-form :model="graphql" layout="vertical" class="protocol-form"><a-form-item label="Operation Name（可选）"><a-input v-model="graphql.operationName" placeholder="例如：FindOrder" /></a-form-item><a-form-item label="Query" required><CodeEditor v-model="graphql.query" language="graphql" :height="180" /></a-form-item><a-form-item label="Variables（JSON 对象）"><CodeEditor v-model="graphql.variables" :height="150" /></a-form-item></a-form></template>
              <template v-else-if="protocolType === 'SOAP'"><div class="editor-caption">SOAP 1.1 以 XML Envelope 发送，系统自动补充 <code>Content-Type: text/xml</code>。SOAP 仅支持 POST。</div><a-form :model="soap" layout="vertical" class="protocol-form"><a-form-item label="SOAPAction（可选）"><a-input v-model="soap.action" placeholder="例如：urn:OrderService/Ping" /></a-form-item><a-form-item label="SOAP Envelope" required><CodeEditor v-model="soap.envelope" language="xml" :height="270" /></a-form-item></a-form></template>
              <template v-else-if="protocolType === 'GRPC'">
                <div class="editor-caption">使用在线服务端反射，或导入项目级 <code>.proto</code> / FileDescriptorSet 后离线发现服务与方法；两种来源都会把 JSON 转为 Protobuf 请求。离线描述符不会向目标发送反射请求；本节点支持 Unary、受限服务端流、客户端流与双向流。</div>
                <section class="descriptor-panel">
                  <div class="descriptor-head"><div><strong>描述符来源</strong><small>未选择时使用在线反射；导入后仅保存已校验的编译描述符集，不保存原始请求、认证或响应。</small></div><a-tag :color="grpcDescriptor.selectedId ? 'green' : 'arcoblue'">{{ grpcDescriptor.selectedId ? '离线描述符' : '在线反射' }}</a-tag></div>
                  <div class="descriptor-select-row"><a-select v-model="grpcDescriptor.selectedId" allow-clear placeholder="在线反射（默认）" @change="changeGrpcDescriptor"><a-option v-for="item in grpcDescriptor.items" :key="item.id" :value="item.id">{{ item.name }} · {{ item.serviceCount }} 服务 · {{ item.sourceFormat === 'PROTO3' ? 'Proto3' : 'DescriptorSet' }}</a-option></a-select><a-popconfirm content="删除后无法恢复该项目的离线描述符，是否继续？" @ok="deleteGrpcDescriptor"><a-button status="danger" :disabled="!grpcDescriptor.selectedId">删除</a-button></a-popconfirm></div>
                  <div class="descriptor-import-row"><a-input v-model="grpcDescriptor.name" :max-length="128" placeholder="描述符名称，例如：订单服务 Proto" /><label class="proto-file-input"><input type="file" accept=".proto,.desc,.pb,.bin" multiple @change="selectGrpcFiles" /><span>{{ grpcDescriptor.files.length ? `已选 ${grpcDescriptor.files.length} 个文件` : '选择 Proto 文件' }}</span></label><a-button :loading="grpcDescriptor.importing" @click="importGrpcDescriptor">导入</a-button></div>
                  <small class="descriptor-help">支持最多 16 个总计 512 KB 的 <code>.proto</code> 文件（可解析同批次平铺文件的 import），或一个 protoc 生成的 <code>FileDescriptorSet</code>（.desc/.pb）。</small>
                </section>
                <a-form :model="grpc" layout="vertical" class="protocol-form">
                  <div class="auth-grid"><a-form-item label="服务" required><div class="discovery-field"><a-input v-model="grpc.service" placeholder="例如：example.v1.OrderService" /><a-button :loading="grpcDiscovery.loading" @click="discoverGrpcServices">发现服务</a-button></div><a-select v-if="grpcDiscovery.services.length" v-model="grpc.service" class="discovery-select" allow-search placeholder="从已发现服务中选择" @change="grpcDiscovery.methods = []"><a-option v-for="item in grpcDiscovery.services" :key="item" :value="item">{{ item }}</a-option></a-select></a-form-item><a-form-item label="方法" required><div class="discovery-field"><a-input v-model="grpc.method" placeholder="例如：GetOrder" /><a-button :loading="grpcDiscovery.loading" @click="discoverGrpcMethods">发现方法</a-button></div><a-select v-if="grpcDiscovery.methods.length" v-model="grpc.method" class="discovery-select" allow-search placeholder="从已发现方法中选择"><a-option v-for="item in grpcDiscovery.methods" :key="item.name" :value="item.name">{{ item.name }} · {{ grpcCallType(item.callType) }}</a-option></a-select><small v-if="grpcDiscovery.methods.some((item) => item.callType !== 'UNARY')" class="discovery-tip">服务端流、客户端流与双向流均可直接调用；双向流会同时展示发送进度与实时响应。</small></a-form-item></div>
                  <div class="auth-grid"><a-form-item label="传输安全"><a-checkbox v-model="grpc.tls">使用 TLS</a-checkbox></a-form-item><a-form-item label="超时（毫秒）"><a-input-number v-model="grpc.timeoutMs" :min="500" :max="['CLIENT_STREAMING', 'BIDI_STREAMING'].includes(grpcSelectedCallType) ? 60000 : 10000" :step="500" /></a-form-item></div><a-form-item :label="['CLIENT_STREAMING', 'BIDI_STREAMING'].includes(grpcSelectedCallType) ? '待提交消息 JSON' : '请求 JSON'" required><CodeEditor v-model="grpc.requestJson" :height="245" /></a-form-item>
                </a-form>
                <section v-if="grpcStream.session || grpcSelectedCallType === 'SERVER_STREAMING'" class="grpc-stream-panel">
                  <div class="grpc-stream-head"><div><strong>服务端流消息</strong><a-tag :color="grpcStreamStatusColor">{{ grpcStream.session?.status ?? '未启动' }}</a-tag><small v-if="grpcStream.session">{{ grpcStream.session.target }} · {{ grpcStream.session.messageCount }}/100 条 · {{ grpcStream.session.responseBytes }} / 524288 bytes</small></div><div><a-button size="small" :disabled="!grpcStream.session" @click="loadGrpcStreamMessages">刷新消息</a-button><a-button size="small" status="danger" :loading="sending" :disabled="grpcStream.session?.status !== 'OPEN'" @click="cancelGrpcServerStream">取消</a-button></div></div>
                  <div v-if="grpcStream.session?.closeReason" class="grpc-stream-close">{{ grpcStream.session.closeReason }}</div>
                  <div v-if="grpcStream.messages.length" class="grpc-stream-messages"><article v-for="item in grpcStream.messages" :key="item.id" class="grpc-stream-message"><div><a-tag size="small" color="green">收到</a-tag><small>{{ date(item.createdAt) }} · {{ item.sizeBytes }} bytes<template v-if="item.truncated"> · 已截断</template></small></div><pre>{{ item.payload }}</pre></article></div>
                  <a-empty v-else :description="grpcStream.session ? '正在等待服务端消息' : '选择服务端流方法后，点击调用开始接收消息'" />
                </section>
                <section v-if="grpcClientStream.session || grpcSelectedCallType === 'CLIENT_STREAMING'" class="grpc-stream-panel grpc-client-stream-panel">
                  <div class="grpc-stream-head"><div><strong>客户端流控制</strong><a-tag :color="grpcClientStreamStatusColor">{{ grpcClientStream.session?.status ?? '未建立' }}</a-tag><small v-if="grpcClientStream.session">{{ grpcClientStream.session.target }} · 已发送 {{ grpcClientStream.session.sentMessageCount }}/100 条 · {{ grpcClientStream.session.sentBytes }} / 524288 bytes</small></div><div><a-button size="small" :disabled="!grpcClientStream.session" @click="loadGrpcClientStream">刷新状态</a-button><a-button size="small" type="primary" :loading="sending" :disabled="grpcClientStream.session?.status !== 'OPEN'" @click="sendGrpcClientStreamMessage">提交当前消息</a-button><a-button size="small" :loading="sending" :disabled="grpcClientStream.session?.status !== 'OPEN'" @click="completeGrpcClientStream">完成发送</a-button><a-button size="small" status="danger" :loading="sending" :disabled="!grpcClientStream.session || !['OPEN', 'HALF_CLOSED'].includes(grpcClientStream.session.status)" @click="cancelGrpcClientStream">取消</a-button></div></div>
                  <div v-if="grpcClientStream.session?.closeReason" class="grpc-stream-close">{{ grpcClientStream.session.closeReason }}</div>
                  <div class="client-stream-help">先点击顶部“调用”建立客户端流，再反复编辑并提交上方 JSON 消息；点击“完成发送”后会半关闭请求方向并等待服务端的最终响应。消息和响应仅保存在当前内存会话。</div>
                  <article v-if="grpcClientStream.session?.responseBody" class="grpc-stream-message"><div><a-tag size="small" color="green">最终响应</a-tag><small>{{ grpcClientStream.session.responseBytes }} bytes<template v-if="grpcClientStream.session.responseTruncated"> · 已截断</template></small></div><pre>{{ grpcClientStream.session.responseBody }}</pre></article>
                  <a-empty v-else :description="grpcClientStream.session ? grpcClientStream.session.status === 'HALF_CLOSED' ? '已完成发送，正在等待服务端最终响应' : '客户端流已建立，可提交上方 JSON 消息' : '选择客户端流方法后，点击调用建立会话'" />
                </section>
                <section v-if="grpcBidiStream.session || grpcSelectedCallType === 'BIDI_STREAMING'" class="grpc-stream-panel grpc-bidi-stream-panel">
                  <div class="grpc-stream-head"><div><strong>双向流控制</strong><a-tag :color="grpcBidiStreamStatusColor">{{ grpcBidiStream.session?.status ?? '未建立' }}</a-tag><small v-if="grpcBidiStream.session">{{ grpcBidiStream.session.target }} · 发送 {{ grpcBidiStream.session.sentMessageCount }}/100 条 · 接收 {{ grpcBidiStream.session.receivedMessageCount }}/100 条 · {{ grpcBidiStream.session.responseBytes }} / 524288 bytes</small></div><div><a-button size="small" :disabled="!grpcBidiStream.session" @click="loadGrpcBidiStream">刷新状态</a-button><a-button size="small" type="primary" :loading="sending" :disabled="grpcBidiStream.session?.status !== 'OPEN'" @click="sendGrpcBidiStreamMessage">提交当前消息</a-button><a-button size="small" :loading="sending" :disabled="grpcBidiStream.session?.status !== 'OPEN'" @click="completeGrpcBidiStream">完成发送</a-button><a-button size="small" status="danger" :loading="sending" :disabled="!grpcBidiStream.session || !['OPEN', 'HALF_CLOSED'].includes(grpcBidiStream.session.status)" @click="cancelGrpcBidiStream">取消</a-button></div></div>
                  <div v-if="grpcBidiStream.session?.closeReason" class="grpc-stream-close">{{ grpcBidiStream.session.closeReason }}</div>
                  <div class="client-stream-help">先点击顶部“调用”建立双向流；每次提交上方 JSON 后会继续接收服务端消息。点击“完成发送”只半关闭客户端方向，服务端仍可继续推送响应；所有双向消息只保留在当前内存会话。</div>
                  <div v-if="grpcBidiStream.messages.length" class="grpc-stream-messages"><article v-for="item in grpcBidiStream.messages" :key="item.id" class="grpc-stream-message"><div><a-tag size="small" color="green">收到</a-tag><small>{{ date(item.createdAt) }} · {{ item.sizeBytes }} bytes<template v-if="item.truncated"> · 已截断</template></small></div><pre>{{ item.payload }}</pre></article></div>
                  <a-empty v-else :description="grpcBidiStream.session ? grpcBidiStream.session.status === 'HALF_CLOSED' ? '已完成客户端发送，仍在等待服务端消息' : '双向流已建立，可提交上方 JSON 消息并等待实时响应' : '选择双向流方法后，点击调用建立会话'" />
                </section>
              </template>
              <template v-else>
                <div class="editor-caption">连接仅允许公开可访问的 <code>ws://</code> 或 <code>wss://</code> 目标。会话、认证值和文本消息仅保存在服务端内存，不会写入调试历史；连接前保存的订阅帧会在每次成功连接后自动发送。</div>
                <section class="websocket-panel">
                  <div class="websocket-head"><div><strong>连接状态</strong><a-tag :color="webSocketStatusColor">{{ webSocket.session?.status ?? '未连接' }}</a-tag><small v-if="webSocket.session">{{ webSocket.session.target }} · {{ webSocket.session.messageCount }} 条消息<template v-if="webSocket.session.autoReconnect"> · 重连 {{ webSocket.session.reconnectAttempts }}/{{ webSocket.session.maxReconnectAttempts }}</template><template v-if="webSocket.session.selectedSubprotocol"> · {{ webSocket.session.selectedSubprotocol }}</template></small></div><div><a-button size="small" :loading="sending" @click="connectWebSocket">连接</a-button><a-button size="small" status="danger" :disabled="!webSocket.session || webSocket.session.status === 'CLOSED'" @click="closeWebSocket">关闭</a-button></div></div>
                  <div v-if="webSocket.session?.closeReason" class="websocket-close">{{ webSocket.session.closeReason }}</div>
                  <div class="subscription-box"><div class="subscription-title"><strong>订阅帧</strong><small>保存到当前会话，首次连接和自动重连后均会发送；不会持久化。</small></div><a-textarea v-model="webSocket.subscriptionDraft" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'" :auto-size="{ minRows: 2, maxRows: 5 }" placeholder="例如：{&quot;type&quot;:&quot;subscribe&quot;,&quot;topic&quot;:&quot;orders&quot;}" :max-length="32000" show-word-limit /><div class="subscription-actions"><a-button size="small" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'" @click="addSubscription">保存订阅帧</a-button><span>{{ webSocket.subscriptions.length }}/12</span></div><div v-if="webSocket.subscriptions.length" class="subscription-list"><article v-for="(item, index) in webSocket.subscriptions" :key="`${index}-${item}`"><code>{{ item }}</code><a-button size="mini" status="danger" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'" @click="removeSubscription(index)">移除</a-button></article></div></div>
                  <a-textarea v-model="webSocket.message" :auto-size="{ minRows: 3, maxRows: 6 }" placeholder="输入要发送的文本消息" :max-length="32000" show-word-limit />
                  <div class="websocket-send"><a-button type="primary" :loading="sending" :disabled="webSocket.session?.status !== 'OPEN'" @click="sendWebSocketMessage">发送文本消息 <icon-send /></a-button><a-button size="small" @click="loadWebSocketMessages">刷新消息</a-button></div>
                  <div v-if="webSocket.messages.length" class="websocket-messages"><article v-for="item in webSocket.messages" :key="item.id" class="websocket-message" :class="item.direction.toLowerCase()"><div><a-tag size="small" :color="item.direction === 'IN' ? 'green' : 'arcoblue'">{{ item.direction === 'IN' ? '收到' : '发送' }}</a-tag><small>{{ date(item.createdAt) }}<template v-if="item.truncated"> · 已截断</template></small></div><pre>{{ item.payload }}</pre></article></div>
                  <a-empty v-else description="连接后即可查看实时文本消息" />
                </section>
              </template>
            </a-tab-pane>
            <a-tab-pane key="headers" :title="protocolType === 'GRPC' ? 'Metadata' : 'Headers'">
              <template v-if="protocolType !== 'GRPC'"><div class="editor-caption">每行一个请求头，格式为“名称: 值”。{{ protocolType === 'WEBSOCKET' ? 'WebSocket 的 Upgrade 与 Sec-WebSocket-* 握手头由客户端自动处理。' : 'GraphQL 与 SOAP 会补充协议 Content-Type；Host、Content-Length 等传输层请求头会被拒绝。' }}</div><a-textarea v-model="headersText" :auto-size="{ minRows: 11, maxRows: 13 }" placeholder="Content-Type: application/json" /></template>
              <template v-else><div class="editor-caption">每行一个 ASCII Metadata，格式为“名称: 值”。最多 32 项、总长度 24 KB；<code>grpc-</code> 保留前缀和 <code>-bin</code> 二进制 Metadata 不可用。Metadata 会同时用于服务反射、Unary、服务端流、客户端流与双向流调用，且不写入历史。</div><a-textarea v-model="grpcMetadataText" :auto-size="{ minRows: 11, maxRows: 13 }" placeholder="x-request-id: {{$randomUUID}}" /></template>
            </a-tab-pane>
            <a-tab-pane key="auth" :title="protocolType === 'GRPC' ? '认证' : 'Auth'">
              <template v-if="protocolType !== 'GRPC'"><div class="editor-caption">认证配置会在服务端解析并只用于当前请求或 WebSocket 握手，密钥不会回显或写入历史。</div><a-form :model="auth" layout="vertical" class="auth-form"><a-form-item label="认证类型"><a-select v-model="auth.type"><a-option value="NONE">无认证</a-option><a-option value="BEARER">Bearer Token</a-option><a-option value="BASIC">Basic Auth</a-option><a-option value="API_KEY">API Key</a-option></a-select></a-form-item><a-form-item v-if="auth.type === 'BEARER'" label="Token"><a-input-password v-model="auth.token" placeholder="{{API_TOKEN}} 或直接填写 Token" /></a-form-item><template v-if="auth.type === 'BASIC'"><a-form-item label="用户名"><a-input v-model="auth.username" placeholder="{{API_USERNAME}}" /></a-form-item><a-form-item label="密码"><a-input-password v-model="auth.password" placeholder="{{API_PASSWORD}}" /></a-form-item></template><template v-if="auth.type === 'API_KEY'"><div class="auth-grid"><a-form-item label="参数名称"><a-input v-model="auth.key" placeholder="X-API-Key" /></a-form-item><a-form-item label="放置位置"><a-select v-model="auth.placement"><a-option value="HEADER">Header</a-option><a-option value="QUERY">Query</a-option></a-select></a-form-item></div><a-form-item label="参数值"><a-input-password v-model="auth.value" placeholder="{{API_KEY}}" /></a-form-item></template></a-form></template>
              <template v-else><div class="editor-caption">认证值仅在服务端执行期解析，并作为 gRPC Metadata 附加到反射、Unary、服务端流、客户端流和双向流调用；不会回显或写入历史。API Key 仅支持 Metadata 位置。</div><a-form :model="grpcAuth" layout="vertical" class="auth-form"><a-form-item label="认证类型"><a-select v-model="grpcAuth.type"><a-option value="NONE">无认证</a-option><a-option value="BEARER">Bearer Token</a-option><a-option value="BASIC">Basic Auth</a-option><a-option value="API_KEY">API Key</a-option></a-select></a-form-item><a-form-item v-if="grpcAuth.type === 'BEARER'" label="Token"><a-input-password v-model="grpcAuth.token" placeholder="{{API_TOKEN}} 或直接填写 Token" /></a-form-item><template v-if="grpcAuth.type === 'BASIC'"><a-form-item label="用户名"><a-input v-model="grpcAuth.username" placeholder="{{API_USERNAME}}" /></a-form-item><a-form-item label="密码"><a-input-password v-model="grpcAuth.password" placeholder="{{API_PASSWORD}}" /></a-form-item></template><template v-if="grpcAuth.type === 'API_KEY'"><a-form-item label="Metadata 名称"><a-input v-model="grpcAuth.key" placeholder="x-api-key" /></a-form-item><a-form-item label="参数值"><a-input-password v-model="grpcAuth.value" placeholder="{{API_KEY}}" /></a-form-item></template></a-form></template>
            </a-tab-pane>
            <a-tab-pane v-if="protocolType === 'WEBSOCKET'" key="connection" title="连接"><div class="editor-caption">子协议由客户端生成 <code>Sec-WebSocket-Protocol</code> 握手头；自动重连仅在目标异常断开时触发，手动关闭不会重连。</div><a-form :model="webSocket" layout="vertical" class="auth-form"><a-form-item label="子协议（可选，逗号分隔）"><a-input v-model="webSocket.subprotocolsText" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'" placeholder="graphql-transport-ws, mqtt" /></a-form-item><a-form-item><a-checkbox v-model="webSocket.autoReconnect" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'">目标异常关闭时自动重连</a-checkbox></a-form-item><div v-if="webSocket.autoReconnect" class="auth-grid"><a-form-item label="最多重连次数"><a-input-number v-model="webSocket.maxReconnectAttempts" :min="1" :max="5" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'" /></a-form-item><a-form-item label="重连间隔（毫秒）"><a-input-number v-model="webSocket.reconnectDelayMs" :min="100" :max="10000" :step="100" :disabled="!!webSocket.session && webSocket.session.status !== 'CLOSED'" /></a-form-item></div></a-form></a-tab-pane>
            <a-tab-pane key="variables" title="变量"><div class="editor-caption">{{ variableCaption }}</div><a-table :data="variables" :pagination="false" size="small" row-key="key"><template #columns><a-table-column title="变量"><template #cell="{ record }"><code>{{ '{' + '{' + record.key + '}' + '}' }}</code></template></a-table-column><a-table-column title="类型"><template #cell="{ record }"><a-tag :color="record.secret ? 'orange' : 'green'">{{ record.secret ? '环境密钥' : '项目参数' }}</a-tag></template></a-table-column><a-table-column title="说明"><template #cell="{ record }"><span>{{ record.preview }}</span></template></a-table-column></template></a-table><a-empty v-if="!variables.length" description="当前环境没有可用变量"><template #extra><router-link to="/project-settings/parameters">前往项目参数</router-link></template></a-empty></a-tab-pane>
            <a-tab-pane key="security" title="安全边界"><div class="security-note"><icon-safe /><div><strong>本节点不执行脚本，也不保存请求体、响应体、请求头、认证值或 WebSocket 消息到数据库。</strong><span>{{ networkPolicyDescription }}</span></div></div></a-tab-pane>
          </a-tabs>
          <div v-if="protocolType === 'HTTP'" class="response-splitter"><span></span></div>
          <section v-if="protocolType !== 'WEBSOCKET'" class="response-panel">
            <div class="response-head"><div><strong>返回响应</strong><a-tag v-if="result" :color="statusColor">{{ statusText }}</a-tag><span v-if="result">{{ result.durationMs }} ms</span><span v-if="result">{{ result.truncated ? '已截断至 512 KB' : `${result.body.length} 字符` }}</span></div><div><a-button size="small" :disabled="!result" @click="copyResponse">复制响应</a-button></div></div>
            <div v-if="!result" class="response-empty"><div><icon-thunderbolt /></div><strong>点击“发送”按钮获取返回结果</strong><span>响应状态、耗时、Header 与 Body 会显示在这里</span></div>
            <a-tabs v-else v-model:active-key="responseTab" size="small"><a-tab-pane key="body" title="Body"><pre class="response-code">{{ responseBody }}</pre></a-tab-pane><a-tab-pane key="headers" :title="`Headers (${result?.responseHeaders.length ?? 0})`"><pre class="response-code">{{ responseHeadersText }}</pre></a-tab-pane></a-tabs>
          </section>
        </div>
      </section>
    </a-spin>
    <a-modal v-model:visible="saveInterfaceDialog" title="保存为正式接口" width="560px" ok-text="保存" :ok-loading="savingInterface" :on-before-ok="saveAsInterface">
      <a-form :model="saveInterfaceForm" layout="vertical">
        <a-alert type="info">保存后会在接口管理中创建 1.0.0 初始版本。Header、Auth、URL 和 JSON Body 中识别出的敏感值将替换为环境变量占位符。</a-alert>
        <a-form-item label="接口目录" required :validate-status="saveInterfaceErrors.catalogId ? 'error' : undefined" :help="saveInterfaceErrors.catalogId">
          <a-select v-model="saveInterfaceForm.catalogId" placeholder="选择接口目录" @change="delete saveInterfaceErrors.catalogId">
            <a-option v-for="catalog in catalogOptions" :key="catalog.id" :value="catalog.id">{{ '\u3000'.repeat(catalog.depth) }}{{ catalog.name }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="接口名称" required :validate-status="saveInterfaceErrors.name ? 'error' : undefined" :help="saveInterfaceErrors.name">
          <a-input v-model="saveInterfaceForm.name" :max-length="200" show-word-limit placeholder="例如：创建订单" @input="delete saveInterfaceErrors.name" />
        </a-form-item>
        <a-alert v-if="saveInterfaceErrors.form" type="error">{{ saveInterfaceErrors.form }}</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="generatedCodeDialog" title="生成 cURL" width="720px" :footer="false"><pre class="generated-code">{{ generatedCode }}</pre><div class="code-modal-actions"><a-button type="primary" @click="copyGeneratedCode">复制 cURL</a-button></div></a-modal>
  </div>
</template>

<style scoped>
.api-debug-page--embedded{height:100%;min-height:0;padding:0;background:#fff}.api-debug-page--embedded :deep(.arco-spin),.api-debug-page--embedded :deep(.arco-spin-child){height:100%}.debug-workspace--embedded{height:100%;min-height:0;border:0;border-radius:0}.request-editor{display:flex;min-height:0;flex-direction:column}.embedded-request-tabbar{display:flex;align-items:center;justify-content:space-between;height:50px;padding:0 16px;border-bottom:1px solid var(--color-border-2);background:#fff}.embedded-request-tabbar>div{display:flex;align-items:center;gap:8px}.embedded-request-tabbar>div:first-child{color:rgb(var(--primary-6))}.request-bar.request-bar--saveable{grid-template-columns:108px minmax(260px,1fr) auto auto auto;padding-top:18px}.send-actions{display:flex}.send-actions>.arco-btn:first-child{border-radius:4px 0 0 4px}.send-actions>.arco-dropdown>.arco-btn{min-width:34px;padding:0 8px;border-left:1px solid rgba(255,255,255,.35);border-radius:0 4px 4px 0}.quick-http-tabs{height:360px;min-height:360px}.quick-http-tabs :deep(.arco-tabs-content){height:308px;overflow:auto;padding-bottom:18px}.quick-table-title{display:flex;align-items:center;gap:10px;margin-bottom:8px;color:var(--color-text-2);font-weight:600}.quick-table-title small{color:var(--color-text-3);font-weight:400}.quick-kv-table{min-width:720px;border-top:1px solid var(--color-border-2)}.quick-kv-row{display:grid;grid-template-columns:32px minmax(140px,1fr) minmax(180px,1.25fr) minmax(140px,1fr) 36px;align-items:center;min-height:41px;border-bottom:1px solid var(--color-border-2)}.quick-kv-row>.arco-input-wrapper{height:100%;border:0;border-left:1px solid var(--color-border-2);border-radius:0;background:transparent}.quick-kv-head{min-height:36px;padding:0 8px;color:var(--color-text-3);font-size:12px;background:var(--color-fill-1)}.quick-kv-head strong{font-weight:600}.body-type-tabs{margin-bottom:13px}.operation-card{display:flex;align-items:center;justify-content:space-between;max-width:720px;margin:8px 0 14px;padding:16px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.operation-card>div{display:grid;gap:5px}.operation-card span{color:var(--color-text-3);font-size:12px}.post-form{display:grid;grid-template-columns:220px minmax(260px,1fr);gap:14px;max-width:760px}.post-checks{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;max-width:760px}.post-checks article{display:flex;gap:8px;padding:11px;border:1px solid rgb(var(--green-3));border-radius:7px;background:rgb(var(--green-1));color:rgb(var(--green-7))}.post-checks article.failed{border-color:rgb(var(--red-3));background:rgb(var(--red-1));color:rgb(var(--red-7))}.post-checks article>div{display:grid;gap:3px}.post-checks span{font-size:11px}.settings-form{max-width:520px}.response-splitter{display:flex;height:9px;align-items:center;justify-content:center;border-top:1px solid #e8ebf1;border-bottom:1px solid #e8ebf1;background:#f5f7fa}.response-splitter span{width:32px;height:3px;border-radius:3px;background:#bcc4d2}.response-panel{min-height:250px;flex:1;border-top:0}.response-empty{display:grid;min-height:235px;place-content:center;justify-items:center;gap:8px;color:var(--color-text-3)}.response-empty>div{display:grid;width:56px;height:56px;place-items:center;border-radius:15px;background:var(--color-fill-2);font-size:25px}.response-empty strong{color:var(--color-text-2)}.response-empty span{font-size:12px}.generated-code{max-height:420px;margin:0;padding:16px;overflow:auto;border-radius:8px;background:#172033;color:#c5d7f7;white-space:pre-wrap;word-break:break-word}.code-modal-actions{display:flex;justify-content:flex-end;margin-top:14px}
.api-debug-page--embedded{min-height:100%;padding:20px 24px 32px}.quick-workspace-tabbar{display:flex;align-items:stretch;height:48px;margin:-26px -28px 22px;border-bottom:1px solid #e7e9ee;background:#fff}.quick-workspace-tab{position:relative;display:flex;min-width:145px;align-items:center;justify-content:center;gap:8px;padding:0 18px;border-right:1px solid #eceef2;color:#6c768b;text-decoration:none}.quick-workspace-tab.active{color:#6f53cc;font-weight:650}.quick-workspace-tab.active::after{position:absolute;right:14px;bottom:0;left:14px;height:2px;background:#8c62e8;content:""}.debug-workspace--embedded{grid-template-columns:minmax(0,1fr)}
.request-bar.request-bar--saveable{grid-template-columns:108px minmax(0,1fr) auto 105px}.history-actions{justify-content:flex-end}.history-actions .arco-btn{flex:1}.api-debug-page :deep(.arco-modal-body .arco-alert){margin-bottom:16px}
.debug-summary{display:grid;grid-template-columns:1.5fr 1fr .9fr .65fr;gap:18px;align-items:center;margin-bottom:20px}.debug-summary>div{display:grid;gap:4px}.debug-summary span,.debug-summary small,.tree-title small,.history-item small,.editor-caption{color:var(--color-text-3);font-size:12px}.debug-summary strong{font-size:20px}.tree-title>div{display:grid;gap:3px}.history-list{display:grid;gap:7px;margin-top:14px}.history-item{display:grid;gap:7px;padding:10px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.history-item>div{display:flex;gap:5px}.history-item code{overflow:hidden;color:var(--color-text-2);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.editor-caption{padding:5px 0 12px;line-height:1.6}.auth-form,.protocol-form{max-width:620px}.auth-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.discovery-field{display:flex;gap:8px}.discovery-field .arco-input-wrapper{min-width:0;flex:1}.discovery-select{width:100%;margin-top:8px}.discovery-tip{display:block;margin-top:7px;color:var(--color-text-3);font-size:12px}.descriptor-panel{display:grid;gap:10px;max-width:820px;margin:0 0 16px;padding:13px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.descriptor-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.descriptor-head>div{display:grid;gap:3px}.descriptor-head strong{font-size:13px}.descriptor-head small,.descriptor-help{color:var(--color-text-3);font-size:12px;line-height:1.55}.descriptor-select-row,.descriptor-import-row{display:flex;gap:8px;align-items:center}.descriptor-select-row .arco-select,.descriptor-import-row .arco-input-wrapper{min-width:0;flex:1}.proto-file-input{display:inline-flex;position:relative;align-items:center;justify-content:center;min-width:138px;height:32px;padding:0 11px;overflow:hidden;border:1px solid var(--color-border-2);border-radius:4px;background:var(--color-bg-2);color:var(--color-text-2);font-size:12px;cursor:pointer}.proto-file-input:hover{border-color:rgb(var(--primary-6));color:rgb(var(--primary-6))}.proto-file-input input{position:absolute;inset:0;opacity:0;cursor:pointer}.protocol-method{display:flex;align-items:center;justify-content:center;margin:0;min-width:108px}.security-note{display:flex;gap:12px;align-items:flex-start;margin:12px 0;padding:16px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.security-note>div{display:grid;gap:6px}.security-note span{color:var(--color-text-3);font-size:13px;line-height:1.65}.websocket-panel{display:grid;gap:13px}.websocket-head{display:flex;justify-content:space-between;gap:14px;align-items:flex-start;padding:12px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.websocket-head>div{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.websocket-head strong{font-size:14px}.websocket-head small,.websocket-close{color:var(--color-text-3);font-size:12px}.websocket-close{padding:9px 11px;border-left:3px solid var(--color-border-3);background:var(--color-fill-1)}.subscription-box{display:grid;gap:9px;padding:12px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.subscription-title{display:grid;gap:3px}.subscription-title strong{font-size:13px}.subscription-title small,.subscription-actions span{color:var(--color-text-3);font-size:12px}.subscription-actions{display:flex;align-items:center;gap:8px}.subscription-list{display:grid;gap:7px}.subscription-list article{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;padding:8px;border:1px solid var(--color-border-2);border-radius:6px;background:#fff}.subscription-list code{max-height:72px;overflow:auto;white-space:pre-wrap;word-break:break-word;font:11px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}.websocket-send{display:flex;gap:8px;align-items:center}.websocket-messages{display:grid;gap:8px;max-height:390px;overflow:auto;padding-right:3px}.websocket-message{display:grid;gap:7px;padding:10px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.websocket-message.in{border-left:3px solid rgb(var(--green-6))}.websocket-message.out{border-left:3px solid rgb(var(--arcoblue-6))}.websocket-message>div{display:flex;align-items:center;gap:7px}.websocket-message small{color:var(--color-text-3);font-size:11px}.websocket-message pre{max-height:180px;margin:0;overflow:auto;white-space:pre-wrap;word-break:break-word;font:12px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace}.response-code{min-height:230px;margin:0;padding:14px;overflow:auto;border:1px solid var(--color-border-2);border-radius:7px;background:#172033;color:#c5d7f7;font:12px/1.65 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap;word-break:break-word}@media (max-width:900px){.debug-summary{grid-template-columns:1fr 1fr}.debug-workspace{grid-template-columns:1fr}.request-tree{border-right:0;border-bottom:1px solid var(--color-border-2);max-height:280px;overflow:auto}}@media (max-width:640px){.debug-summary,.auth-grid{grid-template-columns:1fr}.descriptor-select-row,.descriptor-import-row{align-items:stretch;flex-direction:column}.proto-file-input{min-height:32px}.request-bar{grid-template-columns:95px 1fr}.request-bar .arco-btn{grid-column:1 / -1}.environment-bar{flex-wrap:wrap}.environment-bar .arco-btn{margin-left:0}.websocket-head{align-items:stretch;flex-direction:column}}
.grpc-stream-panel{display:grid;gap:12px;max-width:820px;margin-top:16px}.grpc-stream-head{display:flex;justify-content:space-between;gap:14px;align-items:flex-start;padding:12px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.grpc-stream-head>div{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.grpc-stream-head strong{font-size:14px}.grpc-stream-head small,.grpc-stream-close,.client-stream-help{color:var(--color-text-3);font-size:12px}.grpc-stream-close,.client-stream-help{padding:9px 11px;border-left:3px solid var(--color-border-3);background:var(--color-fill-1);line-height:1.6}.grpc-stream-messages{display:grid;gap:8px;max-height:390px;overflow:auto;padding-right:3px}.grpc-stream-message{display:grid;gap:7px;padding:10px;border:1px solid var(--color-border-2);border-left:3px solid rgb(var(--green-6));border-radius:8px;background:#fff}.grpc-stream-message>div{display:flex;align-items:center;gap:7px}.grpc-stream-message small{color:var(--color-text-3);font-size:11px}.grpc-stream-message pre{max-height:180px;margin:0;overflow:auto;white-space:pre-wrap;word-break:break-word;font:12px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace}@media (max-width:640px){.grpc-stream-head{align-items:stretch;flex-direction:column}}
.request-bar.request-bar--saveable{grid-template-columns:108px minmax(0,1fr) auto auto}
.save-actions{display:flex;align-items:center;white-space:nowrap}
.save-actions>.arco-btn:first-child{border-radius:4px 0 0 4px}
.save-actions>.arco-btn:last-child{margin-left:-1px;border-radius:0 4px 4px 0}
.quick-request-name{max-width:min(440px,48vw);padding:4px 7px;overflow:hidden;border:1px solid transparent;border-radius:4px;background:transparent;color:rgb(var(--primary-6));font:inherit;font-weight:650;text-overflow:ellipsis;white-space:nowrap;cursor:text}
.quick-request-name:hover,.quick-request-name:focus-visible{border-color:rgb(var(--primary-3));background:rgb(var(--primary-1));outline:none}
.quick-request-name-editor{width:min(440px,48vw)}
@media (max-width:640px){.request-bar.request-bar--saveable{grid-template-columns:95px minmax(0,1fr)}.send-actions,.save-actions{grid-column:1/-1}.save-actions>.arco-btn{flex:1}.quick-request-name,.quick-request-name-editor{max-width:52vw;width:52vw}}
</style>
