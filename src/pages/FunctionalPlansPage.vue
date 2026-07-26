<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface TestCase { id: string; caseCode: string; title: string; priority: string; status: string }
interface Plan { id: string; planCode: string; name: string; status: string; description: string; caseCount: number; updatedAt: string }
interface PlanCase { id: string; caseId: string; caseCode: string; title: string; priority: string; status: string }
interface Run { id: string; planId: string; name: string; status: string; totalCount: number; passedCount: number; failedCount: number; blockedCount: number; notRunCount: number; createdAt: string }
interface RunCase { id: string; caseId: string; caseCode: string; title: string; status: string; note: string }
interface RunStep { id: string; runCaseId: string; stepNo: number; stepText: string; status: string; actualResult: string }

const context = ref<Context | null>(null)
const projectId = ref('')
const cases = ref<TestCase[]>([])
const plans = ref<Plan[]>([])
const selectedPlanId = ref('')
const planCases = ref<PlanCase[]>([])
const runs = ref<Run[]>([])
const selectedRunId = ref('')
const runCases = ref<RunCase[]>([])
const selectedRunCaseId = ref('')
const runSteps = ref<RunStep[]>([])
const loading = ref(true)
const saving = ref(false)
const planDialog = ref(false)
const runDialog = ref(false)
const planForm = reactive({ planCode: '', name: '', status: 'DRAFT', description: '', caseIds: [] as string[] })
const runForm = reactive({ name: '' })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const selectedPlan = computed(() => plans.value.find((plan) => plan.id === selectedPlanId.value))
const selectedRun = computed(() => runs.value.find((run) => run.id === selectedRunId.value))
const activeCount = computed(() => runs.value.filter((run) => ['RUNNING', 'NOT_STARTED'].includes(run.status)).length)
const statusLabel = (status: string) => ({ DRAFT: '草稿', READY: '待执行', ARCHIVED: '已归档', NOT_STARTED: '未开始', RUNNING: '执行中', COMPLETED: '已完成', NOT_RUN: '未执行', PASSED: '通过', FAILED: '失败', BLOCKED: '阻塞' }[status] ?? status)
const statusColor = (status: string) => ({ DRAFT: 'gray', READY: 'arcoblue', ARCHIVED: 'gray', NOT_STARTED: 'orange', RUNNING: 'arcoblue', COMPLETED: 'green', NOT_RUN: 'gray', PASSED: 'green', FAILED: 'red', BLOCKED: 'orange' }[status] ?? 'gray')
const priorityColor = (priority: string) => ({ P0: 'red', P1: 'orange', P2: 'arcoblue', P3: 'gray' }[priority] ?? 'gray')
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'PUT', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadCases() { cases.value = await read<TestCase[]>(`/api/v1/projects/${projectId.value}/functional/cases`) }
async function loadPlans() {
  plans.value = await read<Plan[]>(`/api/v1/projects/${projectId.value}/functional/plans`)
  if (selectedPlanId.value && !plans.value.some((plan) => plan.id === selectedPlanId.value)) selectedPlanId.value = ''
  if (!selectedPlanId.value && plans.value[0]) selectedPlanId.value = plans.value[0].id
  await loadPlanDetails()
}
async function loadPlanDetails() {
  planCases.value = selectedPlanId.value ? await read<PlanCase[]>(`/api/v1/projects/${projectId.value}/functional/plans/${selectedPlanId.value}/cases`) : []
  runs.value = await read<Run[]>(`/api/v1/projects/${projectId.value}/functional/runs${selectedPlanId.value ? `?planId=${selectedPlanId.value}` : ''}`)
  selectedRunId.value = runs.value[0]?.id ?? ''
  await loadRunDetails()
}
async function loadRunDetails() {
  runCases.value = selectedRunId.value ? await read<RunCase[]>(`/api/v1/projects/${projectId.value}/functional/runs/${selectedRunId.value}/cases`) : []
  selectedRunCaseId.value = runCases.value[0]?.id ?? ''
  await loadRunSteps()
}
async function loadRunSteps() { runSteps.value = selectedRunCaseId.value ? await read<RunStep[]>(`/api/v1/projects/${projectId.value}/functional/run-cases/${selectedRunCaseId.value}/steps`) : [] }
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context'); projectId.value = projects.value[0]?.id ?? ''
    await loadCases(); await loadPlans()
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载测试计划失败') } finally { loading.value = false }
}
async function changeProject() { selectedPlanId.value = ''; selectedRunId.value = ''; try { await loadCases(); await loadPlans() } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } }
function openPlan() { Object.assign(planForm, { planCode: '', name: '', status: 'DRAFT', description: '', caseIds: cases.value.map((item) => item.id) }); planDialog.value = true }
function openRun() { runForm.name = selectedPlan.value ? `${selectedPlan.value.name} · 执行批次` : ''; runDialog.value = true }
async function savePlan() {
  saving.value = true
  try { const created = await mutate<Plan>(`/api/v1/projects/${projectId.value}/functional/plans`, 'POST', planForm); planDialog.value = false; selectedPlanId.value = created.id; await loadPlans(); Message.success('测试计划已创建') }
  catch (error) { Message.error(error instanceof Error ? error.message : '保存计划失败') } finally { saving.value = false }
}
async function saveRun() {
  if (!selectedPlanId.value) return
  saving.value = true
  try { const created = await mutate<Run>(`/api/v1/projects/${projectId.value}/functional/plans/${selectedPlanId.value}/runs`, 'POST', runForm); runDialog.value = false; selectedRunId.value = created.id; await loadPlanDetails(); selectedRunId.value = created.id; await loadRunDetails(); Message.success('执行批次已创建') }
  catch (error) { Message.error(error instanceof Error ? error.message : '创建执行批次失败') } finally { saving.value = false }
}
async function selectPlan(id: string) { selectedPlanId.value = id; await loadPlanDetails() }
async function selectRun(id: string) { selectedRunId.value = id; await loadRunDetails() }
async function selectRunCase(id: string) { selectedRunCaseId.value = id; await loadRunSteps() }
async function updateRunCase(item: RunCase, status: string) {
  try { await mutate(`/api/v1/projects/${projectId.value}/functional/run-cases/${item.id}`, 'PUT', { status, note: item.note }); await loadPlanDetails(); Message.success(`用例已标记为${statusLabel(status)}`) }
  catch (error) { Message.error(error instanceof Error ? error.message : '更新执行结果失败') }
}
async function updateStep(step: RunStep, status: string) {
  try { await mutate(`/api/v1/projects/${projectId.value}/functional/run-steps/${step.id}`, 'PUT', { status, actualResult: step.actualResult }); await loadRunSteps(); Message.success('步骤结果已保存') }
  catch (error) { Message.error(error instanceof Error ? error.message : '保存步骤结果失败') }
}
onMounted(load)
</script>

<template>
  <div class="page plans-page">
    <PageHeader eyebrow="FUNCTIONAL DELIVERY" title="测试计划与执行" description="将需求和用例组织成测试计划，创建执行批次并记录步骤级结果，形成可追溯的质量报告。" action="新建测试计划" @action="openPlan" />
    <section class="plan-summary panel"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>测试计划</span><strong>{{ plans.length }}</strong><small>{{ activeCount }} 个有执行批次</small></div><div><span>计划用例</span><strong>{{ selectedPlan?.caseCount ?? 0 }}</strong><small>当前计划范围</small></div><div><span>最新报告</span><strong>{{ selectedRun?.passedCount ?? 0 }}/{{ selectedRun?.totalCount ?? 0 }}</strong><small>{{ selectedRun ? statusLabel(selectedRun.status) : '尚未执行' }}</small></div></section>
    <section class="plan-layout">
      <aside class="plan-list panel"><div class="section-heading"><div><span class="eyebrow">TEST PLANS</span><h3>测试计划</h3></div><a-button type="text" size="small" @click="openPlan"><icon-plus /></a-button></div><a-spin :loading="loading"><button v-for="plan in plans" :key="plan.id" class="plan-item" :class="{ selected: selectedPlanId === plan.id }" @click="selectPlan(plan.id)"><div><strong>{{ plan.name }}</strong><small>{{ plan.planCode }} · {{ plan.caseCount }} 个用例</small></div><a-tag :color="statusColor(plan.status)">{{ statusLabel(plan.status) }}</a-tag></button></a-spin><a-empty v-if="!loading && plans.length === 0" description="还没有测试计划" /><a-button long type="outline" class="create-plan-button" @click="openPlan"><icon-plus /> 创建测试计划</a-button></aside>
      <div v-if="selectedPlan" class="plan-content">
        <section class="plan-header panel"><div><div class="eyebrow">{{ selectedPlan.planCode }}</div><h2>{{ selectedPlan.name }}</h2><p>{{ selectedPlan.description || '暂无计划说明，创建执行批次后开始记录结果。' }}</p></div><a-space><a-button :disabled="selectedPlan.caseCount === 0" @click="openRun"><icon-play-circle /> 创建执行批次</a-button><a-button type="primary" @click="openPlan"><icon-edit /> 编辑计划</a-button></a-space></section>
        <section class="execution-grid"><div class="panel plan-cases"><div class="section-title"><div><strong>计划用例</strong><span>{{ planCases.length }} 条</span></div><a-tag color="arcoblue">范围快照</a-tag></div><a-table :data="planCases" :pagination="false" row-key="id"><template #columns><a-table-column title="用例" data-index="title"><template #cell="{ record }"><div class="case-name"><span class="case-icon"><icon-check-circle /></span><div><strong>{{ record.title }}</strong><small>{{ record.caseCode }}</small></div></div></template></a-table-column><a-table-column title="优先级"><template #cell="{ record }"><a-tag :color="priorityColor(record.priority)">{{ record.priority }}</a-tag></template></a-table-column><a-table-column title="资产状态"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag></template></a-table-column></template></a-table><a-empty v-if="planCases.length === 0" description="编辑计划并选择用例" /></div><div class="panel run-list"><div class="section-title"><div><strong>执行批次</strong><span>{{ runs.length }} 个</span></div><a-button type="text" size="small" :disabled="selectedPlan.caseCount === 0" @click="openRun"><icon-plus /></a-button></div><button v-for="run in runs" :key="run.id" class="run-item" :class="{ selected: selectedRunId === run.id }" @click="selectRun(run.id)"><div><strong>{{ run.name }}</strong><small>{{ date(run.createdAt) }}</small></div><div class="run-rate"><b>{{ run.passedCount }}/{{ run.totalCount }}</b><a-tag :color="statusColor(run.status)">{{ statusLabel(run.status) }}</a-tag></div></button><a-empty v-if="runs.length === 0" description="创建批次开始执行" /></div></section>
        <section v-if="selectedRun" class="panel report-panel"><div class="section-title"><div><strong>执行报告</strong><span>{{ selectedRun.name }}</span></div><a-tag :color="statusColor(selectedRun.status)">{{ statusLabel(selectedRun.status) }}</a-tag></div><div class="report-metrics"><div><span>总用例</span><strong>{{ selectedRun.totalCount }}</strong></div><div class="passed"><span>通过</span><strong>{{ selectedRun.passedCount }}</strong></div><div class="failed"><span>失败</span><strong>{{ selectedRun.failedCount }}</strong></div><div class="blocked"><span>阻塞</span><strong>{{ selectedRun.blockedCount }}</strong></div><div class="not-run"><span>未执行</span><strong>{{ selectedRun.notRunCount }}</strong></div></div><div class="run-detail"><div class="run-case-list"><div class="detail-heading">用例结果</div><button v-for="item in runCases" :key="item.id" class="run-case-item" :class="{ selected: selectedRunCaseId === item.id }" @click="selectRunCase(item.id)"><div><strong>{{ item.title }}</strong><small>{{ item.caseCode }}</small></div><a-tag :color="statusColor(item.status)">{{ statusLabel(item.status) }}</a-tag></button></div><div class="step-list"><div class="detail-heading">步骤级结果 <span v-if="runSteps.length">{{ runSteps.length }} 步</span></div><div v-for="step in runSteps" :key="step.id" class="step-item"><div class="step-number">{{ step.stepNo }}</div><div class="step-copy"><strong>{{ step.stepText }}</strong><a-input v-model="step.actualResult" placeholder="记录实际结果" size="small" /></div><a-select :model-value="step.status" size="small" :style="{ width: '96px' }" @change="(value) => updateStep(step, String(value))"><a-option value="NOT_RUN">未执行</a-option><a-option value="PASSED">通过</a-option><a-option value="FAILED">失败</a-option><a-option value="BLOCKED">阻塞</a-option></a-select></div><a-empty v-if="runSteps.length === 0" description="选择一个执行用例查看步骤" /></div><div v-if="runCases.find((item) => item.id === selectedRunCaseId)" class="run-case-actions"><a-button status="success" @click="updateRunCase(runCases.find((item) => item.id === selectedRunCaseId)!, 'PASSED')">标记通过</a-button><a-button status="danger" @click="updateRunCase(runCases.find((item) => item.id === selectedRunCaseId)!, 'FAILED')">标记失败</a-button><a-button @click="updateRunCase(runCases.find((item) => item.id === selectedRunCaseId)!, 'BLOCKED')">标记阻塞</a-button></div></div></section>
      </div>
      <div v-else class="panel no-plan"><a-empty description="选择或创建一个测试计划，开始组织功能测试执行。"><template #extra><a-button type="primary" @click="openPlan">新建测试计划</a-button></template></a-empty></div>
    </section>
    <a-modal v-model:visible="planDialog" title="新建测试计划" width="700px" :ok-loading="saving" @ok="savePlan"><a-form :model="planForm" layout="vertical"><div class="form-grid"><a-form-item label="计划编号" required><a-input v-model="planForm.planCode" placeholder="例如：PLAN_ORDER_001" /></a-form-item><a-form-item label="计划状态"><a-select v-model="planForm.status"><a-option value="DRAFT">草稿</a-option><a-option value="READY">待执行</a-option><a-option value="ARCHIVED">已归档</a-option></a-select></a-form-item></div><a-form-item label="计划名称" required><a-input v-model="planForm.name" placeholder="例如：订单核心回归测试" /></a-form-item><a-form-item label="计划说明"><a-textarea v-model="planForm.description" :auto-size="{ minRows: 3, maxRows: 6 }" /></a-form-item><a-form-item label="选择用例"><div class="case-picker"><a-checkbox v-for="item in cases" :key="item.id" v-model="planForm.caseIds" :value="item.id">{{ item.caseCode }} · {{ item.title }}</a-checkbox><a-empty v-if="cases.length === 0" description="请先在用例库创建测试用例" /></div></a-form-item></a-form></a-modal>
    <a-modal v-model:visible="runDialog" title="创建执行批次" width="520px" :ok-loading="saving" @ok="saveRun"><a-form :model="runForm" layout="vertical"><a-alert type="info">将复制当前计划的 {{ selectedPlan?.caseCount ?? 0 }} 个用例，并初始化步骤级执行结果。</a-alert><a-form-item label="批次名称" required><a-input v-model="runForm.name" placeholder="例如：2026-07-12 订单回归" /></a-form-item></a-form></a-modal>
  </div>
</template>

<style scoped>
.plan-summary{display:grid;grid-template-columns:1.4fr 1.1fr .8fr .8fr .9fr;gap:18px;align-items:center;margin-bottom:20px}.plan-summary>div{display:grid;gap:4px}.plan-summary span,.plan-summary small,.plan-summary p,.section-title span,.case-name small,.plan-item small,.run-item small{color:var(--color-text-3);font-size:12px}.plan-summary strong{font-size:20px}.plan-layout{display:grid;grid-template-columns:270px minmax(0,1fr);gap:18px;align-items:start}.plan-list{padding:16px 10px;min-height:640px}.section-heading,.section-title{display:flex;align-items:center;justify-content:space-between}.section-heading{padding:0 6px 12px}.section-heading h3{margin:4px 0 0;font-size:16px}.plan-item,.run-item,.run-case-item{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;border:0;background:transparent;border-radius:8px;text-align:left;cursor:pointer}.plan-item{padding:12px 10px}.plan-item:hover,.plan-item.selected,.run-item:hover,.run-item.selected,.run-case-item:hover,.run-case-item.selected{background:rgb(var(--primary-1))}.plan-item>div,.run-item>div,.run-case-item>div{display:grid;gap:4px;min-width:0}.plan-item strong,.run-item strong,.run-case-item strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.create-plan-button{margin-top:18px}.plan-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding:22px 24px;margin-bottom:18px}.plan-header h2{margin:5px 0 6px;font-size:22px}.plan-header p{margin:0;color:var(--color-text-3);max-width:640px}.execution-grid{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(280px,.8fr);gap:18px;margin-bottom:18px}.plan-cases,.run-list,.report-panel{padding:20px}.section-title{margin-bottom:16px}.section-title>div{display:flex;align-items:baseline;gap:10px}.section-title strong{font-size:16px}.case-name{display:flex;align-items:center;gap:10px}.case-name small{display:block;margin-top:4px}.case-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px;background:rgb(var(--success-1));color:rgb(var(--success-6))}.run-list{min-height:260px}.run-item{padding:11px 8px}.run-rate{display:flex!important;align-items:flex-end;gap:8px}.run-rate b{font-size:12px;color:var(--color-text-2)}.report-metrics{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-bottom:20px}.report-metrics>div{display:grid;gap:5px;padding:14px;border-radius:9px;background:var(--color-fill-2)}.report-metrics span{font-size:12px;color:var(--color-text-3)}.report-metrics strong{font-size:22px}.report-metrics .passed strong{color:rgb(var(--success-6))}.report-metrics .failed strong{color:rgb(var(--danger-6))}.report-metrics .blocked strong{color:rgb(var(--warning-6))}.report-metrics .not-run strong{color:var(--color-text-3)}.run-detail{display:grid;grid-template-columns:260px minmax(0,1fr);gap:18px;position:relative}.detail-heading{font-weight:600;color:var(--color-text-2);margin-bottom:10px}.detail-heading span{margin-left:8px;color:var(--color-text-3);font-size:12px}.run-case-item{padding:10px 8px}.step-list{min-height:160px}.step-item{display:grid;grid-template-columns:28px 1fr 96px;gap:10px;align-items:center;border-bottom:1px solid var(--color-border-2);padding:10px 0}.step-number{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-size:12px}.step-copy{display:grid;gap:6px;min-width:0}.step-copy strong{font-size:13px;font-weight:500}.run-case-actions{grid-column:1/-1;display:flex;justify-content:flex-end;gap:8px;padding-top:12px;border-top:1px solid var(--color-border-2)}.no-plan{min-height:440px;display:grid;place-items:center}.form-grid{display:grid;grid-template-columns:1.3fr 1fr;gap:16px}.case-picker{display:grid;gap:10px;max-height:190px;overflow:auto;padding:12px;border:1px solid var(--color-border-2);border-radius:6px}@media (max-width:1100px){.plan-summary{grid-template-columns:1fr 1fr}.plan-layout{grid-template-columns:220px minmax(0,1fr)}}@media (max-width:780px){.plan-layout,.execution-grid,.run-detail{grid-template-columns:1fr}.plan-header{flex-direction:column}.report-metrics{grid-template-columns:repeat(2,1fr)}.form-grid{grid-template-columns:1fr}}
</style>
