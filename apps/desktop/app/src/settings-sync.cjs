'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const revision = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const keyOf = item => item.kind === 'ENVIRONMENT' ? `E:${item.environmentCode}` : `P:${item.environmentCode}:${item.key.toLowerCase()}`
const content = item => ({ kind: item.kind, environmentCode: item.environmentCode, key: item.key, name: item.name, baseUrl: item.baseUrl, description: item.description, value: item.value })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null })
const validKey = key => typeof key === 'string' && /^(?:E:[a-z0-9][a-z0-9_-]{0,63}|P:[a-z0-9][a-z0-9_-]{0,63}:[a-z_][a-z0-9_.-]{0,127})$/.test(key)
function requireMergeCapacity(local, remote, bases = []) {
  const merged = [...new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i])).values()]
  if (merged.filter(i => i.kind === 'ENVIRONMENT').length > 100 || merged.filter(i => i.kind === 'PARAMETER').length > 1000)
    throw new Error('合并后（含待核对的缺失配置）超过 100 个环境或 1000 个普通参数限制，请先核对资料或解除绑定重新预览。')
  // Budget the larger variant of each conflict and both sets of secret names; values are never read.
  const largest = new Map()
  for (const item of [...local.items, ...remote.items]) {
    const encoded = JSON.stringify(content(item)), key = keyOf(item)
    if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded)
  }
  const bytes = [...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 0)
    + Buffer.byteLength(JSON.stringify([...new Set([...local.blockedKeys, ...remote.blockedKeys])])) + 1024
  if (bytes > 3 * 1024 * 1024) throw new Error('合并后配置超过 3 MiB 同步范围，请先缩减资料。')
}
function validateItem(item) {
  const fail = () => { throw new Error('项目配置同步资料无效，请核对两端环境和普通参数。') }
  if (!item || typeof item.environmentCode !== 'string' || !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(item.environmentCode)) fail()
  if (item.kind === 'ENVIRONMENT') {
    if (item.key !== null || item.value !== null || typeof item.name !== 'string' || !item.name.trim() || item.name !== item.name.trim() || item.name.length > 160
      || typeof item.description !== 'string' || item.description.length > 1200 || item.description !== item.description.trim()
      || typeof item.baseUrl !== 'string' || item.baseUrl.length > 2000) fail()
    if (item.baseUrl) {
      let url; try { url = new URL(item.baseUrl) } catch { fail() }
      if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash || item.baseUrl.endsWith('/')) fail()
    }
  } else if (item.kind === 'PARAMETER') {
    if (item.name !== null || item.baseUrl !== null || item.description !== null || typeof item.key !== 'string'
      || !/^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/.test(item.key) || item.key.toUpperCase() === 'BASE_URL'
      || typeof item.value !== 'string' || item.value.length > 20000) fail()
  } else fail()
  return item
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 1100 || !Array.isArray(state.conflicts) || state.conflicts.length > 2200)
    throw new Error('项目配置同步记录损坏，请保留现有数据。')
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('项目配置同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的配置不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('项目配置同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean') throw new Error('项目配置同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('项目配置同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.key !== c.remote.key)) throw new Error('项目配置同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 1100) throw new Error('项目配置同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('项目配置同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class SettingsSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'settings-sync.bin', validate: validateState, empty,
    label: '项目配置', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class SettingsSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，项目配置同步已停止；请切回绑定账号。')
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/project-settings/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'PROJECT_SETTINGS_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 1100
      || !Array.isArray(snapshot.blockedKeys) || snapshot.blockedKeys.length > 1000 || !Number.isInteger(snapshot.excludedSecrets) || snapshot.excludedSecrets < 0 || snapshot.excludedSecrets > 1000)
      throw new Error('服务不支持当前项目配置同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('同一环境存在仅大小写不同的参数键，请先统一名称。'); keys.add(key) }
    if (snapshot.items.filter(i => i.kind === 'ENVIRONMENT').length > 100 || snapshot.items.filter(i => i.kind === 'PARAMETER').length > 1000
      || snapshot.blockedKeys.some(k => !validKey(k)) || new Set(snapshot.blockedKeys).size !== snapshot.blockedKeys.length) throw new Error('同步配置数量或密钥标识无效。')
    return snapshot
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有项目配置同步绑定，请先暂停并解除后再绑定其他项目。')
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
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount, excludedSecrets: counts.excludedSecrets }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('项目配置在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定项目配置同步。')
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
    const bases = new Map(this.state.bases.map(i => [keyOf(i), i])), blocked = new Set([...local.blockedKeys, ...remote.blockedKeys])
    const push = [], pull = [], conflicts = [], unchanged = []
    for (const key of new Set([...l.keys(), ...r.keys(), ...bases.keys()])) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      if (same(a, b)) { unchanged.push(content(a)); continue }
      const issue = reason => conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason, canResolve: false })
      if (blocked.has(key)) { issue('一端存在同名密钥，请在各端自行维护；同步不会覆盖密钥。'); continue }
      if (base && (!a || !b)) { issue('一端配置已删除或转为密钥，请核对；不会自动删除或重新创建。'); continue }
      if (a && b && a.key !== b.key) { issue('参数键大小写不同，请先在两端统一名称。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) {
        conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一配置在两端均已修改。' : '两端已有同名配置内容不同。', canResolve: true }); continue
      }
      const side = !b || (a && base && !same(a, base)) ? 'remote' : 'local', item = side === 'remote' ? a : b
      const target = side === 'remote' ? r : l
      if (item.kind === 'PARAMETER' && !target.has(`E:${item.environmentCode}`)
        && !(side === 'remote' ? local : remote).items.some(i => i.kind === 'ENVIRONMENT' && i.environmentCode === item.environmentCode)) { issue('参数所属环境尚未定义，请先补齐环境。'); continue }
      ;(side === 'remote' ? push : pull).push(content(item))
    }
    // A blocked environment must not strand dependent parameters in an unresolvable pending batch.
    for (const [entries, target] of [[push, r], [pull, l]]) for (let i = entries.length - 1; i >= 0; i--) {
      const item = entries[i]
      if (item.kind === 'PARAMETER' && !target.has(`E:${item.environmentCode}`) && !entries.some(e => e.kind === 'ENVIRONMENT' && e.environmentCode === item.environmentCode)) {
        const key = keyOf(item); conflicts.push({ key, local: l.get(key) ?? null, remote: r.get(key) ?? null, reason: '所属环境需要先处理冲突，再同步普通参数。', canResolve: false }); entries.splice(i, 1)
      }
    }
    return { push, pull, conflicts, unchanged, upload: push.length, download: pull.length, conflictCount: conflicts.length, excludedSecrets: local.excludedSecrets + remote.excludedSecrets }
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
    try { receipt = await this[p.side](`/api/v1/projects/${project}/project-settings/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('配置同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('配置同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('配置同步回执内容不匹配。')
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
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前配置冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!same(a, c.local) || !same(other, c.remote) || l.blockedKeys.includes(key) || r.blockedKeys.includes(key)) throw new Error('冲突配置已变化，请重新同步并核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
  }
}
module.exports = { SettingsSync, SettingsSyncStore, validateState, validateItem, keyOf, empty }
