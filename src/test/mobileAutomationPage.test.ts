import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Modal, Select, Drawer } from '@arco-design/web-vue'
import MobileAutomationPage from '@/pages/MobileAutomationPage.vue'
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers(); state.projectId = 'p1' })
async function setup(override?: (url: string, method: string) => unknown, full = false) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init?.method ?? 'GET')
    const data = custom ?? (url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }, { id: 'p2' }] } }
      : url.endsWith('/csrf') ? { token: 'test', headerName: 'X-CSRF' }
        : { devices: [], applications: [], elements: [], scenarios: [], executions: [] })
    return { ok: init?.method !== 'POST', json: async () => ({ data, message: 'Rejected input' }) }
  }))
  const wrapper = (full ? mount : shallowMount)(MobileAutomationPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
describe('Mobile automation page', () => {
  for (const title of ['新建设备', '新建移动应用', '新建元素对象', '新建移动端场景']) {
    it(`keeps ${title} open when saving fails`, async () => {
      const wrapper = await setup()
      const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === title)!
      expect(modal.props('onBeforeOk')).toBeTypeOf('function')
      expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false)
    })
  }
  it('ignores stale assets after switching projects', async () => {
    let delayed = false, release!: (value: unknown) => void
    const wrapper = await setup(url => delayed && url.includes('/p2/') ? new Promise(resolve => { release = resolve }) : undefined)
    delayed = true; const select = wrapper.findAllComponents(Select)[0]!
    select.vm.$emit('update:modelValue', 'p2'); select.vm.$emit('change'); await flushPromises()
    select.vm.$emit('update:modelValue', 'p1'); select.vm.$emit('change'); await flushPromises()
    release({ devices: [{ id: 'old-project', name: 'old-project' }], applications: [], elements: [], scenarios: [], executions: [] }); await flushPromises()
    expect(wrapper.text()).not.toContain('old-project')
  })
  it('refreshes an open report and discards its response after closing', async () => {
    vi.useFakeTimers(); let calls = 0, release!: (value: unknown) => void
    const execution = { id: 'e1', scenarioId: 's1', status: 'RUNNING' }
    const report = { execution, summary: { totalSteps: 0 }, steps: [] }
    const wrapper = await setup(url => url.endsWith('/report') ? (++calls === 1 ? report : new Promise(resolve => { release = resolve })) : url.endsWith('/artifacts') ? [] : url.endsWith('/mobile-automation') ? { devices: [], applications: [], elements: [], scenarios: [{ id: 's1', name: 'Scenario', steps: [] }], executions: [execution] } : undefined, true)
    wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '报告')!.vm.$emit('click'); await flushPromises()
    await vi.advanceTimersByTimeAsync(3000); await flushPromises(); expect(calls).toBe(2)
    const drawer = wrapper.findComponent(Drawer); drawer.vm.$emit('cancel'); await flushPromises()
    release(report); await flushPromises(); expect(drawer.props('visible')).toBe(false)
  })

})
