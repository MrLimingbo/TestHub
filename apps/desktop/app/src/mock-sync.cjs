'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const revision = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const keyOf = item => JSON.stringify([item.serverName.toLowerCase(), item.ruleName?.toLowerCase() ?? null])
const sortedMap = map => Object.fromEntries(Object.entries(map).sort(([a], [b]) => a.localeCompare(b, 'en')))
const content = item => ({ serverName: item.serverName, ruleName: item.ruleName, rule: item.rule ? {
  name: item.rule.name, httpMethod: item.rule.httpMethod, path: item.rule.path, priority: item.rule.priority,
  requestQuery: sortedMap(item.rule.requestQuery), requestHeaders: sortedMap(item.rule.requestHeaders),
  requestBodyMatch: item.rule.requestBodyMatch, requestBody: item.rule.requestBody, responseStatus: item.rule.responseStatus,
  responseHeaders: sortedMap(item.rule.responseHeaders), responseBody: item.rule.responseBody,
} : null })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const matchingNames = (a, b) => a.serverName === b.serverName && a.ruleName === b.ruleName
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null })
const validKey = key => {
  try { const pair = JSON.parse(key); return Array.isArray(pair) && pair.length === 2 && validName(pair[0]) && pair[0] === pair[0].toLowerCase()
    && (pair[1] === null || (validName(pair[1]) && pair[1] === pair[1].toLowerCase())) && JSON.stringify(pair) === key } catch { return false }
}
const validName = value => typeof value === 'string' && value.trim().length > 0 && value === value.trim() && value.length <= 160
function capacity(items, requireParents = false) {
  const servers = new Map(), names = new Set(), counts = new Map()
  for (const item of items) {
    validateItem(item); const server = item.serverName.toLowerCase(); names.add(server)
    if (item.rule === null) servers.set(server, item.serverName)
    else counts.set(server, (counts.get(server) || 0) + 1)
  }
  if (names.size > 20 || [...counts.values()].some(n => n > 200)) throw new Error('合并后（含待核对的缺失资料）超过 20 个服务或每服务 200 条规则限制。')
  if (requireParents && items.some(i => i.rule && servers.get(i.serverName.toLowerCase()) !== i.serverName)) throw new Error('Mock 规则缺少服务或名称大小写不同，请核对资料。')
}
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i]))
  capacity([...merged.values()])
  const largest = new Map()
  for (const item of [...local.items, ...remote.items]) {
    const encoded = JSON.stringify(content(item)), key = keyOf(item)
    if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded)
  }
  const bytes = [...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024)
  if (bytes > 3 * 1024 * 1024) throw new Error('合并后Mock 服务与规则超过 3 MiB 同步范围，请先缩减资料。')
}
function validateItem(item) {
  const fail = () => { throw new Error('Mock 同步资料无效，请核对两端规则定义。') }
  if (!item || !validName(item.serverName) || Object.keys(item).some(k => !['serverName', 'ruleName', 'rule'].includes(k))) fail()
  if (item.ruleName === null && item.rule === null) return item
  const r = item.rule
  if (!validName(item.ruleName) || !r || r.name !== item.ruleName
    || Object.keys(r).some(k => !['name', 'httpMethod', 'path', 'priority', 'requestQuery', 'requestHeaders', 'requestBodyMatch', 'requestBody', 'responseStatus', 'responseHeaders', 'responseBody'].includes(k))
    || !['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].includes(r.httpMethod)
    || typeof r.path !== 'string' || !r.path.startsWith('/') || r.path.length > 700 || r.path.trim() !== r.path || /[?#\\]/.test(r.path) || r.path.includes('//') || r.path.includes('..')
    || !Number.isInteger(r.priority) || r.priority < 0 || r.priority > 1000 || !Number.isInteger(r.responseStatus) || r.responseStatus < 200 || r.responseStatus > 599
    || !['ANY', 'EXACT', 'JSON_SUBSET'].includes(r.requestBodyMatch) || typeof r.requestBody !== 'string' || r.requestBody.length > 524288
    || (r.requestBodyMatch === 'ANY' ? r.requestBody !== '' : !r.requestBody.trim()) || typeof r.responseBody !== 'string' || r.responseBody.length > 524288) fail()
  if (r.requestBodyMatch === 'JSON_SUBSET') { try { JSON.parse(r.requestBody) } catch { fail() } }
  for (const [field, isHeader] of [['requestQuery', false], ['requestHeaders', true], ['responseHeaders', true]]) {
    const map = r[field], seen = new Set()
    if (!map || Array.isArray(map) || typeof map !== 'object' || Object.keys(map).length > 32) fail()
    for (const [key, value] of Object.entries(map)) {
      if (!key.trim() || key !== key.trim() || key.length > 128 || typeof value !== 'string' || value.length > 8192 || /[\r\n]/.test(value)
        || (isHeader && !/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$/.test(key)) || (field === 'requestHeaders' && key !== key.toLowerCase())) fail()
      const normalized = isHeader ? key.toLowerCase() : key
      if (seen.has(normalized)) fail(); seen.add(normalized)
      if (field === 'responseHeaders' && ['connection', 'content-length', 'keep-alive', 'set-cookie', 'te', 'trailer', 'transfer-encoding', 'upgrade', 'x-request-id'].includes(normalized)) fail()
    }
    if (field === 'responseHeaders' && !seen.has('content-type')) fail()
  }
  return item
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 4020 || !Array.isArray(state.conflicts) || state.conflicts.length > 8040)
    throw new Error('Mock 服务与规则同步记录损坏，请保留现有数据。')
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('Mock 服务与规则同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的Mock 资料不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('Mock 服务与规则同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean') throw new Error('Mock 服务与规则同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('Mock 服务与规则同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || !matchingNames(c.local, c.remote))) throw new Error('Mock 服务与规则同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 4020) throw new Error('Mock 服务与规则同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('Mock 服务与规则同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class MockSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'mock-sync.bin', validate: validateState, empty,
    label: 'Mock 服务与规则', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class MockSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，Mock 服务与规则同步已停止；请切回绑定账号。')
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/mock/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'MOCK_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 4020
      || !Number.isInteger(snapshot.excludedPublicKeys) || snapshot.excludedPublicKeys < 0 || snapshot.excludedPublicKeys > 20
      || !Number.isInteger(snapshot.excludedShares) || snapshot.excludedShares < 0 || snapshot.excludedShares > 4000)
      throw new Error('服务不支持当前Mock 服务与规则同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('Mock 服务与规则存在同名或大小写重复，请先统一名称。'); keys.add(key) }
    capacity(snapshot.items, true)
    return snapshot
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有Mock 服务与规则同步绑定，请先暂停并解除后再绑定其他项目。')
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
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount, excludedShares: counts.excludedShares, excludedPublicKeys: counts.excludedPublicKeys }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('Mock 服务与规则在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定Mock 服务与规则同步。')
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
      const issue = reason => conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason, canResolve: false })
      const item = a || b || base, parentKey = keyOf({ serverName: item.serverName, ruleName: null })
      const localParent = l.get(parentKey), remoteParent = r.get(parentKey)
      if (item.rule && bases.has(parentKey) && (!localParent || !remoteParent)) {
        issue('所属服务已删除或改名，请先核对；不会自动恢复服务或发送新规则。'); continue
      }
      if (item.rule && localParent && remoteParent && !matchingNames(localParent, remoteParent)) {
        issue('所属服务名称大小写不同，请先在两端统一服务名称。'); continue
      }
      if (same(a, b)) { unchanged.push(content(a)); continue }
      if (base && (!a || !b)) { issue('一端Mock 资料已删除或改名，请核对；不会自动删除或重新创建。'); continue }
      if (a && b && !matchingNames(a, b)) { issue('服务或规则名称大小写不同，请先在两端统一名称。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) {
        conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一Mock 资料在两端均已修改。' : '两端已有同名Mock 资料内容不同。', canResolve: true }); continue
      }
      const side = !b || (a && base && !same(a, base)) ? 'remote' : 'local', outgoing = side === 'remote' ? a : b
      ;(side === 'remote' ? push : pull).push(content(outgoing))
    }
    return { push, pull, conflicts, unchanged, upload: push.length, download: pull.length, conflictCount: conflicts.length,
      excludedShares: local.excludedShares + remote.excludedShares, excludedPublicKeys: local.excludedPublicKeys + remote.excludedPublicKeys }
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
    try { receipt = await this[p.side](`/api/v1/projects/${project}/mock/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('Mock 同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('Mock 同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('Mock 同步回执内容不匹配。')
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
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前Mock 资料冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!same(a, c.local) || !same(other, c.remote)) throw new Error('冲突Mock 资料已变化，请重新同步并核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
  }
}
module.exports = { MockSync, MockSyncStore, validateState, validateItem, keyOf, empty }
