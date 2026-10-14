<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface Execution {
  id: string; projectId: string; module: string; name: string; status: string
  createdAt: string; startedAt: string | null; finishedAt: string | null; updatedAt: string
  totalCount: number | null; passedCount: number | null; failedCount: number | null; blockedCount: number | null
}
const props = defineProps<{ projectId: string }>()
const records = ref<Execution[]>([]), loading = ref(false), error = ref('')
const keyword = ref(''), moduleFilter = ref(''), statusFilter = ref('')
let sequence = 0, disposed = false, timer: ReturnType<typeof setInterval> | undefined
const modules: Record<string, { label: string; path: string }> = {
  FUNCTIONAL: { label: '功能测试', path: '/functional/plans' },
  API: { label: '接口自动化', path: '/api-automation' },
  API_HISTORY: { label: '接口自动化同步历史', path: '/api-automation?tab=reports' },
  PERFORMANCE: { label: '性能测试', path: '/performance' },
  UI: { label: 'Web UI', path: '/ui-automation' },
  MOBILE: { label: '移动端', path: '/mobile-automation' },
}
const statuses: Record<string, string> = {
  NOT_STARTED: '未开始', QUEUED: '排队中', ACCEPTED: '已接收', RUNNING: '执行中', CANCELLING: '取消中',
  COMPLETED: '已结束', SUCCEEDED: '执行成功', FAILED: '执行失败', CANCELLED: '已取消',
  TIMED_OUT: '已超时', REJECTED: '已拒绝',
}
const activeStatuses = new Set(['NOT_STARTED', 'QUEUED', 'ACCEPTED', 'RUNNING', 'CANCELLING'])
const filtered = computed(() => records.value.filter(r =>
  (!moduleFilter.value || r.module === moduleFilter.value)
  && (!statusFilter.value || r.status === statusFilter.value)
  && r.name.toLowerCase().includes(keyword.value.trim().toLowerCase())).map(r => ({ ...r, rowKey: r.module + ':' + r.id })))
const activeCount = computed(() => records.value.filter(r => activeStatuses.has(r.status)).length)
const color = (status: string) => ({ RUNNING: 'arcoblue', SUCCEEDED: 'green', FAILED: 'red',
  TIMED_OUT: 'red', REJECTED: 'red', QUEUED: 'orange', ACCEPTED: 'arcoblue', CANCELLING: 'orange' }[status] ?? 'gray')
const date = (value: string | null) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
async function refresh(background = false) {
  const request = ++sequence, project = props.projectId
  if (!background) records.value = []
  error.value = ''
  if (!project || disposed) { loading.value = false; return }
  loading.value = true
  try {
    const response = await fetch(`/api/v1/projects/${project}/tasks/executions`, { credentials: 'include' })
    const body = await response.json() as { data: Execution[]; message?: string }
    if (!response.ok) throw new Error(body.message ?? '加载执行记录失败，请重试')
    if (!disposed && request === sequence && project === props.projectId) records.value = body.data
  } catch (cause) {
    if (!disposed && request === sequence && project === props.projectId) {
      records.value = []; error.value = cause instanceof Error ? cause.message : '加载执行记录失败，请重试'
    }
  } finally {
    if (!disposed && request === sequence && project === props.projectId) loading.value = false
  }
}
watch(() => props.projectId, () => {
  keyword.value = ''; moduleFilter.value = ''; statusFilter.value = ''; void refresh()
}, { immediate: true, flush: 'sync' })
onMounted(() => { timer = setInterval(() => {
  if (props.projectId && !loading.value && !error.value && document.visibilityState === 'visible') void refresh(true)
}, 5000) })
onBeforeUnmount(() => { disposed = true; sequence++; clearInterval(timer) })
</script>

<template>
  <section class="panel execution-panel" aria-label="真实执行记录">
    <div class="execution-heading">
      <div><h2>真实执行记录</h2><p>汇集当前项目各模块最近 100 条记录，同步历史单独标识，按创建时间排序，每 5 秒刷新。报告与取消操作在对应测试模块中完成。</p></div>
      <a-button :loading="loading" :disabled="!projectId" @click="refresh()">刷新执行</a-button>
    </div>
    <div class="execution-toolbar">
      <a-input-search v-model="keyword" aria-label="搜索执行名称" placeholder="搜索执行名称" allow-clear />
      <a-select v-model="moduleFilter" aria-label="筛选执行模块"><a-option value="">全部模块</a-option><a-option v-for="(item, key) in modules" :key="key" :value="key">{{ item.label }}</a-option></a-select>
      <a-select v-model="statusFilter" aria-label="筛选执行状态"><a-option value="">全部状态</a-option><a-option v-for="(label, key) in statuses" :key="key" :value="key">{{ label }}</a-option></a-select>
      <span class="execution-count">{{ filtered.length }} 条 · 当前范围 {{ activeCount }} 条未结束</span>
    </div>
    <a-alert v-if="error" type="error">{{ error }}</a-alert>
    <a-spin :loading="loading">
      <a-table :data="filtered" :pagination="{ pageSize: 10 }" row-key="rowKey" :scroll="{ x: 950 }">
        <template #columns>
          <a-table-column title="执行名称" :width="250"><template #cell="{ record }"><div class="execution-name"><strong>{{ record.name }}</strong><small>{{ modules[record.module]?.label }}</small><small class="execution-id">{{ record.id }}</small></div></template></a-table-column>
          <a-table-column title="状态" :width="125"><template #cell="{ record }"><a-tag :color="color(record.status)">{{ statuses[record.status] ?? record.status }}</a-tag></template></a-table-column>
          <a-table-column title="功能用例结果" :width="180"><template #cell="{ record }"><span v-if="record.totalCount !== null">{{ record.passedCount }} 通过 / {{ record.failedCount }} 失败 / {{ record.blockedCount }} 阻塞<br><small>共 {{ record.totalCount }} 项</small></span><span v-else>—</span></template></a-table-column>
          <a-table-column title="创建时间" :width="165"><template #cell="{ record }"><time :datetime="record.createdAt">{{ date(record.createdAt) }}</time></template></a-table-column>
          <a-table-column title="结束时间" :width="165"><template #cell="{ record }"><time :datetime="record.finishedAt ?? undefined">{{ date(record.finishedAt) }}</time></template></a-table-column>
          <a-table-column title="详情" :width="100"><template #cell="{ record }"><a-link v-if="modules[record.module]" :href="modules[record.module].path">前往模块</a-link></template></a-table-column>
        </template>
        <template #empty><a-empty :description="error ? '暂未取得执行记录' : '当前筛选范围没有真实执行记录。'" /></template>
      </a-table>
    </a-spin>
  </section>
</template>

<style scoped>
.execution-panel :deep(.arco-spin){display:block;width:100%}.execution-id{font-family:var(--font-family-mono);font-size:11px;white-space:nowrap}.execution-panel{margin-bottom:20px;padding:20px}.execution-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.execution-heading h2{margin:0;font-size:17px}.execution-heading p,.execution-count,.execution-name small,td small{font-size:12px;color:var(--color-text-3)}.execution-heading p{margin:8px 0 18px;line-height:1.6}.execution-toolbar{display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap}.execution-toolbar :deep(.arco-input-search){width:230px}.execution-toolbar :deep(.arco-select){width:150px}.execution-count{margin-left:auto}.execution-name{display:grid;gap:6px}.execution-name strong{overflow-wrap:anywhere}.execution-name small{overflow-wrap:anywhere}time{white-space:nowrap;font-size:12px}@media(max-width:620px){.execution-heading{flex-wrap:wrap}.execution-toolbar :deep(.arco-input-search){width:100%}.execution-count{width:100%;margin-left:0}}
</style>
