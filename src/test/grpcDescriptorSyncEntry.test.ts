import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import ApiDebugPage from '@/pages/ApiDebugPage.vue'
const native = vi.hoisted(() => ({ local: false, account: false, open: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => native.account, openGrpcDescriptorSync: native.open }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ projectId: 'p1', selectProject: vi.fn(), resolveProjectId: () => 'p1' }) }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals(); native.local = false; native.account = false; native.open.mockReset() })
async function setup() {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => ({ data: url.endsWith('/access/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }] } } : url.endsWith('/parameters') ? { environments: [], parameters: [] } : url.endsWith('/secrets') ? { environments: [], secrets: [] } : [] }) })))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/api-testing/quick-request'); await router.isReady()
  const wrapper = shallowMount(ApiDebugPage, { global: { plugins: [ArcoVue, router], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
describe('gRPC descriptor sync desktop entry', () => {
  for (const mode of ['local', 'account']) it(`opens native descriptor binding in ${mode} mode without executing a request`, async () => {
    native.local = mode === 'local'; native.account = mode === 'account'
    const wrapper = await setup(); wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === 'gRPC 描述文件同步')!.vm.$emit('click')
    await flushPromises(); expect(native.open).toHaveBeenCalledOnce()
    expect(vi.mocked(fetch).mock.calls.every(([, o]) => !o?.method || o.method === 'GET')).toBe(true)
  })
  it('does not expose native operations in a normal browser', async () => { expect((await setup()).text()).not.toContain('gRPC 描述文件同步') })
  it('reports opening failures without writing data', async () => {
    native.local = true; native.open.mockRejectedValue(new Error('工作区忙'))
    const error = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() })), wrapper = await setup()
    wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === 'gRPC 描述文件同步')!.vm.$emit('click'); await flushPromises()
    expect(error).toHaveBeenCalledWith('工作区忙')
  })
})
