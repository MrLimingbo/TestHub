import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import PerformancePage from '@/pages/PerformancePage.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
async function setup() {
  for (const kind of ['success', 'error'] as const) vi.spyOn(Message, kind).mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(); useSessionStore(pinia).projectId = 'p'
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    let data: unknown = []
    if (init?.method === 'POST') data = { id: 'saved', ...JSON.parse(String(init.body)), version: 1, threadGroupCount: 1 }
    else if (url.endsWith('/csrf')) data = { token: 'fixture', headerName: 'X-CSRF' }
    else if (url.endsWith('/context')) data = { organizations: [{ id: 'o' }], projects: { o: [{ id: 'p', name: 'QA', code: 'QA' }] } }
    return new Response(JSON.stringify({ data }))
  }); vi.stubGlobal('fetch', fetcher)
  const w = shallowMount(PerformancePage, { global: { plugins: [pinia, ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  w.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
  const d = w.findComponent({ name: 'Modal' }); d.findAllComponents({ name: 'Input' }).find(i => i.props('placeholder') === '例如：订单核心链路基准压测')!.vm.$emit('update:modelValue', 'QA'); await flushPromises()
  return { w, d, fetcher }
}
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, i]) => i?.method === 'POST')
const labels: Record<string, string> = { threadCount: '并发线程', rampUpSeconds: '加压时长（秒）', durationSeconds: '持续时长（秒）', targetTps: '目标 TPS', p95ThresholdMillis: 'P95 阈值（毫秒）', errorThresholdPercent: '错误率阈值（%）', expectedStatusCode: '预期状态码', rendezvousUsers: '集合点等待线程', stepCount: '阶梯阶段数', stepIntervalSeconds: '阶梯间隔（秒）' }
async function numeric(q: Awaited<ReturnType<typeof setup>>, field: string, raw: string, group = 0) {
  const item = q.d.findAllComponents({ name: 'FormItem' }).filter(i => i.props('label') === labels[field])[group]!
  const old = item.findComponent({ name: 'InputNumber' })
  if (old.exists()) old.vm.$emit('update:modelValue', raw === '' ? undefined : Number(raw))
  else item.findComponent({ name: 'Input' }).vm.$emit('update:modelValue', raw)
  await flushPromises()
}
async function save(q: Awaited<ReturnType<typeof setup>>) { await ((q.d.props() as Record<string, unknown>).onBeforeOk as () => Promise<boolean>)(); await flushPromises() }
async function model(q: Awaited<ReturnType<typeof setup>>, value: string) { const s = q.d.findAllComponents({ name: 'FormItem' }).find(i => i.props('label') === '负载模型')!.findComponent({ name: 'Select' }); s.vm.$emit('update:modelValue', value); s.vm.$emit('change', value); await flushPromises() }
it('TC-D16PERF-57: invalid integer text blocks submission and keeps the dialog', async () => {
  const q = await setup()
  for (const field of ['threadCount', 'rampUpSeconds', 'durationSeconds', 'targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers']) {
    const valid = field === 'expectedStatusCode' ? '200' : '10'
    for (const raw of ['1.5', '1.0000000000000001']) { await numeric(q, field, raw); await save(q); expect(writes(q)).toHaveLength(0); expect(q.d.props('visible')).toBe(true) }
    await numeric(q, field, valid)
  }
  await model(q, 'STEPPED_RAMP')
  for (const field of ['stepCount', 'stepIntervalSeconds']) { await numeric(q, field, '2.5'); await save(q); expect(writes(q)).toHaveLength(0); await numeric(q, field, '2') }
})
it('TC-D16PERF-58: exact integer spellings submit numbers', async () => {
  const q = await setup(); await numeric(q, 'threadCount', '1e1'); await numeric(q, 'durationSeconds', '10.0'); await numeric(q, 'expectedStatusCode', '2e2'); await save(q)
  const g = JSON.parse(String(writes(q)[0]![1]!.body)).threadGroups[0]; expect(g.threadCount).toBe(10); expect(g.durationSeconds).toBe(10); expect(g.expectedStatusCode).toBe(200)
})
it('TC-D16PERF-59: error percent is not rounded before validation', async () => {
  const q = await setup(); await numeric(q, 'errorThresholdPercent', '2.501'); await save(q); expect(writes(q)).toHaveLength(0); expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('errorThresholdPercent'))
  await numeric(q, 'errorThresholdPercent', '2.50'); await save(q); expect(JSON.parse(String(writes(q)[0]![1]!.body)).threadGroups[0].errorThresholdPercent).toBe(2.5)
})
it('TC-D16PERF-60: optional blanks and model changes keep null policy', async () => {
  const q = await setup(); await model(q, 'STEPPED_RAMP'); await model(q, 'RAMP_UP')
  for (const field of ['targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers', 'errorThresholdPercent']) await numeric(q, field, '')
  await save(q); const g = JSON.parse(String(writes(q)[0]![1]!.body)).threadGroups[0]
  for (const field of ['targetTps', 'p95ThresholdMillis', 'expectedStatusCode', 'rendezvousUsers', 'errorThresholdPercent', 'stepCount', 'stepIntervalSeconds']) expect(g[field]).toBeUndefined()
})
it('TC-D16PERF-61: removing a group retains the surviving group raw values', async () => {
  const q = await setup(); await numeric(q, 'threadCount', '1.5')
  q.d.findAllComponents({ name: 'Button' }).find(b => b.text().includes('添加线程组'))!.vm.$emit('click'); await flushPromises(); await numeric(q, 'threadCount', '20.0', 1)
  q.d.findAllComponents({ name: 'Button' }).find(b => b.text().includes('删除'))!.vm.$emit('click'); await flushPromises(); await save(q)
  expect(JSON.parse(String(writes(q)[0]![1]!.body)).threadGroups[0].threadCount).toBe(20)
})
