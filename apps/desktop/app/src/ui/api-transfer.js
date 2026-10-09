'use strict'
// The existing transfer page loads only this entry button; its form is untouched.
;(function () {
  const entry = document.querySelector('#open-api-transfer')
  if (entry) {
    entry.addEventListener('click', async () => {
      entry.disabled = true
      try { await window.testhubDesktop.openApiTransfer() }
      catch (value) {
        const error = document.querySelector('#error')
        error.textContent = value.message || '无法打开接口设计资产迁移，请重试。'; error.hidden = false
      } finally { entry.disabled = false }
    })
    return
  }
  const form = document.querySelector('#api-transfer-form'), source = document.querySelector('#source'), target = document.querySelector('#target')
  const includeHistory = document.querySelector('#include-history')
  const includeGrpc = document.querySelector('#include-grpc')
  const includeFiles = document.querySelector('#include-files'), prepare = document.querySelector('#prepare'), commit = document.querySelector('#commit')
  const preview = document.querySelector('#preview'), errorElement = document.querySelector('#error')
  let token, busy = false, ready = false, server = ''
  function showAccount(actor) {
    document.querySelector('#account').textContent = `当前账号：${actor.name}（${actor.account}） · ${server}`
  }
  function error(value) { errorElement.textContent = value.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '迁移失败，请重试。'; errorElement.hidden = false }
  function controls(value) {
    busy = value
    for (const element of [source, target, includeFiles, includeHistory, includeGrpc, prepare]) element.disabled = value || !ready
    commit.disabled = value || !ready || !token
  }
  function invalidate() { token = undefined; preview.textContent = ''; commit.disabled = true }
  function summary(counts) { return `${counts.catalogs} 个目录、${counts.definitions} 个接口定义、${counts.versions} 个接口版本、${counts.cases} 条接口用例${counts.debugHistory == null ? '' : `；${counts.debugHistory} 条调试历史`}${counts.grpcDescriptors == null ? '' : `；${counts.grpcDescriptors} 个 gRPC 描述符`}${counts.files ? `；${counts.files.items} 个文件（${(counts.files.bytes / 1024 / 1024).toFixed(2)} MiB）` : ''}` }
  for (const element of [source, target, includeFiles, includeHistory, includeGrpc]) element.addEventListener('change', invalidate)
  controls(true)
  window.testhubDesktop.apiTransferContext().then(context => {
    server = context.server
    showAccount(context.actor)
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
      const result = await window.testhubDesktop.prepareApiTransfer({ sourceId: source.value, targetId: target.value, includeFiles: includeFiles.checked, includeGrpc: includeGrpc.checked, includeHistory: includeHistory.checked })
      showAccount(result.actor)
      token = result.token
      preview.textContent = `将复制 ${summary(result.counts)} 至“${result.target.name}”。预览有效期 10 分钟。`
    } catch (value) { error(value) } finally { controls(false) }
  })
  commit.addEventListener('click', async () => {
    if (busy || !token) return
    errorElement.hidden = true; controls(true)
    try {
      const result = await window.testhubDesktop.commitApiTransfer(token)
      if (result.cancelled) return
      token = undefined; preview.textContent = `接口设计资产迁移完成：${summary(result)}。刷新账号网页即可查看。`
    } catch (value) { error(value) } finally { controls(false) }
  })
})()
