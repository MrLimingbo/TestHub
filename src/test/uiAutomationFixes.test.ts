import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Message, Modal, Input } from '@arco-design/web-vue'
import UiAutomationPage from '@/pages/UiAutomationPage.vue'
import PageHeader from '@/components/PageHeader.vue'

const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); state.projectId = 'p1' })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: '受控请求失败' }), { status, headers: { 'Content-Type': 'application/json' } })
const deferred = () => { let resolve!: (value: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const profile = { id: 'p', name: '浏览器A', browser: 'chromium', baseUrl: 'http://localhost', viewportWidth: 800, viewportHeight: 600, enabled: true, version: 1 }
const object = { id: 'o', name: '对象A', selectorValue: '#name', description: '' }
const scenario = { id: 's', name: '场景A', browserProfileId: 'p', description: '', failureStrategy: 'STOP', variables: {}, steps: [{ id: 'nav', type: 'NAVIGATE', name: '导航', url: '/' }], version: 1 }
const schedule = { id: 't', name: '计划A', scenarioId: 's', cronExpression: '0 0 9 ? * MON-FRI', timezone: 'UTC', timeoutSeconds: 30, maxRetryAttempts: 0, retryBackoffSeconds: 5, enabled: false }
const execution = { id: 'e', scenarioId: 's', scenarioName: '场景A', status: 'QUEUED', requestedAt: '2026-10-27T00:00:00Z', retryAttempt: 0 }
type Kind = 'profile' | 'object' | 'scenario' | 'schedule' | 'execute'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const names = { profile: '配置名称', object: '名称', scenario: '场景名称', schedule: '计划名称' }
const titles = { profile: '浏览器项目', object: '页面对象', scenario: 'Web UI 场景', schedule: '定时计划', execute: '执行 Web UI 场景' }
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(c => kind === 'scenario' ? ['新建 Web UI 场景', '编辑 Web UI 场景'].includes(String(c.props('title'))) : String(c.props('title')).includes(titles[kind]))!
const field = (w: VueWrapper, kind: Exclude<Kind, 'execute'>) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === names[kind])!.findComponent(Input)
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('update:visible', false); modal(w, kind).vm.$emit('cancel'); await flushPromises() }
async function open(w: VueWrapper, kind: Kind, name = '草稿A', edit = false) {
  if (kind === 'scenario') {
    if (edit) w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('编辑编排'))!.vm.$emit('click')
    else w.findComponent(PageHeader).vm.$emit('action')
  } else if (kind === 'execute') w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('执行场景'))!.vm.$emit('click')
  else {
    const tab = kind === 'profile' ? '浏览器项目' : kind === 'object' ? '页面对象' : '定时执行'
    await w.findAll('.arco-tabs-tab').find(c => c.text().includes(tab))!.trigger('click'); await flushPromises()
    if (edit) w.findAllComponents({ name: 'Button' }).filter(c => c.text() === '编辑' && c.isVisible())[0]!.vm.$emit('click')
    else w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('新建' + (kind === 'schedule' ? '定时计划' : tab)))!.vm.$emit('click')
  }
  await flushPromises(); if (kind !== 'execute') { field(w, kind).vm.$emit('update:modelValue', name); await flushPromises() }
}
async function setup(initial: Hook = () => undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  let hook = initial
  const fetcher = vi.fn((url: string, init?: RequestInit) => hook(url, init) ?? Promise.resolve(response(
    url.endsWith('/context') ? { organizations: [{ id: 'org' }], projects: { org: ['p1', 'p2'].map(id => ({ id, organizationId: 'org', name: id, code: id })) } }
      : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' }
        : init?.method === 'POST' || init?.method === 'PUT' ? { ...scenario, id: 'saved' }
          : url.endsWith('/browser-profiles') ? [profile] : url.endsWith('/page-objects') ? [object]
            : url.endsWith('/scenarios') ? [scenario] : url.endsWith('/schedules') ? [schedule]
              : url.endsWith('/runner-status') ? [{ runnerId: 'qa', trusted: true, executionEnabled: true }] : [])))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(UiAutomationPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true, UiAutomationHistoryPanel: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, fetcher, hook: (value: Hook) => { hook = value } }
}
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? ''))
for (const kind of ['profile', 'object', 'scenario', 'schedule'] as const) {
  for (const [edit, status] of [[false, 200], [true, 200], [false, 503]] as const) it(`${kind}: late ${edit ? 'edit' : 'create'} ${status} preserves a replacement draft`, async () => {
    const q = await setup(), old = deferred(); q.hook((_url, init) => ['POST', 'PUT'].includes(init?.method ?? '') ? old.promise : undefined)
    await open(q.w, kind, '旧A', edit); const pending = save(q.w, kind); await flushPromises(); await cancel(q.w, kind); await open(q.w, kind, '新B')
    old.resolve(response({ ...scenario, id: 'saved' }, status)); expect(await pending).toBe(false); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe('新B'); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  for (const action of ['cancel', 'project-return', 'unmount'] as const) it(`${kind}: ${action} while CSRF is pending prevents an obsolete write`, async () => {
    const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
    await open(q.w, kind); const pending = save(q.w, kind); await flushPromises()
    if (action === 'cancel') { await cancel(q.w, kind); await open(q.w, kind, '新B') }
    else if (action === 'project-return') { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() }
    else q.w.unmount()
    csrf.resolve(response({ token: 'fixture', headerName: 'X-CSRF' })); await pending; await flushPromises()
    expect(writes(q)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  it(`${kind}: an old completion cannot clear the new save spinner`, async () => {
    const q = await setup(), old = deferred(), latest = deferred(); let calls = 0
    q.hook((_url, init) => init?.method === 'POST' ? (++calls === 1 ? old.promise : latest.promise) : undefined)
    await open(q.w, kind); const first = save(q.w, kind); await flushPromises(); await cancel(q.w, kind); await open(q.w, kind, '新B')
    const second = save(q.w, kind); await flushPromises(); old.resolve(response(scenario)); await first; await flushPromises()
    expect(modal(q.w, kind).props('okLoading')).toBe(true); expect(modal(q.w, kind).props('visible')).toBe(true)
    latest.resolve(response({ ...scenario, id: 'saved' })); await second; await flushPromises(); expect(modal(q.w, kind).props('visible')).toBe(false)
  })
  it(`${kind}: a current failure retains input and permits retry`, async () => {
    const q = await setup(); let fail = true
    q.hook((_url, init) => init?.method === 'POST' ? Promise.resolve(response({ ...scenario, id: 'saved' }, fail ? 503 : 200)) : undefined)
    await open(q.w, kind, '保留草稿'); await save(q.w, kind); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe('保留草稿'); expect(modal(q.w, kind).props('okLoading')).toBe(false); expect(Message.error).toHaveBeenCalledOnce()
    fail = false; await save(q.w, kind); await flushPromises(); expect(modal(q.w, kind).props('visible')).toBe(false)
  })
}
for (const action of ['cancel', 'project-return', 'unmount'] as const) it(`execute: ${action} during CSRF cannot queue a task`, async () => {
  const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await open(q.w, 'execute'); const pending = save(q.w, 'execute'); await flushPromises()
  if (action === 'cancel') await cancel(q.w, 'execute'); else if (action === 'project-return') { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() } else q.w.unmount()
  csrf.resolve(response({ token: 'fixture', headerName: 'X-CSRF' })); await pending; await flushPromises()
  expect(writes(q)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
})
for (const status of [200, 503]) it(`execute: a late ${status} after cancellation has no toast or follow-up`, async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(q.w, 'execute'); const pending = save(q.w, 'execute'); await flushPromises(); await cancel(q.w, 'execute')
  const before = q.fetcher.mock.calls.length; old.resolve(response(execution, status)); await pending; await flushPromises()
  expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled(); expect(modal(q.w, 'execute').props('visible')).toBe(false)
})
for (const status of [200, 503]) it(`initial context ${status} after unmount cannot reload assets or show errors`, async () => {
  const old = deferred(), q = await setup(url => url.endsWith('/context') ? old.promise : undefined); q.w.unmount(); const before = q.fetcher.mock.calls.length
  old.resolve(response({ organizations: [], projects: {} }, status)); await flushPromises()
  expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0); expect(Message.error).not.toHaveBeenCalled()
})
for (const action of ['unmount', 'project-return'] as const) it(`initial assets after ${action} cannot revive the old page`, async () => {
  const old = deferred(), q = await setup(url => url.endsWith('/browser-profiles') ? old.promise : undefined)
  if (action === 'unmount') q.w.unmount(); else { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() }
  const before = q.fetcher.mock.calls.length; old.resolve(response([profile])); await flushPromises(); expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0); expect(Message.error).not.toHaveBeenCalled()
})
it('duplicate object save callbacks only send one write', async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(q.w, 'object'); const first = save(q.w, 'object'), second = save(q.w, 'object'); await flushPromises()
  expect(writes(q)).toHaveLength(1); old.resolve(response(object)); await Promise.all([first, second])
})
it('object save captures the submitted form before awaiting CSRF', async () => {
  const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await open(q.w, 'object', '提交A'); const pending = save(q.w, 'object'); await flushPromises(); field(q.w, 'object').vm.$emit('update:modelValue', '后来输入B')
  csrf.resolve(response({ token: 'fixture', headerName: 'X-CSRF' })); await pending
  expect(JSON.parse(String(writes(q)[0]![1]?.body)).name).toBe('提交A')
})
