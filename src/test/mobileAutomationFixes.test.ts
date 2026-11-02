import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Message, Modal, Input } from '@arco-design/web-vue'
import MobileAutomationPage from '@/pages/MobileAutomationPage.vue'
import PageHeader from '@/components/PageHeader.vue'

const native = vi.hoisted(() => ({ local: false, prepare: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => false, ensureLocalAppium: native.prepare, openMobileAutomationSync: vi.fn(), openMobileAutomationHistorySync: vi.fn(), openMobileAutomationArtifactSync: vi.fn(), openMobileApplicationFileSync: vi.fn(), openLocalExecutionSettings: vi.fn() }))
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); state.projectId = 'p1'; native.local = false; native.prepare.mockReset() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: '受控请求失败' }), { status, headers: { 'Content-Type': 'application/json' } })
const deferred = () => { let resolve!: (value: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
const device = { id: 'd', name: '设备A', platform: 'ANDROID', udid: 'qa-owned', automationName: 'UiAutomator2', capabilities: {}, enabled: true, version: 1 }
const app = { id: 'a', name: '应用A', platform: 'ANDROID', appIdentifier: 'io.testhub.qa', versionName: '1.0', appFileId: null, enabled: true, version: 1 }
const element = { id: 'o', name: '元素A', platform: 'ANDROID', locatorStrategy: 'ACCESSIBILITY_ID', locatorValue: 'qa-name', description: '', updatedAt: '2026-10-27T00:00:00Z' }
const scenario = { id: 's', name: '场景A', deviceId: 'd', applicationId: 'a', description: '', failureStrategy: 'STOP', variables: {}, steps: [{ id: 'launch', type: 'LAUNCH', name: '启动', timeoutMillis: 1000 }], version: 1 }
const execution = { id: 'e', scenarioId: 's', scenarioName: '场景A', status: 'QUEUED', requestedAt: '2026-10-27T00:00:00Z', retryAttempt: 0 }
type Kind = 'device' | 'app' | 'element' | 'scenario' | 'execute'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const names = { device: '设备名称', app: '应用名称', element: '元素名称', scenario: '场景名称' }
const titles = { device: ['新建设备', '编辑设备'], app: ['新建移动应用', '编辑移动应用'], element: ['新建元素对象', '编辑元素对象'], scenario: ['新建移动端场景', '编辑移动端场景'], execute: ['执行移动端场景'] }
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(c => titles[kind].includes(String(c.props('title'))))!
const field = (w: VueWrapper, kind: Exclude<Kind, 'execute'>) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === names[kind])!.findComponent(Input)
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('update:visible', false); modal(w, kind).vm.$emit('cancel'); await flushPromises() }
async function open(w: VueWrapper, kind: Kind, name = '草稿A', edit = false) {
  if (kind === 'scenario') {
    if (edit) w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('编辑编排'))!.vm.$emit('click')
    else w.findComponent(PageHeader).vm.$emit('action')
  } else if (kind === 'execute') w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('执行场景'))!.vm.$emit('click')
  else {
    const tab = { device: '设备', app: '应用包', element: '元素对象' }[kind]
    await w.findAll('.arco-tabs-tab').find(c => c.text() === tab)!.trigger('click'); await flushPromises()
    if (edit) w.findAllComponents({ name: 'Button' }).filter(c => c.text() === '编辑' && c.isVisible())[0]!.vm.$emit('click')
    else w.findAllComponents({ name: 'Button' }).find(c => c.text() === { device: '新建设备', app: '新建应用', element: '新建元素' }[kind])!.vm.$emit('click')
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
          : url.endsWith('/mobile-automation') ? { devices: [device], applications: [app], elements: [element], scenarios: [scenario], executions: [] } : [])))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(MobileAutomationPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true, MobileAutomationHistoryPanel: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, fetcher, hook: (value: Hook) => { hook = value } }
}
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? ''))
for (const kind of ['device', 'app', 'element', 'scenario'] as const) {
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
  const old = deferred(), q = await setup(url => url.endsWith('/mobile-automation') ? old.promise : undefined)
  if (action === 'unmount') q.w.unmount(); else { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() }
  const before = q.fetcher.mock.calls.length; old.resolve(response({ devices: [device], applications: [app], elements: [element], scenarios: [scenario], executions: [] })); await flushPromises(); expect(q.fetcher.mock.calls.slice(before)).toHaveLength(0); expect(Message.error).not.toHaveBeenCalled()
})
it('duplicate element save callbacks only send one write', async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(q.w, 'element'); const first = save(q.w, 'element'), second = save(q.w, 'element'); await flushPromises()
  expect(writes(q)).toHaveLength(1); old.resolve(response(element)); await Promise.all([first, second])
})
it('element save captures the submitted form before awaiting CSRF', async () => {
  const q = await setup(), csrf = deferred(); q.hook(url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await open(q.w, 'element', '提交A'); const pending = save(q.w, 'element'); await flushPromises(); field(q.w, 'element').vm.$emit('update:modelValue', '后来输入B')
  csrf.resolve(response({ token: 'fixture', headerName: 'X-CSRF' })); await pending
  expect(JSON.parse(String(writes(q)[0]![1]?.body)).name).toBe('提交A')
})

for (const action of ['project-return', 'unmount'] as const) for (const status of [200, 503]) it(`asset refresh ${status} after ${action} has no stale feedback`, async () => {
  const q = await setup(), old = deferred(); q.hook(url => url.endsWith('/mobile-automation') ? old.promise : undefined)
  q.w.findAllComponents({ name: 'Button' }).find(c => c.text() === '刷新')!.vm.$emit('click'); await flushPromises()
  if (action === 'unmount') q.w.unmount(); else { state.projectId = 'p2'; state.projectId = 'p1'; await flushPromises() }
  old.resolve(response({ devices: [], applications: [], elements: [], scenarios: [], executions: [] }, status)); await flushPromises()
  expect(Message.error).not.toHaveBeenCalled(); if (action !== 'unmount') expect(q.w.text()).toContain('场景A')
})
it('a current refresh failure remains visible to the user', async () => {
  const q = await setup(); q.hook(url => url.endsWith('/mobile-automation') ? Promise.resolve(response({}, 503)) : undefined)
  q.w.findAllComponents({ name: 'Button' }).find(c => c.text() === '刷新')!.vm.$emit('click'); await flushPromises(); expect(Message.error).toHaveBeenCalledOnce()
})
for (const status of [200, 503]) it(`execute: old ${status} keeps a replacement execution dialog open`, async () => {
  const q = await setup(), old = deferred(); q.hook((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(q.w, 'execute'); const pending = save(q.w, 'execute'); await flushPromises(); await cancel(q.w, 'execute'); await open(q.w, 'execute')
  old.resolve(response(execution, status)); await pending; await flushPromises(); expect(modal(q.w, 'execute').props('visible')).toBe(true); expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
})
it('old native preparation cannot queue after cancel and reopening the same scenario', async () => {
  native.local = true; let release!: () => void; native.prepare.mockImplementation(() => new Promise<void>(r => { release = r }))
  const q = await setup(); await open(q.w, 'execute'); const pending = save(q.w, 'execute'); await flushPromises(); await cancel(q.w, 'execute'); await open(q.w, 'execute')
  release(); await pending; await flushPromises(); expect(writes(q)).toHaveLength(0); expect(modal(q.w, 'execute').props('visible')).toBe(true)
})
it('old native preparation cannot release a newer preparation spinner', async () => {
  native.local = true; const releases: Array<() => void> = []; native.prepare.mockImplementation(() => new Promise<void>(r => releases.push(r)))
  const q = await setup(); await open(q.w, 'execute'); const first = save(q.w, 'execute'); await flushPromises(); await cancel(q.w, 'execute'); await open(q.w, 'execute'); const second = save(q.w, 'execute'); await flushPromises()
  expect(releases).toHaveLength(2); releases[0]!(); await first; await flushPromises(); expect(modal(q.w, 'execute').props('okLoading')).toBe(true); expect(writes(q)).toHaveLength(0)
  releases[1]!(); await second; await flushPromises(); expect(writes(q)).toHaveLength(1)
})
