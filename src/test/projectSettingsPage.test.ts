import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, Modal } from '@arco-design/web-vue'
import ProjectSettingsPage from '@/pages/ProjectSettingsPage.vue'
import { useSessionStore } from '@/stores/session'
const route = reactive({ path: '/project-settings/environments', query: {} })
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }))
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; Reflect.deleteProperty(window, 'testhubDesktop'); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); route.path = '/project-settings/environments' })
async function setup(override?: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init)
    if (custom && typeof custom === 'object' && 'ok' in custom) return custom
    const project = url.includes('/p2/') ? 'p2' : 'p1'
    const data = custom ?? (url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' }
      : url.endsWith('/parameters') ? { environments: ['test'], parameters: [{ id: 'parameter-' + project, environmentCode: 'test', key: project === 'p1' ? 'MODE_ONE' : 'MODE_TWO', value: 'fixture', updatedAt: '2026-10-05T00:00:00Z' }] }
        : url.endsWith('/secrets') ? { environments: ['test'], secrets: [] }
          : url.endsWith('/environments') ? [{ id: 'env-' + project, name: '测试环境', environmentCode: 'test', enabled: true, defaultEnvironment: true }]
            : url.includes('/audit-logs') ? [{ id: project, action: project, createdAt: '2026-10-05T00:00:00Z' }]
              : [])
    return { ok: true, json: async () => ({ data }) }
  }))
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.organizations = [{ id: 'o1', name: '组织', code: 'org' }]
  session.projectsByOrganization = { o1: ['p1', 'p2'].map(id => ({ id, name: id, code: id, description: '', status: 'ACTIVE', createdByName: null, createdAt: '2026-10-05T00:00:00Z', updatedAt: '' })) }
  session.projectId = 'p1'; vi.spyOn(session, 'loadAccessContext').mockResolvedValue()
  const wrapper = mount(ProjectSettingsPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, RouterLink: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return { wrapper, session }
}
const button = (w: ReturnType<typeof mount>, name: string) => w.findAllComponents(Button).find(b => b.text() === name)!
const environmentModal = (w: ReturnType<typeof mount>) => w.findAllComponents(Modal)[2]!
describe('Project settings workspace boundaries', () => {
  it('opens native configuration sync in both desktop modes without submitting page data', async () => {
    for (const workspaceMode of ['local', 'server']) {
      const openSettingsSync = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(window, 'testhubDesktop', { configurable: true, value: { workspaceMode, openSettingsSync } })
      const { wrapper } = await setup(); button(wrapper, '项目配置同步').vm.$emit('click'); await flushPromises()
      expect(openSettingsSync).toHaveBeenCalledOnce(); expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
      wrapper.unmount()
    }
  })
  it('does not display desktop synchronization on the web', async () => {
    const { wrapper } = await setup(); expect(wrapper.text()).not.toContain('项目配置同步')
  })
  it('project selection reloads resources and closes old forms', async () => {
    const { wrapper, session } = await setup()
    button(wrapper, '新建参数').vm.$emit('click'); await flushPromises(); expect(environmentModal(wrapper).props('visible')).toBe(true)
    session.selectProject('p2'); await flushPromises()
    expect(environmentModal(wrapper).props('visible')).toBe(false); expect(wrapper.text()).toContain('MODE_TWO'); expect(wrapper.text()).not.toContain('MODE_ONE')
  })
  it('late previous project resources cannot overwrite the selected project', async () => {
    let release!: (value: unknown) => void
    const pending = setup(url => url.endsWith('/p1/parameters') ? new Promise(resolve => { release = resolve }) : undefined)
    const { wrapper, session } = await pending; session.selectProject('p2'); await flushPromises()
    release({ environments: [], parameters: [{ id: 'late', key: 'LATE', value: 'late' }] }); await flushPromises()
    expect(wrapper.text()).toContain('MODE_TWO'); expect(wrapper.text()).not.toContain('LATE')
  })
  it('failed parameter save keeps entered form open and deduplicates pending submits', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup((url, init) => url.endsWith('/parameters') && init?.method === 'POST' ? new Promise(resolve => { release = resolve }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    const modal = environmentModal(wrapper), input = modal.findAllComponents(Input).find(i => i.props('modelValue') === 'MODE_ONE')!
    expect(input.props('disabled')).toBe(true)
    const save = modal.props('onBeforeOk')!(vi.fn()); expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false); await flushPromises()
    expect(vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    release({ ok: false, json: async () => ({ message: '保存失败' }) }); await flushPromises()
    expect(await save).toBe(false); expect(modal.props('visible')).toBe(true)
  })
  it('captures the project and fields before CSRF and cannot close a replacement form', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.endsWith('/csrf') ? new Promise(resolve => { release = resolve }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    const save = environmentModal(wrapper).props('onBeforeOk')!(vi.fn()); await flushPromises()
    session.selectProject('p2'); await flushPromises(); button(wrapper, '新建参数').vm.$emit('click'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); await flushPromises(); expect(await save).toBe(false)
    const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(call[0]).toBe('/api/v1/projects/p1/parameters'); expect(JSON.parse(String(call[1]!.body))).toMatchObject({ key: 'MODE_ONE', value: 'fixture' })
    expect(environmentModal(wrapper).props('visible')).toBe(true); expect(wrapper.text()).toContain('MODE_TWO')
  })
  it('failed new project load clears old data and exposes retry', async () => {
    const { wrapper, session } = await setup(url => url.endsWith('/p2/parameters') ? { ok: false, json: async () => ({ message: '工作区暂不可用' }) } : undefined)
    session.selectProject('p2'); await flushPromises(); expect(wrapper.text()).toContain('工作区暂不可用'); expect(wrapper.text()).not.toContain('MODE_ONE'); expect(button(wrapper, '重试')).toBeTruthy()
  })
  it('route change loads audit records and discards obsolete log responses', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.includes('/p1/audit-logs?') ? new Promise(resolve => { release = resolve }) : undefined)
    route.path = '/project-settings/logs'; await flushPromises(); session.selectProject('p2'); await flushPromises()
    release([{ id: 'late', action: 'OLD_LOG' }]); await flushPromises(); expect(wrapper.text()).not.toContain('OLD_LOG')
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('/p2/audit-logs?'))).toBe(true)
  })
  it('project creation failures retain the dialog', async () => {
    const { wrapper } = await setup((_, init) => init?.method === 'POST' ? { ok: false, json: async () => ({ message: '标识已存在' }) } : undefined)
    wrapper.findAllComponents(Button).find(b => b.attributes('aria-label') === '新建项目')!.vm.$emit('click'); await flushPromises()
    const modal = wrapper.findAllComponents(Modal)[0]!, inputs = modal.findAllComponents(Input)
    inputs[0]!.vm.$emit('update:modelValue', '新项目'); inputs[1]!.vm.$emit('update:modelValue', 'new-project'); await flushPromises()
    expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false); expect(modal.props('visible')).toBe(true)
  })
  it('file upload captures the originating project before waiting for CSRF', async () => {
    route.path = '/project-settings/files'
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.endsWith('/csrf') ? new Promise(resolve => { release = resolve }) : undefined)
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['fixture'], 'fixture.txt', { type: 'text/plain' })] })
    await input.trigger('change'); await flushPromises(); session.selectProject('p2'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); await flushPromises()
    const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(call[0]).toBe('/api/v1/projects/p1/files'); expect(call[1]!.body).toBeInstanceOf(FormData)
    expect((call[1]!.body as FormData).get('file')).toBeInstanceOf(File)
  })
  it('completion after resource refresh cannot close a newly opened project form', async () => {
    let delayed = false, release!: (value: unknown) => void
    const { wrapper } = await setup((url, init) => {
      if (init?.method === 'PUT') { delayed = true; return { id: 'p1' } }
      if (delayed && url.endsWith('/p1/parameters')) return new Promise(resolve => { release = resolve })
      return undefined
    })
    wrapper.findAllComponents(Button).find(b => b.attributes('aria-label') === '编辑项目 p1')!.vm.$emit('click'); await flushPromises()
    const modal = wrapper.findAllComponents(Modal)[0]!, save = modal.props('onBeforeOk')!(vi.fn()); await flushPromises()
    wrapper.findAllComponents(Button).find(b => b.attributes('aria-label') === '新建项目')!.vm.$emit('click'); await flushPromises()
    release({ environments: [], parameters: [] }); await flushPromises()
    expect(await save).toBe(false); expect(modal.props('visible')).toBe(true)
  })
  it('unmounted view ignores delayed settings responses', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(url => url.endsWith('/p1/parameters') ? new Promise(resolve => { release = resolve }) : undefined)
    wrapper.unmount(); release({ environments: [], parameters: [] }); await flushPromises(); expect(wrapper.exists()).toBe(false)
  })
})
