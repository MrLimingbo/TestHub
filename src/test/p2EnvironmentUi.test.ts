import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Modal, Option } from '@arco-design/web-vue'
import ProjectSettingsPage from '@/pages/ProjectSettingsPage.vue'
import { useSessionStore } from '@/stores/session'

vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/project-settings/environments', query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear() })
async function setup(orphan = false) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify({ data: url.endsWith('/environments') ? [{ id: 'off', name: '停用环境', environmentCode: 'off', defaultEnvironment: true, enabled: false }]
    : url.endsWith('/parameters') ? { environments: ['off', 'orphan'], parameters: orphan ? [{ id: 'old', environmentCode: 'orphan', key: 'LEGACY', value: 'old', updatedAt: '2026-10-30T00:00:00Z' }] : [] }
      : url.endsWith('/secrets') ? { environments: [], secrets: [] } : [] }))))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p'; session.organizations = [{ id: 'o', name: 'QA', code: 'QA' }]; session.projectsByOrganization = { o: [{ id: 'p', name: 'QA', code: 'QA', status: 'ACTIVE', description: '', createdByName: null, createdAt: '', updatedAt: '' }] }
  vi.spyOn(session, 'loadAccessContext').mockResolvedValue()
  const w = mount(ProjectSettingsPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, RouterLink: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises(); return w
}
it('[TC-P2-D01-06][POS][UI] orphan rows remain visible with a missing-definition label', async () => {
  const w = await setup(true); expect(w.text()).toContain('LEGACY'); expect(w.text()).toContain('缺失定义'); expect(w.findAllComponents(Button).find(b => b.text() === '编辑')!.props('disabled')).toBe(true)
})
it('[TC-P2-D03-CFG-02][POS][UI] disabled definitions are selectable for maintenance with an execution warning', async () => {
  const w = await setup(); w.findAllComponents(Button).find(b => b.text() === '新建参数')!.vm.$emit('click'); await flushPromises()
  const modal = w.findAllComponents(Modal).find(m => m.props('title') === '新建环境参数')!
  const option = modal.findAllComponents(Option).find(o => o.props('value') === 'off')!
  expect(option.props('disabled')).not.toBe(true); expect(modal.text()).toContain('不可执行')
})
