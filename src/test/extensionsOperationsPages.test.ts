import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue, { Button, Input, Modal, Popconfirm, Textarea } from '@arco-design/web-vue'
import PluginsPage from '@/pages/PluginsPage.vue'
import OperationsPage from '@/pages/OperationsPage.vue'
const wrappers: ReturnType<typeof mount>[] = []
const stamp = '2026-10-05T00:00:00Z'
const adapter = { id: 'adapter-one', adapterType: 'OIDC', displayName: 'QA_ONLY_ADAPTER', endpoint: 'https://login.example.test', configuredFields: ['clientId', 'clientSecret'], enabled: false, createdAt: stamp, updatedAt: stamp }
const plugin = { id: 'plugin-one', pluginKey: 'qa.plugin', displayName: 'QA_ONLY_PLUGIN', pluginType: 'INTEGRATION', activeVersion: '1.0.0', status: 'DISABLED', permissions: ['integration:outbound'], installedAt: stamp, updatedAt: stamp }
const key = { id: 'key-one', keyId: 'qa.key', displayName: 'QA_SIGNER', enabled: true, createdAt: stamp }
const overview = { runtimeMode: 'DESKTOP', status: 'UP', checkedAt: stamp, components: ['H2', '本地持久队列', '本地文件存储'].map((label, i) => ({ key: String(i), label, status: 'UP', detail: '正常' })), controls: [{ key: 'distribution', label: '安装包发行验收', status: 'ATTENTION', detail: '尚待验收' }], dataProtection: { migrationVersion: 'V19', migrationStatus: 'UP', migrationDetail: '正常', controls: [{ key: 'backup', label: '备份校验', status: 'ATTENTION', detail: '未核验' }] }, capacity: { status: 'ATTENTION', summary: 'QA capacity', controls: [{ key: 'resource', label: '资源告警', status: 'ATTENTION', detail: '待核对' }] } }
const failed = { ok: false, json: async () => ({ message: 'QA 服务暂不可用' }) }
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); delete (window as Window & { testhubDesktop?: unknown }).testhubDesktop })
async function setup(component: typeof PluginsPage | typeof OperationsPage, override?: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init)
    if (custom && typeof custom === 'object' && 'ok' in custom) return custom
    const data = custom ?? (url.endsWith('/capabilities') ? { systemAdministration: true } : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : url.endsWith('/overview') ? overview : url.endsWith('/plugins') ? [plugin] : url.endsWith('/adapters') ? [adapter] : url.endsWith('/signing-keys') ? [key] : url.endsWith('/adapter-definitions') ? [{ type: 'OIDC', displayName: 'OIDC', category: '身份', requiredFields: ['clientId', 'clientSecret'], description: 'QA' }] : {})
    return { ok: true, json: async () => ({ data }) }
  }))
  const wrapper = mount(component, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
const button = (w: ReturnType<typeof mount>, text: string) => w.findAllComponents(Button).find(b => b.text() === text)!
const modal = (w: ReturnType<typeof mount>, index: number) => w.findAllComponents(Modal)[index]!
const submit = (w: ReturnType<typeof mount>, index: number) => modal(w, index).props('onBeforeOk')!(vi.fn())
const mutations = () => vi.mocked(fetch).mock.calls.filter(([, init]) => init?.method && init.method !== 'GET')
describe('extensions capability and forms', () => {
  it('ordinary account reads definitions but never requests signing keys or mounts mutation forms', async () => {
    const w = await setup(PluginsPage, url => url.endsWith('/capabilities') ? { systemAdministration: false } : undefined)
    expect(w.text()).toContain('QA_ONLY_PLUGIN'); expect(w.text()).toContain('当前账号可查看登记信息'); expect(w.findAllComponents(Modal)).toHaveLength(0)
    expect(button(w, '新建适配器')).toBeUndefined(); expect(button(w, '安装签名包')).toBeUndefined(); expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/signing-keys'))).toBe(false)
  })
  it('capability failure is visible with retry and does not read registry', async () => {
    let reject = true
    const w = await setup(PluginsPage, url => url.endsWith('/capabilities') && reject ? failed : undefined)
    expect(w.text()).toContain('QA 服务暂不可用'); expect(vi.mocked(fetch).mock.calls).toHaveLength(1)
    reject = false; button(w, '重试').vm.$emit('click'); await flushPromises(); expect(w.text()).toContain('QA_ONLY_PLUGIN')
  })
  it('failed refresh clears registry and signing key network failure is distinct from no permission', async () => {
    let reject = false
    const w = await setup(PluginsPage, url => reject && url.endsWith('/plugins') ? failed : url.endsWith('/signing-keys') ? failed : undefined)
    expect(w.text()).toContain('QA 服务暂不可用'); expect(button(w, '安装签名包')).toBeTruthy()
    reject = true; button(w, '刷新插件与适配器').vm.$emit('click'); await flushPromises(); expect(w.text()).not.toContain('QA_ONLY_PLUGIN')
  })
  it('adapter edit submits null to preserve encrypted configuration without re-entering credentials', async () => {
    const w = await setup(PluginsPage); button(w, '编辑').vm.$emit('click'); await flushPromises()
    expect(modal(w, 2).findComponent(Textarea).props('modelValue')).toBe(''); expect(modal(w, 2).text()).toContain('留空保留现有加密配置')
    expect(await submit(w, 2)).toBe(true); expect(JSON.parse(String(mutations()[0]![1]!.body))).toMatchObject({ displayName: 'QA_ONLY_ADAPTER', configuration: null })
  })
  it('adapter failure retains secret draft and pending saves are deduplicated', async () => {
    let release!: (value: unknown) => void
    const w = await setup(PluginsPage, (_, init) => init?.method === 'PUT' ? new Promise(r => { release = r }) : undefined)
    button(w, '编辑').vm.$emit('click'); await flushPromises(); modal(w, 2).findComponent(Textarea).vm.$emit('update:modelValue', '{"clientSecret":"qa-secret-draft"}'); await flushPromises()
    const pending = submit(w, 2); expect(await submit(w, 2)).toBe(false); await flushPromises(); expect(mutations()).toHaveLength(1)
    release(failed); expect(await pending).toBe(false); expect(modal(w, 2).props('visible')).toBe(true); expect(modal(w, 2).findComponent(Textarea).props('modelValue')).toContain('qa-secret-draft')
  })
  it('adapter target and fields are frozen before CSRF; cancelled callback cannot close replacement', async () => {
    let release!: (value: unknown) => void
    const w = await setup(PluginsPage, url => url.endsWith('/csrf') ? new Promise(r => { release = r }) : undefined)
    button(w, '编辑').vm.$emit('click'); await flushPromises(); const pending = submit(w, 2); await flushPromises()
    modal(w, 2).vm.$emit('cancel'); button(w, '新建适配器').vm.$emit('click'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); expect(await pending).toBe(false); expect(mutations()[0]![0]).toBe('/api/v1/extensions/adapters/adapter-one'); expect(JSON.parse(String(mutations()[0]![1]!.body)).configuration).toBeNull(); expect(modal(w, 2).props('visible')).toBe(true)
  })
  it('package failure retains exact signed characters and cancel clears manifest/signature', async () => {
    const w = await setup(PluginsPage, (_, init) => init?.method === 'POST' ? failed : undefined)
    button(w, '安装签名包').vm.$emit('click'); await flushPromises(); const raw = ' \n{"qa":true}\n '
    const inputs = modal(w, 1).findAllComponents(Textarea); inputs[0]!.vm.$emit('update:modelValue', raw); inputs[1]!.vm.$emit('update:modelValue', 'qa-signature'); await flushPromises()
    expect(await submit(w, 1)).toBe(false); expect(JSON.parse(String(mutations()[0]![1]!.body)).manifestJson).toBe(raw); expect(modal(w, 1).props('visible')).toBe(true)
    modal(w, 1).vm.$emit('cancel'); await flushPromises(); expect(inputs[0]!.props('modelValue')).toBe(''); expect(inputs[1]!.props('modelValue')).toBe('')
  })
  it('key registration cancellation invalidates a pending success', async () => {
    let release!: (value: unknown) => void
    const w = await setup(PluginsPage, (_, init) => init?.method === 'POST' ? new Promise(r => { release = r }) : undefined)
    button(w, '登记密钥').vm.$emit('click'); await flushPromises(); modal(w, 0).findAllComponents(Input)[0]!.vm.$emit('update:modelValue', 'qa.signer'); await flushPromises()
    const pending = submit(w, 0); await flushPromises(); modal(w, 0).vm.$emit('cancel'); release({}); expect(await pending).toBe(false)
  })
  it('plugin/key trust changes require confirmation and repeated actions are deduplicated', async () => {
    let release!: (value: unknown) => void
    const w = await setup(PluginsPage, (_, init) => init?.method === 'POST' ? new Promise(r => { release = r }) : undefined)
    const confirms = w.findAllComponents(Popconfirm); expect(confirms.some(p => String(p.props('content')).includes('已登记插件保持原状态'))).toBe(true)
    const pluginConfirm = confirms.find(p => String(p.props('content')).includes('声明权限'))!
    expect(mutations()).toHaveLength(0); pluginConfirm.vm.$emit('ok'); pluginConfirm.vm.$emit('ok'); await flushPromises(); expect(mutations()).toHaveLength(1); release({}); await flushPromises()
  })
  it('unmount suppresses late adapter completion', async () => {
    let release!: (value: unknown) => void
    const w = await setup(PluginsPage, (_, init) => init?.method === 'PUT' ? new Promise(r => { release = r }) : undefined)
    button(w, '编辑').vm.$emit('click'); await flushPromises(); modal(w, 2).findComponent(Textarea).vm.$emit('update:modelValue', '{"clientSecret":"qa-draft"}'); await flushPromises(); const pending = submit(w, 2); await flushPromises(); w.unmount(); release({}); expect(await pending).toBe(false)
  })
})
describe('operations permission and projection', () => {
  it('ordinary account reads only permission and never fetches management overview', async () => {
    const w = await setup(OperationsPage, () => ({ systemAdministration: false })); expect(w.text()).toContain('运行状态由系统管理员维护'); expect(vi.mocked(fetch).mock.calls).toHaveLength(1)
  })
  it('local shows actual dependency labels, attention item count and native backup action', async () => {
    const openBackup = vi.fn(async () => {}); (window as Window & { testhubDesktop?: unknown }).testhubDesktop = { workspaceMode: 'local', openBackup }
    const w = await setup(OperationsPage); expect(w.text()).toContain('H2、本地持久队列、本地文件存储'); expect(w.text()).toContain('安装包发行边界'); expect(w.findAll('.metric strong')[3]!.text()).toBe('3')
    button(w, '加密备份与恢复').vm.$emit('click'); await flushPromises(); expect(openBackup).toHaveBeenCalledOnce()
  })
  it('failed refresh clears old health and exposes retry', async () => {
    let reject = false; const w = await setup(OperationsPage, url => reject && url.endsWith('/overview') ? failed : undefined)
    reject = true; button(w, '刷新检查').vm.$emit('click'); await flushPromises(); expect(w.text()).toContain('QA 服务暂不可用'); expect(w.find('.readiness-hero').exists()).toBe(false)
  })
  it('late overview cannot overwrite a newer refresh', async () => {
    let calls = 0, release!: (value: unknown) => void
    const w = await setup(OperationsPage, url => url.endsWith('/overview') && ++calls === 1 ? new Promise(r => { release = r }) : undefined)
    button(w, '刷新检查').vm.$emit('click'); await flushPromises(); release({ ...overview, dataProtection: { ...overview.dataProtection, migrationVersion: 'OBSOLETE' } }); await flushPromises(); expect(w.text()).toContain('V19'); expect(w.text()).not.toContain('OBSOLETE')
  })
})
