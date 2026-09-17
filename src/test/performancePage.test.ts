import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Modal, Input, Textarea, Select, Drawer } from '@arco-design/web-vue'
import PerformancePage from '@/pages/PerformancePage.vue'
import PageHeader from '@/components/PageHeader.vue'
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers(); state.projectId = 'p1' })
const plan = (id: string) => ({ id, name: id, status: 'READY', version: 1, threadGroupCount: 0, threadGroups: [] })
async function setup(override?: (url: string, method: string) => unknown, full = false) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init?.method ?? 'GET')
    const data = custom ?? (url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }, { id: 'p2' }] } }
      : url.endsWith('/csrf') ? { token: 'test', headerName: 'X-CSRF' }
        : url.endsWith('/plans') ? [plan('a'), plan('b')] : /\/plans\/[ab]$/.test(url) ? plan(url.at(-1)!) : [])
    return { ok: init?.method !== 'POST', json: async () => ({ data, message: '保存失败' }) }
  }))
  const wrapper = (full ? mount : shallowMount)(PerformancePage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true, PerformanceExecutionChart: true, PerformanceResourceChart: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
describe('Performance page', () => {
  it('preserves the plan dialog and entered name after a rejected save', async () => {
    const wrapper = await setup()
    wrapper.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
    const modal = wrapper.findComponent(Modal)
    const input = modal.findAllComponents(Input).find(i => i.props('placeholder') === '例如：订单核心链路基准压测')!
    input.vm.$emit('update:modelValue', 'Unsaved plan'); await flushPromises()
    expect(modal.props('onBeforeOk')).toBeTypeOf('function')
    expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false)
    expect(modal.props('visible')).toBe(true)
    expect(input.props('modelValue')).toBe('Unsaved plan')
  })
  it('ignores a slow detail response after selecting another plan', async () => {
    let delay = false; let release!: (value: unknown) => void
    const wrapper = await setup(url => delay && url.endsWith('/plans/a') ? new Promise(resolve => { release = resolve }) : undefined)
    delay = true
    const rows = wrapper.findAll('.plan-item')
    await rows[0]!.trigger('click'); await rows[1]!.trigger('click'); await flushPromises()
    release(plan('a')); await flushPromises()
    expect(wrapper.find('.plan-header h2').text()).toBe('b')
  })
  it('keeps the thread name input mounted as the user types', async () => {
    const wrapper = await setup()
    wrapper.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
    const input = wrapper.findComponent(Modal).findAllComponents(Input).find(i => i.props('placeholder') === '例如：下单主链路')!
    input.vm.$emit('update:modelValue', 'New thread name'); await flushPromises()
    const updated = wrapper.findComponent(Modal).findAllComponents(Input).find(i => i.props('placeholder') === '例如：下单主链路')!
    expect(updated.vm).toBe(input.vm)
    expect(updated.props('modelValue')).toBe('New thread name')
  })
  it('retains invalid JSON and does not submit it', async () => {
    const wrapper = await setup()
    wrapper.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
    const modal = wrapper.findComponent(Modal)
    const textarea = modal.findAllComponents(Textarea).find(t => t.props('placeholder') === '{"Content-Type":"application/json"}')!
    textarea.vm.$emit('update:modelValue', '{'); await flushPromises()
    expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false)
    expect(modal.props('visible')).toBe(true)
    expect(textarea.props('modelValue')).toBe('{')
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
  it('discards old project plans and closes its editor on project change', async () => {
    let delay = false; let release!: (value: unknown) => void
    const wrapper = await setup(url => delay && url.includes('/p2/') && url.endsWith('/plans') ? new Promise(resolve => { release = resolve }) : undefined)
    wrapper.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
    delay = true
    const select = wrapper.findAllComponents(Select)[0]!
    select.vm.$emit('update:modelValue', 'p2'); select.vm.$emit('change'); await flushPromises()
    expect(wrapper.findComponent(Modal).props('visible')).toBe(false)
    expect(wrapper.findAll('.plan-item')).toHaveLength(0)
    select.vm.$emit('update:modelValue', 'p1'); select.vm.$emit('change'); await flushPromises()
    release([plan('old-project')]); await flushPromises()
    expect(wrapper.find('.plan-header h2').text()).toBe('a')
    expect(wrapper.findAll('.plan-item').map(x => x.text()).join()).not.toContain('old-project')
  })

  it('ignores a report response after the drawer is closed', async () => {
    let release!: (value: unknown) => void
    const execution = { id: 'e1', planId: 'a', status: 'SUCCEEDED', requestedAt: '2026-09-15T00:00:00Z' }
    const wrapper = await setup(url => url.endsWith('/executions') ? [execution]
      : url.endsWith('/report') ? new Promise(resolve => { release = resolve }) : undefined, true)
    const button = wrapper.findAllComponents({ name: 'Button' }).find(b => b.text() === '报告')!
    button.vm.$emit('click'); await flushPromises()
    const drawer = wrapper.findComponent(Drawer)
    drawer.vm.$emit('cancel'); await flushPromises()
    release({ execution, summary: {}, quality: {}, metrics: [], resources: [], errors: [], logs: [], artifacts: [] }); await flushPromises()
    expect(drawer.props('visible')).toBe(false)
    expect(wrapper.find('.report-heading').exists()).toBe(false)
  })

})
