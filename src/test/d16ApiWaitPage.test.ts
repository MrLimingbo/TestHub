import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import AutomationPage from '@/pages/AutomationPage.vue'
import AutomationSidebar from '@/components/api-automation/AutomationSidebar.vue'
import { useSessionStore } from '@/stores/session'
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const response = (data: unknown) => new Response(JSON.stringify({ data }))
async function setup() {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(); useSessionStore(pinia).projectId = 'p'
  let current = { id: 's', projectId: 'p', name: 'QA', description: '', environmentCode: 'test', failureStrategy: 'STOP', version: 1, variables: {}, preRequestScript: '', postResponseScript: '', steps: [{ id: 'get', type: 'REQUEST', name: 'HTTP', config: { method: 'GET', url: 'https://8.8.8.8/qa' } }, { id: 'wait', type: 'WAIT', name: '等待', config: { delayMillis: 1 } }], createdAt: '2026-11-02T00:00:00Z', updatedAt: '2026-11-02T00:00:00Z' }
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') { const input = JSON.parse(String(init.body)); current = { ...current, ...input.scenario, version: current.version + 1 }; return response(current) }
    return response(url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : url.endsWith('/context') ? { organizations: [{ id: 'o' }], projects: { o: [{ id: 'p', organizationId: 'o', name: 'QA', code: 'QA' }] } } : url.endsWith('/environments') ? [{ id: 'env', name: 'QA', environmentCode: 'test', enabled: true, defaultEnvironment: true }] : url.endsWith('/scenarios') ? [current] : url.endsWith('/scenarios/s') ? current : [])
  }); vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/api-automation', component: { template: '<div />' } }] }); await router.push('/api-automation')
  const w = shallowMount(AutomationPage, { global: { plugins: [pinia, ArcoVue, router], renderStubDefaultSlot: true, stubs: { Popover: { template: '<div><slot/><slot name="content"/></div>' } }, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  w.findComponent(AutomationSidebar).vm.$emit('select', 's'); await flushPromises(); await w.findAll('.scenario-flow-node')[1]!.trigger('click'); await flushPromises()
  return { w, fetcher }
}
async function input(q: Awaited<ReturnType<typeof setup>>, raw: string) {
  const form = q.w.findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === '等待时间')!
  const number = form.findComponent({ name: 'InputNumber' })
  if (number.exists()) { number.vm.$emit('input', Number(raw), raw, new Event('input')); number.vm.$emit('change', Number(raw)) }
  else form.findComponent({ name: 'Input' }).vm.$emit('input', raw)
  await flushPromises()
}
async function save(w: VueWrapper) { w.findAllComponents({ name: 'Button' }).find(c => c.text().trim() === '保存场景')!.vm.$emit('click'); await flushPromises() }
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([,init]) => init?.method === 'PUT')
it('TC-D16API-24: explicit integral input is sent as a bounded JSON number', async () => {
  const q = await setup()
  for (const raw of ['10', '10.0', '1e1', '0', '600000']) { await input(q, raw); await save(q.w); const call = writes(q).at(-1)!; expect(JSON.parse(String(call[1]!.body)).scenario.steps[1].config.delayMillis).toBe(Number(raw)) }
})
it('TC-D16API-25: fractional or lossy WAIT input cannot trigger a business save', async () => {
  const q = await setup()
  for (const raw of ['1.5', '1.0000000000000001', '-1', '600001', 'NaN', 'Infinity', '', '1e10000', '"10"']) { await input(q, raw); await save(q.w); expect(writes(q)).toHaveLength(0) }
  expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('delayMillis'))
})
