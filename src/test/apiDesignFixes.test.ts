import { defineComponent } from 'vue'
import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import ApiDesignPage from '@/pages/ApiDesignPage.vue'
import { emptyApiDesignPreferences } from '@/utils/apiDesignPreferences'
import { useSessionStore } from '@/stores/session'

type Kind = 'catalog' | 'definition' | 'version' | 'interchange'
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: String(data) }), { status })
function deferred<T = Response>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(release => { resolve = release })
  return { promise, resolve }
}
const timestamp = '2026-10-24T00:00:00Z'
const version = (id = 'v1') => ({ id, versionName: '1.0.0', summary: '', description: '原始快照', requestHeaders: '[]', requestQuery: '[]', requestPath: '[]', requestBody: '{"source":"original"}', responseBody: '{}', exampleResponse: '{"statusCode":200}', status: 'DRAFT', publishedAt: null, createdAt: timestamp })
const definition = (id: string) => ({ id, catalogId: 'root', method: 'GET', path: '/' + id, name: id, status: 'DRAFT', currentVersionId: 'v1', versionCount: 1, createdBy: 'u1', updatedBy: 'u1', createdAt: timestamp, updatedAt: timestamp })
const Workbench = defineComponent({ name: 'ApiInterfaceWorkbench', props: ['api', 'version'], emits: ['new-version', 'edit'], template: '<div class="test-workbench"><slot name="editor" /></div>' })
async function setup(override?: (url: string, init?: RequestInit) => Promise<Response> | undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  const state = { versions: [version()] }
  const fetcher = vi.fn((url: string, init?: RequestInit) => override?.(url, init) ?? Promise.resolve(response(
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' }
      : url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', organizationId: 'o1', name: '项目', code: 'P1' }, { id: 'p2', organizationId: 'o1', name: '项目2', code: 'P2' }] } }
        : url.endsWith('/catalogs') ? [{ id: 'root', parentId: null, name: '根目录', sortOrder: 0 }]
          : url.endsWith('/definitions') ? [definition('a'), definition('b')]
            : url.endsWith('/versions') ? state.versions
              : url.endsWith('/workspace-preferences') ? { revision: 0, state: emptyApiDesignPreferences() } : [])))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/api-testing/interfaces', component: { template: '<div />' } }] }); await router.push('/api-testing/interfaces')
  const w = mount(ApiDesignPage, { global: { plugins: [pinia, ArcoVue, router], stubs: { teleport: true, ApiInterfaceWorkbench: Workbench, Dropdown: { template: '<div><slot /><slot name="content" /></div>' }, Dsubmenu: { template: '<div><slot /><slot name="content" /></div>' } }, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, session, fetcher, state }
}
const titles: Record<Kind, string> = { catalog: '新建接口目录', definition: '新建接口与初始版本', version: '基于当前版本创建快照', interchange: '导入 / 导出接口资产' }
const labels = { catalog: '目录名称', definition: '接口名称', version: '新版本名称' }
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents({ name: 'Modal' }).find(item => item.props('title') === titles[kind])!
const field = (w: VueWrapper, kind: Exclude<Kind, 'interchange'>) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(item => item.props('label') === labels[kind])!.findComponent({ name: 'Input' })
const documentField = (w: VueWrapper) => modal(w, 'interchange').findAllComponents({ name: 'FormItem' }).find(item => item.props('label') === '接口定义文档')!.findComponent({ name: 'Textarea' })
const importButton = (w: VueWrapper) => modal(w, 'interchange').findAllComponents({ name: 'Button' }).find(item => item.text().includes('校验并导入'))!
async function open(w: VueWrapper, kind: Kind, value = '草稿B', format = 'OPENAPI') {
  if (kind === 'version') w.findComponent(Workbench).vm.$emit('new-version')
  else if (kind === 'definition') w.get('[aria-label="新建接口"]').trigger('click')
  else w.findAllComponents({ name: 'Doption' }).find(item => item.text().trim() === (kind === 'catalog' ? '新建目录' : '导入 / 导出'))!.vm.$emit('click')
  await flushPromises()
  if (kind === 'interchange') {
    modal(w, kind).findComponent({ name: 'RadioGroup' }).vm.$emit('update:modelValue', format); await flushPromises()
    if (format !== 'CURL') documentField(w).vm.$emit('update:modelValue', value)
    else modal(w, kind).findAllComponents({ name: 'FormItem' }).find(item => item.props('label') === '接口名称')!.findComponent({ name: 'Input' }).vm.$emit('update:modelValue', value)
  } else field(w, kind).vm.$emit('update:modelValue', value)
  await flushPromises()
}
const save = (w: VueWrapper, kind: Exclude<Kind, 'interchange'>) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function cancel(w: VueWrapper, kind: Kind) { modal(w, kind).vm.$emit('cancel'); modal(w, kind).vm.$emit('update:visible', false); await flushPromises() }
const saved = (kind: Kind) => kind === 'catalog' ? { id: 'saved', name: '目录A', parentId: null, sortOrder: 1 } : kind === 'definition' ? definition('a') : version('v2')

for (const kind of ['catalog', 'definition', 'version'] as const) {
  for (const status of [200, 503]) it(`${kind}: late ${status} preserves replacement draft and errors`, async () => {
    const old = deferred(); const { w } = await setup((_url, init) => init?.method === 'POST' ? old.promise : undefined)
    await open(w, kind, '草稿A'); const pending = save(w, kind); await flushPromises(); await cancel(w, kind); await open(w, kind)
    old.resolve(response(status === 200 ? saved(kind) : '旧错误', status)); expect(await pending).toBe(false); await flushPromises()
    expect(modal(w, kind).props('visible')).toBe(true); expect(field(w, kind).props('modelValue')).toBe('草稿B')
    expect(modal(w, kind).text()).not.toContain('旧错误'); expect(Message.error).not.toHaveBeenCalled(); expect(Message.success).not.toHaveBeenCalled()
  })
  it(`${kind}: old completion cannot stop a newer save spinner`, async () => {
    const old = deferred(), current = deferred(); let posts = 0
    const { w } = await setup((_url, init) => init?.method === 'POST' ? (++posts === 1 ? old.promise : current.promise) : undefined)
    await open(w, kind, '草稿A'); const first = save(w, kind); await flushPromises(); await cancel(w, kind); await open(w, kind); const second = save(w, kind); await flushPromises()
    old.resolve(response(saved(kind))); await first; await flushPromises(); expect(modal(w, kind).props('okLoading')).toBe(true); expect(modal(w, kind).props('visible')).toBe(true)
    current.resolve(response(saved(kind))); expect(await second).toBe(true); await flushPromises(); expect(modal(w, kind).props('visible')).toBe(false)
  })
  for (const action of ['cancel', 'unmount', 'project']) it(`${kind}: ${action} during CSRF prevents stale submission`, async () => {
    const csrf = deferred(); const { w, session, fetcher } = await setup(url => url.endsWith('/csrf') ? csrf.promise : undefined)
    await open(w, kind); const pending = save(w, kind); await flushPromises()
    if (action === 'cancel') await cancel(w, kind); else if (action === 'unmount') w.unmount(); else { session.projectId = 'p2'; await flushPromises() }
    csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); expect(await pending).toBe(false)
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
  it(`${kind}: current failure keeps draft available for retry`, async () => {
    let fail = true
    const { w } = await setup((_url, init) => init?.method === 'POST' ? Promise.resolve(response(fail ? '受控错误' : saved(kind), fail ? 503 : 200)) : undefined)
    await open(w, kind); expect(await save(w, kind)).toBe(false); await flushPromises()
    expect(modal(w, kind).props('visible')).toBe(true); expect(modal(w, kind).props('okLoading')).toBe(false); expect(modal(w, kind).text()).toContain('受控错误')
    fail = false; expect(await save(w, kind)).toBe(true); expect(modal(w, kind).props('visible')).toBe(false)
  })
}
it('replacement version draft retains its original base after an old save refreshes versions', async () => {
  const old = deferred(); const { w, state, fetcher } = await setup((_url, init) => init?.method === 'POST' ? old.promise : undefined)
  await open(w, 'version', '2.0.0'); const pending = save(w, 'version'); await flushPromises(); await cancel(w, 'version'); await open(w, 'version', '3.0.0')
  state.versions = [{ ...version('v2'), description: '旧请求新建的快照', requestBody: '{"source":"changed"}' }]
  old.resolve(response(version('v2'))); await pending; await flushPromises()
  const second = save(w, 'version'); await flushPromises(); await second
  const payload = JSON.parse(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST').at(-1)![1]!.body as string)
  expect(payload.versionName).toBe('3.0.0'); expect(payload.description).toBe('原始快照'); expect(payload.requestBody).toBe('{"source":"original"}')
})

for (const format of ['OPENAPI', 'POSTMAN', 'HAR', 'CURL']) {
  for (const status of [200, 503]) it(`${format}: late import ${status} cannot decorate a replacement draft`, async () => {
    const old = deferred(); const { w } = await setup((_url, init) => init?.method === 'POST' ? old.promise : undefined)
    await open(w, 'interchange', '文档A', format); importButton(w).vm.$emit('click'); await flushPromises(); await cancel(w, 'interchange'); await open(w, 'interchange', '文档B', format)
    old.resolve(response(status === 200 ? { format, importedCount: 1, items: ['旧接口A'] } : '旧错误', status)); await flushPromises()
    expect(modal(w, 'interchange').props('visible')).toBe(true); expect(w.find('.interchange-result').exists()).toBe(false)
    expect(Message.success).not.toHaveBeenCalled(); expect(Message.error).not.toHaveBeenCalled()
    if (format !== 'CURL') expect(documentField(w).props('modelValue')).toBe('文档B')
  })
  it(`${format}: an old import cannot clear a newer spinner or result`, async () => {
    const old = deferred(), current = deferred(); let posts = 0
    const { w } = await setup((_url, init) => init?.method === 'POST' ? (++posts === 1 ? old.promise : current.promise) : undefined)
    await open(w, 'interchange', '文档A', format); importButton(w).vm.$emit('click'); await flushPromises(); await cancel(w, 'interchange'); await open(w, 'interchange', '文档B', format); importButton(w).vm.$emit('click'); await flushPromises()
    old.resolve(response({ format, importedCount: 1, items: ['旧接口A'] })); await flushPromises(); expect(importButton(w).props('loading')).toBe(true); expect(w.find('.interchange-result').exists()).toBe(false)
    current.resolve(response({ format, importedCount: 1, items: ['新接口B'] })); await flushPromises(); expect(importButton(w).props('loading')).toBe(false); expect(w.get('.interchange-result').text()).toContain('新接口B'); expect(w.get('.interchange-result').text()).not.toContain('旧接口A')
  })
}
for (const action of ['cancel', 'unmount', 'project', 'edit', 'format']) it(`import: ${action} during CSRF prevents stale submission`, async () => {
  const csrf = deferred(); const { w, session, fetcher } = await setup(url => url.endsWith('/csrf') ? csrf.promise : undefined)
  await open(w, 'interchange'); importButton(w).vm.$emit('click'); await flushPromises()
  if (action === 'cancel') await cancel(w, 'interchange')
  else if (action === 'unmount') w.unmount()
  else if (action === 'project') session.projectId = 'p2'
  else if (action === 'edit') documentField(w).vm.$emit('update:modelValue', '新内容')
  else modal(w, 'interchange').findComponent({ name: 'RadioGroup' }).vm.$emit('update:modelValue', 'HAR')
  await flushPromises(); csrf.resolve(response({ headerName: 'X-CSRF', token: 'fixture' })); await flushPromises()
  expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
})
it('current import failure releases its spinner and permits retry', async () => {
  let fail = true
  const { w } = await setup((_url, init) => init?.method === 'POST' ? Promise.resolve(response(fail ? '受控错误' : { format: 'OPENAPI', importedCount: 1, items: ['重试成功'] }, fail ? 503 : 200)) : undefined)
  await open(w, 'interchange'); importButton(w).vm.$emit('click'); await flushPromises(); expect(Message.error).toHaveBeenCalledWith('受控错误'); expect(importButton(w).props('loading')).toBe(false)
  fail = false; importButton(w).vm.$emit('click'); await flushPromises(); expect(w.get('.interchange-result').text()).toContain('重试成功')
})
for (const action of ['file', 'reopen', 'edit']) it(`late file read cannot overwrite a newer ${action}`, async () => {
  const { w } = await setup(); await open(w, 'interchange')
  const old = deferred<string>(), input = w.get<HTMLInputElement>('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [{ name: 'old.json', size: 20, text: () => old.promise }] }); await input.trigger('change')
  if (action === 'reopen') { await cancel(w, 'interchange'); await open(w, 'interchange', '新草稿') }
  else if (action === 'edit') { documentField(w).vm.$emit('update:modelValue', '新草稿'); await flushPromises() }
  else { Object.defineProperty(input.element, 'files', { configurable: true, value: [{ name: 'new.json', size: 20, text: async () => '新草稿' }] }); await input.trigger('change'); await flushPromises() }
  vi.mocked(Message.success).mockClear(); old.resolve('旧文档'); await flushPromises()
  expect(documentField(w).props('modelValue')).toBe('新草稿'); expect(modal(w, 'interchange').text()).not.toContain('old.json'); expect(Message.success).not.toHaveBeenCalled()
})
