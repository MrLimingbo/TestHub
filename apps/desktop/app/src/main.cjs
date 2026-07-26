'use strict'

const { app, BrowserWindow, Menu, ipcMain, net, protocol } = require('electron')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const {
  isTrustedServerUrl,
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
    },
  })
  denyAllPermissions(mainWindow.webContents.session)
  applyRemoteContentRestrictions(mainWindow, normalized)
  mainWindow.on('closed', () => { mainWindow = undefined })
  void mainWindow.loadURL(normalized)
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
  return { serverUrl: preferences.serverUrl ?? 'http://127.0.0.1:5173' }
})

ipcMain.handle('testhub-desktop:preferences:save', async (event, value) => {
  if (!isSetupSender(event)) throw new Error('不受信任的桌面设置请求。')
  const normalized = await writePreferences(app.getPath('userData'), value)
  openTestHubWindow(normalized)
  if (setupWindow && !setupWindow.isDestroyed()) setupWindow.close()
  return { serverUrl: normalized }
})

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
