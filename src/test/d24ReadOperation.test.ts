import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'

const source = readFileSync(resolve('apps/desktop/app/src/ui/read-operation.js'), 'utf8')
const flush = async () => { await Promise.resolve(); await Promise.resolve() }
function mount(bridge: object) {
  vi.useFakeTimers()
  document.body.innerHTML = '<main><button id="write" disabled>提交</button><p id="preview">草稿</p></main>'
  Object.defineProperty(window, 'testhubDesktop', { configurable: true, value: bridge })
  new Function('window', 'document', source)(window, document)
}
afterEach(() => { window.dispatchEvent(new Event('pagehide')); vi.clearAllTimers(); vi.useRealTimers(); document.body.replaceChildren(); Reflect.deleteProperty(window, 'testhubDesktop') })
it('U01 cancellation control announces progress immediately and waits for the real terminal state', async () => {
  let state = 'RUNNING'
  const cancel = vi.fn(async () => ({ id: 'a', state: 'CANCELLING' }))
  mount({ readOperationStatus: async () => ({ id: 'a', state }), cancelReadOperation: cancel }); await flush()
  const button = document.querySelector<HTMLButtonElement>('#read-operation button')!
  expect(button.disabled).toBe(false); expect(button.type).toBe('button')
  button.click(); expect(document.querySelector('#read-operation')!.textContent).toContain('正在取消')
  expect(document.querySelector('[role="status"]')!.getAttribute('aria-live')).toBe('polite'); await flush()
  expect(cancel).toHaveBeenCalledWith('a'); expect(button.disabled).toBe(true)
  state = 'WAITING'; await vi.advanceTimersByTimeAsync(250); expect(document.body.textContent).toContain('终止未确认')
  state = 'CANCELLED'; await vi.advanceTimersByTimeAsync(250); expect(document.body.textContent).toContain('预览已取消'); expect(button.hidden).toBe(true)
})
it('U02 late polling and pagehide cannot overwrite a newer operation or draft', async () => {
  let old!: (value: object) => void; let late!: (value: object) => void; let calls = 0
  mount({ readOperationStatus: () => ++calls === 1 ? new Promise(resolve => { old = resolve }) : calls === 2 ? Promise.resolve({ id: 'new', state: 'RUNNING' }) : new Promise(resolve => { late = resolve }) })
  await vi.advanceTimersByTimeAsync(250); old({ id: 'old', state: 'CANCELLED' }); await flush()
  expect(document.body.textContent).toContain('正在读取预览'); expect(document.body.textContent).not.toContain('预览已取消')
  await vi.advanceTimersByTimeAsync(250); window.dispatchEvent(new Event('pagehide'))
  document.querySelector('#preview')!.textContent = '新页面草稿'
  late({ id: 'old', state: 'CANCELLED' }); await flush()
  expect(document.querySelector('#preview')!.textContent).toBe('新页面草稿'); expect(document.body.textContent).not.toContain('预览已取消')
})
it('U03 an ordinary write has no read cancellation control and existing disabled controls stay disabled', async () => {
  mount({ readOperationStatus: async () => ({ state: 'IDLE' }) }); await flush()
  expect(document.querySelector<HTMLElement>('#read-operation')!.hidden).toBe(true)
  expect(document.querySelector<HTMLButtonElement>('#read-operation button')!.hidden).toBe(true)
  expect(document.querySelector<HTMLButtonElement>('#write')!.disabled).toBe(true)
})
