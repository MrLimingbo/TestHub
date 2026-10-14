'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const { ProjectFileLinks, blocked } = require('./project-file-links.cjs')
const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const revision = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const kinds = ['CATALOG_REQUIREMENTS', 'CATALOG_CASES', 'CATALOG_PLANS', 'REQUIREMENT', 'CASE', 'PLAN']
const keyOf = item => JSON.stringify([item.kind, item.name.toLowerCase()])
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  return value
}
const content = item => ({ kind: item.kind, name: item.name, definition: stable(item.definition) })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null })
const validName = (v, max = 4096) => typeof v === 'string' && v.length > 0 && v === v.trim() && v.length <= max
const validKey = key => { try { const pair = JSON.parse(key); return Array.isArray(pair) && pair.length === 2 && kinds.includes(pair[0]) && validName(pair[1]) && pair[1] === pair[1].toLowerCase() && JSON.stringify(pair) === key } catch { return false } }
function capacity(items) {
  const counts = new Map()
  for (const i of items) { validateItem(i); counts.set(i.kind, (counts.get(i.kind) || 0) + 1) }
  if ([...counts.values()].some(n => n > 100)) throw new Error('合并后（含待核对的缺失资料）每类超过 100 条 功能测试 资料限制，请先核对。')
}
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i])); capacity([...merged.values()])
  const largest = new Map()
  for (const i of [...local.items, ...remote.items]) { const encoded = JSON.stringify(content(i)), key = keyOf(i); if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded) }
  if ([...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024) > 3 * 1024 * 1024) throw new Error('合并后 功能测试 资料超过 3 MiB 同步范围，请先缩减资料。')
}
function pathParts(name) {
  let parts; try { parts = JSON.parse(name) } catch { throw new Error('功能测试目录路径无效。') }
  if (!Array.isArray(parts) || !parts.length || parts.length > 16 || parts.some(p => !validName(p, 160)) || JSON.stringify(parts) !== name || name.length > 4096) throw new Error('功能测试目录路径无效。')
  return parts
}
function dependencies(item) {
  const d = item.definition
  if (item.kind.startsWith('CATALOG_')) { const parts = pathParts(item.name); return parts.length > 1 ? [{kind:item.kind,name:JSON.stringify(parts.slice(0,-1))}] : [] }
  if (item.kind === 'REQUIREMENT') return [{kind:'CATALOG_REQUIREMENTS',name:d.catalogPath}]
  if (item.kind === 'CASE') return [{kind:'CATALOG_CASES',name:d.catalogPath}, ...(d.requirementCode ? [{kind:'REQUIREMENT',name:d.requirementCode}] : [])]
  return [...(d.catalogPath ? [{kind:'CATALOG_PLANS',name:d.catalogPath}] : []), ...d.caseCodes.map(name => ({kind:'CASE',name}))]
}
function references(items) {
  const names = new Set(items.map(i => JSON.stringify([i.kind, i.name])))
  if (items.some(i => dependencies(i).some(d => !names.has(JSON.stringify([d.kind, d.name]))))) throw new Error('功能测试引用缺失或目录名称大小写不同，请先核对资料。')
}
function hasProjectLinks(item) { return blocked(item) }
function validateItem(item) {
  const fail = () => { throw new Error('功能测试同步资料无效，请核对定义与引用。') }
  const exact = (v, fields) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === fields.length && fields.every(f => Object.hasOwn(v, f))
  const code = v => typeof v === 'string' && /^[A-Z][A-Z0-9_-]{1,39}$/.test(v)
  const text = (v, max = 20000) => typeof v === 'string' && v === v.trim() && v.length <= max
  if (!exact(item, ['kind', 'name', 'definition']) || !kinds.includes(item.kind)) fail()
  const d = item.definition
  if (item.kind.startsWith('CATALOG_')) {
    pathParts(item.name)
    if (!exact(d, ['sortOrder']) || !Number.isInteger(d.sortOrder) || d.sortOrder < 0 || d.sortOrder > 9999) fail()
  } else {
    if (!code(item.name)) fail()
    if (item.kind === 'REQUIREMENT') {
      if (!exact(d, ['catalogPath','title','priority','status','description']) || !validName(d.title,200) || !['P0','P1','P2','P3'].includes(d.priority) || !['DRAFT','REVIEWING','APPROVED','ARCHIVED'].includes(d.status) || !text(d.description)) fail()
      pathParts(d.catalogPath)
    } else if (item.kind === 'CASE') {
      if (!exact(d, ['catalogPath','requirementCode','title','priority','status','caseType','precondition','steps','expectedResult']) || (d.requirementCode !== null && !code(d.requirementCode)) || !validName(d.title,200) || !['P0','P1','P2','P3'].includes(d.priority) || !['DRAFT','READY','BLOCKED','ARCHIVED'].includes(d.status) || !['UNIT','API','FUNCTIONAL','INSTALLATION','CONFIGURATION','PERFORMANCE','SECURITY','OTHER'].includes(d.caseType) || !text(d.precondition) || !validName(d.steps,20000) || !validName(d.expectedResult,20000)) fail()
      pathParts(d.catalogPath)
    } else {
      if (!exact(d, ['catalogPath','title','status','description','caseCodes']) || !validName(d.title,200) || !['DRAFT','READY','ARCHIVED'].includes(d.status) || !text(d.description) || !Array.isArray(d.caseCodes) || d.caseCodes.length > 100 || new Set(d.caseCodes).size !== d.caseCodes.length || d.caseCodes.some(v=>!code(v))) fail()
      if (d.catalogPath !== null) pathParts(d.catalogPath)
    }
  }
  return item
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 600 || !Array.isArray(state.conflicts) || state.conflicts.length > 1200)
    throw new Error('功能测试 资料同步记录损坏，请保留现有数据。')
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('功能测试 资料同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的资料不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('功能测试 资料同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean') throw new Error('功能测试 资料同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('功能测试 资料同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name)) throw new Error('功能测试 资料同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 600) throw new Error('功能测试 资料同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('功能测试 资料同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class FunctionalSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'functional-sync.bin', validate: validateState, empty,
    label: '功能测试 资料', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class FunctionalSync {
  constructor({ store, local, remote, guard, fileLinkContext, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, fileLinkContext, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，功能测试 资料同步已停止；请切回绑定账号。')
    this.fileMapper = new ProjectFileLinks(b, await this.fileLinkContext?.(b))
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/functional/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'FUNCTIONAL_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 600
      || !Number.isSafeInteger(snapshot.excludedExecutions) || snapshot.excludedExecutions < 0 || !Number.isSafeInteger(snapshot.excludedFiles) || snapshot.excludedFiles < 0)
      throw new Error('服务不支持当前功能测试 资料同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('功能测试 资料存在同名或大小写重复，请先统一名称。'); keys.add(key) }
    capacity(snapshot.items); references(snapshot.items)
    return { ...snapshot, items: snapshot.items.map(item => this.fileMapper.logical(item, side)) }
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有功能测试 资料同步绑定，请先暂停并解除后再绑定其他项目。')
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
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount, excludedExecutions: counts.excludedExecutions, excludedFiles: counts.excludedFiles }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('功能测试 资料在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定功能测试 资料同步。')
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
    const l = new Map(local.items.map(i => [keyOf(i), i])), r = new Map(remote.items.map(i => [keyOf(i), i])), bases = new Map(this.state.bases.map(i => [keyOf(i), i]))
    const push = [], pull = [], conflicts = [], unchanged = []
    const issue = (key, reason) => { const a = l.get(key), b = r.get(key); const old = conflicts.find(c => c.key === key); if (old) { old.reason = reason; old.canResolve = false } else conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason, canResolve: false }) }
    for (const key of new Set([...l.keys(), ...r.keys(), ...bases.keys()])) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      if ([a, b].some(i => i && hasProjectLinks(i))) { issue(key, '正文含本端项目或文件链接，请先显式迁移并核对引用；该资料暂停同步。'); continue }
      if (same(a, b)) { unchanged.push(content(a)); continue }
      if (base && (!a || !b)) { issue(key, '一端资料已删除或改名，请核对；不会自动删除或重新创建。'); continue }
      if (a && b && a.name !== b.name) { issue(key, '资料名称大小写不同，请先在两端统一名称。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) { conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一资料在两端均已修改。' : '两端已有同名资料内容不同。', canResolve: true }); continue }
      const side = !b || (a && base && !same(a, base)) ? push : pull; side.push(content(side === push ? a : b))
    }
    // Propagate blocked references through up to 16 directory levels, requirements, cases and plans.
    for (let pass = 0; pass < 20; pass++) {
      for (const item of [...push, ...pull, ...conflicts.filter(c => c.canResolve).flatMap(c => [c.local, c.remote])]) {
        const key = keyOf(item), deps = dependencies(item)
        if (deps.some(d => {
          const dep = keyOf(d), a = l.get(dep), b = r.get(dep)
          return conflicts.some(c => c.key === dep) || (bases.has(dep) && (!a || !b)) || (a && b && a.name !== b.name)
        })) issue(key, '引用的父目录、需求或用例需要先核对；相关资料暂停同步。')

      }
    }
    const blocked = new Set(conflicts.map(c => c.key))
    const uploads = push.filter(i => !blocked.has(keyOf(i))), downloads = pull.filter(i => !blocked.has(keyOf(i)))
    return { push: uploads, pull: downloads, conflicts, unchanged, upload: uploads.length, download: downloads.length, conflictCount: conflicts.length, excludedExecutions: local.excludedExecutions + remote.excludedExecutions, excludedFiles: local.excludedFiles + remote.excludedFiles }
  }
  async send(side, items, expectedRevision, b) {
    if (!items.length) return
    const next = clone(this.state); next.pending = { side, request: { requestKey: crypto.randomUUID(), expectedUserId: side === 'local' ? b.localActorId : b.actor.id,
      expectedRevision, operations: items.map(item => ({ operationId: crypto.randomUUID(), item: this.fileMapper.physical(content(item), side) })) } }
    await this.save(next); await this.recoverPending(b)
  }
  async recoverPending(b) {
    const p = this.state.pending; if (!p) return
    await this.actors(b); const csrf = await this[p.side]('/api/v1/auth/csrf', undefined, b); await this.guard(b)
    const project = p.side === 'local' ? b.source.id : b.target.id
    let receipt
    try { receipt = await this[p.side](`/api/v1/projects/${project}/functional/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('功能测试 同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('功能测试 同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('功能测试 同步回执内容不匹配。')
      const key = keyOf(op.item); next.bases = next.bases.filter(i => keyOf(i) !== key); next.bases.push(content(this.fileMapper.logical(op.item, p.side)))
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
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前资料冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!this.changes(l, r).conflicts.find(c => c.key === key)?.canResolve || !same(a, c.local) || !same(other, c.remote)) throw new Error('冲突资料已变化，请重新同步并核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
  }
}
module.exports = { FunctionalSync, FunctionalSyncStore, validateState, validateItem, keyOf, empty }
