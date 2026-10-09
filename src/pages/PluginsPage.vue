<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'
import { isLocalDesktop } from '@/utils/desktopWorkspace'

interface Envelope<T> { data: T }
interface Plugin { id: string; pluginKey: string; displayName: string; pluginType: string; activeVersion: string; status: string; signerKeyId: string; packageChecksum: string; permissions: string[]; entrypoint: string; installedAt: string; updatedAt: string; enabledAt?: string; disabledAt?: string }
interface SigningKey { id: string; keyId: string; displayName: string; enabled: boolean; createdAt: string; revokedAt?: string }
interface AdapterDefinition { type: string; displayName: string; category: string; requiredFields: string[]; description: string }
interface Adapter { id: string; adapterType: string; displayName: string; endpoint: string; configuredFields: string[]; enabled: boolean; createdAt: string; updatedAt: string }

const plugins = ref<Plugin[]>([]), signingKeys = ref<SigningKey[]>([]), adapters = ref<Adapter[]>([]), definitions = ref<AdapterDefinition[]>([])
const loading = ref(true), allowed = ref(false), loadError = ref(''), keyError = ref('')
const keyManagerVisible = ref(false), keyVisible = ref(false), packageVisible = ref(false), adapterVisible = ref(false)
const editingAdapterId = ref<string | null>(null), pending = reactive(new Set<string>())
const saving = reactive({ key: false, package: false, adapter: false })
const sequences = { key: 0, package: 0, adapter: 0 }
let disposed = false, loadSequence = 0, keySequence = 0
const formKey = reactive({ keyId: '', displayName: '', publicKeyBase64: '' })
const formPackage = reactive({ signerKeyId: '', manifestJson: '{\n  "pluginKey": "example.adapter",\n  "displayName": "示例扩展",\n  "pluginType": "INTEGRATION",\n  "version": "1.0.0",\n  "entrypoint": "adapter/index.js",\n  "permissions": ["integration:outbound"]\n}', signatureBase64: '' })
const formAdapter = reactive({ adapterType: 'OIDC', displayName: '', endpoint: '', configurationJson: '', enabled: false })
const selectedDefinition = computed(() => definitions.value.find(item => item.type === formAdapter.adapterType))
const date = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as Envelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const encoded = body === undefined ? undefined : JSON.stringify(body)
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: encoded })
}
async function loadKeys() {
  if (!allowed.value || disposed) return
  const request = ++keySequence; signingKeys.value = []; keyError.value = ''
  try { const value = await read<SigningKey[]>('/api/v1/extensions/signing-keys'); if (!disposed && request === keySequence) signingKeys.value = value }
  catch (error) { if (!disposed && request === keySequence) keyError.value = error instanceof Error ? error.message : '签名密钥读取失败' }
}
async function load() {
  const request = ++loadSequence; loading.value = true; loadError.value = ''
  plugins.value = []; adapters.value = []; definitions.value = []; signingKeys.value = []; allowed.value = false; keySequence++
  try {
    const capability = await read<{ systemAdministration: boolean }>('/api/v1/administration/capabilities')
    if (typeof capability?.systemAdministration !== 'boolean') throw new Error('无法读取插件管理权限')
    if (disposed || request !== loadSequence) return
    const [nextPlugins, nextAdapters, nextDefinitions] = await Promise.all([
      read<Plugin[]>('/api/v1/extensions/plugins'), read<Adapter[]>('/api/v1/extensions/adapters'), read<AdapterDefinition[]>('/api/v1/extensions/adapter-definitions'),
    ])
    if (disposed || request !== loadSequence) return
    allowed.value = capability.systemAdministration; plugins.value = nextPlugins; adapters.value = nextAdapters; definitions.value = nextDefinitions
    if (allowed.value) await loadKeys()
  } catch (error) { if (!disposed && request === loadSequence) loadError.value = error instanceof Error ? error.message : '扩展中心加载失败' }
  finally { if (!disposed && request === loadSequence) loading.value = false }
}
type FormKind = 'key' | 'package' | 'adapter'
function cancel(kind: FormKind) {
  sequences[kind]++
  if (kind === 'key') formKey.publicKeyBase64 = ''
  if (kind === 'package') { formPackage.signatureBase64 = ''; formPackage.manifestJson = '' }
  if (kind === 'adapter') formAdapter.configurationJson = ''
}
async function save(kind: FormKind, url: string, method: 'POST' | 'PUT', input: unknown, message: string): Promise<boolean> {
  if (disposed || !allowed.value || saving[kind]) return false
  const sequence = sequences[kind]; saving[kind] = true
  try {
    await mutate(url, method, input)
    if (disposed || sequence !== sequences[kind]) return false
    if (kind === 'key') keyVisible.value = false
    if (kind === 'package') packageVisible.value = false
    if (kind === 'adapter') adapterVisible.value = false
    if (kind === 'key') formKey.publicKeyBase64 = ''
    if (kind === 'package') { formPackage.signatureBase64 = ''; formPackage.manifestJson = '' }
    if (kind === 'adapter') formAdapter.configurationJson = ''
    if (kind === 'key') await loadKeys(); else await load()
    if (disposed || sequence !== sequences[kind]) return false
    Message.success(message); return true
  } catch (error) { if (!disposed && sequence === sequences[kind]) Message.error(error instanceof Error ? error.message : '保存失败'); return false }
  finally { saving[kind] = false }
}
function openKey() { if (!allowed.value) return; cancel('key'); Object.assign(formKey, { keyId: '', displayName: '', publicKeyBase64: '' }); keyVisible.value = true }
function saveKey() { return save('key', '/api/v1/extensions/signing-keys', 'POST', { ...formKey }, '可信签名密钥已登记') }
async function openKeyManager() { if (!allowed.value) return; keyManagerVisible.value = true; await loadKeys() }
async function action(key: string, url: string, message: string, method: 'POST' | 'DELETE' = 'POST') {
  if (disposed || !allowed.value || pending.has(key)) return
  pending.add(key)
  try { await mutate(url, method); if (disposed) return; await load(); if (!disposed) Message.success(message) }
  catch (error) { if (!disposed) Message.error(error instanceof Error ? error.message : '操作失败') }
  finally { pending.delete(key) }
}
function changeSigningKey(key: SigningKey, enabled: boolean) { return action('key:' + key.id, `/api/v1/extensions/signing-keys/${key.id}/${enabled ? 'enable' : 'revoke'}`, enabled ? '签名密钥已启用' : '签名密钥已撤销') }
function openPackage() { if (!allowed.value) return; cancel('package'); formPackage.signerKeyId = signingKeys.value.find(item => item.enabled)?.keyId ?? ''; packageVisible.value = true }
function savePackage() { return save('package', '/api/v1/extensions/plugins/packages', 'POST', { ...formPackage }, '插件清单已校验并登记为停用状态') }
function changePlugin(plugin: Plugin, enabled: boolean) { return action('plugin:' + plugin.id, `/api/v1/extensions/plugins/${plugin.id}/${enabled ? 'enable' : 'disable'}`, enabled ? '插件已启用' : '插件已停用') }
function resetAdapter(type = 'OIDC') {
  const definition = definitions.value.find(item => item.type === type)
  formAdapter.adapterType = type; formAdapter.displayName = ''; formAdapter.endpoint = ''; formAdapter.enabled = false
  formAdapter.configurationJson = JSON.stringify(Object.fromEntries((definition?.requiredFields ?? []).map(field => [field, ''])), null, 2)
}
function openAdapter(adapter?: Adapter) {
  if (!allowed.value) return
  cancel('adapter'); editingAdapterId.value = adapter?.id ?? null
  if (adapter) Object.assign(formAdapter, { adapterType: adapter.adapterType, displayName: adapter.displayName, endpoint: adapter.endpoint, enabled: adapter.enabled, configurationJson: '' })
  else resetAdapter()
  adapterVisible.value = true
}
function adapterTypeChanged(type: unknown) { if (!editingAdapterId.value) resetAdapter(String(type)) }
function saveAdapter(): Promise<boolean> {
  try {
    const configuration: unknown = editingAdapterId.value && !formAdapter.configurationJson.trim() ? null : JSON.parse(formAdapter.configurationJson)
    if (configuration !== null && (typeof configuration !== 'object' || Array.isArray(configuration) || Object.values(configuration).some(value => typeof value !== 'string'))) throw new Error('配置必须是字符串字段的 JSON 对象')
    if (configuration === null && !editingAdapterId.value) throw new Error('新建适配器必须填写完整配置')
    const id = editingAdapterId.value, input = { adapterType: formAdapter.adapterType, displayName: formAdapter.displayName, endpoint: formAdapter.endpoint, configuration, enabled: formAdapter.enabled }
    return save('adapter', '/api/v1/extensions/adapters' + (id ? '/' + id : ''), id ? 'PUT' : 'POST', input, '企业适配器配置已保存')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败'); return Promise.resolve(false) }
}
function removeAdapter(adapter: Adapter) { return action('adapter:' + adapter.id, `/api/v1/extensions/adapters/${adapter.id}`, '企业适配器已删除', 'DELETE') }
onBeforeUnmount(() => { disposed = true; loadSequence++; keySequence++; cancel('key'); cancel('package'); cancel('adapter') })
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="TRUSTED EXTENSIONS" title="插件中心" description="以签名清单、最小权限和可逆生命周期管理扩展；控制面不会直接加载或执行第三方插件代码。" :action="allowed ? '安装签名插件' : undefined" @action="openPackage" />
    <a-alert v-if="loadError" type="error">{{ loadError }} <a-button type="text" @click="load">重试</a-button></a-alert>
    <a-alert v-else-if="!loading && !allowed" type="info">插件和企业适配器由当前服务的系统管理员维护；当前账号可查看登记信息。</a-alert>
    <a-alert v-if="isLocalDesktop()" type="info">本机拥有者管理此工作区的签名信任、插件登记和加密适配器配置；这些服务级配置不会迁入账号。插件清单登记与启用不会自动下载或执行第三方代码，实际接入仍需对应的独立工作进程。</a-alert>
    <a-alert v-if="allowed && keyError" type="error">{{ keyError }} <a-button type="text" @click="loadKeys">重读签名密钥</a-button></a-alert>
    <a-button type="text" :loading="loading" @click="load">刷新插件与适配器</a-button>
    <section class="panel safety-note"><icon-safe /><div><strong>受控扩展边界</strong><p>升级后的插件会自动保持停用状态，需管理员复核后再启用。凭据仅作为加密适配器配置保存，不会在列表、审计详情或浏览器中回显。</p></div></section>
    <a-spin :loading="loading">
      <section class="panel data-panel">
        <div class="section-head"><div><span class="eyebrow">PLUGIN REGISTRY</span><h3>已安装插件</h3></div><a-space v-if="allowed"><a-button @click="openPackage"><icon-upload /> 安装签名包</a-button><a-button type="outline" @click="openKeyManager"><icon-key /> 管理签名密钥</a-button></a-space></div>
        <a-table :data="plugins" :pagination="false" row-key="id">
          <template #columns>
            <a-table-column title="插件" data-index="displayName"><template #cell="{ record }"><strong>{{ record.displayName }}</strong><small>{{ record.pluginKey }} · {{ record.pluginType }}</small></template></a-table-column>
            <a-table-column title="版本"><template #cell="{ record }"><code>{{ record.activeVersion }}</code></template></a-table-column>
            <a-table-column title="最小权限"><template #cell="{ record }"><a-space wrap><a-tag v-for="permission in record.permissions" :key="permission" size="small">{{ permission }}</a-tag></a-space></template></a-table-column>
            <a-table-column title="状态"><template #cell="{ record }"><a-badge :status="record.status === 'ENABLED' ? 'success' : 'normal'" :text="record.status === 'ENABLED' ? '已启用' : '已停用'" /></template></a-table-column>
            <a-table-column title="更新时间"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column>
            <a-table-column title="操作" :width="110"><template #cell="{ record }"><a-popconfirm v-if="allowed" :content="record.status === 'ENABLED' ? '停用此插件登记，确认继续？' : '启用此插件登记的声明权限，确认继续？此操作不会下载或执行插件代码。'" @ok="changePlugin(record, record.status !== 'ENABLED')"><a-button type="text" :loading="pending.has('plugin:' + record.id)" :status="record.status === 'ENABLED' ? 'warning' : 'success'">{{ record.status === 'ENABLED' ? '停用' : '启用' }}</a-button></a-popconfirm><span v-else>仅管理员</span></template></a-table-column>
          </template>
        </a-table>
        <a-empty v-if="!plugins.length" description="尚未安装可信插件；先登记 Ed25519 公钥，再上传带签名的 JSON 清单。" />
      </section>

      <section class="adapter-area">
        <div class="adapter-intro"><div><span class="eyebrow">ENTERPRISE ADAPTERS</span><h3>企业系统适配器</h3><p>身份认证、通知与缺陷系统均通过声明式适配器配置接入，网络调用须由独立部署的适配器工作进程承接。</p></div><a-button v-if="allowed" type="primary" @click="openAdapter()"><icon-plus /> 新建适配器</a-button></div>
        <div class="adapter-grid">
          <article v-for="adapter in adapters" :key="adapter.id" class="adapter-card"><div class="adapter-card-head"><a-tag color="arcoblue">{{ adapter.adapterType }}</a-tag><a-badge :status="adapter.enabled ? 'success' : 'normal'" :text="adapter.enabled ? '已启用' : '已停用'" /></div><strong>{{ adapter.displayName }}</strong><code>{{ adapter.endpoint }}</code><small>已配置字段：{{ adapter.configuredFields.join('、') || '—' }}</small><div v-if="allowed"><a-button type="text" size="small" @click="openAdapter(adapter)">编辑</a-button><a-popconfirm content="删除后需要重新配置，确定继续？" @ok="removeAdapter(adapter)"><a-button type="text" status="danger" size="small" :loading="pending.has('adapter:' + adapter.id)">删除</a-button></a-popconfirm></div></article>
          <article v-for="definition in definitions" v-show="!adapters.some(adapter => adapter.adapterType === definition.type)" :key="definition.type" class="adapter-card adapter-available"><div class="adapter-card-head"><a-tag color="gray">{{ definition.category }}</a-tag><span>未配置</span></div><strong>{{ definition.displayName }}</strong><small>{{ definition.description }}</small><small>必填：{{ definition.requiredFields.join('、') }}</small><a-button v-if="allowed" type="outline" size="small" @click="openAdapter(); resetAdapter(definition.type)">配置</a-button></article>
        </div>
      </section>
    </a-spin>

    <a-drawer v-if="allowed" v-model:visible="keyManagerVisible" title="可信签名密钥" :width="640" :footer="false"><div class="key-toolbar"><small>公钥登记后不再回显；撤销只阻止新安装，不会静默改变已安装插件。</small><a-button type="primary" size="small" @click="openKey"><icon-plus /> 登记密钥</a-button></div><a-alert v-if="!allowed" type="warning" :show-icon="true">仅系统管理员可查看、登记或撤销签名密钥。</a-alert><a-alert v-if="keyError" type="error">{{ keyError }} <a-button type="text" @click="loadKeys">重读签名密钥</a-button></a-alert><a-table v-if="allowed" :data="signingKeys" :pagination="false" row-key="id"><template #columns><a-table-column title="密钥"><template #cell="{ record }"><strong>{{ record.displayName }}</strong><small class="key-meta">{{ record.keyId }}</small></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-badge :status="record.enabled ? 'success' : 'normal'" :text="record.enabled ? '可用于安装' : '已撤销'" /></template></a-table-column><a-table-column title="登记时间"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-popconfirm :content="record.enabled ? '撤销密钥只阻止新安装，已登记插件保持原状态，确认继续？' : '重新信任此签名密钥，确认继续？'" @ok="changeSigningKey(record, !record.enabled)"><a-button type="text" :loading="pending.has('key:' + record.id)" :status="record.enabled ? 'warning' : 'success'">{{ record.enabled ? '撤销' : '启用' }}</a-button></a-popconfirm></template></a-table-column></template></a-table><a-empty v-if="allowed && !signingKeys.length" description="尚未登记可信签名密钥" /></a-drawer>
    <a-modal v-if="allowed" v-model:visible="keyVisible" title="登记可信 Ed25519 签名密钥" width="620px" :ok-loading="saving.key" :on-before-ok="saveKey" @cancel="cancel('key')"><a-alert type="warning" :show-icon="true">只接受 X.509 格式的 Base64 Ed25519 公钥。登记后公钥不再回显；撤销会阻止新插件包使用该密钥。</a-alert><a-form :model="formKey" layout="vertical" class="modal-form"><a-form-item label="密钥标识" required><a-input v-model="formKey.keyId" placeholder="acme.release.2026" /></a-form-item><a-form-item label="显示名称" required><a-input v-model="formKey.displayName" placeholder="Acme 发布签名密钥" /></a-form-item><a-form-item label="公钥 Base64" required><a-textarea v-model="formKey.publicKeyBase64" :auto-size="{ minRows: 4, maxRows: 6 }" /></a-form-item></a-form></a-modal>
    <a-modal v-if="allowed" v-model:visible="packageVisible" title="安装已签名插件包" width="720px" :ok-loading="saving.package" :on-before-ok="savePackage" @cancel="cancel('package')"><a-alert type="info" :show-icon="true">签名覆盖下方 JSON 的原始字符；格式化、空白变更或字段调整都会使签名失效。新装和升级后的插件均为停用状态。</a-alert><a-form :model="formPackage" layout="vertical" class="modal-form"><a-form-item label="可信签名密钥" required><a-select v-model="formPackage.signerKeyId" placeholder="选择已启用密钥"><a-option v-for="key in signingKeys.filter(item => item.enabled)" :key="key.keyId" :value="key.keyId">{{ key.displayName }} · {{ key.keyId }}</a-option></a-select><small v-if="!allowed">当前账号无系统管理员权限，无法读取或使用签名密钥。</small></a-form-item><a-form-item label="插件 JSON 清单" required><a-textarea v-model="formPackage.manifestJson" :auto-size="{ minRows: 11, maxRows: 16 }" /></a-form-item><a-form-item label="Ed25519 签名 Base64" required><a-textarea v-model="formPackage.signatureBase64" :auto-size="{ minRows: 3, maxRows: 5 }" /></a-form-item></a-form></a-modal>
    <a-modal v-if="allowed" v-model:visible="adapterVisible" :title="editingAdapterId ? '编辑企业适配器' : '新建企业适配器'" width="640px" :ok-loading="saving.adapter" :on-before-ok="saveAdapter" @cancel="cancel('adapter')"><a-alert type="info" :show-icon="true">控制面只校验和加密保存配置。启用后应由隔离的适配器进程执行目录同步、通知或缺陷操作。</a-alert><a-form :model="formAdapter" layout="vertical" class="modal-form"><a-form-item label="适配器类型" required><a-select v-model="formAdapter.adapterType" :disabled="!!editingAdapterId" @change="adapterTypeChanged"><a-option v-for="definition in definitions" :key="definition.type" :value="definition.type">{{ definition.displayName }} · {{ definition.category }}</a-option></a-select></a-form-item><a-form-item label="名称" required><a-input v-model="formAdapter.displayName" placeholder="企业 OIDC" /></a-form-item><a-form-item label="服务地址" required :extra="formAdapter.adapterType === 'LDAP' ? '仅接受标准端口的 LDAPS 地址。' : '仅接受标准端口的 HTTPS 地址。'"><a-input v-model="formAdapter.endpoint" :placeholder="formAdapter.adapterType === 'LDAP' ? 'ldaps://directory.example.com' : 'https://service.example.com'" /></a-form-item><a-form-item label="加密配置 JSON" :required="!editingAdapterId" :extra="editingAdapterId ? '留空保留现有加密配置；填写则整体替换，请提交完整配置。' : `必填：${selectedDefinition?.requiredFields.join('、') ?? '—'}；保存后仅显示字段名。`"><a-textarea v-model="formAdapter.configurationJson" :auto-size="{ minRows: 7, maxRows: 11 }" /></a-form-item><a-form-item field="enabled"><a-switch v-model="formAdapter.enabled" /> <span class="switch-label">启用配置（仍需部署对应的隔离适配器工作进程）</span></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.safety-note{display:flex;gap:12px;align-items:flex-start;margin-bottom:20px;background:linear-gradient(120deg,rgb(var(--primary-1)),#fff)}.safety-note svg{margin-top:2px;color:rgb(var(--primary-6));font-size:20px}.safety-note strong{display:block;margin-bottom:4px}.safety-note p{margin:0;color:var(--color-text-3);font-size:13px;line-height:1.6}.section-head,.adapter-intro,.key-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.section-head h3,.adapter-intro h3{margin:3px 0 0;font-size:18px}.section-head small,.key-toolbar small{display:block;color:var(--color-text-3)}.key-toolbar{align-items:center;margin-bottom:14px}.key-toolbar small{line-height:1.5}.adapter-area{margin-top:22px}.adapter-intro{margin-bottom:12px}.adapter-intro>div{max-width:690px}.adapter-intro p{margin:6px 0 0;color:var(--color-text-3);font-size:13px;line-height:1.6}.adapter-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.adapter-card{display:grid;align-content:start;gap:10px;min-height:192px;padding:15px;border:1px solid var(--color-border-2);border-radius:10px;background:#fff;box-shadow:0 5px 18px rgba(25,57,103,.04)}.adapter-card-head{display:flex;justify-content:space-between;gap:8px;align-items:center}.adapter-card strong{font-size:15px}.adapter-card code{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.adapter-card small,.key-meta{color:var(--color-text-3);font-size:12px;line-height:1.55}.key-meta{display:block;margin-top:4px}.adapter-card>div:last-child{align-self:end}.adapter-available{border-style:dashed;background:var(--color-fill-1)}.adapter-available span{font-size:12px;color:var(--color-text-3)}.modal-form{margin-top:14px}.modal-form small{display:block;margin-top:6px;color:var(--color-text-3);font-size:12px}.switch-label{margin-left:8px;color:var(--color-text-2);font-size:13px}@media (max-width:1100px){.adapter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media (max-width:720px){.section-head,.adapter-intro,.key-toolbar{align-items:stretch;flex-direction:column}.adapter-intro>a-button,.key-toolbar>a-button{width:100%}.adapter-grid{grid-template-columns:1fr}}
</style>
