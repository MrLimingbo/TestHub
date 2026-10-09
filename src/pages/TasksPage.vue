<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'
import UnifiedExecutionsPanel from '@/components/UnifiedExecutionsPanel.vue'
import { useSessionStore } from '@/stores/session'
import { isLocalDesktop, isAccountDesktop, openTaskSync } from '@/utils/desktopWorkspace'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface ExecutionTask {
  id: string; organizationId: string; projectId: string; taskType: string; name: string; status: string
  progress: number; requestedBy: string; createdAt: string; updatedAt: string; version: number
  sourceTaskId: string | null; sourceStatus: string | null; sourceVersion: number | null; sourceCreatedAt: string | null
}
const context = ref<Context | null>(null), session = useSessionStore()
const projectId = computed({ get: () => session.projectId, set: (value: string) => session.selectProject(value) })
const tasks = ref<ExecutionTask[]>([]), loading = ref(true), saving = ref(false), loadError = ref('')
const visible = ref(false), editingId = ref<string | null>(null), keyword = ref(''), pendingTasks = ref(new Set<string>())
let taskRequest = 0, formRequest = 0, formProjectId = '', disposed = false
const form = reactive({ taskType: 'API', name: '', status: 'QUEUED', progress: 0, expectedVersion: null as number | null })
const taskTypes = ['API', 'FUNCTIONAL', 'PERFORMANCE', 'UI', 'MOBILE', 'OTHER']
const desktop = isLocalDesktop() || isAccountDesktop()
async function syncSettings() {
  try { await openTaskSync() } catch (error) { Message.error(error instanceof Error ? error.message : '无法打开任务同步设置') }
}
const statusOptions = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED']
const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const filteredTasks = computed(() => tasks.value.filter((task) => task.name.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const activeCount = computed(() => tasks.value.filter((task) => ['QUEUED', 'RUNNING'].includes(task.status)).length)
const finishedCount = computed(() => tasks.value.filter((task) => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status)).length)
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const statusLabel = (status: string) => ({ QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已成功', FAILED: '失败', CANCELLED: '已取消' }[status] ?? status)
const statusColor = (status: string) => ({ QUEUED: 'orange', RUNNING: 'arcoblue', SUCCEEDED: 'green', FAILED: 'red', CANCELLED: 'gray' }[status] ?? 'gray')
const typeLabel = (type: string) => ({ API: '接口', FUNCTIONAL: '功能', PERFORMANCE: '性能', UI: 'Web UI', MOBILE: '移动端', OTHER: '其他' }[type] ?? type)
async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate(url: string, method: 'POST' | 'PUT', body?: unknown) {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  await read<void>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadTasks() {
  const request = ++taskRequest, project = projectId.value
  tasks.value = []; loadError.value = ''
  if (disposed || !context.value || !selectedProject.value) { loading.value = false; return }
  loading.value = true
  try {
    const value = await read<ExecutionTask[]>(`/api/v1/projects/${project}/tasks`)
    if (!disposed && request === taskRequest && project === projectId.value) tasks.value = value
  } catch (error) {
    if (!disposed && request === taskRequest && project === projectId.value) loadError.value = error instanceof Error ? error.message : '加载任务失败'
  } finally { if (!disposed && request === taskRequest && project === projectId.value) loading.value = false }
}
async function load() {
  try {
    const value = await read<Context>('/api/v1/access/context')
    if (disposed) return
    context.value = value
    const previous = projectId.value
    session.resolveProjectId(projects.value)
    if (previous === projectId.value) await loadTasks()
  } catch (error) { if (!disposed) { loading.value = false; loadError.value = error instanceof Error ? error.message : '加载项目失败' } }
}
function closeForm() { formRequest++; visible.value = false }
function create() {
  if (!selectedProject.value || loading.value) return
  formRequest++; formProjectId = projectId.value; editingId.value = null
  Object.assign(form, { taskType: 'API', name: '', status: 'QUEUED', progress: 0, expectedVersion: null }); visible.value = true
}
function edit(task: ExecutionTask) {
  if (task.projectId !== projectId.value || pendingTasks.value.has(task.id)) return
  formRequest++; formProjectId = task.projectId; editingId.value = task.id
  Object.assign(form, { taskType: task.taskType, name: task.name, status: task.status, progress: task.progress, expectedVersion: task.version }); visible.value = true
}
async function save() {
  if (saving.value || disposed || formProjectId !== projectId.value || !visible.value) return false
  const request = formRequest, project = formProjectId, id = editingId.value
  const input = id ? { name: form.name, status: form.status, progress: form.progress, expectedVersion: form.expectedVersion } : { taskType: form.taskType, name: form.name }
  saving.value = true
  try {
    await mutate(`/api/v1/projects/${project}/tasks${id ? '/' + id : ''}`, id ? 'PUT' : 'POST', input)
    if (disposed || request !== formRequest || project !== projectId.value) return false
    visible.value = false; await loadTasks()
    if (disposed || request !== formRequest || project !== projectId.value) return false
    Message.success(id ? '任务登记已更新' : '任务登记已创建'); return true
  } catch (error) { if (!disposed && request === formRequest && project === projectId.value) Message.error(error instanceof Error ? error.message : '保存失败'); return false }
  finally { saving.value = false }
}
async function updateTask(task: ExecutionTask, status: string, progress: number) {
  await act(task, 'PUT', '', { name: task.name, status, progress, expectedVersion: task.version }, `任务已${statusLabel(status)}`)
}
async function cancel(task: ExecutionTask) {
  await act(task, 'POST', '/cancel', { expectedVersion: task.version }, '任务登记已取消')
}
async function act(task: ExecutionTask, method: 'POST' | 'PUT', suffix: string, input: unknown, message: string) {
  if (disposed || task.projectId !== projectId.value || pendingTasks.value.has(task.id)) return
  pendingTasks.value.add(task.id)
  try {
    await mutate(`/api/v1/projects/${task.projectId}/tasks/${task.id}${suffix}`, method, input)
    if (disposed || task.projectId !== projectId.value) return
    await loadTasks()
    if (!disposed && task.projectId === projectId.value) Message.success(message)
  } catch (error) { if (!disposed && task.projectId === projectId.value) Message.error(error instanceof Error ? error.message : '操作失败') }
  finally { pendingTasks.value.delete(task.id) }
}
watch(projectId, () => { closeForm(); keyword.value = ''; void loadTasks() }, { flush: 'sync' })
onMounted(load)
onBeforeUnmount(() => { disposed = true; taskRequest++; formRequest++ })
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="EXECUTION CENTER" title="任务中心" description="查看各测试模块的真实执行记录，并管理独立的手工任务登记。" :action="!loading && selectedProject ? '新建任务' : undefined" @action="create" />
    <UnifiedExecutionsPanel :project-id="context && selectedProject ? projectId : ''" />
    <section class="panel task-summary"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '240px' }"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>活动登记</span><strong>{{ activeCount }}</strong></div><div><span>已结束</span><strong>{{ finishedCount }}</strong></div></section>
    <section class="panel data-panel"><h2 class="registration-heading">手工任务登记</h2><div class="task-toolbar"><div><a-input-search v-model="keyword" placeholder="搜索任务名称" :style="{ width: '260px' }" allow-clear /><a-tag color="arcoblue">{{ tasks.length }} 个任务</a-tag></div><a-space><a-button v-if="desktop" @click="syncSettings">任务登记同步</a-button><a-button :loading="loading" :disabled="!selectedProject" @click="loadTasks">刷新</a-button><a-button type="primary" :disabled="loading || !selectedProject" @click="create"><icon-plus /> 新建任务</a-button></a-space></div><a-alert v-if="loadError" type="error">{{ loadError }}</a-alert><a-spin :loading="loading"><a-table :data="filteredTasks" :pagination="false" row-key="id"><template #columns><a-table-column title="任务名称" data-index="name"><template #cell="{ record }"><div class="task-name"><span class="task-icon"><icon-play-circle /></span><div><strong>{{ record.name }}</strong><small>{{ typeLabel(record.taskType) }}登记 · {{ date(record.createdAt) }}</small><small v-if="record.sourceTaskId" class="task-source">迁入登记 · 来源{{ statusLabel(record.sourceStatus) }} · 来源第 {{ record.sourceVersion }} 版<span v-if="record.sourceCreatedAt"> · {{ date(record.sourceCreatedAt) }}</span></small></div></div></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column><a-table-column title="进度" data-index="progress"><template #cell="{ record }"><div class="progress-cell"><a-progress :percent="record.progress / 100" :show-text="false" size="small" :stroke-width="6" /><span>{{ record.progress }}%</span></div></template></a-table-column><a-table-column title="最近更新" data-index="updatedAt" :width="165"><template #cell="{ record }"><time class="task-date" :datetime="record.updatedAt">{{ date(record.updatedAt) }}</time></template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button v-if="record.status === 'QUEUED'" type="text" :disabled="pendingTasks.has(record.id)" @click="updateTask(record, 'RUNNING', 1)">标记执行中</a-button><a-button v-if="record.status === 'RUNNING'" type="text" :disabled="pendingTasks.has(record.id)" status="success" @click="updateTask(record, 'SUCCEEDED', 100)">标记成功</a-button><a-button v-if="['QUEUED','RUNNING'].includes(record.status)" type="text" :loading="pendingTasks.has(record.id)" status="danger" @click="cancel(record)">取消</a-button><a-button type="text" :disabled="pendingTasks.has(record.id)" @click="edit(record)">编辑</a-button></a-space></template></a-table-column></template></a-table></a-spin><a-empty v-if="!loading && !loadError && filteredTasks.length === 0" description="当前筛选范围没有任务登记。" /></section>
    <a-modal v-model:visible="visible" :title="editingId ? '编辑执行任务' : '新建执行任务'" width="600px" :ok-loading="saving" :on-before-ok="save" @cancel="closeForm"><a-form :model="form" layout="vertical"><a-alert type="info">在此保存任务登记及状态；测试执行请前往对应测试模块。</a-alert><a-form-item v-if="!editingId" label="任务类型" required><a-select v-model="form.taskType"><a-option v-for="type in taskTypes" :key="type" :value="type">{{ typeLabel(type) }}</a-option></a-select></a-form-item><a-form-item label="任务名称" required><a-input v-model="form.name" placeholder="例如：交易中台接口回归" /></a-form-item><template v-if="editingId"><a-form-item label="状态"><a-select v-model="form.status"><a-option v-for="status in statusOptions" :key="status" :value="status">{{ statusLabel(status) }}</a-option></a-select></a-form-item><a-form-item label="进度"><a-input-number v-model="form.progress" :min="0" :max="100" :style="{ width: '180px' }" /> %</a-form-item></template></a-form></a-modal>
  </div>
</template>

<style scoped>
.data-panel{padding:20px}.registration-heading{margin:0 0 16px;font-size:17px}.task-date{font-size:12px;white-space:nowrap}.task-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.task-summary div{display:grid;gap:4px}.task-summary span,.task-summary small,.task-name small{color:var(--color-text-3);font-size:12px}.task-summary strong{font-size:20px}.task-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.task-toolbar>div{display:flex;align-items:center;gap:12px}.task-name{display:flex;align-items:center;gap:10px}.task-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.task-name small{display:block;margin-top:4px}.progress-cell{display:flex;align-items:center;gap:10px;min-width:150px}.progress-cell :deep(.arco-progress){flex:1}.progress-cell span{width:38px;color:var(--color-text-2);font-size:12px}@media (max-width:900px){.task-summary{grid-template-columns:1fr 1fr}.task-toolbar{gap:12px;align-items:flex-start;flex-direction:column}.task-toolbar>div{width:100%;justify-content:space-between}}
</style>
