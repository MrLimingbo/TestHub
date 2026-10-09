'use strict'
;(function () {
  const entry = document.querySelector('#open-settings-transfer')
  if (entry) {
    entry.addEventListener('click', async () => {
      entry.disabled = true
      try { await window.testhubDesktop.openSettingsTransfer() }
      catch (value) { const error = document.querySelector('#error'); error.textContent = value.message || '无法打开项目配置迁移，请重试。'; error.hidden = false }
      finally { entry.disabled = false }
    })
    return
  }
  const form = document.querySelector('#settings-transfer-form'), source = document.querySelector('#source'), target = document.querySelector('#target')
  const prepare = document.querySelector('#prepare'), commit = document.querySelector('#commit'), preview = document.querySelector('#preview'), errorElement = document.querySelector('#error')
  let token, busy = false, ready = false
  function summary(c) { return `${c.environments} 个环境、${c.parameters} 个普通参数；排除 ${c.excludedSecrets} 个密钥` }
  function error(value) { errorElement.textContent = value.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '迁移失败，请重试。'; errorElement.hidden = false }
  function controls(value) { busy = value; source.disabled = value || !ready; target.disabled = value || !ready; prepare.disabled = value || !ready; commit.disabled = value || !ready || !token }
  function invalidate() { token = undefined; preview.textContent = ''; commit.disabled = true }
  source.addEventListener('change', invalidate); target.addEventListener('change', invalidate)
  controls(true)
  window.testhubDesktop.settingsTransferContext().then(context => {
    document.querySelector('#account').textContent = `当前账号：${context.actor.name}（${context.actor.account}） · ${context.server}`
    for (const [select, projects] of [[source, context.sourceProjects], [target, context.targetProjects]]) {
      const empty = document.createElement('option'); empty.value = ''; empty.textContent = projects.length ? '请选择项目' : '暂无有权限的项目'; select.append(empty)
      for (const project of projects) { const option = document.createElement('option'); option.value = project.id; option.textContent = `${project.organization} / ${project.name}`; select.append(option) }
    }
    ready = context.sourceProjects.length > 0 && context.targetProjects.length > 0
  }).catch(value => { document.querySelector('#account').textContent = '未能读取项目，请关闭迁移窗口后重新打开。'; error(value) }).finally(() => controls(false))
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !ready) return
    invalidate(); errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.prepareSettingsTransfer({ sourceId: source.value, targetId: target.value })
      token = result.token; preview.textContent = `将复制 ${summary(result.counts)} 至“${result.target.name}”。相同配置复用、不同配置报冲突；保留目标默认环境；预览有效期 10 分钟。`
    } catch (value) { error(value) } finally { controls(false) }
  })
  commit.addEventListener('click', async () => {
    if (busy || !token) return
    errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.commitSettingsTransfer(token)
      if (result.cancelled) return
      token = undefined; preview.textContent = `项目配置资料迁移完成：新增 ${result.createdEnvironments} 个环境、复用 ${result.reusedEnvironments} 个环境，新增 ${result.createdParameters} 个参数（含基础地址）、复用 ${result.reusedParameters} 个参数。排除 ${result.excludedSecrets} 个密钥，请在目标账号重新填写。刷新账号网页即可查看和编辑。`
    } catch (value) { error(value) } finally { controls(false) }
  })
})()
