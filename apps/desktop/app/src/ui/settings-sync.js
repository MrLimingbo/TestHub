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
      const heading = document.createElement('strong'), record = item.local || item.remote, parts = item.key.split(':')
      heading.textContent = (record?.kind || (parts[0] === 'E' ? 'ENVIRONMENT' : 'PARAMETER')) === 'ENVIRONMENT'
        ? `环境 · ${record?.environmentCode || parts[1]}` : `普通参数 · ${record?.environmentCode || parts[1]} / ${record?.key || parts[2]}`
      section.prepend(heading)
      for (const [side, label] of [['local', '本地'], ['remote', '网页']]) {
        const record = item[side], p = document.createElement('pre'); p.className = 'conflict-value'
        p.textContent = !record ? `${label}：配置不存在或作为密钥保留` : record.kind === 'ENVIRONMENT'
          ? `${label}：${record.environmentCode} · ${record.name}\n基础地址：${record.baseUrl || '未设置'}\n说明：${record.description || '未设置'}`
          : `${label}：${record.environmentCode} / ${record.key}\n参数值：${record.value}`
        section.append(p)
        if (item.canResolve) {
          const button = document.createElement('button'); button.type = 'button'; button.dataset.conflict = 'true'
          button.dataset.conflictKey = item.key; button.dataset.choice = side; button.textContent = `保留${label}版本`
          button.disabled = !binding.enabled || Boolean(value.pending) || binding.requiresRebind
          button.addEventListener('click', () => act(() => bridge.resolveSettingsSync({ key: item.key, choice: side }))); section.append(button)
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
      try { render(await bridge.settingsSyncStatus()) } catch { /* Preserve the original actionable failure. */ }
      failure(error)
    } finally { controls(false) }
  }
  async function context() {
    const status = await bridge.settingsSyncStatus(); render(status)
    if (status.binding) return
    token = undefined; $('#preview').textContent = ''
    const value = await bridge.settingsSyncContext()
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
    void act(async () => { token = undefined; const value = await bridge.previewSettingsSync({ sourceId: $('#source').value, targetId: $('#target').value }); token = value.token
      $('#preview').textContent = `首次上传 ${value.upload} 项、下载 ${value.download} 项，${value.conflictCount} 项需核对。两端共 ${value.excludedSecrets} 个密钥不复制。绑定后后续新建和修改也会自动同步。` })
  })
  $('#bind').addEventListener('click', () => act(async () => { const value = await bridge.bindSettingsSync(token); if (!value.cancelled) token = undefined; return value }))
  $('#run').addEventListener('click', () => act(() => bridge.runSettingsSync()))
  $('#toggle').addEventListener('click', () => act(() => bridge.enableSettingsSync(!state.binding.enabled)))
  $('#unbind').addEventListener('click', () => act(async () => { const value = await bridge.unbindSettingsSync(); if (!value.cancelled) { token = undefined; await context() } return value }))
  $('#refresh').addEventListener('click', () => act(context))
  void act(context)
  async function poll() {
    if (busy || !state?.binding) return
    try {
      const value = await bridge.settingsSyncStatus()
      if (busy || JSON.stringify(value) === JSON.stringify(state)) return
      const focused = document.activeElement?.dataset
      const key = focused?.conflictKey, choice = focused?.choice
      render(value); controls(false)
      if (key) [...document.querySelectorAll('[data-conflict]')].find(b => b.dataset.conflictKey === key && b.dataset.choice === choice)?.focus()
    } catch (error) { if (!busy) failure(error) }
  }
  window.setInterval(() => { void poll() }, 5000)
})()
