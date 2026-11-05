/* global process, console */
import fs from 'node:fs/promises'
import { openSync, closeSync, constants } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import { setTimeout as timer, clearTimeout } from 'node:timers'
import { performance } from 'node:perf_hooks'
import { parsePs, ownedTree, intervalCpu, distribution, trend, completeness, sequence, QuietGate } from './d26-metrics.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const reportArgument = process.argv.indexOf('--report')
const report = path.resolve(root, reportArgument < 0 ? 'docs/test-reports/2026-11-04/d26-baseline-r1' : process.argv[reportArgument + 1] || '')
if (!report.startsWith(path.join(root, 'docs/test-reports') + path.sep)) throw new Error('D26 report must remain under repository test reports')
const run = promisify(execFile), require = createRequire(import.meta.url)
const { inventory, digest } = require(path.join(root, 'apps/desktop/build/runtime-integrity.cjs'))
const { verifyRuntime } = require(path.join(root, 'apps/desktop/build/skip-runtime-dependency-copy.cjs'))
const seconds = () => performance.now() / 1000
const utc = () => new Date().toISOString()
const json = async (file, value) => { await fs.writeFile(file + '.tmp', JSON.stringify(value, null, 2) + '\n', { mode: 0o600 }); await fs.rename(file + '.tmp', file) }
const append = (file, value) => fs.appendFile(file, JSON.stringify(value) + '\n', { mode: 0o600 })
const readJson = async file => JSON.parse(await fs.readFile(file, 'utf8'))
const copy = (a, b) => fs.cp(a, b, { recursive: true, mode: constants.COPYFILE_FICLONE, preserveTimestamps: true, verbatimSymlinks: true })
const cleanError = error => ({ code: error.code || error.name, message: String(error.message).replace(/[a-f0-9]{32,}|[A-Za-z0-9+/=_-]{48,}/gi, '[redacted]').slice(0, 400) })
async function ps() { return parsePs((await run('/bin/ps', ['-axo', 'pid=,ppid=,rss=,time=,comm='], { timeout: 5000, maxBuffer: 4 * 1024 * 1024 })).stdout) }
async function waitUntil(deadline) { const ms = (deadline - seconds()) * 1000; if (ms > 0) await delay(ms) }
async function treeHash(directory) { const i = await inventory(directory); return crypto.createHash('sha256').update(JSON.stringify(i)).digest('hex') }

class Native {
  constructor(config, configFile, logFile) {
    this.pending = new Map(); this.sequence = 0; this.config = config; this.meta = null
    const log = openSync(logFile, 'a', 0o600)
    const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE
    this.child = spawn(config.electron, [config.entry, '--config', configFile, '--no-first-run'], { cwd: config.app, env, stdio: ['ignore', log, log, 'ipc'] }); closeSync(log)
    this.done = new Promise(resolve => this.child.once('exit', (code, signal) => { this.exit = { code, signal }; for (const p of this.pending.values()) { clearTimeout(p.timeout); p.reject(new Error('Owned Electron exited')) }; this.pending.clear(); resolve(this.exit) }))
    this.child.on('error', error => { this.spawnError = cleanError(error) })
    this.child.on('message', response => { const p = this.pending.get(response.id); if (!p) return; this.pending.delete(response.id); clearTimeout(p.timeout); if (response.ok) p.resolve(response.value); else p.reject(Object.assign(new Error(response.error.message), { code: response.error.name })) })
  }
  call(command, data = {}, timeoutMs = 20000) {
    if (!this.child.connected) return Promise.reject(new Error('Owned Electron IPC unavailable'))
    const id = ++this.sequence
    return new Promise((resolve, reject) => {
      const timeout = timer(() => { this.pending.delete(id); reject(new Error('Owned IPC timeout: ' + command)) }, timeoutMs)
      this.pending.set(id, { resolve, reject, timeout }); this.child.send({ id, command, ...data }, error => { if (error) { clearTimeout(timeout); this.pending.delete(id); reject(error) } })
    })
  }
  async ready() {
    const end = seconds() + 100
    while (seconds() < end) {
      if (this.exit || this.spawnError) throw new Error('Owned desktop startup failed')
      try { const meta = await this.call('META'); if (meta.ready) { assertSafe(meta); this.meta = meta; await this.call('PARK'); return meta } }
      catch (error) { if (this.exit) throw error }
      await delay(1000)
    }
    throw new Error('Owned desktop readiness exceeded 100 seconds')
  }
  async stop() {
    const began = seconds()
    if (!this.exit) { try { await this.call('STOP', {}, 5000) } catch { /* App exit can close IPC before the acknowledgement. */ } }
    let fallbackUsed = false
    await Promise.race([this.done, delay(30000)])
    if (!this.exit) { fallbackUsed = true; const rows = ownedTree(await ps(), this.child.pid); for (const p of rows.reverse()) { try { process.kill(p.pid, 'SIGTERM') } catch (e) { if (e.code !== 'ESRCH') throw e } }; await Promise.race([this.done, delay(5000)]) }
    const remaining = (await ps()).filter(p => p.executable.startsWith(this.config.owned + path.sep))
    for (const p of remaining) { fallbackUsed = true; try { process.kill(p.pid, 'SIGKILL') } catch (e) { if (e.code !== 'ESRCH') throw e } }
    await delay(300)
    return { ordinaryTeardown: true, upgradeShutdownRecheck: false, elapsedSeconds: seconds() - began, fallbackUsed,
      remainingOwnedProcesses: (await ps()).filter(p => p.executable.startsWith(this.config.owned + path.sep)).length }
  }
}
function assertSafe(meta) {
  if (meta.safety.visibleViolations || meta.safety.unexpectedDialogs || meta.windows.some(w => w.visible || w.focused || !w.offscreen)) throw new Error('Offscreen safety violation')
}
function hostCpu() { return os.cpus().map(p => ({ idle: p.times.idle, total: Object.values(p.times).reduce((a, b) => a + b, 0) })) }
function busyCapacity(a, b) { const total = b.reduce((s, x, i) => s + x.total - a[i].total, 0), idle = b.reduce((s, x, i) => s + x.idle - a[i].idle, 0); return total > 0 ? (total - idle) / total * 100 : null }
async function handles(pids) {
  try {
    const { stdout } = await run('/usr/sbin/lsof', ['-nP', '-a', '-p', pids.join(','), '-F', 'pf'], { timeout: 2000, maxBuffer: 1024 * 1024 })
    const counts = new Map(); let pid
    for (const line of stdout.split('\n')) { if (/^p\d+$/.test(line)) { pid = +line.slice(1); counts.set(pid, 0) }; if (/^f\d+$/.test(line)) counts.set(pid, (counts.get(pid) || 0) + 1) }
    return { counts, reason: null }
  } catch (error) { return { counts: new Map(), reason: error.killed ? 'LSOF_TIMEOUT' : error.code || 'LSOF_UNAVAILABLE' } }
}
async function heap(config, pid) {
  try {
    const { stdout } = await run(path.join(config.runtime, 'jre/bin/jstat'), ['-gc', String(pid)], { timeout: 2000, maxBuffer: 10000 })
    const [names, values] = stdout.trim().split('\n').map(x => x.trim().split(/\s+/)), data = Object.fromEntries(names.map((x, i) => [x, +values[i]]))
    if (!['S0U', 'S1U', 'EU', 'OU', 'YGC', 'YGCT', 'FGC', 'FGCT', 'GCT'].every(k => Number.isFinite(data[k]))) throw new Error('Jstat columns unavailable')
    return { usedKiB: data.S0U + data.S1U + data.EU + data.OU, youngCollections: data.YGC, fullCollections: data.FGC, gcSeconds: data.GCT, reason: null }
  } catch (error) { return { usedKiB: null, reason: error.killed ? 'JSTAT_TIMEOUT' : error.code || 'JSTAT_UNAVAILABLE' } }
}
class Sampler {
  constructor(native, config, start, resourceFile, state) { Object.assign(this, { native, config, start, resourceFile, state }); this.samples = []; this.violation = null; this.previous = new Map(); this.previousTime = null; this.host = hostCpu(); this.collectorCpu = process.cpuUsage(); this.highSince = null }
  async sample(slot = 0) {
    const began = seconds(), wall = Date.now(); let sample
    try {
      const [meta, rows] = await Promise.all([this.native.call('META'), ps()]); assertSafe(meta)
      if (this.native.meta && (meta.javaPid !== this.native.meta.javaPid || meta.runnerPid !== this.native.meta.runnerPid)) this.violation = 'OWNED_PROCESS_RESTART'
      const owned = ownedTree(rows, this.native.child.pid), elapsed = this.previousTime === null ? null : began - this.previousTime
      const rendererIds = new Set(meta.processes.filter(p => p.type === 'Tab').map(p => p.pid))
      const fd = await handles(owned.map(p => p.pid)), jvmHeap = await heap(this.config, meta.javaPid)
      const processes = owned.map(p => ({ pid: p.pid, ppid: p.ppid, role: p.pid === this.native.child.pid ? 'main' : p.pid === meta.javaPid ? 'jvm' : rendererIds.has(p.pid) ? 'renderer' : p.pid === meta.runnerPid ? 'apiRunner' : 'other',
        rssKiB: p.rssKiB, cpuSeconds: p.cpuSeconds, intervalCpuPercent: intervalCpu(p, this.previous.get(p.pid), elapsed), fileDescriptors: fd.counts.get(p.pid) ?? null }))
      const roles = {}
      for (const role of ['main', 'renderer', 'jvm', 'tree']) { const list = role === 'tree' ? processes : processes.filter(p => p.role === role); roles[role] = { processCount: list.length, rssMiB: list.length ? list.reduce((s, p) => s + p.rssKiB, 0) / 1024 : null,
        cpuPercent: list.length && list.every(p => Number.isFinite(p.intervalCpuPercent)) ? list.reduce((s, p) => s + p.intervalCpuPercent, 0) : null,
        fileDescriptors: list.length && list.every(p => p.fileDescriptors !== null) ? list.reduce((s, p) => s + p.fileDescriptors, 0) : null } }
      const nextHost = hostCpu(), busy = busyCapacity(this.host, nextHost), ownCpu = process.cpuUsage(), collectorPct = elapsed ? (ownCpu.user + ownCpu.system - this.collectorCpu.user - this.collectorCpu.system) / 1e6 / elapsed * 100 : null
      const externalCpuCapacityPercent = elapsed && Number.isFinite(roles.tree.cpuPercent) ? Math.max(0, busy - (roles.tree.cpuPercent + collectorPct) / os.cpus().length) : null
      if (externalCpuCapacityPercent >= 25) { this.highSince ??= began; if (began - this.highSince >= 60) this.violation = 'EXTERNAL_CPU_POLLUTION' } else this.highSince = null
      if (elapsed && (elapsed >= 60 || Math.abs((wall - this.wall) / 1000 - elapsed) > 2)) this.violation = 'CLOCK_SLEEP_OR_SAMPLE_GAP'
      sample = { at: new Date(wall).toISOString(), slot, elapsedSeconds: began - this.start, phase: began - this.start <= 900 ? 'warmup' : began - this.start <= 8100 ? 'measurement' : 'cooldown', intervalSeconds: elapsed,
        mandatoryValid: ['main', 'renderer', 'jvm', 'tree'].every(r => roles[r].processCount > 0 && Number.isFinite(roles[r].rssMiB) && Number.isFinite(roles[r].cpuPercent)), roles, processes,
        jvmHeap, fdUnavailableReason: fd.reason, hostBusyCapacityPercent: busy, externalCpuCapacityPercent, collectorCpuPercent: collectorPct, hostLoadAverage: os.loadavg(),
        safety: meta.safety, collectorElapsedMs: (seconds() - began) * 1000, error: null }
      this.previous = new Map(owned.map(p => [p.pid, p])); this.previousTime = began; this.wall = wall; this.host = nextHost; this.collectorCpu = ownCpu
    } catch (error) { sample = { at: utc(), slot, elapsedSeconds: began - this.start, mandatoryValid: false, error: cleanError(error) }; if (/Offscreen/.test(error.message)) this.violation = 'OFFSCREEN_SAFETY' }
    this.samples.push(sample); await append(this.resourceFile, sample)
    return sample
  }
  async loop() {
    for (let slot = 1; slot <= 1800 && !this.cancelled; slot++) {
      await waitUntil(this.start + slot * 5); if (this.cancelled) break
      await this.sample(slot); if (slot % 6 === 0) await this.state({ elapsedSeconds: seconds() - this.start, sampledSlots: slot, violation: this.violation })
      if (this.violation) break
    }
  }
}

async function snapshot(config) {
  const runtime = config.runtime
  await copy(path.join(root, '.testhub/desktop-runtime/darwin-arm64'), runtime)
  await copy(path.join(root, 'apps/desktop/app'), config.app)
  const electronPackage = require.resolve('electron/package.json', { paths: [path.join(root, 'apps/desktop')] })
  await copy(path.join(path.dirname(electronPackage), 'dist/Electron.app'), path.join(config.owned, 'Electron.app'))
  await fs.rm(path.join(runtime, 'web'), { recursive: true }); await copy(path.join(root, 'dist'), path.join(runtime, 'web'))
  await fs.copyFile(path.join(root, 'services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar'), path.join(runtime, 'platform-api.jar'))
  for (const name of ['api-runner', 'jmeter-runner', 'playwright-runner', 'appium-runner']) {
    await fs.rm(path.join(runtime, 'services', name, 'src'), { recursive: true }); await copy(path.join(root, 'services', name, 'src'), path.join(runtime, 'services', name, 'src'))
    await fs.copyFile(path.join(root, 'services', name, 'package.json'), path.join(runtime, 'services', name, 'package.json'))
  }
  await fs.copyFile(path.join(root, 'services/runner-transport/client.mjs'), path.join(runtime, 'services/runner-transport/client.mjs'))
  const manifestFile = path.join(config.owned, 'watchdog.mf'); await fs.writeFile(manifestFile, 'Manifest-Version: 1.0\nPremain-Class: io.testhub.platform.desktop.DesktopEngineWatchdog\n\n')
  await run(path.join(runtime, 'jre/bin/jar'), ['--create', '--file', path.join(runtime, 'jmeter-watchdog.jar'), '--manifest', manifestFile, '-C', path.join(root, 'services/platform-api/target/classes'), 'io/testhub/platform/desktop/DesktopEngineWatchdog.class'])
  await fs.rm(manifestFile)
  const manifest = await readJson(path.join(runtime, 'runtime.json'))
  Object.assign(manifest, { backendSha256: await digest(path.join(runtime, 'platform-api.jar')), desktopSchemaVersion: 57, preparedAt: utc(), distributionReady: false,
    qaSnapshot: 'D26 internal source candidate; reused existing native runtime; no fresh jlink or distribution claim', integrity: await inventory(runtime) })
  await json(path.join(runtime, 'runtime.json'), manifest); await verifyRuntime(path.dirname(runtime), 'darwin', 'arm64', true)
  const java = await run(path.join(runtime, 'jre/bin/java'), ['-version']), node = await run(path.join(runtime, 'node/node'), ['--version'])
  const binaryKinds = (await run('/usr/bin/file', [config.electron, path.join(runtime, 'jre/bin/java'), path.join(runtime, 'node/node')])).stdout.split('\n').filter(Boolean).map(x => x.slice(x.indexOf(':') + 1).trim())
  if (binaryKinds.some(x => !x.includes('arm64'))) throw new Error('Native arm64 runtime required')
  return { frozenAt: utc(), os: os.type(), release: os.release(), architecture: os.arch(), logicalCpuCount: os.cpus().length, physicalMemoryBytes: os.totalmem(), javaVersion: java.stderr.trim(), nodeVersion: node.stdout.trim(), electronVersion: manifest.electronVersion,
    backendSha256: manifest.backendSha256, runtimeSha256: await treeHash(runtime), desktopAppSha256: await treeHash(config.app), electronSha256: await treeHash(path.join(config.owned, 'Electron.app')), binaryKinds,
    collectorSha256: await digest(fileURLToPath(import.meta.url)), nativeEntrySha256: await digest(config.entry), distributionReady: false }
}
async function launchNative(config, profile, tag) {
  const live = { ...config, userData: profile }, configFile = path.join(config.owned, tag + '.json')
  await json(configFile, live); const native = new Native(live, configFile, path.join(config.owned, tag + '.log'))
  try { await native.ready(); return native } catch (error) { error.teardown = await native.stop(); throw error }
}
async function quietWindow(config, update) {
  const began = seconds(), gate = new QuietGate(); let previous = hostCpu()
  while (seconds() - began < 1800) {
    await delay(5000); const next = hostCpu(), busy = busyCapacity(previous, next); previous = next
    const admission = gate.observe(seconds(), busy)
    await append(path.join(report, 'QUIET-ADMISSION.jsonl'), { at: utc(), formalWindowStarted: false, busyCapacityPercent: busy, ...admission })
    await update({ phase: 'WAITING_QUIET_HOST', quietSeconds: admission.quietSeconds, hostBusyCapacityPercent: busy })
    if (admission.ready) return
  }
  throw new Error('No quiet 120-second interval within 30 minutes')
}
async function waitForLowInterference(update) {
  // Scheduling standby has no running application or business requests. Formal retry limits stay unchanged.
  const gate = new QuietGate(); let previous = hostCpu()
  while (true) {
    await delay(5000); const next = hostCpu(), busy = busyCapacity(previous, next); previous = next
    const admission = gate.observe(seconds(), busy)
    await append(path.join(report, 'SCHEDULING-STANDBY.jsonl'), { at: utc(), formalWindowStarted: false, busyCapacityPercent: busy, ...admission })
    await update({ phase: 'WAITING_LOW_INTERFERENCE_SLOT', quietSeconds: admission.quietSeconds, hostBusyCapacityPercent: busy, expectedFinishAt: null })
    if (admission.ready) { await json(path.join(report, 'SCHEDULING-ADMISSION.json'), { at: utc(), continuousQuietSeconds: admission.quietSeconds, thresholdPercent: 25, formalAttemptCount: 0 }); return }
  }
}
async function collectWindow(config, seed, attempt, update) {
  const dir = path.join(report, 'attempt-' + attempt); await fs.mkdir(dir, { recursive: true })
  const profile = path.join(config.owned, 'profile-' + attempt); await copy(config.template, profile)
  const initialDatabaseSha256 = await digest(path.join(profile, 'local-workspace/workspace.mv.db'))
  const native = await launchNative(config, profile, 'window-' + attempt); let sampler, samplerWork, probesWork, finished = false
  const result = { attempt, initialDatabaseSha256, startedAt: utc(), durationSpecification: { warmup: 900, measurement: 7200, cooldown: 900 }, requests: [], probeResults: [], valid: false }
  try {
    const prepared = await native.call('PREPARE', { seed }); result.initialCounts = { tasks: prepared.taskCount, plans: prepared.planCount, cases: prepared.caseCount }
    const start = seconds(), startedWall = Date.now(); result.startedAt = utc(); result.expectedFinishAt = new Date(startedWall + 9000000).toISOString()
    await update({ phase: 'WARMUP', attempt, startedAt: result.startedAt, expectedFinishAt: result.expectedFinishAt })
    sampler = new Sampler(native, config, start, path.join(dir, 'resources.jsonl'), progress => update({ phase: progress.elapsedSeconds <= 900 ? 'WARMUP' : progress.elapsedSeconds <= 8100 ? 'MEASUREMENT' : 'COOLDOWN', attempt, ...progress }))
    await sampler.sample(0); samplerWork = sampler.loop()
    probesWork = (async () => {
      for (let slot = 1; slot <= 300 && !finished; slot++) {
        await waitUntil(start + slot * 30); if (finished) break
        for (const route of ['/actuator/health/liveness', '/actuator/health/readiness']) {
          const row = { at: utc(), elapsedSeconds: seconds() - start, route, ...await native.call('PROBE', { route }) }; result.probeResults.push(row); await append(path.join(dir, 'probes.jsonl'), row)
        }
      }
    })()
    let cycle = 0
    for (const phase of [{ name: 'warmup', end: 900 }, { name: 'measurement', end: 8100 }]) {
      let lastStart = -Infinity
      while (seconds() - start + 100 < phase.end && !sampler.violation) {
        const opIndices = {}; let completed = 0
        for (const operation of sequence) {
          await waitUntil(lastStart + 1)
          if (seconds() - start >= phase.end || sampler.violation) break
          lastStart = seconds(); const index = opIndices[operation] || 0; opIndices[operation] = index + 1
          const row = { at: utc(), elapsedSeconds: lastStart - start, phase: phase.name, cycle, index, operation }
          try { Object.assign(row, await native.call('OP', { operation, index, cycle, tag: 'a' + attempt })) } catch (error) { Object.assign(row, { error: cleanError(error).code, status: null, elapsedMs: (seconds() - lastStart) * 1000, timeoutLimitMs: 10000 }) }
          row.driverElapsedMs = (seconds() - lastStart) * 1000; result.requests.push(row); await append(path.join(dir, 'requests.jsonl'), row); completed++
        }
        if (completed !== 100) { result.partialCycle = { phase: phase.name, cycle, completed }; break }
        cycle++
      }
      await append(path.join(dir, 'events.jsonl'), { at: utc(), event: 'BUSINESS_PHASE_FINISHED', phase: phase.name, elapsedSeconds: seconds() - start, completedCycles: cycle, tailIdleSeconds: Math.max(0, phase.end - (seconds() - start)) })
      if (sampler.violation) break
      await waitUntil(start + phase.end)
    }
    if (!sampler.violation) await waitUntil(start + 9000)
    finished = true; sampler.cancelled = true; await samplerWork; await probesWork
    result.actualDurationSeconds = seconds() - start; result.clockDifferenceSeconds = (Date.now() - startedWall) / 1000 - result.actualDurationSeconds
    result.finalCounts = await native.call('COUNTS'); result.metricsIntegrity = completeness(sampler.samples.filter(s => s.slot > 0), 9000)
    result.violation = sampler.violation
    result.valid = !sampler.violation && !result.partialCycle && result.metricsIntegrity.valid && result.finalCounts.tasks === 200 && result.finalCounts.plans === 20 && result.finalCounts.pendingPlans === 0 && Math.abs(result.clockDifferenceSeconds) < 2 && result.actualDurationSeconds >= 9000
    result.operationStatistics = Object.fromEntries([...new Set(sequence)].map(operation => {
      const rows = result.requests.filter(r => r.phase === 'measurement' && r.operation === operation)
      return [operation, { ...distribution(rows.map(r => r.elapsedMs)), errors: rows.filter(r => r.error).length, errorsByType: rows.filter(r => r.error).reduce((o, r) => { o[r.error] = (o[r.error] || 0) + 1; return o }, {}), throughputPerSecond: rows.length / 7200 }]
    }))
    result.resourceCohorts = {}
    for (const [name, from, to] of [['measurementFirst15', 900, 1800], ['measurementLast15', 7200, 8100], ['cooldown15', 8100, 9000.5]]) {
      const samples = sampler.samples.filter(s => s.elapsedSeconds > from && s.elapsedSeconds <= to)
      result.resourceCohorts[name] = Object.fromEntries(['main', 'renderer', 'jvm', 'tree'].map(role => [role,
        Object.fromEntries(['rssMiB', 'cpuPercent', 'fileDescriptors'].map(metric => [metric, trend(samples.map(s => [s.elapsedSeconds, s.roles?.[role]?.[metric] ?? null]))]))]))
    }
    result.externalCpu = distribution(sampler.samples.map(s => s.externalCpuCapacityPercent)); result.sampleCount = sampler.samples.length - 1
    result.probes = { count: result.probeResults.length, errors: result.probeResults.filter(p => p.error).length, ...distribution(result.probeResults.map(p => p.elapsedMs)) }
    result.errorCount = result.requests.filter(r => r.error).length; result.measurementRequests = result.requests.filter(r => r.phase === 'measurement').length
  } finally {
    finished = true; if (sampler) sampler.cancelled = true
    if (samplerWork) await samplerWork.catch(() => {}); if (probesWork) await probesWork.catch(() => {})
    result.teardown = await native.stop(); if (result.teardown.fallbackUsed || result.teardown.remainingOwnedProcesses) result.valid = false
    result.endedAt = utc(); const { requests, probeResults, ...summary } = result; void requests; void probeResults
    await json(path.join(dir, 'SUMMARY.json'), summary)
    await fs.rm(profile, { recursive: true, force: true })
  }
  return result
}

async function finalReport(config, state) {
  const summaries = []
  for (const name of (await fs.readdir(report)).filter(x => /^attempt-\d+$/.test(x))) {
    try { summaries.push(await readJson(path.join(report, name, 'SUMMARY.json'))) } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  const valid = summaries.filter(s => s.valid), complete = valid.length === 2
  await json(path.join(report, 'BASELINE-RESULTS.json'), { status: complete ? 'BASELINE_COLLECTED_PENDING_REVIEW' : 'INCOMPLETE', observations: [{ id: 'D26-R', status: 'SPEC_RETAINED' }, { id: 'D26-L', status: 'SPEC_RETAINED' }], productComplianceAssessed: false,
    validWindowCount: valid.length, attemptedWindowCount: summaries.length, summaries, error: state.error || null, completedAt: utc(), environmentBlockersDeferred: 12, upgradeShutdownRisk: 'UNCONFIRMED_RETAINED', rootCauseConfirmed: false })
  const lines = ['# D26资源与延迟基线采集报告', '', complete ? '两个独立窗口采集完成，待用户审核。D26-R与D26-L的SPEC观察继续保留；本轮不评定预算、泄漏或SLA达标。' : '采集尚未取得两个有效窗口，当前结果不完整。D26-R与D26-L继续保留。', '',
    '业务序列已按用户确认，将5次任务创建/5次任务删除替换为5组功能测试计划创建/删除；其余90次、200条任务、20个基准计划及窗口时长保持。每窗口15分钟预热、120分钟测量、15分钟冷却；真实离屏桌面＋隔离H2。', '',
    `有效窗口${valid.length}/2；尝试${summaries.length}次。资源CPU为区间累计CPU差值，100%为一核；进程树RSS含共享页，不能当独占内存。延迟为渲染器fetch至正文消费完成。`, '',
    '|窗口|有效|测量业务请求|全部业务错误|必需指标完整率|最长指标间隔(秒)|', '|---|---|---:|---:|---:|---:|']
  for (const s of summaries) lines.push(`|${s.attempt}|${s.valid}|${s.measurementRequests ?? '—'}|${s.errorCount ?? '—'}|${s.metricsIntegrity ? (s.metricsIntegrity.ratio * 100).toFixed(2) + '%' : '—'}|${s.metricsIntegrity?.longestGapSeconds ?? '—'}|`)
  for (const s of valid) {
    lines.push('', `## 有效窗口${s.attempt}`, '', `UTC：${s.startedAt}至${s.endedAt}。实际采集${s.actualDurationSeconds.toFixed(2)}秒；探针${s.probes.count}条，错误${s.probes.errors}条。`, '', '|业务操作|N|P50毫秒|P95毫秒|P99毫秒|最大毫秒|错误|', '|---|---:|---:|---:|---:|---:|---:|')
    for (const [op, d] of Object.entries(s.operationStatistics)) lines.push(`|${op}|${d.n}|${d.p50?.toFixed(2)}|${d.p95?.toFixed(2)}${d.p95SampleSufficient ? '' : '（样本不足）'}|${d.p99?.toFixed(2)}${d.p99SampleSufficient ? '' : '（样本不足）'}|${d.max?.toFixed(2)}|${d.errors}|`)
    lines.push('', '|角色/区间|RSS中位MiB|RSS峰值MiB|RSS斜率MiB/分钟|CPU中位%|CPU峰值%|', '|---|---:|---:|---:|---:|---:|')
    for (const [cohort, roles] of Object.entries(s.resourceCohorts)) for (const [role, m] of Object.entries(roles)) lines.push(`|${role}/${cohort}|${m.rssMiB.median?.toFixed(2)}|${m.rssMiB.peak?.toFixed(2)}|${m.rssMiB.slopePerMinute?.toFixed(4)}|${m.cpuPercent.median?.toFixed(2)}|${m.cpuPercent.peak?.toFixed(2)}|`)
  }
  if (state.error) lines.push('', '未完成原因：' + state.error.message)
  lines.push('', '所有错误和超时保留于逐请求JSONL；未自动把错误归类为产品Bug，须依据对应响应和可重现行为核实。各窗口的同初始数据库摘要、完整率、吞吐和外部CPU见BASELINE-RESULTS.json。可选FD/堆不可用以null及原因保存。', '',
    '历史47项观察中45项结果已审核；本轮2项D26只采基线并保留SPEC。12项环境补齐继续暂缓。升级关闭超时OBS-XREG-SHUTDOWN单独保留UNCONFIRMED_RETAINED，根因未确认，本轮未调查/复验。普通采集清理不构成升级关闭复验。', '',
    '仅修改采集资产与报告，没有修改产品功能、提交或推送代码。当前模块完成后等待审核，未进入下一模块。')
  await fs.writeFile(path.join(report, 'FINAL-REPORT.md'), lines.join('\n') + '\n')
}
async function cleanup(config) {
  const marker = await readJson(path.join(config.owned, 'OWNER.json'))
  if (marker.qaName !== config.qaName || !config.owned.startsWith(path.join(root, '.testhub/d26-owned-'))) throw new Error('Cleanup ownership mismatch')
  const remaining = (await ps()).filter(p => p.executable.startsWith(config.owned + path.sep))
  if (remaining.length) throw new Error('Owned processes still active; preserving private workspace')
  let keychain = 'REMOVED'
  try { await run('/usr/bin/security', ['delete-generic-password', '-s', config.qaName + ' Safe Storage', '-a', config.qaName + ' Key']) } catch (error) { if (error.code === 44) keychain = 'NOT_PRESENT'; else throw new Error('Owned QA keychain cleanup failed') }
  await fs.rm(config.owned, { recursive: true })
  await json(path.join(report, 'CLEANUP.json'), { at: utc(), ownedProcessesRemaining: 0, ownedPrivateWorkspaceRemoved: true, rawLogsDatabasesCandidateRemoved: true, ownedKeychain: keychain, userWorkspacesOrSharedServicesTouched: false, upgradeShutdownRecheck: false })
}
async function worker(configFile) {
  const config = await readJson(configFile), state = { status: 'RUNNING', supervisorPid: process.pid, startedAt: utc(), phase: 'SNAPSHOT', completedWindows: 0, requiredWindows: 2 }
  const update = async value => { Object.assign(state, value, { updatedAt: utc() }); await json(path.join(report, 'STATUS.json'), state) }
  const awake = spawn('/usr/bin/caffeinate', ['-i', '-w', String(process.pid)], { stdio: 'ignore' })
  try {
    await update({}); const candidate = await snapshot(config); await json(path.join(report, 'CANDIDATE.json'), candidate)
    if (config.waitForLowInterference) await waitForLowInterference(update)
    await update({ phase: 'SEED_AND_PRECHECK' })
    const seedProfile = path.join(config.owned, 'seed'), native = await launchNative(config, seedProfile, 'seed'); let seed, seedTeardown
    try {
      seed = await native.call('SEED', {}, 180000)
      const checks = []
      for (const operation of [...new Set(sequence)]) { const r = await native.call('OP', { operation, index: 0, cycle: 0, tag: 'pilot' }); checks.push({ operation, ...r }); if (r.error) throw new Error('Pilot operation failed: ' + operation + '/' + r.error) }
      const counts = await native.call('COUNTS'); if (counts.tasks !== 200 || counts.plans !== 20 || counts.pendingPlans !== 0) throw new Error('Pilot dataset not bounded')
      const sampler = new Sampler(native, config, seconds(), path.join(report, 'PRECHECK-RESOURCES.jsonl'), async () => {})
      await sampler.sample(0); await delay(5000); const sample = await sampler.sample(1)
      if (!sample.mandatoryValid) throw new Error('Mandatory process metrics preflight failed')
      const meta = await native.call('META'); assertSafe(meta)
      await json(path.join(report, 'PRECHECK.json'), { at: utc(), checks, counts, resourceMetricsValid: sample.mandatoryValid, optionalHeap: sample.jvmHeap.reason, optionalFd: sample.fdUnavailableReason, safety: meta.safety, nativeVersions: meta.versions, formalSamples: false })
    } finally { seedTeardown = await native.stop(); await json(path.join(report, 'PRECHECK-TEARDOWN.json'), seedTeardown) }
    if (seedTeardown.fallbackUsed || seedTeardown.remainingOwnedProcesses) throw new Error('Pilot ordinary teardown required fallback')
    await copy(seedProfile, config.template); await fs.rm(seedProfile, { recursive: true }); await json(path.join(config.owned, 'seed-descriptor.json'), seed)
    await json(path.join(report, 'SEED.json'), { taskCount: 200, basePlanCount: 20, caseCount: 200, casesPerPlan: 10, initialDatabaseSha256: await digest(path.join(config.template, 'local-workspace/workspace.mv.db')), pilotNotIncludedInFormalSamples: true })
    for (let attempt = 1; attempt <= 4 && state.completedWindows < 2; attempt++) {
      await quietWindow(config, update)
      if (await treeHash(config.runtime) !== candidate.runtimeSha256 || await treeHash(config.app) !== candidate.desktopAppSha256) throw new Error('Frozen candidate changed')
      const result = await collectWindow(config, seed, attempt, update)
      if (result.valid) state.completedWindows++
      await update({ phase: result.valid ? 'WINDOW_COMPLETE' : 'WINDOW_INVALID', lastAttempt: attempt, lastViolation: result.violation, completedWindows: state.completedWindows })
    }
    await update({ status: state.completedWindows === 2 ? 'COMPLETE_PENDING_REVIEW' : 'INCOMPLETE', phase: 'FINALIZING', finishedAt: utc() })
  } catch (error) { await update({ status: 'INCOMPLETE', phase: 'FAILED', error: cleanError(error), finishedAt: utc() }) }
  finally {
    try { await cleanup(config); state.cleanup = 'COMPLETE' } catch (error) { state.cleanup = 'BLOCKED'; state.cleanupError = cleanError(error) }
    awake.kill(); await finalReport(config, state); await update({ phase: 'AWAITING_REVIEW' })
    const hashes = {}
    for (const name of (await fs.readdir(report)).filter(x => /\.(json|md)$/.test(x) && x !== 'ARTIFACT-HASHES.json')) hashes[name] = await digest(path.join(report, name))
    await json(path.join(report, 'ARTIFACT-HASHES.json'), { at: utc(), files: hashes })
  }
}

if (process.argv.includes('--worker')) await worker(process.argv[process.argv.indexOf('--worker') + 1])
else if (process.argv.includes('--start')) {
  await fs.mkdir(report, { recursive: true })
  try {
    const previous = await readJson(path.join(report, 'STATUS.json'))
    if (previous.status === 'RUNNING') throw new Error('Existing D26 run must be inspected before starting another')
    if (previous.completedWindows || previous.lastAttempt) throw new Error('Preserve and review existing formal attempts before restarting')
    await append(path.join(report, 'PREPARATION-HISTORY.jsonl'), previous)
  } catch (error) { if (error.code !== 'ENOENT') throw error }
  const id = crypto.randomUUID(), owned = path.join(root, '.testhub/d26-owned-' + id); await fs.mkdir(owned, { recursive: true, mode: 0o700 })
  const config = { owned, qaName: 'TestHub-QA-' + id, runtime: path.join(owned, 'runtime/darwin-arm64'), app: path.join(owned, 'app'), electron: path.join(owned, 'Electron.app/Contents/MacOS/Electron'), entry: path.join(owned, 'd26-native.cjs'), template: path.join(owned, 'template'), waitForLowInterference: process.argv.includes('--wait-for-low-interference') }
  await fs.copyFile(path.join(root, 'scripts/qa/d26-native.cjs'), config.entry)
  await json(path.join(owned, 'OWNER.json'), { qaName: config.qaName, purpose: 'D26 approved isolated background baseline', createdAt: utc() })
  const configFile = path.join(owned, 'config.json'); await json(configFile, config)
  await json(path.join(report, 'RUN-OWNERSHIP.json'), { at: utc(), ownedDirectory: owned, qaName: config.qaName, cleanupStatus: 'PENDING_ACTIVE_COLLECTION' })
  for (const name of ['ENVIRONMENT-BLOCKERS.json', 'UNCONFIRMED-RISK.json']) await fs.copyFile(path.join(root, 'docs/test-reports/2026-11-04/d23-json-policy-fixes-r2', name), path.join(report, name))
  const log = openSync(path.join(owned, 'supervisor.log'), 'a', 0o600)
  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), '--worker', configFile, '--report', report], { detached: true, cwd: root, stdio: ['ignore', log, log] }); closeSync(log); child.unref()
  console.log(JSON.stringify({ supervisorPid: child.pid, statusFile: path.join(report, 'STATUS.json'), ownedDirectory: owned }))
} else console.log('Use --start for the approved full-duration background collection. No shortened formal mode is provided.')
