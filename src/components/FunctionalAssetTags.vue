<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { toApiRequestError } from '@/composables/useApiFormErrors'

const props = defineProps<{ projectId: string }>()
type AssetType = 'REQUIREMENT' | 'CASE' | 'PLAN'
interface Asset { id: string; requirementCode?: string; caseCode?: string; planCode?: string; title?: string; name?: string }
interface Tag { id: string; name: string; color: string }
const type = ref<AssetType>('CASE'), assetId = ref(''), assets = ref<Asset[]>([]), tags = ref<Tag[]>([]), selected = ref<string[]>([])
const loading = ref(false), saving = ref(false), ready = ref(false), error = ref('')
let epoch = 0, active = true
const paths = { REQUIREMENT: 'requirements', CASE: 'cases', PLAN: 'plans' }
const current = (token: number, project: string) => active && token === epoch && project === props.projectId
async function read<T>(route: string, init?: RequestInit): Promise<T> {
  const response = await fetch(route, { credentials: 'include', ...init })
  const body = await response.json()
  if (!response.ok) throw toApiRequestError(response, body)
  return body.data
}
function failure(value: unknown) { error.value = value instanceof Error ? value.message : '读取或保存标签关联失败，请重试。' }
async function load() {
  const token = ++epoch, project = props.projectId, kind = type.value
  assets.value = []; tags.value = []; assetId.value = ''; selected.value = []; ready.value = false; error.value = ''
  if (!project) return
  loading.value = true
  try {
    const base = `/api/v1/projects/${project}/functional`
    const [rows, dictionary] = await Promise.all([read<Asset[]>(`${base}/${paths[kind]}`), read<Tag[]>(`${base}/tags`)])
    if (current(token, project)) { assets.value = rows; tags.value = dictionary }
  } catch (value) { if (current(token, project)) failure(value) }
  finally { if (current(token, project)) loading.value = false }
}
async function selectAsset() {
  const token = ++epoch, project = props.projectId, id = assetId.value, kind = type.value
  selected.value = []; ready.value = false; error.value = ''
  if (!id) { loading.value = false; return }
  loading.value = true
  try {
    const rows = await read<Tag[]>(`/api/v1/projects/${project}/functional/asset-tags?assetType=${kind}&assetId=${id}`)
    if (current(token, project)) { selected.value = rows.map(t => t.id); ready.value = true }
  } catch (value) { if (current(token, project)) failure(value) }
  finally { if (current(token, project)) loading.value = false }
}
async function save() {
  if (!ready.value || loading.value || saving.value) return
  const token = epoch, project = props.projectId, id = assetId.value, kind = type.value, tagIds = [...selected.value]
  saving.value = true; error.value = ''
  try {
    const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
    if (!current(token, project)) return
    const rows = await read<Tag[]>(`/api/v1/projects/${project}/functional/asset-tags/${kind}/${id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify({ tagIds }),
    })
    if (current(token, project)) { selected.value = rows.map(t => t.id); Message.success('标签关联已保存') }
  } catch (value) { if (current(token, project)) failure(value) }
  finally { saving.value = false }
}
watch(() => [props.projectId, type.value], load, { immediate: true })
onBeforeUnmount(() => { active = false; epoch++ })
</script>

<template>
  <section class="panel asset-tags" aria-label="资产标签关联">
    <div class="section-heading"><div><h3>资产标签关联</h3><p>选择资产后维护完整标签集合；取消全部标签并保存可解除关联。</p></div><a-button :disabled="loading || saving" @click="load">刷新资产与标签</a-button></div>
    <div class="asset-selectors">
      <label>资产类型<select v-model="type" aria-label="关联资产类型" :disabled="saving"><option value="REQUIREMENT">需求</option><option value="CASE">测试用例</option><option value="PLAN">测试计划</option></select></label>
      <label>资产<select v-model="assetId" aria-label="关联资产" :disabled="loading || saving" @change="selectAsset"><option value="">请选择资产</option><option v-for="asset in assets" :key="asset.id" :value="asset.id">{{ asset.requirementCode || asset.caseCode || asset.planCode }} · {{ asset.title || asset.name }}</option></select></label>
    </div>
    <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
    <p v-if="loading" role="status">正在读取资产标签…</p>
    <template v-else-if="ready">
      <a-checkbox-group v-model="selected" :disabled="saving" class="tag-options"><a-checkbox v-for="tag in tags" :key="tag.id" :value="tag.id"><a-tag :color="tag.color">{{ tag.name }}</a-tag></a-checkbox></a-checkbox-group>
      <p v-if="!tags.length">当前项目尚无标签，请先添加标签。</p>
      <a-button type="primary" :loading="saving" @click="save">保存标签关联</a-button>
    </template>
    <p v-else-if="!loading && !error">{{ assets.length ? '请选择资产以读取当前标签。' : '当前类型尚无资产，请先创建资产。' }}</p>
  </section>
</template>

<style scoped>
.asset-tags{padding:24px}.section-heading{display:flex;justify-content:space-between;gap:16px;align-items:center}.section-heading h3{margin:0 0 8px}.section-heading p{color:var(--color-text-3)}.asset-selectors{display:flex;flex-wrap:wrap;gap:16px;margin:16px 0}.asset-selectors label{display:flex;flex-direction:column;gap:8px;min-width:180px;flex:1}.asset-selectors select{padding:9px;border:1px solid var(--color-border-2);border-radius:4px;background:var(--color-bg-2);color:var(--color-text-1)}.tag-options{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}
</style>
