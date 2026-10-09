import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Modal } from '@arco-design/web-vue'
import TasksPage from '@/pages/TasksPage.vue'
import { useSessionStore } from '@/stores/session'
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const task = (projectId = 'p1', name = '任务一') => ({ id: 't-' + projectId, projectId, organizationId: 'o1', taskType: 'API', name, status: 'QUEUED', progress: 0, version: 1, requestedBy: 'qa', createdAt: '2026-10-04T00:00:00Z', updatedAt: '2026-10-04T00:00:00Z', sourceTaskId: null })
async function setup(override?: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const custom = await override?.(url, init)
    if (custom && typeof custom === 'object' && 'ok' in custom) return custom
    const data = custom ?? (url.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', name: '项目一' }, { id: 'p2', name: '项目二' }] } } : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : [task(url.includes('/p2/') ? 'p2' : 'p1', url.includes('/p2/') ? '项目二登记' : '任务一')])
    return { ok: true, json: async () => ({ data }) }
  }))
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'
  const wrapper = mount(TasksPage, { global: { plugins: [pinia, ArcoVue], stubs: { Modal: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return { wrapper, session }
}
const button = (w: ReturnType<typeof mount>, name: string) => w.findAllComponents(Button).find(b => b.text() === name)!
const modal = (w: ReturnType<typeof mount>) => w.findComponent(Modal)
describe('Task registration page', () => {
  it('opens native sync management in the local desktop without writing registrations', async () => {
    const openTaskSync = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('testhubDesktop', { workspaceMode: 'local', openTaskSync })
    const { wrapper } = await setup()
    button(wrapper, '任务登记同步').vm.$emit('click'); await flushPromises()
    expect(openTaskSync).toHaveBeenCalledOnce()
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })
  it('ordinary web clients do not show a native-only sync action', async () => {
    const { wrapper } = await setup(); expect(button(wrapper, '任务登记同步')).toBeUndefined()
  })
  it('keeps failed edits open and submits the displayed version', async () => {
    const { wrapper } = await setup((_, init) => init?.method === 'PUT' ? { ok: false, json: async () => ({ message: '任务已更新' }) } : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    expect(await modal(wrapper).props('onBeforeOk')!(vi.fn())).toBe(false); expect(modal(wrapper).props('visible')).toBe(true)
    const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(String(call[1]!.body))).toMatchObject({ name: '任务一', expectedVersion: 1, progress: 0 })
  })
  it('global project selection closes old forms and rejects a delayed list response', async () => {
    let delayed = false, release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => delayed && url.endsWith('/p1/tasks') ? new Promise(resolve => { release = resolve }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises(); delayed = true
    button(wrapper, '刷新').vm.$emit('click'); await flushPromises(); session.selectProject('p2'); await flushPromises()
    expect(modal(wrapper).props('visible')).toBe(false); expect(wrapper.text()).toContain('项目二登记')
    release([task('p1', '迟到旧任务')]); await flushPromises(); expect(wrapper.text()).not.toContain('迟到旧任务'); expect(wrapper.text()).toContain('项目二登记')
  })
  it('captures the edited project before awaiting CSRF and leaves the new project intact', async () => {
    let release!: (value: unknown) => void
    const { wrapper, session } = await setup(url => url.endsWith('/csrf') ? new Promise(resolve => { release = resolve }) : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    const save = modal(wrapper).props('onBeforeOk')!(vi.fn()); await flushPromises(); session.selectProject('p2'); await flushPromises()
    release({ token: 'fixture', headerName: 'X-CSRF' }); await flushPromises(); expect(await save).toBe(false)
    const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(String(call[0])).toBe('/api/v1/projects/p1/tasks/t-p1'); expect(wrapper.text()).toContain('项目二登记'); expect(modal(wrapper).props('visible')).toBe(false)
  })
  it('a failed new-project load clears old rows and displays the failure', async () => {
    const { wrapper, session } = await setup(url => url.endsWith('/p2/tasks') ? { ok: false, json: async () => ({ message: '服务暂不可用' }) } : undefined)
    session.selectProject('p2'); await flushPromises(); expect(wrapper.text()).toContain('服务暂不可用'); expect(wrapper.text()).not.toContain('任务一')
  })
  it('deduplicates pending cancellation and sends the record version', async () => {
    let release!: (value: unknown) => void
    const { wrapper } = await setup((url, init) => url.endsWith('/cancel') && init?.method === 'POST' ? new Promise(resolve => { release = resolve }) : undefined)
    const cancel = button(wrapper, '取消'); cancel.vm.$emit('click'); cancel.vm.$emit('click'); await flushPromises()
    const calls = vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/cancel')); expect(calls).toHaveLength(1)
    expect(JSON.parse(String(calls[0]![1]!.body))).toEqual({ expectedVersion: 1 }); expect(cancel.props('loading')).toBe(true)
    release({}); await flushPromises(); expect(button(wrapper, '取消').props('loading')).toBe(false)
  })
  it('shows immutable source status and version for imported registrations', async () => {
    const { wrapper } = await setup(url => url.endsWith('/tasks') ? [{ ...task(), status: 'CANCELLED', sourceTaskId: 'source', sourceStatus: 'RUNNING', sourceVersion: 5, sourceCreatedAt: '2026-10-01T00:00:00Z' }] : undefined)
    expect(wrapper.text()).toContain('迁入登记'); expect(wrapper.text()).toContain('来源执行中'); expect(wrapper.text()).toContain('来源第 5 版'); expect(button(wrapper, '标记执行中')).toBeUndefined()
  })
  it('does not load or create tasks with no accessible project', async () => {
    const { wrapper } = await setup(url => url.endsWith('/context') ? { organizations: [], projects: {} } : undefined)
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith('/tasks'))).toHaveLength(0)
    expect(button(wrapper, '新建任务').props('disabled')).toBe(true)
  })
  it('unmounting invalidates a pending list request', async () => {
    let delayed = false, release!: (value: unknown) => void
    const { wrapper } = await setup(url => delayed && url.endsWith('/tasks') ? new Promise(resolve => { release = resolve }) : undefined)
    delayed = true; button(wrapper, '刷新').vm.$emit('click'); await flushPromises(); wrapper.unmount()
    release([task('p1', '销毁后的任务')]); await flushPromises(); expect(wrapper.exists()).toBe(false)
  })
})
