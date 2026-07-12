<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string; code: string }
interface Project { id: string; organizationId: string; name: string; code: string; status: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface Role { code: string; name: string; description: string }
interface Member { membershipId: string; email: string; displayName: string; roleCode: string; createdAt: string }
interface Token { id: string; name: string; tokenPrefix: string; scopes: string[]; expiresAt: string | null; lastUsedAt: string | null; revokedAt: string | null; createdAt: string }
interface CreatedToken { name: string; token: string; tokenPrefix: string; scopes: string[]; expiresAt: string | null }

const context = ref<Context | null>(null)
const roles = ref<Role[]>([])
const projectId = ref('')
const members = ref<Member[]>([])
const tokens = ref<Token[]>([])
const loading = ref(true)
const memberVisible = ref(false)
const tokenVisible = ref(false)
const createdToken = ref<CreatedToken | null>(null)
const createdTokenVisible = ref(false)
const memberForm = reactive({ email: '', roleCode: 'TESTER' })
const tokenForm = reactive({ name: '', scopes: ['project:read'], expiresInDays: 30 })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const roleLabel = (code: string) => roles.value.find((role) => role.code === code)?.name ?? code
const date = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value)) : '永不过期'

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function loadProjectData() {
  if (!projectId.value) return
  ;[members.value, tokens.value] = await Promise.all([
    read<Member[]>(`/api/v1/projects/${projectId.value}/members`),
    read<Token[]>(`/api/v1/projects/${projectId.value}/access-tokens`),
  ])
}

async function load() {
  loading.value = true
  try {
    const [nextContext, nextRoles] = await Promise.all([read<Context>('/api/v1/access/context'), read<Role[]>('/api/v1/access/roles')])
    context.value = nextContext
    roles.value = nextRoles
    projectId.value = projects.value[0]?.id ?? ''
    await loadProjectData()
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载项目权限失败')
  } finally {
    loading.value = false
  }
}

async function changeProject() {
  try { await loadProjectData() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') }
}

async function saveMember() {
  try {
    await mutate<Member>(`/api/v1/projects/${projectId.value}/members`, 'POST', memberForm)
    memberVisible.value = false
    memberForm.email = ''
    memberForm.roleCode = 'TESTER'
    await loadProjectData()
    Message.success('成员权限已更新')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') }
}

async function createToken() {
  try {
    createdToken.value = await mutate<CreatedToken>(`/api/v1/projects/${projectId.value}/access-tokens`, 'POST', tokenForm)
    createdTokenVisible.value = true
    tokenVisible.value = false
    tokenForm.name = ''
    await loadProjectData()
    Message.success('访问令牌已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建失败') }
}

async function revokeToken(token: Token) {
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/access-tokens/${token.id}/revoke`, 'POST')
    await loadProjectData()
    Message.success('访问令牌已撤销')
  } catch (error) { Message.error(error instanceof Error ? error.message : '撤销失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="PROJECT SETTINGS" title="成员与访问令牌" description="管理项目成员角色，以及供 CLI 和 CI 使用的可撤销访问令牌。" action="添加成员" @action="memberVisible = true" />
    <nav class="settings-tabs" aria-label="项目设置导航"><router-link to="/project-settings">成员与访问令牌</router-link><router-link to="/project-settings/parameters">项目参数</router-link><router-link to="/project-settings/secrets">环境密钥</router-link><router-link to="/project-settings/files">项目文件</router-link></nav>
    <section class="panel access-summary">
      <div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div>
      <a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject">
        <a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option>
      </a-select>
      <div><span>成员数量</span><strong>{{ members.length }}</strong></div>
      <div><span>有效令牌</span><strong>{{ tokens.filter((token) => !token.revokedAt).length }}</strong></div>
    </section>

    <a-spin :loading="loading" class="access-content">
      <section class="panel data-panel">
        <div class="section-heading"><div><h3>项目成员</h3><p>角色变更立即生效；本地预置账号可使用 lead@testhub.local 和 tester@testhub.local。</p></div><a-button type="primary" @click="memberVisible = true"><icon-plus /> 添加成员</a-button></div>
        <a-table :data="members" :pagination="false" row-key="membershipId">
          <template #columns>
            <a-table-column title="成员" data-index="displayName"><template #cell="{ record }"><strong>{{ record.displayName }}</strong><small class="table-subtitle">{{ record.email }}</small></template></a-table-column>
            <a-table-column title="项目角色" data-index="roleCode"><template #cell="{ record }"><a-tag color="arcoblue">{{ roleLabel(record.roleCode) }}</a-tag></template></a-table-column>
            <a-table-column title="加入时间" data-index="createdAt"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column>
          </template>
        </a-table>
      </section>

      <section class="panel data-panel">
        <div class="section-heading"><div><h3>个人访问令牌</h3><p>令牌仅在创建时展示一次，数据库只保存 SHA-256 哈希；撤销后立即失效。</p></div><a-button type="primary" @click="tokenVisible = true"><icon-plus /> 新建令牌</a-button></div>
        <a-table :data="tokens" :pagination="false" row-key="id">
          <template #columns>
            <a-table-column title="名称" data-index="name"><template #cell="{ record }"><strong>{{ record.name }}</strong><small class="table-subtitle">{{ record.tokenPrefix }}••••</small></template></a-table-column>
            <a-table-column title="权限范围" data-index="scopes"><template #cell="{ record }"><a-space><a-tag v-for="scope in record.scopes" :key="scope" color="purple">{{ scope }}</a-tag></a-space></template></a-table-column>
            <a-table-column title="过期时间" data-index="expiresAt"><template #cell="{ record }">{{ date(record.expiresAt) }}</template></a-table-column>
            <a-table-column title="状态"><template #cell="{ record }"><a-tag :color="record.revokedAt ? 'red' : 'green'">{{ record.revokedAt ? '已撤销' : '有效' }}</a-tag></template></a-table-column>
            <a-table-column title="操作"><template #cell="{ record }"><a-button v-if="!record.revokedAt" type="text" status="danger" @click="revokeToken(record)">撤销</a-button></template></a-table-column>
          </template>
        </a-table>
      </section>
    </a-spin>

    <a-modal v-model:visible="memberVisible" title="添加或更新项目成员" width="520px" @ok="saveMember">
      <a-form :model="memberForm" layout="vertical"><a-form-item label="本地账号邮箱" required><a-input v-model="memberForm.email" placeholder="例如 tester@testhub.local" /></a-form-item><a-form-item label="项目角色" required><a-select v-model="memberForm.roleCode"><a-option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }} · {{ role.description }}</a-option></a-select></a-form-item></a-form>
    </a-modal>
    <a-modal v-model:visible="tokenVisible" title="新建个人访问令牌" width="560px" @ok="createToken">
      <a-form :model="tokenForm" layout="vertical"><a-form-item label="令牌名称" required><a-input v-model="tokenForm.name" placeholder="例如 Jenkins 每晚回归" /></a-form-item><a-form-item label="权限范围" required><a-checkbox-group v-model="tokenForm.scopes"><a-checkbox value="project:read">读取项目</a-checkbox><a-checkbox value="project:write">写入项目</a-checkbox><a-checkbox value="run:execute">触发执行</a-checkbox></a-checkbox-group></a-form-item><a-form-item label="有效期"><a-input-number v-model="tokenForm.expiresInDays" :min="1" :max="365" /> <span class="form-hint">天（1–365）</span></a-form-item></a-form>
    </a-modal>
    <a-modal v-model:visible="createdTokenVisible" title="请立即保存访问令牌" :footer="false" width="620px">
      <a-alert type="warning">令牌关闭后将无法再次查看，请将它存放到 CI 的密钥管理系统中。</a-alert><pre class="token-value">{{ createdToken?.token }}</pre><a-space><a-button type="primary" @click="createdTokenVisible = false">我已保存</a-button></a-space>
    </a-modal>
  </div>
</template>

<style scoped>
.settings-tabs{display:flex;gap:8px;margin:-8px 0 20px}.settings-tabs a{padding:8px 12px;color:var(--color-text-2);border-radius:6px;text-decoration:none}.settings-tabs a.router-link-active{color:rgb(var(--primary-6));background:rgb(var(--primary-1));font-weight:600}.access-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.access-summary div{display:grid;gap:4px}.access-summary span,.access-summary small,.table-subtitle,.section-heading p{color:var(--color-text-3);font-size:12px}.access-summary strong{font-size:20px}.access-content{display:grid;gap:20px}.section-heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;margin-bottom:18px}.section-heading h3{margin:0 0 5px}.section-heading p{margin:0}.table-subtitle{display:block;margin-top:3px}.token-value{margin:16px 0;padding:14px;white-space:pre-wrap;word-break:break-all;background:#111827;color:#c7f9e9;border-radius:8px}.form-hint{color:var(--color-text-3);margin-left:8px}@media (max-width:900px){.access-summary{grid-template-columns:1fr 1fr}.section-heading{flex-direction:column}}
</style>
