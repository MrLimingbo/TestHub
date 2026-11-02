import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import ApiDebugPage from '@/pages/ApiDebugPage.vue'
import { useSessionStore } from '@/stores/session'

const wrappers: VueWrapper[] = []
afterEach(async () => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; await flushPromises(); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: String(data) }), { status })
function deferred<T = Response>() { let resolve!: (value: T) => void; const promise = new Promise<T>(release => { resolve = release }); return { promise, resolve } }
const CodeEditor = { name: 'QaCodeEditor', props: { modelValue: { type: String, default: '' } }, emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
const Select = { name: 'QaSelect', props: { modelValue: { type: [String, Number, Array], default: '' }, placeholder: { type: String, default: '' } }, emits: ['update:modelValue', 'change'], template: '<div><slot /></div>' }
const discovery = (service: string) => ({ services: [service], methods: [{ name: 'Unary', callType: 'UNARY' }], truncated: false })
const wsSession = { id: 'ws-a', status: 'OPEN', target: 'wss://8.8.8.8/echo', messageCount: 0, connectedAt: '2026-10-24T00:00:00Z', autoReconnect: false }
async function setup(protocol = 'HTTP', override?: (url: string, init?: RequestInit) => Promise<Response> | undefined, replay?: unknown) {
  for (const name of ['success', 'error', 'info', 'warning'] as const) vi.spyOn(Message, name).mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  const fetcher = vi.fn((url: string, init?: RequestInit) => override?.(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
      : url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', organizationId: 'o1', name: 'QA', code: 'P1' }, { id: 'p2', organizationId: 'o1', name: 'QA2', code: 'P2' }] } }
        : url.endsWith('/catalogs') ? [{ id: 'root', name: '根目录', parentId: null, sortOrder: 0 }]
          : url.endsWith('/environments') ? [{ environmentCode: 'dev', name: '开发', enabled: true, defaultEnvironment: true }, { environmentCode: 'qa', name: 'QA', enabled: true }]
            : url.endsWith('/parameters') ? { environments: ['dev', 'qa'], parameters: [{ key: 'DEV_VALUE', value: 'dev', environmentCode: 'dev' }, { key: 'QA_VALUE', value: 'qa', environmentCode: 'qa' }] }
              : url.endsWith('/secrets') ? { environments: ['qa'], secrets: [{ key: 'QA_SECRET', environmentCode: 'qa' }] }
                : url.endsWith('/descriptors') ? [{ id: 'offline-b', name: '离线B', serviceCount: 1, sourceFormat: 'PROTO3' }]
                  : url.includes('/discovery') ? discovery('qa.Other')
                    : url.endsWith('/unary') ? { success: true, status: 'OK', body: '{}', durationMs: 1 }
                      : url.endsWith('/connect') || url.endsWith('/sessions/ws-a') ? wsSession
                        : url.endsWith('/curl/import') ? { importedCount: 1, items: ['已保存'] } : [])))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/debug', component: { template: '<div />' } }] })
  await router.push({ path: '/debug', query: replay ? { replayDraft: JSON.stringify(replay) } : { protocol, url: protocol === 'GRPC' ? '8.8.8.8:443' : protocol === 'WEBSOCKET' ? 'wss://8.8.8.8/echo' : 'https://8.8.8.8/echo' } })
  const w = mount(ApiDebugPage, { global: { plugins: [pinia, ArcoVue, router], stubs: { teleport: true, CodeEditor, Select, ApiDebugSyncArchive: true, Dropdown: { template: '<div><slot /><slot name="content" /></div>' } }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, session, fetcher }
}
const button = (w: VueWrapper, label: string) => w.findAllComponents({ name: 'Button' }).find(item => item.text().trim() === label)!
const history = { id: 'history-ws', projectId: 'p1', method: 'GET', protocol: 'WEBSOCKET', requestUrl: 'wss://8.8.8.8/new', replayDraft: JSON.stringify({ protocol: 'WEBSOCKET', request: { url: 'wss://8.8.8.8/new', headers: [], auth: { type: 'NONE' }, subscriptions: ['subscribe'] } }), createdAt: '2026-10-30T00:00:00Z' }
async function connected(closeStatus = 200) {
  const calls: string[] = []
  const q = await setup('WEBSOCKET', (url, init) => {
    if (init?.method === 'POST') calls.push(url)
    if (url.includes('/api-debug/history')) return Promise.resolve(response([history]))
    if (url.endsWith('/close')) return Promise.resolve(response({ ...wsSession, status: 'CLOSED', autoReconnect: false }, closeStatus))
    return undefined
  })
  button(q.w, '连接').vm.$emit('click'); await flushPromises(); return { ...q, calls }
}
it('TC-P2R2-D14-01: read-only history load keeps old session but prevents sending new draft to it', async () => {
  const q = await connected(); button(q.w, '载入').vm.$emit('click'); await flushPromises()
  expect(q.calls.filter(x => x.endsWith('/connect'))).toHaveLength(1); expect(q.calls.some(x => x.endsWith('/close'))).toBe(false)
  expect(button(q.w, '发送文本消息').props('disabled')).toBe(true)
})
it('TC-P2R2-D14-03: explicit replacement closes the owned old session before new connect', async () => {
  const q = await connected(); button(q.w, '载入').vm.$emit('click'); await flushPromises(); button(q.w, '连接').vm.$emit('click'); await flushPromises()
  expect(q.calls.map(x => x.endsWith('/close') ? 'close' : 'connect')).toEqual(['connect', 'close', 'connect'])
})
it('TC-P2R2-D14-04: failed close preserves the old handle and prevents a second connection', async () => {
  const q = await connected(503); button(q.w, '载入').vm.$emit('click'); await flushPromises(); button(q.w, '连接').vm.$emit('click'); await flushPromises()
  expect(q.calls.filter(x => x.endsWith('/connect'))).toHaveLength(1); expect(q.calls.some(x => x.endsWith('/sessions/ws-a/close'))).toBe(true); expect(Message.error).toHaveBeenCalled()
})
for (const action of ['project', 'unmount']) it(`TC-P2R2-D14-05: late creation after ${action} cleans only its own original-project session`, async () => {
  const gate = deferred(); const q = await setup('WEBSOCKET', url => url.endsWith('/connect') ? gate.promise : url.endsWith('/close') ? Promise.resolve(response({ ...wsSession, status: 'CLOSED' })) : undefined)
  button(q.w, '连接').vm.$emit('click'); await flushPromises(); if (action === 'project') q.session.projectId = 'p2'; else q.w.unmount()
  gate.resolve(response(wsSession)); await flushPromises()
  const close = q.fetcher.mock.calls.filter(([url, init]) => url.endsWith('/close') && init?.method === 'POST'); expect(close).toHaveLength(1); expect(close[0]![0]).toContain('/p1/api-debug/websocket/sessions/ws-a/close')
  expect(q.fetcher.mock.calls.some(([url, init]) => url.includes('/sessions/') && (!init?.method || init.method === 'GET'))).toBe(false)
})
