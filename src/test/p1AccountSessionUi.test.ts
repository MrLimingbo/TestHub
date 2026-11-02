import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Button, InputPassword, Message, Modal } from '@arco-design/web-vue'
import AccountManagementPage from '@/pages/AccountManagementPage.vue'
import { useSessionStore } from '@/stores/session'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear() })
async function setup(self: boolean, fail = false) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const target = { id: self ? 'me' : 'other', account: 'qa-account', displayName: 'QA', email: 'qa@example.test', phone: null, enabled: true, createdAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:00:00Z' }
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => new Response(JSON.stringify(init?.method === 'PUT' && fail ? { message: '受控保存失败' } : { data: url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' } : init?.method ? {} : { items: [target], total: 1, page: 1, pageSize: 10 } }), { status: init?.method === 'PUT' && fail ? 503 : 200 })))
  const pinia = createPinia(), session = useSessionStore(pinia); session.signedIn = true; session.initialized = true; session.profile = { id: 'me', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: '2026-10-29T00:00:00Z' }
  const router = createRouter({ history: createMemoryHistory(), routes: ['/accounts', '/login'].map(path => ({ path, component: { template: '<div />' } })) }); await router.push('/accounts')
  const w = mount(AccountManagementPage, { global: { plugins: [pinia, ArcoVue, router], stubs: { Modal: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  w.findAllComponents(Button).find(b => b.text() === '修改密码')!.vm.$emit('click'); await flushPromises()
  const modal = w.findAllComponents(Modal).find(m => String(m.props('title')).startsWith('修改密码 ·'))!
  const password = crypto.randomUUID(); modal.findAllComponents(InputPassword).forEach(input => input.vm.$emit('update:modelValue', password)); await flushPromises()
  return { w, modal, session, router, save: () => (modal.props('onBeforeOk') as () => Promise<boolean>)() }
}
it('[P1-U05-01][POS] self password reset clears the old client identity and asks for login', async () => {
  const q = await setup(true); expect(await q.save()).toBe(true); await flushPromises()
  expect(q.session.signedIn).toBe(false); expect(q.router.currentRoute.value.path).toBe('/login'); expect(Message.success).toHaveBeenCalledWith(expect.stringContaining('重新登录'))
})
it('[P1-U05-02][POS] resetting another account keeps the current administrator signed in', async () => {
  const q = await setup(false); expect(await q.save()).toBe(true); expect(q.session.signedIn).toBe(true); expect(q.router.currentRoute.value.path).toBe('/accounts')
})
it('[P1-U05-03][NEG] failed self reset does not falsely log out or close the password draft', async () => {
  const q = await setup(true, true); expect(await q.save()).toBe(false); expect(q.session.signedIn).toBe(true)
  expect(q.modal.props('visible')).toBe(true); expect(q.router.currentRoute.value.path).toBe('/accounts')
})
