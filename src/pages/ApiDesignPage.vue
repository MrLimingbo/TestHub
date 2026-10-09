<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { useRoute, useRouter } from 'vue-router'
import ApiDefinitionEditor from '@/components/api-testing/ApiDefinitionEditor.vue'
import ApiDebugPage from '@/pages/ApiDebugPage.vue'
import ApiInterfaceWorkbench from '@/components/api-testing/ApiInterfaceWorkbench.vue'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'
import {
  apiTreeDisplayText,
  availableCatalogMoveTargets as catalogMoveTargets,
  buildApiManagementTreeRows,
  interfaceRouteQuery,
  nextApiCopyPath,
  nextWorkspaceTabAfterClose,
  type ApiTreeDisplayMode,
  type ApiTreeStatusFilter,
} from '@/utils/apiManagementTree'
import { apiCaseType, formatApiParameterLines, parseApiParameterLines } from '@/utils/apiTesting'
import { apiDebugDraftFromHistory, apiDebugRequestNameFromReplayDraft, storeApiDebugRouteDraft, type ApiDebugRouteDraft } from '@/utils/apiDebugDraft'
import {
  API_INTERCHANGE_MAX_LABEL,
  apiInterchangeDocumentSizeError,
  apiInterchangeSizeError,
} from '@/utils/apiInterchange'

import { isLocalDesktop } from '@/utils/desktopWorkspace'
import { createApiDesignPreferencesClient, emptyApiDesignPreferences, readLegacyPreferences, PreferencesConflictError, type ApiDesignPreferencesState } from '@/utils/apiDesignPreferences'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Catalog { id: string; parentId: string | null; name: string; sortOrder: number }
interface ApiDefinition { id: string; catalogId: string; method: string; path: string; name: string; status: string; currentVersionId: string | null; versionCount: number; createdBy: string; updatedBy: string; createdAt: string; updatedAt: string }
interface Parameter { name: string; type: string; required: boolean; example: string; description: string }
interface ApiVersion { id: string; versionName: string; summary: string; description: string; requestHeaders: string; requestQuery: string; requestPath: string; requestBody: string; responseBody: string; exampleResponse: string; status: string; publishedAt: string | null; createdAt: string }
interface ApiTreeCase { id: string; apiId: string; name: string; responseValidation: string }
interface ImportResult { format: string; importedCount: number; items: string[]; caseCount?: number; skippedCount?: number; warnings?: string[] }
interface QuickRequestHistory { importSourceId?: string | null; id: string; method: string; protocol?: string; requestUrl: string; responseStatus: number | null; durationMs: number; result: string; createdAt: string; replayDraft?: string; localDraft?: ApiDebugRouteDraft }
interface QuickRequestFolder { id: string; parentId: string | null; name: string }
interface QuickRequestFolderRow extends QuickRequestFolder { depth: number }
type DocumentInterchangeKind = 'OPENAPI' | 'POSTMAN' | 'HAR'
type QuickRequestTreeRow =
  | { key: string; kind: 'folder'; folder: QuickRequestFolder; depth: number }
  | { key: string; kind: 'request'; item: QuickRequestHistory; depth: number }

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
const interfaceRootExpanded = ref(true)
const apiTreeDisplayMode = ref<ApiTreeDisplayMode>('NAME')
const quickRequestRootExpanded = ref(true)
const quickRequestHistory = ref<QuickRequestHistory[]>([])
const quickRequestFolders = ref<QuickRequestFolder[]>([])
const quickRequestCopies = ref<QuickRequestHistory[]>([])
const quickRequestHiddenIds = ref<string[]>([])
const quickRequestFolderIds = ref<Record<string, string>>({})
const preferencesLoading = ref(false)
const preferencesSaving = ref(false)
const preferencesReady = ref(false)
const preferencesError = ref('')
const preferencesConflict = ref(false)
const legacyPreferencesAvailable = ref(false)
const quickFolderDialog = ref(false)
const quickFolderForm = reactive({ id: '', parentId: null as string | null, name: '' })
type PendingPreferences = { state: ApiDesignPreferencesState; copies: QuickRequestHistory[]; success: string }
const pendingPreferences = ref<PendingPreferences | null>(null)
const preferencesLocked = computed(() => !preferencesReady.value || preferencesLoading.value || preferencesSaving.value || Boolean(pendingPreferences.value))
let preferencesGeneration = 0
function preferencesClient() {
  const userId = session.profile?.id
  return createApiDesignPreferencesClient({
    read: (id) => read(`/api/v1/projects/${encodeURIComponent(id)}/api-design/preferences`),
    write: (id, value) => mutate(`/api/v1/projects/${encodeURIComponent(id)}/api-design/preferences`, 'PUT', value, () => { if (session.profile?.id !== userId) throw new Error('登录身份已变化，请重新加载') }),
  })
}
let preferenceClient = preferencesClient()
const apiCases = ref<ApiTreeCase[]>([])
const expandedApiCaseIds = ref<Set<string>>(new Set())
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
let caseOpenNonce = 0

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const selectedApi = computed(() => definitions.value.find((api) => api.id === selectedApiId.value))
const selectedVersion = computed(() => versions.value.find((version) => version.id === selectedVersionId.value))
const catalogMap = computed(() => new Map(catalogs.value.map((catalog) => [catalog.id, catalog])))
const catalogOptionLabels = computed(() => new Map(catalogs.value.map((catalog) => {
  const path: string[] = []
  const visited = new Set<string>()
  let current: Catalog | undefined = catalog
  while (current && !visited.has(current.id)) {
    visited.add(current.id)
    path.unshift(current.name)
    current = current.parentId ? catalogMap.value.get(current.parentId) : undefined
  }
  if (current) path.unshift('…')
  return [catalog.id, path.join(' / ')]
})))
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
const isQuickRequest = computed(() => route.path.endsWith('/quick-request'))
const caseOpenRequest = computed(() => {
  if (typeof route.query.caseRequest !== 'string') return 0
  const request = Number(route.query.caseRequest)
  return Number.isSafeInteger(request) && request > 0 ? request : 0
})
const debugCaseRequest = computed(() => typeof route.query.debugCase === 'string' ? route.query.debugCase : '')
const debugCasesByApi = computed(() => {
  const grouped = new Map<string, ApiTreeCase[]>()
  apiCases.value.forEach((item) => {
    if (apiCaseType(item.responseValidation) !== 'DEBUG') return
    grouped.set(item.apiId, [...(grouped.get(item.apiId) ?? []), item])
  })
  return grouped
})
const quickRequestFolderRows = computed<QuickRequestFolderRow[]>(() => {
  const rows: QuickRequestFolderRow[] = []
  const visited = new Set<string>()
  const appendChildren = (parentId: string | null, depth: number) => {
    quickRequestFolders.value
      .filter((folder) => folder.parentId === parentId && !visited.has(folder.id))
      .forEach((folder) => {
        visited.add(folder.id)
        rows.push({ ...folder, depth })
        appendChildren(folder.id, depth + 1)
      })
  }
  appendChildren(null, 0)
  quickRequestFolders.value
    .filter((folder) => !visited.has(folder.id))
    .forEach((folder) => rows.push({ ...folder, depth: 0 }))
  return rows
})
const quickFolderParentOptions = computed(() => {
  const excluded = new Set(quickFolderForm.id ? [quickFolderForm.id] : [])
  let changed = true
  while (changed) {
    changed = false
    for (const folder of quickRequestFolders.value) {
      if (folder.parentId && excluded.has(folder.parentId) && !excluded.has(folder.id)) { excluded.add(folder.id); changed = true }
    }
  }
  return quickRequestFolderRows.value.filter(folder => !excluded.has(folder.id))
})
const visibleQuickRequestItems = computed(() => [...quickRequestHistory.value, ...quickRequestCopies.value]
  .filter((item) => !quickRequestHiddenIds.value.includes(item.id)))
const quickRequestTreeRows = computed<QuickRequestTreeRow[]>(() => {
  const rows: QuickRequestTreeRow[] = []
  const validFolderIds = new Set(quickRequestFolderRows.value.map((folder) => folder.id))
  quickRequestFolderRows.value.forEach((folder) => {
    rows.push({ key: `folder:${folder.id}`, kind: 'folder', folder, depth: folder.depth })
    visibleQuickRequestItems.value
      .filter((item) => quickRequestFolderIds.value[item.id] === folder.id)
      .forEach((item) => rows.push({ key: `request:${item.id}`, kind: 'request', item, depth: folder.depth + 1 }))
  })
  visibleQuickRequestItems.value
    .filter((item) => {
      const folderId = quickRequestFolderIds.value[item.id]
      return !folderId || !validFolderIds.has(folderId)
    })
    .forEach((item) => rows.push({ key: `request:${item.id}`, kind: 'request', item, depth: 0 }))
  return rows
})
const methodClass = (method: string) => `method-${method.toLowerCase()}`
const apiTreeLabel = (api: ApiDefinition) => apiTreeDisplayText(api, apiTreeDisplayMode.value)
const parseParams = (value: string) => { try { return JSON.parse(value) as Parameter[] } catch { return [] } }
const actorName = (id: string) => id === session.profile?.id ? session.profile.displayName : id || '未记录'
const importResultSummary = (result: ImportResult) => `接口 ${result.importedCount} 个，用例 ${result.caseCount ?? 0} 个，跳过 ${result.skippedCount ?? 0} 个`
const interchangeCaseCount = computed(() => interchangeResult.value?.caseCount ?? 0)
const interchangeSkippedCount = computed(() => interchangeResult.value?.skippedCount ?? 0)
const interchangeWarnings = computed(() => Array.isArray(interchangeResult.value?.warnings)
  ? interchangeResult.value.warnings.filter((warning) => typeof warning === 'string' && warning.trim())
  : [])
const interchangeResultSummary = computed(() => interchangeResult.value
  ? importResultSummary(interchangeResult.value)
  : '')

async function read<T>(url: string, init?: RequestInit): Promise<T> { const response = await fetch(url, { credentials: 'include', ...init }); const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { code?: string; message?: string; details?: Record<string, unknown> }; if (!response.ok) throw toApiRequestError(response, body); return body.data }
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown, beforeSend?: () => void): Promise<T> { const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf'); beforeSend?.(); return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) }) }
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
let assetsGeneration = 0
async function loadAssets() {
  const generation = ++assetsGeneration
  const activeProjectId = projectId.value
  if (!activeProjectId) return
  const [catalogData, definitionData, historyData, caseData] = await Promise.all([
    read<Catalog[]>(`/api/v1/projects/${activeProjectId}/api-design/catalogs`),
    read<ApiDefinition[]>(`/api/v1/projects/${activeProjectId}/api-design/definitions`),
    read<QuickRequestHistory[]>(`/api/v1/projects/${activeProjectId}/api-debug/history?limit=20`),
    read<ApiTreeCase[]>(`/api/v1/projects/${activeProjectId}/api-design/cases`),
  ])
  if (generation !== assetsGeneration || activeProjectId !== projectId.value) return
  catalogs.value = catalogData
  definitions.value = definitionData
  quickRequestHistory.value = historyData
  apiCases.value = caseData
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
  expandedApiCaseIds.value = new Set()
  try { await loadAssets() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') }
}
function toggleCatalog(catalogId: string) {
  const expanded = new Set(expandedCatalogIds.value)
  if (expanded.has(catalogId)) expanded.delete(catalogId)
  else expanded.add(catalogId)
  expandedCatalogIds.value = expanded
  selectedCatalogId.value = catalogId
}
async function setApiTreeDisplayMode(mode: ApiTreeDisplayMode) {
  if (preferencesLocked.value) return
  const state = currentPreferences()
  state.displayMode = mode
  await saveQuickRequestTreeState(state, quickRequestCopies.value, '显示方式已保存')
}
function availableCatalogMoveTargets(catalog: Catalog) {
  return catalogMoveTargets(catalogs.value, catalog.id)
}
async function loadQuickRequestHistory() {
  if (!projectId.value) return
  const activeProjectId = projectId.value
  const generation = preferencesGeneration
  try {
    const history = await read<QuickRequestHistory[]>(`/api/v1/projects/${activeProjectId}/api-debug/history?limit=20`)
    if (activeProjectId === projectId.value && generation === preferencesGeneration) quickRequestHistory.value = history
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载快捷请求历史失败')
  }
}
async function openQuickRequest() {
  quickRequestRootExpanded.value = true
  if (!isQuickRequest.value) await router.push('/api-testing/quick-request')
}
async function newQuickRequest(folderId = '') {
  quickRequestRootExpanded.value = true
  await router.push({ path: '/api-testing/quick-request', query: { newRequest: String(Date.now()), ...(folderId ? { folderId } : {}) } })
}
function quickRequestStorageKey(name: string) { return `testhub:quick-request:${session.profile?.id ?? 'anonymous'}:${projectId.value}:${name}` }
function quickRequestLabel(item: QuickRequestHistory) {
  return item.localDraft?.name?.trim() || apiDebugRequestNameFromReplayDraft(item.replayDraft) || item.requestUrl
}
function currentPreferences(): ApiDesignPreferencesState {
  return { folders: quickRequestFolders.value.map(f => ({ ...f })), hiddenIds: [...quickRequestHiddenIds.value], folderIds: { ...quickRequestFolderIds.value }, displayMode: apiTreeDisplayMode.value }
}
function applyPreferences(state: ApiDesignPreferencesState) {
  quickRequestFolders.value = state.folders
  quickRequestHiddenIds.value = state.hiddenIds
  quickRequestFolderIds.value = state.folderIds
  apiTreeDisplayMode.value = state.displayMode
}
async function loadQuickRequestTreeState(importLegacy: 'automatic' | 'confirmed' | 'none' = 'automatic') {
  const generation = ++preferencesGeneration
  const id = projectId.value
  preferencesReady.value = false
  preferencesLoading.value = Boolean(id)
  preferencesSaving.value = false
  preferencesError.value = ''
  preferencesConflict.value = false
  legacyPreferencesAvailable.value = false
  pendingPreferences.value = null
  applyPreferences(emptyApiDesignPreferences())
  quickRequestCopies.value = []
  if (!id) return
  try {
    const result = await preferenceClient.load(id, () => {
      if (generation !== preferencesGeneration || id !== projectId.value) return null
      if (importLegacy === 'confirmed' || (importLegacy === 'automatic' && isLocalDesktop())) return readLegacyPreferences(window.localStorage, id)
      legacyPreferencesAvailable.value = ['folders', 'hidden', 'folder-ids'].some(name => window.localStorage.getItem(`testhub:quick-request:${id}:${name}`) !== null)
        || window.localStorage.getItem(`testhub:api-tree:${id}:display-mode`) !== null
      return null
    })
    if (generation !== preferencesGeneration || id !== projectId.value) return
    applyPreferences(result.state)
    preferencesReady.value = true
    const copies = JSON.parse(window.sessionStorage.getItem(quickRequestStorageKey('copies')) ?? (isLocalDesktop() ? window.sessionStorage.getItem(`testhub:quick-request:${id}:copies`) : null) ?? '[]') as unknown
    quickRequestCopies.value = Array.isArray(copies)
      ? copies.filter((item): item is QuickRequestHistory => Boolean(item) && typeof item.id === 'string' && typeof item.method === 'string' && typeof item.requestUrl === 'string') : []
    return true
  } catch (error) {
    if (generation !== preferencesGeneration || id !== projectId.value) return false
    preferencesConflict.value = error instanceof PreferencesConflictError
    preferencesError.value = error instanceof Error ? error.message : '加载目录/UI 状态失败，请重试'
    return false
  } finally {
    if (generation === preferencesGeneration) preferencesLoading.value = false
  }
}
async function saveQuickRequestTreeState(state: ApiDesignPreferencesState, copies = quickRequestCopies.value, success = '目录已保存'): Promise<boolean> {
  if (!projectId.value || !preferencesReady.value || preferencesSaving.value || preferencesConflict.value) return false
  const id = projectId.value
  const generation = preferencesGeneration
  const copiesKey = quickRequestStorageKey('copies')
  const candidate = pendingPreferences.value ?? { state, copies: [...copies], success }
  pendingPreferences.value = candidate
  preferencesSaving.value = true
  preferencesError.value = ''
  try {
    // Session-only replay data is never passed to the preferences API.
    const result = await preferenceClient.save(id, () => candidate.state)
    window.sessionStorage.setItem(copiesKey, JSON.stringify(candidate.copies))
    if (generation !== preferencesGeneration || id !== projectId.value) return false
    applyPreferences(result.state)
    quickRequestCopies.value = candidate.copies
    pendingPreferences.value = null
    Message.success(candidate.success)
    return true
  } catch (error) {
    if (generation !== preferencesGeneration || id !== projectId.value) return false
    preferencesConflict.value = error instanceof PreferencesConflictError
    preferencesError.value = error instanceof Error ? error.message : '保存目录/UI 状态失败，请重试'
    Message.error(preferencesError.value)
    return false
  } finally {
    if (generation === preferencesGeneration) preferencesSaving.value = false
  }
}
async function retryPreferences() {
  const pending = pendingPreferences.value
  if (!pending) { await loadQuickRequestTreeState(); return }
  if (await saveQuickRequestTreeState(pending.state, pending.copies, pending.success)) quickFolderDialog.value = false
}
async function reloadPreferences() {
  // Explicit user action only: a conflict must never be resolved by silently overwriting the server.
  await loadQuickRequestTreeState('none')
}
function confirmImportLegacyPreferences() {
  if (preferencesLocked.value) return
  const generation = preferencesGeneration
  Modal.confirm({
    title: '导入当前 origin 的旧目录/UI 状态',
    content: '旧数据没有用户身份标记，可能属于同一浏览器中的其他用户。请确认这些目录属于你，再导入到当前账号和项目。只导入非秘密树状态，不导入请求草稿；保留旧 localStorage。无法读取其他 origin（含旧端口）的数据。',
    okText: '确认属于我并导入',
    onBeforeOk: () => generation === preferencesGeneration ? loadQuickRequestTreeState('confirmed') : false,
  })
}
function addQuickRequestFolder(parentId: string | null = null) {
  if (preferencesLocked.value) return
  Object.assign(quickFolderForm, { id: '', parentId, name: '' })
  quickFolderDialog.value = true
}
function renameQuickRequestFolder(folder: QuickRequestFolder) {
  if (preferencesLocked.value) return
  Object.assign(quickFolderForm, folder)
  quickFolderDialog.value = true
}
async function saveQuickRequestFolder() {
  if (!preferencesReady.value || preferencesLoading.value || preferencesSaving.value || preferencesConflict.value) return false
  const name = quickFolderForm.name.trim()
  if (!name) { Message.warning('请输入快捷请求目录名称'); return false }
  const state = currentPreferences()
  if (quickFolderForm.id) {
    if (!state.folders.some(folder => folder.id === quickFolderForm.id)) { Message.error('目录已不存在，请重新选择目录'); return false }
    state.folders = state.folders.map(folder => folder.id === quickFolderForm.id ? { ...folder, name, parentId: quickFolderForm.parentId } : folder)
  } else {
    if (quickFolderForm.parentId && !state.folders.some(folder => folder.id === quickFolderForm.parentId)) { Message.error('父目录已不存在，请重新选择目录'); return false }
    state.folders.push({ id: globalThis.crypto.randomUUID(), parentId: quickFolderForm.parentId, name })
  }
  pendingPreferences.value = null
  return saveQuickRequestTreeState(state)
}
async function copyQuickRequestFolder(folder: QuickRequestFolder) {
  if (preferencesLocked.value) return
  const state = currentPreferences()
  const copied: QuickRequestFolder[] = []
  const copyBranch = (source: QuickRequestFolder, parentId: string | null, isRoot: boolean) => {
    const id = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${copied.length}`
    copied.push({ id, parentId, name: isRoot ? `${source.name.slice(0, 157)} 副本` : source.name })
    quickRequestFolders.value
      .filter((candidate) => candidate.parentId === source.id)
      .forEach((child) => copyBranch(child, id, false))
  }
  copyBranch(folder, folder.parentId, true)
  state.folders.push(...copied)
  await saveQuickRequestTreeState(state, quickRequestCopies.value, '快捷请求目录已复制')
}
async function deleteQuickRequestFolder(folder: QuickRequestFolder) {
  if (pendingPreferences.value) return saveQuickRequestTreeState(pendingPreferences.value.state)
  if (preferencesLocked.value) return false
  const state = currentPreferences()
  const deletedIds = new Set([folder.id])
  let foundChild = true
  while (foundChild) {
    foundChild = false
    quickRequestFolders.value.forEach((item) => {
      if (item.parentId && deletedIds.has(item.parentId) && !deletedIds.has(item.id)) {
        deletedIds.add(item.id)
        foundChild = true
      }
    })
  }
  state.folders = state.folders.filter((item) => !deletedIds.has(item.id))
  state.folderIds = Object.fromEntries(
    Object.entries(quickRequestFolderIds.value).filter(([, folderId]) => !deletedIds.has(folderId)),
  )
  return saveQuickRequestTreeState(state, quickRequestCopies.value, '快捷请求目录已删除')
}
function confirmDeleteQuickRequestFolder(folder: QuickRequestFolder) {
  if (preferencesLocked.value) return
  const generation = preferencesGeneration
  Modal.confirm({
    title: '删除快捷请求目录',
    content: `删除“${folder.name}”会同时删除其子目录，确定继续吗？`,
    okText: '删除',
    okButtonProps: { status: 'danger' },
    onBeforeOk: () => generation === preferencesGeneration ? deleteQuickRequestFolder(folder) : false,
  })
}
function draftFromQuickRequest(item: QuickRequestHistory): ApiDebugRouteDraft | null {
  const storedDraft = item.localDraft ?? apiDebugDraftFromHistory(item)
  if (storedDraft) return storedDraft
  const protocol = (item.protocol ?? item.method).toUpperCase()
  if (protocol === 'GRPC') {
    const match = item.requestUrl.match(/^grpc(s)?:\/\/([^/]+)\/(.+)\/([^/]+)$/i)
    if (!match) return null
    return {
      replayDraft: JSON.stringify({
        protocol: 'GRPC',
        request: {
          endpoint: match[2],
          tls: Boolean(match[1]),
          service: match[3],
          method: match[4],
          requestJson: '{}',
          timeoutMs: 10_000,
          metadata: [],
          auth: { type: 'NONE' },
        },
      }),
    }
  }
  if (protocol === 'WEBSOCKET') return { protocol: 'WEBSOCKET', url: item.requestUrl }
  if (protocol === 'GRAPHQL' || protocol === 'SOAP') return { protocol, method: 'POST', url: item.requestUrl }
  return null
}
async function copyQuickRequest(item: QuickRequestHistory) {
  if (preferencesLocked.value) return
  const state = currentPreferences()
  const draft = draftFromQuickRequest(item)
  if (!draft) {
    Message.warning('当前请求没有可复制的回放配置')
    return
  }
  const id = `copy:${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
  const copies = [...quickRequestCopies.value, { ...item, id, createdAt: new Date().toISOString(), localDraft: draft }]
  const folderId = quickRequestFolderIds.value[item.id]
  if (folderId) state.folderIds[id] = folderId
  await saveQuickRequestTreeState(state, copies, '快捷请求已复制')
}
async function moveQuickRequest(item: QuickRequestHistory, folderId: string | null) {
  if (preferencesLocked.value) return
  const state = currentPreferences()
  const nextFolderIds = { ...quickRequestFolderIds.value }
  if (folderId) nextFolderIds[item.id] = folderId
  else delete nextFolderIds[item.id]
  state.folderIds = nextFolderIds
  const folder = folderId ? quickRequestFolders.value.find((candidate) => candidate.id === folderId) : null
  await saveQuickRequestTreeState(state, quickRequestCopies.value, folder ? `已移动到“${folder.name}”` : '已移动到快捷请求根目录')
}
async function deleteQuickRequest(item: QuickRequestHistory) {
  if (pendingPreferences.value) return saveQuickRequestTreeState(pendingPreferences.value.state)
  if (preferencesLocked.value) return false
  const state = currentPreferences()
  const copies = item.localDraft ? quickRequestCopies.value.filter(candidate => candidate.id !== item.id) : quickRequestCopies.value
  if (!item.localDraft) state.hiddenIds = [...new Set([...state.hiddenIds, item.id])]
  const nextFolderIds = { ...quickRequestFolderIds.value }
  delete nextFolderIds[item.id]
  state.folderIds = nextFolderIds
  return saveQuickRequestTreeState(state, copies, '快捷请求已删除')
}
function confirmDeleteQuickRequest(item: QuickRequestHistory) {
  if (preferencesLocked.value) return
  const generation = preferencesGeneration
  Modal.confirm({
    title: '删除快捷请求',
    content: `确定删除“${quickRequestLabel(item)}”吗？执行历史不会受到影响。`,
    okText: '删除',
    okButtonProps: { status: 'danger' },
    onBeforeOk: () => generation === preferencesGeneration ? deleteQuickRequest(item) : false,
  })
}
async function loadQuickRequest(item: QuickRequestHistory) {
  const draft = draftFromQuickRequest(item)
  if (!draft) {
    Message.warning('仅 HTTP 请求历史支持载入与重发')
    return
  }
  const draftKey = storeApiDebugRouteDraft(window.sessionStorage, draft)
  quickRequestRootExpanded.value = true
  await router.push({ path: '/api-testing/quick-request', query: { draft: draftKey } })
}
function openHttpDefinition(catalogId = '', method = 'GET') {
  if (!prepareDefinition()) return
  if (catalogId) definitionForm.catalogId = catalogId
  definitionForm.method = method
  definitionDialog.value = true
}
async function openProtocolQuickRequest(protocol: 'GRAPHQL' | 'SOAP' | 'WEBSOCKET' | 'GRPC') {
  const defaults: Record<typeof protocol, ApiDebugRouteDraft> = {
    GRAPHQL: { protocol: 'GRAPHQL', method: 'POST', url: 'https://api.example.com/graphql', body: 'query Example {\n  __typename\n}' },
    SOAP: { protocol: 'SOAP', method: 'POST', url: 'https://api.example.com/soap', body: '<?xml version="1.0" encoding="UTF-8"?>\n<Envelope />' },
    WEBSOCKET: { protocol: 'WEBSOCKET', url: 'wss://ws.postman-echo.com/raw' },
    GRPC: { protocol: 'GRPC', url: 'grpc.example.com:443', body: '{}' },
  }
  const draft = storeApiDebugRouteDraft(window.sessionStorage, defaults[protocol])
  await router.push({ path: '/api-testing/quick-request', query: { draft, source: 'interface-menu' } })
}
function copyText(value: string, success: string) {
  return navigator.clipboard.writeText(value)
    .then(() => Message.success(success))
    .catch(() => Message.error('复制失败，请检查浏览器剪贴板权限'))
}
function currentApiLink(api: ApiDefinition) {
  return new URL(router.resolve({ path: '/api-testing/interfaces', query: { apiId: api.id } }).href, window.location.origin).href
}
function renameCatalog(catalog: Catalog) { openCatalog(catalog) }
async function moveCatalog(catalog: Catalog, parentId: string | null) {
  if (catalog.parentId === parentId) return
  try {
    await mutate<Catalog>(`/api/v1/projects/${projectId.value}/api-design/catalogs/${catalog.id}`, 'PUT', {
      name: catalog.name,
      parentId,
      sortOrder: catalog.sortOrder,
    })
    await loadAssets()
    const parent = parentId ? catalogs.value.find((item) => item.id === parentId) : null
    Message.success(parent ? `目录已移动到“${parent.name}”` : '目录已移动到接口根目录')
  } catch (error) { Message.error(error instanceof Error ? error.message : '移动目录失败') }
}
function uniqueCatalogCopyName(catalog: Catalog) {
  const siblingNames = new Set(catalogs.value.filter((item) => item.parentId === catalog.parentId).map((item) => item.name))
  const base = `${catalog.name} 副本`
  let candidate = base
  let suffix = 2
  while (siblingNames.has(candidate)) candidate = `${base} ${suffix++}`
  return candidate
}
function currentVersionOf(api: ApiDefinition, items: ApiVersion[]) {
  return items.find((version) => version.id === api.currentVersionId) ?? items[0]
}
function versionExample(value: string) {
  try { return JSON.parse(value || '{}') as Record<string, unknown> } catch { return {} }
}
async function createDefinitionCopy(api: ApiDefinition, catalogId: string, routeRegistry: ApiDefinition[]) {
  const items = await read<ApiVersion[]>(`/api/v1/projects/${projectId.value}/api-design/definitions/${api.id}/versions`)
  const version = currentVersionOf(api, items)
  if (!version) throw new Error(`接口“${api.name}”没有可复制的版本`)
  const created = await mutate<ApiDefinition>(`/api/v1/projects/${projectId.value}/api-design/definitions`, 'POST', {
    catalogId,
    method: api.method,
    path: nextApiCopyPath(api.method, api.path, routeRegistry),
    name: `${api.name} 副本`.slice(0, 200),
    versionName: version.versionName,
    summary: version.summary,
    description: version.description,
    headers: parseParams(version.requestHeaders),
    query: parseParams(version.requestQuery),
    pathParameters: parseParams(version.requestPath),
    requestBody: version.requestBody,
    responseBody: version.responseBody,
    exampleResponse: versionExample(version.exampleResponse),
  })
  routeRegistry.push(created)
  return created
}
async function copyDefinition(api: ApiDefinition) {
  saving.value = true
  try {
    const created = await createDefinitionCopy(api, api.catalogId, [...definitions.value])
    await loadAssets()
    await selectApi(created.id)
    Message.success('接口已复制，副本请求路径已自动避让冲突')
  } catch (error) { Message.error(error instanceof Error ? error.message : '复制接口失败') } finally { saving.value = false }
}
async function copyCatalog(catalog: Catalog) {
  saving.value = true
  const routeRegistry = [...definitions.value]
  try {
    const copyBranch = async (source: Catalog, parentId: string | null, root: boolean) => {
      const created = await mutate<Catalog>(`/api/v1/projects/${projectId.value}/api-design/catalogs`, 'POST', {
        name: root ? uniqueCatalogCopyName(source) : source.name,
        parentId,
        sortOrder: source.sortOrder,
      })
      for (const api of definitions.value.filter((item) => item.catalogId === source.id)) {
        await createDefinitionCopy(api, created.id, routeRegistry)
      }
      for (const child of catalogs.value.filter((item) => item.parentId === source.id)) {
        await copyBranch(child, created.id, false)
      }
      return created
    }
    const created = await copyBranch(catalog, catalog.parentId, true)
    await loadAssets()
    expandedCatalogIds.value = new Set([...expandedCatalogIds.value, created.id])
    Message.success('接口目录及其中接口已复制')
  } catch (error) {
    await loadAssets()
    Message.error(error instanceof Error ? `${error.message}；已保留成功创建的资产` : '复制接口目录失败')
  } finally { saving.value = false }
}
async function updateDefinitionMetadata(api: ApiDefinition, values: Partial<Pick<ApiDefinition, 'catalogId' | 'name'>>) {
  return mutate<ApiDefinition>(`/api/v1/projects/${projectId.value}/api-design/definitions/${api.id}`, 'PUT', {
    catalogId: values.catalogId ?? api.catalogId,
    method: api.method,
    path: api.path,
    name: values.name ?? api.name,
  })
}
async function renameDefinition(api: ApiDefinition) {
  const name = window.prompt('请输入接口名称', api.name)?.trim()
  if (!name || name === api.name) return
  try {
    await updateDefinitionMetadata(api, { name })
    await loadAssets()
    Message.success('接口已重命名')
  } catch (error) { Message.error(error instanceof Error ? error.message : '重命名接口失败') }
}
async function moveDefinition(api: ApiDefinition, catalogId: string) {
  if (api.catalogId === catalogId) return
  try {
    await updateDefinitionMetadata(api, { catalogId })
    await loadAssets()
    expandCatalogPath(catalogId)
    Message.success(`接口已移动到“${catalogMap.value.get(catalogId)?.name ?? '目标目录'}”`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '移动接口失败') }
}
async function curlForDefinition(api: ApiDefinition) {
  return read<{ command: string }>(`/api/v1/projects/${projectId.value}/api-design/definitions/${api.id}/interchange/curl?baseUrl=${encodeURIComponent('https://api.example.com')}`)
}
async function copyDefinitionCurl(api: ApiDefinition) {
  try {
    const result = await curlForDefinition(api)
    await copyText(result.command, '接口 cURL 已复制')
  } catch (error) { Message.error(error instanceof Error ? error.message : '生成 cURL 失败') }
}
async function downloadDefinitionCurl(api: ApiDefinition) {
  try {
    const result = await curlForDefinition(api)
    const link = document.createElement('a')
    link.href = URL.createObjectURL(new Blob([`${result.command}\n`], { type: 'text/plain;charset=utf-8' }))
    link.download = `${api.name.replace(/[^\p{L}\p{N}_.-]+/gu, '-') || 'api'}.curl.txt`
    link.click()
    URL.revokeObjectURL(link.href)
    Message.success('接口 cURL 文件已导出')
  } catch (error) { Message.error(error instanceof Error ? error.message : '导出接口失败') }
}
async function openApiCase(api: ApiDefinition) {
  if (!api.currentVersionId) {
    Message.error('接口没有可用版本，无法添加用例')
    return
  }
  const query = interfaceRouteQuery(route.query, api.id)
  query.caseRequest = String(++caseOpenNonce)
  await router.replace({ path: '/api-testing/interfaces', query, hash: route.hash })
}
async function clearCaseOpenRequest() {
  if (!caseOpenRequest.value) return
  const query = interfaceRouteQuery(route.query, selectedApiId.value)
  delete query.caseRequest
  await router.replace({ path: '/api-testing/interfaces', query, hash: route.hash })
}
function debugCasesForApi(apiId: string) {
  return debugCasesByApi.value.get(apiId) ?? []
}
function toggleApiCases(apiId: string) {
  const expanded = new Set(expandedApiCaseIds.value)
  if (expanded.has(apiId)) expanded.delete(apiId)
  else expanded.add(apiId)
  expandedApiCaseIds.value = expanded
}
function updateApiCaseList(apiId: string, items: ApiTreeCase[]) {
  apiCases.value = [...apiCases.value.filter((item) => item.apiId !== apiId), ...items]
  if (items.some((item) => apiCaseType(item.responseValidation) === 'DEBUG')) {
    expandedApiCaseIds.value = new Set([...expandedApiCaseIds.value, apiId])
  }
}
async function openDebugCase(api: ApiDefinition, item: ApiTreeCase) {
  const query = interfaceRouteQuery(route.query, api.id)
  query.debugCase = item.id
  await router.replace({ path: '/api-testing/interfaces', query, hash: route.hash })
}
async function clearDebugCaseRequest() {
  if (!debugCaseRequest.value) return
  const query = interfaceRouteQuery(route.query, selectedApiId.value)
  delete query.debugCase
  await router.replace({ path: '/api-testing/interfaces', query, hash: route.hash })
}
function openScopedInterchange(catalogId = '') {
  selectedCatalogId.value = catalogId
  openInterchange()
}
async function exportScope(kind: 'OPENAPI' | 'POSTMAN' | 'HAR', catalogId = '') {
  selectedCatalogId.value = catalogId
  if (kind === 'OPENAPI') await downloadOpenApi()
  else if (kind === 'POSTMAN') await downloadPostman()
  else await downloadHar()
}
function openCatalog(catalog?: Catalog, parentId = '') { clearFormErrors(catalogErrors); editingCatalogId.value = catalog?.id ?? ''; Object.assign(catalogForm, { name: catalog?.name ?? '', parentId: catalog ? (catalog.parentId ?? '') : (parentId || selectedCatalogId.value), sortOrder: catalog?.sortOrder ?? catalogs.value.length }); catalogDialog.value = true }
async function saveCatalog() { clearFormErrors(catalogErrors); if (!catalogForm.name.trim()) { catalogErrors.name = '请输入目录名称'; return false }; saving.value = true; try { const url = editingCatalogId.value ? `/api/v1/projects/${projectId.value}/api-design/catalogs/${editingCatalogId.value}` : `/api/v1/projects/${projectId.value}/api-design/catalogs`; const catalog = await mutate<Catalog>(url, editingCatalogId.value ? 'PUT' : 'POST', { ...catalogForm, parentId: catalogForm.parentId || null }); catalogDialog.value = false; selectedCatalogId.value = catalog.id; await loadAssets(); Message.success(editingCatalogId.value ? '接口目录已更新' : '接口目录已创建'); return true } catch (error) { Object.assign(catalogErrors, formErrorsFrom(error)); if (!Object.keys(catalogErrors).length) catalogErrors.name = error instanceof Error ? error.message : '保存目录失败'; return false } finally { saving.value = false } }
async function deleteCatalog(catalog: Catalog) { try { await mutate<void>(`/api/v1/projects/${projectId.value}/api-design/catalogs/${catalog.id}`, 'DELETE'); if (selectedCatalogId.value === catalog.id) selectedCatalogId.value = ''; await loadAssets(); Message.success('接口目录已删除') } catch (error) { Message.error(error instanceof Error ? error.message : '删除目录失败') } }
function confirmDeleteCatalog(catalog: Catalog) {
  Modal.confirm({
    title: '删除接口目录',
    content: `仅空目录可删除，确定删除“${catalog.name}”吗？`,
    okText: '删除',
    okButtonProps: { status: 'danger' },
    onOk: () => deleteCatalog(catalog),
  })
}
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
function confirmDeleteDefinition(api: ApiDefinition) {
  Modal.confirm({
    title: '删除接口',
    content: `删除“${api.name}”会同时删除全部版本和测试用例，确定继续吗？`,
    okText: '删除',
    okButtonProps: { status: 'danger' },
    onOk: () => deleteDefinition(api),
  })
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
  const query = interfaceRouteQuery(route.query, selectedApiId.value)
  const path = selectedApiId.value ? '/api-testing/interfaces' : route.path
  await router.replace({ path, query, hash: route.hash })
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
async function specChanged(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  interchangeForm.document = ''
  specFileName.value = ''
  interchangeResult.value = null
  try {
    const fileSizeError = apiInterchangeSizeError(file.size)
    if (fileSizeError) {
      Message.error(fileSizeError)
      return
    }
    const document = await file.text()
    const documentSizeError = apiInterchangeDocumentSizeError(document)
    if (documentSizeError) {
      Message.error(documentSizeError)
      return
    }
    interchangeForm.document = document
    specFileName.value = file.name
    Message.success(`已读取 ${file.name}`)
  } catch {
    interchangeForm.document = ''
    specFileName.value = ''
    Message.error('读取接口文档失败')
  } finally {
    input.value = ''
    if (specInput.value && specInput.value !== input) specInput.value.value = ''
  }
}
const documentImportOptions: Record<DocumentInterchangeKind, { path: string; label: string }> = {
  OPENAPI: { path: 'openapi', label: 'OpenAPI / Swagger' },
  POSTMAN: { path: 'postman', label: 'Postman Collection' },
  HAR: { path: 'har', label: 'HAR 1.2' },
}
function documentImportIsValid() {
  if (!interchangeForm.catalogId) {
    Message.error('请选择目标接口目录')
    return false
  }
  if (!interchangeForm.document.trim()) {
    Message.error('请选择文件或粘贴接口定义文档')
    return false
  }
  const sizeError = apiInterchangeDocumentSizeError(interchangeForm.document)
  if (sizeError) {
    Message.error(sizeError)
    return false
  }
  return true
}
async function importDocument(kind: DocumentInterchangeKind) {
  interchangeResult.value = null
  if (!documentImportIsValid()) return
  saving.value = true
  try {
    const option = documentImportOptions[kind]
    const result = await mutate<ImportResult>(`/api/v1/projects/${projectId.value}/api-design/interchange/${option.path}/import`, 'POST', {
      catalogId: interchangeForm.catalogId,
      document: interchangeForm.document,
    })
    interchangeResult.value = result
    await loadAssets()
    Message.success(`已导入 ${option.label}：${importResultSummary(result)}`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '导入接口资产失败')
  } finally {
    saving.value = false
  }
}
async function importInterchange() {
  interchangeResult.value = null
  if (interchangeKind.value !== 'CURL') {
    await importDocument(interchangeKind.value)
    return
  }
  saving.value = true
  try {
    const result = await mutate<ImportResult>(`/api/v1/projects/${projectId.value}/api-design/interchange/curl/import`, 'POST', { catalogId: interchangeForm.catalogId, name: interchangeForm.name, command: interchangeForm.command })
    interchangeResult.value = result
    await loadAssets()
    Message.success(`已导入 cURL：${importResultSummary(result)}`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '导入接口资产失败')
  } finally {
    saving.value = false
  }
}
async function downloadOpenApi() { try { const scope = selectedCatalogId.value ? `?catalogId=${encodeURIComponent(selectedCatalogId.value)}` : ''; const response = await fetch(`/api/v1/projects/${projectId.value}/api-design/interchange/openapi/export${scope}`, { credentials: 'include' }); if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; throw new Error(body.message ?? '导出失败') }; const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'testhub-openapi.json'; link.click(); URL.revokeObjectURL(link.href); Message.success('OpenAPI 3.1 文档已导出') } catch (error) { Message.error(error instanceof Error ? error.message : '导出接口文档失败') } }
async function downloadPostman() { try { const scope = selectedCatalogId.value ? '?catalogId=' + encodeURIComponent(selectedCatalogId.value) : ''; const response = await fetch('/api/v1/projects/' + projectId.value + '/api-design/interchange/postman/export' + scope, { credentials: 'include' }); if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; throw new Error(body.message ?? '导出失败') }; const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'testhub-postman-collection.json'; link.click(); URL.revokeObjectURL(link.href); Message.success('Postman Collection 2.1 已导出') } catch (error) { Message.error(error instanceof Error ? error.message : '导出 Postman Collection 失败') } }
async function downloadHar() { try { const scope = selectedCatalogId.value ? '?catalogId=' + encodeURIComponent(selectedCatalogId.value) : ''; const response = await fetch('/api/v1/projects/' + projectId.value + '/api-design/interchange/har/export' + scope, { credentials: 'include' }); if (!response.ok) { const body = await response.json().catch(() => ({})) as { message?: string }; throw new Error(body.message ?? '导出失败') }; const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'testhub-api-archive.har'; link.click(); URL.revokeObjectURL(link.href); Message.success('HAR 1.2 文档已导出') } catch (error) { Message.error(error instanceof Error ? error.message : '导出 HAR 文档失败') } }
async function previewCurl() { if (!selectedApi.value) { Message.warning('请先选择一个接口'); return }; try { const result = await read<{ command: string }>(`/api/v1/projects/${projectId.value}/api-design/definitions/${selectedApi.value.id}/interchange/curl?baseUrl=${encodeURIComponent('https://api.example.com')}`); curlPreview.value = result.command; interchangeDialog.value = true } catch (error) { Message.error(error instanceof Error ? error.message : '生成 cURL 失败') } }
watch(() => route.query.apiId, async (value) => {
  if (typeof value !== 'string' || value === selectedApiId.value || !definitions.value.some((api) => api.id === value)) return
  await selectApi(value)
})
watch([projectId, () => session.profile?.id], ([, userId], previous) => {
  if (previous && userId !== previous[1]) { preferenceClient.dispose(); preferenceClient = preferencesClient() }
  quickFolderDialog.value = false
  quickRequestHistory.value = []
  void loadQuickRequestTreeState()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { preferencesGeneration++; assetsGeneration++; preferenceClient.dispose() })
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
              <a-doption @click="openInterchange"><icon-import /> 导入 / 导出</a-doption>
            </template>
          </a-dropdown>
        </div>

        <div class="tree-project-row">
          <div><icon-storage /><span>默认模块</span></div>
          <small>{{ statusFilterLabel }} · {{ visibleApiCount }}</small>
        </div>
        <div class="tree-root-row interface-root-row">
          <button type="button" class="tree-root-main" @click="interfaceRootExpanded = !interfaceRootExpanded">
            <icon-code-square /><strong>接口</strong>
          </button>
          <a-button
            type="text"
            size="mini"
            :aria-expanded="interfaceRootExpanded"
            :aria-label="interfaceRootExpanded ? '收起接口根目录' : '展开接口根目录'"
            @click="interfaceRootExpanded = !interfaceRootExpanded"
          >
            <icon-down v-if="interfaceRootExpanded" /><icon-right v-else />
          </a-button>
          <a-dropdown trigger="click">
            <a-button type="text" size="mini" aria-label="接口根目录更多操作" @click.stop><icon-more /></a-button>
            <template #content>
              <a-doption @click="openHttpDefinition()"><icon-plus /> 新建 HTTP 接口</a-doption>
              <a-dsubmenu trigger="click">
                <template #default><icon-apps /> 新建其他类型接口</template>
                <template #content>
                  <a-doption @click="openProtocolQuickRequest('WEBSOCKET')">WebSocket</a-doption>
                  <a-doption disabled>Socket.IO（暂未支持）</a-doption>
                  <a-doption disabled>MCP（暂未支持）</a-doption>
                  <a-doption @click="openHttpDefinition('', 'POST')">Webhook</a-doption>
                  <a-doption disabled>TCP (Socket)（暂未支持）</a-doption>
                  <a-doption @click="openProtocolQuickRequest('GRPC')">gRPC</a-doption>
                  <a-doption disabled>Dubbo（暂未支持）</a-doption>
                  <a-doption @click="openProtocolQuickRequest('GRAPHQL')">GraphQL</a-doption>
                  <a-doption @click="openProtocolQuickRequest('SOAP')">SOAP</a-doption>
                </template>
              </a-dsubmenu>
              <a-doption disabled><icon-file /> 新建 Markdown（暂未支持）</a-doption>
              <a-doption @click="openCatalog()"><icon-folder-add /> 新建目录</a-doption>
              <a-doption @click="openScopedInterchange()"><icon-import /> 导入</a-doption>
              <a-dsubmenu trigger="click">
                <template #default><icon-export /> 导出</template>
                <template #content>
                  <a-doption @click="exportScope('OPENAPI')">OpenAPI 3.1</a-doption>
                  <a-doption @click="exportScope('POSTMAN')">Postman Collection</a-doption>
                  <a-doption @click="exportScope('HAR')">HAR 1.2</a-doption>
                </template>
              </a-dsubmenu>
              <a-doption disabled><icon-code /> 生成业务代码（暂未支持）</a-doption>
              <a-doption :disabled="preferencesLocked" @click="setApiTreeDisplayMode('NAME')"><icon-check v-if="apiTreeDisplayMode === 'NAME'" /> 接口显示为名称</a-doption>
              <a-doption :disabled="preferencesLocked" @click="setApiTreeDisplayMode('URL')"><icon-check v-if="apiTreeDisplayMode === 'URL'" /> 接口显示为 URL</a-doption>
            </template>
          </a-dropdown>
        </div>

        <a-spin class="tree-spin" :loading="loading">
          <div class="asset-tree-scroll">
            <template v-if="interfaceRootExpanded">
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
                      <a-button type="text" size="mini" :aria-label="`目录 ${row.catalog.name} 更多操作`" @click.stop><icon-more /></a-button>
                      <template #content>
                        <a-doption @click="openHttpDefinition(row.catalog.id)"><icon-plus /> 添加 HTTP 接口</a-doption>
                        <a-dsubmenu trigger="click">
                          <template #default><icon-apps /> 添加其他类型接口</template>
                          <template #content>
                            <a-doption @click="openProtocolQuickRequest('WEBSOCKET')">WebSocket</a-doption>
                            <a-doption disabled>Socket.IO（暂未支持）</a-doption>
                            <a-doption disabled>MCP（暂未支持）</a-doption>
                            <a-doption @click="openHttpDefinition(row.catalog.id, 'POST')">Webhook</a-doption>
                            <a-doption disabled>TCP (Socket)（暂未支持）</a-doption>
                            <a-doption @click="openProtocolQuickRequest('GRPC')">gRPC</a-doption>
                            <a-doption disabled>Dubbo（暂未支持）</a-doption>
                            <a-doption @click="openProtocolQuickRequest('GRAPHQL')">GraphQL</a-doption>
                            <a-doption @click="openProtocolQuickRequest('SOAP')">SOAP</a-doption>
                          </template>
                        </a-dsubmenu>
                        <a-doption disabled><icon-file /> 添加 Markdown（暂未支持）</a-doption>
                        <a-doption @click="openCatalog(undefined, row.catalog.id)"><icon-folder-add /> 添加目录</a-doption>
                        <a-doption @click="renameCatalog(row.catalog)"><icon-edit /> 重命名</a-doption>
                        <a-doption @click="copyCatalog(row.catalog)"><icon-copy /> 复制</a-doption>
                        <a-dsubmenu trigger="click">
                          <template #default><icon-more /> 更多</template>
                          <template #content>
                            <a-dsubmenu trigger="click">
                              <template #default><icon-folder-add /> 移动到</template>
                              <template #content>
                                <a-doption :disabled="row.catalog.parentId === null" @click="moveCatalog(row.catalog, null)">接口根目录</a-doption>
                                <a-doption
                                  v-for="target in availableCatalogMoveTargets(row.catalog)"
                                  :key="target.id"
                                  :disabled="row.catalog.parentId === target.id"
                                  @click="moveCatalog(row.catalog, target.id)"
                                >{{ target.name }}</a-doption>
                              </template>
                            </a-dsubmenu>
                            <a-dsubmenu trigger="click">
                              <template #default><icon-export /> 导出</template>
                              <template #content>
                                <a-doption @click="exportScope('OPENAPI', row.catalog.id)">OpenAPI 3.1</a-doption>
                                <a-doption @click="exportScope('POSTMAN', row.catalog.id)">Postman Collection</a-doption>
                                <a-doption @click="exportScope('HAR', row.catalog.id)">HAR 1.2</a-doption>
                              </template>
                            </a-dsubmenu>
                            <a-doption disabled><icon-code /> 生成业务代码（暂未支持）</a-doption>
                          </template>
                        </a-dsubmenu>
                        <a-doption class="danger-option" @click="confirmDeleteCatalog(row.catalog)"><icon-delete /> 删除</a-doption>
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
                  <button
                    type="button"
                    class="api-case-toggle"
                    :class="{ hidden: !debugCasesForApi(row.api.id).length }"
                    :aria-label="expandedApiCaseIds.has(row.api.id) ? `收起接口 ${row.api.name} 的调试用例` : `展开接口 ${row.api.name} 的调试用例`"
                    :disabled="!debugCasesForApi(row.api.id).length"
                    @click="toggleApiCases(row.api.id)"
                  >
                    <icon-down v-if="expandedApiCaseIds.has(row.api.id)" />
                    <icon-right v-else />
                  </button>
                  <button type="button" class="asset-tree-main" @click="selectApi(row.api.id)">
                    <a-checkbox
                      v-if="multiSelectMode"
                      :model-value="selectedApiIds.includes(row.api.id)"
                      :aria-label="`选择接口 ${row.api.name}`"
                      @click.stop
                      @change="toggleApiSelection(row.api.id, Boolean($event))"
                    />
                    <span class="method-label" :class="methodClass(row.api.method)">{{ row.api.method }}</span>
                    <span class="api-tree-name" :title="apiTreeLabel(row.api)">{{ apiTreeLabel(row.api) }}</span>
                    <small v-if="debugCasesForApi(row.api.id).length">{{ debugCasesForApi(row.api.id).length }}</small>
                  </button>
                  <div class="tree-row-actions">
                    <a-dropdown trigger="click">
                      <a-button type="text" size="mini" :aria-label="`接口 ${row.api.name} 更多操作`" @click.stop><icon-more /></a-button>
                      <template #content>
                        <a-doption @click="openApiCase(row.api)"><icon-plus /> 添加用例</a-doption>
                        <a-doption @click="renameDefinition(row.api)"><icon-edit /> 重命名</a-doption>
                        <a-dsubmenu trigger="click">
                          <template #default><icon-robot /> AI 编程</template>
                          <template #content>
                            <a-doption disabled>生成请求参数（暂未支持）</a-doption>
                            <a-doption disabled>生成响应示例（暂未支持）</a-doption>
                          </template>
                        </a-dsubmenu>
                        <a-doption @click="copyDefinition(row.api)"><icon-copy /> 复制</a-doption>
                        <a-doption @click="copyText(currentApiLink(row.api), '协作链接已复制')"><icon-link /> 复制协作链接</a-doption>
                        <a-doption @click="copyDefinitionCurl(row.api)"><icon-code-square /> 复制 cURL</a-doption>
                        <a-dsubmenu trigger="click">
                          <template #default><icon-more /> 更多</template>
                          <template #content>
                            <a-doption disabled><icon-branch /> 复制到其他分支（暂未支持）</a-doption>
                            <a-dsubmenu trigger="click">
                              <template #default><icon-folder-add /> 移动到</template>
                              <template #content>
                                <a-doption
                                  v-for="target in catalogs"
                                  :key="target.id"
                                  :disabled="row.api.catalogId === target.id"
                                  @click="moveDefinition(row.api, target.id)"
                                >{{ target.name }}</a-doption>
                              </template>
                            </a-dsubmenu>
                            <a-doption @click="downloadDefinitionCurl(row.api)"><icon-export /> 导出 cURL</a-doption>
                            <a-doption disabled><icon-code /> 生成业务代码（暂未支持）</a-doption>
                          </template>
                        </a-dsubmenu>
                        <a-doption class="danger-option" @click="confirmDeleteDefinition(row.api)"><icon-delete /> 删除</a-doption>
                      </template>
                    </a-dropdown>
                  </div>
                </div>
                <template v-if="row.kind === 'api' && expandedApiCaseIds.has(row.api.id)">
                  <button
                    v-for="item in debugCasesForApi(row.api.id)"
                    :key="item.id"
                    type="button"
                    class="api-debug-case-row"
                    :style="{ paddingLeft: `${38 + row.depth * 17}px` }"
                    @click="openDebugCase(row.api, item)"
                  >
                    <icon-thunderbolt />
                    <span :title="item.name">{{ item.name }}</span>
                  </button>
                </template>
              </template>
              <a-empty v-if="!loading && !treeRows.length" :description="keyword || statusFilter !== 'ALL' ? '没有匹配的接口' : '还没有接口目录'">
                <template #extra><a-button type="outline" @click="openCatalog()">新建目录</a-button></template>
              </a-empty>
            </template>

            <a-alert v-if="legacyPreferencesAvailable" type="info">
              当前 origin 存在无用户归属标记的旧目录数据，未自动上传。
              <a-button :disabled="preferencesLocked" @click="confirmImportLegacyPreferences">确认归属并导入旧数据</a-button>
            </a-alert>
            <a-alert v-if="preferencesError" type="error" aria-live="polite">
              {{ preferencesError }}
              <a-button v-if="!preferencesConflict" :loading="preferencesSaving" @click="retryPreferences">{{ pendingPreferences ? '重试保存' : '重试加载/导入' }}</a-button>
              <a-button :disabled="preferencesSaving" :loading="preferencesLoading" @click="reloadPreferences">{{ pendingPreferences ? '放弃待保存操作并重新加载' : '仅加载服务端状态' }}</a-button>
            </a-alert>
            <span v-if="preferencesLoading || preferencesSaving" role="status">{{ preferencesSaving ? '正在保存目录/UI 状态…' : '正在加载目录/UI 状态…' }}</span>
            <section class="quick-request-tree-section" :class="{ active: isQuickRequest }">
              <div class="tree-root-row quick-request-root">
                <button type="button" class="tree-root-main" :aria-current="isQuickRequest ? 'page' : undefined" @click="openQuickRequest">
                  <icon-thunderbolt /><strong>快捷请求</strong><small>{{ visibleQuickRequestItems.length }}</small>
                </button>
                <a-button
                  type="text"
                  size="mini"
                  :aria-label="quickRequestRootExpanded ? '收起快捷请求' : '展开快捷请求'"
                  :aria-expanded="quickRequestRootExpanded"
                  @click="quickRequestRootExpanded = !quickRequestRootExpanded"
                >
                  <icon-down v-if="quickRequestRootExpanded" /><icon-right v-else />
                </a-button>
                <a-dropdown trigger="click">
                  <a-button type="text" size="mini" aria-label="快捷请求更多操作" @click.stop><icon-more /></a-button>
                  <template #content>
                    <a-doption @click="newQuickRequest()"><icon-thunderbolt /> 新建快捷请求</a-doption>
                    <a-doption :disabled="preferencesLocked" @click="addQuickRequestFolder(null)"><icon-folder-add /> 新建目录</a-doption>
                  </template>
                </a-dropdown>
              </div>
              <div v-if="quickRequestRootExpanded" class="quick-request-tree-items">
                <template v-for="row in quickRequestTreeRows" :key="row.key">
                  <div
                    v-if="row.kind === 'folder'"
                    class="quick-request-folder-row"
                    :style="{ paddingLeft: `${7 + row.depth * 16}px` }"
                  >
                    <icon-folder /><span>{{ row.folder.name }}</span>
                    <a-dropdown trigger="click">
                      <a-button type="text" size="mini" :aria-label="`目录 ${row.folder.name} 更多操作`" @click.stop><icon-more /></a-button>
                      <template #content>
                        <a-doption @click="newQuickRequest(row.folder.id)"><icon-thunderbolt /> 添加快捷请求</a-doption>
                        <a-doption :disabled="preferencesLocked" @click="addQuickRequestFolder(row.folder.id)"><icon-folder-add /> 添加目录</a-doption>
                        <a-doption :disabled="preferencesLocked" @click="renameQuickRequestFolder(row.folder)"><icon-edit /> 编辑</a-doption>
                        <a-doption :disabled="preferencesLocked" @click="copyQuickRequestFolder(row.folder)"><icon-copy /> 复制</a-doption>
                        <a-doption class="danger-option" :disabled="preferencesLocked" @click="confirmDeleteQuickRequestFolder(row.folder)"><icon-delete /> 删除</a-doption>
                      </template>
                    </a-dropdown>
                  </div>
                  <div
                    v-else
                    class="quick-request-history-row"
                    :style="{ paddingLeft: `${row.depth * 16}px` }"
                  >
                    <button
                      type="button"
                      class="quick-request-history"
                      :title="quickRequestLabel(row.item)"
                      @click="loadQuickRequest(row.item)"
                    >
                      <span class="method-label" :class="methodClass(row.item.method)">{{ row.item.method }}</span>
                      <span>{{ quickRequestLabel(row.item) }}</span>
                      <small v-if="row.item.importSourceId">迁入历史</small>
                      <small>{{ row.item.result === 'DRAFT' ? '草稿' : row.item.protocol === 'WEBSOCKET' && row.item.result === 'COMPLETED' ? '已连接' : row.item.responseStatus ?? '失败' }}</small>
                    </button>
                    <a-dropdown trigger="click">
                      <a-button type="text" size="mini" :aria-label="`快捷请求 ${quickRequestLabel(row.item)} 更多操作`" @click.stop><icon-more /></a-button>
                      <template #content>
                        <a-doption :disabled="preferencesLocked" @click="copyQuickRequest(row.item)"><icon-copy /> 复制</a-doption>
                        <a-dsubmenu trigger="click">
                          <template #default><icon-folder-add /> 移动到</template>
                          <template #content>
                            <a-doption :disabled="preferencesLocked || !quickRequestFolderIds[row.item.id]" @click="moveQuickRequest(row.item, null)"><icon-thunderbolt /> 快捷请求根目录</a-doption>
                            <a-doption
                              v-for="folder in quickRequestFolderRows"
                              :key="folder.id"
                              :disabled="preferencesLocked || quickRequestFolderIds[row.item.id] === folder.id"
                              @click="moveQuickRequest(row.item, folder.id)"
                            >
                              <icon-folder /> {{ folder.name }}
                            </a-doption>
                          </template>
                        </a-dsubmenu>
                        <a-doption class="danger-option" :disabled="preferencesLocked" @click="confirmDeleteQuickRequest(row.item)"><icon-delete /> 删除</a-doption>
                      </template>
                    </a-dropdown>
                  </div>
                </template>
                <a-empty v-if="!visibleQuickRequestItems.length && !quickRequestFolders.length" description="暂无快捷请求" />
              </div>
            </section>
          </div>
        </a-spin>

        <div v-if="interfaceRootExpanded" class="tree-footer">
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

    <main class="api-editor-pane" :class="{ 'api-editor-pane--quick': isQuickRequest }">
      <template v-if="isQuickRequest">
        <section class="document-panel quick-request-panel">
          <ApiDebugPage :key="route.fullPath" embedded @history-change="loadQuickRequestHistory" @project-change="changeProject" />
        </section>
      </template>
      <template v-else>
        <header class="workspace-tabbar">
          <div class="workspace-tabs-track">
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
            :open-case-request="route.query.apiId === selectedApi.id ? caseOpenRequest : 0"
            :open-debug-case="route.query.apiId === selectedApi.id ? debugCaseRequest : ''"
            @update:selected-version-id="selectedVersionId = $event"
            @edit="prepareInlineEditor"
            @new-version="openVersion"
            @publish="publishVersion"
            @delete="deleteDefinition(selectedApi)"
            @generate-code="previewCurl"
            @case-list-change="updateApiCaseList(selectedApi.id, $event)"
            @case-opened="clearCaseOpenRequest"
            @debug-case-opened="clearDebugCaseRequest"
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
      </template>
    </main>
    <a-modal v-model:visible="quickFolderDialog" :title="quickFolderForm.id ? '编辑快捷请求目录' : '新建快捷请求目录'" width="520px" :ok-loading="preferencesSaving" :on-before-ok="saveQuickRequestFolder" :mask-closable="false" :esc-to-close="!preferencesSaving" :closable="!preferencesSaving" :cancel-button-props="{ disabled: preferencesSaving }">
      <a-alert v-if="preferencesError" type="error">{{ preferencesError }}<a-button :disabled="preferencesSaving" :loading="preferencesLoading" @click="reloadPreferences">重新加载服务端状态（保留表单输入）</a-button></a-alert>
      <a-form :model="quickFolderForm" layout="vertical">
        <a-form-item label="父目录">
          <a-select :model-value="quickFolderForm.parentId ?? undefined" :disabled="preferencesSaving" allow-clear placeholder="快捷请求根目录" @update:model-value="quickFolderForm.parentId = typeof $event === 'string' ? $event : null" @clear="quickFolderForm.parentId = null">
            <a-option v-for="folder in quickFolderParentOptions" :key="folder.id" :value="folder.id">{{ '\u3000'.repeat(folder.depth) }}{{ folder.name }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="目录名称" required>
          <a-input v-model="quickFolderForm.name" :disabled="preferencesSaving" :max-length="160" placeholder="请输入快捷请求目录名称" @press-enter="async () => { if (await saveQuickRequestFolder()) quickFolderDialog = false }" />
        </a-form-item>
      </a-form>
    </a-modal>
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
          <a-form-item label="目标接口目录" required><a-select v-model="interchangeForm.catalogId"><a-option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalogOptionLabels.get(catalog.id) ?? catalog.name }}</a-option></a-select></a-form-item>
          <template v-if="interchangeKind === 'OPENAPI' || interchangeKind === 'POSTMAN' || interchangeKind === 'HAR'">
            <a-form-item label="接口定义文档" required :extra="`支持 OpenAPI 3.0/3.1、Swagger 2.0 JSON/YAML、Postman Collection 2.0/2.1 JSON 或 HAR 1.2 JSON；文件或粘贴内容上限 ${API_INTERCHANGE_MAX_LABEL}。`"><input ref="specInput" class="file-input" type="file" accept=".json,.yaml,.yml,.har,application/json,application/yaml,text/yaml" @change="specChanged" /><div class="spec-picker"><span>{{ specFileName || '尚未选择文件，可直接粘贴文档' }}</span><a-button @click="chooseSpecFile"><icon-upload /> 选择文件</a-button></div><a-textarea v-model="interchangeForm.document" :placeholder="interchangeKind === 'POSTMAN' ? '粘贴 Postman Collection 2.0/2.1 JSON 文档' : interchangeKind === 'HAR' ? '粘贴 HAR 1.2 JSON 文档' : '粘贴 OpenAPI 或 Swagger JSON/YAML 文档'" :auto-size="{ minRows: 10, maxRows: 16 }" /></a-form-item>
          </template>
          <template v-else>
            <a-form-item label="接口名称" extra="留空时使用方法与请求路径自动命名。"><a-input v-model="interchangeForm.name" placeholder="例如：创建订单" /></a-form-item>
            <a-form-item label="cURL 命令" required extra="支持 -X/--request、-H/--header、-d/--data-raw 等常用参数。"><a-textarea v-model="interchangeForm.command" :auto-size="{ minRows: 7, maxRows: 12 }" /></a-form-item>
          </template>
          <a-button type="primary" :loading="saving" @click="importInterchange"><icon-import /> 校验并导入</a-button>
        </a-form>
        <section v-if="interchangeResult" class="interchange-result">
          <a-result status="success" title="导入完成" :subtitle="`${interchangeResult.format} 已写入当前项目并记录审计日志。`" />
          <a-space wrap>
            <a-tag color="arcoblue">接口 {{ interchangeResult.importedCount }}</a-tag>
            <a-tag color="green">用例 {{ interchangeCaseCount }}</a-tag>
            <a-tag :color="interchangeSkippedCount ? 'orange' : 'gray'">跳过 {{ interchangeSkippedCount }}</a-tag>
          </a-space>
          <p class="interchange-result-summary">{{ interchangeResultSummary }}</p>
          <a-space wrap><a-tag v-for="item in interchangeResult.items" :key="item" color="arcoblue">{{ item }}</a-tag></a-space>
          <a-alert v-if="interchangeWarnings.length" type="warning" class="interchange-warnings">
            <strong>导入警告（{{ interchangeWarnings.length }}）</strong>
            <ul><li v-for="(warning, index) in interchangeWarnings" :key="`${index}:${warning}`">{{ warning }}</li></ul>
          </a-alert>
        </section>
        <section v-if="curlPreview" class="curl-preview"><div><strong>当前接口 cURL</strong><span>可复制到终端或 CI 配置中继续使用。</span></div><a-textarea :model-value="curlPreview" readonly :auto-size="{ minRows: 5, maxRows: 9 }" /></section>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.api-design-page{display:grid;grid-template-columns:340px minmax(0,1fr);height:calc(100vh - 68px);min-height:640px;overflow:hidden;background:#fff;transition:grid-template-columns .18s ease}.api-design-page.tree-is-collapsed{grid-template-columns:54px minmax(0,1fr)}.api-tree-pane{display:flex;min-width:0;min-height:0;flex-direction:column;border-right:1px solid #e8e9ee;background:#fff}.tree-header{display:flex;min-height:76px;align-items:center;justify-content:space-between;gap:12px;padding:15px 14px 10px 18px}.tree-header>div{display:grid;gap:5px;min-width:0}.tree-header h1{margin:0;color:#2e3748;font-size:22px;letter-spacing:-.5px}.tree-header span{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.tree-is-collapsed .tree-header{justify-content:center;padding:12px 6px}.branch-row{display:flex;align-items:center;gap:8px;margin:0 14px 12px;padding:8px 10px;border:0;border-radius:7px;background:transparent;color:#667189;cursor:default}.branch-row strong{font-size:13px}.branch-row svg:last-child{margin-left:auto}.tree-toolbar{display:grid;grid-template-columns:minmax(0,1fr) 38px 42px;gap:7px;padding:0 14px 13px}.tree-toolbar :deep(.arco-input-wrapper){height:38px;border-radius:8px;background:#f8f9fb}.tree-toolbar :deep(.arco-btn){height:38px;border-radius:8px}.tree-toolbar :deep(.arco-btn-primary){background:#8c62e8}.tree-project-row,.tree-root-row{display:flex;align-items:center;justify-content:space-between;padding:9px 18px;color:#6c778d}.tree-project-row{border-top:1px solid #f0f1f4;font-size:12px}.tree-project-row>div,.tree-root-row>div{display:flex;align-items:center;gap:8px}.tree-project-row small{font-size:10px}.tree-root-row{padding-top:12px;color:#465268}.tree-root-row span{color:#adb3be;font-size:11px}.interface-root-row{gap:2px;padding:4px 10px 4px 18px}.tree-root-toggle{width:100%;border:0;background:#fff;cursor:pointer}.tree-root-toggle:hover,.tree-root-toggle:focus-visible{background:#f7f8fa}.tree-root-toggle:focus-visible,.tree-root-main:focus-visible,.quick-request-history:focus-visible{outline:2px solid #8c62e8;outline-offset:-2px}.tree-spin{display:flex;min-height:0;flex:1;overflow:hidden}.tree-spin :deep(.arco-spin-children){display:flex;min-height:0;flex:1;flex-direction:column}.asset-tree-scroll{min-height:0;flex:1;padding:2px 7px 50px;overflow:auto}.asset-tree-entry{position:relative;display:flex;min-width:0;align-items:center;margin:1px 0;border-radius:7px}.asset-tree-entry:hover,.asset-tree-entry.active{background:#f1f2f5}.asset-tree-entry.active .api-tree-name{font-weight:650;color:#263247}.asset-tree-main{display:flex;min-width:0;flex:1;align-items:center;gap:7px;height:38px;padding:0 34px 0 6px;border:0;background:transparent;color:#4c586e;text-align:left;cursor:pointer}.asset-tree-main:focus-visible{outline:2px solid #8c62e8;outline-offset:-2px;border-radius:7px}.asset-tree-main>span:not(.method-label){overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.asset-tree-main>small{margin-left:auto;color:#929baa;font-size:10px}.tree-chevron{flex:0 0 auto;color:#8791a4;font-size:12px}.catalog-entry .asset-tree-main>svg:not(.tree-chevron){flex:0 0 auto;font-size:17px}.tree-row-actions{position:absolute;right:3px;display:none;align-items:center;background:linear-gradient(90deg,transparent,#f1f2f5 22%)}.asset-tree-entry:hover .tree-row-actions,.asset-tree-entry.active .tree-row-actions,.tree-row-actions:focus-within{display:flex}.asset-tree-entry:hover,.asset-tree-entry.active{background:#f1f2f5}.api-entry .asset-tree-main{gap:8px}.api-entry .arco-checkbox{flex:0 0 auto}.api-tree-name{min-width:0;flex:1;font-size:12px}.method-label{flex:0 0 auto;font-size:10px;font-weight:800;line-height:1;letter-spacing:.1px}.method-get{color:#14a36f}.method-post{color:#eb6b25}.method-put{color:#3974df}.method-patch{color:#875ce2}.method-delete{color:#e14d54}.method-head,.method-options{color:#75839a}.quick-request-tree-section{margin-top:10px;padding-top:8px;border-top:1px solid #edf0f5}.quick-request-tree-section.active{background:#faf9ff}.quick-request-root{padding:0 5px 0 10px}.tree-root-main{display:flex;min-width:0;flex:1;align-items:center;gap:8px;padding:9px 8px;border:0;border-radius:7px;background:transparent;color:#465268;text-align:left;cursor:pointer}.tree-root-main strong{font-size:13px}.tree-root-main small{margin-left:auto;color:#929baa;font-size:10px}.quick-request-tree-section.active .tree-root-main{color:#6f53cc;font-weight:650}.quick-request-tree-items{display:grid;gap:2px;padding:2px 3px 7px 24px}.quick-request-history-row,.quick-request-folder-row{display:flex;min-width:0;align-items:center;gap:2px}.quick-request-folder-row{gap:7px;height:32px;padding:0 7px;color:#576277;font-size:11px}.quick-request-folder-row:hover{background:#f0eff8;border-radius:6px}.quick-request-folder-row span{min-width:0;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.quick-request-history{display:flex;min-width:0;flex:1;align-items:center;gap:7px;height:32px;padding:0 7px;border:0;border-radius:6px;background:transparent;color:#576277;text-align:left;cursor:pointer}.quick-request-history:hover{background:#f0eff8}.quick-request-history>span:not(.method-label){min-width:0;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.quick-request-history small{color:#929baa;font-size:10px}.quick-request-tree-items :deep(.arco-empty){padding:10px 0}.tree-footer{display:flex;min-height:46px;align-items:center;justify-content:space-between;gap:8px;padding:7px 12px;border-top:1px solid #eceef2;background:#fff}.tree-footer :deep(.arco-btn-text){color:#6f7890}.api-editor-pane{display:grid;min-width:0;min-height:0;grid-template-rows:54px minmax(0,1fr);overflow:hidden;background:#fff}.api-editor-pane--quick{grid-template-rows:minmax(0,1fr)}.workspace-tabbar{display:flex;min-width:0;align-items:stretch;justify-content:space-between;border-bottom:1px solid #e7e9ee;background:#fff}.workspace-tabs-track{display:flex;min-width:0;flex:1;overflow-x:auto;scrollbar-width:none}.workspace-tabs-track::-webkit-scrollbar{display:none}.workspace-tab{position:relative;display:flex;min-width:130px;max-width:235px;align-items:center;justify-content:center;gap:8px;padding:0 16px;border:0;border-right:1px solid #eceef2;background:#fff;color:#687388;text-decoration:none;cursor:pointer}.workspace-tab>span:not(.method-label){overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.workspace-tab:hover{background:#fafafa}.workspace-tab.active{color:#2f384a;font-weight:650;background:#fff}.workspace-tab.active::after,.workspace-tab.router-link-active::after{position:absolute;right:10px;bottom:0;left:10px;height:2px;background:#8c62e8;content:""}.tab-close{flex:0 0 auto;color:#a5acb8;font-size:12px}.tab-close:hover{color:#e14d54}.workspace-tab-actions{display:flex;flex:0 0 auto;align-items:center;gap:3px;padding:0 10px;border-left:1px solid #eceef2}.workspace-tab-actions :deep(.arco-select-view){border-radius:8px;background:#f8f9fb}.document-panel{min-width:0;min-height:0;overflow:auto;background:#fff}.document-panel>.arco-empty{padding-top:120px}.quick-request-panel{height:100%}.danger-option{color:rgb(var(--red-6))}.interchange-dialog{display:grid;gap:16px}.interchange-actions,.spec-picker,.curl-preview>div{display:flex;align-items:center;gap:12px}.interchange-actions{justify-content:flex-start}.file-input{display:none}.spec-picker{justify-content:space-between;padding:9px 0}.spec-picker span,.curl-preview span{color:var(--color-text-3);font-size:12px}.spec-picker span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.interchange-result,.curl-preview{padding:14px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.interchange-result :deep(.arco-result){padding:4px 0 10px}.curl-preview{display:grid;gap:10px}@media(max-width:1180px){.api-design-page{grid-template-columns:300px minmax(0,1fr)}.workspace-tab-actions :deep(.arco-select){display:none}}@media(max-width:760px){.api-design-page{grid-template-columns:260px minmax(680px,1fr)}.interchange-actions,.spec-picker,.curl-preview>div{align-items:flex-start;flex-direction:column}}
.api-entry .asset-tree-main{gap:8px;padding-left:2px}.api-case-toggle{display:grid;flex:0 0 20px;width:20px;height:28px;place-items:center;padding:0;border:0;background:transparent;color:#8791a4;cursor:pointer}.api-case-toggle.hidden{visibility:hidden}.api-case-toggle svg{font-size:11px}.api-debug-case-row{display:flex;width:100%;height:31px;align-items:center;gap:8px;padding-right:32px;border:0;border-radius:6px;background:transparent;color:#687388;text-align:left;cursor:pointer}.api-debug-case-row:hover{background:#f3f1fb;color:#6f53cc}.api-debug-case-row svg{flex:0 0 auto;color:#8c62e8}.api-debug-case-row span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}
.interchange-result-summary{margin:10px 0;color:var(--color-text-2);font-size:12px}.interchange-warnings{margin-top:12px}.interchange-warnings ul{margin:8px 0 0;padding-left:20px}.interchange-warnings li+li{margin-top:4px}
</style>
