import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import ApiDebugSyncArchive from '@/components/ApiDebugSyncArchive.vue'
const wrappers: ReturnType<typeof mount>[] = []
const key = 'A'.repeat(26) + '.' + 'B'.repeat(26)
const entry = { key, nativeRecord: false, protocol: 'HTTP', method: 'POST', result: 'COMPLETED', createdAt: '2026-10-20T01:00:00Z', target: 'https://example.test/…' }
const template = { protocol: 'HTTP', request: { method: 'POST', url: 'https://example.test/owned', body: '{{SYNC_REVIEW_BODY}}' } }
const response = (data: unknown) => ({ ok: true, json: async () => ({ data }) })
async function setup() {
  const wrapper = mount(ApiDebugSyncArchive, { props: { projectId: 'p1' }, attachTo: document.body, global: { plugins: [ArcoVue] } })
  wrappers.push(wrapper); await wrapper.get('button').trigger('click'); await flushPromises(); return wrapper
}
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); document.body.innerHTML = '' })
it('lists only imported summaries and reads scoped immutable detail without POST', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/archive') ? { entries: [entry, { ...entry, key: 'native', nativeRecord: true }] } : { key, sha256: 'c'.repeat(64), payload: { target: entry.target } })))
  await setup(); expect(document.body.textContent).not.toContain('native')
  const button = [...document.querySelectorAll('button')].find(v => v.textContent === '查看同步记录')!; button.click(); await flushPromises()
  expect(document.querySelector('a[download]')?.getAttribute('href')).toContain(`/p1/api-debug/history/archive/${key}/export`)
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('templates require a manual load action and emit no network dispatch', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/archive') ? { entries: [entry] } : { key, sha256: 'c'.repeat(64), payload: { template } })))
  const wrapper = await setup(); [...document.querySelectorAll('label')].find(v => v.textContent?.includes('脱敏模板'))!.click(); await flushPromises()
  ;[...document.querySelectorAll('button')].find(v => v.textContent === '查看同步记录')!.click(); await flushPromises(); expect(wrapper.emitted('load')).toBeUndefined()
  ;[...document.querySelectorAll('button')].find(v => v.textContent === '载入脱敏模板')!.click(); await flushPromises()
  expect(wrapper.emitted('load')).toEqual([[JSON.stringify(template)]]); expect(vi.mocked(fetch).mock.calls.length).toBe(3)
})
it('project change discards a late detail response and closes the drawer', async () => {
  let finish!: (value: unknown) => void
  vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/archive') ? Promise.resolve(response({ entries: [entry] })) : new Promise(resolve => { finish = resolve })))
  const wrapper = await setup(); [...document.querySelectorAll('button')].find(v => v.textContent === '查看同步记录')!.click(); await flushPromises()
  await wrapper.setProps({ projectId: 'p2' }); finish(response({ key, payload: { template } })); await flushPromises(); expect(document.querySelector('a[download]')).toBeNull(); expect(wrapper.emitted('load')).toBeUndefined()
})
it('range errors are actionable and no history write is attempted', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, json: async () => ({ message: '原生历史超过范围，请核对' }) })))
  await setup(); expect(document.body.textContent).toContain('原生历史超过范围，请核对')
})
