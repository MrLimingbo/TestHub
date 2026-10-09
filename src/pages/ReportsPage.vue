<script setup lang="ts">
import { Message } from '@arco-design/web-vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DashboardTrendChart from '@/components/DashboardTrendChart.vue'
import MetricGrid from '@/components/MetricGrid.vue'
import PageHeader from '@/components/PageHeader.vue'
import type { MetricCard } from '@/types/platform'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface TrendPoint { date: string; completedExecutions: number; passedExecutions: number; failedExecutions: number }
type HealthStatus = 'HEALTHY' | 'ATTENTION' | 'AT_RISK' | 'NO_EXECUTIONS'
interface QualitySummary {
  functionalCaseCount: number
  apiDefinitionCount: number
  automationScenarioCount: number
  openDefectCount: number
  criticalDefectCount: number
  completedExecutions: number
  passedExecutions: number
  failedExecutions: number
  passRatePercent: number | null
}
interface ProjectHealth {
  projectId: string
  projectName: string
  projectCode: string
  completedExecutions: number
  passedExecutions: number
  failedExecutions: number
  passRatePercent: number | null
  openDefectCount: number
  criticalDefectCount: number
  healthStatus: HealthStatus
}
interface QualityReport {
  from: string
  to: string
  generatedAt: string
  projectCount: number
  summary: QualitySummary
  trend: TrendPoint[]
  projects: ProjectHealth[]
}
interface QualityReportSchedule {
  id: string
  name: string
  projectIds: string[]
  cronExpression: string
  timezone: string
  periodDays: number
  enabled: boolean
  lastRunId: string | null
  lastRunStatus: string | null
  lastRunAt: string | null
}
interface QualityReportScheduleRun { id: string; status: string; deliveryCount: number; from: string; to: string; errorMessage: string | null; startedAt: string; finishedAt: string | null }

const context = ref<Context | null>(null)
const selectedProjectIds = ref<string[]>([])
const fromDate = ref(daysAgo(29))
const toDate = ref(daysAgo(0))
const report = ref<QualityReport | null>(null)
const loading = ref(true)
const savingSchedule = ref(false), reportError = ref(''), runsError = ref('')
let reportRequest = 0, schedulesRequest = 0, runsRequest = 0, formRequest = 0, disposed = false
const schedules = ref<QualityReportSchedule[]>([])
const scheduleVisible = ref(false)
const runsVisible = ref(false)
const editingScheduleId = ref<string | null>(null)
const selectedSchedule = ref<QualityReportSchedule | null>(null)
const scheduleRuns = ref<QualityReportScheduleRun[]>([])
const scheduleForm = ref({ name: '', projectIds: [] as string[], cronExpression: '0 0 9 ? * MON', timezone: 'Asia/Shanghai', periodDays: 7, enabled: true })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const summary = computed(() => report.value?.summary ?? null)
const selectedScopeLabel = computed(() => {
  if (!selectedProjectIds.value.length || selectedProjectIds.value.length === projects.value.length) return '全部可访问项目'
  return `已选择 ${selectedProjectIds.value.length} 个项目`
})
const metrics = computed<MetricCard[]>(() => {
  if (!report.value || !summary.value) return []
  return [
    { label: '覆盖项目', value: number(report.value.projectCount), delta: selectedScopeLabel.value, caption: '数据范围', tone: 'blue' },
    { label: '功能用例', value: number(summary.value.functionalCaseCount), delta: `${number(summary.value.apiDefinitionCount)} 个接口定义`, caption: '当前资产', tone: 'violet' },
    { label: '已判定执行', value: number(summary.value.completedExecutions), delta: passRate(summary.value.passRatePercent), caption: '周期通过率', tone: 'green' },
    { label: '待解决缺陷', value: number(summary.value.openDefectCount), delta: summary.value.criticalDefectCount ? `${number(summary.value.criticalDefectCount)} 个严重` : '无严重缺陷', caption: '当前状态', tone: 'orange' },
  ]
})
const qualityLabel = computed(() => {
  if (!summary.value) return '正在汇总质量数据'
  if (summary.value.criticalDefectCount) return '存在风险'
  if (!summary.value.completedExecutions) return '等待执行'
  return summary.value.failedExecutions ? '需要关注' : '质量稳定'
})
const qualityColor = computed(() => summary.value?.criticalDefectCount ? 'red' : summary.value?.failedExecutions ? 'orange' : 'green')
const qualityDescription = computed(() => {
  if (!summary.value) return '按你有权访问的项目汇总测试质量数据。'
  if (summary.value.criticalDefectCount) return `发现 ${summary.value.criticalDefectCount} 个严重未关闭缺陷，建议优先处理。`
  if (!summary.value.completedExecutions) return '当前周期暂无已判定执行，完成测试后将生成通过率趋势。'
  return `共 ${number(summary.value.completedExecutions)} 项已判定执行，其中 ${number(summary.value.passedExecutions)} 项通过。`
})
const healthColumns = [
  { title: '项目', dataIndex: 'projectName', slotName: 'project', width: 230 },
  { title: '执行', dataIndex: 'completedExecutions', width: 90 },
  { title: '通过 / 失败', dataIndex: 'passRatePercent', slotName: 'outcomes', width: 165 },
  { title: '未关闭缺陷', dataIndex: 'openDefectCount', slotName: 'defects', width: 130 },
  { title: '健康状态', dataIndex: 'healthStatus', slotName: 'health', width: 125 },
]

function daysAgo(days: number) {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const part = (type: string) => Number(parts.find(item => item.type === type)!.value)
  return new Date(Date.UTC(part('year'), part('month') - 1, part('day') - days)).toISOString().slice(0, 10)
}
function number(value: number) { return new Intl.NumberFormat('zh-CN').format(value) }
function passRate(value: number | null) { return value == null ? '暂无结论' : `${value}% 通过` }
function healthLabel(status: HealthStatus) {
  return { HEALTHY: '质量稳定', ATTENTION: '需要关注', AT_RISK: '存在风险', NO_EXECUTIONS: '等待执行' }[status]
}
function healthColor(status: HealthStatus) {
  return { HEALTHY: 'green', ATTENTION: 'orange', AT_RISK: 'red', NO_EXECUTIONS: 'gray' }[status]
}
function queryString() {
  const query = new URLSearchParams({ from: fromDate.value, to: toDate.value })
  selectedProjectIds.value.forEach((projectId) => query.append('projectIds', projectId))
  return query.toString()
}
async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '无法读取质量报表')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadReport() {
  if (!context.value || disposed) return
  const request = ++reportRequest, query = queryString()
  report.value = null; reportError.value = ''
  if (!fromDate.value || !toDate.value || fromDate.value > toDate.value) {
    loading.value = false; reportError.value = '请填写有效日期，开始日期不能晚于结束日期'
    return
  }
  loading.value = true
  try {
    const value = await read<QualityReport>(`/api/v1/reports/quality?${query}`)
    if (!disposed && request === reportRequest) report.value = value
  } catch (error) {
    if (!disposed && request === reportRequest) reportError.value = error instanceof Error ? error.message : '无法读取质量报表'
  } finally {
    if (!disposed && request === reportRequest) loading.value = false
  }
}
async function initialize() {
  try {
    const value = await read<Context>('/api/v1/access/context')
    if (disposed) return
    context.value = value
    selectedProjectIds.value = projects.value.map((project) => project.id)
    await Promise.all([loadReport(), loadSchedules()])
  } catch (error) {
    if (disposed) return
    loading.value = false
    Message.error(error instanceof Error ? error.message : '无法读取项目范围')
  }
}
async function loadSchedules() {
  const request = ++schedulesRequest
  try { const value = await read<QualityReportSchedule[]>('/api/v1/reports/quality/schedules'); if (!disposed && request === schedulesRequest) schedules.value = value } catch (error) { if (!disposed && request === schedulesRequest) Message.error(error instanceof Error ? error.message : '无法读取定时报告') }
}
function createSchedule() {
  formRequest++
  editingScheduleId.value = null
  scheduleForm.value = { name: '', projectIds: selectedProjectIds.value.length ? [...selectedProjectIds.value] : projects.value.map((project) => project.id), cronExpression: '0 0 9 ? * MON', timezone: 'Asia/Shanghai', periodDays: 7, enabled: true }
  scheduleVisible.value = true
}
function editSchedule(schedule: QualityReportSchedule) {
  formRequest++
  editingScheduleId.value = schedule.id
  scheduleForm.value = { name: schedule.name, projectIds: [...schedule.projectIds], cronExpression: schedule.cronExpression, timezone: schedule.timezone, periodDays: schedule.periodDays, enabled: schedule.enabled }
  scheduleVisible.value = true
}
function closeSchedule() { formRequest++; scheduleVisible.value = false }
async function saveSchedule() {
  if (savingSchedule.value) return false
  const request = formRequest, id = editingScheduleId.value
  const input = { ...scheduleForm.value, projectIds: [...scheduleForm.value.projectIds] }
  savingSchedule.value = true
  try {
    const url = id ? `/api/v1/reports/quality/schedules/${id}` : '/api/v1/reports/quality/schedules'
    await mutate<QualityReportSchedule>(url, id ? 'PUT' : 'POST', input)
    if (disposed || request !== formRequest) return false
    scheduleVisible.value = false; await loadSchedules()
    Message.success(id ? '定时报告已更新' : '定时报告已创建'); return true
  } catch (error) { if (!disposed && request === formRequest) Message.error(error instanceof Error ? error.message : '保存定时报告失败'); return false }
  finally { savingSchedule.value = false }
}
async function runSchedule(schedule: QualityReportSchedule) {
  try {
    const run = await mutate<QualityReportScheduleRun>(`/api/v1/reports/quality/schedules/${schedule.id}/run`, 'POST')
    await loadSchedules()
    if (run.status === 'SUCCEEDED') Message.success(`质量报告已生成，已写入 ${run.deliveryCount} 条通知投递`)
    else Message.error(run.errorMessage ?? '质量报告运行失败')
  } catch (error) { Message.error(error instanceof Error ? error.message : '启动定时报告失败') }
}
async function removeSchedule(schedule: QualityReportSchedule) {
  try { await mutate<void>(`/api/v1/reports/quality/schedules/${schedule.id}`, 'DELETE'); await loadSchedules(); Message.success('定时报告已删除') } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
}
function closeRuns() { runsRequest++; runsVisible.value = false; selectedSchedule.value = null; scheduleRuns.value = []; runsError.value = '' }
async function viewRuns(schedule: QualityReportSchedule) {
  const request = ++runsRequest
  selectedSchedule.value = schedule; scheduleRuns.value = []; runsError.value = ''; runsVisible.value = true
  try {
    const value = await read<QualityReportScheduleRun[]>(`/api/v1/reports/quality/schedules/${schedule.id}/runs`)
    if (!disposed && request === runsRequest && runsVisible.value) scheduleRuns.value = value
  } catch (error) { if (!disposed && request === runsRequest && runsVisible.value) runsError.value = error instanceof Error ? error.message : '无法读取运行记录' }
}
function date(value: string | null) { return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '尚未运行' }
async function downloadCsv() {
  if (!fromDate.value || !toDate.value || fromDate.value > toDate.value) {
    Message.warning('开始日期不能晚于结束日期')
    return
  }
  const query = queryString(), from = fromDate.value, to = toDate.value
  try {
    const response = await fetch(`/api/v1/reports/quality/export?${query}`, { credentials: 'include' })
    if (!response.ok) {
      const body = await response.json().catch(() => ({})) as { message?: string }
      throw new Error(body.message ?? '导出失败')
    }
    const blob = await response.blob()
    if (disposed) return
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `testhub-quality-report-${from}-to-${to}.csv`
    link.click()
    URL.revokeObjectURL(link.href)
    Message.success('质量报表 CSV 已生成，内容已进行电子表格公式安全处理')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '导出质量报表失败')
  }
}

watch([fromDate, toDate, selectedProjectIds], () => { void loadReport() }, { deep: true })
onMounted(() => { void initialize() })
onBeforeUnmount(() => { disposed = true; reportRequest++; schedulesRequest++; runsRequest++; formRequest++ })
</script>

<template>
  <div class="page reports-page">
    <PageHeader eyebrow="质量洞察" title="跨项目质量报表" description="仅汇总你有权访问的项目，支持自定义时间范围、健康度对比与 CSV 导出。" action="导出 CSV" @action="downloadCsv" />

    <section class="report-filter panel">
      <div class="report-filter__copy"><strong>统计范围</strong><small>最长 90 天；清空项目筛选时默认聚合全部可访问项目。</small></div>
      <div class="report-filter__controls">
        <label>开始日期<input v-model="fromDate" type="date" aria-label="开始日期" /></label>
        <span class="report-filter__divider">至</span>
        <label>结束日期<input v-model="toDate" type="date" aria-label="结束日期" /></label>
        <a-select v-model="selectedProjectIds" multiple allow-clear :max-tag-count="2" :style="{ width: '280px' }" placeholder="全部可访问项目" aria-label="筛选统计项目">
          <a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option>
        </a-select>
        <a-button :loading="loading" @click="loadReport"><template #icon><icon-refresh /></template>刷新</a-button>
        <a-button type="primary" @click="createSchedule"><template #icon><icon-calendar /></template>定时报告</a-button>
      </div>
    </section>

    <a-alert v-if="reportError" type="error" role="alert">{{ reportError }}</a-alert>
    <a-spin :loading="loading" class="dashboard-spin">
      <MetricGrid v-if="metrics.length" :metrics="metrics" />
      <section v-if="report && summary" class="report-layout">
        <article class="panel panel--wide">
          <div class="panel-title"><div><h3>执行与通过趋势</h3><p>{{ report.from }} 至 {{ report.to }} · {{ selectedScopeLabel }}</p></div><span class="data-hint">{{ number(summary.completedExecutions) }} 项已判定</span></div>
          <DashboardTrendChart :points="report.trend" />
        </article>

        <article class="panel quality-gate-panel">
          <div class="panel-title"><div><h3>质量摘要</h3><p>执行终态与当前未关闭缺陷</p></div><a-tag :color="qualityColor">{{ qualityLabel }}</a-tag></div>
          <div class="quality-gate__score"><strong>{{ passRate(summary.passRatePercent) }}</strong><span>{{ qualityDescription }}</span></div>
          <div class="quality-gate__row"><span>通过执行</span><strong class="success-text">{{ number(summary.passedExecutions) }}</strong></div>
          <div class="quality-gate__row"><span>失败执行</span><strong :class="summary.failedExecutions ? 'danger-text' : ''">{{ number(summary.failedExecutions) }}</strong></div>
          <div class="quality-gate__row"><span>自动化场景</span><strong>{{ number(summary.automationScenarioCount) }}</strong></div>
          <div class="quality-gate__row"><span>严重缺陷</span><strong :class="summary.criticalDefectCount ? 'danger-text' : ''">{{ number(summary.criticalDefectCount) }}</strong></div>
        </article>

        <article class="panel panel--full health-table-panel">
          <div class="panel-title"><div><h3>项目健康度</h3><p>按项目比较执行质量与未关闭缺陷，可据此定位风险项目。</p></div><span class="data-hint">{{ report.projectCount }} 个项目</span></div>
          <a-table :columns="healthColumns" :data="report.projects" row-key="projectId" :pagination="{ pageSize: 8, size: 'small' }" :bordered="false">
            <template #project="{ record }"><div class="project-cell"><strong>{{ record.projectName }}</strong><small>{{ record.projectCode }}</small></div></template>
            <template #outcomes="{ record }"><div class="outcome-cell"><strong>{{ passRate(record.passRatePercent) }}</strong><small>通过 {{ number(record.passedExecutions) }} · 失败 {{ number(record.failedExecutions) }}</small></div></template>
            <template #defects="{ record }"><span :class="record.criticalDefectCount ? 'danger-text' : ''">{{ number(record.openDefectCount) }} <small v-if="record.criticalDefectCount">（严重 {{ record.criticalDefectCount }}）</small></span></template>
            <template #health="{ record }"><a-tag :color="healthColor(record.healthStatus)">{{ healthLabel(record.healthStatus) }}</a-tag></template>
          </a-table>
        </article>
      </section>
      <a-empty v-else-if="!loading" description="未能生成质量报表，请调整筛选范围后重试" />
    </a-spin>
    <section class="panel schedule-panel"><div class="panel-title"><div><h3>定时质量报告</h3><p>按 Quartz Cron 与时区生成跨项目质量报告；通知模板可订阅 ready / risk 事件。</p></div><a-button type="text" @click="createSchedule"><icon-plus /> 新建计划</a-button></div><a-table :data="schedules" :pagination="false" row-key="id"><template #columns><a-table-column title="计划"><template #cell="{ record }"><strong>{{ record.name }}</strong><small class="schedule-meta">{{ record.projectIds.length }} 个项目 · {{ record.periodDays }} 天周期</small></template></a-table-column><a-table-column title="触发规则"><template #cell="{ record }"><code>{{ record.cronExpression }}</code><small class="schedule-meta">{{ record.timezone }}</small></template></a-table-column><a-table-column title="上次运行"><template #cell="{ record }"><a-tag v-if="record.lastRunStatus" :color="record.lastRunStatus === 'SUCCEEDED' ? 'green' : 'red'">{{ record.lastRunStatus }}</a-tag><span v-else class="schedule-meta">尚未运行</span><small class="schedule-meta">{{ date(record.lastRunAt) }}</small></template></a-table-column><a-table-column title="状态"><template #cell="{ record }"><a-badge :status="record.enabled ? 'success' : 'normal'" :text="record.enabled ? '已启用' : '已停用'" /></template></a-table-column><a-table-column title="操作" :width="230"><template #cell="{ record }"><a-space><a-button type="text" :disabled="!record.enabled" @click="runSchedule(record)">立即运行</a-button><a-button type="text" @click="viewRuns(record)">记录</a-button><a-button type="text" @click="editSchedule(record)">编辑</a-button><a-popconfirm content="删除后历史运行记录也会移除，确认继续？" @ok="removeSchedule(record)"><a-button type="text" status="danger">删除</a-button></a-popconfirm></a-space></template></a-table-column></template></a-table><a-empty v-if="schedules.length === 0" description="尚未创建定时质量报告，可先选择项目和周报周期。" /></section>
    <a-modal v-model:visible="scheduleVisible" :title="editingScheduleId ? '编辑定时质量报告' : '新建定时质量报告'" width="640px" :ok-loading="savingSchedule" :on-before-ok="saveSchedule" @cancel="closeSchedule"><a-form :model="scheduleForm" layout="vertical"><a-form-item label="计划名称" required><a-input v-model="scheduleForm.name" placeholder="例如：每周项目质量报告" /></a-form-item><a-form-item label="项目范围" required extra="选择需要纳入质量汇总且你有管理权限的项目。"><a-select v-model="scheduleForm.projectIds" multiple :max-tag-count="3" placeholder="选择项目"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select></a-form-item><a-form-item label="Quartz Cron" required extra="采用六或七段 Quartz 表达式，例如每周一 09:00：0 0 9 ? * MON。"><a-input v-model="scheduleForm.cronExpression" /></a-form-item><div class="schedule-form-row"><a-form-item label="时区" required><a-select v-model="scheduleForm.timezone"><a-option value="Asia/Shanghai">Asia/Shanghai</a-option><a-option value="UTC">UTC</a-option></a-select></a-form-item><a-form-item label="统计周期（天）" required><a-input-number v-model="scheduleForm.periodDays" :min="1" :max="90" /></a-form-item></div><a-form-item label="启用计划"><a-switch v-model="scheduleForm.enabled"><template #checked>启用</template><template #unchecked>停用</template></a-switch></a-form-item></a-form></a-modal>
    <a-modal v-model:visible="runsVisible" :title="`${selectedSchedule?.name ?? ''} · 运行记录`" width="760px" :footer="false" @cancel="closeRuns"><a-alert v-if="runsError" type="error">{{ runsError }}</a-alert><a-table :data="scheduleRuns" :pagination="false" row-key="id"><template #columns><a-table-column title="状态"><template #cell="{ record }"><a-tag :color="record.status === 'SUCCEEDED' ? 'green' : 'red'">{{ record.status }}</a-tag></template></a-table-column><a-table-column title="统计范围"><template #cell="{ record }">{{ record.from }} 至 {{ record.to }}</template></a-table-column><a-table-column title="通知投递" data-index="deliveryCount" /><a-table-column title="开始时间"><template #cell="{ record }">{{ date(record.startedAt) }}</template></a-table-column><a-table-column title="结果"><template #cell="{ record }"><span class="schedule-error">{{ record.errorMessage ?? '质量报告已生成' }}</span></template></a-table-column></template></a-table><a-empty v-if="scheduleRuns.length === 0" description="暂无运行记录" /></a-modal>
  </div>
</template>

<style scoped>
.report-filter{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:15px 18px;margin-bottom:18px}.report-filter__copy{display:grid;gap:4px}.report-filter__copy strong{font-size:14px}.report-filter__copy small,.schedule-meta,.schedule-error{color:var(--color-text-3);font-size:12px}.report-filter__controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end}.report-filter label{display:flex;align-items:center;gap:7px;color:var(--color-text-3);font-size:12px;white-space:nowrap}.report-filter input{height:32px;padding:0 8px;border:1px solid var(--color-border-2);border-radius:6px;color:var(--color-text-1);font:inherit;background:var(--color-bg-2)}.report-filter input:focus{outline:2px solid rgb(var(--primary-2));outline-offset:1px}.report-filter__divider{color:var(--color-text-3);font-size:12px}.report-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(300px,.9fr);gap:18px}.panel--full{grid-column:1/-1}.quality-gate__score{display:grid;gap:8px;padding:16px;margin:4px 0 12px;border-radius:9px;background:linear-gradient(135deg,rgb(var(--primary-1)),rgb(var(--green-1)))}.quality-gate__score strong{font-size:25px;line-height:1}.quality-gate__score span{color:var(--color-text-2);font-size:12px;line-height:1.6}.quality-gate__row{display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid var(--color-border-2);font-size:13px}.quality-gate__row:last-child{border-bottom:0}.quality-gate__row span{color:var(--color-text-3)}.health-table-panel{padding-bottom:8px}.project-cell,.outcome-cell{display:grid;gap:3px;min-width:0}.project-cell strong,.project-cell small,.outcome-cell strong,.outcome-cell small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-cell small,.outcome-cell small{color:var(--color-text-3);font-size:11px}.outcome-cell strong{font-size:12px}.schedule-panel{margin-top:18px}.schedule-meta{display:block;margin-top:4px}.schedule-form-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}.health-table-panel :deep(.arco-table-th),.schedule-panel :deep(.arco-table-th){font-size:12px;color:var(--color-text-3);background:var(--color-fill-1)}.health-table-panel :deep(.arco-table-td),.schedule-panel :deep(.arco-table-td){font-size:13px}.health-table-panel :deep(.arco-pagination){justify-content:flex-end;margin:14px 0 4px}@media (max-width:1100px){.report-filter{align-items:flex-start;flex-direction:column}.report-filter__controls{justify-content:flex-start}.report-layout{grid-template-columns:1fr}.panel--wide{grid-column:auto}}@media (max-width:640px){.report-filter__controls{align-items:stretch;flex-direction:column;width:100%}.report-filter label{justify-content:space-between}.report-filter input{flex:1}.report-filter__divider{display:none}.report-filter :deep(.arco-select){width:100%!important}.health-table-panel,.schedule-panel{overflow:auto}.schedule-form-row{grid-template-columns:1fr}}
</style>
