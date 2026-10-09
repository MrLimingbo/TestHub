'use strict'
const form = document.querySelector('#execution-form')
const input = document.querySelector('#origins')
const appium = document.querySelector('#appium-server')
const button = document.querySelector('#save')
const errorElement = document.querySelector('#error')
function showError(error) {
  errorElement.textContent = error.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '无法保存本地执行设置，请重试。'
  errorElement.hidden = false
}
button.disabled = true
window.testhubDesktop.loadExecutionSettings().then(value => { input.value = value.allowedOrigins.join('\n'); appium.value = value.appiumServerUrl })
  .catch(showError).finally(() => { button.disabled = false })
form.addEventListener('submit', async event => {
  event.preventDefault(); button.disabled = true; errorElement.hidden = true
  try { await window.testhubDesktop.saveExecutionSettings({ allowedOrigins: input.value.split(/\r?\n/).map(line => line.trim()).filter(Boolean), appiumServerUrl: appium.value }) }
  catch (error) { showError(error); button.disabled = false }
})
