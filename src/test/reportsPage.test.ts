import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue, { Modal, Select, Message } from '@arco-design/web-vue'
import ReportsPage from '@/pages/ReportsPage.vue'
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers() })
const summary = { functionalCaseCount: 0, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 0, passedExecutions: 0, failedExecutions: 0, passRatePercent: null }
const report = (name = '最新项目') => ({ from: '2026-10-01', to: '2026-10-04', generatedAt: '', projectCount: 1, summary, trend: [], projects: [{ projectId: 'p1', projectName: name, projectCode: 'QA', ...summary, healthStatus: 'NO_EXECUTIONS' }] })
const schedule = { id: 's1', name: '周报', projectIds: ['p1'], cronExpression: '0 0 9 ? * MON', timezone: 'UTC', periodDays: 7, enabled: false, lastRunAt: null, lastRunStatus: null }
async function setup(override?: (url: string, init?: RequestInit) => unknown) {
  vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
    const custom = await override?.(input, init)
    if (custom && typeof custom === 'object' && 'ok' in custom) return custom
    const data = custom ?? (input.endsWith('/context') ? { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1', name: '项目一' }, { id: 'p2', name: '项目二' }] } } : input.endsWith('/csrf') ? { token: 'test', headerName: 'X-CSRF' } : input.endsWith('/schedules') ? [schedule] : report())
    return { ok: true, json: async () => ({ data }) }
  }))
  const wrapper = mount(ReportsPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, DashboardTrendChart: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
const button = (w: ReturnType<typeof mount>, text: string) => w.findAllComponents({ name: 'Button' }).find(b => b.text() === text)!
describe('Reports page', () => {
  it('keeps failed schedule edits open with entered content', async () => {
    const wrapper = await setup((_, init) => init?.method === 'PUT' ? { ok: false, json: async () => ({ message: '计划名称冲突' }) } : undefined)
    button(wrapper, '编辑').vm.$emit('click'); await flushPromises()
    const modal = wrapper.findAllComponents(Modal).find(m => m.props('title') === '编辑定时质量报告')!
    expect(await modal.props('onBeforeOk')!(vi.fn())).toBe(false); expect(modal.props('visible')).toBe(true)
    expect(wrapper.text()).toContain('周报')
    const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(String(call[1]!.body))).toMatchObject({ name: '周报', projectIds: ['p1'], enabled: false })
  })
  it('ignores old report responses after filter changes', async () => {
    let delayed = false, release!: (value: unknown) => void
    const wrapper = await setup(url => delayed && url.includes('/quality?') && url.includes('projectIds=p2') ? new Promise(resolve => { release = resolve }) : undefined)
    delayed = true; const select = wrapper.findAllComponents(Select)[0]!
    select.vm.$emit('update:modelValue', ['p2']); await flushPromises(); select.vm.$emit('update:modelValue', ['p1']); await flushPromises()
    release(report('过期项目')); await flushPromises(); expect(wrapper.text()).not.toContain('过期项目'); expect(wrapper.text()).toContain('最新项目')
  })
  it('invalid dates invalidate an earlier request and display an actionable error', async () => {
    let delayed = false, release!: (value: unknown) => void
    const wrapper = await setup(url => delayed && url.includes('/quality?') ? new Promise(resolve => { release = resolve }) : undefined)
    delayed = true; await wrapper.get('input[aria-label="开始日期"]').setValue('2026-10-01'); await flushPromises()
    await wrapper.get('input[aria-label="结束日期"]').setValue('2026-09-01'); await flushPromises()
    release(report('过期项目')); await flushPromises(); expect(wrapper.text()).not.toContain('过期项目'); expect(wrapper.text()).toContain('开始日期不能晚于结束日期')
  })
  it('closing run records discards a delayed response', async () => {
    let release!: (value: unknown) => void
    const wrapper = await setup(url => url.endsWith('/runs') ? new Promise(resolve => { release = resolve }) : undefined)
    button(wrapper, '记录').vm.$emit('click'); await flushPromises()
    const modal = wrapper.findAllComponents(Modal).find(m => String(m.props('title')).includes('运行记录'))!
    expect(modal.props('visible')).toBe(true); modal.vm.$emit('cancel'); await flushPromises()
    release([{ id: 'old', status: 'FAILED', errorMessage: '过期运行记录', startedAt: '2026-10-04T00:00:00Z', from: '2026-10-01', to: '2026-10-04' }]); await flushPromises()
    expect(modal.props('visible')).toBe(false); expect(wrapper.text()).not.toContain('过期运行记录')
  })
  it('does not offer running disabled schedules', async () => {
    const wrapper = await setup(); expect(button(wrapper, '立即运行').props('disabled')).toBe(true)
  })
  it('uses Shanghai reporting dates around UTC midnight', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-03T18:00:00Z'))
    const wrapper = await setup(); expect((wrapper.get('input[aria-label="结束日期"]').element as HTMLInputElement).value).toBe('2026-10-04')
  })
  it('exports the clicked date range and keeps its filename when filters change during download', async () => {
    let release!: (value: unknown) => void, downloaded = ''
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:qa')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { downloaded = this.download })
    const wrapper = await setup(url => url.includes('/quality/export?') ? new Promise(resolve => { release = resolve }) : undefined)
    const from = (wrapper.get('input[aria-label="开始日期"]').element as HTMLInputElement).value
    const to = (wrapper.get('input[aria-label="结束日期"]').element as HTMLInputElement).value
    wrapper.findComponent({ name: 'PageHeader' }).vm.$emit('action'); await flushPromises()
    await wrapper.get('input[aria-label="开始日期"]').setValue('2026-10-01'); await flushPromises()
    release({ ok: true, blob: async () => new Blob(['QA CSV']) }); await flushPromises()
    expect(downloaded).toBe(`testhub-quality-report-${from}-to-${to}.csv`)
    const call = vi.mocked(fetch).mock.calls.find(([url]) => String(url).includes('/quality/export?'))!
    expect(String(call[0])).toContain(`from=${from}&to=${to}`)
  })
  it('surfaces a failed report instead of retaining old statistics', async () => {
    let fail = false; vi.spyOn(Message, 'error').mockImplementation(() => ({} as ReturnType<typeof Message.error>))
    const wrapper = await setup(url => fail && url.includes('/quality?') ? { ok: false, json: async () => ({ message: '报表服务暂不可用' }) } : undefined)
    fail = true; button(wrapper, '刷新').vm.$emit('click'); await flushPromises()
    expect(wrapper.text()).toContain('报表服务暂不可用'); expect(wrapper.text()).not.toContain('最新项目')
  })
})
