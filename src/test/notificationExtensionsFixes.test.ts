import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, Modal, Textarea } from '@arco-design/web-vue'
import { Message } from '@arco-design/web-vue'
import NotificationTemplatesPage from '@/pages/NotificationTemplatesPage.vue'
import PluginsPage from '@/pages/PluginsPage.vue'
import { useSessionStore } from '@/stores/session'

type Kind = 'notification' | 'key' | 'package' | 'adapter'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status >= 400 ? { message: 'QA请求失败' } : { data }), { status })
function deferred() { let resolve!: (value: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const stamp = '2026-10-27T00:00:00Z', csrf = { token: 'fixture', headerName: 'X-CSRF' }
const template = { id: 'n1', name: 'QA模板', eventCode: 'quality.report.ready', channel: 'WEBHOOK', subjectTemplate: 'QA主题', bodyTemplate: 'QA正文', targetUrl: 'https://localhost/qa', signingSecretConfigured: true, enabled: true, updatedAt: stamp }
const adapter = { id: 'a1', adapterType: 'OIDC', displayName: 'QA适配器', endpoint: 'https://service.example.test', configuredFields: ['clientId', 'clientSecret', 'redirectUri'], enabled: false, updatedAt: stamp }
const titles: Record<Kind, string[]> = { notification: ['新建通知模板', '编辑通知模板'], key: ['登记可信 Ed25519 签名密钥'], package: ['安装已签名插件包'], adapter: ['新建企业适配器', '编辑企业适配器'] }
const button = (w: VueWrapper, name: string) => w.findAllComponents(Button).find(b => b.text() === name)!
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(m => titles[kind].includes(String(m.props('title'))))!
const field = (w: VueWrapper, kind: Kind) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === { notification: '模板名称', key: '显示名称', package: '插件 JSON 清单', adapter: '名称' }[kind])!.findComponent(kind === 'package' ? Textarea : Input)
const primaryValue = (kind: Kind, name: string) => kind === 'package' ? JSON.stringify({ pluginKey: 'qa.fixture', displayName: name, version: '1.0.0' }) : name
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('update:visible', false); modal(w, kind).vm.$emit('cancel'); await flushPromises() }
async function open(w: VueWrapper, kind: Kind, edit = false, name = '草稿A') {
  button(w, edit ? '编辑' : { notification: '新建模板', key: '登记密钥', package: '安装签名包', adapter: '新建适配器' }[kind]).vm.$emit('click')
  await flushPromises(); field(w, kind).vm.$emit('update:modelValue', primaryValue(kind, name))
  if (kind === 'adapter' && !edit) modal(w, kind).findComponent(Textarea).vm.$emit('update:modelValue', '{"clientId":"qa","clientSecret":"qa-fixture","redirectUri":"https://client.example.test"}')
  await flushPromises()
}
async function setup(kind: Kind) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  let hook: Hook = () => undefined
  const fetcher = vi.fn((url: string, init?: RequestInit) => hook(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? csrf
      : url.endsWith('/capabilities') ? { systemAdministration: true }
        : url.endsWith('/notification-templates') ? [template]
          : url.endsWith('/signing-keys') ? [{ id: 'k1', keyId: 'qa.key', displayName: 'QA密钥', enabled: true, createdAt: stamp }]
            : url.endsWith('/adapters') ? [adapter]
              : url.endsWith('/adapter-definitions') ? [{ type: 'OIDC', requiredFields: ['clientId', 'clientSecret', 'redirectUri'] }] : [])))
  vi.stubGlobal('fetch', fetcher)
  const pinia = createPinia(), session = useSessionStore(pinia); session.organizations = [{ id: 'o1', name: 'QA', code: 'qa' }]
  session.projectsByOrganization = { o1: ['p1', 'p2'].map(id => ({ id, name: id, code: id, description: '', status: 'ACTIVE', createdByName: null, createdAt: stamp, updatedAt: stamp })) }; session.projectId = 'p1'
  const w = mount(kind === 'notification' ? NotificationTemplatesPage : PluginsPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, session, fetcher, hook: (value: Hook) => { hook = value } }
}
type Fixture = Awaited<ReturnType<typeof setup>>
const writes = (q: Fixture) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT', 'DELETE'].includes(init?.method ?? ''))
const variants: [Kind, boolean][] = [['notification', false], ['notification', true], ['key', false], ['package', false], ['adapter', false], ['adapter', true]]
for (const [kind, edit] of variants) {
  for (const action of ['cancel', 'unmount'] as const) it(`${kind} ${edit ? 'edit' : 'create'}: ${action} during CSRF prevents obsolete writes`, async () => {
    const q = await setup(kind), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
    await open(q.w, kind, edit); const pending = save(q.w, kind); await flushPromises()
    if (action === 'cancel') { await cancel(q.w, kind); await open(q.w, kind, false, '新B') } else q.w.unmount()
    old.resolve(response(csrf)); expect(await pending).toBe(false); await flushPromises()
    expect(writes(q)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
    if (action === 'cancel') { expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe(primaryValue(kind, '新B')) }
  })
  for (const status of [200, 503]) it(`${kind} ${edit ? 'edit' : 'create'}: late ${status} preserves the replacement draft`, async () => {
    const q = await setup(kind), old = deferred(); q.hook((_url, init) => ['POST', 'PUT'].includes(init?.method ?? '') ? old.promise : undefined)
    await open(q.w, kind, edit); const pending = save(q.w, kind); await flushPromises(); await cancel(q.w, kind); await open(q.w, kind, false, '新B')
    old.resolve(response({}, status)); expect(await pending).toBe(false); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe(primaryValue(kind, '新B')); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  it(`${kind} ${edit ? 'edit' : 'create'}: current failures retain input and permit retry`, async () => {
    const q = await setup(kind); let fail = true; q.hook((_url, init) => ['POST', 'PUT'].includes(init?.method ?? '') ? Promise.resolve(response({}, fail ? 503 : 200)) : undefined)
    await open(q.w, kind, edit, '保留草稿'); expect(await save(q.w, kind)).toBe(false); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe(primaryValue(kind, '保留草稿')); expect(Message.error).toHaveBeenCalledOnce()
    fail = false; expect(await save(q.w, kind)).toBe(true); await flushPromises(); expect(modal(q.w, kind).props('visible')).toBe(false)
  })
  it(`${kind} ${edit ? 'edit' : 'create'}: submission freezes input and deduplicates pending callbacks`, async () => {
    const q = await setup(kind), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
    await open(q.w, kind, edit, '提交A'); const pending = save(q.w, kind); expect(await save(q.w, kind)).toBe(false); await flushPromises()
    field(q.w, kind).vm.$emit('update:modelValue', primaryValue(kind, '后来B')); old.resolve(response(csrf)); expect(await pending).toBe(true)
    expect(writes(q)).toHaveLength(1); const data = JSON.parse(String(writes(q)[0]![1]?.body))
    expect(kind === 'package' ? JSON.parse(data.manifestJson).displayName : data.name ?? data.displayName).toBe('提交A')
    const expectedUrl = kind === 'notification' ? '/api/v1/projects/p1/notification-templates' + (edit ? '/n1' : '') : '/api/v1/extensions/' + (kind === 'key' ? 'signing-keys' : kind === 'package' ? 'plugins/packages' : 'adapters' + (edit ? '/a1' : ''))
    expect(writes(q)[0]![0]).toBe(expectedUrl)
    if (kind === 'adapter' && edit) expect(data.configuration).toBeNull()
  })
}
for (const edit of [false, true]) it(`notification ${edit ? 'edit' : 'create'}: project switch and return cancels the pending form`, async () => {
  const q = await setup('notification'), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
  await open(q.w, 'notification', edit); const pending = save(q.w, 'notification'); await flushPromises()
  q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises(); old.resolve(response(csrf)); expect(await pending).toBe(false)
  expect(writes(q)).toHaveLength(0); expect(modal(q.w, 'notification').props('visible')).toBe(false); expect(Message.error).not.toHaveBeenCalled()
})
