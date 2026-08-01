<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import CodeEditor from '@/components/CodeEditor.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface MockServer { id: string; organizationId: string; projectId: string; publicKey: string; name: string; enabled: boolean; smartExampleEnabled: boolean; createdBy: string; createdAt: string; updatedAt: string; ruleCount: number }
interface MockRule { id: string; serverId: string; organizationId: string; projectId: string; name: string; httpMethod: string; path: string; priority: number; requestQuery: Record<string, string>; requestHeaders: Record<string, string>; requestBodyMatch: 'ANY' | 'EXACT' | 'JSON_SUBSET'; requestBody: string; responseStatus: number; responseHeaders: Record<string, string>; responseBody: string; enabled: boolean; createdAt: string; updatedAt: string }
interface SmartMockExample { apiId: string; name: string; method: string; path: string; versionName: string; responseStatus: number; bodyPreview: string }
interface MockShare { id: string; serverId: string; organizationId: string; projectId: string; shareKey: string; name: string; expiresAt: string | null; revokedAt: string | null; createdBy: string; createdAt: string }

const context = ref<Context | null>(null)
const session = useSessionStore()
const projectId = computed({ get: () => session.projectId, set: (value: string) => session.selectProject(value) })
const servers = ref<MockServer[]>([])
const rules = ref<MockRule[]>([])
const smartExamples = ref<SmartMockExample[]>([])
const shares = ref<MockShare[]>([])
const selectedServerId = ref('')
const loading = ref(true)
const rulesLoading = ref(false)
const smartLoading = ref(false)
const creating = ref(false)
const sharing = ref(false)
const serverVisible = ref(false)
const ruleVisible = ref(false)
const shareVisible = ref(false)
const serverForm = reactive({ name: '' })
const ruleForm = reactive({ name: '', httpMethod: 'GET', path: '/orders', priority: 0, requestQuery: '{\n}', requestHeaders: '{\n}', requestBodyMatch: 'ANY', requestBody: '', responseStatus: 200, headers: '{\n  "Content-Type": "application/json; charset=utf-8"\n}', body: '{\n  "code": 0,\n  "message": "mock response"\n}' })
const shareForm = reactive({ name: '', expiresInDays: 30 })
const shareNeverExpires = ref(false)

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const selectedServer = computed(() => servers.value.find((server) => server.id === selectedServerId.value) ?? null)
const enabledCount = computed(() => servers.value.filter((server) => server.enabled).length)
const ruleCount = computed(() => servers.value.reduce((total, server) => total + server.ruleCount, 0))
const smartExampleCount = computed(() => smartExamples.value.length)
const activeShareCount = computed(() => shares.value.filter((share) => !share.revokedAt).length)
const runtimeUrl = computed(() => selectedServer.value ? `${window.location.origin}/api/v1/mock/${selectedServer.value.publicKey}` : '')

async function read<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: 'include', ...init })
  const envelope = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(envelope.message ?? '请求失败，请稍后重试')
  return envelope.data
}

async function mutate<T>(path: string, body: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(body) })
}

async function loadRules() {
  if (!projectId.value || !selectedServerId.value) { rules.value = []; return }
  rulesLoading.value = true
  try { rules.value = await read<MockRule[]>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServerId.value}/rules`) } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载 Mock 规则失败')
  } finally { rulesLoading.value = false }
}

async function loadSmartExamples() {
  if (!projectId.value || !selectedServerId.value) { smartExamples.value = []; return }
  smartLoading.value = true
  try { smartExamples.value = await read<SmartMockExample[]>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServerId.value}/smart-examples`) } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载智能示例失败')
  } finally { smartLoading.value = false }
}

async function loadShares() {
  if (!projectId.value || !selectedServerId.value) { shares.value = []; return }
  try { shares.value = await read<MockShare[]>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServerId.value}/shares`) } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载 Mock 分享链接失败')
  }
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = session.resolveProjectId(projects.value)
    await loadServers()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载 Mock 服务失败') } finally { loading.value = false }
}

async function loadServers() {
  if (!projectId.value) { servers.value = []; return }
  servers.value = await read<MockServer[]>(`/api/v1/projects/${projectId.value}/mock/servers`)
  if (!servers.value.some((server) => server.id === selectedServerId.value)) selectedServerId.value = servers.value[0]?.id ?? ''
  await loadRules()
  await loadSmartExamples()
  await loadShares()
}

async function changeProject() {
  selectedServerId.value = ''
  rules.value = []
  smartExamples.value = []
  shares.value = []
  try { await loadServers() } catch (error) { Message.error(error instanceof Error ? error.message : '切换项目失败') }
}

async function selectServer(serverId: string) {
  selectedServerId.value = serverId
  await loadRules()
  await loadSmartExamples()
  await loadShares()
}

async function createServer() {
  if (!projectId.value || !serverForm.name.trim()) { Message.warning('请填写 Mock 服务名称'); return }
  creating.value = true
  try {
    const server = await mutate<MockServer>(`/api/v1/projects/${projectId.value}/mock/servers`, { name: serverForm.name })
    servers.value = [server, ...servers.value]
    selectedServerId.value = server.id
    rules.value = []
    smartExamples.value = []
    shares.value = []
    serverForm.name = ''
    serverVisible.value = false
    Message.success('Mock 服务已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建 Mock 服务失败') } finally { creating.value = false }
}

function stringMap(source: string, label: string) {
  let value: unknown
  try { value = JSON.parse(source || '{}') } catch { throw new Error(`${label}必须是 JSON 对象`) }
  if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error(`${label}必须是 JSON 对象`)
  const result: Record<string, string> = {}
  for (const [key, headerValue] of Object.entries(value as Record<string, unknown>)) {
    if (typeof headerValue !== 'string') throw new Error(`${label}值必须是字符串`)
    result[key] = headerValue
  }
  return result
}

function responseHeaders() { return stringMap(ruleForm.headers, '响应头') }
function requestQuery() { return stringMap(ruleForm.requestQuery, '请求参数条件') }
function requestHeaders() { return stringMap(ruleForm.requestHeaders, '请求头条件') }

function resetRuleForm() {
  Object.assign(ruleForm, { name: '', httpMethod: 'GET', path: '/orders', priority: 0, requestQuery: '{\n}', requestHeaders: '{\n}', requestBodyMatch: 'ANY', requestBody: '', responseStatus: 200, headers: '{\n  "Content-Type": "application/json; charset=utf-8"\n}', body: '{\n  "code": 0,\n  "message": "mock response"\n}' })
}

async function createRule() {
  if (!projectId.value || !selectedServer.value || !ruleForm.name.trim() || !ruleForm.path.trim()) { Message.warning('请填写规则名称和路径'); return }
  creating.value = true
  try {
    const rule = await mutate<MockRule>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServer.value.id}/rules`, {
      name: ruleForm.name, httpMethod: ruleForm.httpMethod, path: ruleForm.path, priority: ruleForm.priority,
      requestQuery: requestQuery(), requestHeaders: requestHeaders(), requestBodyMatch: ruleForm.requestBodyMatch,
      requestBody: ruleForm.requestBody, responseStatus: ruleForm.responseStatus, responseHeaders: responseHeaders(), responseBody: ruleForm.body,
    })
    rules.value = [rule, ...rules.value]
    servers.value = servers.value.map((server) => server.id === selectedServer.value?.id ? { ...server, ruleCount: server.ruleCount + 1 } : server)
    resetRuleForm()
    ruleVisible.value = false
    Message.success('手动 Mock 规则已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建 Mock 规则失败') } finally { creating.value = false }
}

async function setEnabled(value: boolean | string | number) {
  if (!projectId.value || !selectedServer.value) return
  try {
    const server = await mutate<MockServer>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServer.value.id}/enabled`, { enabled: Boolean(value) })
    servers.value = servers.value.map((item) => item.id === server.id ? server : item)
    Message.success(server.enabled ? 'Mock 服务已启用' : 'Mock 服务已停用')
  } catch (error) { Message.error(error instanceof Error ? error.message : '更新 Mock 服务失败') }
}

async function setSmartExampleEnabled(value: boolean | string | number) {
  if (!projectId.value || !selectedServer.value) return
  try {
    const server = await mutate<MockServer>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServer.value.id}/smart-example/enabled`, { enabled: Boolean(value) })
    servers.value = servers.value.map((item) => item.id === server.id ? server : item)
    Message.success(server.smartExampleEnabled ? '智能示例 Mock 已启用' : '智能示例 Mock 已停用')
  } catch (error) { Message.error(error instanceof Error ? error.message : '更新智能示例 Mock 失败') }
}

function shareUrl(share: MockShare) { return `${window.location.origin}/mock/share/${share.shareKey}` }

async function createShare() {
  if (!projectId.value || !selectedServer.value || !shareForm.name.trim()) { Message.warning('请填写分享名称'); return }
  sharing.value = true
  try {
    const share = await mutate<MockShare>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServer.value.id}/shares`, {
      name: shareForm.name, expiresInDays: shareNeverExpires.value ? null : shareForm.expiresInDays,
    })
    shares.value = [share, ...shares.value]
    shareForm.name = ''
    shareForm.expiresInDays = 30
    shareNeverExpires.value = false
    shareVisible.value = false
    Message.success('只读分享链接已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建分享链接失败') } finally { sharing.value = false }
}

async function revokeShare(share: MockShare) {
  if (!projectId.value || !selectedServer.value) return
  try {
    const result = await mutate<MockShare>(`/api/v1/projects/${projectId.value}/mock/servers/${selectedServer.value.id}/shares/${share.id}/revoke`, {})
    shares.value = shares.value.map((item) => item.id === result.id ? result : item)
    Message.success('分享链接已撤销')
  } catch (error) { Message.error(error instanceof Error ? error.message : '撤销分享链接失败') }
}

async function copyShareUrl(share: MockShare) {
  const url = shareUrl(share)
  if (!navigator.clipboard) { Message.info(url); return }
  try { await navigator.clipboard.writeText(url); Message.success('分享链接已复制') } catch { Message.info(url) }
}

async function copyRuntimeUrl() {
  if (!runtimeUrl.value || !navigator.clipboard) { Message.info(runtimeUrl.value || '请先选择 Mock 服务'); return }
  try { await navigator.clipboard.writeText(runtimeUrl.value); Message.success('Mock 地址已复制') } catch { Message.info(runtimeUrl.value) }
}

function statusColor(status: number) { return status >= 500 ? 'red' : status >= 400 ? 'orange' : status >= 300 ? 'arcoblue' : 'green' }
function conditionSummary(rule: MockRule) {
  const conditions = [`优先级 ${rule.priority}`]
  if (Object.keys(rule.requestQuery).length) conditions.push(`${Object.keys(rule.requestQuery).length} 个参数`)
  if (Object.keys(rule.requestHeaders).length) conditions.push(`${Object.keys(rule.requestHeaders).length} 个请求头`)
  if (rule.requestBodyMatch !== 'ANY') conditions.push(rule.requestBodyMatch === 'EXACT' ? '请求体精确' : 'JSON 子集')
  return conditions.join(' · ')
}
function date(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '永不过期' }

onMounted(load)
</script>

<template>
  <div class="page mock-page">
    <PageHeader eyebrow="API SIMULATION" title="Mock 服务" description="手动规则优先，未命中时可回退到当前项目已发布接口的响应示例。" action="新建 Mock 服务" @action="serverVisible = true" />
    <section class="mock-summary panel"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>Mock 服务</span><strong>{{ servers.length }}</strong><small>{{ enabledCount }} 个已启用</small></div><div><span>手动规则</span><strong>{{ ruleCount }}</strong><small>条件匹配与优先级</small></div></section>
    <section class="mock-workspace">
      <aside class="server-panel panel"><div class="panel-heading"><div><span class="eyebrow">MOCK SERVERS</span><h3>服务列表</h3></div><a-button type="text" size="small" @click="loadServers"><icon-refresh /></a-button></div><a-spin :loading="loading"><button v-for="server in servers" :key="server.id" class="server-row" :class="{ selected: selectedServerId === server.id }" @click="selectServer(server.id)"><icon-cloud /><div><strong>{{ server.name }}</strong><small>{{ server.ruleCount }} 条规则 · {{ server.enabled ? '已启用' : '已停用' }}</small></div><a-tag size="small" :color="server.enabled ? 'green' : 'gray'">{{ server.enabled ? 'ON' : 'OFF' }}</a-tag></button><a-empty v-if="!servers.length && !loading" description="还没有 Mock 服务"><template #extra><a-button type="outline" @click="serverVisible = true">新建服务</a-button></template></a-empty></a-spin></aside>
      <section class="mock-detail panel"><template v-if="selectedServer"><div class="detail-head"><div><span class="eyebrow">PUBLIC ENDPOINT</span><h3>{{ selectedServer.name }}</h3><small>创建于 {{ date(selectedServer.createdAt) }} · 请求体不会被平台持久化</small></div><div class="detail-actions"><a-switch :model-value="selectedServer.enabled" checked-text="启用" unchecked-text="停用" @change="setEnabled" /><a-button type="primary" :disabled="!selectedServer.enabled" @click="ruleVisible = true"><icon-plus /> 新建规则</a-button></div></div><section class="runtime-box"><div><strong>公开调用地址</strong><small>无需登录；停用服务或未命中规则会安全返回 404。</small></div><code>{{ runtimeUrl }}</code><a-button size="small" @click="copyRuntimeUrl">复制地址</a-button></section><section class="smart-box"><div class="smart-head"><div><span class="eyebrow">PUBLISHED API EXAMPLES</span><strong>智能示例 Mock</strong><small>仅使用当前项目已发布接口的当前版本；手动规则命中时始终优先。</small></div><a-switch :model-value="selectedServer.smartExampleEnabled" checked-text="已启用" unchecked-text="已停用" @change="setSmartExampleEnabled" /></div><div class="smart-status"><a-tag :color="selectedServer.smartExampleEnabled ? 'green' : 'gray'">{{ selectedServer.smartExampleEnabled ? '已启用' : '未启用' }}</a-tag><span>可用已发布接口：{{ smartExampleCount }} 个</span><a-button size="mini" @click="loadSmartExamples">刷新来源</a-button></div><a-table :data="smartExamples" :loading="smartLoading" :pagination="false" size="small" row-key="apiId"><template #columns><a-table-column title="接口"><template #cell="{ record }"><div class="rule-name"><strong>{{ record.name }}</strong><small>{{ record.versionName }} · {{ record.bodyPreview }}</small></div></template></a-table-column><a-table-column title="路由"><template #cell="{ record }"><a-tag :color="record.method === 'GET' ? 'green' : 'arcoblue'">{{ record.method }}</a-tag> <code>{{ record.path }}</code></template></a-table-column><a-table-column title="示例响应" :width="100"><template #cell="{ record }"><a-tag :color="statusColor(record.responseStatus)">{{ record.responseStatus }}</a-tag></template></a-table-column></template></a-table><a-empty v-if="!smartExamples.length && !smartLoading" description="当前项目没有已发布接口示例" /></section><div class="rule-head"><div><strong>Mock 规则</strong><small>先按优先级，再精确匹配方法、路径、参数、请求头和请求体条件；无需脚本。</small></div><a-button size="small" @click="loadRules">刷新</a-button></div><a-table :data="rules" :loading="rulesLoading" :pagination="false" row-key="id"><template #columns><a-table-column title="规则"><template #cell="{ record }"><div class="rule-name"><strong>{{ record.name }}</strong><small>{{ record.responseHeaders['Content-Type'] ?? 'application/json' }}</small></div></template></a-table-column><a-table-column title="请求"><template #cell="{ record }"><a-tag :color="record.httpMethod === 'GET' ? 'green' : record.httpMethod === 'POST' ? 'arcoblue' : 'orange'">{{ record.httpMethod }}</a-tag> <code>{{ record.path }}</code></template></a-table-column><a-table-column title="匹配条件"><template #cell="{ record }"><small class="condition-summary">{{ conditionSummary(record) }}</small></template></a-table-column><a-table-column title="响应"><template #cell="{ record }"><a-tag :color="statusColor(record.responseStatus)">{{ record.responseStatus }}</a-tag><small>{{ record.responseBody.length }} 字符</small></template></a-table-column></template></a-table><a-empty v-if="!rules.length && !rulesLoading" description="当前服务还没有规则"><template #extra><a-button type="outline" :disabled="!selectedServer.enabled" @click="ruleVisible = true">新建第一条规则</a-button></template></a-empty></template><a-empty v-else description="选择或创建一个 Mock 服务后配置规则" /></section>
    </section>
    <section v-if="selectedServer" class="share-box panel">
      <div class="share-head"><div><span class="eyebrow">TEAM COLLABORATION</span><h3>协作与分享</h3><small>项目成员沿用自身 RBAC 管理 Mock；外部链接只读展示调用说明，不包含响应正文、密钥或成员数据。</small></div><a-button type="primary" @click="shareVisible = true"><icon-share-alt /> 创建只读分享</a-button></div>
      <div class="share-status"><a-tag color="arcoblue">{{ activeShareCount }} 条有效链接</a-tag><span>已撤销或已过期的链接不能再读取说明。</span><a-button size="mini" @click="loadShares">刷新链接</a-button></div>
      <a-table :data="shares" :pagination="false" row-key="id" size="small"><template #columns><a-table-column title="分享名称"><template #cell="{ record }"><div class="rule-name"><strong>{{ record.name }}</strong><small>创建于 {{ date(record.createdAt) }}</small></div></template></a-table-column><a-table-column title="有效期"><template #cell="{ record }">{{ date(record.expiresAt) }}</template></a-table-column><a-table-column title="状态" :width="100"><template #cell="{ record }"><a-tag :color="record.revokedAt ? 'red' : 'green'">{{ record.revokedAt ? '已撤销' : '有效' }}</a-tag></template></a-table-column><a-table-column title="操作" :width="190"><template #cell="{ record }"><a-space><a-button v-if="!record.revokedAt" type="text" size="small" @click="copyShareUrl(record)">复制链接</a-button><a-popconfirm v-if="!record.revokedAt" content="撤销后该分享链接将立即失效，是否继续？" @ok="revokeShare(record)"><a-button type="text" size="small" status="danger">撤销</a-button></a-popconfirm></a-space></template></a-table-column></template></a-table><a-empty v-if="!shares.length" description="还没有只读分享链接" />
    </section>
    <a-modal v-model:visible="serverVisible" title="新建 Mock 服务" :confirm-loading="creating" @ok="createServer"><a-form :model="serverForm" layout="vertical"><a-form-item label="服务名称" required><a-input v-model="serverForm.name" :max-length="160" placeholder="例如：订单服务模拟环境" /></a-form-item><div class="modal-tip">创建后会生成一条公开地址。该地址不需要登录，适用于联调与自动化测试；可随时停用服务。</div></a-form></a-modal>
    <a-modal v-model:visible="ruleVisible" title="新建 Mock 规则" width="860px" :confirm-loading="creating" @ok="createRule"><a-form :model="ruleForm" layout="vertical"><div class="form-grid"><a-form-item label="规则名称" required><a-input v-model="ruleForm.name" :max-length="160" placeholder="例如：预览订单成功" /></a-form-item><a-form-item label="优先级"><a-input-number v-model="ruleForm.priority" :min="0" :max="1000" /><small class="field-tip">数值越大越先匹配；同优先级按创建顺序。</small></a-form-item></div><a-divider orientation="left">请求匹配</a-divider><div class="form-grid"><a-form-item label="请求方法" required><a-select v-model="ruleForm.httpMethod"><a-option v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']" :key="method" :value="method">{{ method }}</a-option></a-select></a-form-item><a-form-item label="精确路径" required><a-input v-model="ruleForm.path" :max-length="700" placeholder="/orders" /></a-form-item></div><div class="form-grid"><a-form-item label="请求参数条件（JSON 对象）"><CodeEditor v-model="ruleForm.requestQuery" :height="110" /></a-form-item><a-form-item label="请求头条件（JSON 对象）"><CodeEditor v-model="ruleForm.requestHeaders" :height="110" /></a-form-item></div><div class="form-grid"><a-form-item label="请求体匹配"><a-select v-model="ruleForm.requestBodyMatch"><a-option value="ANY">任意请求体</a-option><a-option value="EXACT">请求体精确匹配</a-option><a-option value="JSON_SUBSET">JSON 子集匹配</a-option></a-select></a-form-item><div class="match-hint"><strong>{{ ruleForm.requestBodyMatch === 'ANY' ? '不限制请求体' : ruleForm.requestBodyMatch === 'EXACT' ? '字节文本完全一致' : '请求 JSON 必须包含给定字段' }}</strong><small>参数和请求头值均使用精确匹配。</small></div></div><a-form-item v-if="ruleForm.requestBodyMatch !== 'ANY'" :label="ruleForm.requestBodyMatch === 'JSON_SUBSET' ? 'JSON 请求体条件' : '请求体条件'"><CodeEditor v-model="ruleForm.requestBody" :height="140" /></a-form-item><a-divider orientation="left">响应配置</a-divider><div class="form-grid"><a-form-item label="响应状态"><a-input-number v-model="ruleForm.responseStatus" :min="200" :max="599" /></a-form-item><div /></div><a-form-item label="响应头（JSON 对象）"><CodeEditor v-model="ruleForm.headers" :height="110" /></a-form-item><a-form-item label="响应体"><CodeEditor v-model="ruleForm.body" :height="160" /></a-form-item><div class="modal-tip">未命中规则、路径或方法不匹配、服务停用时均返回 404。此节点不执行脚本；JSON 子集仅匹配对象字段，数组需完全一致。</div></a-form></a-modal>
    <a-modal v-model:visible="shareVisible" title="创建只读 Mock 分享" width="560px" :confirm-loading="sharing" @ok="createShare"><a-form :model="shareForm" layout="vertical"><a-form-item label="分享名称" required><a-input v-model="shareForm.name" :max-length="120" placeholder="例如：合作方订单联调说明" /></a-form-item><a-form-item label="有效期"><a-input-number v-model="shareForm.expiresInDays" :disabled="shareNeverExpires" :min="1" :max="365" /> <span class="form-hint">天（1–365）</span><a-checkbox v-model="shareNeverExpires" class="never-expire">永不过期</a-checkbox></a-form-item><div class="modal-tip">分享页只显示方法、路径、状态码和匹配条件摘要。它不会公开响应正文、响应头、项目成员、环境变量或密钥；可随时撤销。</div></a-form></a-modal>
  </div>
</template>

<style scoped>
.mock-summary{display:grid;grid-template-columns:1.45fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.mock-summary>div{display:grid;gap:4px}.mock-summary span,.mock-summary small,.server-row small,.detail-head small,.runtime-box small,.smart-head small,.rule-head small,.rule-name small,.modal-tip,.field-tip,.match-hint small,.condition-summary,.smart-status{color:var(--color-text-3);font-size:12px}.mock-summary strong{font-size:20px}.mock-workspace{display:grid;grid-template-columns:290px minmax(0,1fr);gap:18px;align-items:start}.server-panel{min-height:520px;padding:16px}.panel-heading,.detail-head,.detail-actions,.runtime-box,.rule-head,.smart-head,.smart-status{display:flex;gap:12px}.panel-heading,.detail-head,.runtime-box,.rule-head,.smart-head{align-items:flex-start;justify-content:space-between}.panel-heading h3,.detail-head h3{margin:4px 0 0;font-size:16px}.server-row{display:flex;width:100%;gap:10px;align-items:center;margin-top:8px;padding:11px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff;text-align:left;cursor:pointer}.server-row:hover,.server-row.selected{border-color:rgb(var(--primary-6));background:rgb(var(--primary-1))}.server-row>div{display:grid;min-width:0;flex:1;gap:3px}.server-row strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.server-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.mock-detail{min-height:520px;padding:20px}.detail-head>div:first-child,.smart-head>div{display:grid;gap:2px}.detail-actions{align-items:center;flex-wrap:wrap}.runtime-box{align-items:center;margin:18px 0;padding:14px;border:1px solid rgb(var(--primary-2));border-radius:8px;background:rgb(var(--primary-1))}.runtime-box>div{display:grid;gap:4px}.runtime-box code{min-width:0;max-width:48%;overflow:hidden;color:rgb(var(--primary-6));text-overflow:ellipsis;white-space:nowrap;font-size:12px}.smart-box{margin:18px 0;padding:14px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.smart-head strong{font-size:15px}.smart-status{align-items:center;margin:10px 0}.rule-head{margin:18px 0 10px}.rule-head>div{display:grid;gap:4px}.rule-name{display:grid;gap:3px;min-width:0}.rule-name strong,.rule-name small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rule-name strong{font-size:13px}.rule-name small{font-size:11px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.field-tip{display:block;margin-top:4px}.match-hint{display:grid;align-content:center;gap:5px;padding:10px;border:1px solid var(--color-border-2);border-radius:6px;background:var(--color-fill-1)}.condition-summary{line-height:1.5}.modal-tip{padding:10px;border-left:3px solid var(--color-border-3);background:var(--color-fill-1);line-height:1.6}@media (max-width:900px){.mock-summary{grid-template-columns:1fr 1fr}.mock-workspace{grid-template-columns:1fr}.server-panel{min-height:auto}.runtime-box{align-items:flex-start;flex-direction:column}.runtime-box code{max-width:100%}}@media (max-width:640px){.mock-summary,.form-grid{grid-template-columns:1fr}.detail-head,.smart-head{align-items:stretch;flex-direction:column}.runtime-box{align-items:stretch}.detail-actions{justify-content:space-between}.smart-status{flex-wrap:wrap}}
</style>

<style scoped>
.share-box{margin-top:20px;padding:20px}.share-head,.share-status{display:flex;gap:12px}.share-head{align-items:flex-start;justify-content:space-between}.share-head>div{display:grid;gap:4px}.share-head h3{margin:0;font-size:16px}.share-head small,.share-status{color:var(--color-text-3);font-size:12px}.share-status{align-items:center;margin:12px 0;flex-wrap:wrap}.form-hint{color:var(--color-text-3);margin-left:8px}.never-expire{margin-left:14px}@media (max-width:640px){.share-head{flex-direction:column;align-items:stretch}.never-expire{display:block;margin:10px 0 0}}
</style>
