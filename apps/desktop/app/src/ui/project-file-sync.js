'use strict'
;(function () {
  const bridge = window.testhubDesktop, $ = selector => document.querySelector(selector)
  let state, token, fileProjects, busy = false
  const label = item => `${item.name} · ${(item.size / 1024).toFixed(1)} KiB · SHA-256 ${item.sha256.slice(0, 12)} · ${item.id.slice(-8)}`
  function controls(value) {
    busy = value
    for (const button of document.querySelectorAll('button')) button.disabled = value
    $('#bind').disabled = value || !token
    if (state?.binding) {
      $('#run').disabled = value || !state.binding.enabled || state.binding.requiresRebind
      $('#toggle').disabled = value || state.binding.requiresRebind
    }
    for (const select of document.querySelectorAll('select')) select.disabled = value
  }
  function failure(error) {
    $('#error').textContent = error.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '文件同步失败，请重试。'
    $('#error').hidden = false
  }
  function render(value) {
    state = value
    const binding = value.binding
    $('#bound').hidden = !binding; $('#bind-form').hidden = Boolean(binding)
    $('#status').textContent = !binding ? '尚未启用。请连接账号服务并登录，再选择项目绑定。'
      : binding.requiresRebind ? '工作区已恢复备份，旧同步已暂停。请解除绑定并重新核对。'
        : !binding.enabled ? '同步已暂停，两端文件均保留。'
          : value.pending ? '有待确认的文件上传，联网后将用原请求安全重试。'
            : value.conflicts.length ? `同步已启用，有 ${value.conflicts.length} 个文件需人工核对。`
              : '同步已启用，运行时每 30 秒检查两端新文件。'
    if (!binding) return
    $('#binding').textContent = `${binding.source.organization} / ${binding.source.name} ↔ ${binding.actor.account} / ${binding.target.name} · ${binding.server}`
    $('#paired-summary').textContent = `绑定时已确认 ${binding.pairs.length} 对已有文件。`
    $('#last-sync').textContent = value.lastSyncedAt ? `最近同步：${new Date(value.lastSyncedAt).toLocaleString()}` : '尚未完成首次同步。'
    $('#toggle').textContent = binding.enabled ? '暂停同步' : '恢复同步'
    const conflicts = $('#conflicts'); conflicts.replaceChildren()
    for (const issue of value.conflicts) {
      const section = document.createElement('section'), heading = document.createElement('strong'), message = document.createElement('p'), detail = document.createElement('p')
      heading.textContent = `文件 · ${issue.local?.name || issue.remote?.name || issue.key}`
      message.textContent = issue.reason
      detail.textContent = `本地：${issue.local ? label(issue.local) : '不存在'}；网页：${issue.remote ? label(issue.remote) : '不存在'}`
      section.append(heading, message, detail); conflicts.append(section)
    }
    if (value.error) failure({ message: value.error })
  }
  async function act(operation) {
    if (busy) return
    $('#error').hidden = true; controls(true)
    try { const result = await operation(); if (result?.cancelled) return; if (result?.binding !== undefined) render(result) }
    catch (error) {
      try { render(await bridge.projectFileSyncStatus()) } catch { /* Retain the original error. */ }
      failure(error)
    } finally { controls(false) }
  }
  async function context() {
    const status = await bridge.projectFileSyncStatus(); render(status)
    if (status.binding) return
    token = undefined; fileProjects = undefined; $('#file-pairs').replaceChildren(); $('#preview').textContent = ''
    const value = await bridge.projectFileSyncContext()
    $('#account').textContent = `${value.actor.displayName}（${value.actor.account || value.actor.email}） · ${value.server}`
    for (const [select, projects] of [[$('#source'), value.sourceProjects], [$('#target'), value.targetProjects]]) {
      select.replaceChildren(); const empty = document.createElement('option'); empty.value = ''; empty.textContent = '请选择项目'; select.append(empty)
      for (const project of projects) { const option = document.createElement('option'); option.value = project.id; option.textContent = `${project.organization} / ${project.name}`; select.append(option) }
    }
  }
  function invalidate() { token = undefined; $('#preview').textContent = ''; controls(busy) }
  function invalidateProjects() { fileProjects = undefined; $('#file-pairs').replaceChildren(); invalidate() }
  $('#source').addEventListener('change', invalidateProjects); $('#target').addEventListener('change', invalidateProjects)
  $('#load-files').addEventListener('click', () => act(async () => {
    invalidateProjects()
    const sourceId = $('#source').value, targetId = $('#target').value
    const value = await bridge.previewProjectFileSync({ mode: 'files', sourceId, targetId })
    fileProjects = { sourceId, targetId }
    for (const file of value.local) {
      const row = document.createElement('div'), title = document.createElement('label'), select = document.createElement('select'), detail = document.createElement('p')
      row.className = 'pair'; title.textContent = `本地 ${label(file)} 对应网页文件`; select.id = `file-${file.id}`; title.htmlFor = select.id; select.dataset.file = file.id
      const none = document.createElement('option'); none.value = ''; none.textContent = '独立文件，复制到网页'; select.append(none)
      for (const remote of value.remote.filter(other => other.size === file.size && other.sha256 === file.sha256)) {
        const option = document.createElement('option'); option.value = remote.id; option.textContent = label(remote); select.append(option)
      }
      select.addEventListener('change', () => { detail.textContent = select.value ? label(value.remote.find(remote => remote.id === select.value)) : ''; invalidate() })
      row.append(title, select, detail); $('#file-pairs').append(row)
    }
    if (!value.local.length) { const note = document.createElement('p'); note.textContent = '本地尚无文件；网页文件会复制到本地。'; $('#file-pairs').append(note) }
  }))
  $('#bind-form').addEventListener('submit', event => {
    event.preventDefault()
    void act(async () => {
      token = undefined
      if (!fileProjects) throw new Error('请先读取并确认已有文件。')
      const filePairs = [...document.querySelectorAll('[data-file]')].filter(select => select.value)
        .map(select => ({ localFileId: select.dataset.file, remoteFileId: select.value }))
      const value = await bridge.previewProjectFileSync({ ...fileProjects, filePairs }); token = value.token
      $('#preview').textContent = `首次上传 ${value.upload} 个、下载 ${value.download} 个文件；${value.conflictCount} 个同来源文件需人工核对。已确认 ${filePairs.length} 对已有文件。`
    })
  })
  $('#bind').addEventListener('click', () => act(async () => { const result = await bridge.bindProjectFileSync(token); if (!result.cancelled) token = undefined; return result }))
  $('#run').addEventListener('click', () => act(() => bridge.runProjectFileSync()))
  $('#toggle').addEventListener('click', () => act(() => bridge.enableProjectFileSync(!state.binding.enabled)))
  $('#unbind').addEventListener('click', () => act(async () => { const result = await bridge.unbindProjectFileSync(); if (!result.cancelled) { token = undefined; await context() } return result }))
  $('#refresh').addEventListener('click', () => act(context))
  void act(context)
  window.setInterval(() => { if (busy || !state?.binding) return; void bridge.projectFileSyncStatus().then(value => {
    if (!busy && JSON.stringify(value) !== JSON.stringify(state)) { render(value); controls(false) }
  }).catch(error => { if (!busy) failure(error) }) }, 5000)
})()
