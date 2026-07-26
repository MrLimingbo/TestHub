<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface NotificationTemplate { id: string; name: string; eventCode: string; channel: string; subjectTemplate: string; bodyTemplate: string; enabled: boolean; targetUrl: string | null; signingSecretConfigured: boolean; updatedBy: string; updatedAt: string }
interface NotificationDelivery { id: string; templateName: string; eventCode: string; channel: string; targetUrl: string; status: string; attemptCount: number; nextAttemptAt: string; deliveredAt: string | null; responseStatus: number | null; lastError: string | null; createdAt: string }

const context = ref<Context | null>(null)
const projectId = ref('')
const templates = ref<NotificationTemplate[]>([])
const deliveries = ref<NotificationDelivery[]>([])
const loading = ref(true)
const visible = ref(false)
const editingId = ref<string | null>(null)
const channels = ['EMAIL', 'WEBHOOK', 'WECHAT', 'DINGTALK']
const form = reactive({ name: '', eventCode: 'run.failed', channel: 'EMAIL', subjectTemplate: 'TestHub 通知：{{event}}', bodyTemplate: '项目 {{project}} 的任务 {{task}} 执行结果：{{status}}', targetUrl: '', signingSecret: '', enabled: true })

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
  const [templateItems, deliveryItems] = await Promise.all([
    read<NotificationTemplate[]>(`/api/v1/projects/${projectId.value}/notification-templates`),
    read<NotificationDelivery[]>(`/api/v1/projects/${projectId.value}/notification-deliveries`),
  ])
  templates.value = templateItems
  deliveries.value = deliveryItems
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
  form.targetUrl = ''
  form.signingSecret = ''
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
  form.targetUrl = template.targetUrl ?? ''
  form.signingSecret = ''
  form.enabled = template.enabled
  visible.value = true
}
async function retry(delivery: NotificationDelivery) {
  try {
    await mutate(`/api/v1/projects/${projectId.value}/notification-deliveries/${delivery.id}/retry`, 'POST')
    await loadTemplates()
    Message.success('通知已重新进入投递队列')
  } catch (error) { Message.error(error instanceof Error ? error.message : '重试失败') }
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
    <PageHeader eyebrow="NOTIFICATION CENTER" title="通知与集成" description="按项目维护可复用的邮件、Webhook 与企业消息通知模板；质量报告 Webhook 支持签名、受控重试和 CI 消费。" action="新建模板" @action="create" />
    <section class="panel template-summary"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '240px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>模板总数</span><strong>{{ templates.length }}</strong></div><div><span>已启用</span><strong>{{ templates.filter((item) => item.enabled).length }}</strong></div></section>
    <section class="panel data-panel"><div class="template-toolbar"><div><a-tag color="arcoblue">项目级模板</a-tag><span class="toolbar-note">事件编码可被任务、流水线和 Webhook 适配器复用</span></div><a-button type="primary" @click="create"><icon-plus /> 新建模板</a-button></div><a-spin :loading="loading"><a-table :data="templates" :pagination="false" row-key="id"><template #columns><a-table-column title="模板名称" data-index="name"><template #cell="{ record }"><strong>{{ record.name }}</strong><small class="template-meta">{{ record.eventCode }}</small></template></a-table-column><a-table-column title="渠道" data-index="channel"><template #cell="{ record }"><a-tag :color="record.channel === 'EMAIL' ? 'arcoblue' : record.channel === 'WEBHOOK' ? 'purple' : 'orange'">{{ record.channel }}</a-tag></template></a-table-column><a-table-column title="主题模板" data-index="subjectTemplate"><template #cell="{ record }"><span class="ellipsis">{{ record.subjectTemplate }}</span></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-badge :status="record.enabled ? 'success' : 'normal'" :text="record.enabled ? '已启用' : '已停用'" /></template></a-table-column><a-table-column title="更新时间" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="edit(record)">编辑</a-button><a-button type="text" status="danger" @click="remove(record)">删除</a-button></a-space></template></a-table-column></template></a-table></a-spin><a-empty v-if="!loading && templates.length === 0" description="当前项目还没有通知模板，先创建一个用于任务和集成通知。" /></section>
    <section class="integration-grid">
      <article class="panel contract-panel">
        <div class="panel-title"><div><h3>质量报告 Webhook 契约</h3><p>CI 或自建服务使用签名头验证来源，并根据事件编码路由。</p></div><a-tag color="purple">HMAC-SHA256</a-tag></div><div class="contract-headers"><code>X-TestHub-Event</code><span>quality.report.ready / quality.report.risk</span><code>X-TestHub-Delivery-Id</code><span>幂等投递标识</span><code>X-TestHub-Timestamp</code><span>Unix 秒级时间戳</span><code>X-TestHub-Signature</code><span>sha256=HMAC(secret, timestamp + '\n' + body)</span></div><pre>curl -X POST "$WEBHOOK_URL" \
  -H "X-TestHub-Signature: sha256=…" \
  -d '{"event":"quality.report.risk"}'</pre><small>Webhook 必须使用 HTTPS；系统管理员需先在 <code>notifications.webhook.allowed-hosts</code> 配置允许域名。</small>
      </article>
      <article class="panel contract-panel">
        <div class="panel-title"><div><h3>CI 接入</h3><p>现有接口自动化入站 Webhook、CLI 与流水线可独立使用。</p></div><a-tag color="arcoblue">CI</a-tag></div><ul><li>Jenkins：使用项目访问令牌调用 API 自动化执行接口。</li><li>GitHub Actions / GitLab CI：在流水线失败时消费质量风险 Webhook。</li><li>通知投递失败会退避重试，最多 5 次；可在下表手动重新投递。</li></ul>
      </article>
    </section>
    <section class="panel data-panel delivery-panel"><div class="panel-title"><div><h3>通知投递记录</h3><p>不展示签名密钥或原始报表载荷；每次定时报告对同一模板事件仅投递一次。</p></div><span class="data-hint">最近 {{ deliveries.length }} 条</span></div><a-table :data="deliveries" :pagination="false" row-key="id"><template #columns><a-table-column title="事件 / 模板"><template #cell="{ record }"><strong>{{ record.eventCode }}</strong><small class="template-meta">{{ record.templateName }}</small></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-tag :color="record.status === 'SUCCEEDED' ? 'green' : record.status === 'FAILED' ? 'red' : record.status === 'RETRYING' ? 'orange' : 'arcoblue'">{{ record.status }}</a-tag><small v-if="record.responseStatus" class="template-meta">HTTP {{ record.responseStatus }}</small></template></a-table-column><a-table-column title="尝试"><template #cell="{ record }">{{ record.attemptCount }} 次</template></a-table-column><a-table-column title="最近结果"><template #cell="{ record }"><span class="ellipsis">{{ record.lastError ?? (record.deliveredAt ? `已于 ${date(record.deliveredAt)} 投递` : `下次：${date(record.nextAttemptAt)}`) }}</span></template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-button v-if="record.status === 'FAILED' || record.status === 'SUCCEEDED'" type="text" @click="retry(record)">重新投递</a-button><span v-else class="template-meta">队列处理中</span></template></a-table-column></template></a-table><a-empty v-if="!loading && deliveries.length === 0" description="尚无定时质量报告通知投递记录。" /></section>
    <a-modal v-model:visible="visible" :title="editingId ? '编辑通知模板' : '新建通知模板'" width="680px" @ok="save"><a-form :model="form" layout="vertical"><a-form-item label="模板名称" required><a-input v-model="form.name" placeholder="例如：接口回归失败通知" /></a-form-item><a-form-item label="事件编码" required extra="质量报告使用 quality.report.ready 或 quality.report.risk。"><a-input v-model="form.eventCode" placeholder="quality.report.risk" /></a-form-item><a-form-item label="通知渠道" required><a-select v-model="form.channel"><a-option v-for="channel in channels" :key="channel" :value="channel">{{ channel }}</a-option></a-select></a-form-item><template v-if="form.channel === 'WEBHOOK'"><a-form-item label="Webhook 地址" required extra="仅允许系统配置名单中的 HTTPS 域名。"><a-input v-model="form.targetUrl" placeholder="https://hooks.example.com/testhub" /></a-form-item><a-form-item label="Webhook 签名密钥" :required="!editingId" :extra="editingId ? '留空会保留现有密钥。' : '将加密保存，仅用于 HMAC-SHA256 签名。'"><a-input-password v-model="form.signingSecret" placeholder="至少设置一次签名密钥" /></a-form-item></template><a-form-item label="主题模板" required><a-input v-model="form.subjectTemplate" placeholder="TestHub 通知：{{event}}" /></a-form-item><a-form-item label="正文模板" required><a-textarea v-model="form.bodyTemplate" :auto-size="{ minRows: 5, maxRows: 10 }" placeholder="支持 {{project}}、{{task}}、{{status}} 等事件变量。" /></a-form-item><a-form-item label="启用状态"><a-switch v-model="form.enabled"><template #checked>启用</template><template #unchecked>停用</template></a-switch></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.template-summary{display:grid;grid-template-columns:1.5fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.template-summary div{display:grid;gap:4px}.template-summary span,.template-summary small,.template-meta,.toolbar-note{color:var(--color-text-3);font-size:12px}.template-summary strong{font-size:20px}.template-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.toolbar-note{margin-left:10px}.template-meta{display:block;margin-top:4px}.ellipsis{display:block;max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.integration-grid{display:grid;grid-template-columns:1.2fr .8fr;gap:18px;margin:0 0 18px}.contract-panel{display:grid;gap:12px}.contract-headers{display:grid;grid-template-columns:180px 1fr;gap:8px;font-size:12px}.contract-headers span,.contract-panel small,.contract-panel li{color:var(--color-text-3);line-height:1.65}.contract-panel code{font-size:11px}.contract-panel pre{margin:0;padding:12px;border-radius:8px;overflow:auto;background:var(--color-fill-1);font-size:11px}.contract-panel ul{margin:0;padding-left:20px}.delivery-panel{margin-top:0}@media (max-width:900px){.template-summary{grid-template-columns:1fr 1fr}.template-toolbar{gap:12px;align-items:flex-start;flex-direction:column}.toolbar-note{display:block;margin:6px 0 0}.integration-grid{grid-template-columns:1fr}.contract-headers{grid-template-columns:1fr}}
</style>
