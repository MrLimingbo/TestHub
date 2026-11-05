'use strict'
const { AsyncLocalStorage } = require('node:async_hooks')
const crypto = require('node:crypto')
const scope = new AsyncLocalStorage()
function cancelled() { const error = new Error('预览已取消，请重新预览。'); error.code = 'READ_CANCELLED'; return error }
function checkpoint() { scope.getStore()?.controller.signal.throwIfAborted() }
function requestSignal(timeoutMs, caller, method = 'GET') {
  checkpoint()
  if (scope.getStore() && !['GET', 'HEAD'].includes(method.toUpperCase())) throw new Error('只读预览不能提交业务变更。')
  return globalThis.AbortSignal.any([globalThis.AbortSignal.timeout(timeoutMs), caller, scope.getStore()?.controller.signal].filter(Boolean))
}
async function readResponse(response, signal, consume) {
  if (!response.body) { signal.throwIfAborted(); return }
  if (!response.body.getReader) {
    for await (const chunk of response.body) { signal.throwIfAborted(); consume(chunk) }
    signal.throwIfAborted(); return
  }
  const reader = response.body.getReader(); let cancellation
  const abort = () => { cancellation = reader.cancel(signal.reason) }
  signal.addEventListener('abort', abort, { once: true })
  try {
    if (signal.aborted) abort()
    while (true) {
      signal.throwIfAborted()
      const value = await reader.read()
      signal.throwIfAborted()
      if (value.done) break
      consume(value.value)
    }
  } finally {
    signal.removeEventListener('abort', abort)
    try { await (cancellation || reader.cancel()) } finally { reader.releaseLock() }
  }
}
class ReadOperations {
  constructor({ trusted, now = Date.now }) { this.trusted = trusted; this.now = now }
  owner(event) {
    if (!this.trusted(event) || event.senderFrame !== event.sender.mainFrame) throw new Error('不受信任的预览取消请求。')
    return `${event.sender.id}|${event.senderFrame.url}`
  }
  view(record) {
    return { id: record.id, state: record.cancelledAt !== undefined && !record.terminal ? (this.now() - record.cancelledAt >= 5000 ? 'WAITING' : 'CANCELLING') : record.terminal || 'RUNNING' }
  }
  status(event) {
    const owner = this.owner(event)
    if (this.active?.owner === owner) return this.view(this.active)
    return this.last?.owner === owner ? { id: this.last.id, state: this.last.state } : { state: 'IDLE' }
  }
  cancelRecord(record) {
    if (record.cancelledAt === undefined) {
      record.cancelledAt = this.now(); record.invalidate(); record.controller.abort(cancelled())
    }
    return this.view(record)
  }
  cancel(event, id) {
    const owner = this.owner(event)
    if (this.active?.owner === owner && this.active.id === id) return this.cancelRecord(this.active)
    if (!this.active && this.last?.owner === owner && this.last.id === id) return { id, state: this.last.state }
    throw new Error('预览操作已失效，请核对当前窗口。')
  }
  async run(event, exclusive, operation, invalidate = () => {}) {
    const owner = this.owner(event); let record, result, failure
    try {
      result = await exclusive(async () => {
        if (this.active) throw new Error('其他工作区操作正在进行，请稍后重试。')
        record = { id: crypto.randomUUID(), owner, sender: event.sender, controller: new globalThis.AbortController(), invalidate }
        record.finished = new Promise(resolve => { record.finish = resolve })
        this.active = record
        const navigate = details => { if (details.isMainFrame) this.cancelRecord(record) }
        const destroy = () => this.cancelRecord(record)
        record.cleanup = () => { event.sender.removeListener('did-start-navigation', navigate); event.sender.removeListener('destroyed', destroy) }
        event.sender.on('did-start-navigation', navigate); event.sender.on('destroyed', destroy)
        invalidate()
        return scope.run(record, async () => { checkpoint(); const value = await operation(); checkpoint(); return value })
      })
    } catch (error) { failure = error }
    // exclusive's finally must have released this operation's lock before a terminal state is published.
    if (record) {
      if (record.controller.signal.aborted) failure = cancelled()
      if (failure) record.invalidate()
      record.terminal = record.controller.signal.aborted ? 'CANCELLED' : failure ? 'FAILED' : 'COMPLETED'
      record.cleanup(); this.last = { owner, id: record.id, state: record.terminal }
      if (this.active === record) this.active = undefined
      record.finish()
    }
    if (failure) throw failure
    return result
  }
  closeAfterCancellation(window) {
    const record = this.active
    if (!record || record.sender !== window.webContents) return false
    this.cancelRecord(record)
    if (!record.closing) record.closing = record.finished.then(() => { if (!window.isDestroyed()) window.close() })
    return true
  }
}
module.exports = { ReadOperations, checkpoint, requestSignal, readResponse }
