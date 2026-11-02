import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import MockServicePage from '@/pages/MockServicePage.vue'
import { useSessionStore } from '@/stores/session'
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const stamp = '2026-11-02T00:00:00Z'
const response = (data: unknown) => new Response(JSON.stringify({ data }))
async function setup() {
  for (const kind of ['success', 'error', 'warning'] as const) vi.spyOn(Message, kind).mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(); useSessionStore(pinia).projectId = 'p'
  const server = { id: 's', projectId: 'p', name: 'QA', publicKey: 'fixture-public', enabled: true, smartExampleEnabled: false, ruleCount: 0, createdAt: stamp, updatedAt: stamp }
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') { const body = JSON.parse(String(init.body)); return response(url.endsWith('/shares') ? { id: 'share', name: body.name, shareKey: 'fixture-share', expiresAt: null, revokedAt: null, createdAt: stamp } : { id: 'r', serverId: 's', ...body, enabled: true, createdAt: stamp, updatedAt: stamp }) }
    return response(url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : url.endsWith('/context') ? { organizations: [{ id: 'o' }], projects: { o: [{ id: 'p', name: 'QA', code: 'QA' }] } } : url.endsWith('/servers') ? [server] : [])
  }); vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/mock', component: { template: '<div />' } }] }); await router.push('/mock')
  const w = shallowMount(MockServicePage, { global: { plugins: [pinia, ArcoVue, router], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises(); return { w, fetcher }
}
const modal = (w: VueWrapper, title: string) => w.findAllComponents({ name: 'Modal' }).find(m => m.props('title') === title)!
async function open(q: Awaited<ReturnType<typeof setup>>, share = false) {
  q.w.findAllComponents({ name: 'Button' }).find(b => b.text().trim() === (share ? '创建只读分享' : '新建规则'))!.vm.$emit('click'); await flushPromises()
  const dialog = modal(q.w, share ? '创建只读 Mock 分享' : '新建 Mock 规则')
  dialog.findAllComponents({ name: 'Input' }).find(i => i.props('placeholder') === (share ? '例如：合作方订单联调说明' : '例如：预览订单成功'))!.vm.$emit('update:modelValue', 'QA'); await flushPromises(); return dialog
}
async function numeric(dialog: VueWrapper, label: string, raw: string) {
  const f = dialog.findAllComponents({ name: 'FormItem' }).find(i => i.props('label') === label)!, number = f.findComponent({ name: 'InputNumber' })
  if (number.exists()) { number.vm.$emit('input', Number(raw), raw, new Event('input')); number.vm.$emit('update:modelValue', Number(raw)); number.vm.$emit('change', Number(raw)) }
  else { const input = f.findComponent({ name: 'Input' }); input.vm.$emit('input', raw); input.vm.$emit('update:modelValue', raw) }
  await flushPromises()
}
async function save(dialog: VueWrapper) { await ((dialog.props() as Record<string, unknown>).onBeforeOk as () => Promise<boolean>)(); await flushPromises() }
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([,i]) => i?.method === 'POST')
it('TC-D16MOCK-35: invalid rule numbers remain visible and never submit', async () => {
  const q = await setup(), d = await open(q)
  for (const raw of ['1.5', '1.0000000000000001', '-1', '1001']) { await numeric(d, '优先级', raw); await save(d); expect(writes(q)).toHaveLength(0); expect(d.props('visible')).toBe(true) }
  expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('priority'))
  await numeric(d, '优先级', '10')
  for (const raw of ['200.5', '200.0000000000000001', '199', '600']) { await numeric(d, '响应状态', raw); await save(d); expect(writes(q)).toHaveLength(0); expect(d.props('visible')).toBe(true) }
  expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('responseStatus'))
})
it('TC-D16MOCK-36: invalid expiry never creates a share', async () => {
  const q = await setup(), d = await open(q, true)
  for (const raw of ['1.5', '1.0000000000000001', '0', '366']) { await numeric(d, '有效期', raw); await save(d); expect(writes(q)).toHaveLength(0); expect(d.props('visible')).toBe(true) }
})
it('TC-D16MOCK-37: explicit integral spellings submit JSON numbers', async () => {
  const q = await setup(), d = await open(q); await numeric(d, '优先级', '1e1'); await numeric(d, '响应状态', '200.0'); await save(d)
  const body = JSON.parse(String(writes(q)[0]![1]!.body)); expect(body.priority).toBe(10); expect(body.responseStatus).toBe(200)
})
it('TC-D16MOCK-38: existing string-only JSON editor guard remains', async () => {
  const q = await setup(), d = await open(q)
  d.findAllComponents({ name: 'CodeEditor' })[0]!.vm.$emit('update:modelValue', '{"id":1}'); await flushPromises(); await save(d); expect(writes(q)).toHaveLength(0); expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('字符串'))
})
it('TC-D16MOCK-40: never-expiring share ignores the disabled expiry input', async () => {
  const q = await setup(), d = await open(q, true); await numeric(d, '有效期', '1.5')
  d.findComponent({ name: 'Checkbox' }).vm.$emit('update:modelValue', true); await flushPromises(); await save(d)
  expect(JSON.parse(String(writes(q)[0]![1]!.body)).expiresInDays).toBeNull()
})
