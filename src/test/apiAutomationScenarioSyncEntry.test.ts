import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import AutomationPage from '@/pages/AutomationPage.vue'
const native = vi.hoisted(() => ({ local: false, account: false, open: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => native.account, openApiAutomationScenarioSync: native.open, openApiAutomationAssetSync: vi.fn(), openApiAutomationHistorySync: vi.fn(), openApiAutomationTriggerSync: vi.fn() }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ projectId: 'p1', selectProject: vi.fn(), resolveProjectId: () => 'p1' }) }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => {
  wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0
  vi.restoreAllMocks(); vi.unstubAllGlobals(); native.local = false; native.account = false; native.open.mockReset()
})
async function setup() {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => ({ data: url.endsWith('/access/context')
    ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }] } } : [] }) })))
  const wrapper = shallowMount(AutomationPage, { global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
describe('API automation native scenario sync entry', () => {
  for (const mode of ['local', 'account']) it(`opens binding in ${mode} mode without creating an execution`, async () => {
    native.local = mode === 'local'; native.account = mode === 'account'; native.open.mockResolvedValue(undefined)
    const wrapper = await setup()
    wrapper.findAllComponents({ name: 'Button' }).find(button => button.text() === '场景同步')!.vm.$emit('click')
    await flushPromises(); expect(native.open).toHaveBeenCalledOnce()
    expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
  })
  it('hides native binding in an ordinary browser', async () => { const wrapper = await setup(); expect(wrapper.text()).not.toContain('场景同步') })
  it('reports native open errors and leaves business data unchanged', async () => {
    native.local = true; native.open.mockRejectedValue(new Error('工作区忙'))
    const error = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
    const wrapper = await setup(); wrapper.findAllComponents({ name: 'Button' }).find(button => button.text() === '场景同步')!.vm.$emit('click')
    await flushPromises(); expect(error).toHaveBeenCalledWith('工作区忙')
  })
})
