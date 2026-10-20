<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { isLocalDesktop, isAccountDesktop, openApiDebugHistorySync, openApiDebugReplaySync } from '@/utils/desktopWorkspace'
interface Entry { key: string; revision: string; nativeRecord: boolean; method: string; protocol: string; result: string; target: string; createdAt: string }
interface Item { key: string; revision: string; sha256: string; payload: Record<string, unknown> }
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ load: [template: string] }>()
const visible = ref(false), mode = ref('history'), entries = ref<Entry[]>([]), loading = ref(false), error = ref(''), detail = ref<Item | null>(null)
let sequence = 0
const desktop = isLocalDesktop() || isAccountDesktop()
const route = (project: string, kind: string) => `/api/v1/projects/${encodeURIComponent(project)}/api-debug/${kind}/archive`
async function read<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: 'include' }), value = await response.json()
  if (!response.ok) throw new Error(value.message || `同步记录请求失败（HTTP ${response.status}）`)
  return value.data
}
async function load() {
  const token = ++sequence, project = props.projectId, kind = mode.value
  entries.value = []; detail.value = null; error.value = ''; loading.value = Boolean(project)
  if (!project) return
  try {
    const value = await read<{ entries: Entry[] }>(route(project, kind))
    if (token !== sequence || project !== props.projectId) return
    if (!Array.isArray(value.entries)) throw new Error('同步索引不完整')
    entries.value = value.entries.filter(v => !v.nativeRecord).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.key.localeCompare(a.key))
  } catch (failure) { if (token === sequence) error.value = failure instanceof Error ? failure.message : '无法加载同步记录' }
  finally { if (token === sequence) loading.value = false }
}
async function show(entry: Entry) {
  const token = ++sequence, project = props.projectId, kind = mode.value
  detail.value = null; error.value = ''; loading.value = true
  try {
    const value = await read<Item>(route(project, kind) + '/' + encodeURIComponent(entry.key))
    if (token === sequence && project === props.projectId) detail.value = value
  } catch (failure) { if (token === sequence) error.value = failure instanceof Error ? failure.message : '无法加载记录' }
  finally { if (token === sequence) loading.value = false }
}
async function openSync() {
  try { await (mode.value === 'history' ? openApiDebugHistorySync() : openApiDebugReplaySync()) }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '无法打开同步绑定' }
}
function useTemplate() {
  if (!detail.value || mode.value !== 'replay-drafts' || !detail.value.payload.template) return
  emit('load', JSON.stringify(detail.value.payload.template)); visible.value = false
}
watch(mode, () => { if (visible.value) void load() })
watch(() => props.projectId, () => { sequence++; entries.value = []; detail.value = null; error.value = ''; loading.value = false; visible.value = false }, { flush: 'sync' })
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <a-button type="text" size="small" :disabled="!projectId" @click="visible = true; load()">同步历史与模板</a-button>
  <a-drawer v-model:visible="visible" title="同步历史与脱敏模板" :width="800" :footer="false" @cancel="sequence++; loading = false">
    <a-alert>历史摘要与模板分别确认绑定。副本只读，不计为新执行；载入模板不会发送。本地原稿保留。</a-alert>
    <a-radio-group v-model="mode" type="button" class="archive-toolbar"><a-radio value="history">历史摘要</a-radio><a-radio value="replay-drafts">脱敏模板</a-radio></a-radio-group>
    <div class="archive-toolbar"><a-button :loading="loading" @click="load">刷新同步记录</a-button><a-button v-if="desktop" @click="openSync">{{ mode === 'history' ? '绑定历史摘要同步' : '绑定脱敏模板同步' }}</a-button></div>
    <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
    <a-table :data="entries" :loading="loading" :pagination="{ pageSize: 10 }" row-key="key" size="small">
      <template #columns>
        <a-table-column title="来源记录"><template #cell="{ record }"><code>{{ record.key }}</code></template></a-table-column>
        <a-table-column title="协议"><template #cell="{ record }">{{ record.protocol }} · {{ record.method }}</template></a-table-column>
        <a-table-column title="结果" data-index="result" />
        <a-table-column title="原时间"><template #cell="{ record }">{{ new Date(record.createdAt).toLocaleString() }}</template></a-table-column>
        <a-table-column title="操作"><template #cell="{ record }"><a-button type="text" @click="show(record)">查看同步记录</a-button></template></a-table-column>
      </template>
    </a-table>
    <section v-if="detail" class="archive-detail" aria-label="只读同步记录">
      <p>来源：<code>{{ detail.key }}</code></p><p>SHA-256：<code>{{ detail.sha256 }}</code></p>
      <template v-if="mode === 'replay-drafts'"><a-alert type="warning">模板仍可能包含业务请求内容。请补齐占位符，重新选择环境、文件和 gRPC 描述符，并核对目标后手动发送。</a-alert><a-button class="archive-toolbar" @click="useTemplate">载入脱敏模板</a-button></template>
      <a :href="route(projectId, mode) + '/' + encodeURIComponent(detail.key) + '/export'" download>下载只读同步记录</a>
      <pre>{{ JSON.stringify(detail.payload, null, 2) }}</pre>
    </section>
  </a-drawer>
</template>
<style scoped>
.archive-toolbar{display:flex;gap:12px;margin:16px 0}.archive-detail{margin-top:20px}code{overflow-wrap:anywhere;font-size:11px}pre{max-height:360px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}
</style>
