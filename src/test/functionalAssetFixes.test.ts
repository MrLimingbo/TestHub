import { computed, ref } from 'vue'
import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import FunctionalTestingPage from '@/pages/FunctionalTestingPage.vue'
import FunctionalGovernancePage from '@/pages/FunctionalGovernancePage.vue'
import { functionalWorkspaceKey, type FunctionalWorkspace, type FunctionalCase } from '@/composables/useFunctionalWorkspace'
import { useSessionStore } from '@/stores/session'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: String(data) }), { status })
function deferred() {
  let resolve!: (response: Response) => void
  const promise = new Promise<Response>(release => { resolve = release })
  return { promise, resolve }
}
function mocks() {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
}
function asset(id: string): FunctionalCase {
  return { id, displayId: Number(id.slice(1)), catalogId: 'catalog', requirementId: null, caseCode: id.toUpperCase(), title: id, priority: 'P1', status: 'DRAFT', caseType: 'FUNCTIONAL', precondition: '', steps: '步骤', expectedResult: '结果', createdBy: '', createdByName: '', createdAt: '', updatedAt: '', executedAt: null, executedBy: null, executedByName: null }
}
async function setup(kind: 'requirements' | 'cases' = 'requirements', override?: (url: string, init?: RequestInit) => Promise<Response> | undefined) {
  mocks()
  const pinia = createPinia(); const session = useSessionStore(pinia); session.projectId = 'p1'
  const workspace: FunctionalWorkspace = { catalogs: ref([{ id: 'catalog', name: '目录', scope: kind === 'requirements' ? 'REQUIREMENTS' : 'CASES', parentId: null, sortOrder: 0 }]), requirements: ref([]), cases: ref([asset('c1'), asset('c2')]), defects: ref([]), plans: ref([]), selectedCatalogId: ref(''), selectedCatalogName: computed(() => '全部'), loading: ref(false), reload: vi.fn(async () => {}) }
  const fetcher = vi.fn((url: string, init?: RequestInit) => override?.(url, init) ?? Promise.resolve(response(url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' } : {})))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(FunctionalTestingPage, { props: { assetType: kind }, global: { plugins: [pinia, ArcoVue], provide: { [functionalWorkspaceKey as symbol]: workspace }, stubs: { teleport: true }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, workspace, fetcher, session }
}
const modal = (w: VueWrapper) => w.findComponent({ name: 'Modal' })
const field = (w: VueWrapper, label: string) => w.findAllComponents({ name: 'FormItem' }).find(item => item.props('label') === label)!
async function open(w: VueWrapper, kind = 'requirements', title = '新草稿') {
  await w.get('.create-asset').trigger('click'); await flushPromises()
  field(w, kind === 'requirements' ? '需求标题' : '用例标题').findComponent({ name: 'Input' }).vm.$emit('update:modelValue', title)
  await flushPromises()
}
function save(w: VueWrapper) { return (modal(w).props('onBeforeOk') as () => Promise<boolean>)() }
async function cancel(w: VueWrapper) { modal(w).vm.$emit('cancel'); modal(w).vm.$emit('update:visible', false); await flushPromises() }
async function batch(w: VueWrapper) {
  w.findComponent({ name: 'Table' }).vm.$emit('update:selectedKeys', ['c1', 'c2']); await flushPromises()
  w.findAllComponents({ name: 'Popconfirm' }).find(p => String(p.props('content')).startsWith('确认删除选中的'))!.vm.$emit('ok'); await flushPromises()
}

for (const kind of ['requirements', 'cases'] as const) {
  for (const status of [200, 503]) it(`${kind}: late ${status} cannot close, clear or show errors on the replacement draft`, async () => {
    const old = deferred(); const { w } = await setup(kind, (_url, init) => init?.method === 'POST' ? old.promise : undefined)
    await open(w, kind, '草稿A'); const saving = save(w); await flushPromises()
    await cancel(w); await open(w, kind, '草稿B')
    old.resolve(response(status === 200 ? {} : '旧请求错误', status)); expect(await saving).toBe(false); await flushPromises()
    expect(modal(w).props('visible')).toBe(true)
    expect(field(w, kind === 'requirements' ? '需求标题' : '用例标题').findComponent({ name: 'Input' }).props('modelValue')).toBe('草稿B')
    expect(Message.error).not.toHaveBeenCalled(); expect(Message.success).not.toHaveBeenCalled()
  })
}
it('an old save completing while a new save is pending does not stop its spinner', async () => {
  const old = deferred(), current = deferred(); let posts = 0
  const { w } = await setup('requirements', (_url, init) => init?.method === 'POST' ? (++posts === 1 ? old.promise : current.promise) : undefined)
  await open(w); const first = save(w); await flushPromises(); await cancel(w); await open(w, 'requirements', '新草稿B'); const second = save(w); await flushPromises()
  old.resolve(response({})); await first; await flushPromises(); expect(modal(w).props('okLoading')).toBe(true); expect(modal(w).props('visible')).toBe(true)
  current.resolve(response({})); expect(await second).toBe(true); await flushPromises(); expect(modal(w).props('visible')).toBe(false); expect(modal(w).props('okLoading')).toBe(false)
})
for (const action of ['cancel', 'unmount']) it(`${action} during CSRF does not send the old draft`, async () => {
  const csrf = deferred(); const { w, fetcher } = await setup('requirements', url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await open(w); const pending = save(w); await flushPromises()
  if (action === 'cancel') await cancel(w); else w.unmount()
  csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); expect(await pending).toBe(false)
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
for (const refreshFails of [false, true]) it(`partial batch failure reconciles committed rows and retries only failed IDs (refresh failure=${refreshFails})`, async () => {
  let fail = true
  const { w, workspace, fetcher } = await setup('cases', (url, init) => init?.method === 'DELETE' ? Promise.resolve(response(fail && url.endsWith('/c2') ? '删除失败' : {}, fail && url.endsWith('/c2') ? 503 : 200)) : undefined)
  if (refreshFails) vi.mocked(workspace.reload).mockRejectedValueOnce(new Error('刷新失败'))
  await batch(w)
  expect(workspace.cases.value.map(c => c.id)).toEqual(['c2']); expect(w.get('.batch-delete').text()).toContain('（1）')
  expect(w.findComponent({ name: 'Table' }).props('selectedKeys')).toEqual(['c2'])
  fail = false; fetcher.mockClear()
  w.findAllComponents({ name: 'Popconfirm' }).find(p => String(p.props('content')).startsWith('确认删除选中的'))!.vm.$emit('ok'); await flushPromises()
  expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([url]) => url)).toEqual(['/api/v1/projects/p1/functional/cases/c2'])
  expect(workspace.cases.value).toEqual([]); expect(w.get('.batch-delete').text()).toContain('（0）')
})
it('waits for the late successful deletion before reconciling a fast failure', async () => {
  const late = deferred(); const { w, workspace } = await setup('cases', (url, init) => init?.method === 'DELETE' ? (url.endsWith('/c1') ? late.promise : Promise.resolve(response('删除失败', 503))) : undefined)
  await batch(w); expect(w.findComponent({ name: 'Button' }).exists()).toBe(true); expect(workspace.cases.value).toHaveLength(2)
  late.resolve(response({})); await flushPromises(); expect(workspace.cases.value.map(c => c.id)).toEqual(['c2'])
})

for (const type of ['TEXT', 'NUMBER', 'DATE']) it(`SELECT to ${type} sends empty options while preserving the field identity`, async () => {
  mocks(); const pinia = createPinia(); useSessionStore(pinia).projectId = 'p1'
  const definition = { id: 'f1', name: '字段', assetType: 'CASE', fieldKey: 'qa_field', fieldType: 'SELECT', required: false, optionsJson: '["旧选项"]', updatedAt: '' }
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => response(url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' } : url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', organizationId: 'o1' }] } } : url.endsWith('/custom-fields') ? [definition] : init?.method === 'PUT' ? {} : []))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(FunctionalGovernancePage, { global: { plugins: [pinia, ArcoVue], stubs: { teleport: true, FunctionalAssetTags: true }, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  w.findAllComponents({ name: 'Button' }).find(b => b.text() === '编辑')!.vm.$emit('click'); await flushPromises()
  field(w, '字段类型').findComponent({ name: 'Select' }).vm.$emit('update:modelValue', type); await flushPromises()
  const dialog = w.findAllComponents({ name: 'Modal' }).find(m => m.props('visible'))!; await (dialog.props('onBeforeOk') as () => Promise<boolean>)(); await flushPromises()
  const put = fetcher.mock.calls.find(([, init]) => init?.method === 'PUT')!
  expect(put[0]).toBe('/api/v1/projects/p1/functional/custom-fields/f1')
  expect(JSON.parse(put[1]!.body as string)).toMatchObject({ assetType: 'CASE', fieldKey: 'qa_field', fieldType: type, options: [] })
})
