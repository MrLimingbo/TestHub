<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Requirement { id: string; requirementCode: string; title: string }
interface TestCase { id: string; caseCode: string; title: string }
interface Defect { id: string; defectCode: string; title: string; severity: string; status: string; source: string; externalUrl: string | null; description: string; linkCount: number; updatedAt: string }
interface DefectLink { id: string; assetType: string; assetId: string; assetLabel: string; createdAt: string }
interface ImportResult { type: string; completed: boolean; importedCount: number; errors: Array<{ rowNumber: number; message: string }> }
type AssetKind = 'REQUIREMENTS' | 'CASES'
type LinkType = 'REQUIREMENT' | 'CASE'

const context = ref<Context | null>(null)
const projectId = ref('')
const requirements = ref<Requirement[]>([])
const cases = ref<TestCase[]>([])
const defects = ref<Defect[]>([])
const selectedDefectId = ref('')
const links = ref<DefectLink[]>([])
const loading = ref(true)
const saving = ref(false)
const defectDialog = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadFile = ref<File | null>(null)
const importKind = ref<AssetKind>('REQUIREMENTS')
const importResult = ref<ImportResult | null>(null)
const defectForm = reactive({ defectCode: '', title: '', severity: 'MAJOR', status: 'OPEN', source: 'TESTHUB', externalUrl: '', description: '' })
const linkForm = reactive<{ assetType: LinkType; assetId: string }>({ assetType: 'CASE', assetId: '' })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const selectedDefect = computed(() => defects.value.find((defect) => defect.id === selectedDefectId.value))
const linkAssets = computed(() => linkForm.assetType === 'CASE' ? cases.value.map((item) => ({ id: item.id, label: `${item.caseCode} · ${item.title}` })) : requirements.value.map((item) => ({ id: item.id, label: `${item.requirementCode} · ${item.title}` })))
const statusLabel = (value: string) => ({ OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }[value] ?? value)
const statusColor = (value: string) => ({ OPEN: 'red', IN_PROGRESS: 'orange', RESOLVED: 'green', CLOSED: 'gray' }[value] ?? 'gray')
const severityLabel = (value: string) => ({ CRITICAL: '致命', MAJOR: '严重', MINOR: '一般', TRIVIAL: '轻微' }[value] ?? value)
const severityColor = (value: string) => ({ CRITICAL: 'red', MAJOR: 'orangered', MINOR: 'orange', TRIVIAL: 'gray' }[value] ?? 'gray')
const assetLabel = (value: string) => ({ REQUIREMENT: '需求', CASE: '测试用例' }[value] ?? value)
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadDefectDetails() { links.value = selectedDefectId.value ? await read<DefectLink[]>(`/api/v1/projects/${projectId.value}/functional/defects/${selectedDefectId.value}/links`) : [] }
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context'); projectId.value = projects.value[0]?.id ?? ''
    await loadProjectData()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载测试交付数据失败') } finally { loading.value = false }
}
async function loadProjectData() {
  const [requirementData, caseData, defectData] = await Promise.all([
    read<Requirement[]>(`/api/v1/projects/${projectId.value}/functional/requirements`),
    read<TestCase[]>(`/api/v1/projects/${projectId.value}/functional/cases`),
    read<Defect[]>(`/api/v1/projects/${projectId.value}/functional/defects`),
  ])
  requirements.value = requirementData; cases.value = caseData; defects.value = defectData
  if (!selectedDefectId.value || !defectData.some((defect) => defect.id === selectedDefectId.value)) selectedDefectId.value = defectData[0]?.id ?? ''
  await loadDefectDetails()
}
async function changeProject() { selectedDefectId.value = ''; linkForm.assetId = ''; try { await loadProjectData() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function openDefect() { Object.assign(defectForm, { defectCode: '', title: '', severity: 'MAJOR', status: 'OPEN', source: 'TESTHUB', externalUrl: '', description: '' }); defectDialog.value = true }
async function saveDefect() {
  saving.value = true
  try {
    const created = await mutate<Defect>(`/api/v1/projects/${projectId.value}/functional/defects`, 'POST', defectForm)
    defectDialog.value = false; selectedDefectId.value = created.id; await loadProjectData(); Message.success('缺陷已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建缺陷失败') } finally { saving.value = false }
}
async function selectDefect(id: string) { selectedDefectId.value = id; await loadDefectDetails() }
async function addLink() {
  if (!selectedDefectId.value || !linkForm.assetId) { Message.warning('请选择要关联的测试资产'); return }
  try { await mutate(`/api/v1/projects/${projectId.value}/functional/defects/${selectedDefectId.value}/links`, 'POST', linkForm); linkForm.assetId = ''; await loadProjectData(); Message.success('测试资产已关联') }
  catch (error) { Message.error(error instanceof Error ? error.message : '关联失败') }
}
async function removeLink(linkId: string) {
  if (!selectedDefectId.value) return
  try { await mutate(`/api/v1/projects/${projectId.value}/functional/defects/${selectedDefectId.value}/links/${linkId}`, 'DELETE'); await loadProjectData(); Message.success('关联已解除') }
  catch (error) { Message.error(error instanceof Error ? error.message : '解除关联失败') }
}
function download(kind: AssetKind, template = false) { window.open(`/api/v1/projects/${projectId.value}/functional/assets/export?type=${kind}&template=${template}`, '_blank', 'noopener') }
function chooseFile() { fileInput.value?.click() }
function fileChanged(event: Event) { uploadFile.value = (event.target as HTMLInputElement).files?.[0] ?? null; importResult.value = null }
async function importWorkbook() {
  if (!uploadFile.value) { Message.warning('请先选择 .xlsx 文件'); return }
  saving.value = true
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    const form = new FormData(); form.append('type', importKind.value); form.append('file', uploadFile.value)
    importResult.value = await read<ImportResult>(`/api/v1/projects/${projectId.value}/functional/assets/import`, { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token }, body: form })
    if (importResult.value.completed) { await loadProjectData(); Message.success(`已导入 ${importResult.value.importedCount} 条资产`) } else Message.warning('导入预检未通过，请处理错误后重试')
  } catch (error) { Message.error(error instanceof Error ? error.message : '导入失败') } finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page delivery-page">
    <PageHeader eyebrow="TRACEABILITY DELIVERY" title="缺陷与资产交换" description="将缺陷与需求、用例建立追溯关系，并通过标准 Excel 工作簿完成资产导入、导出和批量迁移。" action="新建缺陷" @action="openDefect" />
    <section class="delivery-summary panel"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>缺陷</span><strong>{{ defects.length }}</strong><small>{{ defects.filter((item) => item.status === 'OPEN').length }} 个待处理</small></div><div><span>已关联资产</span><strong>{{ defects.reduce((total, item) => total + item.linkCount, 0) }}</strong><small>需求、用例与执行记录</small></div><div><span>Excel 边界</span><strong>500</strong><small>每批最多导入行数</small></div></section>
    <section class="delivery-grid">
      <article class="panel defect-panel"><div class="section-heading"><div><span class="eyebrow">DEFECT TRACKING</span><h3>项目缺陷</h3></div><a-button type="primary" size="small" @click="openDefect"><icon-plus /> 新建</a-button></div><a-spin :loading="loading"><button v-for="defect in defects" :key="defect.id" class="defect-item" :class="{ selected: selectedDefectId === defect.id }" @click="selectDefect(defect.id)"><div><strong>{{ defect.title }}</strong><small>{{ defect.defectCode }} · {{ defect.linkCount }} 个关联资产</small></div><a-tag :color="statusColor(defect.status)">{{ statusLabel(defect.status) }}</a-tag></button><a-empty v-if="!defects.length && !loading" description="尚未登记项目缺陷" /></a-spin></article>
      <article class="panel trace-panel"><template v-if="selectedDefect"><div class="trace-title"><div><span class="eyebrow">{{ selectedDefect.defectCode }}</span><h3>{{ selectedDefect.title }}</h3><p>{{ selectedDefect.description || '暂无缺陷说明。' }}</p></div><div class="trace-tags"><a-tag :color="severityColor(selectedDefect.severity)">{{ severityLabel(selectedDefect.severity) }}</a-tag><a-tag :color="statusColor(selectedDefect.status)">{{ statusLabel(selectedDefect.status) }}</a-tag></div></div><div class="link-form"><a-select v-model="linkForm.assetType" :style="{ width: '112px' }" @change="linkForm.assetId = ''"><a-option value="CASE">测试用例</a-option><a-option value="REQUIREMENT">需求</a-option></a-select><a-select v-model="linkForm.assetId" allow-search allow-clear placeholder="选择要关联的资产" :style="{ flex: 1 }"><a-option v-for="asset in linkAssets" :key="asset.id" :value="asset.id">{{ asset.label }}</a-option></a-select><a-button type="primary" @click="addLink"><icon-link /> 关联</a-button></div><div class="link-list"><div class="subheading"><strong>追溯关联</strong><span>{{ links.length }} 条</span></div><div v-for="link in links" :key="link.id" class="link-row"><div><a-tag color="arcoblue">{{ assetLabel(link.assetType) }}</a-tag><strong>{{ link.assetLabel }}</strong><small>{{ date(link.createdAt) }}</small></div><a-popconfirm content="确认解除该缺陷关联吗？" @ok="removeLink(link.id)"><a-button type="text" status="danger"><icon-delete /></a-button></a-popconfirm></div><a-empty v-if="!links.length" description="尚未关联测试资产" /></div></template><a-empty v-else description="选择或创建一个缺陷，建立可追溯关系"><template #extra><a-button type="primary" @click="openDefect">新建缺陷</a-button></template></a-empty></article>
    </section>
    <section class="exchange-panel panel">
      <div class="section-heading"><div><span class="eyebrow">EXCEL EXCHANGE</span><h3>资产导入与导出</h3><p>仅接受平台模板的 .xlsx 文件；导入先完整预检，任意行失败都不会写入数据。</p></div><a-tag color="green">项目级隔离</a-tag></div><div class="exchange-grid"><div class="exchange-card"><strong>导出与模板</strong><p>导出当前项目的需求或测试用例，也可下载空白标准模板。</p><a-radio-group v-model="importKind" type="button"><a-radio value="REQUIREMENTS">需求</a-radio><a-radio value="CASES">测试用例</a-radio></a-radio-group><a-space><a-button @click="download(importKind)"><icon-download /> 导出当前数据</a-button><a-button type="outline" @click="download(importKind, true)"><icon-file /> 下载模板</a-button></a-space></div><div class="exchange-card"><strong>导入工作簿</strong><p>大小不超过 5 MB、单次不超过 500 行。请先在当前项目建立所需目录。</p><input ref="fileInput" class="file-input" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="fileChanged" /><div class="file-picker"><span>{{ uploadFile?.name ?? '尚未选择文件' }}</span><a-button @click="chooseFile"><icon-upload /> 选择 .xlsx</a-button></div><a-button type="primary" :loading="saving" :disabled="!uploadFile" @click="importWorkbook"><icon-import /> 预检并导入</a-button></div><div class="exchange-card result-card"><strong>导入结果</strong><template v-if="importResult"><a-result :status="importResult.completed ? 'success' : 'warning'" :title="importResult.completed ? `已导入 ${importResult.importedCount} 条资产` : '导入预检未通过'" :subtitle="importResult.completed ? '所有数据已写入并记录审计日志。' : '请依据以下行号修正 Excel 后重新导入。'" /><div v-if="importResult.errors.length" class="import-errors"><div v-for="error in importResult.errors" :key="`${error.rowNumber}-${error.message}`"><a-tag color="red">{{ error.rowNumber ? `第 ${error.rowNumber} 行` : '文件' }}</a-tag><span>{{ error.message }}</span></div></div></template><a-empty v-else description="导入结果将显示在这里" /></div></div>
    </section>
    <a-modal v-model:visible="defectDialog" title="新建项目缺陷" width="650px" :ok-loading="saving" @ok="saveDefect"><a-form :model="defectForm" layout="vertical"><div class="form-grid"><a-form-item label="缺陷编号" required><a-input v-model="defectForm.defectCode" placeholder="例如：BUG_ORDER_001" /></a-form-item><a-form-item label="严重程度"><a-select v-model="defectForm.severity"><a-option value="CRITICAL">致命</a-option><a-option value="MAJOR">严重</a-option><a-option value="MINOR">一般</a-option><a-option value="TRIVIAL">轻微</a-option></a-select></a-form-item></div><a-form-item label="缺陷标题" required><a-input v-model="defectForm.title" placeholder="例如：订单提交后未生成交易记录" /></a-form-item><div class="form-grid"><a-form-item label="缺陷状态"><a-select v-model="defectForm.status"><a-option value="OPEN">待处理</a-option><a-option value="IN_PROGRESS">处理中</a-option><a-option value="RESOLVED">已解决</a-option><a-option value="CLOSED">已关闭</a-option></a-select></a-form-item><a-form-item label="来源"><a-input v-model="defectForm.source" placeholder="默认 TESTHUB" /></a-form-item></div><a-form-item label="外部链接（可选）"><a-input v-model="defectForm.externalUrl" placeholder="https://jira.example.com/browse/BUG-1" /></a-form-item><a-form-item label="缺陷说明"><a-textarea v-model="defectForm.description" :auto-size="{ minRows: 4, maxRows: 7 }" placeholder="记录复现步骤、影响范围和预期修复结果" /></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.delivery-summary{display:grid;grid-template-columns:1.4fr 1.1fr repeat(3,.82fr);gap:18px;align-items:center;margin-bottom:20px}.delivery-summary>div{display:grid;gap:4px}.delivery-summary span,.delivery-summary small,.defect-item small,.trace-title p,.exchange-card p,.link-row small,.subheading span{color:var(--color-text-3);font-size:12px}.delivery-summary strong{font-size:20px}.delivery-grid{display:grid;grid-template-columns:280px minmax(0,1fr);gap:18px;margin-bottom:18px}.defect-panel,.trace-panel,.exchange-panel{padding:21px}.section-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:18px}.section-heading h3{margin:4px 0;font-size:18px}.section-heading p{margin:6px 0 0;max-width:560px;line-height:1.65}.defect-item{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;padding:12px 8px;border:0;border-radius:8px;background:transparent;text-align:left;cursor:pointer}.defect-item:hover,.defect-item.selected{background:rgb(var(--primary-1))}.defect-item>div{display:grid;gap:4px;min-width:0}.defect-item strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.trace-panel{min-height:280px}.trace-title{display:flex;justify-content:space-between;gap:18px;padding-bottom:16px;border-bottom:1px solid var(--color-border-2)}.trace-title h3{margin:4px 0 6px;font-size:20px}.trace-title p{margin:0;line-height:1.65}.trace-tags{display:flex;align-items:flex-start;gap:6px;white-space:nowrap}.link-form{display:flex;gap:8px;margin:16px 0}.link-list{border-top:1px solid var(--color-border-2);padding-top:14px}.subheading{display:flex;justify-content:space-between;margin-bottom:8px}.link-row{display:flex;align-items:center;justify-content:space-between;padding:9px 0;border-bottom:1px solid var(--color-border-2)}.link-row>div{display:flex;align-items:center;gap:9px;min-width:0}.link-row strong{font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.exchange-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.exchange-card{display:grid;align-content:start;gap:12px;min-height:190px;padding:16px;border:1px solid var(--color-border-2);border-radius:9px;background:var(--color-fill-1)}.exchange-card>strong{font-size:15px}.exchange-card p{margin:0;line-height:1.65}.file-input{display:none}.file-picker{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 0;border-bottom:1px dashed var(--color-border-3);font-size:12px;color:var(--color-text-2)}.file-picker span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.result-card{background:rgb(var(--primary-1))}.result-card :deep(.arco-result){padding:4px 0}.import-errors{display:grid;gap:7px;max-height:150px;overflow:auto}.import-errors>div{display:flex;align-items:flex-start;gap:6px;font-size:12px;line-height:1.55}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}@media (max-width:1100px){.delivery-summary{grid-template-columns:1fr 1fr}.exchange-grid{grid-template-columns:1fr}.delivery-grid{grid-template-columns:240px minmax(0,1fr)}}@media (max-width:720px){.delivery-grid,.form-grid{grid-template-columns:1fr}.trace-title,.link-form{flex-direction:column}.link-form :deep(.arco-select),.link-form :deep(.arco-btn){width:100%!important}.delivery-summary{grid-template-columns:1fr}}
</style>
