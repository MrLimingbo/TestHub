'use strict'

const form = document.querySelector('#server-form')
const serverUrlInput = document.querySelector('#server-url')
const errorElement = document.querySelector('#error')
const connectButton = document.querySelector('#connect')

function showError(message) {
  errorElement.textContent = message
  errorElement.hidden = false
}

async function initialize() {
  try {
    const preferences = await window.testhubDesktop.loadPreferences()
    serverUrlInput.value = preferences.serverUrl
  } catch {
    showError('无法读取桌面端设置，请重试。')
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault()
  errorElement.hidden = true
  connectButton.disabled = true
  try {
    await window.testhubDesktop.saveServerUrl(serverUrlInput.value)
  } catch (error) {
    showError(error instanceof Error ? error.message : '无法保存服务地址。')
    connectButton.disabled = false
  }
})

void initialize()

const localButton = document.querySelector('#local')
const recoverButton = document.querySelector('#recover-backup')
recoverButton.addEventListener('click', async () => {
  try { await window.testhubDesktop.openBackup() } catch (error) { showError(error.message) }
})
localButton.addEventListener('click', async () => {
  localButton.disabled = true; errorElement.hidden = true
  try { await window.testhubDesktop.enterLocalWorkspace() }
  catch (error) { showError(error.message || '本地工作区启动失败。'); localButton.disabled = false }
})
setInterval(async () => {
  try {
    const { localStatus, canRestore } = await window.testhubDesktop.loadPreferences()
    document.querySelector('#local-status').textContent = localStatus?.message || ''
    localButton.disabled = ['starting', 'recovering', 'stopping'].includes(localStatus?.phase)
    recoverButton.hidden = !(canRestore && localStatus?.phase === 'error')
  } catch { /* The setup window may close as the main window becomes ready. */ }
}, 1000)
