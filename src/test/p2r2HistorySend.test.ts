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
const CodeEditor = { name: 'QaCodeEditor', props: { modelValue: { type: String, default: '' } }, emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
const Select = { name: 'QaSelect', props: { modelValue: { type: [String, Number, Array], default: '' }, placeholder: { type: String, default: '' } }, emits: ['update:modelValue', 'change'], template: '<div><slot /></div>' }
const discovery = (service: string) => ({ services: [service], methods: [{ name: 'Unary', callType: 'UNARY' }], truncated: false })
const wsSession = { id: 'ws-a', status: 'OPEN', target: 'wss://8.8.8.8/echo', messageCount: 0, connectedAt: '2026-10-24T00:00:00Z', autoReconnect: false }
async function setup(protocol = 'HTTP', override?: (url: string, init?: RequestInit) => Promise<Response> | undefined, replay?: unknown, embedded = false, legacyUrl?: string) {
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
  await router.push({ path: '/debug', query: legacyUrl ? { method: 'GET', protocol: 'HTTP', url: legacyUrl, legacyHistory: '1' } : replay ? { replayDraft: JSON.stringify(replay) } : { protocol, url: protocol === 'GRPC' ? '8.8.8.8:443' : protocol === 'WEBSOCKET' ? 'wss://8.8.8.8/echo' : 'https://8.8.8.8/echo' } })
  const w = mount(ApiDebugPage, { props: { embedded }, global: { plugins: [pinia, ArcoVue, router], stubs: { teleport: true, CodeEditor, Select, ApiDebugSyncArchive: true, Dropdown: { template: '<div><slot /><slot name="content" /></div>' } }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, session, fetcher }
}
const button = (w: VueWrapper, label: string) => w.findAllComponents({ name: 'Button' }).find(item => item.text().trim() === label)!
const legacy = (requestUrl = 'https://8.8.8.8/legacy?token=***', extra = {}) => ({ id: 'legacy', projectId: 'p1', method: 'GET', protocol: 'HTTP', requestUrl, replayDraft: null, createdAt: '2026-10-01T00:00:00Z', result: 'COMPLETED', responseStatus: 200, ...extra })
const executed = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([url, init]) => url.endsWith('/execute') && init?.method === 'POST')
const result = { historyId: 'h', success: true, statusCode: 200, durationMs: 1, responseHeaders: [], body: '{}', truncated: false }
async function historySetup(item = legacy(), replay?: unknown, embedded = false) {
  return setup('HTTP', url => url.includes('/api-debug/history') ? Promise.resolve(response([item])) : url.endsWith('/execute') ? Promise.resolve(response(result)) : undefined, replay, embedded, embedded ? item.requestUrl : undefined)
}
async function loadLegacy(q: Awaited<ReturnType<typeof setup>>) { const load = button(q.w, '载入'); if (load) load.vm.$emit('click'); await flushPromises() }
const review = async (w: VueWrapper) => { w.findAllComponents({ name: 'Checkbox' }).find(c => c.text().includes('核对重建请求'))!.vm.$emit('update:modelValue', true); await flushPromises() }
it('TC-P2R2-D13-01: legacy summary clears unrelated credentials without auto execution', async () => {
  const q = await historySetup(legacy('https://8.8.8.8/legacy'), { protocol: 'HTTP', request: { method: 'POST', url: 'https://8.8.8.8/original', body: 'synthetic-body', headers: [{ name: 'Authorization', value: 'synthetic-header' }], auth: { type: 'BEARER', token: 'synthetic-token' } } })
  await loadLegacy(q); expect(executed(q)).toHaveLength(0); expect(q.w.text()).toContain('旧摘要')
  await review(q.w); button(q.w, '发送').vm.$emit('click'); await flushPromises()
  const p = JSON.parse(String(executed(q)[0]![1]!.body)); expect(p.auth.type).toBe('NONE'); expect(p.body).toBe(''); expect(p.headers).toEqual([])
})
for (const embedded of [false, true]) it(`TC-P2R2-D13-02: unresolved historical masks block every UI send, embedded=${embedded}`, async () => {
  const q = await historySetup(legacy(), undefined, embedded); await loadLegacy(q); button(q.w, '发送').vm.$emit('click'); await flushPromises(); expect(executed(q)).toHaveLength(0)
  await review(q.w); button(q.w, embedded ? '发送' : '重发').vm.$emit('click'); await flushPromises(); expect(executed(q)).toHaveLength(0)
})
it('TC-P2R2-D13-03: an ordinary wildcard request is not a historical placeholder', async () => {
  const q = await setup('HTTP', url => url.endsWith('/execute') ? Promise.resolve(response(result)) : undefined, { protocol: 'HTTP', request: { method: 'GET', url: 'https://8.8.8.8/path/***', headers: [], body: '', auth: { type: 'NONE' } } })
  button(q.w, '发送').vm.$emit('click'); await flushPromises(); expect(executed(q)).toHaveLength(1)
})
it('TC-P2R2-D13-04: reconstructed and explicitly reviewed history can be manually sent', async () => {
  const q = await historySetup(); await loadLegacy(q)
  const value = q.w.findAllComponents({ name: 'Input' }).find(c => c.props('modelValue') === '***')!; value.vm.$emit('update:modelValue', 'synthetic-value'); await flushPromises()
  expect(executed(q)).toHaveLength(0); await review(q.w); button(q.w, '发送').vm.$emit('click'); await flushPromises(); expect(executed(q)).toHaveLength(1); expect(JSON.parse(String(executed(q)[0]![1]!.body)).url).toContain('synthetic-value')
})
it('TC-P2R2-D13-05: valid encrypted replay does not reject its redacted summary URL', async () => {
  const item = legacy('https://8.8.8.8/?token=***', { replayStatus: 'AVAILABLE', replayDraft: JSON.stringify({ protocol: 'HTTP', request: { method: 'GET', url: 'https://8.8.8.8/?token=synthetic-real', auth: { type: 'NONE' } } }) })
  const q = await historySetup(item); button(q.w, '重发').vm.$emit('click'); await flushPromises(); expect(executed(q)).toHaveLength(1)
})
it('TC-P2R2-D13-06: P1 unavailable states remain disabled', async () => {
  for (const replayStatus of ['EXPIRED', 'UNKNOWN_CREATED_AT', 'UNREADABLE']) {
    const q = await historySetup(legacy('https://8.8.8.8', { replayStatus })); expect(button(q.w, '载入').props('disabled')).toBe(true); expect(button(q.w, '重发').props('disabled')).toBe(true); expect(executed(q)).toHaveLength(0)
  }
})
it('TC-P2R2-D13-07: A/B/A invalidates historical reconstruction approval', async () => {
  const q = await historySetup(legacy('https://8.8.8.8/legacy')); await loadLegacy(q); await review(q.w)
  q.session.projectId = 'p2'; q.session.projectId = 'p1'; await flushPromises(); button(q.w, '发送').vm.$emit('click'); await flushPromises(); expect(executed(q)).toHaveLength(0)
})
it('TC-P2R2-D13-08: new ordinary drafts keep the original API payload without a star blacklist', async () => {
  const q = await setup('HTTP', url => url.endsWith('/execute') ? Promise.resolve(response(result)) : undefined, { protocol: 'HTTP', request: { method: 'POST', url: 'https://8.8.8.8/direct?q=***', body: '***', headers: [], auth: { type: 'NONE' } } })
  button(q.w, '发送').vm.$emit('click'); await flushPromises(); expect(JSON.parse(String(executed(q)[0]![1]!.body))).toMatchObject({ body: '***' })
})
