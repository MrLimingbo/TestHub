'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = v => globalThis.structuredClone(v)
const id = v => typeof v === 'string' && /^[A-Z0-9]{26}$/.test(v)
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const revision = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const object = v => v && typeof v === 'object' && !Array.isArray(v)
const exact = (v, fields) => object(v) && Object.keys(v).length === fields.length && fields.every(f => Object.hasOwn(v, f))
const name = v => typeof v === 'string' && v.trim() === v && v.length > 0 && v.length <= 160
const kinds = ['SCHEDULE', 'WEBHOOK']
const keyOf = v => v.kind + '\n' + v.name.toLowerCase()
const validKey = k => typeof k === 'string' && kinds.includes(k.split('\n')[0]) && name(k.slice(k.indexOf('\n') + 1)) && k.slice(k.indexOf('\n') + 1) === k.slice(k.indexOf('\n') + 1).toLowerCase()
function stable(v) { return Array.isArray(v) ? v.map(stable) : object(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, stable(v[k])])) : v }
const content = i => ({ kind: i.kind, name: i.name, definition: stable(i.definition) })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null, identities: { local: {}, remote: {} }, references: { local: {}, remote: {} } })
function validateItem(i) {
  if (!exact(i, ['kind', 'name', 'definition']) || !kinds.includes(i.kind) || !name(i.name)) throw new Error('触发配置类型或名称不合法。')
  const d = i.definition
  if (i.kind === 'WEBHOOK') { if (!exact(d, ['scenarioName']) || !name(d.scenarioName)) throw new Error('Webhook 定义字段不合法。') }
  else if (!exact(d, ['scenarioName', 'executionProfileName', 'cronExpression', 'timezone', 'timeoutSeconds', 'maxConsecutiveFailures'])
    || !name(d.scenarioName) || (d.executionProfileName !== null && !name(d.executionProfileName)) || !name(d.cronExpression)
    || typeof d.timezone !== 'string' || !d.timezone || d.timezone.trim() !== d.timezone || d.timezone.length > 64
    || !Number.isInteger(d.timeoutSeconds) || d.timeoutSeconds < 10 || d.timeoutSeconds > 3600
    || !Number.isInteger(d.maxConsecutiveFailures) || d.maxConsecutiveFailures < 1 || d.maxConsecutiveFailures > 10) throw new Error('定时计划字段不合法。')
  return i
}
function capacity(items) {
  items.forEach(validateItem)
  if (items.length > 400 || kinds.some(k => items.filter(i => i.kind === k).length > 200) || Buffer.byteLength(JSON.stringify(items)) > 2 * 1024 * 1024) throw new Error('每类触发配置最多 200 条，合计最多 2 MiB。')
}
function requireMergeCapacity(l, r, bases = []) {
  const items = new Map([...bases, ...l.items, ...r.items].map(i => [keyOf(i), i])); capacity([...items.values()])
  const keys = new Set([...items.keys(), ...Object.keys(l.blockedKeys), ...Object.keys(r.blockedKeys)])
  if (keys.size > 400 || kinds.some(k => [...keys].filter(key => key.startsWith(k + '\n')).length > 200)) throw new Error('合并后每类触发配置超过 200 条。')
}
function validateReferences(v) {
  if (!object(v) || Object.keys(v).length > 2000 || Object.entries(v).some(([k, v]) => k.indexOf('\n') < 1 || !['SCENARIO', 'EXECUTION_PROFILE'].includes(k.split('\n')[0]) || !name(k.slice(k.indexOf('\n') + 1)) || !id(v))) throw new Error('场景或配置集标识无效。')
}
function refs(i) { const result = ['SCENARIO\n' + i.definition.scenarioName]; if (i.kind === 'SCHEDULE' && i.definition.executionProfileName) result.push('EXECUTION_PROFILE\n' + i.definition.executionProfileName); return result }
function destinationIssue(i, target) { if (refs(i).some(key => !target.referenceIds[key])) return '目标场景／运行配置缺失、名称不同或重复，请先核对基础资料和场景同步。' }
function validateState(s) {
  if (s?.version !== 1 || !Array.isArray(s.bases) || !Array.isArray(s.conflicts) || s.conflicts.length > 800) throw new Error('触发配置同步记录损坏，请保留现有数据。')
  capacity(s.bases)
  for (const side of ['local', 'remote']) {
    if (!object(s.identities?.[side]) || Object.keys(s.identities[side]).length > 400 || Object.entries(s.identities[side]).some(([k, v]) => !validKey(k) || !id(v))) throw new Error('触发配置同步标识无效。')
    validateReferences(s.references?.[side])
  }
  const b = s.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('触发配置同步绑定无效。')
  if (!b && (s.bases.length || s.conflicts.length || s.pending)) throw new Error('未绑定资料不能含有同步记录。')
  const keys = new Set(); for (const i of s.bases) { if (keys.has(keyOf(i))) throw new Error('触发配置基线重复。'); keys.add(keyOf(i)) }
  const conflicts = new Set()
  for (const c of s.conflicts) {
    if (!validKey(c.key) || conflicts.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean' || (c.reviewAfterMissing !== undefined && typeof c.reviewAfterMissing !== 'boolean')) throw new Error('触发配置冲突记录无效。')
    conflicts.add(c.key); for (const i of [c.local, c.remote]) if (i !== null) { validateItem(i); if (keyOf(i) !== c.key) throw new Error('触发配置冲突来源不匹配。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name)) throw new Error('触发配置冲突选择无效。')
  }
  if (s.pending) {
    const p = s.pending, r = p.request
    if (!b || !['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 400
      || Buffer.byteLength(JSON.stringify(r)) > 4 * 1024 * 1024) throw new Error('触发配置待确认请求无效。')
    const keys = new Set(), operations = new Set()
    for (const op of r.operations) { validateItem(op.item); if (!uuid(op.operationId) || operations.has(op.operationId) || keys.has(keyOf(op.item))) throw new Error('触发配置待确认操作重复。'); operations.add(op.operationId); keys.add(keyOf(op.item)) }
  }
  return s
}
class ApiAutomationTriggerSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'api-automation-trigger-sync.bin', validate: validateState, empty, label: '接口自动化触发配置', maxPlain: 16 * 1024 * 1024, maxEncrypted: 20 * 1024 * 1024 }) }
}
class ApiAutomationTriggerSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，接口自动化触发配置同步已停止；请切回绑定账号。')

  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const s = await this[side](`/api/v1/projects/${project}/api-automation/triggers/sync?expectedUserId=${actor}`, undefined, b)
    if (s?.version !== 1 || s.scope !== 'API_AUTOMATION_TRIGGER_SYNC' || s.projectId !== project || s.actorId !== actor
      || !revision(s.revision) || !Array.isArray(s.items) || !object(s.itemIds) || !object(s.blockedKeys) || !object(s.referenceIds)) throw new Error('触发配置服务、账号或协议无效。')
    capacity(s.items); validateReferences(s.referenceIds)
    const keys = new Set()
    for (const item of s.items) { validateItem(item); if (keys.has(keyOf(item)) || !id(s.itemIds[keyOf(item)])) throw new Error('触发器名称重复或标识不完整。'); keys.add(keyOf(item)) }
    for (const [key, reason] of Object.entries(s.blockedKeys)) { if (!validKey(key) || keys.has(key) || typeof reason !== 'string' || reason.length > 1000) throw new Error('暂停触发器标识无效。'); keys.add(key) }
    if (keys.size > 400 || Object.entries(s.itemIds).some(([key, value]) => !keys.has(key) || !id(value))) throw new Error('触发配置标识不完整。')
    return s
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有接口自动化触发配置同步绑定，请先暂停并解除后再绑定其他项目。')
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
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('接口自动化触发配置在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定接口自动化触发配置同步。')
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
    const bases = new Map(this.state.bases.map(i => [keyOf(i), i])), push = [], pull = [], conflicts = [], unchanged = []
    const issue = (key, reason, reviewAfterMissing = false) => {
      const a = l.get(key), b = r.get(key)
      conflicts.push({ key, local: a || null, remote: b || null, reason, reviewAfterMissing, canResolve: Boolean(reviewAfterMissing && a && b && a.name === b.name) })
    }
    const keys = new Set([...l.keys(), ...r.keys(), ...bases.keys(), ...Object.keys(local.blockedKeys), ...Object.keys(remote.blockedKeys)])
    for (const key of keys) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      const blocked = local.blockedKeys[key] || remote.blockedKeys[key]
      if (blocked) { issue(key, blocked, Boolean(base)); conflicts.at(-1).canResolve = false; continue }
      const recreated = [['local', a, local], ['remote', b, remote]].some(([side, item, s]) => item && Object.entries(this.state.identities[side]).some(([before, identity]) => before === key ? identity !== s.itemIds[key] : identity === s.itemIds[key]))
      const referenceRecreated = ['local', 'remote'].some(side => {
        const s = side === 'local' ? local : remote
        return [...(a ? refs(a) : []), ...(b ? refs(b) : [])].some(ref => s.referenceIds[ref] && Object.entries(this.state.references[side]).some(([before, identity]) => before === ref ? identity !== s.referenceIds[ref] : identity === s.referenceIds[ref]))
      })
      if (recreated || referenceRecreated) { issue(key, '触发器或引用来源已改名／同名重建，请人工核对后选择配置。', true); continue }
      if (base && (!a || !b)) { issue(key, '一端触发器已删除或改名，不会自动删除或重建，请核对。', true); continue }
      if (this.state.conflicts.some(c => c.key === key && c.reviewAfterMissing) && a && b) { issue(key, '缺失或重建资料已恢复，请人工核对后选择。', true); continue }
      if (same(a, b)) { unchanged.push(content(a)); continue }
      if (a && b && a.name !== b.name) { issue(key, '触发器名称大小写不同，请先在两端统一。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) { conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一触发配置在两端均已修改。' : '两端同名触发配置内容不同。', canResolve: true }); continue }
      const destination = !b || (a && base && !same(a, base)) ? push : pull
      destination.push(content(destination === push ? a : b))
    }
    for (const [items, target] of [[push, remote], [pull, local]]) for (const item of items) {
      const reason = destinationIssue(item, target); if (reason) issue(keyOf(item), reason)
    }
    const blocked = new Set(conflicts.map(c => c.key)), uploads = push.filter(i => !blocked.has(keyOf(i))), downloads = pull.filter(i => !blocked.has(keyOf(i)))
    return { push: uploads, pull: downloads, conflicts, unchanged, upload: uploads.length, download: downloads.length, conflictCount: conflicts.length }
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
    try { receipt = await this[p.side](`/api/v1/projects/${project}/api-automation/triggers/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('接口自动化触发配置同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('接口自动化触发配置同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('接口自动化触发配置同步回执内容不匹配。')
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
      const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
      await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds }, references: { local: latestLocal.referenceIds, remote: latestRemote.referenceIds }, lastSyncedAt: this.now() })
    } finally { this.busy = false }
  }
  async resolve(key, choice) {
    await this.load(); const b = this.state.binding, c = this.state.conflicts.find(c => c.key === key)
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前资料冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!this.changes(l, r).conflicts.find(c => c.key === key)?.canResolve || !same(a, c.local) || !same(other, c.remote)) throw new Error('冲突资料已变化，请重新同步并核对后选择。')
    const selected = choice === 'local' ? a : other, target = choice === 'local' ? r : l
    const issue = destinationIssue(selected, target); if (issue) throw new Error(issue)
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
    const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds }, references: { local: latestLocal.referenceIds, remote: latestRemote.referenceIds } })
  }
}
module.exports = { ApiAutomationTriggerSync, ApiAutomationTriggerSyncStore, validateItem, validateState, empty, keyOf }
