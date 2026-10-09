'use strict'
const form = document.querySelector('#backup-form')
const password = document.querySelector('#password')
const confirmation = document.querySelector('#confirmation')
const statusElement = document.querySelector('#status')
const errorElement = document.querySelector('#error')
let submitting = false
function disable(busy) { for (const input of form.querySelectorAll('input, button')) input.disabled = busy }
function showError(error) {
  errorElement.textContent = error.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '备份操作失败，请重试。'
  errorElement.hidden = false
}
async function refresh() {
  try {
    const status = await window.testhubDesktop.backupStatus()
    if (status.message) statusElement.textContent = status.message + (status.busy && status.totalBytes > 0
      ? ` ${Math.floor(status.completedBytes / status.totalBytes * 100)}%` : '')
    disable(submitting || status.busy)
  } catch (error) { showError(error) }
}
form.addEventListener('submit', async event => {
  event.preventDefault()
  if (submitting) return
  const action = event.submitter?.value
  errorElement.hidden = true
  if (action === 'export' && confirmation.value !== password.value) { showError(new Error('两次输入的备份密码不一致。')); return }
  const value = { action, password: password.value }
  password.value = ''; confirmation.value = ''
  submitting = true; disable(true); statusElement.textContent = '请选择备份文件…'
  try {
    const result = await window.testhubDesktop.runBackup(value)
    if (result.cancelled) statusElement.textContent = '操作已取消，当前工作区保持不变。'
  } catch (error) { showError(error) }
  finally { value.password = ''; submitting = false; disable(false) }
})
disable(true)
void refresh()
const timer = setInterval(refresh, 700)
window.addEventListener('pagehide', () => window.clearInterval(timer), { once: true })
