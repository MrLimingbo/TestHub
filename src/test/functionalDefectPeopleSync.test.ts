import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import FunctionalTestingLayout from '@/layouts/FunctionalTestingLayout.vue'
const native = vi.hoisted(() => ({ local: false, account: false, open: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => native.account, openFunctionalSync: vi.fn(), openFunctionalDefectSync: vi.fn(), openFunctionalDefectPeopleSync: native.open }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ projectId: 'p1' }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/functional/delivery' }) }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals(); native.local = false; native.account = false; native.open.mockReset() })
async function setup() {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ data: [] }) })))
  const w = shallowMount(FunctionalTestingLayout, { global: { plugins: [ArcoVue], stubs: { RouterLink: true, RouterView: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return w
}
describe('Functional defect people desktop sync entry', () => {
  for (const mode of ['local', 'account']) it(`opens the native binding page in ${mode} mode without modifying assets`, async () => {
    native.local = mode === 'local'; native.account = mode === 'account'; native.open.mockResolvedValue(undefined)
    const w = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '缺陷人员同步')!.vm.$emit('click'); await flushPromises()
    expect(native.open).toHaveBeenCalledOnce(); expect(vi.mocked(fetch).mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })
  it('hides the entry in a browser', async () => { const w = await setup(); expect(w.text()).not.toContain('缺陷人员同步') })
  it('reports native open errors without mutating assets', async () => {
    native.local = true; native.open.mockRejectedValue(new Error('工作区忙')); const error = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
    const w = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '缺陷人员同步')!.vm.$emit('click'); await flushPromises(); expect(error).toHaveBeenCalledWith('工作区忙')
  })
})
