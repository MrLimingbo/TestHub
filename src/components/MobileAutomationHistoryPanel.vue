<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import MobileAutomationHistoryArtifacts from '@/components/MobileAutomationHistoryArtifacts.vue'
interface HistoryEntry { key: string; revision: string; nativeRecord: boolean; scenarioName: string; status: string; requestedAt: string; finishedAt: string | null }
interface HistoryReport {
  scenarioName:string; scenarioVersion:number; deviceName:string; platform:string; applicationName:string; status:string; statusCode:string|null
  requestedAt:string; deadlineAt:string; startedAt:string|null; finishedAt:string|null
  summary:{totalSteps:number;passedSteps:number;failedSteps:number;totalDurationMillis:number}
  steps:Array<{stepId:string;stepSequence:number;stepType:string;stepName:string;outcome:string;durationMillis:number;occurredAt:string}>
}
interface HistoryItem { key: string; revision: string; sha256: string; report: HistoryReport }
const props = defineProps<{ projectId: string }>()
const emit = defineEmits<{ count: [value: number] }>()
const entries = ref<HistoryEntry[]>([]), loading = ref(false), error = ref('')
const selected = ref<HistoryItem | null>(null), detailLoading = ref(false), detailError = ref(''), visible = ref(false)
let sequence = 0, detailSequence = 0
const statuses: Record<string, string> = { SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '已超时', REJECTED: '已拒绝' }
const date = (v: string | null) => v ? new Date(v).toLocaleString() : '—'
const url = computed(() => selected.value && props.projectId ? `/api/v1/projects/${encodeURIComponent(props.projectId)}/mobile-automation/history/${encodeURIComponent(selected.value.key)}/export` : '')
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
    const value = await read<{ entries: HistoryEntry[] }>(`/api/v1/projects/${encodeURIComponent(project)}/mobile-automation/history`)
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
    const value = await read<HistoryItem>(`/api/v1/projects/${encodeURIComponent(project)}/mobile-automation/history/${encodeURIComponent(entry.key)}`)
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
  <section id="mobile-history" class="automation-history-panel panel" aria-label="移动端同步历史">
    <div class="history-heading"><div><h3>同步历史</h3><p>来自其他工作区的已结束执行；保留执行时的来源版本，报告只读。</p></div><a-button :loading="loading" :disabled="!projectId" @click="load">刷新同步历史</a-button></div>
    <a-alert v-if="error" type="error" role="alert">{{ error }}</a-alert>
    <a-table :data="entries" :loading="loading" :pagination="{ pageSize: 12 }" row-key="key" size="small">
      <template #columns>
        <a-table-column title="原场景"><template #cell="{ record }">{{ record.scenarioName }}<small class="history-source">来源执行 {{ record.key.split('.')[1] }}</small></template></a-table-column>
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
          <a-alert type="info">以下为来源执行的脱敏快照。来源场景版本可能与当前工作区不同；该历史不会创建或取消 Runner 任务。</a-alert>
          <a-descriptions :column="2" bordered class="history-facts">
            <a-descriptions-item label="原场景">{{ selected.report.scenarioName }}</a-descriptions-item>
            <a-descriptions-item label="来源场景版本">v{{ selected.report.scenarioVersion }}</a-descriptions-item>
            <a-descriptions-item label="结果">{{ statuses[selected.report.status] || selected.report.status }}</a-descriptions-item>
            <a-descriptions-item label="结果代码">{{ selected.report.statusCode || '—' }}</a-descriptions-item>
            <a-descriptions-item label="原提交时间">{{ date(selected.report.requestedAt) }}</a-descriptions-item>
            <a-descriptions-item label="原开始时间">{{ date(selected.report.startedAt) }}</a-descriptions-item>
            <a-descriptions-item label="原结束时间">{{ date(selected.report.finishedAt) }}</a-descriptions-item>

            <a-descriptions-item label="来源项目" :span="2"><code>{{ selected.key.split('.')[0] }}</code></a-descriptions-item>
            <a-descriptions-item label="来源执行" :span="2"><code>{{ selected.key.split('.')[1] }}</code></a-descriptions-item>
            <a-descriptions-item label="报告内容 SHA-256" :span="2"><code>{{ selected.sha256 }}</code></a-descriptions-item>
          </a-descriptions>
          <p>设备：{{ selected.report.deviceName }}；平台：{{ selected.report.platform }}；应用：{{ selected.report.applicationName }}。</p>
          <p>步骤 {{ selected.report.summary.totalSteps }}：通过 {{ selected.report.summary.passedSteps }}，失败 {{ selected.report.summary.failedSteps }}；累计 {{ selected.report.summary.totalDurationMillis }} ms。</p>
          <p class="history-note">保留执行时的场景版本和步骤结果，不包含 UDID、能力参数、应用标识／安装文件、定位值、输入、变量或错误原文。原始截图须单独确认产物绑定；该历史不计入原有质量统计。</p>
          <a :href="url" download>下载脱敏历史报告</a>
          <h4>步骤结果</h4><a-table :data="selected.report.steps" :pagination="{ pageSize: 15 }" size="small" row-key="stepId">
            <template #columns><a-table-column title="序号" data-index="stepSequence" /><a-table-column title="名称" data-index="stepName" /><a-table-column title="类型" data-index="stepType" /><a-table-column title="结果" data-index="outcome" /><a-table-column title="耗时 ms" data-index="durationMillis" /><a-table-column title="原发生时间"><template #cell="{ record }">{{ date(record.occurredAt) }}</template></a-table-column></template>
          </a-table>
          <MobileAutomationHistoryArtifacts :project-id="projectId" :history-key="selected.key" />
          <details><summary>结构化步骤结果</summary><pre class="history-json">{{ JSON.stringify(selected.report.steps, null, 2) }}</pre></details>
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
