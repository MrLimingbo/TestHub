import { computed, ref } from 'vue'
import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import FunctionalPlansPage from '@/pages/FunctionalPlansPage.vue'
import FunctionalDeliveryPage from '@/pages/FunctionalDeliveryPage.vue'
import ProjectVersionsPage from '@/pages/ProjectVersionsPage.vue'
import { functionalWorkspaceKey, type FunctionalWorkspace } from '@/composables/useFunctionalWorkspace'
import { useSessionStore } from '@/stores/session'

type Kind = 'plan' | 'defect' | 'version'
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: String(data) }), { status })
function deferred() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>(release => { resolve = release })
  return { promise, resolve }
}
const timestamp = '2026-10-24T00:00:00Z'
const plan = (id: string) => ({ id, catalogId: null, planCode: id.toUpperCase(), name: `计划${id}`, status: 'READY', description: '', caseCount: 2, updatedAt: timestamp })
const defect = (id: string) => ({ id, displayId: Number(id.slice(1)), catalogId: null, defectCode: id.toUpperCase(), title: `缺陷${id}`, dueDate: null, bugType: 'CODE_ERROR', keywords: '', browsers: [], operatingSystems: [], ccUserIds: [], severity: 'MAJOR', priority: 'P1', status: 'OPEN', confirmed: false, assigneeId: null, assigneeName: null, assignedBy: null, assignedByName: null, resolution: null, resolvedBy: null, resolvedByName: null, resolvedAt: null, source: 'TESTHUB', externalUrl: null, description: '<p>复现步骤</p>', createdBy: '', createdByName: '', createdAt: timestamp, updatedAt: timestamp, linkCount: 1 })
const run = (id: string) => ({ id: `run-${id}`, planId: id, name: `批次${id}`, status: 'NOT_STARTED', totalCount: 2, passedCount: 0, failedCount: 0, blockedCount: 0, notRunCount: 2, createdAt: timestamp })
const runCase = (id: string) => ({ id, caseId: id, caseCode: id, title: `用例${id}`, status: 'NOT_RUN', note: '' })
const step = (id: string) => ({ id: `step-${id}`, runCaseId: id, stepNo: 1, stepText: `步骤${id}`, status: 'NOT_RUN', actualResult: '' })
const link = (id: string) => ({ id: `link-${id}`, assetType: 'CASE', assetId: id, assetLabel: `关联${id}`, createdAt: timestamp })
async function setup(kind: Kind, override?: (url: string, init?: RequestInit) => Promise<Response> | undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  const workspace: FunctionalWorkspace = { catalogs: ref([]), requirements: ref([]), cases: ref([]), defects: ref([defect('d1'), defect('d2')]), plans: ref([]), selectedCatalogId: ref(''), selectedCatalogName: computed(() => '全部'), loading: ref(false), reload: vi.fn(async () => {}) }
  const state = { plans: [plan('a'), plan('b')], defects: [defect('d1'), defect('d2')] }
  const fetcher = vi.fn((url: string, init?: RequestInit) => override?.(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
      : url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', organizationId: 'o1', name: '项目', code: 'P1' }, { id: 'p2', organizationId: 'o1', name: '项目2', code: 'P2' }] } }
        : url.endsWith('/functional/plans') ? state.plans
          : url.includes('/plans/') && url.endsWith('/cases') ? [{ id: 'pc', caseId: 'c1', caseCode: 'C1', title: url.includes('/a/') ? '范围A' : '范围B', priority: 'P1', status: 'READY' }]
            : url.includes('/runs?') ? [run(url.endsWith('=a') ? 'a' : 'b')]
              : url.includes('/runs/') && url.endsWith('/cases') ? [runCase('c1'), runCase('c2')]
                : url.endsWith('/steps') ? [step(url.includes('/c1/') ? 'c1' : 'c2')]
                  : url.endsWith('/links') ? [link(url.includes('/d1/') ? 'd1' : 'd2')]
                    : url.endsWith('/functional/defects') ? state.defects : [])))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] }); await router.push('/')
  const w = mount({ plan: FunctionalPlansPage, defect: FunctionalDeliveryPage, version: ProjectVersionsPage }[kind], { global: { plugins: [pinia, ArcoVue, router], provide: { [functionalWorkspaceKey as symbol]: workspace }, stubs: { teleport: true, RichTextEditor: true, RichTextViewer: true }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, state, workspace, session, fetcher }
}
const labels = { plan: '计划名称', defect: 'Bug 标题', version: '版本号' }
const field = (w: VueWrapper, label: string) => w.findAllComponents({ name: 'FormItem' }).find(item => item.props('label') === label)!.findComponent({ name: 'Input' })
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents({ name: 'Modal' }).find(item => String(item.props('title')).includes({ plan: '测试计划', defect: 'Bug', version: '版本' }[kind]) && !String(item.props('title')).includes('解决'))!
async function open(w: VueWrapper, kind: Kind, title = '草稿B') {
  w.findAllComponents({ name: 'Button' }).find(item => item.text().includes({ plan: '新建测试计划', defect: '提 Bug', version: '创建版本' }[kind]))!.vm.$emit('click'); await flushPromises()
  field(w, labels[kind]).vm.$emit('update:modelValue', title); await flushPromises()
}
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('cancel'); modal(w, kind).vm.$emit('update:visible', false); await flushPromises() }

for (const kind of ['plan', 'defect', 'version'] as const) {
  for (const status of [200, 503]) it(`${kind}: late ${status} preserves replacement draft and its errors`, async () => {
    const old = deferred(); const { w } = await setup(kind, (_url, init) => init?.method === 'POST' ? old.promise : undefined)
    await open(w, kind, '草稿A'); const pending = save(w, kind); await flushPromises(); await cancel(w, kind); await open(w, kind)
    old.resolve(response(status === 200 ? { id: 'saved' } : '旧错误', status)); expect(await pending).toBe(false); await flushPromises()
    expect(modal(w, kind).props('visible')).toBe(true); expect(field(w, labels[kind]).props('modelValue')).toBe('草稿B')
    expect(Message.error).not.toHaveBeenCalled(); expect(Message.success).not.toHaveBeenCalled()
  })
  it(`${kind}: old completion cannot stop a newer save spinner`, async () => {
    const old = deferred(), current = deferred(); let posts = 0
    const { w } = await setup(kind, (_url, init) => init?.method === 'POST' ? (++posts === 1 ? old.promise : current.promise) : undefined)
    await open(w, kind, '草稿A'); const first = save(w, kind); await flushPromises(); await cancel(w, kind); await open(w, kind); const second = save(w, kind); await flushPromises()
    old.resolve(response({ id: 'saved' })); await first; await flushPromises()
    expect(modal(w, kind).props('okLoading')).toBe(true); expect(modal(w, kind).props('visible')).toBe(true)
    current.resolve(response({ id: 'saved2' })); expect(await second).toBe(true); await flushPromises(); expect(modal(w, kind).props('visible')).toBe(false)
  })
  for (const action of ['cancel', 'unmount', 'project']) it(`${kind}: ${action} during CSRF prevents stale submission`, async () => {
    const csrf = deferred(); const { w, session, fetcher } = await setup(kind, url => url.endsWith('/csrf') ? csrf.promise : undefined)
    await open(w, kind); const pending = save(w, kind); await flushPromises()
    if (action === 'cancel') await cancel(w, kind); else if (action === 'unmount') w.unmount(); else { session.projectId = 'p2'; await flushPromises() }
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); expect(await pending).toBe(false)
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
}
for (const status of [200, 503]) {
  it(`late plan ${status} cannot replace the current plan scope`, async () => {
    let hold = false; const old = deferred()
    const { w } = await setup('plan', url => hold && url.endsWith('/plans/a/cases') ? old.promise : undefined)
    hold = true; await w.findAll('.plan-item')[0]!.trigger('click'); await w.findAll('.plan-item')[1]!.trigger('click'); await flushPromises()
    expect(w.get('.plan-cases').text()).toContain('范围B')
    old.resolve(response(status === 200 ? [{ id: 'old', title: '旧范围A' }] : '旧错误', status)); await flushPromises()
    expect(w.get('.plan-header').text()).toContain('计划b'); expect(w.get('.plan-cases').text()).toContain('范围B'); expect(w.get('.plan-cases').text()).not.toContain('旧范围A'); expect(Message.error).not.toHaveBeenCalled()
  })
  it(`late step ${status} cannot replace current case steps`, async () => {
    let hold = false; const old = deferred()
    const { w } = await setup('plan', url => hold && url.endsWith('/run-cases/c1/steps') ? old.promise : undefined)
    hold = true; await w.findAll('.run-case-item')[0]!.trigger('click'); await w.findAll('.run-case-item')[1]!.trigger('click'); await flushPromises()
    old.resolve(response(status === 200 ? [step('c1')] : '旧错误', status)); await flushPromises()
    expect(w.get('.step-list').text()).toContain('步骤c2'); expect(w.get('.step-list').text()).not.toContain('步骤c1'); expect(Message.error).not.toHaveBeenCalled()
  })
  it(`late defect links ${status} cannot replace a reopened drawer`, async () => {
    let hold = true; const old = deferred()
    const { w } = await setup('defect', url => hold && url.endsWith('/defects/d1/links') ? old.promise : undefined)
    const titles = () => w.findAll('.bug-title')
    await titles()[0]!.trigger('click'); await flushPromises()
    const drawer = w.findAllComponents({ name: 'Drawer' })[0]!; drawer.vm.$emit('update:visible', false); await flushPromises()
    hold = false; await titles()[1]!.trigger('click'); await flushPromises()
    old.resolve(response(status === 200 ? [link('d1')] : '旧错误', status)); await flushPromises()
    expect(w.get('.trace-section').text()).toContain('关联d2'); expect(w.get('.trace-section').text()).not.toContain('关联d1'); expect(Message.error).not.toHaveBeenCalled()
  })
}
for (const kind of ['plan', 'defect'] as const) for (const refreshFails of [false, true]) for (const slowSuccess of [false, true]) it(`${kind}: partial deletion reconciles and retries only failures (refreshFails=${refreshFails}, slowSuccess=${slowSuccess})`, async () => {
  let fail = true, refresh = false
  const late = deferred(); const firstId = kind === 'plan' ? 'a' : 'd1', lastId = kind === 'plan' ? 'b' : 'd2'
  const collection = kind === 'plan' ? 'plans' : 'defects'
  const { w, state, fetcher, workspace } = await setup(kind, (url, init) => {
    if (init?.method === 'DELETE') {
      if (fail && url.endsWith('/' + lastId)) return Promise.resolve(response('受控删除失败', 503))
      if (slowSuccess && url.endsWith('/' + firstId)) return late.promise
      if (kind === 'plan') state.plans = state.plans.filter(p => !url.endsWith('/' + p.id)); else state.defects = state.defects.filter(d => !url.endsWith('/' + d.id))
      return Promise.resolve(response({}))
    }
    if (refreshFails && refresh && url.endsWith('/functional/' + collection)) return Promise.resolve(response('刷新失败', 503))
    return undefined
  })
  if (kind === 'plan') for (const item of w.findAll('.plan-item')) item.findComponent({ name: 'Checkbox' }).vm.$emit('change', true)
  else w.findComponent({ name: 'Table' }).vm.$emit('update:selectedKeys', [firstId, lastId])
  await flushPromises(); refresh = true
  const batch = () => w.findAllComponents({ name: 'Popconfirm' }).find(item => String(item.props('content')).startsWith('确认删除选中的'))!.vm.$emit('ok')
  batch(); await flushPromises()
  if (slowSuccess) {
    expect(kind === 'plan' ? w.findAll('.plan-item').length : workspace.defects.value.length).toBe(2)
    if (kind === 'plan') state.plans = state.plans.filter(p => p.id !== firstId); else state.defects = state.defects.filter(d => d.id !== firstId)
    late.resolve(response({})); await flushPromises()
  }
  if (kind === 'plan') { expect(w.findAll('.plan-item')).toHaveLength(1); expect(w.get('.plan-item').text()).toContain('计划b') }
  else { expect(workspace.defects.value.map(d => d.id)).toEqual([lastId]); expect(w.findComponent({ name: 'Table' }).props('selectedKeys')).toEqual([lastId]) }
  expect(w.findAllComponents({ name: 'Popconfirm' }).find(item => String(item.props('content')).startsWith('确认删除选中的'))!.props('content')).toContain('1 个')
  fail = false; refresh = false; fetcher.mockClear(); batch(); await flushPromises()
  expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([url]) => url)).toEqual([`/api/v1/projects/p1/functional/${collection}/${lastId}`])
  expect(kind === 'plan' ? w.findAll('.plan-item').length : workspace.defects.value.length).toBe(0)
})
it('long plan name gives a valid Unicode-safe default in the run editor', async () => {
  const name = '长'.repeat(192) + '😀' + '尾'.repeat(6)
  const { w } = await setup('plan', url => url.endsWith('/functional/plans') ? Promise.resolve(response([{ ...plan('a'), name }])) : undefined)
  w.findAllComponents({ name: 'Button' }).find(item => item.text() === '创建执行批次')!.vm.$emit('click'); await flushPromises()
  const value = field(w, '批次名称').props('modelValue') as string
  expect(value.startsWith('长'.repeat(192))).toBe(true); expect(value.length).toBeLessThanOrEqual(200); expect(value.endsWith(' · 执行批次')).toBe(true); expect(value).not.toMatch(/[\uD800-\uDBFF] · 执行批次$/)
})
