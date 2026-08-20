'use strict'

const { app, BrowserWindow, Menu, dialog, ipcMain, net, protocol } = require('electron')
const { Buffer } = require('node:buffer')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const {
  isTrustedServerUrl,
  normalizeHttpDebugRequest,
  normalizeServerUrl,
  readPreferences,
  writePreferences,
} = require('./desktop-core.cjs')

const SETUP_ORIGIN = 'testhub-desktop://setup'
const SETUP_PREFIX = `${SETUP_ORIGIN}/`

protocol.registerSchemesAsPrivileged([
  {
    scheme: 'testhub-desktop',
    privileges: { standard: true, secure: true, supportFetchAPI: true },
  },
])

let mainWindow
let setupWindow
let activeServerUrl
const approvedTargetOrigins = new Set()
const MAX_RESPONSE_BYTES = 512 * 1024

function desktopAssetUrl(requestUrl) {
  const request = new URL(requestUrl)
  if (request.hostname !== 'setup') {
    throw new Error('不支持的桌面资源主机。')
  }

  const uiRoot = path.resolve(__dirname, 'ui')
  const relativePath = request.pathname === '/' ? 'setup.html' : decodeURIComponent(request.pathname.slice(1))
  const candidate = path.resolve(uiRoot, relativePath)
  if (!candidate.startsWith(`${uiRoot}${path.sep}`)) {
    throw new Error('不支持的桌面资源路径。')
  }
  return pathToFileURL(candidate).toString()
}

function isSetupSender(event) {
  return event.senderFrame?.url.startsWith(SETUP_PREFIX) === true
}

function denyAllPermissions(targetSession) {
  targetSession.setPermissionCheckHandler(() => false)
  targetSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false))
}

function applyRemoteContentRestrictions(window, trustedServerUrl) {
  const contents = window.webContents
  contents.on('will-attach-webview', (event) => event.preventDefault())
  contents.on('will-navigate', (event, nextUrl) => {
    if (!isTrustedServerUrl(nextUrl, trustedServerUrl)) {
      event.preventDefault()
    }
  })
  contents.setWindowOpenHandler(({ url }) => (isTrustedServerUrl(url, trustedServerUrl) ? { action: 'allow' } : { action: 'deny' }))
}

function openTestHubWindow(nextServerUrl) {
  const normalized = normalizeServerUrl(nextServerUrl)
  activeServerUrl = normalized
  approvedTargetOrigins.clear()

  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.destroy()
  }
  mainWindow = new BrowserWindow({
    title: 'TestHub',
    minWidth: 1180,
    minHeight: 720,
    width: 1440,
    height: 960,
    backgroundColor: '#f5f7fa',
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      webviewTag: false,
      preload: path.join(__dirname, 'remote-preload.cjs'),
    },
  })
  denyAllPermissions(mainWindow.webContents.session)
  applyRemoteContentRestrictions(mainWindow, normalized)
  mainWindow.on('closed', () => { mainWindow = undefined })
  void mainWindow.loadURL(normalized)
}

function isTrustedRemoteSender(event) {
  return Boolean(activeServerUrl) && isTrustedServerUrl(event.senderFrame?.url ?? '', activeServerUrl)
}

async function approveTarget(event, request) {
  const origin = new URL(request.url).origin
  if (approvedTargetOrigins.has(origin)) return
  const parent = BrowserWindow.fromWebContents(event.sender)
  const decision = await dialog.showMessageBox(parent, {
    type: 'warning',
    title: '允许本地网络请求',
    message: `允许 TestHub 桌面端从本机访问 ${origin}？`,
    detail: '请求将直接从这台电脑发出，可访问当前电脑所在内网。仅当你信任所连接的 TestHub 服务和目标地址时允许。重定向不会被自动跟随。',
    buttons: ['允许本次运行期间访问', '取消'],
    defaultId: 1,
    cancelId: 1,
    noLink: true,
  })
  if (decision.response !== 0) throw new Error('已取消访问该目标地址。')
  approvedTargetOrigins.add(origin)
}

function isTextual(contentType) {
  const value = contentType.toLowerCase()
  return value.startsWith('text/') || value.includes('json') || value.includes('xml')
    || value.includes('javascript') || value.includes('graphql')
}

async function responseBody(response) {
  if (!response.body) return { body: '', responseSize: 0, truncated: false }
  const reader = response.body.getReader()
  const chunks = []
  let stored = 0
  let responseSize = 0
  let truncated = false
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    responseSize += value.byteLength
    const writable = Math.min(value.byteLength, MAX_RESPONSE_BYTES - stored)
    if (writable > 0) {
      chunks.push(Buffer.from(value.buffer, value.byteOffset, writable))
      stored += writable
    }
    if (writable < value.byteLength || stored >= MAX_RESPONSE_BYTES) {
      truncated = true
      await reader.cancel()
      break
    }
  }
  const bytes = Buffer.concat(chunks)
  const contentType = response.headers.get('content-type') || 'text/plain'
  return {
    body: isTextual(contentType) ? bytes.toString('utf8') : `data:${contentType};base64,${bytes.toString('base64')}`,
    responseSize,
    truncated,
  }
}

async function recordLocalExecution(targetSession, projectId, request, result) {
  const csrfResponse = await targetSession.fetch(`${activeServerUrl}/api/v1/auth/csrf`, { credentials: 'include' })
  const csrfEnvelope = await csrfResponse.json().catch(() => ({}))
  if (!csrfResponse.ok || !csrfEnvelope?.data?.token || !csrfEnvelope?.data?.headerName) {
    throw new Error(csrfEnvelope?.message || '无法获取桌面执行历史所需的安全会话。')
  }
  const response = await targetSession.fetch(`${activeServerUrl}/api/v1/projects/${encodeURIComponent(projectId)}/api-debug/local-executions`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      [csrfEnvelope.data.headerName]: csrfEnvelope.data.token,
    },
    body: JSON.stringify({
      request,
      result: {
        success: result.success,
        statusCode: result.statusCode,
        durationMs: result.durationMs,
        responseSize: result.responseSize,
        errorMessage: result.errorMessage,
      },
    }),
  })
  const envelope = await response.json().catch(() => ({}))
  if (!response.ok || !envelope?.data?.historyId) throw new Error(envelope?.message || '桌面执行历史保存失败。')
  return envelope.data.historyId
}

async function executeHttpDebug(event, value) {
  if (!isTrustedRemoteSender(event)) throw new Error('不受信任的桌面调试请求。')
  const projectId = String(value?.projectId ?? '')
  if (!/^[0-9A-Za-z_-]{1,64}$/.test(projectId)) throw new Error('桌面调试项目标识不合法。')
  const request = normalizeHttpDebugRequest(value?.request)
  await approveTarget(event, request)
  const started = Date.now()
  let result
  const controller = new globalThis.AbortController()
  const timeout = globalThis.setTimeout(() => controller.abort(), request.timeoutMs)
  try {
    const response = await net.fetch(request.url, {
      method: request.method,
      headers: request.headers.map((header) => [header.name, header.value]),
      body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
      credentials: 'omit',
      redirect: 'manual',
      signal: controller.signal,
    })
    const content = await responseBody(response)
    result = {
      historyId: '',
      success: true,
      statusCode: response.status,
      statusText: response.statusText || `HTTP ${response.status}`,
      durationMs: Date.now() - started,
      responseHeaders: Array.from(response.headers.entries()).map(([name, headerValue]) => ({ name, value: headerValue })),
      body: content.body,
      truncated: content.truncated,
      errorMessage: null,
      responseSize: content.responseSize,
    }
  } catch (error) {
    result = {
      historyId: '',
      success: false,
      statusCode: null,
      statusText: null,
      durationMs: Date.now() - started,
      responseHeaders: [],
      body: '',
      truncated: false,
      errorMessage: error?.name === 'AbortError' ? '连接目标服务超时' : (error?.message || '连接目标服务失败'),
      responseSize: 0,
    }
  } finally {
    globalThis.clearTimeout(timeout)
  }

  try {
    result.historyId = await recordLocalExecution(event.sender.session, projectId, request, result)
  } catch (error) {
    result.historyId = `desktop-${Date.now()}`
    result.historyWarning = error?.message || '桌面执行历史保存失败。'
  }
  delete result.responseSize
  return result
}

function openSetupWindow() {
  if (setupWindow && !setupWindow.isDestroyed()) {
    setupWindow.focus()
    return
  }
  setupWindow = new BrowserWindow({
    title: 'TestHub · 连接服务器',
    width: 620,
    height: 520,
    resizable: false,
    maximizable: false,
    backgroundColor: '#f5f7fa',
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      webviewTag: false,
      preload: path.join(__dirname, 'setup-preload.cjs'),
    },
  })
  denyAllPermissions(setupWindow.webContents.session)
  setupWindow.webContents.on('will-attach-webview', (event) => event.preventDefault())
  setupWindow.on('closed', () => { setupWindow = undefined })
  void setupWindow.loadURL(`${SETUP_ORIGIN}/setup.html`)
}

function installApplicationMenu() {
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {
      label: 'TestHub',
      submenu: [
        { label: '服务器设置', click: () => openSetupWindow() },
        { type: 'separator' },
        { role: 'quit', label: '退出 TestHub' },
      ],
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload', label: '刷新' },
        { role: 'togglefullscreen', label: '切换全屏' },
      ],
    },
  ]))
}

async function startApplication() {
  const environmentServerUrl = process.env.TESTHUB_DESKTOP_SERVER_URL
  if (environmentServerUrl) {
    openTestHubWindow(environmentServerUrl)
    return
  }
  const preferences = await readPreferences(app.getPath('userData'))
  if (preferences.serverUrl) {
    openTestHubWindow(preferences.serverUrl)
  } else {
    openSetupWindow()
  }
}

ipcMain.handle('testhub-desktop:preferences:load', async (event) => {
  if (!isSetupSender(event)) throw new Error('不受信任的桌面设置请求。')
  const preferences = await readPreferences(app.getPath('userData'))
  return { serverUrl: preferences.serverUrl ?? '' }
})

ipcMain.handle('testhub-desktop:preferences:save', async (event, value) => {
  if (!isSetupSender(event)) throw new Error('不受信任的桌面设置请求。')
  const normalized = await writePreferences(app.getPath('userData'), value)
  openTestHubWindow(normalized)
  if (setupWindow && !setupWindow.isDestroyed()) setupWindow.close()
  return { serverUrl: normalized }
})

ipcMain.handle('testhub-desktop:api-debug:execute', executeHttpDebug)

app.whenReady().then(async () => {
  protocol.handle('testhub-desktop', (request) => net.fetch(desktopAssetUrl(request.url)))
  installApplicationMenu()
  await startApplication()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) void startApplication()
  })
}).catch((error) => {
  console.error('TestHub Desktop failed to start:', error)
  app.exit(1)
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
