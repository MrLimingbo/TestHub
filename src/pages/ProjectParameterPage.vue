<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Parameter { id: string; environmentCode: string; key: string; value: string; secret: boolean; updatedBy: string; updatedAt: string }
interface ParameterCollection { environments: string[]; parameters: Parameter[] }

const context = ref<Context | null>(null)
const projectId = ref('')
const environment = ref('')
const environments = ref<string[]>([])
const parameters = ref<Parameter[]>([])
const loading = ref(true)
const visible = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({ environmentCode: 'test', key: '', value: '' })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate(url: string, method: 'POST' | 'DELETE', body?: unknown) {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  await read<void>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}

async function loadParameters() {
  if (!projectId.value) return
  const query = environment.value ? `?environment=${encodeURIComponent(environment.value)}` : ''
  const result = await read<ParameterCollection>(`/api/v1/projects/${projectId.value}/parameters${query}`)
  environments.value = result.environments
  parameters.value = result.parameters
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadParameters()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载项目参数失败') } finally { loading.value = false }
}

async function changeProject() {
  environment.value = ''
  try { await loadParameters() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') }
}

async function changeEnvironment() {
  try { await loadParameters() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') }
}

function create() {
  editingId.value = null
  form.environmentCode = environment.value || 'test'
  form.key = ''
  form.value = ''
  visible.value = true
}

function edit(parameter: Parameter) {
  editingId.value = parameter.id
  form.environmentCode = parameter.environmentCode
  form.key = parameter.key
  form.value = parameter.value
  visible.value = true
}

async function save() {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/parameters`, 'POST', form)
    visible.value = false
    await loadParameters()
    Message.success(editingId.value ? '项目参数已更新' : '项目参数已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') }
}

async function remove(parameter: Parameter) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/parameters/${parameter.id}`, 'DELETE')
    await loadParameters()
    Message.success('项目参数已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="PROJECT CONFIGURATION" title="项目参数" description="按项目与环境维护非敏感配置。环境密钥将在下一小节单独加密存储与脱敏展示。" action="新建参数" @action="create" />
    <nav class="settings-tabs" aria-label="项目设置导航"><router-link to="/project-settings">成员与访问令牌</router-link><router-link to="/project-settings/parameters">项目参数</router-link><router-link to="/project-settings/secrets">环境密钥</router-link><router-link to="/project-settings/files">项目文件</router-link></nav>
    <section class="panel parameter-summary">
      <div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div>
      <a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select>
      <div><span>环境数量</span><strong>{{ environments.length }}</strong></div><div><span>参数数量</span><strong>{{ parameters.length }}</strong></div>
    </section>
    <section class="panel data-panel">
      <div class="parameter-toolbar"><div><a-select v-model="environment" allow-clear placeholder="全部环境" :style="{ width: '190px' }" @change="changeEnvironment"><a-option v-for="item in environments" :key="item" :value="item">{{ item }}</a-option></a-select></div><a-button type="primary" @click="create"><icon-plus /> 新建参数</a-button></div>
      <a-spin :loading="loading">
        <a-table :data="parameters" :pagination="false" row-key="id">
          <template #columns>
            <a-table-column title="环境" data-index="environmentCode"><template #cell="{ record }"><a-tag color="arcoblue">{{ record.environmentCode }}</a-tag></template></a-table-column>
            <a-table-column title="参数名称" data-index="key"><template #cell="{ record }"><strong>{{ record.key }}</strong></template></a-table-column>
            <a-table-column title="参数值" data-index="value"><template #cell="{ record }"><code class="parameter-value">{{ record.value }}</code></template></a-table-column>
            <a-table-column title="更新时间" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column>
            <a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="edit(record)">编辑</a-button><a-button type="text" status="danger" @click="remove(record)">删除</a-button></a-space></template></a-table-column>
          </template>
        </a-table>
      </a-spin>
    </section>
    <a-modal v-model:visible="visible" :title="editingId ? '编辑项目参数' : '新建项目参数'" width="580px" @ok="save">
      <a-form :model="form" layout="vertical"><a-form-item label="环境标识" required extra="小写字母、数字、下划线或连字符，例如 test、staging、prod。"><a-input v-model="form.environmentCode" placeholder="test" :disabled="!!editingId" /></a-form-item><a-form-item label="参数名称" required extra="支持字母、数字、下划线、点和连字符，例如 API_BASE_URL。"><a-input v-model="form.key" placeholder="API_BASE_URL" :disabled="!!editingId" /></a-form-item><a-form-item label="参数值" required><a-textarea v-model="form.value" placeholder="https://api.test.example.com" :auto-size="{ minRows: 3, maxRows: 6 }" /></a-form-item></a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.settings-tabs{display:flex;gap:8px;margin:-8px 0 20px}.settings-tabs a{padding:8px 12px;color:var(--color-text-2);border-radius:6px;text-decoration:none}.settings-tabs a.router-link-active{color:rgb(var(--primary-6));background:rgb(var(--primary-1));font-weight:600}.parameter-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.parameter-summary div{display:grid;gap:4px}.parameter-summary span,.parameter-summary small{color:var(--color-text-3);font-size:12px}.parameter-summary strong{font-size:20px}.parameter-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.parameter-value{display:inline-block;max-width:330px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:4px 7px;background:var(--color-fill-2);border-radius:4px;color:var(--color-text-2)}@media (max-width:900px){.parameter-summary{grid-template-columns:1fr 1fr}.parameter-toolbar{gap:12px;align-items:flex-start;flex-direction:column}}
</style>
