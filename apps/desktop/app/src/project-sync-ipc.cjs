'use strict'
const { Buffer } = require('node:buffer')
const crypto = require('node:crypto')
const fs = require('node:fs/promises')
const path = require('node:path')
const { normalizeServerUrl } = require('./desktop-core.cjs')

async function ensureSyncSession(runtime, fetcher) {
  let response
  try { response = await fetcher(runtime.url + '/api/v1/auth/me', { credentials: 'include', redirect: 'error', signal: globalThis.AbortSignal.timeout(10000) }) }
  catch { throw new Error('本地工作区会话暂时不可达，请稍后重试。') }
  await response.body?.cancel()
  // Rotating an already authenticated session invalidates CSRF captured by the UI or another sync module.
  if (response.ok) return
  if (response.status !== 401) throw new Error(`本地工作区会话校验失败（HTTP ${response.status}）。`)
  await runtime.authorizeSession(fetcher)
}

async function syncRequest(fetcher, url, options = {}, { label = '任务', limit = 1024 * 1024, timeoutMs = 30000 } = {}) {
  let response
  try { response = await fetcher(url, { ...options, credentials: 'include', redirect: 'error', signal: globalThis.AbortSignal.timeout(timeoutMs) }) }
  catch { throw new Error('同步服务暂时不可达，本地编辑可继续；联网后会自动重试。') }
  if (!response.ok) {
    const message = response.status === 409 ? `账号或${label}版本已变化，请重新同步并核对冲突。`
      : [401, 403].includes(response.status) ? '同步会话已失效或项目无写入权限，请重新登录绑定账号并核对权限。'
        : response.status === 404 ? `当前服务不支持${label}同步，请更新服务。` : `${label}同步请求失败（HTTP ${response.status}），请核对资料数量与服务状态。`
    await response.body?.cancel(); const error = new Error(message); error.status = response.status; throw error
  }
  const chunks = []; let bytes = 0
  for await (const chunk of response.body) {
    bytes += chunk.length
    if (bytes > limit) throw new Error(`${label}同步响应超过 ${limit / (1024 * 1024)} MiB 限制。`)
    chunks.push(Buffer.from(chunk))
  }
  try { const envelope = JSON.parse(Buffer.concat(chunks).toString('utf8')); if (!envelope.data) throw new Error(); return envelope.data }
  catch { throw new Error('同步服务返回的数据无效，请核对服务版本。') }
}

async function syncBinary(fetcher, url, size, digest, { label = '文件', timeoutMs = 120000 } = {}) {
  if (!Number.isSafeInteger(size) || size < 1 || size > 20 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(digest))
    throw new Error('文件大小或摘要无效。')
  let response
  try { response = await fetcher(url, { credentials: 'include', redirect: 'error', signal: globalThis.AbortSignal.timeout(timeoutMs) }) }
  catch { throw new Error('文件来源暂时不可达，联网后可重试。') }
  if (!response.ok) { await response.body?.cancel(); throw new Error(`${label}下载失败（HTTP ${response.status}），请核对来源文件。`) }
  const chunks = []; let length = 0; const hash = crypto.createHash('sha256')
  for await (const chunk of response.body) {
    length += chunk.length
    if (length > size) throw new Error('文件内容超过记录大小，同步已停止。')
    hash.update(chunk); chunks.push(Buffer.from(chunk))
  }
  if (length !== size || hash.digest('hex') !== digest) throw new Error('来源文件大小或 SHA-256 已变化，同步已停止。')
  return Buffer.concat(chunks)
}

function installProjectSync({ ipcMain, dialog, session, directory, origin, windows, runtime, server, exclusive, available, openWindow, trusted, fileLinkContext }, definition) {
  const { Client, Store, channel, page, filename, title, transport } = definition
  let client, initializing, lastError = '', disposed = false
  const remoteSession = url => session.fromPartition('persist:testhub-server-' + crypto.createHash('sha256').update(url).digest('hex').slice(0, 24))
  async function get() {
    if (!client) {
      if (!initializing) initializing = (async () => {
        const local = await runtime()
        client = new Client({ store: new Store(directory, local.codec), fileLinkContext,
          guard: async binding => {
            if (disposed || normalizeServerUrl(await server()) !== binding.server) throw new Error('账号服务已切换，同步已停止；请切回绑定服务。')
          },
          local: async (route, options) => syncRequest((url, init) => session.fromPartition('persist:testhub-local-workspace').fetch(url, init), new URL(route, local.url).href, options, transport),
          remote: async (route, options, binding) => syncRequest((url, init) => remoteSession(binding.server).fetch(url, init), new URL(route, binding.server).href, options, transport),
          localBinary: async (route, size, digest) => syncBinary((url, init) => session.fromPartition('persist:testhub-local-workspace').fetch(url, init), new URL(route, local.url).href, size, digest, transport),
          remoteBinary: async (route, size, digest, binding) => syncBinary((url, init) => remoteSession(binding.server).fetch(url, init), new URL(route, binding.server).href, size, digest, transport),
        }); await client.load()
      })().finally(() => { initializing = undefined })
      await initializing
    }
    return client
  }
  function requireSender(event) {
    const setup = windows().setup
    if (event.sender !== setup?.webContents || event.senderFrame !== event.sender.mainFrame || event.senderFrame?.url !== `${origin}/${page}`)
      throw new Error(`不受信任的${title}请求。`)
  }
  const invoke = (name, operation) => ipcMain.handle(`testhub-desktop:${channel}:${name}`, (event, value) => {
    requireSender(event)
    return exclusive(async () => { try { const result = await operation(await get(), value); lastError = ''; return result }
      catch (error) { lastError = error.message; throw error } })
  })
  async function open(event) {
    if (event && !trusted(event)) throw new Error(`不受信任的${title}入口。`)
    openWindow(page)
  }
  ipcMain.handle(`testhub-desktop:${channel}:open`, open)
  ipcMain.handle(`testhub-desktop:${channel}:status`, event => {
    requireSender(event)
    const read = async () => ({ ...(await (await get()).status()), error: lastError })
    // A loaded status only clones memory; polling must remain readable during a long sync or backup.
    return client?.state ? read() : exclusive(read)
  })
  invoke('context', async c => {
    const status = await c.status()
    if (status.binding) return status
    const url = normalizeServerUrl(await server())
    const [actor, local, remote] = await Promise.all([c.remote('/api/v1/auth/me', undefined, { server: url }), c.local('/api/v1/access/context'), c.remote('/api/v1/access/context', undefined, { server: url })])
    const projects = value => value.organizations.flatMap(org => (value.projects[org.id] || []).map(p => ({ id: p.id, name: p.name, organization: org.name })))
    return { ...status, server: url, actor, sourceProjects: projects(local), targetProjects: projects(remote) }
  })
  invoke('preview', async (c, value) => definition.preview ? definition.preview(c, value, await server()) : c.preview(value?.sourceId, value?.targetId, await server()))
  invoke('bind', async (c, token) => {
    const plan = c.plan
    if (!plan || plan.token !== token) throw new Error('绑定预览已失效，请重新预览。')
    const decision = await dialog.showMessageBox(windows().setup, { type: 'question', title: `启用${title}`,
      message: `将本地“${plan.binding.source.name}”与 ${plan.binding.actor.account} 的“${plan.binding.target.name}”绑定？`,
      detail: definition.planDetail(plan),
      buttons: ['取消', '确认绑定并同步'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    await c.bind(token); await c.run(); return c.status()
  })
  invoke('run', async c => { await runtime(); await c.run(); return c.status() })
  invoke('enabled', async (c, value) => {
    if (value && c.state.binding?.requiresRebind) throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。')
    await c.enabled(value); return c.status()
  })
  invoke('unbind', async c => {
    const decision = await dialog.showMessageBox(windows().setup, { type: 'question', title: `解除${title}绑定`,
      message: `停止这两个项目之间的${title}？`, detail: definition.unbindDetail,
      buttons: ['取消', '解除绑定'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    await c.unbind(); return c.status()
  })
  invoke('resolve', async (c, value) => {
    const identifier = definition.identifier(value), conflict = c.state.conflicts.find(item => definition.conflictId(item) === identifier)
    if (!definition.canResolve(conflict) || !['local', 'remote'].includes(value.choice)) throw new Error('请选择当前有效的冲突及版本。')
    const decision = await dialog.showMessageBox(windows().setup, { type: 'question', title: `处理${title}冲突`,
      message: `确认用${value.choice === 'local' ? '本地' : '网页'}版本更新另一端？`,
      detail: definition.conflictDetail(conflict,c.state.binding),
      buttons: ['取消', '确认使用该版本'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    await c.resolve(identifier, value.choice); return c.status()
  })
  return { open,
    linkContext: async binding => (await get()).linkContext(binding),
    tick: async () => {
      if (disposed || !available()) return
      try {
        if (!client) {
          try { await fs.access(path.join(directory, filename)) } catch (error) { if (error.code === 'ENOENT') return; throw error }
        }
        await exclusive(async () => {
          const c = await get(), status = await c.status()
          if (!status.binding?.enabled || status.binding.requiresRebind) return
          await runtime(); await c.run(); lastError = ''
        })
      } catch (error) { lastError = error.message }
    },
    suspendForRestore: async () => { await (await get()).suspendForRestore(); lastError = '' },
    stop: () => { disposed = true },
  }
}
module.exports = { installProjectSync, syncRequest, syncBinary, ensureSyncSession }
