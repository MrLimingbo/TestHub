import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, Message, Modal, Textarea } from '@arco-design/web-vue'
import PluginsPage from '@/pages/PluginsPage.vue'

const wrappers: VueWrapper[] = []
const conflict = '同类型的适配器名称已存在，请使用其他名称'
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
async function setup() {
  let reject = true
  const adapter = { id: 'existing', adapterType: 'OIDC', displayName: 'occupied', endpoint: 'https://login.qa.invalid', configuredFields: ['clientId', 'clientSecret', 'redirectUri'], enabled: false, createdAt: '2026-11-04T00:00:00Z', updatedAt: '2026-11-04T00:00:00Z' }
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (['POST', 'PUT'].includes(init?.method ?? '') && url.includes('/adapters')) {
      return new Response(JSON.stringify(reject ? { code: 'REQUEST_REJECTED', message: conflict } : { data: adapter }), { status: reject ? 409 : 200 })
    }
    const data = url.endsWith('/capabilities') ? { systemAdministration: true }
      : url.endsWith('/csrf') ? { token: 'owned-fixture', headerName: 'X-CSRF' }
        : url.endsWith('/adapters') ? [adapter]
          : url.endsWith('/adapter-definitions') ? [{ type: 'OIDC', displayName: 'OIDC', category: 'QA', requiredFields: ['clientId', 'clientSecret', 'redirectUri'] }] : []
    return new Response(JSON.stringify({ data }))
  })
  vi.stubGlobal('fetch', fetcher)
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  const w = mount(PluginsPage, { global: { plugins: [createPinia(), ArcoVue], stubs: { Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises()
  return { w, fetcher, permitRetry: () => { reject = false } }
}

it('C12 create and edit retain the draft after409 and allow a renamed retry', async () => {
  for (const edit of [false, true]) {
    const q = await setup()
    q.w.findAllComponents(Button).find(b => b.text() === (edit ? '编辑' : '新建适配器'))!.vm.$emit('click'); await flushPromises()
    const modal = q.w.findAllComponents(Modal).find(m => String(m.props('title')).includes('企业适配器'))!
    const field = (label: string) => modal.findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === label)!.findComponent(Input)
    field('名称').vm.$emit('update:modelValue', 'occupied'); field('服务地址').vm.$emit('update:modelValue', 'https://login.qa.invalid')
    const draft = JSON.stringify({ clientId: 'owned-client', clientSecret: crypto.randomUUID(), redirectUri: 'https://client.qa.invalid/login' })
    modal.findComponent(Textarea).vm.$emit('update:modelValue', draft); await flushPromises()
    const save = () => (modal.props('onBeforeOk') as () => Promise<boolean>)()
    expect(await save()).toBe(false); await flushPromises()
    expect(Message.error).toHaveBeenLastCalledWith(conflict); expect(modal.props('visible')).toBe(true)
    expect(field('名称').props('modelValue')).toBe('occupied'); expect(modal.findComponent(Textarea).props('modelValue') === draft).toBe(true)
    const writes = () => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? ''))
    expect(writes()).toHaveLength(1)
    field('名称').vm.$emit('update:modelValue', 'free-name'); await flushPromises(); q.permitRetry()
    expect(await save()).toBe(true); await flushPromises(); expect(modal.props('visible')).toBe(false); expect(writes()).toHaveLength(2)
    const [url, init] = writes()[1]!, sent = JSON.parse(String(init?.body))
    expect(init?.method).toBe(edit ? 'PUT' : 'POST'); expect(url).toBe('/api/v1/extensions/adapters' + (edit ? '/existing' : ''))
    expect(sent.displayName).toBe('free-name'); expect(JSON.stringify(sent.configuration) === draft).toBe(true)
  }
})
