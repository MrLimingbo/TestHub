import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Modal, Select, Drawer } from '@arco-design/web-vue'
import MobileAutomationPage from '@/pages/MobileAutomationPage.vue'
const native = vi.hoisted(() => ({ local: false, account: false, open: vi.fn(), prepare: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => native.account, openMobileAutomationSync: native.open, ensureLocalAppium: native.prepare, openLocalExecutionSettings: vi.fn() }))
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers(); state.projectId = 'p1'; native.local = false; native.account = false; native.open.mockReset(); native.prepare.mockReset() })
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
  for (const mode of ['local', 'server']) {
    it(`opens native synchronization in ${mode} mode without submitting execution`, async () => {
      native.local = mode === 'local'; native.account = mode === 'server'; native.open.mockResolvedValue(undefined)
      const wrapper = await setup()
      wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '移动端资料同步')!.vm.$emit('click'); await flushPromises()
      expect(native.open).toHaveBeenCalledOnce()
      expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    })
  }
  it('hides native synchronization in an ordinary browser', async () => {
    const wrapper = await setup(); expect(wrapper.text()).not.toContain('移动端资料同步')
  })
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

  it('sends scene edits with the current version and preserves the form on conflict', async () => {
    const scene = { id: 's1', name: '订单', deviceId: 'd1', applicationId: 'a1', version: 2, description: '说明', variables: { order: 'A1' }, failureStrategy: 'STOP', steps: [{ id: 'open', type: 'LAUNCH', name: '启动', timeoutMillis: 1000 }] }
    const wrapper = await setup(url => url.endsWith('/mobile-automation') ? { devices: [], applications: [], elements: [], scenarios: [scene], executions: [] } : undefined, true)
    wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '编辑编排')!.vm.$emit('click'); await flushPromises()
    const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === '编辑移动端场景')!
    const fetcher = vi.mocked(fetch)
    fetcher.mockImplementationOnce(async () => ({ ok: true, json: async () => ({ data: { token: 'test', headerName: 'X-CSRF' } }) }) as Response)
      .mockImplementationOnce(async () => ({ ok: false, json: async () => ({ message: '场景版本已变化' }) }) as Response)
    expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false)
    const call = fetcher.mock.calls.find(([url, init]) => String(url).endsWith('/scenarios/s1') && init?.method === 'PUT')!
    expect(JSON.parse(String(call[1]!.body))).toMatchObject({ expectedVersion: 2, scenario: { name: '订单', variables: { order: 'A1' } } })
    expect(modal.props('visible')).toBe(true)
  })
  for (const action of ['cancel', 'project', 'failure']) {
    it(`does not queue a mobile task after native preparation ${action}`, async () => {
      native.local = true
      let release!: () => void, reject!: (value: Error) => void
      native.prepare.mockImplementation(() => new Promise<void>((resolve, fail) => { release = resolve; reject = fail }))
      const snapshot = { devices: [{ id: 'd1', enabled: true }], applications: [{ id: 'a1', enabled: true }], elements: [], scenarios: [{ id: 's1', name: '订单', deviceId: 'd1', applicationId: 'a1', steps: [], variables: {} }], executions: [] }
      const wrapper = await setup(url => url.endsWith('/mobile-automation') ? snapshot : undefined, true)
      wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '执行场景')!.vm.$emit('click'); await flushPromises()
      const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === '执行移动端场景')!
      const pending = modal.props('onBeforeOk')!(vi.fn()); await flushPromises()
      if (action === 'cancel') modal.vm.$emit('update:visible', false)
      if (action === 'project') state.selectProject('p2')
      if (action === 'failure') reject(new Error('Appium 未就绪')); else release()
      expect(await pending).toBe(false)
      expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    })
  }

})
