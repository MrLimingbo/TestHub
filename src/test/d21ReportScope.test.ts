import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Message, Select } from '@arco-design/web-vue'
import ReportsPage from '@/pages/ReportsPage.vue'
import PageHeader from '@/components/PageHeader.vue'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const summary = { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }
async function setup(count: number) {
  const ids = Array.from({ length: count }, (_, i) => `p${i + 1}`)
  const fetcher = vi.fn(async (url: string) => {
    if (url.includes('/quality/export?')) return new Response('owned fixture CSV', { headers: { 'Content-Type': 'text/csv' } })
    const data = url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: ids.map(id => ({ id, name: id, code: id, organizationId: 'o1' })) } }
      : url.endsWith('/schedules') ? [] : { from: '2026-11-04', to: '2026-11-04', projectCount: count, summary, trend: [], projects: [] }
    return new Response(JSON.stringify({ data }))
  })
  vi.stubGlobal('fetch', fetcher)
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:owned-d21-fixture')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'warning').mockImplementation(() => ({ close: vi.fn() }))
  const w = mount(ReportsPage, { global: { plugins: [createPinia(), ArcoVue], stubs: { Modal: true, DashboardTrendChart: true, UnifiedExecutionsPanel: true }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises()
  const select = w.find('.report-filter__controls').findComponent(Select)
  const requests = (exported: boolean) => fetcher.mock.calls.map(([url]) => url).filter(url => exported ? url.includes('/quality/export?') : url.includes('/quality?'))
  const selected = (url: string) => new URL(url, 'https://testhub.example.test').searchParams.getAll('projectIds')
  const choose = async (values: string[]) => { select.vm.$emit('update:modelValue', values); await flushPromises() }
  const download = async () => { w.findComponent(PageHeader).vm.$emit('action'); await flushPromises() }
  const refresh = async () => { await w.findAllComponents(Button).find(c => c.text() === '刷新')!.get('button').trigger('click'); await flushPromises() }
  return { w, ids, select, requests, selected, choose, download, refresh }
}

it('U01 initial all selection and CSV send the explicit legal project scope', async () => {
  const q = await setup(2)
  expect(q.selected(q.requests(false).at(-1)!)).toEqual(q.ids)
  await q.download(); expect(q.selected(q.requests(true).at(-1)!)).toEqual(q.ids)
  expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledOnce()
})
it('U02 clearing a small scope omits the parameter and describes the default accessible range', async () => {
  const q = await setup(2); await q.choose([])
  expect(q.selected(q.requests(false).at(-1)!)).toEqual([])
  expect(q.w.text()).toContain('清空项目筛选时默认聚合全部可访问项目')
  await q.download(); expect(q.selected(q.requests(true).at(-1)!)).toEqual([])
})
it('U03 over fifty accessible projects with no selection blocks query and export', async () => {
  const q = await setup(51)
  expect(q.w.text()).toContain('请先选择1至50个项目，再查询或导出')
  expect(q.requests(false)).toHaveLength(0)
  await q.refresh(); await q.download()
  expect(q.requests(false)).toHaveLength(0); expect(q.requests(true)).toHaveLength(0)
  expect(q.w.findComponent(PageHeader).props('action')).toBeUndefined()
})
it('U04 fifty selected projects work and fifty one cannot query or export', async () => {
  const q = await setup(51)
  expect(q.select.props('limit')).toBe(50)
  await q.choose(q.ids.slice(0, 50)); expect(q.selected(q.requests(false).at(-1)!)).toHaveLength(50)
  await q.download(); expect(q.selected(q.requests(true).at(-1)!)).toHaveLength(50)
  const before = [q.requests(false).length, q.requests(true).length]
  await q.choose(q.ids); await q.refresh(); await q.download()
  expect(q.w.text()).toContain('最多选择50个项目')
  expect([q.requests(false).length, q.requests(true).length]).toEqual(before)
})
