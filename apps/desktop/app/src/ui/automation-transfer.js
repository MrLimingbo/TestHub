'use strict'
;(function () {
  const entry = document.querySelector('#open-automation-transfer')
  if (entry) {
    entry.addEventListener('click', async () => {
      entry.disabled = true
      try { await window.testhubDesktop.openAutomationTransfer() }
      catch (value) {
        const error = document.querySelector('#error')
        error.textContent = value.message || '无法打开接口自动化迁移，请重试。'; error.hidden = false
      } finally { entry.disabled = false }
    })
    return
  }
  const form = document.querySelector('#automation-transfer-form')
  const source = document.querySelector('#source'), target = document.querySelector('#target')
  const prepare = document.querySelector('#prepare'), commit = document.querySelector('#commit')
  const preview = document.querySelector('#preview'), errorElement = document.querySelector('#error')
  let token, busy = false, ready = false, server = ''
  function summary(c) { return `${c.scenarios} 个场景、${c.reusableSteps} 个公共步骤、${c.dataSets} 个数据集（${c.dataSetSourceFiles || 0} 个来源文件）、${c.executionProfiles} 个运行配置和 ${c.schedules} 个计划` }
  function error(value) { errorElement.textContent = value.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '迁移失败，请重试。'; errorElement.hidden = false }
  function controls(value) { busy = value; source.disabled = value || !ready; target.disabled = value || !ready; prepare.disabled = value || !ready; commit.disabled = value || !ready || !token }
  function invalidate() { token = undefined; preview.textContent = ''; commit.disabled = true }
  source.addEventListener('change', invalidate); target.addEventListener('change', invalidate)
  controls(true)
  window.testhubDesktop.automationTransferContext().then(context => {
    server = context.server
    document.querySelector('#account').textContent = `当前账号：${context.actor.name}（${context.actor.account}） · ${server}`
    for (const [select, projects] of [[source, context.sourceProjects], [target, context.targetProjects]]) {
      const empty = document.createElement('option'); empty.value = ''; empty.textContent = projects.length ? '请选择项目' : '暂无有权限的项目'; select.append(empty)
      for (const project of projects) { const option = document.createElement('option'); option.value = project.id; option.textContent = `${project.organization} / ${project.name}`; select.append(option) }
    }
    ready = context.sourceProjects.length > 0 && context.targetProjects.length > 0
  }).catch(value => {
    document.querySelector('#account').textContent = '未能读取项目，请关闭迁移窗口后重新打开。'; error(value)
  }).finally(() => controls(false))
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !ready) return
    invalidate(); errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.prepareAutomationTransfer({ sourceId: source.value, targetId: target.value })
      token = result.token
      preview.textContent = `将复制 ${summary(result.counts)} 至“${result.target.name}”。预览有效期 10 分钟。`
    } catch (value) { error(value) } finally { controls(false) }
  })
  commit.addEventListener('click', async () => {
    if (busy || !token) return
    errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.commitAutomationTransfer(token)
      if (result.cancelled) return
      token = undefined; preview.textContent = `接口自动化资料迁移完成：${summary(result)}。刷新账号网页即可查看。`
    } catch (value) { error(value) } finally { controls(false) }
  })
})()
