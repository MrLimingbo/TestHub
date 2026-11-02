import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, Message, Modal, Popconfirm } from '@arco-design/web-vue'
import ReportsPage from '@/pages/ReportsPage.vue'
import TasksPage from '@/pages/TasksPage.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'

type Kind = 'report' | 'task'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(status === 204 ? undefined : JSON.stringify(status >= 400 ? { message: 'QA请求失败' } : { data }), { status })
function deferred() { let resolve!: (value: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const schedule = { id: 's1', name: 'QA周报', projectIds: ['p1'], cronExpression: '0 0 9 ? * MON', timezone: 'Asia/Shanghai', periodDays: 7, enabled: true, version: 0, lastRunAt: null, lastRunStatus: null }
const task = { id: 't1', projectId: 'p1', organizationId: 'o1', taskType: 'API', name: 'QA任务', status: 'QUEUED', progress: 0, version: 1, requestedBy: 'qa', createdAt: '2026-10-27T00:00:00Z', updatedAt: '2026-10-27T00:00:00Z', sourceTaskId: null }
const summary = { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }
const csrf = { token: 'fixture', headerName: 'X-CSRF' }
const button = (w: VueWrapper, name: string) => w.findAllComponents(Button).find(b => b.text() === name)!
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(m => String(m.props('title')).includes(kind === 'report' ? '定时质量报告' : '执行任务'))!
const field = (w: VueWrapper, kind: Kind) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === (kind === 'report' ? '计划名称' : '任务名称'))!.findComponent(Input)
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function close(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('update:visible', false); modal(w, kind).vm.$emit('cancel'); await flushPromises() }
async function open(w: VueWrapper, kind: Kind, name = '草稿A', edit = false) {
  if (edit) button(w, '编辑').vm.$emit('click')
  else if (kind === 'report') button(w, '新建计划').vm.$emit('click')
  else w.findComponent(PageHeader).vm.$emit('action')
  await flushPromises(); field(w, kind).vm.$emit('update:modelValue', name); await flushPromises()
}
async function setup(kind: Kind) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  let hook: Hook = () => undefined
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    const custom = hook(url, init)
    if (custom) return custom
    const data = url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: ['p1', 'p2'].map(id => ({ id, name: id, code: id, organizationId: 'o1' })) } }
      : url.endsWith('/csrf') ? csrf
        : init?.method ? url.endsWith('/run') ? { id: 'r1', status: 'SUCCEEDED', deliveryCount: 0 } : kind === 'report' ? schedule : task
          : url.endsWith('/schedules') ? [schedule]
            : url.endsWith('/tasks') ? url.includes('/p1/') ? [task] : []
              : { from: '2026-10-01', to: '2026-10-27', projectCount: 0, summary, trend: [], projects: [] }
    return Promise.resolve(response(data))
  })
  vi.stubGlobal('fetch', fetcher)
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  const w = mount(kind === 'report' ? ReportsPage : TasksPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, Popconfirm: true, DashboardTrendChart: true, UnifiedExecutionsPanel: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises()
  return { w, session, fetcher, hook: (value: Hook) => { hook = value } }
}
type Fixture = Awaited<ReturnType<typeof setup>>
const writes = (q: Fixture) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT', 'DELETE'].includes(init?.method ?? ''))

for (const kind of ['report', 'task'] as const) for (const edit of [false, true]) {
  for (const action of ['cancel', 'unmount'] as const) it(`${kind} ${edit ? 'edit' : 'create'}: ${action} during CSRF prevents an obsolete write`, async () => {
    const q = await setup(kind), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
    await open(q.w, kind, '旧草稿A', edit); const pending = save(q.w, kind); await flushPromises()
    if (action === 'cancel') { await close(q.w, kind); await open(q.w, kind, '新草稿B') } else q.w.unmount()
    old.resolve(response(csrf)); expect(await pending).toBe(false); await flushPromises()
    expect(writes(q)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
    if (action === 'cancel') { expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe('新草稿B') }
  })
}
for (const edit of [false, true]) it(`task ${edit ? 'edit' : 'create'}: project switch and return during CSRF invalidates the old form`, async () => {
  const q = await setup('task'), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
  await open(q.w, 'task', '旧草稿A', edit); const pending = save(q.w, 'task'); await flushPromises()
  q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises()
  old.resolve(response(csrf)); expect(await pending).toBe(false); await flushPromises()
  expect(writes(q)).toHaveLength(0); expect(modal(q.w, 'task').props('visible')).toBe(false); expect(Message.error).not.toHaveBeenCalled()
})
for (const edit of [false, true]) for (const action of ['reopen', 'unmount'] as const) it(`report ${edit ? 'edit' : 'create'}: ${action} during the post-save reload cannot receive an old completion`, async () => {
  const q = await setup('report'), old = deferred(); q.hook((url, init) => url.endsWith('/schedules') && !init?.method ? old.promise : undefined)
  await open(q.w, 'report', '旧草稿A', edit); const pending = save(q.w, 'report'); await flushPromises()
  expect(writes(q)).toHaveLength(1); expect(modal(q.w, 'report').props('visible')).toBe(false)
  if (action === 'reopen') await open(q.w, 'report', '新草稿B'); else q.w.unmount()
  old.resolve(response([schedule])); expect(await pending).toBe(false); await flushPromises()
  expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  if (action === 'reopen') { expect(modal(q.w, 'report').props('visible')).toBe(true); expect(field(q.w, 'report').props('modelValue')).toBe('新草稿B') }
})

function act(q: Fixture, action: 'run' | 'delete') {
  if (action === 'run') button(q.w, '立即运行').vm.$emit('click')
  else q.w.findComponent(Popconfirm).vm.$emit('ok')
}
for (const action of ['run', 'delete'] as const) {
  it(`report ${action}: unmount during CSRF cannot send an obsolete operation`, async () => {
    const q = await setup('report'), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
    act(q, action); await flushPromises(); q.w.unmount(); old.resolve(response(csrf)); await flushPromises()
    expect(writes(q)).toHaveLength(0); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  for (const phase of ['business', 'reload'] as const) for (const status of [200, 503]) it(`report ${action}: ${phase} ${status} after unmount has no follow-up request or feedback`, async () => {
    const q = await setup('report'), old = deferred()
    q.hook((url, init) => phase === 'business' ? init?.method === (action === 'run' ? 'POST' : 'DELETE') ? old.promise : undefined : url.endsWith('/schedules') && !init?.method ? old.promise : undefined)
    act(q, action); await flushPromises(); expect(writes(q)).toHaveLength(1); q.w.unmount(); const before = q.fetcher.mock.calls.length
    old.resolve(response(phase === 'reload' ? [schedule] : { id: 'r1', status: 'SUCCEEDED', deliveryCount: 0 }, status)); await flushPromises()
    expect(q.fetcher.mock.calls).toHaveLength(before); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  for (const status of [200, 503]) it(`report ${action}: a current ${status} still gives feedback`, async () => {
    const q = await setup('report'); q.hook((_url, init) => init?.method === (action === 'run' ? 'POST' : 'DELETE') ? Promise.resolve(response({ id: 'r1', status: 'SUCCEEDED', deliveryCount: 0 }, status)) : undefined)
    act(q, action); await flushPromises()
    expect(status === 200 ? Message.success : Message.error).toHaveBeenCalledOnce()
  })
}
for (const status of [200, 503]) it(`CSV ${status} after unmount neither downloads nor displays a message`, async () => {
  vi.spyOn(URL, 'createObjectURL'); vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const q = await setup('report'), old = deferred(); q.hook(url => url.includes('/quality/export?') ? old.promise : undefined)
  q.w.findComponent(PageHeader).vm.$emit('action'); await flushPromises(); q.w.unmount()
  old.resolve(status === 200 ? new Response('QA CSV') : response({}, status)); await flushPromises()
  expect(URL.createObjectURL).not.toHaveBeenCalled(); expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled(); expect(Message.success).not.toHaveBeenCalled()
})
it('a current CSV failure is still visible', async () => {
  const q = await setup('report'); q.hook(url => url.includes('/quality/export?') ? Promise.resolve(response({}, 503)) : undefined)
  q.w.findComponent(PageHeader).vm.$emit('action'); await flushPromises(); expect(Message.error).toHaveBeenCalledWith('QA请求失败')
})
for (const kind of ['report', 'task'] as const) {
  it(`${kind}: a current save failure retains input and permits retry`, async () => {
    const q = await setup(kind); let fail = true
    q.hook((_url, init) => init?.method === 'POST' ? Promise.resolve(response({}, fail ? 503 : 200)) : undefined)
    await open(q.w, kind, '保留草稿'); expect(await save(q.w, kind)).toBe(false); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe('保留草稿'); expect(Message.error).toHaveBeenCalledOnce()
    fail = false; expect(await save(q.w, kind)).toBe(true); await flushPromises(); expect(modal(q.w, kind).props('visible')).toBe(false)
  })
  it(`${kind}: save captures the submitted input and rejects duplicate pending callbacks`, async () => {
    const q = await setup(kind), old = deferred(); q.hook(url => url.endsWith('/csrf') ? old.promise : undefined)
    await open(q.w, kind, '提交A'); const pending = save(q.w, kind); expect(await save(q.w, kind)).toBe(false); await flushPromises()
    field(q.w, kind).vm.$emit('update:modelValue', '后来输入B'); old.resolve(response(csrf)); expect(await pending).toBe(true)
    expect(writes(q)).toHaveLength(1); expect(JSON.parse(String(writes(q)[0]![1]?.body)).name).toBe('提交A')
  })
}
