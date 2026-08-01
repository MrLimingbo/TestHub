<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRoute, useRouter } from 'vue-router'
import ApiDefinitionEditor from '@/components/api-testing/ApiDefinitionEditor.vue'
import ApiInterfaceWorkbench from '@/components/api-testing/ApiInterfaceWorkbench.vue'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'
import {
  buildApiManagementTreeRows,
  nextWorkspaceTabAfterClose,
  type ApiTreeStatusFilter,
} from '@/utils/apiManagementTree'
import { formatApiParameterLines, parseApiParameterLines } from '@/utils/apiTesting'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Catalog { id: string; parentId: string | null; name: string; sortOrder: number }
interface ApiDefinition { id: string; catalogId: string; method: string; path: string; name: string; status: string; currentVersionId: string | null; versionCount: number; createdBy: string; updatedBy: string; createdAt: string; updatedAt: string }
interface Parameter { name: string; type: string; required: boolean; example: string; description: string }
interface ApiVersion { id: string; versionName: string; summary: string; description: string; requestHeaders: string; requestQuery: string; requestPath: string; requestBody: string; responseBody: string; exampleResponse: string; status: string; publishedAt: string | null; createdAt: string }
interface ImportResult { format: string; importedCount: number; items: string[] }

const context = ref<Context | null>(null)
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const projectId = computed({ get: () => session.projectId, set: (value: string) => session.selectProject(value) })
const catalogs = ref<Catalog[]>([])
const definitions = ref<ApiDefinition[]>([])
const selectedCatalogId = ref('')
const selectedApiId = ref('')
const versions = ref<ApiVersion[]>([])
const selectedVersionId = ref('')
const loading = ref(true)
const saving = ref(false)
const catalogDialog = ref(false)
const definitionDialog = ref(false)
const versionDialog = ref(false)
const interchangeDialog = ref(false)
const keyword = ref('')
const editingCatalogId = ref('')
const editingApiId = ref('')
const selectedApiIds = ref<string[]>([])
const openApiIds = ref<string[]>([])
const expandedCatalogIds = ref<Set<string>>(new Set())
const statusFilter = ref<ApiTreeStatusFilter>('ALL')
const multiSelectMode = ref(false)
const treeCollapsed = ref(false)
const selectedCaseCounts = reactive<Record<string, number>>({})
const interchangeKind = ref<'OPENAPI' | 'CURL' | 'POSTMAN' | 'HAR'>('OPENAPI')
const specInput = ref<HTMLInputElement | null>(null)
const specFileName = ref('')
const interchangeResult = ref<ImportResult | null>(null)
const curlPreview = ref('')
const catalogForm = reactive({ name: '', parentId: '', sortOrder: 0 })
const definitionForm = reactive({ catalogId: '', method: 'GET', path: '/api/v1/orders/{orderId}', name: '', versionName: '1.0.0', summary: '', description: '', headersText: '["Authorization","string",true,"","Bearer Token"]', queryText: '', pathText: '["orderId","string",true,"","订单标识"]', requestBody: '{\n  "name": "示例"\n}', responseStatus: 200, responseBody: '{\n  "code": 0,\n  "data": {}\n}' })
const versionForm = reactive({ versionName: '', summary: '' })
const catalogErrors = reactive<Record<string, string>>({})
const definitionErrors = reactive<Record<string, string>>({})
const versionErrors = reactive<Record<string, string>>({})
const interchangeForm = reactive({ catalogId: '', document: '', name: '', command: "curl -X POST 'https://api.example.com/api/v1/orders?dryRun=true' -H 'Authorization: Bearer <token>' --data-raw '{\"name\":\"示例\"}'" })
let versionLoadGeneration = 0

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const selectedApi = computed(() => definitions.value.find((api) => api.id === selectedApiId.value))
const selectedVersion = computed(() => versions.value.find((version) => version.id === selectedVersionId.value))
const catalogMap = computed(() => new Map(catalogs.value.map((catalog) => [catalog.id, catalog])))
const selectedCatalog = computed(() => selectedApi.value ? catalogMap.value.get(selectedApi.value.catalogId) : null)
const openApis = computed(() => openApiIds.value
  .map((id) => definitions.value.find((api) => api.id === id))
  .filter((api): api is ApiDefinition => Boolean(api)))
const treeRows = computed(() => buildApiManagementTreeRows(
  catalogs.value,
  definitions.value,
  expandedCatalogIds.value,
  keyword.value,
  statusFilter.value,
))
const visibleApiCount = computed(() => treeRows.value.filter((row) => row.kind === 'api').length)
const statusFilterLabel = computed(() => ({ ALL: '全部接口', PUBLISHED: '已发布', DRAFT: '草稿' }[statusFilter.value]))
const methodClass = (method: string) => `method-${method.toLowerCase()}`
const parseParams = (value: string) => { try { return JSON.parse(value) as Parameter[] } catch { return [] } }
const actorName = (id: string) => id === session.profile?.id ? session.profile.displayName : id || '未记录'

async function read<T>(url: string, init?: RequestInit): Promise<T> { const response = await fetch(url, { credentials: 'include', ...init }); const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { code?: string; message?: string; details?: Record<string, unknown> }; if (!response.ok) throw toApiRequestError(response, body); return body.data }
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> { const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf'); return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) }) }
async function loadVersions() {
  const generation = ++versionLoadGeneration
  const apiId = selectedApiId.value
  const activeProjectId = projectId.value
  if (!apiId) {
    versions.value = []
    selectedVersionId.value = ''
    return
  }
  const data = await read<ApiVersion[]>(`/api/v1/projects/${activeProjectId}/api-design/definitions/${apiId}/versions`)
  if (generation !== versionLoadGeneration || apiId !== selectedApiId.value || activeProjectId !== projectId.value) return
  versions.value = data
  selectedVersionId.value = data.find((version) => version.id === selectedApi.value?.currentVersionId)?.id ?? data[0]?.id ?? ''
}
function expandCatalogPath(catalogId: string) {
  const expanded = new Set(expandedCatalogIds.value)
  let current = catalogMap.value.get(catalogId)
  while (current) {
    expanded.add(current.id)
    current = current.parentId ? catalogMap.value.get(current.parentId) : undefined
  }
  expandedCatalogIds.value = expanded
}
function syncOpenTabs(definitionData: ApiDefinition[]) {
  const validIds = new Set(definitionData.map((api) => api.id))
  openApiIds.value = openApiIds.value.filter((id) => validIds.has(id))
  if (selectedApiId.value && validIds.has(selectedApiId.value) && !openApiIds.value.includes(selectedApiId.value)) {
    openApiIds.value.push(selectedApiId.value)
  }
}
async function loadAssets() {
  const [catalogData, definitionData] = await Promise.all([
    read<Catalog[]>(`/api/v1/projects/${projectId.value}/api-design/catalogs`),
    read<ApiDefinition[]>(`/api/v1/projects/${projectId.value}/api-design/definitions`),
  ])
  catalogs.value = catalogData
  definitions.value = definitionData
  selectedApiIds.value = selectedApiIds.value.filter((id) => definitionData.some((api) => api.id === id))
  const requestedId = typeof route.query.apiId === 'string' ? route.query.apiId : ''
  if (requestedId && definitionData.some((api) => api.id === requestedId)) selectedApiId.value = requestedId
  if (!selectedApiId.value || !definitionData.some((api) => api.id === selectedApiId.value)) {
    selectedApiId.value = definitionData[0]?.id ?? ''
  }
  if (!expandedCatalogIds.value.size) {
    expandedCatalogIds.value = new Set(catalogData.filter((catalog) => !catalog.parentId).map((catalog) => catalog.id))
  }
  syncOpenTabs(definitionData)
  if (selectedApi.value) expandCatalogPath(selectedApi.value.catalogId)
  await loadVersions()
}
async function load() { loading.value = true; try { context.value = await read<Context>('/api/v1/access/context'); projectId.value = session.resolveProjectId(projects.value); await loadAssets() } catch (error) { Message.error(error instanceof Error ? error.message : '加载接口资产失败') } finally { loading.value = false } }
async function changeProject() {
  selectedCatalogId.value = ''
  selectedApiId.value = ''
  openApiIds.value = []
  expandedCatalogIds.value = new Set()
  try { await loadAssets() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') }
}
function toggleCatalog(catalogId: string) {
  const expanded = new Set(expandedCatalogIds.value)
  if (expanded.has(catalogId)) expanded.delete(catalogId)
  else expanded.add(catalogId)
  expandedCatalogIds.value = expanded
  selectedCatalogId.value = catalogId
}
function openCatalog(catalog?: Catalog, parentId = '') { clearFormErrors(catalogErrors); editingCatalogId.value = catalog?.id ?? ''; Object.assign(catalogForm, { name: catalog?.name ?? '', parentId: catalog?.parentId ?? (parentId || selectedCatalogId.value), sortOrder: catalog?.sortOrder ?? catalogs.value.length }); catalogDialog.value = true }
async function saveCatalog() { clearFormErrors(catalogErrors); if (!catalogForm.name.trim()) { catalogErrors.name = '请输入目录名称'; return false }; saving.value = true; try { const url = editingCatalogId.value ? `/api/v1/projects/${projectId.value}/api-design/catalogs/${editingCatalogId.value}` : `/api/v1/projects/${projectId.value}/api-design/catalogs`; const catalog = await mutate<Catalog>(url, editingCatalogId.value ? 'PUT' : 'POST', { ...catalogForm, parentId: catalogForm.parentId || null }); catalogDialog.value = false; selectedCatalogId.value = catalog.id; await loadAssets(); Message.success(editingCatalogId.value ? '接口目录已更新' : '接口目录已创建'); return true } catch (error) { Object.assign(catalogErrors, formErrorsFrom(error)); if (!Object.keys(catalogErrors).length) catalogErrors.name = error instanceof Error ? error.message : '保存目录失败'; return false } finally { saving.value = false } }
async function deleteCatalog(catalog: Catalog) { try { await mutate<void>(`/api/v1/projects/${projectId.value}/api-design/catalogs/${catalog.id}`, 'DELETE'); if (selectedCatalogId.value === catalog.id) selectedCatalogId.value = ''; await loadAssets(); Message.success('接口目录已删除') } catch (error) { Message.error(error instanceof Error ? error.message : '删除目录失败') } }
function nextVersion(value: string) { const match = value.match(/^v?(\d+)\.(\d+)\.(\d+)/); return match ? `${match[1]}.${match[2]}.${Number(match[3]) + 1}` : '1.0.0' }
function responseStatus(value?: string) { try { const status = Number((JSON.parse(value || '{}') as { statusCode?: unknown }).statusCode); return Number.isInteger(status) && status >= 100 && status <= 599 ? status : 200 } catch { return 200 } }
function prepareDefinition(api?: ApiDefinition) {
  if (!catalogs.value.length) {
    Message.warning('请先创建接口目录')
    return false
  }
  clearFormErrors(definitionErrors)
  editingApiId.value = api?.id ?? ''
  const current = api ? selectedVersion.value : null
  Object.assign(definitionForm, {
    catalogId: api?.catalogId ?? (selectedCatalogId.value || catalogs.value[0].id),
    method: api?.method ?? 'GET',
    path: api?.path ?? '/api/v1/orders/{orderId}',
    name: api?.name ?? '',
    versionName: current ? nextVersion(current.versionName) : '1.0.0',
    summary: current?.summary ?? '',
    description: current?.description ?? '',
    headersText: current ? formatApiParameterLines(current.requestHeaders) : '["Authorization","string",true,"","Bearer Token"]',
    queryText: current ? formatApiParameterLines(current.requestQuery) : '',
    pathText: current ? formatApiParameterLines(current.requestPath) : '["orderId","string",true,"","订单标识"]',
    requestBody: current?.requestBody ?? '{\n  "name": "示例"\n}',
    responseStatus: responseStatus(current?.exampleResponse),
    responseBody: current?.responseBody ?? '{\n  "code": 0,\n  "data": {}\n}',
  })
  return true
}
function openDefinition(api?: ApiDefinition) {
  if (!prepareDefinition(api)) return
  definitionDialog.value = true
}
function prepareInlineEditor() {
  if (selectedApi.value) prepareDefinition(selectedApi.value)
}
function clearDefinitionError(field: string) {
  delete definitionErrors[field]
}
async function saveDefinition() {
  clearFormErrors(definitionErrors)
  if (!definitionForm.catalogId) definitionErrors.catalogId = '请选择接口目录'
  if (!definitionForm.name.trim()) definitionErrors.name = '请输入接口名称'
  if (!definitionForm.path.trim()) definitionErrors.path = '请输入请求路径'
  if (!definitionForm.versionName.trim()) definitionErrors.versionName = '请输入版本号'
  if (!Number.isInteger(definitionForm.responseStatus) || definitionForm.responseStatus < 100 || definitionForm.responseStatus > 599) {
    definitionErrors.responseStatus = '响应状态码应在 100 到 599 之间'
  }
  for (const [field, value] of [['requestBody', definitionForm.requestBody], ['responseBody', definitionForm.responseBody]] as const) {
    if (value.trim()) {
      try { JSON.parse(value) } catch { definitionErrors[field] = '请输入合法 JSON' }
    }
  }
  if (Object.keys(definitionErrors).length) return false
  saving.value = true
  try {
    const payload = {
      catalogId: definitionForm.catalogId,
      method: definitionForm.method,
      path: definitionForm.path,
      name: definitionForm.name,
      versionName: definitionForm.versionName,
      summary: definitionForm.summary,
      description: definitionForm.description,
      headers: parseApiParameterLines(definitionForm.headersText),
      query: parseApiParameterLines(definitionForm.queryText),
      pathParameters: parseApiParameterLines(definitionForm.pathText),
      requestBody: definitionForm.requestBody,
      responseBody: definitionForm.responseBody,
      exampleResponse: { statusCode: definitionForm.responseStatus },
    }
    const saved = await mutate<ApiDefinition>(
      editingApiId.value
        ? `/api/v1/projects/${projectId.value}/api-design/definitions/${editingApiId.value}/draft`
        : `/api/v1/projects/${projectId.value}/api-design/definitions`,
      editingApiId.value ? 'PUT' : 'POST',
      payload,
    )
    definitionDialog.value = false
    selectedApiId.value = saved.id
    if (!openApiIds.value.includes(saved.id)) openApiIds.value = [...openApiIds.value, saved.id]
    await loadAssets()
    await syncRouteToApi()
    Message.success(editingApiId.value ? '接口已更新并创建新版本快照' : '接口与初始版本已创建')
    return true
  } catch (error) {
    Object.assign(definitionErrors, formErrorsFrom(error))
    if (!Object.keys(definitionErrors).length) {
      const message = error instanceof Error ? error.message : '保存接口失败'
      if (message.includes('版本')) definitionErrors.versionName = message
      else if (message.includes('路径') || message.includes('方法')) definitionErrors.path = message
      else definitionErrors.form = message
    }
    return false
  } finally {
    saving.value = false
  }
}
async function deleteDefinition(api: ApiDefinition) {
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/api-design/definitions/${api.id}`, 'DELETE')
    selectedApiIds.value = selectedApiIds.value.filter((id) => id !== api.id)
    const next = nextWorkspaceTabAfterClose(openApiIds.value, selectedApiId.value, api.id)
    openApiIds.value = next.openIds
    selectedApiId.value = next.activeId
    await loadAssets()
    await syncRouteToApi()
    Message.success('接口已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除接口失败') }
}
async function deleteSelectedDefinitions() {
  const targets = definitions.value.filter((api) => selectedApiIds.value.includes(api.id))
  if (!targets.length) return
  try {
    await mutate<{ deletedCount: number }>(`/api/v1/projects/${projectId.value}/api-design/definitions`, 'DELETE', {
      ids: targets.map((api) => api.id),
    })
    const deletedIds = new Set(targets.map((api) => api.id))
    openApiIds.value = openApiIds.value.filter((id) => !deletedIds.has(id))
    if (selectedApiId.value && deletedIds.has(selectedApiId.value)) selectedApiId.value = openApiIds.value[0] ?? ''
    selectedApiIds.value = []
    await loadAssets()
    await syncRouteToApi()
    Message.success(`已删除 ${targets.length} 个接口`)
  } catch (error) {
    await loadAssets()
    Message.error(error instanceof Error ? error.message : '批量删除接口失败')
  }
}
function toggleApiSelection(id: string, checked: boolean) {
  selectedApiIds.value = checked
    ? [...new Set([...selectedApiIds.value, id])]
    : selectedApiIds.value.filter((candidate) => candidate !== id)
}
async function syncRouteToApi() {
  const query = { ...route.query }
  if (selectedApiId.value) query.apiId = selectedApiId.value
  else delete query.apiId
  await router.replace({ path: route.path, query, hash: route.hash })
}
async function selectApi(id: string) {
  if (!openApiIds.value.includes(id)) openApiIds.value = [...openApiIds.value, id]
  selectedApiId.value = id
  const api = definitions.value.find((item) => item.id === id)
  if (api) expandCatalogPath(api.catalogId)
  await loadVersions()
  await syncRouteToApi()
}
async function closeApiTab(id: string) {
  const next = nextWorkspaceTabAfterClose(openApiIds.value, selectedApiId.value, id)
  openApiIds.value = next.openIds
  if (selectedApiId.value !== next.activeId) {
    selectedApiId.value = next.activeId
    await loadVersions()
    await syncRouteToApi()
  }
}
async function closeOtherTabs() {
  openApiIds.value = selectedApiId.value ? [selectedApiId.value] : []
}
async function closeAllTabs() {
  openApiIds.value = []
  selectedApiId.value = ''
  versions.value = []
  selectedVersionId.value = ''
  await syncRouteToApi()
}
function toggleMultiSelectMode() {
  multiSelectMode.value = !multiSelectMode.value
  if (!multiSelectMode.value) selectedApiIds.value = []
}
function openVersion() {
  if (!selectedVersion.value) return
  clearFormErrors(versionErrors)
  versionForm.versionName = nextVersion(selectedVersion.value.versionName)
  versionForm.summary = selectedVersion.value.summary
  versionDialog.value = true
}
async function saveVersion() {
  clearFormErrors(versionErrors)
  if (!selectedApiId.value || !selectedVersion.value) return false
  if (!versionForm.versionName.trim()) {
    versionErrors.versionName = '请输入新版本名称'
    return false
  }
  saving.value = true
  try {
    const base = selectedVersion.value
    const created = await mutate<ApiVersion>(`/api/v1/projects/${projectId.value}/api-design/definitions/${selectedApiId.value}/versions`, 'POST', {
      versionName: versionForm.versionName,
      summary: versionForm.summary,
      description: base.description,
      headers: parseParams(base.requestHeaders),
      query: parseParams(base.requestQuery),
      pathParameters: parseParams(base.requestPath),
      requestBody: base.requestBody,
      responseBody: base.responseBody,
      exampleResponse: JSON.parse(base.exampleResponse || '{}'),
    })
    versionDialog.value = false
    await loadAssets()
    selectedVersionId.value = created.id
    Message.success('版本快照已创建')
    return true
  } catch (error) {
    Object.assign(versionErrors, formErrorsFrom(error))
    if (!Object.keys(versionErrors).length) {
      const message = error instanceof Error ? error.message : '创建版本失败'
      if (message.includes('版本')) versionErrors.versionName = message
      else versionErrors.form = message
    }
    return false
  } finally {
    saving.value = false
  }
}
async function publishVersion() { if (!selectedApiId.value || !selectedVersionId.value) return; try { await mutate(`/api/v1/projects/${projectId.value}/api-design/definitions/${selectedApiId.value}/versions/${selectedVersionId.value}/publish`, 'POST'); await loadAssets(); Message.success('版本已发布，在线文档已更新') } catch (error) { Message.error(error instanceof Error ? error.message : '发布失败') } }
function openInterchange() { if (!catalogs.value.length) { Message.warning('请先创建接口目录'); return }; Object.assign(interchangeForm, { catalogId: selectedCatalogId.value || catalogs.value[0].id, document: '', name: '', command: "curl -X POST 'https://api.example.com/api/v1/orders?dryRun=true' -H 'Authorization: Bearer <token>' --data-raw '{\"name\":\"示例\"}'" }); interchangeKind.value = 'OPENAPI'; specFileName.value = ''; interchangeResult.value = null; curlPreview.value = ''; interchangeDialog.value = true }
function chooseSpecFile() { specInput.value?.click() }
async function specChanged(event: Event) { const file = (event.target as HTMLInputElement).files?.[0]; if (!file) return; if (file.size > 2 * 1024 * 1024) { Message.error('接口定义文档不能超过 2 MB'); return }; try { interchangeForm.document = await file.text(); specFileName.value = file.name; Message.success(`已读取 ${file.name}`) } catch { Message.error('读取接口文档失败') } finally { if (specInput.value) specInput.value.value = '' } }
async function importInterchange() { saving.value = true; try { const result = interchangeKind.value === 'OPENAPI' ? await mutate<ImportResult>(`/api/v1/projects/${projectId.value}/api-design/interchange/openapi/import`, 'POST', { catalogId: interchangeForm.catalogId, document: interchangeForm.document }) : await mutate<ImportResult>(`/api/v1/projects/${projectId.value}/api-design/interchange/curl/import`, 'POST', { catalogId: interchangeForm.catalogId, name: interchangeForm.name, command: interchangeForm.command }); interchangeResult.value = result; await loadAssets(); Message.success(`已导入 ${result.importedCount} 个接口资产`) } catch (error) { Message.error(error instanceof Error ? error.message : '导入接口资产失败') } finally { saving.value = false } }
async function downloadOpenApi() { try { const scope = selectedCatalogId.value ? `?catalogId=${encodeURIComponent(selectedCatalogId.value)}` : ''; const response = await fetch(`/api/v1/projects/${projectId.value}/api-design/interchange/openapi/export${scope}`, { credentials: 'include' }); if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; throw new Error(body.message ?? '导出失败') }; const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'testhub-openapi.json'; link.click(); URL.revokeObjectURL(link.href); Message.success('OpenAPI 3.1 文档已导出') } catch (error) { Message.error(error instanceof Error ? error.message : '导出接口文档失败') } }
async function importPostman() { saving.value = true; try { const result = await mutate<ImportResult>('/api/v1/projects/' + projectId.value + '/api-design/interchange/postman/import', 'POST', { catalogId: interchangeForm.catalogId, document: interchangeForm.document }); interchangeResult.value = result; await loadAssets(); Message.success('已导入 ' + result.importedCount + ' 个 Postman 接口资产') } catch (error) { Message.error(error instanceof Error ? error.message : '导入 Postman Collection 失败') } finally { saving.value = false } }
async function downloadPostman() { try { const scope = selectedCatalogId.value ? '?catalogId=' + encodeURIComponent(selectedCatalogId.value) : ''; const response = await fetch('/api/v1/projects/' + projectId.value + '/api-design/interchange/postman/export' + scope, { credentials: 'include' }); if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; throw new Error(body.message ?? '导出失败') }; const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'testhub-postman-collection.json'; link.click(); URL.revokeObjectURL(link.href); Message.success('Postman Collection 2.1 已导出') } catch (error) { Message.error(error instanceof Error ? error.message : '导出 Postman Collection 失败') } }
async function importHar() { saving.value = true; try { const result = await mutate<ImportResult>('/api/v1/projects/' + projectId.value + '/api-design/interchange/har/import', 'POST', { catalogId: interchangeForm.catalogId, document: interchangeForm.document }); interchangeResult.value = result; await loadAssets(); Message.success('已导入 ' + result.importedCount + ' 个 HAR 接口资产') } catch (error) { Message.error(error instanceof Error ? error.message : '导入 HAR 文档失败') } finally { saving.value = false } }
async function downloadHar() { try { const scope = selectedCatalogId.value ? '?catalogId=' + encodeURIComponent(selectedCatalogId.value) : ''; const response = await fetch('/api/v1/projects/' + projectId.value + '/api-design/interchange/har/export' + scope, { credentials: 'include' }); if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; throw new Error(body.message ?? '导出失败') }; const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'testhub-api-archive.har'; link.click(); URL.revokeObjectURL(link.href); Message.success('HAR 1.2 文档已导出') } catch (error) { Message.error(error instanceof Error ? error.message : '导出 HAR 文档失败') } }
async function previewCurl() { if (!selectedApi.value) { Message.warning('请先选择一个接口'); return }; try { const result = await read<{ command: string }>(`/api/v1/projects/${projectId.value}/api-design/definitions/${selectedApi.value.id}/interchange/curl?baseUrl=${encodeURIComponent('https://api.example.com')}`); curlPreview.value = result.command; interchangeDialog.value = true } catch (error) { Message.error(error instanceof Error ? error.message : '生成 cURL 失败') } }
watch(() => route.query.apiId, async (value) => {
  if (typeof value !== 'string' || value === selectedApiId.value || !definitions.value.some((api) => api.id === value)) return
  await selectApi(value)
})
onMounted(load)
</script>

<template>
  <div class="api-design-page" :class="{ 'tree-is-collapsed': treeCollapsed }">
    <aside class="api-tree-pane" aria-label="接口目录树">
      <header class="tree-header">
        <div v-if="!treeCollapsed">
          <h1>接口管理</h1>
          <span>{{ selectedProject?.name ?? '未分配项目' }}</span>
        </div>
        <a-button type="text" :aria-label="treeCollapsed ? '展开接口树' : '收起接口树'" @click="treeCollapsed = !treeCollapsed">
          <icon-menu-unfold v-if="treeCollapsed" /><icon-menu-fold v-else />
        </a-button>
      </header>

      <template v-if="!treeCollapsed">
        <button class="branch-row" type="button" title="当前项目默认版本线">
          <icon-branch />
          <strong>main</strong>
          <icon-down />
        </button>
        <div class="tree-toolbar">
          <a-input-search v-model="keyword" allow-clear placeholder="搜索接口名称、方法或路径" aria-label="搜索接口" />
          <a-dropdown trigger="click">
            <a-button :aria-label="`筛选接口，当前：${statusFilterLabel}`"><icon-filter /></a-button>
            <template #content>
              <a-doption @click="statusFilter = 'ALL'">全部接口</a-doption>
              <a-doption @click="statusFilter = 'PUBLISHED'">仅已发布</a-doption>
              <a-doption @click="statusFilter = 'DRAFT'">仅草稿</a-doption>
            </template>
          </a-dropdown>
          <a-dropdown trigger="click">
            <a-button type="primary" aria-label="新建接口资产"><icon-plus /></a-button>
            <template #content>
              <a-doption @click="openDefinition()"><icon-plus /> 新建接口</a-doption>
              <a-doption @click="openCatalog()"><icon-folder-add /> 新建目录</a-doption>
              <a-doption @click="router.push('/api-testing/quick-request')"><icon-thunderbolt /> 快捷请求</a-doption>
              <a-doption @click="openInterchange"><icon-import /> 导入 / 导出</a-doption>
            </template>
          </a-dropdown>
        </div>

        <div class="tree-project-row">
          <div><icon-storage /><span>默认模块</span></div>
          <small>{{ statusFilterLabel }} · {{ visibleApiCount }}</small>
        </div>
        <div class="tree-root-row">
          <div><icon-code-square /><strong>接口</strong></div>
          <span>根目录</span>
        </div>

        <a-spin class="tree-spin" :loading="loading">
          <div class="asset-tree-scroll">
            <template v-for="row in treeRows" :key="row.key">
              <div
                v-if="row.kind === 'catalog'"
                class="asset-tree-entry catalog-entry"
                :class="{ active: selectedCatalogId === row.catalog.id }"
                :style="{ paddingLeft: `${8 + row.depth * 17}px` }"
              >
                <button type="button" class="asset-tree-main" @click="toggleCatalog(row.catalog.id)">
                  <icon-down v-if="row.expanded" class="tree-chevron" />
                  <icon-right v-else class="tree-chevron" />
                  <icon-folder />
                  <span>{{ row.catalog.name }}</span>
                  <small>{{ row.count }}</small>
                </button>
                <div class="tree-row-actions">
                  <a-dropdown trigger="click">
                    <a-button type="text" size="mini" :aria-label="`目录 ${row.catalog.name} 操作`" @click.stop><icon-more /></a-button>
                    <template #content>
                      <a-doption @click="openCatalog(undefined, row.catalog.id)">新建子目录</a-doption>
                      <a-doption @click="openCatalog(row.catalog)">重命名 / 移动</a-doption>
                      <a-doption class="danger-option">
                        <a-popconfirm content="仅空目录可删除，确定继续吗？" @ok="deleteCatalog(row.catalog)">
                          <span>删除目录</span>
                        </a-popconfirm>
                      </a-doption>
                    </template>
                  </a-dropdown>
                </div>
              </div>
              <div
                v-else
                class="asset-tree-entry api-entry"
                :class="{ active: selectedApiId === row.api.id }"
                :style="{ paddingLeft: `${8 + row.depth * 17}px` }"
              >
                <button type="button" class="asset-tree-main" @click="selectApi(row.api.id)">
                  <a-checkbox
                    v-if="multiSelectMode"
                    :model-value="selectedApiIds.includes(row.api.id)"
                    :aria-label="`选择接口 ${row.api.name}`"
                    @click.stop
                    @change="toggleApiSelection(row.api.id, Boolean($event))"
                  />
                  <span class="method-label" :class="methodClass(row.api.method)">{{ row.api.method }}</span>
                  <span class="api-tree-name">{{ row.api.name }}</span>
                  <small v-if="selectedCaseCounts[row.api.id] !== undefined">{{ selectedCaseCounts[row.api.id] }}</small>
                </button>
                <div class="tree-row-actions">
                  <a-dropdown trigger="click">
                    <a-button type="text" size="mini" :aria-label="`接口 ${row.api.name} 操作`" @click.stop><icon-more /></a-button>
                    <template #content>
                      <a-doption @click="selectApi(row.api.id).then(() => openDefinition(row.api))">编辑接口</a-doption>
                      <a-doption @click="toggleApiSelection(row.api.id, true); multiSelectMode = true">加入批量选择</a-doption>
                      <a-doption class="danger-option">
                        <a-popconfirm content="删除接口会同时删除全部版本和测试用例，确定继续吗？" @ok="deleteDefinition(row.api)">
                          <span>删除接口</span>
                        </a-popconfirm>
                      </a-doption>
                    </template>
                  </a-dropdown>
                </div>
              </div>
            </template>
            <a-empty v-if="!loading && !treeRows.length" :description="keyword || statusFilter !== 'ALL' ? '没有匹配的接口' : '还没有接口目录'">
              <template #extra><a-button type="outline" @click="openCatalog()">新建目录</a-button></template>
            </a-empty>
          </div>
        </a-spin>

        <div class="tree-footer">
          <a-button type="text" size="small" @click="toggleMultiSelectMode">
            <icon-select-all /> {{ multiSelectMode ? '退出批量选择' : '批量管理' }}
          </a-button>
          <a-popconfirm :content="`确定删除已选择的 ${selectedApiIds.length} 个接口吗？`" @ok="deleteSelectedDefinitions">
            <a-button v-if="multiSelectMode" size="small" status="danger" :disabled="!selectedApiIds.length">
              删除 ({{ selectedApiIds.length }})
            </a-button>
          </a-popconfirm>
        </div>
      </template>
    </aside>

    <main class="api-editor-pane">
      <header class="workspace-tabbar">
        <div class="workspace-tabs-track">
          <router-link to="/api-testing/quick-request" class="workspace-tab quick-tab">
            <icon-thunderbolt /><span>快捷请求</span>
          </router-link>
          <button
            v-for="api in openApis"
            :key="api.id"
            type="button"
            class="workspace-tab"
            :class="{ active: selectedApiId === api.id }"
            @click="selectApi(api.id)"
          >
            <span class="method-label" :class="methodClass(api.method)">{{ api.method }}</span>
            <span>{{ api.name }}</span>
            <icon-close class="tab-close" :aria-label="`关闭 ${api.name}`" @click.stop="closeApiTab(api.id)" />
          </button>
        </div>
        <div class="workspace-tab-actions">
          <a-button type="text" aria-label="新建接口" @click="openDefinition()"><icon-plus /></a-button>
          <a-dropdown trigger="click">
            <a-button type="text" aria-label="工作区更多操作"><icon-more /></a-button>
            <template #content>
              <a-doption @click="openInterchange">导入 / 导出</a-doption>
              <a-doption @click="toggleMultiSelectMode">批量管理</a-doption>
              <a-doption :disabled="openApiIds.length <= 1" @click="closeOtherTabs">关闭其他标签</a-doption>
              <a-doption :disabled="!openApiIds.length" @click="closeAllTabs">关闭全部标签</a-doption>
            </template>
          </a-dropdown>
          <a-select v-model="projectId" size="small" :style="{ width: '180px' }" aria-label="接口管理项目" @change="changeProject">
            <a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option>
          </a-select>
        </div>
      </header>

      <section class="document-panel">
        <ApiInterfaceWorkbench
          v-if="selectedApi && selectedVersion"
          :key="selectedApi.id"
          :project-id="projectId"
          :api="selectedApi"
          :version="selectedVersion"
          :versions="versions"
          :selected-version-id="selectedVersionId"
          :catalog-name="selectedCatalog?.name ?? '未分类'"
          :created-by-name="actorName(selectedApi.createdBy)"
          :updated-by-name="actorName(selectedApi.updatedBy)"
          @update:selected-version-id="selectedVersionId = $event"
          @edit="prepareInlineEditor"
          @new-version="openVersion"
          @publish="publishVersion"
          @delete="deleteDefinition(selectedApi)"
          @generate-code="previewCurl"
          @case-count-change="selectedCaseCounts[selectedApi.id] = $event"
        >
          <template #editor>
            <ApiDefinitionEditor
              :model-value="definitionForm"
              :catalogs="catalogs"
              :errors="definitionErrors"
              :editing="true"
              :inline="true"
              :saving="saving"
              :show-actions="true"
              @update:model-value="Object.assign(definitionForm, $event)"
              @save="saveDefinition"
              @cancel="prepareInlineEditor"
              @clear-error="clearDefinitionError"
            />
          </template>
        </ApiInterfaceWorkbench>
        <a-empty v-else description="选择或创建一个接口开始维护和调试"><template #extra><a-button type="primary" @click="openDefinition()">新建接口</a-button></template></a-empty>
      </section>
    </main>
    <a-modal v-model:visible="catalogDialog" :title="editingCatalogId ? '修改接口目录' : '新建接口目录'" width="520px" :ok-loading="saving" :on-before-ok="saveCatalog">
      <a-form :model="catalogForm" layout="vertical">
        <a-form-item label="目录名称" required :validate-status="catalogErrors.name ? 'error' : undefined" :help="catalogErrors.name">
          <a-input v-model="catalogForm.name" placeholder="例如：订单服务" @input="catalogErrors.name = ''" />
        </a-form-item>
        <a-form-item label="父级目录">
          <a-select v-model="catalogForm.parentId" allow-clear placeholder="创建为一级目录">
            <a-option v-for="catalog in catalogs.filter((item) => item.id !== editingCatalogId)" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="definitionDialog" :title="editingApiId ? '修改接口并创建新版本' : '新建接口与初始版本'" width="800px" :ok-loading="saving" :on-before-ok="saveDefinition">
      <ApiDefinitionEditor
        :model-value="definitionForm"
        :catalogs="catalogs"
        :errors="definitionErrors"
        :editing="Boolean(editingApiId)"
        :saving="saving"
        @update:model-value="Object.assign(definitionForm, $event)"
        @clear-error="clearDefinitionError"
      />
    </a-modal>
    <a-modal v-model:visible="versionDialog" title="基于当前版本创建快照" width="520px" :ok-loading="saving" :on-before-ok="saveVersion">
      <a-form :model="versionForm" layout="vertical">
        <a-alert type="info">新版本将继承当前文档内容；创建后可继续修改接口并形成下一版快照。</a-alert>
        <a-alert v-if="versionErrors.form" type="error">{{ versionErrors.form }}</a-alert>
        <a-form-item label="新版本名称" required :validate-status="versionErrors.versionName ? 'error' : undefined" :help="versionErrors.versionName">
          <a-input v-model="versionForm.versionName" placeholder="例如：1.1.0" @input="versionErrors.versionName = ''" />
        </a-form-item>
        <a-form-item label="版本摘要"><a-input v-model="versionForm.summary" placeholder="例如：增加订单扩展字段" /></a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="interchangeDialog" title="导入 / 导出接口资产" width="820px" :footer="false">
      <div class="interchange-dialog">
        <a-alert type="info">导入会在写入前校验；任一接口冲突或格式错误都不会留下部分资产。</a-alert>
        <div class="interchange-actions"><a-button @click="downloadOpenApi"><icon-download /> 导出 OpenAPI 3.1</a-button><a-button @click="downloadPostman"><icon-download /> 导出 Postman Collection</a-button><a-button @click="downloadHar"><icon-download /> 导出 HAR 1.2</a-button><a-button :disabled="!selectedApi" @click="previewCurl"><icon-code /> 生成当前接口 cURL</a-button></div>
        <a-radio-group v-model="interchangeKind" type="button"><a-radio value="OPENAPI">OpenAPI / Swagger</a-radio><a-radio value="POSTMAN">Postman Collection</a-radio><a-radio value="HAR">HAR 1.2</a-radio><a-radio value="CURL">cURL</a-radio></a-radio-group>
        <a-form :model="interchangeForm" layout="vertical">
          <a-form-item label="目标接口目录" required><a-select v-model="interchangeForm.catalogId"><a-option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option></a-select></a-form-item>
          <template v-if="interchangeKind === 'OPENAPI' || interchangeKind === 'POSTMAN' || interchangeKind === 'HAR'">
            <a-form-item label="接口定义文档" required extra="支持 OpenAPI 3.0/3.1、Swagger 2.0 JSON/YAML、Postman Collection 2.0/2.1 JSON 或 HAR 1.2 JSON；文件上限 2 MB。"><input ref="specInput" class="file-input" type="file" accept=".json,.yaml,.yml,.har,application/json,application/yaml,text/yaml" @change="specChanged" /><div class="spec-picker"><span>{{ specFileName || '尚未选择文件，可直接粘贴文档' }}</span><a-button @click="chooseSpecFile"><icon-upload /> 选择文件</a-button></div><a-textarea v-model="interchangeForm.document" :placeholder="interchangeKind === 'POSTMAN' ? '粘贴 Postman Collection 2.0/2.1 JSON 文档' : interchangeKind === 'HAR' ? '粘贴 HAR 1.2 JSON 文档' : '粘贴 OpenAPI 或 Swagger JSON/YAML 文档'" :auto-size="{ minRows: 10, maxRows: 16 }" /></a-form-item>
          </template>
          <template v-else>
            <a-form-item label="接口名称" extra="留空时使用方法与请求路径自动命名。"><a-input v-model="interchangeForm.name" placeholder="例如：创建订单" /></a-form-item>
            <a-form-item label="cURL 命令" required extra="支持 -X/--request、-H/--header、-d/--data-raw 等常用参数。"><a-textarea v-model="interchangeForm.command" :auto-size="{ minRows: 7, maxRows: 12 }" /></a-form-item>
          </template>
          <a-button type="primary" :loading="saving" @click="interchangeKind === 'POSTMAN' ? importPostman() : interchangeKind === 'HAR' ? importHar() : importInterchange()"><icon-import /> 校验并导入</a-button>
        </a-form>
        <section v-if="interchangeResult" class="interchange-result"><a-result status="success" :title="'已导入 ' + interchangeResult.importedCount + ' 个接口资产'" :subtitle="interchangeResult.format + ' 已写入当前项目并记录审计日志。'" /><a-space wrap><a-tag v-for="item in interchangeResult.items" :key="item" color="arcoblue">{{ item }}</a-tag></a-space></section>
        <section v-if="curlPreview" class="curl-preview"><div><strong>当前接口 cURL</strong><span>可复制到终端或 CI 配置中继续使用。</span></div><a-textarea :model-value="curlPreview" readonly :auto-size="{ minRows: 5, maxRows: 9 }" /></section>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.api-design-page{display:grid;grid-template-columns:340px minmax(0,1fr);height:calc(100vh - 68px);min-height:640px;overflow:hidden;background:#fff;transition:grid-template-columns .18s ease}.api-design-page.tree-is-collapsed{grid-template-columns:54px minmax(0,1fr)}.api-tree-pane{display:flex;min-width:0;min-height:0;flex-direction:column;border-right:1px solid #e8e9ee;background:#fff}.tree-header{display:flex;min-height:76px;align-items:center;justify-content:space-between;gap:12px;padding:15px 14px 10px 18px}.tree-header>div{display:grid;gap:5px;min-width:0}.tree-header h1{margin:0;color:#2e3748;font-size:22px;letter-spacing:-.5px}.tree-header span{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.tree-is-collapsed .tree-header{justify-content:center;padding:12px 6px}.branch-row{display:flex;align-items:center;gap:8px;margin:0 14px 12px;padding:8px 10px;border:0;border-radius:7px;background:transparent;color:#667189;cursor:default}.branch-row strong{font-size:13px}.branch-row svg:last-child{margin-left:auto}.tree-toolbar{display:grid;grid-template-columns:minmax(0,1fr) 38px 42px;gap:7px;padding:0 14px 13px}.tree-toolbar :deep(.arco-input-wrapper){height:38px;border-radius:8px;background:#f8f9fb}.tree-toolbar :deep(.arco-btn){height:38px;border-radius:8px}.tree-toolbar :deep(.arco-btn-primary){background:#8c62e8}.tree-project-row,.tree-root-row{display:flex;align-items:center;justify-content:space-between;padding:9px 18px;color:#6c778d}.tree-project-row{border-top:1px solid #f0f1f4;font-size:12px}.tree-project-row>div,.tree-root-row>div{display:flex;align-items:center;gap:8px}.tree-project-row small{font-size:10px}.tree-root-row{padding-top:12px;color:#465268}.tree-root-row span{color:#adb3be;font-size:11px}.tree-spin{display:flex;min-height:0;flex:1;overflow:hidden}.tree-spin :deep(.arco-spin-children){display:flex;min-height:0;flex:1;flex-direction:column}.asset-tree-scroll{min-height:0;flex:1;padding:2px 7px 50px;overflow:auto}.asset-tree-entry{position:relative;display:flex;min-width:0;align-items:center;margin:1px 0;border-radius:7px}.asset-tree-entry:hover,.asset-tree-entry.active{background:#f1f2f5}.asset-tree-entry.active .api-tree-name{font-weight:650;color:#263247}.asset-tree-main{display:flex;min-width:0;flex:1;align-items:center;gap:7px;height:38px;padding:0 34px 0 6px;border:0;background:transparent;color:#4c586e;text-align:left;cursor:pointer}.asset-tree-main:focus-visible{outline:2px solid #8c62e8;outline-offset:-2px;border-radius:7px}.asset-tree-main>span:not(.method-label){overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.asset-tree-main>small{margin-left:auto;color:#929baa;font-size:10px}.tree-chevron{flex:0 0 auto;color:#8791a4;font-size:12px}.catalog-entry .asset-tree-main>svg:not(.tree-chevron){flex:0 0 auto;font-size:17px}.tree-row-actions{position:absolute;right:3px;display:none;align-items:center;background:linear-gradient(90deg,transparent,#f1f2f5 22%)}.asset-tree-entry:hover .tree-row-actions,.asset-tree-entry:focus-within .tree-row-actions{display:flex}.api-entry .asset-tree-main{gap:8px}.api-entry .arco-checkbox{flex:0 0 auto}.api-tree-name{min-width:0;flex:1;font-size:12px}.method-label{flex:0 0 auto;font-size:10px;font-weight:800;line-height:1;letter-spacing:.1px}.method-get{color:#14a36f}.method-post{color:#eb6b25}.method-put{color:#3974df}.method-patch{color:#875ce2}.method-delete{color:#e14d54}.method-head,.method-options{color:#75839a}.tree-footer{display:flex;min-height:46px;align-items:center;justify-content:space-between;gap:8px;padding:7px 12px;border-top:1px solid #eceef2;background:#fff}.tree-footer :deep(.arco-btn-text){color:#6f7890}.api-editor-pane{display:grid;min-width:0;min-height:0;grid-template-rows:54px minmax(0,1fr);overflow:hidden;background:#fff}.workspace-tabbar{display:flex;min-width:0;align-items:stretch;justify-content:space-between;border-bottom:1px solid #e7e9ee;background:#fff}.workspace-tabs-track{display:flex;min-width:0;flex:1;overflow-x:auto;scrollbar-width:none}.workspace-tabs-track::-webkit-scrollbar{display:none}.workspace-tab{position:relative;display:flex;min-width:130px;max-width:235px;align-items:center;justify-content:center;gap:8px;padding:0 16px;border:0;border-right:1px solid #eceef2;background:#fff;color:#687388;text-decoration:none;cursor:pointer}.workspace-tab>span:not(.method-label){overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.workspace-tab:hover{background:#fafafa}.workspace-tab.active{color:#2f384a;font-weight:650;background:#fff}.workspace-tab.active::after,.workspace-tab.router-link-active::after{position:absolute;right:10px;bottom:0;left:10px;height:2px;background:#8c62e8;content:""}.quick-tab{min-width:125px;color:#6e56c8}.tab-close{flex:0 0 auto;color:#a5acb8;font-size:12px}.tab-close:hover{color:#e14d54}.workspace-tab-actions{display:flex;flex:0 0 auto;align-items:center;gap:3px;padding:0 10px;border-left:1px solid #eceef2}.workspace-tab-actions :deep(.arco-select-view){border-radius:8px;background:#f8f9fb}.document-panel{min-width:0;min-height:0;overflow:auto;background:#fff}.document-panel>.arco-empty{padding-top:120px}.danger-option{color:rgb(var(--red-6))}.interchange-dialog{display:grid;gap:16px}.interchange-actions,.spec-picker,.curl-preview>div{display:flex;align-items:center;gap:12px}.interchange-actions{justify-content:flex-start}.file-input{display:none}.spec-picker{justify-content:space-between;padding:9px 0}.spec-picker span,.curl-preview span{color:var(--color-text-3);font-size:12px}.spec-picker span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.interchange-result,.curl-preview{padding:14px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.interchange-result :deep(.arco-result){padding:4px 0 10px}.curl-preview{display:grid;gap:10px}@media(max-width:1180px){.api-design-page{grid-template-columns:300px minmax(0,1fr)}.workspace-tab-actions :deep(.arco-select){display:none}}@media(max-width:760px){.api-design-page{grid-template-columns:260px minmax(680px,1fr)}.interchange-actions,.spec-picker,.curl-preview>div{align-items:flex-start;flex-direction:column}}
</style>
