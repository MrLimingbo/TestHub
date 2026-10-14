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
const keyOf = item => item.name.toLowerCase()
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  return value
}
const content = item => ({ name: item.name, definition: stable(item.definition) })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null, identities: { local: {}, remote: {} }, references: { local: {}, remote: {} } })
const validName = (v, max = 160) => typeof v === 'string' && v.length > 0 && v === v.trim() && v.length <= max
const validKey = key => validName(key) && key === key.toLowerCase()
function capacity(items) {
  items.forEach(validateItem)
  if (items.length > 200) throw new Error('场景资料超过 200 条同步范围。')
}
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i])); capacity([...merged.values()])
  const largest = new Map()
  for (const i of [...local.items, ...remote.items]) { const encoded = JSON.stringify(content(i)), key = keyOf(i); if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded) }
  if ([...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024) > 8 * 1024 * 1024) throw new Error('合并后接口自动化场景超过 8 MiB 同步范围，请先缩减资料。')
}
function unsupported(item) { return blocked(item) }
const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
const object = value => value && typeof value === 'object' && !Array.isArray(value)
function variables(value) {
  return object(value) && Object.keys(value).length <= 100 && Object.entries(value).every(([key, text]) => /^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(key) && typeof text === 'string' && text.length <= 10000)
}
function header(d, named = false) {
  return (!named || validName(d.name)) && typeof d.description === 'string' && d.description === d.description.trim() && d.description.length <= 1200
    && typeof d.environmentCode === 'string' && /^[a-z0-9][a-z0-9_-]{0,63}$/.test(d.environmentCode)
    && ['STOP', 'CONTINUE'].includes(d.failureStrategy) && variables(d.variables)
    && typeof d.preRequestScript === 'string' && d.preRequestScript.length <= 8192 && typeof d.postResponseScript === 'string' && d.postResponseScript.length <= 8192
}
function steps(values, primitive = false) {
  if (!Array.isArray(values) || !values.length || values.length > 300) return false
  const ids = new Set()
  return values.every(step => {
    if (!exact(step, ['id', 'type', 'name', 'config']) || typeof step.id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(step.id) || ids.has(step.id)
      || !validName(step.name) || !object(step.config)) return false
    ids.add(step.id)
    if (['REQUEST', 'PARALLEL', 'EXTRACT', 'ASSERT', 'CONDITION', 'LOOP', 'WAIT'].includes(step.type)) return true
    if (primitive || !['REUSABLE_STEP', 'SUB_SCENARIO'].includes(step.type) || !exact(step.config, ['sourceName', 'revision']) || !validName(step.config.sourceName)) return false
    const r = step.config.revision
    if (step.type === 'REUSABLE_STEP') return exact(r, ['name', 'description', 'steps']) && validName(r.name)
      && typeof r.description === 'string' && r.description === r.description.trim() && r.description.length <= 1200 && steps(r.steps, true) && JSON.stringify(r.steps).length <= 256 * 1024
    return exact(r, ['name', 'description', 'environmentCode', 'failureStrategy', 'steps', 'variables', 'preRequestScript', 'postResponseScript'])
      && header(r, true) && r.preRequestScript === '' && r.postResponseScript === '' && steps(r.steps, true) && JSON.stringify(r.steps).length <= 512 * 1024
  })
}
function validateItem(item) {
  if (!exact(item, ['name', 'definition']) || !validName(item.name) || !exact(item.definition, ['description', 'environmentCode', 'failureStrategy', 'steps', 'variables', 'preRequestScript', 'postResponseScript'])
    || !header(item.definition) || !steps(item.definition.steps) || item.definition.steps.some(step => step.type === 'SUB_SCENARIO' && step.config.sourceName === item.name))
    throw new Error('场景同步字段、固定历史内容或引用不合法。')
  return item
}
function refs(item) {
  return item.definition.steps.filter(step => ['REUSABLE_STEP', 'SUB_SCENARIO'].includes(step.type))
    .map(step => ({ kind: step.type === 'REUSABLE_STEP' ? 'REUSABLE_STEP' : 'SCENARIO', name: step.config.sourceName }))
}
function validateReferences(value) {
  if (!object(value) || Object.keys(value).length > 300 || Object.entries(value).some(([key, value]) => {
    const split = key.indexOf('\n')
    return split < 1 || !['SCENARIO', 'REUSABLE_STEP'].includes(key.slice(0, split)) || !validName(key.slice(split + 1)) || !id(value)
  })) throw new Error('场景引用来源标识不完整。')
}
function destinationIssue(item, target, candidates) {
  if (!target.enabledEnvironments.has(item.definition.environmentCode)) return '目标场景环境缺失或已停用；该场景暂停同步。'
  for (const ref of refs(item)) {
    const key = ref.kind + '\n' + ref.name
    if (target.referenceIds[key] && (ref.kind !== 'SCENARIO' || !Object.hasOwn(target.blockedNames, ref.name))) continue
    if (ref.kind === 'SCENARIO' && candidates.some(value => value.name === ref.name)) continue
    return ref.kind === 'REUSABLE_STEP' ? '目标公共步骤缺失或名称大小写不同，请先核对基础资料同步。' : '目标子场景缺失、失效或暂停，请先核对来源场景。'
  }
}
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 200 || !Array.isArray(state.conflicts) || state.conflicts.length > 400)
    throw new Error('接口自动化场景同步记录损坏，请保留现有数据。')
  const b = state.binding
  for (const side of ['local', 'remote']) {
    const identities = state.identities?.[side]
    if (!object(identities) || Object.keys(identities).length > 200 || Object.entries(identities).some(([key, value]) => !validKey(key) || !id(value)))
      throw new Error('场景同步标识无效。')
    validateReferences(state.references?.[side])
  }
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('接口自动化场景同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的资料不能含有同步记录。')
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('接口自动化场景同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean'
      || (c.reviewAfterMissing !== undefined && typeof c.reviewAfterMissing !== 'boolean')) throw new Error('接口自动化场景同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('接口自动化场景同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name)) throw new Error('接口自动化场景同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 200) throw new Error('接口自动化场景同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('接口自动化场景同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class ApiAutomationScenarioSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'api-automation-scenario-sync.bin', validate: validateState, empty,
    label: '接口自动化场景', maxPlain: 48 * 1024 * 1024, maxEncrypted: 64 * 1024 * 1024 }) }
}
class ApiAutomationScenarioSync {
  constructor({ store, local, remote, guard, fileLinkContext, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, fileLinkContext, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，接口自动化场景同步已停止；请切回绑定账号。')
    this.fileMapper = new ProjectFileLinks(b, await this.fileLinkContext?.(b))
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const [snapshot, environments] = await Promise.all([
      this[side](`/api/v1/projects/${project}/api-automation/scenarios/sync?expectedUserId=${actor}`, undefined, b),
      this[side](`/api/v1/projects/${project}/environments`, undefined, b),
    ])
    if (snapshot?.version !== 1 || snapshot.scope !== 'API_AUTOMATION_SCENARIO_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || !object(snapshot.blockedNames) || !object(snapshot.itemIds))
      throw new Error('服务不支持当前场景同步协议，请更新两端服务。')
    validateReferences(snapshot.referenceIds)
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('场景名称重复，请先统一。'); keys.add(key) }
    for (const [name, reason] of Object.entries(snapshot.blockedNames)) {
      if (!validName(name) || typeof reason !== 'string' || keys.has(name.toLowerCase())) throw new Error('暂停场景标识不合法。')
      keys.add(name.toLowerCase())
    }
    capacity(snapshot.items)
    if (keys.size > 200 || Object.keys(snapshot.itemIds).length !== keys.size || [...keys].some(key => !id(snapshot.itemIds[key]))
      || Object.keys(snapshot.itemIds).some(key => !keys.has(key))) throw new Error('场景同步标识不完整。')
    if (!Array.isArray(environments) || environments.some(value => typeof value.environmentCode !== 'string' || typeof value.enabled !== 'boolean'))
      throw new Error('目标环境列表不完整，请先核对环境服务。')
    return { ...snapshot, items: snapshot.items.map(item => this.fileMapper.logical(item, side)),
      enabledEnvironments: new Set(environments.filter(value => value.enabled).map(value => value.environmentCode)) }
  }
  async preview(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有接口自动化场景同步绑定，请先暂停并解除后再绑定其他项目。')
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
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('接口自动化场景在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定接口自动化场景同步。')
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
    const blocked = (snapshot, key) => Object.entries(snapshot.blockedNames).find(([name]) => name.toLowerCase() === key)?.[1]
    const issue = (key, reason, reviewAfterMissing = false) => {
      const a = l.get(key), b = r.get(key)
      conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason,
        canResolve: Boolean(reviewAfterMissing && a && b && a.name === b.name), reviewAfterMissing })
    }
    const names = new Set([...l.keys(), ...r.keys(), ...bases.keys(), ...Object.keys(local.blockedNames).map(name => name.toLowerCase()), ...Object.keys(remote.blockedNames).map(name => name.toLowerCase())])
    for (const key of names) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      const blockedReason = blocked(local, key) || blocked(remote, key)
      if (blockedReason) { issue(key, blockedReason, Boolean(base)); conflicts.at(-1).canResolve = false; continue }
      if ([a, b].some(item => item && unsupported(item))) { issue(key, '场景或固定历史包含未配对、失效的文件链接，相关场景暂停。'); continue }
      const replaced = [['local', a, local], ['remote', b, remote]].some(([side, item, snapshot]) => item && Object.entries(this.state.identities[side])
        .some(([before, identity]) => before === key ? identity !== snapshot.itemIds[key] : identity === snapshot.itemIds[key]))
      const references = [...(a ? refs(a) : []), ...(b ? refs(b) : [])]
      const referenceReplaced = ['local', 'remote'].some(side => {
        const current = side === 'local' ? local : remote
        return references.some(ref => {
          const key = ref.kind + '\n' + ref.name, identity = current.referenceIds[key]
          return identity && Object.entries(this.state.references[side]).some(([before, id]) => before === key ? id !== identity : id === identity)
        })
      })
      if (replaced || referenceReplaced) { issue(key, '场景或引用来源已改名、同名重建，请人工核对后选择场景内容。', true); continue }
      if (base && (!a || !b)) { issue(key, '一端场景已删除或改名，不会自动删除或重建，请核对。', true); continue }
      if (this.state.conflicts.some(value => value.key === key && value.reviewAfterMissing) && a && b) { issue(key, '缺失或重建资料已恢复，请人工核对后选择。', true); continue }
      if (same(a, b)) { unchanged.push(content(a)); continue }
      if (a && b && a.name !== b.name) { issue(key, '场景名称大小写不同，请先在两端统一。'); continue }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) { conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一场景在两端均已编辑。' : '两端同名场景内容不同。', canResolve: true }); continue }
      const destination = !b || (a && base && !same(a, base)) ? push : pull
      destination.push(content(destination === push ? a : b))
    }
    for (let pass = 0; pass < 201; pass++) {
      let changed = false
      for (const [items, target] of [[push, remote], [pull, local]]) {
        const candidates = items.filter(item => !conflicts.some(value => value.key === keyOf(item)))
        for (const item of candidates) {
          const reason = destinationIssue(item, target, candidates)
          if (reason) { issue(keyOf(item), reason); changed = true }
        }
      }
      if (!changed) break
    }
    const blockedKeys = new Set(conflicts.map(value => value.key))
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
    try { receipt = await this[p.side](`/api/v1/projects/${project}/api-automation/scenarios/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('场景同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('场景同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!same(result.item, op.item)) throw new Error('场景同步回执内容不匹配。')
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
    const reason = destinationIssue(selected, target, [])
    if (reason) throw new Error(reason)
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b)
    const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds }, references: { local: latestLocal.referenceIds, remote: latestRemote.referenceIds } })
  }
}
module.exports = { ApiAutomationScenarioSync, ApiAutomationScenarioSyncStore, validateState, validateItem, keyOf, empty }
