'use strict'
;(function () {
  const entry = document.querySelector('#open-task-transfer')
  if (entry) {
    entry.addEventListener('click', async () => {
      entry.disabled = true
      try { await window.testhubDesktop.openTaskTransfer() }
      catch (value) { const error = document.querySelector('#error'); error.textContent = value.message || '无法打开任务登记迁移，请重试。'; error.hidden = false }
      finally { entry.disabled = false }
    })
    return
  }
  const form = document.querySelector('#task-transfer-form'), source = document.querySelector('#source'), target = document.querySelector('#target')
  const prepare = document.querySelector('#prepare'), commit = document.querySelector('#commit'), preview = document.querySelector('#preview'), errorElement = document.querySelector('#error')
  let token, busy = false, ready = false
  function summary(c) { return `${c.tasks} 条任务登记（${c.cancelledActiveTasks} 条活动登记转为取消）` }
  function error(value) { errorElement.textContent = value.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '迁移失败，请重试。'; errorElement.hidden = false }
  function controls(value) { busy = value; source.disabled = value || !ready; target.disabled = value || !ready; prepare.disabled = value || !ready; commit.disabled = value || !ready || !token }
  function invalidate() { token = undefined; preview.textContent = ''; commit.disabled = true }
  source.addEventListener('change', invalidate); target.addEventListener('change', invalidate)
  controls(true)
  window.testhubDesktop.taskTransferContext().then(context => {
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
      const result = await window.testhubDesktop.prepareTaskTransfer({ sourceId: source.value, targetId: target.value })
      token = result.token; preview.textContent = `将复制 ${summary(result.counts)} 至“${result.target.name}”。迁入后不会启动测试；预览有效期 10 分钟。`
    } catch (value) { error(value) } finally { controls(false) }
  })
  commit.addEventListener('click', async () => {
    if (busy || !token) return
    errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.commitTaskTransfer(token)
      if (result.cancelled) return
      token = undefined; preview.textContent = `任务登记资料迁移完成：${summary(result)}。刷新账号网页即可查看；来源摘要可查看，任务登记可以编辑；测试执行请前往对应模块。`
    } catch (value) { error(value) } finally { controls(false) }
  })
})()
