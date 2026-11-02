import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ArcoVue, { Button, Input, Message, Modal } from '@arco-design/web-vue'
import ReportsPage from '@/pages/ReportsPage.vue'
import TasksPage from '@/pages/TasksPage.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionStore } from '@/stores/session'

type Kind = 'report' | 'task'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
const response = (data: unknown, status = 200) => new Response(status === 204 ? undefined : JSON.stringify(status >= 400 ? { message: 'QA请求失败' } : { data }), { status })
const schedule = { id: 's1', name: 'QA周报', projectIds: ['p1'], cronExpression: '0 0 9 ? * MON', timezone: 'Asia/Shanghai', periodDays: 7, enabled: true, version: 0, lastRunAt: null, lastRunStatus: null }
const task = { id: 't1', projectId: 'p1', organizationId: 'o1', taskType: 'API', name: 'QA任务', status: 'QUEUED', progress: 0, version: 1, requestedBy: 'qa', createdAt: '2026-10-27T00:00:00Z', updatedAt: '2026-10-27T00:00:00Z', sourceTaskId: null }
const summary = { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }
const csrf = { token: 'fixture', headerName: 'X-CSRF' }
const button = (w: VueWrapper, name: string) => w.findAllComponents(Button).find(b => b.text() === name)!
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(m => String(m.props('title')).includes(kind === 'report' ? '定时质量报告' : '执行任务'))!
const field = (w: VueWrapper, kind: Kind) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === (kind === 'report' ? '计划名称' : '任务名称'))!.findComponent(Input)
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
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

it('TC-P2-D22-07: stale update keeps draft and original version without an automatic retry', async () => {
  const q = await setup('report'); q.hook((_url, init) => init?.method === 'PUT' ? Promise.resolve(response({}, 409)) : undefined)
  await open(q.w, 'report', '保留的草稿', true); expect(await save(q.w, 'report')).toBe(false); await flushPromises()
  expect(modal(q.w, 'report').props('visible')).toBe(true); expect(field(q.w, 'report').props('modelValue')).toBe('保留的草稿')
  expect(writes(q)).toHaveLength(1); expect(JSON.parse(writes(q)[0]![1]!.body as string).expectedVersion).toBe(0)
  expect(modal(q.w, 'report').text()).toContain('读取最新版本')
})
it('TC-P2-D22-08: explicit version refresh keeps draft and requires a separate save', async () => {
  const q = await setup('report'); let version = 0
  q.hook((url, init) => init?.method === 'PUT' ? Promise.resolve(response({}, version === 0 ? 409 : 200)) : url.endsWith('/schedules') && !init?.method ? Promise.resolve(response([{ ...schedule, version, name: '其他人修改的最新名称' }])) : undefined)
  await open(q.w, 'report', '我的待核对草稿', true); await save(q.w, 'report'); await flushPromises(); version = 1
  button(q.w, '读取最新版本').vm.$emit('click'); await flushPromises()
  expect(writes(q)).toHaveLength(1); expect(field(q.w, 'report').props('modelValue')).toBe('我的待核对草稿')
  expect(modal(q.w, 'report').text()).toContain('其他人修改的最新名称')
  expect(await save(q.w, 'report')).toBe(true); expect(JSON.parse(writes(q)[1]![1]!.body as string).expectedVersion).toBe(1)
})
