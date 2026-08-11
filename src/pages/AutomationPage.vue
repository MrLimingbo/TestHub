<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { Message } from '@arco-design/web-vue'
import AutomationRunPanel from '@/components/api-automation/AutomationRunPanel.vue'
import AutomationSidebar from '@/components/api-automation/AutomationSidebar.vue'
import { useSessionStore } from '@/stores/session'
import { parseCurlCommand } from '@/utils/apiAutomation'
import { apiCaseType, buildApiRunUrl, type ApiRunSnapshot } from '@/utils/apiTesting'

interface ApiEnvelope<T> { data: T }
interface Organization { id: string; name: string }
interface Project { id: string; organizationId: string; name: string; code: string }
interface Context { organizations: Organization[]; projects: Record<string, Project[]> }
interface ProjectEnvironment { id: string; name: string; environmentCode: string; baseUrl: string; enabled: boolean; defaultEnvironment: boolean }
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
interface ImportApiDefinition { id: string; method: string; path: string; name: string; currentVersionId: string | null }
interface ImportApiCase { id: string; apiId: string; name: string; environmentCode: string | null; requestSnapshot: ApiRunSnapshot; expectedStatus: number | null; responseValidation: string }
type WorkbenchTab = 'steps' | 'data' | 'reports' | 'integrations'
type ImportMode = 'INTERFACE' | 'TEST_CASE' | 'DEBUG_CASE' | 'CURL'

const context = ref<Context | null>(null)
const session = useSessionStore()
const projectId = computed({ get: () => session.projectId, set: (value: string) => session.selectProject(value) })
const scenarios = ref<Scenario[]>([])
const environments = ref<ProjectEnvironment[]>([])
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
const workbenchTab = ref<WorkbenchTab>('steps')
const scenarioEditorTab = ref<'flow' | 'scripts' | 'variables'>('flow')
const scenarioSearch = ref('')
const stepInspectorVisible = ref(false)
const importVisible = ref(false)
const importMode = ref<ImportMode>('INTERFACE')
const importSelectionId = ref('')
const importDefinitions = ref<ImportApiDefinition[]>([])
const importCases = ref<ImportApiCase[]>([])
const selectedRunDataSetId = ref('')
const notificationEnabled = ref(false)
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
const createForm = reactive({ name: '', description: '', environmentCode: '', failureStrategy: 'STOP' as 'STOP' | 'CONTINUE' })
const webhookForm = reactive({ name: '', scenarioId: '' })
const scheduleForm = reactive({ name: '', scenarioId: '', cronExpression: '0 0 9 * * ?', timezone: 'Asia/Shanghai', timeoutSeconds: 300, maxConsecutiveFailures: 3, enabled: true })
const dataSetForm = reactive({ name: '', sourceFormat: 'JSON' as 'JSON' | 'CSV', sourceMode: 'TEXT' as 'TEXT' | 'FILE', file: null as File | null, content: '[\n  {"orderId":"1001","region":"east"},\n  {"orderId":"1002","region":"west"}\n]' })
const dataSetExecutionForm = reactive({ dataSetId: '', scenarioId: '', timeoutSeconds: 300 })
const reusableStepForm = reactive({ name: '', description: '' })
const executionProfileForm = reactive({ name: '', environmentCode: '', variables: '{}' })
const executionOptionsForm = reactive({ executionProfileId: '', overrides: '{}', timeoutSeconds: 300 })
const importForm = reactive({ baseUrl: '{{BASE_URL}}', curl: "curl -X GET '{{BASE_URL}}/health'" })

const projects = computed(() => context.value?.organizations.flatMap((organization) => context.value?.projects[organization.id] ?? []) ?? [])
const selectedProject = computed(() => projects.value.find((project) => project.id === projectId.value))
const defaultEnvironmentCode = computed(() => environments.value.find((item) => item.enabled && item.defaultEnvironment)?.environmentCode
  ?? environments.value.find((item) => item.enabled)?.environmentCode
  ?? '')
const selectedStep = computed(() => detail.value?.steps.find((step) => step.id === selectedStepId.value) ?? null)
const onlineRunner = computed(() => runners.value[0] ?? null)
const runnerTrusted = computed(() => onlineRunner.value?.trusted === true)
const runnerHttpExecutionEnabled = computed(() => runnerTrusted.value && onlineRunner.value?.executionEnabled === true)
const runnerTrustLabel = computed(() => ({ VERIFIED: '已验证', UNVERIFIED: '未验证', NOT_CONFIGURED: '未配置校验' }[onlineRunner.value?.trustStatus ?? 'NOT_CONFIGURED']))
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
const canSaveSelectedAsReusable = computed(() => selectedStep.value !== null
  && !['REUSABLE_STEP', 'SUB_SCENARIO'].includes(selectedStep.value.type))
const importCandidates = computed(() => importCases.value.filter((item) => importMode.value === 'TEST_CASE'
  ? apiCaseType(item.responseValidation) === 'TEST'
  : apiCaseType(item.responseValidation) === 'DEBUG'))
const runnerSummary = computed(() => onlineRunner.value
  ? `${onlineRunner.value.runnerVersion} · ${runnerTrustLabel.value}`
  : '等待 Runner 心跳')
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

async function loadEnvironments() {
  if (!projectId.value) { environments.value = []; return }
  environments.value = await read<ProjectEnvironment[]>(`/api/v1/projects/${projectId.value}/environments`)
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

async function loadImportSources() {
  if (!projectId.value) { importDefinitions.value = []; importCases.value = []; return }
  const [definitions, cases] = await Promise.all([
    read<ImportApiDefinition[]>(`/api/v1/projects/${projectId.value}/api-design/definitions`),
    read<ImportApiCase[]>(`/api/v1/projects/${projectId.value}/api-design/cases`),
  ])
  importDefinitions.value = definitions
  importCases.value = cases
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

async function deleteScenario(scenarioId: string) {
  if (!projectId.value) return
  try {
    await mutate<void>(`/api/v1/projects/${projectId.value}/api-automation/scenarios/${scenarioId}`, 'DELETE')
    if (detail.value?.id === scenarioId) {
      detail.value = null
      selectedStepId.value = ''
      stepInspectorVisible.value = false
      selectedRunDataSetId.value = ''
    }
    await loadScenarios()
    Message.success('场景已删除')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '删除场景失败')
  }
}

async function load() {
  loading.value = true
  try {
    context.value = await read<Context>('/api/v1/access/context')
    projectId.value = session.resolveProjectId(projects.value)
    await Promise.all([loadEnvironments(), loadScenarios(), loadRunnerStatus(), loadExecutions(), loadDataSets(), loadReusableSteps(), loadExecutionProfiles(), loadWebhooks(), loadSchedules(), loadImportSources()])
    createForm.environmentCode = defaultEnvironmentCode.value
  } catch (error) { Message.error(error instanceof Error ? error.message : '加载接口自动化场景失败') } finally { loading.value = false }
}

function defaultStep(type: StepType): AutomationStep {
  const id = `${type.toLowerCase()}-${Date.now()}`
  const defaults: Record<StepType, Record<string, unknown>> = {
    REQUEST: { method: 'GET', url: '{{BASE_URL}}/health', maxAttempts: 1 }, EXTRACT: { source: 'LAST_STATUS_CODE', variable: 'lastStatusCode' },
    PARALLEL: { requests: [
      { method: 'GET', url: '{{BASE_URL}}/health', maxAttempts: 1 },
      { method: 'GET', url: '{{BASE_URL}}/version', maxAttempts: 1 },
    ] },
    ASSERT: { source: 'LAST_STATUS_CODE', expectedStatus: 200 }, CONDITION: { source: 'LAST_STATUS_CODE', operator: 'EQUALS', expectedStatus: 200 },
    LOOP: { totalRuns: 2 }, WAIT: { delayMillis: 300 },
    REUSABLE_STEP: { reusableStepId: '', reusableStepVersion: 1 }, SUB_SCENARIO: { scenarioId: '', scenarioVersion: 1 },
  }
  return { id, type, name: `新的${typeMeta[type].label}`, config: defaults[type] }
}

async function createScenario() {
  if (!projectId.value || !createForm.name.trim() || !createForm.environmentCode) { Message.warning('请填写场景名称并选择运行环境'); return }
  saving.value = true
  try {
    const scenario = await mutate<ScenarioDetail>(`/api/v1/projects/${projectId.value}/api-automation/scenarios`, 'POST', {
      ...createForm, steps: [defaultStep('REQUEST')], variables: {},
    })
    createVisible.value = false
    createForm.name = ''; createForm.description = ''; createForm.environmentCode = defaultEnvironmentCode.value; createForm.failureStrategy = 'STOP'
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
  stepInspectorVisible.value = true
}

function selectStep(stepId: string) {
  selectedStepId.value = stepId
  stepInspectorVisible.value = true
}

function openImport(mode: ImportMode) {
  if (!detail.value) { Message.warning('请先选择或新建一个场景'); return }
  importMode.value = mode
  importSelectionId.value = mode === 'INTERFACE' ? (importDefinitions.value[0]?.id ?? '') : (importCandidates.value[0]?.id ?? '')
  importVisible.value = true
}

function importedContentType(snapshot: ApiRunSnapshot): string {
  return snapshot.headerValues.find((header) => header.enabled && header.name.toLowerCase() === 'content-type')?.value ?? ''
}

async function importRequestStep() {
  if (!detail.value) return
  try {
    let request: AutomationStep
    let assertion: AutomationStep | null = null
    if (importMode.value === 'CURL') {
      const parsed = parseCurlCommand(importForm.curl)
      request = defaultStep('REQUEST')
      request.name = `${parsed.method} ${parsed.url.split('?')[0].split('/').filter(Boolean).at(-1) ?? '请求'}`
      request.config = { method: parsed.method, url: parsed.url, body: parsed.body, contentType: parsed.contentType, maxAttempts: 1 }
      if (parsed.externalizedSecrets.length) Message.info(`已将 ${parsed.externalizedSecrets.join('、')} 的直接凭据替换或移除，请改用环境密钥变量`)
    } else if (importMode.value === 'INTERFACE') {
      const definition = importDefinitions.value.find((item) => item.id === importSelectionId.value)
      if (!definition) throw new Error('请选择要导入的接口')
      const versions = await read<Array<{ id: string; requestBody: string }>>(`/api/v1/projects/${projectId.value}/api-design/definitions/${definition.id}/versions`)
      const requestBody = versions.find((version) => version.id === definition.currentVersionId)?.requestBody ?? versions[0]?.requestBody ?? ''
      request = defaultStep('REQUEST')
      request.name = definition.name
      request.config = { method: definition.method, url: `${importForm.baseUrl.replace(/\/+$/, '')}${definition.path}`, body: requestBody, contentType: requestBody ? 'application/json' : '', maxAttempts: 1 }
    } else {
      const source = importCandidates.value.find((item) => item.id === importSelectionId.value)
      if (!source) throw new Error('请选择要导入的接口用例')
      const definition = importDefinitions.value.find((item) => item.id === source.apiId)
      if (!definition) throw new Error('接口用例所属接口不存在或已删除')
      request = defaultStep('REQUEST')
      request.name = source.name
      request.config = {
        method: definition.method,
        url: buildApiRunUrl(definition, source.requestSnapshot),
        body: source.requestSnapshot.body,
        contentType: importedContentType(source.requestSnapshot),
        maxAttempts: 1,
      }
      if (source.expectedStatus) {
        assertion = defaultStep('ASSERT')
        assertion.name = `${source.name} · 状态断言`
        assertion.config = { source: 'LAST_STATUS_CODE', expectedStatus: source.expectedStatus }
      }
    }
    detail.value.steps.push(request)
    if (assertion) detail.value.steps.push(assertion)
    selectedStepId.value = request.id
    importVisible.value = false
    stepInspectorVisible.value = true
    Message.success(assertion ? '已导入请求与状态断言' : '已导入请求步骤')
  } catch (error) {
    Message.error(error instanceof Error ? error.message : '导入请求步骤失败')
  }
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
  selectedStep.value.config = { ...selectedStep.value.config, requests: [...requests, { method: 'GET', url: '{{BASE_URL}}/health', maxAttempts: 1 }] }
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

function openExecutionOptions(preserveSelections = false) {
  if (!detail.value) { Message.warning('请先选择一个场景'); return }
  if (!preserveSelections) {
    executionOptionsForm.executionProfileId = ''
    executionOptionsForm.overrides = '{}'
    executionOptionsForm.timeoutSeconds = 300
  }
  executionConfigurationPreview.value = null
  executionOptionsVisible.value = true
}

async function runCurrentScenario() {
  if (!detail.value) { Message.warning('请先选择一个场景'); return }
  if (selectedRunDataSetId.value) {
    dataSetExecutionForm.dataSetId = selectedRunDataSetId.value
    dataSetExecutionForm.scenarioId = detail.value.id
    dataSetExecutionForm.timeoutSeconds = executionOptionsForm.timeoutSeconds
    await submitDataSetExecution()
  } else {
    await submitExecution()
  }
  if (notificationEnabled.value) Message.info('已开启本页执行提醒，可在“测试报告”中持续查看最新状态')
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
  executionProfileForm.environmentCode = detail.value?.environmentCode || defaultEnvironmentCode.value
  executionProfileForm.variables = '{}'
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
    <section class="automation-workbench">
      <header class="automation-commandbar">
        <div class="commandbar-context">
          <span class="product-mark"><icon-code /></span>
          <div>
            <small>自动化测试 / {{ selectedProject?.name ?? '未分配项目' }}</small>
            <strong>{{ detail?.name ?? '接口自动化工作台' }}</strong>
          </div>
          <a-tag v-if="detail" size="small" color="purple">v{{ detail.version }}</a-tag>
        </div>
        <div class="commandbar-actions">
          <span class="runner-pill" :class="{ online: onlineRunner, trusted: runnerTrusted }"><i /> {{ runnerHttpExecutionEnabled ? 'Runner 可执行' : runnerTrusted ? 'Runner 已验证' : onlineRunner ? 'Runner 待验证' : 'Runner 离线' }}</span>
          <a-button size="small" @click="load"><icon-refresh /> 刷新</a-button>
          <a-button size="small" :disabled="!detail" @click="revisionVisible = true"><icon-history /> 修订记录</a-button>
        </div>
      </header>

      <div class="automation-workbench__body">
        <AutomationSidebar
          :scenarios="scenarios"
          :selected-scenario-id="detail?.id"
          :keyword="scenarioSearch"
          :loading="loading"
          :active-asset="workbenchTab"
          :data-set-count="dataSets.length"
          :schedule-count="schedules.length"
          :report-count="executions.length"
          @create="createVisible = true"
          @refresh="loadScenarios"
          @select="selectScenario"
          @delete="deleteScenario"
          @update:keyword="scenarioSearch = $event"
          @update:active-asset="workbenchTab = $event"
        />

        <main class="automation-studio">
          <template v-if="detail">
            <header class="studio-document-header">
              <div class="scenario-identity">
                <button class="priority-chip" aria-label="场景优先级">P2 <icon-down /></button>
                <div>
                  <a-input :model-value="detail.name" class="studio-title" aria-label="场景名称" @update:model-value="updateScenarioField('name', String($event))" />
                  <span>{{ detail.steps.length }} 个步骤 · {{ detail.environmentCode }} · 更新于 {{ date(detail.updatedAt) }}</span>
                </div>
              </div>
              <a-space>
                <a-button size="small" :disabled="!canSaveSelectedAsReusable" @click="openCreateReusableStep"><icon-save /> 存为公共步骤</a-button>
                <a-button type="primary" size="small" :loading="saving" @click="saveScenario"><icon-save /> 保存</a-button>
              </a-space>
            </header>

            <nav class="studio-tabs" aria-label="场景工作区">
              <button :class="{ active: workbenchTab === 'steps' }" @click="workbenchTab = 'steps'">测试步骤</button>
              <button :class="{ active: workbenchTab === 'data' }" @click="workbenchTab = 'data'">测试数据 <span>{{ dataSets.length }}</span></button>
              <button :class="{ active: workbenchTab === 'reports' }" @click="workbenchTab = 'reports'">测试报告 <span>{{ executions.length }}</span></button>
              <button :class="{ active: workbenchTab === 'integrations' }" @click="workbenchTab = 'integrations'">CI/CD</button>
            </nav>

            <section v-if="workbenchTab === 'steps'" class="studio-scroll">
              <div class="scenario-summary-line">
                <a-input :model-value="detail.description" placeholder="添加场景说明" allow-clear @update:model-value="updateScenarioField('description', String($event))" />
                <a-tag :color="detail.failureStrategy === 'STOP' ? 'orange' : 'green'">{{ detail.failureStrategy === 'STOP' ? '遇错停止' : '失败后继续' }}</a-tag>
              </div>
              <div class="scenario-editor-tabs">
                <button :class="{ active: scenarioEditorTab === 'flow' }" @click="scenarioEditorTab = 'flow'"><icon-branch /> 步骤编排</button>
                <button :class="{ active: scenarioEditorTab === 'scripts' }" @click="scenarioEditorTab = 'scripts'"><icon-code-block /> 前后置脚本</button>
                <button :class="{ active: scenarioEditorTab === 'variables' }" @click="scenarioEditorTab = 'variables'"><icon-tags /> 场景变量</button>
              </div>

              <template v-if="scenarioEditorTab === 'flow'">
                <div class="flow-overview"><strong>{{ detail.steps.length }} 个步骤</strong><span>{{ runnerHttpExecutionEnabled ? '可信 Runner 已启用受限执行' : '可编辑并保存，执行受 Runner 安全开关限制' }}</span></div>
                <section class="scenario-flow-canvas">
                  <div class="flow-terminal start">开始</div>
                  <template v-for="(step, index) in detail.steps" :key="step.id">
                    <div class="scenario-flow-line"><span /></div>
                    <button class="scenario-flow-node" :class="[{ selected: selectedStepId === step.id }, `node-${typeMeta[step.type].tone}`]" @click="selectStep(step.id)">
                      <span class="scenario-flow-index">{{ index + 1 }}</span>
                      <span class="scenario-flow-icon">{{ typeMeta[step.type].icon }}</span>
                      <span class="scenario-flow-copy"><strong>{{ step.name }}</strong><small>{{ typeMeta[step.type].label }} · {{ typeMeta[step.type].description }}</small></span>
                      <icon-settings />
                    </button>
                  </template>
                  <div class="scenario-flow-line"><span /></div>
                  <a-popover trigger="click" position="bottom" :content-style="{ padding: '0' }">
                    <button class="flow-add-trigger"><icon-plus /> 添加步骤</button>
                    <template #content>
                      <div class="step-library">
                        <section>
                          <small>请求接口</small>
                          <button @click="openImport('INTERFACE')"><span class="step-library-icon purple"><icon-import /></span><div><strong>从接口导入</strong><em>{{ importDefinitions.length }} 个可用接口</em></div></button>
                          <button @click="openImport('TEST_CASE')"><span class="step-library-icon amber">TC</span><div><strong>从单接口用例导入</strong><em>自动附加状态断言</em></div></button>
                          <button @click="openImport('DEBUG_CASE')"><span class="step-library-icon blue"><icon-thunderbolt /></span><div><strong>从接口调试用例导入</strong><em>复用已保存请求参数</em></div></button>
                          <button @click="addStep('REQUEST')"><span class="step-library-icon slate"><icon-send /></span><div><strong>添加 HTTP 请求</strong><em>手工配置方法与地址</em></div></button>
                          <button @click="openImport('CURL')"><span class="step-library-icon slate">&gt;_</span><div><strong>从 cURL 导入</strong><em>自动移除直接凭据</em></div></button>
                        </section>
                        <section>
                          <small>流程控制</small>
                          <div class="step-library-grid">
                            <button @click="addStep('PARALLEL')"><span class="step-library-icon cyan">⇆</span><strong>并行分组</strong></button>
                            <button @click="addStep('CONDITION')"><span class="step-library-icon pink">◇</span><strong>条件分支</strong></button>
                            <button @click="addStep('LOOP')"><span class="step-library-icon orange">↻</span><strong>For 循环</strong></button>
                            <button @click="addStep('WAIT')"><span class="step-library-icon cyan">◷</span><strong>等待时间</strong></button>
                            <button @click="addStep('EXTRACT')"><span class="step-library-icon purple">⇣</span><strong>提取变量</strong></button>
                            <button @click="addStep('ASSERT')"><span class="step-library-icon green">✓</span><strong>响应断言</strong></button>
                            <button @click="addStep('REUSABLE_STEP')"><span class="step-library-icon purple">♧</span><strong>公共步骤</strong></button>
                            <button @click="addStep('SUB_SCENARIO')"><span class="step-library-icon blue">⌘</span><strong>其他场景</strong></button>
                          </div>
                        </section>
                        <footer><icon-safe /> 任意脚本和数据库直连不进入控制面；脚本仅在隔离 Runner 中执行。</footer>
                      </div>
                    </template>
                  </a-popover>
                  <div class="scenario-flow-line"><span /></div>
                  <div class="flow-terminal end">结束</div>
                </section>
              </template>

              <section v-else-if="scenarioEditorTab === 'scripts'" class="scenario-script-editor">
                <div class="script-safety"><icon-safe /><div><strong>隔离 JavaScript · pm.* 兼容</strong><small>脚本在 Runner 容器中限时执行，不可访问网络、文件、进程、模块、Cookie 或环境密钥。</small></div></div>
                <a-tabs default-active-key="pre">
                  <a-tab-pane key="pre" title="前置脚本"><a-textarea :model-value="detail.preRequestScript" :max-length="8192" :auto-size="{ minRows: 13, maxRows: 20 }" placeholder="// pm.environment.set('orderId', pm.variables.get('orderId'));" @update:model-value="updateScenarioScript('preRequestScript', String($event))" /></a-tab-pane>
                  <a-tab-pane key="post" title="后置脚本"><a-textarea :model-value="detail.postResponseScript" :max-length="8192" :auto-size="{ minRows: 13, maxRows: 20 }" placeholder="// pm.test('状态正确', () => pm.response.to.have.status(200));" @update:model-value="updateScenarioScript('postResponseScript', String($event))" /></a-tab-pane>
                </a-tabs>
              </section>

              <section v-else class="scenario-variable-editor">
                <header><div><strong>场景变量</strong><small>变量仅作用于当前场景；Token、密码等凭据请使用项目环境密钥。</small></div><a-button type="primary" size="small" @click="addVariable"><icon-plus /> 添加变量</a-button></header>
                <div class="variable-table-head"><span>变量名</span><span>变量值</span><span /></div>
                <div v-for="(row, index) in variableRows" :key="index" class="variable-table-row"><a-input v-model="row.key" placeholder="例如：baseUrl" /><a-input v-model="row.value" placeholder="支持 {{变量名}} 引用" /><a-button type="text" status="danger" @click="removeVariable(index)"><icon-delete /></a-button></div>
                <a-empty v-if="!variableRows.length" description="尚未定义场景变量" />
              </section>
            </section>

            <section v-else-if="workbenchTab === 'data'" class="studio-scroll asset-page">
              <header class="asset-page-header"><div><span>DATA DRIVEN</span><h2>测试数据</h2><p>导入 CSV 或 JSON，每一行创建独立的隔离执行任务。</p></div><a-button type="primary" @click="openCreateDataSet"><icon-plus /> 新建数据集</a-button></header>
              <div class="asset-card-list">
                <article v-for="dataSet in dataSets" :key="dataSet.id" class="asset-card"><span class="asset-card-icon cyan"><icon-storage /></span><div><strong>{{ dataSet.name }}</strong><small>{{ dataSet.sourceFormat }} · {{ dataSet.rowCount }} 行 · {{ dataSet.columns.length }} 字段 · {{ date(dataSet.updatedAt) }}</small><code>{{ dataSet.columns.join(' · ') }}</code></div><a-tag :color="dataSet.sourceFile ? 'green' : 'arcoblue'">{{ dataSet.sourceFile ? '已归档' : '手工录入' }}</a-tag><a-button type="text" @click="showDataSet(dataSet)">预览</a-button><a-button type="text" @click="openDataSetExecution(dataSet)">逐行执行</a-button><a-popconfirm content="删除后不可恢复，确定继续？" @ok="deleteDataSet(dataSet)"><a-button type="text" status="danger" :loading="deletingDataSetId === dataSet.id">删除</a-button></a-popconfirm></article>
                <a-empty v-if="!dataSets.length" description="尚未创建测试数据"><template #extra><a-button type="outline" @click="openCreateDataSet">导入首个 CSV / JSON</a-button></template></a-empty>
              </div>
            </section>

            <section v-else-if="workbenchTab === 'reports'" class="studio-scroll asset-page">
              <header class="asset-page-header"><div><span>RUNNER REPORTS</span><h2>测试报告</h2><p>按任务查看状态、Runner、数据行、耗时和脱敏结构化结果。</p></div><a-button @click="loadExecutions"><icon-refresh /> 刷新</a-button></header>
              <a-table :data="executions" :pagination="{ pageSize: 12 }" row-key="id" size="small">
                <template #columns>
                  <a-table-column title="场景 / 数据行" :width="220"><template #cell="{ record }"><strong>{{ record.scenarioName }}</strong><small class="table-secondary">v{{ record.scenarioVersion }}<template v-if="record.dataSetId"> · 数据行 {{ record.dataSetRowNumber }}</template></small></template></a-table-column>
                  <a-table-column title="状态" :width="110"><template #cell="{ record }"><a-tag :color="executionColor[record.status] ?? 'gray'">{{ executionLabel[record.status] ?? record.status }}</a-tag></template></a-table-column>
                  <a-table-column title="Runner / 原因"><template #cell="{ record }"><span>{{ record.runnerId ?? '等待投递' }}</span><small class="table-secondary">{{ record.statusMessage || 'Outbox 持久化任务' }}</small></template></a-table-column>
                  <a-table-column title="提交时间" :width="170"><template #cell="{ record }">{{ date(record.requestedAt) }}</template></a-table-column>
                  <a-table-column title="操作" :width="130"><template #cell="{ record }"><a-button type="text" size="small" @click="showExecutionDetail(record)">查看报告</a-button><a-button v-if="canCancelExecution(record.status)" type="text" status="warning" size="small" @click="cancelExecution(record)">取消</a-button></template></a-table-column>
                </template>
              </a-table>
              <a-empty v-if="!executions.length" description="尚未提交执行任务" />
            </section>

            <section v-else class="studio-scroll asset-page">
              <header class="asset-page-header"><div><span>CI · SCHEDULE · WEBHOOK</span><h2>持续集成与触发器</h2><p>使用 CLI、定时计划或签名 Webhook 触发同一冻结场景版本。</p></div><a-space><a-button @click="openCreateSchedule"><icon-calendar /> 新建计划</a-button><a-button type="primary" @click="openCreateWebhook"><icon-plus /> 新建 Webhook</a-button></a-space></header>
              <div class="integration-grid">
                <section class="integration-card"><header><span class="asset-card-icon purple"><icon-code /></span><div><strong>CLI / CI</strong><small>适用于 Jenkins、GitLab CI 与 GitHub Actions</small></div></header><a-tabs default-active-key="run" size="mini"><a-tab-pane key="run" title="触发"><pre><code>{{ cliExecutionCommand }}</code></pre></a-tab-pane><a-tab-pane key="report" title="报告"><pre><code>{{ cliReportCommand }}</code></pre></a-tab-pane></a-tabs></section>
                <section class="integration-card"><header><span class="asset-card-icon blue"><icon-link /></span><div><strong>签名 Webhook</strong><small>密钥仅在创建时显示一次</small></div></header><code class="webhook-address">{{ webhookEndpoint }}</code></section>
              </div>
              <div class="asset-section-heading"><div><strong>运行配置集</strong><small>冻结环境与非敏感变量，可在右侧运行设置中选择</small></div><a-button size="small" @click="openCreateExecutionProfile"><icon-plus /> 添加</a-button></div>
              <div class="asset-card-list compact"><article v-for="profile in executionProfiles" :key="profile.id" class="asset-card"><span class="asset-card-icon cyan">◈</span><div><strong>{{ profile.name }}</strong><small>{{ profile.environmentCode }} · v{{ profile.version }} · {{ profile.variableCount }} 个变量</small></div><a-tag color="cyan">执行时冻结</a-tag><a-button type="text" @click="openEditExecutionProfile(profile)">编辑</a-button></article><a-empty v-if="!executionProfiles.length" description="尚未创建运行配置集" /></div>
              <div class="asset-section-heading"><div><strong>定时任务</strong><small>连续失败达到阈值后自动停用</small></div><a-button size="small" @click="openCreateSchedule"><icon-plus /> 添加</a-button></div>
              <div class="asset-card-list compact"><article v-for="schedule in schedules" :key="schedule.id" class="asset-card"><span class="asset-card-icon orange"><icon-calendar /></span><div><strong>{{ schedule.name }}</strong><small>{{ schedule.cronExpression }} · {{ schedule.timezone }} · 连续失败 {{ schedule.consecutiveFailures }}/{{ schedule.maxConsecutiveFailures }}</small></div><a-tag :color="schedule.enabled ? 'green' : 'gray'">{{ schedule.enabled ? '已启用' : '已停用' }}</a-tag><a-button type="text" @click="runSchedule(schedule)">立即执行</a-button><a-button type="text" @click="openEditSchedule(schedule)">编辑</a-button><a-popconfirm content="确定删除该计划？" @ok="deleteSchedule(schedule)"><a-button type="text" status="danger">删除</a-button></a-popconfirm></article><a-empty v-if="!schedules.length" description="尚未创建定时任务" /></div>
              <div class="asset-section-heading"><div><strong>Webhook</strong><small>HMAC 签名、防重放、项目范围隔离</small></div><a-button size="small" @click="openCreateWebhook"><icon-plus /> 添加</a-button></div>
              <div class="asset-card-list compact"><article v-for="webhook in webhooks" :key="webhook.id" class="asset-card"><span class="asset-card-icon blue"><icon-link /></span><div><strong>{{ webhook.name }}</strong><small>{{ scenarios.find((item) => item.id === webhook.scenarioId)?.name ?? webhook.scenarioId }} · {{ webhook.id }}</small></div><a-tag :color="webhook.enabled ? 'green' : 'gray'">{{ webhook.enabled ? '已启用' : '已停用' }}</a-tag><a-button type="text" @click="setWebhookEnabled(webhook, !webhook.enabled)">{{ webhook.enabled ? '停用' : '启用' }}</a-button><a-popconfirm content="撤销后不能恢复，确定继续？" @ok="revokeWebhook(webhook)"><a-button type="text" status="danger">撤销</a-button></a-popconfirm></article><a-empty v-if="!webhooks.length" description="尚未创建 Webhook" /></div>
            </section>
          </template>
          <a-empty v-else class="studio-empty" description="选择或新建一个场景用例开始编排"><template #extra><a-button type="primary" @click="createVisible = true"><icon-plus /> 新建场景</a-button></template></a-empty>
        </main>

        <AutomationRunPanel
          :has-scenario="Boolean(detail)"
          :scenario-environment="detail?.environmentCode ?? ''"
          :environments="environments"
          :failure-strategy="detail?.failureStrategy ?? 'STOP'"
          :data-sets="dataSets"
          :profiles="executionProfiles"
          :selected-data-set-id="selectedRunDataSetId"
          :selected-profile-id="executionOptionsForm.executionProfileId"
          :timeout-seconds="executionOptionsForm.timeoutSeconds"
          :runner-connected="Boolean(onlineRunner)"
          :runner-trusted="runnerTrusted"
          :execution-enabled="runnerHttpExecutionEnabled"
          :runner-label="runnerSummary"
          :notification-enabled="notificationEnabled"
          :saving="saving"
          :running="submittingExecution || submittingDataSetExecution"
          @update:selected-data-set-id="selectedRunDataSetId = $event"
          @update:selected-profile-id="executionOptionsForm.executionProfileId = $event"
          @update:timeout-seconds="executionOptionsForm.timeoutSeconds = $event"
          @update:notification-enabled="notificationEnabled = $event"
          @update:failure-strategy="updateScenarioField('failureStrategy', $event)"
          @update:scenario-environment="updateScenarioField('environmentCode', $event)"
          @create-profile="openCreateExecutionProfile"
          @advanced="openExecutionOptions(true)"
          @save="saveScenario"
          @run="runCurrentScenario"
        />
      </div>
    </section>

    <a-drawer v-model:visible="stepInspectorVisible" :width="420" :footer="false" unmount-on-close>
      <template #title><span class="inspector-drawer-title">步骤配置 <a-tag v-if="selectedStep" size="small" color="purple">{{ typeMeta[selectedStep.type].label }}</a-tag></span></template>
      <template v-if="detail && selectedStep">
        <a-form :model="selectedStep" layout="vertical">
          <a-form-item label="步骤名称"><a-input :model-value="selectedStep.name" @update:model-value="renameStep(String($event))" /></a-form-item>
          <template v-if="selectedStep.type === 'REQUEST'">
            <a-form-item label="请求方法"><a-select :model-value="String(configValue('method', 'GET'))" @change="setRequestMethod(String($event))"><a-option v-for="method in ['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS']" :key="method" :value="method">{{ method }}</a-option></a-select></a-form-item>
            <a-form-item label="请求地址"><a-input :model-value="String(configValue('url'))" placeholder="{{BASE_URL}}/orders" @update:model-value="setConfig('url', String($event))" /></a-form-item>
            <a-form-item label="内容类型"><a-select :model-value="String(configValue('contentType', ''))" allow-clear placeholder="未设置" @change="setConfig('contentType', String($event ?? ''))"><a-option value="application/json">application/json</a-option><a-option value="application/x-www-form-urlencoded">application/x-www-form-urlencoded</a-option><a-option value="text/plain">text/plain</a-option><a-option value="application/xml">application/xml</a-option></a-select></a-form-item>
            <a-form-item label="请求正文"><a-textarea :model-value="String(configValue('body', ''))" :auto-size="{ minRows: 7, maxRows: 14 }" placeholder="支持 {{变量名}}；凭据请使用环境密钥" @update:model-value="setConfig('body', String($event))" /></a-form-item>
            <a-form-item label="最大尝试次数"><a-select :model-value="Number(configValue('maxAttempts', 1))" @change="setConfig('maxAttempts', Number($event))"><a-option :value="1">1 次</a-option><a-option :value="2" :disabled="!['GET','HEAD','OPTIONS'].includes(String(configValue('method', 'GET')))">2 次</a-option><a-option :value="3" :disabled="!['GET','HEAD','OPTIONS'].includes(String(configValue('method', 'GET')))">3 次</a-option></a-select></a-form-item>
          </template>
          <template v-else-if="selectedStep.type === 'PARALLEL'">
            <article v-for="(request, index) in parallelRequests()" :key="index" class="parallel-editor-card"><header><strong>并行请求 {{ index + 1 }}</strong><a-button type="text" status="danger" size="mini" :disabled="parallelRequests().length <= 2" @click="removeParallelRequest(index)">移除</a-button></header><a-input :model-value="request.method" @update:model-value="updateParallelRequest(index, 'method', String($event))" /><a-input :model-value="request.url" placeholder="{{BASE_URL}}/health" @update:model-value="updateParallelRequest(index, 'url', String($event))" /></article>
            <a-button long type="outline" :disabled="parallelRequests().length >= 5" @click="addParallelRequest"><icon-plus /> 添加并行请求</a-button>
          </template>
          <template v-else-if="selectedStep.type === 'EXTRACT'"><a-form-item label="保存为变量"><a-input :model-value="String(configValue('variable', 'lastStatusCode'))" @update:model-value="setConfig('variable', String($event))" /></a-form-item><a-alert type="info">当前仅提取最近一次 HTTP 状态码。</a-alert></template>
          <template v-else-if="selectedStep.type === 'ASSERT'"><a-form-item label="期望状态码"><a-input-number :model-value="Number(configValue('expectedStatus', 200))" :min="100" :max="599" :style="{ width: '100%' }" @change="setConfig('expectedStatus', Number($event))" /></a-form-item></template>
          <template v-else-if="selectedStep.type === 'CONDITION'"><a-form-item label="条件"><a-select :model-value="String(configValue('operator', 'EQUALS'))" @change="setConfig('operator', String($event))"><a-option value="EQUALS">状态码等于</a-option><a-option value="NOT_EQUALS">状态码不等于</a-option></a-select></a-form-item><a-form-item label="状态码"><a-input-number :model-value="Number(configValue('expectedStatus', 200))" :min="100" :max="599" :style="{ width: '100%' }" @change="setConfig('expectedStatus', Number($event))" /></a-form-item></template>
          <template v-else-if="selectedStep.type === 'LOOP'"><a-form-item label="总执行次数"><a-input-number :model-value="Number(configValue('totalRuns', 2))" :min="1" :max="10" :style="{ width: '100%' }" @change="setConfig('totalRuns', Number($event))" /></a-form-item></template>
          <template v-else-if="selectedStep.type === 'WAIT'"><a-form-item label="等待时间"><a-input-number :model-value="Number(configValue('delayMillis', 300))" :min="0" :max="600000" :style="{ width: '100%' }" @change="setConfig('delayMillis', Number($event))"><template #suffix>ms</template></a-input-number></a-form-item></template>
          <template v-else-if="selectedStep.type === 'REUSABLE_STEP'"><a-form-item label="公共步骤"><a-select :model-value="String(configValue('reusableStepId', ''))" @change="selectReusableStep(String($event))"><a-option v-for="item in reusableSteps" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.version }}</a-option></a-select></a-form-item></template>
          <template v-else-if="selectedStep.type === 'SUB_SCENARIO'"><a-form-item label="其他场景"><a-select :model-value="String(configValue('scenarioId', ''))" @change="selectSubScenario(String($event))"><a-option v-for="item in scenarios.filter((scenario) => scenario.id !== detail?.id)" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.version }}</a-option></a-select></a-form-item></template>
        </a-form>
        <div class="inspector-actions"><a-popconfirm content="确定删除当前步骤？" @ok="removeStep"><a-button status="danger"><icon-delete /> 删除步骤</a-button></a-popconfirm><a-button type="primary" :loading="saving" @click="saveScenario"><icon-save /> 保存场景</a-button></div>
      </template>
    </a-drawer>

    <a-modal v-model:visible="importVisible" title="导入请求步骤" width="680px" :ok-text="'导入到当前场景'" @ok="importRequestStep">
      <a-tabs v-model:active-key="importMode" type="rounded">
        <a-tab-pane key="INTERFACE" title="接口" />
        <a-tab-pane key="TEST_CASE" title="单接口用例" />
        <a-tab-pane key="DEBUG_CASE" title="调试用例" />
        <a-tab-pane key="CURL" title="cURL" />
      </a-tabs>
      <a-form :model="importForm" layout="vertical" class="import-form">
        <template v-if="importMode === 'INTERFACE'">
          <a-form-item label="选择接口" required><a-select v-model="importSelectionId" show-search placeholder="请选择接口"><a-option v-for="definition in importDefinitions" :key="definition.id" :value="definition.id">{{ definition.method }} · {{ definition.name }} · {{ definition.path }}</a-option></a-select></a-form-item>
          <a-form-item label="基础地址"><a-input v-model="importForm.baseUrl" placeholder="{{BASE_URL}}" /></a-form-item>
        </template>
        <template v-else-if="importMode === 'TEST_CASE' || importMode === 'DEBUG_CASE'">
          <a-form-item label="选择用例" required><a-select v-model="importSelectionId" show-search placeholder="请选择接口用例"><a-option v-for="item in importCandidates" :key="item.id" :value="item.id">{{ item.name }} · {{ importDefinitions.find((definition) => definition.id === item.apiId)?.method }}</a-option></a-select></a-form-item>
          <a-alert type="info" :show-icon="true">请求参数来自已保存快照；直接凭据在用例保存时已被清除。单接口测试用例若配置期望状态码，会同时导入断言步骤。</a-alert>
        </template>
        <template v-else>
          <a-form-item label="cURL 命令" required><a-textarea v-model="importForm.curl" :auto-size="{ minRows: 10, maxRows: 18 }" placeholder="curl -X POST 'https://api.example.com/orders' --data-raw '{&quot;name&quot;:&quot;demo&quot;}'" /></a-form-item>
          <a-alert type="warning" :show-icon="true">Authorization、Cookie、URL 凭据以及常见密码字段不会以明文写入场景；请将生成的变量占位符配置为项目环境密钥。</a-alert>
        </template>
      </a-form>
    </a-modal>

    <a-modal v-model:visible="createVisible" title="新建接口自动化场景" width="600px" :confirm-loading="saving" @ok="createScenario">
      <a-form :model="createForm" layout="vertical">
        <a-form-item label="场景名称" required>
          <a-input v-model="createForm.name" :max-length="160" placeholder="例如：订单核心链路回归" />
        </a-form-item>
        <a-form-item label="场景说明">
          <a-textarea v-model="createForm.description" :max-length="1200" placeholder="说明该场景覆盖的业务路径" />
        </a-form-item>
        <div class="form-grid">
          <a-form-item label="运行环境" required>
            <a-select v-model="createForm.environmentCode" placeholder="请选择项目环境">
              <a-option v-for="environment in environments" :key="environment.id" :value="environment.environmentCode" :disabled="!environment.enabled">{{ environment.name }} · {{ environment.environmentCode }}</a-option>
            </a-select>
          </a-form-item>
          <a-form-item label="失败策略">
            <a-select v-model="createForm.failureStrategy">
              <a-option value="STOP">失败时停止（默认）</a-option>
              <a-option value="CONTINUE">失败后继续（最终仍失败）</a-option>
            </a-select>
          </a-form-item>
        </div>
        <div class="modal-tip">将自动创建一个引用 <code>BASE_URL</code> 的 GET 请求步骤，执行时由所选项目环境解析。Runner 接入前，保存不会发起任何外部请求。</div>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="executionProfileVisible" :title="editingExecutionProfileId ? '编辑场景运行配置集' : '新建场景运行配置集'" width="680px" :confirm-loading="savingExecutionProfile" @ok="createExecutionProfile">
      <a-form :model="executionProfileForm" layout="vertical">
        <a-form-item label="配置集名称" required>
          <a-input v-model="executionProfileForm.name" :max-length="160" placeholder="例如：预发订单回归" />
        </a-form-item>
        <a-form-item label="执行环境" required>
          <a-select v-model="executionProfileForm.environmentCode" placeholder="请选择项目环境">
            <a-option v-for="environment in environments" :key="environment.id" :value="environment.environmentCode" :disabled="!environment.enabled">{{ environment.name }} · {{ environment.environmentCode }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="非敏感变量（JSON 对象）">
          <a-textarea v-model="executionProfileForm.variables" :auto-size="{ minRows: 7, maxRows: 12 }" :max-length="262144" />
        </a-form-item>
        <a-alert type="warning" :show-icon="true">只接受字符串值，最多 100 项。配置集变量会在执行时冻结，但不能覆盖当前环境密钥；密钥必须使用项目环境密钥管理。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="executionOptionsVisible" title="提交场景执行" width="720px" :confirm-loading="submittingExecution" @ok="submitExecution">
      <a-form :model="executionOptionsForm" layout="vertical">
        <a-form-item label="运行配置集（可选）">
          <a-select v-model="executionOptionsForm.executionProfileId" allow-clear placeholder="使用场景默认环境" @change="clearExecutionConfigurationPreview">
            <a-option v-for="profile in executionProfiles" :key="profile.id" :value="profile.id">{{ profile.name }} · {{ profile.environmentCode }} · v{{ profile.version }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="本次覆盖变量（JSON 对象）">
          <a-textarea v-model="executionOptionsForm.overrides" :auto-size="{ minRows: 5, maxRows: 10 }" :max-length="262144" placeholder="{&quot;orderId&quot;:&quot;OD-1001&quot;}" @update:model-value="clearExecutionConfigurationPreview" />
        </a-form-item>
        <a-button type="outline" size="small" :loading="previewingExecutionConfiguration" @click="previewExecutionConfiguration">
          <icon-eye /> 预览最终来源</a-button>
        <section v-if="executionConfigurationPreview" class="execution-configuration-preview">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="场景版本">v{{ executionConfigurationPreview.scenarioVersion }}</a-descriptions-item>
            <a-descriptions-item label="执行环境">{{ executionConfigurationPreview.environmentCode }}</a-descriptions-item>
            <a-descriptions-item label="配置集" :span="2">{{ executionConfigurationPreview.executionProfileId ? `v${executionConfigurationPreview.executionProfileVersion}（提交时固定）` : '使用场景默认环境' }}</a-descriptions-item>
          </a-descriptions>
          <div class="execution-configuration-preview-head">
            <strong>引用变量的最终来源</strong>
            <small>只显示名称、来源与使用位置，不显示变量或密钥取值。</small>
          </div>
          <a-table :data="executionConfigurationPreview.variableReferences" :pagination="false" size="small" row-key="name">
            <template #columns>
              <a-table-column title="变量" data-index="name" :width="130" />
              <a-table-column title="最终来源" :width="170">
                <template #cell="{ record }">
                  <a-tag :color="record.secret ? 'orange' : 'arcoblue'">{{ record.source }}</a-tag>
                </template>
              </a-table-column>
              <a-table-column title="使用位置">
                <template #cell="{ record }">{{ referenceLocations(record) }}</template>
              </a-table-column>
            </template>
          </a-table>
          <a-empty v-if="!executionConfigurationPreview.variableReferences.length" description="当前场景没有模板变量引用" />
        </section>
        <a-form-item label="执行超时">
          <a-input-number v-model="executionOptionsForm.timeoutSeconds" :min="10" :max="3600" :style="{ width: '100%' }">
            <template #suffix>秒</template>
          </a-input-number>
        </a-form-item>
        <a-alert type="info" :show-icon="true">优先级从低到高：项目参数、场景变量、运行配置集、数据集行、本次覆盖变量、环境密钥、Runner 局部变量。预览和任务创建都只保留脱敏来源，值不在浏览器、日志、清单或报告中展示。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="reusableStepVisible" title="保存项目级公共步骤" width="620px" :confirm-loading="savingReusableStep" @ok="createReusableStep">
      <a-form :model="reusableStepForm" layout="vertical">
        <a-form-item label="公共步骤名称" required>
          <a-input v-model="reusableStepForm.name" :max-length="160" placeholder="例如：获取订单健康状态" />
        </a-form-item>
        <a-form-item label="说明">
          <a-textarea v-model="reusableStepForm.description" :max-length="1200" placeholder="说明此基础步骤的前置条件与用途" />
        </a-form-item>
        <a-alert type="info" :show-icon="true">会复制当前选中的基础步骤并创建 v1。公共步骤不能嵌套公共步骤或子场景；后续更新会产生新版本，现有引用不会漂移。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="dataSetVisible" title="新建接口自动化数据集" width="720px" :confirm-loading="savingDataSet" @ok="createDataSet">
      <a-form :model="dataSetForm" layout="vertical">
        <div class="form-grid">
          <a-form-item label="数据集名称" required>
            <a-input v-model="dataSetForm.name" :max-length="160" placeholder="例如：订单回归参数" />
          </a-form-item>
          <a-form-item label="来源格式" required>
            <a-select v-model="dataSetForm.sourceFormat">
              <a-option value="JSON">JSON 对象数组</a-option>
              <a-option value="CSV">CSV（首行为字段名）</a-option>
            </a-select>
          </a-form-item>
        </div>
        <a-form-item label="导入方式" required>
          <a-radio-group v-model="dataSetForm.sourceMode" type="button">
            <a-radio value="TEXT">粘贴内容</a-radio>
            <a-radio value="FILE">上传原始文件</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="dataSetForm.sourceMode === 'TEXT'" label="数据内容" required>
          <a-textarea v-model="dataSetForm.content" :auto-size="{ minRows: 12, maxRows: 18 }" :max-length="524288" placeholder="JSON 或 CSV 数据内容" />
        </a-form-item>
        <a-form-item v-else label="原始 CSV / JSON 文件" required>
          <input ref="dataSetFileInput" class="data-set-file-input" type="file" accept=".csv,.json,application/json,text/csv" @change="dataSetFileChanged" />
          <div class="data-set-file-picker">
            <div>
              <strong>{{ dataSetForm.file?.name ?? '尚未选择文件' }}</strong>
              <small v-if="dataSetForm.file">{{ dataSetForm.file.size }} 字节 · 将按当前来源格式校验并归档至 MinIO</small>
              <small v-else>仅支持 UTF-8 编码的 .csv / .json，最大 512 KiB</small>
            </div>
            <a-button @click="selectDataSetFile">选择文件</a-button>
          </div>
        </a-form-item>
        <a-alert type="warning" :show-icon="true">字段名只能使用字母、数字和下划线，且不能以数字开头；最多 50 个字段、200 行。不要把 API Token、密码或私钥写入数据集。文件导入会保存原始附件，但附件不会发送给 Runner。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="dataSetExecutionVisible" title="按数据集逐行执行" width="560px" :confirm-loading="submittingDataSetExecution" @ok="submitDataSetExecution">
      <a-form :model="dataSetExecutionForm" layout="vertical">
        <a-form-item label="数据集">
          <a-select v-model="dataSetExecutionForm.dataSetId">
            <a-option v-for="dataSet in dataSets" :key="dataSet.id" :value="dataSet.id">{{ dataSet.name }} · {{ dataSet.rowCount }} 行</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="执行场景">
          <a-select v-model="dataSetExecutionForm.scenarioId">
            <a-option v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">{{ scenario.name }} · v{{ scenario.version }}</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="单行超时">
          <a-input-number v-model="dataSetExecutionForm.timeoutSeconds" :min="10" :max="3600" :style="{ width: '100%' }">
            <template #suffix>秒</template>
          </a-input-number>
        </a-form-item>
        <a-alert type="info" :show-icon="true">确认后将根据数据集行数创建多个独立 Runner 任务。每条任务只冻结其本行所需的非敏感变量，任务消息和报告不携带行内取值。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="dataSetPreviewVisible" :title="dataSetDetail ? `${dataSetDetail.dataSet.name} · 数据预览` : '数据集预览'" :footer="false" width="780px">
      <template v-if="dataSetDetail">
        <a-descriptions :column="3" size="small" bordered>
          <a-descriptions-item label="来源格式">{{ dataSetDetail.dataSet.sourceFormat }}</a-descriptions-item>
          <a-descriptions-item label="总行数">{{ dataSetDetail.dataSet.rowCount }}</a-descriptions-item>
          <a-descriptions-item label="字段数">{{ dataSetDetail.dataSet.columns.length }}</a-descriptions-item>
          <a-descriptions-item label="原始文件" :span="3">
            <template v-if="dataSetDetail.dataSet.sourceFile">
              <a-link :href="sourceFileDownloadUrl(dataSetDetail.dataSet)" target="_blank">{{ dataSetDetail.dataSet.sourceFile.originalName }}（{{ dataSetDetail.dataSet.sourceFile.sizeBytes }} 字节）</a-link>
              <small class="data-set-source-file-note">已按项目权限归档到 MinIO；下载需保持当前登录会话。</small>
            </template>
            <span v-else>手工粘贴录入（无原始附件）</span>
          </a-descriptions-item>
          <a-descriptions-item label="校验和" :span="3">
            <code>{{ dataSetDetail.dataSet.checksum }}</code>
          </a-descriptions-item>
        </a-descriptions>
        <div class="data-set-preview-head">
          <strong>前 {{ dataSetDetail.previewRows.length }} 行预览</strong>
          <small>预览仅对当前项目有读取权限的成员可见。</small>
        </div>
        <a-table :data="dataSetDetail.previewRows" :pagination="false" size="small" :scroll="{ x: 680 }">
          <template #columns>
            <a-table-column v-for="column in dataSetDetail.dataSet.columns" :key="column" :title="column" :data-index="column" :width="160" />
          </template>
        </a-table>
        <a-empty v-if="!dataSetDetail.previewRows.length" description="没有可预览的数据行" />
      </template>
    </a-modal>
    <a-modal v-model:visible="webhookVisible" title="新建签名 Webhook" width="580px" :confirm-loading="savingWebhook" @ok="createWebhook">
      <a-form :model="webhookForm" layout="vertical">
        <a-form-item label="Webhook 名称" required>
          <a-input v-model="webhookForm.name" :max-length="160" placeholder="例如：发布后订单回归" />
        </a-form-item>
        <a-form-item label="触发场景" required>
          <a-select v-model="webhookForm.scenarioId">
            <a-option v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">{{ scenario.name }} · v{{ scenario.version }}</a-option>
          </a-select>
        </a-form-item>
        <a-alert type="warning" :show-icon="true">系统只保存 AES-GCM 加密后的 HMAC 密钥；创建成功后会仅展示一次原始密钥。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="webhookSecretVisible" title="立即保存 Webhook 密钥" :footer="false" width="640px" @cancel="createdWebhookSecret = ''">
      <a-alert type="warning" :show-icon="true">此密钥不会再次显示。请保存到 CI 的受保护密钥变量中，不要提交至源码。</a-alert>
      <pre class="webhook-secret">
<code>{{ createdWebhookSecret }}</code>
</pre>
      <a-button type="primary" long @click="webhookSecretVisible = false; createdWebhookSecret = ''">我已安全保存</a-button>
    </a-modal>
    <a-modal v-model:visible="scheduleVisible" :title="editingScheduleId ? '编辑 Quartz 定时计划' : '新建 Quartz 定时计划'" width="640px" :confirm-loading="savingSchedule" @ok="saveSchedule">
      <a-form :model="scheduleForm" layout="vertical">
        <a-form-item label="计划名称" required>
          <a-input v-model="scheduleForm.name" :max-length="160" placeholder="例如：每日订单接口回归" />
        </a-form-item>
        <a-form-item label="触发场景" required>
          <a-select v-model="scheduleForm.scenarioId">
            <a-option v-for="scenario in scenarios" :key="scenario.id" :value="scenario.id">{{ scenario.name }} · v{{ scenario.version }}</a-option>
          </a-select>
        </a-form-item>
        <div class="schedule-form-grid">
          <a-form-item label="Quartz Cron" required>
            <a-input v-model="scheduleForm.cronExpression" placeholder="0 0 9 * * ?" />
            <template #extra>六或七段格式，例如每天 09:00：<code>0 0 9 * * ?</code>
            </template>
          </a-form-item>
          <a-form-item label="时区" required>
            <a-input v-model="scheduleForm.timezone" placeholder="Asia/Shanghai" />
          </a-form-item>
          <a-form-item label="单次超时">
            <a-input-number v-model="scheduleForm.timeoutSeconds" :min="10" :max="3600" :style="{ width: '100%' }">
              <template #suffix>秒</template>
            </a-input-number>
          </a-form-item>
          <a-form-item label="连续失败停用阈值">
            <a-input-number v-model="scheduleForm.maxConsecutiveFailures" :min="1" :max="10" :style="{ width: '100%' }">
              <template #suffix>次</template>
            </a-input-number>
          </a-form-item>
        </div>
        <a-form-item label="状态">
          <a-switch v-model="scheduleForm.enabled" checked-text="启用" unchecked-text="停用" />
        </a-form-item>
        <a-alert type="warning" :show-icon="true">计划只会调用现有隔离执行链路。Runner 未通过可信校验或未显式启用时，任务会安全结束；达到连续失败阈值后计划自动停用。</a-alert>
      </a-form>
    </a-modal>
    <a-modal v-model:visible="revisionVisible" title="场景修订记录" :footer="false" width="560px">
      <a-table :data="revisions" :pagination="false" row-key="id">
        <template #columns>
          <a-table-column title="版本">
            <template #cell="{ record }">
              <a-tag color="arcoblue">v{{ record.version }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="保存时间">
            <template #cell="{ record }">{{ date(record.createdAt) }}</template>
          </a-table-column>
        </template>
      </a-table>
      <a-empty v-if="!revisions.length" description="尚无修订记录" />
    </a-modal>
    <a-drawer v-model:visible="executionDetailVisible" :width="560" title="执行任务详情" :footer="false">
      <template v-if="executionDetail">
        <section class="execution-detail-summary">
          <div>
            <span>场景</span>
            <strong>{{ executionDetail.scenarioName }}</strong>
            <small>版本 v{{ executionDetail.scenarioVersion }} · {{ executionDetail.id }}</small>
          </div>
          <a-tag :color="executionColor[executionDetail.status] ?? 'gray'">{{ executionLabel[executionDetail.status] ?? executionDetail.status }}</a-tag>
        </section>
        <a-descriptions :column="1" size="small" bordered class="execution-descriptions">
          <a-descriptions-item label="原因">{{ executionDetail.statusCode ? `${executionDetail.statusCode}${executionDetail.statusMessage ? ` · ${executionDetail.statusMessage}` : ''}` : '等待 Runner 处理' }}</a-descriptions-item>
          <a-descriptions-item label="Runner">{{ executionDetail.runnerId ?? '尚未接收' }}</a-descriptions-item>
          <a-descriptions-item v-if="executionDetail.executionProfileId" label="运行配置集">v{{ executionDetail.executionProfileVersion }}（环境与非敏感变量已在提交时固定，取值不展示）</a-descriptions-item>
          <a-descriptions-item v-if="executionDetail.dataSetId" label="数据驱动">数据集第 {{ executionDetail.dataSetRowNumber }} 行（行内变量取值不展示）</a-descriptions-item>
          <a-descriptions-item label="任务 ID">
            <code>{{ executionDetail.taskId }}</code>
          </a-descriptions-item>
          <a-descriptions-item label="截止时间">{{ date(executionDetail.deadlineAt) }}</a-descriptions-item>
        </a-descriptions>
        <a-link v-if="executionReportExportUrl" class="execution-report-export" :href="executionReportExportUrl" download>
          <icon-download /> 下载脱敏报告 JSON</a-link>
        <div v-if="executionReportIntegrity" class="execution-report-integrity">
          <a-tag size="small" color="green">SHA-256</a-tag>
          <code>{{ executionReportIntegrity.sha256 }}</code>
          <small>与下载响应的 ETag 和 <code>X-TestHub-Report-SHA256</code> 一致，可用于 CI 归档校验。</small>
        </div>
        <section v-if="executionReport" class="execution-report">
          <div class="execution-manifest-head">
            <div>
              <span class="eyebrow">REDACTED STRUCTURED REPORT</span>
              <h4>脱敏结构化报告</h4>
              <small>{{ executionReport.contract }} · v{{ executionReport.reportVersion }}；可由 CLI/CI 读取并归档。</small>
            </div>
            <a-tag color="green">安全输出</a-tag>
          </div>
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item v-if="executionReport.failureStrategy" label="失败策略">
              <a-tag size="small" :color="executionReport.failureStrategy.strategy === 'CONTINUE' ? 'green' : 'orange'">{{ executionReport.failureStrategy.strategy === 'CONTINUE' ? '失败后继续' : '失败即停止' }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item v-if="executionReport.failureStrategy" label="安全继续">{{ executionReport.failureStrategy.continuedFailureCount ? `已继续 ${executionReport.failureStrategy.continuedFailureCount} 次，最终执行仍会失败` : '未触发继续执行' }}</a-descriptions-item>
            <a-descriptions-item label="HTTP 尝试">{{ executionReport.summary.httpResultCount }}（成功 {{ executionReport.summary.httpSucceededCount }} / 失败 {{ executionReport.summary.httpFailedCount }}）</a-descriptions-item>
            <a-descriptions-item label="累计耗时">{{ executionReport.summary.httpDurationMillis }} ms</a-descriptions-item>
            <a-descriptions-item label="安全步骤">{{ executionReport.summary.stepResultCount }}</a-descriptions-item>
            <a-descriptions-item label="断言">通过 {{ executionReport.summary.assertionPassedCount }} / 失败 {{ executionReport.summary.assertionFailedCount }}</a-descriptions-item>
            <a-descriptions-item label="pm.test 统计">{{ executionReport.summary.scriptResultCount }} 次执行，共 {{ executionReport.summary.scriptTestCount }} 条</a-descriptions-item>
            <a-descriptions-item label="pm.test 结果">通过 {{ executionReport.summary.scriptPassedCount }} / 失败 {{ executionReport.summary.scriptFailedCount }}</a-descriptions-item>
          </a-descriptions>
          <section v-if="executionReport.parallelGroups.length" class="parallel-group-report">
            <div class="manifest-binding-head">
              <strong>并行请求组汇总</strong>
              <small>按冻结父步骤聚合：仅统计分支、尝试、序号与耗时，不记录请求或响应内容。</small>
            </div>
            <div class="parallel-group-list">
              <article v-for="group in executionReport.parallelGroups" :key="group.stepId" class="parallel-group-row">
                <span class="parallel-group-icon">⇆</span>
                <div>
                  <strong>{{ executionStepName(group.stepId) }}</strong>
                  <small>{{ group.stepId }} · 请求序号 {{ group.firstRequestSequence ?? '—' }}–{{ group.lastRequestSequence ?? '—' }} · 累计 {{ group.totalDurationMillis }} ms</small>
                </div>
                <a-tag size="small" color="arcoblue">配置 {{ group.configuredBranchCount }}</a-tag>
                <a-tag size="small" color="purple">完成 {{ group.completedBranchCount }}</a-tag>
                <a-tag size="small" color="green">成功 {{ group.succeededBranchCount }}</a-tag>
                <a-tag size="small" :color="group.failedBranchCount ? 'red' : 'green'">失败 {{ group.failedBranchCount }}</a-tag>
                <a-tag size="small" :color="group.retryAttemptCount ? 'orange' : 'gray'">重试 {{ group.retryAttemptCount }}</a-tag>
              </article>
            </div>
          </section>
        </section>
        <section v-if="executionManifest" class="execution-manifest">
          <div class="execution-manifest-head">
            <div>
              <span class="eyebrow">FROZEN SAFE MANIFEST</span>
              <h4>已固化执行清单</h4>
              <small>提交前会校验 <code v-pre>{{变量}}</code> 引用；清单只保存步骤结构、来源和使用位置。</small>
            </div>
            <a-tag color="green">SHA-256</a-tag>
          </div>
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="场景版本">v{{ executionManifest.scenarioVersion }}</a-descriptions-item>
            <a-descriptions-item label="环境">{{ executionManifest.snapshot.environmentCode }}</a-descriptions-item>
            <a-descriptions-item label="步骤数">{{ executionManifest.stepCount }}</a-descriptions-item>
            <a-descriptions-item label="变量定义">{{ executionManifest.variableCount }}（密钥 {{ executionManifest.secretReferenceCount }}）</a-descriptions-item>
            <a-descriptions-item label="已解析引用" :span="2">{{ executionManifest.snapshot.variableReferences.length }} 个</a-descriptions-item>
            <a-descriptions-item label="校验和" :span="2">
              <code>{{ executionManifest.checksum }}</code>
            </a-descriptions-item>
          </a-descriptions>
          <div class="manifest-binding-head">
            <strong>已解析引用</strong>
            <small>来源按 环境密钥 → 场景变量 → 项目参数 选择；只记录步骤和配置项名称。</small>
          </div>
          <div class="manifest-reference-list">
            <div v-for="reference in executionManifest.snapshot.variableReferences" :key="reference.name" class="manifest-reference-row">
              <code>{{ reference.name }}</code>
              <a-tag size="small" :color="reference.secret ? 'orange' : 'arcoblue'">{{ reference.source }}</a-tag>
              <span>{{ referenceLocations(reference) }}</span>
            </div>
            <a-empty v-if="!executionManifest.snapshot.variableReferences.length" description="当前场景没有模板变量引用" />
          </div>
          <div class="manifest-binding-head">
            <strong>变量引用</strong>
            <small>来源与变量名可审阅，取值不会展示。</small>
          </div>
          <div class="manifest-binding-list">
            <div v-for="binding in executionManifest.snapshot.variableBindings" :key="`${binding.source}-${binding.name}`" class="manifest-binding-row">
              <code>{{ binding.name }}</code>
              <a-tag size="small" :color="binding.secret ? 'orange' : 'arcoblue'">{{ binding.source }}</a-tag>
              <span>{{ binding.secret ? '仅密钥引用' : '仅变量引用' }}</span>
            </div>
            <a-empty v-if="!executionManifest.snapshot.variableBindings.length" description="当前场景没有变量引用" />
          </div>
        </section>
        <a-alert v-else type="info" class="execution-manifest-legacy" :show-icon="true">该任务在安全执行清单发布前创建，因此没有可审阅的变量引用快照。</a-alert>
        <section v-if="executionRuntimeSnapshot" class="execution-runtime-snapshot">
          <div class="execution-manifest-head">
            <div>
              <span class="eyebrow">FROZEN NON-SENSITIVE RUNTIME INPUTS</span>
              <h4>冻结非敏感运行时变量</h4>
              <small>仅固化当前场景实际引用的非敏感变量；环境密钥只保留名称，不读取、不解密、不传递取值。</small>
            </div>
            <a-tag color="arcoblue">内部快照</a-tag>
          </div>
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="环境">{{ executionRuntimeSnapshot.snapshot.environmentCode }}</a-descriptions-item>
            <a-descriptions-item label="快照版本">v{{ executionRuntimeSnapshot.snapshot.snapshotVersion }}</a-descriptions-item>
            <a-descriptions-item label="非敏感变量">{{ executionRuntimeSnapshot.variableCount }}</a-descriptions-item>
            <a-descriptions-item label="已保留密钥名">{{ executionRuntimeSnapshot.withheldSecretCount }}</a-descriptions-item>
            <a-descriptions-item label="校验和" :span="2">
              <code>{{ executionRuntimeSnapshot.checksum }}</code>
            </a-descriptions-item>
          </a-descriptions>
          <div class="manifest-binding-head">
            <strong>非敏感变量来源</strong>
            <small>非敏感值已在控制面内部固化，本页面不展示；运行时配置交接仍保持关闭。</small>
          </div>
          <div class="manifest-binding-list">
            <div v-for="variable in executionRuntimeSnapshot.snapshot.variables" :key="`${variable.source}-${variable.name}`" class="manifest-binding-row">
              <code>{{ variable.name }}</code>
              <a-tag size="small" color="arcoblue">{{ variable.source }}</a-tag>
              <span>取值不对外展示</span>
            </div>
            <a-empty v-if="!executionRuntimeSnapshot.snapshot.variables.length" description="当前场景没有引用非敏感变量" />
          </div>
          <div class="manifest-binding-head">
            <strong>环境密钥</strong>
            <small>仅保留名称，不含取值。</small>
          </div>
          <div class="runtime-secret-tags">
            <a-tag v-for="name in executionRuntimeSnapshot.snapshot.withheldSecretNames" :key="name" color="orange">{{ name }} · 已保留</a-tag>
            <a-empty v-if="!executionRuntimeSnapshot.snapshot.withheldSecretNames.length" description="当前场景没有引用环境密钥" />
          </div>
        </section>
        <a-alert v-else-if="executionManifest" type="info" class="execution-manifest-legacy" :show-icon="true">该任务在非敏感运行时变量快照发布前创建，因此不提供变量快照。</a-alert>
        <section class="http-result-panel">
          <div class="execution-log-head">
            <div>
              <span class="eyebrow">REDACTED HTTP RESULTS</span>
              <h4>逐请求执行结果</h4>
              <small>重试会保留相同请求序号并标注尝试次数；仅展示冻结步骤名称、方法、状态码和耗时，不记录地址、正文、响应或密钥。</small>
            </div>
          </div>
          <a-spin :loading="executionDetailLoading">
            <div class="http-result-list">
              <article v-for="result in executionHttpResults" :key="result.id" class="http-result-row">
                <span class="http-result-sequence">{{ result.requestSequence }}</span>
                <div>
                  <strong>{{ executionStepName(result.stepId) }}</strong>
                  <small>{{ result.stepId }} · {{ result.runnerId }} · {{ date(result.occurredAt) }}</small>
                </div>
                <a-tag size="small" color="purple">尝试 {{ result.attemptNumber }}</a-tag>
                <a-tag size="small" color="arcoblue">{{ result.method }}</a-tag>
                <a-tag size="small" :color="httpResultColor[result.outcome]">{{ result.statusCode ?? '无响应' }}</a-tag>
                <span class="http-result-duration">{{ result.durationMillis }} ms</span>
              </article>
              <a-empty v-if="!executionHttpResults.length" description="尚未收到受限 HTTP 请求结果" />
            </div>
          </a-spin>
        </section>
        <section class="step-result-panel">
          <div class="execution-log-head">
            <div>
              <span class="eyebrow">SAFE STEP RESULTS</span>
              <h4>提取与断言结果</h4>
              <small>只记录步骤类型、通过状态与已观察状态码；不保存变量取值或响应内容。</small>
            </div>
          </div>
          <a-spin :loading="executionDetailLoading">
            <div class="step-result-list">
              <article v-for="result in executionStepResults" :key="result.id" class="step-result-row">
                <span class="http-result-sequence">{{ result.stepSequence }}</span>
                <div>
                  <strong>{{ executionStepName(result.stepId) }}</strong>
                  <small>{{ result.stepType }} · {{ result.runnerId }} · {{ date(result.occurredAt) }}</small>
                </div>
                <a-tag size="small" :color="result.stepType === 'ASSERT' ? 'green' : 'purple'">{{ result.stepType === 'ASSERT' ? '状态断言' : '状态提取' }}</a-tag>
                <a-tag size="small" :color="result.outcome === 'FAILED' ? 'red' : 'green'">{{ result.outcome }}</a-tag>
                <span class="http-result-duration">HTTP {{ result.observedStatusCode }}</span>
              </article>
              <a-empty v-if="!executionStepResults.length" description="尚未收到提取或断言结果" />
            </div>
          </a-spin>
        </section>
        <section class="script-result-panel">
          <div class="execution-log-head">
            <div>
              <span class="eyebrow">SAFE PM.TEST RESULTS</span>
              <h4>脚本断言统计</h4>
              <small>仅保存阶段和通过/失败计数；不保存断言名称、变量、响应、请求或密钥。</small>
            </div>
          </div>
          <a-spin :loading="executionDetailLoading">
            <div class="script-result-list">
              <article v-for="result in executionScriptResults" :key="result.id" class="script-result-row">
                <span class="http-result-sequence">✓</span>
                <div>
                  <strong>{{ result.phase === 'PRE_REQUEST' ? '前置脚本' : '后置脚本' }}</strong>
                  <small>{{ result.runnerId }} · {{ date(result.occurredAt) }} · 仅聚合计数</small>
                </div>
                <a-tag size="small" color="purple">{{ result.testCount }} 条</a-tag>
                <a-tag size="small" color="green">通过 {{ result.passedCount }}</a-tag>
                <a-tag size="small" :color="result.failedCount ? 'red' : 'green'">失败 {{ result.failedCount }}</a-tag>
              </article>
              <a-empty v-if="!executionScriptResults.length" description="尚未收到 pm.test 断言统计" />
            </div>
          </a-spin>
        </section>
        <div class="execution-log-head">
          <div>
            <span class="eyebrow">SAFE RUNNER LOG</span>
            <h4>实时结构化日志</h4>
            <small>每 1.5 秒刷新；仅接受固定代码与固定消息。</small>
          </div>
          <a-button size="small" :loading="executionDetailLoading" @click="loadExecutionDetail">
            <icon-refresh /> 刷新</a-button>
        </div>
        <a-spin :loading="executionDetailLoading">
          <div class="execution-log-list">
            <article v-for="entry in executionLogs" :key="entry.id" class="execution-log-row">
              <a-tag size="small" :color="logLevelColor[entry.level] ?? 'gray'">{{ entry.level }}</a-tag>
              <div>
                <strong>{{ entry.code }}</strong>
                <p>{{ entry.message }}</p>
                <small>{{ entry.source === 'RUNNER' ? `${entry.runnerId ?? 'Runner'} · ` : '控制面 · ' }}{{ date(entry.occurredAt) }}</small>
              </div>
            </article>
            <a-empty v-if="!executionLogs.length" description="尚未收到 Runner 日志" />
          </div>
        </a-spin>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.automation-page{min-height:calc(100vh - 68px);padding:0;overflow:hidden;background:#f6f7fa}.automation-workbench{display:flex;flex-direction:column;height:calc(100vh - 68px);min-height:680px;color:#273143;background:#fff}.automation-commandbar{display:flex;align-items:center;justify-content:space-between;min-height:55px;padding:0 15px 0 17px;border-bottom:1px solid #e5e8ef;background:linear-gradient(180deg,#fff,#fcfcfe)}.commandbar-context,.commandbar-actions{display:flex;align-items:center;gap:10px}.commandbar-context>div{display:grid;gap:2px}.commandbar-context small{color:#8d96a6;font-size:9px}.commandbar-context strong{font-size:13px}.product-mark{display:grid;place-items:center;width:31px;height:31px;color:#fff;border-radius:9px;background:linear-gradient(145deg,#8b6df1,#5c42d7);box-shadow:0 7px 18px rgba(104,76,219,.2)}.runner-pill{display:flex;align-items:center;gap:6px;padding:5px 9px;color:#7e8796;font-size:9px;border:1px solid #e3e6ec;border-radius:14px;background:#fafbfc}.runner-pill i{width:6px;height:6px;border-radius:50%;background:#aeb5c0}.runner-pill.online i{background:#e49b39}.runner-pill.trusted i{background:#26aa7e;box-shadow:0 0 0 3px #e2f6ef}.automation-workbench__body{display:grid;grid-template-columns:238px minmax(530px,1fr) 292px;min-height:0;flex:1}.automation-studio{display:flex;flex-direction:column;min-width:0;min-height:0;background:#fff}.studio-document-header{display:flex;align-items:center;justify-content:space-between;gap:20px;min-height:66px;padding:9px 18px;border-bottom:1px solid #e9ebf1}.scenario-identity{display:flex;align-items:center;gap:11px;min-width:0}.scenario-identity>div{display:grid;min-width:0;gap:2px}.scenario-identity>div>span{color:#959ead;font-size:9px}.priority-chip{height:28px;padding:0 10px;color:#5f6b7c;font-size:10px;border:1px solid #dfe3eb;border-radius:7px;background:#fff}.studio-title{width:min(420px,40vw)}.studio-title :deep(.arco-input){height:28px;padding:0;color:#283246;font-size:16px;font-weight:700;border:0;background:transparent;box-shadow:none}.studio-tabs{display:flex;align-items:end;gap:24px;height:48px;padding:0 20px;border-bottom:1px solid #e9ebf1}.studio-tabs button{height:48px;padding:0 2px;color:#7d8798;font-size:11px;border:0;border-bottom:2px solid transparent;background:transparent;cursor:pointer}.studio-tabs button.active{color:#6b4de1;font-weight:700;border-bottom-color:#7658e8}.studio-tabs button span{margin-left:3px;padding:1px 5px;color:#929aaa;font-size:8px;border-radius:8px;background:#eef0f4}.studio-scroll{min-height:0;padding:15px 20px 28px;overflow:auto;background:#fff}.scenario-summary-line{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:12px}.scenario-summary-line :deep(.arco-input-wrapper){border:0;background:transparent}.scenario-summary-line :deep(.arco-input){padding-left:0;color:#767f8f;font-size:10px}.scenario-editor-tabs{display:flex;gap:5px;margin:12px 0;padding:3px;border-radius:8px;background:#f4f5f8}.scenario-editor-tabs button{display:flex;align-items:center;justify-content:center;gap:6px;min-width:120px;height:30px;padding:0 13px;color:#7d8797;font-size:10px;border:0;border-radius:6px;background:transparent;cursor:pointer}.scenario-editor-tabs button.active{color:#4e3bb3;font-weight:700;background:#fff;box-shadow:0 2px 8px rgba(32,43,69,.07)}.flow-overview{display:flex;align-items:center;justify-content:space-between;padding:9px 8px;color:#8f98a8;font-size:9px}.flow-overview strong{color:#465166;font-size:11px}.scenario-flow-canvas{display:flex;flex-direction:column;align-items:center;min-height:420px;padding:22px 28px 42px;border:1px dashed #e2e5ed;border-radius:10px;background-color:#fbfcfe;background-image:radial-gradient(#dfe3eb .65px,transparent .65px);background-size:17px 17px}.flow-terminal{display:grid;place-items:center;min-width:58px;height:26px;padding:0 14px;color:#fff;font-size:9px;border-radius:13px;background:#3c485d}.flow-terminal.end{color:#687487;background:#e9ecf2}.scenario-flow-line{display:grid;place-items:center;width:30px;height:22px}.scenario-flow-line span{height:22px;border-left:1px dashed #aeb7c7}.scenario-flow-node{display:grid;grid-template-columns:25px 31px minmax(0,1fr) auto;align-items:center;gap:9px;width:min(470px,92%);min-height:56px;padding:9px 12px;color:#3c4759;text-align:left;border:1px solid #e0e4eb;border-left:3px solid #6079e8;border-radius:9px;background:#fff;box-shadow:0 5px 14px rgba(31,46,76,.055);cursor:pointer;transition:.16s ease}.scenario-flow-node:hover,.scenario-flow-node.selected{border-color:#8d75e9;box-shadow:0 0 0 3px #eeeafd,0 7px 18px rgba(67,48,147,.08);transform:translateY(-1px)}.scenario-flow-node.node-green{border-left-color:#25a980}.scenario-flow-node.node-orange{border-left-color:#e28b3c}.scenario-flow-node.node-cyan{border-left-color:#2aa4bf}.scenario-flow-node.node-violet{border-left-color:#7658e8}.scenario-flow-node.node-gray{border-left-color:#8994a6}.scenario-flow-index{display:grid;place-items:center;width:23px;height:23px;color:#708099;font-size:9px;border-radius:6px;background:#f0f2f6}.scenario-flow-icon{display:grid;place-items:center;width:30px;height:30px;color:#6547db;font-size:15px;border-radius:8px;background:#efebff}.scenario-flow-copy{display:grid;min-width:0;gap:3px}.scenario-flow-copy strong,.scenario-flow-copy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scenario-flow-copy strong{font-size:11px}.scenario-flow-copy small{color:#929bab;font-size:9px}.scenario-flow-node>svg{color:#a0a8b5}.flow-add-trigger{display:flex;align-items:center;gap:6px;height:30px;padding:0 13px;color:#694add;font-size:10px;font-weight:700;border:1px dashed #9b86ec;border-radius:15px;background:#fff;cursor:pointer}.flow-add-trigger:hover{background:#f4f1ff}.step-library{width:480px;padding:12px;background:#fff}.step-library section{display:grid;gap:5px}.step-library section+section{margin-top:12px}.step-library section>small{padding:0 4px 3px;color:#8f98a7;font-size:9px}.step-library section>button,.step-library-grid button{display:grid;grid-template-columns:30px minmax(0,1fr);align-items:center;gap:9px;min-height:42px;padding:6px 8px;color:#4c586c;text-align:left;border:1px solid transparent;border-radius:8px;background:#fff;cursor:pointer}.step-library section>button:hover,.step-library-grid button:hover{border-color:#e4dfff;background:#f8f7ff}.step-library section>button>div{display:grid;gap:2px}.step-library button strong{font-size:10px}.step-library button em{color:#9ba3b0;font-size:8px;font-style:normal}.step-library-icon{display:grid;place-items:center;width:28px;height:28px;color:#647187;font-size:11px;font-weight:700;border-radius:7px;background:#eef1f5}.step-library-icon.purple{color:#6a49df;background:#eeeaff}.step-library-icon.amber{color:#b97612;background:#fff2dc}.step-library-icon.blue{color:#2d72de;background:#e8f2ff}.step-library-icon.cyan{color:#168ca8;background:#e5f8fb}.step-library-icon.pink{color:#d94f9f;background:#ffe9f6}.step-library-icon.orange{color:#d26f22;background:#fff0e4}.step-library-icon.green{color:#188965;background:#e5f7f0}.step-library-grid{display:grid;grid-template-columns:1fr 1fr;gap:5px}.step-library-grid button{grid-template-columns:30px minmax(0,1fr);border-color:#edf0f4}.step-library footer{display:flex;gap:6px;margin:11px -12px -12px;padding:10px 12px;color:#8b94a4;font-size:8px;border-top:1px solid #eceef3;background:#fafbfc}.scenario-script-editor,.scenario-variable-editor{margin-top:12px;padding:16px;border:1px solid #e8eaf0;border-radius:10px;background:#fbfbfd}.script-safety{display:flex;gap:10px;margin-bottom:12px;padding:11px;color:#624aa9;border:1px solid #e7e0ff;border-radius:8px;background:#f7f4ff}.script-safety>div{display:grid;gap:3px}.script-safety strong{font-size:10px}.script-safety small{color:#8c84a7;font-size:9px}.scenario-variable-editor header,.asset-section-heading{display:flex;align-items:center;justify-content:space-between}.scenario-variable-editor header>div,.asset-section-heading>div{display:grid;gap:3px}.scenario-variable-editor header strong{font-size:13px}.scenario-variable-editor header small,.asset-section-heading small{color:#919aa9;font-size:9px}.variable-table-head,.variable-table-row{display:grid;grid-template-columns:.8fr 1.2fr 34px;gap:8px;align-items:center}.variable-table-head{margin-top:15px;padding:8px;color:#8b94a4;font-size:9px;border-bottom:1px solid #e8eaf0}.variable-table-row{padding:7px 0;border-bottom:1px solid #eef0f4}.asset-page{padding:22px 24px 36px;background:#fafbfc}.asset-page-header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:18px}.asset-page-header>div{display:grid;gap:4px}.asset-page-header span{color:#7457df;font-size:9px;font-weight:700;letter-spacing:1px}.asset-page-header h2{margin:0;font-size:18px}.asset-page-header p{margin:0;color:#8b94a4;font-size:10px}.asset-card-list{display:grid;gap:8px}.asset-card{display:grid;grid-template-columns:35px minmax(0,1fr) auto auto auto auto;gap:8px;align-items:center;padding:11px 12px;border:1px solid #e4e7ed;border-radius:9px;background:#fff}.asset-card>div{display:grid;min-width:0;gap:3px}.asset-card strong,.asset-card small,.asset-card code{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.asset-card strong{font-size:11px}.asset-card small,.asset-card code{color:#929bab;font-size:9px}.asset-card-icon{display:grid;place-items:center;width:32px;height:32px;border-radius:8px}.asset-card-icon.cyan{color:#158da9;background:#e5f8fb}.asset-card-icon.purple{color:#6548da;background:#eeeaff}.asset-card-icon.blue{color:#3576d6;background:#e9f2ff}.asset-card-icon.orange{color:#cc742d;background:#fff0e4}.table-secondary{display:block;margin-top:3px;color:#959ead;font-size:9px}.integration-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.integration-card{min-width:0;padding:13px;border:1px solid #e4e7ed;border-radius:9px;background:#fff}.integration-card header{display:flex;align-items:center;gap:9px;margin-bottom:10px}.integration-card header>div{display:grid;gap:3px}.integration-card header strong{font-size:11px}.integration-card header small{color:#929baa;font-size:9px}.integration-card pre{max-height:170px;margin:0;padding:10px;overflow:auto;color:#dce5f7;font-size:8px;line-height:1.55;border-radius:7px;background:#182033;white-space:pre-wrap}.webhook-address{display:block;padding:10px;overflow:hidden;color:#5d687b;font-size:9px;text-overflow:ellipsis;border-radius:7px;background:#f3f5f8}.asset-section-heading{margin:22px 0 9px}.asset-section-heading strong{font-size:12px}.asset-card-list.compact .asset-card{grid-template-columns:35px minmax(0,1fr) auto auto auto auto}.studio-empty{display:grid;place-items:center;min-height:100%}.inspector-drawer-title{display:flex;align-items:center;gap:8px}.parallel-editor-card{display:grid;gap:8px;margin-bottom:10px;padding:10px;border:1px solid #e5e8ef;border-radius:8px;background:#fafbfc}.parallel-editor-card header{display:flex;align-items:center;justify-content:space-between}.inspector-actions{position:sticky;bottom:0;display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:24px;padding-top:12px;background:#fff}.import-form{margin-top:14px}
@media (max-width:1380px){.automation-workbench__body{grid-template-columns:220px minmax(480px,1fr) 275px}.step-library{width:440px}.asset-card{grid-template-columns:35px minmax(0,1fr) auto auto}.asset-card>a-button,.asset-card :deep(.arco-popconfirm){grid-column:2;justify-self:start}}
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
