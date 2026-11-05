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

async function setNumber(q: Fixture, kind: Kind, raw: string) {
  const label = kind === 'task' ? '进度' : '统计周期（天）'
  const item = modal(q.w, kind).findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === label)!
  const number = item.findComponent({ name: 'InputNumber' })
  if (number.exists()) number.vm.$emit('update:modelValue', raw === '' ? undefined : Number(raw))
  else item.findComponent(Input).vm.$emit('update:modelValue', raw)
  await flushPromises()
}
it('QF-02/07: 任务进度空、小数和越界不能提交', async () => {
  const q = await setup('task'); await open(q.w, 'task', 'QA任务', true)
  for (const raw of ['', '-1', '101', '1.5', '10.0000000000000001', '1e10000', 'NaN']) {
    await setNumber(q, 'task', raw); await save(q.w, 'task'); expect(writes(q)).toHaveLength(0)
  }
})
it('QF-05: 报告周期空、小数和越界不能提交', async () => {
  const q = await setup('report'); await open(q.w, 'report', 'QA周报', true)
  for (const raw of ['', '0', '91', '1.5', '7.0000000000000001', '1e10000', 'NaN']) {
    await setNumber(q, 'report', raw); await save(q.w, 'report'); expect(writes(q)).toHaveLength(0)
  }
})
it('QF-01/06: 等值整数与原版本一起提交', async () => {
  const q = await setup('task'); await open(q.w, 'task', 'QA任务', true); await setNumber(q, 'task', '1e1'); await save(q.w, 'task')
  expect(JSON.parse(String(writes(q)[0]![1]!.body))).toMatchObject({ progress: 10, expectedVersion: 1 })
})
it('QF-04/06: 报告周期等值整数与原版本一起提交', async () => {
  const q = await setup('report'); await open(q.w, 'report', 'QA周报', true); await setNumber(q, 'report', '7.0'); await save(q.w, 'report')
  expect(JSON.parse(String(writes(q)[0]![1]!.body))).toMatchObject({ periodDays: 7, expectedVersion: 0 })
})
it('QF-01/04: 上下界与等值整数均可提交', async () => {
  const { webIntegerInput } = await import('@/utils/webIntegerInput')
  for (const raw of ['0', '100', '10.0', '1e1']) expect(webIntegerInput(raw, 0, 100, 'progress')).toBe(Number(raw))
  for (const raw of ['1', '90', '7.0', '7e0']) expect(webIntegerInput(raw, 1, 90, 'periodDays')).toBe(Number(raw))
})
it('QF-03: 创建任务不新增进度字段', async () => {
  const q = await setup('task'); await open(q.w, 'task'); await save(q.w, 'task')
  expect(JSON.parse(String(writes(q)[0]![1]!.body))).not.toHaveProperty('progress')
})
for (const status of ['QUEUED', 'SUCCEEDED']) it(`QF-07: ${status} 状态下非法进度仍阻止提交`, async () => {
  const q = await setup('task'); await open(q.w, 'task', 'QA任务', true)
  const statusField = modal(q.w, 'task').findAllComponents({ name: 'FormItem' }).find(f => f.props('label') === '状态')!
  statusField.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', status)
  await setNumber(q, 'task', '1.5'); await save(q.w, 'task'); expect(writes(q)).toHaveLength(0)
})
it('QF-05: 创建报告非法周期保持草稿且不提交', async () => {
  const q = await setup('report'); await open(q.w, 'report', '新周报')
  await setNumber(q, 'report', '7.0000000000000001'); await save(q.w, 'report')
  expect(writes(q)).toHaveLength(0); expect(modal(q.w, 'report').props('visible')).toBe(true); expect(field(q.w, 'report').props('modelValue')).toBe('新周报')
})
