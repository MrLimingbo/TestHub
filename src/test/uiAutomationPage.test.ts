import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Modal, Select, Drawer } from '@arco-design/web-vue'
import UiAutomationPage from '@/pages/UiAutomationPage.vue'
import PageHeader from '@/components/PageHeader.vue'
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers(); state.projectId = 'p1' })
const profile = (id: string, enabled = true) => ({ id, name: id, browser: 'chromium', enabled, version: 1 })
const scenario = { id: 's1', browserProfileId: 'enabled', name: 'Scenario', steps: [], variables: {}, failureStrategy: 'STOP', version: 1 }
async function setup(override?: (url: string, method: string) => unknown, full = false) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init?.method ?? 'GET')
    const data = custom ?? (url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }, { id: 'p2' }] } }
      : url.endsWith('/csrf') ? { token: 'test', headerName: 'X-CSRF' }
        : url.endsWith('/browser-profiles') ? [profile('disabled', false), profile('enabled')]
          : url.endsWith('/scenarios') ? [scenario] : [])
    return { ok: init?.method !== 'POST', json: async () => ({ data, message: 'Rejected input' }) }
  }))
  const wrapper = (full ? mount : shallowMount)(UiAutomationPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
describe('Web UI automation page', () => {
  for (const title of ['新建浏览器项目', '新建页面对象', '新建 Web UI 场景', '新建定时计划']) {
    it(`keeps ${title} open when saving fails`, async () => {
      const wrapper = await setup()
      const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === title)!
      expect(modal.props('onBeforeOk')).toBeTypeOf('function')
      expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false)
    })
  }
  it('selects an enabled browser profile for a new scenario', async () => {
    const wrapper = await setup()
    wrapper.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
    const dialog = wrapper.findAllComponents(Modal).find(m => m.props('title') === '新建 Web UI 场景')!
    expect(dialog.findAllComponents(Select).find(s => s.props('placeholder') === '选择已启用配置')!.props('modelValue')).toBe('enabled')
  })
  it('ignores a stale asset response from a previous project', async () => {
    let delayed = false; let release!: (value: unknown) => void
    const wrapper = await setup(url => delayed && url.includes('/p2/') && url.endsWith('/browser-profiles') ? new Promise(resolve => { release = resolve }) : undefined)
    delayed = true
    const select = wrapper.findAllComponents(Select)[0]!
    select.vm.$emit('update:modelValue', 'p2'); select.vm.$emit('change'); await flushPromises()
    select.vm.$emit('update:modelValue', 'p1'); select.vm.$emit('change'); await flushPromises()
    release([profile('old-project')]); await flushPromises()
    const tables = wrapper.findAllComponents({ name: 'Table' })
    expect(JSON.stringify(tables.map(t => t.props('data')))).not.toContain('old-project')
  })
  it('does not restore a closed report after its request completes', async () => {
    let release!: (value: unknown) => void
    const execution = { id: 'e1', scenarioId: 's1', scenarioName: 'Scenario', status: 'SUCCEEDED', requestedAt: '2026-09-16T00:00:00Z' }
    const wrapper = await setup(url => url.endsWith('/executions') ? [execution] : url.endsWith('/report') ? new Promise(resolve => { release = resolve }) : undefined, true)
    wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '报告')!.vm.$emit('click'); await flushPromises()
    const drawer = wrapper.findComponent(Drawer); drawer.vm.$emit('cancel'); await flushPromises()
    release({ execution, summary: { totalSteps: 0 }, steps: [], artifacts: [] }); await flushPromises()
    expect(drawer.props('visible')).toBe(false)
  })
  it('submits the selected scenario and keeps the execute dialog open on rejection', async () => {
    const wrapper = await setup(undefined, true)
    wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '执行场景')!.vm.$emit('click'); await flushPromises()
    const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === '执行 Web UI 场景')!
    expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/api/v1/projects/p1/ui-automation/scenarios/s1/executions', expect.objectContaining({ method: 'POST' }))
    expect(modal.props('visible')).toBe(true)
  })
  it('refreshes an open running report until it reaches a terminal state', async () => {
    vi.useFakeTimers(); let calls = 0
    const execution = { id: 'e1', scenarioId: 's1', scenarioName: 'Scenario', status: 'RUNNING', requestedAt: '2026-09-16T00:00:00Z' }
    const wrapper = await setup(url => url.endsWith('/executions') ? [execution] : url.endsWith('/report') ? { execution: { ...execution, status: ++calls > 1 ? 'SUCCEEDED' : 'RUNNING' }, summary: { totalSteps: 0 }, steps: [], artifacts: [] } : undefined, true)
    wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '报告')!.vm.$emit('click'); await flushPromises()
    expect(calls).toBe(1)
    await vi.advanceTimersByTimeAsync(3000); await flushPromises()
    expect(calls).toBe(2)
  })

})
