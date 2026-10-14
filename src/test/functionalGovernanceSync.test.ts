import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import FunctionalGovernancePage from '@/pages/FunctionalGovernancePage.vue'
const native = vi.hoisted(() => ({ local: false, account: false, open: vi.fn(), projectId: 'p1' }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => native.account, openFunctionalGovernanceSync: native.open, openFunctionalAssetTagSync: native.open }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ get projectId() { return native.projectId }, selectProject: (id: string) => { native.projectId = id }, resolveProjectId: () => native.projectId }) }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals(); native.local = false; native.account = false; native.projectId = 'p1'; native.open.mockReset() })
async function setup(rejectEdit = false) {
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const failed = rejectEdit && init?.method === 'PUT'
    const data = url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }] } }
      : url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'qa' }
        : url.endsWith('/tags') ? [{ id: 'stable-tag', name: '核心', color: '#336699', createdAt: '2026-10-09T00:00:00Z' }] : []
    return { ok: !failed, status: failed ? 409 : 200, json: async () => failed ? { message: '标签名称已存在' } : { data } }
  })
  vi.stubGlobal('fetch', fetcher)
  const w = shallowMount(FunctionalGovernancePage, { global: { plugins: [ArcoVue], stubs: { RouterLink: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, fetcher }
}
describe('Governance native entry and tag editing', () => {
  for (const mode of ['local', 'account']) for (const label of ['治理定义同步', '标签关联同步']) it(`opens definitions sync in ${mode} desktop mode`, async () => {
    native.local = mode === 'local'; native.account = mode === 'account'; native.open.mockResolvedValue(undefined)
    const { w, fetcher } = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.text() === label)!.vm.$emit('click'); await flushPromises()
    expect(native.open).toHaveBeenCalledOnce(); expect(fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  })
  it('hides native binding entry in the browser', async () => { const { w } = await setup(); expect(w.text()).not.toContain('治理定义同步') })
  it('shows native errors without submitting definitions', async () => {
    native.local = true; native.open.mockRejectedValue(new Error('工作区忙')); const error = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
    const { w } = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '治理定义同步')!.vm.$emit('click'); await flushPromises(); expect(error).toHaveBeenCalledWith('工作区忙')
  })
  for (const rejected of [false, true]) it(`edits the same tag ID and ${rejected ? 'keeps failures editable' : 'preserves existing assignments'}`, async () => {
    vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); const error = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
    const { w, fetcher } = await setup(rejected); w.findAllComponents({ name: 'Button' }).find(b => b.attributes('aria-label') === '编辑标签')!.vm.$emit('click'); await flushPromises()
    const modal = w.findAllComponents({ name: 'Modal' }).find(m => m.props('title') === '编辑标签')!
    const inputs = modal.findAllComponents({ name: 'Input' }); expect(inputs[1]!.props('modelValue')).toBe('#336699')
    inputs[0]!.vm.$emit('update:modelValue', '关键链路'); inputs[1]!.vm.$emit('update:modelValue', 'green'); await flushPromises()
    const saved = await (modal.props('onBeforeOk') as () => Promise<boolean>)(); await flushPromises()
    expect(saved).toBe(!rejected); expect(modal.props('visible')).toBe(rejected)
    const calls = fetcher.mock.calls.filter(([, init]) => init?.method === 'PUT'); expect(calls).toHaveLength(1)
    expect(calls[0]![0]).toBe('/api/v1/projects/p1/functional/tags/stable-tag'); expect(JSON.parse(calls[0]![1]!.body as string)).toEqual({ name: '关键链路', color: 'green' })
    expect(fetcher.mock.calls.some(([url]) => url.includes('/asset-tags'))).toBe(false)
    if (rejected) expect(error).toHaveBeenCalledWith('标签名称已存在')
  })
  it('rejects blank tag edits without a write', async () => {
    const { w, fetcher } = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.attributes('aria-label') === '编辑标签')!.vm.$emit('click'); await flushPromises()
    const modal = w.findAllComponents({ name: 'Modal' }).find(m => m.props('title') === '编辑标签')!; modal.findAllComponents({ name: 'Input' })[0]!.vm.$emit('update:modelValue', '  '); await flushPromises()
    expect(await (modal.props('onBeforeOk') as () => Promise<boolean>)()).toBe(false); expect(fetcher.mock.calls.some(([, init]) => init?.method)).toBe(false)
  })
})
