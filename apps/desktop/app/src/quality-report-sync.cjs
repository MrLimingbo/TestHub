'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = v => globalThis.structuredClone(v)
const id = v => typeof v === 'string' && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(v)
const digest = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const object = v => v && typeof v === 'object' && !Array.isArray(v)
const exact = (v, fields) => object(v) && Object.keys(v).length === fields.length && fields.every(f => Object.hasOwn(v, f))
const name = v => typeof v === 'string' && v.trim() === v && v.length > 0 && v.length <= 160
const stamp = v => Number.isSafeInteger(v) && v >= 0
const keyOf = i => 'SCHEDULE\n' + i.name.toLowerCase()
const validKey = v => typeof v === 'string' && v.startsWith('SCHEDULE\n') && name(v.slice(9)) && v.slice(9).toLowerCase() === v.slice(9)
function stable(v) { return Array.isArray(v) ? v.map(stable) : object(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, stable(v[k])])) : v }
const same = (a, b) => Boolean(a && b) && JSON.stringify(stable(a)) === JSON.stringify(stable(b))
function validateItem(i) {
  if (!exact(i, ['kind', 'name', 'definition']) || i.kind !== 'SCHEDULE' || !name(i.name)) throw new Error('质量报告计划类型或名称无效。')
  const d = i.definition
  if (!exact(d, ['projectIds', 'cronExpression', 'timezone', 'periodDays']) || !Array.isArray(d.projectIds) || !d.projectIds.length || d.projectIds.length > 50 || d.projectIds.some(p => !id(p)) || new Set(d.projectIds).size !== d.projectIds.length
    || !name(d.cronExpression) || typeof d.timezone !== 'string' || !d.timezone || d.timezone.trim() !== d.timezone || d.timezone.length > 64 || !Number.isInteger(d.periodDays) || d.periodDays < 1 || d.periodDays > 90) throw new Error('质量报告计划字段或项目范围无效。')
  return i
}
function capacity(items) {
  if (!Array.isArray(items) || items.length > 1000 || Buffer.byteLength(JSON.stringify(items)) > 4 * 1024 * 1024) throw new Error('质量报告计划最多 1000 条、4 MiB，不会截断。')
  items.forEach(validateItem)
}
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], identities: { local: {}, remote: {} }, lastSyncedAt: null, remaining: 0 })
function links(value, source, target) {
  if (!Array.isArray(value) || !value.length || value.length > 1000 || !value.some(p => p.localId === source && p.remoteId === target)
    || value.some(p => !exact(p, ['localId', 'remoteId']) || !id(p.localId) || !id(p.remoteId)) || new Set(value.map(p => p.localId)).size !== value.length || new Set(value.map(p => p.remoteId)).size !== value.length) throw new Error('请逐一确认项目对应关系，不允许重复或跨用绑定项目。')
  if (value.some(p => p.localId === source && p.remoteId !== target || p.remoteId === target && p.localId !== source)) throw new Error('绑定项目对应关系不同。')
  return value
}
function validateState(s) {
  if (s?.version !== 1 || !Array.isArray(s.conflicts) || s.conflicts.length > 2000 || !stamp(s.remaining) || s.remaining > 1000) throw new Error('质量报告同步状态损坏。')
  capacity(s.bases)
  if (new Set(s.bases.map(keyOf)).size !== s.bases.length) throw new Error('质量报告基线重复。')
  for (const side of ['local', 'remote']) if (!object(s.identities?.[side]) || Object.keys(s.identities[side]).length > 1000 || Object.entries(s.identities[side]).some(([k, v]) => !validKey(k) || !id(v))) throw new Error('质量报告同步标识无效。')
  const b = s.binding
  if (b) {
    if (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id) || b.confirmed !== true || typeof b.enabled !== 'boolean' || b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean') throw new Error('质量报告绑定无效。')
    links(b.projectLinks, b.source.id, b.target.id)
  } else if (s.bases.length || s.pending || s.conflicts.length || s.remaining) throw new Error('未绑定不能保留同步请求。')
  const keys = new Set()
  for (const c of s.conflicts) {
    if (!validKey(c.key) || keys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean' || typeof c.reviewAfterMissing !== 'boolean') throw new Error('质量报告冲突记录无效。')
    keys.add(c.key)
    for (const side of ['local', 'remote']) if (c[side] !== null) { validateItem(c[side]); if (keyOf(c[side]) !== c.key || !id(c[side + 'Id']) || !stamp(c[side + 'Version'])) throw new Error('冲突来源不匹配。') }
  }
  if (s.pending) {
    const p = s.pending, r = p.request
    if (!b || !['local', 'remote'].includes(p.side) || p.source?.side !== (p.side === 'local' ? 'remote' : 'local') || !validKey(p.source.key) || !id(p.source.id) || !stamp(p.source.version) || !uuid(r?.requestKey) || !digest(r.expectedRevision) || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || r.operations.length !== 1 || !uuid(r.operations[0].operationId)) throw new Error('质量报告待确认请求无效。')
    validateItem(p.source.item); validateItem(r.operations[0].item)
    if (keyOf(p.source.item) !== p.source.key || keyOf(r.operations[0].item) !== p.source.key) throw new Error('待确认来源不同。')
  }
  return s
}
class QualityReportSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'quality-report-sync.bin', validate: validateState, empty, label: '质量报告计划', maxPlain: 16 * 1024 * 1024, maxEncrypted: 20 * 1024 * 1024 }) }
}
class QualityReportSync {
  constructor({ store, local, remote, guard, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(s) { validateState(s); await this.store.write(s); this.state = s }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt, remaining: this.state.remaining } }
  async actors(b) {
    await this.guard(b)
    const [l, r] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (l.id !== b.localActorId || r.id !== b.actor.id) throw new Error('账号或本地工作区变化，质量报告同步停止。')
  }
  physical(item, side, b) {
    const v = clone(item)
    v.definition.projectIds = item.definition.projectIds.map(p => {
      const link = b.projectLinks.find(l => l.localId === p); if (!link) throw new Error('计划引用项目尚未配对。')
      return side === 'local' ? link.localId : link.remoteId
    })
    return v
  }
  async rows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const raw = await this[side](`/api/v1/projects/${project}/quality-report/sync?expectedUserId=${actor}`, undefined, b)
    if (raw?.version !== 1 || raw.scope !== 'QUALITY_REPORT_PLAN_SYNC' || raw.projectId !== project || raw.actorId !== actor || !digest(raw.revision) || !object(raw.itemIds) || !object(raw.itemVersions) || !object(raw.blockedKeys) || !Array.isArray(raw.projectIds) || raw.projectIds.length > 1000 || raw.projectIds.some(p => !id(p)) || new Set(raw.projectIds).size !== raw.projectIds.length) throw new Error('质量报告同步服务、项目权限或协议无效。')
    capacity(raw.items)
    const result = { ...raw, items: [], blockedKeys: { ...raw.blockedKeys }, available: [] }, keys = new Set()
    for (const link of b.projectLinks) if (raw.projectIds.includes(side === 'local' ? link.localId : link.remoteId)) result.available.push(link.localId)
    for (const item of raw.items) {
      const key = keyOf(item)
      if (keys.has(key) || Object.hasOwn(raw.blockedKeys, key) || !id(raw.itemIds[key]) || !stamp(raw.itemVersions[key]) || !item.definition.projectIds.includes(project)) throw new Error('计划标识、版本或范围无效。')
      keys.add(key); const v = clone(item), mapped = []
      for (const p of item.definition.projectIds) {
        const link = b.projectLinks.find(l => (side === 'local' ? l.localId : l.remoteId) === p)
        if (!link) { result.blockedKeys[key] = '计划项目尚未逐一配对，请解除绑定并补充项目对应关系。'; break }
        mapped.push(link.localId)
      }
      if (!result.blockedKeys[key]) { v.definition.projectIds = mapped; result.items.push(v) }
    }
    for (const [k, reason] of Object.entries(result.blockedKeys)) if (!validKey(k) || typeof reason !== 'string' || reason.length > 1000 || !id(raw.itemIds[k]) || !stamp(raw.itemVersions[k])) throw new Error('暂停计划标识无效。')
    if (new Set([...Object.keys(raw.itemIds), ...Object.keys(result.blockedKeys)]).size > 1000 || Object.keys(raw.itemIds).some(k => !keys.has(k) && !Object.hasOwn(result.blockedKeys, k))) throw new Error('计划标识集合不完整。')
    return result
  }
  changes(l, r) {
    const a = new Map(l.items.map(i => [keyOf(i), i])), b = new Map(r.items.map(i => [keyOf(i), i])), bases = new Map(this.state.bases.map(i => [keyOf(i), i]))
    const keys = new Set([...a.keys(), ...b.keys(), ...bases.keys(), ...Object.keys(l.blockedKeys), ...Object.keys(r.blockedKeys)])
    if (keys.size > 1000) throw new Error('合并计划超过 1000 条，不会截断。')
    const push = [], pull = [], conflicts = [], unchanged = []
    const issue = (key, reason, review = false, resolve = false) => conflicts.push({ key, local: a.get(key) || null, remote: b.get(key) || null, localId: l.itemIds[key] || null, remoteId: r.itemIds[key] || null, localVersion: l.itemVersions[key] ?? null, remoteVersion: r.itemVersions[key] ?? null, reason, reviewAfterMissing: review, canResolve: resolve })
    for (const key of keys) {
      const x = a.get(key), y = b.get(key), base = bases.get(key), blocked = l.blockedKeys[key] || r.blockedKeys[key]
      if (blocked) { issue(key, blocked); continue }
      const recreated = [['local', x, l], ['remote', y, r]].some(([side, item, rows]) => item && Object.entries(this.state.identities[side]).some(([prior, id]) => prior === key ? id !== rows.itemIds[key] : id === rows.itemIds[key]))
      if (recreated || base && (!x || !y) || this.state.conflicts.some(c => c.key === key && c.reviewAfterMissing)) { issue(key, '计划已删除、改名或重建；不会自动删除或重建，请核对。', true, Boolean(x && y && x.name === y.name)); continue }
      if (same(x, y)) { unchanged.push(x); continue }
      if (x && y && x.name !== y.name) { issue(key, '名称大小写不同，请先统一。'); continue }
      if (x && y && (!base || !same(x, base) && !same(y, base))) { issue(key, '两端同名计划内容不同，请选择保留版本。', false, true); continue }
      const upload = !y || Boolean(x && base && !same(x, base)), item = upload ? x : y, target = upload ? r : l
      if (item.definition.projectIds.some(p => !target.available.includes(p))) issue(key, '目标项目对应关系缺失或无写入权限，请核对。')
      else (upload ? push : pull).push(item)
    }
    return { push, pull, conflicts, unchanged, upload: push.length, download: pull.length, conflictCount: conflicts.length }
  }
  async preview(sourceId, targetId, server, projectLinks, confirmed) {
    await this.load(); this.plan = undefined
    if (this.state.binding || !id(sourceId) || !id(targetId) || confirmed !== true) throw new Error('请单独确认计划同步和项目对应关系，解除旧绑定后重新预览。')
    server = normalizeServerUrl(server); links(projectLinks, sourceId, targetId)
    const [actor, localActor, l, r] = await Promise.all([this.remote('/api/v1/auth/me', undefined, { server }), this.local('/api/v1/auth/me'), this.local('/api/v1/access/context'), this.remote('/api/v1/access/context', undefined, { server })])
    const projects = c => c.organizations.flatMap(o => (c.projects[o.id] || []).map(p => ({ id: p.id, name: p.name, organization: o.name, organizationId: o.id })))
    const locals = projects(l), remotes = projects(r), source = locals.find(p => p.id === sourceId), target = remotes.find(p => p.id === targetId)
    if (!source || !target || projectLinks.some(link => !locals.some(p => p.id === link.localId && p.organizationId === source.organizationId) || !remotes.some(p => p.id === link.remoteId && p.organizationId === target.organizationId))) throw new Error('配对项目必须属于各自绑定项目的组织。')
    const binding = { server, actor: { id: actor.id, account: actor.account || actor.email }, localActorId: localActor.id, source, target, projectLinks: clone(projectLinks), enabled: true, confirmed: true }
    await this.actors(binding); const [localRows, remoteRows] = await Promise.all([this.rows('local', binding), this.rows('remote', binding)]), counts = this.changes(localRows, remoteRows)
    const projectDetail = projectLinks.map(link => `${locals.find(p => p.id === link.localId).name} (${link.localId}) ↔ ${remotes.find(p => p.id === link.remoteId).name} (${link.remoteId})`).join('\n')
    this.plan = { token: crypto.randomUUID(), expires: Date.now() + 600000, binding, localRows, remoteRows, counts, projectDetail }
    return { token: this.plan.token, binding: clone(binding), projectDetail, upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('预览已失效。')
    await this.actors(p.binding); const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('计划或项目权限在确认期间变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding, identities: { local: l.itemIds, remote: r.itemIds } }); this.plan = undefined
  }
  async enabled(value) { await this.load(); if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定。'); if (value && this.state.binding.requiresRebind) throw new Error('工作区恢复过备份，请解除旧绑定并重新预览。'); if (value) await this.actors(this.state.binding); const next = clone(this.state); next.binding.enabled = value; await this.save(next) }
  async unbind() { await this.save(empty()); this.plan = undefined }
  async suspendForRestore() { await this.load(); if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) } }
  async recoverPending(b) {
    const p = this.state.pending; if (!p) return
    await this.actors(b)
    const source = await this.rows(p.source.side, b), item = source.items.find(i => keyOf(i) === p.source.key)
    if (!same(item, p.source.item) || source.itemIds[p.source.key] !== p.source.id || source.itemVersions[p.source.key] !== p.source.version) throw new Error('待确认计划的来源、启用状态或项目权限变化，请核对；保留原请求。')
    const csrf = await this[p.side]('/api/v1/auth/csrf', undefined, b); await this.guard(b)
    const project = p.side === 'local' ? b.source.id : b.target.id
    // A 409 may refer to a deleted or manually re-enabled committed target. Never discard/rebase this UUID.
    const receipt = await this[p.side](`/api/v1/projects/${project}/quality-report/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b)
    const op = p.request.operations[0], result = receipt?.results?.[0]
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== 1 || !exact(result, ['operationId', 'id', 'version', 'item']) || result.operationId !== op.operationId || !id(result.id) || !stamp(result.version) || !same(validateItem(result.item), op.item)) throw new Error('质量报告同步回执不完整，保留原请求。')
    const next = clone(this.state); next.bases = next.bases.filter(i => keyOf(i) !== p.source.key).concat(p.source.item); next.conflicts = next.conflicts.filter(c => c.key !== p.source.key); next.identities[p.side][p.source.key] = result.id; next.pending = null; await this.save(next)
  }
  async send(sourceSide, item, b, initial) {
    const side = sourceSide === 'local' ? 'remote' : 'local', key = keyOf(item), [source, target] = await Promise.all([this.rows(sourceSide, b), this.rows(side, b)])
    const selected = source.items.find(i => keyOf(i) === key), prior = initial[sourceSide], before = initial[side]
    if (!same(selected, item) || source.itemIds[key] !== prior.itemIds[key] || source.itemVersions[key] !== prior.itemVersions[key]) throw new Error('来源计划在读取期间变化。')
    if (target.blockedKeys[key] || target.itemIds[key] !== before.itemIds[key] || target.itemVersions[key] !== before.itemVersions[key] || !sameOptional(target.items.find(i => keyOf(i) === key), before.items.find(i => keyOf(i) === key))) throw new Error('目标计划在读取期间变化。')
    if (item.definition.projectIds.some(p => !target.available.includes(p))) throw new Error('目标项目权限变化。')
    const next = clone(this.state); next.pending = { side, source: { side: sourceSide, key, id: source.itemIds[key], version: source.itemVersions[key], item: clone(item) }, request: { requestKey: crypto.randomUUID(), expectedUserId: side === 'local' ? b.localActorId : b.actor.id, expectedRevision: target.revision, operations: [{ operationId: crypto.randomUUID(), item: this.physical(item, side, b) }] } }
    await this.save(next); await this.recoverPending(b)
  }
  async run() {
    if (this.busy) return; this.busy = true
    try {
      await this.load(); const b = this.state.binding; if (!b?.enabled || b.requiresRebind) return
      await this.actors(b); await this.recoverPending(b)
      const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)]), changes = this.changes(l, r), next = clone(this.state)
      next.conflicts = changes.conflicts; for (const item of changes.unchanged) next.bases = next.bases.filter(i => keyOf(i) !== keyOf(item)).concat(item)
      const work = [...changes.push.map(item => ({ side: 'local', item })), ...changes.pull.map(item => ({ side: 'remote', item }))]; next.remaining = Math.max(0, work.length - 20); await this.save(next)
      for (const v of work.slice(0, 20)) await this.send(v.side, v.item, b, { local: l, remote: r })
      const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
      await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds }, lastSyncedAt: this.now() })
    } finally { this.busy = false }
  }
  async resolve(key, choice) {
    await this.load(); const b = this.state.binding, c = this.state.conflicts.find(v => v.key === key)
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('请先核对当前冲突。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    for (const [side, rows] of [['local', l], ['remote', r]]) if (!same(rows.items.find(i => keyOf(i) === key), c[side]) || rows.itemIds[key] !== c[side + 'Id'] || rows.itemVersions[key] !== c[side + 'Version']) throw new Error('冲突计划或启用状态已变化，请重新核对。')
    if (!this.changes(l, r).conflicts.find(v => v.key === key)?.canResolve) throw new Error('冲突范围已变化。')
    await this.send(choice, c[choice], b, { local: l, remote: r }); const [latestLocal, latestRemote] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    await this.save({ ...clone(this.state), identities: { local: latestLocal.itemIds, remote: latestRemote.itemIds } })
  }
}
function sameOptional(a, b) { return !a && !b || same(a, b) }
module.exports = { QualityReportSync, QualityReportSyncStore, validateItem, validateState, empty, keyOf }
