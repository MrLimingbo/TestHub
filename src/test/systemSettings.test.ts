import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, InputPassword, InputSearch, Modal, Textarea } from '@arco-design/web-vue'
import AccountManagementPage from '@/pages/AccountManagementPage.vue'
import EnvironmentManagementPage from '@/pages/EnvironmentManagementPage.vue'
import ModelConfigurationPage from '@/pages/ModelConfigurationPage.vue'
import SystemSettingsPage from '@/pages/SystemSettingsPage.vue'
import SystemSettingsLayout from '@/layouts/SystemSettingsLayout.vue'
import { useSessionStore } from '@/stores/session'
const route = reactive<{ path: string; redirectedFrom?: { path: string } }>({ path: '/system-settings/accounts' })
const replace = vi.fn(async (path: string) => { route.path = path })
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ replace }) }))
const wrappers: ReturnType<typeof mount>[] = []
const timestamp = '2026-10-05T00:00:00Z'
const account = (id: string) => ({ id, account: id, displayName: id, email: id + '@example.test', phone: '', enabled: true, createdAt: timestamp, updatedAt: timestamp })
const env = (id: string) => ({ id: 'env-' + id, name: id === 'p1' ? '环境一' : '环境二', environmentCode: 'stage', baseUrl: '', description: '', enabled: true, defaultEnvironment: false, variableCount: 0, secretCount: 0, version: 1, createdAt: timestamp, updatedAt: timestamp })
afterEach(() => {
  wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear()
  route.path = '/system-settings/accounts'; delete route.redirectedFrom; replace.mockClear()
  delete (window as Window & { testhubDesktop?: unknown }).testhubDesktop
})
async function setup(component: typeof AccountManagementPage | typeof EnvironmentManagementPage | typeof ModelConfigurationPage | typeof SystemSettingsPage | typeof SystemSettingsLayout, override?: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init)
    if (custom && typeof custom === 'object' && 'ok' in custom) return custom
    const project = url.includes('/p2/') ? 'p2' : 'p1'
    const data = custom ?? (url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' }
      : url.endsWith('/capabilities') ? { systemAdministration: false }
        : url.includes('/accounts?') ? { items: [account('qa-one'), account('qa-two')], page: 1, pageSize: 10, total: 2 }
          : url.endsWith('/system-config') ? [{ key: 'qa.mode', value: 'original', secret: false, updatedAt: timestamp }]
            : url.endsWith('/environments') ? [env(project)]
              : url.endsWith('/parameters') ? { environments: ['stage'], parameters: [] }
                : url.endsWith('/secrets') ? { environments: ['stage'], secrets: [] } : [])
    return { ok: true, json: async () => ({ data }) }
  }))
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.organizations = [{ id: 'o1', name: '组织', code: 'org' }]
  session.projectsByOrganization = { o1: ['p1', 'p2'].map(id => ({ id, name: id, code: id, description: '', status: 'ACTIVE', createdByName: null, createdAt: timestamp, updatedAt: timestamp })) }
  session.projectId = 'p1'; vi.spyOn(session, 'loadAccessContext').mockResolvedValue()
  const wrapper = mount(component, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, Drawer: true, RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' }, RouterView: { template: '<div data-child>mounted child</div>' } }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return { wrapper, session }
}
const button = (w: ReturnType<typeof mount>, text: string) => w.findAllComponents(Button).find(b => b.text() === text)!
const modal = (w: ReturnType<typeof mount>, index = 0) => w.findAllComponents(Modal)[index]!
const submit = (w: ReturnType<typeof mount>, index = 0) => modal(w, index).props('onBeforeOk')!(vi.fn())
const mutation = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method && init.method !== 'GET')
const failed = { ok: false, json: async () => ({ message: '服务暂不可用' }) }

describe('system settings capability gate', () => {
  it('ordinary root defaults to environments and hides privileged menus', async () => {
    route.redirectedFrom = { path: '/system-settings' }
    const { wrapper } = await setup(SystemSettingsLayout)
    expect(replace).toHaveBeenCalledWith('/system-settings/environments'); expect(wrapper.find('[data-child]').exists()).toBe(true)
    expect(wrapper.find('a[href="/system-settings/accounts"]').exists()).toBe(false); expect(wrapper.find('a[href="/system-settings/parameters"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/system-settings/models"]').exists()).toBe(false)
  })
  it('direct privileged route never mounts its child for ordinary accounts', async () => {
    route.path = '/system-settings/models'
    const { wrapper } = await setup(SystemSettingsLayout)
    expect(wrapper.find('[data-child]').exists()).toBe(false); expect(wrapper.text()).toContain('此设置由系统管理员维护')
    expect(vi.mocked(fetch).mock.calls).toHaveLength(1)
  })
  it('admin can mount accounts with all navigation choices', async () => {
    const { wrapper } = await setup(SystemSettingsLayout, () => ({ systemAdministration: true }))
    expect(wrapper.find('[data-child]').exists()).toBe(true); expect(wrapper.find('a[href="/system-settings/parameters"]').exists()).toBe(true)
    expect(wrapper.get('a[href="/system-settings/models"]').text()).toBe('模型配置')
  })
  it('capability failure exposes retry and mounts no management page', async () => {
    let rejected = true
    const { wrapper } = await setup(SystemSettingsLayout, () => rejected ? failed : { systemAdministration: true })
    expect(wrapper.text()).toContain('服务暂不可用'); expect(wrapper.find('[data-child]').exists()).toBe(false)
    rejected = false; button(wrapper, '重试').vm.$emit('click'); await flushPromises(); expect(wrapper.find('[data-child]').exists()).toBe(true)
  })
})

describe('model provider editor', () => {
  async function editProvider(apiKeyConfigured = true, editingId = 'model-provider') {
    const provider = {
      id: 'model-provider', displayName: 'Fixture Provider', apiBaseUrl: 'https://example.test/v1',
      protocol: 'CHAT_COMPLETIONS', retryPolicy: 'TRANSIENT_ERRORS', apiKeyConfigured, models: [], version: 1,
    }
    const otherProvider = { ...provider, id: 'other-provider', displayName: 'Other Provider' }
    const { wrapper } = await setup(ModelConfigurationPage, (url, init) => {
      if (url.endsWith('/model-providers')) return [provider, otherProvider]
      if (init?.method === 'PUT') return provider
    })
    const card = wrapper.get(`article[aria-labelledby="model-provider-${editingId}"]`)
    await card.findAll('button').find(action => action.text() === '编辑')!.trigger('click')
    await flushPromises()
    return { wrapper, key: wrapper.get<HTMLInputElement>('#model-provider-api-key') }
  }

  it('puts the provider name at the top of the form and hides only its duplicate card during editing', async () => {
    const { wrapper } = await editProvider()
    expect(wrapper.get('form').element.firstElementChild?.textContent?.trim()).toBe('编辑 Fixture Provider')
    expect(wrapper.find('#model-provider-model-provider').exists()).toBe(false)
    expect(wrapper.findAll('form.editor, article.provider').map(row => row.get('h2').text())).toEqual(['编辑 Fixture Provider', 'Other Provider'])
    expect(wrapper.find('.provider--pinned').exists()).toBe(false)
    button(wrapper, '取消').vm.$emit('click')
    await flushPromises()
    expect(wrapper.get('#model-provider-model-provider').text()).toBe('Fixture Provider')
  })

  it('keeps the second provider editor below the pinned first card and restores the list on cancel', async () => {
    const { wrapper } = await editProvider(true, 'other-provider')
    expect(wrapper.findAll('form.editor, article.provider').map(row => row.get('h2').text())).toEqual(['Fixture Provider', '编辑 Other Provider'])
    expect(wrapper.get('.provider--pinned h2').text()).toBe('Fixture Provider')
    expect(wrapper.find('#model-provider-other-provider').exists()).toBe(false)
    button(wrapper, '取消').vm.$emit('click')
    await flushPromises()
    expect(wrapper.findAll('article.provider h2').map(heading => heading.text())).toEqual(['Fixture Provider', 'Other Provider'])
    expect(wrapper.find('.provider--pinned').exists()).toBe(false)
  })

  it('opens the add-provider form when the list is empty', async () => {
    const { wrapper } = await setup(ModelConfigurationPage)
    button(wrapper, '添加模型提供商').vm.$emit('click')
    await flushPromises()
    expect(wrapper.get('form.editor h2').text()).toBe('添加模型提供商')
    expect(wrapper.find('.provider--pinned').exists()).toBe(false)
  })

  it('shows a fixed mask for the saved key and keeps it out of discovery and save requests', async () => {
    const { wrapper, key } = await editProvider()
    expect(key.attributes('placeholder')).toBe('••••••••••••')
    expect(key.element.value).toBe('')
    expect(wrapper.get('#model-provider-api-key-status').text()).toContain('已保存密钥')
    button(wrapper, '获取可用模型').vm.$emit('click')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mutation().map(([url, init]) => ({ url, ...JSON.parse(String(init?.body)) }))).toMatchObject([
      { url: '/api/v1/model-providers/discover', apiKey: '', clearApiKey: false },
      { url: '/api/v1/model-providers/model-provider', apiKey: '', clearApiKey: false },
    ])
    expect(wrapper.get('#model-provider-model-provider').text()).toBe('Fixture Provider')
  })

  it('keeps newly entered keys masked and sends the typed value when saving a replacement', async () => {
    const { wrapper, key } = await editProvider()
    await key.setValue('fixture-replacement')
    expect(key.attributes('type')).toBe('password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(JSON.parse(String(mutation()[0]?.[1]?.body))).toMatchObject({ apiKey: 'fixture-replacement', clearApiKey: false })
  })

  it('hides the mask while clearing the saved key and sends no replacement', async () => {
    const { wrapper, key } = await editProvider()
    await key.setValue('fixture-replacement')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(key.element.disabled).toBe(true)
    expect(key.attributes('placeholder')).toBeUndefined()
    expect(wrapper.get('#model-provider-api-key-status').text()).toContain('保存后清除')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const payload = JSON.parse(String(mutation()[0]?.[1]?.body))
    expect(payload.clearApiKey).toBe(true)
    expect(payload).not.toHaveProperty('apiKey')
  })

  it('shows the normal input hint when the provider has no saved key', async () => {
    const { wrapper, key } = await editProvider(false)
    expect(key.attributes('placeholder')).toBe('输入提供商的 API 密钥（如需）')
    expect(wrapper.find('#model-provider-api-key-status').exists()).toBe(false)
  })
})

describe('project environment forms', () => {
  it('project switch closes forms and reloads without previous rows', async () => {
    const { wrapper, session } = await setup(EnvironmentManagementPage)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); expect(modal(wrapper).props('visible')).toBe(true)
    session.selectProject('p2'); await flushPromises(); expect(modal(wrapper).props('visible')).toBe(false)
    expect(wrapper.text()).toContain('环境二'); expect(wrapper.text()).not.toContain('环境一')
  })
  it('obsolete environment response cannot overwrite selected project', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(EnvironmentManagementPage, url => url.endsWith('/p1/environments') ? new Promise(r => { release = r }) : undefined)
    session.selectProject('p2'); await flushPromises(); release([env('p1')]); await flushPromises()
    expect(wrapper.text()).toContain('环境二'); expect(wrapper.text()).not.toContain('环境一')
  })
  it('failed environment save retains inputs and suppresses duplicate submit', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(EnvironmentManagementPage, (_, init) => init?.method === 'PUT' ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper)
    expect(await submit(wrapper)).toBe(false); await flushPromises(); expect(mutation()).toHaveLength(1)
    release(failed); expect(await pending).toBe(false); expect(modal(wrapper).props('visible')).toBe(true)
    expect(modal(wrapper).findAllComponents(Input)[0]!.props('modelValue')).toBe('环境一')
  })
  it('environment save captures original project and cannot close a replacement', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(EnvironmentManagementPage, url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper); await flushPromises()
    session.selectProject('p2'); await flushPromises(); button(wrapper, '新建环境').vm.$emit('click'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); expect(await pending).toBe(false)
    expect(mutation()[0]![0]).toBe('/api/v1/projects/p1/environments/env-p1'); expect(JSON.parse(String(mutation()[0]![1]!.body))).toMatchObject({ name: '环境一', expectedVersion: 1 })
    expect(modal(wrapper).props('visible')).toBe(true)
  })
  it('failed project load clears old environments and offers retry', async () => {
    const { wrapper, session } = await setup(EnvironmentManagementPage, url => url.includes('/p2/') ? failed : undefined)
    session.selectProject('p2'); await flushPromises(); expect(wrapper.text()).toContain('服务暂不可用'); expect(wrapper.text()).not.toContain('环境一'); expect(button(wrapper, '重试')).toBeTruthy()
  })
})

describe('account management forms', () => {
  it('latest account search wins over earlier delayed response', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(AccountManagementPage, url => url.includes('keyword=first') ? new Promise(r => { release = r }) : url.includes('keyword=second') ? { items: [account('second-result')], total: 1, page: 1, pageSize: 10 } : undefined)
    const search = wrapper.findComponent(InputSearch)
    search.vm.$emit('update:modelValue', 'first'); await flushPromises(); search.vm.$emit('search'); await flushPromises()
    search.vm.$emit('update:modelValue', 'second'); await flushPromises(); search.vm.$emit('search'); await flushPromises()
    release({ items: [account('first-result')], total: 1, page: 1, pageSize: 10 }); await flushPromises()
    expect(wrapper.text()).toContain('second-result'); expect(wrapper.text()).not.toContain('first-result')
  })
  it('account save captures immutable fields before csrf', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(AccountManagementPage, url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper); await flushPromises()
    modal(wrapper).findAllComponents(Input)[1]!.vm.$emit('update:modelValue', 'changed while saving'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); expect(await pending).toBe(true)
    expect(JSON.parse(String(mutation()[0]![1]!.body))).toMatchObject({ displayName: 'qa-one' })
  })
  it('account create failure retains modal and entered fields', async () => {
    const { wrapper } = await setup(AccountManagementPage, (_, init) => init?.method === 'POST' ? failed : undefined)
    button(wrapper, '新增账号').vm.$emit('click'); await flushPromises(); modal(wrapper).findAllComponents(Input)[0]!.vm.$emit('update:modelValue', 'qa-new'); await flushPromises()
    expect(await submit(wrapper)).toBe(false); expect(modal(wrapper).props('visible')).toBe(true)
    expect(modal(wrapper).findAllComponents(Input)[0]!.props('modelValue')).toBe('qa-new')
  })
  it('password request captures target and cannot close a replacement password form', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(AccountManagementPage, url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    const buttons = wrapper.findAllComponents(Button).filter(b => b.text() === '修改密码')
    buttons[0]!.vm.$emit('click'); await flushPromises(); const inputs = modal(wrapper, 1).findAllComponents(InputPassword)
    inputs.forEach(i => i.vm.$emit('update:modelValue', 'QA-password-fixture')); await flushPromises()
    const pending = submit(wrapper, 1); expect(await submit(wrapper, 1)).toBe(false); await flushPromises()
    buttons[1]!.vm.$emit('click'); await flushPromises(); release({ token: 'fixture', headerName: 'X-CSRF' }); expect(await pending).toBe(false)
    expect(mutation()).toHaveLength(1); expect(mutation()[0]![0]).toBe('/api/v1/accounts/qa-one/password')
    expect(JSON.parse(String(mutation()[0]![1]!.body))).toEqual({ password: 'QA-password-fixture' }); expect(modal(wrapper, 1).props('visible')).toBe(true)
    expect(modal(wrapper, 1).findAll('input').every(i => (i.element as HTMLInputElement).value === '')).toBe(true)
  })
  it('password cancellation clears plaintext and a failed account reload clears old rows', async () => {
    let rejected = false
    const { wrapper } = await setup(AccountManagementPage, url => rejected && url.includes('/accounts?') ? failed : undefined)
    button(wrapper, '修改密码').vm.$emit('click'); await flushPromises(); modal(wrapper, 1).findAllComponents(InputPassword)[0]!.vm.$emit('update:modelValue', 'QA-password-fixture'); await flushPromises()
    modal(wrapper, 1).vm.$emit('cancel'); await flushPromises(); expect((modal(wrapper, 1).findAll('input')[0]!.element as HTMLInputElement).value).toBe('')
    rejected = true; wrapper.findComponent(InputSearch).vm.$emit('search'); await flushPromises(); expect(wrapper.text()).toContain('服务暂不可用'); expect(wrapper.text()).not.toContain('qa-one@example.test')
  })
})

describe('system parameter forms', () => {
  it('saves an empty ordinary value without showing a required marker', async () => {
    const { wrapper } = await setup(SystemSettingsPage)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    modal(wrapper).findComponent(Textarea).vm.$emit('update:modelValue', ''); await flushPromises()
    expect(modal(wrapper).findAll('.arco-form-item-label-required-symbol').length).toBe(1)
    expect(wrapper.text()).toContain('普通参数可留空')
    expect(await submit(wrapper)).toBe(true)
    expect(JSON.parse(String(mutation()[0]![1]!.body))).toEqual({ key: 'qa.mode', value: '' })
    expect(modal(wrapper).props('visible')).toBe(false)
  })

  it('failed parameter save keeps editor open and pending submit is deduplicated', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(SystemSettingsPage, (_, init) => init?.method === 'POST' ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper)
    expect(await submit(wrapper)).toBe(false); await flushPromises(); expect(mutation()).toHaveLength(1)
    release(failed); expect(await pending).toBe(false); expect(modal(wrapper).props('visible')).toBe(true)
    expect(modal(wrapper).findComponent(Textarea).props('modelValue')).toBe('original')
  })
  it('captures config before csrf and does not close a newly opened form', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup(SystemSettingsPage, url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(wrapper); await flushPromises()
    wrapper.findComponent({ name: 'PageHeader' }).vm.$emit('action'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); expect(await pending).toBe(false)
    expect(JSON.parse(String(mutation()[0]![1]!.body))).toEqual({ key: 'qa.mode', value: 'original' }); expect(modal(wrapper).props('visible')).toBe(true)
  })
  it('shows local policy and opens the existing native authorization window', async () => {
    const open = vi.fn().mockResolvedValue(undefined)
    ;(window as Window & { testhubDesktop?: unknown }).testhubDesktop = { workspaceMode: 'local', openExecutionSettings: open }
    const { wrapper } = await setup(SystemSettingsPage)
    expect(wrapper.text()).toContain('本机 HTTP 私网调试'); expect(wrapper.text()).toContain('按目标地址单独授权')
    button(wrapper, '打开本地执行设置').vm.$emit('click'); await flushPromises(); expect(open).toHaveBeenCalledOnce()
  })
  it('online policy retains restrictions without showing a local authorization action', async () => {
    const { wrapper } = await setup(SystemSettingsPage)
    expect(wrapper.text()).toContain('在线 HTTP 私网调试'); expect(wrapper.text()).toContain('DNS 重绑定'); expect(button(wrapper, '打开本地执行设置')).toBeUndefined()
  })
})
