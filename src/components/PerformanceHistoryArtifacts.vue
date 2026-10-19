<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
interface Artifact { key: string; sourceArtifactId: string; artifactType: 'JMETER_JTL' | 'JMETER_LOG'; originalName: string; sizeBytes: number; checksum: string; archivedAt: string }
const props = defineProps<{ projectId: string; historyKey: string }>()
const files = ref<Artifact[]>([]), loading = ref(false), error = ref('')
let sequence = 0
const download = (file: Artifact) => `/api/v1/projects/${encodeURIComponent(props.projectId)}/performance/artifacts/${encodeURIComponent(file.key)}/download`
const size = (value: number) => value < 1024 ? `${value} B` : value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KiB` : `${(value / 1024 / 1024).toFixed(1)} MiB`
async function load() {
  const token = ++sequence, project = props.projectId, history = props.historyKey
  files.value = []; error.value = ''; loading.value = Boolean(project && history)
  if (!project || !history) return
  try {
    const response = await fetch(`/api/v1/projects/${encodeURIComponent(project)}/performance/history/${encodeURIComponent(history)}/artifacts`, { credentials: 'include' })
    const body = await response.json()
    if (!response.ok) throw new Error(body.message || `原始工件读取失败（HTTP ${response.status}）`)
    if (token !== sequence || project !== props.projectId || history !== props.historyKey) return
    const value = body.data
    if (!Array.isArray(value) || value.length > 2 || value.some(f => !f || !['JMETER_JTL','JMETER_LOG'].includes(f.artifactType)
      || f.key !== history + '.' + f.artifactType || f.originalName !== (f.artifactType === 'JMETER_JTL' ? 'results.jtl' : 'jmeter.log')
      || !Number.isSafeInteger(f.sizeBytes) || f.sizeBytes < 0 || f.sizeBytes > 20 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(f.checksum))
      || new Set(value.map(f => f.key)).size !== value.length) throw new Error('原始工件列表不完整，请刷新后核对')
    files.value = value
  } catch (failure) { if (token === sequence) error.value = failure instanceof Error ? failure.message : '无法加载原始工件' }
  finally { if (token === sequence) loading.value = false }
}
watch(() => [props.projectId, props.historyKey], () => { void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <section class="history-artifacts" aria-label="原始 JTL／日志">
    <div class="artifacts-heading"><h4>原始 JTL／日志</h4><a-button size="small" :loading="loading" :disabled="!projectId || !historyKey" @click="load">刷新原始工件</a-button></div>
    <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
    <a-spin :loading="loading">
      <p v-if="!loading && !error && !files.length" class="artifacts-note">当前历史尚无原始工件。可在桌面单独绑定“原始 JTL／日志同步”。</p>
      <template v-if="files.length">
        <p class="artifacts-note">文件保留来源的完整原始内容，可能含敏感信息。每次传输和下载均校验字节长度与 SHA-256。</p>
        <ul class="artifact-list"><li v-for="file in files" :key="file.key"><a :href="download(file)" download>{{ file.artifactType === 'JMETER_JTL' ? '下载原始 JTL' : '下载原始日志' }}</a><span>{{ size(file.sizeBytes) }} · {{ new Date(file.archivedAt).toLocaleString() }}</span><code>SHA-256 {{ file.checksum }}</code></li></ul>
      </template>
    </a-spin>
  </section>
</template>
<style scoped>
.history-artifacts { margin: 20px 0; border-top: 1px solid var(--color-border-2); padding-top: 12px; }
.artifacts-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.artifacts-heading h4 { margin: 0; }
.artifacts-note,.artifact-list span { font-size: 12px; color: var(--color-text-3); }.artifact-list { list-style: none; padding: 0; }.artifact-list li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin: 12px 0; }.artifact-list code { width: 100%; overflow-wrap: anywhere; font-size: 11px; }
</style>
