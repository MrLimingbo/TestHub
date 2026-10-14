import { afterEach, expect, it, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import FunctionalAssetTags from '@/components/FunctionalAssetTags.vue'
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals() })
function response(data: unknown, ok = true) { return { ok, status: ok ? 200 : 409, json: async () => ok ? { data } : { message: '目标标签已变化' } } }
async function setup(failRead = false, failWrite = false) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/csrf')) return response({ headerName: 'X-CSRF', token: 'qa' })
    if (init?.method === 'PUT') return response([], !failWrite)
    if (url.includes('/asset-tags?')) return response([{ id: 't1' }], !failRead)
    if (url.endsWith('/tags')) return response([{ id: 't1', name: '核心', color: 'green' }])
    return response([{ id: 'asset1', caseCode: 'CASE-1', requirementCode: 'REQ-1', planCode: 'PLAN-1', title: '资产' }])
  })
  vi.stubGlobal('fetch', fetcher)
  const w = shallowMount(FunctionalAssetTags, { props: { projectId: 'p1' }, global: { plugins: [ArcoVue], renderStubDefaultSlot: true } })
  wrappers.push(w); await flushPromises(); return { w, fetcher }
}
async function select(w: ReturnType<typeof shallowMount>) { await w.get('select[aria-label="关联资产"]').setValue('asset1'); await flushPromises() }
async function save(w: ReturnType<typeof shallowMount>) { w.findAllComponents({ name: 'Button' }).find(b => b.text() === '保存标签关联')!.vm.$emit('click'); await flushPromises() }
it('reads without writing and saves a deliberate empty set with CSRF', async () => {
  const { w, fetcher } = await setup(); await select(w); expect(fetcher.mock.calls.some(([, init]) => init?.method)).toBe(false)
  w.findComponent({ name: 'CheckboxGroup' }).vm.$emit('update:modelValue', []); await flushPromises(); await save(w)
  const call = fetcher.mock.calls.find(([, init]) => init?.method === 'PUT')!
  expect(call[0]).toBe('/api/v1/projects/p1/functional/asset-tags/CASE/asset1'); expect(JSON.parse(call[1]!.body as string)).toEqual({ tagIds: [] }); expect(call[1]!.headers).toMatchObject({ 'X-CSRF': 'qa' })
})
for (const kind of ['REQUIREMENT', 'PLAN']) it(`isolates ${kind} asset selection and saves to the matching route`, async () => {
  const { w, fetcher } = await setup(); await w.get('select[aria-label="关联资产类型"]').setValue(kind); await flushPromises(); await select(w); await save(w)
  expect(fetcher.mock.calls.find(([, init]) => init?.method === 'PUT')![0]).toBe(`/api/v1/projects/p1/functional/asset-tags/${kind}/asset1`)
})
it('failed association reads prevent accidental clearing or saving', async () => {
  const { w, fetcher } = await setup(true); await select(w); expect(w.text()).toContain('目标标签已变化'); expect(w.text()).not.toContain('保存标签关联'); expect(fetcher.mock.calls.some(([, init]) => init?.method)).toBe(false)
})
it('write failures preserve the selection and remain retryable', async () => {
  const { w } = await setup(false, true); await select(w); await save(w); expect(w.text()).toContain('目标标签已变化'); expect(w.findComponent({ name: 'CheckboxGroup' }).props('modelValue')).toEqual(['t1']); expect(w.text()).toContain('保存标签关联')
})
it('a late association read cannot populate a changed project', async () => {
  const { w, fetcher } = await setup(); let release!: (value: ReturnType<typeof response>) => void
  fetcher.mockImplementationOnce(async () => new Promise(resolve => { release = resolve }))
  await w.get('select[aria-label="关联资产"]').setValue('asset1'); await w.setProps({ projectId: 'p2' }); await flushPromises(); release(response([{ id: 't1' }])); await flushPromises()
  expect(w.get('select[aria-label="关联资产"]').element).toHaveProperty('value', ''); expect(w.text()).not.toContain('保存标签关联')
})
it('project change during CSRF acquisition prevents sending the captured write', async () => {
  const { w, fetcher } = await setup(); await select(w); let release!: (value: ReturnType<typeof response>) => void
  fetcher.mockImplementationOnce(async () => new Promise(resolve => { release = resolve })); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '保存标签关联')!.vm.$emit('click'); await flushPromises(); await w.setProps({ projectId: 'p2' }); await flushPromises(); release(response({ headerName: 'X-CSRF', token: 'qa' })); await flushPromises()
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
})
it('unmount during CSRF acquisition prevents sending a write after navigation', async () => {
  const { w, fetcher } = await setup(); await select(w); let release!: (value: ReturnType<typeof response>) => void
  fetcher.mockImplementationOnce(async () => new Promise(resolve => { release = resolve })); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '保存标签关联')!.vm.$emit('click'); await flushPromises(); w.unmount(); release(response({ headerName: 'X-CSRF', token: 'qa' })); await flushPromises(); expect(fetcher.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false)
})
