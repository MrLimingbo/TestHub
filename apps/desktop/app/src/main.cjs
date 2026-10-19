'use strict'

const { app, BrowserWindow, Menu, dialog, ipcMain, net, protocol, session, safeStorage } = require('electron')
const { Buffer } = require('node:buffer')
const path = require('node:path')
const crypto = require('node:crypto')
const fs = require('node:fs/promises')
const { exportWorkspace, passwordCheck } = require('./workspace-archive.cjs')
const { databaseTool, stageRestore, activateRestore, recoverInterruptedRestore } = require('./workspace-restore.cjs')
const { LocalRuntime, loadBundle, loadSecrets } = require('./local-runtime.cjs')
const { LocalBrowsers } = require('./local-browsers.cjs')
const { LocalEngines } = require('./local-engines.cjs')
const { FunctionalTransfer, jsonRequest } = require('./functional-transfer.cjs')
const { ApiTransfer, apiTransferResponseBudget } = require('./api-transfer.cjs')
const { AutomationTransfer, automationTransferResponseBudget } = require('./automation-transfer.cjs')
const { MockTransfer, mockTransferResponseBudget } = require('./mock-transfer.cjs')
const { MobileAutomationTransfer, mobileAutomationTransferResponseBudget } = require('./mobile-automation-transfer.cjs')
const { QualityReportTransfer, qualityReportTransferResponseBudget } = require('./quality-report-transfer.cjs')
const { TaskTransfer, taskTransferResponseBudget } = require('./task-transfer.cjs')
const { installTaskSync } = require('./task-sync-ipc.cjs')
const { installSettingsSync } = require('./settings-sync-ipc.cjs')
const { installNotificationSync } = require('./notification-sync-ipc.cjs')
const { installMockSync } = require('./mock-sync-ipc.cjs')
const { installPerformanceSync } = require('./performance-sync-ipc.cjs')
const { installUiAutomationSync } = require('./ui-automation-sync-ipc.cjs')
const { installMobileAutomationSync } = require('./mobile-automation-sync-ipc.cjs')
const { installFunctionalDefectWorkflowSync } = require('./functional-defect-workflow-sync-ipc.cjs')
const { installFunctionalDefectPeopleSync } = require('./functional-defect-people-sync-ipc.cjs')
const { installFunctionalDefectExecutionSync } = require('./functional-defect-execution-sync-ipc.cjs')
const { installFunctionalHistorySync } = require('./functional-history-sync-ipc.cjs')
const { installProjectFileSync } = require('./project-file-sync-ipc.cjs')
const { installFunctionalDefectLinkSync } = require('./functional-defect-link-sync-ipc.cjs')
const { installProjectVersionSync } = require('./project-version-sync-ipc.cjs')
const { installFunctionalDefectSync } = require('./functional-defect-sync-ipc.cjs')
const { installFunctionalAssetTagSync } = require('./functional-asset-tag-sync-ipc.cjs')
const { installFunctionalGovernanceSync } = require('./functional-governance-sync-ipc.cjs')
const { installFunctionalSync } = require('./functional-sync-ipc.cjs')
const { installApiDesignSync } = require('./api-design-sync-ipc.cjs')
const { installApiAutomationAssetSync } = require('./api-automation-asset-sync-ipc.cjs')
const { installApiAutomationScenarioSync } = require('./api-automation-scenario-sync-ipc.cjs')
const { installApiAutomationHistorySync } = require('./api-automation-history-sync-ipc.cjs')
const { installPerformanceHistorySync } = require('./performance-history-sync-ipc.cjs')
const { installPerformanceArtifactSync } = require('./performance-artifact-sync-ipc.cjs')
const { installUiAutomationHistorySync } = require('./ui-automation-history-sync-ipc.cjs')
const { installUiAutomationArtifactSync } = require('./ui-automation-artifact-sync-ipc.cjs')
const { installApiAutomationTriggerSync } = require('./api-automation-trigger-sync-ipc.cjs')
const { ensureSyncSession } = require('./project-sync-ipc.cjs')
const { SettingsTransfer, settingsTransferResponseBudget } = require('./settings-transfer.cjs')
const { NotificationTransfer, notificationTransferResponseBudget } = require('./notification-transfer.cjs')
const { UiAutomationTransfer, uiAutomationTransferResponseBudget } = require('./ui-automation-transfer.cjs')
const { PerformanceTransfer, performanceTransferResponseBudget } = require('./performance-transfer.cjs')
const { normalizeOrigins, readExecutionOrigins, writeExecutionOrigins, readExecutionSettings, writeExecutionSettings, normalizeAppiumServer } = require('./local-execution.cjs')
const { pathToFileURL } = require('node:url')
const {
  isTrustedServerUrl,
  normalizeHttpDebugRequest,
  normalizeServerUrl,
  readPreferences,
  writePreferences,
  selectLocalWorkspace,
} = require('./desktop-core.cjs')

if (!app.isPackaged && process.env.TESTHUB_DESKTOP_USER_DATA) app.setPath('userData', path.resolve(process.env.TESTHUB_DESKTOP_USER_DATA))
const ownsWorkspace = app.requestSingleInstanceLock()
if (!ownsWorkspace) app.quit()

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
let workspaceMode = 'server'
let taskSync, settingsSync, notificationSync, mockSync, performanceSync, uiAutomationSync, mobileAutomationSync, functionalSync, apiDesignSync, apiAutomationAssetSync, apiAutomationScenarioSync, apiAutomationHistorySync, performanceHistorySync, performanceArtifactSync, uiAutomationHistorySync, uiAutomationArtifactSync, apiAutomationTriggerSync, functionalGovernanceSync, functionalAssetTagSync, functionalDefectSync, projectVersionSync, functionalDefectLinkSync, functionalDefectWorkflowSync, functionalDefectPeopleSync, functionalDefectExecutionSync, functionalHistorySync, projectFileSync, taskSyncTimer
let localRuntime
let localEngines
let preparingJmeter
let localBrowsers
let preparingBrowser
let localStatus = { phase: 'stopped', message: '' }
let quitting = false
let savingExecutionSettings = false
let backupOperation
let transferOperation, transferClient, transferContextKey
let apiTransferClient, apiTransferContextKey
let automationTransferClient, automationTransferContextKey
let mockTransferClient, mockTransferContextKey
let performanceTransferClient, performanceTransferContextKey
let qualityReportTransferClient, qualityReportTransferContextKey
let taskTransferClient, taskTransferContextKey
let settingsTransferClient, settingsTransferContextKey
let notificationTransferClient, notificationTransferContextKey
let mobileAutomationTransferClient, mobileAutomationTransferContextKey
let uiAutomationTransferClient, uiAutomationTransferContextKey
let backupStatus = { busy: false, message: '', completedBytes: 0, totalBytes: 0 }
let enteringLocalWorkspace
const LOCAL_PARTITION = 'persist:testhub-local-workspace'
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

async function openTestHubWindow(nextServerUrl, mode = 'server') {
  const normalized = normalizeServerUrl(nextServerUrl)
  activeServerUrl = normalized
  workspaceMode = mode
  approvedTargetOrigins.clear()

  const previous = mainWindow
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
      partition: mode === 'local' ? LOCAL_PARTITION : 'persist:testhub-server-' + crypto.createHash('sha256').update(normalized).digest('hex').slice(0, 24),
      additionalArguments: [`--testhub-workspace=${mode}`],
    },
  })
  if (previous && !previous.isDestroyed()) previous.destroy()
  denyAllPermissions(mainWindow.webContents.session)
  applyRemoteContentRestrictions(mainWindow, normalized)
  const opened = mainWindow
  opened.on('closed', () => { if (mainWindow === opened) mainWindow = undefined })
  await opened.loadURL(normalized)
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

function openSetupWindow(page = 'setup.html') {
  if (setupWindow && !setupWindow.isDestroyed()) {
    if (backupOperation || transferOperation) { setupWindow.focus(); return }
    if (setupWindow.webContents.getURL() !== `${SETUP_ORIGIN}/${page}`) void setupWindow.loadURL(`${SETUP_ORIGIN}/${page}`)
    setupWindow.focus()
    return
  }
  setupWindow = new BrowserWindow({
    title: 'TestHub · 工作区',
    width: 620,
    height: 660,
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
  setupWindow.on('close', event => { if ((backupOperation || transferOperation) && !quitting) event.preventDefault() })
  setupWindow.on('closed', () => { setupWindow = undefined })
  void setupWindow.loadURL(`${SETUP_ORIGIN}/${page}`)
}

function installApplicationMenu() {
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {
      label: 'TestHub',
      submenu: [
        { label: '本地工作区', click: () => { void enterLocalWorkspace().catch(showLocalError) } },
        { label: '连接账号或企业服务', click: () => openSetupWindow() },
        { label: '本地备份与恢复', click: () => openSetupWindow('backup.html') },
        { label: '本地执行设置', click: () => openSetupWindow('execution.html') },
        { label: '任务登记同步', click: () => { void taskSync?.open().catch(showLocalError) } },
        { label: '项目配置同步', click: () => { void settingsSync?.open().catch(showLocalError) } },
        { label: '项目文件同步', click: () => { void projectFileSync?.open().catch(showLocalError) } },
        { label: 'Mock 同步', click: () => { void mockSync?.open().catch(showLocalError) } },
        { label: '性能计划同步', click: () => { void performanceSync?.open().catch(showLocalError) } },
        { label: '缺陷工作流同步', click: () => { void functionalDefectWorkflowSync?.open().catch(showLocalError) } },
        { label: '缺陷人员同步', click: () => { void functionalDefectPeopleSync?.open().catch(showLocalError) } },
        { label: '缺陷执行关联同步', click: () => { void functionalDefectExecutionSync?.open().catch(showLocalError) } },
        { label: '执行历史同步', click: () => { void functionalHistorySync?.open().catch(showLocalError) } },
        { label: '缺陷关联同步', click: () => { void functionalDefectLinkSync?.open().catch(showLocalError) } },
        { label: '版本与构建同步', click: () => { void projectVersionSync?.open().catch(showLocalError) } },
        { label: '缺陷资料同步', click: () => { void functionalDefectSync?.open().catch(showLocalError) } },
        { label: '标签关联同步', click: () => { void functionalAssetTagSync?.open().catch(showLocalError) } },
        { label: '治理定义同步', click: () => { void functionalGovernanceSync?.open().catch(showLocalError) } },
        { label: '功能测试资料同步', click: () => { void functionalSync?.open().catch(showLocalError) } },
        { label: '接口设计资料同步', click: () => { void apiDesignSync?.open().catch(showLocalError) } },
        { label: '接口自动化基础资料同步', click: () => { void apiAutomationAssetSync?.open().catch(showLocalError) } },
        { label: '接口自动化场景同步', click: () => { void apiAutomationScenarioSync?.open().catch(showLocalError) } },
        { label: '接口自动化执行历史同步', click: () => { void apiAutomationHistorySync?.open().catch(showLocalError) } },
        { label: '原始 JTL／日志同步', click: () => { void performanceArtifactSync?.open().catch(showLocalError) } },
        { label: '性能执行历史同步', click: () => { void performanceHistorySync?.open().catch(showLocalError) } },
        { label: 'Web UI 执行历史同步', click: () => { void uiAutomationHistorySync?.open().catch(showLocalError) } },
        { label: 'Web UI 报告产物同步', click: () => { void uiAutomationArtifactSync?.open().catch(showLocalError) } },
        { label: '接口自动化触发配置同步', click: () => { void apiAutomationTriggerSync?.open().catch(showLocalError) } },
        { label: '移动端资料同步', click: () => { void mobileAutomationSync?.open().catch(showLocalError) } },
        { label: 'Web UI 资料同步', click: () => { void uiAutomationSync?.open().catch(showLocalError) } },
        { label: '通知模板同步', click: () => { void notificationSync?.open().catch(showLocalError) } },
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

function showLocalError(error) {
  localStatus = { phase: 'error', message: error.message || '本地工作区启动失败，请重试。' }
  openSetupWindow()
}

function enterLocalWorkspace() {
  if (backupOperation || transferOperation) return Promise.reject(new Error('工作区正在备份、恢复或迁移，请稍候。'))
  if (!enteringLocalWorkspace) enteringLocalWorkspace = enterLocalWorkspaceOnce().finally(() => { enteringLocalWorkspace = undefined })
  return enteringLocalWorkspace
}
async function enterLocalWorkspaceOnce() {
  await initializeLocalRuntime()
  workspaceMode = 'local'
  openSetupWindow()
  const alreadyReady = localRuntime.phase === 'ready'
  const origin = await localRuntime.start()
  if (alreadyReady) {
    await localRuntime.authorizeSession((url, options) => session.fromPartition(LOCAL_PARTITION).fetch(url, options))
    await openTestHubWindow(origin, 'local')
    if (setupWindow && !setupWindow.isDestroyed() && !backupOperation) setupWindow.close()
  }
  if (workspaceMode === 'local') await selectLocalWorkspace(app.getPath('userData'))
}

async function initializeLocalRuntime() {
  if (!localRuntime) await recoverInterruptedRestore(app.getPath('userData'))
  if (!localRuntime) {
    localRuntime = new LocalRuntime({
      bundleDirectory: app.isPackaged ? path.join(process.resourcesPath, 'local-runtime', `${process.platform}-${process.arch}`)
        : process.env.TESTHUB_DESKTOP_RUNTIME_DIR || path.resolve(__dirname, '../../../../.testhub/desktop-runtime', `${process.platform}-${process.arch}`),
      dataDirectory: path.join(app.getPath('userData'), 'local-workspace'),
      getBrowserCache: async () => {
        const manager = await getLocalBrowsers()
        try { return (await manager.plan('chromium')).cache }
        catch (error) { manager.update('error', error.message); return undefined }
      },
      prepareBrowser: async browser => {
        const manager = await getLocalBrowsers()
        if (manager.pending && manager.preparing !== browser) await manager.pending
        await manager.ensure(browser)
      },
      getJmeterHome: async () => {
        const engines = await getLocalEngines()
        try { return await engines.installed() }
        catch (error) { engines.update('error', error.message); return undefined }
      },
      allowedOrigins: await readExecutionOrigins(app.getPath('userData')),
      appiumServerUrl: (await readExecutionSettings(app.getPath('userData'))).appiumServerUrl,
      codec: {
        encrypt: async value => {
          if (!await safeStorage.isAsyncEncryptionAvailable() || (process.platform === 'linux' && safeStorage.getSelectedStorageBackend() === 'basic_text'))
            throw new Error('系统安全存储不可用，请先解锁系统钥匙串。')
          return safeStorage.encryptStringAsync(value)
        },
        decrypt: async bytes => (await safeStorage.decryptStringAsync(bytes)).result,
      },
      onStatus: status => {
        localStatus = status
        if (status.phase === 'error' && workspaceMode === 'local' && !quitting) openSetupWindow()
      },
      onReady: async origin => {
        await localRuntime.authorizeSession((url, options) => session.fromPartition(LOCAL_PARTITION).fetch(url, options))
        if (workspaceMode === 'local' && !quitting) {
          await openTestHubWindow(origin, 'local')
          if (setupWindow && !setupWindow.isDestroyed() && !backupOperation) setupWindow.close()
        }
      },
    })
  }
}

async function startApplication() {
  const environmentServerUrl = process.env.TESTHUB_DESKTOP_SERVER_URL
  if (environmentServerUrl) { await openTestHubWindow(environmentServerUrl); return }
  const preferences = await readPreferences(app.getPath('userData'))
  if (preferences.serverUrl && preferences.mode !== 'local') await openTestHubWindow(preferences.serverUrl)
  else await enterLocalWorkspace()
}

ipcMain.handle('testhub-desktop:workspace:local', async event => {
  if (!isSetupSender(event)) throw new Error('不受信任的工作区请求。')
  await enterLocalWorkspace()
})
ipcMain.handle('testhub-desktop:workspace:resume', async event => {
  if (workspaceMode !== 'local' || !isTrustedRemoteSender(event) || event.senderFrame !== event.sender.mainFrame
    || activeServerUrl !== localRuntime?.url) throw new Error('不受信任的本地会话请求。')
  await localRuntime.authorizeSession((url, options) => event.sender.session.fetch(url, options))
})
ipcMain.handle('testhub-desktop:workspace:connect', async event => {
  if (!isTrustedRemoteSender(event) || event.senderFrame !== event.sender.mainFrame) throw new Error('不受信任的服务设置请求。')
  await enteringLocalWorkspace
  if (!isTrustedRemoteSender(event) || event.senderFrame !== event.sender.mainFrame) throw new Error('工作区已切换，请重新打开服务设置。')
  openSetupWindow()
})

async function getLocalEngines() {
  if (!localEngines) {
    const bundle = await loadBundle(localRuntime.bundleDirectory)
    localEngines ??= new LocalEngines({ directory: path.join(app.getPath('userData'), 'engines'), bundle,
      fetcher: (url, options) => net.fetch(url, options) })
  }
  return localEngines
}
async function getLocalBrowsers() {
  if (!localBrowsers) {
    const bundle = await loadBundle(localRuntime.bundleDirectory)
    localBrowsers ??= new LocalBrowsers({ directory: path.join(app.getPath('userData'), 'browser-engines'), bundle,
      fetcher: (url, options) => net.fetch(url, options) })
  }
  return localBrowsers
}
function requireLocalSender(event) {
  if (workspaceMode !== 'local' || !isTrustedRemoteSender(event) || event.senderFrame !== event.sender.mainFrame
    || activeServerUrl !== localRuntime?.url) throw new Error('不受信任的本地组件请求。')
}
ipcMain.handle('testhub-desktop:jmeter:status', event => {
  requireLocalSender(event)
  return localEngines?.status ?? { phase: 'idle', message: '', receivedBytes: 0, totalBytes: 0 }
})
ipcMain.handle('testhub-desktop:jmeter:cancel', event => { requireLocalSender(event); localEngines?.cancel() })
ipcMain.handle('testhub-desktop:jmeter:ensure', async (event, targets) => {
  requireLocalSender(event)
  if (localRuntime.phase !== 'ready' || savingExecutionSettings || backupOperation) throw new Error('请等待本地工作区就绪后重试。')
  if (!Array.isArray(targets) || targets.length < 1 || targets.length > 10
    || targets.some(target => typeof target !== 'string' || !localRuntime.allowedOrigins.includes(target)))
    throw new Error('请先在本地执行设置中添加此压测计划的目标地址。')
  if (!preparingJmeter) {
    const generation = localRuntime.generation
    preparingJmeter = (async () => {
      const engines = await getLocalEngines()
      const home = await engines.ensure()
      if (generation !== localRuntime.generation || quitting || workspaceMode !== 'local') throw new Error('工作区已切换，请重新提交压测。')
      engines.update('starting', '正在启动 JMeter…')
      await localRuntime.startJmeter(home)
      engines.update('ready', 'JMeter 已就绪')
    })().catch(error => { localEngines?.update('error', error.message); throw error }).finally(() => { preparingJmeter = undefined })
  }
  await preparingJmeter
})

ipcMain.handle('testhub-desktop:browser:status', event => {
  requireLocalSender(event)
  return localBrowsers?.status ?? { phase: 'idle', message: '', receivedBytes: 0, totalBytes: 0 }
})
ipcMain.handle('testhub-desktop:browser:cancel', event => { requireLocalSender(event); localBrowsers?.cancel() })
ipcMain.handle('testhub-desktop:browser:ensure', async (event, value) => {
  requireLocalSender(event)
  if (localRuntime.phase !== 'ready' || savingExecutionSettings || backupOperation) throw new Error('请等待本地工作区就绪后重试。')
  if (!['chromium', 'firefox', 'webkit'].includes(value?.browser) || !localRuntime.allowedOrigins.includes(value?.origin))
    throw new Error('请先在本地执行设置中添加浏览器项目的目标地址。')
  if (preparingBrowser) throw new Error('正在准备浏览器，请完成后重试。')
  const generation = localRuntime.generation
  preparingBrowser = (async () => {
    const browsers = await getLocalBrowsers(), cache = await browsers.ensure(value.browser)
    if (generation !== localRuntime.generation || quitting || workspaceMode !== 'local') throw new Error('工作区已切换，请重新执行场景。')
    browsers.update('starting', '正在启动本地浏览器运行器…')
    await localRuntime.startPlaywright(cache)
    browsers.update('ready', '浏览器已就绪')
  })().catch(error => { localBrowsers?.update('error', error.message); throw error }).finally(() => { preparingBrowser = undefined })
  await preparingBrowser
})

ipcMain.handle('testhub-desktop:appium:ensure', event => {
  requireLocalSender(event)
  return runApiTransfer(async () => {
    await initializeLocalRuntime(); await localRuntime.start(); await localRuntime.startAppium()
  })
})
ipcMain.handle('testhub-desktop:execution:open', event => {
  if (workspaceMode !== 'local' || !isTrustedRemoteSender(event) || event.senderFrame !== event.sender.mainFrame)
    throw new Error('不受信任的本地执行设置请求。')
  openSetupWindow('execution.html')
})
function isExecutionSettingsSender(event) {
  return event.senderFrame === event.sender.mainFrame && event.senderFrame?.url === `${SETUP_ORIGIN}/execution.html`
}
ipcMain.handle('testhub-desktop:execution:load', event => {
  if (!isExecutionSettingsSender(event)) throw new Error('不受信任的本地执行设置请求。')
  return readExecutionOrigins(app.getPath('userData'))
})
ipcMain.handle('testhub-desktop:execution:save', async (event, value) => {
  if (!isExecutionSettingsSender(event)) throw new Error('不受信任的本地执行设置请求。')
  if (backupOperation || savingExecutionSettings || preparingJmeter || preparingBrowser || localBrowsers?.pending || enteringLocalWorkspace || (localRuntime && ['starting', 'recovering', 'stopping'].includes(localRuntime.phase)))
    throw new Error('工作区正在启动或恢复，请就绪后重试。')
  const origins = normalizeOrigins(value)
  savingExecutionSettings = true
  try {
    await writeExecutionOrigins(app.getPath('userData'), origins)
    if (localRuntime) { await localRuntime.stop(); localRuntime.allowedOrigins = origins }
    await enterLocalWorkspace()
  } finally { savingExecutionSettings = false }
})

ipcMain.handle('testhub-desktop:execution:settings', event => {
  if (!isExecutionSettingsSender(event) || event.sender !== setupWindow?.webContents) throw new Error('不受信任的本地执行设置请求。')
  return readExecutionSettings(app.getPath('userData'))
})
ipcMain.handle('testhub-desktop:execution:save-settings', async (event, value) => {
  if (!isExecutionSettingsSender(event) || event.sender !== setupWindow?.webContents) throw new Error('不受信任的本地执行设置请求。')
  if (backupOperation || transferOperation || savingExecutionSettings || preparingJmeter || preparingBrowser || localBrowsers?.pending || enteringLocalWorkspace
    || (localRuntime && ['starting', 'recovering', 'stopping'].includes(localRuntime.phase))) throw new Error('工作区正在启动或恢复，请就绪后重试。')
  const configuration = { allowedOrigins: normalizeOrigins(value?.allowedOrigins), appiumServerUrl: normalizeAppiumServer(value?.appiumServerUrl) }
  savingExecutionSettings = true
  try {
    await writeExecutionSettings(app.getPath('userData'), configuration)
    if (localRuntime) { await localRuntime.stop(); localRuntime.allowedOrigins = configuration.allowedOrigins; localRuntime.appiumServerUrl = configuration.appiumServerUrl }
    await enterLocalWorkspace()
  } finally { savingExecutionSettings = false }
})

function requireBackupSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/backup.html`) throw new Error('不受信任的备份请求。')
}
function requireTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/transfer.html`) throw new Error('不受信任的迁移请求。')
}
async function getTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  await initializeLocalRuntime()
  await localRuntime.start()
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, localSession = session.fromPartition(LOCAL_PARTITION)
  const localOrigin = localRuntime.url, key = `${endpoint}|${localOrigin}|${mainWindow.webContents.id}`
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if (!transferClient || key !== transferContextKey) {
    transferContextKey = key
    transferClient = new FunctionalTransfer({ current: () => `${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href, options, (route.includes('?version=5&') || route.includes('includeFiles=true')) ? 33 * 1024 * 1024 : 9 * 1024 * 1024),
      remote: (route, options) => {
        if (workspaceMode !== 'server' || endpoint !== activeServerUrl || remoteSession !== mainWindow?.webContents.session) throw new Error('账号服务已变化，请重新打开迁移页面。')
        return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
      } })
  }
  return transferClient
}
function runTransfer(event, operation) {
  requireTransferSender(event)
  if (backupOperation || transferOperation || savingExecutionSettings || enteringLocalWorkspace) throw new Error('其他工作区操作正在进行，请稍后重试。')
  transferOperation = operation().finally(() => { transferOperation = undefined })
  return transferOperation
}
ipcMain.handle('testhub-desktop:transfer:open', event => {
  if (workspaceMode !== 'server' || event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || !isTrustedRemoteSender(event)) throw new Error('请从已登录的账号工作区打开迁移页面。')
  openSetupWindow('transfer.html')
})
ipcMain.handle('testhub-desktop:transfer:context', event => runTransfer(event, async () => ({ ...(await (await getTransferClient()).context()), server: activeServerUrl })))
ipcMain.handle('testhub-desktop:transfer:prepare', (event, value) => runTransfer(event, async () => (await getTransferClient()).prepare(value?.sourceId, value?.targetId, value?.includePlans, value?.includeGovernance, value?.includeDefects, value?.includeFiles, value?.includeHistory, value?.includeProjectVersions, value?.includeGovernanceCatalogs)))
ipcMain.handle('testhub-desktop:transfer:commit', (event, token) => runTransfer(event, async () => {
  const client = await getTransferClient(), plan = client.plan
  if (!plan || plan.token !== token) throw new Error('请重新预览迁移资料。')
  const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传本地基础资料',
    message: `将 ${plan.snapshot.requirements.length} 条需求、${plan.snapshot.cases.length} 条用例和 ${plan.snapshot.plans?.length || 0} 个计划复制到 ${plan.actor.account} 的“${plan.target.name}”？`,
    detail: `目标服务：${activeServerUrl}\n${plan.snapshot.governance ? `同时上传 ${plan.snapshot.governance.tags.length} 个标签、${plan.snapshot.governance.fields.length} 个字段定义及所选资产的标签关联。\n` : ''}${plan.snapshot.defects ? `同时上传 ${plan.snapshot.defects.items.length} 个缺陷；不迁入 ${plan.snapshot.defects.items.reduce((sum, item) => sum + item.omittedExecutionLinks, 0)} 条执行记录关联，清除原人员指派和处理时间。\n` : ''}${plan.snapshot.files ? `同时上传项目文件库的 ${plan.snapshot.files.items.length} 个文件（包括未引用的文件），合计 ${(plan.snapshot.files.items.reduce((sum, item) => sum + item.size, 0) / 1024 / 1024).toFixed(2)} MiB。\n` : ''}仅上传已预览资料和所选项目文件。${plan.snapshot.history ? `同时上传 ${plan.snapshot.history.runs.length} 个执行批次及其用例、步骤结果和历史时间；不复制原执行人员。` : '执行历史不上传。'}${plan.snapshot.projectVersions ? `同时上传 ${plan.snapshot.projectVersions.items.length} 个项目版本，保留版本号、构建、发布状态和日期，归当前账号所有。` : ''}${plan.snapshot.version === 8 ? `同时复制 ${plan.snapshot.catalogs.filter(item => item.scope === 'GOVERNANCE').length} 个治理目录，保留层级与顺序。` : ''}其他资料不上传，本地原件保留。`,
    buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
  if (decision.response !== 1) return { cancelled: true }
  return client.commit(token)
}))
function requireApiTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/api-transfer.html`) throw new Error('不受信任的接口迁移请求。')
}
function runApiTransfer(operation) {
  if (backupOperation || transferOperation || savingExecutionSettings || enteringLocalWorkspace) throw new Error('其他工作区操作正在进行，请稍后重试。')
  transferOperation = Promise.resolve().then(operation).finally(() => { transferOperation = undefined })
  return transferOperation
}
async function getApiTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime()
  await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  // Starting the local backend can take seconds; the account may change during that wait.
  const currentActor = await remote('/api/v1/auth/me')
  if (currentActor?.id !== actor.id) {
    apiTransferClient = undefined
    apiTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!apiTransferClient || key !== apiTransferContextKey) {
    apiTransferContextKey = key
    apiTransferClient = new ApiTransfer({ current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href, options, apiTransferResponseBudget(route)), remote })
  }
  return apiTransferClient
}
ipcMain.handle('testhub-desktop:api-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event))) throw new Error('不受信任的接口迁移请求。')
  return runApiTransfer(async () => {
    await getApiTransferClient()
    // Navigation runs within the shared lock, after authentication has succeeded.
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/api-transfer.html`).catch(showLocalError)
    else openSetupWindow('api-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:api-transfer:context', event => {
  requireApiTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getApiTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:api-transfer:prepare', (event, value) => {
  requireApiTransferSender(event)
  return runApiTransfer(async () => (await getApiTransferClient()).prepare(value?.sourceId, value?.targetId, value?.includeFiles, value?.includeGrpc, value?.includeHistory))
})
ipcMain.handle('testhub-desktop:api-transfer:commit', (event, token) => {
  requireApiTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getApiTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传接口设计资产',
      message: `将“${plan.source.name}”的 ${counts.catalogs} 个目录、${counts.definitions} 个接口定义、${counts.versions} 个接口版本和 ${counts.cases} 条接口用例上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n接口请求正文和示例可能包含敏感值；确认即同意上传所选项目接口数据。${counts.files ? `同时上传全部 ${counts.files.items} 个项目文件（含未引用文件），合计 ${(counts.files.bytes / 1024 / 1024).toFixed(2)} MiB。` : '不上传项目文件。'}保留环境代码，目标必须已有对应且启用的环境。引用源文件但未选择文件、环境缺失或禁用、引用未选择的 gRPC 描述符时将阻断迁移。${counts.grpcDescriptors == null ? '不上传 gRPC 描述符。' : `同时上传 ${counts.grpcDescriptors} 个 gRPC 描述符并重映射引用。`}${counts.debugHistory == null ? '不上传调试历史。' : `同时上传 ${counts.debugHistory} 条历史及可回放草稿，其中可能包含认证凭据、请求正文及私有目标地址。目标重新加密草稿，保留历史时间并标记为迁入历史，不执行请求。`}不包含会话临时快捷请求副本、独立环境参数与凭据库或偏好设置。保留发布状态和历史发布时间，可能影响目标 Mock 示例来源；不保留原发布人。本地原件不动。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireAutomationTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/automation-transfer.html`) throw new Error('不受信任的接口自动化迁移请求。')
}
async function getAutomationTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime()
  await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    automationTransferClient = undefined; automationTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!automationTransferClient || key !== automationTransferContextKey) {
    automationTransferContextKey = key
    automationTransferClient = new AutomationTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, automationTransferResponseBudget(route)), remote,
    })
  }
  return automationTransferClient
}
ipcMain.handle('testhub-desktop:automation-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的接口自动化迁移请求。')
  return runApiTransfer(async () => {
    await getAutomationTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/automation-transfer.html`).catch(showLocalError)
    else openSetupWindow('automation-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:automation-transfer:context', event => {
  requireAutomationTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getAutomationTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:automation-transfer:prepare', (event, value) => {
  requireAutomationTransferSender(event)
  return runApiTransfer(async () => (await getAutomationTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:automation-transfer:commit', (event, token) => {
  requireAutomationTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getAutomationTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传接口自动化资料',
      message: `将“${plan.source.name}”的 ${counts.scenarios} 个场景、${counts.reusableSteps} 个公共步骤、${counts.dataSets} 个数据集、${counts.executionProfiles} 个运行配置及 ${counts.schedules} 个计划上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n会上传全部历史修订及数据集来源文件（${counts.dataSetSourceFiles} 个）；场景脚本、运行配置和数据行可能包含敏感值。目标项目必须已有对应且启用的运行环境。迁入计划一律停用，需要手动检查后启用。执行历史和 Webhook 密钥不迁移；Webhook 请在目标端重新创建。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireMockTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/mock-transfer.html`) throw new Error('不受信任的 Mock 迁移请求。')
}
async function getMockTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime()
  await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    mockTransferClient = undefined; mockTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!mockTransferClient || key !== mockTransferContextKey) {
    mockTransferContextKey = key
    mockTransferClient = new MockTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, mockTransferResponseBudget(route)), remote,
    })
  }
  return mockTransferClient
}
ipcMain.handle('testhub-desktop:mock-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的 Mock 迁移请求。')
  return runApiTransfer(async () => {
    await getMockTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/mock-transfer.html`).catch(showLocalError)
    else openSetupWindow('mock-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:mock-transfer:context', event => {
  requireMockTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getMockTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:mock-transfer:prepare', (event, value) => {
  requireMockTransferSender(event)
  return runApiTransfer(async () => (await getMockTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:mock-transfer:commit', (event, token) => {
  requireMockTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getMockTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传 Mock 资料',
      message: `将“${plan.source.name}”的 ${counts.servers} 个服务、${counts.rules} 条规则和 ${counts.shares} 条分享元数据上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n规则条件和响应可能包含敏感值。迁入服务全部停用并生成新公开地址，分享链接均撤销。智能示例开关会保留，但依赖目标项目已有已发布接口设计。检查后需手动启用服务、重建分享。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requirePerformanceTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/performance-transfer.html`) throw new Error('不受信任的性能迁移请求。')
}
async function getPerformanceTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    performanceTransferClient = undefined; performanceTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!performanceTransferClient || key !== performanceTransferContextKey) {
    performanceTransferContextKey = key
    performanceTransferClient = new PerformanceTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, performanceTransferResponseBudget(route)), remote,
    })
  }
  return performanceTransferClient
}
ipcMain.handle('testhub-desktop:performance-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的性能迁移请求。')
  return runApiTransfer(async () => {
    await getPerformanceTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/performance-transfer.html`).catch(showLocalError)
    else openSetupWindow('performance-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:performance-transfer:context', event => {
  requirePerformanceTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getPerformanceTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:performance-transfer:prepare', (event, value) => {
  requirePerformanceTransferSender(event)
  return runApiTransfer(async () => (await getPerformanceTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:performance-transfer:commit', (event, token) => {
  requirePerformanceTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getPerformanceTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传性能资料',
      message: `将“${plan.source.name}”的 ${counts.plans} 个计划、${counts.threadGroups} 个线程组和 ${counts.csvDataSets} 份内联 CSV 上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n请求配置和 CSV 可能包含敏感值。迁入计划全部为草稿，不会提交压测。目标 URL 原样保留，localhost 在其他电脑上指向不同服务；核对目标和负载后再设为待执行。运行器和目标授权需单独配置。本次不迁入执行历史、指标、JTL 或日志工件。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireUiAutomationTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/ui-automation-transfer.html`) throw new Error('不受信任的Web UI迁移请求。')
}
async function getUiAutomationTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    uiAutomationTransferClient = undefined; uiAutomationTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!uiAutomationTransferClient || key !== uiAutomationTransferContextKey) {
    uiAutomationTransferContextKey = key
    uiAutomationTransferClient = new UiAutomationTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, uiAutomationTransferResponseBudget(route)), remote,
    })
  }
  return uiAutomationTransferClient
}
ipcMain.handle('testhub-desktop:ui-automation-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的Web UI迁移请求。')
  return runApiTransfer(async () => {
    await getUiAutomationTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/ui-automation-transfer.html`).catch(showLocalError)
    else openSetupWindow('ui-automation-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:ui-automation-transfer:context', event => {
  requireUiAutomationTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getUiAutomationTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:ui-automation-transfer:prepare', (event, value) => {
  requireUiAutomationTransferSender(event)
  return runApiTransfer(async () => (await getUiAutomationTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:ui-automation-transfer:commit', (event, token) => {
  requireUiAutomationTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getUiAutomationTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传 Web UI 资料',
      message: `将“${plan.source.name}”的 ${counts.profiles} 个浏览器配置、${counts.pageObjects} 个页面对象、${counts.scenarios} 个场景（${counts.steps} 个步骤）和 ${counts.schedules} 个定时计划上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n变量和步骤可能包含敏感值。浏览器配置与定时计划全部停用，不提交任何执行。localhost 和 IP 地址原样保留，目标授权与运行器需重新配置；启用仍受原有目标策略约束。关联按功能测试计划编号匹配目标，缺失时整批停止。本次不迁入执行历史、截图、录屏或 Trace。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireMobileAutomationTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/mobile-automation-transfer.html`) throw new Error('不受信任的移动端迁移请求。')
}
async function getMobileAutomationTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    mobileAutomationTransferClient = undefined; mobileAutomationTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!mobileAutomationTransferClient || key !== mobileAutomationTransferContextKey) {
    mobileAutomationTransferContextKey = key
    mobileAutomationTransferClient = new MobileAutomationTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, mobileAutomationTransferResponseBudget(route)), remote,
    })
  }
  return mobileAutomationTransferClient
}
ipcMain.handle('testhub-desktop:mobile-automation-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的移动端迁移请求。')
  return runApiTransfer(async () => {
    await getMobileAutomationTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/mobile-automation-transfer.html`).catch(showLocalError)
    else openSetupWindow('mobile-automation-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:mobile-automation-transfer:context', event => {
  requireMobileAutomationTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getMobileAutomationTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:mobile-automation-transfer:prepare', (event, value) => {
  requireMobileAutomationTransferSender(event)
  return runApiTransfer(async () => (await getMobileAutomationTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:mobile-automation-transfer:commit', (event, token) => {
  requireMobileAutomationTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getMobileAutomationTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传 移动端 资料',
      message: `将“${plan.source.name}”的 ${counts.devices} 个设备、${counts.applications} 个应用、${counts.elements} 个元素和 ${counts.scenarios} 个场景（${counts.steps} 个步骤）上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n设备标识、能力、变量和步骤可能包含敏感值。迁入设备和应用全部停用，不提交执行。需要在目标端核对设备、Appium 服务和驱动后手动启用。${counts.detachedFiles} 个应用的本地包关联将解除；不复制 APK/IPA、执行历史或截图。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireQualityReportTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/quality-report-transfer.html`) throw new Error('不受信任的质量报告迁移请求。')
}
async function getQualityReportTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    qualityReportTransferClient = undefined; qualityReportTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!qualityReportTransferClient || key !== qualityReportTransferContextKey) {
    qualityReportTransferContextKey = key
    qualityReportTransferClient = new QualityReportTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, qualityReportTransferResponseBudget(route)), remote,
    })
  }
  return qualityReportTransferClient
}
ipcMain.handle('testhub-desktop:quality-report-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的质量报告迁移请求。')
  return runApiTransfer(async () => {
    await getQualityReportTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/quality-report-transfer.html`).catch(showLocalError)
    else openSetupWindow('quality-report-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:quality-report-transfer:context', event => {
  requireQualityReportTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getQualityReportTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:quality-report-transfer:prepare', (event, value) => {
  requireQualityReportTransferSender(event)
  return runApiTransfer(async () => (await getQualityReportTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:quality-report-transfer:commit', (event, token) => {
  requireQualityReportTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getQualityReportTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传 质量报告 资料',
      message: `将“${plan.source.name}”的 ${counts.schedules} 个定时质量报告（${counts.projects} 个关联项目）上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n所有计划全部停用，不运行或发送通知。来源项目映射到目标项目，其他项目按目标组织中唯一的项目编码匹配，缺失或无权限时整批停止。本次不复制历史报告、运行记录、通知模板或投递记录；统计从目标业务记录重新计算。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireTaskTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/task-transfer.html`) throw new Error('不受信任的任务登记迁移请求。')
}
async function getTaskTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    taskTransferClient = undefined; taskTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!taskTransferClient || key !== taskTransferContextKey) {
    taskTransferContextKey = key
    taskTransferClient = new TaskTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, taskTransferResponseBudget(route)), remote,
    })
  }
  return taskTransferClient
}
ipcMain.handle('testhub-desktop:task-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的任务登记迁移请求。')
  return runApiTransfer(async () => {
    await getTaskTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/task-transfer.html`).catch(showLocalError)
    else openSetupWindow('task-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:task-transfer:context', event => {
  requireTaskTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getTaskTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:task-transfer:prepare', (event, value) => {
  requireTaskTransferSender(event)
  return runApiTransfer(async () => (await getTaskTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:task-transfer:commit', (event, token) => {
  requireTaskTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getTaskTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传 任务登记 资料',
      message: `将“${plan.source.name}”的 ${counts.tasks} 条任务登记（${counts.cancelledActiveTasks} 条活动登记转为取消）上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n本次复制手工任务登记。活动登记转为取消，已结束状态和进度保留，来源状态、版本和时间作为只读摘要。不会启动测试或创建执行请求；同名登记新增，不覆盖目标。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireSettingsTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/settings-transfer.html`) throw new Error('不受信任的项目配置迁移请求。')
}
async function getSettingsTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    settingsTransferClient = undefined; settingsTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!settingsTransferClient || key !== settingsTransferContextKey) {
    settingsTransferContextKey = key
    settingsTransferClient = new SettingsTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, settingsTransferResponseBudget(route)), remote,
    })
  }
  return settingsTransferClient
}
ipcMain.handle('testhub-desktop:settings-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的项目配置迁移请求。')
  return runApiTransfer(async () => {
    await getSettingsTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/settings-transfer.html`).catch(showLocalError)
    else openSetupWindow('settings-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:settings-transfer:context', event => {
  requireSettingsTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getSettingsTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:settings-transfer:prepare', (event, value) => {
  requireSettingsTransferSender(event)
  return runApiTransfer(async () => (await getSettingsTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:settings-transfer:commit', (event, token) => {
  requireSettingsTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getSettingsTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传项目环境与普通参数',
      message: `将“${plan.source.name}”的 ${counts.environments} 个环境、${counts.parameters} 个普通参数上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n排除 ${counts.excludedSecrets} 个密钥，需在目标重新填写。保留目标默认环境；相同配置复用、不同配置报冲突，整批不覆盖。成员、令牌和旧日志不会复制。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function requireNotificationTransferSender(event) {
  if (event.sender !== setupWindow?.webContents || event.senderFrame !== event.sender.mainFrame
    || event.senderFrame?.url !== `${SETUP_ORIGIN}/notification-transfer.html`) throw new Error('不受信任的通知模板迁移请求。')
}
async function getNotificationTransferClient() {
  if (workspaceMode !== 'server' || !mainWindow || mainWindow.isDestroyed()) throw new Error('请先连接账号服务并登录。')
  const endpoint = activeServerUrl, remoteSession = mainWindow.webContents.session, contentsId = mainWindow.webContents.id
  const remote = (route, options) => {
    if (workspaceMode !== 'server' || endpoint !== activeServerUrl || contentsId !== mainWindow?.webContents.id) throw new Error('账号服务已变化，请重新打开迁移页面。')
    return jsonRequest((url, init) => remoteSession.fetch(url, init), new URL(route, endpoint).href, options)
  }
  const actor = await remote('/api/v1/auth/me')
  if (!actor?.id) throw new Error('请先登录账号服务。')
  await initializeLocalRuntime(); await localRuntime.start()
  const localSession = session.fromPartition(LOCAL_PARTITION), localOrigin = localRuntime.url
  await localRuntime.authorizeSession((url, options) => localSession.fetch(url, options))
  if ((await remote('/api/v1/auth/me'))?.id !== actor.id) {
    notificationTransferClient = undefined; notificationTransferContextKey = undefined
    throw new Error('账号已变化，请重新打开迁移页面。')
  }
  const key = `${endpoint}|${localOrigin}|${contentsId}|${actor.id}`
  if (!notificationTransferClient || key !== notificationTransferContextKey) {
    notificationTransferContextKey = key
    notificationTransferClient = new NotificationTransfer({
      current: () => `${workspaceMode}|${activeServerUrl}|${localRuntime?.url}|${mainWindow?.webContents.id}`,
      local: (route, options) => jsonRequest((url, init) => localSession.fetch(url, init), new URL(route, localOrigin).href,
        options, notificationTransferResponseBudget(route)), remote,
    })
  }
  return notificationTransferClient
}
ipcMain.handle('testhub-desktop:notification-transfer:open', event => {
  const nativeTransfer = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/transfer.html`
  if (!nativeTransfer && (event.sender !== mainWindow?.webContents || event.senderFrame !== event.sender.mainFrame || !isTrustedRemoteSender(event)))
    throw new Error('不受信任的通知模板迁移请求。')
  return runApiTransfer(async () => {
    await getNotificationTransferClient()
    if (setupWindow && !setupWindow.isDestroyed()) void setupWindow.loadURL(`${SETUP_ORIGIN}/notification-transfer.html`).catch(showLocalError)
    else openSetupWindow('notification-transfer.html')
  })
})
ipcMain.handle('testhub-desktop:notification-transfer:context', event => {
  requireNotificationTransferSender(event)
  return runApiTransfer(async () => ({ ...(await (await getNotificationTransferClient()).context()), server: activeServerUrl }))
})
ipcMain.handle('testhub-desktop:notification-transfer:prepare', (event, value) => {
  requireNotificationTransferSender(event)
  return runApiTransfer(async () => (await getNotificationTransferClient()).prepare(value?.sourceId, value?.targetId))
})
ipcMain.handle('testhub-desktop:notification-transfer:commit', (event, token) => {
  requireNotificationTransferSender(event)
  return runApiTransfer(async () => {
    const client = await getNotificationTransferClient(), plan = client.validPlan(token), counts = plan.counts
    const decision = await dialog.showMessageBox(setupWindow, { type: 'question', title: '确认上传停用通知模板',
      message: `将“${plan.source.name}”的 ${counts.templates} 个停用通知模板上传到 ${plan.actor.account} 的“${plan.target.name}”？`,
      detail: `目标服务：${activeServerUrl}\n排除 ${counts.excludedTargets} 个接收地址、${counts.excludedSecrets} 个签名密钥，需在目标独立配置。全部新模板停用，同名模板报冲突，整批不覆盖。网络授权和投递历史不复制，不重放旧通知。本地原件不动，后续编辑不会自动同步。`,
      buttons: ['取消', '确认上传'], defaultId: 0, cancelId: 0 })
    if (decision.response !== 1) return { cancelled: true }
    return client.commit(token)
  })
})
function updateBackup(message, completedBytes = 0, totalBytes = 0) {
  backupStatus = { busy: Boolean(backupOperation), message, completedBytes, totalBytes }
}
ipcMain.handle('testhub-desktop:backup:open', event => {
  const nativeSetup = event.sender === setupWindow?.webContents && event.senderFrame === event.sender.mainFrame
    && event.senderFrame?.url === `${SETUP_ORIGIN}/setup.html`
  if (!nativeSetup) requireLocalSender(event)
  openSetupWindow('backup.html')
})
ipcMain.handle('testhub-desktop:backup:status', event => { requireBackupSender(event); return { ...backupStatus, busy: Boolean(backupOperation) } })
ipcMain.handle('testhub-desktop:backup:run', async (event, value) => {
  requireBackupSender(event)
  if (!['export', 'restore'].includes(value?.action)) throw new Error('不支持的备份操作。')
  passwordCheck(value.password)
  const allowedPhases = value.action === 'restore' ? ['ready', 'error', 'stopped'] : ['ready']
  if (backupOperation || transferOperation || workspaceMode !== 'local' || !allowedPhases.includes(localRuntime?.phase) || savingExecutionSettings
    || preparingJmeter || preparingBrowser || localBrowsers?.pending || enteringLocalWorkspace)
    throw new Error('请先进入本地工作区，并等待启动或组件准备完成。')
  const password = value.password, parent = app.getPath('userData')
  backupOperation = (async () => {
    let candidate
    try {
      if (value.action === 'export') {
        const choice = await dialog.showSaveDialog(setupWindow, { title: '保存加密工作区备份',
          defaultPath: path.join(app.getPath('documents'), `TestHub-${new Date().toISOString().replace(/[:.]/g, '-')}.testhub-backup`),
          filters: [{ name: 'TestHub 加密备份', extensions: ['testhub-backup'] }] })
        if (choice.canceled || !choice.filePath) { updateBackup('操作已取消，当前工作区保持不变。'); return { cancelled: true } }
        const destination = path.join(await fs.realpath(path.dirname(choice.filePath)), path.basename(choice.filePath))
        const privateRoot = await fs.realpath(parent)
        if (destination === privateRoot || destination.startsWith(privateRoot + path.sep)) throw new Error('请选择 TestHub 数据目录以外的备份位置。')
        updateBackup('正在暂停本地工作区…')
        await localRuntime.stop()
        try {
          const secrets = await loadSecrets(localRuntime.dataDirectory, localRuntime.codec)
          const report = await databaseTool(localRuntime.bundle, localRuntime.dataDirectory, secrets)
          updateBackup('正在加密数据库与附件…')
          const result = await exportWorkspace({ directory: localRuntime.dataDirectory, destination, password, secrets, databaseVersion: report.databaseVersion,
            progress: (done, total) => updateBackup('正在加密数据库与附件…', done, total) })
          updateBackup(`备份已保存：${destination}`)
          return { ...result, destination }
        } finally { await localRuntime.start() }
      }
      const choice = await dialog.showOpenDialog(setupWindow, { title: '选择要恢复的工作区备份', properties: ['openFile'],
        filters: [{ name: 'TestHub 加密备份', extensions: ['testhub-backup'] }] })
      if (choice.canceled || !choice.filePaths[0]) { updateBackup('操作已取消，当前工作区保持不变。'); return { cancelled: true } }
      updateBackup('正在验证并准备备份；当前工作区保持可用…')
      candidate = await stageRestore({ parent, source: choice.filePaths[0], password, codec: localRuntime.codec,
        bundle: localRuntime.bundle || await loadBundle(localRuntime.bundleDirectory),
        progress: (done, total) => updateBackup('正在验证并准备备份…', done, total) })
      const confirmation = await dialog.showMessageBox(setupWindow, { type: 'question', title: '恢复本地工作区',
        message: `备份创建于 ${candidate.createdAt}，包含 ${candidate.fileCount} 个数据文件。`,
        detail: '恢复后将切换到备份中的工作区。当前工作区会完整保留为回滚目录；定时计划、Webhook、待发通知和未完成的执行不会重放，测试目标需要重新授权。请先保存页面上的修改。',
        buttons: ['取消', '恢复并保留当前工作区'], defaultId: 0, cancelId: 0 })
      if (confirmation.response !== 1) { updateBackup('操作已取消，当前工作区保持不变。'); return { cancelled: true } }
      await taskSync?.suspendForRestore()
      await settingsSync?.suspendForRestore()
      await notificationSync?.suspendForRestore()
      await mockSync?.suspendForRestore()
      await performanceSync?.suspendForRestore()
      await uiAutomationSync?.suspendForRestore()
      await mobileAutomationSync?.suspendForRestore()
      await functionalSync?.suspendForRestore()
      await apiDesignSync?.suspendForRestore()
      await apiAutomationAssetSync?.suspendForRestore()
      await apiAutomationScenarioSync?.suspendForRestore()
      await apiAutomationHistorySync?.suspendForRestore()
      await performanceHistorySync?.suspendForRestore()
      await performanceArtifactSync?.suspendForRestore()
      await uiAutomationHistorySync?.suspendForRestore()
      await uiAutomationArtifactSync?.suspendForRestore()
      await apiAutomationTriggerSync?.suspendForRestore()
      await functionalGovernanceSync?.suspendForRestore()
      await functionalAssetTagSync?.suspendForRestore()
      await functionalDefectSync?.suspendForRestore()
      await projectVersionSync?.suspendForRestore()
      await functionalDefectLinkSync?.suspendForRestore()
      await functionalDefectWorkflowSync?.suspendForRestore()
      await functionalDefectPeopleSync?.suspendForRestore()
      await functionalDefectExecutionSync?.suspendForRestore()
      await functionalHistorySync?.suspendForRestore()
      await projectFileSync?.suspendForRestore()
      const origins = [...localRuntime.allowedOrigins]
      updateBackup('正在切换工作区并保留原数据…')
      await localRuntime.stop()
      try {
        await writeExecutionOrigins(parent, []); localRuntime.allowedOrigins = []
        const previous = await activateRestore({ parent, candidate: candidate.directory,
          activate: () => localRuntime.start(), deactivate: () => localRuntime.stop() })
        candidate = undefined
        updateBackup(`恢复完成，原工作区保留于：${previous}`)
        return { previous }
      } catch (error) {
        if (error.restoreCommitted) throw new Error('备份工作区已启用，但恢复记录未能同步到磁盘。原工作区仍保留，请保留所有数据目录。')
        if (error.restoreRecoveryFailed) throw new Error('恢复及自动回退未完成，请退出并重新打开 TestHub 重试恢复原工作区。所有数据目录已保留。')
        await writeExecutionOrigins(parent, origins); localRuntime.allowedOrigins = origins
        await localRuntime.start()
        throw new Error(`恢复未完成，已恢复原工作区：${error.message}`)
      }
    } finally {
      if (candidate) await fs.rm(candidate.directory, { recursive: true, force: true })
    }
  })().catch(error => { updateBackup(error.message); throw error }).finally(() => { backupOperation = undefined; backupStatus.busy = false })
  return backupOperation
})

ipcMain.handle('testhub-desktop:preferences:load', async (event) => {
  if (!isSetupSender(event)) throw new Error('不受信任的桌面设置请求。')
  const preferences = await readPreferences(app.getPath('userData'))
  return { serverUrl: preferences.serverUrl ?? '', localStatus, canRestore: workspaceMode === 'local' && Boolean(localRuntime) }
})

ipcMain.handle('testhub-desktop:preferences:save', async (event, value) => {
  if (backupOperation || transferOperation) throw new Error('请等待备份、恢复或迁移完成。')
  if (!isSetupSender(event)) throw new Error('不受信任的桌面设置请求。')
  const normalized = await writePreferences(app.getPath('userData'), value)
  await openTestHubWindow(normalized)
  if (setupWindow && !setupWindow.isDestroyed() && !backupOperation) setupWindow.close()
  return { serverUrl: normalized }
})

ipcMain.handle('testhub-desktop:api-debug:execute', executeHttpDebug)

if (ownsWorkspace) app.whenReady().then(async () => {
  const syncOptions = { ipcMain, dialog, session, directory: app.getPath('userData'), origin: SETUP_ORIGIN,
    fileLinkContext: binding => projectFileSync?.linkContext(binding) ?? null,
    windows: () => ({ main: mainWindow, setup: setupWindow }),
    runtime: async () => {
      await initializeLocalRuntime(); await localRuntime.start()
      await ensureSyncSession(localRuntime, (url, options) => session.fromPartition(LOCAL_PARTITION).fetch(url, options))
      return localRuntime
    },
    server: async () => workspaceMode === 'server' && activeServerUrl ? activeServerUrl : (await readPreferences(app.getPath('userData'))).serverUrl,
    exclusive: runApiTransfer,
    available: () => !quitting && !backupOperation && !transferOperation && !savingExecutionSettings && !enteringLocalWorkspace,
    openWindow: openSetupWindow,
    trusted: event => event.sender === mainWindow?.webContents && event.senderFrame === event.sender.mainFrame && isTrustedRemoteSender(event),
  }
  taskSync = installTaskSync(syncOptions)
  settingsSync = installSettingsSync(syncOptions)
  notificationSync = installNotificationSync(syncOptions)
  mockSync = installMockSync(syncOptions)
  performanceSync = installPerformanceSync(syncOptions)
  uiAutomationSync = installUiAutomationSync(syncOptions)
  mobileAutomationSync = installMobileAutomationSync(syncOptions)
  functionalSync = installFunctionalSync(syncOptions)
  apiDesignSync = installApiDesignSync(syncOptions)
  apiAutomationAssetSync = installApiAutomationAssetSync(syncOptions)
  apiAutomationScenarioSync = installApiAutomationScenarioSync(syncOptions)
  apiAutomationHistorySync = installApiAutomationHistorySync(syncOptions)
  performanceHistorySync = installPerformanceHistorySync(syncOptions)
  performanceArtifactSync = installPerformanceArtifactSync(syncOptions)
  uiAutomationHistorySync = installUiAutomationHistorySync(syncOptions)
  uiAutomationArtifactSync = installUiAutomationArtifactSync(syncOptions)
  apiAutomationTriggerSync = installApiAutomationTriggerSync(syncOptions)
  functionalGovernanceSync = installFunctionalGovernanceSync(syncOptions)
  functionalAssetTagSync = installFunctionalAssetTagSync(syncOptions)
  functionalDefectSync = installFunctionalDefectSync(syncOptions)
  projectVersionSync = installProjectVersionSync(syncOptions)
  functionalDefectLinkSync = installFunctionalDefectLinkSync(syncOptions)
  functionalDefectWorkflowSync = installFunctionalDefectWorkflowSync(syncOptions)
  functionalDefectPeopleSync = installFunctionalDefectPeopleSync(syncOptions)
  functionalDefectExecutionSync = installFunctionalDefectExecutionSync(syncOptions)
  functionalHistorySync = installFunctionalHistorySync(syncOptions)
  projectFileSync = installProjectFileSync(syncOptions)
  taskSyncTimer = globalThis.setInterval(() => { void (async () => { await taskSync.tick(); await settingsSync.tick(); await notificationSync.tick(); await mockSync.tick(); await performanceSync.tick(); await uiAutomationSync.tick(); await mobileAutomationSync.tick(); await functionalSync.tick(); await apiDesignSync.tick(); await apiAutomationAssetSync.tick(); await apiAutomationScenarioSync.tick(); await apiAutomationHistorySync.tick(); await performanceHistorySync.tick(); await performanceArtifactSync.tick(); await uiAutomationHistorySync.tick(); await uiAutomationArtifactSync.tick(); await apiAutomationTriggerSync.tick(); await functionalGovernanceSync.tick(); await functionalAssetTagSync.tick(); await functionalDefectSync.tick(); await projectVersionSync.tick(); await functionalDefectLinkSync.tick(); await functionalDefectWorkflowSync.tick(); await functionalDefectPeopleSync.tick(); await functionalDefectExecutionSync.tick(); await functionalHistorySync.tick(); await projectFileSync.tick() })() }, 30000)
  taskSyncTimer.unref()
  protocol.handle('testhub-desktop', (request) => net.fetch(desktopAssetUrl(request.url)))
  installApplicationMenu()
  await startApplication().catch(showLocalError)
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) void startApplication().catch(showLocalError)
  })
}).catch((error) => {
  console.error('TestHub Desktop failed to start:', error)
  showLocalError(error)
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('second-instance', () => { const window = mainWindow || setupWindow; if (window) { if (window.isMinimized()) window.restore(); window.focus() } })
app.on('before-quit', event => {
  globalThis.clearInterval(taskSyncTimer); taskSync?.stop(); settingsSync?.stop(); notificationSync?.stop(); mockSync?.stop(); performanceSync?.stop(); uiAutomationSync?.stop(); mobileAutomationSync?.stop(); functionalSync?.stop(); apiDesignSync?.stop(); apiAutomationAssetSync?.stop(); apiAutomationScenarioSync?.stop(); apiAutomationHistorySync?.stop(); performanceHistorySync?.stop(); performanceArtifactSync?.stop(); uiAutomationHistorySync?.stop(); uiAutomationArtifactSync?.stop(); apiAutomationTriggerSync?.stop(); functionalGovernanceSync?.stop(); functionalAssetTagSync?.stop(); functionalDefectSync?.stop(); projectVersionSync?.stop(); functionalDefectLinkSync?.stop(); functionalDefectWorkflowSync?.stop(); functionalDefectPeopleSync?.stop(); functionalDefectExecutionSync?.stop(); functionalHistorySync?.stop(); projectFileSync?.stop()
  if (quitting || (!localRuntime && !backupOperation && !transferOperation)) return
  event.preventDefault(); quitting = true
  void (async () => { await Promise.all([backupOperation?.catch(() => {}), transferOperation?.catch(() => {})]); await Promise.all([localEngines?.stop(), localBrowsers?.stop()]); await Promise.all([preparingJmeter?.catch(() => {}), preparingBrowser?.catch(() => {})]); await localRuntime?.stop() })().finally(() => app.quit())
})
