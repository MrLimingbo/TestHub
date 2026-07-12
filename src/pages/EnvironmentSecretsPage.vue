<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Secret { id: string; environmentCode: string; key: string; updatedBy: string; updatedAt: string }
interface SecretCollection { environments: string[]; secrets: Secret[] }

const context = ref<Context | null>(null)
const projectId = ref('')
const environment = ref('')
const environments = ref<string[]>([])
const secrets = ref<Secret[]>([])
const loading = ref(true)
const visible = ref(false)
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

async function loadSecrets() {
  if (!projectId.value) return
  const query = environment.value ? `?environment=${encodeURIComponent(environment.value)}` : ''
  const result = await read<SecretCollection>(`/api/v1/projects/${projectId.value}/secrets${query}`)
  environments.value = result.environments
  secrets.value = result.secrets
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await loadSecrets()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载环境密钥失败') } finally { loading.value = false }
}

async function changeProject() { environment.value = ''; try { await loadSecrets() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
async function changeEnvironment() { try { await loadSecrets() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function create() { form.environmentCode = environment.value || 'test'; form.key = ''; form.value = ''; visible.value = true }

async function save() {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/secrets`, 'POST', form)
    visible.value = false
    form.value = ''
    await loadSecrets()
    Message.success('环境密钥已加密保存')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') }
}

async function remove(secret: Secret) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/secrets/${secret.id}`, 'DELETE')
    await loadSecrets()
    Message.success('环境密钥已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="ENVIRONMENT SECRETS" title="环境密钥" description="密钥以 AES-GCM 加密后保存，列表仅展示元数据，密钥值无法再次读取。" action="新建密钥" @action="create" />
    <nav class="settings-tabs" aria-label="项目设置导航"><router-link to="/project-settings">成员与访问令牌</router-link><router-link to="/project-settings/parameters">项目参数</router-link><router-link to="/project-settings/secrets">环境密钥</router-link><router-link to="/project-settings/files">项目文件</router-link></nav>
    <section class="panel secret-summary"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>环境数量</span><strong>{{ environments.length }}</strong></div><div><span>密钥数量</span><strong>{{ secrets.length }}</strong></div></section>
    <section class="panel data-panel"><div class="secret-toolbar"><a-select v-model="environment" allow-clear placeholder="全部环境" :style="{ width: '190px' }" @change="changeEnvironment"><a-option v-for="item in environments" :key="item" :value="item">{{ item }}</a-option></a-select><a-button type="primary" @click="create"><icon-plus /> 新建密钥</a-button></div><a-spin :loading="loading"><a-table :data="secrets" :pagination="false" row-key="id"><template #columns><a-table-column title="环境" data-index="environmentCode"><template #cell="{ record }"><a-tag color="purple">{{ record.environmentCode }}</a-tag></template></a-table-column><a-table-column title="密钥名称" data-index="key"><template #cell="{ record }"><strong>{{ record.key }}</strong><small class="secret-mask">已加密 · 不可回显</small></template></a-table-column><a-table-column title="更新时间" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-button type="text" status="danger" @click="remove(record)">删除</a-button></template></a-table-column></template></a-table></a-spin></section>
    <a-modal v-model:visible="visible" title="新建或更新环境密钥" width="580px" @ok="save"><a-alert type="warning">密钥关闭后不能再次查看；如名称和环境相同，新的值会覆盖旧值。</a-alert><a-form :model="form" layout="vertical"><a-form-item label="环境标识" required><a-input v-model="form.environmentCode" placeholder="test" /></a-form-item><a-form-item label="密钥名称" required><a-input v-model="form.key" placeholder="DATABASE_PASSWORD" /></a-form-item><a-form-item label="密钥值" required><a-input-password v-model="form.value" placeholder="输入后仅会加密保存" allow-clear /></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.settings-tabs{display:flex;gap:8px;margin:-8px 0 20px}.settings-tabs a{padding:8px 12px;color:var(--color-text-2);border-radius:6px;text-decoration:none}.settings-tabs a.router-link-active{color:rgb(var(--primary-6));background:rgb(var(--primary-1));font-weight:600}.secret-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.secret-summary div{display:grid;gap:4px}.secret-summary span,.secret-summary small,.secret-mask{color:var(--color-text-3);font-size:12px}.secret-summary strong{font-size:20px}.secret-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.secret-mask{display:block;margin-top:3px}@media (max-width:900px){.secret-summary{grid-template-columns:1fr 1fr}.secret-toolbar{gap:12px;align-items:flex-start;flex-direction:column}}
</style>
