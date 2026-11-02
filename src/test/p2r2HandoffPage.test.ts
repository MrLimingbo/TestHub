import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import ApiDebugPage from '@/pages/ApiDebugPage.vue'
import { useSessionStore } from '@/stores/session'
import { createApiDebugHandoff, type ApiDebugHandoffRequest } from '@/utils/apiDebugHandoff'
const wrappers: VueWrapper[] = []
afterEach(async () => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; await flushPromises(); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear() })
const CodeEditor = { name: 'QaCodeEditor', props: { modelValue: { type: String, default: '' } }, emits: ['update:modelValue'], template: '<textarea :value="modelValue" />' }
const Select = { name: 'QaSelect', props: { modelValue: { type: [String, Number, Array], default: '' } }, emits: ['update:modelValue', 'change'], template: '<div><slot /></div>' }
const request = (type: ApiDebugHandoffRequest['auth']['type'], placement: 'HEADER' | 'QUERY' = 'HEADER'): ApiDebugHandoffRequest => ({ method: 'POST', url: 'https://8.8.8.8/runtime', headers: [{ name: 'X-Run', value: 'synthetic-header' }], body: 'synthetic-body', environmentCode: null, auth: { type, token: '{{TOKEN}}', username: 'synthetic-user', password: 'synthetic-pass', key: 'X-API-Key', value: '{{KEY}}', placement } })
async function setup(runtime: ApiDebugHandoffRequest | null, invalidate = false, wrongSource = false) {
  for (const name of ['success', 'error', 'info', 'warning'] as const) vi.spyOn(Message, name).mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'; session.signedIn = true; session.profile = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: '2026-10-30T00:00:00Z' }
  const key = runtime ? createApiDebugHandoff(session, 'p1', 'source', runtime) : 'missing'
  if (invalidate) { session.projectId = 'p2'; session.projectId = 'p1' }
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/debug', component: { template: '<div />' } }] }); await router.push({ path: '/debug', query: { handoff: key, sourceApiId: wrongSource ? 'other' : 'source' } })
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => { void init; return new Response(JSON.stringify({ data: url.endsWith('/context') ? { organizations: [{ id: 'o', name: 'QA' }], projects: { o: [{ id: 'p1', name: 'QA', code: 'QA' }, { id: 'p2', name: 'QA2', code: 'QA2' }] } } : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : url.endsWith('/environments') ? [{ name: '默认', environmentCode: 'test', enabled: true, defaultEnvironment: true }] : url.endsWith('/parameters') ? { environments: ['test'], parameters: [] } : url.endsWith('/secrets') ? { environments: ['test'], secrets: [] } : url.endsWith('/execute') ? { historyId: 'run', success: true, statusCode: 200, durationMs: 1, body: '{}', responseHeaders: [], truncated: false } : [] })) })
  vi.stubGlobal('fetch', fetcher)
  const w = mount(ApiDebugPage, { global: { plugins: [pinia, ArcoVue, router], stubs: { teleport: true, CodeEditor, Select, ApiDebugSyncArchive: true }, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises(); return { w, fetcher, session }
}
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(call => (call[1] as RequestInit | undefined)?.method)
const button = (w: VueWrapper, label: string) => w.findAllComponents({ name: 'Button' }).find(b => b.text().trim() === label)!
for (const [type, placement] of [['NONE', 'HEADER'], ['BEARER', 'HEADER'], ['BASIC', 'HEADER'], ['API_KEY', 'HEADER'], ['API_KEY', 'QUERY']] as const) it(`TC-P2R2-D15-01/06: runtime ${type}/${placement} reaches manual execution unchanged`, async () => {
  const original = request(type, placement), q = await setup(original); expect(writes(q)).toHaveLength(0)
  button(q.w, '发送').vm.$emit('click'); await flushPromises(); const execute = q.fetcher.mock.calls.find(([url]) => url.endsWith('/execute'))!; const p = JSON.parse(String((execute[1] as RequestInit).body))
  expect(p.auth).toEqual(original.auth); expect(p.url).toBe(original.url); expect(p.body).toBe(original.body); expect(p.headers).toEqual(original.headers); expect(p.environmentCode).toBeNull()
})
it('TC-P2R2-D15-04: missing packet starts blank and warns instead of restoring another draft', async () => {
  const q = await setup(null); expect(writes(q)).toHaveLength(0); expect(Message.warning).toHaveBeenCalledWith(expect.stringContaining('交接已失效'))
})
it('TC-P2R2-D15-03: a scope round-trip or source-ID mismatch rejects the handoff', async () => {
  for (const [invalidate, wrongSource] of [[true, false], [false, true]]) { const q = await setup(request('BEARER'), invalidate, wrongSource); expect(writes(q)).toHaveLength(0); expect(Message.warning).toHaveBeenCalledWith(expect.stringContaining('交接已失效')) }
})
it('TC-P2R2-D15-03/06: a consumed runtime request cannot survive a project round-trip', async () => {
  const q = await setup(request('BEARER')); q.session.projectId = 'p2'; q.session.projectId = 'p1'; await flushPromises()
  expect(writes(q)).toHaveLength(0)
  button(q.w, '发送').vm.$emit('click'); await flushPromises()
  const execute = q.fetcher.mock.calls.find(([url]) => url.endsWith('/execute'))!
  const payload = JSON.parse(String((execute[1] as RequestInit).body))
  expect(payload.auth.type).toBe('NONE'); expect(payload.auth.token).toBe('')
  expect(payload.body).toBe(''); expect(payload.url).toBe(''); expect(payload.headers).not.toContainEqual({ name: 'X-Run', value: 'synthetic-header' })
  expect(q.w.findAllComponents({ name: 'QaCodeEditor' }).every(editor => editor.props('modelValue') !== 'synthetic-body')).toBe(true)
})
