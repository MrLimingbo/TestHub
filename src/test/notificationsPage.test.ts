import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, InputPassword, Modal, Popconfirm, Select } from '@arco-design/web-vue'
import NotificationTemplatesPage from '@/pages/NotificationTemplatesPage.vue'
import { useSessionStore } from '@/stores/session'
const wrappers: ReturnType<typeof mount>[] = []
const stamp = '2026-10-05T00:00:00Z'
const template = (project: string) => ({ id: 'template-' + project, name: project === 'p1' ? '模板一' : '模板二', eventCode: 'quality.report.ready', channel: 'WEBHOOK', subjectTemplate: 'subject', bodyTemplate: 'body', targetUrl: 'https://hooks.example.test/path', signingSecretConfigured: true, enabled: true, updatedAt: stamp })
const delivery = (project: string) => ({ id: 'delivery-' + project, templateName: project === 'p1' ? 'QA_ONLY_DELIVERY_ONE' : 'QA_ONLY_DELIVERY_TWO', eventCode: 'quality.report.ready', channel: 'WEBHOOK', status: 'SUCCEEDED', attemptCount: 1, nextAttemptAt: stamp, deliveredAt: stamp, createdAt: stamp })
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); delete (window as Window & { testhubDesktop?: unknown }).testhubDesktop })
async function setup(override?: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init)
    if (custom && typeof custom === 'object' && 'ok' in custom) return custom
    const project = url.includes('/p2/') ? 'p2' : 'p1'
    const data = custom ?? (url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : url.endsWith('/notification-templates') ? [template(project)] : url.endsWith('/notification-deliveries') ? [delivery(project)] : [])
    return { ok: true, json: async () => ({ data }) }
  }))
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.organizations = [{ id: 'o1', name: '组织', code: 'org' }]
  session.projectsByOrganization = { o1: ['p1', 'p2'].map(id => ({ id, name: id, code: id, description: '', status: 'ACTIVE', createdByName: null, createdAt: stamp, updatedAt: stamp })) }
  session.projectId = 'p1'
  const wrapper = mount(NotificationTemplatesPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return { wrapper, session }
}
const button = (w: ReturnType<typeof mount>, name: string) => w.findAllComponents(Button).find(b => b.text() === name)!
const modal = (w: ReturnType<typeof mount>) => w.findComponent(Modal)
const submit = (w: ReturnType<typeof mount>) => modal(w).props('onBeforeOk')!(vi.fn())
const mutations = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method)
const failed = { ok: false, json: async () => ({ message: '通知服务暂不可用' }) }
describe('notification project and form boundaries', () => {
  it('top project selection reloads templates and deliveries and closes old forms', async () => {
    const { wrapper, session } = await setup(); button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    session.selectProject('p2'); await flushPromises(); expect(modal(wrapper).props('visible')).toBe(false)
    expect(wrapper.text()).toContain('模板二'); expect(wrapper.text()).toContain('QA_ONLY_DELIVERY_TWO'); expect(wrapper.text()).not.toContain('模板一'); expect(wrapper.text()).not.toContain('QA_ONLY_DELIVERY_ONE')
  })
  it('late response from original project cannot overwrite the current project', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.endsWith('/p1/notification-templates') ? new Promise(r => { release = r }) : undefined)
    session.selectProject('p2'); await flushPromises(); release([template('p1')]); await flushPromises()
    expect(wrapper.text()).toContain('模板二'); expect(wrapper.text()).not.toContain('模板一')
  })
  it('failed project reads clear old rows and show retry', async () => {
    const { wrapper, session } = await setup(url => url.includes('/p2/') ? failed : undefined)
    session.selectProject('p2'); await flushPromises(); expect(wrapper.text()).toContain('通知服务暂不可用'); expect(wrapper.text()).not.toContain('QA_ONLY_DELIVERY_ONE'); expect(button(wrapper, '重试')).toBeTruthy()
  })
  it('failed save retains input and pending submits are deduplicated', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup((_, init) => init?.method === 'PUT' ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper)
    expect(await submit(wrapper)).toBe(false); await flushPromises(); expect(mutations()).toHaveLength(1)
    release(failed); expect(await pending).toBe(false); expect(modal(wrapper).props('visible')).toBe(true); expect(modal(wrapper).findAllComponents(Input)[0]!.props('modelValue')).toBe('模板一')
  })
  it('captures original project, template and secret before csrf; replacement stays open', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); modal(wrapper).findComponent(InputPassword).vm.$emit('update:modelValue', 'qa-signing-fixture'); await flushPromises()
    const pending = submit(wrapper); await flushPromises(); session.selectProject('p2'); await flushPromises(); button(wrapper, '新建模板').vm.$emit('click'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); expect(await pending).toBe(false)
    expect(mutations()[0]![0]).toBe('/api/v1/projects/p1/notification-templates/template-p1'); expect(JSON.parse(String(mutations()[0]![1]!.body))).toMatchObject({ name: '模板一', signingSecret: 'qa-signing-fixture' }); expect(modal(wrapper).props('visible')).toBe(true)
    expect((modal(wrapper).find('input[type=password]').element as HTMLInputElement).value).toBe('')
  })
  it('cancel clears signing plaintext and invalidates a pending completion', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup((_, init) => init?.method === 'PUT' ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); modal(wrapper).findComponent(InputPassword).vm.$emit('update:modelValue', 'qa-signing-fixture'); await flushPromises()
    const pending = submit(wrapper); await flushPromises(); modal(wrapper).vm.$emit('cancel'); await flushPromises(); expect((modal(wrapper).find('input[type=password]').element as HTMLInputElement).value).toBe('')
    release({}); expect(await pending).toBe(false)
  })
  it('cancelling during refresh prevents a late successful modal-close callback', async () => {
    let refresh = false, release!: (value: unknown) => void
    const { wrapper } = await setup((url, init) => {
      if (init?.method === 'PUT') { refresh = true; return {} }
      if (refresh && url.endsWith('/notification-templates')) return new Promise(r => { release = r })
      return undefined
    })
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper); await flushPromises()
    // Cancelling after the write but before refresh completion invalidates the old modal callback.
    modal(wrapper).vm.$emit('cancel'); await flushPromises(); release([template('p1')]); expect(await pending).toBe(false)
  })
  it('manual resend is explicitly confirmed and pending resend cannot be duplicated', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup((url, init) => url.endsWith('/retry') && init?.method === 'POST' ? new Promise(r => { release = r }) : undefined)
    const confirm = wrapper.findAllComponents(Popconfirm).find(p => String(p.props('content')).includes('重复通知'))!
    expect(confirm.exists()).toBe(true); expect(mutations()).toHaveLength(0)
    confirm.vm.$emit('ok'); confirm.vm.$emit('ok'); await flushPromises(); expect(mutations()).toHaveLength(1)
    release({}); await flushPromises()
  })
  it('delete requires confirmation and captures original route through project switch', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    const confirm = wrapper.findAllComponents(Popconfirm).find(p => String(p.props('content')).includes('删除模板'))!
    expect(confirm.exists()).toBe(true); confirm.vm.$emit('ok'); await flushPromises(); session.selectProject('p2'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); await flushPromises(); expect(mutations()[0]![0]).toBe('/api/v1/projects/p1/notification-templates/template-p1'); expect(wrapper.text()).toContain('模板二')
  })
  it('inactive channel definition makes actual delivery limitation visible', async () => {
    const { wrapper } = await setup(); button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    modal(wrapper).findComponent(Select).vm.$emit('update:modelValue', 'EMAIL'); await flushPromises(); expect(modal(wrapper).text()).toContain('尚未接入实际发送')
  })
  it('local workflow connects the account service rather than attempting an authenticated migration directly', async () => {
    const connect = vi.fn().mockResolvedValue(undefined)
    ;(window as Window & { testhubDesktop?: unknown }).testhubDesktop = { workspaceMode: 'local', connectAccount: connect }
    const { wrapper } = await setup(); button(wrapper, '连接账号服务').vm.$emit('click'); await flushPromises(); expect(connect).toHaveBeenCalledOnce(); expect(wrapper.text()).toContain('接收端须能被当前电脑访问')
  })
  it('unmounted notification view ignores delayed resources', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(url => url.endsWith('/notification-templates') ? new Promise(r => { release = r }) : undefined)
    wrapper.unmount(); release([template('p1')]); await flushPromises(); expect(wrapper.exists()).toBe(false)
  })
})

describe('notification sync desktop entry', () => {
  it('opens native sync in local and account modes without issuing business writes', async () => {
    for (const workspaceMode of ['local', 'server']) {
      const open = vi.fn().mockResolvedValue(undefined)
      ;(window as Window & { testhubDesktop?: unknown }).testhubDesktop = { workspaceMode, openNotificationSync: open }
      const { wrapper } = await setup(); button(wrapper, '通知模板同步').vm.$emit('click'); await flushPromises()
      expect(open).toHaveBeenCalledOnce(); expect(mutations()).toHaveLength(0); wrapper.unmount()
    }
  })
  it('ordinary web view does not show the native sync action', async () => {
    const { wrapper } = await setup(); expect(wrapper.text()).not.toContain('通知模板同步')
  })
})
