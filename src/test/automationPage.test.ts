import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ArcoVue, { Modal, Input } from '@arco-design/web-vue'
import AutomationPage from '@/pages/AutomationPage.vue'
import AutomationRunPanel from '@/components/api-automation/AutomationRunPanel.vue'
import AutomationSidebar from '@/components/api-automation/AutomationSidebar.vue'

vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ projectId: 'p1', selectProject: vi.fn(), resolveProjectId: () => 'p1' }) }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals() })

async function setup(saveFails = false) {
  const calls: Array<{ url: string; method: string; body: Record<string, unknown> }> = []
  const scenario = { id: 's1', updatedAt: '2026-09-09T00:00:00Z', name: 'Original', version: 1, description: '', environmentCode: 'test', failureStrategy: 'STOP', steps: [{ id: 'r1', name: 'HTTP', type: 'REQUEST', config: { method: 'GET', url: 'https://example.com' } }], variables: {}, preRequestScript: '', postResponseScript: '' }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const body = JSON.parse(String(init?.body ?? '{}'))
    calls.push({ url, method, body })
    let data: unknown = []
    if (url.endsWith('/access/context')) data = { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }] } }
    else if (url.endsWith('/api-design/definitions')) data = [{ id: 'api1', name: 'Orders', method: 'GET', path: '/orders/{id}', currentVersionId: 'v1' }]
    else if (url.endsWith('/definitions/api1/versions')) data = [{ id: 'v1', requestPath: '[{"name":"id","example":"42","required":true}]', requestQuery: '[{"name":"q","example":"a b"}]', requestHeaders: '[{"name":"Content-Type","example":"application/xml"}]', requestBody: '<request/>', responseBody: '', exampleResponse: '' }]
    else if (url.endsWith('/scenarios')) data = [scenario]
    else if (url.endsWith('/scenarios/s1')) data = method === 'PUT' ? { ...scenario, ...body.scenario, version: 2 } : scenario
    else if (method === 'POST') data = { id: 'exec123', rowCount: 2, executions: [{}, {}] }
    return { ok: !(saveFails && method === 'PUT'), json: async () => ({ data, message: 'Version conflict' }) }
  }))
  const wrapper = shallowMount(AutomationPage, { global: { plugins: [ArcoVue], stubs: { Popover: { template: '<div><slot/><slot name="content"/></div>' } }, config: { warnHandler: () => {} }, renderStubDefaultSlot: true } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, calls }
}

describe('Automation execution editor', () => {
  it('imports version path/query examples and content type into the saved step', async () => {
    const { wrapper, calls } = await setup()
    await wrapper.findAll('button').find(b => b.text().includes('从接口导入'))!.trigger('click')
    const modal = wrapper.findAllComponents(Modal).find(c => c.props('title') === '导入请求步骤')!
    await modal.props('onBeforeOk')!(vi.fn())
    wrapper.findComponent(AutomationRunPanel).vm.$emit('save')
    await flushPromises()
    const saved = calls.find(c => c.method === 'PUT')!.body.scenario as { steps: Array<{ config: unknown }> }
    expect(saved.steps[1].config).toMatchObject({ url: '{{BASE_URL}}/orders/42?q=a%20b', body: '<request/>', contentType: 'application/xml' })
  })
  it.each([false, true])('saves editor changes before execution (dataset=%s)', async dataSet => {
    const { wrapper, calls } = await setup()
    wrapper.findAllComponents(Input).find(c => c.attributes('aria-label') === '场景名称')!.vm.$emit('update:model-value', 'Edited')
    const panel = wrapper.findComponent(AutomationRunPanel)
    if (dataSet) panel.vm.$emit('update:selected-data-set-id', 'd1')
    await wrapper.vm.$nextTick()
    panel.vm.$emit('run')
    await flushPromises()
    const writes = calls.filter(c => c.method !== 'GET')
    expect(writes.map(c => c.method)).toEqual(['PUT', 'POST'])
    expect(writes[0].body.scenario).toMatchObject({ name: 'Edited' })
    expect(writes[1].url).toContain(dataSet ? '/data-sets/d1/scenarios/s1/executions' : '/scenarios/s1/executions')
  })
  it('does not submit execution when saving fails', async () => {
    const { wrapper, calls } = await setup(true)
    wrapper.findComponent(AutomationRunPanel).vm.$emit('update:scenario-environment', 'staging')
    await wrapper.vm.$nextTick()
    wrapper.findComponent(AutomationRunPanel).vm.$emit('run')
    await flushPromises()
    expect(calls.some(c => c.method === 'PUT')).toBe(true)
    expect(calls.some(c => c.method === 'POST')).toBe(false)
  })
  it('does not create an extra revision when nothing changed', async () => {
    const { wrapper, calls } = await setup()
    wrapper.findComponent(AutomationRunPanel).vm.$emit('run')
    await flushPromises()
    expect(calls.filter(c => c.method !== 'GET').map(c => c.method)).toEqual(['POST'])
  })
  it('keeps the create dialog open after required field validation fails', async () => {
    const { wrapper, calls } = await setup()
    wrapper.findComponent(AutomationSidebar).vm.$emit('create')
    await wrapper.vm.$nextTick()
    const modal = wrapper.findComponent(Modal)
    const create = wrapper.findAllComponents(Modal).find(c => c.props('title') === '新建接口自动化场景') ?? modal
    expect(create.props('visible')).toBe(true)
    expect(await create.props('onBeforeOk')!(vi.fn())).toBe(false)
    expect(create.props('visible')).toBe(true)
    expect(calls.some(c => c.method === 'POST')).toBe(false)
  })
})
