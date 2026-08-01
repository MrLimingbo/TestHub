<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import FunctionalOverviewCharts from '@/components/FunctionalOverviewCharts.vue'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface Requirement { id: string; status: string; updatedAt: string }
interface TestCase { id: string; requirementId: string | null; status: string; updatedAt: string }
interface Plan { id: string; planCode: string; name: string; status: string; caseCount: number; updatedAt: string }
interface Run { id: string; name: string; status: string; passedCount: number; failedCount: number; blockedCount: number; notRunCount: number; totalCount: number; updatedAt: string }
interface Defect { id: string; defectCode: string; title: string; severity: string; status: string; linkCount: number; updatedAt: string }
interface Tag { id: string }
interface CustomField { id: string }

const session = useSessionStore()
const requirements = ref<Requirement[]>([])
const cases = ref<TestCase[]>([])
const plans = ref<Plan[]>([])
const runs = ref<Run[]>([])
const defects = ref<Defect[]>([])
const tags = ref<Tag[]>([])
const fields = ref<CustomField[]>([])
const loading = ref(true)

const requirementCoverage = computed(() => {
  if (!requirements.value.length) return 0
  const covered = new Set(cases.value.map((item) => item.requirementId).filter(Boolean)).size
  return Math.round((covered / requirements.value.length) * 100)
})
const readyCases = computed(() => cases.value.filter((item) => ['READY', 'APPROVED'].includes(item.status)).length)
const activePlans = computed(() => plans.value.filter((item) => item.status !== 'ARCHIVED').length)
const openDefects = computed(() => defects.value.filter((item) => !['RESOLVED', 'CLOSED'].includes(item.status)).length)
const criticalDefects = computed(() => defects.value.filter((item) => item.severity === 'CRITICAL' && !['RESOLVED', 'CLOSED'].includes(item.status)).length)
const execution = computed(() => runs.value.reduce((summary, run) => ({
  passed: summary.passed + run.passedCount,
  failed: summary.failed + run.failedCount,
  blocked: summary.blocked + run.blockedCount,
  notRun: summary.notRun + run.notRunCount,
}), { passed: 0, failed: 0, blocked: 0, notRun: 0 }))
const passRate = computed(() => {
  const decided = execution.value.passed + execution.value.failed + execution.value.blocked
  return decided ? Math.round((execution.value.passed / decided) * 100) : 0
})
const recentRows = computed(() => [
  ...plans.value.map((item) => ({ id: `plan-${item.id}`, type: '测试计划', code: item.planCode, name: item.name, status: item.status, updatedAt: item.updatedAt, path: '/functional/plans' })),
  ...defects.value.map((item) => ({ id: `defect-${item.id}`, type: '缺陷', code: item.defectCode, name: item.title, status: item.status, updatedAt: item.updatedAt, path: '/functional/delivery' })),
].sort((left, right) => new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()).slice(0, 7))

const statusLabel = (value: string) => ({
  DRAFT: '草稿',
  READY: '待执行',
  ARCHIVED: '已归档',
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}[value] ?? value)
const statusColor = (value: string) => ({
  DRAFT: 'gray',
  READY: 'arcoblue',
  ARCHIVED: 'gray',
  OPEN: 'red',
  IN_PROGRESS: 'orange',
  RESOLVED: 'green',
  CLOSED: 'gray',
}[value] ?? 'gray')
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'

async function read<T>(url: string): Promise<T> {
  const response = await fetch(url, { credentials: 'include' })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function load() {
  if (!session.projectId) return
  loading.value = true
  try {
    const base = `/api/v1/projects/${session.projectId}/functional`
    const [requirementData, caseData, planData, runData, defectData, tagData, fieldData] = await Promise.all([
      read<Requirement[]>(`${base}/requirements`),
      read<TestCase[]>(`${base}/cases`),
      read<Plan[]>(`${base}/plans`),
      read<Run[]>(`${base}/runs`),
      read<Defect[]>(`${base}/defects`),
      read<Tag[]>(`${base}/tags`),
      read<CustomField[]>(`${base}/custom-fields`),
    ])
    requirements.value = requirementData
    cases.value = caseData
    plans.value = planData
    runs.value = runData
    defects.value = defectData
    tags.value = tagData
    fields.value = fieldData
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载功能测试汇总失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <a-spin class="overview-spin" :loading="loading">
    <section class="overview-metrics">
      <router-link to="/functional/requirements" class="overview-metric metric-blue">
        <div><span>需求资产</span><icon-file /></div><strong>{{ requirements.length }}</strong><p>用例覆盖率 {{ requirementCoverage }}%</p>
      </router-link>
      <router-link to="/functional/cases" class="overview-metric metric-green">
        <div><span>测试用例</span><icon-check-circle /></div><strong>{{ cases.length }}</strong><p>{{ readyCases }} 条已就绪</p>
      </router-link>
      <router-link to="/functional/plans" class="overview-metric metric-violet">
        <div><span>测试计划</span><icon-calendar /></div><strong>{{ plans.length }}</strong><p>{{ activePlans }} 个有效计划</p>
      </router-link>
      <router-link to="/functional/plans" class="overview-metric metric-cyan">
        <div><span>执行通过率</span><icon-bar-chart /></div><strong>{{ passRate }}%</strong><p>{{ runs.length }} 个执行批次</p>
      </router-link>
      <router-link to="/functional/delivery" class="overview-metric metric-orange">
        <div><span>待解决缺陷</span><icon-bug /></div><strong>{{ openDefects }}</strong><p>{{ criticalDefects }} 个致命缺陷</p>
      </router-link>
      <router-link to="/functional/governance" class="overview-metric metric-slate">
        <div><span>治理配置</span><icon-settings /></div><strong>{{ tags.length + fields.length }}</strong><p>{{ tags.length }} 标签 · {{ fields.length }} 字段</p>
      </router-link>
    </section>

    <FunctionalOverviewCharts
      :asset-counts="[requirements.length, cases.length, plans.length, defects.length]"
      :execution="execution"
    />

    <section class="overview-bottom">
      <article class="panel recent-panel">
        <div class="overview-heading"><div><h3>近期测试动态</h3><p>最近更新的测试计划和缺陷</p></div><span>{{ recentRows.length }} 条</span></div>
        <a-table :data="recentRows" :scroll="{ x: 760 }" :pagination="false" row-key="id" :bordered="false">
          <template #columns>
            <a-table-column title="类型" :width="90"><template #cell="{ record }"><a-tag :color="record.type === '缺陷' ? 'orangered' : 'arcoblue'">{{ record.type }}</a-tag></template></a-table-column>
            <a-table-column title="编号" data-index="code" :width="150" />
            <a-table-column title="名称" data-index="name" />
            <a-table-column title="状态" :width="100"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column>
            <a-table-column title="更新时间" :width="170"><template #cell="{ record }">{{ date(record.updatedAt) }}</template></a-table-column>
            <a-table-column title="" fixed="right" :width="70"><template #cell="{ record }"><router-link class="row-link" :to="record.path">查看</router-link></template></a-table-column>
          </template>
        </a-table>
        <a-empty v-if="!loading && recentRows.length === 0" description="暂无测试计划或缺陷动态" />
      </article>
      <article class="panel quick-panel">
        <div class="overview-heading"><div><h3>快捷入口</h3><p>进入核心测试工作区</p></div></div>
        <div class="overview-quick-links">
          <router-link to="/functional/requirements"><span class="quick-icon blue"><icon-file /></span><div><strong>维护需求</strong><small>管理验收范围与状态</small></div><icon-right /></router-link>
          <router-link to="/functional/cases"><span class="quick-icon green"><icon-check-circle /></span><div><strong>编写用例</strong><small>沉淀步骤和预期结果</small></div><icon-right /></router-link>
          <router-link to="/functional/plans"><span class="quick-icon violet"><icon-play-circle /></span><div><strong>开始执行</strong><small>创建计划与执行批次</small></div><icon-right /></router-link>
          <router-link to="/functional/delivery"><span class="quick-icon orange"><icon-import /></span><div><strong>资产交换</strong><small>导入、导出与缺陷追溯</small></div><icon-right /></router-link>
        </div>
      </article>
    </section>
  </a-spin>
</template>

<style scoped>
.overview-spin{display:block;min-height:520px}.overview-metrics{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:13px;margin-bottom:17px}.overview-metric{display:block;min-width:0;padding:17px 17px 15px;color:var(--text);text-decoration:none;border:1px solid var(--line);border-radius:12px;background:#fff;box-shadow:0 5px 18px rgba(26,44,81,.035);transition:transform .18s ease,box-shadow .18s ease}.overview-metric:hover{transform:translateY(-2px);box-shadow:0 10px 24px rgba(26,44,81,.09)}.overview-metric>div{display:flex;align-items:center;justify-content:space-between;color:var(--muted);font-size:11px}.overview-metric>div svg{font-size:18px}.overview-metric strong{display:block;margin:13px 0 6px;font-size:27px}.overview-metric p{margin:0;color:var(--muted);font-size:10px;white-space:nowrap}.metric-blue svg,.metric-blue strong{color:#3167f6}.metric-green svg,.metric-green strong{color:#25a981}.metric-violet svg,.metric-violet strong{color:#7658e8}.metric-cyan svg,.metric-cyan strong{color:#168fa4}.metric-orange svg,.metric-orange strong{color:#dc7634}.metric-slate svg,.metric-slate strong{color:#56647c}.overview-bottom{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(300px,.5fr);gap:17px;margin-top:17px}.recent-panel,.quick-panel{padding:20px}.overview-heading{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:13px}.overview-heading h3{margin:0 0 5px;font-size:15px}.overview-heading p,.overview-heading>span{margin:0;color:var(--muted);font-size:11px}.row-link{color:var(--primary);text-decoration:none}.overview-quick-links{display:grid;gap:7px}.overview-quick-links>a{display:grid;grid-template-columns:35px 1fr 15px;align-items:center;gap:10px;padding:10px;color:var(--text);text-decoration:none;border-radius:8px}.overview-quick-links>a:hover{background:var(--color-fill-1)}.overview-quick-links>a>div{display:grid;gap:3px}.overview-quick-links strong{font-size:12px}.overview-quick-links small{color:var(--muted);font-size:10px}.overview-quick-links>a>svg{color:var(--color-text-3)}.quick-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:9px}.quick-icon.blue{color:#3167f6;background:#edf2ff}.quick-icon.green{color:#25a981;background:#e9f8f3}.quick-icon.violet{color:#7658e8;background:#f0edff}.quick-icon.orange{color:#dc7634;background:#fff1e8}@media(max-width:1460px){.overview-metrics{grid-template-columns:repeat(3,1fr)}}@media(max-width:1100px){.overview-bottom{grid-template-columns:1fr}.overview-metrics{grid-template-columns:repeat(2,1fr)}}
</style>
