import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Message, Modal, Input, Textarea } from '@arco-design/web-vue'
import PerformancePage from '@/pages/PerformancePage.vue'
import PageHeader from '@/components/PageHeader.vue'

const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); state.projectId = 'p1' })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: '受控请求失败' }), { status, headers: { 'Content-Type': 'application/json' } })
const deferred = () => { let resolve!: (value: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const plan = (id = 'a') => ({ id, name: id, description: '', status: 'READY', version: 1, threadGroupCount: 1, threadGroups: [{ id: 'group', name: '线程组', targetUrl: 'http://localhost/echo', loadModel: 'STEADY', threadCount: 1, rampUpSeconds: 0, durationSeconds: 10, requestMethod: 'POST', requestHeaders: {}, queryParameters: {}, requestBody: null, sortOrder: 0 }] })
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const modal = (w: VueWrapper) => w.findComponent(Modal)
const nameField = (w: VueWrapper) => modal(w).findAllComponents(Input).find(c => c.props('placeholder') === '例如：订单核心链路基准压测')!
const save = (w: VueWrapper) => (modal(w).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper) { modal(w).vm.$emit('update:visible', false); await flushPromises() }
async function open(w: VueWrapper, edit = false, name = '草稿A') {
  if (edit) w.findAllComponents({ name: 'Button' }).find(c => c.text() === '编辑配置')!.vm.$emit('click')
  else w.findComponent(PageHeader).vm.$emit('action')
  await flushPromises(); nameField(w).vm.$emit('update:modelValue', name); await flushPromises()
}
async function setup(initialHook: Hook = () => undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  let hook = initialHook
  const fetcher = vi.fn((url: string, init?: RequestInit) => hook(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
      : url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: ['p1', 'p2'].map(id => ({ id, organizationId: 'o1', name: id, code: id })) } }
        : /\/plans$/.test(url) ? (init?.method === 'POST' ? plan('saved') : ['c', 'a', 'b'].map(plan))
          : /\/plans\/[abc]$/.test(url) ? plan(url.slice(-1)) : [])))
  vi.stubGlobal('fetch', fetcher)
  const w = shallowMount(PerformancePage, { global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises()
  return { w, fetcher, hook: (value: Hook) => { hook = value } }
}
async function select(w: VueWrapper, id: string) { await w.findAll('.plan-item').find(c => c.find('strong').text() === id)!.trigger('click'); await flushPromises() }
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? ''))

for (const edit of [false, true]) {
  for (const status of [200, 503]) it(`${edit ? 'edit' : 'create'}: late ${status} leaves replacement draft intact`, async () => {
    const q = await setup(), old = deferred(); q.hook((_url, init) => ['POST', 'PUT'].includes(init?.method ?? '') ? old.promise : undefined)
    await open(q.w, edit); const pending = save(q.w); await flushPromises(); await cancel(q.w); await open(q.w, false, '草稿B')
    old.resolve(response(plan('saved'), status)); expect(await pending).toBe(false); await flushPromises()
    expect(modal(q.w).props('visible')).toBe(true); expect(nameField(q.w).props('modelValue')).toBe('草稿B')
    expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  for (const action of ['cancel', 'project-return', 'unmount'] as const) it(`${edit ? 'edit' : 'create'}: ${action} during CSRF prevents obsolete write`, async () => {
    const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
    await open(q.w, edit); const pending = save(q.w); await flushPromises()
    if (action === 'cancel') { await cancel(q.w); await open(q.w, false, '草稿B') }
    else if (action === 'project-return') { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() }
    else q.w.unmount()
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); await pending; await flushPromises()
    expect(writes(q)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
}

it('an old save cannot clear a newer save spinner or close its editor', async () => {
  const q = await setup(), old = deferred(), latest = deferred(); let calls = 0
  q.hook((_url, init) => init?.method === 'POST' ? (++calls === 1 ? old.promise : latest.promise) : undefined)
  await open(q.w); const first = save(q.w); await flushPromises(); await cancel(q.w); await open(q.w, false, '草稿B')
  const second = save(q.w); await flushPromises(); old.resolve(response(plan('old'))); await first; await flushPromises()
  expect(modal(q.w).props('okLoading')).toBe(true); expect(modal(q.w).props('visible')).toBe(true)
  latest.resolve(response(plan('saved'))); await second; await flushPromises()
  expect(modal(q.w).props('visible')).toBe(false); expect(modal(q.w).props('okLoading')).toBe(false)
  expect(Message.success).toHaveBeenCalledOnce()
})
it('duplicate save callbacks only submit once', async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(q.w); const first = save(q.w), second = save(q.w); await flushPromises()
  expect(writes(q)).toHaveLength(1); old.resolve(response(plan('saved'))); await Promise.all([first, second])
})
it('editing keeps the original plan id and version after a later selection', async () => {
  const q = await setup(); await select(q.w, 'a'); await open(q.w, true); await select(q.w, 'b'); await save(q.w)
  const [url, init] = writes(q)[0]!; expect(url).toBe('/api/v1/projects/p1/performance/plans/a')
  expect(JSON.parse(String(init?.body)).expectedVersion).toBe(1)
  expect(Message.success).toHaveBeenCalledWith('压测计划已更新')
})
it('a current save failure preserves inputs and permits a successful retry', async () => {
  const q = await setup(); let fail = true
  q.hook((_url, init) => init?.method === 'POST' ? Promise.resolve(response(plan('saved'), fail ? 503 : 200)) : undefined)
  await open(q.w); await save(q.w); await flushPromises()
  expect(modal(q.w).props('visible')).toBe(true); expect(modal(q.w).props('okLoading')).toBe(false)
  expect(nameField(q.w).props('modelValue')).toBe('草稿A'); expect(Message.error).toHaveBeenCalledOnce()
  fail = false; await save(q.w); await flushPromises(); expect(modal(q.w).props('visible')).toBe(false)
})
for (const action of ['project-return', 'unmount'] as const) it(`save completion after ${action} cannot start follow-up requests`, async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(q.w); const pending = save(q.w); await flushPromises()
  if (action === 'unmount') q.w.unmount(); else { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() }
  const before = q.fetcher.mock.calls.length; old.resolve(response(plan('saved'))); await pending; await flushPromises()
  expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled()
})
it('unmounting during the initial list request prevents detail and operational follow-up requests', async () => {
  const old = deferred(), q = await setup(url => url.endsWith('/plans') ? old.promise : undefined)
  q.w.unmount(); const before = q.fetcher.mock.calls.length; old.resolve(response([plan()])); await flushPromises()
  expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0)
})
it('a delete completion preserves a later selected plan', async () => {
  const q = await setup(), old = deferred(); await select(q.w, 'a')
  q.hook((url, init) => init?.method === 'DELETE' ? old.promise : url.endsWith('/plans') ? Promise.resolve(response([plan('c'), plan('b')])) : undefined)
  q.w.findAllComponents({ name: 'Popconfirm' }).find(c => c.props('content') === '删除后线程组配置将不可恢复，确认删除吗？')!.vm.$emit('ok'); await flushPromises()
  await select(q.w, 'b'); old.resolve(response(null)); await flushPromises()
  expect(q.w.find('.plan-header h2').text()).toBe('b')
})
for (const status of [404, 503]) it(`a stale detail ${status} cannot show an error over the new selection`, async () => {
  const q = await setup(), old = deferred(); q.hook(url => url.endsWith('/plans/a') ? old.promise : undefined)
  await select(q.w, 'a'); await select(q.w, 'b'); old.resolve(response(null, status)); await flushPromises()
  expect(q.w.find('.plan-header h2').text()).toBe('b'); expect(Message.error).not.toHaveBeenCalled()
})
it('detail A-B-A rejects the first A failure while keeping the latest A', async () => {
  const q = await setup(), old = deferred(); let calls = 0
  q.hook(url => url.endsWith('/plans/a') && ++calls === 1 ? old.promise : undefined)
  await select(q.w, 'a'); await select(q.w, 'b'); await select(q.w, 'a'); old.resolve(response(null, 503)); await flushPromises()
  expect(q.w.find('.plan-header h2').text()).toBe('a'); expect(Message.error).not.toHaveBeenCalled()
})
it('a detail failure after unmount produces no global error', async () => {
  const q = await setup(), old = deferred(); q.hook(url => url.endsWith('/plans/a') ? old.promise : undefined)
  await select(q.w, 'a'); q.w.unmount(); old.resolve(response(null, 503)); await flushPromises(); expect(Message.error).not.toHaveBeenCalled()
})
it('a current detail failure is shown and reselecting allows retry', async () => {
  const q = await setup(); let fail = true
  q.hook(url => url.endsWith('/plans/a') ? Promise.resolve(response(plan(), fail ? 503 : 200)) : undefined)
  await select(q.w, 'a'); expect(Message.error).toHaveBeenCalledWith('受控请求失败')
  fail = false; await select(q.w, 'a'); expect(q.w.find('.plan-header h2').text()).toBe('a')
})
for (const body of [' \nQA body \n', '', ' \n ']) it(`request body ${JSON.stringify(body)} keeps its raw-content contract`, async () => {
  const q = await setup(); await open(q.w, true)
  modal(q.w).findAllComponents(Textarea).find(c => String(c.props('placeholder')).includes('可使用原始请求正文'))!.vm.$emit('update:modelValue', body)
  await flushPromises(); await save(q.w)
  expect(JSON.parse(String(writes(q)[0]![1]?.body)).plan.threadGroups[0].requestBody).toBe(body.trim() ? body : undefined)
})
