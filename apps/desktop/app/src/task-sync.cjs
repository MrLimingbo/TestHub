'use strict'
const crypto = require('node:crypto')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const content = task => ({ taskType: task.taskType, name: task.name, status: task.status, progress: task.progress })
const same = (a, b) => JSON.stringify(content(a)) === JSON.stringify(content(b))
const clone = value => globalThis.structuredClone(value)
const empty = () => ({ version: 1, binding: null, pairs: [], pending: null, conflicts: [], lastSyncedAt: null })

function validateTask(task) {
  if (!task || !id(task.id) || !Number.isSafeInteger(task.version) || task.version < 1
    || !['API', 'FUNCTIONAL', 'PERFORMANCE', 'UI', 'MOBILE', 'OTHER'].includes(task.taskType)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(task.status)
    || typeof task.name !== 'string' || !task.name.trim() || task.name !== task.name.trim() || task.name.length > 160
    || !Number.isInteger(task.progress) || task.progress < 0 || task.progress > 100
    || (task.status === 'SUCCEEDED' && task.progress !== 100) || (task.status === 'QUEUED' && task.progress !== 0))
    throw new Error('任务同步资料无效，请更新两端服务并核对登记。')
}
function validateState(value) {
  if (value?.version !== 1 || !Array.isArray(value.pairs) || value.pairs.length > 1000 || !Array.isArray(value.conflicts) || value.conflicts.length > 1000)
    throw new Error('任务同步记录损坏，请保留数据并联系支持。')
  const binding = value.binding
  if (binding && (normalizeServerUrl(binding.server) !== binding.server || !id(binding.actor.id) || !id(binding.localActorId)
    || !id(binding.source.id) || !id(binding.target.id) || typeof binding.enabled !== 'boolean'))
    throw new Error('任务同步绑定无效，请保留数据并联系支持。')
  const local = new Set(), remote = new Set()
  for (const pair of value.pairs) {
    if (!binding || !id(pair.localId) || !id(pair.remoteId) || local.has(pair.localId) || remote.has(pair.remoteId))
      throw new Error('任务同步映射损坏，请保留数据并联系支持。')
    validateTask({ ...pair.base, id: pair.localId, version: 1 }); local.add(pair.localId); remote.add(pair.remoteId)
  }
  if (value.pending && (!binding || !['local', 'remote'].includes(value.pending.side)
    || !Array.isArray(value.pending.links) || value.pending.request?.expectedUserId !== (value.pending.side === 'remote' ? binding.actor.id : binding.localActorId)
    || !Array.isArray(value.pending.request.operations) || !value.pending.links.length || value.pending.links.length > 1000
    || value.pending.request.operations.length !== value.pending.links.length || !/^[a-f0-9-]{36}$/.test(value.pending.request.requestKey)))
    throw new Error('任务同步待发送记录损坏，请保留数据并联系支持。')
  if (value.pending) for (let i = 0; i < value.pending.links.length; i++) {
    const op = value.pending.request.operations[i], link = value.pending.links[i]
    if (!op || !/^[a-f0-9-]{36}$/.test(op.operationId) || (op.taskId !== null && !id(op.taskId))
      || (op.taskId === null ? op.expectedVersion !== null : !Number.isSafeInteger(op.expectedVersion) || op.expectedVersion < 1)
      || !link || (link.localId !== null && !id(link.localId)) || (link.remoteId !== null && !id(link.remoteId))
      || (value.pending.side === 'remote' ? !id(link.localId) || op.taskId !== link.remoteId : !id(link.remoteId) || op.taskId !== link.localId))
      throw new Error('任务同步待发送映射损坏，请保留数据并联系支持。')
    validateTask({ ...op, id: op.taskId ?? 'A'.repeat(26), version: op.expectedVersion ?? 1 })
  }
  return value
}

class TaskSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'task-sync.bin', validate: validateState, empty, label: '任务' }) }
}

class TaskSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) {
    Object.assign(this, { store, local, remote, guard, now }); this.state = null; this.busy = false
  }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), lastSyncedAt: this.state.lastSyncedAt, pending: Boolean(this.state.pending) } }
  async actors(binding) {
    await this.guard(binding)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, binding)])
    if (local.id !== binding.localActorId || remote.id !== binding.actor.id) throw new Error('登录账号或本地工作区已变化，同步已停止；请切回绑定账号。')
  }
  async rows(side, binding) {
    const project = side === 'local' ? binding.source.id : binding.target.id, actor = side === 'local' ? binding.localActorId : binding.actor.id
    const result = await this[side](`/api/v1/projects/${project}/tasks/sync?expectedUserId=${actor}`, undefined, binding)
    if (result?.version !== 1 || result.scope !== 'TASK_REGISTRATION' || result.projectId !== project || result.actorId !== actor
      || result.maxTasks !== 1000 || !Array.isArray(result.tasks) || result.tasks.length > 1000)
      throw new Error('服务不支持当前任务登记同步协议，请更新两端服务。')
    const ids = new Set()
    for (const task of result.tasks) { validateTask(task); if (ids.has(task.id)) throw new Error('同步任务标识重复。'); ids.add(task.id) }
    return result.tasks
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有同步绑定，请先暂停并解除后再绑定其他项目。')
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
    if (localRows.length + remoteRows.length > 1000) throw new Error('首次合并后超过每个项目 1000 条任务登记限制。')
    this.plan = { token: crypto.randomUUID(), binding, expires: Date.now() + 600000, localRows, remoteRows }
    return { token: this.plan.token, binding: clone(binding), upload: localRows.length, download: remoteRows.length }
  }
  async bind(token) {
    const plan = this.plan
    if (!plan || plan.token !== token || plan.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(plan.binding)
    const [local, remote] = await Promise.all([this.rows('local', plan.binding), this.rows('remote', plan.binding)])
    if (JSON.stringify(local) !== JSON.stringify(plan.localRows) || JSON.stringify(remote) !== JSON.stringify(plan.remoteRows))
      throw new Error('任务登记在确认期间已变化，请重新预览后绑定。')
    await this.save({ ...empty(), binding: plan.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定任务登记同步。')
    if (value && this.state.binding.requiresRebind) throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。')
    if (value) await this.actors(this.state.binding)
    const next = clone(this.state); next.binding.enabled = value; await this.save(next)
  }
  async unbind() { await this.load(); await this.save(empty()); this.plan = undefined }
  async suspendForRestore() {
    await this.load()
    // A restored database can move versions backwards. Never reuse its old baseline or pending writes.
    if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) }
  }
  operation(source, target) {
    return { operationId: crypto.randomUUID(), taskId: target?.id ?? null, expectedVersion: target?.version ?? null, ...content(source) }
  }
  async send(side, entries, binding) {
    if (!entries.length) return
    const next = clone(this.state)
    next.pending = { side, request: { requestKey: crypto.randomUUID(), expectedUserId: side === 'local' ? binding.localActorId : binding.actor.id,
      operations: entries.map(e => e.operation) }, links: entries.map(({ localId, remoteId }) => ({ localId, remoteId })) }
    // Persist the identical request before sending: a timeout or restart must not duplicate a new registration.
    await this.save(next); await this.recoverPending(binding)
  }
  async recoverPending(binding) {
    const pending = this.state.pending
    if (!pending) return
    await this.actors(binding)
    const csrf = await this[pending.side]('/api/v1/auth/csrf', undefined, binding)
    await this.guard(binding)
    const project = pending.side === 'local' ? binding.source.id : binding.target.id
    let receipt
    try {
      receipt = await this[pending.side](`/api/v1/projects/${project}/tasks/sync`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(pending.request) }, binding)
    } catch (error) {
      // A 409 guarantees the whole batch was rejected. Re-read both sides before making a new decision.
      if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) }
      throw error
    }
    if (receipt?.requestKey !== pending.request.requestKey || receipt.results?.length !== pending.links.length) throw new Error('同步回执不完整，将保留待发送记录供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (let i = 0; i < pending.links.length; i++) {
      const operation = pending.request.operations[i], result = receipt.results.find(r => r.operationId === operation.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('同步回执标识不匹配。')
      seen.add(result.operationId); validateTask(result.task)
      if (!same(operation, result.task) || (operation.taskId && operation.taskId !== result.task.id)) throw new Error('同步回执内容不匹配。')
      const link = pending.links[i], pair = { localId: pending.side === 'local' ? result.task.id : link.localId,
        remoteId: pending.side === 'remote' ? result.task.id : link.remoteId, base: content(operation) }
      next.pairs = next.pairs.filter(p => p.localId !== pair.localId && p.remoteId !== pair.remoteId); next.pairs.push(pair)
      next.conflicts = next.conflicts.filter(c => c.localId !== pair.localId)
    }
    next.pending = null; await this.save(next)
  }
  async run() {
    if (this.busy) return
    this.busy = true
    try {
      await this.load(); const binding = this.state.binding
      if (!binding?.enabled || binding.requiresRebind) return
      await this.actors(binding); await this.recoverPending(binding)
      const [local, remote] = await Promise.all([this.rows('local', binding), this.rows('remote', binding)])
      await this.actors(binding)
      const localById = new Map(local.map(t => [t.id, t])), remoteById = new Map(remote.map(t => [t.id, t]))
      const push = [], pull = [], conflicts = [], next = clone(this.state)
      for (const pair of next.pairs) {
        const l = localById.get(pair.localId), r = remoteById.get(pair.remoteId)
        if (!l || !r) { conflicts.push({ localId: pair.localId, local: l ?? null, remote: r ?? null, reason: '一端登记已不存在，请保留现有资料并核对。' }); continue }
        if (same(l, r)) { pair.base = content(l); continue }
        const lc = !same(l, pair.base), rc = !same(r, pair.base)
        if (lc && rc) conflicts.push({ localId: pair.localId, local: l, remote: r, reason: '同一条登记在两端均已修改。' })
        else if (lc) push.push({ localId: l.id, remoteId: r.id, operation: this.operation(l, r) })
        else if (rc) pull.push({ localId: l.id, remoteId: r.id, operation: this.operation(r, l) })
      }
      for (const l of local) if (!next.pairs.some(p => p.localId === l.id)) push.push({ localId: l.id, remoteId: null, operation: this.operation(l) })
      for (const r of remote) if (!next.pairs.some(p => p.remoteId === r.id)) pull.push({ localId: null, remoteId: r.id, operation: this.operation(r) })
      next.conflicts = conflicts; await this.save(next)
      await this.send('remote', push, binding); await this.send('local', pull, binding)
      await this.save({ ...clone(this.state), lastSyncedAt: this.now() })
    } finally { this.busy = false }
  }
  async resolve(localId, choice) {
    await this.load(); const binding = this.state.binding, conflict = this.state.conflicts.find(c => c.localId === localId)
    if (!binding?.enabled || binding.requiresRebind || this.state.pending || !conflict?.local || !conflict.remote || !['local', 'remote'].includes(choice))
      throw new Error('当前冲突无法自动处理，请先启用同步并核对两端资料。')
    await this.actors(binding)
    const [local, remote] = await Promise.all([this.rows('local', binding), this.rows('remote', binding)])
    const l = local.find(t => t.id === conflict.local.id), r = remote.find(t => t.id === conflict.remote.id)
    if (!l || !r || JSON.stringify(l) !== JSON.stringify(conflict.local) || JSON.stringify(r) !== JSON.stringify(conflict.remote))
      throw new Error('冲突资料已变化，请刷新并重新核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [{ localId: l.id, remoteId: r.id,
      operation: this.operation(choice === 'local' ? l : r, choice === 'local' ? r : l) }], binding)
  }
}
module.exports = { TaskSync, TaskSyncStore, validateState }
