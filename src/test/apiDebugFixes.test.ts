import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import ApiDebugPage from '@/pages/ApiDebugPage.vue'
import { useSessionStore } from '@/stores/session'
import { buildApiRunUrl, createApiRunSnapshot, requestHeaders } from '@/utils/apiTesting'

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
                      : url.endsWith('/close') ? { ...wsSession, status: 'CLOSED' }
                        : url.endsWith('/connect') || url.endsWith('/sessions/ws-a') ? wsSession
                        : url.endsWith('/curl/import') ? { importedCount: 1, items: ['已保存'] } : [])))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/debug', component: { template: '<div />' } }] })
  await router.push({ path: '/debug', query: replay ? { replayDraft: JSON.stringify(replay) } : { protocol, url: protocol === 'GRPC' ? '8.8.8.8:443' : protocol === 'WEBSOCKET' ? 'wss://8.8.8.8/echo' : 'https://8.8.8.8/echo' } })
  const w = mount(ApiDebugPage, { global: { plugins: [pinia, ArcoVue, router], stubs: { teleport: true, CodeEditor, Select, ApiDebugSyncArchive: true, Dropdown: { template: '<div><slot /><slot name="content" /></div>' } }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, session, fetcher }
}
const button = (w: VueWrapper, label: string) => w.findAllComponents({ name: 'Button' }).find(item => item.text().trim() === label)!
const saveModal = (w: VueWrapper) => w.findAllComponents({ name: 'Modal' }).find(item => item.props('title') === '保存为正式接口')!
const nameField = (w: VueWrapper) => saveModal(w).findAllComponents({ name: 'FormItem' }).find(item => item.props('label') === '接口名称')!.findComponent({ name: 'Input' })
const save = (w: VueWrapper) => (saveModal(w).props('onBeforeOk') as () => Promise<boolean>)()
async function openSave(w: VueWrapper, name = '草稿B') { button(w, '保存为接口').vm.$emit('click'); await flushPromises(); nameField(w).vm.$emit('update:modelValue', name); await flushPromises() }
async function cancelSave(w: VueWrapper) { saveModal(w).vm.$emit('update:visible', false); await flushPromises() }

const httpResult = (id: string) => ({ historyId: id, success: true, statusCode: 200, statusText: 'OK', durationMs: 3, responseHeaders: [], body: id, truncated: false, errorMessage: null })
it('[P1-U12-05][POS] replay disclosure names encrypted fields, permissions, retention and legacy exceptions', async () => {
  const { w } = await setup(); expect(w.text()).toContain('30天'); expect(w.text()).toContain('项目写权限'); expect(w.text()).toContain('旧历史密文')
  expect(w.text()).not.toContain('仅保存脱敏摘要')
})
it('[P1-U12-06][NEG] expired history is explicitly labeled and cannot be loaded or resent', async () => {
  const { w } = await setup('HTTP', url => url.includes('/api-debug/history') ? Promise.resolve(response([{ id: 'expired', projectId: 'p1', method: 'GET', protocol: 'HTTP', requestUrl: 'https://example.test', responseStatus: 200, durationMs: 1, responseSize: 0, result: 'COMPLETED', errorMessage: null, replayDraft: null, replayStatus: 'EXPIRED', createdAt: '2026-09-01T00:00:00Z' }])) : undefined)
  expect(w.get('.history-item').text()).toContain('回放已到期'); expect(button(w, '载入').props('disabled')).toBe(true); expect(button(w, '重发').props('disabled')).toBe(true)
})
it('[DF-D01][POS][OBS-07-E006] current HTTP completion identifies its project and execution', async () => {
  const { w, fetcher } = await setup('HTTP', url => url.endsWith('/execute') ? Promise.resolve(response(httpResult('run-A'))) : undefined)
  button(w, '发送').vm.$emit('click'); await flushPromises()
  expect(Message.success).toHaveBeenCalledWith(expect.stringContaining('p1'))
  expect(Message.success).toHaveBeenCalledWith(expect.stringContaining('run-A'))
  expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/execute'))).toHaveLength(1)
})
for (const status of [200, 503]) it(`[DF-D0${status === 200 ? 2 : 3}][NEG][OBS-07-E006] old HTTP ${status} is attributed to its origin without altering a newer response`, async () => {
  const old = deferred(); let count = 0
  const { w, session, fetcher } = await setup('HTTP', url => url.endsWith('/execute') ? ++count === 1 ? old.promise : Promise.resolve(response(httpResult('run-B'))) : undefined)
  button(w, '发送').vm.$emit('click'); await flushPromises(); session.selectProject('p2'); await flushPromises()
  button(w, '发送').vm.$emit('click'); await flushPromises(); vi.mocked(Message.success).mockClear()
  old.resolve(response(status === 200 ? httpResult('run-A') : 'A失败', status)); await flushPromises()
  expect(w.get('.response-code').text()).toBe('run-B')
  const feedback = status === 200 ? Message.success : Message.error
  expect(feedback).toHaveBeenCalledWith(expect.stringContaining('p1'))
  if (status === 200) expect(feedback).toHaveBeenCalledWith(expect.stringContaining('run-A'))
  expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/execute'))).toHaveLength(2)
})
it('[DF-D04][NEG][OBS-07-E006] an A/B/A round trip does not let the original completion stop a new send', async () => {
  const old = deferred(), latest = deferred(); let count = 0
  const { w, session } = await setup('HTTP', url => url.endsWith('/execute') ? ++count === 1 ? old.promise : latest.promise : undefined)
  button(w, '发送').vm.$emit('click'); await flushPromises(); session.selectProject('p2'); session.selectProject('p1'); await flushPromises()
  button(w, '发送').vm.$emit('click'); await flushPromises(); old.resolve(response(httpResult('run-old'))); await flushPromises()
  expect(button(w, '发送').props('loading')).toBe(true)
  latest.resolve(response(httpResult('run-current'))); await flushPromises(); expect(button(w, '发送').props('loading')).toBe(false)
})
it('[DF-D05][NEG][OBS-07-E006] an unmounted HTTP completion preserves the existing notification with its original scope', async () => {
  const old = deferred(), { w, fetcher } = await setup('HTTP', url => url.endsWith('/execute') ? old.promise : undefined)
  button(w, '发送').vm.$emit('click'); await flushPromises(); w.unmount(); const before = fetcher.mock.calls.length
  old.resolve(response(httpResult('run-old'))); await flushPromises()
  expect(Message.success).toHaveBeenCalledWith(expect.stringContaining('p1'))
  expect(Message.success).toHaveBeenCalledWith(expect.stringContaining('run-old')); expect(Message.error).not.toHaveBeenCalled()
  expect(fetcher.mock.calls).toHaveLength(before)
})

for (const status of [200, 503]) it(`late save ${status} preserves replacement dialog and name`, async () => {
  const old = deferred(); const { w } = await setup('HTTP', url => url.endsWith('/curl/import') ? old.promise : undefined)
  await openSave(w, '草稿A'); const pending = save(w); await flushPromises(); await cancelSave(w); await openSave(w)
  old.resolve(response(status === 200 ? { items: ['旧接口A'] } : '旧错误', status)); expect(await pending).toBe(false); await flushPromises()
  expect(saveModal(w).props('visible')).toBe(true); expect(nameField(w).props('modelValue')).toBe('草稿B')
  expect(Message.error).not.toHaveBeenCalled(); expect(Message.success).not.toHaveBeenCalled(); expect(saveModal(w).props('okLoading')).toBe(false)
})
it('late save cannot stop a newer save spinner', async () => {
  const old = deferred(), current = deferred(); let count = 0
  const { w } = await setup('HTTP', url => url.endsWith('/curl/import') ? (++count === 1 ? old.promise : current.promise) : undefined)
  await openSave(w, '草稿A'); const first = save(w); await flushPromises(); await cancelSave(w); await openSave(w); const second = save(w); await flushPromises()
  old.resolve(response({ items: ['A'] })); expect(await first).toBe(false); expect(saveModal(w).props('okLoading')).toBe(true)
  current.resolve(response({ items: ['B'] })); expect(await second).toBe(true); expect(saveModal(w).props('visible')).toBe(false)
})
for (const action of ['cancel', 'unmount', 'project']) it(`save ${action} during CSRF prevents stale POST`, async () => {
  const csrf = deferred(); const { w, session, fetcher } = await setup('HTTP', url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await openSave(w); const pending = save(w); await flushPromises()
  if (action === 'cancel') await cancelSave(w); else if (action === 'unmount') w.unmount(); else session.projectId = 'p2'
  await flushPromises(); csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); expect(await pending).toBe(false)
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
it('current save failure releases spinner and supports retry', async () => {
  let fail = true
  const { w } = await setup('HTTP', url => url.endsWith('/curl/import') ? Promise.resolve(response(fail ? '受控错误' : { items: ['重试'] }, fail ? 503 : 200)) : undefined)
  await openSave(w); expect(await save(w)).toBe(false); expect(saveModal(w).props('visible')).toBe(true); expect(saveModal(w).props('okLoading')).toBe(false)
  fail = false; expect(await save(w)).toBe(true)
})

for (const status of [200, 503]) it(`late reflection ${status} cannot overwrite newer offline discovery`, async () => {
  const old = deferred(); const { w } = await setup('GRPC', (url, init) => url.endsWith('/grpc/discovery') && init?.method === 'POST' ? old.promise : undefined)
  button(w, '发现服务').vm.$emit('click'); await flushPromises()
  const descriptor = w.findAllComponents({ name: 'QaSelect' }).find(item => item.props('placeholder') === '在线反射（默认）')!
  descriptor.vm.$emit('update:modelValue', 'offline-b'); descriptor.vm.$emit('change', 'offline-b'); await flushPromises()
  await w.get('.discovery-select').trigger('click'); await flushPromises(); expect(w.text()).toContain('qa.Other')
  old.resolve(response(status === 200 ? discovery('qa.Old') : '旧反射错误', status)); await flushPromises()
  expect(w.text()).toContain('qa.Other')
  expect(w.text()).not.toContain('qa.Old')
  expect(Message.error).not.toHaveBeenCalled()
})
for (const action of ['project', 'unmount']) it(`late WS connection after ${action} closes its owned session without reading or reporting connected`, async () => {
  const old = deferred(); const { w, session, fetcher } = await setup('WEBSOCKET', url => url.endsWith('/connect') ? old.promise : undefined)
  button(w, '连接').vm.$emit('click'); await flushPromises()
  if (action === 'project') session.projectId = 'p2'; else w.unmount()
  await flushPromises(); old.resolve(response(wsSession)); await flushPromises()
  expect(fetcher.mock.calls.some(([url, init]) => url.includes('/websocket/sessions/') && (!init?.method || init.method === 'GET'))).toBe(false)
  expect(fetcher.mock.calls.filter(([url, init]) => url.endsWith('/websocket/sessions/ws-a/close') && init?.method === 'POST')).toHaveLength(1)
  expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
})
it('failed initial WS session read does not report connected', async () => {
  const { w } = await setup('WEBSOCKET', url => url.endsWith('/sessions/ws-a') ? Promise.resolve(response('会话不可读', 404)) : undefined)
  button(w, '连接').vm.$emit('click'); await flushPromises()
  expect(Message.error).toHaveBeenCalledWith('会话不可读'); expect(Message.success).not.toHaveBeenCalled()
})
it('restored qa environment immediately filters parameters and secret labels', async () => {
  const { w } = await setup('HTTP', undefined, { protocol: 'HTTP', request: { url: 'https://8.8.8.8/echo', method: 'GET', environmentCode: 'qa' } })
  expect(w.text()).toContain('变量 2'); expect(w.text()).toContain('密钥 1')
  const protocol = w.findAllComponents({ name: 'QaSelect' }).find(item => item.props('modelValue') === 'HTTP')!; protocol.vm.$emit('update:modelValue', 'GRAPHQL'); protocol.vm.$emit('change', 'GRAPHQL'); await flushPromises(); const tabs = w.findAllComponents({ name: 'Tabs' })[0]; tabs.vm.$emit('update:activeKey', 'variables'); await flushPromises()
  const table = w.findComponent({ name: 'Table' })
  expect(table.props('data').map((item: { key: string }) => item.key)).toEqual(['QA_VALUE', 'QA_SECRET'])
})
it('gRPC replay restores metadata key/value and can resend unchanged', async () => {
  const { w, fetcher } = await setup('GRPC', undefined, { protocol: 'GRPC', request: { endpoint: '8.8.8.8:443', tls: true, service: 'qa.Echo', method: 'Unary', requestJson: '{}', metadata: [{ key: 'x-qa', value: 'trace' }] } })
  button(w, '调用').vm.$emit('click'); await flushPromises()
  const post = fetcher.mock.calls.find(([url]) => url.endsWith('/grpc/unary'))
  expect(post).toBeDefined(); expect(JSON.parse(post![1]!.body as string).metadata).toEqual([{ key: 'x-qa', value: 'trace' }])
})

for (const field of ['query', 'header'] as const) for (const value of ['', '  ']) it(`required ${field} rejects empty ${JSON.stringify(value)} before building request`, () => {
  const snapshot = createApiRunSnapshot({ requestHeaders: '[{"name":"X-Required","required":true,"example":"ok"}]', requestQuery: '[{"name":"requiredQuery","required":true,"example":"ok"}]', requestPath: '[]', requestBody: '', responseBody: '', exampleResponse: '' })
  const values = field === 'query' ? snapshot.queryValues : snapshot.headerValues; values[0].value = value
  expect(() => field === 'query' ? buildApiRunUrl({ method: 'GET', path: '/echo' }, snapshot) : requestHeaders(snapshot)).toThrow(field === 'query' ? 'requiredQuery' : 'X-Required')
})
it('optional empty parameters remain omitted and required populated values remain encoded', () => {
  const snapshot = createApiRunSnapshot({ requestHeaders: '[]', requestQuery: '[]', requestPath: '[]', requestBody: '', responseBody: '', exampleResponse: '' }, 'https://8.8.8.8')
  snapshot.queryValues = [{ name: 'required', value: '中文 空', required: true, enabled: true, type: 'string', example: '', description: '' }, { name: 'optional', value: '', required: false, enabled: true, type: 'string', example: '', description: '' }]
  snapshot.headerValues = [{ name: 'X-Required', value: 'ok', required: true, enabled: true, type: 'string', example: '', description: '' }, { name: 'X-Optional', value: '', required: false, enabled: true, type: 'string', example: '', description: '' }]
  expect(buildApiRunUrl({ method: 'GET', path: '/echo' }, snapshot)).toBe('https://8.8.8.8/echo?required=%E4%B8%AD%E6%96%87%20%E7%A9%BA')
  expect(requestHeaders(snapshot)).toEqual([{ name: 'X-Required', value: 'ok' }])
})
