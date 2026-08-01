<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import {
  type FunctionalCase,
  type FunctionalRequirement,
  useFunctionalWorkspace,
} from '@/composables/useFunctionalWorkspace'
import { clearFormErrors, formErrorsFrom, toApiRequestError } from '@/composables/useApiFormErrors'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
type AssetTab = 'requirements' | 'cases'

const props = defineProps<{ assetType: AssetTab }>()
const session = useSessionStore()
const workspace = useFunctionalWorkspace()
const { catalogs, requirements, cases, selectedCatalogId, selectedCatalogName, loading } = workspace
const keyword = ref('')
const caseTypeFilter = ref<string>()
const selectedAssetIds = ref<string[]>([])
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formErrors = reactive<Record<string, string>>({})
const form = reactive({
  catalogId: '',
  requirementCode: '',
  title: '',
  priority: 'P1',
  status: 'DRAFT',
  description: '',
  requirementId: '',
  caseCode: '',
  caseType: 'FUNCTIONAL',
  precondition: '',
  steps: '',
  expectedResult: '',
})

const isRequirements = computed(() => props.assetType === 'requirements')
const catalogMap = computed(() => new Map(catalogs.value.map((catalog) => [catalog.id, catalog])))
const filteredRequirements = computed(() => requirements.value.filter((item) => {
  const matchesCatalog = !selectedCatalogId.value || item.catalogId === selectedCatalogId.value
  const query = keyword.value.trim().toLowerCase()
  return matchesCatalog && (!query || `${item.requirementCode} ${item.title}`.toLowerCase().includes(query))
}))
const filteredCases = computed(() => cases.value.filter((item) => {
  const matchesCatalog = !selectedCatalogId.value || item.catalogId === selectedCatalogId.value
  const matchesCaseType = !caseTypeFilter.value || caseTypeFilter.value === 'ALL' || item.caseType === caseTypeFilter.value
  const query = keyword.value.trim().toLowerCase()
  return matchesCatalog && matchesCaseType && (!query || `${item.caseCode} ${item.title}`.toLowerCase().includes(query))
}))
const activeRows = computed(() => isRequirements.value ? filteredRequirements.value : filteredCases.value)
const activeResource = computed(() => isRequirements.value ? 'requirements' : 'cases')
const statusLabel = (status: string) => ({ DRAFT: '草稿', REVIEWING: '评审中', APPROVED: '已通过', ARCHIVED: '已归档', READY: '待执行', BLOCKED: '阻塞' }[status] ?? status)
const statusColor = (status: string) => ({ DRAFT: 'gray', REVIEWING: 'orange', APPROVED: 'green', ARCHIVED: 'gray', READY: 'arcoblue', BLOCKED: 'red' }[status] ?? 'gray')
const priorityColor = (priority: string) => ({ P0: 'red', P1: 'orange', P2: 'arcoblue', P3: 'gray' }[priority] ?? 'gray')
const caseTypes = [
  { value: 'UNIT', label: '单元测试' },
  { value: 'API', label: '接口测试' },
  { value: 'FUNCTIONAL', label: '功能测试' },
  { value: 'INSTALLATION', label: '安装部署' },
  { value: 'CONFIGURATION', label: '配置相关' },
  { value: 'PERFORMANCE', label: '性能测试' },
  { value: 'SECURITY', label: '安全相关' },
  { value: 'OTHER', label: '其他' },
]
const caseTypeLabel = (type: string) => caseTypes.find((item) => item.value === type)?.label ?? type
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'

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

function clearForm() {
  clearFormErrors(formErrors)
  Object.assign(form, {
    catalogId: selectedCatalogId.value || catalogs.value[0]?.id || '',
    requirementCode: '',
    title: '',
    priority: 'P1',
    status: 'DRAFT',
    description: '',
    requirementId: '',
    caseCode: '',
    caseType: 'FUNCTIONAL',
    precondition: '',
    steps: '',
    expectedResult: '',
  })
}

function openAsset(item?: FunctionalRequirement | FunctionalCase) {
  if (!catalogs.value.length) {
    Message.warning('请先在左侧创建资产目录')
    return
  }
  editingId.value = item?.id ?? null
  clearForm()
  if (item) {
    Object.assign(form, item)
    if (!isRequirements.value) form.requirementId = (item as FunctionalCase).requirementId ?? ''
  }
  dialogVisible.value = true
}

function showSaveError(error: unknown, fallback: string) {
  clearFormErrors(formErrors)
  Object.assign(formErrors, formErrorsFrom(error))
  if (Object.keys(formErrors).length === 0) Message.error(error instanceof Error ? error.message : fallback)
}

async function save(): Promise<boolean> {
  saving.value = true
  try {
    if (isRequirements.value) {
      const url = editingId.value
        ? `/api/v1/projects/${session.projectId}/functional/requirements/${editingId.value}`
        : `/api/v1/projects/${session.projectId}/functional/requirements`
      await mutate(url, editingId.value ? 'PUT' : 'POST', {
        catalogId: form.catalogId,
        requirementCode: form.requirementCode,
        title: form.title,
        priority: form.priority,
        status: form.status,
        description: form.description,
      })
    } else {
      const url = editingId.value
        ? `/api/v1/projects/${session.projectId}/functional/cases/${editingId.value}`
        : `/api/v1/projects/${session.projectId}/functional/cases`
      await mutate(url, editingId.value ? 'PUT' : 'POST', {
        catalogId: form.catalogId,
        requirementId: form.requirementId || null,
        caseCode: form.caseCode,
        title: form.title,
        priority: form.priority,
        status: form.status,
        caseType: form.caseType,
        precondition: form.precondition,
        steps: form.steps,
        expectedResult: form.expectedResult,
      })
    }
    dialogVisible.value = false
    await workspace.reload()
    Message.success(isRequirements.value ? '需求已保存' : '测试用例已保存')
    return true
  } catch (error) {
    showSaveError(error, '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

async function remove(item: FunctionalRequirement | FunctionalCase) {
  try {
    await mutate(`/api/v1/projects/${session.projectId}/functional/${activeResource.value}/${item.id}`, 'DELETE')
    await workspace.reload()
    selectedAssetIds.value = selectedAssetIds.value.filter((id) => id !== item.id)
    Message.success(isRequirements.value ? '需求已删除' : '测试用例已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除失败')
  }
}

async function removeSelected() {
  if (!selectedAssetIds.value.length) return
  const ids = [...selectedAssetIds.value]
  try {
    await Promise.all(ids.map((id) => mutate(`/api/v1/projects/${session.projectId}/functional/${activeResource.value}/${id}`, 'DELETE')))
    await workspace.reload()
    selectedAssetIds.value = []
    Message.success(`已删除 ${ids.length} 条${isRequirements.value ? '需求' : '测试用例'}`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '批量删除失败')
  }
}

watch(() => props.assetType, () => { selectedAssetIds.value = [] })
</script>

<template>
  <section class="panel asset-library">
    <header class="asset-toolbar">
      <div class="asset-title">
        <span class="asset-title-icon" :class="{ cases: !isRequirements }">
          <icon-file v-if="isRequirements" />
          <icon-check-circle v-else />
        </span>
        <div>
          <h1>{{ isRequirements ? '需求库' : '用例库' }}</h1>
          <p>{{ selectedCatalogName }} · 共 {{ activeRows.length }} 条</p>
        </div>
      </div>
      <div class="asset-actions">
        <a-select v-if="!isRequirements" v-model="caseTypeFilter" allow-clear placeholder="所有类型" :style="{ width: '132px' }">
          <a-option value="ALL">所有类型</a-option>
          <a-option v-for="type in caseTypes" :key="type.value" :value="type.value">{{ type.label }}</a-option>
        </a-select>
        <a-input-search v-model="keyword" allow-clear :placeholder="isRequirements ? '搜索需求编号或名称' : '搜索用例编号或名称'" :style="{ width: '260px' }" />
        <a-tooltip content="刷新"><a-button shape="circle" aria-label="刷新资产" @click="workspace.reload"><icon-refresh /></a-button></a-tooltip>
        <a-button class="create-asset" type="primary" @click="openAsset()"><icon-plus /> {{ isRequirements ? '新建需求' : '新建用例' }}</a-button>
        <a-popconfirm :disabled="!selectedAssetIds.length" :content="`确认删除选中的 ${selectedAssetIds.length} 条${isRequirements ? '需求' : '测试用例'}吗？`" @ok="removeSelected">
          <a-button class="batch-delete" status="danger" :disabled="!selectedAssetIds.length"><icon-delete /> 批量删除（{{ selectedAssetIds.length }}）</a-button>
        </a-popconfirm>
      </div>
    </header>

    <a-spin class="asset-spin" :loading="loading">
      <a-table v-if="isRequirements" v-model:selected-keys="selectedAssetIds" :data="filteredRequirements" :row-selection="{ type: 'checkbox', showCheckedAll: true }" :scroll="{ x: 1050 }" :pagination="{ pageSize: 10, showTotal: true }" row-key="id" :bordered="false">
        <template #columns>
          <a-table-column title="ID" data-index="displayId" :width="92" :sortable="{ sortDirections: ['ascend', 'descend'], sorter: true }" />
          <a-table-column title="需求" data-index="title">
            <template #cell="{ record }"><div class="asset-name"><span class="row-icon requirement"><icon-file /></span><div><strong>{{ record.title }}</strong><small>{{ record.requirementCode }}</small></div></div></template>
          </a-table-column>
          <a-table-column title="所属目录" :width="150"><template #cell="{ record }">{{ catalogMap.get(record.catalogId)?.name ?? '未分类' }}</template></a-table-column>
          <a-table-column title="优先级" :width="90"><template #cell="{ record }"><a-tag :color="priorityColor(record.priority)">{{ record.priority }}</a-tag></template></a-table-column>
          <a-table-column title="状态" :width="105"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column>
          <a-table-column title="最近更新" :width="180"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column>
          <a-table-column title="操作" fixed="right" :width="130">
            <template #cell="{ record }"><a-space><a-button type="text" @click="openAsset(record)">编辑</a-button><a-popconfirm content="确认删除该需求吗？" @ok="remove(record)"><a-button type="text" status="danger">删除</a-button></a-popconfirm></a-space></template>
          </a-table-column>
        </template>
      </a-table>

      <a-table v-else v-model:selected-keys="selectedAssetIds" :data="filteredCases" :row-selection="{ type: 'checkbox', showCheckedAll: true }" :scroll="{ x: 1450 }" :pagination="{ pageSize: 10, showTotal: true }" row-key="id" :bordered="false">
        <template #columns>
          <a-table-column title="ID" data-index="displayId" :width="92" :sortable="{ sortDirections: ['ascend', 'descend'], sorter: true }" />
          <a-table-column title="用例名称" data-index="title" :min-width="300">
            <template #cell="{ record }"><div class="asset-name"><span class="row-icon case"><icon-check-circle /></span><div><strong>{{ record.title }}</strong></div></div></template>
          </a-table-column>
          <a-table-column title="用例类型" :width="112"><template #cell="{ record }"><a-tag>{{ caseTypeLabel(record.caseType) }}</a-tag></template></a-table-column>
          <a-table-column title="优先级" :width="85"><template #cell="{ record }"><a-tag :color="priorityColor(record.priority)">{{ record.priority }}</a-tag></template></a-table-column>
          <a-table-column title="状态" :width="100"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column>
          <a-table-column title="最近更新" :width="175"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column>
          <a-table-column title="创建人" :width="110"><template #cell="{ record }">{{ record.createdByName || '-' }}</template></a-table-column>
          <a-table-column title="创建时间" :width="175"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column>
          <a-table-column title="执行人" :width="110"><template #cell="{ record }">{{ record.executedByName || '-' }}</template></a-table-column>
          <a-table-column title="执行时间" :width="175"><template #cell="{ record }">{{ date(record.executedAt) }}</template></a-table-column>
          <a-table-column title="操作" fixed="right" :width="130">
            <template #cell="{ record }"><a-space><a-button type="text" @click="openAsset(record)">编辑</a-button><a-popconfirm content="确认删除该测试用例吗？" @ok="remove(record)"><a-button type="text" status="danger">删除</a-button></a-popconfirm></a-space></template>
          </a-table-column>
        </template>
      </a-table>
    </a-spin>

    <a-modal v-model:visible="dialogVisible" :title="`${editingId ? '编辑' : '新建'}${isRequirements ? '需求' : '测试用例'}`" width="680px" :ok-loading="saving" :on-before-ok="save">
      <a-form :model="form" layout="vertical">
        <a-form-item label="所属目录" required :validate-status="formErrors.catalogId ? 'error' : undefined" :help="formErrors.catalogId"><a-select v-model="form.catalogId" @change="delete formErrors.catalogId"><a-option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option></a-select></a-form-item>
        <template v-if="isRequirements">
          <div class="form-grid"><a-form-item label="需求编号" required :validate-status="formErrors.requirementCode ? 'error' : undefined" :help="formErrors.requirementCode"><a-input v-model="form.requirementCode" placeholder="例如：REQ_ORDER_001" @input="delete formErrors.requirementCode" /></a-form-item><a-form-item label="优先级"><a-select v-model="form.priority" @change="delete formErrors.priority"><a-option v-for="priority in ['P0', 'P1', 'P2', 'P3']" :key="priority" :value="priority">{{ priority }}</a-option></a-select></a-form-item></div>
          <a-form-item label="需求标题" required :validate-status="formErrors.title ? 'error' : undefined" :help="formErrors.title"><a-input v-model="form.title" placeholder="描述可验收的业务目标" @input="delete formErrors.title" /></a-form-item>
          <a-form-item label="需求说明" :validate-status="formErrors.description ? 'error' : undefined" :help="formErrors.description"><a-textarea v-model="form.description" :auto-size="{ minRows: 4, maxRows: 8 }" placeholder="补充范围、验收条件和业务背景" @input="delete formErrors.description" /></a-form-item>
          <a-form-item label="状态"><a-select v-model="form.status"><a-option value="DRAFT">草稿</a-option><a-option value="REVIEWING">评审中</a-option><a-option value="APPROVED">已通过</a-option><a-option value="ARCHIVED">已归档</a-option></a-select></a-form-item>
        </template>
        <template v-else>
          <div class="form-grid"><a-form-item label="用例编号" required :validate-status="formErrors.caseCode ? 'error' : undefined" :help="formErrors.caseCode"><a-input v-model="form.caseCode" placeholder="例如：CASE_ORDER_001" @input="delete formErrors.caseCode" /></a-form-item><a-form-item label="用例类型" required :validate-status="formErrors.caseType ? 'error' : undefined" :help="formErrors.caseType"><a-select v-model="form.caseType" @change="delete formErrors.caseType"><a-option v-for="type in caseTypes" :key="type.value" :value="type.value">{{ type.label }}</a-option></a-select></a-form-item></div>
          <div class="form-grid"><a-form-item label="用例标题" required :validate-status="formErrors.title ? 'error' : undefined" :help="formErrors.title"><a-input v-model="form.title" placeholder="例如：正常提交订单" @input="delete formErrors.title" /></a-form-item><a-form-item label="优先级"><a-select v-model="form.priority" @change="delete formErrors.priority"><a-option v-for="priority in ['P0', 'P1', 'P2', 'P3']" :key="priority" :value="priority">{{ priority }}</a-option></a-select></a-form-item></div>
          <a-form-item label="关联需求"><a-select v-model="form.requirementId" allow-clear><a-option v-for="item in requirements" :key="item.id" :value="item.id">{{ item.requirementCode }} · {{ item.title }}</a-option></a-select></a-form-item>
          <a-form-item label="前置条件"><a-textarea v-model="form.precondition" :auto-size="{ minRows: 2, maxRows: 4 }" /></a-form-item>
          <div class="form-grid"><a-form-item label="测试步骤" required :validate-status="formErrors.steps ? 'error' : undefined" :help="formErrors.steps"><a-textarea v-model="form.steps" :auto-size="{ minRows: 5, maxRows: 9 }" placeholder="每行一个步骤" @input="delete formErrors.steps" /></a-form-item><a-form-item label="预期结果" required :validate-status="formErrors.expectedResult ? 'error' : undefined" :help="formErrors.expectedResult"><a-textarea v-model="form.expectedResult" :auto-size="{ minRows: 5, maxRows: 9 }" placeholder="与步骤对应的预期结果" @input="delete formErrors.expectedResult" /></a-form-item></div>
          <a-form-item label="状态"><a-select v-model="form.status"><a-option value="DRAFT">草稿</a-option><a-option value="READY">待执行</a-option><a-option value="BLOCKED">阻塞</a-option><a-option value="ARCHIVED">已归档</a-option></a-select></a-form-item>
        </template>
      </a-form>
    </a-modal>
  </section>
</template>

<style scoped>
.asset-library{padding:0;overflow:hidden;min-height:650px}.asset-toolbar{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:17px 20px;border-bottom:1px solid var(--line)}.asset-title{display:flex;align-items:center;gap:11px}.asset-title-icon{display:grid;place-items:center;width:38px;height:38px;color:#3167f6;border-radius:10px;background:#edf2ff}.asset-title-icon.cases{color:#24a880;background:#e9f8f3}.asset-title h1{margin:0 0 4px;font-size:18px}.asset-title p{margin:0;color:var(--muted);font-size:11px}.asset-actions{display:flex;align-items:center;gap:8px}.asset-spin{display:block;min-height:480px}.asset-name{display:flex;align-items:center;gap:10px}.asset-name>div{display:grid;gap:4px;min-width:0}.asset-name strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.asset-name small{color:var(--color-text-3);font-size:11px}.row-icon{display:grid;place-items:center;width:30px;height:30px;border-radius:8px}.row-icon.requirement{color:#3167f6;background:#edf2ff}.row-icon.case{color:#25a981;background:#e9f8f3}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.asset-library :deep(.arco-table .arco-empty){padding:74px 0}@media(max-width:1100px){.asset-toolbar{align-items:flex-start;flex-direction:column}.asset-actions{width:100%}.asset-actions :deep(.arco-input-wrapper){flex:1}}
</style>
