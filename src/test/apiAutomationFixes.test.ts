import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import AutomationPage from '@/pages/AutomationPage.vue'
import AutomationSidebar from '@/components/api-automation/AutomationSidebar.vue'
import AutomationRunPanel from '@/components/api-automation/AutomationRunPanel.vue'
import { useSessionStore } from '@/stores/session'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: '旧操作受控错误' }), { status, headers: { 'Content-Type': 'application/json' } })
const deferred = () => { let resolve!: (r: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const scenario = (id = 'a') => ({ id, projectId: 'p1', name: id, description: '', version: 1, updatedAt: '2026-10-26T00:00:00Z', createdAt: '2026-10-26T00:00:00Z', environmentCode: 'test', failureStrategy: 'STOP', variables: {}, preRequestScript: '', postResponseScript: '', steps: [{ id: 'get', type: 'REQUEST', name: 'HTTP', config: { method: 'GET', url: 'https://example.test' } }] })
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
type Kind = 'scenario' | 'dataset' | 'profile' | 'schedule'
const titles = { scenario: '新建接口自动化场景', dataset: '新建接口自动化数据集', profile: '新建场景运行配置集', schedule: '新建 Quartz 定时计划' }
const labels = { scenario: '场景名称', dataset: '数据集名称', profile: '配置集名称', schedule: '计划名称' }
const paths = { scenario: 'scenarios', dataset: 'data-sets', profile: 'execution-profiles', schedule: 'schedules' }
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents({ name: 'Modal' }).find(c => c.props('title') === titles[kind])!
const nameField = (w: VueWrapper, kind: Kind) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === labels[kind])!.findComponent({ name: 'Input' })
const editor = (w: VueWrapper) => w.findAllComponents({ name: 'Input' }).find(c => c.attributes('aria-label') === '场景名称')!
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('update:visible', false); await flushPromises() }
async function open(w: VueWrapper, kind: Kind, name = '草稿B') {
  if (kind === 'scenario') w.findComponent(AutomationSidebar).vm.$emit('create')
  else if (kind === 'profile') w.findComponent(AutomationRunPanel).vm.$emit('create-profile')
  else {
    w.findComponent(AutomationSidebar).vm.$emit('update:active-asset', kind === 'dataset' ? 'data' : 'integrations')
    await flushPromises()
    w.findAllComponents({ name: 'Button' }).find(c => c.text().trim() === (kind === 'dataset' ? '新建数据集' : '新建计划'))!.vm.$emit('click')
  }
  await flushPromises()
  nameField(w, kind).vm.$emit('update:modelValue', name)
  await flushPromises()
}

async function setup(initialHook: Hook = () => undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  let hook: Hook = initialHook
  const fetcher = vi.fn((url: string, init?: RequestInit) => hook(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
      : url.endsWith('/access/context') ? { organizations: [{ id: 'o1' }], projects: { o1: ['p1', 'p2'].map(id => ({ id, organizationId: 'o1', name: id, code: id })) } }
        : url.endsWith('/environments') ? [{ id: 'env', name: '测试', environmentCode: 'test', enabled: true, defaultEnvironment: true }]
          : url.endsWith('/scenarios') ? [scenario(), scenario('b')]
            : /\/scenarios\/[ab]$/.test(url) ? scenario(url.slice(-1))
              : [])))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/api-automation', component: { template: '<div />' } }] }); await router.push('/api-automation')
  const w = shallowMount(AutomationPage, { global: { plugins: [pinia, ArcoVue, router], renderStubDefaultSlot: true, config: { warnHandler: () => {} }, stubs: { Popover: { template: '<div><slot/><slot name="content"/></div>' } } } })
  wrappers.push(w); await flushPromises()
  return { w, session, fetcher, hook: (value: Hook) => { hook = value } }
}
const saved = (kind: Kind) => kind === 'scenario' ? scenario() : kind === 'dataset' ? { dataSet: { id: 'data', name: '旧A', rowCount: 2, columns: ['row'] }, previewRows: [], previewTruncated: false } : { id: 'saved', name: '旧A', version: 1 }

it('[DF-N01][POS][OBS-08-E012] the run panel describes a page-local submission hint without persistence or delivery', async () => {
  const q = await setup(), panel = q.w.findComponent(AutomationRunPanel)
  const rendered = mountRunPanel(panel.props())
  expect(rendered.text()).toContain('本页提交提示'); expect(rendered.text()).toContain('刷新后重置'); expect(rendered.text()).toContain('不发送外部通知')
})
function mountRunPanel(props: InstanceType<typeof AutomationRunPanel>['$props']) {
  const w = shallowMount(AutomationRunPanel, { props, global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); return w
}
it('[DF-N02][POS][OBS-08-E012] enabling the hint keeps it local and resets on page remount', async () => {
  const q = await setup(), before = q.fetcher.mock.calls.length
  q.w.findComponent(AutomationRunPanel).vm.$emit('update:notification-enabled', true); await flushPromises()
  expect(q.w.findComponent(AutomationRunPanel).props('notificationEnabled')).toBe(true); expect(q.fetcher.mock.calls).toHaveLength(before)
  q.w.unmount(); const next = await setup(); expect(next.w.findComponent(AutomationRunPanel).props('notificationEnabled')).toBe(false)
})
for (const enabled of [true, false]) it(`[DF-N0${enabled ? 3 : 4}][${enabled ? 'POS' : 'NEG'}][OBS-08-E012] hint=${enabled} only controls the existing local message`, async () => {
  const info = vi.spyOn(Message, 'info').mockImplementation(() => ({ close: vi.fn() })), q = await setup()
  q.hook((url, init) => url.endsWith('/executions') && init?.method === 'POST' ? Promise.resolve(response({ id: 'execution-qa' })) : undefined)
  const panel = q.w.findComponent(AutomationRunPanel); panel.vm.$emit('update:notification-enabled', enabled); panel.vm.$emit('run'); await flushPromises()
  const hint = info.mock.calls.filter(([message]) => String(message).includes('刷新后重置'))
  expect(hint).toHaveLength(enabled ? 1 : 0)
  expect(q.fetcher.mock.calls.some(([url]) => /notification|subscription|delivery/.test(url))).toBe(false)
  for (const [, init] of q.fetcher.mock.calls.filter(([, init]) => init?.method)) expect(String(init?.body)).not.toMatch(/notificationEnabled|notification-enabled/)
})

for (const kind of ['scenario', 'dataset', 'profile', 'schedule'] as const) {
  for (const status of [200, 503]) it(`${kind}: late ${status} leaves replacement draft and messages intact`, async () => {
    const q = await setup(), old = deferred()
    q.hook((url, init) => url.endsWith('/' + paths[kind]) && init?.method === 'POST' ? old.promise : undefined)
    await open(q.w, kind, '草稿A'); const pending = save(q.w, kind); await flushPromises(); await cancel(q.w, kind); await open(q.w, kind)
    old.resolve(response(saved(kind), status)); await pending; await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true)
    expect(nameField(q.w, kind).props('modelValue')).toBe('草稿B')
    expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
  })
  it(`${kind}: old completion cannot stop a newer save`, async () => {
    const q = await setup(), old = deferred(), latest = deferred(); let writes = 0
    q.hook((url, init) => url.endsWith('/' + paths[kind]) && init?.method === 'POST' ? (++writes === 1 ? old.promise : latest.promise) : undefined)
    await open(q.w, kind, '草稿A'); const first = save(q.w, kind); await flushPromises(); await cancel(q.w, kind); await open(q.w, kind)
    const second = save(q.w, kind); await flushPromises(); old.resolve(response(saved(kind))); await first; await flushPromises()
    expect(modal(q.w, kind).props('okLoading')).toBe(true); expect(modal(q.w, kind).props('visible')).toBe(true)
    latest.resolve(response(saved(kind))); await second; await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(false); expect(modal(q.w, kind).props('okLoading')).toBe(false)
  })
  for (const action of ['cancel', 'project'] as const) it(`${kind}: ${action} while CSRF is pending prevents stale write`, async () => {
    const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
    await open(q.w, kind); const pending = save(q.w, kind); await flushPromises()
    if (action === 'cancel') await cancel(q.w, kind); else { q.session.projectId = 'p2'; await flushPromises() }
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); await pending; await flushPromises()
    expect(q.fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
  it(`${kind}: current failure keeps the draft and allows retry`, async () => {
    const q = await setup(); let fail = true
    q.hook((url, init) => url.endsWith('/' + paths[kind]) && init?.method === 'POST' ? Promise.resolve(response(saved(kind), fail ? 503 : 200)) : undefined)
    await open(q.w, kind); await save(q.w, kind); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(modal(q.w, kind).props('okLoading')).toBe(false)
    expect(nameField(q.w, kind).props('modelValue')).toBe('草稿B'); expect(Message.error).toHaveBeenCalledOnce()
    fail = false; await save(q.w, kind); await flushPromises(); expect(modal(q.w, kind).props('visible')).toBe(false)
  })
}

for (const status of [200, 503]) {
  it(`late detail ${status} cannot replace scenario B`, async () => {
    const q = await setup(), old = deferred(); q.hook(url => url.endsWith('/scenarios/a') ? old.promise : undefined)
    q.w.findComponent(AutomationSidebar).vm.$emit('select', 'a'); await flushPromises()
    q.w.findComponent(AutomationSidebar).vm.$emit('select', 'b'); await flushPromises()
    old.resolve(response(scenario(), status)); await flushPromises()
    expect(editor(q.w).props('modelValue')).toBe('b'); expect(Message.error).not.toHaveBeenCalled()
  })
  it(`late save ${status} cannot replace scenario B`, async () => {
    const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'PUT' ? old.promise : undefined)
    q.w.findComponent(AutomationRunPanel).vm.$emit('save'); await flushPromises()
    q.w.findComponent(AutomationSidebar).vm.$emit('select', 'b'); await flushPromises()
    old.resolve(response({ ...scenario(), name: '旧A', version: 2 }, status)); await flushPromises()
    expect(editor(q.w).props('modelValue')).toBe('b'); expect(Message.error).not.toHaveBeenCalled(); expect(Message.success).not.toHaveBeenCalled()
  })
}

for (const action of ['project-return', 'unmount'] as const) it(`old scenario list after ${action} causes no follow-up requests`, async () => {
  const q = await setup(), old = deferred(); q.hook(url => url.endsWith('/scenarios') ? old.promise : undefined)
  q.w.findComponent(AutomationSidebar).vm.$emit('refresh'); await flushPromises()
  if (action === 'unmount') q.w.unmount()
  else { q.session.projectId = 'p2'; q.session.projectId = 'p1'; await flushPromises() }
  const before = q.fetcher.mock.calls.length; old.resolve(response([scenario()])); await flushPromises()
  expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0)
})

it('unmounting during initial load cannot restart the old page poller', async () => {
  const old = deferred(), q = await setup(url => url.endsWith('/scenarios') ? old.promise : undefined)
  q.w.unmount()
  const interval = vi.spyOn(window, 'setInterval'), before = q.fetcher.mock.calls.length
  old.resolve(response([scenario()])); await flushPromises()
  expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0)
  expect(interval).not.toHaveBeenCalled()
})

const previewModal = (w: VueWrapper) => w.findAllComponents({ name: 'Modal' }).find(c => c.props('title') === '提交场景执行')!
const previewButton = (w: VueWrapper) => previewModal(w).findAllComponents({ name: 'Button' }).find(c => c.text().includes('预览最终来源'))!
const previewData = { scenarioId: 'a', scenarioVersion: 1, environmentCode: 'test', executionProfileId: null, executionProfileVersion: null, variableReferences: [] }
async function openPreview(w: VueWrapper) { w.findComponent(AutomationRunPanel).vm.$emit('advanced'); await flushPromises() }
async function changeOverride(w: VueWrapper) {
  previewModal(w).findAllComponents({ name: 'FormItem' }).find(c => String(c.props('label')).includes('本次覆盖变量'))!.findComponent({ name: 'Textarea' }).vm.$emit('update:modelValue', '{"VALUE":"new"}')
  await flushPromises()
}
for (const status of [200, 503]) it(`preview ${status} after input changes is discarded`, async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await openPreview(q.w); previewButton(q.w).vm.$emit('click'); await flushPromises(); await changeOverride(q.w)
  old.resolve(response(previewData, status)); await flushPromises()
  expect(q.w.find('.execution-configuration-preview').exists()).toBe(false); expect(Message.error).not.toHaveBeenCalled()
})
it('old preview cannot stop a newer preview spinner', async () => {
  const q = await setup(), old = deferred(), latest = deferred(); let writes = 0
  q.hook((_url, init) => init?.method === 'POST' ? (++writes === 1 ? old.promise : latest.promise) : undefined)
  await openPreview(q.w); previewButton(q.w).vm.$emit('click'); await flushPromises(); await changeOverride(q.w)
  previewButton(q.w).vm.$emit('click'); await flushPromises(); old.resolve(response(previewData)); await flushPromises()
  expect(previewButton(q.w).props('loading')).toBe(true)
  latest.resolve(response(previewData)); await flushPromises()
  expect(previewButton(q.w).props('loading')).toBe(false); expect(q.w.find('.execution-configuration-preview').exists()).toBe(true)
})
it('closing preview during CSRF prevents its obsolete submission', async () => {
  const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await openPreview(q.w); previewButton(q.w).vm.$emit('click'); await flushPromises()
  previewModal(q.w).vm.$emit('update:visible', false); await flushPromises()
  csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); await flushPromises()
  expect(q.fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
