'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const MAX_BYTES = 32 * 1024 * 1024
const projectId = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const uiAutomationTransferResponseBudget = route =>
  /^\/api\/v1\/projects\/[A-Z0-9]{26}\/ui-automation\/transfer$/.test(route) ? 33 * 1024 * 1024 : 9 * 1024 * 1024

class UiAutomationTransfer {
  constructor({ local, remote, current, now = Date.now }) { Object.assign(this, { local, remote, current, now }) }
  async context() {
    const actor = await this.remote('/api/v1/auth/me')
    if (!actor?.id) throw new Error('请先登录账号服务。')
    const local = await this.local('/api/v1/access/context'), remote = await this.remote('/api/v1/access/context')
    const projects = value => value.organizations.flatMap(org => (value.projects[org.id] || []).map(project =>
      ({ id: project.id, name: project.name, organization: org.name })))
    return { actor: { id: actor.id, name: actor.displayName, account: actor.account || actor.email },
      sourceProjects: projects(local), targetProjects: projects(remote) }
  }
  async prepare(sourceId, targetId) {
    this.plan = undefined
    if (!projectId(sourceId) || !projectId(targetId)) throw new Error('请选择有效的来源项目和目标项目。')
    const endpoint = this.current(), context = await this.context()
    const source = context.sourceProjects.find(item => item.id === sourceId), target = context.targetProjects.find(item => item.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取项目。')
    const capabilities = await this.remote(`/api/v1/projects/${targetId}/ui-automation/transfer/capabilities`)
    if (capabilities?.version !== 1 || capabilities.scope !== 'UI_AUTOMATION' ||
      !Number.isSafeInteger(capabilities.maxBytes) || capabilities.maxBytes < 1)
      throw new Error('目标服务不支持Web UI资料迁移，请更新服务后重试。')
    const snapshot = await this.local(`/api/v1/projects/${sourceId}/ui-automation/transfer`)
    const arrays = ['profiles', 'pageObjects', 'scenarios', 'schedules']
    if (snapshot?.version !== 1 || snapshot.sourceProjectId !== sourceId ||
      Object.keys(snapshot).some(key => !['version', 'sourceProjectId', ...arrays].includes(key)) ||
      arrays.some(key => !Array.isArray(snapshot[key]) || snapshot[key].some(item => !item || typeof item !== 'object' || Array.isArray(item))) ||
      snapshot.scenarios.some(item => !Array.isArray(item.steps) || item.steps.some(step => !step || typeof step !== 'object' || Array.isArray(step))))
      throw new Error('Web UI 资料快照无效或版本不兼容。')
    const serialized = JSON.stringify(snapshot)
    if (Buffer.byteLength(serialized) > Math.min(MAX_BYTES, capabilities.maxBytes)) throw new Error('Web UI 资料超过目标服务的大小限制。')
    const counts = { profiles: snapshot.profiles.length, pageObjects: snapshot.pageObjects.length,
      scenarios: snapshot.scenarios.length, steps: snapshot.scenarios.reduce((total, item) => total + item.steps.length, 0),
      schedules: snapshot.schedules.length }
    if (!counts.profiles && !counts.pageObjects && !counts.scenarios && !counts.schedules) throw new Error('本地项目没有可迁移的 Web UI 资料。')
    const actor = await this.remote('/api/v1/auth/me')
    if (actor.id !== context.actor.id || endpoint !== this.current()) throw new Error('账号或服务已变化，请重新预览。')
    const token = crypto.randomUUID()
    this.plan = { token, snapshot: JSON.parse(serialized), source, target, actor: context.actor,
      endpoint, expires: this.now() + 10 * 60000, requestKey: crypto.randomUUID(), counts }
    return { token, source, target, actor: context.actor, counts }
  }
  validPlan(token) {
    const plan = this.plan
    if (!plan || plan.token !== token || plan.expires <= this.now() || plan.endpoint !== this.current()) {
      this.plan = undefined; throw new Error('预览已失效，请重新预览。')
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
      const csrf = await this.remote('/api/v1/auth/csrf'); this.validPlan(token)
      const result = await this.remote(`/api/v1/projects/${plan.target.id}/ui-automation/transfer`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ requestKey: plan.requestKey, expectedUserId: plan.actor.id, snapshot: plan.snapshot }),
      })
      this.plan = undefined; return result
    } finally { this.busy = false }
  }
}
module.exports = { UiAutomationTransfer, uiAutomationTransferResponseBudget }
