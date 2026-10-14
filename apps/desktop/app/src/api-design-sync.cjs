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
const kinds = ['CATALOG', 'DEFINITION', 'VERSION', 'CASE']
const keyOf = item => JSON.stringify([item.kind, item.name.toLowerCase()])
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  return value
}
const content = item => ({ kind: item.kind, name: item.name, definition: stable(item.definition) })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null, identities: { local: {}, remote: {} } })
const validName = (v, max = 4096) => typeof v === 'string' && v.length > 0 && v === v.trim() && v.length <= max
const validKey = key => { try { const pair = JSON.parse(key); return Array.isArray(pair) && pair.length === 2 && kinds.includes(pair[0]) && validName(pair[1]) && pair[1] === pair[1].toLowerCase() && JSON.stringify(pair) === key } catch { return false } }
function capacity(items) {
  const counts = new Map()
  for (const i of items) { validateItem(i); counts.set(i.kind, (counts.get(i.kind) || 0) + 1) }
  if ([...counts.values()].reduce((n, value) => n + value, 0) > 300) throw new Error('合并后接口设计资料超过 300 条，请先核对。')
}
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i])); capacity([...merged.values()])
  const largest = new Map()
  for (const i of [...local.items, ...remote.items]) { const encoded = JSON.stringify(content(i)), key = keyOf(i); if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded) }
  if ([...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024) > 8 * 1024 * 1024) throw new Error('合并后接口设计资料超过 8 MiB 同步范围，请先缩减资料。')
}
function pathParts(name) {
  let parts; try { parts = JSON.parse(name) } catch { throw new Error('接口目录路径无效。') }
  if (!Array.isArray(parts) || !parts.length || parts.length > 32 || parts.some(part => !validName(part, 160)) || JSON.stringify(parts) !== name || name.length > 4096) throw new Error('接口目录路径无效。')
  return parts
}
function route(name) {
  const match = /^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) (\/[^ \r\n]{0,699})$/.exec(name)
  if (!match) throw new Error('接口方法或路径无效。')
  return { method: match[1], path: match[2] }
}
function child(name) {
  const split = name.indexOf('\n')
  if (split < 1 || name.indexOf('\n', split + 1) !== -1 || name.includes('\r')) throw new Error('接口子资料名称无效。')
  route(name.slice(0, split)); const label = name.slice(split + 1)
  if (!validName(label, 200)) throw new Error('接口子资料名称无效。')
  return { parent: name.slice(0, split), label }
}
function dependencies(item) {
  if (item.kind === 'CATALOG') { const parts = pathParts(item.name); return parts.length > 1 ? [{ kind: 'CATALOG', name: JSON.stringify(parts.slice(0, -1)) }] : [] }
  if (item.kind === 'DEFINITION') return [{ kind: 'CATALOG', name: item.definition.catalogPath }, { kind: 'VERSION', name: item.name + '\n' + item.definition.currentVersionName }]
  return [{ kind: 'DEFINITION', name: child(item.name).parent }]
}
function references(items) {
  const names = new Set(items.map(item => JSON.stringify([item.kind, item.name])))
  if (items.some(item => dependencies(item).some(dep => !names.has(JSON.stringify([dep.kind, dep.name]))))) throw new Error('接口目录、版本或用例引用缺失，请先核对。')
}
function containsDescriptor(value) {
  if (Array.isArray(value)) return value.some(containsDescriptor)
  if (!value || typeof value !== 'object') return false
  return Object.entries(value).some(([key, part]) => {
    const normalized = key.replace(/[_-]/g, '').toLowerCase()
    return (['descriptorid', 'grpcdescriptorid'].includes(normalized) && part !== null && part !== '') || containsDescriptor(part)
  })
}
function unsupported(item) { return blocked(item) || (item.kind === 'CASE' && containsDescriptor(item.definition.requestSnapshot)) }
function validateItem(item) {
  const fail = () => { throw new Error('接口设计同步资料无效，请核对字段与引用。') }
  const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
  const text = (value, max) => typeof value === 'string' && value === value.trim() && value.length <= max
  if (!exact(item, ['kind', 'name', 'definition']) || !kinds.includes(item.kind)) fail()
  const d = item.definition
  if (item.kind === 'CATALOG') {
    pathParts(item.name)
    if (!exact(d, ['sortOrder']) || !Number.isInteger(d.sortOrder) || d.sortOrder < 0 || d.sortOrder > 9999) fail()
  } else if (item.kind === 'DEFINITION') {
    route(item.name)
    if (!exact(d, ['catalogPath', 'displayName', 'currentVersionName']) || !validName(d.displayName, 200) || !/^v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?$/.test(d.currentVersionName)) fail()
    pathParts(d.catalogPath)
  } else if (item.kind === 'VERSION') {
    const value = child(item.name)
    if (!/^v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?$/.test(value.label)
      || !exact(d, ['summary', 'description', 'headers', 'query', 'pathParameters', 'requestBody', 'responseBody', 'exampleResponse', 'status'])
      || !text(d.summary, 500) || !text(d.description, 20000) || !text(d.requestBody, 2000000) || !text(d.responseBody, 2000000)
      || !['DRAFT', 'PUBLISHED'].includes(d.status) || !exact(d.exampleResponse, Object.keys(d.exampleResponse || {}))) fail()
    for (const field of ['headers', 'query', 'pathParameters']) {
      if (!Array.isArray(d[field]) || d[field].length > 200 || d[field].some(parameter => !exact(parameter, ['name', 'type', 'required', 'example', 'description'])
        || !validName(parameter.name, 120) || !validName(parameter.type, 40) || typeof parameter.required !== 'boolean'
        || !text(parameter.example, 1000) || !text(parameter.description, 2000))) fail()
    }
  } else {
    const value = child(item.name)
    if (!validName(value.label, 200) || !exact(d, ['environmentCode', 'requestSnapshot', 'expectedStatus', 'responseValidation', 'savedResponseBody'])
      || (d.environmentCode !== null && (typeof d.environmentCode !== 'string' || !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(d.environmentCode)))
      || !d.requestSnapshot || typeof d.requestSnapshot !== 'object' || Array.isArray(d.requestSnapshot)
      || (d.expectedStatus !== null && (!Number.isInteger(d.expectedStatus) || d.expectedStatus < 100 || d.expectedStatus > 599))
      || !text(d.responseValidation, 20000) || typeof d.savedResponseBody !== 'string' || d.savedResponseBody.length > 1000000) fail()
  }
  return item
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 300 || !Array.isArray(state.conflicts) || state.conflicts.length > 600)
    throw new Error('接口设计 资料同步记录损坏，请保留现有数据。')
  const b = state.binding
  for (const side of ['local', 'remote']) {
    const identities = state.identities?.[side]
    if (!identities || typeof identities !== 'object' || Array.isArray(identities) || Object.keys(identities).length > 300
      || Object.entries(identities).some(([key, value]) => {
        const split = key.indexOf('\n'), kind = key.slice(0, split), name = key.slice(split + 1)
        return !id(value) || split < 1 || !kinds.includes(kind) || !name || name !== name.toLowerCase()
      }))
      throw new Error('接口设计同步引用标识无效。')
  }
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('接口设计 资料同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的资料不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('接口设计 资料同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean'
      || (c.reviewAfterMissing !== undefined && typeof c.reviewAfterMissing !== 'boolean')) throw new Error('接口设计 资料同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('接口设计 资料同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name)) throw new Error('接口设计 资料同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 300) throw new Error('接口设计 资料同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('接口设计 资料同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class ApiDesignSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'api-design-sync.bin', validate: validateState, empty,
    label: '接口设计 资料', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class ApiDesignSync {
  constructor({ store, local, remote, guard, fileLinkContext, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, fileLinkContext, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，接口设计 资料同步已停止；请切回绑定账号。')
    this.fileMapper = new ProjectFileLinks(b, await this.fileLinkContext?.(b))
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const [snapshot, environments] = await Promise.all([
      this[side](`/api/v1/projects/${project}/api-design/sync?expectedUserId=${actor}`, undefined, b),
      this[side](`/api/v1/projects/${project}/environments`, undefined, b),
    ])
    if (snapshot?.version !== 1 || snapshot.scope !== 'API_DESIGN_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 300
      || !snapshot.itemIds || typeof snapshot.itemIds !== 'object' || Array.isArray(snapshot.itemIds))
      throw new Error('服务不支持当前接口设计 资料同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('接口设计 资料存在同名或大小写重复，请先统一名称。'); keys.add(key) }
    capacity(snapshot.items); references(snapshot.items)
    if (Object.keys(snapshot.itemIds).length !== snapshot.items.length || snapshot.items.some(item => snapshot.itemIds[item.kind + '\n' + item.name.toLowerCase()] === undefined)
      || Object.values(snapshot.itemIds).some(value => !id(value))) throw new Error('接口设计资料引用标识不完整。')
    if (!Array.isArray(environments) || environments.some(value => typeof value.environmentCode !== 'string' || typeof value.enabled !== 'boolean'))
      throw new Error('目标环境列表不完整，请先核对环境服务。')
    return { ...snapshot, items: snapshot.items.map(item => this.fileMapper.logical(item, side)),
      enabledEnvironments: new Set(environments.filter(value => value.enabled).map(value => value.environmentCode)) }
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有接口设计 资料同步绑定，请先暂停并解除后再绑定其他项目。')
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
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('接口设计 资料在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定接口设计 资料同步。')
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
    const l = new Map(local.items.map(item => [keyOf(item), item])), r = new Map(remote.items.map(item => [keyOf(item), item]))
    const bases = new Map(this.state.bases.map(item => [keyOf(item), item]))
    const push = [], pull = [], conflicts = [], unchanged = []
    const issue = (key, reason, reviewAfterMissing = false) => {
      const a = l.get(key), b = r.get(key)
      conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason,
        canResolve: Boolean(reviewAfterMissing && a && b && (a.kind !== 'VERSION' || same(a, b))), reviewAfterMissing })
    }
    for (const key of new Set([...l.keys(), ...r.keys(), ...bases.keys()])) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      const prior = this.state.conflicts.find(value => value.key === key)
      if ([a, b].some(item => item && unsupported(item))) { issue(key, '资料包含尚未确认的项目文件链接或 gRPC 描述符引用；该资料暂停同步。'); continue }
      const recreated = [['local', a, local], ['remote', b, remote]].some(([side, item, snapshot]) => item && Object.entries(this.state.identities[side])
        .some(([before, identity]) => before !== item.kind + '\n' + item.name.toLowerCase() && identity === snapshot.itemIds[item.kind + '\n' + item.name.toLowerCase()]
          || before === item.kind + '\n' + item.name.toLowerCase() && identity !== snapshot.itemIds[before]))
      if (recreated) { issue(key, '已有接口资料发生改名或同名重建，请先核对对应关系。', true); continue }
      if (base && (!a || !b)) { issue(key, '一端资料已删除或改名，请核对；不会自动删除或重新创建。', true); continue }
      if (prior?.reviewAfterMissing && a && b) { issue(key, '缺失或重建的资料已出现，请先人工核对。', true); continue }
      if (same(a, b)) { unchanged.push(content(a)); continue }
      if (a && b && a.name !== b.name) { issue(key, '接口资料名称大小写不同，请先在两端统一。'); continue }
      if (a && b && a.kind === 'VERSION') {
        const comparable = item => ({ ...content(item), definition: stable({ ...item.definition, status: 'DRAFT' }) })
        if (JSON.stringify(comparable(a)) === JSON.stringify(comparable(b)) && a.definition.status !== b.definition.status
          && (!base || base.definition.status === 'DRAFT')) {
          const destination = a.definition.status === 'PUBLISHED' ? push : pull
          destination.push(content(a.definition.status === 'PUBLISHED' ? a : b)); continue
        }
        if (base && same(a, base) !== same(b, base)) {
          const chosen = same(a, base) ? b : a
          const destination = same(a, base) ? pull : push
          if (chosen.definition.status === 'DRAFT' && (a.definition.status === 'PUBLISHED' || b.definition.status === 'PUBLISHED')) {
            issue(key, '同名版本内容与发布状态均已变化，请选择保留哪端内容；已发布状态不会撤销。')
            conflicts.at(-1).canResolve = true
          } else destination.push(content(chosen))
          continue
        }
        conflicts.push({ key, local: content(a), remote: content(b), reason: '同名版本内容不同，请选择保留哪端内容；已发布状态不会撤销。', canResolve: true }); continue
      }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) {
        conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一接口资料在两端均已修改。' : '两端已有同名资料但内容不同。', canResolve: true }); continue
      }
      const destination = !b || (a && base && !same(a, base)) ? push : pull
      destination.push(content(destination === push ? a : b))
    }
    for (const [items, target] of [[push, remote], [pull, local]]) {
      for (const item of items) {
        const code = item.kind === 'CASE' ? item.definition.environmentCode : null
        if (code && !target.enabledEnvironments.has(code))
          issue(keyOf(item), `目标项目缺少已启用的环境 ${code}；该接口用例暂停同步。`)
      }
    }
    for (let pass = 0; pass < 36; pass++) {
      let added = false
      for (const item of [...push, ...pull, ...conflicts.filter(conflict => conflict.canResolve).flatMap(conflict => [conflict.local, conflict.remote])]) {
        const key = keyOf(item)
        if (conflicts.some(conflict => conflict.key === key)) continue
        if (dependencies(item).some(dependency => {
          const dep = keyOf(dependency), a = l.get(dep), b = r.get(dep)
          return conflicts.some(conflict => conflict.key === dep) || (bases.has(dep) && (!a || !b)) || (a && b && a.name !== b.name)
        })) { issue(key, '引用的接口目录、定义或当前版本需要先核对；相关资料暂停同步。'); added = true }
      }
      if (!added) break
    }
    const blockedKeys = new Set(conflicts.map(conflict => conflict.key))
    const uploads = push.filter(item => !blockedKeys.has(keyOf(item))), downloads = pull.filter(item => !blockedKeys.has(keyOf(item)))
    return { push: uploads, pull: downloads, conflicts, unchanged, upload: uploads.length, download: downloads.length, conflictCount: conflicts.length }
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
    try { receipt = await this[p.side](`/api/v1/projects/${project}/api-design/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('接口设计 同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('接口设计 同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('接口设计 同步回执内容不匹配。')
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
      const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
      await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds }, lastSyncedAt: this.now() })
    } finally { this.busy = false }
  }
  async resolve(key, choice) {
    await this.load(); const b = this.state.binding, c = this.state.conflicts.find(c => c.key === key)
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前资料冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!this.changes(l, r).conflicts.find(c => c.key === key)?.canResolve || !same(a, c.local) || !same(other, c.remote)) throw new Error('冲突资料已变化，请重新同步并核对后选择。')
    const selected = content(choice === 'local' ? a : other)
    if (selected.kind === 'VERSION' && (a.definition.status === 'PUBLISHED' || other.definition.status === 'PUBLISHED'))
      selected.definition.status = 'PUBLISHED'
    await this.send(choice === 'local' ? 'remote' : 'local', [selected], choice === 'local' ? r.revision : l.revision, b)
    if (selected.kind === 'VERSION' && !same(selected, choice === 'local' ? a : other))
      await this.send(choice, [selected], (await this.rows(choice, b)).revision, b)
    const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds } })
  }
}
module.exports = { ApiDesignSync, ApiDesignSyncStore, validateState, validateItem, keyOf, empty }
