import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import UiAutomationPage from '@/pages/UiAutomationPage.vue'
import { useSessionStore } from '@/stores/session'
let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); localStorage.clear() })
it('TC-P2R2-D18-05: object tab says queue-time resolution and does not promise save-time freezing', async () => {
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify({ data: url.endsWith('/context') ? { organizations: [{ id: 'o', name: 'QA' }], projects: { o: [{ id: 'p1', name: 'QA', code: 'QA' }] } } : [] }))))
  wrapper = mount(UiAutomationPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); await flushPromises()
  wrapper.findComponent({ name: 'Tabs' }).vm.$emit('update:activeKey', 'objects'); await flushPromises()
  expect(wrapper.text()).toContain('入队时解析并冻结'); expect(wrapper.text()).not.toContain('场景保存时会冻结实际定位器')
})
