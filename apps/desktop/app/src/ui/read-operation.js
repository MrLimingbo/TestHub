'use strict'
;(function () {
  const bridge = window.testhubDesktop
  if (!bridge?.readOperationStatus || document.querySelector('#read-operation')) return
  const section = document.createElement('section'); section.id = 'read-operation'; section.hidden = true
  const message = document.createElement('p'); message.setAttribute('role', 'status'); message.setAttribute('aria-live', 'polite')
  const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = '取消预览'
  section.append(message, cancel); (document.querySelector('main') || document.body).prepend(section)
  let alive = true, sequence = 0, current, requested
  function render(value) {
    current = value; section.hidden = value.state === 'IDLE'
    section.dataset.operationId = value.id || ''
    const state = requested === value.id && value.state === 'RUNNING' ? 'CANCELLING' : value.state
    message.textContent = { RUNNING: '正在读取预览，可取消或关闭此窗口。', CANCELLING: '正在取消，请等待终止确认…', WAITING: '终止未确认，正在等待；工作区仍锁定。', CANCELLED: '预览已取消，可重新预览或关闭窗口。', COMPLETED: '预览读取完成。', FAILED: '预览读取结束，请核对页面提示。' }[state] || ''
    cancel.hidden = !['RUNNING', 'CANCELLING', 'WAITING'].includes(state)
    cancel.disabled = state !== 'RUNNING'
  }
  async function poll() {
    const version = ++sequence
    try { const value = await bridge.readOperationStatus(); if (alive && version === sequence) render(value) }
    catch { if (alive && version === sequence) { section.hidden = false; message.textContent = '无法确认预览状态，请等待后重试；请勿强制退出。'; cancel.hidden = true } }
  }
  cancel.addEventListener('click', async () => {
    if (!current?.id || cancel.disabled) return
    requested = current.id; const version = ++sequence
    render({ ...current, state: 'CANCELLING' })
    try { const value = await bridge.cancelReadOperation(current.id); if (alive && version === sequence) render(value) }
    catch { if (alive && version === sequence) { message.textContent = '尚未确认取消，请核对当前预览状态。'; requested = undefined } }
  })
  const interval = window.setInterval(() => { void poll() }, 250)
  window.addEventListener('pagehide', () => { alive = false; sequence++; window.clearInterval(interval) }, { once: true })
  void poll()
})()
