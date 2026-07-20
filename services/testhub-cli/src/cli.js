import { createHash } from 'node:crypto'
import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const DEFAULT_TIMEOUT_SECONDS = 300
const MIN_TIMEOUT_SECONDS = 10
const MAX_TIMEOUT_SECONDS = 3600
const DEFAULT_WAIT_TIMEOUT_SECONDS = 900
const MAX_WAIT_TIMEOUT_SECONDS = 7200
const DEFAULT_POLL_INTERVAL_SECONDS = 5
const MAX_POLL_INTERVAL_SECONDS = 60
const SHA256_PATTERN = /^[a-f0-9]{64}$/
const TERMINAL_EXECUTION_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'TIMED_OUT'])
const ACTIVE_EXECUTION_STATUSES = new Set(['QUEUED', 'ACCEPTED', 'RUNNING', 'CANCELLING'])
const EXECUTION_FAILED_EXIT_CODE = 2
const WAIT_TIMED_OUT_EXIT_CODE = 3

export class CliError extends Error {
  constructor(message, exitCode = 1) {
    super(message)
    this.name = 'CliError'
    this.exitCode = exitCode
  }
}

export function usage() {
  return `TestHub CLI

Usage:
  testhub execution submit [--base-url URL] [--project ID] [--scenario ID] [--timeout SECONDS]
  testhub execution run [--base-url URL] [--project ID] [--scenario ID] [--timeout SECONDS] [--wait-timeout SECONDS] [--poll-interval SECONDS] [--report-output PATH]
  testhub execution wait [--base-url URL] [--project ID] [--execution ID] [--wait-timeout SECONDS] [--poll-interval SECONDS]
  testhub execution report [--base-url URL] [--project ID] [--execution ID]
  testhub execution export [--base-url URL] [--project ID] [--execution ID] --output PATH [--if-none-match ETAG]

Configuration:
  --token or TESTHUB_TOKEN              Project access token (never printed)
  --base-url or TESTHUB_URL             TestHub HTTP(S) base URL
  --project or TESTHUB_PROJECT_ID       Project identifier
  --scenario or TESTHUB_SCENARIO_ID     Scenario identifier for submit
  --execution or TESTHUB_EXECUTION_ID   Execution identifier for wait/report/export

Examples:
  TESTHUB_TOKEN=... testhub execution submit --project <projectId> --scenario <scenarioId>
  TESTHUB_TOKEN=... testhub execution run --project <projectId> --scenario <scenarioId> --report-output report.json
  TESTHUB_TOKEN=... testhub execution wait --project <projectId> --execution <executionId>
  TESTHUB_TOKEN=... testhub execution export --project <projectId> --execution <executionId> --output report.json`
}

export function parseArgs(argv) {
  if (argv.length === 1 && ['--help', '-h'].includes(argv[0])) return { help: true, command: [] }
  if (argv.length < 2) throw new CliError('缺少命令。使用 --help 查看可用命令。')

  const command = argv.slice(0, 2)
  const options = {}
  for (let index = 2; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help' || argument === '-h') return { help: true, command }
    if (!argument.startsWith('--')) throw new CliError(`无法识别的位置参数：${argument}`)
    const separator = argument.indexOf('=')
    const key = argument.slice(2, separator === -1 ? undefined : separator)
    if (!key) throw new CliError('命令行参数名称不能为空。')
    let value = separator === -1 ? undefined : argument.slice(separator + 1)
    if (value === undefined) {
      value = argv[index + 1]
      if (!value || value.startsWith('--')) throw new CliError(`参数 --${key} 缺少值。`)
      index += 1
    }
    if (Object.hasOwn(options, key)) throw new CliError(`参数 --${key} 不能重复。`)
    options[key] = value
  }
  return { command, options, help: false }
}

export async function run(argv, environment = process.env, dependencies = {}) {
  const parsed = parseArgs(argv)
  if (parsed.help) {
    write(dependencies.stdout, usage())
    return
  }
  const [group, action] = parsed.command
  if (group !== 'execution' || !['submit', 'run', 'wait', 'report', 'export'].includes(action)) {
    throw new CliError('仅支持 execution submit、execution run、execution wait、execution report 和 execution export 命令。')
  }

  const config = resolveConfig(parsed.options, environment, action)
  const fetchImpl = dependencies.fetchImpl ?? globalThis.fetch
  if (typeof fetchImpl !== 'function') throw new CliError('当前 Node.js 环境不支持 fetch；请使用 Node.js 22 或更高版本。')

  if (action === 'submit') {
    const result = await submitExecution(config, parsed.options, fetchImpl)
    writeJson(dependencies.stdout, result)
    return result
  }

  if (action === 'run') return runExecution(config, parsed.options, fetchImpl, dependencies)

  if (action === 'report') {
    const response = await request(fetchImpl, `${executionBaseUrl(config)}/executions/${encode(config.executionId)}/report`, {
      token: config.token,
    })
    const report = await responseData(response)
    writeJson(dependencies.stdout, report)
    return report
  }

  if (action === 'wait') return waitForExecution(config, parsed.options, fetchImpl, dependencies)

  return exportReport(config, parsed.options, fetchImpl, dependencies)
}

export function resolveConfig(options, environment, action) {
  assertKnownOptions(options, action)
  const baseUrl = normalizeBaseUrl(option(options, environment, 'base-url', 'TESTHUB_URL'))
  const projectId = requiredOption(option(options, environment, 'project', 'TESTHUB_PROJECT_ID'), '项目 ID')
  const token = requiredOption(option(options, environment, 'token', 'TESTHUB_TOKEN'), '项目访问令牌')
  const config = { baseUrl, projectId, token }
  if (action === 'submit' || action === 'run') {
    config.scenarioId = requiredOption(option(options, environment, 'scenario', 'TESTHUB_SCENARIO_ID'), '场景 ID')
  }
  if (action === 'wait' || action === 'report' || action === 'export') {
    config.executionId = requiredOption(option(options, environment, 'execution', 'TESTHUB_EXECUTION_ID'), '执行 ID')
  }
  return config
}

async function submitExecution(config, options, fetchImpl) {
  const timeoutSeconds = timeout(options.timeout)
  const response = await request(fetchImpl, `${executionBaseUrl(config)}/scenarios/${encode(config.scenarioId)}/executions`, {
    method: 'POST',
    token: config.token,
    body: JSON.stringify({ timeoutSeconds }),
  })
  const execution = await responseData(response)
  return {
    executionId: requiredString(execution, 'id', '执行提交响应'),
    taskId: requiredString(execution, 'taskId', '执行提交响应'),
    status: requiredString(execution, 'status', '执行提交响应'),
  }
}

async function runExecution(config, options, fetchImpl, dependencies) {
  const submission = await submitExecution(config, options, fetchImpl)
  const outcome = await pollExecution({ ...config, executionId: submission.executionId }, options, fetchImpl, dependencies)
  let reportArtifact = null
  if (options['report-output'] && outcome.summary.waitStatus === 'COMPLETED') {
    reportArtifact = await downloadReport({ ...config, executionId: submission.executionId }, options['report-output'], fetchImpl)
  }
  const result = { submission, execution: outcome.summary, reportArtifact }
  writeJson(dependencies.stdout, result)
  if (outcome.error) throw outcome.error
  return result
}

async function waitForExecution(config, options, fetchImpl, dependencies) {
  const outcome = await pollExecution(config, options, fetchImpl, dependencies)
  writeJson(dependencies.stdout, outcome.summary)
  if (outcome.error) throw outcome.error
  return outcome.summary
}

async function pollExecution(config, options, fetchImpl, dependencies) {
  const wait = waitOptions(options)
  const now = dependencies.now ?? Date.now
  const sleep = dependencies.sleep ?? defaultSleep
  const startedAt = now()
  const deadline = startedAt + wait.timeoutMilliseconds
  let polls = 0

  while (true) {
    const response = await request(fetchImpl, `${executionBaseUrl(config)}/executions/${encode(config.executionId)}`, {
      token: config.token,
    })
    const execution = await responseData(response)
    polls += 1
    const summary = waitSummary(execution, config.executionId, polls, Math.max(0, now() - startedAt))

    if (TERMINAL_EXECUTION_STATUSES.has(summary.status)) {
      summary.waitStatus = 'COMPLETED'
      if (summary.status !== 'SUCCEEDED') {
        return { summary, error: new CliError(`接口自动化执行以 ${summary.status} 结束。`, EXECUTION_FAILED_EXIT_CODE) }
      }
      return { summary, error: null }
    }

    if (!ACTIVE_EXECUTION_STATUSES.has(summary.status)) {
      summary.waitStatus = 'UNSUPPORTED_STATUS'
      return { summary, error: new CliError('TestHub 返回了未受支持的执行状态。', 1) }
    }

    const remainingMilliseconds = deadline - now()
    if (remainingMilliseconds <= 0) {
      summary.waitStatus = 'CLIENT_TIMED_OUT'
      return { summary, error: new CliError('等待接口自动化执行结束时超时。', WAIT_TIMED_OUT_EXIT_CODE) }
    }
    await sleep(Math.min(wait.pollIntervalMilliseconds, remainingMilliseconds))
  }
}

function waitOptions(options) {
  return {
    timeoutMilliseconds: boundedSeconds(options['wait-timeout'], DEFAULT_WAIT_TIMEOUT_SECONDS,
      MIN_TIMEOUT_SECONDS, MAX_WAIT_TIMEOUT_SECONDS, '等待超时时间') * 1000,
    pollIntervalMilliseconds: boundedSeconds(options['poll-interval'], DEFAULT_POLL_INTERVAL_SECONDS,
      1, MAX_POLL_INTERVAL_SECONDS, '轮询间隔') * 1000,
  }
}

function waitSummary(execution, expectedExecutionId, polls, waitedMilliseconds) {
  const executionId = requiredString(execution, 'id', '执行状态响应')
  if (executionId !== expectedExecutionId) throw new CliError('执行状态响应与请求的执行 ID 不匹配。')
  const taskId = requiredString(execution, 'taskId', '执行状态响应')
  const status = requiredString(execution, 'status', '执行状态响应')
  return {
    executionId,
    taskId,
    status,
    statusCode: typeof execution.statusCode === 'string' ? execution.statusCode : null,
    polls,
    waitedMilliseconds,
  }
}

async function exportReport(config, options, fetchImpl, dependencies) {
  const output = requiredOption(options.output, '输出文件路径')
  const result = await downloadReport(config, output, fetchImpl, options['if-none-match'])
  writeJson(dependencies.stdout, result)
  return result
}

async function downloadReport(config, output, fetchImpl, ifNoneMatch) {
  const baseUrl = `${executionBaseUrl(config)}/executions/${encode(config.executionId)}/report`
  const integrity = await responseData(await request(fetchImpl, `${baseUrl}/integrity`, { token: config.token }))
  const metadata = validateIntegrityMetadata(integrity)
  const headers = {}
  if (ifNoneMatch) headers['If-None-Match'] = ifNoneMatch
  const response = await request(fetchImpl, `${baseUrl}/export`, { token: config.token, headers, allowedStatuses: [304] })

  verifyArtifactHeaders(response, metadata)
  if (response.status === 304) {
    return { status: 'not-modified', eTag: metadata.eTag, sha256: metadata.sha256 }
  }

  const content = Buffer.from(await response.arrayBuffer())
  const receivedSha256 = createHash('sha256').update(content).digest('hex')
  if (receivedSha256 !== metadata.sha256) throw new CliError('报告文件校验失败：下载内容的 SHA-256 与完整性元数据不一致。')

  const target = resolve(output)
  await writeArtifact(target, content)
  return { status: 'exported', output: target, sha256: receivedSha256, bytes: content.byteLength }
}

async function writeArtifact(target, content) {
  const temporary = `${target}.tmp-${process.pid}`
  await mkdir(dirname(target), { recursive: true })
  try {
    await writeFile(temporary, content, { mode: 0o600 })
    await rename(temporary, target)
  } catch {
    await rm(temporary, { force: true }).catch(() => undefined)
    throw new CliError('无法安全写入报告文件。')
  }
}

function validateIntegrityMetadata(metadata) {
  if (!metadata || typeof metadata !== 'object') throw new CliError('完整性接口返回了无效数据。')
  const sha256 = typeof metadata.sha256 === 'string' ? metadata.sha256.toLowerCase() : ''
  if (!SHA256_PATTERN.test(sha256)) throw new CliError('完整性接口未返回有效的 SHA-256。')
  const eTag = typeof metadata.eTag === 'string' ? metadata.eTag : ''
  if (!eTag) throw new CliError('完整性接口未返回 ETag。')
  return { sha256, eTag }
}

function verifyArtifactHeaders(response, metadata) {
  const responseSha256 = response.headers.get('x-testhub-report-sha256')
  if (responseSha256?.toLowerCase() !== metadata.sha256) throw new CliError('报告下载响应的 SHA-256 头与完整性元数据不一致。')
  if (response.headers.get('etag') !== metadata.eTag) throw new CliError('报告下载响应的 ETag 与完整性元数据不一致。')
}

function executionBaseUrl(config) {
  return `${config.baseUrl}/api/v1/projects/${encode(config.projectId)}/api-automation`
}

function normalizeBaseUrl(value) {
  const raw = requiredOption(value, '服务地址')
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new CliError('服务地址必须是有效的 HTTP(S) URL。')
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new CliError('服务地址必须是不含账号、查询参数或片段的 HTTP(S) URL。')
  }
  return url.toString().replace(/\/$/, '')
}

function timeout(value) {
  if (value === undefined) return DEFAULT_TIMEOUT_SECONDS
  return boundedSeconds(value, DEFAULT_TIMEOUT_SECONDS, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, '超时时间')
}

function boundedSeconds(value, defaultValue, minimum, maximum, name) {
  if (value === undefined) return defaultValue
  if (!/^\d+$/.test(value)) throw new CliError(`${name}必须是 ${minimum} 到 ${maximum} 之间的整数秒。`)
  const seconds = Number(value)
  if (seconds < minimum || seconds > maximum) throw new CliError(`${name}必须是 ${minimum} 到 ${maximum} 之间的整数秒。`)
  return seconds
}

async function request(fetchImpl, url, { method = 'GET', token, body, headers = {}, allowedStatuses = [] }) {
  let response
  try {
    response = await fetchImpl(url, {
      method,
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${token}`,
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      ...(body ? { body } : {}),
    })
  } catch {
    throw new CliError('无法连接 TestHub 服务。')
  }
  if (response.ok || allowedStatuses.includes(response.status)) return response
  throw new CliError(`TestHub 请求失败（HTTP ${response.status}）。`)
}

async function responseData(response) {
  let payload
  try {
    payload = await response.json()
  } catch {
    throw new CliError('TestHub 返回了无效的 JSON 响应。')
  }
  if (!payload || typeof payload !== 'object' || !Object.hasOwn(payload, 'data')) {
    throw new CliError('TestHub 返回了不符合约定的响应结构。')
  }
  return payload.data
}

function requiredString(value, property, responseName) {
  if (!value || typeof value[property] !== 'string' || !value[property]) {
    throw new CliError(`${responseName}缺少 ${property}。`)
  }
  return value[property]
}

function requiredOption(value, name) {
  if (typeof value !== 'string' || !value.trim()) throw new CliError(`缺少${name}。可使用命令行参数或对应 TESTHUB_* 环境变量。`)
  return value.trim()
}

function option(options, environment, optionName, environmentName) {
  return options[optionName] ?? environment[environmentName]
}

function assertKnownOptions(options, action) {
  const shared = ['base-url', 'project', 'token']
  const allowed = action === 'submit'
    ? [...shared, 'scenario', 'timeout']
    : action === 'run'
      ? [...shared, 'scenario', 'timeout', 'wait-timeout', 'poll-interval', 'report-output']
    : action === 'wait'
      ? [...shared, 'execution', 'wait-timeout', 'poll-interval']
    : action === 'report'
      ? [...shared, 'execution']
      : [...shared, 'execution', 'output', 'if-none-match']
  for (const name of Object.keys(options)) {
    if (!allowed.includes(name)) throw new CliError(`命令 execution ${action} 不支持参数 --${name}。`)
  }
}

function encode(value) {
  return encodeURIComponent(value)
}

function write(stream, message) {
  const target = stream ?? process.stdout
  target.write(`${message}\n`)
}

function writeJson(stream, value) {
  write(stream, JSON.stringify(value, null, 2))
}

function defaultSleep(milliseconds) {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds))
}
