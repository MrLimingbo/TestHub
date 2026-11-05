import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Message, Modal, Select } from '@arco-design/web-vue'
import MobileAutomationPage from '@/pages/MobileAutomationPage.vue'

vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => false, isAccountDesktop: () => false }))
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const element = { id: 'e1', name: '旧元素', platform: 'ANDROID', locatorStrategy: 'ID', locatorValue: 'qa', description: '', updatedAt: '2026-10-27T00:00:00Z' }
async function setup(edit = false, invalid = false) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const fetcher = vi.fn((url: string, init?: RequestInit) => Promise.resolve(new Response(JSON.stringify({ data:
    url.endsWith('/context') ? { organizations: [{ id: 'org' }], projects: { org: [{ id: 'p1', organizationId: 'org', name: 'p1', code: 'p1' }] } }
      : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' }
        : ['POST', 'PUT'].includes(init?.method ?? '') ? element
          : url.endsWith('/mobile-automation') ? { devices: [], applications: [], elements: [{ ...element, locatorStrategy: invalid ? 'IOS_PREDICATE' : 'ID' }], scenarios: [], executions: [] } : []
  }), { headers: { 'Content-Type': 'application/json' } })))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(MobileAutomationPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true, MobileAutomationHistoryPanel: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  await w.findAll('.arco-tabs-tab').find(c => c.text() === '元素对象')!.trigger('click'); await flushPromises()
  w.findAllComponents({ name: 'Button' }).find(c => c.isVisible() && c.text() === (edit ? '编辑' : '新建元素'))!.vm.$emit('click'); await flushPromises()
  const modal = w.findAllComponents(Modal).find(c => c.props('title') === (edit ? '编辑元素对象' : '新建元素对象'))!
  const field = (label: string) => modal.findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === label)!
  const platform = field('平台').findComponent(Select), strategy = field('定位策略').findComponent(Select)
  const save = () => (modal.props('onBeforeOk') as () => Promise<boolean>)()
  const writes = () => fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? ''))
  return { platform, strategy, field, save, writes }
}
it('U01 platform options use the approved four-strategy matrix', async () => {
  const q = await setup()
  const values = () => (q.strategy.props('options') as Array<{ value: string }>).map(o => o.value)
  expect(values()).toEqual(['ACCESSIBILITY_ID', 'ID', 'XPATH', 'ANDROID_UIAUTOMATOR'])
  q.platform.vm.$emit('update:modelValue', 'IOS'); await flushPromises()
  expect(values()).toEqual(['ACCESSIBILITY_ID', 'ID', 'XPATH', 'IOS_PREDICATE'])
})
it('U02 switching platform clears exclusive strategies and retains common ones', async () => {
  const q = await setup(); q.strategy.vm.$emit('update:modelValue', 'ANDROID_UIAUTOMATOR'); await flushPromises()
  q.platform.vm.$emit('update:modelValue', 'IOS'); await flushPromises(); expect(q.strategy.props('modelValue')).toBe('')
  q.strategy.vm.$emit('update:modelValue', 'ID'); await flushPromises(); q.platform.vm.$emit('update:modelValue', 'ANDROID'); await flushPromises(); expect(q.strategy.props('modelValue')).toBe('ID')
})
it('U03 legacy invalid element cannot be silently saved', async () => {
  const q = await setup(true, true); await q.save(); expect(q.writes()).toHaveLength(0); expect(Message.error).toHaveBeenCalled()
})
it('U04 valid editing retains the timestamp and locator fields', async () => {
  const q = await setup(true); q.strategy.vm.$emit('update:modelValue', 'XPATH'); await flushPromises(); await q.save()
  expect(q.writes()).toHaveLength(1); expect(JSON.parse(String(q.writes()[0]![1]!.body))).toMatchObject({ expectedUpdatedAt: element.updatedAt, element: { platform: 'ANDROID', locatorStrategy: 'XPATH', locatorValue: 'qa' } })
})
