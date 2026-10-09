'use strict'
;(function () {
  const bridge = window.testhubDesktop, $ = selector => document.querySelector(selector)
  let state, token, busy = false
  function controls(value) {
    busy = value
    for (const button of document.querySelectorAll('button')) button.disabled = value
    $('#bind').disabled = value || !token
    if (state?.binding) {
      $('#run').disabled = value || !state.binding.enabled || state.binding.requiresRebind
      $('#toggle').disabled = value || state.binding.requiresRebind
    }
    for (const button of document.querySelectorAll('[data-conflict]')) button.disabled = value || !state?.binding?.enabled || Boolean(state.pending) || state.binding.requiresRebind
    $('#source').disabled = value; $('#target').disabled = value
  }
  function failure(error) {
    $('#error').textContent = error.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '同步失败，请重试。'; $('#error').hidden = false
  }
  function render(value) {
    state = value; const binding = value.binding
    $('#bound').hidden = !binding; $('#bind-form').hidden = Boolean(binding)
    $('#status').textContent = !binding ? '尚未启用。请先连接账号服务并登录，再选择项目绑定。'
      : binding.requiresRebind ? '工作区已恢复备份，旧同步已暂停。请解除绑定并重新核对。'
        : !binding.enabled ? '同步已暂停，本地与网页资料均保留。'
          : value.pending ? '有待确认的提交，联网后将用原请求安全重试。' : value.conflicts.length ? `同步已启用，有 ${value.conflicts.length} 条冲突需要处理。` : '同步已启用，应用运行时每 30 秒检查两端变更。'
    if (!binding) return
    $('#binding').textContent = `${binding.source.organization} / ${binding.source.name} ↔ ${binding.actor.account} / ${binding.target.name} · ${binding.server}`
    $('#last-sync').textContent = value.lastSyncedAt ? `最近同步：${new Date(value.lastSyncedAt).toLocaleString()}` : '尚未完成首次同步。'
    $('#toggle').textContent = binding.enabled ? '暂停同步' : '恢复同步'
    const conflicts = $('#conflicts'); conflicts.replaceChildren()
    for (const item of value.conflicts) {
      const section = document.createElement('section'), summary = document.createElement('p'); summary.textContent = item.reason; section.append(summary)
      for (const [side, label] of [['local', '本地'], ['remote', '网页']]) {
        const task = item[side], p = document.createElement('p'); p.className = 'hint'; p.textContent = task ? `${label}：${task.name} · ${task.status} · ${task.progress}%` : `${label}：登记不存在`; section.append(p)
        if (item.local && item.remote) {
          const button = document.createElement('button'); button.type = 'button'; button.dataset.conflict = 'true'; button.textContent = `保留${label}版本`
          button.disabled = !binding.enabled || Boolean(value.pending) || binding.requiresRebind
          button.addEventListener('click', () => act(() => bridge.resolveTaskSync({ localId: item.localId, choice: side }))); section.append(button)
        }
      }
      conflicts.append(section)
    }
    if (value.error) failure({ message: value.error })
  }
  async function act(operation) {
    if (busy) return
    $('#error').hidden = true; controls(true)
    try { const value = await operation(); if (value?.cancelled) return; if (value?.binding !== undefined) render(value) }
    catch (error) {
      try { render(await bridge.taskSyncStatus()) } catch { /* Preserve the original actionable failure. */ }
      failure(error)
    } finally { controls(false) }
  }
  async function context() {
    const status = await bridge.taskSyncStatus(); render(status)
    if (status.binding) return
    const value = await bridge.taskSyncContext()
    $('#account').textContent = `${value.actor.displayName}（${value.actor.account || value.actor.email}） · ${value.server}`
    for (const [select, projects] of [[$('#source'), value.sourceProjects], [$('#target'), value.targetProjects]]) {
      select.replaceChildren(); const empty = document.createElement('option'); empty.value = ''; empty.textContent = '请选择项目'; select.append(empty)
      for (const project of projects) { const option = document.createElement('option'); option.value = project.id; option.textContent = `${project.organization} / ${project.name}`; select.append(option) }
    }
  }
  function invalidate() { token = undefined; $('#preview').textContent = ''; controls(false) }
  $('#source').addEventListener('change', invalidate); $('#target').addEventListener('change', invalidate)
  $('#bind-form').addEventListener('submit', event => {
    event.preventDefault()
    void act(async () => { token = undefined; const value = await bridge.previewTaskSync({ sourceId: $('#source').value, targetId: $('#target').value }); token = value.token
      $('#preview').textContent = `首次上传 ${value.upload} 条、下载 ${value.download} 条登记。绑定后此项目的后续新建和修改也会自动同步。` })
  })
  $('#bind').addEventListener('click', () => act(async () => { const value = await bridge.bindTaskSync(token); if (!value.cancelled) token = undefined; return value }))
  $('#run').addEventListener('click', () => act(() => bridge.runTaskSync()))
  $('#toggle').addEventListener('click', () => act(() => bridge.enableTaskSync(!state.binding.enabled)))
  $('#unbind').addEventListener('click', () => act(async () => { const value = await bridge.unbindTaskSync(); if (!value.cancelled) { token = undefined; await context() } return value }))
  $('#refresh').addEventListener('click', () => act(context))
  void act(context)
  window.setInterval(() => { if (!busy && state?.binding) void act(async () => bridge.taskSyncStatus()) }, 5000)
})()
