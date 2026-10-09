import { randomUUID } from 'node:crypto'

// Only the native parent owns downloads. Tasks pass a validated browser enum, never paths or URLs.
export function prepareDesktopBrowser(browser, signal) {
  if (process.env.TESTHUB_DESKTOP_BROWSER_PREPARATION !== 'ipc') return Promise.resolve()
  if (!process.connected) return Promise.reject(new Error('本地浏览器组件服务未连接。'))
  return new Promise((resolve, reject) => {
    const id = randomUUID()
    const timer = setTimeout(() => finish(new Error('浏览器组件准备超时，请检查网络后重试。')), 15 * 60 * 1000)
    const finish = error => {
      globalThis.clearTimeout(timer); process.off('message', receive); process.off('disconnect', disconnect)
      signal?.removeEventListener('abort', abort)
      if (error) reject(error); else resolve()
    }
    const receive = message => { if (message?.type === 'browser-prepared' && message.id === id) finish(message.error ? new Error(message.error) : undefined) }
    const disconnect = () => finish(new Error('本地浏览器组件服务已关闭。'))
    const abort = () => finish(new Error('浏览器任务已取消。'))
    process.on('message', receive); process.once('disconnect', disconnect); signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) { abort(); return }
    process.send({ type: 'prepare-browser', id, browser }, error => { if (error) finish(error) })
  })
}
