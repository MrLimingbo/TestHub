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
const content = item => ({ name: item.name, eventCode: item.eventCode, channel: item.channel, subjectTemplate: item.subjectTemplate, bodyTemplate: item.bodyTemplate })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null })
const validKey = key => typeof key === 'string' && key.length > 0 && key.length <= 240 && key === key.trim() && key === key.toLowerCase()
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i]))
  if (merged.size > 1000) throw new Error('合并后（含待核对的缺失模板）超过 1000 个通知模板限制，请先核对资料或解除绑定重新预览。')
  const largest = new Map()
  for (const item of [...local.items, ...remote.items]) {
    const encoded = JSON.stringify(content(item)), key = keyOf(item)
    if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded)
  }
  const bytes = [...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024)
  if (bytes > 3 * 1024 * 1024) throw new Error('合并后通知模板超过 3 MiB 同步范围，请先缩减资料。')
}
function validateItem(item) {
  const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value === value.trim() && value.length <= max
  if (!item || !text(item.name, 120) || !text(item.subjectTemplate, 255) || !text(item.bodyTemplate, 20000)
    || typeof item.eventCode !== 'string' || !/^[a-z][a-z0-9_.-]{1,79}$/.test(item.eventCode)
    || !['EMAIL', 'WEBHOOK', 'WECHAT', 'DINGTALK'].includes(item.channel)
    || Object.keys(item).some(key => !['name', 'eventCode', 'channel', 'subjectTemplate', 'bodyTemplate'].includes(key)))
    throw new Error('通知模板同步资料无效，请核对两端模板定义。')
  return item
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 1000 || !Array.isArray(state.conflicts) || state.conflicts.length > 2000)
    throw new Error('通知模板同步记录损坏，请保留现有数据。')
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('通知模板同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的模板不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('通知模板同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean') throw new Error('通知模板同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('通知模板同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name || c.local.channel !== c.remote.channel)) throw new Error('通知模板同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 1000) throw new Error('通知模板同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('通知模板同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class NotificationSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'notification-sync.bin', validate: validateState, empty,
    label: '通知模板', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class NotificationSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，通知模板同步已停止；请切回绑定账号。')
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/notification-templates/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'NOTIFICATION_TEMPLATES_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 1000
      || !Number.isInteger(snapshot.excludedTargets) || snapshot.excludedTargets < 0 || snapshot.excludedTargets > snapshot.items.length
      || !Number.isInteger(snapshot.excludedSecrets) || snapshot.excludedSecrets < 0 || snapshot.excludedSecrets > snapshot.items.length)
      throw new Error('服务不支持当前通知模板同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('通知模板存在同名或大小写重复，请先统一名称。'); keys.add(key) }
    return snapshot
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有通知模板同步绑定，请先暂停并解除后再绑定其他项目。')
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
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount, excludedSecrets: counts.excludedSecrets, excludedTargets: counts.excludedTargets }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('通知模板在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定通知模板同步。')
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
      if (base && (!a || !b)) { issue('一端模板已删除或改名，请核对；不会自动删除或重新创建。'); continue }
      if (a && b && a.name !== b.name) { issue('模板名称大小写不同，请先在两端统一名称。'); continue }
      if (a && b && a.channel !== b.channel) { issue('模板通道不同，请先在两端手动统一；同步不会切换投递通道。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) {
        conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一模板在两端均已修改。' : '两端已有同名模板内容不同。', canResolve: true }); continue
      }
      const side = !b || (a && base && !same(a, base)) ? 'remote' : 'local', item = side === 'remote' ? a : b
      ;(side === 'remote' ? push : pull).push(content(item))
    }
    return { push, pull, conflicts, unchanged, upload: push.length, download: pull.length, conflictCount: conflicts.length,
      excludedSecrets: local.excludedSecrets + remote.excludedSecrets, excludedTargets: local.excludedTargets + remote.excludedTargets }
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
    try { receipt = await this[p.side](`/api/v1/projects/${project}/notification-templates/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('通知同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('通知同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('通知同步回执内容不匹配。')
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
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前模板冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!same(a, c.local) || !same(other, c.remote)) throw new Error('冲突模板已变化，请重新同步并核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
  }
}
module.exports = { NotificationSync, NotificationSyncStore, validateState, validateItem, keyOf, empty }
