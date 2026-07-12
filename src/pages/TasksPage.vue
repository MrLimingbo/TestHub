<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface ExecutionTask { id: string; organizationId: string; projectId: string; taskType: string; name: string; status: string; progress: number; requestedBy: string; createdAt: string; updatedAt: string }

const context = ref<Context | null>(null)
const projectId = ref('')
const tasks = ref<ExecutionTask[]>([])
const loading = ref(true)
const visible = ref(false)
const editingId = ref<string | null>(null)
const keyword = ref('')
const form = reactive({ taskType: 'API', name: '', status: 'QUEUED', progress: 0 })
const taskTypes = ['API', 'FUNCTIONAL', 'PERFORMANCE', 'UI', 'MOBILE', 'OTHER']
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
  if (!projectId.value) return
  tasks.value = await read<ExecutionTask[]>(`/api/v1/projects/${projectId.value}/tasks`)
}
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadTasks()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载任务失败') } finally { loading.value = false }
}
async function changeProject() { try { await loadTasks() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function create() {
  editingId.value = null
  form.taskType = 'API'; form.name = ''; form.status = 'QUEUED'; form.progress = 0; visible.value = true
}
function edit(task: ExecutionTask) {
  editingId.value = task.id
  form.taskType = task.taskType; form.name = task.name; form.status = task.status; form.progress = task.progress; visible.value = true
}
async function save() {
  try {
    const url = editingId.value ? `/api/v1/projects/${projectId.value}/tasks/${editingId.value}` : `/api/v1/projects/${projectId.value}/tasks`
    await mutate(url, editingId.value ? 'PUT' : 'POST', editingId.value ? { name: form.name, status: form.status, progress: form.progress } : { taskType: form.taskType, name: form.name })
    visible.value = false; await loadTasks(); Message.success(editingId.value ? '任务已更新' : '任务已加入执行队列')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') }
}
async function updateTask(task: ExecutionTask, status: string, progress: number) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/tasks/${task.id}`, 'PUT', { name: task.name, status, progress })
    await loadTasks(); Message.success(`任务已${statusLabel(status)}`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '更新失败') }
}
async function cancel(task: ExecutionTask) {
  try { await mutate(`/api/v1/projects/${projectId.value}/tasks/${task.id}/cancel`, 'POST'); await loadTasks(); Message.success('任务已取消') }
  catch (error) { Message.error(error instanceof Error ? error.message : '取消失败') }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="EXECUTION CENTER" title="任务中心" description="统一查看项目执行任务。本节点提供队列、状态和取消基础能力，Runner 接入后将由独立执行服务消费任务。" action="新建任务" @action="create" />
    <section class="panel task-summary"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>活动任务</span><strong>{{ activeCount }}</strong></div><div><span>已结束</span><strong>{{ finishedCount }}</strong></div></section>
    <section class="panel data-panel"><div class="task-toolbar"><div><a-input-search v-model="keyword" placeholder="搜索任务名称" :style="{ width: '260px' }" allow-clear /><a-tag color="arcoblue">{{ tasks.length }} 个任务</a-tag></div><a-button type="primary" @click="create"><icon-plus /> 新建任务</a-button></div><a-spin :loading="loading"><a-table :data="filteredTasks" :pagination="false" row-key="id"><template #columns><a-table-column title="任务名称" data-index="name"><template #cell="{ record }"><div class="task-name"><span class="task-icon"><icon-play-circle /></span><div><strong>{{ record.name }}</strong><small>{{ typeLabel(record.taskType) }}任务 · {{ date(record.createdAt) }}</small></div></div></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column><a-table-column title="进度" data-index="progress"><template #cell="{ record }"><div class="progress-cell"><a-progress :percent="record.progress / 100" size="small" :stroke-width="6" /><span>{{ record.progress }}%</span></div></template></a-table-column><a-table-column title="最近更新" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button v-if="record.status === 'QUEUED'" type="text" @click="updateTask(record, 'RUNNING', 1)">启动</a-button><a-button v-if="record.status === 'RUNNING'" type="text" status="success" @click="updateTask(record, 'SUCCEEDED', 100)">标记成功</a-button><a-button v-if="['QUEUED','RUNNING'].includes(record.status)" type="text" status="danger" @click="cancel(record)">取消</a-button><a-button type="text" @click="edit(record)">编辑</a-button></a-space></template></a-table-column></template></a-table></a-spin><a-empty v-if="!loading && filteredTasks.length === 0" description="当前项目还没有执行任务，创建一个任务开始演示队列状态。" /></section>
    <a-modal v-model:visible="visible" :title="editingId ? '编辑执行任务' : '新建执行任务'" width="600px" @ok="save"><a-form :model="form" layout="vertical"><a-form-item v-if="!editingId" label="任务类型" required><a-select v-model="form.taskType"><a-option v-for="type in taskTypes" :key="type" :value="type">{{ typeLabel(type) }}</a-option></a-select></a-form-item><a-form-item label="任务名称" required><a-input v-model="form.name" placeholder="例如：交易中台接口回归" /></a-form-item><template v-if="editingId"><a-form-item label="状态"><a-select v-model="form.status"><a-option v-for="status in statusOptions" :key="status" :value="status">{{ statusLabel(status) }}</a-option></a-select></a-form-item><a-form-item label="进度"><a-input-number v-model="form.progress" :min="0" :max="100" :style="{ width: '180px' }" /> %</a-form-item></template></a-form></a-modal>
  </div>
</template>

<style scoped>
.task-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.task-summary div{display:grid;gap:4px}.task-summary span,.task-summary small,.task-name small{color:var(--color-text-3);font-size:12px}.task-summary strong{font-size:20px}.task-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.task-toolbar>div{display:flex;align-items:center;gap:12px}.task-name{display:flex;align-items:center;gap:10px}.task-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.task-name small{display:block;margin-top:4px}.progress-cell{display:flex;align-items:center;gap:10px;min-width:150px}.progress-cell :deep(.arco-progress){flex:1}.progress-cell span{width:38px;color:var(--color-text-2);font-size:12px}@media (max-width:900px){.task-summary{grid-template-columns:1fr 1fr}.task-toolbar{gap:12px;align-items:flex-start;flex-direction:column}.task-toolbar>div{width:100%;justify-content:space-between}}
</style>
