<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import RichTextEditor from '@/components/RichTextEditor.vue'
import RichTextViewer from '@/components/RichTextViewer.vue'
import { useFunctionalWorkspace } from '@/composables/useFunctionalWorkspace'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'
import {
  DEFAULT_REPRODUCTION_TEMPLATE,
  richTextPlainText,
  sanitizeRichText,
} from '@/utils/richText'

interface ApiEnvelope<T> { data: T }
interface Requirement { id: string; requirementCode: string; title: string }
interface TestCase { id: string; caseCode: string; title: string }
interface ProjectMember { userId: string; account: string; displayName: string; roleCode: string }
interface Defect {
  id: string
  displayId: number
  catalogId: string | null
  defectCode: string
  title: string
  dueDate: string | null
  bugType: string
  keywords: string
  browsers: string[]
  operatingSystems: string[]
  ccUserIds: string[]
  severity: string
  priority: string
  status: string
  confirmed: boolean
  assigneeId: string | null
  assigneeName: string | null
  assignedBy: string | null
  assignedByName: string | null
  resolution: string | null
  resolvedBy: string | null
  resolvedByName: string | null
  resolvedAt: string | null
  source: string
  externalUrl: string | null
  description: string
  createdBy: string
  createdByName: string
  createdAt: string
  updatedAt: string
  linkCount: number
}
interface DefectLink { id: string; assetType: string; assetId: string; assetLabel: string; createdAt: string }
interface ImportResult { type: string; completed: boolean; importedCount: number; errors: Array<{ rowNumber: number; message: string }> }
type AssetKind = 'REQUIREMENTS' | 'CASES'
type LinkType = 'REQUIREMENT' | 'CASE'
type ViewFilter = 'ALL' | 'UNCLOSED' | 'CREATED_BY_ME' | 'ASSIGNED_TO_ME' | 'RESOLVED_BY_ME' | 'ASSIGNED_BY_ME'

const bugTypeOptions = [
  { value: 'CODE_ERROR', label: '代码错误' },
  { value: 'CONFIGURATION', label: '配置相关' },
  { value: 'INSTALLATION', label: '安装部署' },
  { value: 'SECURITY', label: '安全相关' },
  { value: 'PERFORMANCE', label: '性能问题' },
  { value: 'STANDARD', label: '标准规范' },
  { value: 'TEST_SCRIPT', label: '测试脚本' },
  { value: 'DESIGN_DEFECT', label: '设计缺陷' },
  { value: 'OTHER', label: '其他' },
]
const browserOptions = [
  { value: 'CHROME', label: 'Chrome' },
  { value: 'EDGE', label: 'Edge' },
  { value: 'IE', label: 'IE系列' },
  { value: 'SAFARI', label: 'safari' },
  { value: '360_BROWSER', label: '360浏览器' },
  { value: 'QQ_BROWSER', label: 'QQ浏览器' },
  { value: 'OTHER', label: '其他' },
]
const operatingSystemOptions = [
  { value: 'WINDOWS', label: 'Windows' },
  { value: 'MAC_OS', label: 'Mac OS' },
  { value: 'LINUX', label: 'Linux' },
  { value: 'UBUNTU', label: 'Ubuntu' },
  { value: 'OTHER', label: '其他' },
]

const session = useSessionStore()
const workspace = useFunctionalWorkspace()
const projectId = computed(() => session.projectId)
const requirements = ref<Requirement[]>([])
const cases = ref<TestCase[]>([])
const members = ref<ProjectMember[]>([])
const defects = ref<Defect[]>([])
const selectedDefectIds = ref<string[]>([])
const selectedDefectId = ref('')
const links = ref<DefectLink[]>([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const viewFilter = ref<ViewFilter>('ALL')
const statusFilter = ref<string>()
const severityFilter = ref<string>()
const assigneeFilter = ref<string>()
const confirmedFilter = ref<string>()
const defectDialog = ref(false)
const detailVisible = ref(false)
const resolveVisible = ref(false)
const exchangeVisible = ref(false)
const editingId = ref<string | null>(null)
const resolvingDefect = ref<Defect | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadFile = ref<File | null>(null)
const importKind = ref<AssetKind>('REQUIREMENTS')
const importResult = ref<ImportResult | null>(null)
const defectErrors = reactive<Record<string, string>>({})
const defectForm = reactive({
  catalogId: '',
  defectCode: '',
  title: '',
  dueDate: '',
  bugType: 'CODE_ERROR',
  keywords: '',
  browsers: [] as string[],
  operatingSystems: [] as string[],
  ccUserIds: [] as string[],
  severity: 'MAJOR',
  priority: 'P1',
  status: 'OPEN',
  confirmed: false,
  assigneeId: '',
  resolution: '',
  source: 'TESTHUB',
  externalUrl: '',
  description: DEFAULT_REPRODUCTION_TEMPLATE,
})
const resolutionForm = reactive({ resolution: 'FIXED' })
const linkForm = reactive<{ assetType: LinkType; assetId: string }>({ assetType: 'CASE', assetId: '' })

const selectedDefect = computed(() => defects.value.find((item) => item.id === selectedDefectId.value) ?? null)
const unclosedCount = computed(() => defects.value.filter((item) => !['RESOLVED', 'CLOSED'].includes(item.status)).length)
const profileId = computed(() => session.profile?.id ?? '')
const viewTabs = computed(() => [
  { value: 'ALL' as const, label: '全部', count: defects.value.length },
  { value: 'UNCLOSED' as const, label: '未关闭', count: unclosedCount.value },
  { value: 'CREATED_BY_ME' as const, label: '由我创建' },
  { value: 'ASSIGNED_TO_ME' as const, label: '指派给我' },
  { value: 'RESOLVED_BY_ME' as const, label: '由我解决' },
  { value: 'ASSIGNED_BY_ME' as const, label: '由我指派' },
])
const filteredDefects = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return defects.value.filter((item) => {
    const matchesCatalog = !workspace.selectedCatalogId.value || item.catalogId === workspace.selectedCatalogId.value
    const matchesView = viewFilter.value === 'ALL'
      || (viewFilter.value === 'UNCLOSED' && !['RESOLVED', 'CLOSED'].includes(item.status))
      || (viewFilter.value === 'CREATED_BY_ME' && item.createdBy === profileId.value)
      || (viewFilter.value === 'ASSIGNED_TO_ME' && item.assigneeId === profileId.value)
      || (viewFilter.value === 'RESOLVED_BY_ME' && item.resolvedBy === profileId.value)
      || (viewFilter.value === 'ASSIGNED_BY_ME' && item.assignedBy === profileId.value)
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value
    const matchesSeverity = !severityFilter.value || item.severity === severityFilter.value
    const matchesAssignee = !assigneeFilter.value || item.assigneeId === assigneeFilter.value
    const matchesConfirmed = !confirmedFilter.value
      || (confirmedFilter.value === 'YES' ? item.confirmed : !item.confirmed)
    const matchesKeyword = !query
      || `${item.displayId} ${item.defectCode} ${item.title} ${item.createdByName} ${item.assigneeName ?? ''}`.toLowerCase().includes(query)
    return matchesCatalog && matchesView && matchesStatus && matchesSeverity && matchesAssignee
      && matchesConfirmed && matchesKeyword
  })
})
const linkAssets = computed(() => linkForm.assetType === 'CASE'
  ? cases.value.map((item) => ({ id: item.id, label: `${item.caseCode} · ${item.title}` }))
  : requirements.value.map((item) => ({ id: item.id, label: `${item.requirementCode} · ${item.title}` })))

const statusLabel = (value: string) => ({ OPEN: '激活', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }[value] ?? value)
const statusColor = (value: string) => ({ OPEN: 'arcoblue', IN_PROGRESS: 'orange', RESOLVED: 'green', CLOSED: 'gray' }[value] ?? 'gray')
const severityLabel = (value: string) => ({ CRITICAL: '致命', MAJOR: '严重', MINOR: '一般', TRIVIAL: '轻微' }[value] ?? value)
const severityClass = (value: string) => `severity-${value.toLowerCase()}`
const priorityLabel = (value: string) => ({ P0: '紧急', P1: '高', P2: '中', P3: '低' }[value] ?? value)
const optionLabel = (options: Array<{ value: string; label: string }>, value: string) =>
  options.find((option) => option.value === value)?.label ?? value
const optionLabels = (options: Array<{ value: string; label: string }>, values: string[]) =>
  values.length ? values.map((value) => optionLabel(options, value)).join('、') : '-'
const ccNames = (values: string[]) => values.length
  ? values.map((userId) => {
    const member = members.value.find((item) => item.userId === userId)
    return member?.displayName || member?.account || userId
  }).join('、')
  : '-'
const resolutionLabel = (value: string | null) => value ? ({
  FIXED: '已修复',
  DUPLICATE: '重复缺陷',
  BY_DESIGN: '设计如此',
  CANNOT_REPRODUCE: '无法重现',
  POSTPONED: '延期处理',
  EXTERNAL: '外部原因',
}[value] ?? value) : '-'
const assetLabel = (value: string) => ({ REQUIREMENT: '需求', CASE: '测试用例' }[value] ?? value)
const date = (value: string | null, dateOnly = false) => value
  ? new Intl.DateTimeFormat('zh-CN', dateOnly ? { dateStyle: 'medium' } : { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : '-'

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { code?: string; message?: string; details?: Record<string, unknown> }
  if (!response.ok) throw toApiRequestError(response, body)
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

async function load() {
  if (!projectId.value) return
  loading.value = true
  try {
    const [requirementData, caseData, defectData, memberData] = await Promise.all([
      read<Requirement[]>(`/api/v1/projects/${projectId.value}/functional/requirements`),
      read<TestCase[]>(`/api/v1/projects/${projectId.value}/functional/cases`),
      read<Defect[]>(`/api/v1/projects/${projectId.value}/functional/defects`),
      read<ProjectMember[]>(`/api/v1/projects/${projectId.value}/members`),
    ])
    requirements.value = requirementData
    cases.value = caseData
    defects.value = defectData
    members.value = memberData
    workspace.defects.value = defectData
    selectedDefectIds.value = selectedDefectIds.value.filter((id) => defectData.some((item) => item.id === id))
    if (selectedDefectId.value && !defectData.some((item) => item.id === selectedDefectId.value)) {
      selectedDefectId.value = ''
      links.value = []
    }
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载缺陷数据失败')
  } finally {
    loading.value = false
  }
}

function defectPayload(defect: Defect, patch: Partial<Defect> = {}) {
  const value = { ...defect, ...patch }
  return {
    catalogId: value.catalogId ?? '',
    defectCode: value.defectCode,
    title: value.title,
    dueDate: value.dueDate ?? null,
    bugType: value.bugType,
    keywords: value.keywords,
    browsers: value.browsers,
    operatingSystems: value.operatingSystems,
    ccUserIds: value.ccUserIds,
    severity: value.severity,
    priority: value.priority,
    status: value.status,
    confirmed: value.confirmed,
    assigneeId: value.assigneeId ?? '',
    resolution: value.resolution ?? '',
    source: value.source,
    externalUrl: value.externalUrl ?? '',
    description: value.description,
  }
}

function openDefect(defect?: Defect) {
  clearFormErrors(defectErrors)
  editingId.value = defect?.id ?? null
  Object.assign(defectForm, {
    catalogId: defect?.catalogId ?? workspace.selectedCatalogId.value,
    defectCode: defect?.defectCode ?? '',
    title: defect?.title ?? '',
    dueDate: defect?.dueDate ?? '',
    bugType: defect?.bugType ?? 'CODE_ERROR',
    keywords: defect?.keywords ?? '',
    browsers: [...(defect?.browsers ?? [])],
    operatingSystems: [...(defect?.operatingSystems ?? [])],
    ccUserIds: [...(defect?.ccUserIds ?? [])],
    severity: defect?.severity ?? 'MAJOR',
    priority: defect?.priority ?? 'P1',
    status: defect?.status ?? 'OPEN',
    confirmed: defect?.confirmed ?? false,
    assigneeId: defect?.assigneeId ?? '',
    resolution: defect?.resolution ?? '',
    source: defect?.source ?? 'TESTHUB',
    externalUrl: defect?.externalUrl ?? '',
    description: defect?.description ?? DEFAULT_REPRODUCTION_TEMPLATE,
  })
  defectDialog.value = true
}

async function saveDefect(): Promise<boolean> {
  clearFormErrors(defectErrors)
  const title = defectForm.title.trim()
  const source = defectForm.source.trim().toUpperCase()
  const externalUrl = defectForm.externalUrl.trim()
  const keywords = defectForm.keywords.trim()
  if (!title) defectErrors.title = '请输入 Bug 标题'
  else if (title.length > 200) defectErrors.title = 'Bug 标题不能超过 200 个字符'
  if (!/^[A-Z][A-Z0-9_-]{1,23}$/.test(source)) defectErrors.source = '来源需为 2-24 位大写字母、数字、下划线或连字符'
  if (externalUrl) {
    if (externalUrl.length > 1000) defectErrors.externalUrl = '外部链接不能超过 1000 个字符'
    else {
      try {
        const url = new URL(externalUrl)
        if (!['http:', 'https:'].includes(url.protocol)) defectErrors.externalUrl = '请输入 HTTP 或 HTTPS 地址'
      } catch {
        defectErrors.externalUrl = '请输入有效的 HTTP 或 HTTPS 地址'
      }
    }
  }
  if (defectForm.description.length > 20_000) defectErrors.description = '重现步骤不能超过 20000 个字符'
  else if (!richTextPlainText(defectForm.description)) defectErrors.description = '请填写重现步骤'
  if (keywords.length > 500) defectErrors.keywords = '关键词不能超过 500 个字符'
  if (Object.keys(defectErrors).length > 0) return false
  defectForm.title = title
  defectForm.source = source
  defectForm.externalUrl = externalUrl
  defectForm.keywords = keywords
  defectForm.description = sanitizeRichText(defectForm.description)
  saving.value = true
  try {
    const url = editingId.value
      ? `/api/v1/projects/${projectId.value}/functional/defects/${editingId.value}`
      : `/api/v1/projects/${projectId.value}/functional/defects`
    const saved = await mutate<Defect>(url, editingId.value ? 'PUT' : 'POST', defectForm)
    defectDialog.value = false
    selectedDefectId.value = saved.id
    await load()
    Message.success(editingId.value ? 'Bug 已更新' : 'Bug 已创建')
    return true
  } catch (error) {
    Object.assign(defectErrors, formErrorsFrom(error))
    if (Object.keys(defectErrors).length === 0) Message.error(error instanceof Error ? error.message : '保存 Bug 失败')
    return false
  } finally {
    saving.value = false
  }
}

async function updateDefect(defect: Defect, patch: Partial<Defect>, success: string): Promise<boolean> {
  saving.value = true
  try {
    await mutate<Defect>(`/api/v1/projects/${projectId.value}/functional/defects/${defect.id}`, 'PUT', defectPayload(defect, patch))
    await load()
    Message.success(success)
    return true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '更新 Bug 失败')
    return false
  } finally {
    saving.value = false
  }
}

function openResolve(defect: Defect) {
  resolvingDefect.value = defect
  resolutionForm.resolution = defect.resolution ?? 'FIXED'
  resolveVisible.value = true
}

async function resolveDefect(): Promise<boolean> {
  if (!resolvingDefect.value) return false
  const resolved = await updateDefect(resolvingDefect.value, {
    status: 'RESOLVED',
    resolution: resolutionForm.resolution,
    confirmed: true,
  }, 'Bug 已解决')
  if (resolved) resolveVisible.value = false
  return resolved
}

async function removeDefect(defect: Defect) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/functional/defects/${defect.id}`, 'DELETE')
    selectedDefectIds.value = selectedDefectIds.value.filter((id) => id !== defect.id)
    if (selectedDefectId.value === defect.id) {
      selectedDefectId.value = ''
      detailVisible.value = false
    }
    await load()
    Message.success('Bug 已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除 Bug 失败')
  }
}

async function deleteSelectedDefects() {
  if (!selectedDefectIds.value.length) return
  const ids = [...selectedDefectIds.value]
  try {
    await Promise.all(ids.map((id) => mutate(`/api/v1/projects/${projectId.value}/functional/defects/${id}`, 'DELETE')))
    selectedDefectIds.value = []
    selectedDefectId.value = ''
    await load()
    Message.success(`已删除 ${ids.length} 个 Bug`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '批量删除 Bug 失败')
  }
}

async function openDetail(defect: Defect) {
  selectedDefectId.value = defect.id
  detailVisible.value = true
  await loadLinks()
}

async function loadLinks() {
  links.value = selectedDefectId.value
    ? await read<DefectLink[]>(`/api/v1/projects/${projectId.value}/functional/defects/${selectedDefectId.value}/links`)
    : []
}

async function addLink() {
  if (!selectedDefectId.value || !linkForm.assetId) {
    Message.warning('请选择要关联的测试资产')
    return
  }
  try {
    await mutate(`/api/v1/projects/${projectId.value}/functional/defects/${selectedDefectId.value}/links`, 'POST', linkForm)
    linkForm.assetId = ''
    await Promise.all([loadLinks(), load()])
    Message.success('测试资产已关联')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '关联失败')
  }
}

async function removeLink(linkId: string) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/functional/defects/${selectedDefectId.value}/links/${linkId}`, 'DELETE')
    await Promise.all([loadLinks(), load()])
    Message.success('关联已解除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '解除关联失败')
  }
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = undefined
  severityFilter.value = undefined
  assigneeFilter.value = undefined
  confirmedFilter.value = undefined
}

function download(kind: AssetKind, template = false) {
  window.open(`/api/v1/projects/${projectId.value}/functional/assets/export?type=${kind}&template=${template}`, '_blank', 'noopener')
}
function chooseFile() { fileInput.value?.click() }
function fileChanged(event: Event) {
  uploadFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  importResult.value = null
}
async function importWorkbook() {
  if (!uploadFile.value) {
    Message.warning('请先选择 .xlsx 文件')
    return
  }
  saving.value = true
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    const form = new FormData()
    form.append('type', importKind.value)
    form.append('file', uploadFile.value)
    importResult.value = await read<ImportResult>(`/api/v1/projects/${projectId.value}/functional/assets/import`, {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
      body: form,
    })
    if (importResult.value.completed) {
      await load()
      Message.success(`已导入 ${importResult.value.importedCount} 条资产`)
    } else {
      Message.warning('导入预检未通过，请处理错误后重试')
    }
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    saving.value = false
  }
}

watch(projectId, (current, previous) => {
  if (current && current !== previous) load()
})
onMounted(load)
</script>

<template>
  <section class="panel bug-workbench">
    <header class="bug-toolbar">
      <div class="view-tabs" role="tablist" aria-label="Bug 视图">
        <button
          v-for="tab in viewTabs"
          :key="tab.value"
          type="button"
          :class="{ active: viewFilter === tab.value }"
          @click="viewFilter = tab.value"
        >
          {{ tab.label }} <b v-if="tab.count !== undefined">{{ tab.count }}</b>
        </button>
      </div>
      <div class="primary-actions">
        <a-dropdown trigger="click">
          <a-button><icon-export /> 导出</a-button>
          <template #content>
            <a-doption @click="download('REQUIREMENTS')">导出需求</a-doption>
            <a-doption @click="download('CASES')">导出测试用例</a-doption>
            <a-doption @click="download('REQUIREMENTS', true)">下载需求模板</a-doption>
            <a-doption @click="download('CASES', true)">下载用例模板</a-doption>
          </template>
        </a-dropdown>
        <a-button @click="exchangeVisible = true"><icon-swap /> 导入与交换</a-button>
        <a-button type="primary" @click="openDefect()"><icon-plus /> 提 Bug</a-button>
        <a-popconfirm
          :disabled="!selectedDefectIds.length"
          :content="`确认删除选中的 ${selectedDefectIds.length} 个 Bug 吗？`"
          @ok="deleteSelectedDefects"
        >
          <a-button status="danger" :disabled="!selectedDefectIds.length"><icon-delete /> 批量删除（{{ selectedDefectIds.length }}）</a-button>
        </a-popconfirm>
      </div>
    </header>

    <div class="filter-toolbar">
      <a-select v-model="statusFilter" allow-clear placeholder="全部状态" :style="{ width: '124px' }">
        <a-option value="OPEN">激活</a-option>
        <a-option value="IN_PROGRESS">处理中</a-option>
        <a-option value="RESOLVED">已解决</a-option>
        <a-option value="CLOSED">已关闭</a-option>
      </a-select>
      <a-select v-model="severityFilter" allow-clear placeholder="全部严重程度" :style="{ width: '142px' }">
        <a-option value="CRITICAL">致命</a-option>
        <a-option value="MAJOR">严重</a-option>
        <a-option value="MINOR">一般</a-option>
        <a-option value="TRIVIAL">轻微</a-option>
      </a-select>
      <a-select v-model="assigneeFilter" allow-clear allow-search placeholder="全部指派人" :style="{ width: '142px' }">
        <a-option v-for="member in members" :key="member.userId" :value="member.userId">{{ member.displayName || member.account }}</a-option>
      </a-select>
      <a-select v-model="confirmedFilter" allow-clear placeholder="全部确认状态" :style="{ width: '142px' }">
        <a-option value="YES">已确认</a-option>
        <a-option value="NO">未确认</a-option>
      </a-select>
      <a-input-search v-model="keyword" allow-clear placeholder="搜索 ID、Bug 标题、创建人或指派人" :style="{ width: '300px' }" />
      <a-button type="text" @click="resetFilters">重置</a-button>
      <span class="result-count">共 {{ filteredDefects.length }} 项</span>
    </div>

    <a-spin :loading="loading">
      <a-table
        v-model:selected-keys="selectedDefectIds"
        :data="filteredDefects"
        :row-selection="{ type: 'checkbox', showCheckedAll: true }"
        :scroll="{ x: 1550 }"
        :pagination="{ pageSize: 20, showTotal: true }"
        row-key="id"
        :bordered="false"
      >
        <template #columns>
          <a-table-column title="ID" data-index="displayId" :width="82" :sortable="{ sortDirections: ['ascend', 'descend'], sorter: true }" />
          <a-table-column title="Bug 标题" data-index="title" :min-width="290">
            <template #cell="{ record }">
              <button class="bug-title" type="button" @click="openDetail(record)">{{ record.title }}</button>
            </template>
          </a-table-column>
          <a-table-column title="严重程度" :width="106">
            <template #cell="{ record }"><span class="severity" :class="severityClass(record.severity)">{{ severityLabel(record.severity) }}</span></template>
          </a-table-column>
          <a-table-column title="优先级" :width="92">
            <template #cell="{ record }"><a-tag color="arcoblue">{{ priorityLabel(record.priority) }}</a-tag></template>
          </a-table-column>
          <a-table-column title="状态" :width="102">
            <template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template>
          </a-table-column>
          <a-table-column title="创建者" :width="118"><template #cell="{ record }">{{ record.createdByName || '-' }}</template></a-table-column>
          <a-table-column title="创建日期" :width="130"><template #cell="{ record }">{{ date(record.createdAt, true) }}</template></a-table-column>
          <a-table-column title="是否确认" :width="96">
            <template #cell="{ record }"><span :class="{ confirmed: record.confirmed }">{{ record.confirmed ? '已确认' : '未确认' }}</span></template>
          </a-table-column>
          <a-table-column title="指派给" :width="118"><template #cell="{ record }">{{ record.assigneeName || '-' }}</template></a-table-column>
          <a-table-column title="解决人" :width="118"><template #cell="{ record }">{{ record.resolvedByName || '-' }}</template></a-table-column>
          <a-table-column title="解决方案" :width="122"><template #cell="{ record }">{{ resolutionLabel(record.resolution) }}</template></a-table-column>
          <a-table-column title="操作" fixed="right" :width="210">
            <template #cell="{ record }">
              <a-space :size="2">
                <a-tooltip v-if="!record.confirmed" content="确认 Bug">
                  <a-button type="text" shape="circle" aria-label="确认 Bug" @click="updateDefect(record, { confirmed: true }, 'Bug 已确认')"><icon-check-circle /></a-button>
                </a-tooltip>
                <a-tooltip v-if="!['RESOLVED', 'CLOSED'].includes(record.status)" content="解决">
                  <a-button type="text" shape="circle" aria-label="解决 Bug" @click="openResolve(record)"><icon-check /></a-button>
                </a-tooltip>
                <a-popconfirm v-if="record.status === 'RESOLVED'" content="确认关闭该 Bug 吗？" @ok="updateDefect(record, { status: 'CLOSED' }, 'Bug 已关闭')">
                  <a-tooltip content="关闭"><a-button type="text" shape="circle" aria-label="关闭 Bug"><icon-stop /></a-button></a-tooltip>
                </a-popconfirm>
                <a-tooltip v-if="['RESOLVED', 'CLOSED'].includes(record.status)" content="激活">
                  <a-button type="text" shape="circle" aria-label="激活 Bug" @click="updateDefect(record, { status: 'OPEN', resolution: null }, 'Bug 已重新激活')"><icon-refresh /></a-button>
                </a-tooltip>
                <a-tooltip content="编辑">
                  <a-button type="text" shape="circle" aria-label="编辑 Bug" @click="openDefect(record)"><icon-edit /></a-button>
                </a-tooltip>
                <a-popconfirm content="删除后关联关系也会移除，确认继续吗？" @ok="removeDefect(record)">
                  <a-tooltip content="删除"><a-button type="text" shape="circle" status="danger" aria-label="删除 Bug"><icon-delete /></a-button></a-tooltip>
                </a-popconfirm>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
      <a-empty v-if="!loading && filteredDefects.length === 0" description="当前视图暂无 Bug">
        <template #extra><a-button type="primary" @click="openDefect()">提交第一个 Bug</a-button></template>
      </a-empty>
    </a-spin>
  </section>

  <a-modal
    v-model:visible="defectDialog"
    :title="editingId ? `编辑 Bug #${defects.find((item) => item.id === editingId)?.displayId ?? ''}` : '提交 Bug'"
    width="980px"
    :ok-loading="saving"
    :on-before-ok="saveDefect"
  >
    <a-form :model="defectForm" layout="vertical">
      <a-form-item label="所属目录" :validate-status="defectErrors.catalogId ? 'error' : undefined" :help="defectErrors.catalogId">
        <a-select v-model="defectForm.catalogId" allow-clear placeholder="未分类">
          <a-option v-for="catalog in workspace.catalogs.value" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option>
        </a-select>
      </a-form-item>
      <a-form-item label="Bug 标题" required :validate-status="defectErrors.title ? 'error' : undefined" :help="defectErrors.title">
        <a-input v-model="defectForm.title" placeholder="简明描述问题现象" @input="delete defectErrors.title" />
      </a-form-item>
      <div class="form-grid">
        <a-form-item label="Bug 类型">
          <a-select v-model="defectForm.bugType" allow-search>
            <a-option v-for="option in bugTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="截止日期">
          <a-date-picker v-model="defectForm.dueDate" allow-clear value-format="YYYY-MM-DD" placeholder="请选择截止日期" style="width:100%" />
        </a-form-item>
      </div>
      <a-form-item label="关键词" :validate-status="defectErrors.keywords ? 'error' : undefined" :help="defectErrors.keywords">
        <a-input v-model="defectForm.keywords" :max-length="500" show-word-limit placeholder="多个关键词可使用空格或逗号分隔" @input="delete defectErrors.keywords" />
      </a-form-item>
      <div class="form-grid">
        <a-form-item label="严重程度">
          <a-select v-model="defectForm.severity">
            <a-option value="CRITICAL">致命</a-option><a-option value="MAJOR">严重</a-option>
            <a-option value="MINOR">一般</a-option><a-option value="TRIVIAL">轻微</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="优先级">
          <a-select v-model="defectForm.priority">
            <a-option value="P0">紧急</a-option><a-option value="P1">高</a-option>
            <a-option value="P2">中</a-option><a-option value="P3">低</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model="defectForm.status">
            <a-option value="OPEN">激活</a-option><a-option value="IN_PROGRESS">处理中</a-option>
            <a-option value="RESOLVED">已解决</a-option><a-option value="CLOSED">已关闭</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="指派给" :validate-status="defectErrors.assigneeId ? 'error' : undefined" :help="defectErrors.assigneeId">
          <a-select v-model="defectForm.assigneeId" allow-clear allow-search placeholder="暂不指派">
            <a-option v-for="member in members" :key="member.userId" :value="member.userId">{{ member.displayName || member.account }}</a-option>
          </a-select>
        </a-form-item>
      </div>
      <div class="form-grid">
        <a-form-item label="浏览器">
          <a-select v-model="defectForm.browsers" multiple allow-clear allow-search placeholder="全部或未指定">
            <a-option v-for="option in browserOptions" :key="option.value" :value="option.value">{{ option.label }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="操作系统">
          <a-select v-model="defectForm.operatingSystems" multiple allow-clear allow-search placeholder="全部或未指定">
            <a-option v-for="option in operatingSystemOptions" :key="option.value" :value="option.value">{{ option.label }}</a-option>
          </a-select>
        </a-form-item>
      </div>
      <a-form-item label="抄送给" :validate-status="defectErrors.ccUserIds ? 'error' : undefined" :help="defectErrors.ccUserIds">
        <a-select v-model="defectForm.ccUserIds" multiple allow-clear allow-search placeholder="选择项目成员">
          <a-option v-for="member in members" :key="member.userId" :value="member.userId">{{ member.displayName || member.account }}</a-option>
        </a-select>
      </a-form-item>
      <a-form-item v-if="['RESOLVED', 'CLOSED'].includes(defectForm.status)" label="解决方案">
        <a-select v-model="defectForm.resolution">
          <a-option value="FIXED">已修复</a-option><a-option value="DUPLICATE">重复缺陷</a-option>
          <a-option value="BY_DESIGN">设计如此</a-option><a-option value="CANNOT_REPRODUCE">无法重现</a-option>
          <a-option value="POSTPONED">延期处理</a-option><a-option value="EXTERNAL">外部原因</a-option>
        </a-select>
      </a-form-item>
      <div class="form-grid">
        <a-form-item label="来源" :validate-status="defectErrors.source ? 'error' : undefined" :help="defectErrors.source">
          <a-input v-model="defectForm.source" placeholder="TESTHUB" @input="delete defectErrors.source" />
        </a-form-item>
        <a-form-item label="外部链接" :validate-status="defectErrors.externalUrl ? 'error' : undefined" :help="defectErrors.externalUrl">
          <a-input v-model="defectForm.externalUrl" placeholder="https://..." @input="delete defectErrors.externalUrl" />
        </a-form-item>
      </div>
      <a-form-item label="重现步骤" required :validate-status="defectErrors.description ? 'error' : undefined" :help="defectErrors.description">
        <RichTextEditor
          v-model="defectForm.description"
          :min-height="250"
          placeholder="请填写步骤、实际结果和期望结果"
          @update:model-value="delete defectErrors.description"
        />
      </a-form-item>
      <a-checkbox v-model="defectForm.confirmed">标记为已确认</a-checkbox>
    </a-form>
  </a-modal>

  <a-modal v-model:visible="resolveVisible" title="解决 Bug" width="480px" :ok-loading="saving" :on-before-ok="resolveDefect">
    <a-form :model="resolutionForm" layout="vertical">
      <a-alert type="info">解决后将记录当前操作人为解决人和解决时间。</a-alert>
      <a-form-item label="解决方案" required>
        <a-select v-model="resolutionForm.resolution">
          <a-option value="FIXED">已修复</a-option><a-option value="DUPLICATE">重复缺陷</a-option>
          <a-option value="BY_DESIGN">设计如此</a-option><a-option value="CANNOT_REPRODUCE">无法重现</a-option>
          <a-option value="POSTPONED">延期处理</a-option><a-option value="EXTERNAL">外部原因</a-option>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>

  <a-drawer v-model:visible="detailVisible" :width="620" :footer="false">
    <template #title>Bug #{{ selectedDefect?.displayId }} · 详情</template>
    <template v-if="selectedDefect">
      <div class="detail-heading">
        <div><span>{{ selectedDefect.defectCode }}</span><h2>{{ selectedDefect.title }}</h2></div>
        <a-button type="primary" size="small" @click="openDefect(selectedDefect)">编辑</a-button>
      </div>
      <div class="detail-meta">
        <div><span>状态</span><a-tag :color="statusColor(selectedDefect.status)">{{ statusLabel(selectedDefect.status) }}</a-tag></div>
        <div><span>严重程度</span><strong>{{ severityLabel(selectedDefect.severity) }}</strong></div>
        <div><span>优先级</span><strong>{{ priorityLabel(selectedDefect.priority) }}</strong></div>
        <div><span>Bug 类型</span><strong>{{ optionLabel(bugTypeOptions, selectedDefect.bugType) }}</strong></div>
        <div><span>截止日期</span><strong>{{ selectedDefect.dueDate || '-' }}</strong></div>
        <div><span>指派给</span><strong>{{ selectedDefect.assigneeName || '-' }}</strong></div>
        <div><span>浏览器</span><strong>{{ optionLabels(browserOptions, selectedDefect.browsers) }}</strong></div>
        <div><span>操作系统</span><strong>{{ optionLabels(operatingSystemOptions, selectedDefect.operatingSystems) }}</strong></div>
        <div><span>抄送给</span><strong>{{ ccNames(selectedDefect.ccUserIds) }}</strong></div>
        <div><span>关键词</span><strong>{{ selectedDefect.keywords || '-' }}</strong></div>
        <div><span>创建者</span><strong>{{ selectedDefect.createdByName || '-' }}</strong></div>
        <div><span>创建时间</span><strong>{{ date(selectedDefect.createdAt) }}</strong></div>
        <div><span>解决人</span><strong>{{ selectedDefect.resolvedByName || '-' }}</strong></div>
        <div><span>解决方案</span><strong>{{ resolutionLabel(selectedDefect.resolution) }}</strong></div>
      </div>
      <section class="description-box">
        <span>重现步骤</span>
        <RichTextViewer class="rich-description" :value="selectedDefect.description" />
        <a v-if="selectedDefect.externalUrl" :href="selectedDefect.externalUrl" target="_blank" rel="noopener">查看外部缺陷链接</a>
      </section>
      <section class="trace-section">
        <div class="trace-title"><strong>关联测试资产</strong><span>{{ links.length }} 条</span></div>
        <div class="link-form">
          <a-select v-model="linkForm.assetType" :style="{ width: '112px' }" @change="linkForm.assetId = ''">
            <a-option value="CASE">测试用例</a-option><a-option value="REQUIREMENT">需求</a-option>
          </a-select>
          <a-select v-model="linkForm.assetId" allow-search allow-clear placeholder="选择要关联的资产" :style="{ flex: 1 }">
            <a-option v-for="asset in linkAssets" :key="asset.id" :value="asset.id">{{ asset.label }}</a-option>
          </a-select>
          <a-button type="primary" @click="addLink"><icon-link /> 关联</a-button>
        </div>
        <div v-for="link in links" :key="link.id" class="link-row">
          <div><a-tag color="arcoblue">{{ assetLabel(link.assetType) }}</a-tag><strong>{{ link.assetLabel }}</strong><small>{{ date(link.createdAt) }}</small></div>
          <a-popconfirm content="确认解除该关联吗？" @ok="removeLink(link.id)"><a-button type="text" status="danger"><icon-delete /></a-button></a-popconfirm>
        </div>
        <a-empty v-if="links.length === 0" description="尚未关联测试资产" />
      </section>
    </template>
  </a-drawer>

  <a-drawer v-model:visible="exchangeVisible" title="资产导入与交换" :width="720" :footer="false">
    <a-alert type="info">仅接受平台模板的 .xlsx 文件；导入会先完整预检，任意行失败都不会写入数据。单次最多 500 行、5 MB。</a-alert>
    <div class="exchange-grid">
      <section>
        <h3>导出与模板</h3>
        <a-radio-group v-model="importKind" type="button">
          <a-radio value="REQUIREMENTS">需求</a-radio><a-radio value="CASES">测试用例</a-radio>
        </a-radio-group>
        <a-space wrap>
          <a-button @click="download(importKind)"><icon-export /> 导出当前数据</a-button>
          <a-button @click="download(importKind, true)"><icon-file /> 下载标准模板</a-button>
        </a-space>
      </section>
      <section>
        <h3>导入工作簿</h3>
        <input ref="fileInput" class="file-input" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="fileChanged" />
        <div class="file-picker"><span>{{ uploadFile?.name ?? '尚未选择文件' }}</span><a-button @click="chooseFile"><icon-upload /> 选择文件</a-button></div>
        <a-button type="primary" :loading="saving" :disabled="!uploadFile" @click="importWorkbook"><icon-import /> 预检并导入</a-button>
      </section>
      <section v-if="importResult">
        <a-result :status="importResult.completed ? 'success' : 'warning'" :title="importResult.completed ? `已导入 ${importResult.importedCount} 条资产` : '导入预检未通过'" />
        <div v-if="importResult.errors.length" class="import-errors">
          <div v-for="error in importResult.errors" :key="`${error.rowNumber}-${error.message}`"><a-tag color="red">第 {{ error.rowNumber }} 行</a-tag><span>{{ error.message }}</span></div>
        </div>
      </section>
    </div>
  </a-drawer>
</template>

<style scoped>
.bug-workbench{padding:0;overflow:hidden;min-height:650px}.bug-workbench :deep(.arco-spin){display:block;width:100%}.bug-toolbar{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:12px 16px;border-bottom:1px solid var(--color-border-2)}.view-tabs,.primary-actions,.filter-toolbar{display:flex;align-items:center;gap:6px}.view-tabs{min-width:0;overflow-x:auto}.view-tabs button{display:flex;align-items:center;gap:5px;min-height:36px;padding:0 12px;color:var(--color-text-2);font-size:13px;white-space:nowrap;border:0;border-radius:5px;background:transparent;cursor:pointer}.view-tabs button:hover,.view-tabs button.active{color:rgb(var(--primary-6));background:rgb(var(--primary-1))}.view-tabs b{display:inline-grid;place-items:center;min-width:20px;height:20px;padding:0 5px;color:rgb(var(--primary-6));font-size:11px;border-radius:10px;background:#fff}.primary-actions{flex-shrink:0}.filter-toolbar{padding:11px 16px;background:var(--color-fill-1);border-bottom:1px solid var(--color-border-2)}.result-count{margin-left:auto;color:var(--color-text-3);font-size:12px}.bug-title{max-width:100%;overflow:hidden;color:#234caa;font-weight:600;text-align:left;text-overflow:ellipsis;white-space:nowrap;border:0;background:transparent;cursor:pointer}.bug-title:hover{text-decoration:underline}.severity{font-weight:600}.severity-critical{color:rgb(var(--danger-6))}.severity-major{color:#f28b16}.severity-minor{color:#b38a47}.severity-trivial{color:var(--color-text-3)}.confirmed{color:rgb(var(--success-6))}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.detail-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding-bottom:18px;border-bottom:1px solid var(--color-border-2)}.detail-heading span{color:rgb(var(--primary-6));font-size:12px}.detail-heading h2{margin:5px 0 0;font-size:20px;line-height:1.45}.detail-meta{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin:18px 0}.detail-meta>div{display:grid;gap:5px;padding:11px;border-radius:7px;background:var(--color-fill-1)}.detail-meta span,.description-box>span{color:var(--color-text-3);font-size:12px}.detail-meta strong{font-size:13px}.description-box{padding:15px;border:1px solid var(--color-border-2);border-radius:8px}.rich-description{min-height:72px;margin:9px 0;line-height:1.75;overflow-wrap:anywhere}.rich-description :deep(p){margin:0 0 10px}.rich-description :deep(table){width:100%;border-collapse:collapse}.rich-description :deep(th),.rich-description :deep(td){padding:6px 8px;border:1px solid var(--color-border-3)}.rich-description :deep(img){max-width:100%;height:auto}.trace-section{margin-top:22px}.trace-title{display:flex;justify-content:space-between;margin-bottom:12px}.trace-title span,.link-row small{color:var(--color-text-3);font-size:12px}.link-form{display:flex;gap:8px;margin-bottom:12px}.link-row{display:flex;align-items:center;justify-content:space-between;padding:10px 0;border-bottom:1px solid var(--color-border-2)}.link-row>div{display:flex;align-items:center;gap:8px;min-width:0}.link-row strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.exchange-grid{display:grid;gap:18px;margin-top:18px}.exchange-grid>section{display:grid;gap:12px;padding:16px;border:1px solid var(--color-border-2);border-radius:8px}.exchange-grid h3{margin:0}.file-input{display:none}.file-picker{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px;background:var(--color-fill-1);border-radius:6px}.file-picker span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.import-errors{display:grid;gap:8px;max-height:210px;overflow:auto}.import-errors>div{display:flex;gap:8px}.bug-workbench :deep(.arco-table-th.arco-table-col-fixed-right),.bug-workbench :deep(.arco-table-td.arco-table-col-fixed-right){background:var(--color-bg-2)}@media(max-width:1380px){.bug-toolbar{align-items:flex-start;flex-direction:column}.primary-actions{width:100%;justify-content:flex-end}.filter-toolbar{flex-wrap:wrap}.result-count{width:100%;text-align:right}}@media(max-width:800px){.primary-actions{flex-wrap:wrap;justify-content:flex-start}.form-grid,.detail-meta{grid-template-columns:1fr}.link-form{flex-direction:column}.link-form :deep(.arco-select),.link-form :deep(.arco-btn){width:100%!important}}
</style>
