<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Catalog { id: string; parentId: string | null; name: string; sortOrder: number }
interface Requirement { id: string; catalogId: string; requirementCode: string; title: string; priority: string; status: string; description: string; updatedAt: string }
interface TestCase { id: string; catalogId: string; requirementId: string | null; caseCode: string; title: string; priority: string; status: string; precondition: string; steps: string; expectedResult: string; updatedAt: string }
type AssetTab = 'requirements' | 'cases'
type DialogMode = 'catalog' | 'requirement' | 'case'

const context = ref<Context | null>(null)
const projectId = ref('')
const catalogs = ref<Catalog[]>([])
const requirements = ref<Requirement[]>([])
const cases = ref<TestCase[]>([])
const loading = ref(true)
const saving = ref(false)
const activeTab = ref<AssetTab>('requirements')
const selectedCatalogId = ref('')
const keyword = ref('')
const dialogVisible = ref(false)
const dialogMode = ref<DialogMode>('requirement')
const editingId = ref<string | null>(null)
const form = reactive({
  name: '', parentId: '', sortOrder: 0,
  catalogId: '', requirementCode: '', title: '', priority: 'P1', status: 'DRAFT', description: '',
  requirementId: '', caseCode: '', precondition: '', steps: '', expectedResult: '',
})

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const currentCatalog = computed(() => catalogs.value.find((catalog) => catalog.id === selectedCatalogId.value))
const catalogMap = computed(() => new Map(catalogs.value.map((catalog) => [catalog.id, catalog])))
const flatCatalogs = computed(() => {
  const result: Array<Catalog & { depth: number }> = []
  const visit = (parentId: string | null, depth: number) => {
    catalogs.value.filter((catalog) => catalog.parentId === parentId).forEach((catalog) => {
      result.push({ ...catalog, depth })
      visit(catalog.id, depth + 1)
    })
  }
  visit(null, 0)
  return result
})
const filteredRequirements = computed(() => requirements.value.filter((item) => {
  const matchesCatalog = !selectedCatalogId.value || item.catalogId === selectedCatalogId.value
  const query = keyword.value.trim().toLowerCase()
  return matchesCatalog && (!query || `${item.requirementCode} ${item.title}`.toLowerCase().includes(query))
}))
const filteredCases = computed(() => cases.value.filter((item) => {
  const matchesCatalog = !selectedCatalogId.value || item.catalogId === selectedCatalogId.value
  const query = keyword.value.trim().toLowerCase()
  return matchesCatalog && (!query || `${item.caseCode} ${item.title}`.toLowerCase().includes(query))
}))
const activeRows = computed(() => activeTab.value === 'requirements' ? filteredRequirements.value : filteredCases.value)
const selectedCatalogName = computed(() => currentCatalog.value?.name ?? '全部目录')
const statusLabel = (status: string) => ({ DRAFT: '草稿', REVIEWING: '评审中', APPROVED: '已通过', ARCHIVED: '已归档', READY: '待执行', BLOCKED: '阻塞' }[status] ?? status)
const statusColor = (status: string) => ({ DRAFT: 'gray', REVIEWING: 'orange', APPROVED: 'green', ARCHIVED: 'gray', READY: 'arcoblue', BLOCKED: 'red' }[status] ?? 'gray')
const priorityColor = (priority: string) => ({ P0: 'red', P1: 'orange', P2: 'arcoblue', P3: 'gray' }[priority] ?? 'gray')
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadAssets() {
  if (!projectId.value) return
  const [catalogData, requirementData, caseData] = await Promise.all([
    read<Catalog[]>(`/api/v1/projects/${projectId.value}/functional/catalogs`),
    read<Requirement[]>(`/api/v1/projects/${projectId.value}/functional/requirements`),
    read<TestCase[]>(`/api/v1/projects/${projectId.value}/functional/cases`),
  ])
  catalogs.value = catalogData; requirements.value = requirementData; cases.value = caseData
  if (selectedCatalogId.value && !catalogData.some((catalog) => catalog.id === selectedCatalogId.value)) selectedCatalogId.value = ''
}
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadAssets()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载功能测试资产失败') } finally { loading.value = false }
}
async function changeProject() { selectedCatalogId.value = ''; try { await loadAssets() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function clearForm() {
  Object.assign(form, { name: '', parentId: selectedCatalogId.value, sortOrder: 0, catalogId: selectedCatalogId.value, requirementCode: '', title: '', priority: 'P1', status: 'DRAFT', description: '', requirementId: '', caseCode: '', precondition: '', steps: '', expectedResult: '' })
}
function openCatalog() { editingId.value = null; dialogMode.value = 'catalog'; clearForm(); dialogVisible.value = true }
function openAsset(mode: AssetTab, item?: Requirement | TestCase) {
  activeTab.value = mode; dialogMode.value = mode === 'requirements' ? 'requirement' : 'case'; editingId.value = item?.id ?? null; clearForm()
  if (mode === 'requirements' && item) Object.assign(form, item)
  if (mode === 'cases' && item) {
    const caseItem = item as TestCase
    Object.assign(form, caseItem, { requirementId: caseItem.requirementId ?? '' })
  }
  dialogVisible.value = true
}
function selectCatalog(id: string) { selectedCatalogId.value = id }
async function save() {
  saving.value = true
  try {
    if (dialogMode.value === 'catalog') {
      const url = editingId.value ? `/api/v1/projects/${projectId.value}/functional/catalogs/${editingId.value}` : `/api/v1/projects/${projectId.value}/functional/catalogs`
      await mutate(url, editingId.value ? 'PUT' : 'POST', { name: form.name, parentId: form.parentId || null, sortOrder: form.sortOrder })
    } else if (dialogMode.value === 'requirement') {
      const url = editingId.value ? `/api/v1/projects/${projectId.value}/functional/requirements/${editingId.value}` : `/api/v1/projects/${projectId.value}/functional/requirements`
      await mutate(url, editingId.value ? 'PUT' : 'POST', { catalogId: form.catalogId, requirementCode: form.requirementCode, title: form.title, priority: form.priority, status: form.status, description: form.description })
    } else {
      const url = editingId.value ? `/api/v1/projects/${projectId.value}/functional/cases/${editingId.value}` : `/api/v1/projects/${projectId.value}/functional/cases`
      await mutate(url, editingId.value ? 'PUT' : 'POST', { catalogId: form.catalogId, requirementId: form.requirementId || null, caseCode: form.caseCode, title: form.title, priority: form.priority, status: form.status, precondition: form.precondition, steps: form.steps, expectedResult: form.expectedResult })
    }
    dialogVisible.value = false; await loadAssets(); Message.success('功能测试资产已保存')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') } finally { saving.value = false }
}
async function remove(type: 'catalog' | 'requirement' | 'case', id: string) {
  try {
    const resource = type === 'catalog' ? 'catalogs' : type === 'requirement' ? 'requirements' : 'cases'
    await mutate(`/api/v1/projects/${projectId.value}/functional/${resource}/${id}`, 'DELETE')
    if (type === 'catalog' && selectedCatalogId.value === id) selectedCatalogId.value = ''
    await loadAssets(); Message.success('已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
}
onMounted(load)
</script>

<template>
  <div class="page functional-page">
    <PageHeader eyebrow="QUALITY ASSETS" title="功能测试" description="从需求、用例库开始建立可追溯的功能测试资产，后续可关联计划、执行与缺陷。" action="新建测试用例" @action="openAsset('cases')" />
    <section class="functional-summary panel">
      <div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div>
      <a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select>
      <div><span>需求</span><strong>{{ requirements.length }}</strong><small>覆盖 {{ new Set(requirements.map((item) => item.catalogId)).size }} 个目录</small></div>
      <div><span>测试用例</span><strong>{{ cases.length }}</strong><small>{{ cases.filter((item) => item.status === 'READY').length }} 个待执行</small></div>
      <div><span>待评审</span><strong>{{ requirements.filter((item) => item.status === 'REVIEWING').length }}</strong><small>需求评审队列</small></div>
    </section>
    <section class="functional-workspace">
      <aside class="catalog-panel panel">
        <div class="catalog-heading"><div><span class="eyebrow">ASSET CATALOG</span><h3>资产目录</h3></div><a-button type="text" size="small" @click="openCatalog"><icon-plus /></a-button></div>
        <button class="catalog-all" :class="{ selected: !selectedCatalogId }" @click="selectCatalog('')"><icon-apps /> 全部资产 <span>{{ requirements.length + cases.length }}</span></button>
        <div v-if="flatCatalogs.length === 0" class="catalog-empty"><icon-folder-add /><p>还没有目录</p><a-button type="text" @click="openCatalog">新建一级目录</a-button></div>
        <div v-for="catalog in flatCatalogs" :key="catalog.id" class="catalog-row-wrap">
          <button class="catalog-row" :class="{ selected: selectedCatalogId === catalog.id }" :style="{ paddingLeft: `${12 + catalog.depth * 18}px` }" @click="selectCatalog(catalog.id)">
            <icon-folder /><span>{{ catalog.name }}</span><small>{{ requirements.filter((item) => item.catalogId === catalog.id).length + cases.filter((item) => item.catalogId === catalog.id).length }}</small>
          </button>
        </div>
        <div class="catalog-footer"><span>目录 {{ catalogs.length }}</span><a-button type="text" size="small" :disabled="!selectedCatalogId" @click="remove('catalog', selectedCatalogId)"><icon-delete /></a-button></div>
      </aside>
      <div class="asset-panel">
        <div class="asset-tabs"><button :class="{ active: activeTab === 'requirements' }" @click="activeTab = 'requirements'"><icon-file /> 需求库 <a-badge :count="requirements.length" :max-count="99" /></button><button :class="{ active: activeTab === 'cases' }" @click="activeTab = 'cases'"><icon-check-circle /> 用例库 <a-badge :count="cases.length" :max-count="99" /></button><router-link class="plan-link" to="/functional/plans"><icon-calendar /> 测试计划与执行 <icon-right /></router-link><router-link class="plan-link governance-link" to="/functional/governance"><icon-settings /> 资产治理 <icon-right /></router-link><router-link class="plan-link governance-link" to="/functional/delivery"><icon-bug /> 缺陷与交换 <icon-right /></router-link></div>
        <section class="panel data-panel">
          <div class="asset-toolbar"><div><strong>{{ activeTab === 'requirements' ? '需求条目' : '测试用例' }}</strong><span class="muted"> · {{ selectedCatalogName }}</span><a-input-search v-model="keyword" placeholder="搜索编号或名称" allow-clear :style="{ width: '250px', marginLeft: '18px' }" /></div><a-space><a-button @click="openCatalog"><icon-folder-add /> 新建目录</a-button><a-button type="primary" @click="openAsset(activeTab)"><icon-plus /> {{ activeTab === 'requirements' ? '新建需求' : '新建用例' }}</a-button></a-space></div>
          <a-spin :loading="loading"><a-table v-if="activeTab === 'requirements'" :data="filteredRequirements" :pagination="{ pageSize: 8 }" row-key="id"><template #columns><a-table-column title="需求" data-index="title"><template #cell="{ record }"><div class="asset-name"><span class="asset-icon requirement"><icon-file /></span><div><strong>{{ record.title }}</strong><small>{{ record.requirementCode }} · {{ catalogMap.get(record.catalogId)?.name ?? '未分类' }}</small></div></div></template></a-table-column><a-table-column title="优先级" data-index="priority"><template #cell="{ record }"><a-tag :color="priorityColor(record.priority)">{{ record.priority }}</a-tag></template></a-table-column><a-table-column title="状态" data-index="status"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column><a-table-column title="最近更新" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="openAsset('requirements', record)">编辑</a-button><a-button type="text" status="danger" @click="remove('requirement', record.id)">删除</a-button></a-space></template></a-table-column></template></a-table><a-table v-else :data="filteredCases" :pagination="{ pageSize: 8 }" row-key="id"><template #columns><a-table-column title="测试用例" data-index="title"><template #cell="{ record }"><div class="asset-name"><span class="asset-icon case"><icon-check-circle /></span><div><strong>{{ record.title }}</strong><small>{{ record.caseCode }} · {{ catalogMap.get(record.catalogId)?.name ?? '未分类' }}</small></div></div></template></a-table-column><a-table-column title="优先级" data-index="priority"><template #cell="{ record }"><a-tag :color="priorityColor(record.priority)">{{ record.priority }}</a-tag></template></a-table-column><a-table-column title="状态" data-index="status"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column><a-table-column title="最近更新" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="openAsset('cases', record)">编辑</a-button><a-button type="text" status="danger" @click="remove('case', record.id)">删除</a-button></a-space></template></a-table-column></template></a-table></a-spin>
          <a-empty v-if="!loading && activeRows.length === 0" description="当前目录还没有资产，先创建一个需求或测试用例。" />
        </section>
      </div>
    </section>
    <a-modal v-model:visible="dialogVisible" :title="dialogMode === 'catalog' ? (editingId ? '编辑目录' : '新建目录') : dialogMode === 'requirement' ? (editingId ? '编辑需求' : '新建需求') : (editingId ? '编辑测试用例' : '新建测试用例')" width="680px" :ok-loading="saving" @ok="save">
      <a-form :model="form" layout="vertical">
        <template v-if="dialogMode === 'catalog'"><a-form-item label="目录名称" required><a-input v-model="form.name" placeholder="例如：订单核心流程" /></a-form-item><a-form-item label="父级目录"><a-select v-model="form.parentId" allow-clear placeholder="创建为一级目录"><a-option v-for="catalog in catalogs.filter((item) => item.id !== editingId)" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option></a-select></a-form-item></template>
        <template v-else-if="dialogMode === 'requirement'"><a-form-item label="所属目录" required><a-select v-model="form.catalogId"><a-option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option></a-select></a-form-item><div class="form-grid"><a-form-item label="需求编号" required><a-input v-model="form.requirementCode" placeholder="例如：REQ_ORDER_001" /></a-form-item><a-form-item label="优先级"><a-select v-model="form.priority"><a-option value="P0">P0 · 最高</a-option><a-option value="P1">P1 · 高</a-option><a-option value="P2">P2 · 中</a-option><a-option value="P3">P3 · 低</a-option></a-select></a-form-item></div><a-form-item label="需求标题" required><a-input v-model="form.title" placeholder="描述可验收的业务目标" /></a-form-item><a-form-item label="需求说明"><a-textarea v-model="form.description" :auto-size="{ minRows: 4, maxRows: 8 }" placeholder="补充范围、验收条件和业务背景" /></a-form-item><a-form-item v-if="editingId" label="状态"><a-select v-model="form.status"><a-option value="DRAFT">草稿</a-option><a-option value="REVIEWING">评审中</a-option><a-option value="APPROVED">已通过</a-option><a-option value="ARCHIVED">已归档</a-option></a-select></a-form-item></template>
        <template v-else><a-form-item label="所属目录" required><a-select v-model="form.catalogId"><a-option v-for="catalog in catalogs" :key="catalog.id" :value="catalog.id">{{ catalog.name }}</a-option></a-select></a-form-item><div class="form-grid"><a-form-item label="用例编号" required><a-input v-model="form.caseCode" placeholder="例如：CASE_ORDER_001" /></a-form-item><a-form-item label="优先级"><a-select v-model="form.priority"><a-option value="P0">P0 · 最高</a-option><a-option value="P1">P1 · 高</a-option><a-option value="P2">P2 · 中</a-option><a-option value="P3">P3 · 低</a-option></a-select></a-form-item></div><a-form-item label="用例标题" required><a-input v-model="form.title" placeholder="例如：正常提交订单" /></a-form-item><a-form-item label="关联需求"><a-select v-model="form.requirementId" allow-clear><a-option v-for="requirement in requirements" :key="requirement.id" :value="requirement.id">{{ requirement.requirementCode }} · {{ requirement.title }}</a-option></a-select></a-form-item><a-form-item label="前置条件"><a-textarea v-model="form.precondition" :auto-size="{ minRows: 2, maxRows: 4 }" /></a-form-item><a-form-item label="测试步骤" required><a-textarea v-model="form.steps" :auto-size="{ minRows: 4, maxRows: 8 }" placeholder="每行一个步骤" /></a-form-item><a-form-item label="预期结果" required><a-textarea v-model="form.expectedResult" :auto-size="{ minRows: 3, maxRows: 6 }" /></a-form-item></template>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.functional-summary{display:grid;grid-template-columns:1.45fr 1.1fr .7fr .8fr .8fr;gap:18px;align-items:center;margin-bottom:20px}.functional-summary>div{display:grid;gap:4px}.functional-summary span,.functional-summary small,.muted{color:var(--color-text-3);font-size:12px}.functional-summary strong{font-size:20px}.functional-workspace{display:grid;grid-template-columns:250px minmax(0,1fr);gap:18px;align-items:start}.catalog-panel{padding:16px 10px;min-height:540px}.catalog-heading{display:flex;align-items:center;justify-content:space-between;padding:0 6px 12px}.catalog-heading h3{margin:4px 0 0;font-size:16px}.catalog-all,.catalog-row{display:flex;align-items:center;gap:8px;width:100%;border:0;background:transparent;color:var(--color-text-2);border-radius:7px;cursor:pointer;text-align:left;padding:9px 12px;font-size:13px}.catalog-all:hover,.catalog-row:hover,.catalog-all.selected,.catalog-row.selected{background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.catalog-all span,.catalog-row small{margin-left:auto;color:var(--color-text-3);font-size:12px}.catalog-row-wrap{margin-top:2px}.catalog-empty{text-align:center;color:var(--color-text-3);padding:48px 8px 32px}.catalog-empty svg{font-size:30px;color:rgb(var(--primary-5))}.catalog-empty p{margin:10px 0 2px}.catalog-footer{display:flex;justify-content:space-between;align-items:center;border-top:1px solid var(--color-border-2);margin-top:18px;padding:12px 6px;color:var(--color-text-3);font-size:12px}.asset-panel{min-width:0}.asset-tabs{display:flex;align-items:center;gap:4px;margin:0 0 12px;padding:0 4px}.asset-tabs button,.plan-link{display:flex;align-items:center;gap:8px;border:0;background:transparent;padding:10px 16px;border-radius:8px;color:var(--color-text-2);cursor:pointer;font-weight:600;text-decoration:none}.asset-tabs button.active{background:#fff;color:rgb(var(--primary-6));box-shadow:0 4px 14px rgba(23,55,104,.08)}.plan-link{margin-left:auto;color:rgb(var(--primary-6));font-size:13px}.governance-link{margin-left:0}.asset-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.asset-toolbar>div{display:flex;align-items:center;min-width:0}.asset-toolbar strong{font-size:16px;white-space:nowrap}.data-panel{min-height:440px}.asset-name{display:flex;align-items:center;gap:10px}.asset-name small{display:block;margin-top:4px;color:var(--color-text-3);font-size:12px}.asset-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px}.asset-icon.requirement{background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.asset-icon.case{background:rgb(var(--success-1));color:rgb(var(--success-6))}.form-grid{display:grid;grid-template-columns:1.5fr 1fr;gap:16px}@media (max-width:1100px){.functional-summary{grid-template-columns:1fr 1fr}.functional-workspace{grid-template-columns:210px minmax(0,1fr)}}@media (max-width:760px){.functional-workspace{grid-template-columns:1fr}.catalog-panel{min-height:0}.asset-toolbar{align-items:flex-start;flex-direction:column}.asset-toolbar>div{width:100%}.form-grid{grid-template-columns:1fr}}
</style>
