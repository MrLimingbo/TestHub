<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRoute, useRouter } from 'vue-router'
import { type Project, useSessionStore } from '@/stores/session'
import { isLocalDesktop, isAccountDesktop, openSettingsSync } from '@/utils/desktopWorkspace'

interface ApiEnvelope<T> { data: T }
interface Role { code: string; name: string; description: string }
interface Member { membershipId: string; account: string; email: string; displayName: string; roleCode: string; createdAt: string }
interface AccountOption { id: string; account: string; displayName: string; email: string }
interface Parameter { id: string; environmentCode: string; key: string; value: string; updatedAt: string }
interface ParameterCollection { environments: string[]; parameters: Parameter[] }
interface Secret { id: string; environmentCode: string; key: string; updatedAt: string }
interface SecretCollection { environments: string[]; secrets: Secret[] }
interface ProjectEnvironment { id: string; name: string; environmentCode: string; defaultEnvironment: boolean; enabled: boolean }
interface FileObject { id: string; originalName: string; contentType: string; sizeBytes: number; createdAt: string }
interface AuditEntry {
  id: string
  userId: string | null
  projectId: string | null
  action: string
  resourceType: string
  resourceId: string | null
  outcome: string
  requestId: string
  createdAt: string
}

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const desktop = isLocalDesktop() || isAccountDesktop()
async function showSettingsSync() {
  try { await openSettingsSync() } catch (error) { Message.error(error instanceof Error ? error.message : '无法打开项目配置同步。') }
}
const loading = ref(false)
const loadError = ref('')
const pending = reactive(new Set<string>())
let ready = false, disposed = false, resourceSequence = 0, logSequence = 0, viewSequence = 0
let formSequence = 0, formProjectId = ''
const currentView = (projectId: string, view: number) => !disposed && session.projectId === projectId && viewSequence === view
function closeForms() {
  formSequence++
  projectDialog.value = false; memberDialog.value = false; environmentDialog.value = false
  environmentForm.value = ''
}
function captureForm() { formProjectId = session.projectId; formSequence++ }
function clearResources() {
  members.value = []; parameters.value = []; secrets.value = []; projectEnvironments.value = []; files.value = []; auditEntries.value = []
}

const saving = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const projectDialog = ref(false)
const memberDialog = ref(false)
const environmentDialog = ref(false)
const editingProject = ref(false)
const editingProjectId = ref<string | null>(null)
const editingParameterId = ref<string | null>(null)
const environmentKind = ref<'parameter' | 'secret'>('parameter')
const environmentFilter = ref('')
const environmentView = ref<'parameters' | 'secrets'>('parameters')
const auditAction = ref('')
const roles = ref<Role[]>([])
const members = ref<Member[]>([])
const accountOptions = ref<AccountOption[]>([])
const accountLookupError = ref('')
const parameters = ref<Parameter[]>([])
const secrets = ref<Secret[]>([])
const projectEnvironments = ref<ProjectEnvironment[]>([])
const files = ref<FileObject[]>([])
const auditEntries = ref<AuditEntry[]>([])
const projectForm = reactive({ name: '', code: '', description: '' })
const memberForm = reactive({ account: '', roleCode: 'TESTER' })
const environmentForm = reactive({ environmentCode: 'test', key: '', value: '' })

const activeTab = computed(() => {
  if (route.path.endsWith('/members')) return 'members'
  if (route.path.endsWith('/environments')) return 'environments'
  if (route.path.endsWith('/files')) return 'files'
  if (route.path.endsWith('/logs')) return 'logs'
  return 'info'
})
const currentProject = computed(() => session.currentProject)
const availableProjects = computed(() => session.projects)
const environmentNames = computed(() => projectEnvironments.value.map((item) => item.environmentCode))
const visibleParameters = computed(() => environmentFilter.value
  ? parameters.value.filter((item) => item.environmentCode === environmentFilter.value && item.key !== 'BASE_URL')
  : parameters.value.filter((item) => item.key !== 'BASE_URL'))
const visibleSecrets = computed(() => environmentFilter.value
  ? secrets.value.filter((item) => item.environmentCode === environmentFilter.value)
  : secrets.value)
const totalFileSize = computed(() => files.value.reduce((total, file) => total + file.sizeBytes, 0))
const auditActions = computed(() => Array.from(new Set(auditEntries.value.map((entry) => entry.action))).sort())

const tabItems = [
  { key: 'info', label: '项目信息', path: '/project-settings' },
  { key: 'members', label: '项目成员', path: '/project-settings/members' },
  { key: 'environments', label: '项目环境', path: '/project-settings/environments' },
  { key: 'files', label: '文件管理', path: '/project-settings/files' },
  { key: 'logs', label: '操作日志', path: '/project-settings/logs' },
]

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const encoded = body === undefined ? undefined : JSON.stringify(body)
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: encoded,
  })
}

async function loadProjectResources() {
  const projectId = session.projectId, sequence = ++resourceSequence, view = viewSequence
  clearResources(); loadError.value = ''
  if (!projectId) { loading.value = false; return }
  loading.value = true
  try {
    const [nextMembers, parameterCollection, secretCollection, nextFiles, nextEnvironments] = await Promise.all([
      read<Member[]>(`/api/v1/projects/${projectId}/members`),
      read<ParameterCollection>(`/api/v1/projects/${projectId}/parameters`),
      read<SecretCollection>(`/api/v1/projects/${projectId}/secrets`),
      read<FileObject[]>(`/api/v1/projects/${projectId}/files`),
      read<ProjectEnvironment[]>(`/api/v1/projects/${projectId}/environments`),
    ])
    if (sequence !== resourceSequence || !currentView(projectId, view)) return
    members.value = nextMembers; parameters.value = parameterCollection.parameters; secrets.value = secretCollection.secrets
    files.value = nextFiles; projectEnvironments.value = nextEnvironments
    if (activeTab.value === 'logs') await loadProjectLogs()
  } catch (error) {
    if (sequence === resourceSequence && currentView(projectId, view)) loadError.value = error instanceof Error ? error.message : '加载项目设置失败'
  } finally {
    if (sequence === resourceSequence && currentView(projectId, view)) loading.value = false
  }
}

async function loadProjectLogs() {
  const projectId = session.projectId, sequence = ++logSequence, view = viewSequence
  auditEntries.value = []; loadError.value = ''
  if (!projectId) return
  const query = new URLSearchParams({ limit: '100' })
  if (auditAction.value) query.set('action', auditAction.value)
  try {
    const entries = await read<AuditEntry[]>(`/api/v1/projects/${projectId}/audit-logs?${query}`)
    if (sequence === logSequence && currentView(projectId, view)) auditEntries.value = entries
  } catch (error) {
    if (sequence === logSequence && currentView(projectId, view)) loadError.value = error instanceof Error ? error.message : '加载项目操作日志失败'
  }
}

async function load() {
  loading.value = true
  try {
    await session.loadAccessContext()
    if (disposed) return
    ready = true
    await loadProjectResources()
    const nextRoles = await read<Role[]>('/api/v1/access/roles')
    if (!disposed) roles.value = nextRoles
  } catch (error) {
    if (!disposed) { loadError.value = error instanceof Error ? error.message : '加载项目设置失败'; loading.value = false }
  }
}

async function rowMutation(key: string, url: string, method: 'POST' | 'DELETE', message: string) {
  const projectId = session.projectId, view = viewSequence
  if (!projectId || pending.has(key)) return
  pending.add(key)
  try {
    await mutate<void>(url, method)
    if (currentView(projectId, view)) { await loadProjectResources(); if (currentView(projectId, view)) Message.success(message) }
  } catch (error) {
    if (currentView(projectId, view)) Message.error(error instanceof Error ? error.message : '操作失败')
  } finally { pending.delete(key) }
}

function selectProject(projectId: string) {
  session.selectProject(projectId)
}

function createProject() {
  captureForm()
  editingProject.value = false
  editingProjectId.value = null
  projectForm.name = ''
  projectForm.code = ''
  projectForm.description = ''
  projectDialog.value = true
}

function editProject(project: Project) {
  captureForm()
  editingProject.value = true
  editingProjectId.value = project.id
  projectForm.name = project.name
  projectForm.code = project.code
  projectForm.description = project.description
  projectDialog.value = true
}

async function saveProject(): Promise<boolean> {
  if (saving.value || disposed) return false
  if (!projectForm.name.trim() || !projectForm.code.trim()) { Message.warning('请填写项目名称和项目标识'); return false }
  const projectId = formProjectId, view = viewSequence, form = formSequence
  const edited = editingProject.value, editingId = editingProjectId.value, input = { ...projectForm }
  saving.value = true
  try {
    const saved = edited && editingId
      ? await mutate<Project>(`/api/v1/projects/${editingId}`, 'PUT', input)
      : await mutate<Project>('/api/v1/projects', 'POST', input)
    if (!currentView(projectId, view) || form !== formSequence) return false
    projectDialog.value = false
    await session.loadAccessContext()
    if (disposed || view !== viewSequence) return false
    session.selectProject(saved.id)
    const selectedView = viewSequence, completedForm = formSequence
    await loadProjectResources()
    if (!currentView(saved.id, selectedView) || completedForm !== formSequence) return false
    Message.success(edited ? '项目信息已更新' : '项目已创建'); return true
  } catch (error) {
    if (currentView(projectId, view) && form === formSequence) Message.error(error instanceof Error ? error.message : '保存项目失败')
    return false
  } finally { saving.value = false }
}

async function deleteProject(project: Project) {
  const key = 'project:' + project.id, projectId = session.projectId, view = viewSequence
  if (pending.has(key)) return
  pending.add(key)
  try {
    await mutate<void>(`/api/v1/projects/${project.id}`, 'DELETE')
    if (!currentView(projectId, view)) return
    await session.loadAccessContext()
    if (!disposed) { await loadProjectResources(); Message.success('项目已删除，历史数据已保留') }
  } catch (error) {
    if (currentView(projectId, view)) Message.error(error instanceof Error ? error.message : '删除项目失败')
  } finally { pending.delete(key) }
}

function openMember(member?: Member) {
  if (!session.projectId) return
  captureForm()
  accountLookupError.value = ''; accountOptions.value = []
  const form = formSequence
  void read<AccountOption[]>('/api/v1/accounts/options').then(accounts => {
    if (!disposed && form === formSequence) accountOptions.value = accounts
  }).catch(error => { if (!disposed && form === formSequence) accountLookupError.value = error instanceof Error ? error.message : '账号选项读取失败，请直接输入账号' })
  memberForm.account = member?.account ?? ''
  memberForm.roleCode = member?.roleCode ?? 'TESTER'
  memberDialog.value = true
}

async function saveMember(): Promise<boolean> {
  const account = memberForm.account.trim(), projectId = formProjectId, view = viewSequence, form = formSequence
  if (!projectId || saving.value || disposed) return false
  if (!account) { Message.warning('请输入账号'); return false }
  const input = { account, roleCode: memberForm.roleCode }
  saving.value = true
  try {
    await mutate<Member>(`/api/v1/projects/${projectId}/members`, 'POST', input)
    if (!currentView(projectId, view) || form !== formSequence) return false
    memberDialog.value = false; await loadProjectResources()
    if (!currentView(projectId, view) || form !== formSequence) return false
    Message.success('项目成员已保存'); return true
  } catch (error) {
    if (currentView(projectId, view) && form === formSequence) Message.error(error instanceof Error ? error.message : '保存成员失败')
    return false
  } finally { saving.value = false }
}

async function removeMember(member: Member) {
  await rowMutation('member:' + member.membershipId, `/api/v1/projects/${session.projectId}/members/${member.membershipId}`, 'DELETE', '成员已移出项目')
}

function openEnvironment(kind: 'parameter' | 'secret', item?: Parameter) {
  if (loading.value || !session.projectId) return
  if (!projectEnvironments.value.length) {
    Message.warning('请先在系统设置中创建项目环境')
    void router.push('/system-settings/environments')
    return
  }
  captureForm()
  environmentKind.value = kind
  editingParameterId.value = item?.id ?? null
  environmentForm.environmentCode = item?.environmentCode ?? (
    environmentFilter.value
    || projectEnvironments.value.find((environment) => environment.defaultEnvironment)?.environmentCode
    || projectEnvironments.value.find((environment) => environment.enabled)?.environmentCode
    || ''
  )
  environmentForm.key = item?.key ?? ''
  environmentForm.value = item?.value ?? ''
  environmentDialog.value = true
}

async function saveEnvironmentItem(): Promise<boolean> {
  const projectId = formProjectId, view = viewSequence, form = formSequence, kind = environmentKind.value
  if (!projectId || saving.value || disposed) return false
  const input = { ...environmentForm }
  saving.value = true
  try {
    const endpoint = kind === 'secret' ? 'secrets' : 'parameters'
    await mutate<void>(`/api/v1/projects/${projectId}/${endpoint}`, 'POST', input)
    if (!currentView(projectId, view) || form !== formSequence) return false
    environmentDialog.value = false; environmentForm.value = ''; await loadProjectResources()
    if (!currentView(projectId, view) || form !== formSequence) return false
    Message.success(kind === 'secret' ? '环境密钥已加密保存' : '环境参数已保存'); return true
  } catch (error) {
    if (currentView(projectId, view) && form === formSequence) Message.error(error instanceof Error ? error.message : '保存环境配置失败')
    return false
  } finally { saving.value = false }
}

async function removeEnvironmentItem(kind: 'parameters' | 'secrets', id: string) {
  await rowMutation(kind + ':' + id, `/api/v1/projects/${session.projectId}/${kind}/${id}`, 'DELETE', '环境配置已删除')
}

function chooseFile() {
  fileInput.value?.click()
}

async function uploadFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const projectId = session.projectId, view = viewSequence
  if (!file || !projectId || uploading.value) return
  uploading.value = true
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    const form = new FormData()
    form.append('file', file)
    const response = await fetch(`/api/v1/projects/${projectId}/files`, {
      method: 'POST',
      credentials: 'include',
      headers: { [csrf.headerName]: csrf.token },
      body: form,
    })
    const body = await response.json().catch(() => ({})) as { message?: string }
    if (!response.ok) throw new Error(body.message ?? '上传失败')
    if (currentView(projectId, view)) { await loadProjectResources(); if (currentView(projectId, view)) Message.success('文件上传成功') }
  } catch (error) {
    if (currentView(projectId, view)) Message.error(error instanceof Error ? error.message : '上传文件失败')
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function downloadFile(file: FileObject) {
  window.open(`/api/v1/projects/${session.projectId}/files/${file.id}/download`, '_blank', 'noopener')
}

async function removeFile(file: FileObject) {
  await rowMutation('file:' + file.id, `/api/v1/projects/${session.projectId}/files/${file.id}`, 'DELETE', '文件已删除')
}

function roleLabel(roleCode: string) {
  return roles.value.find((role) => role.code === roleCode)?.name ?? roleCode
}

function formatDate(value?: string | null) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

function formatSize(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(2)} MB`
}

watch(() => route.query.required, (required) => {
  if (required && !session.projectId) Message.warning('请先创建或选择一个项目，再进入测试模块')
}, { immediate: true })

watch(() => session.projectId, () => {
  viewSequence++; resourceSequence++; logSequence++; closeForms(); clearResources()
  environmentFilter.value = ''; auditAction.value = ''; loadError.value = ''; loading.value = false
  if (ready) void loadProjectResources()
}, { flush: 'sync' })
watch(activeTab, () => {
  viewSequence++; resourceSequence++; logSequence++; closeForms(); clearResources()
  loadError.value = ''; loading.value = false
  if (ready) void loadProjectResources()
}, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; resourceSequence++; logSequence++; closeForms() })
onMounted(load)
</script>

<template>
  <div class="project-settings">
    <aside class="project-rail">
      <div class="rail-heading">
        <div><span>当前项目</span><strong>{{ currentProject?.name ?? '尚未选择' }}</strong></div>
        <a-button type="primary" shape="circle" size="small" aria-label="新建项目" @click="createProject"><icon-plus /></a-button>
      </div>
      <a-select
        :model-value="session.projectId"
        class="project-switcher"
        placeholder="选择项目"
        allow-search
        @change="selectProject(String($event))"
      >
        <a-option v-for="project in availableProjects" :key="project.id" :value="project.id">
          {{ project.name }}
        </a-option>
      </a-select>
      <div class="project-list">
        <div
          v-for="project in availableProjects"
          :key="project.id"
          class="project-item"
          :class="{ active: project.id === session.projectId }"
          role="button"
          tabindex="0"
          @click="selectProject(project.id)"
          @keydown.enter="selectProject(project.id)"
          @keydown.space.prevent="selectProject(project.id)"
        >
          <span class="project-avatar">{{ project.name.slice(0, 1) }}</span>
          <span class="project-item-copy"><strong>{{ project.name }}</strong><small>{{ project.code }}</small></span>
          <span class="project-item-status"><icon-check v-if="project.id === session.projectId" /></span>
          <span class="project-item-actions" @click.stop @keydown.stop>
            <a-button type="text" size="mini" :aria-label="`编辑项目 ${project.name}`" @click="editProject(project)">
              <icon-edit />
            </a-button>
            <a-popconfirm
              :content="`确定删除“${project.name}”吗？项目将停止使用，但历史测试数据会保留。`"
              type="warning"
              @ok="deleteProject(project)"
            >
              <a-button type="text" size="mini" status="danger" :loading="pending.has('project:' + project.id)" :aria-label="`删除项目 ${project.name}`">
                <icon-delete />
              </a-button>
            </a-popconfirm>
          </span>
        </div>
      </div>
      <a-empty v-if="availableProjects.length === 0" description="暂无项目" />
    </aside>

    <main class="settings-main">
      <header class="settings-topbar">
        <nav aria-label="项目设置功能">
          <router-link v-for="tab in tabItems" :key="tab.key" :to="tab.path" :class="{ active: activeTab === tab.key }">
            {{ tab.label }}
          </router-link>
        </nav>
        <div class="project-context">
          <span class="status-dot" /> {{ currentProject?.name ?? '未选择项目' }}
        </div>
      </header>

      <a-spin :loading="loading" class="settings-content">
        <a-alert v-if="loadError" type="error">{{ loadError }} <a-button type="text" @click="load">重试</a-button></a-alert>
        <a-result v-if="!currentProject" status="info" title="请先创建项目" subtitle="所有测试资产都需要归属于项目。创建项目后即可配置成员、环境与文件。">
          <template #extra><a-button type="primary" @click="createProject">新建项目</a-button></template>
        </a-result>

        <template v-else-if="activeTab === 'info'">
          <section class="info-hero panel">
            <div class="project-emblem">{{ currentProject.name.slice(0, 1) }}</div>
            <div class="info-copy">
              <span class="eyebrow">PROJECT PROFILE</span>
              <div class="project-title"><h1>{{ currentProject.name }}</h1><a-button type="text" @click="editProject(currentProject)"><icon-edit /> 编辑</a-button></div>
              <p>{{ currentProject.description || '暂未填写项目描述，可通过编辑补充项目目标、范围与说明。' }}</p>
              <div class="project-meta">
                <div><span>项目标识</span><strong>{{ currentProject.code }}</strong></div>
                <div><span>管理员</span><strong>{{ currentProject.createdByName || session.profile?.displayName || '系统管理员' }}</strong></div>
                <div><span>项目成员</span><strong>{{ members.length }} 人</strong></div>
                <div><span>创建时间</span><strong>{{ formatDate(currentProject.createdAt) }}</strong></div>
              </div>
            </div>
          </section>
          <section class="feature-grid">
            <button class="feature-card panel" @click="router.push('/project-settings/members')">
              <span class="feature-icon purple"><icon-user-group /></span>
              <span><strong>项目成员</strong><small>添加成员并分配项目角色与权限</small></span>
              <b>{{ members.length }}</b><icon-right />
            </button>
            <button class="feature-card panel" @click="router.push('/project-settings/environments')">
              <span class="feature-icon blue"><icon-computer /></span>
              <span><strong>项目环境</strong><small>集中维护环境参数与敏感密钥</small></span>
              <b>{{ environmentNames.length }}</b><icon-right />
            </button>
            <button class="feature-card panel" @click="router.push('/project-settings/files')">
              <span class="feature-icon green"><icon-folder /></span>
              <span><strong>文件管理</strong><small>管理项目附件、数据文件与测试资源</small></span>
              <b>{{ files.length }}</b><icon-right />
            </button>
            <button class="feature-card panel" @click="router.push('/project-settings/logs')">
              <span class="feature-icon orange"><icon-history /></span>
              <span><strong>操作日志</strong><small>追踪项目配置与关键资源变更</small></span>
              <icon-right />
            </button>
          </section>
        </template>

        <section v-else-if="activeTab === 'members'" class="content-panel panel">
          <div class="content-heading">
            <div><span class="eyebrow">PROJECT MEMBERS</span><h2>项目成员</h2><p>添加项目协作者，并通过角色控制其管理、编辑或只读权限。</p></div>
            <a-button type="primary" @click="openMember()"><icon-plus /> 添加成员</a-button>
          </div>
          <a-table :data="members" :pagination="false" row-key="membershipId">
            <template #columns>
              <a-table-column title="成员">
                <template #cell="{ record }"><div class="member-cell"><a-avatar>{{ record.displayName.slice(0, 1) }}</a-avatar><span><strong>{{ record.displayName }}</strong><small>{{ record.account }} · {{ record.email }}</small></span></div></template>
              </a-table-column>
              <a-table-column title="项目角色"><template #cell="{ record }"><a-tag color="arcoblue">{{ roleLabel(record.roleCode) }}</a-tag></template></a-table-column>
              <a-table-column title="加入时间"><template #cell="{ record }">{{ formatDate(record.createdAt) }}</template></a-table-column>
              <a-table-column title="操作" :width="180">
                <template #cell="{ record }"><a-space><a-button type="text" @click="openMember(record)">编辑角色</a-button><a-popconfirm content="确定将该成员移出项目吗？" @ok="removeMember(record)"><a-button type="text" status="danger" :loading="pending.has('member:' + record.membershipId)">移除</a-button></a-popconfirm></a-space></template>
              </a-table-column>
            </template>
          </a-table>
          <a-empty v-if="members.length === 0" description="暂无项目成员" />
        </section>

        <section v-else-if="activeTab === 'environments'" class="content-panel panel">
          <div class="content-heading">
            <div><span class="eyebrow">PROJECT ENVIRONMENTS</span><h2>项目环境</h2><p>按环境隔离普通参数与敏感密钥，供接口调试、自动化和性能测试统一引用。</p></div>
            <a-space wrap><a-button v-if="desktop" @click="showSettingsSync">项目配置同步</a-button><a-button @click="router.push('/system-settings/environments')"><icon-settings /> 管理环境</a-button><a-button :disabled="loading" @click="openEnvironment('secret')"><icon-lock /> 新建密钥</a-button><a-button type="primary" :disabled="loading" @click="openEnvironment('parameter')"><icon-plus /> 新建参数</a-button></a-space>
          </div>
          <div class="environment-toolbar">
            <a-select v-model="environmentFilter" allow-clear placeholder="全部环境" :style="{ width: '200px' }">
              <a-option v-for="environment in projectEnvironments" :key="environment.id" :value="environment.environmentCode">{{ environment.name }} · {{ environment.environmentCode }}</a-option>
            </a-select>
            <a-radio-group v-model="environmentView" type="button">
              <a-radio value="parameters">环境参数（{{ visibleParameters.length }}）</a-radio>
              <a-radio value="secrets">敏感密钥（{{ visibleSecrets.length }}）</a-radio>
            </a-radio-group>
          </div>
          <a-table v-if="environmentView === 'parameters'" :data="visibleParameters" :pagination="false" row-key="id">
            <template #columns>
              <a-table-column title="环境"><template #cell="{ record }"><a-tag color="arcoblue">{{ record.environmentCode }}</a-tag></template></a-table-column>
              <a-table-column title="参数名称"><template #cell="{ record }"><strong>{{ record.key }}</strong></template></a-table-column>
              <a-table-column title="参数值"><template #cell="{ record }"><code class="value-code">{{ record.value }}</code></template></a-table-column>
              <a-table-column title="更新时间"><template #cell="{ record }">{{ formatDate(record.updatedAt) }}</template></a-table-column>
              <a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="openEnvironment('parameter', record)">编辑</a-button><a-popconfirm content="确定删除该参数吗？" @ok="removeEnvironmentItem('parameters', record.id)"><a-button type="text" status="danger" :loading="pending.has('parameters:' + record.id)">删除</a-button></a-popconfirm></a-space></template></a-table-column>
            </template>
          </a-table>
          <a-table v-else :data="visibleSecrets" :pagination="false" row-key="id">
            <template #columns>
              <a-table-column title="环境"><template #cell="{ record }"><a-tag color="purple">{{ record.environmentCode }}</a-tag></template></a-table-column>
              <a-table-column title="密钥名称"><template #cell="{ record }"><strong>{{ record.key }}</strong><small class="masked">•••••••• · 已加密</small></template></a-table-column>
              <a-table-column title="更新时间"><template #cell="{ record }">{{ formatDate(record.updatedAt) }}</template></a-table-column>
              <a-table-column title="操作"><template #cell="{ record }"><a-popconfirm content="确定删除该密钥吗？" @ok="removeEnvironmentItem('secrets', record.id)"><a-button type="text" status="danger" :loading="pending.has('secrets:' + record.id)">删除</a-button></a-popconfirm></template></a-table-column>
            </template>
          </a-table>
        </section>

        <section v-else-if="activeTab === 'files'" class="content-panel panel">
          <input ref="fileInput" class="file-input" type="file" @change="uploadFile">
          <div class="content-heading">
            <div><span class="eyebrow">PROJECT FILES</span><h2>文件管理</h2><p>统一管理测试数据、接口定义、证书和项目附件，所有文件都与当前项目隔离。</p></div>
            <a-button type="primary" :loading="uploading" @click="chooseFile"><icon-upload /> 上传文件</a-button>
          </div>
          <div class="file-summary">
            <div><span>文件数量</span><strong>{{ files.length }}</strong></div>
            <div><span>占用空间</span><strong>{{ formatSize(totalFileSize) }}</strong></div>
            <div><span>文件归属</span><strong>{{ currentProject?.name || '当前项目' }}</strong></div>
          </div>
          <a-table :data="files" :pagination="false" row-key="id">
            <template #columns>
              <a-table-column title="文件名称"><template #cell="{ record }"><div class="file-cell"><span><icon-file /></span><div><strong>{{ record.originalName }}</strong><small>{{ record.contentType }}</small></div></div></template></a-table-column>
              <a-table-column title="大小"><template #cell="{ record }">{{ formatSize(record.sizeBytes) }}</template></a-table-column>
              <a-table-column title="上传时间"><template #cell="{ record }">{{ formatDate(record.createdAt) }}</template></a-table-column>
              <a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="downloadFile(record)">下载</a-button><a-popconfirm content="确定删除该文件吗？" @ok="removeFile(record)"><a-button type="text" status="danger" :loading="pending.has('file:' + record.id)">删除</a-button></a-popconfirm></a-space></template></a-table-column>
            </template>
          </a-table>
          <a-empty v-if="files.length === 0" description="当前项目还没有文件" />
        </section>

        <section v-else class="content-panel panel">
          <div class="content-heading">
            <div><span class="eyebrow">PROJECT ACTIVITY</span><h2>操作日志</h2><p>查看当前项目的配置调整、成员变更和测试资源操作记录。</p></div>
            <a-button @click="loadProjectLogs"><icon-refresh /> 刷新</a-button>
          </div>
          <div class="audit-toolbar">
            <a-select v-model="auditAction" allow-clear placeholder="全部操作" :style="{ width: '260px' }" @change="loadProjectLogs">
              <a-option v-for="action in auditActions" :key="action" :value="action">{{ action }}</a-option>
            </a-select>
            <span>最近 100 条项目操作记录</span>
          </div>
          <a-table :data="auditEntries" :scroll="{ x: 1050 }" :pagination="{ pageSize: 10 }" row-key="id">
            <template #columns>
              <a-table-column title="时间" :width="185"><template #cell="{ record }">{{ formatDate(record.createdAt) }}</template></a-table-column>
              <a-table-column title="操作" :width="275"><template #cell="{ record }"><a-tag color="arcoblue">{{ record.action }}</a-tag></template></a-table-column>
              <a-table-column title="资源" :width="210"><template #cell="{ record }"><strong>{{ record.resourceType }}</strong><small class="audit-resource">{{ record.resourceId ?? '—' }}</small></template></a-table-column>
              <a-table-column title="结果" :width="100"><template #cell="{ record }"><a-tag :color="record.outcome === 'SUCCESS' ? 'green' : 'red'">{{ record.outcome }}</a-tag></template></a-table-column>
              <a-table-column title="请求 ID" :width="220"><template #cell="{ record }"><code class="request-id">{{ record.requestId }}</code></template></a-table-column>
            </template>
          </a-table>
          <a-empty v-if="auditEntries.length === 0" description="当前项目暂无操作日志" />
        </section>
      </a-spin>
    </main>

    <a-modal v-model:visible="projectDialog" :title="editingProject ? '编辑项目' : '新增项目'" :ok-loading="saving" width="600px" :on-before-ok="saveProject">
      <a-form :model="projectForm" layout="vertical">
        <a-form-item label="项目名称" required><a-input v-model="projectForm.name" :max-length="160" show-word-limit placeholder="例如：交易中台 V4" /></a-form-item>
        <a-form-item label="项目标识" required extra="2–64 位小写字母、数字、下划线或连字符，创建测试数据后仍建议保持稳定。"><a-input v-model="projectForm.code" placeholder="trade-v4" /></a-form-item>
        <a-form-item label="项目描述"><a-textarea v-model="projectForm.description" :max-length="2000" show-word-limit :auto-size="{ minRows: 4, maxRows: 8 }" placeholder="说明项目目标、测试范围或协作约定" /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:visible="memberDialog" title="添加成员" :ok-loading="saving" width="540px" :on-before-ok="saveMember">
      <a-form :model="memberForm" layout="vertical">
        <a-form-item label="账号" required>
          <a-select
            v-model="memberForm.account"
            allow-search
            allow-create
            :max-length="160"
            placeholder="选择已有账号或直接输入新账号"
          >
            <a-option v-for="account in accountOptions" :key="account.id" :value="account.account">
              <div class="account-option">
                <strong>{{ account.account }}</strong>
                <small>{{ account.displayName }} · {{ account.email }}</small>
              </div>
            </a-option>
          </a-select>
          <template #extra><span v-if="accountLookupError">{{ accountLookupError }}；可直接输入账号。 </span>可下拉选择已有账号；输入新账号后保存，将同步创建系统账号，默认密码为 admin。</template>
        </a-form-item>
        <a-form-item label="项目角色" required><a-select v-model="memberForm.roleCode"><a-option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }} · {{ role.description }}</a-option></a-select></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:visible="environmentDialog" :title="environmentKind === 'secret' ? '新建环境密钥' : (editingParameterId ? '编辑环境参数' : '新建环境参数')" :ok-loading="saving" width="580px" :on-before-ok="saveEnvironmentItem">
      <a-alert v-if="environmentKind === 'secret'" type="warning">密钥值仅在传输时可见，保存后会加密且无法再次回显。</a-alert>
      <a-form :model="environmentForm" layout="vertical">
        <a-form-item label="所属环境" required><a-select v-model="environmentForm.environmentCode" :disabled="!!editingParameterId" placeholder="请选择项目环境"><a-option v-for="environment in projectEnvironments" :key="environment.id" :value="environment.environmentCode" :disabled="!environment.enabled">{{ environment.name }} · {{ environment.environmentCode }}</a-option></a-select></a-form-item>
        <a-form-item :label="environmentKind === 'secret' ? '密钥名称' : '参数名称'" required><a-input v-model="environmentForm.key" :disabled="!!editingParameterId" placeholder="API_BASE_URL" /></a-form-item>
        <a-form-item :label="environmentKind === 'secret' ? '密钥值' : '参数值'" required>
          <a-input-password v-if="environmentKind === 'secret'" v-model="environmentForm.value" placeholder="输入需要加密保存的值" />
          <a-textarea v-else v-model="environmentForm.value" :auto-size="{ minRows: 3, maxRows: 7 }" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.project-settings{display:grid;grid-template-columns:244px minmax(0,1fr);min-height:100%;background:#f6f7fa}.project-rail{display:flex;flex-direction:column;min-height:calc(100vh - 68px);padding:18px 14px;border-right:1px solid var(--line);background:#fff}.rail-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:0 4px 14px}.rail-heading>div{display:grid;gap:4px;min-width:0}.rail-heading span{color:var(--muted);font-size:11px}.rail-heading strong{overflow:hidden;font-size:14px;text-overflow:ellipsis;white-space:nowrap}.project-switcher{width:100%;margin-bottom:14px}.project-list{display:grid;gap:5px;max-height:calc(100vh - 220px);overflow:auto}.project-item{position:relative;display:grid;grid-template-columns:34px minmax(0,1fr) 58px;align-items:center;gap:9px;width:100%;padding:10px;border:0;border-radius:8px;color:var(--text);text-align:left;background:transparent;cursor:pointer;outline:none}.project-item:hover{background:#f4f6fa}.project-item:focus-visible{box-shadow:0 0 0 2px rgba(49,103,246,.24)}.project-item.active{color:var(--primary);background:#eef3ff}.project-avatar{display:grid;place-items:center;width:34px;height:34px;border-radius:9px;color:#fff;font-weight:700;background:linear-gradient(145deg,#6f8fff,#315fdc)}.project-item-copy{display:grid;gap:3px;min-width:0}.project-item strong,.project-item small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-item strong{font-size:12px}.project-item small{color:var(--muted);font-size:10px}.project-item-status,.project-item-actions{grid-column:3;grid-row:1;justify-self:end;width:58px}.project-item-status{display:grid;place-items:center}.project-item-actions{display:flex;justify-content:flex-end;opacity:0;pointer-events:none}.project-item:hover .project-item-status,.project-item:focus-within .project-item-status{display:none}.project-item:hover .project-item-actions,.project-item:focus-within .project-item-actions{opacity:1;pointer-events:auto}.project-item-actions :deep(.arco-btn){width:26px;height:26px;padding:0}.settings-main{min-width:0}.settings-topbar{display:flex;align-items:center;justify-content:space-between;height:54px;padding:0 26px;border-bottom:1px solid var(--line);background:#fff}.settings-topbar nav{display:flex;height:100%;gap:26px}.settings-topbar a{position:relative;display:flex;align-items:center;color:#48546a;font-size:13px;text-decoration:none}.settings-topbar a.active{color:var(--primary);font-weight:600}.settings-topbar a.active::after{position:absolute;right:0;bottom:0;left:0;height:2px;background:var(--primary);content:''}.project-context{display:flex;align-items:center;gap:7px;color:var(--muted);font-size:11px}.status-dot{width:7px;height:7px;border-radius:50%;background:#24b47e;box-shadow:0 0 0 4px #e2f7ef}.settings-content{display:block;min-height:calc(100vh - 122px);padding:28px 30px 48px}.info-hero{display:grid;grid-template-columns:92px minmax(0,1fr);gap:24px;padding:32px}.project-emblem{display:grid;place-items:center;width:84px;height:84px;border-radius:20px;color:#fff;font-size:34px;font-weight:800;background:linear-gradient(145deg,#6f8fff,#315fdc);box-shadow:0 13px 25px rgba(49,103,246,.2)}.project-title{display:flex;align-items:center;gap:8px}.project-title h1{margin:6px 0 4px;font-size:25px}.info-copy>p{max-width:850px;margin:0;color:var(--muted);font-size:13px;line-height:1.75}.project-meta{display:grid;grid-template-columns:repeat(4,minmax(120px,1fr));gap:18px;margin-top:27px;padding-top:22px;border-top:1px solid var(--line)}.project-meta>div{display:grid;gap:6px}.project-meta span{color:var(--muted);font-size:11px}.project-meta strong{font-size:13px}.feature-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-top:16px}.feature-card{display:grid;grid-template-columns:46px minmax(0,1fr) auto 18px;align-items:center;gap:14px;width:100%;padding:22px;text-align:left;cursor:pointer}.feature-card:hover{border-color:#bdcaff;box-shadow:0 9px 24px rgba(49,103,246,.08)}.feature-icon{display:grid;place-items:center;width:46px;height:46px;border-radius:12px;font-size:22px}.feature-icon.purple{color:#7453d8;background:#f0ecff}.feature-icon.blue{color:#3167f6;background:#eaf0ff}.feature-icon.green{color:#1eaa7b;background:#e4f7f0}.feature-icon.orange{color:#dc8131;background:#fff0e3}.feature-card>span:nth-child(2){display:grid;gap:5px}.feature-card strong{font-size:14px}.feature-card small{color:var(--muted);font-size:11px}.feature-card b{color:var(--primary);font-size:20px}.content-panel{padding:0;overflow:hidden}.content-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px;padding:24px 26px;border-bottom:1px solid var(--line)}.content-heading h2{margin:5px 0;font-size:21px}.content-heading p{margin:0;color:var(--muted);font-size:12px}.member-cell,.file-cell{display:flex;align-items:center;gap:10px}.member-cell>span,.file-cell>div{display:grid;gap:3px}.member-cell small,.file-cell small,.masked{display:block;color:var(--muted);font-size:10px}.account-option{display:grid;gap:2px;padding:2px 0}.account-option strong{font-size:13px}.account-option small{color:var(--color-text-3);font-size:11px}.environment-toolbar,.audit-toolbar{display:flex;align-items:center;justify-content:space-between;padding:14px 26px;background:#fafbfe;border-bottom:1px solid var(--line)}.audit-toolbar>span{color:var(--muted);font-size:11px}.audit-resource{display:block;margin-top:3px;color:var(--muted);font-size:10px}.request-id{color:var(--muted);font-size:11px}.value-code{display:inline-block;max-width:380px;padding:4px 7px;overflow:hidden;color:#38445c;text-overflow:ellipsis;white-space:nowrap;border-radius:5px;background:#f1f3f7}.file-input{display:none}.file-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:1px;background:var(--line);border-bottom:1px solid var(--line)}.file-summary>div{display:grid;gap:5px;padding:17px 26px;background:#fafbfe}.file-summary span{color:var(--muted);font-size:10px}.file-summary strong{font-size:15px}.file-cell>span{display:grid;place-items:center;width:34px;height:34px;color:var(--primary);border-radius:8px;background:#edf2ff}@media(max-width:1050px){.project-settings{grid-template-columns:220px minmax(0,1fr)}.project-meta,.feature-grid{grid-template-columns:repeat(2,1fr)}}
</style>
