'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const key = value => typeof value === 'string' && /^[A-Z0-9]{26}\.[A-Z0-9]{26}$/.test(value)
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const terminal = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'TIMED_OUT']
const exact = (v, fields) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === fields.length && fields.every(f => Object.hasOwn(v, f))
const time = value => typeof value === 'string' && Number.isFinite(Date.parse(value)) && Date.parse(value) >= 1000 && Date.parse(value) <= 2147483647499
const optionalTime = value => value === null || time(value)
const integer = value => Number.isSafeInteger(value) && value >= 0
const hash = value => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex')
const entryFields = ['key', 'revision', 'nativeRecord', 'scenarioName', 'status', 'requestedAt', 'finishedAt']
function validateEntry(v) {
  if (!exact(v, entryFields) || !key(v.key) || !digest(v.revision) || typeof v.nativeRecord !== 'boolean'
    || typeof v.scenarioName !== 'string' || !v.scenarioName.trim() || v.scenarioName.length > 160
    || !terminal.includes(v.status) || !time(v.requestedAt) || !optionalTime(v.finishedAt)) throw new Error('历史索引字段不完整或不合法。')
  return v
}
function validateItem(v) {
  const p=v?.report,fail=()=>{throw new Error('移动端历史字段或 SHA-256 校验失败。')}
  const name=n=>typeof n==='string'&&n.trim().length>0&&n.length<=160
  const before=(a,b)=>a!==null&&b!==null&&Date.parse(a)+1<Date.parse(b)
  if(!exact(v,['key','revision','sha256','report'])||!key(v.key)||!digest(v.revision)||!digest(v.sha256)
    ||!exact(p,['scenarioName','scenarioVersion','deviceName','platform','applicationName','status','statusCode','requestedAt','deadlineAt','startedAt','finishedAt','summary','steps'])
    ||hash(p)!==v.sha256||!name(p.scenarioName)||!integer(p.scenarioVersion)||p.scenarioVersion<1||p.scenarioVersion>2147483647
    ||!name(p.deviceName)||p.deviceName.length>120||!['ANDROID','IOS'].includes(p.platform)||!name(p.applicationName)||p.applicationName.length>120
    ||!terminal.includes(p.status)||!(p.statusCode===null||typeof p.statusCode==='string'&&/^[A-Z0-9_]{1,80}$/.test(p.statusCode))
    ||!time(p.requestedAt)||!time(p.deadlineAt)||!optionalTime(p.startedAt)||!optionalTime(p.finishedAt)||Date.parse(p.deadlineAt)<Date.parse(p.requestedAt)
    ||before(p.startedAt,p.requestedAt)||before(p.finishedAt,p.requestedAt)||before(p.finishedAt,p.startedAt)||!Array.isArray(p.steps)||p.steps.length>300)fail()
  const ids=new Set(),sequences=new Set();let last=0,passed=0,failed=0,duration=0
  for(const step of p.steps){
    if(!exact(step,['stepId','stepSequence','stepType','stepName','outcome','durationMillis','occurredAt'])||typeof step.stepId!=='string'||!step.stepId.trim()||step.stepId.length>64||ids.has(step.stepId)
      ||!integer(step.stepSequence)||step.stepSequence<=last||step.stepSequence>300||sequences.has(step.stepSequence)
      ||!['LAUNCH','INPUT','TAP','ASSERT_VISIBLE','ASSERT_TEXT','WAIT','SCREENSHOT'].includes(step.stepType)||!name(step.stepName)||!['PASSED','FAILED','SKIPPED'].includes(step.outcome)
      ||!integer(step.durationMillis)||step.durationMillis>120000||!time(step.occurredAt))fail()
    ids.add(step.stepId);sequences.add(step.stepSequence);last=step.stepSequence;duration+=step.durationMillis
    if(step.outcome==='PASSED')passed++;if(step.outcome==='FAILED')failed++
  }
  const s=p.summary
  if(!exact(s,['totalSteps','passedSteps','failedSteps','totalDurationMillis'])||s.totalSteps!==p.steps.length||s.passedSteps!==passed||s.failedSteps!==failed||s.totalDurationMillis!==duration)fail()
  if(Buffer.byteLength(JSON.stringify(v))>1024*1024)throw new Error('单条历史报告超过 1 MiB。')
  return v
}
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null })
const same = (a, b) => Boolean(a && b) && a.revision === b.revision && a.scenarioName === b.scenarioName && a.status === b.status && a.requestedAt === b.requestedAt && a.finishedAt === b.finishedAt
function validateState(value) {
  if (value?.version !== 1 || !Array.isArray(value.bases) || value.bases.length > 1000 || !Array.isArray(value.conflicts) || value.conflicts.length > 1000) throw new Error('历史同步记录损坏，请保留现有数据。')
  const b = value.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('历史同步绑定无效。')
  if (!b && (value.bases.length || value.pending || value.conflicts.length)) throw new Error('未绑定的历史不能含有同步记录。')
  const names = new Set()
  for (const entry of value.bases) { validateEntry(entry); if (names.has(entry.key)) throw new Error('历史基线重复。'); names.add(entry.key) }
  const conflicts = new Set()
  for (const c of value.conflicts) {
    if (!key(c.key) || conflicts.has(c.key) || typeof c.reason !== 'string' || c.canResolve !== false) throw new Error('历史冲突记录无效。')
    conflicts.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateEntry(item); if (item.key !== c.key) throw new Error('历史冲突来源不匹配。') }
  }
  const p = value.pending
  if (p) {
    const r = p.request
    if (!b || !['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !digest(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.items) || !r.items.length || r.items.length > 20
      || !Array.isArray(p.entries) || p.entries.length !== r.items.length || Buffer.byteLength(JSON.stringify(r)) > 4 * 1024 * 1024) throw new Error('待确认历史请求无效。')
    const keys = new Set()
    r.items.forEach((item, i) => { validateItem(item); validateEntry(p.entries[i]); if (keys.has(item.key) || item.key !== p.entries[i].key || item.revision !== p.entries[i].revision) throw new Error('待确认历史来源不匹配。'); keys.add(item.key) })
  }
  return value
}
class MobileAutomationHistorySyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'mobile-automation-history-sync.bin', validate: validateState, empty, label: '移动端执行历史', maxPlain: 12 * 1024 * 1024, maxEncrypted: 16 * 1024 * 1024 }) }
}
class MobileAutomationHistorySync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { validateState(value); await this.store.write(value); this.state = value }
  async status() { return clone(await this.load()) }
  async actors(b) {
    await this.guard(b)
    const [l, r] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (l.id !== b.localActorId || r.id !== b.actor.id) throw new Error('绑定账号已变化，历史同步已停止。')
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const value = await this[side](`/api/v1/projects/${project}/mobile-automation/history/sync?expectedUserId=${actor}`, undefined, b)
    if (value?.version !== 1 || value.scope !== 'MOBILE_HISTORY_SYNC' || value.projectId !== project || value.actorId !== actor
      || !digest(value.revision) || !Array.isArray(value.entries) || value.entries.length > 1000) throw new Error('历史服务版本、账号或索引无效。')
    const keys = new Set()
    for (const entry of value.entries) {
      validateEntry(entry)
      if (keys.has(entry.key) || entry.nativeRecord !== entry.key.startsWith(project + '.')) throw new Error('历史来源重复或归属不合法。')
      keys.add(entry.key)
    }
    return value
  }
  changes(l, r) {
    const local = new Map(l.entries.map(e => [e.key, e])), remote = new Map(r.entries.map(e => [e.key, e]))
    const keys = new Set([...local.keys(), ...remote.keys(), ...this.state.bases.map(e => e.key)])
    if (keys.size > 1000) throw new Error('合并历史超过 1000 条；现有记录保留，不会截断复制。')
    const push = [], pull = [], conflicts = [], unchanged = []
    for (const key of keys) {
      const a = local.get(key), b = remote.get(key), base = this.state.bases.find(e => e.key === key)
      const issue = reason => conflicts.push({ key, local: a || null, remote: b || null, reason, canResolve: false })
      if (base && (!a || !b)) { issue('一端历史缺失，不会自动删除或重建，请核对来源。'); continue }
      if (same(a, b)) { unchanged.push(a); continue }
      if (a?.nativeRecord && b?.nativeRecord) { issue('两端都有同一来源的真实执行，但报告修订不同，请核对原工作区。'); continue }
      if (a?.nativeRecord) { push.push(a); continue }
      if (b?.nativeRecord) { pull.push(b); continue }
      if (a && b) { issue('同一来源的两个历史副本不同，原执行端暂不可用，请先核对来源。'); continue }
      if (a) push.push(a); else if (b) pull.push(b)
    }
    return { push, pull, conflicts, unchanged, upload: push.length, download: pull.length, conflictCount: conflicts.length }
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有历史同步绑定，请先解除后再绑定其他项目。')
    if (!id(sourceId) || !id(targetId)) throw new Error('请选择有效项目。')
    server = normalizeServerUrl(server)
    const [actor, localActor, l, r] = await Promise.all([this.remote('/api/v1/auth/me', undefined, { server }), this.local('/api/v1/auth/me'), this.local('/api/v1/access/context'), this.remote('/api/v1/access/context', undefined, { server })])
    const projects = v => v.organizations.flatMap(org => (v.projects[org.id] || []).map(p => ({ id: p.id, name: p.name, organization: org.name })))
    const source = projects(l).find(p => p.id === sourceId), target = projects(r).find(p => p.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取。')
    const binding = { server, actor: { id: actor.id, account: actor.account || actor.email }, localActorId: localActor.id, source, target, enabled: true }
    await this.actors(binding)
    const [localRows, remoteRows] = await Promise.all([this.rows('local', binding), this.rows('remote', binding)])
    const counts = this.changes(localRows, remoteRows)
    this.plan = { token: crypto.randomUUID(), expires: Date.now() + 600000, binding, localRows, remoteRows, counts }
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('历史绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('历史在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定历史同步。')
    if (value && this.state.binding.requiresRebind) throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。')
    if (value) await this.actors(this.state.binding)
    const next = clone(this.state); next.binding.enabled = value; await this.save(next)
  }
  async suspendForRestore() {
    await this.load()
    if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) }
  }
  async unbind() { await this.save(empty()); this.plan = undefined }
  async recoverPending(b) {
    const p = this.state.pending; if (!p) return
    await this.actors(b); const csrf = await this[p.side]('/api/v1/auth/csrf', undefined, b); await this.guard(b)
    const project = p.side === 'local' ? b.source.id : b.target.id
    let result
    try { result = await this[p.side](`/api/v1/projects/${project}/mobile-automation/history/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (result?.requestKey !== p.request.requestKey || !Array.isArray(result.results) || result.results.length !== p.request.items.length
      || p.request.items.some((item, i) => result.results[i]?.key !== item.key || result.results[i]?.revision !== item.revision || result.results[i]?.sha256 !== item.sha256)) throw new Error('历史回执不完整，保留原请求供安全重试。')
    const next = clone(this.state)
    for (const entry of p.entries) { next.bases = next.bases.filter(e => e.key !== entry.key); next.bases.push(entry); next.conflicts = next.conflicts.filter(e => e.key !== entry.key) }
    next.pending = null; await this.save(next)
  }
  async send(side, entries, b) {
    const sourceSide = side === 'local' ? 'remote' : 'local', sourceProject = sourceSide === 'local' ? b.source.id : b.target.id
    for (let offset = 0; offset < entries.length;) {
      const items = [], selected = []; let bytes = 1024
      while (offset < entries.length && items.length < 20) {
        const e = entries[offset]
        const item = validateItem(await this[sourceSide](`/api/v1/projects/${sourceProject}/mobile-automation/history/${e.key}`, undefined, b))
        if (item.key !== e.key || item.revision !== e.revision || item.report.scenarioName !== e.scenarioName || item.report.status !== e.status
          || item.report.requestedAt !== e.requestedAt || item.report.finishedAt !== e.finishedAt) throw new Error('来源历史已变化，请重新同步。')
        const size = Buffer.byteLength(JSON.stringify(item))
        if (items.length && bytes + size > 3 * 1024 * 1024) break
        bytes += size; items.push(item); selected.push(e); offset++
      }
      await this.actors(b); const target = await this.rows(side, b)
      // Recheck each destination after prior batches, without allowing a changed copy to be overwritten silently.
      for (const e of selected) {
        const current = target.entries.find(v => v.key === e.key), base = this.runTargets[side].entries.find(v => v.key === e.key)
        if (current?.nativeRecord || (!current !== !base) || (current && !same(current, base))) throw new Error('目标历史已变化，请重新同步并核对。')
      }
      const next = clone(this.state); next.pending = { side, entries: clone(selected), request: { requestKey: crypto.randomUUID(), expectedUserId: side === 'local' ? b.localActorId : b.actor.id, expectedRevision: target.revision, items } }
      await this.save(next); await this.recoverPending(b)
    }
  }
  async run() {
    if (this.busy) return; this.busy = true
    try {
      await this.load(); const b = this.state.binding; if (!b?.enabled || b.requiresRebind) return
      await this.actors(b); await this.recoverPending(b)
      const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)]), changes = this.changes(l, r)
      this.runTargets = { local: l, remote: r }
      const next = clone(this.state); next.conflicts = changes.conflicts
      for (const e of changes.unchanged) { next.bases = next.bases.filter(v => v.key !== e.key); next.bases.push(e) }
      await this.save(next)
      await this.send('remote', changes.push, b); await this.send('local', changes.pull, b)
      await this.save({ ...clone(this.state), lastSyncedAt: this.now() })
    } finally { this.runTargets = undefined; this.busy = false }
  }
  async resolve() { throw new Error('执行历史只读，请在原执行端核对不同副本；不会人工改写真实结果。') }
}
module.exports = { MobileAutomationHistorySync, MobileAutomationHistorySyncStore, validateState, validateItem, validateEntry, empty }
