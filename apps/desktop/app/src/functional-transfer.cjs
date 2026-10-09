'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
async function jsonRequest(fetcher, url, options = {}, maxBytes = 9 * 1024 * 1024) {
  const response = await fetcher(url, { ...options, redirect: 'error', signal: globalThis.AbortSignal.timeout(60000) })
  const chunks = []; let length = 0
  for await (const bytes of response.body) {
    length += bytes.length
    if (length > maxBytes) throw new Error('迁移数据超过大小限制，请减少项目中的资料后重试。')
    chunks.push(Buffer.from(bytes))
  }
  let result
  try { result = JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { throw new Error('服务未返回有效迁移数据，请确认服务版本支持本地资料迁移。') }
  if (!response.ok) throw new Error(result.message || `迁移请求失败（HTTP ${response.status}）。`)
  return result.data
}
class FunctionalTransfer {
  constructor({ local, remote, current, now = Date.now }) { this.local = local; this.remote = remote; this.current = current; this.now = now }
  async context() {
    const actor = await this.remote('/api/v1/auth/me')
    const local = await this.local('/api/v1/access/context'), remote = await this.remote('/api/v1/access/context')
    const projects = value => value.organizations.flatMap(org => (value.projects[org.id] || []).map(project => ({ id: project.id, name: project.name, organization: org.name })))
    return { actor: { id: actor.id, name: actor.displayName, account: actor.account || actor.email }, sourceProjects: projects(local), targetProjects: projects(remote) }
  }
  async prepare(sourceId, targetId, includePlans = false, includeGovernance = false, includeDefects = false, includeFiles = false, includeHistory = false, includeProjectVersions = false, includeGovernanceCatalogs = false) {
    this.plan = undefined
    if (typeof includePlans !== 'boolean' || typeof includeGovernance !== 'boolean' || typeof includeDefects !== 'boolean' || typeof includeFiles !== 'boolean' || typeof includeHistory !== 'boolean' || typeof includeProjectVersions !== 'boolean' || typeof includeGovernanceCatalogs !== 'boolean') throw new Error('请选择有效的迁移范围。')
    if (includeHistory && !includePlans) throw new Error('迁移执行历史时请同时选择测试计划。')
    if (![sourceId, targetId].every(value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value))) throw new Error('请选择有效的来源项目和目标项目。')
    const context = await this.context(), source = context.sourceProjects.find(item => item.id === sourceId), target = context.targetProjects.find(item => item.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取项目。')
    const capabilities = await this.remote(`/api/v1/projects/${targetId}/functional/transfer/capabilities`)
    if (capabilities?.version !== 1 || capabilities.scope !== 'FUNCTIONAL_DEFINITIONS') throw new Error('目标服务不支持当前基础资料迁移，请更新服务后重试。')
    if (includeGovernanceCatalogs && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(8))) throw new Error('目标服务尚不支持治理目录迁移，请更新服务，或取消勾选治理目录。')
    if (!includeGovernanceCatalogs && includeProjectVersions && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(7))) throw new Error('目标服务尚不支持项目版本迁移，请更新服务，或取消勾选项目版本。')
    if (!includeGovernanceCatalogs && !includeProjectVersions && includeHistory && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(6))) throw new Error('目标服务尚不支持执行历史迁移，请更新服务，或取消勾选执行历史。')
    if (!includeGovernanceCatalogs && !includeProjectVersions && !includeHistory && includeFiles && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(5))) throw new Error('目标服务尚不支持项目文件迁移，请更新服务，或取消勾选项目文件。')
    if (!includeGovernanceCatalogs && !includeProjectVersions && !includeHistory && includeDefects && !includeFiles && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(4))) throw new Error('目标服务尚不支持缺陷迁移，请更新服务，或取消勾选缺陷。')
    if (!includeGovernanceCatalogs && !includeProjectVersions && !includeHistory && includeGovernance && !includeDefects && !includeFiles && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(3))) throw new Error('目标服务尚不支持标签与字段迁移，请更新服务，或取消勾选标签与字段。')
    if (!includeGovernanceCatalogs && !includeProjectVersions && !includeHistory && includePlans && !includeGovernance && !includeDefects && !includeFiles && (!Array.isArray(capabilities.versions) || !capabilities.versions.includes(2))) throw new Error('目标服务尚不支持测试计划迁移，请更新服务，或取消勾选测试计划。')
    const version = includeGovernanceCatalogs ? 8 : includeProjectVersions ? 7 : includeHistory ? 6 : includeFiles ? 5 : includeDefects ? 4 : includeGovernance ? 3 : includePlans ? 2 : 1
    const query = includeGovernanceCatalogs ? `?version=8&includePlans=${includePlans}&includeGovernance=${includeGovernance}&includeDefects=${includeDefects}&includeFiles=${includeFiles}&includeHistory=${includeHistory}&includeProjectVersions=${includeProjectVersions}&includeGovernanceCatalogs=true` : includeProjectVersions ? `?version=7&includePlans=${includePlans}&includeGovernance=${includeGovernance}&includeDefects=${includeDefects}&includeFiles=${includeFiles}&includeHistory=${includeHistory}&includeProjectVersions=true` : includeHistory ? `?version=6&includePlans=true&includeGovernance=${includeGovernance}&includeDefects=${includeDefects}&includeFiles=${includeFiles}&includeHistory=true` : includeFiles ? `?version=5&includePlans=${includePlans}&includeGovernance=${includeGovernance}&includeDefects=${includeDefects}` : includeDefects ? `?version=4&includePlans=${includePlans}&includeGovernance=${includeGovernance}` : includeGovernance ? `?version=3&includePlans=${includePlans}` : includePlans ? '?version=2' : ''
    const snapshot = await this.local(`/api/v1/projects/${sourceId}/functional/transfer${query}`)
    const governanceCatalogs = Array.isArray(snapshot?.catalogs) ? snapshot.catalogs.filter(item => item?.scope === 'GOVERNANCE') : []
    if (!includeGovernanceCatalogs && governanceCatalogs?.length) throw new Error('治理目录超出所选迁移范围。')
    const projectVersions = snapshot?.projectVersions
    if (includeProjectVersions ? !projectVersions || !Array.isArray(projectVersions.items) || projectVersions.items.length > 5000 : projectVersions != null) throw new Error('项目版本资料不完整或超出所选范围。')
    const history = snapshot?.history
    if (includeHistory ? !history || !Array.isArray(history.runs) || history.runs.length > 1000 || history.runs.some(run => !Array.isArray(run?.cases) || run.cases.some(item => !Array.isArray(item?.steps))) : history != null) throw new Error('执行历史资料不完整或超出所选范围。')
    const historyCounts = history ? { runs: history.runs.length, cases: history.runs.reduce((sum, run) => sum + run.cases.length, 0), steps: history.runs.reduce((sum, run) => sum + run.cases.reduce((total, item) => total + item.steps.length, 0), 0) } : undefined
    if (historyCounts && (historyCounts.cases > 10000 || historyCounts.steps > 50000)) throw new Error('执行历史超过 10000 条用例或 50000 条步骤限制。')
    const files = snapshot?.files
    if (includeFiles ? !files || files.projectId !== sourceId || !Array.isArray(files.items) || files.items.length > 100 : files != null) throw new Error('项目文件资料不完整或超出所选范围。')
    let fileBytes = 0
    if (files) for (const file of files.items) {
      if (!file || !Number.isSafeInteger(file.size) || file.size < 1 || file.size > 20 * 1024 * 1024 || typeof file.content !== 'string' || file.content.length > 28 * 1024 * 1024) throw new Error('项目文件大小或内容无效。')
      const bytes = Buffer.from(file.content, 'base64')
      if (bytes.length !== file.size || bytes.toString('base64') !== file.content || crypto.createHash('sha256').update(bytes).digest('hex') !== file.sha256) throw new Error('项目文件大小或 SHA-256 校验失败。')
      fileBytes += file.size
      if (fileBytes > 20 * 1024 * 1024) throw new Error('项目文件合计不能超过 20 MiB。')
    }
    const defects = snapshot?.defects
    if (includeDefects ? !defects || !Array.isArray(defects.items) || defects.items.some(item => !Array.isArray(item?.links) || !Number.isSafeInteger(item?.omittedExecutionLinks) || item.omittedExecutionLinks < 0) : defects != null) throw new Error('缺陷资料不完整或超出所选范围，请更新本地组件后重试。')
    const governance = snapshot?.governance
    if (includeGovernance ? !governance || !Array.isArray(governance.tags) || !Array.isArray(governance.fields) || !Array.isArray(governance.bindings)
      || governance.bindings.some(item => !Array.isArray(item?.tagIds)) : governance != null) throw new Error('标签与字段资料不完整或超出所选范围，请更新本地组件后重试。')
    if (snapshot?.version !== version || !Array.isArray(snapshot.catalogs) || !Array.isArray(snapshot.requirements) || !Array.isArray(snapshot.cases)
      || (includePlans && !Array.isArray(snapshot.plans)) || (!includePlans && snapshot.plans != null)
      || snapshot.requirements.length + snapshot.cases.length + (snapshot.plans?.length || 0) + (governance?.tags.length || 0) + (governance?.fields.length || 0) + (defects?.items.length || 0) + (files?.items.length || 0) + (projectVersions?.items.length || 0) + (includeGovernanceCatalogs ? snapshot.catalogs.length : 0) === 0) throw new Error('本地项目在所选范围内没有可迁移的资料，或版本不兼容。')
    const maxMiB = includeFiles ? 32 : 8
    if (Buffer.byteLength(JSON.stringify(snapshot)) > maxMiB * 1024 * 1024) throw new Error(`本次迁移资料超过 ${maxMiB} MiB 限制。`)
    const token = crypto.randomUUID()
    this.plan = { token, snapshot, source, target, actor: context.actor, endpoint: this.current(), expires: this.now() + 10 * 60000, requestKey: crypto.randomUUID() }
    const counts = { catalogs: snapshot.catalogs.length, requirements: snapshot.requirements.length, cases: snapshot.cases.length, plans: snapshot.plans?.length || 0 }
    if (governance) counts.governance = { tags: governance.tags.length, fields: governance.fields.length, bindings: governance.bindings.reduce((sum, item) => sum + item.tagIds.length, 0) }
    if (defects) counts.defects = { items: defects.items.length, links: defects.items.reduce((sum, item) => sum + item.links.length, 0), omittedExecutionLinks: defects.items.reduce((sum, item) => sum + item.omittedExecutionLinks, 0) }
    if (includeGovernanceCatalogs) counts.governanceCatalogs = governanceCatalogs.length
    if (projectVersions) counts.projectVersions = projectVersions.items.length
    if (historyCounts) counts.history = historyCounts
    if (files) counts.files = { items: files.items.length, bytes: fileBytes }
    return { token, source, target, actor: context.actor, counts }
  }
  async commit(token) {
    const plan = this.plan
    if (!plan || plan.token !== token || plan.expires < this.now() || plan.endpoint !== this.current()) throw new Error('预览已失效，请重新预览。')
    if (this.busy) throw new Error('迁移正在提交，请等待结果。')
    this.busy = true
    try {
      const actor = await this.remote('/api/v1/auth/me')
      if (actor.id !== plan.actor.id || plan.endpoint !== this.current()) throw new Error('账号或服务已变化，请重新预览。')
      const csrf = await this.remote('/api/v1/auth/csrf')
      const result = await this.remote(`/api/v1/projects/${plan.target.id}/functional/transfer`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ requestKey: plan.requestKey, expectedUserId: plan.actor.id, snapshot: plan.snapshot }),
      })
      this.plan = undefined
      return result
    } finally { this.busy = false }
  }
}
module.exports = { FunctionalTransfer, jsonRequest }
