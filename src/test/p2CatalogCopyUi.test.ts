import { defineComponent } from 'vue'
import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import ApiDesignPage from '@/pages/ApiDesignPage.vue'
import { emptyApiDesignPreferences } from '@/utils/apiDesignPreferences'
import { useSessionStore } from '@/stores/session'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: String(data) }), { status })
function deferred<T = Response>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(release => { resolve = release })
  return { promise, resolve }
}
const timestamp = '2026-10-24T00:00:00Z'
const version = (id = 'v1') => ({ id, versionName: '1.0.0', summary: '', description: '原始快照', requestHeaders: '[]', requestQuery: '[]', requestPath: '[]', requestBody: '{"source":"original"}', responseBody: '{}', exampleResponse: '{"statusCode":200}', status: 'DRAFT', publishedAt: null, createdAt: timestamp })
const definition = (id: string) => ({ id, catalogId: 'root', method: 'GET', path: '/' + id, name: id, status: 'DRAFT', currentVersionId: 'v1', versionCount: 1, createdBy: 'u1', updatedBy: 'u1', createdAt: timestamp, updatedAt: timestamp })
const Workbench = defineComponent({ name: 'ApiInterfaceWorkbench', props: { api: { type: Object, default: null }, version: { type: Object, default: null } }, emits: ['new-version', 'edit'], template: '<div class="test-workbench"><slot name="editor" /></div>' })
async function setup(override?: (url: string, init?: RequestInit) => Promise<Response> | undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  const state = { versions: [version()] }
  const fetcher = vi.fn((url: string, init?: RequestInit) => override?.(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
      : url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', organizationId: 'o1', name: '项目', code: 'P1' }, { id: 'p2', organizationId: 'o1', name: '项目2', code: 'P2' }] } }
        : url.endsWith('/catalogs') ? [{ id: 'root', parentId: null, name: '根目录', sortOrder: 0 }]
          : url.endsWith('/definitions') ? [definition('a'), definition('b')]
            : url.endsWith('/versions') ? state.versions
              : url.endsWith('/workspace-preferences') ? { revision: 0, state: emptyApiDesignPreferences() } : [])))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/api-testing/interfaces', component: { template: '<div />' } }] }); await router.push('/api-testing/interfaces')
  const w = mount(ApiDesignPage, { global: { plugins: [pinia, ArcoVue, router], stubs: { teleport: true, ApiInterfaceWorkbench: Workbench, Dropdown: { template: '<div><slot /><slot name="content" /></div>' }, Dsubmenu: { template: '<div><slot /><slot name="content" /></div>' } }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, session, fetcher, state }
}
const copy = async (w: VueWrapper) => { w.findAllComponents({ name: 'Doption' }).find(item => item.text().trim() === '复制')!.vm.$emit('click'); await flushPromises() }
it('TC-P2-D10-13: ambiguous failure reuses the durable request key and digest', async () => {
  let fail = true
  const { w, fetcher } = await setup((url, init) => url.endsWith('/copy-preview') ? Promise.resolve(response({ sourceDigest: 'a'.repeat(64), itemCount: 2, limit: 200 }))
    : url.endsWith('/copy') && init?.method === 'POST' ? (fail ? Promise.reject(new TypeError('connection lost')) : Promise.resolve(response({ rootCatalogId: 'copied', catalogCount: 1, definitionCount: 1 }))) : undefined)
  await copy(w)
  expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('相同请求重试'))
  fail = false; await copy(w)
  const posts = fetcher.mock.calls.filter(([url, init]) => url.endsWith('/copy') && init?.method === 'POST')
  expect(posts).toHaveLength(2)
  expect(JSON.parse(posts[0]![1]!.body as string)).toEqual(JSON.parse(posts[1]![1]!.body as string))
  expect(JSON.parse(posts[0]![1]!.body as string).requestKey).toMatch(/^[a-f0-9-]{36}$/)
  expect(fetcher.mock.calls.some(([url, init]) => url.endsWith('/catalogs') && init?.method === 'POST')).toBe(false)
})
it('TC-P2-D10-13: project switch during CSRF prevents a copy to the new project', async () => {
  const csrf = deferred()
  const { w, session, fetcher } = await setup(url => url.endsWith('/copy-preview') ? Promise.resolve(response({ sourceDigest: 'a'.repeat(64), itemCount: 2, limit: 200 })) : url.endsWith('/csrf') ? csrf.promise : undefined)
  await copy(w); session.projectId = 'p2'; await flushPromises()
  csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); await flushPromises()
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  expect(Message.success).not.toHaveBeenCalled()
})
it('TC-P2-D10-13: late successful copy cannot refresh the current project', async () => {
  const old = deferred()
  const { w, session, fetcher } = await setup((url, init) => url.endsWith('/copy-preview') ? Promise.resolve(response({ sourceDigest: 'a'.repeat(64), itemCount: 2, limit: 200 })) : init?.method === 'POST' ? old.promise : undefined)
  await copy(w); session.projectId = 'p2'; await flushPromises(); const calls = fetcher.mock.calls.length
  old.resolve(response({ rootCatalogId: 'old-project-copy', catalogCount: 1, definitionCount: 1 })); await flushPromises()
  expect(fetcher.mock.calls.length).toBe(calls)
  expect(Message.success).not.toHaveBeenCalled()
})
it('TC-P2-D10-13: leaving and returning during CSRF cancels the original submission', async () => {
  const csrf = deferred()
  const { w, session, fetcher } = await setup(url => url.endsWith('/copy-preview') ? Promise.resolve(response({ sourceDigest: 'a'.repeat(64), itemCount: 2, limit: 200 })) : url.endsWith('/csrf') ? csrf.promise : undefined)
  await copy(w); session.projectId = 'p2'; session.projectId = 'p1'; await flushPromises()
  csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); await flushPromises()
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
