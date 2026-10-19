<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'
import PerformanceExecutionChart from '@/components/PerformanceExecutionChart.vue'
import PerformanceHistoryPanel from '@/components/PerformanceHistoryPanel.vue'
import PerformanceResourceChart from '@/components/PerformanceResourceChart.vue'
import { isLocalDesktop, isAccountDesktop, openPerformanceSync, openPerformanceHistorySync, ensureLocalJmeter, localJmeterStatus, cancelJmeterPreparation, openLocalExecutionSettings, type LocalEngineStatus } from '@/utils/desktopWorkspace'
import { useSessionStore } from '@/stores/session'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface ThreadGroup {
  id: string
  name: string
  targetUrl: string
  loadModel: 'STEADY' | 'RAMP_UP' | 'STEPPED_RAMP'
  threadCount: number
  rampUpSeconds: number
  stepCount: number | null
  stepIntervalSeconds: number | null
  durationSeconds: number
  targetTps: number | null
  p95ThresholdMillis: number | null
  errorThresholdPercent: number | null
  requestMethod: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'
  requestHeaders: Record<string, string>
  queryParameters: Record<string, string>
  requestBody: string | null
  expectedStatusCode: number | null
  responseContains: string | null
  dataSetCsv: string | null
  rendezvousUsers: number | null
  sortOrder: number
}
interface Plan {
  id: string
  name: string
  description: string
  status: 'DRAFT' | 'READY' | 'ARCHIVED'
  version: number
  threadGroupCount: number
  updatedAt: string
}
interface PlanDetail extends Omit<Plan, 'threadGroupCount'> { threadGroups: ThreadGroup[] }
interface ThreadGroupInput {
  name: string
  targetUrl: string
  loadModel: 'STEADY' | 'RAMP_UP' | 'STEPPED_RAMP'
  threadCount: number
  rampUpSeconds: number
  stepCount?: number
  stepIntervalSeconds?: number
  durationSeconds: number
  targetTps?: number
  p95ThresholdMillis?: number
  errorThresholdPercent?: number
  requestMethod: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'
  requestHeadersText: string
  queryParametersText: string
  requestBody?: string
  expectedStatusCode?: number
  responseContains?: string
  dataSetCsv?: string
  rendezvousUsers?: number
}
interface PerformanceExecution {
  id: string
  taskId: string
  planId: string
  planName: string
  planVersion: number
  status: string
  statusCode: string
  statusMessage: string
  runnerId: string | null
  routingAttempts: number
  requestedAt: string
  deadlineAt: string
  startedAt: string | null
  finishedAt: string | null
}
interface RunnerStatus {
  runnerId: string
  runnerVersion: string
  executionEnabled: boolean
  trusted: boolean
  trustStatus: string
  maxConcurrentTasks: number
  activeTaskCount: number
  heartbeatAt: string
}
interface PerformanceMetric {
  occurredAt: string
  threadGroupName: string
  sampleCount: number
  successCount: number
  errorCount: number
  activeThreads: number
  throughputPerSecond: number
  averageLatencyMillis: number
  p95LatencyMillis: number
  minLatencyMillis: number
  maxLatencyMillis: number
}
interface PerformanceResourceMetric {
  runnerId: string
  cpuPercent: number
  residentMemoryBytes: number
  occurredAt: string
}
interface PerformanceSummary {
  sampleCount: number
  successCount: number
  errorCount: number
  errorRatePercent: number
  peakThroughputPerSecond: number
  averageLatencyMillis: number
  p95LatencyMillis: number
  minLatencyMillis: number
  maxLatencyMillis: number
  peakActiveThreads: number
}
interface QualityThreshold {
  threadGroupName: string
  sampleCount: number
  p95LatencyMillis: number
  errorRatePercent: number
  p95ThresholdMillis: number | null
  errorThresholdPercent: number | null
  status: string
  breaches: string[]
}
interface QualityVerdict {
  status: string
  configuredChecks: number
  passedChecks: number
  failedChecks: number
  inconclusiveChecks: number
  threadGroups: QualityThreshold[]
}
interface ExecutionArtifact {
  id: string
  artifactType: string
  originalName: string
  contentType: string
  sizeBytes: number
  checksum: string
  createdAt: string
}
interface ExecutionError {
  threadGroupName: string
  responseCode: string
  errorCount: number
  occurredAt: string
}
interface PerformanceComparison {
  baseline: PerformanceExecution
  baselineSummary: PerformanceSummary
  delta: { sampleCount: number; errorRatePercent: number; peakThroughputPerSecond: number; p95LatencyMillis: number }
}
interface PerformanceReport {
  execution: PerformanceExecution
  summary: PerformanceSummary
  metrics: PerformanceMetric[]
  resources: PerformanceResourceMetric[]
  errors: ExecutionError[]
  logs: Array<{ occurredAt: string; code: string; message: string; source: string }>
  quality: QualityVerdict
  comparison: PerformanceComparison | null
  artifacts: ExecutionArtifact[]
}

const context = ref<Context | null>(null)
const session = useSessionStore()
const projectId = computed({ get: () => session.projectId, set: (value: string) => session.selectProject(value) })
const plans = ref<Plan[]>([])
const selectedPlanId = ref('')
const selectedDetail = ref<PlanDetail | null>(null)
const executions = ref<PerformanceExecution[]>([])
const runners = ref<RunnerStatus[]>([])
const operationalError = ref('')
const loading = ref(true)
const saving = ref(false)
const planDialog = ref(false)
const editing = ref(false)
const reportDrawer = ref(false)
const reportLoading = ref(false)
const selectedReport = ref<PerformanceReport | null>(null)
let operationalTimer: number | undefined
let reportTimer: number | undefined
let detailRequest = 0
let plansRequest = 0
let operationalRequest = 0
let reportRequest = 0
const planForm = reactive<{ name: string; description: string; status: Plan['status']; threadGroups: ThreadGroupInput[] }>({
  name: '', description: '', status: 'DRAFT', threadGroups: [],
})

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const readyCount = computed(() => plans.value.filter((plan) => plan.status === 'READY').length)
const threadGroupCount = computed(() => plans.value.reduce((total, plan) => total + plan.threadGroupCount, 0))
const configuredThreads = computed(() => selectedDetail.value?.threadGroups.reduce((total, group) => total + group.threadCount, 0) ?? 0)
const selectedExecutions = computed(() => executions.value.filter((execution) => execution.planId === selectedPlanId.value))
const activeRunner = computed(() => runners.value.find((runner) => runner.trusted) ?? runners.value[0])
const verifiedRunners = computed(() => runners.value.filter((runner) => runner.trusted))
const readyRunners = computed(() => verifiedRunners.value.filter((runner) => runner.executionEnabled))
const runnerCapacity = computed(() => readyRunners.value.reduce((total, runner) => total + runner.maxConcurrentTasks, 0))
const activeRunnerTasks = computed(() => readyRunners.value.reduce((total, runner) => total + runner.activeTaskCount, 0))
const availableRunnerSlots = computed(() => Math.max(0, runnerCapacity.value - activeRunnerTasks.value))
const runnerLabel = computed(() => !activeRunner.value ? '离线' : readyRunners.value.length ? `${readyRunners.value.length} 个已就绪` : '已接入 · 执行禁用')
const runnerTone = computed(() => !activeRunner.value ? 'offline' : readyRunners.value.length ? 'ready' : 'pending')
const runnerPoolLabel = computed(() => runnerCapacity.value ? `${availableRunnerSlots.value} / ${runnerCapacity.value} 空闲槽位` : '等待可执行 Runner')
const preparingEngine = ref(false)
const engineStatus = ref<LocalEngineStatus>()
let preparationCancelled = false
let engineTimer: number | undefined
const localDesktop = isLocalDesktop()
const desktop = localDesktop || isAccountDesktop()
async function showSync() {
  try { await openPerformanceSync() }
  catch (error) { Message.error(error instanceof Error ? error.message : '打开性能计划同步失败') }
}
async function showHistorySync() {
  try { await openPerformanceHistorySync() }
  catch (error) { Message.error(error instanceof Error ? error.message : '打开性能执行历史同步失败') }
}
async function cancelEnginePreparation() { preparationCancelled = true; await cancelJmeterPreparation() }
const canSubmit = computed(() => selectedDetail.value?.status === 'READY')
const statusLabel = (status: string) => ({ DRAFT: '草稿', READY: '待执行', ARCHIVED: '已归档', QUEUED: '队列中', ACCEPTED: '已接收', RUNNING: '执行中', CANCELLING: '取消中', CANCELLED: '已取消', SUCCEEDED: '已完成', FAILED: '失败', REJECTED: '已拒绝', TIMED_OUT: '超时' }[status] ?? status)
const statusColor = (status: string) => ({ DRAFT: 'gray', READY: 'arcoblue', ARCHIVED: 'gray', QUEUED: 'arcoblue', ACCEPTED: 'arcoblue', RUNNING: 'orange', CANCELLING: 'orange', CANCELLED: 'gray', SUCCEEDED: 'green', FAILED: 'red', REJECTED: 'red', TIMED_OUT: 'red' }[status] ?? 'gray')
const qualityLabel = (status: string) => ({ PASSED: '阈值通过', FAILED: '阈值未通过', INCONCLUSIVE: '阈值待确认', NOT_CONFIGURED: '未配置阈值', NOT_EVALUATED: '等待完成' }[status] ?? status)
const qualityColor = (status: string) => ({ PASSED: 'green', FAILED: 'red', INCONCLUSIVE: 'orange', NOT_CONFIGURED: 'gray', NOT_EVALUATED: 'arcoblue' }[status] ?? 'gray')
const qualityDescription = (quality: QualityVerdict) => {
  if (quality.status === 'PASSED') return `已通过 ${quality.passedChecks} 项阈值检查。`
  if (quality.status === 'FAILED') return `${quality.failedChecks} 项阈值检查未通过，请结合线程组明细定位。`
  if (quality.status === 'INCONCLUSIVE') return `有 ${quality.inconclusiveChecks} 项检查缺少有效指标。`
  if (quality.status === 'NOT_CONFIGURED') return '该执行快照没有配置 P95 或错误率阈值。'
  return '执行尚未成功结束，完成后将依据冻结的线程组阈值判定。'
}
const loadModelLabel = (model: string) => ({ STEADY: '恒定负载', RAMP_UP: '线性加压', STEPPED_RAMP: '阶梯加压' }[model] ?? model)
const formatTime = (seconds: number) => seconds >= 60 ? `${Math.floor(seconds / 60)} 分 ${seconds % 60 ? `${seconds % 60} 秒` : ''}` : `${seconds} 秒`
const date = (value: string) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '-'
const size = (bytes: number) => bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
const cpu = (value: number) => `${Number(value).toFixed(2)}%`
const memory = (value: number) => `${(value / 1024 / 1024).toFixed(1)} MB`
const signed = (value: number, digits = 0) => `${value > 0 ? '+' : ''}${Number(value).toFixed(digits)}`
const thresholdColor = (status: string) => ({ PASSED: 'green', FAILED: 'red', INCONCLUSIVE: 'orange', NOT_CONFIGURED: 'gray' }[status] ?? 'gray')
const thresholdLabel = (status: string) => ({ PASSED: '通过', FAILED: '未通过', INCONCLUSIVE: '待确认', NOT_CONFIGURED: '未配置' }[status] ?? status)
const artifactLabel = (type: string) => ({ JMETER_JTL: '原始 JTL', JMETER_LOG: 'JMeter 日志' }[type] ?? type)
const peakResourceCpu = (resources: PerformanceResourceMetric[]) => resources.reduce((maximum, resource) => Math.max(maximum, Number(resource.cpuPercent)), 0)
const peakResourceMemory = (resources: PerformanceResourceMetric[]) => resources.reduce((maximum, resource) => Math.max(maximum, resource.residentMemoryBytes), 0)
const latestResource = (resources: PerformanceResourceMetric[]) => resources.at(-1) ?? null

function defaultThreadGroup(): ThreadGroupInput {
  return { name: '默认线程组', targetUrl: 'https://api.example.test/health', loadModel: 'RAMP_UP', threadCount: 100, rampUpSeconds: 60, durationSeconds: 600, p95ThresholdMillis: 500, errorThresholdPercent: 1, requestMethod: 'GET', requestHeadersText: '{}', queryParametersText: '{}', expectedStatusCode: 200 }
}
function resetForm() {
  Object.assign(planForm, { name: '', description: '', status: 'DRAFT', threadGroups: [defaultThreadGroup()] })
}
function copyGroup(group: ThreadGroup): ThreadGroupInput {
  return { name: group.name, targetUrl: group.targetUrl, loadModel: group.loadModel, threadCount: group.threadCount, rampUpSeconds: group.rampUpSeconds, stepCount: group.stepCount ?? undefined, stepIntervalSeconds: group.stepIntervalSeconds ?? undefined, durationSeconds: group.durationSeconds, targetTps: group.targetTps ?? undefined, p95ThresholdMillis: group.p95ThresholdMillis ?? undefined, errorThresholdPercent: group.errorThresholdPercent ?? undefined, requestMethod: group.requestMethod ?? 'GET', requestHeadersText: JSON.stringify(group.requestHeaders ?? {}, null, 2), queryParametersText: JSON.stringify(group.queryParameters ?? {}, null, 2), requestBody: group.requestBody ?? undefined, expectedStatusCode: group.expectedStatusCode ?? undefined, responseContains: group.responseContains ?? undefined, dataSetCsv: group.dataSetCsv ?? undefined, rendezvousUsers: group.rendezvousUsers ?? undefined }
}

function requestParameters(value: string, label: string): Record<string, string> {
  let parsed: unknown
  try { parsed = JSON.parse(value.trim() || '{}') } catch { throw new Error(`${label}必须是 JSON 对象`) }
  if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(`${label}必须是 JSON 对象`)
  const parameters: Record<string, string> = {}
  for (const [name, headerValue] of Object.entries(parsed)) {
    if (typeof headerValue !== 'string') throw new Error(`${label} ${name} 的值必须是字符串`)
    parameters[name] = headerValue
  }
  return parameters
}
function requestHeaders(value: string) {
  return requestParameters(value, '请求头')
}
function queryParameters(value: string) {
  return requestParameters(value, '查询参数')
}

function groupPayload(group: ThreadGroupInput) {
  return {
    name: group.name, targetUrl: group.targetUrl, loadModel: group.loadModel, threadCount: group.threadCount,
    rampUpSeconds: group.rampUpSeconds, stepCount: group.stepCount, stepIntervalSeconds: group.stepIntervalSeconds,
    durationSeconds: group.durationSeconds, targetTps: group.targetTps,
    p95ThresholdMillis: group.p95ThresholdMillis, errorThresholdPercent: group.errorThresholdPercent,
    requestMethod: group.requestMethod, requestHeaders: requestHeaders(group.requestHeadersText),
    queryParameters: queryParameters(group.queryParametersText),
    requestBody: group.requestBody?.trim() || undefined, expectedStatusCode: group.expectedStatusCode,
    responseContains: group.responseContains?.trim() || undefined, dataSetCsv: group.dataSetCsv || undefined,
    rendezvousUsers: group.rendezvousUsers,
  }
}

function loadModelChanged(group: ThreadGroupInput) {
  if (group.loadModel !== 'STEPPED_RAMP') {
    group.stepCount = undefined
    group.stepIntervalSeconds = undefined
    return
  }
  group.rampUpSeconds = 0
  group.stepCount = Math.min(4, group.threadCount)
  group.stepIntervalSeconds = 15
  group.rendezvousUsers = undefined
}
function executionDuration(group: Pick<ThreadGroup, 'loadModel' | 'durationSeconds' | 'stepCount' | 'stepIntervalSeconds'>) {
  return group.loadModel === 'STEPPED_RAMP' ? group.durationSeconds + ((group.stepCount ?? 1) - 1) * (group.stepIntervalSeconds ?? 0) : group.durationSeconds
}
function stepSummary(group: Pick<ThreadGroup, 'loadModel' | 'stepCount' | 'stepIntervalSeconds'>) {
  return group.loadModel === 'STEPPED_RAMP' ? `${group.stepCount} 阶段 · 每 ${group.stepIntervalSeconds} 秒启动一阶` : null
}

function dataSetSummary(csv: string | null) {
  if (!csv) return '未配置数据集'
  const rows = csv.trim().split(/\r?\n/)
  return `CSV ${Math.max(0, rows.length - 1)} 行 · ${rows[0]?.split(',').length ?? 0} 字段`
}

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}
async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
}
async function loadDetail() {
  const request = ++detailRequest
  const project = projectId.value
  const plan = selectedPlanId.value
  selectedDetail.value = null
  const detail = plan ? await read<PlanDetail>(`/api/v1/projects/${project}/performance/plans/${plan}`) : null
  if (request === detailRequest && project === projectId.value && plan === selectedPlanId.value) selectedDetail.value = detail
}
async function loadPlans() {
  const request = ++plansRequest
  const project = projectId.value
  const data = await read<Plan[]>(`/api/v1/projects/${project}/performance/plans`)
  if (request !== plansRequest || project !== projectId.value) return
  plans.value = data
  if (selectedPlanId.value && !plans.value.some((plan) => plan.id === selectedPlanId.value)) selectedPlanId.value = ''
  if (!selectedPlanId.value && plans.value[0]) selectedPlanId.value = plans.value[0].id
  await loadDetail()
}
async function loadOperationalData() {
  if (!projectId.value) return
  const request = ++operationalRequest
  const project = projectId.value
  const [executionData, runnerData] = await Promise.all([
    read<PerformanceExecution[]>(`/api/v1/projects/${projectId.value}/performance/executions`),
    read<RunnerStatus[]>(`/api/v1/projects/${projectId.value}/performance/runner-status`),
  ])
  if (request !== operationalRequest || project !== projectId.value) return
  executions.value = executionData
  runners.value = runnerData
  operationalError.value = ''
}
async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = session.resolveProjectId(projects.value)
    if (projectId.value) { await loadPlans(); await loadOperationalData() }
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '加载压测计划失败')
  } finally {
    loading.value = false
  }
}
async function changeProject() {
  closeReport()
  planDialog.value = false
  plans.value = []
  executions.value = []
  runners.value = []
  selectedPlanId.value = ''
  selectedDetail.value = null
  try { await loadPlans(); await loadOperationalData() } catch (error) { Message.error(error instanceof Error ? error.message : '切换项目失败') }
}
async function selectPlan(id: string) {
  selectedPlanId.value = id
  try { await loadDetail() } catch (error) { Message.error(error instanceof Error ? error.message : '加载计划详情失败') }
}
function openCreate() {
  editing.value = false
  resetForm()
  planDialog.value = true
}
function openEdit() {
  if (!selectedDetail.value) return
  editing.value = true
  Object.assign(planForm, { name: selectedDetail.value.name, description: selectedDetail.value.description, status: selectedDetail.value.status, threadGroups: selectedDetail.value.threadGroups.map(copyGroup) })
  planDialog.value = true
}
function addThreadGroup() {
  if (planForm.threadGroups.length < 10) planForm.threadGroups.push({ ...defaultThreadGroup(), name: `线程组 ${planForm.threadGroups.length + 1}` })
}
function removeThreadGroup(index: number) {
  if (planForm.threadGroups.length > 1) planForm.threadGroups.splice(index, 1)
}
async function savePlan() {
  saving.value = true
  try {
    const payload = { name: planForm.name, description: planForm.description, status: planForm.status, threadGroups: planForm.threadGroups.map(groupPayload) }
    const detail = editing.value && selectedDetail.value
      ? await mutate<PlanDetail>(`/api/v1/projects/${projectId.value}/performance/plans/${selectedDetail.value.id}`, 'PUT', { expectedVersion: selectedDetail.value.version, plan: payload })
      : await mutate<PlanDetail>(`/api/v1/projects/${projectId.value}/performance/plans`, 'POST', payload)
    planDialog.value = false
    selectedPlanId.value = detail.id
    await loadPlans()
    Message.success(editing.value ? '压测计划已更新' : '压测计划已创建')
    return true
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '保存压测计划失败')
    return false
  } finally {
    saving.value = false
  }
}
async function deletePlan() {
  if (!selectedDetail.value) return
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/performance/plans/${selectedDetail.value.id}`, 'DELETE')
    selectedPlanId.value = ''
    await loadPlans()
    Message.success('压测计划已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除压测计划失败')
  }
}
async function submitExecution() {
  if (!selectedDetail.value || !canSubmit.value || preparingEngine.value) return
  const selectedPlan = selectedDetail.value, selectedProjectId = projectId.value
  preparingEngine.value = true; preparationCancelled = false
  try {
    if (localDesktop) {
      engineTimer = window.setInterval(() => { void localJmeterStatus().then(status => { engineStatus.value = status }).catch(() => {}) }, 300)
      await ensureLocalJmeter(selectedPlan.threadGroups.map(group => new URL(group.targetUrl).origin))
      if (preparationCancelled) return
      if (selectedPlan !== selectedDetail.value || selectedProjectId !== projectId.value) throw new Error('所选压测计划已变化，请重新提交。')
    }
    const execution = await mutate<PerformanceExecution>(`/api/v1/projects/${projectId.value}/performance/plans/${selectedDetail.value.id}/executions`, 'POST', { timeoutSeconds: 3600 })
    executions.value = [execution, ...executions.value]
    Message.success('压测任务已进入安全队列')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '提交压测任务失败')
  } finally {
    preparingEngine.value = false
    if (engineTimer !== undefined) window.clearInterval(engineTimer)
    engineTimer = undefined
  }
}
async function cancelExecution(execution: PerformanceExecution) {
  try {
    const updated = await mutate<PerformanceExecution>(`/api/v1/projects/${projectId.value}/performance/executions/${execution.id}`, 'DELETE')
    executions.value = executions.value.map((item) => item.id === updated.id ? updated : item)
    Message.success(updated.status === 'CANCELLED' ? '压测任务已取消' : '已请求 Runner 取消压测任务')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '取消压测任务失败')
  }
}

function terminal(status: string) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'TIMED_OUT'].includes(status)
}
async function loadReport() {
  if (!selectedReport.value) return
  const request = ++reportRequest
  const project = projectId.value
  const executionId = selectedReport.value.execution.id
  reportLoading.value = true
  try {
    const report = await read<PerformanceReport>(`/api/v1/projects/${project}/performance/executions/${executionId}/report`)
    if (request !== reportRequest || project !== projectId.value || !reportDrawer.value) return
    selectedReport.value = report
    executions.value = executions.value.map((execution) => execution.id === report.execution.id ? report.execution : execution)
    if (terminal(report.execution.status) && reportTimer !== undefined) { window.clearInterval(reportTimer); reportTimer = undefined }
  } catch (error) {
    if (request === reportRequest) Message.error(error instanceof Error ? error.message : '加载压测报告失败')
  } finally {
    if (request === reportRequest) reportLoading.value = false
  }
}
async function openReport(execution: PerformanceExecution) {
  closeReport()
  selectedReport.value = { execution, summary: { sampleCount: 0, successCount: 0, errorCount: 0, errorRatePercent: 0, peakThroughputPerSecond: 0, averageLatencyMillis: 0, p95LatencyMillis: 0, minLatencyMillis: 0, maxLatencyMillis: 0, peakActiveThreads: 0 }, metrics: [], resources: [], errors: [], logs: [], quality: { status: 'NOT_EVALUATED', configuredChecks: 0, passedChecks: 0, failedChecks: 0, inconclusiveChecks: 0, threadGroups: [] }, comparison: null, artifacts: [] }
  reportDrawer.value = true
  await loadReport()
  if (reportDrawer.value && selectedReport.value?.execution.id === execution.id && !terminal(selectedReport.value.execution.status) && reportTimer === undefined) reportTimer = window.setInterval(() => { void loadReport() }, 2000)
}
function closeReport() {
  reportRequest++
  reportLoading.value = false
  if (reportTimer !== undefined) { window.clearInterval(reportTimer); reportTimer = undefined }
  reportDrawer.value = false
  selectedReport.value = null
}
function artifactDownloadUrl(artifact: ExecutionArtifact) {
  if (!selectedReport.value) return '#'
  return `/api/v1/projects/${projectId.value}/performance/executions/${selectedReport.value.execution.id}/artifacts/${artifact.id}/download`
}
function reportExportUrl() {
  if (!selectedReport.value) return '#'
  return `/api/v1/projects/${projectId.value}/performance/executions/${selectedReport.value.execution.id}/report/export`
}

onMounted(() => {
  void load()
  operationalTimer = window.setInterval(() => { if (projectId.value) void loadOperationalData().catch(error => { operationalError.value = error instanceof Error ? error.message : '刷新执行状态失败' }) }, 2000)
})
onBeforeUnmount(() => {
  if (preparingEngine.value && localDesktop) void cancelEnginePreparation()
  if (engineTimer !== undefined) window.clearInterval(engineTimer)
  if (operationalTimer !== undefined) window.clearInterval(operationalTimer)
  closeReport()
})
</script>

<template>
  <div class="page performance-page">
    <PageHeader eyebrow="JMETER RUNNER · CONTROL PLANE" title="性能测试" description="维护压测计划，并通过独立 Runner 执行受限 JMeter 任务、追踪实时指标和查看报告。" action="新建压测计划" @action="openCreate" />
    <div v-if="desktop" class="sync-entry"><a-button @click="showSync">性能计划同步</a-button><a-button @click="showHistorySync">历史同步</a-button><small>计划定义与已结束执行的脱敏报告分别绑定；运行任务和原始工件保留原端。</small></div>

    <a-alert v-if="localDesktop" class="runner-alert" :show-icon="true">
      <div aria-live="polite">{{ preparingEngine ? engineStatus?.message || '正在准备 JMeter…' : '首次提交压测时会自动下载并校验 JMeter，安装后可离线复用。压测只访问本地执行设置中允许的目标。' }}</div>
      <div v-if="preparingEngine && engineStatus?.receivedBytes">已下载 {{ (engineStatus.receivedBytes / 1024 / 1024).toFixed(1) }} MB<span v-if="engineStatus.totalBytes"> / {{ (engineStatus.totalBytes / 1024 / 1024).toFixed(1) }} MB</span></div>
      <a-button v-if="preparingEngine" size="small" @click="cancelEnginePreparation">取消准备</a-button>
      <a-button v-else size="small" @click="openLocalExecutionSettings">本地执行设置</a-button>
    </a-alert>

    <section class="performance-summary panel">
      <div class="project-summary"><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code ?? '—' }}</small></div>
      <a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select>
      <div><span>压测计划</span><strong>{{ plans.length }}</strong><small>{{ readyCount }} 个待执行</small></div>
      <div><span>线程组</span><strong>{{ threadGroupCount }}</strong><small>最多 10 组 / 计划</small></div>
      <div><span>当前并发配置</span><strong>{{ configuredThreads.toLocaleString() }}</strong><small>{{ selectedDetail ? '所选计划汇总' : '选择计划查看' }}</small></div>
      <div class="runner-state" :class="runnerTone"><span>JMeter Runner</span><strong><i></i>{{ runnerLabel }}</strong><small>{{ activeRunner ? `${activeRunner.runnerId} · ${activeRunner.runnerVersion}` : '等待 Runner 心跳' }}</small></div>
      <div><span>Runner 池槽位</span><strong>{{ availableRunnerSlots }} / {{ runnerCapacity }}</strong><small>{{ runnerPoolLabel }}</small></div>
    </section>

    <a-alert v-if="!localDesktop || readyRunners.length" class="runner-alert" :type="readyRunners.length ? 'success' : 'info'" :show-icon="true">{{ readyRunners.length ? `已接入 ${readyRunners.length} 个可执行 JMeter Runner，当前 ${runnerPoolLabel}。满载 Runner 会延迟并重新投递任务；Runner 失联时，已分配任务会明确失败而不会重复压测。` : 'JMeter Runner 当前处于执行禁用状态。提交任务会被受控拒绝，不会向任何目标地址发送压测流量。' }}</a-alert>

    <section v-if="runners.length" class="runner-pool panel">
      <div class="section-title"><div><strong>Runner 调度池</strong><span>{{ runnerPoolLabel }}</span></div><small>心跳每两秒刷新</small></div>
      <div class="runner-grid">
        <article v-for="runner in runners" :key="runner.runnerId" class="runner-card">
          <div><strong>{{ runner.runnerId }}</strong><small>{{ runner.runnerVersion }} · {{ runner.trusted ? '已验证' : runner.trustStatus }}</small></div>
          <a-tag :color="runner.trusted && runner.executionEnabled ? 'green' : 'gray'">{{ runner.executionEnabled ? `${runner.activeTaskCount} / ${runner.maxConcurrentTasks} 已用` : '执行禁用' }}</a-tag>
          <small>心跳：{{ date(runner.heartbeatAt) }}</small>
        </article>
      </div>
    </section>

    <a-alert v-if="operationalError" type="warning">{{ operationalError }}；正在自动重试。</a-alert>

    <section class="performance-layout">
      <aside class="plan-list panel">
        <div class="section-heading"><div><span class="eyebrow">LOAD PLANS</span><h3>压测计划</h3></div><a-button type="text" size="small" @click="openCreate"><icon-plus /></a-button></div>
        <a-spin :loading="loading">
          <button v-for="plan in plans" :key="plan.id" class="plan-item" :class="{ selected: selectedPlanId === plan.id }" @click="selectPlan(plan.id)">
            <div><strong>{{ plan.name }}</strong><small>{{ plan.threadGroupCount }} 个线程组 · {{ date(plan.updatedAt) }}</small></div><a-tag :color="statusColor(plan.status)">{{ statusLabel(plan.status) }}</a-tag>
          </button>
          <a-empty v-if="!loading && plans.length === 0" description="还没有压测计划" />
        </a-spin>
        <a-button long type="outline" class="create-plan-button" @click="openCreate"><icon-plus /> 创建压测计划</a-button>
      </aside>

      <div v-if="selectedDetail" class="plan-content">
        <section class="plan-header panel">
          <div><div class="eyebrow">PLAN · V{{ selectedDetail.version }}</div><h2>{{ selectedDetail.name }}</h2><p>{{ selectedDetail.description || '暂未填写计划说明。' }}</p></div>
          <a-space><a-button :disabled="!canSubmit" :loading="preparingEngine" @click="submitExecution"><icon-play-circle /> 提交压测</a-button><a-popconfirm content="删除后线程组配置将不可恢复，确认删除吗？" @ok="deletePlan"><a-button status="danger">删除</a-button></a-popconfirm><a-button type="primary" @click="openEdit"><icon-edit /> 编辑配置</a-button></a-space>
        </section>

        <section class="plan-overview panel">
          <div><span>线程组数量</span><strong>{{ selectedDetail.threadGroups.length }}</strong></div>
          <div><span>总并发线程</span><strong>{{ configuredThreads.toLocaleString() }}</strong></div>
          <div><span>最长总时长</span><strong>{{ selectedDetail.threadGroups.length ? formatTime(Math.max(...selectedDetail.threadGroups.map(executionDuration))) : '—' }}</strong></div>
          <div><span>Runner 状态</span><strong :class="runnerTone === 'ready' ? 'ready' : 'pending'">{{ runnerPoolLabel }}</strong></div>
        </section>

        <section class="thread-groups">
          <article v-for="group in selectedDetail.threadGroups" :key="group.id" class="thread-group panel">
            <header><div><span class="eyebrow">THREAD GROUP {{ group.sortOrder + 1 }}</span><h3>{{ group.name }}</h3></div><a-space><a-tag color="purple">{{ group.requestMethod }}</a-tag><a-tag color="arcoblue">{{ loadModelLabel(group.loadModel) }}</a-tag></a-space></header>
            <p class="target-url"><strong>{{ group.requestMethod }}</strong> {{ group.targetUrl }}</p>
            <div class="group-metrics"><div><span>并发线程</span><strong>{{ group.threadCount.toLocaleString() }}</strong></div><div><span>{{ group.loadModel === 'STEPPED_RAMP' ? '总运行时长' : '加压时长' }}</span><strong>{{ formatTime(group.loadModel === 'STEPPED_RAMP' ? executionDuration(group) : group.rampUpSeconds) }}</strong></div><div><span>持续时间</span><strong>{{ formatTime(group.durationSeconds) }}</strong></div><div><span>目标 TPS</span><strong>{{ group.targetTps?.toLocaleString() ?? '未设置' }}</strong></div></div>
            <div class="request-summary"><span v-if="stepSummary(group)">{{ stepSummary(group) }}</span><span>请求头 {{ Object.keys(group.requestHeaders).length }} 项</span><span>查询参数 {{ Object.keys(group.queryParameters).length }} 项</span><span>{{ group.requestBody ? `正文 ${group.requestBody.length} 字符` : '无请求正文' }}</span><span>{{ group.expectedStatusCode ? `状态码 = ${group.expectedStatusCode}` : '未断言状态码' }}</span><span>{{ group.responseContains ? '已配置响应包含断言' : '未断言响应正文' }}</span><span>{{ dataSetSummary(group.dataSetCsv) }}</span><span>{{ group.rendezvousUsers ? `集合点 ${group.rendezvousUsers} 线程` : '未配置集合点' }}</span></div>
            <footer><span>P95 ≤ {{ group.p95ThresholdMillis ? `${group.p95ThresholdMillis}ms` : '未设置' }}</span><span>错误率 ≤ {{ group.errorThresholdPercent ?? '未设置' }}{{ group.errorThresholdPercent === null ? '' : '%' }}</span></footer>
          </article>
        </section>

        <section class="execution-panel panel">
          <div class="section-title"><div><strong>压测任务</strong><span>{{ selectedExecutions.length }} 条</span></div><a-button size="small" :disabled="!canSubmit" :loading="preparingEngine" @click="submitExecution"><icon-plus /> 提交压测</a-button></div>
          <a-table :data="selectedExecutions" :pagination="false" row-key="id" size="small">
            <template #columns>
              <a-table-column title="任务"><template #cell="{ record }"><div class="execution-name"><strong>{{ record.planName }}</strong><small>{{ record.taskId }}</small></div></template></a-table-column>
              <a-table-column title="状态"><template #cell="{ record }"><a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag><small class="execution-code">{{ record.statusCode }}</small></template></a-table-column>
              <a-table-column title="Runner"><template #cell="{ record }"><div>{{ record.runnerId ?? '等待分配' }}</div><small v-if="record.routingAttempts" class="execution-code">调度尝试 {{ record.routingAttempts }} 次</small></template></a-table-column>
              <a-table-column title="提交时间"><template #cell="{ record }">{{ date(record.requestedAt) }}</template></a-table-column>
              <a-table-column title="操作" :width="164"><template #cell="{ record }"><a-space><a-button size="mini" @click="openReport(record)">报告</a-button><a-popconfirm v-if="['QUEUED', 'ACCEPTED', 'RUNNING', 'CANCELLING'].includes(record.status)" content="确认请求取消该压测任务吗？" @ok="cancelExecution(record)"><a-button size="mini" status="danger">取消</a-button></a-popconfirm></a-space></template></a-table-column>
            </template>
          </a-table>
          <a-empty v-if="selectedExecutions.length === 0" description="尚未提交压测任务；将计划切换为“待执行”后即可提交。" />
        </section>
      </div>

      <div v-else class="panel empty-plan"><a-empty description="选择或创建压测计划，开始维护负载配置。"><template #extra><a-button type="primary" @click="openCreate">新建压测计划</a-button></template></a-empty></div>
    </section>

    <PerformanceHistoryPanel :project-id="projectId" />

    <a-modal v-model:visible="planDialog" :title="editing ? '编辑压测计划' : '新建压测计划'" width="900px" :ok-loading="saving" :mask-closable="false" :on-before-ok="savePlan">
      <a-form :model="planForm" layout="vertical">
        <div class="form-grid"><a-form-item label="计划名称" required><a-input v-model="planForm.name" placeholder="例如：订单核心链路基准压测" /></a-form-item><a-form-item label="计划状态"><a-select v-model="planForm.status"><a-option value="DRAFT">草稿</a-option><a-option value="READY">待执行</a-option><a-option value="ARCHIVED">已归档</a-option></a-select></a-form-item></div>
        <a-form-item label="计划说明"><a-textarea v-model="planForm.description" :auto-size="{ minRows: 2, maxRows: 4 }" placeholder="说明容量目标、场景范围或验收依据" /></a-form-item>
        <div class="form-section-heading"><div><strong>线程组配置</strong><span>每个计划 1–10 个线程组；请求头不支持 Authorization、Cookie 等敏感认证字段，后续通过项目密钥注入。</span></div><a-button type="outline" size="small" :disabled="planForm.threadGroups.length >= 10" @click="addThreadGroup"><icon-plus /> 添加线程组</a-button></div>
        <article v-for="(group, index) in planForm.threadGroups" :key="index" class="thread-group-form">
          <header><strong>线程组 {{ index + 1 }}</strong><a-button type="text" status="danger" size="small" :disabled="planForm.threadGroups.length === 1" @click="removeThreadGroup(index)"><icon-delete /> 删除</a-button></header>
          <div class="form-grid group-title-grid"><a-form-item label="线程组名称" required><a-input v-model="group.name" placeholder="例如：下单主链路" /></a-form-item><a-form-item label="负载模型"><a-select v-model="group.loadModel" @change="loadModelChanged(group)"><a-option value="RAMP_UP">线性加压</a-option><a-option value="STEADY">恒定负载</a-option><a-option value="STEPPED_RAMP">阶梯加压</a-option></a-select></a-form-item></div>
          <a-form-item label="目标 URL" required><a-input v-model="group.targetUrl" placeholder="https://api.example.test/orders" /></a-form-item>
          <div class="request-grid"><a-form-item label="请求方法"><a-select v-model="group.requestMethod"><a-option value="GET">GET</a-option><a-option value="POST">POST</a-option><a-option value="PUT">PUT</a-option><a-option value="PATCH">PATCH</a-option><a-option value="DELETE">DELETE</a-option><a-option value="HEAD">HEAD</a-option></a-select></a-form-item><a-form-item label="预期状态码"><a-input-number v-model="group.expectedStatusCode" :min="100" :max="599" placeholder="可选" /></a-form-item><a-form-item label="响应包含文本"><a-input v-model="group.responseContains" :max-length="1000" placeholder="可选，使用大小写敏感文本" /></a-form-item></div>
          <div class="request-content-grid"><a-form-item label="请求头（JSON 对象）"><a-textarea v-model="group.requestHeadersText" :auto-size="{ minRows: 3, maxRows: 7 }" placeholder="{&quot;Content-Type&quot;:&quot;application/json&quot;}" /></a-form-item><a-form-item label="查询参数（JSON 对象）"><a-textarea v-model="group.queryParametersText" :auto-size="{ minRows: 3, maxRows: 7 }" placeholder="{&quot;page&quot;:&quot;1&quot;,&quot;source&quot;:&quot;${source}&quot;}" /><template #extra>目标 URL 不可直接携带 <code>?query</code>；参数会由 Runner 安全编码，值可引用当前 CSV 数据集变量。</template></a-form-item><a-form-item label="请求正文"><a-textarea v-model="group.requestBody" :auto-size="{ minRows: 3, maxRows: 7 }" :max-length="65536" :disabled="group.requestMethod === 'GET' || group.requestMethod === 'HEAD'" placeholder="POST / PUT / PATCH / DELETE 可使用原始请求正文" /></a-form-item></div>
          <a-form-item label="内联 CSV 数据集"><a-textarea v-model="group.dataSetCsv" :auto-size="{ minRows: 3, maxRows: 8 }" :max-length="65536" placeholder="orderId,quantity&#10;A-100,1&#10;B-200,2" /><template #extra>首行为变量名，最多 20 列、500 行；可在目标路径、请求头、正文和响应断言中使用 <code>${variable}</code>。</template></a-form-item>
          <a-form-item label="集合点等待线程"><a-input-number v-model="group.rendezvousUsers" :min="2" :max="group.threadCount" :disabled="group.loadModel === 'STEPPED_RAMP'" placeholder="可选" /><template #extra>{{ group.loadModel === 'STEPPED_RAMP' ? '阶梯加压会展开为多个 JMeter 核心线程组，暂不支持跨阶段集合点同步。' : '达到指定线程数后同时放行；Runner 固定最多等待 30 秒，适用于单个隔离 JMeter 进程内的突发压力。' }}</template></a-form-item>
          <div class="number-grid"><a-form-item label="并发线程" required><a-input-number v-model="group.threadCount" :min="1" :max="100000" /></a-form-item><a-form-item v-if="group.loadModel !== 'STEPPED_RAMP'" label="加压时长（秒）" required><a-input-number v-model="group.rampUpSeconds" :min="0" :max="86400" /></a-form-item><a-form-item v-else label="阶梯阶段数" required><a-input-number v-model="group.stepCount" :min="2" :max="Math.min(group.threadCount, 20)" /></a-form-item><a-form-item v-if="group.loadModel === 'STEPPED_RAMP'" label="阶梯间隔（秒）" required><a-input-number v-model="group.stepIntervalSeconds" :min="1" :max="86400" /><template #extra>每个阶段新增一批线程；最后一阶启动后保持下方配置的持续时长。</template></a-form-item><a-form-item label="持续时长（秒）" required><a-input-number v-model="group.durationSeconds" :min="10" :max="86400" /></a-form-item><a-form-item label="目标 TPS"><a-input-number v-model="group.targetTps" :min="group.loadModel === 'STEPPED_RAMP' ? (group.stepCount ?? 2) : 1" :max="1000000" placeholder="可选" /></a-form-item><a-form-item label="P95 阈值（毫秒）"><a-input-number v-model="group.p95ThresholdMillis" :min="1" :max="600000" placeholder="可选" /></a-form-item><a-form-item label="错误率阈值（%）"><a-input-number v-model="group.errorThresholdPercent" :min="0" :max="100" :precision="2" placeholder="可选" /></a-form-item></div>
        </article>
      </a-form>
    </a-modal>

    <a-drawer v-model:visible="reportDrawer" width="760px" :footer="false" @cancel="closeReport">
      <template #title>压测执行报告</template>
      <a-spin :loading="reportLoading">
        <template v-if="selectedReport">
          <section class="report-heading"><div><strong>{{ selectedReport.execution.planName }}</strong><span>{{ selectedReport.execution.taskId }}</span></div><a-space><a-tag :color="statusColor(selectedReport.execution.status)">{{ statusLabel(selectedReport.execution.status) }}</a-tag><a-tag :color="qualityColor(selectedReport.quality.status)">{{ qualityLabel(selectedReport.quality.status) }}</a-tag><a-link :href="reportExportUrl()" target="_blank">导出 CSV</a-link><a-button size="small" @click="loadReport"><icon-refresh /> 刷新</a-button></a-space></section>
          <section class="report-summary"><div><span>样本数</span><strong>{{ selectedReport.summary.sampleCount.toLocaleString() }}</strong></div><div><span>峰值 TPS</span><strong>{{ Number(selectedReport.summary.peakThroughputPerSecond).toFixed(2) }}</strong></div><div><span>P95</span><strong>{{ selectedReport.summary.p95LatencyMillis }}ms</strong></div><div><span>错误率</span><strong>{{ Number(selectedReport.summary.errorRatePercent).toFixed(2) }}%</strong></div><div><span>峰值并发</span><strong>{{ selectedReport.summary.peakActiveThreads }}</strong></div></section>
          <section class="report-panel quality-panel"><div class="report-panel-title"><h3>质量阈值判定</h3><span>{{ selectedReport.quality.configuredChecks }} 项已配置</span></div><a-alert :type="selectedReport.quality.status === 'FAILED' ? 'error' : selectedReport.quality.status === 'PASSED' ? 'success' : 'info'" :show-icon="true">{{ qualityDescription(selectedReport.quality) }}</a-alert><div class="threshold-list"><article v-for="threshold in selectedReport.quality.threadGroups" :key="threshold.threadGroupName" class="threshold-item"><div class="threshold-title"><strong>{{ threshold.threadGroupName }}</strong><a-tag size="small" :color="thresholdColor(threshold.status)">{{ thresholdLabel(threshold.status) }}</a-tag></div><small>{{ threshold.sampleCount.toLocaleString() }} 样本 · P95 {{ threshold.p95LatencyMillis }}ms · 错误率 {{ Number(threshold.errorRatePercent).toFixed(2) }}%</small><p v-if="threshold.p95ThresholdMillis !== null || threshold.errorThresholdPercent !== null">阈值：<span v-if="threshold.p95ThresholdMillis !== null">P95 ≤ {{ threshold.p95ThresholdMillis }}ms</span><span v-if="threshold.p95ThresholdMillis !== null && threshold.errorThresholdPercent !== null"> · </span><span v-if="threshold.errorThresholdPercent !== null">错误率 ≤ {{ threshold.errorThresholdPercent }}%</span></p><p v-for="breach in threshold.breaches" :key="breach" class="threshold-breach">{{ breach }}</p></article></div><a-empty v-if="selectedReport.quality.threadGroups.length === 0" description="任务完成后将依据冻结的线程组阈值进行判定" /></section>
          <section class="report-panel"><div class="report-panel-title"><h3>错误归因</h3><span>仅汇总失败状态码，不保留响应正文</span></div><div class="error-list"><article v-for="error in selectedReport.errors" :key="`${error.threadGroupName}-${error.responseCode}`" class="error-item"><div><strong>{{ error.threadGroupName }}</strong><small>最后汇总：{{ date(error.occurredAt) }}</small></div><div><a-tag color="red">{{ error.responseCode }}</a-tag><strong>{{ error.errorCount.toLocaleString() }} 次</strong></div></article></div><a-empty v-if="selectedReport.errors.length === 0" description="暂无失败样本摘要" /></section>
          <section v-if="selectedReport.comparison" class="report-panel"><div class="report-panel-title"><h3>与上一成功执行对比</h3><span>基线：{{ date(selectedReport.comparison.baseline.requestedAt) }} · v{{ selectedReport.comparison.baseline.planVersion }}</span></div><div class="comparison-grid"><div><span>样本数</span><strong>{{ signed(selectedReport.comparison.delta.sampleCount) }}</strong></div><div><span>峰值 TPS</span><strong>{{ signed(selectedReport.comparison.delta.peakThroughputPerSecond, 2) }}</strong></div><div><span>P95</span><strong>{{ signed(selectedReport.comparison.delta.p95LatencyMillis) }}ms</strong></div><div><span>错误率</span><strong>{{ signed(selectedReport.comparison.delta.errorRatePercent, 2) }}%</strong></div></div><small class="comparison-note">差异 = 当前执行 − 基线执行；P95 和错误率为负值通常代表质量改善。</small></section>
          <section class="report-panel"><h3>实时趋势</h3><PerformanceExecutionChart :metrics="selectedReport.metrics" /><a-empty v-if="selectedReport.metrics.length === 0" description="等待 JMeter Runner 回传首批指标" /></section>
          <section class="report-panel"><div class="report-panel-title"><h3>运行资源监控</h3><span>仅采集隔离 JMeter 子进程，不包含控制面服务</span></div><template v-if="selectedReport.resources.length"><div class="resource-summary"><div><span>峰值 CPU</span><strong>{{ cpu(peakResourceCpu(selectedReport.resources)) }}</strong></div><div><span>最新 CPU</span><strong>{{ cpu(latestResource(selectedReport.resources)?.cpuPercent ?? 0) }}</strong></div><div><span>峰值常驻内存</span><strong>{{ memory(peakResourceMemory(selectedReport.resources)) }}</strong></div><div><span>最新常驻内存</span><strong>{{ memory(latestResource(selectedReport.resources)?.residentMemoryBytes ?? 0) }}</strong></div></div><PerformanceResourceChart :resources="selectedReport.resources" /><small class="resource-note">Runner：{{ latestResource(selectedReport.resources)?.runnerId }} · 最近采集：{{ date(latestResource(selectedReport.resources)?.occurredAt ?? '') }}</small></template><a-empty v-else description="任务启动后将每秒采集隔离 JMeter 进程的 CPU 与常驻内存" /></section>
          <section class="report-panel"><div class="report-panel-title"><h3>原始执行工件</h3><span>{{ localDesktop ? '本地存储' : 'MinIO' }} · 项目权限下载</span></div><div class="artifact-list"><div v-for="artifact in selectedReport.artifacts" :key="artifact.id" class="artifact-item"><div><strong>{{ artifactLabel(artifact.artifactType) }}</strong><small>{{ artifact.originalName }} · {{ size(artifact.sizeBytes) }} · {{ date(artifact.createdAt) }}</small></div><a-link :href="artifactDownloadUrl(artifact)" target="_blank">下载</a-link></div></div><a-empty v-if="selectedReport.artifacts.length === 0" description="Runner 完成后将归档 results.jtl 与 jmeter.log" /></section>
          <section class="report-panel"><h3>执行日志</h3><a-timeline><a-timeline-item v-for="log in selectedReport.logs" :key="`${log.occurredAt}-${log.code}`" :label="date(log.occurredAt)"><strong>{{ log.code }}</strong><p>{{ log.message }}</p></a-timeline-item></a-timeline><a-empty v-if="selectedReport.logs.length === 0" description="暂无执行日志" /></section>
        </template>
      </a-spin>
    </a-drawer>
  </div>
</template>

<style scoped>
.sync-entry{display:flex;align-items:center;gap:12px;margin-bottom:16px}.sync-entry small{color:var(--color-text-3)}
.performance-summary{display:grid;grid-template-columns:1.25fr 1.1fr repeat(5,.8fr);gap:18px;align-items:center;margin-bottom:16px}.performance-summary>div{display:grid;gap:4px;min-width:0}.performance-summary span,.performance-summary small,.plan-item small,.plan-header p,.group-metrics span,.plan-overview span,.thread-group footer,.form-section-heading span,.execution-name small,.execution-code,.runner-card small,.runner-pool>.section-title>small,.request-summary{color:var(--color-text-3);font-size:12px}.performance-summary strong{font-size:20px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-summary strong{font-size:16px}.runner-state strong{font-size:14px;color:var(--color-text-2);display:flex;align-items:center;gap:6px}.runner-state i{width:7px;height:7px;border-radius:50%;background:rgb(var(--danger-6))}.runner-state.pending i{background:rgb(var(--warning-6))}.runner-state.ready i{background:rgb(var(--success-6))}.plan-overview .pending{font-size:14px;color:rgb(var(--warning-6))}.plan-overview .ready{font-size:14px;color:rgb(var(--success-6))}.runner-alert{margin-bottom:18px}.runner-pool{padding:16px 20px;margin-bottom:18px}.runner-pool .section-title{margin-bottom:12px}.runner-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:10px}.runner-card{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:7px 12px;padding:12px;border:1px solid var(--color-border-2);border-radius:7px;background:var(--color-fill-1)}.runner-card>div{display:grid;gap:3px;min-width:0}.runner-card strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.runner-card>small{grid-column:1/-1}.performance-layout{display:grid;grid-template-columns:278px minmax(0,1fr);gap:18px;align-items:start}.plan-list{padding:16px 10px;min-height:590px}.section-heading,.section-title{display:flex;align-items:center;justify-content:space-between}.section-heading{padding:0 6px 12px}.section-heading h3{margin:4px 0 0;font-size:16px}.section-title{margin-bottom:16px}.section-title>div{display:flex;align-items:baseline;gap:10px}.section-title strong{font-size:16px}.plan-item{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;border:0;background:transparent;border-radius:8px;text-align:left;cursor:pointer;padding:12px 10px}.plan-item:hover,.plan-item.selected{background:rgb(var(--primary-1))}.plan-item>div{display:grid;gap:4px;min-width:0}.plan-item strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.create-plan-button{margin-top:18px}.plan-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;padding:22px 24px;margin-bottom:18px}.plan-header h2{margin:5px 0 6px;font-size:22px}.plan-header p{margin:0;max-width:660px;line-height:1.6}.plan-overview{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;padding:0;margin-bottom:18px;overflow:hidden}.plan-overview>div{display:grid;gap:6px;padding:17px 20px;background:var(--color-fill-1)}.plan-overview strong{font-size:18px}.thread-groups{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.thread-group{padding:20px}.thread-group header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.thread-group h3{margin:4px 0 0;font-size:16px}.target-url{padding:10px 12px;margin:16px 0;background:var(--color-fill-2);border-radius:6px;color:var(--color-text-2);font-family:var(--font-family-mono);font-size:12px;overflow-wrap:anywhere}.target-url strong{margin-right:6px;color:rgb(var(--primary-6))}.group-metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:14px}.group-metrics>div{display:grid;gap:5px}.group-metrics strong{font-size:16px}.request-summary{display:flex;flex-wrap:wrap;gap:6px 12px;padding:12px 0 0}.request-summary span{padding:3px 7px;border-radius:4px;background:var(--color-fill-2)}.thread-group footer{display:flex;justify-content:space-between;gap:12px;margin-top:18px;padding-top:13px;border-top:1px solid var(--color-border-2)}.execution-panel{padding:20px;margin-top:18px}.execution-name{display:grid;gap:4px;max-width:260px}.execution-name strong,.execution-name small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.execution-code{display:block;margin-top:4px}.empty-plan{min-height:430px;display:grid;place-items:center}.form-grid{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(190px,.5fr);gap:16px}.form-section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;padding:8px 0 14px;border-top:1px solid var(--color-border-2)}.form-section-heading>div{display:grid;gap:5px}.thread-group-form{padding:16px;margin-bottom:16px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.thread-group-form>header{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}.group-title-grid{grid-template-columns:minmax(0,1fr) 190px}.request-grid{display:grid;grid-template-columns:150px 170px minmax(0,1fr);gap:0 16px}.request-content-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.number-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 16px}@media (max-width:1250px){.performance-summary{grid-template-columns:1fr 1fr 1fr}.performance-layout{grid-template-columns:240px minmax(0,1fr)}}@media (max-width:920px){.performance-layout,.thread-groups{grid-template-columns:1fr}.plan-list{min-height:auto}.plan-overview{grid-template-columns:repeat(2,1fr)}.request-grid,.request-content-grid{grid-template-columns:1fr}}@media (max-width:680px){.performance-summary,.form-grid,.group-title-grid,.request-grid,.request-content-grid,.number-grid{grid-template-columns:1fr}.plan-header,.form-section-heading{flex-direction:column}.thread-group footer{flex-direction:column;gap:5px}}
.report-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.report-heading>div{display:grid;gap:4px;min-width:0}.report-heading strong{font-size:17px}.report-heading span{font:12px var(--font-family-mono);color:var(--color-text-3);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.report-summary{display:grid;grid-template-columns:repeat(5,1fr);gap:1px;background:var(--color-border-2);overflow:hidden;border-radius:8px;margin-bottom:18px}.report-summary>div{display:grid;gap:7px;padding:15px;background:var(--color-bg-2)}.report-summary span{font-size:12px;color:var(--color-text-3)}.report-summary strong{font-size:18px}.report-panel{padding:16px;border:1px solid var(--color-border-2);border-radius:8px;margin-bottom:16px}.report-panel h3{margin:0 0 10px;font-size:15px}.report-panel p{margin:4px 0;color:var(--color-text-2)}.report-panel-title{display:flex;justify-content:space-between;align-items:baseline;gap:16px;margin-bottom:10px}.report-panel-title h3{margin:0}.report-panel-title span,.comparison-note,.resource-note{color:var(--color-text-3);font-size:12px}.threshold-list{display:grid;gap:10px;margin-top:14px}.threshold-item{padding:12px;border:1px solid var(--color-border-2);border-radius:6px;background:var(--color-fill-1)}.threshold-title{display:flex;align-items:center;justify-content:space-between;gap:12px}.threshold-item small{display:block;margin-top:6px;color:var(--color-text-3)}.threshold-item p{font-size:12px}.threshold-breach{color:rgb(var(--danger-6))!important}.error-list{display:grid;gap:8px}.error-item{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 12px;border-radius:6px;background:var(--color-fill-1)}.error-item>div{display:grid;gap:4px}.error-item>div:last-child{display:flex;align-items:center;gap:8px}.error-item small{color:var(--color-text-3);font-size:12px}.error-item strong{font-size:14px}.comparison-grid,.resource-summary{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:var(--color-border-2);border-radius:6px;overflow:hidden}.comparison-grid>div,.resource-summary>div{display:grid;gap:6px;padding:12px;background:var(--color-fill-1)}.comparison-grid span,.resource-summary span{font-size:12px;color:var(--color-text-3)}.comparison-grid strong,.resource-summary strong{font-size:16px}.comparison-note,.resource-note{display:block;margin-top:10px}.artifact-list{display:grid;gap:8px}.artifact-item{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 12px;border-radius:6px;background:var(--color-fill-1)}.artifact-item>div{display:grid;gap:4px}.artifact-item small{color:var(--color-text-3);font-size:12px}@media (max-width:920px){.report-summary{grid-template-columns:repeat(3,1fr)}.comparison-grid,.resource-summary{grid-template-columns:repeat(2,1fr)}}@media (max-width:680px){.report-summary,.comparison-grid,.resource-summary{grid-template-columns:1fr}.report-panel-title{align-items:flex-start;flex-direction:column;gap:5px}}
</style>
