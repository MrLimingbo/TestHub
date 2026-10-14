'use strict'
const crypto = require('node:crypto')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')

const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const revision = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const empty = () => ({ version: 1, binding: null, links: [], pending: null, conflicts: [], lastSyncedAt: null })
const sameContent = (a, b) => a && b && a.size === b.size && a.sha256 === b.sha256
const validItem = item => item && id(item.id) && id(item.key) && typeof item.name === 'string' && item.name.length > 0 && item.name.length <= 255
  && typeof item.type === 'string' && item.type.length > 0 && item.type.length <= 160 && Number.isSafeInteger(item.size)
  && item.size > 0 && item.size <= 20 * 1024 * 1024 && revision(item.sha256)
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.links) || state.links.length > 1000 || !Array.isArray(state.conflicts) || state.conflicts.length > 1000)
    throw new Error('项目文件同步记录损坏，请保留现有数据。')
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || !Array.isArray(b.pairs) || b.pairs.length > 1000
    || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('项目文件绑定无效。')
  if (!b && (state.links.length || state.pending || state.conflicts.length)) throw new Error('未绑定的项目文件不能保留同步记录。')
  const checkLink = p => p && id(p.key) && id(p.localId) && id(p.remoteId)
  if (state.links.some(p => !checkLink(p)) || new Set(state.links.map(p => p.key)).size !== state.links.length) throw new Error('项目文件对应标识重复或无效。')
  if (b) {
    if (b.pairs.some(p => !checkLink({ key: p.localKey, localId: p.localId, remoteId: p.remoteId }) || !id(p.remoteKey))
      || new Set(b.pairs.map(p => p.localId)).size !== b.pairs.length
      || new Set(b.pairs.map(p => p.remoteId)).size !== b.pairs.length
      || new Set(b.pairs.map(p => p.localKey)).size !== b.pairs.length
      || new Set(b.pairs.map(p => p.remoteKey)).size !== b.pairs.length) throw new Error('已有项目文件配对无效。')
  }
  if (state.conflicts.some(c => !id(c?.key) || typeof c.reason !== 'string' || !c.reason)
    || new Set(state.conflicts.map(c => c.key)).size !== state.conflicts.length) throw new Error('项目文件冲突记录无效。')
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !id(p.sourceId) || !id(p.sourceKey)
      || !uuid(r?.requestKey) || !revision(r.expectedRevision) || !id(r.sourceKey)
      || r.sourceKey !== p.sourceKey || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id)
      || !validItem({ id: p.sourceId, key: p.sourceKey, name: r.name, type: r.type, size: r.size, sha256: r.sha256 })
      || typeof r.content !== 'string' || r.content.length > Math.ceil(20 * 1024 * 1024 / 3) * 4)
      throw new Error('项目文件待发送记录无效。')
  }
  return state
}
class ProjectFileSyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'project-file-sync.bin', validate: validateState, empty,
    label: '项目文件', maxPlain: 34 * 1024 * 1024, maxEncrypted: 42 * 1024 * 1024 }) }
}
class ProjectFileSync {
  constructor({ store, local, remote, localBinary, remoteBinary, guard, now = () => new Date().toISOString() }) {
    Object.assign(this, { store, local, remote, localBinary, remoteBinary, guard, now }); this.busy = false
  }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async linkContext(binding) {
    await this.load()
    const b = this.state.binding
    if (!b?.enabled || b.requiresRebind || b.server !== binding.server || b.localActorId !== binding.localActorId
      || b.actor.id !== binding.actor.id || b.source.id !== binding.source.id || b.target.id !== binding.target.id) return null
    await this.actors(b)
    const [local, remote] = await Promise.all([this.raw('local', b), this.raw('remote', b)])
    const byLocal = new Map(local.items.map(item => [item.id, item]))
    const byRemote = new Map(remote.items.map(item => [item.id, item]))
    const pairs = new Map(b.pairs.map(pair => [pair.localKey, pair]))
    const links = this.state.links.filter(link => {
      const a = byLocal.get(link.localId), other = byRemote.get(link.remoteId), pair = pairs.get(link.key)
      return a?.key === link.key && other && sameContent(a, other)
        && other.key === (pair ? pair.remoteKey : link.key)
        && (!pair || pair.localId === link.localId && pair.remoteId === link.remoteId)
    })
    if (new Set(links.map(link => link.localId)).size !== links.length || new Set(links.map(link => link.remoteId)).size !== links.length)
      throw new Error('项目文件对应关系重复，正文链接同步已停止。')
    return { sourceProject: b.source.id, targetProject: b.target.id, links: clone(links) }
  }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，项目文件同步已停止。')
  }
  async raw(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/files/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'PROJECT_FILE_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 1000
      || snapshot.items.some(item => !validItem(item)) || new Set(snapshot.items.map(item => item.id)).size !== snapshot.items.length
      || new Set(snapshot.items.map(item => item.key)).size !== snapshot.items.length)
      throw new Error('服务不支持当前项目文件同步协议，或文件资料无效。')
    return snapshot
  }
  mapped(side, snapshot, b) {
    if (side === 'local') return snapshot
    const items = snapshot.items.map(item => {
      const pair = b.pairs.find(p => p.remoteId === item.id)
      if (pair && item.key !== pair.remoteKey) throw new Error('已有网页文件来源标识已变化，请重新核对。')
      return pair ? { ...item, key: pair.localKey } : item
    })
    if (new Set(items.map(item => item.key)).size !== items.length) throw new Error('项目文件配对与来源标识冲突，请重新核对。')
    return { ...snapshot, items }
  }
  async pairingContext(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有项目文件同步绑定，请先解除后重新绑定。')
    if (!id(sourceId) || !id(targetId)) throw new Error('请选择有效的本地项目和账号项目。')
    server = normalizeServerUrl(server)
    const [actor, localActor, local, remote] = await Promise.all([this.remote('/api/v1/auth/me', undefined, { server }), this.local('/api/v1/auth/me'),
      this.local('/api/v1/access/context'), this.remote('/api/v1/access/context', undefined, { server })])
    const projects = context => context.organizations.flatMap(org => (context.projects[org.id] || []).map(p => ({ id: p.id, name: p.name, organization: org.name })))
    const source = projects(local).find(p => p.id === sourceId), target = projects(remote).find(p => p.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新选择。')
    const binding = { server, actor: { id: actor.id, name: actor.displayName, account: actor.account || actor.email }, localActorId: localActor.id, source, target, enabled: true, pairs: [] }
    await this.actors(binding)
    const [localRows, remoteRows] = await Promise.all([this.raw('local', binding), this.raw('remote', binding)])
    return { binding, localRows, remoteRows }
  }
  async references(sourceId, targetId, server) {
    const p = await this.pairingContext(sourceId, targetId, server)
    return { local: p.localRows.items, remote: p.remoteRows.items }
  }
  async preview(sourceId, targetId, server, chosen = []) {
    const p = await this.pairingContext(sourceId, targetId, server)
    if (!Array.isArray(chosen) || chosen.length > 1000) throw new Error('项目文件配对数量无效。')
    const pairs = []
    for (const value of chosen) {
      const local = p.localRows.items.find(item => item.id === value?.localFileId)
      const remote = p.remoteRows.items.find(item => item.id === value?.remoteFileId)
      if (!local || !remote || !sameContent(local, remote)) throw new Error('配对文件不存在或内容摘要不同，请重新核对。')
      pairs.push({ localId: local.id, remoteId: remote.id, localKey: local.key, remoteKey: remote.key })
    }
    const binding = { ...p.binding, pairs }
    validateState({ ...empty(), binding })
    const local = this.mapped('local', p.localRows, binding), remote = this.mapped('remote', p.remoteRows, binding)
    const linked = new Set(pairs.map(pair => pair.localKey))
    const localKeys = new Set(local.items.map(item => item.key)), remoteKeys = new Set(remote.items.map(item => item.key))
    const conflictCount = local.items.filter(item => remoteKeys.has(item.key) && !linked.has(item.key)).length
    this.plan = { token: crypto.randomUUID(), binding, localRevision: local.revision, remoteRevision: remote.revision, expires: Date.now() + 600000,
      counts: { upload: local.items.filter(item => !remoteKeys.has(item.key)).length, download: remote.items.filter(item => !localKeys.has(item.key)).length, conflictCount } }
    return { token: this.plan.token, binding: clone(binding), ...this.plan.counts }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [local, remote] = await Promise.all([this.raw('local', p.binding), this.raw('remote', p.binding)])
    if (local.revision !== p.localRevision || remote.revision !== p.remoteRevision) throw new Error('文件在确认期间已变化，请重新预览。')
    const links = p.binding.pairs.map(pair => ({ key: pair.localKey, localId: pair.localId, remoteId: pair.remoteId }))
    await this.save({ ...empty(), binding: p.binding, links }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定项目文件同步。')
    if (value && this.state.binding.requiresRebind) throw new Error('工作区已恢复备份，请解除旧绑定并重新预览。')
    if (value) await this.actors(this.state.binding)
    const next = clone(this.state); next.binding.enabled = value; await this.save(next)
  }
  async unbind() { await this.load(); await this.save(empty()); this.plan = undefined }
  async suspendForRestore() {
    await this.load()
    if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) }
  }
  changes(local, remote) {
    const l = new Map(local.items.map(item => [item.key, item])), r = new Map(remote.items.map(item => [item.key, item]))
    const push = [], pull = [], conflicts = []
    for (const key of new Set([...l.keys(), ...r.keys(), ...this.state.links.map(link => link.key)])) {
      const a = l.get(key), b = r.get(key), link = this.state.links.find(value => value.key === key)
      let reason = ''
      if (link && (!a || !b || a.id !== link.localId || b.id !== link.remoteId)) reason = '已同步文件在一端删除或重建，已暂停该文件；请人工核对，不会自动重建或删除另一端。'
      else if (a && b && !sameContent(a, b)) reason = '两端同来源文件内容不同，已暂停该文件；不会覆盖。'
      else if (a && b && !link) reason = '两端已有相同来源标识的文件，请解除绑定并逐一确认配对。'
      if (reason) conflicts.push({ key, reason, local: a || null, remote: b || null, canResolve: false })
      else if (a && !b) push.push(a)
      else if (!a && b) pull.push(b)
    }
    return { push, pull, conflicts }
  }
  async recoverPending(b) {
    const pending = this.state.pending
    if (!pending) return
    await this.actors(b)
    const csrf = await this[pending.side]('/api/v1/auth/csrf', undefined, b); await this.guard(b)
    const project = pending.side === 'local' ? b.source.id : b.target.id
    let receipt
    try { receipt = await this[pending.side](`/api/v1/projects/${project}/files/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(pending.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== pending.request.requestKey || receipt.sourceKey !== pending.sourceKey || !id(receipt.fileId))
      throw new Error('文件同步回执不完整，将保留原请求安全重试。')
    const link = pending.side === 'remote'
      ? { key: pending.sourceKey, localId: pending.sourceId, remoteId: receipt.fileId }
      : { key: pending.sourceKey, localId: receipt.fileId, remoteId: pending.sourceId }
    const next = clone(this.state)
    next.links = next.links.filter(value => value.key !== link.key).concat(link)
    next.pending = null
    await this.save(next)
  }
  async send(sourceSide, item, targetRevision, b) {
    const sourceProject = sourceSide === 'local' ? b.source.id : b.target.id
    const destination = sourceSide === 'local' ? 'remote' : 'local'
    const bytes = await this[sourceSide + 'Binary'](`/api/v1/projects/${sourceProject}/files/${item.id}/download`, item.size, item.sha256, b)
    await this.actors(b)
    const request = { requestKey: crypto.randomUUID(), expectedUserId: destination === 'local' ? b.localActorId : b.actor.id,
      expectedRevision: targetRevision, sourceKey: item.key, name: item.name, type: item.type, size: item.size,
      sha256: item.sha256, content: bytes.toString('base64') }
    const next = clone(this.state); next.pending = { side: destination, sourceId: item.id, sourceKey: item.key, request }
    await this.save(next); await this.recoverPending(b)
  }
  async run() {
    if (this.busy) return
    this.busy = true
    try {
      await this.load(); const b = this.state.binding
      if (!b?.enabled || b.requiresRebind) return
      await this.actors(b); await this.recoverPending(b)
      for (let n = 0; n < 100; n++) {
        const [localRaw, remoteRaw] = await Promise.all([this.raw('local', b), this.raw('remote', b)])
        const local = this.mapped('local', localRaw, b), remote = this.mapped('remote', remoteRaw, b)
        const changes = this.changes(local, remote)
        const next = clone(this.state); next.conflicts = changes.conflicts; await this.save(next)
        if (changes.push.length) await this.send('local', changes.push[0], remote.revision, b)
        else if (changes.pull.length) await this.send('remote', changes.pull[0], local.revision, b)
        else { await this.save({ ...clone(this.state), lastSyncedAt: this.now() }); return }
      }
      throw new Error('本轮最多同步 100 个项目文件；剩余文件将在下次检查时继续。')
    } finally { this.busy = false }
  }
  async resolve() { throw new Error('项目文件不会自动删除或覆盖；请人工核对后重新绑定。') }
}
module.exports = { ProjectFileSync, ProjectFileSyncStore, validateState, validItem, empty }
