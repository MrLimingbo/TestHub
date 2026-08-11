<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface ProjectEnvironment {
  id: string
  organizationId: string
  projectId: string
  name: string
  environmentCode: string
  baseUrl: string
  description: string
  enabled: boolean
  defaultEnvironment: boolean
  variableCount: number
  secretCount: number
  version: number
  createdAt: string
  updatedAt: string
}
interface ProjectParameter { id: string; environmentCode: string; key: string; value: string; updatedAt: string }
interface EnvironmentSecret { id: string; environmentCode: string; key: string; updatedAt: string }
interface ParameterCollection { environments: string[]; parameters: ProjectParameter[] }
interface SecretCollection { environments: string[]; secrets: EnvironmentSecret[] }

const session = useSessionStore()
const loading = ref(false)
const saving = ref(false)
const environmentVisible = ref(false)
const variableVisible = ref(false)
const detailVisible = ref(false)
const activeView = ref<'environments' | 'variables'>('environments')
const detailKind = ref<'parameters' | 'secrets'>('parameters')
const variableKind = ref<'parameter' | 'secret'>('parameter')
const editingEnvironment = ref<ProjectEnvironment | null>(null)
const selectedEnvironment = ref<ProjectEnvironment | null>(null)
const editingVariable = ref<ProjectParameter | null>(null)
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const environments = ref<ProjectEnvironment[]>([])
const parameters = ref<ProjectParameter[]>([])
const secrets = ref<EnvironmentSecret[]>([])
const environmentForm = reactive({
  name: '',
  environmentCode: '',
  baseUrl: '',
  description: '',
  enabled: true,
  defaultEnvironment: false,
})
const variableForm = reactive({ key: '', value: '' })

const currentProject = computed(() => session.currentProject)
const filteredEnvironments = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return environments.value
  return environments.value.filter((item) => [item.name, item.environmentCode, item.baseUrl, item.description]
    .some((value) => value.toLowerCase().includes(query)))
})
const pagedEnvironments = computed(() => filteredEnvironments.value.slice(
  (page.value - 1) * pageSize.value,
  page.value * pageSize.value,
))
const activeParameters = computed(() => parameters.value.filter((item) =>
  item.environmentCode === selectedEnvironment.value?.environmentCode && item.key !== 'BASE_URL'))
const activeSecrets = computed(() => secrets.value.filter((item) =>
  item.environmentCode === selectedEnvironment.value?.environmentCode))
const allVariableRows = computed(() => environments.value.flatMap((environment) => [
  ...parameters.value.filter((item) => item.environmentCode === environment.environmentCode && item.key !== 'BASE_URL')
    .map((item) => ({ ...item, environmentName: environment.name, kind: '参数' as const })),
  ...secrets.value.filter((item) => item.environmentCode === environment.environmentCode)
    .map((item) => ({ ...item, value: '', environmentName: environment.name, kind: '密钥' as const })),
]))

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
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

async function load() {
  if (!session.projectId) {
    environments.value = []
    parameters.value = []
    secrets.value = []
    return
  }
  loading.value = true
  try {
    const projectId = session.projectId
    const [environmentRows, parameterData, secretData] = await Promise.all([
      read<ProjectEnvironment[]>(`/api/v1/projects/${projectId}/environments`),
      read<ParameterCollection>(`/api/v1/projects/${projectId}/parameters`),
      read<SecretCollection>(`/api/v1/projects/${projectId}/secrets`),
    ])
    environments.value = environmentRows
    parameters.value = parameterData.parameters
    secrets.value = secretData.secrets
    if (selectedEnvironment.value) {
      selectedEnvironment.value = environmentRows.find((item) => item.id === selectedEnvironment.value?.id) ?? null
      if (!selectedEnvironment.value) detailVisible.value = false
    }
    const maxPage = Math.max(1, Math.ceil(filteredEnvironments.value.length / pageSize.value))
    if (page.value > maxPage) page.value = maxPage
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载项目环境失败')
  } finally {
    loading.value = false
  }
}

function selectProject(value: unknown) {
  session.selectProject(String(value ?? ''))
}

function openCreate() {
  editingEnvironment.value = null
  Object.assign(environmentForm, {
    name: '', environmentCode: '', baseUrl: '', description: '', enabled: true,
    defaultEnvironment: environments.value.length === 0,
  })
  environmentVisible.value = true
}

function openEdit(environment: ProjectEnvironment) {
  editingEnvironment.value = environment
  Object.assign(environmentForm, {
    name: environment.name,
    environmentCode: environment.environmentCode,
    baseUrl: environment.baseUrl,
    description: environment.description,
    enabled: environment.enabled,
    defaultEnvironment: environment.defaultEnvironment,
  })
  environmentVisible.value = true
}

async function saveEnvironment(): Promise<boolean> {
  if (!session.projectId || !environmentForm.name.trim() || !environmentForm.environmentCode.trim()) {
    Message.warning('请填写环境名称和环境标识')
    return false
  }
  saving.value = true
  try {
    const editing = editingEnvironment.value
    const url = editing
      ? `/api/v1/projects/${session.projectId}/environments/${editing.id}`
      : `/api/v1/projects/${session.projectId}/environments`
    const body = editing
      ? {
          name: environmentForm.name,
          baseUrl: environmentForm.baseUrl,
          description: environmentForm.description,
          enabled: environmentForm.enabled,
          expectedVersion: editing.version,
        }
      : environmentForm
    await mutate<ProjectEnvironment>(url, editing ? 'PUT' : 'POST', body)
    environmentVisible.value = false
    await load()
    Message.success(editing ? '环境已更新' : '环境已创建')
    return true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '保存环境失败')
    return false
  } finally {
    saving.value = false
  }
}

async function makeDefault(environment: ProjectEnvironment) {
  if (!session.projectId || environment.defaultEnvironment) return
  try {
    await mutate<ProjectEnvironment>(`/api/v1/projects/${session.projectId}/environments/${environment.id}/default`, 'POST')
    await load()
    Message.success(`“${environment.name}”已设为默认环境`)
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '设置默认环境失败')
  }
}

async function removeEnvironment(environment: ProjectEnvironment) {
  if (!session.projectId) return
  try {
    await mutate<void>(`/api/v1/projects/${session.projectId}/environments/${environment.id}`, 'DELETE')
    await load()
    Message.success('环境已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除环境失败')
  }
}

function manageVariables(environment: ProjectEnvironment, kind: 'parameters' | 'secrets' = 'parameters') {
  selectedEnvironment.value = environment
  detailKind.value = kind
  detailVisible.value = true
}

function openVariable(kind: 'parameter' | 'secret', variable?: ProjectParameter) {
  variableKind.value = kind
  editingVariable.value = variable ?? null
  variableForm.key = variable?.key ?? ''
  variableForm.value = variable?.value ?? ''
  variableVisible.value = true
}

async function saveVariable(): Promise<boolean> {
  if (!session.projectId || !selectedEnvironment.value || !variableForm.key.trim() || !variableForm.value) {
    Message.warning('请填写名称和值')
    return false
  }
  saving.value = true
  try {
    const resource = variableKind.value === 'secret' ? 'secrets' : 'parameters'
    await mutate<void>(`/api/v1/projects/${session.projectId}/${resource}`, 'POST', {
      environmentCode: selectedEnvironment.value.environmentCode,
      key: variableForm.key,
      value: variableForm.value,
    })
    variableVisible.value = false
    await load()
    Message.success(variableKind.value === 'secret' ? '环境密钥已加密保存' : '环境变量已保存')
    return true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '保存环境配置失败')
    return false
  } finally {
    saving.value = false
  }
}

async function removeVariable(resource: 'parameters' | 'secrets', id: string) {
  if (!session.projectId) return
  try {
    await mutate<void>(`/api/v1/projects/${session.projectId}/${resource}/${id}`, 'DELETE')
    await load()
    Message.success(resource === 'secrets' ? '环境密钥已删除' : '环境变量已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除环境配置失败')
  }
}

function search() {
  page.value = 1
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

onMounted(load)
</script>

<template>
  <div class="environment-page">
    <header class="environment-header">
      <div>
        <span class="eyebrow">PROJECT ENVIRONMENTS</span>
        <h1>环境管理</h1>
        <p>为每个项目维护独立的服务地址、变量与加密密钥，并用于接口测试和接口自动化执行。</p>
      </div>
      <a-button type="primary" @click="openCreate"><icon-plus /> 新建环境</a-button>
    </header>

    <section class="environment-console">
      <a-tabs v-model:active-key="activeView" class="environment-tabs">
        <a-tab-pane key="environments" title="环境列表" />
        <a-tab-pane key="variables" title="变量视图" />
      </a-tabs>

      <div class="environment-toolbar">
        <div class="project-filter">
          <label>项目</label>
          <a-select :model-value="session.projectId" :style="{ width: '230px' }" placeholder="请选择项目" @change="selectProject">
            <a-option v-for="project in session.projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.code }}</a-option>
          </a-select>
          <a-tag v-if="currentProject" color="arcoblue">项目隔离</a-tag>
        </div>
        <a-input-search
          v-model="keyword"
          :style="{ width: '310px' }"
          allow-clear
          placeholder="根据环境名称、标识或域名搜索"
          @search="search"
          @clear="search"
          @press-enter="search"
        />
      </div>

      <a-spin :loading="loading" class="environment-spin">
        <template v-if="activeView === 'environments'">
          <a-table :data="pagedEnvironments" :pagination="false" :scroll="{ x: 1130 }" row-key="id">
            <template #columns>
              <a-table-column title="环境名称" :width="220">
                <template #cell="{ record }">
                  <div class="environment-name">
                    <span class="environment-mark" :class="{ disabled: !record.enabled }"><icon-cloud /></span>
                    <div><strong>{{ record.name }}</strong><small>{{ record.environmentCode }}</small></div>
                    <a-tag v-if="record.defaultEnvironment" size="small" color="purple">默认</a-tag>
                  </div>
                </template>
              </a-table-column>
              <a-table-column title="项目" :width="180"><template #cell>{{ currentProject?.name ?? '—' }}</template></a-table-column>
              <a-table-column title="环境域名" :width="300">
                <template #cell="{ record }"><code class="domain-code">{{ record.baseUrl || '未配置基础地址' }}</code></template>
              </a-table-column>
              <a-table-column title="变量 / 密钥" :width="150">
                <template #cell="{ record }"><button class="count-link" @click="manageVariables(record)">{{ record.variableCount }} 个变量 · {{ record.secretCount }} 个密钥</button></template>
              </a-table-column>
              <a-table-column title="状态" :width="90"><template #cell="{ record }"><a-tag :color="record.enabled ? 'green' : 'gray'">{{ record.enabled ? '启用' : '停用' }}</a-tag></template></a-table-column>
              <a-table-column title="更新时间" :width="175"><template #cell="{ record }">{{ formatDate(record.updatedAt) }}</template></a-table-column>
              <a-table-column title="操作" :width="250" fixed="right">
                <template #cell="{ record }">
                  <a-space :size="4">
                    <a-button type="text" size="small" @click="manageVariables(record)">管理变量</a-button>
                    <a-button type="text" size="small" @click="openEdit(record)">编辑</a-button>
                    <a-dropdown trigger="click">
                      <a-button type="text" size="small"><icon-more /></a-button>
                      <template #content>
                        <a-doption :disabled="record.defaultEnvironment" @click="makeDefault(record)">设为默认环境</a-doption>
                        <a-doption class="danger-option" :disabled="record.defaultEnvironment" @click="removeEnvironment(record)">删除环境</a-doption>
                      </template>
                    </a-dropdown>
                  </a-space>
                </template>
              </a-table-column>
            </template>
          </a-table>
          <a-empty v-if="!loading && !pagedEnvironments.length" description="当前项目暂无符合条件的环境">
            <template #extra><a-button type="primary" @click="openCreate">创建第一个环境</a-button></template>
          </a-empty>
        </template>
        <template v-else>
          <a-table :data="allVariableRows" :pagination="{ pageSize: 12 }" row-key="id">
            <template #columns>
              <a-table-column title="环境"><template #cell="{ record }"><strong>{{ record.environmentName }}</strong><small class="table-secondary">{{ record.environmentCode }}</small></template></a-table-column>
              <a-table-column title="名称"><template #cell="{ record }"><code>{{ record.key }}</code></template></a-table-column>
              <a-table-column title="类型" :width="100"><template #cell="{ record }"><a-tag :color="record.kind === '密钥' ? 'orange' : 'arcoblue'">{{ record.kind }}</a-tag></template></a-table-column>
              <a-table-column title="值"><template #cell="{ record }"><span :class="{ masked: record.kind === '密钥' }">{{ record.kind === '密钥' ? '••••••••（已加密）' : record.value }}</span></template></a-table-column>
              <a-table-column title="更新时间" :width="180"><template #cell="{ record }">{{ formatDate(record.updatedAt) }}</template></a-table-column>
            </template>
          </a-table>
          <a-empty v-if="!loading && !allVariableRows.length" description="当前项目尚未配置环境变量或密钥" />
        </template>
      </a-spin>

      <footer v-if="activeView === 'environments'" class="environment-footer">
        <span>共 {{ filteredEnvironments.length }} 条 · 环境数据严格按项目隔离</span>
        <a-pagination v-model:current="page" v-model:page-size="pageSize" :total="filteredEnvironments.length" show-page-size :page-size-options="[10, 20, 50]" />
      </footer>
    </section>

    <a-modal
      v-model:visible="environmentVisible"
      :title="editingEnvironment ? '编辑环境' : '新建项目环境'"
      width="660px"
      :ok-loading="saving"
      :on-before-ok="saveEnvironment"
    >
      <a-alert type="info" class="form-alert">保存后，接口测试与接口自动化可直接选择此环境；基础地址会以 <code>BASE_URL</code> 变量参与服务端解析。</a-alert>
      <a-form :model="environmentForm" layout="vertical">
        <div class="form-grid">
          <a-form-item label="环境名称" required><a-input v-model="environmentForm.name" :max-length="160" placeholder="例如：预发布环境" /></a-form-item>
          <a-form-item label="环境标识" required extra="保存后不可修改，用于用例、场景和运行记录关联。"><a-input v-model="environmentForm.environmentCode" :disabled="Boolean(editingEnvironment)" :max-length="64" placeholder="staging" /></a-form-item>
        </div>
        <a-form-item label="基础地址" extra="仅允许不含账号、查询参数和片段的 HTTP(S) URL；可暂不配置。"><a-input v-model="environmentForm.baseUrl" placeholder="https://staging-api.example.com" /></a-form-item>
        <a-form-item label="环境说明"><a-textarea v-model="environmentForm.description" :max-length="1200" :auto-size="{ minRows: 3, maxRows: 6 }" placeholder="说明用途、网络范围或维护责任人" /></a-form-item>
        <div class="switch-grid">
          <a-form-item label="环境状态"><a-switch v-model="environmentForm.enabled" :disabled="Boolean(editingEnvironment?.defaultEnvironment)" checked-text="启用" unchecked-text="停用" /></a-form-item>
          <a-form-item v-if="!editingEnvironment" label="默认环境"><a-switch v-model="environmentForm.defaultEnvironment" checked-text="设为默认" unchecked-text="普通环境" /></a-form-item>
        </div>
      </a-form>
    </a-modal>

    <a-drawer v-model:visible="detailVisible" :width="720" :footer="false">
      <template #title>
        <div class="drawer-title"><span class="environment-mark"><icon-cloud /></span><div><strong>{{ selectedEnvironment?.name }}</strong><small>{{ selectedEnvironment?.environmentCode }} · {{ selectedEnvironment?.baseUrl || '未配置基础地址' }}</small></div></div>
      </template>
      <div v-if="selectedEnvironment" class="variable-drawer">
        <a-alert type="warning">密钥使用 AES-GCM 加密保存，不会在列表、执行清单或报告中回显。接口与自动化通过 <code v-pre>{{变量名}}</code> 引用。</a-alert>
        <a-tabs v-model:active-key="detailKind">
          <a-tab-pane key="parameters" :title="`环境变量 (${activeParameters.length})`">
            <div class="drawer-toolbar"><span>非敏感配置</span><a-button type="primary" size="small" @click="openVariable('parameter')"><icon-plus /> 新增变量</a-button></div>
            <a-table :data="activeParameters" :pagination="false" row-key="id" size="small">
              <template #columns>
                <a-table-column title="变量名"><template #cell="{ record }"><code>{{ record.key }}</code></template></a-table-column>
                <a-table-column title="值"><template #cell="{ record }"><span class="variable-value">{{ record.value }}</span></template></a-table-column>
                <a-table-column title="操作" :width="130"><template #cell="{ record }"><a-button type="text" size="small" @click="openVariable('parameter', record)">编辑</a-button><a-popconfirm content="确定删除该变量吗？" @ok="removeVariable('parameters', record.id)"><a-button type="text" status="danger" size="small">删除</a-button></a-popconfirm></template></a-table-column>
              </template>
            </a-table>
            <a-empty v-if="!activeParameters.length" description="此环境暂无自定义变量" />
          </a-tab-pane>
          <a-tab-pane key="secrets" :title="`环境密钥 (${activeSecrets.length})`">
            <div class="drawer-toolbar"><span>加密敏感配置</span><a-button type="primary" size="small" @click="openVariable('secret')"><icon-plus /> 新增密钥</a-button></div>
            <a-table :data="activeSecrets" :pagination="false" row-key="id" size="small">
              <template #columns>
                <a-table-column title="密钥名"><template #cell="{ record }"><code>{{ record.key }}</code></template></a-table-column>
                <a-table-column title="值"><template #cell><span class="masked">•••••••• · 已加密</span></template></a-table-column>
                <a-table-column title="操作" :width="100"><template #cell="{ record }"><a-popconfirm content="删除后无法恢复，确定继续吗？" @ok="removeVariable('secrets', record.id)"><a-button type="text" status="danger" size="small">删除</a-button></a-popconfirm></template></a-table-column>
              </template>
            </a-table>
            <a-empty v-if="!activeSecrets.length" description="此环境暂无加密密钥" />
          </a-tab-pane>
        </a-tabs>
      </div>
    </a-drawer>

    <a-modal v-model:visible="variableVisible" :title="variableKind === 'secret' ? '保存环境密钥' : (editingVariable ? '编辑环境变量' : '新增环境变量')" width="560px" :ok-loading="saving" :on-before-ok="saveVariable">
      <a-form :model="variableForm" layout="vertical">
        <a-form-item label="所属环境"><a-input :model-value="`${selectedEnvironment?.name ?? ''} · ${selectedEnvironment?.environmentCode ?? ''}`" disabled /></a-form-item>
        <a-form-item :label="variableKind === 'secret' ? '密钥名称' : '变量名称'" required extra="支持字母、数字、点、下划线和连字符，且不能以数字开头。"><a-input v-model="variableForm.key" :disabled="Boolean(editingVariable)" :max-length="128" placeholder="例如：API_TOKEN" /></a-form-item>
        <a-form-item :label="variableKind === 'secret' ? '密钥值' : '变量值'" required>
          <a-input-password v-if="variableKind === 'secret'" v-model="variableForm.value" placeholder="保存后不会再次回显" />
          <a-textarea v-else v-model="variableForm.value" :auto-size="{ minRows: 3, maxRows: 8 }" :max-length="20000" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.environment-page{min-height:100%;padding:26px 28px 44px;background:#f6f7fb;color:#253047}.environment-header{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:18px}.environment-header h1{margin:5px 0 6px;font-size:24px;letter-spacing:-.02em}.environment-header p{max-width:760px;margin:0;color:#7d8798;font-size:12px;line-height:1.7}.eyebrow{color:#7960e8;font-size:10px;font-weight:800;letter-spacing:.16em}.environment-console{overflow:hidden;border:1px solid #e4e7ef;border-radius:12px;background:#fff;box-shadow:0 8px 26px rgba(40,52,83,.05)}.environment-tabs{height:57px;padding:0 18px;border-bottom:1px solid #e8eaf0}.environment-tabs :deep(.arco-tabs-content){display:none}.environment-tabs :deep(.arco-tabs-nav-tab){height:57px}.environment-toolbar{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:14px 18px;border-bottom:1px solid #eceef3;background:#fbfcfe}.project-filter{display:flex;align-items:center;gap:9px}.project-filter label{color:#6f7889;font-size:11px;font-weight:600}.environment-spin{display:block;min-height:420px}.environment-spin :deep(.arco-spin-children){min-height:420px}.environment-name,.drawer-title{display:flex;align-items:center;gap:10px}.environment-name>div,.drawer-title>div{display:grid;gap:3px;min-width:0}.environment-name strong,.environment-name small,.drawer-title strong,.drawer-title small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.environment-name strong{font-size:12px}.environment-name small,.drawer-title small,.table-secondary{display:block;color:#939baa;font-size:10px}.environment-mark{display:grid;place-items:center;flex:0 0 auto;width:32px;height:32px;color:#7356e6;border-radius:8px;background:#f0ecff}.environment-mark.disabled{color:#9da5b2;background:#eef0f3}.domain-code{display:block;max-width:280px;overflow:hidden;color:#4b5770;text-overflow:ellipsis;white-space:nowrap}.count-link{padding:0;color:#6650cf;font-size:11px;border:0;background:transparent;cursor:pointer}.count-link:hover{text-decoration:underline}.danger-option{color:#d54d58}.environment-footer{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:14px 18px;border-top:1px solid #eceef3}.environment-footer>span{color:#8d95a4;font-size:11px}.form-alert{margin-bottom:17px}.form-grid,.switch-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.drawer-title small{max-width:480px}.variable-drawer{display:grid;gap:18px}.drawer-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.drawer-toolbar>span{color:#7f8898;font-size:11px}.variable-value{display:block;max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.masked{color:#a16a1d;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}@media(max-width:980px){.environment-page{padding:20px 16px}.environment-toolbar{align-items:stretch;flex-direction:column}.environment-toolbar :deep(.arco-input-search){width:100%!important}.project-filter{flex-wrap:wrap}.environment-footer{align-items:flex-start;flex-direction:column}}@media(max-width:640px){.environment-header{align-items:flex-start;flex-direction:column}.form-grid,.switch-grid{grid-template-columns:1fr}.project-filter :deep(.arco-select){width:100%!important}}
</style>
