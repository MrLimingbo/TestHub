import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, Message, Modal, Select, Textarea } from '@arco-design/web-vue'
import ReportsPage from '@/pages/ReportsPage.vue'
import TasksPage from '@/pages/TasksPage.vue'
import NotificationTemplatesPage from '@/pages/NotificationTemplatesPage.vue'
import PluginsPage from '@/pages/PluginsPage.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'

type Kind = 'report' | 'task' | 'notification' | 'package' | 'adapter'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear() })
const reply = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: 'QA受控失败' }), { status })
function deferred() {
  let resolve!: (value: Response) => void
  const pending = new Promise<Response>(r => { resolve = r })
  return { get promise() { return pending.then(value => value.clone()) }, resolve }
}
const stamp = '2026-10-29T00:00:00Z', csrf = { token: 'fixture', headerName: 'X-CSRF' }
const task = { id: 't1', projectId: 'p1', name: '登记', taskType: 'API', status: 'QUEUED', progress: 0, version: 1, createdAt: stamp, updatedAt: stamp }
const summary = { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }
const report = { from: '2026-10-01', to: '2026-10-29', projectCount: 1, summary, trend: [], projects: [] }
const titles = { report: '新建定时质量报告', task: '新建执行任务', notification: '新建通知模板', package: '安装已签名插件包', adapter: '新建企业适配器' }
const fields = { report: '计划名称', task: '任务名称', notification: '模板名称', package: '插件 JSON 清单', adapter: '名称' }
const button = (w: VueWrapper, title: string) => w.findAllComponents(Button).find(b => b.text().trim() === title)!
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(m => m.props('title') === titles[kind])!
const field = (w: VueWrapper, kind: Kind) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === fields[kind])!.findComponent(kind === 'package' ? Textarea : Input)
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function open(w: VueWrapper, kind: Kind, name = '新草稿') {
  if (kind === 'task' || kind === 'notification') w.findComponent(PageHeader).vm.$emit('action')
  else button(w, { report: '新建计划', package: '安装签名包', adapter: '新建适配器' }[kind]).vm.$emit('click')
  await flushPromises(); field(w, kind).vm.$emit('update:modelValue', name); await flushPromises()
}
async function close(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('update:visible', false); modal(w, kind).vm.$emit('cancel'); await flushPromises() }
async function setup(kind: Kind, count = 2, administration = true) {
  for (const name of ['error', 'success', 'warning'] as const) vi.spyOn(Message, name).mockImplementation(() => ({ close: vi.fn() }))
  const projects = Array.from({ length: count }, (_, i) => ({ id: `p${i + 1}`, name: `项目${i + 1}`, code: `P${i + 1}`, organizationId: 'o1', status: 'ACTIVE', description: '', createdByName: null, createdAt: stamp, updatedAt: stamp }))
  const context = { organizations: [{ id: 'o1', name: 'QA', code: 'QA' }], projects: { o1: projects } }
  let hook: Hook = () => undefined
  const fetcher = vi.fn((url: string, init?: RequestInit) => hook(url, init) ?? Promise.resolve(
    url.includes('/export?') ? new Response('qa,csv') : reply(
      url.endsWith('/context') ? context : url.endsWith('/csrf') ? csrf
        : url.endsWith('/capabilities') ? { systemAdministration: administration }
          : init?.method ? {} : url.includes('/quality?') ? report : url.endsWith('/tasks') ? [task]
            : url.endsWith('/signing-keys') ? [{ id: 'k1', keyId: 'qa.key', displayName: 'QA公钥', enabled: true, createdAt: stamp }]
              : url.endsWith('/adapter-definitions') ? [{ type: 'OIDC', displayName: 'OIDC', category: 'IDENTITY', description: '', requiredFields: ['clientId'] }] : [])))
  vi.stubGlobal('fetch', fetcher)
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'; session.organizations = context.organizations; session.projectsByOrganization = context.projects
  const w = mount(kind === 'report' ? ReportsPage : kind === 'task' ? TasksPage : kind === 'notification' ? NotificationTemplatesPage : PluginsPage, {
    global: { plugins: [pinia, ArcoVue], stubs: { Modal: true, Drawer: true, DashboardTrendChart: true, UnifiedExecutionsPanel: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } },
  })
  wrappers.push(w); await flushPromises(); return { w, fetcher, session, hook: (value: Hook) => { hook = value } }
}
type Fixture = Awaited<ReturnType<typeof setup>>
const writes = (q: Fixture) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT', 'DELETE'].includes(init?.method ?? ''))
const queries = (q: Fixture) => q.fetcher.mock.calls.filter(([url]) => url.includes('/quality?') || url.includes('/quality/export?'))
const scopeSelect = (q: Fixture) => q.w.findAllComponents(Select)[0]!
async function scope(q: Fixture, ids: string[]) { scopeSelect(q).vm.$emit('update:modelValue', ids); await flushPromises() }
function stubDownload() {
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:qa'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
}

it('[DF-R01][POS][OBS-13-E015] fifty accessible projects retain the existing initial report', async () => {
  const q = await setup('report', 50); expect(queries(q).length).toBeGreaterThan(0)
  expect(new URL(String(queries(q).at(-1)![0]), 'https://qa.invalid').searchParams.getAll('projectIds')).toHaveLength(50)
})
it('[DF-R02][NEG][OBS-13-E015] fifty-one projects wait for explicit selection without an implicit all-project request', async () => {
  const q = await setup('report', 51)
  expect(queries(q)).toHaveLength(0); expect(scopeSelect(q).props('modelValue')).toEqual([])
  expect(q.w.text()).toContain('请先选择'); expect(q.w.text()).toContain('50')
  await open(q.w, 'report'); expect(modal(q.w, 'report').findComponent(Select).props('modelValue')).toEqual([])
})
for (const size of [1, 50]) it(`[DF-R0${size === 1 ? 3 : 4}][POS][OBS-13-E015] explicit ${size}-project selection queries and exports exactly that scope`, async () => {
  stubDownload(); const q = await setup('report', 51)
  await scope(q, Array.from({ length: size }, (_, i) => `p${i + 1}`)); q.w.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
  expect(queries(q)).toHaveLength(2)
  for (const [url] of queries(q)) expect(new URL(String(url), 'https://qa.invalid').searchParams.getAll('projectIds')).toHaveLength(size)
})
it('[DF-R05][NEG][OBS-13-E015] clearing a large scope gates refresh and export as well as automatic querying', async () => {
  const q = await setup('report', 51); await scope(q, ['p1']); await scope(q, []); const before = queries(q).length
  button(q.w, '刷新').vm.$emit('click'); q.w.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
  expect(queries(q)).toHaveLength(before); expect(Message.warning).toHaveBeenCalled()
})
it('[DF-R06][NEG][OBS-13-E015] an injected oversized selection cannot bypass request guards', async () => {
  const q = await setup('report', 51); await scope(q, Array.from({ length: 51 }, (_, i) => `p${i + 1}`))
  button(q.w, '刷新').vm.$emit('click'); q.w.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
  expect(queries(q)).toHaveLength(0); expect(q.w.text()).toContain('最多选择50个项目')
})
it('[DF-R07][NEG][OBS-13-E015] selecting a fifty-first project gives an explicit limit explanation', async () => {
  const q = await setup('report', 51); expect(scopeSelect(q).props('limit')).toBe(50)
  scopeSelect(q).vm.$emit('exceed-limit', 'p51', new Event('click')); await flushPromises()
  expect(Message.warning).toHaveBeenCalledWith('最多选择50个项目')
})
it('[DF-R08][NEG][OBS-13-E015] a late report cannot repopulate a cleared large-project scope', async () => {
  const q = await setup('report', 51), old = deferred(); q.hook(url => url.includes('/quality?') ? old.promise : undefined)
  await scope(q, ['p1']); await scope(q, []); old.resolve(reply({ ...report, projectCount: 999 })); await flushPromises()
  expect(q.w.findComponent({ name: 'MetricGrid' }).exists()).toBe(false); expect(q.w.text()).toContain('请先选择')
})
it('[DF-R09][POS][OBS-13-E015] clearing a scope of fifty or fewer retains the existing all-project semantics', async () => {
  const q = await setup('report', 2); await scope(q, [])
  expect(new URL(String(queries(q).at(-1)![0]), 'https://qa.invalid').searchParams.getAll('projectIds')).toHaveLength(0)
})
it('[DF-R10][NEG][OBS-13-E015] empty and oversized schedule selections do not send invalid writes', async () => {
  const q = await setup('report', 51); await open(q.w, 'report')
  expect(await save(q.w, 'report')).toBe(false)
  modal(q.w, 'report').findComponent(Select).vm.$emit('update:modelValue', Array.from({ length: 51 }, (_, i) => `p${i + 1}`)); await flushPromises()
  expect(await save(q.w, 'report')).toBe(false); expect(writes(q)).toHaveLength(0)
})

it('[DF-T01][POS][OBS-13-E009] refreshing the task list does not disable opening a fresh draft', async () => {
  const q = await setup('task'), pending = deferred(); q.hook(url => url.endsWith('/tasks') ? pending.promise : undefined)
  button(q.w, '刷新').vm.$emit('click'); await flushPromises()
  expect(button(q.w, '新建任务').props('disabled')).toBe(false); expect(q.w.findComponent(PageHeader).props('action')).toBe('新建任务')
  await open(q.w, 'task'); expect(modal(q.w, 'task').props('visible')).toBe(true)
  pending.resolve(reply([])); await flushPromises(); expect(field(q.w, 'task').props('modelValue')).toBe('新草稿')
})
for (const status of [200, 503]) it(`[DF-T0${status === 200 ? 2 : 3}][NEG][OBS-13-E009] late task refresh ${status} preserves a new draft`, async () => {
  const q = await setup('task'), pending = deferred(); q.hook(url => url.endsWith('/tasks') ? pending.promise : undefined)
  button(q.w, '刷新').vm.$emit('click'); await flushPromises(); await open(q.w, 'task')
  pending.resolve(reply([], status)); await flushPromises()
  expect(modal(q.w, 'task').props('visible')).toBe(true); expect(field(q.w, 'task').props('modelValue')).toBe('新草稿')
})
it('[DF-T04][NEG][OBS-13-E009] a project round trip still cancels a draft opened while refreshing', async () => {
  const q = await setup('task'), pending = deferred(); q.hook(url => url.endsWith('/tasks') ? pending.promise : undefined)
  button(q.w, '刷新').vm.$emit('click'); await flushPromises(); await open(q.w, 'task')
  q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises(); pending.resolve(reply([])); await flushPromises()
  expect(modal(q.w, 'task').props('visible')).toBe(false); expect(writes(q)).toHaveLength(0)
})
for (const [index, phase] of ['csrf', 'business', 'reload'].entries()) for (const status of [200, 503]) it(`[DF-S${index}${status === 200 ? 1 : 2}][NEG][OBS-13-E017] old ${phase} ${status} cannot lock or unlock a replacement save`, async () => {
  const q = await setup('task'), old = deferred(), latest = deferred(); let count = 0
  q.hook((url, init) => {
    if (phase === 'csrf' && url.endsWith('/csrf') && ++count === 1) return old.promise
    if (phase === 'reload' && !init?.method && url.endsWith('/tasks') && ++count === 1) return old.promise
    if (init?.method === 'POST') return phase === 'business' && ++count === 1 ? old.promise : phase === 'reload' && writes(q).length === 1 ? undefined : latest.promise
    return undefined
  })
  await open(q.w, 'task', '旧A'); const first = save(q.w, 'task'); await flushPromises(); await close(q.w, 'task'); await open(q.w, 'task', '新B')
  expect(modal(q.w, 'task').props('okLoading')).toBe(false)
  const second = save(q.w, 'task'); await flushPromises(); expect(modal(q.w, 'task').props('okLoading')).toBe(true)
  expect(await save(q.w, 'task')).toBe(false)
  old.resolve(reply(phase === 'csrf' ? csrf : phase === 'reload' ? [] : {}, status)); expect(await first).toBe(false); await flushPromises()
  expect(modal(q.w, 'task').props('okLoading')).toBe(true); expect(field(q.w, 'task').props('modelValue')).toBe('新B')
  latest.resolve(reply({})); expect(await second).toBe(true); await flushPromises(); expect(modal(q.w, 'task').props('visible')).toBe(false)
})
it('[DF-S07][POS][OBS-13-E017] current task failure releases its own save state and supports retry', async () => {
  const q = await setup('task'); let fail = true; q.hook((_url, init) => init?.method === 'POST' ? Promise.resolve(reply({}, fail ? 503 : 200)) : undefined)
  await open(q.w, 'task'); expect(await save(q.w, 'task')).toBe(false); expect(modal(q.w, 'task').props('okLoading')).toBe(false)
  fail = false; expect(await save(q.w, 'task')).toBe(true)
})

for (const [index, kind] of (['notification', 'package', 'adapter'] as const).entries()) {
  const obs = { notification: 'OBS-14-E004', package: 'OBS-14-E014', adapter: 'OBS-14-E019' }[kind]
  it(`[DF-F${index}1][POS][${obs}] a pending list refresh permits a new authorized draft without a write`, async () => {
    const q = await setup(kind), pending = deferred(); q.hook(url => url.endsWith(kind === 'notification' ? '/notification-templates' : '/capabilities') ? pending.promise : undefined)
    button(q.w, kind === 'notification' ? '刷新记录' : '刷新插件与适配器').vm.$emit('click'); await flushPromises(); await open(q.w, kind)
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(writes(q)).toHaveLength(0)
    pending.resolve(reply(kind === 'notification' ? [] : { systemAdministration: true })); await flushPromises()
    expect(field(q.w, kind).props('modelValue')).toBe('新草稿'); expect(modal(q.w, kind).props('visible')).toBe(true)
  })
  for (const status of [200, 503]) it(`[DF-F${index}${status === 200 ? 2 : 3}][NEG][${obs}] list ${status} cannot overwrite a draft opened during refresh`, async () => {
    const q = await setup(kind), pending = deferred(); q.hook(url => url.endsWith(kind === 'notification' ? '/notification-templates' : '/plugins') ? pending.promise : undefined)
    button(q.w, kind === 'notification' ? '刷新记录' : '刷新插件与适配器').vm.$emit('click'); await flushPromises(); await open(q.w, kind)
    pending.resolve(reply([], status)); await flushPromises()
    expect(modal(q.w, kind).props('visible')).toBe(true); expect(field(q.w, kind).props('modelValue')).toBe('新草稿'); expect(writes(q)).toHaveLength(0)
  })
}
it('[DF-F04][NEG][OBS-14-E004] switching projects invalidates a template draft opened during refresh', async () => {
  const q = await setup('notification'), pending = deferred(); q.hook(url => url.endsWith('/notification-templates') ? pending.promise : undefined)
  button(q.w, '刷新记录').vm.$emit('click'); await flushPromises(); await open(q.w, 'notification')
  q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises(); pending.resolve(reply([])); await flushPromises()
  expect(modal(q.w, 'notification').props('visible')).toBe(false); expect(writes(q)).toHaveLength(0)
})
for (const [index, kind] of (['package', 'adapter'] as const).entries()) {
  const obs = kind === 'package' ? 'OBS-14-E014' : 'OBS-14-E019'
  it(`[DF-P${index}1][NEG][${obs}] read-only users cannot open or submit administrative drafts`, async () => {
    const q = await setup(kind, 2, false); q.w.findComponent(PageHeader).vm.$emit('action'); await flushPromises()
    expect(q.w.findAllComponents(Modal)).toHaveLength(0); expect(writes(q)).toHaveLength(0)
  })
  it(`[DF-P${index}2][NEG][${obs}] a confirmed permission revocation invalidates the pending draft`, async () => {
    const q = await setup(kind), pending = deferred(); q.hook(url => url.endsWith('/capabilities') ? pending.promise : undefined)
    button(q.w, '刷新插件与适配器').vm.$emit('click'); await flushPromises(); await open(q.w, kind)
    pending.resolve(reply({ systemAdministration: false })); await flushPromises()
    expect(q.w.findAllComponents(Modal)).toHaveLength(0); expect(writes(q)).toHaveLength(0)
  })
}
