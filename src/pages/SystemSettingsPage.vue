<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Config { key: string; value: string; secret: boolean; updatedBy: string; updatedAt: string }

const configs = ref<Config[]>([])
const loading = ref(true)
const visible = ref(false)
const editingKey = ref<string | null>(null)
const form = reactive({ key: '', value: '' })
const privateNetworkSaving = ref(false)
const PRIVATE_NETWORK_CONFIG = 'api-debug.allow-configured-private-targets'
const privateNetworkEnabled = computed(() => configs.value.find((item) => item.key === PRIVATE_NETWORK_CONFIG)?.value === 'true')
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
async function load() { loading.value = true; try { configs.value = await read<Config[]>('/api/v1/system-config') } catch (error) { Message.error(error instanceof Error ? error.message : '加载系统配置失败') } finally { loading.value = false } }
function create() { editingKey.value = null; form.key = ''; form.value = ''; visible.value = true }
function edit(config: Config) { editingKey.value = config.key; form.key = config.key; form.value = config.value; visible.value = true }
async function save() { try { await mutate('/api/v1/system-config', 'POST', form); visible.value = false; await load(); Message.success(editingKey.value ? '系统配置已更新' : '系统配置已创建') } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') } }
async function remove(config: Config) { try { await mutate(`/api/v1/system-config/${encodeURIComponent(config.key)}`, 'DELETE'); await load(); Message.success('系统配置已删除') } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') } }
async function setPrivateNetworkEnabled(value: boolean | string | number) {
  const enabled = value === true || value === 'true' || value === 1
  privateNetworkSaving.value = true
  try {
    await mutate('/api/v1/system-config', 'POST', { key: PRIVATE_NETWORK_CONFIG, value: String(enabled) })
    await load()
    Message.success(enabled ? '在线 HTTP 私网调试已启用' : '在线 HTTP 私网调试已关闭')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '更新私网调试策略失败')
  } finally {
    privateNetworkSaving.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader eyebrow="SYSTEM ADMINISTRATION" title="系统配置" description="仅系统管理员可维护的非敏感平台配置。敏感凭据应使用部署环境变量或项目环境密钥。" action="新建配置" @action="create" />
    <section class="panel config-note"><icon-info-circle-fill /><div><strong>配置边界</strong><p>此处仅保存非敏感配置。密钥、令牌和连接密码不会在系统配置中存储或回显。</p></div></section>
    <section class="panel network-policy">
      <div>
        <strong>在线 HTTP 私网调试</strong>
        <p>启用后，接口地址或项目环境变量可使用任意 RFC1918/ULA 私网 IP 和端口。回环、链路本地、云元数据、组播及保留地址仍永久禁止；为避免 DNS 重绑定，在线调试不放行解析到私网的域名。</p>
        <small>配置键：{{ PRIVATE_NETWORK_CONFIG }}。此开关会扩大服务端网络访问范围，请仅在受信任的私有化部署中启用。</small>
      </div>
      <a-switch :model-value="privateNetworkEnabled" :loading="privateNetworkSaving" type="round" @change="setPrivateNetworkEnabled" />
    </section>
    <section class="panel data-panel"><a-spin :loading="loading"><a-table :data="configs" :pagination="false" row-key="key"><template #columns><a-table-column title="配置键" data-index="key"><template #cell="{ record }"><strong>{{ record.key }}</strong></template></a-table-column><a-table-column title="配置值" data-index="value"><template #cell="{ record }"><code class="config-value">{{ record.value }}</code></template></a-table-column><a-table-column title="更新时间" data-index="updatedAt"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column><a-table-column title="操作"><template #cell="{ record }"><a-space><a-button type="text" @click="edit(record)">编辑</a-button><a-button type="text" status="danger" @click="remove(record)">删除</a-button></a-space></template></a-table-column></template></a-table></a-spin></section>
    <a-modal v-model:visible="visible" :title="editingKey ? '编辑系统配置' : '新建系统配置'" width="560px" @ok="save"><a-form :model="form" layout="vertical"><a-form-item label="配置键" required extra="小写字母开头，支持数字、点、下划线和连字符。"><a-input v-model="form.key" placeholder="platform.timezone" :disabled="!!editingKey" /></a-form-item><a-form-item label="配置值" required><a-textarea v-model="form.value" placeholder="Asia/Shanghai" :auto-size="{ minRows: 3, maxRows: 6 }" /></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.config-note{display:flex;gap:12px;align-items:flex-start;margin-bottom:16px;background:rgb(var(--primary-1));color:var(--color-text-2)}.config-note svg{color:rgb(var(--primary-6));font-size:20px;margin-top:2px}.config-note strong{display:block;margin-bottom:4px}.config-note p{margin:0;font-size:13px}.network-policy{display:flex;align-items:center;justify-content:space-between;gap:24px;margin-bottom:20px;border-color:rgb(var(--orange-3));background:rgb(var(--orange-1))}.network-policy>div{display:grid;gap:5px}.network-policy strong{color:var(--color-text-1)}.network-policy p{margin:0;color:var(--color-text-2);font-size:13px;line-height:1.7}.network-policy small{color:var(--color-text-3)}.network-policy :deep(.arco-switch){flex:0 0 auto}.config-value{display:inline-block;max-width:420px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:4px 7px;background:var(--color-fill-2);border-radius:4px;color:var(--color-text-2)}
</style>
