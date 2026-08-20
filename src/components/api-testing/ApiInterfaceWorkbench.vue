<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRouter } from 'vue-router'
import CodeEditor from '@/components/CodeEditor.vue'
import { storeApiDebugRouteDraft } from '@/utils/apiDebugDraft'
import { executeDesktopHttpRequest, isDesktopHttpDebugAvailable } from '@/utils/desktopApiDebug'
import {
  apiCaseName,
  apiCasePersistence,
  apiCaseType,
  buildApiRunUrl,
  cloneApiRunSnapshot,
  createApiRunSnapshot,
  createPersistableApiRunSnapshot,
  mergeApiRunSnapshot,
  parseApiParameters,
  requestHeaders,
  validateApiContract,
  type ApiCaseType,
  type ApiRunSnapshot,
  type ContractCheck,
} from '@/utils/apiTesting'

interface ApiEnvelope<T> { data: T }
interface ApiDefinition {
  id: string
  catalogId: string
  method: string
  path: string
  name: string
  status: string
  createdAt: string
  updatedAt: string
}
interface ApiVersion {
  id: string
  versionName: string
  summary: string
  description: string
  requestHeaders: string
  requestQuery: string
  requestPath: string
  requestBody: string
  responseBody: string
  exampleResponse: string
  status: string
  publishedAt: string | null
  createdAt: string
}
interface DebugResult {
  historyId: string
  success: boolean
  statusCode: number | null
  statusText: string | null
  durationMs: number
  responseHeaders: Array<{ name: string; value: string }>
  body: string
  truncated: boolean
  errorMessage: string | null
  historyWarning?: string | null
}
interface ProjectEnvironment {
  id: string
  name: string
  environmentCode: string
  baseUrl: string
  enabled: boolean
  defaultEnvironment: boolean
}
interface ProjectParameter {
  environmentCode: string
  key: string
  value: string
}
interface ParameterCollection {
  parameters: ProjectParameter[]
}
interface MockServer {
  id: string
  publicKey: string
  name: string
  enabled: boolean
  smartExampleEnabled: boolean
}
interface ApiInterfaceCase {
  id: string
  apiId: string
  name: string
  environmentCode: string | null
  requestSnapshot: ApiRunSnapshot
  expectedStatus: number | null
  responseValidation: string
  savedResponseBody: string
  createdAt: string
  updatedAt: string
}
const CASE_SCENARIOS = ['成功', '失败', '记录不存在', '数据为空', '缺少参数', '参数有误', '已登录', '未登录'] as const

const props = defineProps<{
  projectId: string
  api: ApiDefinition
  version: ApiVersion
  versions: ApiVersion[]
  selectedVersionId: string
  catalogName: string
  createdByName: string
  updatedByName: string
  openCaseRequest?: number
  openDebugCase?: string
}>()
const emit = defineEmits<{
  'update:selectedVersionId': [value: string]
  edit: []
  newVersion: []
  publish: []
  delete: []
  generateCode: []
  caseListChange: [cases: ApiInterfaceCase[]]
  caseOpened: []
  debugCaseOpened: []
}>()
const router = useRouter()

const tab = ref('document')
const requestTab = ref('params')
const responseTab = ref('body')
const sending = ref(false)
const savingCase = ref(false)
const caseDialog = ref(false)
const cases = ref<ApiInterfaceCase[]>([])
const environments = ref<ProjectEnvironment[]>([])
const parameters = ref<ProjectParameter[]>([])
const mockServers = ref<MockServer[]>([])
const environment = ref('')
const snapshot = reactive<ApiRunSnapshot>(createApiRunSnapshot(props.version))
const result = ref<DebugResult | null>(null)
const responseBody = ref('发送请求后将在此显示真实响应。')
const checks = ref<ContractCheck[]>([])
const caseForm = reactive<{
  name: string
  scenario: string
  type: ApiCaseType
  expectedStatus: number
  saveResponse: boolean
}>({ name: '', scenario: '成功', type: 'DEBUG', expectedStatus: 200, saveResponse: false })
const caseErrors = reactive({ name: '' })
let runContextGeneration = 0
let runDraftRevision = 0

const methodColor = computed(() => ({ GET: 'green', POST: 'arcoblue', PUT: 'orange', PATCH: 'purple', DELETE: 'red' }[props.api.method] ?? 'gray'))
const responseHeadersText = computed(() => result.value?.responseHeaders.map((item) => `${item.name}: ${item.value}`).join('\n') || '暂无响应头')
const responseStatusColor = computed(() => !result.value ? 'gray' : result.value.success && (result.value.statusCode ?? 500) < 400 ? 'green' : 'red')
const responseStatusText = computed(() => !result.value ? '等待发送' : result.value.success ? `${result.value.statusCode} ${result.value.statusText ?? ''}` : '连接失败')
const testCases = computed(() => cases.value.filter((item) => apiCaseType(item.responseValidation) === 'TEST'))
const published = computed(() => props.version.status === 'PUBLISHED')
const activeMockServer = computed(() => mockServers.value.find(
  (server) => server.enabled && server.smartExampleEnabled,
) ?? mockServers.value.find((server) => server.enabled) ?? null)
const mockRuntimeUrl = computed(() => activeMockServer.value
  ? `${window.location.origin}/api/v1/mock/${activeMockServer.value.publicKey}${props.api.path}`
  : '')
const mockAvailable = computed(() => Boolean(published.value && activeMockServer.value?.enabled && activeMockServer.value.smartExampleEnabled))
const expectedResponseStatus = computed(() => {
  try {
    const status = Number((JSON.parse(props.version.exampleResponse || '{}') as { statusCode?: unknown }).statusCode)
    return Number.isInteger(status) && status >= 100 && status <= 599 ? status : 200
  } catch {
    return 200
  }
})
const responseStatusName = computed(() => {
  const status = expectedResponseStatus.value
  if (status >= 200 && status < 300) return '成功'
  if (status >= 400 && status < 500) return '客户端错误'
  if (status >= 500) return '服务端错误'
  return '响应'
})
const requestBodyFields = computed(() => {
  try {
    const body = JSON.parse(props.version.requestBody || '{}') as unknown
    if (!body || Array.isArray(body) || typeof body !== 'object') return []
    return Object.entries(body as Record<string, unknown>).map(([name, value]) => ({
      name,
      type: Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value,
      example: typeof value === 'object' ? JSON.stringify(value) : String(value ?? ''),
    }))
  } catch {
    return []
  }
})
const formatDate = (value: string | null | undefined) => value
  ? new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
  : '未记录'

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

function assignSnapshot(value: ApiRunSnapshot) {
  Object.assign(snapshot, cloneApiRunSnapshot(value))
}

function resetSnapshot() {
  const selected = environments.value.find((item) => item.environmentCode === environment.value)
  const baseUrl = selected?.baseUrl
    ? '{{BASE_URL}}'
    : snapshot.baseUrl || 'https://api.example.com'
  assignSnapshot(createApiRunSnapshot(props.version, baseUrl))
  result.value = null
  responseBody.value = '发送请求后将在此显示真实响应。'
  checks.value = []
}

function selectRunEnvironment(value: unknown) {
  environment.value = String(value ?? '')
  const selected = environments.value.find((item) => item.environmentCode === environment.value)
  if (selected?.baseUrl) snapshot.baseUrl = '{{BASE_URL}}'
  else if (!environment.value && snapshot.baseUrl === '{{BASE_URL}}') snapshot.baseUrl = 'https://api.example.com'
  result.value = null
  responseBody.value = '环境已切换，点击发送后使用新环境执行。'
  checks.value = []
}

function safeRequestUrl() {
  try {
    return buildApiRunUrl(props.api, snapshot)
  } catch {
    return `${snapshot.baseUrl.replace(/\/+$/, '')}${props.api.path}`
  }
}

async function loadRunContext() {
  if (!props.projectId) return
  const generation = ++runContextGeneration
  const draftRevision = runDraftRevision
  const resultAtStart = result.value
  const projectId = props.projectId
  const apiId = props.api.id
  try {
    const [environmentData, parameterData, caseData, mockServerData] = await Promise.all([
      read<ProjectEnvironment[]>(`/api/v1/projects/${projectId}/environments`),
      read<ParameterCollection>(`/api/v1/projects/${projectId}/parameters`),
      read<ApiInterfaceCase[]>(`/api/v1/projects/${projectId}/api-design/definitions/${apiId}/cases`),
      read<MockServer[]>(`/api/v1/projects/${projectId}/mock/servers`).catch(() => []),
    ])
    if (generation !== runContextGeneration || projectId !== props.projectId || apiId !== props.api.id) return
    const preserveRunDraft = runDraftRevision !== draftRevision || sending.value || result.value !== resultAtStart
    environments.value = environmentData
    parameters.value = parameterData.parameters
    cases.value = caseData
    openRequestedDebugCase()
    mockServers.value = mockServerData
    if (environment.value && !environments.value.some((item) => item.environmentCode === environment.value)) environment.value = ''
    if (!environment.value) {
      environment.value = environments.value.find((item) => item.enabled && item.defaultEnvironment)?.environmentCode
        ?? environments.value.find((item) => item.enabled)?.environmentCode
        ?? ''
    }
    if (!preserveRunDraft) resetSnapshot()
  } catch (error) {
    if (generation === runContextGeneration) {
      Message.error(error instanceof Error ? error.message : '加载接口运行配置失败')
    }
  }
}

async function sendRequest(caseExpectation?: Pick<ApiInterfaceCase, 'expectedStatus' | 'responseValidation'>) {
  if (!snapshot.baseUrl.trim()) {
    Message.warning('请填写请求服务地址')
    return
  }
  runDraftRevision++
  sending.value = true
  result.value = null
  checks.value = []
  try {
    const payload = {
      method: props.api.method,
      url: buildApiRunUrl(props.api, snapshot),
      headers: requestHeaders(snapshot),
      body: snapshot.body,
      timeoutMs: 10_000,
      environmentCode: environment.value || null,
      auth: { ...snapshot.auth },
      protocol: { type: 'HTTP' },
    }
    const variableValues = Object.fromEntries(parameters.value
      .filter((parameter) => parameter.environmentCode === environment.value)
      .map((parameter) => [parameter.key, parameter.value]))
    const response = isDesktopHttpDebugAvailable()
      ? await executeDesktopHttpRequest(props.projectId, payload, variableValues)
      : await mutate<DebugResult>(`/api/v1/projects/${props.projectId}/api-debug/execute`, 'POST', payload)
    result.value = response
    responseBody.value = response.success ? (response.body || '(目标服务未返回响应体)') : (response.errorMessage ?? '连接目标服务失败')
    checks.value = validateApiContract(props.version, response.statusCode, response.body, caseExpectation
      ? {
          expectedStatus: caseExpectation.expectedStatus,
          validateBody: caseExpectation.responseValidation === 'CONTRACT',
        }
      : undefined)
    if (response.historyWarning) Message.warning(`请求已完成，但${response.historyWarning}`)
    else if (response.success) Message.success(`请求完成 · ${response.statusCode} · ${response.durationMs} ms`)
    else Message.warning(response.errorMessage ?? '请求未能连接到目标服务')
  } catch (error) {
    responseBody.value = error instanceof Error ? error.message : '发送请求失败'
    Message.error(responseBody.value)
  } finally {
    sending.value = false
  }
}

function openSaveCase(type: ApiCaseType = 'DEBUG') {
  runDraftRevision++
  caseErrors.name = ''
  caseForm.scenario = result.value?.success === false || (result.value?.statusCode ?? 200) >= 400 ? '失败' : '成功'
  caseForm.name = apiCaseName(props.api.name, caseForm.scenario)
  caseForm.type = type
  caseForm.expectedStatus = result.value?.statusCode ?? expectedResponseStatus.value
  caseForm.saveResponse = Boolean(result.value?.body)
  caseDialog.value = true
}

const caseRequestMounted = ref(false)
function openRequestedCase(request = props.openCaseRequest) {
  if (!caseRequestMounted.value || !request) return
  openSaveCase()
  emit('caseOpened')
}

function applyCaseScenario(value: string) {
  caseForm.scenario = value
  caseForm.name = apiCaseName(props.api.name, value)
  caseErrors.name = ''
}

async function saveCase() {
  caseErrors.name = ''
  if (!caseForm.name.trim()) {
    caseErrors.name = '请输入用例名称'
    return false
  }
  savingCase.value = true
  try {
    const persistable = createPersistableApiRunSnapshot(snapshot)
    const casePersistence = apiCasePersistence(caseForm.type, caseForm.expectedStatus, caseForm.saveResponse, result.value?.body ?? '')
    await mutate<ApiInterfaceCase>(`/api/v1/projects/${props.projectId}/api-design/definitions/${props.api.id}/cases`, 'POST', {
      name: caseForm.name,
      environmentCode: environment.value || null,
      requestSnapshot: persistable.snapshot,
      ...casePersistence,
    })
    caseDialog.value = false
    cases.value = await read<ApiInterfaceCase[]>(`/api/v1/projects/${props.projectId}/api-design/definitions/${props.api.id}/cases`)
    if (caseForm.type === 'TEST') tab.value = 'cases'
    if (persistable.secretsRemoved) Message.info('直接填写的认证值未写入用例；如需复用，请改用环境变量引用')
    Message.success(`已保存为接口${caseForm.type === 'TEST' ? '测试用例' : '调试用例'}`)
    return true
  } catch (error) {
    const message = error instanceof Error ? error.message : '保存用例失败'
    if (message.includes('用例名称')) caseErrors.name = message
    else Message.error(message)
    return false
  } finally {
    savingCase.value = false
  }
}

function applyCase(item: ApiInterfaceCase) {
  environment.value = item.environmentCode ?? ''
  assignSnapshot(mergeApiRunSnapshot(props.version, item.requestSnapshot))
  result.value = null
  responseBody.value = item.savedResponseBody || '已载入调试用例，点击发送即可再次执行。'
  checks.value = []
  tab.value = 'debug'
  Message.success(`已载入用例：${item.name}`)
}

function openRequestedDebugCase() {
  if (!props.openDebugCase) return
  const item = cases.value.find((candidate) => candidate.id === props.openDebugCase
    && apiCaseType(candidate.responseValidation) === 'DEBUG')
  if (!item) return
  applyCase(item)
  emit('debugCaseOpened')
}

async function runCase(item: ApiInterfaceCase) {
  applyCase(item)
  await sendRequest(item)
}

async function deleteCase(item: ApiInterfaceCase) {
  const type = apiCaseType(item.responseValidation)
  try {
    await mutate<void>(`/api/v1/projects/${props.projectId}/api-design/definitions/${props.api.id}/cases/${item.id}`, 'DELETE')
    cases.value = cases.value.filter((candidate) => candidate.id !== item.id)
    Message.success(`${type === 'TEST' ? '测试' : '调试'}用例已删除`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : `删除${type === 'TEST' ? '测试' : '调试'}用例失败`)
  }
}

function restoreFromDocument() {
  resetSnapshot()
  Message.success('运行参数已恢复为当前接口文档的示例值')
}

function switchToDebug() {
  tab.value = 'debug'
}

function handleWorkbenchTabChange(value: string | number) {
  const next = String(value)
  if (next === 'edit') emit('edit')
}

async function copyMockUrl() {
  if (!mockRuntimeUrl.value) {
    Message.warning('请先在 Mock 服务中创建并启用智能示例服务')
    return
  }
  await navigator.clipboard.writeText(mockRuntimeUrl.value)
  Message.success('Mock URL 已复制')
}

async function openAdvancedDebug() {
  try {
    const draft = storeApiDebugRouteDraft(window.sessionStorage, {
      method: props.api.method,
      url: safeRequestUrl(),
      body: snapshot.body,
      headers: requestHeaders(snapshot).map((item) => `${item.name}: ${item.value}`).join('\n'),
      environment: environment.value,
      protocol: 'HTTP',
    })
    await router.push({
      path: '/api-testing/quick-request',
      query: { draft, sourceApiId: props.api.id },
    })
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '无法打开高级调试')
  }
}

watch(() => [props.projectId, props.api.id], loadRunContext, { immediate: true })
watch(() => props.version.id, resetSnapshot)
watch(snapshot, () => { runDraftRevision++ }, { deep: true, flush: 'sync' })
watch(environment, () => { runDraftRevision++ }, { flush: 'sync' })
watch(cases, (value) => emit('caseListChange', value))
watch(() => props.openDebugCase, openRequestedDebugCase)
watch(() => props.openCaseRequest, (request, previous) => {
  if (request && request !== previous) openRequestedCase(request)
})
onMounted(() => {
  caseRequestMounted.value = true
  openRequestedCase()
})
</script>

<template>
  <section class="api-interface-workbench">
    <a-tabs v-model:active-key="tab" class="workbench-tabs" @change="handleWorkbenchTabChange">
      <a-tab-pane key="document" title="文档">
        <div class="document-view">
          <header class="api-document-header">
            <div class="document-title">
              <h2>{{ api.name }}</h2>
              <div class="endpoint-line">
                <a-tag :color="methodColor" size="large">{{ api.method }}</a-tag>
                <code>{{ api.path }}</code>
                <a-dropdown trigger="click">
                  <button class="publish-state" :class="{ published }" type="button">
                    <i />{{ published ? '已发布' : '草稿' }}<icon-down />
                  </button>
                  <template #content>
                    <a-doption @click="emit('newVersion')"><icon-plus /> 基于当前版本新建快照</a-doption>
                    <a-doption v-if="!published" @click="emit('publish')"><icon-upload /> 发布当前版本</a-doption>
                  </template>
                </a-dropdown>
              </div>
            </div>
            <a-space wrap>
              <a-button class="debug-button" type="primary" @click="switchToDebug"><icon-thunderbolt /> 调试</a-button>
              <a-button @click="emit('generateCode')"><icon-code /> 生成代码</a-button>
              <a-popconfirm content="删除接口会同时删除所有版本和测试用例，确定继续吗？" @ok="emit('delete')">
                <a-button>删除</a-button>
              </a-popconfirm>
            </a-space>
          </header>
          <div class="document-share"><icon-public /><span>项目成员可按现有权限查看此接口</span></div>
          <dl class="api-metadata">
            <div><dt>创建时间</dt><dd>{{ formatDate(api.createdAt) }}</dd></div>
            <div><dt>修改时间</dt><dd>{{ formatDate(api.updatedAt) }}</dd></div>
            <div><dt>修改者</dt><dd>{{ updatedByName }}</dd></div>
            <div><dt>创建者</dt><dd>{{ createdByName }}</dd></div>
            <div><dt>责任人</dt><dd>未设置</dd></div>
            <div><dt>目录</dt><dd>{{ catalogName }}</dd></div>
            <div class="version-metadata">
              <dt>版本</dt>
              <dd>
                <a-select
                  :model-value="selectedVersionId"
                  size="mini"
                  :style="{ width: '112px' }"
                  aria-label="选择接口版本"
                  @change="emit('update:selectedVersionId', String($event))"
                >
                  <a-option v-for="item in versions" :key="item.id" :value="item.id">{{ item.versionName }}</a-option>
                </a-select>
              </dd>
            </div>
          </dl>
          <section v-if="version.summary || version.description" class="document-summary">
            <p>{{ version.summary || version.description }}</p>
          </section>

          <section class="document-section mock-overview">
            <div class="document-section-title">
              <div><h3>Mock</h3><span>已发布响应可作为智能 Mock 数据源</span></div>
              <router-link to="/mock">管理 Mock 服务</router-link>
            </div>
            <a-tabs default-active-key="local" size="small" class="mock-scope-tabs">
              <a-tab-pane key="local" title="本地">
                <div class="mock-table">
                  <div class="mock-table-head"><span>名称</span><span>来源</span><span>URL / 参数</span><span /></div>
                  <div class="mock-table-row">
                    <span><strong :class="{ available: mockAvailable }">{{ expectedResponseStatus }}</strong> {{ responseStatusName }}</span>
                    <span>接口响应</span>
                    <code>{{ mockRuntimeUrl || '尚未配置已启用的智能 Mock 服务' }}</code>
                    <a-button size="mini" :disabled="!mockRuntimeUrl" @click="copyMockUrl">复制</a-button>
                  </div>
                </div>
              </a-tab-pane>
              <a-tab-pane key="cloud" title="云端"><a-empty description="当前私有化环境未配置云端 Mock" /></a-tab-pane>
              <a-tab-pane key="self-hosted" title="自托管"><a-empty description="可前往 Mock 服务配置项目级运行地址" /></a-tab-pane>
            </a-tabs>
          </section>

          <section class="document-section">
            <div class="document-section-title">
              <div><h3>请求参数</h3><span>Path、Query 与 Header 参数</span></div>
              <span>{{ parseApiParameters(version.requestHeaders).length + parseApiParameters(version.requestQuery).length + parseApiParameters(version.requestPath).length }} 项</span>
            </div>
            <a-table
              :data="[
                ...parseApiParameters(version.requestPath).map((item) => ({ ...item, location: 'Path' })),
                ...parseApiParameters(version.requestQuery).map((item) => ({ ...item, location: 'Query' })),
                ...parseApiParameters(version.requestHeaders).map((item) => ({ ...item, location: 'Header' })),
              ]"
              :pagination="false"
              size="small"
            >
              <template #columns>
                <a-table-column title="参数名" data-index="name"><template #cell="{ record }"><code class="parameter-name">{{ record.name }}</code></template></a-table-column>
                <a-table-column title="位置" data-index="location" :width="90"><template #cell="{ record }"><a-tag size="small">{{ record.location }}</a-tag></template></a-table-column>
                <a-table-column title="类型" data-index="type" :width="100" />
                <a-table-column title="必填" :width="70"><template #cell="{ record }"><span :class="{ required: record.required }">{{ record.required ? '必需' : '可选' }}</span></template></a-table-column>
                <a-table-column title="示例" data-index="example" />
                <a-table-column title="说明" data-index="description" />
              </template>
            </a-table>
            <a-empty
              v-if="!parseApiParameters(version.requestHeaders).length && !parseApiParameters(version.requestQuery).length && !parseApiParameters(version.requestPath).length"
              description="当前接口没有 Path、Query 或 Header 参数"
            />
          </section>

          <section class="document-section">
            <div class="document-section-title"><div><h3>Body 参数</h3><span>application/json</span></div></div>
            <div class="schema-example-grid">
              <div class="schema-column">
                <div class="schema-column-title">字段结构</div>
                <div v-for="field in requestBodyFields" :key="field.name" class="schema-field">
                  <code>{{ field.name }}</code><span>{{ field.type }}</span><small>示例字段</small>
                </div>
                <a-empty v-if="!requestBodyFields.length" description="请求体没有可解析的 JSON 对象字段" />
              </div>
              <div class="example-column"><div class="schema-column-title">示例</div><pre>{{ version.requestBody || '{}' }}</pre></div>
            </div>
          </section>

          <section class="document-section response-document">
            <div class="document-section-title"><div><h3>返回响应</h3><span>当前版本响应定义</span></div></div>
            <div class="response-status-tab"><strong>{{ expectedResponseStatus }}</strong> {{ responseStatusName }}</div>
            <div class="schema-example-grid response-schema-grid">
              <div class="schema-column">
                <div class="schema-column-title"><span>HTTP 状态码：{{ expectedResponseStatus }}</span><span>application/json</span></div>
                <pre>{{ version.responseBody || '{}' }}</pre>
              </div>
              <div class="example-column"><div class="schema-column-title">示例</div><pre>{{ version.responseBody || '{}' }}</pre></div>
            </div>
          </section>
        </div>
      </a-tab-pane>

      <a-tab-pane key="edit" title="编辑">
        <slot name="editor">
          <a-empty description="编辑器正在初始化"><template #extra><a-button type="primary" @click="emit('edit')">加载编辑器</a-button></template></a-empty>
        </slot>
      </a-tab-pane>

      <a-tab-pane key="debug" title="调试">
        <div class="workbench-pane">
          <div class="run-toolbar">
            <div class="run-environment-control">
              <a-select :model-value="environment" allow-clear placeholder="请选择环境" aria-label="选择运行环境" @change="selectRunEnvironment">
                <a-option v-for="item in environments" :key="item.id" :value="item.environmentCode" :disabled="!item.enabled">
                  {{ item.name }} · {{ item.environmentCode }}
                </a-option>
              </a-select>
              <a-tooltip content="管理项目环境、变量与密钥"><router-link class="environment-settings-link" to="/system-settings/environments" aria-label="管理项目环境"><icon-settings /></router-link></a-tooltip>
            </div>
            <a-input v-model="snapshot.baseUrl" aria-label="请求服务地址" placeholder="https://api.example.com 或 {{BASE_URL}}" />
            <a-space>
              <a-button @click="restoreFromDocument"><icon-refresh /> 复原文档示例</a-button>
              <a-button @click="openAdvancedDebug">高级调试</a-button>
            </a-space>
            <a-button type="primary" :loading="sending" @click="sendRequest()">发送 <icon-send /></a-button>
          </div>
          <div class="resolved-url"><a-tag :color="methodColor">{{ api.method }}</a-tag><code>{{ safeRequestUrl() }}</code></div>
          <a-tabs v-model:active-key="requestTab" size="small">
            <a-tab-pane key="params" :title="`Params (${snapshot.pathValues.length + snapshot.queryValues.length})`">
              <div class="value-table">
                <div class="value-row value-head"><span>启用</span><span>名称</span><span>位置</span><span>值</span><span>说明</span></div>
                <div v-for="item in snapshot.pathValues" :key="`path-${item.name}`" class="value-row">
                  <a-checkbox v-model="item.enabled" disabled :aria-label="`路径参数 ${item.name} 已启用`" /><code>{{ item.name }}</code><a-tag size="small">Path</a-tag><a-input v-model="item.value" :placeholder="item.required ? '必填' : '可选'" /><span>{{ item.description }}</span>
                </div>
                <div v-for="item in snapshot.queryValues" :key="`query-${item.name}`" class="value-row">
                  <a-checkbox v-model="item.enabled" :disabled="item.required" :aria-label="`启用查询参数 ${item.name}`" /><code>{{ item.name }}</code><a-tag size="small">Query</a-tag><a-input v-model="item.value" :placeholder="item.required ? '必填' : '可选'" /><span>{{ item.description }}</span>
                </div>
                <a-empty v-if="!snapshot.pathValues.length && !snapshot.queryValues.length" description="当前接口没有 Path 或 Query 参数" />
              </div>
            </a-tab-pane>
            <a-tab-pane key="headers" :title="`Headers (${snapshot.headerValues.length})`">
              <div class="value-table">
                <div class="value-row value-head"><span>启用</span><span>名称</span><span>类型</span><span>值</span><span>说明</span></div>
                <div v-for="item in snapshot.headerValues" :key="`header-${item.name}`" class="value-row">
                  <a-checkbox v-model="item.enabled" :disabled="item.required" :aria-label="`启用请求头 ${item.name}`" /><code>{{ item.name }}</code><a-tag size="small">{{ item.type }}</a-tag><a-input v-model="item.value" /><span>{{ item.description }}</span>
                </div>
                <a-empty v-if="!snapshot.headerValues.length" description="当前接口没有 Header 参数" />
              </div>
            </a-tab-pane>
            <a-tab-pane key="body" title="Body"><CodeEditor v-model="snapshot.body" :height="250" /></a-tab-pane>
            <a-tab-pane key="auth" title="Auth">
              <a-form :model="snapshot.auth" layout="vertical" class="auth-form">
                <a-form-item label="认证类型"><a-select v-model="snapshot.auth.type"><a-option value="NONE">无认证</a-option><a-option value="BEARER">Bearer Token</a-option><a-option value="BASIC">Basic Auth</a-option><a-option value="API_KEY">API Key</a-option></a-select></a-form-item>
                <a-form-item v-if="snapshot.auth.type === 'BEARER'" label="Token"><a-input-password v-model="snapshot.auth.token" /></a-form-item>
                <template v-if="snapshot.auth.type === 'BASIC'"><a-form-item label="用户名"><a-input v-model="snapshot.auth.username" /></a-form-item><a-form-item label="密码"><a-input-password v-model="snapshot.auth.password" /></a-form-item></template>
                <template v-if="snapshot.auth.type === 'API_KEY'"><div class="auth-grid"><a-form-item label="名称"><a-input v-model="snapshot.auth.key" /></a-form-item><a-form-item label="位置"><a-select v-model="snapshot.auth.placement"><a-option value="HEADER">Header</a-option><a-option value="QUERY">Query</a-option></a-select></a-form-item></div><a-form-item label="值"><a-input-password v-model="snapshot.auth.value" /></a-form-item></template>
              </a-form>
            </a-tab-pane>
          </a-tabs>
          <section class="response-panel">
            <div class="response-heading">
              <div><strong>响应</strong><a-tag :color="responseStatusColor">{{ responseStatusText }}</a-tag><span v-if="result">{{ result.durationMs }} ms</span><span v-if="result">{{ result.body.length }} 字符</span></div>
              <a-space><a-button :disabled="!result" @click="openSaveCase()">保存为用例</a-button></a-space>
            </div>
            <div v-if="checks.length" class="contract-checks">
              <article v-for="check in checks" :key="check.key" :class="{ failed: !check.passed }"><component :is="check.passed ? 'IconCheckCircle' : 'IconCloseCircle'" /><div><strong>{{ check.label }}</strong><span>{{ check.detail }}</span></div></article>
            </div>
            <a-tabs v-model:active-key="responseTab" size="small">
              <a-tab-pane key="body" title="Body"><pre class="response-code">{{ responseBody }}</pre></a-tab-pane>
              <a-tab-pane key="headers" :title="`Headers (${result?.responseHeaders.length ?? 0})`"><pre class="response-code">{{ responseHeadersText }}</pre></a-tab-pane>
            </a-tabs>
          </section>
        </div>
      </a-tab-pane>

      <a-tab-pane key="cases" :title="`测试用例 (${testCases.length})`">
        <div class="workbench-pane">
          <div class="case-heading"><div><strong>接口测试用例</strong><span>用于稳定的接口回归测试，运行时校验期望状态码与接口契约。</span></div><a-button @click="openSaveCase('TEST')"><icon-plus /> 新建测试用例</a-button></div>
          <div v-if="testCases.length" class="case-list">
            <article v-for="item in testCases" :key="item.id">
              <div><strong>{{ item.name }}</strong><span>{{ item.environmentCode || '无环境' }} · 期望 {{ item.expectedStatus ?? '-' }} · {{ new Date(item.updatedAt).toLocaleString('zh-CN') }}</span></div>
              <a-space><a-button size="small" type="outline" @click="applyCase(item)">仅载入</a-button><a-button size="small" type="primary" :loading="sending" @click="runCase(item)">运行</a-button><a-popconfirm content="确定删除该测试用例吗？" @ok="deleteCase(item)"><a-button size="small" status="danger">删除</a-button></a-popconfirm></a-space>
            </article>
          </div>
          <a-empty v-else description="还没有测试用例"><template #extra><a-button type="primary" @click="openSaveCase('TEST')">新建第一个测试用例</a-button></template></a-empty>
        </div>
      </a-tab-pane>

      <a-tab-pane key="mock" title="Mock">
        <div class="workbench-pane mock-workbench">
          <div class="mock-panel">
            <div><icon-cloud /><strong>智能 Mock 数据源</strong><p>发布当前版本后，响应定义会自动成为项目 Mock 服务的智能示例。</p></div>
            <div class="mock-runtime-card">
              <div><span>运行状态</span><a-tag :color="mockAvailable ? 'green' : 'gray'">{{ mockAvailable ? '可调用' : '待配置' }}</a-tag></div>
              <code>{{ mockRuntimeUrl || '尚未配置已启用的项目 Mock 服务' }}</code>
              <a-button :disabled="!mockRuntimeUrl" @click="copyMockUrl">复制 URL</a-button>
            </div>
            <pre>{{ version.responseBody || '{}' }}</pre>
            <router-link to="/mock"><a-button type="primary">前往 Mock 服务</a-button></router-link>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>

    <a-modal v-model:visible="caseDialog" title="保存为用例" width="700px" :ok-loading="savingCase" :on-before-ok="saveCase" ok-text="保存">
      <a-form :model="caseForm" layout="vertical" class="save-case-form">
        <a-form-item label="用例名称" required :validate-status="caseErrors.name ? 'error' : undefined" :help="caseErrors.name">
          <div class="case-name-row">
            <a-input v-model="caseForm.name" placeholder="请输入用例名称" @input="caseErrors.name = ''" />
            <a-select :model-value="caseForm.scenario" aria-label="用例场景" @change="applyCaseScenario(String($event))">
              <a-option v-for="scenario in CASE_SCENARIOS" :key="scenario" :value="scenario">{{ scenario }}</a-option>
            </a-select>
          </div>
        </a-form-item>
        <a-form-item label="用例类型">
          <a-radio-group v-model="caseForm.type" class="case-type-options">
            <a-radio value="DEBUG">
              <span class="case-type-title">调试用例</span>
              <small>用于开发阶段快速调试接口，便于随时修改请求数据。</small>
            </a-radio>
            <a-radio value="TEST">
              <span class="case-type-title">测试用例</span>
              <small>用于稳定的接口回归测试，执行时校验状态码与接口契约。</small>
            </a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="caseForm.type === 'TEST'" label="期望状态码"><a-input-number v-model="caseForm.expectedStatus" :min="100" :max="599" /></a-form-item>
        <a-form-item>
          <a-checkbox v-model="caseForm.saveResponse" :disabled="!result?.body">同时保存响应</a-checkbox>
          <span v-if="!result?.body" class="case-response-hint">发送请求并获得响应后可保存响应内容</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<style scoped>
.api-interface-workbench{min-width:0}.workbench-header,.section-heading,.run-toolbar,.response-heading,.case-heading,.case-list article{display:flex;align-items:center;justify-content:space-between;gap:12px}.workbench-header{align-items:flex-start;padding-bottom:16px;border-bottom:1px solid var(--color-border-2)}.workbench-title>div{display:flex;gap:7px}.workbench-title h2{margin:8px 0 5px;font-size:21px}.workbench-title code,.resolved-url code{color:rgb(var(--primary-6));font-size:13px}.dropdown-link{display:block;color:inherit;text-decoration:none}.danger-option{color:rgb(var(--red-6))}.workbench-tabs{margin-top:3px}.document-sections{display:grid;gap:19px;padding-top:6px}.section-heading{margin-bottom:9px}.section-heading span,.summary,.case-heading span,.case-list span{color:var(--color-text-3);font-size:12px}.summary{margin:0;line-height:1.7}.code-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.code-grid pre,.mock-panel pre,.response-code{margin:0;padding:13px;border-radius:8px;background:#172033;color:#c5d7f7;overflow:auto;white-space:pre-wrap;word-break:break-word;font:12px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace}.run-toolbar{display:grid;grid-template-columns:220px minmax(280px,1fr) auto auto;margin:5px 0 10px}.run-environment-control{display:grid;grid-template-columns:minmax(0,1fr) 34px;gap:6px}.environment-settings-link{display:grid;place-items:center;width:32px;height:32px;color:#6550d5;border:1px solid var(--color-border-2);border-radius:5px;background:#fff;text-decoration:none}.environment-settings-link:hover{border-color:#8d79eb;background:#f5f2ff}.resolved-url{display:flex;align-items:center;gap:9px;padding:10px 12px;border:1px solid var(--color-border-2);border-radius:7px;background:var(--color-fill-1);overflow:auto}.resolved-url code{white-space:nowrap}.value-table{display:grid;min-height:170px}.value-row{display:grid;grid-template-columns:50px 130px 75px minmax(170px,1fr) minmax(120px,1fr);gap:9px;align-items:center;padding:8px;border-bottom:1px solid var(--color-border-2);font-size:12px}.value-row>span:last-child{color:var(--color-text-3)}.value-head{color:var(--color-text-3);font-weight:600;background:var(--color-fill-1)}.auth-form{max-width:680px}.auth-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.response-panel{margin-top:14px;padding-top:14px;border-top:1px solid var(--color-border-2)}.response-heading>div{display:flex;align-items:center;gap:9px}.response-heading span{color:var(--color-text-3);font-size:12px}.contract-checks{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;margin:12px 0}.contract-checks article{display:flex;align-items:flex-start;gap:8px;padding:10px;border:1px solid rgb(var(--green-3));border-radius:7px;background:rgb(var(--green-1));color:rgb(var(--green-7))}.contract-checks article.failed{border-color:rgb(var(--red-3));background:rgb(var(--red-1));color:rgb(var(--red-7))}.contract-checks article>div{display:grid;gap:3px}.contract-checks span{font-size:11px}.response-code{min-height:220px}.case-heading{align-items:flex-start;padding:8px 0 14px}.case-heading>div{display:grid;gap:4px}.case-list{display:grid;gap:9px}.case-list article{padding:12px;border:1px solid var(--color-border-2);border-radius:8px}.case-list article>div{display:grid;gap:5px}.mock-panel{display:grid;gap:15px;padding:16px;border:1px solid var(--color-border-2);border-radius:9px;background:var(--color-fill-1)}.mock-panel>div{display:grid;grid-template-columns:auto 1fr;gap:7px;align-items:center}.mock-panel p{grid-column:2;margin:0;color:var(--color-text-3);font-size:12px}.mock-panel a{justify-self:start}@media(max-width:900px){.code-grid,.contract-checks{grid-template-columns:1fr}.run-toolbar{grid-template-columns:1fr 1fr}.value-row{grid-template-columns:42px 1fr 70px}.value-row>*:nth-child(4),.value-row>*:nth-child(5){grid-column:2 / -1}}@media(max-width:640px){.workbench-header,.case-list article{align-items:flex-start;flex-direction:column}.run-toolbar{grid-template-columns:1fr}.auth-grid{grid-template-columns:1fr}}
</style>

<style scoped>
.api-interface-workbench {
  min-height: 100%;
  background: #fff;
}

.workbench-tabs {
  margin: 0;
}

.workbench-tabs :deep(.arco-tabs-nav) {
  position: sticky;
  z-index: 5;
  top: 0;
  margin: 0;
  padding: 0 28px;
  border-bottom: 1px solid #ebecef;
  background: rgb(255 255 255 / 96%);
  backdrop-filter: blur(10px);
}

.workbench-tabs :deep(.arco-tabs-nav-tab-list) {
  gap: 4px;
}

.workbench-tabs :deep(.arco-tabs-tab) {
  padding: 17px 10px 15px;
  color: #667188;
  font-weight: 500;
}

.save-case-form .case-name-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 10px;
  width: 100%;
}

.case-type-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
}

.case-type-options :deep(.arco-radio) {
  align-items: flex-start;
  margin: 0;
  padding: 14px;
  border: 1px solid var(--color-border-2);
  border-radius: 8px;
  background: var(--color-fill-1);
}

.case-type-options :deep(.arco-radio-checked) {
  border-color: rgb(var(--primary-5));
  background: rgb(var(--primary-1));
}

.case-type-options :deep(.arco-radio-label) {
  display: grid;
  gap: 6px;
}

.case-type-title {
  font-weight: 600;
}

.case-type-options small,
.case-response-hint {
  color: var(--color-text-3);
  font-size: 12px;
  line-height: 1.6;
}

.case-response-hint {
  margin-left: 10px;
}

.workbench-tabs :deep(.arco-tabs-tab-active) {
  color: #845be4;
}

.workbench-tabs :deep(.arco-tabs-nav-ink) {
  background: #8b62e8;
}

.workbench-tabs :deep(.arco-tabs-content) {
  padding: 0;
}

.document-view {
  width: min(100%, 1280px);
  margin: 0 auto;
  padding: 30px 40px 72px;
  box-sizing: border-box;
}

.api-document-header,
.document-section-title,
.response-heading,
.case-heading,
.case-list article {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.api-document-header {
  align-items: flex-start;
}

.document-title {
  min-width: 0;
}

.document-title h2 {
  margin: 0 0 16px;
  color: #263146;
  font-size: 27px;
  line-height: 1.25;
  letter-spacing: -.4px;
}

.endpoint-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.endpoint-line :deep(.arco-tag) {
  border-radius: 9px;
  font-weight: 750;
}

.endpoint-line code {
  overflow: hidden;
  color: #4f5b72;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.publish-state {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  padding: 3px 0;
  border: 0;
  color: #8992a1;
  font-size: 13px;
  background: transparent;
  cursor: pointer;
}

.publish-state i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #aeb4be;
}

.publish-state.published {
  color: #59667b;
}

.publish-state.published i {
  background: #2bb981;
}

.debug-button {
  border-color: #8c62e8;
  background: #8c62e8;
}

.document-share {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 22px 0 18px;
  color: #5c6980;
  font-size: 13px;
}

.api-metadata {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 30px;
  margin: 0;
  color: #566278;
}

.api-metadata > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.api-metadata dt {
  color: #8992a3;
  font-size: 12px;
}

.api-metadata dd {
  margin: 0;
  color: #465167;
  font-size: 13px;
}

.document-summary {
  margin-top: 22px;
  padding: 12px 14px;
  border-left: 3px solid #8c62e8;
  border-radius: 0 7px 7px 0;
  color: #657086;
  background: #faf8ff;
}

.document-summary p {
  margin: 0;
  line-height: 1.7;
}

.document-section {
  margin-top: 38px;
}

.document-section-title {
  align-items: flex-end;
  margin-bottom: 14px;
}

.document-section-title > div {
  display: grid;
  gap: 5px;
}

.document-section-title h3 {
  margin: 0;
  color: #313b50;
  font-size: 19px;
}

.document-section-title span,
.document-section-title > span,
.case-heading span,
.case-list span {
  color: #8a93a4;
  font-size: 12px;
}

.document-section-title a {
  color: #835ce0;
  text-decoration: none;
}

.mock-scope-tabs :deep(.arco-tabs-nav) {
  position: static;
  padding: 0;
  border-bottom: 1px solid #eceef2;
  backdrop-filter: none;
}

.mock-scope-tabs :deep(.arco-tabs-tab) {
  padding: 10px 9px;
}

.mock-table {
  min-width: 650px;
  overflow: hidden;
  border-bottom: 1px solid #eceef2;
}

.mock-table-head,
.mock-table-row {
  display: grid;
  grid-template-columns: minmax(150px, .8fr) minmax(120px, .7fr) minmax(280px, 2fr) 60px;
  gap: 14px;
  align-items: center;
  min-height: 48px;
  padding: 0 14px;
}

.mock-table-head {
  border-bottom: 1px solid #eceef2;
  color: #7c879b;
  font-size: 12px;
  font-weight: 650;
  background: #fafbfc;
}

.mock-table-row {
  color: #4b576d;
  font-size: 13px;
}

.mock-table-row strong {
  color: #9199a7;
}

.mock-table-row strong.available {
  color: #1bb475;
}

.mock-table-row code {
  overflow: hidden;
  color: #59657a;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-section :deep(.arco-table-th) {
  color: #778197;
  background: #f8f9fb;
}

.document-section :deep(.arco-table-cell) {
  border-color: #eceef2;
}

.parameter-name {
  color: #2686e8;
  font-weight: 650;
}

.required {
  color: #fa7a21;
}

.schema-example-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, .75fr);
  overflow: hidden;
  border: 1px solid #e8eaf0;
  border-radius: 9px;
}

.schema-column,
.example-column {
  min-width: 0;
}

.example-column {
  border-left: 1px solid #e8eaf0;
}

.schema-column-title {
  display: flex;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 16px;
  border-bottom: 1px solid #e8eaf0;
  color: #637087;
  font-size: 12px;
  font-weight: 650;
  background: #fafbfc;
}

.schema-field {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) 90px 1fr;
  gap: 12px;
  align-items: center;
  min-height: 50px;
  padding: 0 16px;
  border-bottom: 1px solid #eff0f3;
}

.schema-field:last-child {
  border-bottom: 0;
}

.schema-field code {
  color: #2387e7;
  font-weight: 650;
}

.schema-field span {
  color: #6c778b;
}

.schema-field small {
  color: #a0a6b2;
}

.schema-example-grid pre {
  min-height: 150px;
  margin: 0;
  padding: 18px;
  overflow: auto;
  color: #42506a;
  background: #fff;
  white-space: pre-wrap;
  word-break: break-word;
  font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, monospace;
}

.response-status-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 -1px 8px;
  padding: 10px 15px;
  border: 1px solid #e8eaf0;
  border-bottom-color: #fff;
  border-radius: 8px 8px 0 0;
  color: #5f6b80;
  background: #fff;
}

.response-status-tab strong {
  color: #1bb475;
}

.response-schema-grid {
  border-radius: 0 9px 9px;
}

.workbench-pane {
  padding: 24px 30px 60px;
}

.run-toolbar {
  display: grid;
  grid-template-columns: 220px minmax(280px, 1fr) auto auto;
  gap: 12px;
  margin: 4px 0 12px;
}

.resolved-url {
  margin-bottom: 10px;
}

.mock-workbench {
  max-width: 980px;
}

.mock-runtime-card {
  display: grid !important;
  grid-template-columns: minmax(0, 1fr) auto !important;
  gap: 10px !important;
  padding: 12px;
  border: 1px solid #e5e7ec;
  border-radius: 8px;
  background: #fff;
}

.mock-runtime-card > div {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  gap: 8px;
}

.mock-runtime-card code {
  overflow: hidden;
  color: #59657a;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1000px) {
  .document-view {
    padding-right: 24px;
    padding-left: 24px;
  }

  .schema-example-grid {
    grid-template-columns: 1fr;
  }

  .example-column {
    border-top: 1px solid #e8eaf0;
    border-left: 0;
  }

  .run-toolbar {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 720px) {
  .api-document-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .workbench-tabs :deep(.arco-tabs-nav) {
    padding: 0 14px;
  }

  .document-view,
  .workbench-pane {
    padding: 20px 16px 50px;
  }

  .run-toolbar {
    grid-template-columns: 1fr;
  }

  .api-metadata {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
}
</style>
