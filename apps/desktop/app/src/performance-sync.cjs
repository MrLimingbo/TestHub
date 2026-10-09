'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const revision = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const keyOf = item => item.name.toLowerCase()
const sortedMap = map => Object.fromEntries(Object.entries(map).sort(([a], [b]) => a.localeCompare(b, 'en')))
const content = item => ({ name: item.name, description: item.description, threadGroups: item.threadGroups.map(group => ({
  name: group.name, targetUrl: group.targetUrl, loadModel: group.loadModel, threadCount: group.threadCount,
  rampUpSeconds: group.rampUpSeconds, stepCount: group.stepCount, stepIntervalSeconds: group.stepIntervalSeconds,
  durationSeconds: group.durationSeconds, targetTps: group.targetTps, p95ThresholdMillis: group.p95ThresholdMillis,
  errorThresholdPercent: group.errorThresholdPercent, requestMethod: group.requestMethod,
  requestHeaders: sortedMap(group.requestHeaders), queryParameters: sortedMap(group.queryParameters),
  requestBody: group.requestBody, expectedStatusCode: group.expectedStatusCode, responseContains: group.responseContains,
  dataSetCsv: group.dataSetCsv, rendezvousUsers: group.rendezvousUsers,
})) })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null })
const validKey = key => typeof key === 'string' && key.length > 0 && key.length <= 480 && key === key.trim() && key === key.toLowerCase()
function csvVariables(csv, fail) {
  if (csv === null) return new Set()
  const rows = csv.split('\n'); if (rows.at(-1) === '') rows.pop()
  if (rows.length < 2 || rows.length > 501) fail()
  const parse = row => {
    const cells = []; let value = '', quoted = false, closed = false
    for (let n = 0; n < row.length; n++) {
      const c = row[n]
      if (c === '"') {
        if (quoted && row[n + 1] === '"') { value += '"'; n++ }
        else if (quoted) { quoted = false; closed = true }
        else if (!value) quoted = true
        else fail()
      } else if (c === ',' && !quoted) { cells.push(value); value = ''; closed = false }
      else { if (closed) fail(); value += c }
    }
    if (quoted) fail(); cells.push(value); return cells
  }
  const headers = parse(rows[0]), names = new Set(headers)
  if (headers.length > 20 || names.size !== headers.length || headers.some(h => !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(h))) fail()
  for (const row of rows.slice(1)) { const values = parse(row); if (values.length !== headers.length || values.some(v => v.length > 4096)) fail() }
  return names
}
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i]))
  if (merged.size > 100) throw new Error('合并后（含待核对的缺失计划）超过 100 个性能计划限制，请先核对资料或解除绑定重新预览。')
  const largest = new Map()
  for (const item of [...local.items, ...remote.items]) {
    const encoded = JSON.stringify(content(item)), key = keyOf(item)
    if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded)
  }
  const bytes = [...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024)
  if (bytes > 3 * 1024 * 1024) throw new Error('合并后性能计划超过 3 MiB 同步范围，请先缩减资料。')
}
function validateItem(item) {
  const fail = () => { throw new Error('性能计划同步资料无效，请核对计划和线程组定义。') }
  const text = (v, max) => typeof v === 'string' && v.trim().length > 0 && v === v.trim() && v.length <= max
  const range = (v, min, max) => Number.isInteger(v) && v >= min && v <= max
  const optional = (v, min, max) => v === null || range(v, min, max)
  if (!item || Object.keys(item).some(k => !['name', 'description', 'threadGroups'].includes(k)) || !text(item.name, 160)
    || typeof item.description !== 'string' || item.description !== item.description.trim() || item.description.length > 2000
    || !Array.isArray(item.threadGroups) || !item.threadGroups.length || item.threadGroups.length > 10) fail()
  const names = new Set(), fields = ['name', 'targetUrl', 'loadModel', 'threadCount', 'rampUpSeconds', 'stepCount', 'stepIntervalSeconds', 'durationSeconds', 'targetTps', 'p95ThresholdMillis', 'errorThresholdPercent', 'requestMethod', 'requestHeaders', 'queryParameters', 'requestBody', 'expectedStatusCode', 'responseContains', 'dataSetCsv', 'rendezvousUsers']
  for (const g of item.threadGroups) {
    if (!g || Object.keys(g).some(k => !fields.includes(k)) || !text(g.name, 160) || names.has(g.name.toLowerCase())
      || !text(g.targetUrl, 2048) || !['STEADY', 'RAMP_UP', 'STEPPED_RAMP'].includes(g.loadModel)
      || !range(g.threadCount, 1, 100000) || !range(g.rampUpSeconds, 0, 86400) || !range(g.durationSeconds, 10, 86400)
      || !optional(g.targetTps, 1, 1000000) || !optional(g.p95ThresholdMillis, 1, 600000)
      || (g.errorThresholdPercent !== null && (typeof g.errorThresholdPercent !== 'number' || !Number.isFinite(g.errorThresholdPercent) || g.errorThresholdPercent < 0 || g.errorThresholdPercent > 100 || Math.abs(g.errorThresholdPercent * 100 - Math.round(g.errorThresholdPercent * 100)) > 1e-7))
      || !['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'].includes(g.requestMethod) || !optional(g.expectedStatusCode, 100, 599)
      || !optional(g.rendezvousUsers, 2, g.threadCount)
      || (g.responseContains !== null && !text(g.responseContains, 1000))
      || (g.requestBody !== null && (typeof g.requestBody !== 'string' || !g.requestBody.trim() || g.requestBody.length > 65536 || ['GET', 'HEAD'].includes(g.requestMethod)))
      || (g.dataSetCsv !== null && (typeof g.dataSetCsv !== 'string' || !g.dataSetCsv.trim() || g.dataSetCsv.length > 65536 || g.dataSetCsv.includes('\r')))) fail()
    names.add(g.name.toLowerCase())
    if (g.loadModel === 'STEPPED_RAMP') {
      if (g.rampUpSeconds !== 0 || g.rendezvousUsers !== null || !range(g.stepCount, 2, Math.min(20, g.threadCount))
        || !range(g.stepIntervalSeconds, 1, 86400) || g.durationSeconds + (g.stepCount - 1) * g.stepIntervalSeconds > 86400
        || (g.targetTps !== null && g.targetTps < g.stepCount)) fail()
    } else if (g.stepCount !== null || g.stepIntervalSeconds !== null) fail()
    try {
      const url = new URL(g.targetUrl.replace(/\$\{[^}]*}/g, '__testhub_data_variable__'))
      if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash || url.host.includes('__testhub_data_variable__')) fail()
    } catch { fail() }
    for (const field of ['requestHeaders', 'queryParameters']) {
      const map = g[field], seen = new Set()
      if (!map || typeof map !== 'object' || Array.isArray(map) || Object.keys(map).length > 16) fail()
      for (const [key, value] of Object.entries(map)) {
        const header = field === 'requestHeaders', folded = header ? key.toLowerCase() : key
        if (!text(key, 128) || !(header ? /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/ : /^[A-Za-z0-9._~-]+$/).test(key)
          || seen.has(folded) || typeof value !== 'string' || value.length > 1024 || /[\r\n]/.test(value)
          || (header && (value !== value.trim() || ['authorization', 'cookie', 'host', 'content-length', 'connection', 'transfer-encoding', 'proxy-authorization', 'proxy-connection', 'upgrade'].includes(folded)))) fail()
        seen.add(folded)
      }
    }
    const variables = csvVariables(g.dataSetCsv, fail)
    for (const value of [g.targetUrl, ...Object.values(g.requestHeaders), ...Object.values(g.queryParameters), g.requestBody, g.responseContains]) {
      if (!value?.includes('${')) continue
      const matches = [...value.matchAll(/\$\{([^}]*)}/g)]
      if (!matches.length || matches.some(m => !variables.has(m[1])) || value.replace(/\$\{[^}]*}/g, '').includes('${')) fail()
    }
  }
  return item
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 100 || !Array.isArray(state.conflicts) || state.conflicts.length > 200)
    throw new Error('性能计划同步记录损坏，请保留现有数据。')
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('性能计划同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的计划不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('性能计划同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean') throw new Error('性能计划同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('性能计划同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name)) throw new Error('性能计划同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 100) throw new Error('性能计划同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('性能计划同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class PerformanceSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'performance-sync.bin', validate: validateState, empty,
    label: '性能计划', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class PerformanceSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，性能计划同步已停止；请切回绑定账号。')
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/performance/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'PERFORMANCE_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 100
      || !Number.isSafeInteger(snapshot.excludedExecutions) || snapshot.excludedExecutions < 0)
      throw new Error('服务不支持当前性能计划同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('性能计划存在同名或大小写重复，请先统一名称。'); keys.add(key) }
    return snapshot
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有性能计划同步绑定，请先暂停并解除后再绑定其他项目。')
    if (!id(sourceId) || !id(targetId)) throw new Error('请选择有效的本地项目和账号项目。')
    server = normalizeServerUrl(server)
    const [actor, localActor, local, remote] = await Promise.all([this.remote('/api/v1/auth/me', undefined, { server }), this.local('/api/v1/auth/me'),
      this.local('/api/v1/access/context'), this.remote('/api/v1/access/context', undefined, { server })])
    const projects = context => context.organizations.flatMap(org => (context.projects[org.id] || []).map(p => ({ id: p.id, name: p.name, organization: org.name })))
    const source = projects(local).find(p => p.id === sourceId), target = projects(remote).find(p => p.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取项目。')
    const binding = { server, actor: { id: actor.id, name: actor.displayName, account: actor.account || actor.email }, localActorId: localActor.id, source, target, enabled: true }
    await this.actors(binding)
    const [localRows, remoteRows] = await Promise.all([this.rows('local', binding), this.rows('remote', binding)])
    requireMergeCapacity(localRows, remoteRows)
    const counts = this.changes(localRows, remoteRows)
    this.plan = { token: crypto.randomUUID(), binding, expires: Date.now() + 600000, localRows, remoteRows, counts }
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount, excludedExecutions: counts.excludedExecutions }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('性能计划在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定性能计划同步。')
    if (value && this.state.binding.requiresRebind) throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。')
    if (value) await this.actors(this.state.binding)
    const next = clone(this.state); next.binding.enabled = value; await this.save(next)
  }
  async unbind() { await this.load(); await this.save(empty()); this.plan = undefined }
  async suspendForRestore() {
    await this.load()
    if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) }
  }
  changes(local, remote) {
    const l = new Map(local.items.map(i => [keyOf(i), i])), r = new Map(remote.items.map(i => [keyOf(i), i]))
    const bases = new Map(this.state.bases.map(i => [keyOf(i), i]))
    const push = [], pull = [], conflicts = [], unchanged = []
    for (const key of new Set([...l.keys(), ...r.keys(), ...bases.keys()])) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      if (same(a, b)) { unchanged.push(content(a)); continue }
      const issue = reason => conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason, canResolve: false })
      if (base && (!a || !b)) { issue('一端计划已删除或改名，请核对；不会自动删除或重新创建。'); continue }
      if (a && b && a.name !== b.name) { issue('计划名称大小写不同，请先在两端统一名称。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) {
        conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一计划在两端均已修改。' : '两端已有同名计划内容不同。', canResolve: true }); continue
      }
      const side = !b || (a && base && !same(a, base)) ? 'remote' : 'local', item = side === 'remote' ? a : b
      ;(side === 'remote' ? push : pull).push(content(item))
    }
    return { push, pull, conflicts, unchanged, upload: push.length, download: pull.length, conflictCount: conflicts.length,
      excludedExecutions: local.excludedExecutions + remote.excludedExecutions }
  }
  async send(side, items, expectedRevision, b) {
    if (!items.length) return
    const next = clone(this.state); next.pending = { side, request: { requestKey: crypto.randomUUID(), expectedUserId: side === 'local' ? b.localActorId : b.actor.id,
      expectedRevision, operations: items.map(item => ({ operationId: crypto.randomUUID(), item: content(item) })) } }
    await this.save(next); await this.recoverPending(b)
  }
  async recoverPending(b) {
    const p = this.state.pending; if (!p) return
    await this.actors(b); const csrf = await this[p.side]('/api/v1/auth/csrf', undefined, b); await this.guard(b)
    const project = p.side === 'local' ? b.source.id : b.target.id
    let receipt
    try { receipt = await this[p.side](`/api/v1/projects/${project}/performance/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('性能同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('性能同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('性能同步回执内容不匹配。')
      const key = keyOf(op.item); next.bases = next.bases.filter(i => keyOf(i) !== key); next.bases.push(content(op.item))
      next.conflicts = next.conflicts.filter(c => c.key !== key)
    }
    next.pending = null; await this.save(next)
  }
  async run() {
    if (this.busy) return; this.busy = true
    try {
      await this.load(); const b = this.state.binding; if (!b?.enabled || b.requiresRebind) return
      await this.actors(b); await this.recoverPending(b)
      const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)]); await this.actors(b)
      requireMergeCapacity(l, r, this.state.bases)
      const changes = this.changes(l, r), next = clone(this.state)
      for (const item of changes.unchanged) { next.bases = next.bases.filter(i => keyOf(i) !== keyOf(item)); next.bases.push(item) }
      next.conflicts = changes.conflicts; await this.save(next)
      await this.send('remote', changes.push, r.revision, b); await this.send('local', changes.pull, l.revision, b)
      await this.save({ ...clone(this.state), lastSyncedAt: this.now() })
    } finally { this.busy = false }
  }
  async resolve(key, choice) {
    await this.load(); const b = this.state.binding, c = this.state.conflicts.find(c => c.key === key)
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前计划冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!same(a, c.local) || !same(other, c.remote)) throw new Error('冲突计划已变化，请重新同步并核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
  }
}
module.exports = { PerformanceSync, PerformanceSyncStore, validateState, validateItem, keyOf, empty }
