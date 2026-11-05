import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Button, InputSearch, Pagination } from '@arco-design/web-vue'
import AccountManagementPage from '@/pages/AccountManagementPage.vue'

interface Page { items: ReturnType<typeof row>[]; total: number; page: number; pageSize: number }
const wrappers: VueWrapper[] = []
const row = (name: string) => ({ id: name, account: name, displayName: name, email: 'qa@example.test', phone: null, enabled: true, createdAt: '2026-11-04T00:00:00Z', updatedAt: '2026-11-04T00:00:00Z' })
const page = (name = 'initial', current = 1, total = 1): Page => ({ items: [row(name)], total, page: current, pageSize: 10 })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
async function setup(answer: (params: URLSearchParams) => Promise<Page | Response> | Page | Response = () => page()) {
  const fetcher = vi.fn(async (url: string) => {
    const value = await answer(new URL(url, 'https://testhub.example.test').searchParams)
    return value instanceof Response ? value : new Response(JSON.stringify({ data: value }), { headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] }); await router.push('/')
  const w = mount(AccountManagementPage, { global: { plugins: [createPinia(), ArcoVue, router], config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  const search = async (value: string) => { const input = w.findComponent(InputSearch); await input.get('input').setValue(value); await input.get('button').trigger('click'); await flushPromises() }
  const latest = () => new URL(fetcher.mock.calls.at(-1)![0], 'https://testhub.example.test').searchParams
  return { w, fetcher, search, latest }
}
it('U01 special search characters reach the query literally once and render the returned row', async () => {
  const keyword = "d%_\\!'+&"
  const q = await setup(params => page(params.get('keyword') ? 'literal-result' : 'initial'))
  await q.search('  ' + keyword + '  ')
  expect(q.latest().get('keyword')).toBe(keyword); expect(q.latest().get('page')).toBe('1')
  expect(q.w.text()).toContain('literal-result'); expect(q.w.text()).toContain('共 1 个账号')
  expect(q.fetcher.mock.calls.at(-1)![0]).toContain('%25'); expect(q.fetcher.mock.calls.at(-1)![0]).toContain('%5C')
})
it('U02 pagination retains the literal keyword while search and clear reset to page one', async () => {
  const q = await setup(params => page('page-' + params.get('page'), Number(params.get('page')), 25))
  await q.search('%_\\!'); q.w.findComponent(Pagination).vm.$emit('change', 2); await flushPromises()
  expect(q.latest().get('keyword')).toBe('%_\\!'); expect(q.latest().get('page')).toBe('2')
  await q.search('next_'); expect(q.latest().get('page')).toBe('1'); expect(q.latest().get('keyword')).toBe('next_')
  const input = q.w.findComponent(InputSearch); await input.get('.arco-input-clear-btn').trigger('click'); await flushPromises()
  expect(q.latest().has('keyword')).toBe(false); expect(q.latest().get('page')).toBe('1')
})
it('U03 a late percent search cannot replace newer underscore results or total', async () => {
  let release!: (value: Page) => void
  const q = await setup(params => params.get('keyword') === '%' ? new Promise(resolve => { release = resolve }) : params.get('keyword') === '_' ? page('new-literal', 1, 7) : page())
  await q.search('%'); await q.search('_'); release(page('old-percent', 1, 99)); await flushPromises()
  expect(q.w.text()).toContain('new-literal'); expect(q.w.text()).toContain('共 7 个账号'); expect(q.w.text()).not.toContain('old-percent')
})
it('U04 failed literal search shows its error and retry retains the same keyword', async () => {
  let failed = true
  const q = await setup(params => params.get('keyword') && failed ? new Response(JSON.stringify({ message: '受控搜索失败' }), { status: 503 }) : page('recovered'))
  await q.search('%_\\!'); expect(q.w.text()).toContain('受控搜索失败')
  failed = false; q.w.findAllComponents(Button).find(button => button.text() === '重试')!.vm.$emit('click'); await flushPromises()
  expect(q.latest().get('keyword')).toBe('%_\\!'); expect(q.w.text()).toContain('recovered'); expect(q.w.text()).not.toContain('受控搜索失败')
})
