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
