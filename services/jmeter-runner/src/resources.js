import { execFile as nodeExecFile } from 'node:child_process'
import { promisify } from 'node:util'

const MAX_CPU_PERCENT = 10_000
const MAX_RESIDENT_MEMORY_BYTES = 1_099_511_627_776

function processId(value) {
  if (!Number.isSafeInteger(value) || value < 1 || value > 2_147_483_647) {
    throw new Error('JMeter 子进程 PID 不合法')
  }
  return value
}

function parse(output) {
  const fields = String(output).trim().split(/\s+/)
  if (fields.length !== 2) throw new Error('JMeter 子进程资源输出不合法')
  const cpuPercent = Number(fields[0])
  const residentMemoryKib = Number(fields[1])
  if (!Number.isFinite(cpuPercent) || cpuPercent < 0 || cpuPercent > MAX_CPU_PERCENT
    || !Number.isSafeInteger(residentMemoryKib) || residentMemoryKib < 0
    || residentMemoryKib > Math.floor(MAX_RESIDENT_MEMORY_BYTES / 1024)) {
    throw new Error('JMeter 子进程资源指标超出范围')
  }
  return Object.freeze({ cpuPercent, residentMemoryBytes: residentMemoryKib * 1024 })
}

export function createProcessResourceSampler({ execFile = nodeExecFile } = {}) {
  const execute = promisify(execFile)
  return async (pid) => {
    const result = await execute('/bin/ps', ['-o', '%cpu=,rss=', '-p', String(processId(pid))], {
      timeout: 1000, maxBuffer: 1024, windowsHide: true,
    })
    return parse(result.stdout)
  }
}

export const sampleProcessResources = createProcessResourceSampler()
