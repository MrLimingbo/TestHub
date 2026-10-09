'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const MiB = 1024 * 1024
const collections = ['catalogs', 'definitions', 'versions', 'cases']
// Only this exact export route may receive the larger file snapshot envelope.
function apiTransferResponseBudget(route) {
  return /^\/api\/v1\/projects\/[A-Z0-9]{26}\/api-design\/transfer\?includeFiles=true(?:&includeGrpc=true)?(?:&includeHistory=true)?$/.test(route) ? 33 * MiB : 9 * MiB
}
class ApiTransfer {
  constructor({ local, remote, current, now = Date.now }) { Object.assign(this, { local, remote, current, now }) }
  async context() {
    const actor = await this.remote('/api/v1/auth/me')
    if (!actor?.id) throw new Error('请先登录账号服务。')
    const local = await this.local('/api/v1/access/context'), remote = await this.remote('/api/v1/access/context')
    const projects = value => value.organizations.flatMap(org => (value.projects[org.id] || []).map(project => ({ id: project.id, name: project.name, organization: org.name })))
    return { actor: { id: actor.id, name: actor.displayName, account: actor.account || actor.email }, sourceProjects: projects(local), targetProjects: projects(remote) }
  }
  async prepare(sourceId, targetId, includeFiles = false, includeGrpc = false, includeHistory = false) {
    this.plan = undefined
    if (typeof includeFiles !== 'boolean' || typeof includeGrpc !== 'boolean' || typeof includeHistory !== 'boolean') throw new Error('请选择有效的迁移范围。')
    if (![sourceId, targetId].every(value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value))) throw new Error('请选择有效的来源项目和目标项目。')
    const endpoint = this.current(), context = await this.context()
    const source = context.sourceProjects.find(item => item.id === sourceId), target = context.targetProjects.find(item => item.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取项目。')
    const capabilities = await this.remote(`/api/v1/projects/${targetId}/api-design/transfer/capabilities`)
    const maxBytes = includeFiles ? 32 * MiB : 8 * MiB
    if (![1, 2, 3].includes(capabilities?.version) || capabilities.scope !== 'API_DEFINITIONS' || !Array.isArray(capabilities.versions) || !capabilities.versions.includes(includeHistory ? 3 : includeGrpc ? 2 : 1)
      || !Number.isSafeInteger(capabilities.maxBytes) || capabilities.maxBytes < 1
      || (includeFiles && (!Number.isSafeInteger(capabilities.fileSnapshotMaxBytes) || capabilities.fileSnapshotMaxBytes < 1))) throw new Error('目标服务不支持当前接口迁移，请更新服务后重试。')
    const snapshot = await this.local(`/api/v1/projects/${sourceId}/api-design/transfer?includeFiles=${includeFiles}${includeGrpc ? '&includeGrpc=true' : ''}${includeHistory ? '&includeHistory=true' : ''}`)
    if (snapshot?.version !== (includeHistory ? 3 : includeGrpc ? 2 : 1) || snapshot.sourceProjectId !== sourceId || collections.some(key => !Array.isArray(snapshot[key]) || snapshot[key].some(item => !item || typeof item !== 'object' || Array.isArray(item)))
      || Object.keys(snapshot).some(key => !['version', 'sourceProjectId', ...collections, 'files', 'grpcDescriptors', 'debugHistory'].includes(key))) throw new Error('接口快照无效或版本不兼容。')
    if (includeGrpc ? !Array.isArray(snapshot.grpcDescriptors) || snapshot.grpcDescriptors.length > 20 : snapshot.grpcDescriptors != null) throw new Error('gRPC 描述符资料与所选范围不一致。')
    let descriptorBytes = 0
    if (includeGrpc) for (const descriptor of snapshot.grpcDescriptors) {
      if (!descriptor || typeof descriptor.content !== 'string' || descriptor.content.length > 2 * MiB) throw new Error('gRPC 描述符内容无效。')
      const bytes = Buffer.from(descriptor.content, 'base64')
      descriptorBytes += bytes.length
      if (!bytes.length || bytes.length > MiB || descriptorBytes > 4 * MiB || bytes.toString('base64') !== descriptor.content
        || crypto.createHash('sha256').update(bytes).digest('hex') !== descriptor.sha256) throw new Error('gRPC 描述符大小或 SHA-256 校验失败。')
    }
    if (includeHistory ? !Array.isArray(snapshot.debugHistory) || snapshot.debugHistory.length > 5000 || snapshot.debugHistory.some(h => !h || typeof h !== 'object' || Array.isArray(h)) : snapshot.debugHistory != null) throw new Error('调试历史与所选范围不一致。')
    const serialized = JSON.stringify(snapshot)
    if (Buffer.byteLength(serialized) > Math.min(maxBytes, includeFiles ? capabilities.fileSnapshotMaxBytes : capabilities.maxBytes)) throw new Error(`本次迁移资料超过 ${includeFiles ? 32 : 8} MiB 或目标服务大小限制。`)
    const files = snapshot.files
    if (includeFiles ? !files || files.projectId !== sourceId || !Array.isArray(files.items) || files.items.length > 100 : files != null) throw new Error('项目文件资料不完整或超出所选范围。')
    let fileBytes = 0
    if (files) for (const file of files.items) {
      if (!file || !Number.isSafeInteger(file.size) || file.size < 1 || file.size > 20 * MiB || typeof file.content !== 'string' || file.content.length > 28 * MiB) throw new Error('项目文件大小或内容无效。')
      const bytes = Buffer.from(file.content, 'base64')
      if (bytes.length !== file.size || bytes.toString('base64') !== file.content || crypto.createHash('sha256').update(bytes).digest('hex') !== file.sha256) throw new Error('项目文件大小或 SHA-256 校验失败。')
      fileBytes += file.size
      if (fileBytes > 20 * MiB) throw new Error('项目文件合计不能超过 20 MiB。')
    }
    const counts = Object.fromEntries(collections.map(key => [key, snapshot[key].length]))
    if (Object.values(counts).reduce((sum, value) => sum + value, 0) + (files?.items.length || 0) + (snapshot.grpcDescriptors?.length || 0) + (snapshot.debugHistory?.length || 0) === 0) throw new Error('本地项目没有可迁移的接口资料。')
    if (includeHistory) counts.debugHistory = snapshot.debugHistory.length
    if (includeGrpc) counts.grpcDescriptors = snapshot.grpcDescriptors.length
    if (files) counts.files = { items: files.items.length, bytes: fileBytes }
    const actor = await this.remote('/api/v1/auth/me')
    if (actor.id !== context.actor.id || endpoint !== this.current()) throw new Error('账号或服务已变化，请重新预览。')
    const token = crypto.randomUUID()
    this.plan = { token, snapshot: JSON.parse(serialized), source, target, actor: context.actor, endpoint, expires: this.now() + 10 * 60000, requestKey: crypto.randomUUID(), counts }
    return { token, source, target, actor: context.actor, counts }
  }
  validPlan(token) {
    const plan = this.plan
    if (!plan || plan.token !== token || plan.expires <= this.now() || plan.endpoint !== this.current()) {
      this.plan = undefined
      throw new Error('预览已失效，请重新预览。')
    }
    return plan
  }
  async commit(token) {
    const plan = this.validPlan(token)
    if (this.busy) throw new Error('迁移正在提交，请等待结果。')
    this.busy = true
    try {
      const actor = await this.remote('/api/v1/auth/me')
      if (actor.id !== plan.actor.id) { this.plan = undefined; throw new Error('账号已变化，请重新预览。') }
      const csrf = await this.remote('/api/v1/auth/csrf')
      this.validPlan(token)
      const result = await this.remote(`/api/v1/projects/${plan.target.id}/api-design/transfer`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ requestKey: plan.requestKey, expectedUserId: plan.actor.id, snapshot: plan.snapshot }),
      })
      this.plan = undefined
      return result
    } finally { this.busy = false }
  }
}
module.exports = { ApiTransfer, apiTransferResponseBudget }
