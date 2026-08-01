<script setup lang="ts">
import { Message } from '@arco-design/web-vue'
import { computed, onMounted, ref, watch } from 'vue'
import DashboardTrendChart from '@/components/DashboardTrendChart.vue'
import MetricGrid from '@/components/MetricGrid.vue'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface DashboardSummary {
  functionalCaseCount: number
  apiDefinitionCount: number
  automationScenarioCount: number
  openDefectCount: number
  criticalDefectCount: number
  completedExecutions: number
  passedExecutions: number
  failedExecutions: number
  passRatePercent: number | null
  healthStatus: 'HEALTHY' | 'ATTENTION' | 'AT_RISK' | 'NO_EXECUTIONS'
}
interface TrendPoint { date: string; completedExecutions: number; passedExecutions: number; failedExecutions: number }
interface DistributionItem { type: 'FUNCTIONAL' | 'API_AUTOMATION' | 'PERFORMANCE'; label: string; completedExecutions: number; passedExecutions: number; failedExecutions: number }
interface ActivityItem { id: string; action: string; resourceType: string; outcome: string; actorName: string; occurredAt: string }
interface Dashboard { periodDays: number; generatedAt: string; summary: DashboardSummary; trend: TrendPoint[]; distribution: DistributionItem[]; activities: ActivityItem[] }

const session = useSessionStore()
const context = ref<Context | null>(null)
const projectId = computed({ get: () => session.projectId, set: (value: string) => session.selectProject(value) })
const periodDays = ref(7)
const dashboard = ref<Dashboard | null>(null)
const loading = ref(true)

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value) ?? null)
const summary = computed(() => dashboard.value?.summary ?? null)
const metrics = computed(() => {
  if (!summary.value) return []
  return [
    { label: '测试用例', value: number(summary.value.functionalCaseCount), delta: '项目资产', caption: '当前范围', tone: 'blue' as const },
    { label: '接口资产', value: number(summary.value.apiDefinitionCount), delta: '已定义接口', caption: '当前范围', tone: 'green' as const },
    { label: '自动化场景', value: number(summary.value.automationScenarioCount), delta: '可执行场景', caption: '当前范围', tone: 'violet' as const },
    { label: '待解决缺陷', value: number(summary.value.openDefectCount), delta: summary.value.criticalDefectCount ? `${summary.value.criticalDefectCount} 个严重` : '无严重缺陷', caption: '当前范围', tone: 'orange' as const },
  ]
})
const qualityValue = computed(() => summary.value?.passRatePercent == null ? '—' : `${summary.value.passRatePercent}%`)
const qualityDescription = computed(() => {
  if (!summary.value) return '正在加载项目质量数据。'
  if (summary.value.healthStatus === 'AT_RISK') return `发现 ${summary.value.criticalDefectCount} 个严重未解决缺陷，请优先处理。`
  if (summary.value.healthStatus === 'NO_EXECUTIONS') return '所选周期内暂无可判定执行，完成一次测试后将计算通过率。'
  if (summary.value.healthStatus === 'ATTENTION') return `所选周期内有 ${summary.value.failedExecutions} 项执行未通过，请查看趋势和报告。`
  return `所选周期内 ${summary.value.completedExecutions} 项执行均已通过。`
})
const qualityTag = computed(() => ({ HEALTHY: '质量稳定', ATTENTION: '需要关注', AT_RISK: '存在风险', NO_EXECUTIONS: '等待执行' }[summary.value?.healthStatus ?? 'NO_EXECUTIONS']))
const donutStyle = computed(() => {
  const items = dashboard.value?.distribution.filter((item) => item.completedExecutions > 0) ?? []
  const total = items.reduce((sum, item) => sum + item.completedExecutions, 0)
  if (!total) return { background: 'conic-gradient(#e7ecf6 0 100%)' }
  const colors: Record<string, string> = { FUNCTIONAL: '#42c59a', API_AUTOMATION: '#3167f6', PERFORMANCE: '#7658e8' }
  let offset = 0
  const parts = items.map((item) => {
    const next = offset + item.completedExecutions / total * 100
    const part = `${colors[item.type]} ${offset.toFixed(2)}% ${next.toFixed(2)}%`
    offset = next
    return part
  })
  return { background: `conic-gradient(${parts.join(', ')})` }
})
const distributionColor = (type: string) => ({ FUNCTIONAL: 'green', API_AUTOMATION: 'blue', PERFORMANCE: 'violet' }[type] ?? 'orange')
const actionLabel = (action: string) => ({
  FUNCTIONAL_CASE_CREATED: '创建了功能用例', FUNCTIONAL_CASE_UPDATED: '更新了功能用例',
  FUNCTIONAL_PLAN_CREATED: '创建了功能测试计划', FUNCTIONAL_PLAN_UPDATED: '更新了功能测试计划',
  FUNCTIONAL_RUN_CREATED: '创建了功能测试执行', FUNCTIONAL_RUN_CASE_UPDATED: '更新了用例执行结果',
  FUNCTIONAL_RUN_STEP_UPDATED: '更新了执行步骤结果',
  FUNCTIONAL_DEFECT_CREATED: '登记了缺陷', FUNCTIONAL_DEFECT_UPDATED: '更新了缺陷',
  API_DEFINITION_CREATED: '创建了接口定义', API_DEFINITION_VERSION_CREATED: '创建了接口版本',
  API_AUTOMATION_SCENARIO_CREATED: '创建了自动化场景', API_AUTOMATION_SCENARIO_UPDATED: '更新了自动化场景',
  API_AUTOMATION_EXECUTION_QUEUED: '发起了接口自动化执行', API_AUTOMATION_EXECUTION_CANCELLED: '取消了接口自动化执行',
  API_AUTOMATION_EXECUTION_MANIFEST_FROZEN: '冻结了接口自动化执行清单',
  API_AUTOMATION_EXECUTION_RUNTIME_SNAPSHOT_FROZEN: '冻结了接口自动化环境快照',
  API_AUTOMATION_SCHEDULE_TRIGGERED: '定时触发了接口自动化', API_AUTOMATION_SCHEDULE_AUTO_DISABLED: '自动停用了异常定时任务',
  PERFORMANCE_TEST_PLAN_CREATED: '创建了压测计划', PERFORMANCE_TEST_PLAN_UPDATED: '更新了压测计划',
  PERFORMANCE_TEST_EXECUTION_QUEUED: '发起了性能测试', PERFORMANCE_TEST_EXECUTION_CANCELLED: '取消了性能测试',
}[action] ?? '记录了项目活动')
const relativeTime = (value: string) => {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return `${Math.floor(seconds / 86400)} 天前`
}
const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
const currentDate = computed(() => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full' }).format(new Date()))

async function read<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: 'include' })
  if (!response.ok) {
    const failure = await response.json().catch(() => ({})) as { message?: string }
    throw new Error(failure.message ?? '无法读取项目质量数据')
  }
  return (await response.json() as ApiEnvelope<T>).data
}
async function loadDashboard() {
  if (!projectId.value) return
  loading.value = true
  try {
    dashboard.value = await read<Dashboard>(`/api/v1/projects/${projectId.value}/dashboard?days=${periodDays.value}`)
  } catch (error) {
    dashboard.value = null
    Message.error(error instanceof Error ? error.message : '无法读取项目质量数据')
  } finally {
    loading.value = false
  }
}
async function initialize() {
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = session.resolveProjectId(projects.value)
    await loadDashboard()
  } catch (error) {
    loading.value = false
    Message.error(error instanceof Error ? error.message : '无法读取项目上下文')
  }
}

watch([projectId, periodDays], () => { void loadDashboard() })
onMounted(() => { void initialize() })
</script>

<template>
  <div class="page dashboard-page">
    <section class="welcome-banner">
      <div>
        <div class="eyebrow">{{ currentDate }} · 项目质量工作台</div>
        <h1>你好，{{ session.profile?.displayName ?? '测试同学' }}</h1>
        <p>{{ selectedProject ? `${selectedProject.name}：${qualityDescription}` : '请选择一个可访问项目查看质量数据。' }}</p>
      </div>
      <div class="quality-score">
        <span>近 {{ periodDays }} 天通过率</span><strong>{{ qualityValue }}</strong><small>{{ qualityTag }}</small>
      </div>
    </section>

    <section class="dashboard-filter panel">
      <div><strong>项目范围</strong><small>所有数据均按当前项目权限聚合，不包含请求、响应或密钥内容。</small></div>
      <a-space>
        <a-select v-model="projectId" :loading="!context" :style="{ width: '190px' }" aria-label="选择质量看板项目">
          <a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option>
        </a-select>
        <a-radio-group v-model="periodDays" type="button" size="small" aria-label="选择统计周期">
          <a-radio :value="7">近 7 天</a-radio><a-radio :value="30">近 30 天</a-radio>
        </a-radio-group>
      </a-space>
    </section>

    <a-spin :loading="loading" class="dashboard-spin">
      <MetricGrid v-if="metrics.length" :metrics="metrics" />
      <section v-if="dashboard" class="dashboard-grid">
        <article class="panel panel--wide">
          <div class="panel-title"><div><h3>质量趋势</h3><p>按完成时间聚合的功能用例、接口自动化与性能任务</p></div><span class="data-hint">已判定 {{ number(summary?.completedExecutions ?? 0) }} 项</span></div>
          <DashboardTrendChart :points="dashboard.trend" />
        </article>
        <article class="panel">
          <div class="panel-title"><div><h3>执行分布</h3><p>只统计有通过或失败结论的执行</p></div><router-link to="/reports" class="panel-link">查看报告</router-link></div>
          <div class="donut-wrap">
            <div class="donut" :style="donutStyle"><div><strong>{{ number(summary?.completedExecutions ?? 0) }}</strong><span>已判定执行</span></div></div>
            <ul class="legend"><li v-for="item in dashboard.distribution" :key="item.type"><i :class="distributionColor(item.type)"></i><span>{{ item.label }}</span><strong>{{ number(item.completedExecutions) }}</strong></li></ul>
          </div>
        </article>
        <article class="panel">
          <div class="panel-title"><div><h3>最近动态</h3><p>项目审计记录中的最近 8 项活动</p></div><router-link to="/audit" class="panel-link">全部动态</router-link></div>
          <div v-if="dashboard.activities.length" class="activity-list"><div v-for="item in dashboard.activities" :key="item.id" class="activity-item"><span class="activity-icon" :class="item.outcome === 'SUCCESS' ? 'success' : 'warning'"></span><div><strong>{{ actionLabel(item.action) }}</strong><p>{{ item.actorName }} · {{ item.resourceType }}</p></div><time>{{ relativeTime(item.occurredAt) }}</time></div></div>
          <a-empty v-else description="当前项目暂无审计活动" />
        </article>
        <article class="panel">
          <div class="panel-title"><div><h3>质量状态</h3><p>仅依据当前周期的执行结论与未解决严重缺陷</p></div></div>
          <div class="quality-overview"><div><span>通过</span><strong class="success-text">{{ number(summary?.passedExecutions ?? 0) }}</strong></div><div><span>未通过</span><strong class="danger-text">{{ number(summary?.failedExecutions ?? 0) }}</strong></div><div><span>严重缺陷</span><strong :class="summary?.criticalDefectCount ? 'danger-text' : ''">{{ number(summary?.criticalDefectCount ?? 0) }}</strong></div></div>
          <p class="quality-note">性能任务的阈值判定请在对应任务报告中查看；本工作台只汇总执行终态。</p>
        </article>
        <article class="panel">
          <div class="panel-title"><div><h3>快捷开始</h3><p>创建常用测试资产</p></div></div>
          <div class="quick-grid"><router-link to="/functional/cases"><icon-safe />新建用例</router-link><router-link to="/api-testing/quick-request"><icon-thunderbolt />调试接口</router-link><router-link to="/api-automation"><icon-robot />编排场景</router-link><router-link to="/performance"><icon-fire />发起压测</router-link></div>
        </article>
      </section>
      <a-empty v-else-if="!loading" description="未能加载项目质量工作台，请刷新后重试" />
    </a-spin>
  </div>
</template>
