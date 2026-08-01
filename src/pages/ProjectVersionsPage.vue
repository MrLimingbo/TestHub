<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRoute } from 'vue-router'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
type VersionStatus = 'UNSTARTED' | 'RELEASED' | 'FAILED' | 'STOPPED'
interface ProjectVersion {
  id: string
  displayId: number
  projectId: string
  projectName: string
  versionNumber: string
  buildNumbers: string[]
  status: VersionStatus
  plannedReleaseDate: string | null
  actualReleaseDate: string | null
  description: string
  createdByName: string
  createdAt: string
  updatedAt: string
}

const session = useSessionStore()
const route = useRoute()
const versions = ref<ProjectVersion[]>([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const statusFilter = ref<'ALL' | VersionStatus>('ALL')
const editorVisible = ref(false)
const detailVisible = ref(false)
const editingId = ref<string | null>(null)
const selectedVersion = ref<ProjectVersion | null>(null)
const formErrors = reactive<Record<string, string>>({})
const form = reactive({
  versionNumber: '',
  buildNumbers: [] as string[],
  status: 'UNSTARTED' as VersionStatus,
  plannedReleaseDate: '',
  actualReleaseDate: '',
  description: '',
})

const projectId = computed(() => session.projectId)
const projectName = computed(() => session.currentProject?.name ?? '当前项目')
const filters = computed(() => [
  { value: 'ALL' as const, label: '全部', count: versions.value.length },
  { value: 'UNSTARTED' as const, label: '未开始', count: count('UNSTARTED') },
  { value: 'RELEASED' as const, label: '已发布', count: count('RELEASED') },
  { value: 'FAILED' as const, label: '发布失败', count: count('FAILED') },
  { value: 'STOPPED' as const, label: '停止维护', count: count('STOPPED') },
])
const filteredVersions = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return versions.value.filter((version) => {
    const matchesStatus = statusFilter.value === 'ALL' || version.status === statusFilter.value
    const searchText = [
      version.displayId,
      version.projectName,
      version.versionNumber,
      version.buildNumbers.join(' '),
      version.description,
    ].join(' ').toLowerCase()
    return matchesStatus && (!query || searchText.includes(query))
  })
})
const releasedCount = computed(() => versions.value.filter((item) => item.status === 'RELEASED').length)
const stoppedCount = computed(() => versions.value.filter((item) => item.status === 'STOPPED').length)

const statusLabel = (status: VersionStatus) => ({
  UNSTARTED: '未开始',
  RELEASED: '已发布',
  FAILED: '发布失败',
  STOPPED: '停止维护',
}[status])
const statusColor = (status: VersionStatus) => ({
  UNSTARTED: 'gray',
  RELEASED: 'green',
  FAILED: 'red',
  STOPPED: 'orangered',
}[status])
const count = (status: VersionStatus) => versions.value.filter((item) => item.status === status).length
const date = (value: string | null) => value || '—'
const dateTime = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'short',
}).format(new Date(value))

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & {
    code?: string
    message?: string
    details?: Record<string, unknown>
  }
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
    versions.value = await read<ProjectVersion[]>(`/api/v1/projects/${projectId.value}/versions`)
    const linkedVersionId = typeof route.query.version === 'string' ? route.query.version : ''
    const linkedVersion = versions.value.find((version) => version.id === linkedVersionId)
    if (linkedVersion) showDetail(linkedVersion)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载版本列表失败')
  } finally {
    loading.value = false
  }
}

function openEditor(version?: ProjectVersion) {
  clearFormErrors(formErrors)
  editingId.value = version?.id ?? null
  Object.assign(form, {
    versionNumber: version?.versionNumber ?? '',
    buildNumbers: [...(version?.buildNumbers ?? [])],
    status: version?.status ?? 'UNSTARTED',
    plannedReleaseDate: version?.plannedReleaseDate ?? '',
    actualReleaseDate: version?.actualReleaseDate ?? '',
    description: version?.description ?? '',
  })
  editorVisible.value = true
}

async function save(): Promise<boolean> {
  clearFormErrors(formErrors)
  form.versionNumber = form.versionNumber.trim()
  form.buildNumbers = form.buildNumbers.map((item) => item.trim()).filter(Boolean)
  form.description = form.description.trim()
  if (!form.versionNumber) formErrors.versionNumber = '请输入版本号'
  if (form.versionNumber.length > 100) formErrors.versionNumber = '版本号不能超过100个字符'
  if (form.buildNumbers.length > 50) formErrors.buildNumbers = '包含构建不能超过50个'
  if (form.buildNumbers.some((item) => item.length > 100)) formErrors.buildNumbers = '单个构建号不能超过100个字符'
  if (form.description.length > 20_000) formErrors.description = '版本描述不能超过20000个字符'
  if (Object.keys(formErrors).length > 0) return false
  saving.value = true
  try {
    const url = editingId.value
      ? `/api/v1/projects/${projectId.value}/versions/${editingId.value}`
      : `/api/v1/projects/${projectId.value}/versions`
    await mutate<ProjectVersion>(url, editingId.value ? 'PUT' : 'POST', {
      ...form,
      plannedReleaseDate: form.plannedReleaseDate || null,
      actualReleaseDate: form.actualReleaseDate || null,
    })
    editorVisible.value = false
    await load()
    Message.success(editingId.value ? '版本已更新' : '版本已创建')
    return true
  } catch (error) {
    Object.assign(formErrors, formErrorsFrom(error))
    if (Object.keys(formErrors).length === 0) {
      Message.error(error instanceof Error ? error.message : '保存版本失败')
    }
    return false
  } finally {
    saving.value = false
  }
}

async function changeStatus(version: ProjectVersion, status: VersionStatus) {
  try {
    await mutate<ProjectVersion>(`/api/v1/projects/${projectId.value}/versions/${version.id}/status`, 'PUT', {
      status,
      actualReleaseDate: status === 'RELEASED'
        ? version.actualReleaseDate ?? localToday()
        : version.actualReleaseDate,
    })
    await load()
    Message.success(`版本已标记为${statusLabel(status)}`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '更新发布状态失败')
  }
}

async function remove(version: ProjectVersion) {
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/versions/${version.id}`, 'DELETE')
    await load()
    Message.success('版本已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除版本失败')
  }
}

function showDetail(version: ProjectVersion) {
  selectedVersion.value = version
  detailVisible.value = true
}

async function copyLink(version: ProjectVersion) {
  const url = new URL(window.location.href)
  url.searchParams.set('version', version.id)
  try {
    await navigator.clipboard.writeText(url.toString())
    Message.success('版本链接已复制')
  } catch {
    Message.warning('浏览器未允许复制，请从地址栏复制链接')
  }
}

function localToday() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

watch(projectId, () => {
  statusFilter.value = 'ALL'
  keyword.value = ''
  void load()
})
onMounted(load)
</script>

<template>
  <div class="version-page">
    <section class="version-header">
      <div>
        <span class="eyebrow">PROJECT RELEASES</span>
        <h1>版本管理</h1>
        <p>版本依赖于当前项目，每个项目可独立维护多个发布版本。</p>
      </div>
      <a-button type="primary" @click="openEditor()"><icon-plus /> 创建版本</a-button>
    </section>

    <section class="panel version-panel">
      <div class="version-toolbar">
        <div class="status-tabs" role="tablist" aria-label="版本状态筛选">
          <button
            v-for="filter in filters"
            :key="filter.value"
            type="button"
            role="tab"
            :aria-selected="statusFilter === filter.value"
            :class="{ active: statusFilter === filter.value }"
            @click="statusFilter = filter.value"
          >
            {{ filter.label }} <b>{{ filter.count }}</b>
          </button>
        </div>
        <a-input-search
          v-model="keyword"
          allow-clear
          placeholder="搜索版本号、构建或描述"
          :style="{ width: '270px' }"
        />
      </div>

      <a-spin class="version-spin" :loading="loading">
        <a-table
          :data="filteredVersions"
          row-key="id"
          :scroll="{ x: 1240 }"
          :pagination="{ pageSize: 20, showTotal: true, showPageSize: true }"
        >
          <template #columns>
            <a-table-column title="ID" data-index="displayId" :width="82" :sortable="{ sorter: true, sortDirections: ['ascend', 'descend'] }" />
            <a-table-column title="应用（项目）" :width="190">
              <template #cell="{ record }">
                <div class="project-cell"><icon-apps /><span>{{ record.projectName }}</span></div>
              </template>
            </a-table-column>
            <a-table-column title="版本号" :width="210">
              <template #cell="{ record }">
                <button class="version-link" type="button" @click="showDetail(record)">{{ record.versionNumber }}</button>
              </template>
            </a-table-column>
            <a-table-column title="包含构建" :width="180">
              <template #cell="{ record }">
                <div v-if="record.buildNumbers.length" class="build-list">
                  <a-tag v-for="build in record.buildNumbers.slice(0, 2)" :key="build" size="small">{{ build }}</a-tag>
                  <small v-if="record.buildNumbers.length > 2">+{{ record.buildNumbers.length - 2 }}</small>
                </div>
                <span v-else>—</span>
              </template>
            </a-table-column>
            <a-table-column title="发布状态" :width="118" :sortable="{ sorter: true, sortDirections: ['ascend', 'descend'] }">
              <template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template>
            </a-table-column>
            <a-table-column title="计划发布日期" :width="132">
              <template #cell="{ record }">{{ date(record.plannedReleaseDate) }}</template>
            </a-table-column>
            <a-table-column title="实际发布日期" :width="132">
              <template #cell="{ record }">{{ date(record.actualReleaseDate) }}</template>
            </a-table-column>
            <a-table-column title="描述" :width="220" ellipsis tooltip>
              <template #cell="{ record }">{{ record.description || '—' }}</template>
            </a-table-column>
            <a-table-column title="操作" fixed="right" :width="184">
              <template #cell="{ record }">
                <a-space :size="2">
                  <a-tooltip content="复制版本链接"><a-button type="text" size="mini" aria-label="复制版本链接" @click="copyLink(record)"><icon-link /></a-button></a-tooltip>
                  <a-tooltip v-if="record.status !== 'RELEASED'" content="标记已发布"><a-button type="text" size="mini" aria-label="标记已发布" @click="changeStatus(record, 'RELEASED')"><icon-check-circle /></a-button></a-tooltip>
                  <a-tooltip v-if="record.status !== 'FAILED'" content="标记发布失败"><a-button type="text" size="mini" status="danger" aria-label="标记发布失败" @click="changeStatus(record, 'FAILED')"><icon-close-circle /></a-button></a-tooltip>
                  <a-tooltip v-if="record.status !== 'STOPPED'" content="停止维护"><a-button type="text" size="mini" status="warning" aria-label="停止维护" @click="changeStatus(record, 'STOPPED')"><icon-pause-circle /></a-button></a-tooltip>
                  <a-tooltip content="编辑版本"><a-button type="text" size="mini" aria-label="编辑版本" @click="openEditor(record)"><icon-edit /></a-button></a-tooltip>
                  <a-popconfirm content="删除后无法恢复，确认删除该版本吗？" @ok="remove(record)">
                    <a-button type="text" size="mini" status="danger" aria-label="删除版本"><icon-delete /></a-button>
                  </a-popconfirm>
                </a-space>
              </template>
            </a-table-column>
          </template>
        </a-table>
      </a-spin>
      <footer class="version-summary">
        本项目共 <b>{{ versions.length }}</b> 个版本，已发布 <b>{{ releasedCount }}</b>，停止维护 <b>{{ stoppedCount }}</b>。
      </footer>
    </section>

    <a-modal
      v-model:visible="editorVisible"
      :title="editingId ? '编辑版本' : '创建版本'"
      width="660px"
      :ok-loading="saving"
      :on-before-ok="save"
    >
      <a-form :model="form" layout="vertical">
        <div class="form-grid">
          <a-form-item label="应用（项目）" required>
            <a-input :model-value="projectName" disabled><template #prefix><icon-apps /></template></a-input>
          </a-form-item>
          <a-form-item label="版本号" required :validate-status="formErrors.versionNumber ? 'error' : undefined" :help="formErrors.versionNumber">
            <a-input v-model="form.versionNumber" placeholder="例如：v2.6.0" @input="delete formErrors.versionNumber" />
          </a-form-item>
        </div>
        <a-form-item label="包含构建" :validate-status="formErrors.buildNumbers ? 'error' : undefined" :help="formErrors.buildNumbers">
          <a-input-tag v-model="form.buildNumbers" allow-clear placeholder="输入构建号后按回车，可添加多个" @change="delete formErrors.buildNumbers" />
        </a-form-item>
        <div class="form-grid form-grid--three">
          <a-form-item label="发布状态">
            <a-select v-model="form.status">
              <a-option value="UNSTARTED">未开始</a-option>
              <a-option value="RELEASED">已发布</a-option>
              <a-option value="FAILED">发布失败</a-option>
              <a-option value="STOPPED">停止维护</a-option>
            </a-select>
          </a-form-item>
          <a-form-item label="计划发布日期">
            <a-date-picker v-model="form.plannedReleaseDate" value-format="YYYY-MM-DD" style="width:100%" />
          </a-form-item>
          <a-form-item label="实际发布日期">
            <a-date-picker v-model="form.actualReleaseDate" value-format="YYYY-MM-DD" style="width:100%" :disabled="!['RELEASED', 'STOPPED'].includes(form.status)" />
          </a-form-item>
        </div>
        <a-form-item label="版本描述" :validate-status="formErrors.description ? 'error' : undefined" :help="formErrors.description">
          <a-textarea v-model="form.description" :max-length="20000" show-word-limit :auto-size="{ minRows: 4, maxRows: 8 }" placeholder="说明本次发布内容、影响范围与注意事项" @input="delete formErrors.description" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-drawer v-model:visible="detailVisible" title="版本详情" width="520px" :footer="false">
      <template v-if="selectedVersion">
        <div class="detail-hero">
          <a-tag :color="statusColor(selectedVersion.status)">{{ statusLabel(selectedVersion.status) }}</a-tag>
          <h2>{{ selectedVersion.versionNumber }}</h2>
          <p>{{ selectedVersion.projectName }}</p>
        </div>
        <a-descriptions :column="1" bordered>
          <a-descriptions-item label="版本 ID">{{ selectedVersion.displayId }}</a-descriptions-item>
          <a-descriptions-item label="应用（项目）">{{ selectedVersion.projectName }}</a-descriptions-item>
          <a-descriptions-item label="包含构建">{{ selectedVersion.buildNumbers.join('、') || '—' }}</a-descriptions-item>
          <a-descriptions-item label="计划发布日期">{{ date(selectedVersion.plannedReleaseDate) }}</a-descriptions-item>
          <a-descriptions-item label="实际发布日期">{{ date(selectedVersion.actualReleaseDate) }}</a-descriptions-item>
          <a-descriptions-item label="创建人">{{ selectedVersion.createdByName }}</a-descriptions-item>
          <a-descriptions-item label="创建时间">{{ dateTime(selectedVersion.createdAt) }}</a-descriptions-item>
        </a-descriptions>
        <section class="detail-description">
          <strong>版本描述</strong>
          <p>{{ selectedVersion.description || '暂无描述' }}</p>
        </section>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.version-page{display:grid;gap:18px}.version-header{display:flex;align-items:flex-end;justify-content:space-between;gap:24px}.version-header>div{display:grid;gap:5px}.version-header h1{margin:0;font-size:24px}.version-header p{margin:0;color:var(--color-text-3);font-size:13px}.eyebrow{color:var(--primary);font-size:10px;font-weight:700;letter-spacing:1.5px}.version-panel{padding:0;overflow:hidden}.version-toolbar{display:flex;align-items:center;justify-content:space-between;gap:20px;min-height:64px;padding:0 16px;border-bottom:1px solid var(--color-border-2)}.status-tabs{display:flex;align-items:stretch;height:64px;overflow-x:auto}.status-tabs button{position:relative;padding:0 14px;color:var(--color-text-2);font:inherit;font-size:13px;white-space:nowrap;border:0;background:transparent;cursor:pointer}.status-tabs button b{display:inline-flex;align-items:center;justify-content:center;min-width:20px;height:20px;margin-left:4px;padding:0 5px;color:var(--color-text-3);font-size:11px;border-radius:10px;background:var(--color-fill-2)}.status-tabs button.active{color:var(--primary);font-weight:650}.status-tabs button.active::after{content:"";position:absolute;right:12px;bottom:0;left:12px;height:3px;border-radius:3px 3px 0 0;background:var(--primary)}.status-tabs button.active b{color:var(--primary);background:rgb(var(--primary-1))}.version-spin{display:block;min-height:430px}.project-cell{display:flex;align-items:center;gap:8px;min-width:0}.project-cell svg{flex:0 0 auto;color:var(--primary)}.project-cell span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.version-link{max-width:100%;padding:0;color:rgb(var(--primary-6));font:inherit;font-weight:600;text-align:left;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:0;background:transparent;cursor:pointer}.version-link:hover{text-decoration:underline}.build-list{display:flex;align-items:center;gap:4px;overflow:hidden}.build-list small{color:var(--color-text-3);white-space:nowrap}.version-summary{min-height:50px;padding:15px 18px;color:var(--color-text-3);font-size:12px;border-top:1px solid var(--color-border-2)}.version-summary b{color:var(--color-text-1)}.version-panel :deep(.arco-table-th){color:var(--color-text-3);font-size:12px;background:var(--color-fill-1)}.version-panel :deep(.arco-table-td){font-size:13px}.version-panel :deep(.arco-pagination){justify-content:flex-end;margin:16px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.form-grid--three{grid-template-columns:.9fr 1fr 1fr}.detail-hero{padding:6px 0 22px}.detail-hero h2{margin:12px 0 4px;font-size:24px}.detail-hero p{margin:0;color:var(--color-text-3)}.detail-description{margin-top:20px;padding:16px;border-radius:8px;background:var(--color-fill-1)}.detail-description p{margin:10px 0 0;color:var(--color-text-2);line-height:1.7;white-space:pre-wrap}@media(max-width:900px){.version-header{align-items:stretch;flex-direction:column}.version-toolbar{align-items:stretch;flex-direction:column;padding:0 14px 14px}.status-tabs{width:100%}.version-toolbar :deep(.arco-input-wrapper){width:100%!important}.form-grid,.form-grid--three{grid-template-columns:1fr}}
</style>
