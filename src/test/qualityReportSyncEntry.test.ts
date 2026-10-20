import { afterEach, expect, it, vi } from 'vitest'
import { shallowMount, flushPromises } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import ReportsPage from '@/pages/ReportsPage.vue'
const native = vi.hoisted(() => ({ local: false, account: false, open: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => native.account, openQualityReportSync: native.open }))
const wrappers: ReturnType<typeof shallowMount>[] = []
async function setup() {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => ({ data: url.endsWith('/access/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', name: '隔离项目' }] } } : url.includes('/schedules') ? [] : { projectCount: 1, summary: { completedExecutions: 0 }, trend: [], projects: [] } }) })))
  const w = shallowMount(ReportsPage, { global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises(); return w
}
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; native.local = false; native.account = false; native.open.mockReset(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
for (const mode of ['local', 'account']) it(`opens plan sync in ${mode} desktop mode without sending a report`, async () => {
  native.local = mode === 'local'; native.account = mode === 'account'; const w = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '质量报告计划同步')!.vm.$emit('click'); await flushPromises(); expect(native.open).toHaveBeenCalledOnce(); expect(vi.mocked(fetch).mock.calls.every(([, o]) => !o?.method)).toBe(true)
})
it('keeps ordinary browser report controls without exposing native synchronization', async () => { expect((await setup()).text()).not.toContain('质量报告计划同步') })
it('shows an actionable native-opening failure without modifying a plan', async () => { native.local = true; native.open.mockRejectedValue(new Error('工作区忙')); const spy = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() })), w = await setup(); w.findAllComponents({ name: 'Button' }).find(b => b.text() === '质量报告计划同步')!.vm.$emit('click'); await flushPromises(); expect(spy).toHaveBeenCalledWith('工作区忙') })
