<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface NotificationTemplate { id: string; name: string; eventCode: string; channel: string; subjectTemplate: string; bodyTemplate: string; enabled: boolean; updatedBy: string; updatedAt: string }

const context = ref<Context | null>(null)
const projectId = ref('')
const templates = ref<NotificationTemplate[]>([])
const loading = ref(true)
const visible = ref(false)
const editingId = ref<string | null>(null)
const channels = ['EMAIL', 'WEBHOOK', 'WECHAT', 'DINGTALK']
const form = reactive({ name: '', eventCode: 'run.failed', channel: 'EMAIL', subjectTemplate: 'TestHub 通知：{{event}}', bodyTemplate: '项目 {{project}} 的任务 {{task}} 执行结果：{{status}}', enabled: true })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown) {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  await read<void>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}

async function loadTemplates() {
  if (!projectId.value) return
  templates.value = await read<NotificationTemplate[]>(`/api/v1/projects/${projectId.value}/notification-templates`)
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadTemplates()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载通知模板失败') } finally { loading.value = false }
}

async function changeProject() { try { await loadTemplates() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function create() {
  editingId.value = null
  form.name = ''
  form.eventCode = 'run.failed'
  form.channel = 'EMAIL'
  form.subjectTemplate = 'TestHub 通知：{{event}}'
  form.bodyTemplate = '项目 {{project}} 的任务 {{task}} 执行结果：{{status}}'
  form.enabled = true
  visible.value = true
}
function edit(template: NotificationTemplate) {
  editingId.value = template.id
  form.name = template.name
  form.eventCode = template.eventCode
  form.channel = template.channel
  form.subjectTemplate = template.subjectTemplate
  form.bodyTemplate = template.bodyTemplate
  form.enabled = template.enabled
  visible.value = true
}
async function save() {
  try {
    const url = editingId.value ? `/api/v1/projects/${projectId.value}/notification-templates/${editingId.value}` : `/api/v1/projects/${projectId.value}/notification-templates`
    await mutate(url, editingId.value ? 'PUT' : 'POST', form)
    visible.value = false
    await loadTemplates()
    Message.success(editingId.value ? '通知模板已更新' : '通知模板已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') }
}
async function remove(template: NotificationTemplate) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/notification-templates/${template.id}`, 'DELETE')
    await loadTemplates()
    Message.success('通知模板已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="NOTIFICATION CENTER" title="通知与集成" description="按项目维护可复用的邮件、Webhook 与企业消息通知模板，为后续任务中心和 CI 集成提供统一出口。" action="新建模板" @action="create" />
    <section class="panel template-summary"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>模板总数</span><strong>{{ templates.length }}</strong></div><div><span>已启用</span><strong>{{ templates.filter((item) => item.enabled).length }}</strong></div></section>
    <section class="panel data-panel"><div class="template-toolbar"><div><a-tag color="arcoblue">项目级模板</a-tag><span class="toolbar-note">事件编码可被任务、流水线和 Webhook 适配器复用</span></div><a-button type="primary" @click="create"><icon-plus /> 新建模板</a-button></div><a-spin :loading="loading"><a-table :data="templates" :pagination="false" row-key="id"><template #columns><a-table-column title="模板名称" data-index="name"><template #cell="{ record }"><strong>{{ record.name }}</strong><small class="template-meta">{{ record.eventCode }}</small></template></a-table-column><a-table-column title="渠道" data-index="channel"><template #cell="{ record }"><a-tag :color="record.channel === 'EMAIL' ? 'arcoblue' : record.channel === 'WEBHOOK' ? 'purple' : 'orange'">{{ record.channel }}</a-tag></template></a-table-column><a-table-column title="主题模板" data-index="subjectTemplate"><template #cell="{ record }"><span class="ellipsis">{{ record.subjectTemplate }}</span></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-badge :status="record.enabled ? 'success' : 'normal'" :text="record.enabled ? '已启用' : '已停用'" /></template></a-table-column><a-table-column title="更新时间" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="edit(record)">编辑</a-button><a-button type="text" status="danger" @click="remove(record)">删除</a-button></a-space></template></a-table-column></template></a-table></a-spin><a-empty v-if="!loading && templates.length === 0" description="当前项目还没有通知模板，先创建一个用于任务和集成通知。" /></section>
    <a-modal v-model:visible="visible" :title="editingId ? '编辑通知模板' : '新建通知模板'" width="680px" @ok="save"><a-form :model="form" layout="vertical"><a-form-item label="模板名称" required><a-input v-model="form.name" placeholder="例如：接口回归失败通知" /></a-form-item><a-form-item label="事件编码" required extra="使用小写字母、数字、点、下划线或连字符，例如 run.failed。"><a-input v-model="form.eventCode" placeholder="run.failed" /></a-form-item><a-form-item label="通知渠道" required><a-select v-model="form.channel"><a-option v-for="channel in channels" :key="channel" :value="channel">{{ channel }}</a-option></a-select></a-form-item><a-form-item label="主题模板" required><a-input v-model="form.subjectTemplate" placeholder="TestHub 通知：{{event}}" /></a-form-item><a-form-item label="正文模板" required><a-textarea v-model="form.bodyTemplate" :auto-size="{ minRows: 5, maxRows: 10 }" placeholder="支持 {{project}}、{{task}} 等事件变量。" /></a-form-item><a-form-item label="启用状态"><a-switch v-model="form.enabled"><template #checked>启用</template><template #unchecked>停用</template></a-switch></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.template-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.template-summary div{display:grid;gap:4px}.template-summary span,.template-summary small,.template-meta,.toolbar-note{color:var(--color-text-3);font-size:12px}.template-summary strong{font-size:20px}.template-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.toolbar-note{margin-left:10px}.template-meta{display:block;margin-top:4px}.ellipsis{display:block;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}@media (max-width:900px){.template-summary{grid-template-columns:1fr 1fr}.template-toolbar{gap:12px;align-items:flex-start;flex-direction:column}.toolbar-note{display:block;margin:6px 0 0}}
</style>
