'use strict'
;(function () {
  const entry = document.querySelector('#open-ui-automation-transfer')
  if (entry) {
    entry.addEventListener('click', async () => {
      entry.disabled = true
      try { await window.testhubDesktop.openUiAutomationTransfer() }
      catch (value) { const error = document.querySelector('#error'); error.textContent = value.message || '无法打开Web UI迁移，请重试。'; error.hidden = false }
      finally { entry.disabled = false }
    })
    return
  }
  const form = document.querySelector('#ui-automation-transfer-form'), source = document.querySelector('#source'), target = document.querySelector('#target')
  const prepare = document.querySelector('#prepare'), commit = document.querySelector('#commit'), preview = document.querySelector('#preview'), errorElement = document.querySelector('#error')
  let token, busy = false, ready = false
  function summary(c) { return `${c.profiles} 个浏览器配置、${c.pageObjects} 个页面对象、${c.scenarios} 个场景（${c.steps} 个步骤）和 ${c.schedules} 个定时计划` }
  function error(value) { errorElement.textContent = value.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '迁移失败，请重试。'; errorElement.hidden = false }
  function controls(value) { busy = value; source.disabled = value || !ready; target.disabled = value || !ready; prepare.disabled = value || !ready; commit.disabled = value || !ready || !token }
  function invalidate() { token = undefined; preview.textContent = ''; commit.disabled = true }
  source.addEventListener('change', invalidate); target.addEventListener('change', invalidate)
  controls(true)
  window.testhubDesktop.uiAutomationTransferContext().then(context => {
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
      const result = await window.testhubDesktop.prepareUiAutomationTransfer({ sourceId: source.value, targetId: target.value })
      token = result.token; preview.textContent = `将复制 ${summary(result.counts)} 至“${result.target.name}”。迁入后浏览器配置和定时计划全部停用；预览有效期 10 分钟。`
    } catch (value) { error(value) } finally { controls(false) }
  })
  commit.addEventListener('click', async () => {
    if (busy || !token) return
    errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.commitUiAutomationTransfer(token)
      if (result.cancelled) return
      token = undefined; preview.textContent = `Web UI资料迁移完成：${summary(result)}。刷新账号网页即可查看；配置目标授权与运行器后再手动启用。`
    } catch (value) { error(value) } finally { controls(false) }
  })
})()
