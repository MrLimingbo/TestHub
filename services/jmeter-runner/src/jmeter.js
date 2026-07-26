import { readFile, mkdir, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const XML_HEADER = '<?xml version="1.0" encoding="UTF-8"?>\n<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">\n<hashTree>'
const XML_FOOTER = '</hashTree>\n</jmeterTestPlan>\n'

function xml(value) {
  return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;')
}

function querySuffix(queryParameters) {
  const entries = Object.entries(queryParameters ?? {}).sort(([left], [right]) => left.localeCompare(right))
  if (entries.length === 0) return ''
  const encodeValue = (value) => String(value).split(/(\$\{[A-Za-z_][A-Za-z0-9_]{0,63}})/g)
    .map((part) => /^\$\{[A-Za-z_][A-Za-z0-9_]{0,63}}$/.test(part) ? part : encodeURIComponent(part)).join('')
  return entries.map(([name, value]) => `${encodeURIComponent(name)}=${encodeValue(value)}`).join('&')
}

function targetParts(targetUrl, queryParameters) {
  const tokens = []
  const target = new URL(targetUrl.replace(/\$\{([A-Za-z_][A-Za-z0-9_]{0,63})}/g, (_, variable) => {
    const token = `__testhub_data_${tokens.length}__`
    tokens.push([token, `\${${variable}}`])
    return token
  }))
  const port = target.port || (target.protocol === 'https:' ? '443' : '80')
  const restore = (value) => tokens.reduce((result, [token, expression]) => result.replaceAll(token, expression), value)
  const query = querySuffix(queryParameters)
  const separator = target.search ? '&' : '?'
  return { protocol: target.protocol.slice(0, -1), host: target.hostname, port,
    path: restore(`${target.pathname || '/'}${target.search}${query ? `${separator}${query}` : ''}`) }
}

function throughputTimer(targetTps) {
  if (targetTps === null || targetTps === undefined) return ''
  return `<ConstantThroughputTimer guiclass="TestBeanGUI" testclass="ConstantThroughputTimer" testname="目标 TPS" enabled="true">
  <doubleProp><name>throughput</name><value>${xml(targetTps * 60)}</value><savedValue>0.0</savedValue></doubleProp>
  <intProp><name>calcMode</name><value>1</value><savedValue>0</savedValue></intProp>
</ConstantThroughputTimer><hashTree/>`
}

function httpArguments(requestBody) {
  if (!requestBody) return '<elementProp name="HTTPsampler.Arguments" elementType="Arguments"><collectionProp name="Arguments.arguments"/></elementProp><boolProp name="HTTPSampler.postBodyRaw">false</boolProp>'
  return `<elementProp name="HTTPsampler.Arguments" elementType="Arguments">
      <collectionProp name="Arguments.arguments">
        <elementProp name="" elementType="HTTPArgument">
          <boolProp name="HTTPArgument.always_encode">false</boolProp>
          <stringProp name="Argument.value">${xml(requestBody)}</stringProp>
          <stringProp name="Argument.metadata">=</stringProp>
        </elementProp>
      </collectionProp>
    </elementProp>
    <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>`
}

function headerManager(headers) {
  const entries = Object.entries(headers ?? {})
  if (entries.length === 0) return ''
  return `<HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="请求头" enabled="true">
    <collectionProp name="HeaderManager.headers">
      ${entries.map(([name, value]) => `<elementProp name="${xml(name)}" elementType="Header"><stringProp name="Header.name">${xml(name)}</stringProp><stringProp name="Header.value">${xml(value)}</stringProp></elementProp>`).join('')}
    </collectionProp>
  </HeaderManager><hashTree/>`
}

function responseAssertion(name, field, testType, expected, message) {
  return `<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="${xml(name)}" enabled="true">
    <collectionProp name="Asserion.test_strings"><stringProp name="${xml(expected)}">${xml(expected)}</stringProp></collectionProp>
    <stringProp name="Assertion.custom_message">${xml(message)}</stringProp>
    <stringProp name="Assertion.test_field">${field}</stringProp>
    <boolProp name="Assertion.assume_success">false</boolProp>
    <intProp name="Assertion.test_type">${testType}</intProp>
  </ResponseAssertion><hashTree/>`
}

function responseAssertions(group) {
  const expectedStatus = group.expectedStatusCode === null || group.expectedStatusCode === undefined ? ''
    : responseAssertion('预期状态码', 'Assertion.response_code', 8, group.expectedStatusCode, `预期 HTTP 状态码 ${group.expectedStatusCode}`)
  const responseContains = group.responseContains === null || group.responseContains === undefined ? ''
    : responseAssertion('响应包含断言', 'Assertion.response_data', 16, group.responseContains, '响应正文未包含预期文本')
  return `${expectedStatus}${responseContains}`
}

function dataSet(group, index) {
  if (!group.dataSetCsv) return ''
  return `<CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="数据参数集" enabled="true">
    <stringProp name="filename">dataset-${index + 1}.csv</stringProp>
    <stringProp name="fileEncoding">UTF-8</stringProp>
    <stringProp name="variableNames"></stringProp>
    <boolProp name="ignoreFirstLine">false</boolProp>
    <stringProp name="delimiter">,</stringProp>
    <boolProp name="quotedData">true</boolProp>
    <boolProp name="recycle">true</boolProp>
    <boolProp name="stopThread">false</boolProp>
    <stringProp name="shareMode">shareMode.all</stringProp>
  </CSVDataSet><hashTree/>`
}

function rendezvousTimer(group) {
  if (group.rendezvousUsers === null || group.rendezvousUsers === undefined) return ''
  return `<SyncTimer guiclass="TestBeanGUI" testclass="SyncTimer" testname="集合点" enabled="true">
    <intProp name="groupSize">${xml(group.rendezvousUsers)}</intProp>
    <longProp name="timeoutInMs">30000</longProp>
  </SyncTimer><hashTree/>`
}

function steppedThreadCounts(threadCount, stepCount) {
  let allocated = 0
  return Array.from({ length: stepCount }, (_, index) => {
    const target = Math.ceil(threadCount * (index + 1) / stepCount)
    const count = target - allocated
    allocated = target
    return count
  })
}

function steppedTargetTps(totalTps, threadCount, startedThreads, stageThreads) {
  if (totalTps === null || totalTps === undefined) return null
  const before = Math.floor(totalTps * startedThreads / threadCount)
  const after = Math.floor(totalTps * (startedThreads + stageThreads) / threadCount)
  return Math.max(1, after - before)
}

function threadGroupXml(group, index, { testName, samplerName, threadCount, rampUpSeconds, delaySeconds, durationSeconds, targetTps }) {
  const target = targetParts(group.targetUrl, group.queryParameters)
  const label = xml(testName)
  const samplerLabel = xml(samplerName)
  return `<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="${label}" enabled="true">
  <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
  <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="循环控制器" enabled="true">
    <boolProp name="LoopController.continue_forever">true</boolProp>
    <stringProp name="LoopController.loops">-1</stringProp>
  </elementProp>
  <stringProp name="ThreadGroup.num_threads">${xml(threadCount)}</stringProp>
  <stringProp name="ThreadGroup.ramp_time">${xml(rampUpSeconds)}</stringProp>
  <boolProp name="ThreadGroup.scheduler">true</boolProp>
  <stringProp name="ThreadGroup.duration">${xml(durationSeconds)}</stringProp>
  <stringProp name="ThreadGroup.delay">${xml(delaySeconds || '')}</stringProp>
  <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
</ThreadGroup>
<hashTree>
  ${dataSet(group, index)}
  ${rendezvousTimer(group)}
  <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="${samplerLabel}" enabled="true">
    ${httpArguments(group.requestBody)}
    <stringProp name="HTTPSampler.domain">${xml(target.host)}</stringProp>
    <stringProp name="HTTPSampler.port">${xml(target.port)}</stringProp>
    <stringProp name="HTTPSampler.protocol">${xml(target.protocol)}</stringProp>
    <stringProp name="HTTPSampler.contentEncoding"></stringProp>
    <stringProp name="HTTPSampler.path">${xml(target.path)}</stringProp>
    <stringProp name="HTTPSampler.method">${xml(group.requestMethod ?? 'GET')}</stringProp>
    <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
    <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
    <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
    <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
    <boolProp name="HTTPSampler.monitor">false</boolProp>
    <stringProp name="HTTPSampler.embedded_url_re"></stringProp>
    <stringProp name="HTTPSampler.connect_timeout"></stringProp>
    <stringProp name="HTTPSampler.response_timeout"></stringProp>
    <stringProp name="HTTPSampler.implementation">HttpClient4</stringProp>
  </HTTPSamplerProxy>
  <hashTree>${headerManager(group.requestHeaders)}${responseAssertions(group)}</hashTree>
  ${throughputTimer(targetTps)}
</hashTree>`
}

function threadGroup(group, index) {
  if (group.loadModel !== 'STEPPED_RAMP') {
    return threadGroupXml(group, index, { testName: group.name, samplerName: group.name, threadCount: group.threadCount,
      rampUpSeconds: group.loadModel === 'STEADY' ? 0 : group.rampUpSeconds, delaySeconds: 0,
      durationSeconds: group.durationSeconds, targetTps: group.targetTps })
  }
  const counts = steppedThreadCounts(group.threadCount, group.stepCount)
  const finalDelay = (group.stepCount - 1) * group.stepIntervalSeconds
  let startedThreads = 0
  return counts.map((stageThreads, stageIndex) => {
    const delaySeconds = stageIndex * group.stepIntervalSeconds
    const result = threadGroupXml(group, index, { testName: `${group.name} · 阶段 ${stageIndex + 1}/${group.stepCount}`,
      samplerName: group.name, threadCount: stageThreads, rampUpSeconds: 0, delaySeconds,
      durationSeconds: group.durationSeconds + finalDelay - delaySeconds,
      targetTps: steppedTargetTps(group.targetTps, group.threadCount, startedThreads, stageThreads) })
    startedThreads += stageThreads
    return result
  }).join('\n')
}

export function createJmx(artifact) {
  const groups = artifact.threadGroups.map(threadGroup).join('\n')
  return `${XML_HEADER}
<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="TestHub 性能任务" enabled="true">
  <stringProp name="TestPlan.comments">Generated by TestHub isolated JMeter Runner.</stringProp>
  <boolProp name="TestPlan.functional_mode">false</boolProp>
  <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
</TestPlan>
<hashTree>
${groups}
</hashTree>
${XML_FOOTER}`
}

export async function prepareWorkDirectory(config, task, artifact) {
  const root = resolve(config.workDirectory, task.executionId)
  const base = resolve(config.workDirectory)
  if (!root.startsWith(`${base}/`)) throw new Error('JMeter 工作目录超出受限路径')
  await rm(root, { recursive: true, force: true })
  await mkdir(root, { recursive: true, mode: 0o700 })
  const planPath = join(root, 'plan.jmx')
  const resultsPath = join(root, 'results.jtl')
  const logPath = join(root, 'jmeter.log')
  await Promise.all(artifact.threadGroups.map(async (group, index) => {
    if (group.dataSetCsv) await writeFile(join(root, `dataset-${index + 1}.csv`), group.dataSetCsv, { encoding: 'utf8', mode: 0o600 })
  }))
  await writeFile(planPath, createJmx(artifact), { encoding: 'utf8', mode: 0o600 })
  return Object.freeze({ root, planPath, resultsPath, logPath })
}

export function jmeterArguments(workload) {
  return ['-n', '-t', workload.planPath, '-l', workload.resultsPath, '-j', workload.logPath,
    '-Jjmeter.save.saveservice.output_format=csv', '-Jjmeter.save.saveservice.print_field_names=true',
    '-Jjmeter.save.saveservice.timestamp_format=ms', '-Jjmeter.save.saveservice.time=true',
    '-Jjmeter.save.saveservice.label=true', '-Jjmeter.save.saveservice.code=true',
    '-Jjmeter.save.saveservice.successful=true', '-Jjmeter.save.saveservice.thread_counts=true',
    '-Jjmeter.save.saveservice.latency=false', '-Jjmeter.save.saveservice.bytes=false',
    '-Jjmeter.save.saveservice.url=false', '-Jjmeter.save.saveservice.response_message=false']
}

function csvRow(source) {
  const result = []; let value = ''; let quoted = false
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index]
    if (character === '"') {
      if (quoted && source[index + 1] === '"') { value += '"'; index += 1 } else quoted = !quoted
    } else if (character === ',' && !quoted) { result.push(value); value = '' } else value += character
  }
  result.push(value)
  return result
}

function percentile(values, proportion) {
  if (values.length === 0) return 0
  const ordered = [...values].sort((left, right) => left - right)
  return ordered[Math.min(ordered.length - 1, Math.max(0, Math.ceil(ordered.length * proportion) - 1))]
}

function emptySeries(name) {
  return { name, sampleCount: 0, successCount: 0, errorCount: 0, activeThreads: 0, values: [], sum: 0, min: 0, max: 0 }
}

export class JmeterMetrics {
  constructor({ resultsPath, startedAt = Date.now() }) {
    this.resultsPath = resultsPath; this.startedAt = startedAt; this.offset = 0; this.remainder = ''; this.header = undefined
    this.series = new Map([['__TOTAL__', emptySeries('__TOTAL__')]])
    this.errors = new Map()
  }

  async refresh() {
    let content
    try { content = await readFile(this.resultsPath, 'utf8') } catch (error) { if (error?.code === 'ENOENT') return [] ; throw error }
    const fresh = content.slice(this.offset); this.offset = content.length
    const lines = `${this.remainder}${fresh}`.split(/\r?\n/); this.remainder = lines.pop() ?? ''
    for (const line of lines) this.accept(line)
    return this.snapshots()
  }

  accept(line) {
    if (!line) return
    const fields = csvRow(line)
    if (!this.header) { this.header = new Map(fields.map((field, index) => [field, index])); return }
    const field = (name) => fields[this.header.get(name)] ?? ''
    const elapsed = Number.parseInt(field('elapsed'), 10)
    const success = field('success') === 'true'
    const label = field('label') || '未命名线程组'
    const responseCode = field('responseCode')
    const threads = Number.parseInt(field('allThreads'), 10)
    if (!Number.isSafeInteger(elapsed) || elapsed < 0) return
    this.add(this.series.get('__TOTAL__'), elapsed, success, threads)
    if (!this.series.has(label)) this.series.set(label, emptySeries(label))
    this.add(this.series.get(label), elapsed, success, threads)
    if (!success) this.addError(label, responseCode)
  }

  add(series, elapsed, success, threads) {
    series.sampleCount += 1; if (success) series.successCount += 1; else series.errorCount += 1
    series.activeThreads = Number.isSafeInteger(threads) && threads >= 0 ? threads : series.activeThreads
    series.sum += elapsed; series.min = series.sampleCount === 1 ? elapsed : Math.min(series.min, elapsed); series.max = Math.max(series.max, elapsed)
    if (series.values.length < 20000) series.values.push(elapsed)
  }

  addError(threadGroupName, responseCode) {
    const code = /^[0-9]{3}$/.test(responseCode) ? responseCode : 'NON_HTTP_ERROR'
    const key = `${threadGroupName}\u0000${code}`
    const current = this.errors.get(key)
    this.errors.set(key, { threadGroupName, responseCode: code, errorCount: (current?.errorCount ?? 0) + 1 })
  }

  errorSummaries(limit = 50) {
    return [...this.errors.values()].sort((left, right) => right.errorCount - left.errorCount
      || left.threadGroupName.localeCompare(right.threadGroupName) || left.responseCode.localeCompare(right.responseCode)).slice(0, limit)
  }

  snapshots(now = Date.now()) {
    const elapsedSeconds = Math.max(0.001, (now - this.startedAt) / 1000)
    return [...this.series.values()].map((series) => ({ threadGroupName: series.name, sampleCount: series.sampleCount,
      successCount: series.successCount, errorCount: series.errorCount, activeThreads: series.activeThreads,
      throughputPerSecond: series.sampleCount / elapsedSeconds, averageLatencyMillis: series.sampleCount === 0 ? 0 : Math.round(series.sum / series.sampleCount),
      p95LatencyMillis: percentile(series.values, 0.95), minLatencyMillis: series.min, maxLatencyMillis: series.max }))
  }
}
