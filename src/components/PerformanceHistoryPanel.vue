<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import PerformanceExecutionChart from '@/components/PerformanceExecutionChart.vue'
import PerformanceResourceChart from '@/components/PerformanceResourceChart.vue'
import PerformanceHistoryArtifacts from '@/components/PerformanceHistoryArtifacts.vue'
interface HistoryEntry { key: string; revision: string; nativeRecord: boolean; planName: string; status: string; requestedAt: string; finishedAt: string | null }
interface HistoryReport {
  planName: string; planVersion: number; status: string; statusCode: string | null
  requestedAt: string; deadlineAt: string; startedAt: string | null; finishedAt: string | null
  summary: { sampleCount: number; successCount: number; errorCount: number; errorRatePercent: number; peakThroughputPerSecond: number; averageLatencyMillis: number; p95LatencyMillis: number; minLatencyMillis: number; maxLatencyMillis: number; peakActiveThreads: number; firstSampleAt: string | null; lastSampleAt: string | null }
  quality: { status: string; configuredChecks: number; passedChecks: number; failedChecks: number; inconclusiveChecks: number; threadGroups: Array<{threadGroupName: string; status: string; sampleCount: number; p95LatencyMillis: number; errorRatePercent: number; p95ThresholdMillis: number | null; errorThresholdPercent: number | null}> }
  metrics: Array<{threadGroupName: string; sampleCount: number; successCount: number; errorCount: number; activeThreads: number; throughputPerSecond: number; averageLatencyMillis: number; p95LatencyMillis: number; minLatencyMillis: number; maxLatencyMillis: number; occurredAt: string}>
  resources: Array<{cpuPercent: number; residentMemoryBytes: number; occurredAt: string}>
}
interface HistoryItem { key: string; revision: string; sha256: string; report: HistoryReport }
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ count: [value: number] }>()
const entries = ref<HistoryEntry[]>([]), loading = ref(false), error = ref('')
const selected = ref<HistoryItem | null>(null), detailLoading = ref(false), detailError = ref(''), visible = ref(false)
let sequence = 0, detailSequence = 0
const statuses: Record<string, string> = { SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '已超时', REJECTED: '已拒绝' }
const date = (v: string | null) => v ? new Date(v).toLocaleString() : '—'
const url = computed(() => selected.value && props.projectId ? `/api/v1/projects/${encodeURIComponent(props.projectId)}/performance/history/${encodeURIComponent(selected.value.key)}/export` : '')
async function read<T>(route: string): Promise<T> {
  const response = await fetch(route, { credentials: 'include' })
  const value = await response.json()
  if (!response.ok) throw new Error(value.message || `历史服务请求失败（HTTP ${response.status}）`)
  return value.data
}
async function load() {
  const token = ++sequence, project = props.projectId
  loading.value = Boolean(project); error.value = ''
  if (!project) { entries.value = []; emit('count', 0); return }
  try {
    const value = await read<{ entries: HistoryEntry[] }>(`/api/v1/projects/${encodeURIComponent(project)}/performance/history`)
    if (token !== sequence || project !== props.projectId) return
    if (!Array.isArray(value.entries)) throw new Error('历史服务返回的数据不完整')
    entries.value = value.entries.filter(entry => !entry.nativeRecord).sort((a, b) => b.requestedAt.localeCompare(a.requestedAt) || b.key.localeCompare(a.key))
    emit('count', entries.value.length)
  } catch (failure) { if (token === sequence) { error.value = failure instanceof Error ? failure.message : '无法加载同步历史'; entries.value = []; emit('count', 0) } }
  finally { if (token === sequence) loading.value = false }
}
async function show(entry: HistoryEntry) {
  const token = ++detailSequence, project = props.projectId
  selected.value = null; detailError.value = ''; detailLoading.value = true; visible.value = true
  try {
    const value = await read<HistoryItem>(`/api/v1/projects/${encodeURIComponent(project)}/performance/history/${encodeURIComponent(entry.key)}`)
    if (token === detailSequence && project === props.projectId) selected.value = value
  } catch (failure) { if (token === detailSequence) detailError.value = failure instanceof Error ? failure.message : '无法加载历史报告' }
  finally { if (token === detailSequence) detailLoading.value = false }
}
watch(() => props.projectId, () => {
  detailSequence++; visible.value = false; selected.value = null; entries.value = []; emit('count', 0); void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { sequence++; detailSequence++ })
</script>

<template>
  <section id="performance-history" class="automation-history-panel panel" aria-label="性能同步历史">
    <div class="history-heading"><div><h3>同步历史</h3><p>来自其他工作区的已结束执行；保留执行时的来源版本，报告只读。</p></div><a-button :loading="loading" :disabled="!projectId" @click="load">刷新同步历史</a-button></div>
    <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
    <a-table :data="entries" :loading="loading" :pagination="{ pageSize: 12 }" row-key="key" size="small">
      <template #columns>
        <a-table-column title="原计划"><template #cell="{ record }">{{ record.planName }}<small class="history-source">来源执行 {{ record.key.split('.')[1] }}</small></template></a-table-column>
        <a-table-column title="结果" :width="100"><template #cell="{ record }"><a-tag :color="record.status === 'SUCCEEDED' ? 'green' : 'gray'">{{ statuses[record.status] || record.status }}</a-tag></template></a-table-column>
        <a-table-column title="原提交时间"><template #cell="{ record }">{{ date(record.requestedAt) }}</template></a-table-column>
        <a-table-column title="原结束时间"><template #cell="{ record }">{{ date(record.finishedAt) }}</template></a-table-column>
        <a-table-column title="操作" :width="100"><template #cell="{ record }"><a-button type="text" @click="show(record)">查看历史</a-button></template></a-table-column>
      </template>
    </a-table>
    <a-drawer v-model:visible="visible" title="只读执行历史" :width="760" :footer="false" unmount-on-close>
      <a-spin :loading="detailLoading">
        <a-alert v-if="detailError" type="error" role="alert">{{ detailError }}</a-alert>
        <template v-if="selected">
          <a-alert type="info">以下为来源执行的脱敏快照。来源计划版本可能与当前工作区不同；该历史不会创建或取消 Runner 任务。</a-alert>
          <a-descriptions :column="2" bordered class="history-facts">
            <a-descriptions-item label="原计划">{{ selected.report.planName }}</a-descriptions-item>
            <a-descriptions-item label="来源计划版本">v{{ selected.report.planVersion }}</a-descriptions-item>
            <a-descriptions-item label="结果">{{ statuses[selected.report.status] || selected.report.status }}</a-descriptions-item>
            <a-descriptions-item label="结果代码">{{ selected.report.statusCode || '—' }}</a-descriptions-item>
            <a-descriptions-item label="原提交时间">{{ date(selected.report.requestedAt) }}</a-descriptions-item>
            <a-descriptions-item label="原开始时间">{{ date(selected.report.startedAt) }}</a-descriptions-item>
            <a-descriptions-item label="原结束时间">{{ date(selected.report.finishedAt) }}</a-descriptions-item>

            <a-descriptions-item label="来源项目" :span="2"><code>{{ selected.key.split('.')[0] }}</code></a-descriptions-item>
            <a-descriptions-item label="来源执行" :span="2"><code>{{ selected.key.split('.')[1] }}</code></a-descriptions-item>
            <a-descriptions-item label="报告内容 SHA-256" :span="2"><code>{{ selected.sha256 }}</code></a-descriptions-item>
          </a-descriptions>
          <p>样本 {{ selected.report.summary.sampleCount }}：成功 {{ selected.report.summary.successCount }}，错误 {{ selected.report.summary.errorCount }}；错误率 {{ selected.report.summary.errorRatePercent }}%。</p>
          <p>峰值 TPS {{ selected.report.summary.peakThroughputPerSecond }}；P95 {{ selected.report.summary.p95LatencyMillis }} ms；质量结论 {{ selected.report.quality.status }}。</p>
          <p class="history-note">保留执行时的计划版本、结果、时间和数值指标。指标及资源采样沿用原报告各最多 2000 条范围；不包含请求地址、请求头或正文、数据集、密钥、日志或 JTL。历史不加入原有质量统计。</p>
          <a :href="url" download>下载脱敏历史报告</a>
          <PerformanceHistoryArtifacts :project-id="projectId" :history-key="selected.key" />
          <PerformanceExecutionChart :metrics="selected.report.metrics" />
          <PerformanceResourceChart :resources="selected.report.resources" />
          <h4>来源质量阈值</h4><a-table :data="selected.report.quality.threadGroups" :pagination="{ pageSize: 10 }" size="small">
            <template #columns><a-table-column title="线程组" data-index="threadGroupName" /><a-table-column title="样本" data-index="sampleCount" /><a-table-column title="P95 ms" data-index="p95LatencyMillis" /><a-table-column title="错误率 %" data-index="errorRatePercent" /><a-table-column title="P95 阈值" data-index="p95ThresholdMillis" /><a-table-column title="错误率阈值" data-index="errorThresholdPercent" /><a-table-column title="结论" data-index="status" /></template>
          </a-table>
          <details><summary>数值指标与资源采样</summary><pre class="history-json">{{ JSON.stringify({ quality: selected.report.quality, metrics: selected.report.metrics, resources: selected.report.resources }, null, 2) }}</pre></details>
        </template>
      </a-spin>
    </a-drawer>
  </section>
</template>

<style scoped>
.automation-history-panel { margin-top: 24px; border-top: 1px solid var(--color-border-2); padding-top: 20px; }
.history-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.history-heading h3 { margin: 0 0 6px; }.history-heading p,.history-source,.history-note { color: var(--color-text-3); font-size: 12px; }
.history-source { display: block; margin-top: 4px; overflow-wrap: anywhere; }.history-facts { margin-top: 16px; }
code { overflow-wrap: anywhere; }.history-json { overflow: auto; max-height: 28rem; font-size: 12px; }
@media (max-width: 760px) { .history-heading { align-items: flex-start; flex-direction: column; } }
</style>
