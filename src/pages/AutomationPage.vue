<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import PageHeader from '@/components/PageHeader.vue'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
type StepType = 'REQUEST' | 'PARALLEL' | 'EXTRACT' | 'ASSERT' | 'CONDITION' | 'LOOP' | 'WAIT' | 'REUSABLE_STEP' | 'SUB_SCENARIO'
interface AutomationStep { id: string; type: StepType; name: string; config: Record<string, unknown> }
interface ParallelRequest { method: string; url: string; body?: string; contentType?: string; maxAttempts?: number }
interface Scenario { id: string; organizationId: string; projectId: string; name: string; description: string; environmentCode: string; failureStrategy: 'STOP' | 'CONTINUE'; version: number; stepCount: number; createdBy: string; createdAt: string; updatedAt: string }
interface ScenarioDetail extends Omit<Scenario, 'stepCount'> { steps: AutomationStep[]; variables: Record<string, string>; preRequestScript: string; postResponseScript: string }
interface Revision { id: string; scenarioId: string; version: number; createdBy: string; createdAt: string }
interface VariableRow { key: string; value: string }
interface ApiRunnerStatus { runnerId: string; runnerType: 'api'; protocolVersion: string; runnerVersion: string; executionEnabled: boolean; trusted: boolean; trustStatus: 'VERIFIED' | 'UNVERIFIED' | 'NOT_CONFIGURED'; startedAt: string; heartbeatAt: string }
interface AutomationExecution { id: string; taskId: string; scenarioId: string; scenarioName: string; scenarioVersion: number; executionProfileId: string | null; executionProfileVersion: number | null; dataSetId: string | null; dataSetRowNumber: number | null; status: string; statusCode: string | null; statusMessage: string; runnerId: string | null; requestedAt: string; deadlineAt: string; finishedAt: string | null }
interface AutomationExecutionLog { id: string; executionId: string; source: string; runnerId: string | null; level: string; code: string; message: string; occurredAt: string; createdAt: string }
interface AutomationExecutionManifestStep { id: string; type: string; name?: string | null; requestMethod?: string | null; parallelRequestCount: number; parallelRequestMethods: string[]; configKeys: string[] }
interface AutomationExecutionManifestBinding { name: string; source: 'SCENARIO' | 'PROJECT_PARAMETER' | 'EXECUTION_PROFILE' | 'EXECUTION_OVERRIDE' | 'DATA_SET' | 'ENVIRONMENT_SECRET' | 'RUNNER_LOCAL'; secret: boolean }
interface AutomationExecutionManifestReference { name: string; source: 'SCENARIO' | 'PROJECT_PARAMETER' | 'EXECUTION_PROFILE' | 'EXECUTION_OVERRIDE' | 'DATA_SET' | 'ENVIRONMENT_SECRET' | 'RUNNER_LOCAL'; secret: boolean; locations: Array<{ stepId: string; configKey: string }> }
interface AutomationExecutionManifest { id: string; executionId: string; scenarioId: string; scenarioVersion: number; checksum: string; stepCount: number; variableCount: number; secretReferenceCount: number; createdAt: string; snapshot: { contract: string; manifestVersion: number; environmentCode: string; failureStrategy: string; steps: AutomationExecutionManifestStep[]; variableBindings: AutomationExecutionManifestBinding[]; variableReferences: AutomationExecutionManifestReference[]; scripts: { preRequestConfigured: boolean; preRequestChecksum: string | null; postResponseConfigured: boolean; postResponseChecksum: string | null } } }
interface AutomationExecutionRuntimeSnapshot { id: string; executionId: string; checksum: string; variableCount: number; withheldSecretCount: number; createdAt: string; snapshot: { contract: string; snapshotVersion: number; environmentCode: string; variables: Array<{ name: string; source: 'SCENARIO' | 'PROJECT_PARAMETER' | 'EXECUTION_PROFILE' | 'EXECUTION_OVERRIDE' | 'DATA_SET' }>; withheldSecretNames: string[] } }
interface AutomationExecutionHttpResult { id: string; executionId: string; stepId: string; requestSequence: number; attemptNumber: number; method: string; outcome: 'SUCCEEDED' | 'FAILED'; statusCode: number | null; durationMillis: number; runnerId: string; occurredAt: string; createdAt: string }
interface AutomationExecutionStepResult { id: string; executionId: string; stepId: string; stepSequence: number; stepType: 'EXTRACT' | 'ASSERT'; outcome: 'COMPLETED' | 'PASSED' | 'FAILED'; observedStatusCode: number; runnerId: string; occurredAt: string; createdAt: string }
interface AutomationExecutionScriptResult { id: string; executionId: string; phase: 'PRE_REQUEST' | 'POST_RESPONSE'; testCount: number; passedCount: number; failedCount: number; runnerId: string; occurredAt: string; createdAt: string }
interface AutomationExecutionReportIntegrity { contract: string; artifactVersion: number; reportContract: string; reportVersion: number; sha256: string; eTag: string; mediaType: string; filename: string }
interface AutomationWebhook { id: string; organizationId: string; projectId: string; scenarioId: string; name: string; enabled: boolean; createdBy: string; createdAt: string; updatedAt: string }
interface CreatedAutomationWebhook { webhook: AutomationWebhook; secret: string }
interface AutomationExecutionReport { contract: string; reportVersion: number; failureStrategy: { strategy: 'STOP' | 'CONTINUE'; continuedFailureCount: number } | null; summary: { httpResultCount: number; httpSucceededCount: number; httpFailedCount: number; httpDurationMillis: number; stepResultCount: number; assertionPassedCount: number; assertionFailedCount: number; scriptResultCount: number; scriptTestCount: number; scriptPassedCount: number; scriptFailedCount: number }; parallelGroups: Array<{ stepId: string; configuredBranchCount: number; completedBranchCount: number; succeededBranchCount: number; failedBranchCount: number; attemptCount: number; retryAttemptCount: number; firstRequestSequence: number | null; lastRequestSequence: number | null; totalDurationMillis: number }> }
interface AutomationSchedule { id: string; organizationId: string; projectId: string; scenarioId: string; name: string; cronExpression: string; timezone: string; timeoutSeconds: number; maxConsecutiveFailures: number; consecutiveFailures: number; enabled: boolean; lastExecutionId: string | null; lastExecutionStatus: string | null; lastTriggeredAt: string | null; createdBy: string; createdAt: string; updatedAt: string }
interface AutomationDataSetSourceFile { id: string; originalName: string; contentType: string; sizeBytes: number }
interface AutomationDataSet { id: string; organizationId: string; projectId: string; name: string; sourceFormat: 'JSON' | 'CSV'; columns: string[]; rowCount: number; checksum: string; sourceFile: AutomationDataSetSourceFile | null; createdBy: string; createdAt: string; updatedAt: string }
interface AutomationDataSetDetail { dataSet: AutomationDataSet; previewRows: Array<Record<string, string>> }
interface AutomationDataSetExecutionBatch { dataSetId: string; dataSetName: string; rowCount: number; executions: AutomationExecution[] }
interface AutomationReusableStep { id: string; organizationId: string; projectId: string; name: string; description: string; version: number; stepCount: number; createdBy: string; createdAt: string; updatedAt: string }
interface AutomationReusableStepDetail extends Omit<AutomationReusableStep, 'stepCount'> { steps: AutomationStep[] }
interface AutomationExecutionProfile { id: string; organizationId: string; projectId: string; name: string; environmentCode: string; version: number; variableCount: number; createdBy: string; createdAt: string; updatedAt: string }
interface AutomationExecutionProfileDetail extends AutomationExecutionProfile { variables: Record<string, string> }
type ExecutionVariableSource = 'SCENARIO' | 'PROJECT_PARAMETER' | 'EXECUTION_PROFILE' | 'EXECUTION_OVERRIDE' | 'DATA_SET' | 'ENVIRONMENT_SECRET' | 'RUNNER_LOCAL'
interface AutomationExecutionConfigurationPreview { scenarioId: string; scenarioVersion: number; environmentCode: string; executionProfileId: string | null; executionProfileVersion: number | null; variableReferences: Array<{ name: string; source: ExecutionVariableSource; secret: boolean; locations: Array<{ stepId: string; configKey: string }> }> }

const context = ref<Context | null>(null)
const projectId = ref('')
const scenarios = ref<Scenario[]>([])
const detail = ref<ScenarioDetail | null>(null)
const revisions = ref<Revision[]>([])
const runners = ref<ApiRunnerStatus[]>([])
const executions = ref<AutomationExecution[]>([])
const executionDetail = ref<AutomationExecution | null>(null)
const executionLogs = ref<AutomationExecutionLog[]>([])
const executionManifest = ref<AutomationExecutionManifest | null>(null)
const executionRuntimeSnapshot = ref<AutomationExecutionRuntimeSnapshot | null>(null)
const executionHttpResults = ref<AutomationExecutionHttpResult[]>([])
const executionStepResults = ref<AutomationExecutionStepResult[]>([])
const executionScriptResults = ref<AutomationExecutionScriptResult[]>([])
const executionReport = ref<AutomationExecutionReport | null>(null)
const executionReportIntegrity = ref<AutomationExecutionReportIntegrity | null>(null)
const dataSets = ref<AutomationDataSet[]>([])
const dataSetDetail = ref<AutomationDataSetDetail | null>(null)
const reusableSteps = ref<AutomationReusableStep[]>([])
const executionProfiles = ref<AutomationExecutionProfile[]>([])
const webhooks = ref<AutomationWebhook[]>([])
const schedules = ref<AutomationSchedule[]>([])
const selectedStepId = ref('')
const loading = ref(true)
const saving = ref(false)
const submittingExecution = ref(false)
const cancellingExecutionId = ref('')
const executionDetailVisible = ref(false)
const executionDetailLoading = ref(false)
const createVisible = ref(false)
const revisionVisible = ref(false)
const webhookVisible = ref(false)
const webhookSecretVisible = ref(false)
const savingWebhook = ref(false)
const updatingWebhookId = ref('')
const createdWebhookSecret = ref('')
const scheduleVisible = ref(false)
const savingSchedule = ref(false)
const runningScheduleId = ref('')
const deletingScheduleId = ref('')
const editingScheduleId = ref<string | null>(null)
const dataSetVisible = ref(false)
const dataSetPreviewVisible = ref(false)
const dataSetExecutionVisible = ref(false)
const dataSetFileInput = ref<HTMLInputElement | null>(null)
const reusableStepVisible = ref(false)
const savingReusableStep = ref(false)
const executionProfileVisible = ref(false)
const savingExecutionProfile = ref(false)
const editingExecutionProfileId = ref<string | null>(null)
const executionProfileExpectedVersion = ref<number | null>(null)
const executionOptionsVisible = ref(false)
const previewingExecutionConfiguration = ref(false)
const executionConfigurationPreview = ref<AutomationExecutionConfigurationPreview | null>(null)
const savingDataSet = ref(false)
const deletingDataSetId = ref('')
const submittingDataSetExecution = ref(false)
const variableRows = ref<VariableRow[]>([])
const createForm = reactive({ name: '', description: '', environmentCode: 'test', failureStrategy: 'STOP' as 'STOP' | 'CONTINUE' })
const webhookForm = reactive({ name: '', scenarioId: '' })
const scheduleForm = reactive({ name: '', scenarioId: '', cronExpression: '0 0 9 * * ?', timezone: 'Asia/Shanghai', timeoutSeconds: 300, maxConsecutiveFailures: 3, enabled: true })
const dataSetForm = reactive({ name: '', sourceFormat: 'JSON' as 'JSON' | 'CSV', sourceMode: 'TEXT' as 'TEXT' | 'FILE', file: null as File | null, content: '[\n  {"orderId":"1001","region":"east"},\n  {"orderId":"1002","region":"west"}\n]' })
const dataSetExecutionForm = reactive({ dataSetId: '', scenarioId: '', timeoutSeconds: 300 })
const reusableStepForm = reactive({ name: '', description: '' })
const executionProfileForm = reactive({ name: '', environmentCode: 'test', variables: '{\n  "baseUrl": "https://api.example.test"\n}' })
const executionOptionsForm = reactive({ executionProfileId: '', overrides: '{}', timeoutSeconds: 300 })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const selectedStep = computed(() => detail.value?.steps.find((step) => step.id === selectedStepId.value) ?? null)
const onlineRunner = computed(() => runners.value[0] ?? null)
const runnerTrusted = computed(() => onlineRunner.value?.trusted === true)
const runnerHttpExecutionEnabled = computed(() => runnerTrusted.value && onlineRunner.value?.executionEnabled === true)
const runnerTrustLabel = computed(() => ({ VERIFIED: '已验证', UNVERIFIED: '未验证', NOT_CONFIGURED: '未配置校验' }[onlineRunner.value?.trustStatus ?? 'NOT_CONFIGURED']))
const runnerTrustColor = computed(() => onlineRunner.value?.trustStatus === 'VERIFIED' ? 'green' : 'orange')
const failureStrategyNotice = computed(() => detail.value?.failureStrategy === 'CONTINUE'
  ? '当前策略会在状态断言或后置脚本失败后继续后续受限步骤，任务最终仍会标记为失败；前置脚本和无响应失败始终停止。'
  : '当前策略会在首个状态断言或脚本失败后停止。')
const cliScenarioId = computed(() => detail.value?.id || '<scenarioId>')
const cliExecutionCommand = computed(() => `TESTHUB_URL="${window.location.origin}" \\
TESTHUB_PROJECT_ID="${projectId.value || '<projectId>'}" \\
TESTHUB_SCENARIO_ID="${cliScenarioId.value}" \\
node services/testhub-cli/src/index.js execution run \\
  --timeout 300 --wait-timeout 900 --poll-interval 5 \\
  --report-output "./testhub-execution-report.json"

# GitLab CI 模板：ci/gitlab/testhub-api-automation.yml`)
const cliReportCommand = computed(() => `TESTHUB_URL="${window.location.origin}" \\
TESTHUB_PROJECT_ID="${projectId.value || '<projectId>'}" \\
TESTHUB_EXECUTION_ID="$TESTHUB_EXECUTION_ID" \\
node services/testhub-cli/src/index.js execution wait \\
  --wait-timeout 900 --poll-interval 5

# 退出码：0 成功；2 执行失败；3 客户端等待超时
node services/testhub-cli/src/index.js execution report

# 下载并校验 SHA-256 后归档脱敏工件
node services/testhub-cli/src/index.js execution export \\
  --output "./testhub-execution-report.json"`)
const executionReportExportUrl = computed(() => executionDetail.value && projectId.value
  ? `/api/v1/projects/${projectId.value}/api-automation/executions/${executionDetail.value.id}/report/export` : '')
const webhookEndpoint = computed(() => `${window.location.origin}/api/v1/integrations/api-automation/webhooks/{webhookId}/trigger`)
const runnerCapabilityBoundary = computed(() => runnerHttpExecutionEnabled.value
  ? '可信 Runner 已启用受限 HTTP、固定流程控制、并行请求组和隔离脚本：并行组只运行 2–5 条独立请求且禁用脚本/局部变量；脚本只在 Runner 容器中以短时同步上下文运行，无法使用网络、文件、进程、模块或计时器；环境密钥、Cookie、请求头、重定向和数据库操作仍保持关闭。'
  : runnerTrusted.value
  ? '已具备签名能力发现资格；短时租约可校验无敏感变量来源索引及快照校验和/计数，运行时配置和用户内容执行仍保持关闭。'
  : '未验证 Runner 仅可执行当前安全预检，不能进行签名能力发现、校验单任务租约、读取快照摘要或获取后续敏感运行配置。')
const canSaveSelectedAsReusable = computed(() => selectedStep.value !== null
  && !['REUSABLE_STEP', 'SUB_SCENARIO'].includes(selectedStep.value.type))
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
const referenceLocations = (reference: { locations: Array<{ stepId: string; configKey: string }> }) => reference.locations.map((location) => `${location.stepId}.${location.configKey}`).join('、')
const executionLabel: Record<string, string> = { QUEUED: '待投递', ACCEPTED: '已接收', RUNNING: '运行中', CANCELLING: '取消中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '已超时', REJECTED: '未启用' }
const executionColor: Record<string, string> = { QUEUED: 'arcoblue', ACCEPTED: 'cyan', RUNNING: 'orange', CANCELLING: 'orange', SUCCEEDED: 'green', FAILED: 'red', CANCELLED: 'gray', TIMED_OUT: 'red', REJECTED: 'orange' }
const logLevelColor: Record<string, string> = { INFO: 'arcoblue', WARN: 'orange', ERROR: 'red', DEBUG: 'gray' }
const httpResultColor: Record<AutomationExecutionHttpResult['outcome'], string> = { SUCCEEDED: 'green', FAILED: 'red' }
const typeMeta: Record<StepType, { label: string; description: string; icon: string; tone: string }> = {
  REQUEST: { label: '接口请求', description: '发送 HTTP 请求', icon: '⚡', tone: 'blue' },
  PARALLEL: { label: '并行请求组', description: '并发独立 HTTP 请求', icon: '⇆', tone: 'cyan' },
  EXTRACT: { label: '数据提取', description: '保存响应字段', icon: '⇣', tone: 'violet' },
  ASSERT: { label: '断言', description: '验证响应结果', icon: '✓', tone: 'green' },
  CONDITION: { label: '状态条件', description: '控制紧随请求', icon: '◇', tone: 'orange' },
  LOOP: { label: '有界循环', description: '重复上一请求', icon: '↻', tone: 'cyan' },
  WAIT: { label: '等待', description: '延迟场景执行', icon: '◷', tone: 'gray' },
  REUSABLE_STEP: { label: '公共步骤', description: '固定版本复用', icon: '♧', tone: 'purple' },
  SUB_SCENARIO: { label: '子场景', description: '展开固定版本', icon: '⌘', tone: 'blue' },
}

async function read<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { credentials: 'include', ...init })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function mutate<T>(url: string, method: 'POST' | 'PUT' | 'DELETE', body?: unknown): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  return read<T>(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
}

async function mutateForm<T>(url: string, form: FormData): Promise<T> {
  const csrf = await read<{ token: string; headerName: string }>('/api/v1/auth/csrf')
  const response = await fetch(url, { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token }, body: form })
  const body = await response.json().catch(() => ({})) as ApiEnvelope<T> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '请求失败，请稍后重试')
  return body.data
}

async function readExecutionManifest(executionId: string): Promise<AutomationExecutionManifest | null> {
  const response = await fetch(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/manifest`, { credentials: 'include' })
  if (response.status === 404) return null
  const body = await response.json().catch(() => ({})) as ApiEnvelope<AutomationExecutionManifest> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '加载执行清单失败')
  return body.data
}

async function readExecutionRuntimeSnapshot(executionId: string): Promise<AutomationExecutionRuntimeSnapshot | null> {
  const response = await fetch(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/runtime-snapshot`, { credentials: 'include' })
  if (response.status === 404) return null
  const body = await response.json().catch(() => ({})) as ApiEnvelope<AutomationExecutionRuntimeSnapshot> & { message?: string }
  if (!response.ok) throw new Error(body.message ?? '加载运行时变量快照失败')
  return body.data
}

async function readExecutionHttpResults(executionId: string): Promise<AutomationExecutionHttpResult[]> {
  return read<AutomationExecutionHttpResult[]>(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/http-results`)
}

async function readExecutionStepResults(executionId: string): Promise<AutomationExecutionStepResult[]> {
  return read<AutomationExecutionStepResult[]>(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/step-results`)
}

async function readExecutionScriptResults(executionId: string): Promise<AutomationExecutionScriptResult[]> {
  return read<AutomationExecutionScriptResult[]>(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/script-results`)
}

async function readExecutionReport(executionId: string): Promise<AutomationExecutionReport> {
  return read<AutomationExecutionReport>(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/report`)
}

async function readExecutionReportIntegrity(executionId: string): Promise<AutomationExecutionReportIntegrity> {
  return read<AutomationExecutionReportIntegrity>(`/api/v1/projects/${projectId.value}/api-automation/executions/${executionId}/report/integrity`)
}

async function loadScenarios() {
  if (!projectId.value) { scenarios.value = []; detail.value = null; return }
  scenarios.value = await read<Scenario[]>(`/api/v1/projects/${projectId.value}/api-automation/scenarios`)
  const target = scenarios.value.some((scenario) => scenario.id === detail.value?.id) ? detail.value?.id : scenarios.value[0]?.id
  if (target) await selectScenario(target)
  else { detail.value = null; revisions.value = []; variableRows.value = [] }
}

async function loadRunnerStatus() {
  if (!projectId.value) { runners.value = []; return }
  runners.value = await read<ApiRunnerStatus[]>(`/api/v1/projects/${projectId.value}/api-automation/runner-status`)
}

async function loadExecutions() {
  if (!projectId.value) { executions.value = []; return }
  executions.value = await read<AutomationExecution[]>(`/api/v1/projects/${projectId.value}/api-automation/executions`)
}

async function loadDataSets() {
  if (!projectId.value) { dataSets.value = []; dataSetDetail.value = null; return }
  dataSets.value = await read<AutomationDataSet[]>(`/api/v1/projects/${projectId.value}/api-automation/data-sets`)
}

async function loadReusableSteps() {
  if (!projectId.value) { reusableSteps.value = []; return }
  reusableSteps.value = await read<AutomationReusableStep[]>(`/api/v1/projects/${projectId.value}/api-automation/reusable-steps`)
}

async function loadExecutionProfiles() {
  if (!projectId.value) { executionProfiles.value = []; return }
  executionProfiles.value = await read<AutomationExecutionProfile[]>(`/api/v1/projects/${projectId.value}/api-automation/execution-profiles`)
}

async function loadWebhooks() {
  if (!projectId.value) { webhooks.value = []; return }
  webhooks.value = await read<AutomationWebhook[]>(`/api/v1/projects/${projectId.value}/api-automation/webhook-triggers`)
}

async function loadSchedules() {
  if (!projectId.value) { schedules.value = []; return }
  schedules.value = await read<AutomationSchedule[]>(`/api/v1/projects/${projectId.value}/api-automation/schedules`)
}

async function loadExecutionDetail() {
  if (!projectId.value || !executionDetail.value) return
  executionDetailLoading.value = true
  try {
    const id = executionDetail.value.id
    const [current, logs, manifest, runtimeSnapshot, httpResults, stepResults, scriptResults, report, integrity] = await Promise.all([
      read<AutomationExecution>(`/api/v1/projects/${projectId.value}/api-automation/executions/${id}`),
      read<AutomationExecutionLog[]>(`/api/v1/projects/${projectId.value}/api-automation/executions/${id}/logs`),
      readExecutionManifest(id),
      readExecutionRuntimeSnapshot(id),
      readExecutionHttpResults(id),
      readExecutionStepResults(id),
      readExecutionScriptResults(id),
      readExecutionReport(id),
      readExecutionReportIntegrity(id),
    ])
    executionDetail.value = current
    executionLogs.value = logs
    executionManifest.value = manifest
    executionRuntimeSnapshot.value = runtimeSnapshot
    executionHttpResults.value = httpResults
    executionStepResults.value = stepResults
    executionScriptResults.value = scriptResults
    executionReport.value = report
    executionReportIntegrity.value = integrity
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载任务详情失败') } finally { executionDetailLoading.value = false }
}

async function selectScenario(scenarioId: string) {
  if (!projectId.value) return
  try {
    const [scenario, history] = await Promise.all([
      read<ScenarioDetail>(`/api/v1/projects/${projectId.value}/api-automation/scenarios/${scenarioId}`),
      read<Revision[]>(`/api/v1/projects/${projectId.value}/api-automation/scenarios/${scenarioId}/revisions`),
    ])
    detail.value = scenario
    revisions.value = history
    variableRows.value = Object.entries(scenario.variables).map(([key, value]) => ({ key, value }))
    selectedStepId.value = scenario.steps[0]?.id ?? ''
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载场景失败') }
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = projects.value[0]?.id ?? ''
    await Promise.all([loadScenarios(), loadRunnerStatus(), loadExecutions(), loadDataSets(), loadReusableSteps(), loadExecutionProfiles(), loadWebhooks(), loadSchedules()])
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载接口自动化场景失败') } finally { loading.value = false }
}

async function changeProject() {
  detail.value = null
  revisions.value = []
  variableRows.value = []
  webhooks.value = []
  schedules.value = []
  dataSets.value = []
  dataSetDetail.value = null
  reusableSteps.value = []
  executionProfiles.value = []
  try { await Promise.all([loadScenarios(), loadRunnerStatus(), loadExecutions(), loadDataSets(), loadReusableSteps(), loadExecutionProfiles(), loadWebhooks(), loadSchedules()]) } catch (error) { Message.error(error instanceof Error ? error.message : '切换项目失败') }
}

function defaultStep(type: StepType): AutomationStep {
  const id = `${type.toLowerCase()}-${Date.now()}`
  const defaults: Record<StepType, Record<string, unknown>> = {
    REQUEST: { method: 'GET', url: '{{baseUrl}}/health', maxAttempts: 1 }, EXTRACT: { source: 'LAST_STATUS_CODE', variable: 'lastStatusCode' },
    PARALLEL: { requests: [
      { method: 'GET', url: '{{baseUrl}}/health', maxAttempts: 1 },
      { method: 'GET', url: '{{baseUrl}}/version', maxAttempts: 1 },
    ] },
    ASSERT: { source: 'LAST_STATUS_CODE', expectedStatus: 200 }, CONDITION: { source: 'LAST_STATUS_CODE', operator: 'EQUALS', expectedStatus: 200 },
    LOOP: { totalRuns: 2 }, WAIT: { delayMillis: 300 },
    REUSABLE_STEP: { reusableStepId: '', reusableStepVersion: 1 }, SUB_SCENARIO: { scenarioId: '', scenarioVersion: 1 },
  }
  return { id, type, name: `新的${typeMeta[type].label}`, config: defaults[type] }
}

async function createScenario() {
  if (!projectId.value || !createForm.name.trim()) { Message.warning('请填写场景名称'); return }
  saving.value = true
  try {
    const scenario = await mutate<ScenarioDetail>(`/api/v1/projects/${projectId.value}/api-automation/scenarios`, 'POST', {
      ...createForm, steps: [defaultStep('REQUEST')], variables: { baseUrl: 'https://api.example.test' },
    })
    createVisible.value = false
    createForm.name = ''; createForm.description = ''; createForm.environmentCode = 'test'; createForm.failureStrategy = 'STOP'
    await loadScenarios()
    await selectScenario(scenario.id)
    Message.success('接口自动化场景已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建场景失败') } finally { saving.value = false }
}

function addStep(type: StepType) {
  if (!detail.value) return
  const step = defaultStep(type)
  detail.value.steps.push(step)
  selectedStepId.value = step.id
}

function removeStep() {
  if (!detail.value || !selectedStep.value) return
  if (detail.value.steps.length === 1) { Message.warning('场景至少需要保留一个步骤'); return }
  const index = detail.value.steps.findIndex((step) => step.id === selectedStepId.value)
  detail.value.steps.splice(index, 1)
  selectedStepId.value = detail.value.steps[Math.max(0, index - 1)]?.id ?? ''
}

function updateScenarioField(field: 'name' | 'description' | 'environmentCode' | 'failureStrategy', value: string) {
  if (!detail.value) return
  detail.value[field] = value as never
}

function updateScenarioScript(field: 'preRequestScript' | 'postResponseScript', value: string) {
  if (detail.value) detail.value[field] = value
}

function renameStep(value: string) {
  if (selectedStep.value) selectedStep.value.name = value
}

function configValue(key: string, fallback: unknown = ''): unknown {
  return selectedStep.value?.config[key] ?? fallback
}

function executionStepName(stepId: string): string {
  return executionManifest.value?.snapshot.steps.find((step) => step.id === stepId)?.name || stepId
}

function setConfig(key: string, value: unknown) {
  if (!selectedStep.value) return
  selectedStep.value.config = { ...selectedStep.value.config, [key]: value }
}

function parallelRequests(): ParallelRequest[] {
  const requests = selectedStep.value?.config.requests
  if (!Array.isArray(requests)) return []
  return requests.filter((request): request is ParallelRequest => request !== null && typeof request === 'object'
    && typeof (request as ParallelRequest).method === 'string' && typeof (request as ParallelRequest).url === 'string')
}

function updateParallelRequest(index: number, field: keyof ParallelRequest, value: string | number) {
  if (!selectedStep.value) return
  const requests = parallelRequests().map((request, requestIndex) => requestIndex === index ? { ...request, [field]: value } : { ...request })
  const current = requests[index]
  if (current && field === 'method' && !['GET', 'HEAD', 'OPTIONS'].includes(current.method) && Number(current.maxAttempts ?? 1) > 1) {
    current.maxAttempts = 1
  }
  selectedStep.value.config = { ...selectedStep.value.config, requests }
}

function addParallelRequest() {
  if (!selectedStep.value) return
  const requests = parallelRequests()
  if (requests.length >= 5) { Message.warning('并行请求组最多包含 5 条请求'); return }
  selectedStep.value.config = { ...selectedStep.value.config, requests: [...requests, { method: 'GET', url: '{{baseUrl}}/health', maxAttempts: 1 }] }
}

function removeParallelRequest(index: number) {
  if (!selectedStep.value) return
  const requests = parallelRequests()
  if (requests.length <= 2) { Message.warning('并行请求组至少需要 2 条请求'); return }
  selectedStep.value.config = { ...selectedStep.value.config, requests: requests.filter((_request, requestIndex) => requestIndex !== index) }
}

function setRequestMethod(value: string) {
  setConfig('method', value)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(value) && Number(configValue('maxAttempts', 1)) > 1) setConfig('maxAttempts', 1)
}

function selectReusableStep(id: string) {
  const source = reusableSteps.value.find((item) => item.id === id)
  if (!selectedStep.value || !source) return
  selectedStep.value.config = { reusableStepId: source.id, reusableStepVersion: source.version }
  selectedStep.value.name = `复用：${source.name}`
}

function selectSubScenario(id: string) {
  const source = scenarios.value.find((item) => item.id === id)
  if (!selectedStep.value || !source) return
  selectedStep.value.config = { scenarioId: source.id, scenarioVersion: source.version }
  selectedStep.value.name = `子场景：${source.name}`
}

function openCreateReusableStep() {
  if (!canSaveSelectedAsReusable.value) { Message.warning('请选择一个基础步骤后再创建公共步骤'); return }
  reusableStepForm.name = selectedStep.value?.name ?? ''
  reusableStepForm.description = ''
  reusableStepVisible.value = true
}

async function createReusableStep() {
  if (!projectId.value || !selectedStep.value || !canSaveSelectedAsReusable.value || !reusableStepForm.name.trim()) {
    Message.warning('请填写公共步骤名称并选择一个基础步骤')
    return
  }
  savingReusableStep.value = true
  try {
    const copied = { id: selectedStep.value.id, type: selectedStep.value.type, name: selectedStep.value.name,
      config: { ...selectedStep.value.config } }
    const created = await mutate<AutomationReusableStepDetail>(`/api/v1/projects/${projectId.value}/api-automation/reusable-steps`, 'POST', {
      name: reusableStepForm.name.trim(), description: reusableStepForm.description, steps: [copied],
    })
    reusableStepVisible.value = false
    await loadReusableSteps()
    Message.success(`公共步骤已保存为 v${created.version}；后续引用会固定该版本`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存公共步骤失败') } finally { savingReusableStep.value = false }
}

function addVariable() { variableRows.value.push({ key: '', value: '' }) }
function removeVariable(index: number) { variableRows.value.splice(index, 1) }
function variables(): Record<string, string> {
  const result: Record<string, string> = {}
  for (const row of variableRows.value) {
    if (!row.key.trim() && !row.value) continue
    const key = row.key.trim()
    if (!key) throw new Error('变量名称不能为空')
    if (result[key] !== undefined) throw new Error(`变量重复：${key}`)
    result[key] = row.value
  }
  return result
}

async function saveScenario() {
  if (!detail.value || !projectId.value) return
  saving.value = true
  try {
    const current = detail.value
    const result = await mutate<ScenarioDetail>(`/api/v1/projects/${projectId.value}/api-automation/scenarios/${current.id}`, 'PUT', {
      expectedVersion: current.version,
      scenario: { name: current.name, description: current.description, environmentCode: current.environmentCode,
        failureStrategy: current.failureStrategy, steps: current.steps, variables: variables(),
        preRequestScript: current.preRequestScript, postResponseScript: current.postResponseScript },
    })
    detail.value = result
    variableRows.value = Object.entries(result.variables).map(([key, value]) => ({ key, value }))
    scenarios.value = scenarios.value.map((scenario) => scenario.id === result.id
      ? { ...scenario, name: result.name, description: result.description, environmentCode: result.environmentCode,
        failureStrategy: result.failureStrategy, version: result.version, stepCount: result.steps.length, updatedAt: result.updatedAt }
      : scenario)
    revisions.value = await read<Revision[]>(`/api/v1/projects/${projectId.value}/api-automation/scenarios/${result.id}/revisions`)
    Message.success(`场景已保存为 v${result.version}`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存场景失败') } finally { saving.value = false }
}

function openExecutionOptions() {
  if (!detail.value) { Message.warning('请先选择一个场景'); return }
  executionOptionsForm.executionProfileId = ''
  executionOptionsForm.overrides = '{}'
  executionOptionsForm.timeoutSeconds = 300
  executionConfigurationPreview.value = null
  executionOptionsVisible.value = true
}

function executionOverrides(): Record<string, string> {
  let parsed: unknown
  try { parsed = JSON.parse(executionOptionsForm.overrides || '{}') } catch { throw new Error('本次执行覆盖变量必须是 JSON 对象') }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('本次执行覆盖变量必须是 JSON 对象')
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (typeof value !== 'string') throw new Error(`覆盖变量 ${key} 必须是字符串`)
    result[key] = value
  }
  return result
}

async function submitExecution() {
  if (!detail.value || !projectId.value) { Message.warning('请先选择一个场景'); return }
  submittingExecution.value = true
  try {
    const execution = await mutate<AutomationExecution>(`/api/v1/projects/${projectId.value}/api-automation/scenarios/${detail.value.id}/executions`, 'POST', {
      timeoutSeconds: executionOptionsForm.timeoutSeconds, executionProfileId: executionOptionsForm.executionProfileId || null, overrides: executionOverrides(),
    })
    executionOptionsVisible.value = false
    await loadExecutions()
    Message.info(`已提交 Runner 任务 ${execution.id.slice(-6)}；未满足可信执行条件时会安全收敛为“未启用”。`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '提交预检任务失败') } finally { submittingExecution.value = false }
}

function clearExecutionConfigurationPreview() {
  executionConfigurationPreview.value = null
}

async function previewExecutionConfiguration() {
  if (!detail.value || !projectId.value) { Message.warning('请先选择一个场景'); return }
  previewingExecutionConfiguration.value = true
  try {
    executionConfigurationPreview.value = await mutate<AutomationExecutionConfigurationPreview>(
      `/api/v1/projects/${projectId.value}/api-automation/scenarios/${detail.value.id}/execution-configuration-preview`, 'POST', {
        executionProfileId: executionOptionsForm.executionProfileId || null, overrides: executionOverrides(),
      },
    )
  } catch (error) {
    executionConfigurationPreview.value = null
    Message.error(error instanceof Error ? error.message : '预览变量来源失败')
  } finally { previewingExecutionConfiguration.value = false }
}

function openCreateExecutionProfile() {
  editingExecutionProfileId.value = null
  executionProfileExpectedVersion.value = null
  executionProfileForm.name = ''
  executionProfileForm.environmentCode = detail.value?.environmentCode || 'test'
  executionProfileForm.variables = '{\n  "baseUrl": "https://api.example.test"\n}'
  executionProfileVisible.value = true
}

async function openEditExecutionProfile(profile: AutomationExecutionProfile) {
  if (!projectId.value) return
  try {
    const current = await read<AutomationExecutionProfileDetail>(`/api/v1/projects/${projectId.value}/api-automation/execution-profiles/${profile.id}`)
    editingExecutionProfileId.value = current.id
    executionProfileExpectedVersion.value = current.version
    executionProfileForm.name = current.name
    executionProfileForm.environmentCode = current.environmentCode
    executionProfileForm.variables = JSON.stringify(current.variables, null, 2)
    executionProfileVisible.value = true
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载运行配置集失败') }
}

async function createExecutionProfile() {
  if (!projectId.value || !executionProfileForm.name.trim() || !executionProfileForm.environmentCode.trim()) { Message.warning('请填写配置集名称和环境标识'); return }
  let variables: Record<string, string>
  try { variables = executionOverridesFrom(executionProfileForm.variables, '配置集变量') } catch (error) { Message.error(error instanceof Error ? error.message : '配置集变量格式不正确'); return }
  savingExecutionProfile.value = true
  try {
    const profile = { name: executionProfileForm.name.trim(), environmentCode: executionProfileForm.environmentCode.trim(), variables }
    const created = editingExecutionProfileId.value
      ? await mutate<AutomationExecutionProfileDetail>(`/api/v1/projects/${projectId.value}/api-automation/execution-profiles/${editingExecutionProfileId.value}`, 'PUT', {
          expectedVersion: executionProfileExpectedVersion.value, profile,
        })
      : await mutate<AutomationExecutionProfileDetail>(`/api/v1/projects/${projectId.value}/api-automation/execution-profiles`, 'POST', profile)
    executionProfileVisible.value = false
    await loadExecutionProfiles()
    Message.success(editingExecutionProfileId.value ? `执行配置集已更新为 v${created.version}` : `执行配置集已创建：${created.name} v${created.version}`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建执行配置集失败') } finally { savingExecutionProfile.value = false }
}

function executionOverridesFrom(source: string, label: string): Record<string, string> {
  let parsed: unknown
  try { parsed = JSON.parse(source || '{}') } catch { throw new Error(`${label}必须是 JSON 对象`) }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(`${label}必须是 JSON 对象`)
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (typeof value !== 'string') throw new Error(`${label} ${key} 必须是字符串`)
    result[key] = value
  }
  return result
}

function canCancelExecution(status: string) {
  return ['QUEUED', 'ACCEPTED', 'RUNNING'].includes(status)
}

async function cancelExecution(execution: AutomationExecution) {
  if (!projectId.value || !canCancelExecution(execution.status)) return
  cancellingExecutionId.value = execution.id
  try {
    const result = await mutate<AutomationExecution>(`/api/v1/projects/${projectId.value}/api-automation/executions/${execution.id}`, 'DELETE')
    await loadExecutions()
    Message.info(result.status === 'CANCELLED' ? '任务已在投递前取消。' : '已向 Runner 发送取消指令，正在等待确认。')
  } catch (error) { Message.error(error instanceof Error ? error.message : '取消任务失败') } finally { cancellingExecutionId.value = '' }
}

function openCreateDataSet() {
  dataSetForm.name = ''
  dataSetForm.sourceFormat = 'JSON'
  dataSetForm.sourceMode = 'TEXT'
  dataSetForm.file = null
  dataSetForm.content = '[\n  {"orderId":"1001","region":"east"},\n  {"orderId":"1002","region":"west"}\n]'
  dataSetVisible.value = true
}

function selectDataSetFile() {
  dataSetFileInput.value?.click()
}

function dataSetFileChanged(event: Event) {
  const selected = (event.target as HTMLInputElement).files?.[0] ?? null
  dataSetForm.file = selected
  if (!selected) return
  const extension = selected.name.split('.').pop()?.toUpperCase()
  if (extension === 'CSV' || extension === 'JSON') dataSetForm.sourceFormat = extension
  if (!dataSetForm.name.trim()) dataSetForm.name = selected.name.replace(/\.(csv|json)$/i, '').slice(0, 160)
}

async function createDataSet() {
  if (!projectId.value || !dataSetForm.name.trim()) {
    Message.warning('请填写数据集名称')
    return
  }
  if (dataSetForm.sourceMode === 'TEXT' && !dataSetForm.content.trim()) { Message.warning('请填写 CSV/JSON 内容'); return }
  if (dataSetForm.sourceMode === 'FILE' && !dataSetForm.file) { Message.warning('请选择 CSV 或 JSON 文件'); return }
  savingDataSet.value = true
  try {
    const created = dataSetForm.sourceMode === 'FILE'
      ? await importDataSetFile()
      : await mutate<AutomationDataSetDetail>(`/api/v1/projects/${projectId.value}/api-automation/data-sets`, 'POST', {
          name: dataSetForm.name.trim(), sourceFormat: dataSetForm.sourceFormat, content: dataSetForm.content,
        })
    dataSetDetail.value = created
    dataSetVisible.value = false
    dataSetPreviewVisible.value = true
    await loadDataSets()
    Message.success(created.dataSet.sourceFile
      ? `数据集已从原始文件导入并存档：${created.dataSet.rowCount} 行将生成独立执行任务`
      : `数据集已保存：${created.dataSet.rowCount} 行将生成独立执行任务`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存数据集失败') } finally { savingDataSet.value = false }
}

async function importDataSetFile(): Promise<AutomationDataSetDetail> {
  const form = new FormData()
  form.set('name', dataSetForm.name.trim())
  form.set('sourceFormat', dataSetForm.sourceFormat)
  form.set('file', dataSetForm.file as File)
  return mutateForm<AutomationDataSetDetail>(`/api/v1/projects/${projectId.value}/api-automation/data-sets/import`, form)
}

function sourceFileDownloadUrl(dataSet: AutomationDataSet) {
  return dataSet.sourceFile ? `/api/v1/projects/${projectId.value}/files/${dataSet.sourceFile.id}/download` : ''
}

async function showDataSet(dataSet: AutomationDataSet) {
  if (!projectId.value) return
  try {
    dataSetDetail.value = await read<AutomationDataSetDetail>(`/api/v1/projects/${projectId.value}/api-automation/data-sets/${dataSet.id}`)
    dataSetPreviewVisible.value = true
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载数据集预览失败') }
}

function openDataSetExecution(dataSet: AutomationDataSet) {
  if (!scenarios.value.length) { Message.warning('请先创建接口自动化场景'); return }
  dataSetExecutionForm.dataSetId = dataSet.id
  dataSetExecutionForm.scenarioId = detail.value?.id || scenarios.value[0].id
  dataSetExecutionForm.timeoutSeconds = 300
  dataSetExecutionVisible.value = true
}

async function submitDataSetExecution() {
  if (!projectId.value || !dataSetExecutionForm.dataSetId || !dataSetExecutionForm.scenarioId) {
    Message.warning('请选择数据集和场景')
    return
  }
  submittingDataSetExecution.value = true
  try {
    const batch = await mutate<AutomationDataSetExecutionBatch>(`/api/v1/projects/${projectId.value}/api-automation/data-sets/${dataSetExecutionForm.dataSetId}/scenarios/${dataSetExecutionForm.scenarioId}/executions`, 'POST', {
      timeoutSeconds: dataSetExecutionForm.timeoutSeconds,
    })
    dataSetExecutionVisible.value = false
    await loadExecutions()
    Message.success(`已按 ${batch.rowCount} 行数据创建 ${batch.executions.length} 个隔离任务；行内取值不会展示。`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '提交数据驱动任务失败') } finally { submittingDataSetExecution.value = false }
}

async function deleteDataSet(dataSet: AutomationDataSet) {
  if (!projectId.value) return
  deletingDataSetId.value = dataSet.id
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/api-automation/data-sets/${dataSet.id}`, 'DELETE')
    if (dataSetDetail.value?.dataSet.id === dataSet.id) dataSetDetail.value = null
    await loadDataSets()
    Message.success('数据集已删除；已创建的执行快照不受影响。')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除数据集失败') } finally { deletingDataSetId.value = '' }
}

function openCreateWebhook() {
  if (!scenarios.value.length) { Message.warning('请先创建接口自动化场景'); return }
  webhookForm.name = ''
  webhookForm.scenarioId = detail.value?.id || scenarios.value[0].id
  webhookVisible.value = true
}

async function createWebhook() {
  if (!projectId.value || !webhookForm.name.trim() || !webhookForm.scenarioId) { Message.warning('请填写 Webhook 名称并选择场景'); return }
  savingWebhook.value = true
  try {
    const created = await mutate<CreatedAutomationWebhook>(`/api/v1/projects/${projectId.value}/api-automation/webhook-triggers`, 'POST', { name: webhookForm.name.trim(), scenarioId: webhookForm.scenarioId })
    createdWebhookSecret.value = created.secret
    webhookVisible.value = false
    webhookSecretVisible.value = true
    await loadWebhooks()
    Message.success('Webhook 已创建，请立即保存密钥')
  } catch (error) { Message.error(error instanceof Error ? error.message : '创建 Webhook 失败') } finally { savingWebhook.value = false }
}

async function setWebhookEnabled(webhook: AutomationWebhook, enabled: boolean) {
  if (!projectId.value) return
  updatingWebhookId.value = webhook.id
  try {
    await mutate<AutomationWebhook>(`/api/v1/projects/${projectId.value}/api-automation/webhook-triggers/${webhook.id}`, 'PUT', { enabled })
    await loadWebhooks()
    Message.success(enabled ? 'Webhook 已启用' : 'Webhook 已停用')
  } catch (error) { Message.error(error instanceof Error ? error.message : '更新 Webhook 失败') } finally { updatingWebhookId.value = '' }
}

async function revokeWebhook(webhook: AutomationWebhook) {
  if (!projectId.value) return
  updatingWebhookId.value = webhook.id
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/api-automation/webhook-triggers/${webhook.id}`, 'DELETE')
    await loadWebhooks()
    Message.success('Webhook 已撤销')
  } catch (error) { Message.error(error instanceof Error ? error.message : '撤销 Webhook 失败') } finally { updatingWebhookId.value = '' }
}

function openCreateSchedule() {
  if (!scenarios.value.length) { Message.warning('请先创建接口自动化场景'); return }
  editingScheduleId.value = null
  scheduleForm.name = ''
  scheduleForm.scenarioId = detail.value?.id || scenarios.value[0].id
  scheduleForm.cronExpression = '0 0 9 * * ?'
  scheduleForm.timezone = 'Asia/Shanghai'
  scheduleForm.timeoutSeconds = 300
  scheduleForm.maxConsecutiveFailures = 3
  scheduleForm.enabled = true
  scheduleVisible.value = true
}

function openEditSchedule(schedule: AutomationSchedule) {
  editingScheduleId.value = schedule.id
  scheduleForm.name = schedule.name
  scheduleForm.scenarioId = schedule.scenarioId
  scheduleForm.cronExpression = schedule.cronExpression
  scheduleForm.timezone = schedule.timezone
  scheduleForm.timeoutSeconds = schedule.timeoutSeconds
  scheduleForm.maxConsecutiveFailures = schedule.maxConsecutiveFailures
  scheduleForm.enabled = schedule.enabled
  scheduleVisible.value = true
}

function schedulePayload() {
  return { name: scheduleForm.name.trim(), scenarioId: scheduleForm.scenarioId, cronExpression: scheduleForm.cronExpression.trim(), timezone: scheduleForm.timezone.trim(), timeoutSeconds: scheduleForm.timeoutSeconds, maxConsecutiveFailures: scheduleForm.maxConsecutiveFailures, enabled: scheduleForm.enabled }
}

async function saveSchedule() {
  if (!projectId.value || !scheduleForm.name.trim() || !scheduleForm.scenarioId || !scheduleForm.cronExpression.trim() || !scheduleForm.timezone.trim()) { Message.warning('请填写计划名称、场景、Cron 表达式和时区'); return }
  savingSchedule.value = true
  try {
    if (editingScheduleId.value) await mutate<AutomationSchedule>(`/api/v1/projects/${projectId.value}/api-automation/schedules/${editingScheduleId.value}`, 'PUT', schedulePayload())
    else await mutate<AutomationSchedule>(`/api/v1/projects/${projectId.value}/api-automation/schedules`, 'POST', schedulePayload())
    scheduleVisible.value = false
    await loadSchedules()
    Message.success(editingScheduleId.value ? '定时计划已更新' : '定时计划已创建')
  } catch (error) { Message.error(error instanceof Error ? error.message : '保存定时计划失败') } finally { savingSchedule.value = false }
}

async function runSchedule(schedule: AutomationSchedule) {
  if (!projectId.value) return
  runningScheduleId.value = schedule.id
  try {
    const execution = await mutate<AutomationExecution>(`/api/v1/projects/${projectId.value}/api-automation/schedules/${schedule.id}/run`, 'POST')
    await Promise.all([loadSchedules(), loadExecutions()])
    Message.info(`已提交计划任务 ${execution.id.slice(-6)}；仍受 Runner 安全开关限制。`)
  } catch (error) { Message.error(error instanceof Error ? error.message : '立即执行失败') } finally { runningScheduleId.value = '' }
}

async function deleteSchedule(schedule: AutomationSchedule) {
  if (!projectId.value) return
  deletingScheduleId.value = schedule.id
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/api-automation/schedules/${schedule.id}`, 'DELETE')
    await loadSchedules()
    Message.success('定时计划已删除')
  } catch (error) { Message.error(error instanceof Error ? error.message : '删除定时计划失败') } finally { deletingScheduleId.value = '' }
}

async function showExecutionDetail(execution: AutomationExecution) {
  executionDetail.value = execution
  executionLogs.value = []
  executionManifest.value = null
  executionRuntimeSnapshot.value = null
  executionHttpResults.value = []
  executionStepResults.value = []
  executionScriptResults.value = []
  executionReport.value = null
  executionReportIntegrity.value = null
  executionDetailVisible.value = true
  await loadExecutionDetail()
}

let executionPoller: number | undefined
onMounted(async () => {
  await load()
  executionPoller = window.setInterval(() => {
    void loadExecutions().catch(() => undefined)
    if (executionDetailVisible.value) void loadExecutionDetail()
  }, 1500)
})
onUnmounted(() => { if (executionPoller) window.clearInterval(executionPoller) })
</script>

<template>
  <div class="page automation-page">
    <PageHeader eyebrow="API AUTOMATION" title="接口自动化场景" description="编排可复用请求、受限并行请求组、提取、断言、固定流程控制与隔离脚本；实际执行始终由独立 Runner 完成。" action="新建场景" @action="createVisible = true" />
    <section class="automation-summary panel"><div><span>当前项目</span><strong>{{ selectedProject?.name ?? '未分配项目' }}</strong><small>{{ selectedProject?.code }}</small></div><a-select v-model="projectId" :style="{ width: '230px' }" @change="changeProject"><a-option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</a-option></a-select><div><span>场景数量</span><strong>{{ scenarios.length }}</strong><small>持久化资产</small></div><div><span>当前版本</span><strong>v{{ detail?.version ?? '—' }}</strong><small>乐观锁保护</small></div></section>
    <section class="runner-status-panel panel" :class="{ online: onlineRunner, trusted: runnerTrusted }"><div class="runner-status-icon"><icon-check-circle-fill v-if="runnerTrusted" /><icon-info-circle-fill v-else /></div><div><span class="eyebrow">ISOLATED API RUNNER</span><strong>{{ runnerHttpExecutionEnabled ? '受限 HTTP Runner 已启用' : runnerTrusted ? 'Runner 身份已验证' : onlineRunner ? 'Runner 协议在线，身份未验证' : '等待 Runner 心跳' }}</strong><small v-if="onlineRunner">{{ onlineRunner.runnerId }} · {{ onlineRunner.runnerVersion }} · 最近心跳 {{ date(onlineRunner.heartbeatAt) }}</small><small v-else>启动独立 Runner 后，控制面会从 Redis 私有心跳中发现它。</small></div><a-tag size="small" :color="runnerTrustColor">{{ onlineRunner ? runnerTrustLabel : '未连接' }}</a-tag><span class="runner-status-boundary">{{ onlineRunner ? runnerCapabilityBoundary : 'Runner 上线并完成可信校验后，才可进行签名能力发现与无敏感索引校验；运行时配置交接默认关闭。' }}</span></section>
    <section class="data-set-panel panel">
      <div class="data-set-head"><div><span class="eyebrow">CSV / JSON · DATA DRIVEN</span><h3>接口自动化数据集</h3><small>数据集只在项目范围内可见。按行运行时，每一行都会创建独立的隔离执行任务；行内变量值不会出现在任务消息、执行清单、日志或报告中。CSV / JSON 原文件可在校验后归档到 MinIO。</small></div><a-button type="primary" @click="openCreateDataSet"><icon-plus /> 新建数据集</a-button></div>
      <div class="data-set-list"><article v-for="dataSet in dataSets" :key="dataSet.id" class="data-set-row"><span class="data-set-icon"><icon-storage /></span><div><strong>{{ dataSet.name }}</strong><small>{{ dataSet.sourceFormat }} · {{ dataSet.rowCount }} 行 · {{ dataSet.columns.length }} 字段<template v-if="dataSet.sourceFile"> · 原文件 {{ dataSet.sourceFile.originalName }}</template><template v-else> · 手工录入</template> · 更新于 {{ date(dataSet.updatedAt) }}</small><code>{{ dataSet.columns.join(' · ') }}</code></div><a-tag :color="dataSet.sourceFile ? 'green' : 'arcoblue'">{{ dataSet.sourceFile ? 'MinIO 已归档' : 'SHA-256' }}</a-tag><a-button type="text" size="small" @click="showDataSet(dataSet)">预览</a-button><a-button type="text" size="small" :disabled="!scenarios.length" @click="openDataSetExecution(dataSet)">逐行执行</a-button><a-popconfirm content="删除后不可恢复，但已创建的执行快照不会受影响。确定继续？" @ok="deleteDataSet(dataSet)"><a-button type="text" status="danger" size="small" :loading="deletingDataSetId === dataSet.id">删除</a-button></a-popconfirm></article><a-empty v-if="!dataSets.length" description="尚未创建接口自动化数据集"><template #extra><a-button type="outline" @click="openCreateDataSet">导入首个 CSV / JSON</a-button></template></a-empty></div>
      <a-alert class="data-set-security" type="warning" :show-icon="true">请不要将 API Token、密码或私钥放入数据集；环境密钥由项目环境密钥功能独立管理。上传原文件最大 512 KiB，仅当前项目成员可下载；其内容不会进入 Runner 任务、日志或报告。若字段名与当前环境密钥重名，平台会拒绝本次数据驱动执行。</a-alert>
    </section>
    <section class="integration-panel panel"><div class="integration-head"><div><span class="eyebrow">CLI · SCHEDULE · WEBHOOK · CI</span><h3>自动化集成触发</h3><small>CLI/CI 使用项目访问令牌的 <code>run:execute</code> 范围；Quartz 定时计划和 Webhook 都只会创建安全 Outbox 任务，报告只返回脱敏固定结构。</small></div><a-space><a-button type="outline" :disabled="!scenarios.length" @click="openCreateSchedule"><icon-calendar /> 新建计划</a-button><a-button type="outline" :disabled="!scenarios.length" @click="openCreateWebhook"><icon-plus /> 新建 Webhook</a-button></a-space></div><a-tabs default-active-key="cli" class="integration-tabs"><a-tab-pane key="cli" title="CLI / PAT"><div class="integration-code-grid"><div><strong>触发当前场景</strong><small>将 PAT 存在 CI 受保护密钥中，不要写入仓库。</small><pre><code>{{ cliExecutionCommand }}</code></pre></div><div><strong>读取结构化报告</strong><small>不包含 URL、请求/响应正文、变量取值、密钥或日志文本。</small><pre><code>{{ cliReportCommand }}</code></pre></div></div></a-tab-pane><a-tab-pane key="ci" title="CI 支持"><div class="ci-note"><strong>Jenkins、GitLab CI 和 GitHub Actions</strong><small>直接执行 CLI 命令并将 <code>TESTHUB_TOKEN</code> 配置为平台密钥变量；运行完成后用执行 ID 请求报告接口归档。</small></div></a-tab-pane><a-tab-pane key="webhook" title="签名 Webhook"><div class="webhook-contract"><strong>入站地址</strong><code>{{ webhookEndpoint }}</code><small>签名原文是 <code>timestamp + newline + nonce + newline + 原始 JSON</code>；请求体仅允许 <code>{"timeoutSeconds":300}</code>。完整 curl/CI 样例见阶段文档。</small></div></a-tab-pane></a-tabs><div class="schedule-list-head"><div><strong>Quartz 定时计划</strong><small>使用 Quartz Cron 六或七段格式；连续失败达到阈值会自动停用，防止不可用 Runner 反复排队。</small></div></div><div class="schedule-list"><article v-for="schedule in schedules" :key="schedule.id" class="schedule-row"><span class="schedule-icon"><icon-calendar /></span><div><strong>{{ schedule.name }}</strong><small>{{ scenarios.find((scenario) => scenario.id === schedule.scenarioId)?.name ?? schedule.scenarioId }} · {{ schedule.cronExpression }} · {{ schedule.timezone }}</small></div><div class="schedule-meta"><a-tag :color="schedule.enabled ? 'green' : 'gray'">{{ schedule.enabled ? '已启用' : '已停用' }}</a-tag><small>连续失败 {{ schedule.consecutiveFailures }}/{{ schedule.maxConsecutiveFailures }} · {{ schedule.lastExecutionStatus ?? '尚未执行' }}</small></div><a-button type="text" size="small" :disabled="!schedule.enabled" :loading="runningScheduleId === schedule.id" @click="runSchedule(schedule)">立即执行</a-button><a-button type="text" size="small" @click="openEditSchedule(schedule)">编辑</a-button><a-popconfirm content="删除后将停止 Quartz 触发，确定继续？" @ok="deleteSchedule(schedule)"><a-button type="text" status="danger" size="small" :loading="deletingScheduleId === schedule.id">删除</a-button></a-popconfirm></article><a-empty v-if="!schedules.length" description="尚未创建接口自动化定时计划" /></div><div class="webhook-list-head"><div><strong>项目 Webhook</strong><small>密钥只会在创建响应中出现一次，列表不返回密钥。</small></div></div><div class="webhook-list"><article v-for="webhook in webhooks" :key="webhook.id" class="webhook-row"><span class="webhook-icon"><icon-link /></span><div><strong>{{ webhook.name }}</strong><small>场景：{{ scenarios.find((scenario) => scenario.id === webhook.scenarioId)?.name ?? webhook.scenarioId }} · {{ webhook.id }}</small></div><a-tag :color="webhook.enabled ? 'green' : 'gray'">{{ webhook.enabled ? '已启用' : '已停用' }}</a-tag><a-button type="text" size="small" :loading="updatingWebhookId === webhook.id" @click="setWebhookEnabled(webhook, !webhook.enabled)">{{ webhook.enabled ? '停用' : '启用' }}</a-button><a-popconfirm content="撤销后不能恢复，确定继续？" @ok="revokeWebhook(webhook)"><a-button type="text" status="danger" size="small" :loading="updatingWebhookId === webhook.id">撤销</a-button></a-popconfirm></article><a-empty v-if="!webhooks.length" description="尚未创建项目 Webhook" /></div></section>
    <section class="execution-profile-panel panel"><div class="execution-profile-head"><div><span class="eyebrow">ENVIRONMENT · FROZEN VARIABLES</span><h3>场景运行配置集</h3><small>配置集在项目范围内复用，用于指定执行环境与非敏感变量。提交时可叠加一次性覆盖值；环境密钥始终优先且任何同名覆盖都会被拒绝。</small></div><a-button type="primary" @click="openCreateExecutionProfile"><icon-plus /> 新建配置集</a-button></div><div class="execution-profile-list"><article v-for="profile in executionProfiles" :key="profile.id" class="execution-profile-row"><span>◈</span><div><strong>{{ profile.name }}</strong><small>{{ profile.environmentCode }} · v{{ profile.version }} · {{ profile.variableCount }} 个非敏感变量 · 更新于 {{ date(profile.updatedAt) }}</small></div><a-tag color="cyan">执行时冻结</a-tag><a-button type="text" size="small" @click="openEditExecutionProfile(profile)">编辑</a-button></article><a-empty v-if="!executionProfiles.length" description="还没有运行配置集；可在提交场景时使用默认场景环境" /></div><a-alert type="warning" :show-icon="true">配置集和本次覆盖变量都不应用于数据集批量任务；不要填入 Token、密码或私钥，请使用项目环境密钥。</a-alert></section>
    <section class="execution-panel panel"><div class="execution-panel-head"><div><span class="eyebrow">RUNNER EXECUTION</span><h3>场景执行任务</h3><small>任务经 MySQL Outbox 投递到 Runner；只有完成可信校验、显式启用和网络白名单都满足时才会发送受限 HTTP 请求。GET/HEAD/OPTIONS 可在瞬时失败时进行有界重试；详情不展示请求、响应或密钥。</small></div><a-button type="primary" :disabled="!detail" :loading="submittingExecution" @click="openExecutionOptions"><icon-play-arrow /> 提交当前场景</a-button></div><a-table :data="executions" :pagination="false" row-key="id" :scroll="{ x: 1020 }" size="small"><template #columns><a-table-column title="场景 / 数据行" :width="220"><template #cell="{ record }"><strong>{{ record.scenarioName }}</strong><small class="execution-version">v{{ record.scenarioVersion }} · {{ record.id.slice(-6) }}<template v-if="record.executionProfileId"> · 配置集 v{{ record.executionProfileVersion }}</template><template v-if="record.dataSetId"> · 数据集第 {{ record.dataSetRowNumber }} 行</template></small></template></a-table-column><a-table-column title="状态" :width="110"><template #cell="{ record }"><a-tag :color="executionColor[record.status] ?? 'gray'">{{ executionLabel[record.status] ?? record.status }}</a-tag></template></a-table-column><a-table-column title="Runner / 原因" :width="260"><template #cell="{ record }"><span>{{ record.runnerId ?? '等待投递' }}</span><small class="execution-message">{{ record.statusCode ? `${record.statusCode}${record.statusMessage ? ` · ${record.statusMessage}` : ''}` : 'Outbox 持久化后等待投递' }}</small></template></a-table-column><a-table-column title="提交时间" :width="170"><template #cell="{ record }">{{ date(record.requestedAt) }}</template></a-table-column><a-table-column title="操作" :width="150" fixed="right"><template #cell="{ record }"><a-space size="mini"><a-button type="text" size="small" @click="showExecutionDetail(record)">详情</a-button><a-button v-if="canCancelExecution(record.status)" type="text" status="warning" size="small" :loading="cancellingExecutionId === record.id" @click="cancelExecution(record)">取消</a-button></a-space></template></a-table-column></template></a-table><a-empty v-if="!executions.length" description="尚未提交执行任务" /></section>
    <section v-if="detail" class="script-panel panel">
      <div class="script-panel-head"><div><span class="eyebrow">ISOLATED JAVASCRIPT · PM COMPAT V1</span><h3>场景前后置脚本</h3><small>前置脚本会在每个 HTTP 请求前运行；后置脚本会在每个 HTTP 响应后运行。兼容受限 <code>pm.*</code> 变量和状态断言工作流，脚本不会读取响应正文、请求头、Cookie 或环境密钥。</small></div><a-tag color="purple">Runner 容器执行</a-tag></div>
      <div class="script-compatibility"><strong>受限 pm.* 兼容范围</strong><div><code>pm.variables</code><code>pm.environment</code><code>pm.response.code</code><code>pm.response.to.have.status()</code><code>pm.test()</code><code>pm.expect()</code></div><small>变量修改仅作用于当前 Runner 内存任务；网络请求、响应正文、Cookie、集合/全局变量和 <code>pm.sendRequest()</code> 不支持。</small></div>
      <a-tabs default-active-key="pre" class="script-tabs"><a-tab-pane key="pre" title="前置脚本"><a-textarea :model-value="detail.preRequestScript" :max-length="8192" :auto-size="{ minRows: 8, maxRows: 14 }" placeholder="// 受限 pm.* 变量示例：&#10;pm.environment.set('orderId', pm.variables.get('orderId') + '-retry');" @update:model-value="updateScenarioScript('preRequestScript', String($event))" /><a-alert class="script-boundary" type="info" :show-icon="true">禁止使用网络、文件、进程、模块、动态函数、定时器及异步 API。执行上限 150ms；脚本输出不会保存到日志或报告。</a-alert></a-tab-pane><a-tab-pane key="post" title="后置脚本"><a-textarea :model-value="detail.postResponseScript" :max-length="8192" :auto-size="{ minRows: 8, maxRows: 14 }" placeholder="// 状态断言示例：&#10;pm.test('创建成功', function () { pm.response.to.have.status(201); });" @update:model-value="updateScenarioScript('postResponseScript', String($event))" /><a-alert class="script-boundary" type="warning" :show-icon="true">任一 <code>pm.test()</code> 断言失败会使当前执行失败。平台只记录固定的脚本完成或失败事件，不记录脚本文本、变量取值或异常详情。</a-alert></a-tab-pane></a-tabs>
    </section>
    <section class="reusable-step-panel panel">
      <div class="reusable-step-head"><div><span class="eyebrow">PROJECT-LOCAL · VERSIONED REUSE</span><h3>公共步骤与子场景引用</h3><small>公共步骤与子场景均固定来源版本、仅限当前项目。保存或执行时会展开成基础步骤；禁止嵌套引用、跨项目引用、递归和子场景脚本。</small></div><a-button type="primary" :disabled="!canSaveSelectedAsReusable" @click="openCreateReusableStep"><icon-plus /> 将选中基础步骤保存为公共步骤</a-button></div>
      <div class="reusable-step-list"><article v-for="item in reusableSteps" :key="item.id" class="reusable-step-row"><span>♧</span><div><strong>{{ item.name }}</strong><small>v{{ item.version }} · {{ item.stepCount }} 个基础步骤 · {{ item.description || '未填写说明' }}</small></div><a-tag color="purple">项目内可复用</a-tag></article><a-empty v-if="!reusableSteps.length" description="选择一个基础步骤后，可将其保存为项目级公共步骤" /></div>
      <a-alert type="info" :show-icon="true">引用节点不会执行控制面代码。Runner 只接收冻结来源版本展开出的受限 HTTP、提取、断言、条件、循环和等待基础步骤。</a-alert>
    </section>
    <section class="automation-workspace">
      <aside class="scenario-panel panel"><div class="panel-heading"><div><span class="eyebrow">SCENARIOS</span><h3>场景库</h3></div><a-button type="text" size="small" @click="loadScenarios"><icon-refresh /></a-button></div><a-spin :loading="loading"><button v-for="scenario in scenarios" :key="scenario.id" class="scenario-row" :class="{ selected: scenario.id === detail?.id }" @click="selectScenario(scenario.id)"><span class="scenario-icon"><icon-code /></span><div><strong>{{ scenario.name }}</strong><small>v{{ scenario.version }} · {{ scenario.stepCount }} 步 · {{ scenario.environmentCode }}</small></div><a-tag size="small" :color="scenario.failureStrategy === 'STOP' ? 'orange' : 'green'">{{ scenario.failureStrategy === 'STOP' ? '遇错停止' : '继续' }}</a-tag></button><a-empty v-if="!loading && !scenarios.length" description="还没有接口自动化场景"><template #extra><a-button type="outline" @click="createVisible = true">新建场景</a-button></template></a-empty></a-spin></aside>
      <section class="builder-panel panel"><template v-if="detail"><div class="builder-head"><div><span class="eyebrow">SCENARIO BUILDER</span><a-input :model-value="detail.name" class="scenario-title" @update:model-value="updateScenarioField('name', String($event))" /><small>v{{ detail.version }} · 最近更新 {{ date(detail.updatedAt) }}</small></div><div class="builder-actions"><a-button @click="revisionVisible = true"><icon-history /> 修订记录</a-button><a-button :loading="saving" type="primary" @click="saveScenario"><icon-save /> 保存</a-button></div></div><div class="builder-settings"><a-input :model-value="detail.environmentCode" placeholder="环境标识" @update:model-value="updateScenarioField('environmentCode', String($event))"><template #prefix>环境</template></a-input><a-select :model-value="detail.failureStrategy" @change="updateScenarioField('failureStrategy', String($event))"><a-option value="STOP">失败时停止（默认）</a-option><a-option value="CONTINUE">失败后继续（最终仍失败）</a-option></a-select><a-input :model-value="detail.description" placeholder="场景说明（可选）" @update:model-value="updateScenarioField('description', String($event))" /></div><div class="runner-notice"><icon-info-circle-fill /><span>{{ runnerHttpExecutionEnabled ? '可信 Runner 可执行受限 HTTP、状态码条件、有界循环与等待；脚本、密钥、Cookie、请求头、重定向和数据库操作仍不可用。' : '场景可保存和协作编排；只有可信 Runner 显式启用受限 HTTP 执行后才会发送网络请求，其他能力继续关闭。' }} {{ failureStrategyNotice }}</span><a-button size="mini" disabled><icon-play-arrow /> {{ runnerHttpExecutionEnabled ? '受限执行已启用' : '等待可信 Runner' }}</a-button></div><section class="flow-canvas"><div class="flow-start">开始</div><template v-for="(step, index) in detail.steps" :key="step.id"><div class="flow-line"></div><button class="flow-node" :class="[{ selected: selectedStepId === step.id }, `node-${typeMeta[step.type].tone}`]" @click="selectedStepId = step.id"><span class="node-index">{{ index + 1 }}</span><span class="node-type">{{ typeMeta[step.type].icon }}</span><div><strong>{{ step.name }}</strong><small>{{ typeMeta[step.type].label }} · {{ typeMeta[step.type].description }}</small></div></button></template><div class="flow-line"></div><div class="flow-end">结束</div></section><div class="palette"><span>添加步骤</span><a-button v-for="type in (Object.keys(typeMeta) as StepType[])" :key="type" size="small" @click="addStep(type)">{{ typeMeta[type].icon }} {{ typeMeta[type].label }}</a-button></div></template><a-empty v-else description="从场景库选择或新建一个接口自动化场景" /></section>
      <aside class="property-panel panel">
        <template v-if="detail && selectedStep">
          <div class="property-title"><div><span>步骤配置</span><a-tag size="small" :color="typeMeta[selectedStep.type].tone === 'gray' ? 'gray' : 'arcoblue'">{{ typeMeta[selectedStep.type].label }}</a-tag></div><a-button type="text" status="danger" size="small" @click="removeStep"><icon-delete /> 删除</a-button></div>
          <a-form :model="selectedStep" layout="vertical">
            <a-form-item label="步骤名称"><a-input :model-value="selectedStep.name" @update:model-value="renameStep(String($event))" /></a-form-item>
            <template v-if="selectedStep.type === 'REQUEST'">
              <a-form-item label="请求方法"><a-select :model-value="String(configValue('method', 'GET'))" @change="setRequestMethod(String($event))"><a-option v-for="method in ['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS']" :key="method" :value="method">{{ method }}</a-option></a-select></a-form-item>
              <a-form-item label="请求地址"><a-input :model-value="String(configValue('url'))" placeholder="{{baseUrl}}/orders" @update:model-value="setConfig('url', String($event))" /></a-form-item>
              <a-form-item label="内容类型"><a-select :model-value="String(configValue('contentType', ''))" allow-clear placeholder="未设置" @change="setConfig('contentType', String($event ?? ''))"><a-option value="application/json">application/json</a-option><a-option value="application/x-www-form-urlencoded">application/x-www-form-urlencoded</a-option><a-option value="text/plain">text/plain</a-option><a-option value="application/xml">application/xml</a-option></a-select></a-form-item>
              <a-form-item label="请求正文"><a-textarea :model-value="String(configValue('body', ''))" :auto-size="{ minRows: 4, maxRows: 8 }" placeholder="仅支持正文模板变量；不支持密钥、Cookie、请求头或脚本" @update:model-value="setConfig('body', String($event))" /></a-form-item>
              <a-form-item label="最大尝试次数"><a-select :model-value="Number(configValue('maxAttempts', 1))" @change="setConfig('maxAttempts', Number($event))"><a-option :value="1">1 次（不重试）</a-option><a-option :value="2" :disabled="!['GET','HEAD','OPTIONS'].includes(String(configValue('method', 'GET')))">2 次</a-option><a-option :value="3" :disabled="!['GET','HEAD','OPTIONS'].includes(String(configValue('method', 'GET')))">3 次</a-option></a-select><div class="step-safe-tip">仅 GET、HEAD、OPTIONS 可重试；仅网络失败、408、429 和 5xx 触发，固定间隔 250ms，任务取消或临近截止时间时不会继续重试。</div></a-form-item>
            </template>
            <template v-else-if="selectedStep.type === 'PARALLEL'">
              <div class="parallel-request-list">
                <article v-for="(request, index) in parallelRequests()" :key="index" class="parallel-request-card">
                  <div class="parallel-request-head"><strong>并发分支 {{ index + 1 }}</strong><a-button type="text" size="mini" status="danger" :disabled="parallelRequests().length <= 2" @click="removeParallelRequest(index)">移除</a-button></div>
                  <a-form-item label="请求方法"><a-select :model-value="request.method" @change="updateParallelRequest(index, 'method', String($event))"><a-option v-for="method in ['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS']" :key="method" :value="method">{{ method }}</a-option></a-select></a-form-item>
                  <a-form-item label="请求地址"><a-input :model-value="request.url" placeholder="{{baseUrl}}/health" @update:model-value="updateParallelRequest(index, 'url', String($event))" /></a-form-item>
                  <a-form-item label="最大尝试次数"><a-select :model-value="Number(request.maxAttempts ?? 1)" @change="updateParallelRequest(index, 'maxAttempts', Number($event))"><a-option :value="1">1 次（不重试）</a-option><a-option :value="2" :disabled="!['GET','HEAD','OPTIONS'].includes(request.method)">2 次</a-option><a-option :value="3" :disabled="!['GET','HEAD','OPTIONS'].includes(request.method)">3 次</a-option></a-select></a-form-item>
                </article>
              </div>
              <a-button type="outline" size="small" :disabled="parallelRequests().length >= 5" @click="addParallelRequest"><icon-plus /> 添加并发分支</a-button>
              <div class="step-safe-tip">每组固定并发 2–5 条互不依赖的 HTTP 请求。组内禁止脚本、局部变量、提取、断言、条件和循环；后续先添加普通请求，才可继续使用提取或断言。</div>
            </template>
            <template v-else-if="selectedStep.type === 'EXTRACT'">
              <a-form-item label="提取来源"><a-input :model-value="String(configValue('source', 'LAST_STATUS_CODE'))" disabled /></a-form-item>
              <a-form-item label="保存为变量"><a-input :model-value="String(configValue('variable', 'lastStatusCode'))" placeholder="例如：lastStatusCode" @update:model-value="setConfig('variable', String($event))" /></a-form-item>
              <div class="step-safe-tip">仅提取最近一次请求的 HTTP 状态码，变量仅在本次 Runner 任务内存中可用，不会持久化取值。</div>
            </template>
            <template v-else-if="selectedStep.type === 'ASSERT'">
              <a-form-item label="断言来源"><a-input :model-value="String(configValue('source', 'LAST_STATUS_CODE'))" disabled /></a-form-item>
              <a-form-item label="期望状态码"><a-input-number :model-value="Number(configValue('expectedStatus', 200))" :min="100" :max="599" :style="{ width: '100%' }" @change="setConfig('expectedStatus', Number($event))" /></a-form-item>
              <div class="step-safe-tip">只支持最近一次请求状态码的精确匹配；不解析或保存响应正文、响应头、Cookie 或密钥。</div>
            </template>
            <template v-else-if="selectedStep.type === 'WAIT'">
              <a-form-item label="延迟时间"><a-input-number :model-value="Number(configValue('delayMillis', 0))" :min="0" :max="600000" :style="{ width: '100%' }" @change="setConfig('delayMillis', Number($event))"><template #suffix>ms</template></a-input-number></a-form-item>
            </template>
            <template v-else-if="selectedStep.type === 'CONDITION'">
              <a-form-item label="条件来源"><a-input :model-value="String(configValue('source', 'LAST_STATUS_CODE'))" disabled /></a-form-item>
              <a-form-item label="操作符"><a-select :model-value="String(configValue('operator', 'EQUALS'))" @change="setConfig('operator', String($event))"><a-option value="EQUALS">等于</a-option><a-option value="NOT_EQUALS">不等于</a-option></a-select></a-form-item>
              <a-form-item label="期望状态码"><a-input-number :model-value="Number(configValue('expectedStatus', 200))" :min="100" :max="599" :style="{ width: '100%' }" @change="setConfig('expectedStatus', Number($event))" /></a-form-item>
              <div class="step-safe-tip">条件只读取最近一次 HTTP 状态码；不满足时会跳过紧随的一个请求步骤，不执行表达式或脚本。</div>
            </template>
            <template v-else-if="selectedStep.type === 'LOOP'">
              <a-form-item label="总执行次数"><a-input-number :model-value="Number(configValue('totalRuns', 2))" :min="1" :max="10" :style="{ width: '100%' }" @change="setConfig('totalRuns', Number($event))"><template #suffix>次</template></a-input-number></a-form-item>
              <div class="step-safe-tip">循环必须紧随一个请求步骤，并只重复该请求；最多 10 次，不支持动态表达式、嵌套循环或并发。</div>
            </template>
            <template v-else-if="selectedStep.type === 'REUSABLE_STEP'">
              <a-form-item label="公共步骤"><a-select :model-value="String(configValue('reusableStepId', ''))" placeholder="选择当前项目公共步骤" @change="selectReusableStep(String($event))"><a-option v-for="item in reusableSteps" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.version }} · {{ item.stepCount }} 步</a-option></a-select></a-form-item>
              <a-form-item label="固定来源版本"><a-input :model-value="String(configValue('reusableStepVersion', ''))" disabled /></a-form-item>
              <div class="step-safe-tip">保存场景后会绑定所选公共步骤的当前版本。之后即使公共步骤更新，已有场景与已创建执行仍使用固定版本。</div>
            </template>
            <template v-else-if="selectedStep.type === 'SUB_SCENARIO'">
              <a-form-item label="子场景"><a-select :model-value="String(configValue('scenarioId', ''))" placeholder="选择当前项目子场景" @change="selectSubScenario(String($event))"><a-option v-for="item in scenarios.filter((scenario) => scenario.id !== detail?.id)" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.version }} · {{ item.stepCount }} 步</a-option></a-select></a-form-item>
              <a-form-item label="固定来源版本"><a-input :model-value="String(configValue('scenarioVersion', ''))" disabled /></a-form-item>
              <div class="step-safe-tip">子场景只展开基础步骤，并与父场景共用其变量、环境和失败策略。带前后置脚本或引用节点的子场景会被拒绝，防止递归执行。</div>
            </template>
          </a-form>
          <a-divider />
          <div class="variables-title"><div><strong>场景变量</strong><small>变量值可见；密钥请使用项目环境密钥。</small></div><a-button type="text" size="small" @click="addVariable"><icon-plus /> 添加</a-button></div>
          <div class="variable-list"><div v-for="(row, index) in variableRows" :key="index" class="variable-row"><a-input v-model="row.key" placeholder="变量名" /><a-input v-model="row.value" placeholder="变量值" /><a-button type="text" status="danger" size="mini" @click="removeVariable(index)"><icon-close /></a-button></div><a-empty v-if="!variableRows.length" description="尚未定义场景变量" /></div>
        </template>
        <a-empty v-else description="选择步骤后配置属性" />
      </aside>
    </section>
    <a-modal v-model:visible="createVisible" title="新建接口自动化场景" width="600px" :confirm-loading="saving" @ok="createScenario"><a-form :model="createForm" layout="vertical"><a-form-item label="场景名称" required><a-input v-model="createForm.name" :max-length="160" placeholder="例如：订单核心链路回归" /></a-form-item><a-form-item label="场景说明"><a-textarea v-model="createForm.description" :max-length="1200" placeholder="说明该场景覆盖的业务路径" /></a-form-item><div class="form-grid"><a-form-item label="环境标识"><a-input v-model="createForm.environmentCode" placeholder="test" /></a-form-item><a-form-item label="失败策略"><a-select v-model="createForm.failureStrategy"><a-option value="STOP">失败时停止（默认）</a-option><a-option value="CONTINUE">失败后继续（最终仍失败）</a-option></a-select></a-form-item></div><div class="modal-tip">将自动创建一个 GET 请求步骤和 <code>baseUrl</code> 场景变量。Runner 接入前，保存不会发起任何外部请求。</div></a-form></a-modal>
    <a-modal v-model:visible="executionProfileVisible" :title="editingExecutionProfileId ? '编辑场景运行配置集' : '新建场景运行配置集'" width="680px" :confirm-loading="savingExecutionProfile" @ok="createExecutionProfile"><a-form :model="executionProfileForm" layout="vertical"><a-form-item label="配置集名称" required><a-input v-model="executionProfileForm.name" :max-length="160" placeholder="例如：预发订单回归" /></a-form-item><a-form-item label="执行环境" required><a-input v-model="executionProfileForm.environmentCode" :max-length="64" placeholder="staging" /></a-form-item><a-form-item label="非敏感变量（JSON 对象）"><a-textarea v-model="executionProfileForm.variables" :auto-size="{ minRows: 7, maxRows: 12 }" :max-length="262144" /></a-form-item><a-alert type="warning" :show-icon="true">只接受字符串值，最多 100 项。配置集变量会在执行时冻结，但不能覆盖当前环境密钥；密钥必须使用项目环境密钥管理。</a-alert></a-form></a-modal>
    <a-modal v-model:visible="executionOptionsVisible" title="提交场景执行" width="720px" :confirm-loading="submittingExecution" @ok="submitExecution"><a-form :model="executionOptionsForm" layout="vertical"><a-form-item label="运行配置集（可选）"><a-select v-model="executionOptionsForm.executionProfileId" allow-clear placeholder="使用场景默认环境" @change="clearExecutionConfigurationPreview"><a-option v-for="profile in executionProfiles" :key="profile.id" :value="profile.id">{{ profile.name }} · {{ profile.environmentCode }} · v{{ profile.version }}</a-option></a-select></a-form-item><a-form-item label="本次覆盖变量（JSON 对象）"><a-textarea v-model="executionOptionsForm.overrides" :auto-size="{ minRows: 5, maxRows: 10 }" :max-length="262144" placeholder="{&quot;orderId&quot;:&quot;OD-1001&quot;}" @update:model-value="clearExecutionConfigurationPreview" /></a-form-item><a-button type="outline" size="small" :loading="previewingExecutionConfiguration" @click="previewExecutionConfiguration"><icon-eye /> 预览最终来源</a-button><section v-if="executionConfigurationPreview" class="execution-configuration-preview"><a-descriptions :column="2" size="small" bordered><a-descriptions-item label="场景版本">v{{ executionConfigurationPreview.scenarioVersion }}</a-descriptions-item><a-descriptions-item label="执行环境">{{ executionConfigurationPreview.environmentCode }}</a-descriptions-item><a-descriptions-item label="配置集" :span="2">{{ executionConfigurationPreview.executionProfileId ? `v${executionConfigurationPreview.executionProfileVersion}（提交时固定）` : '使用场景默认环境' }}</a-descriptions-item></a-descriptions><div class="execution-configuration-preview-head"><strong>引用变量的最终来源</strong><small>只显示名称、来源与使用位置，不显示变量或密钥取值。</small></div><a-table :data="executionConfigurationPreview.variableReferences" :pagination="false" size="small" row-key="name"><template #columns><a-table-column title="变量" data-index="name" :width="130" /><a-table-column title="最终来源" :width="170"><template #cell="{ record }"><a-tag :color="record.secret ? 'orange' : 'arcoblue'">{{ record.source }}</a-tag></template></a-table-column><a-table-column title="使用位置"><template #cell="{ record }">{{ referenceLocations(record) }}</template></a-table-column></template></a-table><a-empty v-if="!executionConfigurationPreview.variableReferences.length" description="当前场景没有模板变量引用" /></section><a-form-item label="执行超时"><a-input-number v-model="executionOptionsForm.timeoutSeconds" :min="10" :max="3600" :style="{ width: '100%' }"><template #suffix>秒</template></a-input-number></a-form-item><a-alert type="info" :show-icon="true">优先级从低到高：项目参数、场景变量、运行配置集、数据集行、本次覆盖变量、环境密钥、Runner 局部变量。预览和任务创建都只保留脱敏来源，值不在浏览器、日志、清单或报告中展示。</a-alert></a-form></a-modal>
    <a-modal v-model:visible="reusableStepVisible" title="保存项目级公共步骤" width="620px" :confirm-loading="savingReusableStep" @ok="createReusableStep"><a-form :model="reusableStepForm" layout="vertical"><a-form-item label="公共步骤名称" required><a-input v-model="reusableStepForm.name" :max-length="160" placeholder="例如：获取订单健康状态" /></a-form-item><a-form-item label="说明"><a-textarea v-model="reusableStepForm.description" :max-length="1200" placeholder="说明此基础步骤的前置条件与用途" /></a-form-item><a-alert type="info" :show-icon="true">会复制当前选中的基础步骤并创建 v1。公共步骤不能嵌套公共步骤或子场景；后续更新会产生新版本，现有引用不会漂移。</a-alert></a-form></a-modal>
    <a-modal v-model:visible="dataSetVisible" title="新建接口自动化数据集" width="720px" :confirm-loading="savingDataSet" @ok="createDataSet">
      <a-form :model="dataSetForm" layout="vertical">
        <div class="form-grid"><a-form-item label="数据集名称" required><a-input v-model="dataSetForm.name" :max-length="160" placeholder="例如：订单回归参数" /></a-form-item><a-form-item label="来源格式" required><a-select v-model="dataSetForm.sourceFormat"><a-option value="JSON">JSON 对象数组</a-option><a-option value="CSV">CSV（首行为字段名）</a-option></a-select></a-form-item></div>
        <a-form-item label="导入方式" required><a-radio-group v-model="dataSetForm.sourceMode" type="button"><a-radio value="TEXT">粘贴内容</a-radio><a-radio value="FILE">上传原始文件</a-radio></a-radio-group></a-form-item>
        <a-form-item v-if="dataSetForm.sourceMode === 'TEXT'" label="数据内容" required>
          <a-textarea v-model="dataSetForm.content" :auto-size="{ minRows: 12, maxRows: 18 }" :max-length="524288" placeholder="JSON 或 CSV 数据内容" />
        </a-form-item>
        <a-form-item v-else label="原始 CSV / JSON 文件" required><input ref="dataSetFileInput" class="data-set-file-input" type="file" accept=".csv,.json,application/json,text/csv" @change="dataSetFileChanged" /><div class="data-set-file-picker"><div><strong>{{ dataSetForm.file?.name ?? '尚未选择文件' }}</strong><small v-if="dataSetForm.file">{{ dataSetForm.file.size }} 字节 · 将按当前来源格式校验并归档至 MinIO</small><small v-else>仅支持 UTF-8 编码的 .csv / .json，最大 512 KiB</small></div><a-button @click="selectDataSetFile">选择文件</a-button></div></a-form-item>
        <a-alert type="warning" :show-icon="true">字段名只能使用字母、数字和下划线，且不能以数字开头；最多 50 个字段、200 行。不要把 API Token、密码或私钥写入数据集。文件导入会保存原始附件，但附件不会发送给 Runner。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="dataSetExecutionVisible" title="按数据集逐行执行" width="560px" :confirm-loading="submittingDataSetExecution" @ok="submitDataSetExecution"><a-form :model="dataSetExecutionForm" layout="vertical"><a-form-item label="数据集"><a-select v-model="dataSetExecutionForm.dataSetId"><a-option v-for="dataSet in dataSets" :key="dataSet.id" :value="dataSet.id">{{ dataSet.name }} · {{ dataSet.rowCount }} 行</a-option></a-select></a-form-item><a-form-item label="执行场景"><a-select v-model="dataSetExecutionForm.scenarioId"><a-option v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">{{ scenario.name }} · v{{ scenario.version }}</a-option></a-select></a-form-item><a-form-item label="单行超时"><a-input-number v-model="dataSetExecutionForm.timeoutSeconds" :min="10" :max="3600" :style="{ width: '100%' }"><template #suffix>秒</template></a-input-number></a-form-item><a-alert type="info" :show-icon="true">确认后将根据数据集行数创建多个独立 Runner 任务。每条任务只冻结其本行所需的非敏感变量，任务消息和报告不携带行内取值。</a-alert></a-form></a-modal>
    <a-modal v-model:visible="dataSetPreviewVisible" :title="dataSetDetail ? `${dataSetDetail.dataSet.name} · 数据预览` : '数据集预览'" :footer="false" width="780px"><template v-if="dataSetDetail"><a-descriptions :column="3" size="small" bordered><a-descriptions-item label="来源格式">{{ dataSetDetail.dataSet.sourceFormat }}</a-descriptions-item><a-descriptions-item label="总行数">{{ dataSetDetail.dataSet.rowCount }}</a-descriptions-item><a-descriptions-item label="字段数">{{ dataSetDetail.dataSet.columns.length }}</a-descriptions-item><a-descriptions-item label="原始文件" :span="3"><template v-if="dataSetDetail.dataSet.sourceFile"><a-link :href="sourceFileDownloadUrl(dataSetDetail.dataSet)" target="_blank">{{ dataSetDetail.dataSet.sourceFile.originalName }}（{{ dataSetDetail.dataSet.sourceFile.sizeBytes }} 字节）</a-link><small class="data-set-source-file-note">已按项目权限归档到 MinIO；下载需保持当前登录会话。</small></template><span v-else>手工粘贴录入（无原始附件）</span></a-descriptions-item><a-descriptions-item label="校验和" :span="3"><code>{{ dataSetDetail.dataSet.checksum }}</code></a-descriptions-item></a-descriptions><div class="data-set-preview-head"><strong>前 {{ dataSetDetail.previewRows.length }} 行预览</strong><small>预览仅对当前项目有读取权限的成员可见。</small></div><a-table :data="dataSetDetail.previewRows" :pagination="false" size="small" :scroll="{ x: 680 }"><template #columns><a-table-column v-for="column in dataSetDetail.dataSet.columns" :key="column" :title="column" :data-index="column" :width="160" /></template></a-table><a-empty v-if="!dataSetDetail.previewRows.length" description="没有可预览的数据行" /></template></a-modal>
    <a-modal v-model:visible="webhookVisible" title="新建签名 Webhook" width="580px" :confirm-loading="savingWebhook" @ok="createWebhook"><a-form :model="webhookForm" layout="vertical"><a-form-item label="Webhook 名称" required><a-input v-model="webhookForm.name" :max-length="160" placeholder="例如：发布后订单回归" /></a-form-item><a-form-item label="触发场景" required><a-select v-model="webhookForm.scenarioId"><a-option v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">{{ scenario.name }} · v{{ scenario.version }}</a-option></a-select></a-form-item><a-alert type="warning" :show-icon="true">系统只保存 AES-GCM 加密后的 HMAC 密钥；创建成功后会仅展示一次原始密钥。</a-alert></a-form></a-modal>
    <a-modal v-model:visible="webhookSecretVisible" title="立即保存 Webhook 密钥" :footer="false" width="640px" @cancel="createdWebhookSecret = ''"><a-alert type="warning" :show-icon="true">此密钥不会再次显示。请保存到 CI 的受保护密钥变量中，不要提交至源码。</a-alert><pre class="webhook-secret"><code>{{ createdWebhookSecret }}</code></pre><a-button type="primary" long @click="webhookSecretVisible = false; createdWebhookSecret = ''">我已安全保存</a-button></a-modal>
    <a-modal v-model:visible="scheduleVisible" :title="editingScheduleId ? '编辑 Quartz 定时计划' : '新建 Quartz 定时计划'" width="640px" :confirm-loading="savingSchedule" @ok="saveSchedule"><a-form :model="scheduleForm" layout="vertical"><a-form-item label="计划名称" required><a-input v-model="scheduleForm.name" :max-length="160" placeholder="例如：每日订单接口回归" /></a-form-item><a-form-item label="触发场景" required><a-select v-model="scheduleForm.scenarioId"><a-option v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">{{ scenario.name }} · v{{ scenario.version }}</a-option></a-select></a-form-item><div class="schedule-form-grid"><a-form-item label="Quartz Cron" required><a-input v-model="scheduleForm.cronExpression" placeholder="0 0 9 * * ?" /><template #extra>六或七段格式，例如每天 09:00：<code>0 0 9 * * ?</code></template></a-form-item><a-form-item label="时区" required><a-input v-model="scheduleForm.timezone" placeholder="Asia/Shanghai" /></a-form-item><a-form-item label="单次超时"><a-input-number v-model="scheduleForm.timeoutSeconds" :min="10" :max="3600" :style="{ width: '100%' }"><template #suffix>秒</template></a-input-number></a-form-item><a-form-item label="连续失败停用阈值"><a-input-number v-model="scheduleForm.maxConsecutiveFailures" :min="1" :max="10" :style="{ width: '100%' }"><template #suffix>次</template></a-input-number></a-form-item></div><a-form-item label="状态"><a-switch v-model="scheduleForm.enabled" checked-text="启用" unchecked-text="停用" /></a-form-item><a-alert type="warning" :show-icon="true">计划只会调用现有隔离执行链路。Runner 未通过可信校验或未显式启用时，任务会安全结束；达到连续失败阈值后计划自动停用。</a-alert></a-form></a-modal>
    <a-modal v-model:visible="revisionVisible" title="场景修订记录" :footer="false" width="560px"><a-table :data="revisions" :pagination="false" row-key="id"><template #columns><a-table-column title="版本"><template #cell="{ record }"><a-tag color="arcoblue">v{{ record.version }}</a-tag></template></a-table-column><a-table-column title="保存时间"><template #cell="{ record }">{{ date(record.createdAt) }}</template></a-table-column></template></a-table><a-empty v-if="!revisions.length" description="尚无修订记录" /></a-modal>
    <a-drawer v-model:visible="executionDetailVisible" :width="560" title="执行任务详情" :footer="false">
      <template v-if="executionDetail">
        <section class="execution-detail-summary">
          <div><span>场景</span><strong>{{ executionDetail.scenarioName }}</strong><small>版本 v{{ executionDetail.scenarioVersion }} · {{ executionDetail.id }}</small></div>
          <a-tag :color="executionColor[executionDetail.status] ?? 'gray'">{{ executionLabel[executionDetail.status] ?? executionDetail.status }}</a-tag>
        </section>
        <a-descriptions :column="1" size="small" bordered class="execution-descriptions">
          <a-descriptions-item label="原因">{{ executionDetail.statusCode ? `${executionDetail.statusCode}${executionDetail.statusMessage ? ` · ${executionDetail.statusMessage}` : ''}` : '等待 Runner 处理' }}</a-descriptions-item>
          <a-descriptions-item label="Runner">{{ executionDetail.runnerId ?? '尚未接收' }}</a-descriptions-item>
          <a-descriptions-item v-if="executionDetail.executionProfileId" label="运行配置集">v{{ executionDetail.executionProfileVersion }}（环境与非敏感变量已在提交时固定，取值不展示）</a-descriptions-item>
          <a-descriptions-item v-if="executionDetail.dataSetId" label="数据驱动">数据集第 {{ executionDetail.dataSetRowNumber }} 行（行内变量取值不展示）</a-descriptions-item>
          <a-descriptions-item label="任务 ID"><code>{{ executionDetail.taskId }}</code></a-descriptions-item>
          <a-descriptions-item label="截止时间">{{ date(executionDetail.deadlineAt) }}</a-descriptions-item>
        </a-descriptions>
        <a-link v-if="executionReportExportUrl" class="execution-report-export" :href="executionReportExportUrl" download><icon-download /> 下载脱敏报告 JSON</a-link>
        <div v-if="executionReportIntegrity" class="execution-report-integrity"><a-tag size="small" color="green">SHA-256</a-tag><code>{{ executionReportIntegrity.sha256 }}</code><small>与下载响应的 ETag 和 <code>X-TestHub-Report-SHA256</code> 一致，可用于 CI 归档校验。</small></div>
        <section v-if="executionReport" class="execution-report"><div class="execution-manifest-head"><div><span class="eyebrow">REDACTED STRUCTURED REPORT</span><h4>脱敏结构化报告</h4><small>{{ executionReport.contract }} · v{{ executionReport.reportVersion }}；可由 CLI/CI 读取并归档。</small></div><a-tag color="green">安全输出</a-tag></div><a-descriptions :column="2" size="small" bordered><a-descriptions-item v-if="executionReport.failureStrategy" label="失败策略"><a-tag size="small" :color="executionReport.failureStrategy.strategy === 'CONTINUE' ? 'green' : 'orange'">{{ executionReport.failureStrategy.strategy === 'CONTINUE' ? '失败后继续' : '失败即停止' }}</a-tag></a-descriptions-item><a-descriptions-item v-if="executionReport.failureStrategy" label="安全继续">{{ executionReport.failureStrategy.continuedFailureCount ? `已继续 ${executionReport.failureStrategy.continuedFailureCount} 次，最终执行仍会失败` : '未触发继续执行' }}</a-descriptions-item><a-descriptions-item label="HTTP 尝试">{{ executionReport.summary.httpResultCount }}（成功 {{ executionReport.summary.httpSucceededCount }} / 失败 {{ executionReport.summary.httpFailedCount }}）</a-descriptions-item><a-descriptions-item label="累计耗时">{{ executionReport.summary.httpDurationMillis }} ms</a-descriptions-item><a-descriptions-item label="安全步骤">{{ executionReport.summary.stepResultCount }}</a-descriptions-item><a-descriptions-item label="断言">通过 {{ executionReport.summary.assertionPassedCount }} / 失败 {{ executionReport.summary.assertionFailedCount }}</a-descriptions-item><a-descriptions-item label="pm.test 统计">{{ executionReport.summary.scriptResultCount }} 次执行，共 {{ executionReport.summary.scriptTestCount }} 条</a-descriptions-item><a-descriptions-item label="pm.test 结果">通过 {{ executionReport.summary.scriptPassedCount }} / 失败 {{ executionReport.summary.scriptFailedCount }}</a-descriptions-item></a-descriptions><section v-if="executionReport.parallelGroups.length" class="parallel-group-report"><div class="manifest-binding-head"><strong>并行请求组汇总</strong><small>按冻结父步骤聚合：仅统计分支、尝试、序号与耗时，不记录请求或响应内容。</small></div><div class="parallel-group-list"><article v-for="group in executionReport.parallelGroups" :key="group.stepId" class="parallel-group-row"><span class="parallel-group-icon">⇆</span><div><strong>{{ executionStepName(group.stepId) }}</strong><small>{{ group.stepId }} · 请求序号 {{ group.firstRequestSequence ?? '—' }}–{{ group.lastRequestSequence ?? '—' }} · 累计 {{ group.totalDurationMillis }} ms</small></div><a-tag size="small" color="arcoblue">配置 {{ group.configuredBranchCount }}</a-tag><a-tag size="small" color="purple">完成 {{ group.completedBranchCount }}</a-tag><a-tag size="small" color="green">成功 {{ group.succeededBranchCount }}</a-tag><a-tag size="small" :color="group.failedBranchCount ? 'red' : 'green'">失败 {{ group.failedBranchCount }}</a-tag><a-tag size="small" :color="group.retryAttemptCount ? 'orange' : 'gray'">重试 {{ group.retryAttemptCount }}</a-tag></article></div></section></section>
        <section v-if="executionManifest" class="execution-manifest">
          <div class="execution-manifest-head"><div><span class="eyebrow">FROZEN SAFE MANIFEST</span><h4>已固化执行清单</h4><small>提交前会校验 <code v-pre>{{变量}}</code> 引用；清单只保存步骤结构、来源和使用位置。</small></div><a-tag color="green">SHA-256</a-tag></div>
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="场景版本">v{{ executionManifest.scenarioVersion }}</a-descriptions-item>
            <a-descriptions-item label="环境">{{ executionManifest.snapshot.environmentCode }}</a-descriptions-item>
            <a-descriptions-item label="步骤数">{{ executionManifest.stepCount }}</a-descriptions-item>
            <a-descriptions-item label="变量定义">{{ executionManifest.variableCount }}（密钥 {{ executionManifest.secretReferenceCount }}）</a-descriptions-item>
            <a-descriptions-item label="已解析引用" :span="2">{{ executionManifest.snapshot.variableReferences.length }} 个</a-descriptions-item>
            <a-descriptions-item label="校验和" :span="2"><code>{{ executionManifest.checksum }}</code></a-descriptions-item>
          </a-descriptions>
          <div class="manifest-binding-head"><strong>已解析引用</strong><small>来源按 环境密钥 → 场景变量 → 项目参数 选择；只记录步骤和配置项名称。</small></div>
          <div class="manifest-reference-list"><div v-for="reference in executionManifest.snapshot.variableReferences" :key="reference.name" class="manifest-reference-row"><code>{{ reference.name }}</code><a-tag size="small" :color="reference.secret ? 'orange' : 'arcoblue'">{{ reference.source }}</a-tag><span>{{ referenceLocations(reference) }}</span></div><a-empty v-if="!executionManifest.snapshot.variableReferences.length" description="当前场景没有模板变量引用" /></div>
          <div class="manifest-binding-head"><strong>变量引用</strong><small>来源与变量名可审阅，取值不会展示。</small></div>
          <div class="manifest-binding-list"><div v-for="binding in executionManifest.snapshot.variableBindings" :key="`${binding.source}-${binding.name}`" class="manifest-binding-row"><code>{{ binding.name }}</code><a-tag size="small" :color="binding.secret ? 'orange' : 'arcoblue'">{{ binding.source }}</a-tag><span>{{ binding.secret ? '仅密钥引用' : '仅变量引用' }}</span></div><a-empty v-if="!executionManifest.snapshot.variableBindings.length" description="当前场景没有变量引用" /></div>
        </section>
        <a-alert v-else type="info" class="execution-manifest-legacy" :show-icon="true">该任务在安全执行清单发布前创建，因此没有可审阅的变量引用快照。</a-alert>
        <section v-if="executionRuntimeSnapshot" class="execution-runtime-snapshot">
          <div class="execution-manifest-head"><div><span class="eyebrow">FROZEN NON-SENSITIVE RUNTIME INPUTS</span><h4>冻结非敏感运行时变量</h4><small>仅固化当前场景实际引用的非敏感变量；环境密钥只保留名称，不读取、不解密、不传递取值。</small></div><a-tag color="arcoblue">内部快照</a-tag></div>
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="环境">{{ executionRuntimeSnapshot.snapshot.environmentCode }}</a-descriptions-item>
            <a-descriptions-item label="快照版本">v{{ executionRuntimeSnapshot.snapshot.snapshotVersion }}</a-descriptions-item>
            <a-descriptions-item label="非敏感变量">{{ executionRuntimeSnapshot.variableCount }}</a-descriptions-item>
            <a-descriptions-item label="已保留密钥名">{{ executionRuntimeSnapshot.withheldSecretCount }}</a-descriptions-item>
            <a-descriptions-item label="校验和" :span="2"><code>{{ executionRuntimeSnapshot.checksum }}</code></a-descriptions-item>
          </a-descriptions>
          <div class="manifest-binding-head"><strong>非敏感变量来源</strong><small>非敏感值已在控制面内部固化，本页面不展示；运行时配置交接仍保持关闭。</small></div>
          <div class="manifest-binding-list"><div v-for="variable in executionRuntimeSnapshot.snapshot.variables" :key="`${variable.source}-${variable.name}`" class="manifest-binding-row"><code>{{ variable.name }}</code><a-tag size="small" color="arcoblue">{{ variable.source }}</a-tag><span>取值不对外展示</span></div><a-empty v-if="!executionRuntimeSnapshot.snapshot.variables.length" description="当前场景没有引用非敏感变量" /></div>
          <div class="manifest-binding-head"><strong>环境密钥</strong><small>仅保留名称，不含取值。</small></div>
          <div class="runtime-secret-tags"><a-tag v-for="name in executionRuntimeSnapshot.snapshot.withheldSecretNames" :key="name" color="orange">{{ name }} · 已保留</a-tag><a-empty v-if="!executionRuntimeSnapshot.snapshot.withheldSecretNames.length" description="当前场景没有引用环境密钥" /></div>
        </section>
        <a-alert v-else-if="executionManifest" type="info" class="execution-manifest-legacy" :show-icon="true">该任务在非敏感运行时变量快照发布前创建，因此不提供变量快照。</a-alert>
        <section class="http-result-panel"><div class="execution-log-head"><div><span class="eyebrow">REDACTED HTTP RESULTS</span><h4>逐请求执行结果</h4><small>重试会保留相同请求序号并标注尝试次数；仅展示冻结步骤名称、方法、状态码和耗时，不记录地址、正文、响应或密钥。</small></div></div><a-spin :loading="executionDetailLoading"><div class="http-result-list"><article v-for="result in executionHttpResults" :key="result.id" class="http-result-row"><span class="http-result-sequence">{{ result.requestSequence }}</span><div><strong>{{ executionStepName(result.stepId) }}</strong><small>{{ result.stepId }} · {{ result.runnerId }} · {{ date(result.occurredAt) }}</small></div><a-tag size="small" color="purple">尝试 {{ result.attemptNumber }}</a-tag><a-tag size="small" color="arcoblue">{{ result.method }}</a-tag><a-tag size="small" :color="httpResultColor[result.outcome]">{{ result.statusCode ?? '无响应' }}</a-tag><span class="http-result-duration">{{ result.durationMillis }} ms</span></article><a-empty v-if="!executionHttpResults.length" description="尚未收到受限 HTTP 请求结果" /></div></a-spin></section>
        <section class="step-result-panel"><div class="execution-log-head"><div><span class="eyebrow">SAFE STEP RESULTS</span><h4>提取与断言结果</h4><small>只记录步骤类型、通过状态与已观察状态码；不保存变量取值或响应内容。</small></div></div><a-spin :loading="executionDetailLoading"><div class="step-result-list"><article v-for="result in executionStepResults" :key="result.id" class="step-result-row"><span class="http-result-sequence">{{ result.stepSequence }}</span><div><strong>{{ executionStepName(result.stepId) }}</strong><small>{{ result.stepType }} · {{ result.runnerId }} · {{ date(result.occurredAt) }}</small></div><a-tag size="small" :color="result.stepType === 'ASSERT' ? 'green' : 'purple'">{{ result.stepType === 'ASSERT' ? '状态断言' : '状态提取' }}</a-tag><a-tag size="small" :color="result.outcome === 'FAILED' ? 'red' : 'green'">{{ result.outcome }}</a-tag><span class="http-result-duration">HTTP {{ result.observedStatusCode }}</span></article><a-empty v-if="!executionStepResults.length" description="尚未收到提取或断言结果" /></div></a-spin></section>
        <section class="script-result-panel"><div class="execution-log-head"><div><span class="eyebrow">SAFE PM.TEST RESULTS</span><h4>脚本断言统计</h4><small>仅保存阶段和通过/失败计数；不保存断言名称、变量、响应、请求或密钥。</small></div></div><a-spin :loading="executionDetailLoading"><div class="script-result-list"><article v-for="result in executionScriptResults" :key="result.id" class="script-result-row"><span class="http-result-sequence">✓</span><div><strong>{{ result.phase === 'PRE_REQUEST' ? '前置脚本' : '后置脚本' }}</strong><small>{{ result.runnerId }} · {{ date(result.occurredAt) }} · 仅聚合计数</small></div><a-tag size="small" color="purple">{{ result.testCount }} 条</a-tag><a-tag size="small" color="green">通过 {{ result.passedCount }}</a-tag><a-tag size="small" :color="result.failedCount ? 'red' : 'green'">失败 {{ result.failedCount }}</a-tag></article><a-empty v-if="!executionScriptResults.length" description="尚未收到 pm.test 断言统计" /></div></a-spin></section>
        <div class="execution-log-head"><div><span class="eyebrow">SAFE RUNNER LOG</span><h4>实时结构化日志</h4><small>每 1.5 秒刷新；仅接受固定代码与固定消息。</small></div><a-button size="small" :loading="executionDetailLoading" @click="loadExecutionDetail"><icon-refresh /> 刷新</a-button></div>
        <a-spin :loading="executionDetailLoading"><div class="execution-log-list"><article v-for="entry in executionLogs" :key="entry.id" class="execution-log-row"><a-tag size="small" :color="logLevelColor[entry.level] ?? 'gray'">{{ entry.level }}</a-tag><div><strong>{{ entry.code }}</strong><p>{{ entry.message }}</p><small>{{ entry.source === 'RUNNER' ? `${entry.runnerId ?? 'Runner'} · ` : '控制面 · ' }}{{ date(entry.occurredAt) }}</small></div></article><a-empty v-if="!executionLogs.length" description="尚未收到 Runner 日志" /></div></a-spin>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.automation-summary{display:grid;grid-template-columns:1.45fr 1fr .7fr .7fr;gap:18px;align-items:center;margin-bottom:20px}.automation-summary>div{display:grid;gap:4px}.automation-summary span,.automation-summary small,.scenario-row small,.builder-head small,.runner-notice,.variables-title small,.modal-tip{color:var(--color-text-3);font-size:12px}.automation-summary strong{font-size:20px}.automation-workspace{display:grid;grid-template-columns:280px minmax(420px,1fr) 300px;gap:18px;align-items:start}.scenario-panel,.builder-panel,.property-panel{min-height:620px;padding:16px}.panel-heading,.builder-head,.builder-actions,.property-title,.variables-title,.runner-notice,.palette{display:flex;gap:10px}.panel-heading,.builder-head,.property-title,.variables-title{align-items:flex-start;justify-content:space-between}.panel-heading h3{margin:4px 0 0;font-size:16px}.scenario-row{display:flex;width:100%;gap:10px;align-items:center;margin-top:8px;padding:11px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff;text-align:left;cursor:pointer}.scenario-row:hover,.scenario-row.selected{border-color:rgb(var(--primary-6));background:rgb(var(--primary-1))}.scenario-row>div{display:grid;min-width:0;flex:1;gap:3px}.scenario-row strong,.scenario-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scenario-row strong{font-size:13px}.scenario-icon{display:grid;place-items:center;width:30px;height:30px;border-radius:8px;background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.builder-panel{padding:20px}.builder-head>div:first-child{display:grid;gap:4px;min-width:0}.scenario-title :deep(input){height:28px;padding:0;border:0;background:transparent;font-size:20px;font-weight:700;box-shadow:none}.builder-actions{align-items:center}.builder-settings{display:grid;grid-template-columns:160px 150px minmax(0,1fr);gap:10px;margin:16px 0}.runner-notice{align-items:center;padding:10px;border:1px solid rgb(var(--orange-2));border-radius:8px;background:rgb(var(--orange-1));line-height:1.5}.runner-notice span{flex:1}.flow-canvas{display:grid;justify-items:center;min-height:355px;padding:20px;border:1px dashed var(--color-border-3);border-radius:10px;background:linear-gradient(90deg,rgba(22,93,255,.025) 1px,transparent 1px),linear-gradient(rgba(22,93,255,.025) 1px,transparent 1px);background-size:20px 20px}.flow-start,.flow-end{padding:6px 16px;border-radius:999px;background:var(--color-fill-2);color:var(--color-text-2);font-size:12px}.flow-line{height:17px;border-left:1px dashed var(--color-border-3)}.flow-node{display:flex;width:min(100%,420px);gap:10px;align-items:center;padding:11px;border:1px solid var(--color-border-2);border-left:4px solid rgb(var(--primary-6));border-radius:8px;background:#fff;text-align:left;cursor:pointer}.flow-node:hover,.flow-node.selected{box-shadow:0 0 0 3px rgb(var(--primary-1));border-color:rgb(var(--primary-6))}.node-index,.node-type{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:var(--color-fill-2);color:var(--color-text-2);font-size:11px}.node-type{background:rgb(var(--primary-1));color:rgb(var(--primary-6));font-size:14px}.flow-node>div{display:grid;min-width:0;gap:3px}.flow-node strong,.flow-node small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.flow-node strong{font-size:13px}.flow-node small{color:var(--color-text-3);font-size:11px}.node-violet{border-left-color:rgb(var(--purple-6))}.node-violet .node-type{background:rgb(var(--purple-1));color:rgb(var(--purple-6))}.node-green{border-left-color:rgb(var(--green-6))}.node-green .node-type{background:rgb(var(--green-1));color:rgb(var(--green-6))}.node-orange{border-left-color:rgb(var(--orange-6))}.node-orange .node-type{background:rgb(var(--orange-1));color:rgb(var(--orange-6))}.node-cyan{border-left-color:rgb(var(--cyan-6))}.node-cyan .node-type{background:rgb(var(--cyan-1));color:rgb(var(--cyan-6))}.node-gray{border-left-color:rgb(var(--gray-6))}.palette{align-items:center;flex-wrap:wrap;margin-top:14px}.palette>span{margin-right:auto;color:var(--color-text-2);font-size:12px;font-weight:600}.property-title>div,.variables-title>div{display:grid;gap:5px}.variables-title{align-items:center;margin-bottom:12px}.variable-list{display:grid;gap:8px}.variable-row{display:grid;grid-template-columns:.9fr 1fr auto;gap:6px;align-items:center}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.modal-tip{padding:10px;border-left:3px solid var(--color-border-3);background:var(--color-fill-1);line-height:1.6}@media (max-width:1200px){.automation-workspace{grid-template-columns:250px minmax(360px,1fr)}.property-panel{grid-column:1 / -1;min-height:auto}.property-panel :deep(.arco-form){max-width:520px}}@media (max-width:820px){.automation-summary{grid-template-columns:1fr 1fr}.automation-workspace{grid-template-columns:1fr}.scenario-panel,.builder-panel{min-height:auto}.property-panel{grid-column:auto}.builder-head,.runner-notice{align-items:stretch;flex-direction:column}.builder-actions{justify-content:space-between}.builder-settings{grid-template-columns:1fr}.flow-node{width:100%}}@media (max-width:560px){.automation-summary,.form-grid{grid-template-columns:1fr}.scenario-row{align-items:flex-start}.palette>span{width:100%}.variable-row{grid-template-columns:1fr 1fr auto}}
.runner-status-panel{display:grid;grid-template-columns:auto minmax(0,1fr) auto minmax(220px,.8fr);gap:12px;align-items:center;margin:-4px 0 20px;padding:12px 16px;border:1px solid rgb(var(--orange-2));background:rgb(var(--orange-1))}.runner-status-panel.online.trusted{border-color:rgb(var(--green-2));background:rgb(var(--green-1))}.runner-status-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:9px;background:#fff;color:rgb(var(--orange-6));font-size:18px}.runner-status-panel.online.trusted .runner-status-icon{color:rgb(var(--green-6))}.runner-status-panel>div:nth-child(2){display:grid;gap:3px}.runner-status-panel strong{font-size:14px}.runner-status-panel small,.runner-status-boundary{color:var(--color-text-3);font-size:12px;line-height:1.5}.runner-status-boundary{padding-left:12px;border-left:1px solid var(--color-border-2)}@media (max-width:820px){.runner-status-panel{grid-template-columns:auto minmax(0,1fr)}.runner-status-panel>a-tag,.runner-status-boundary{grid-column:2}.runner-status-boundary{padding-left:0;border-left:0}}
.data-set-panel{margin:-4px 0 20px;padding:16px;border-color:rgb(var(--cyan-2));background:linear-gradient(135deg,rgb(var(--cyan-1)),#fff 55%)}.data-set-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.data-set-head>div{display:grid;gap:4px}.data-set-head h3{margin:0;font-size:16px}.data-set-head small,.data-set-row small,.data-set-preview-head small,.data-set-source-file-note{color:var(--color-text-3);font-size:12px;line-height:1.55}.data-set-list{display:grid;gap:8px;margin-top:14px}.data-set-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto auto auto auto;gap:9px;align-items:center;padding:10px 12px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.data-set-row>div{display:grid;min-width:0;gap:3px}.data-set-row strong,.data-set-row small,.data-set-row code{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.data-set-row code{color:var(--color-text-3);font-size:11px}.data-set-icon{display:grid;place-items:center;width:29px;height:29px;border-radius:7px;background:rgb(var(--cyan-1));color:rgb(var(--cyan-6))}.data-set-security{margin-top:12px}.data-set-preview-head{display:flex;justify-content:space-between;align-items:center;margin:16px 0 8px}.data-set-preview-head strong{font-size:13px}.data-set-file-input{display:none}.data-set-file-picker{display:flex;gap:12px;align-items:center;justify-content:space-between;padding:12px;border:1px dashed rgb(var(--cyan-4));border-radius:8px;background:rgb(var(--cyan-1))}.data-set-file-picker>div{display:grid;min-width:0;gap:3px}.data-set-file-picker strong,.data-set-file-picker small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.data-set-source-file-note{display:block;margin-top:4px}@media (max-width:720px){.data-set-head{align-items:stretch;flex-direction:column}.data-set-head>a-button{width:100%}.data-set-row{grid-template-columns:auto minmax(0,1fr) auto}.data-set-row>a-tag{grid-column:2}.data-set-row>a-button,.data-set-row :deep(.arco-popconfirm){grid-column:2;justify-self:start}.data-set-preview-head,.data-set-file-picker{align-items:flex-start;flex-direction:column}}
.execution-panel{margin:-4px 0 20px;padding:16px}.execution-panel-head{display:flex;gap:18px;align-items:center;justify-content:space-between;margin-bottom:12px}.execution-panel-head>div{display:grid;gap:4px}.execution-panel-head h3{margin:0;font-size:16px}.execution-panel-head small,.execution-version,.execution-message,.execution-no-action,.execution-log-row small,.execution-detail-summary small,.execution-log-head small{display:block;color:var(--color-text-3);font-size:12px;line-height:1.5}.execution-version{margin-top:2px}.execution-message{max-width:250px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.execution-no-action{padding-left:10px}.execution-detail-summary{display:flex;gap:16px;align-items:flex-start;justify-content:space-between;margin-bottom:16px;padding:14px;border:1px solid var(--color-border-2);border-radius:8px;background:var(--color-fill-1)}.execution-detail-summary>div{display:grid;min-width:0;gap:4px}.execution-detail-summary span{font-size:12px;color:var(--color-text-3)}.execution-detail-summary strong{font-size:15px}.execution-detail-summary small{word-break:break-all}.execution-descriptions code{font-size:11px;word-break:break-all}.execution-log-head{display:flex;gap:12px;align-items:center;justify-content:space-between;margin:22px 0 10px}.execution-log-head>div{display:grid;gap:3px}.execution-log-head h4{margin:0;font-size:14px}.execution-log-list{display:grid;gap:8px}.execution-log-row{display:grid;grid-template-columns:auto minmax(0,1fr);gap:9px;align-items:flex-start;padding:10px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.execution-log-row>div{display:grid;gap:3px;min-width:0}.execution-log-row strong{font-size:12px}.execution-log-row p{margin:0;color:var(--color-text-2);font-size:12px;line-height:1.5}.execution-log-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}@media (max-width:720px){.execution-panel-head{align-items:stretch;flex-direction:column}.execution-panel-head>a-button{width:100%}}
.integration-panel{margin:-4px 0 20px;padding:16px;border-color:rgb(var(--primary-2));background:linear-gradient(135deg,rgb(var(--primary-1)),#fff 46%)}.integration-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.integration-head>div{display:grid;gap:4px}.integration-head h3{margin:0;font-size:16px}.integration-head small,.integration-code-grid small,.webhook-list-head small,.webhook-row small,.ci-note small,.webhook-contract small{display:block;color:var(--color-text-3);font-size:12px;line-height:1.55}.integration-tabs{margin-top:10px}.integration-code-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.integration-code-grid>div,.ci-note,.webhook-contract{display:grid;gap:6px;padding:12px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.integration-code-grid strong,.ci-note strong,.webhook-contract strong{font-size:13px}.integration-code-grid pre,.webhook-secret{margin:3px 0 0;padding:10px;overflow:auto;border-radius:6px;background:#172033;color:#dce8ff;font-size:11px;line-height:1.55;white-space:pre-wrap;word-break:break-word}.webhook-contract code{word-break:break-all}.webhook-list-head{display:flex;justify-content:space-between;align-items:center;margin:14px 0 8px}.webhook-list-head>div{display:grid;gap:3px}.webhook-list{display:grid;gap:7px}.webhook-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto auto auto;gap:9px;align-items:center;padding:9px 11px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.webhook-row>div{display:grid;min-width:0;gap:3px}.webhook-row strong,.webhook-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.webhook-icon{display:grid;place-items:center;width:28px;height:28px;border-radius:7px;background:rgb(var(--primary-1));color:rgb(var(--primary-6))}.webhook-secret{margin:14px 0}.execution-report{margin-top:14px;padding:13px;border:1px solid rgb(var(--cyan-2));border-radius:8px;background:rgb(var(--cyan-1))}@media (max-width:720px){.integration-head{align-items:stretch;flex-direction:column}.integration-head>a-button{width:100%}.integration-code-grid{grid-template-columns:1fr}.webhook-row{grid-template-columns:auto minmax(0,1fr) auto}.webhook-row>a-button,.webhook-row :deep(.arco-popconfirm){grid-column:2}.webhook-row :deep(.arco-popconfirm){justify-self:start}}@media (max-width:560px){.webhook-row{grid-template-columns:auto minmax(0,1fr)}.webhook-row>a-tag{grid-column:2}.webhook-row>a-button,.webhook-row :deep(.arco-popconfirm){grid-column:2}}
.schedule-list-head{display:flex;justify-content:space-between;align-items:center;margin:15px 0 8px}.schedule-list-head>div{display:grid;gap:3px}.schedule-list-head small,.schedule-row small{display:block;color:var(--color-text-3);font-size:12px;line-height:1.5}.schedule-list{display:grid;gap:7px}.schedule-row{display:grid;grid-template-columns:auto minmax(0,1fr) minmax(170px,.7fr) auto auto auto;gap:9px;align-items:center;padding:9px 11px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.schedule-row>div{display:grid;min-width:0;gap:3px}.schedule-row strong,.schedule-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.schedule-icon{display:grid;place-items:center;width:28px;height:28px;border-radius:7px;background:rgb(var(--purple-1));color:rgb(var(--purple-6))}.schedule-meta{justify-items:start}.schedule-form-grid{display:grid;grid-template-columns:1.4fr 1fr;gap:0 12px}@media (max-width:720px){.schedule-row{grid-template-columns:auto minmax(0,1fr) auto}.schedule-meta{grid-column:2}.schedule-row>a-button,.schedule-row :deep(.arco-popconfirm){grid-column:2;justify-self:start}.schedule-form-grid{grid-template-columns:1fr}}@media (max-width:560px){.schedule-row{grid-template-columns:auto minmax(0,1fr)}.schedule-row>div:nth-child(2){grid-column:2}.schedule-row .schedule-meta{grid-column:2}.schedule-row>a-button,.schedule-row :deep(.arco-popconfirm){grid-column:2}}
.execution-manifest{margin-top:22px;padding:13px;border:1px solid rgb(var(--green-2));border-radius:8px;background:rgb(var(--green-1))}.execution-manifest-legacy{margin-top:22px}.execution-manifest-head{display:flex;gap:12px;align-items:flex-start;justify-content:space-between;margin-bottom:10px}.execution-manifest-head>div{display:grid;gap:3px}.execution-manifest-head h4{margin:0;font-size:14px}.execution-manifest-head small,.manifest-binding-head small{color:var(--color-text-3);font-size:12px;line-height:1.5}.execution-manifest code{font-size:11px;word-break:break-all}.manifest-binding-head{display:flex;gap:8px;align-items:baseline;justify-content:space-between;margin:13px 0 8px}.manifest-binding-head strong{font-size:12px}.manifest-binding-list,.manifest-reference-list{display:grid;gap:6px}.manifest-binding-row,.manifest-reference-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:8px;align-items:center;padding:7px 9px;border:1px solid var(--color-border-2);border-radius:6px;background:#fff}.manifest-binding-row span,.manifest-reference-row span{color:var(--color-text-3);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}@media (max-width:560px){.execution-manifest :deep(.arco-descriptions-item-label),.execution-manifest :deep(.arco-descriptions-item-value){padding:7px}.manifest-binding-row,.manifest-reference-row{grid-template-columns:minmax(0,1fr) auto}.manifest-binding-row span,.manifest-reference-row span{grid-column:1 / -1}}
.execution-runtime-snapshot{margin-top:14px;padding:13px;border:1px solid rgb(var(--primary-2));border-radius:8px;background:rgb(var(--primary-1))}.execution-runtime-snapshot code{font-size:11px;word-break:break-all}.runtime-secret-tags{display:flex;gap:6px;flex-wrap:wrap;padding:9px;border:1px solid var(--color-border-2);border-radius:6px;background:#fff}.runtime-secret-tags :deep(.arco-empty){width:100%;margin:2px 0}
.http-result-panel{margin-top:22px}.http-result-list{display:grid;gap:8px}.http-result-row{display:grid;grid-template-columns:28px minmax(0,1fr) auto auto auto;align-items:center;gap:8px;padding:10px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.http-result-row>div{display:grid;gap:3px;min-width:0}.http-result-row strong{font-size:12px}.http-result-row small{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.http-result-sequence{display:grid;width:24px;height:24px;place-items:center;border-radius:50%;background:var(--color-fill-2);color:var(--color-text-2);font-size:12px;font-weight:600}.http-result-duration{color:var(--color-text-3);font-size:12px;white-space:nowrap}@media (max-width:720px){.http-result-row{grid-template-columns:28px minmax(0,1fr) auto}.http-result-row>a-tag:nth-of-type(1),.http-result-duration{grid-column:2}.http-result-row>a-tag:nth-of-type(2){grid-column:3;grid-row:1}}
.step-safe-tip{margin-top:-4px;color:var(--color-text-3);font-size:12px;line-height:1.55}.step-result-panel{margin-top:18px}.step-result-list{display:grid;gap:8px}.step-result-row{display:grid;grid-template-columns:28px minmax(0,1fr) auto auto auto;align-items:center;gap:8px;padding:10px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.step-result-row>div{display:grid;gap:3px;min-width:0}.step-result-row strong{font-size:12px}.step-result-row small{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}@media (max-width:720px){.step-result-row{grid-template-columns:28px minmax(0,1fr) auto}.step-result-row>a-tag:nth-of-type(1),.step-result-row .http-result-duration{grid-column:2}.step-result-row>a-tag:nth-of-type(2){grid-column:3;grid-row:1}}
.script-result-panel{margin-top:18px}.script-result-list{display:grid;gap:8px}.script-result-row{display:grid;grid-template-columns:28px minmax(0,1fr) auto auto auto;align-items:center;gap:8px;padding:10px;border:1px solid rgb(var(--purple-2));border-radius:8px;background:rgb(var(--purple-1))}.script-result-row>div{display:grid;gap:3px;min-width:0}.script-result-row strong{font-size:12px}.script-result-row small{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}@media (max-width:720px){.script-result-row{grid-template-columns:28px minmax(0,1fr) auto}.script-result-row>a-tag:nth-of-type(1){grid-column:2}.script-result-row>a-tag:nth-of-type(2),.script-result-row>a-tag:nth-of-type(3){grid-column:3}}
.script-panel{margin:-4px 0 20px;padding:16px;border-color:rgb(var(--purple-2));background:linear-gradient(135deg,rgb(var(--purple-1)),#fff 58%)}.script-panel-head{display:flex;gap:16px;align-items:flex-start;justify-content:space-between}.script-panel-head>div{display:grid;gap:4px}.script-panel-head h3{margin:0;font-size:16px}.script-panel-head small{display:block;color:var(--color-text-3);font-size:12px;line-height:1.55;max-width:780px}.script-compatibility{display:grid;gap:8px;margin-top:13px;padding:11px 12px;border:1px solid rgb(var(--purple-2));border-radius:8px;background:rgba(255,255,255,.78)}.script-compatibility strong{font-size:12px}.script-compatibility>div{display:flex;gap:6px;flex-wrap:wrap}.script-compatibility code{padding:2px 6px;border-radius:4px;background:rgb(var(--purple-1));color:rgb(var(--purple-6));font-size:11px}.script-compatibility small{color:var(--color-text-3);font-size:12px;line-height:1.55}.script-tabs{margin-top:10px}.script-boundary{margin-top:12px}@media (max-width:720px){.script-panel-head{align-items:stretch;flex-direction:column}}
.reusable-step-panel{margin:-4px 0 20px;padding:16px;border-color:rgb(var(--purple-2));background:linear-gradient(135deg,#fff,rgb(var(--purple-1)) 150%)}.reusable-step-head{display:flex;gap:16px;align-items:flex-start;justify-content:space-between}.reusable-step-head>div{display:grid;gap:4px}.reusable-step-head h3{margin:0;font-size:16px}.reusable-step-head small,.reusable-step-row small{display:block;color:var(--color-text-3);font-size:12px;line-height:1.55}.reusable-step-list{display:grid;gap:8px;margin:14px 0}.reusable-step-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:10px;align-items:center;padding:10px 12px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.reusable-step-row>span{display:grid;place-items:center;width:28px;height:28px;border-radius:7px;background:rgb(var(--purple-1));color:rgb(var(--purple-6));font-size:15px}.reusable-step-row>div{display:grid;min-width:0;gap:3px}.reusable-step-row strong,.reusable-step-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}@media (max-width:720px){.reusable-step-head{align-items:stretch;flex-direction:column}.reusable-step-head>a-button{width:100%}.reusable-step-row{grid-template-columns:auto minmax(0,1fr)}.reusable-step-row>a-tag{grid-column:2;justify-self:start}}
.execution-profile-panel{margin:-4px 0 20px;padding:16px;border-color:rgb(var(--cyan-2));background:linear-gradient(135deg,rgb(var(--cyan-1)),#fff 60%)}.execution-profile-head{display:flex;gap:16px;align-items:flex-start;justify-content:space-between}.execution-profile-head>div{display:grid;gap:4px}.execution-profile-head h3{margin:0;font-size:16px}.execution-profile-head small,.execution-profile-row small{display:block;color:var(--color-text-3);font-size:12px;line-height:1.55}.execution-profile-list{display:grid;gap:8px;margin:14px 0}.execution-profile-row{display:grid;grid-template-columns:auto minmax(0,1fr) auto auto;gap:10px;align-items:center;padding:10px 12px;border:1px solid var(--color-border-2);border-radius:8px;background:#fff}.execution-profile-row>span{display:grid;place-items:center;width:28px;height:28px;border-radius:7px;background:rgb(var(--cyan-1));color:rgb(var(--cyan-6));font-size:15px}.execution-profile-row>div{display:grid;min-width:0;gap:3px}.execution-profile-row strong,.execution-profile-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.execution-configuration-preview{display:grid;gap:12px;margin:12px 0;padding:12px;border:1px solid rgb(var(--blue-2));border-radius:8px;background:rgb(var(--blue-1))}.execution-configuration-preview-head{display:grid;gap:3px}.execution-configuration-preview-head small{color:var(--color-text-3);font-size:12px}@media (max-width:720px){.execution-profile-head{align-items:stretch;flex-direction:column}.execution-profile-head>a-button{width:100%}.execution-profile-row{grid-template-columns:auto minmax(0,1fr)}.execution-profile-row>a-tag{grid-column:2;justify-self:start}.execution-profile-row>a-button{grid-column:2;justify-self:start}}
.parallel-group-report{margin-top:14px}.parallel-group-list{display:grid;gap:7px}.parallel-group-row{display:grid;grid-template-columns:auto minmax(0,1fr) repeat(5,auto);gap:7px;align-items:center;padding:9px;border:1px solid var(--color-border-2);border-radius:7px;background:#fff}.parallel-group-row>div{display:grid;gap:3px;min-width:0}.parallel-group-row strong{font-size:12px}.parallel-group-row small{overflow:hidden;color:var(--color-text-3);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.parallel-group-icon{display:grid;width:26px;height:26px;place-items:center;border-radius:50%;background:rgb(var(--cyan-1));color:rgb(var(--cyan-6));font-size:15px}@media (max-width:720px){.parallel-group-row{grid-template-columns:auto minmax(0,1fr) repeat(2,auto)}.parallel-group-row>a-tag:nth-last-child(-n+3){grid-column:2}}
.execution-report-export{display:inline-flex;gap:6px;align-items:center;margin-top:12px;font-size:12px}
.execution-report-integrity{display:grid;grid-template-columns:auto minmax(0,1fr);gap:6px 8px;align-items:center;margin-top:9px;padding:8px 10px;border:1px solid rgb(var(--green-2));border-radius:6px;background:rgb(var(--green-1))}.execution-report-integrity code{overflow:hidden;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.execution-report-integrity small{grid-column:1 / -1;color:var(--color-text-3);font-size:11px;line-height:1.45}
</style>
