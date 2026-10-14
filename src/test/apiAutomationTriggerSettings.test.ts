import { afterEach, expect, it, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { shallowMount, flushPromises } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import AutomationPage from '@/pages/AutomationPage.vue'
import AutomationSidebar from '@/components/api-automation/AutomationSidebar.vue'
const shared = vi.hoisted(() => ({ session: null as unknown as { projectId: string; selectProject: (v: string) => void; resolveProjectId: () => string } }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => shared.session }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => false, isAccountDesktop: () => false, openApiAutomationHistorySync: vi.fn(), openApiAutomationAssetSync: vi.fn(), openApiAutomationScenarioSync: vi.fn(), openApiAutomationTriggerSync: vi.fn() }))
const wrappers: ReturnType<typeof shallowMount>[] = []
const hooks = [
  { id: 'draft', scenarioId: 's1', name: '副本CI', enabled: false, secretReady: false, updatedAt: '2026-10-14T01:00:00Z' },
  { id: 'existing', scenarioId: 's1', name: '原有CI', enabled: false, secretReady: true, updatedAt: '2026-10-14T01:00:00Z' },
]
const scene = { id: 's1', name: '来源场景', version: 1, environmentCode: 'test', failureStrategy: 'STOP', variables: {}, steps: [{ id: 'wait', name: '等待', type: 'WAIT', config: { delayMillis: 1 } }], updatedAt: '2026-10-14T01:00:00Z', createdAt: '2026-10-14T01:00:00Z', description: '', preRequestScript: '', postResponseScript: '' }
const response = (data: unknown) => ({ ok: true, json: async () => ({ data }) })
beforeEach(() => { shared.session = reactive({ projectId: 'p1', selectProject(v: string) { this.projectId = v }, resolveProjectId() { return this.projectId } }) })
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks() })
async function setup(operation?: (route: string, options?: RequestInit) => Promise<unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (route: string, options?: RequestInit) => {
    if (operation && options?.method) return operation(route, options)
    return response(route.endsWith('/access/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }, { id: 'p2' }] } }
      : route.endsWith('/auth/csrf') ? { token: 'fixture', headerName: 'X-CSRF' }
        : route.endsWith('/webhook-triggers') ? hooks
          : route.endsWith('/scenarios') ? [scene]
            : route.endsWith('/scenarios/s1') ? scene : [])
  }))
  const wrapper = shallowMount(AutomationPage, { global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); wrapper.findComponent(AutomationSidebar).vm.$emit('update:activeAsset', 'integrations'); await flushPromises(); return wrapper
}
it('marks copied webhook pending local key and disables its enable action while existing keys remain usable', async () => {
  const wrapper = await setup(); expect(wrapper.text()).toContain('待生成本端密钥')
  const buttons = wrapper.findAllComponents({ name: 'Button' }).filter(b => b.text() === '启用')
  expect(buttons[0].props('disabled')).toBe(true); expect(buttons[1].props('disabled')).toBe(false)
})
it('generates a copied webhook key only through the explicit confirmed local action', async () => {
  const wrapper = await setup(async () => response({ webhook: { ...hooks[0], secretReady: true }, secret: 'fixture-local-key' }))
  wrapper.findAllComponents({ name: 'Popconfirm' }).find(p => String(p.props('content')).startsWith('仅为这个副本'))!.vm.$emit('ok'); await flushPromises()
  const requests = vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST')
  expect(requests).toHaveLength(1); expect(requests[0][0]).toBe('/api/v1/projects/p1/api-automation/webhook-triggers/draft/prepare-secret')
  expect(wrapper.findAllComponents({ name: 'Modal' }).find(m => m.props('title') === '立即保存 Webhook 密钥')!.props('visible')).toBe(true)
  expect(wrapper.text()).toContain('fixture-local-key')
})
it('a late local key cannot appear in a different project after context replacement', async () => {
  let finish!: (v: unknown) => void
  const wrapper = await setup(() => new Promise(resolve => { finish = resolve }))
  wrapper.findAllComponents({ name: 'Popconfirm' }).find(p => String(p.props('content')).startsWith('仅为这个副本'))!.vm.$emit('ok'); await flushPromises()
  shared.session.selectProject('p2'); await flushPromises(); finish(response({ webhook: hooks[0], secret: 'fixture-private-key' })); await flushPromises()
  expect(wrapper.text()).not.toContain('fixture-private-key')
  expect(wrapper.findAllComponents({ name: 'Modal' }).find(m => m.props('title') === '立即保存 Webhook 密钥')!.props('visible')).toBe(false)
})
it('editing existing webhook sends only definition fields without replacing its secret or enabling it', async () => {
  const wrapper = await setup(async () => response(hooks[1]))
  wrapper.findAllComponents({ name: 'Button' }).filter(b => b.text() === '编辑配置')[1].vm.$emit('click'); await flushPromises()
  const dialog = wrapper.findAllComponents({ name: 'Modal' }).find(m => m.props('title') === '编辑签名 Webhook 配置')!
  dialog.findComponent({ name: 'Input' }).vm.$emit('update:modelValue', '修改CI'); await flushPromises()
  await dialog.props('onBeforeOk')(); await flushPromises()
  const request = vi.mocked(fetch).mock.calls.find(([, options]) => options?.method === 'PUT')!
  expect(request[0]).toBe('/api/v1/projects/p1/api-automation/webhook-triggers/existing/configuration')
  expect(JSON.parse(String(request[1]?.body))).toEqual({ name: '修改CI', scenarioId: 's1' })
  expect(wrapper.text()).not.toContain('fixture-local-key')
})
