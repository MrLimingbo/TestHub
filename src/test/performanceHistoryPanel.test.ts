import { afterEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { flushPromises } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import PerformanceHistoryPanel from '@/components/PerformanceHistoryPanel.vue'
vi.mock('@/components/PerformanceExecutionChart.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/PerformanceResourceChart.vue', () => ({ default: { template: '<div />' } }))
const wrappers: ReturnType<typeof mount>[] = []
const source = 'A'.repeat(26), execution = 'B'.repeat(26), key = source + '.' + execution
const entry = { key, revision: 'c'.repeat(64), nativeRecord: false, planName: '来源场景', status: 'SUCCEEDED', requestedAt: '2026-10-13T01:00:00Z', finishedAt: '2026-10-13T01:00:01Z' }
const item = { key, revision: entry.revision, sha256: 'd'.repeat(64), report: {
  planName: entry.planName, planVersion: 7, status: entry.status, statusCode: 'EXECUTION_SUCCEEDED', requestedAt: entry.requestedAt, deadlineAt: '2026-10-13T01:01:00Z', startedAt: entry.requestedAt, finishedAt: entry.finishedAt,
    summary: { sampleCount: 0, successCount: 0, errorCount: 0, errorRatePercent: 0, peakThroughputPerSecond: 0, averageLatencyMillis: 0, p95LatencyMillis: 0, minLatencyMillis: 0, maxLatencyMillis: 0, peakActiveThreads: 0, firstSampleAt: null, lastSampleAt: null },
    quality: { status: 'NOT_CONFIGURED', configuredChecks: 0, passedChecks: 0, failedChecks: 0, inconclusiveChecks: 0, threadGroups: [] }, metrics: [], resources: []
} }
function response(data: unknown) { return { ok: true, json: async () => ({ data }) } }
async function setup(projectId = 'p1') {
  const wrapper = mount(PerformanceHistoryPanel, { props: { projectId }, attachTo: document.body, global: { plugins: [ArcoVue] } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); document.body.innerHTML = '' })
it('shows archived origins without requiring a current plan and excludes duplicate native records', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => response({ entries: [entry, { ...entry, key: 'other', nativeRecord: true, planName: '本端真实执行' }] })))
  const wrapper = await setup()
  expect(wrapper.text()).toContain('来源场景'); expect(wrapper.text()).not.toContain('本端真实执行')
  expect(wrapper.emitted('count')?.at(-1)).toEqual([1]); expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('opens a read-only report with original version and a scoped export link', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response(url.endsWith('/history') ? { entries: [entry] } : item)))
  const wrapper = await setup(); await wrapper.findAll('button').find(b => b.text() === '查看历史')!.trigger('click'); await flushPromises()
  expect(document.body.textContent).toContain('v7')
  expect(document.querySelector('a[download]')?.getAttribute('href')).toBe(`/api/v1/projects/p1/performance/history/${key}/export`)
  expect(document.body.textContent).not.toContain('取消任务'); expect(document.body.textContent).toContain('报告只读')
})
it('late inventory from the previous project does not replace the selected project', async () => {
  let finish!: (v: unknown) => void
  vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('/p1/') ? new Promise(resolve => { finish = resolve }) : Promise.resolve(response({ entries: [{ ...entry, planName: '新项目历史' }] }))))
  const wrapper = await setup(); await wrapper.setProps({ projectId: 'p2' }); await flushPromises()
  finish(response({ entries: [entry] })); await flushPromises()
  expect(wrapper.text()).toContain('新项目历史'); expect(wrapper.text()).not.toContain('来源场景')
})
it('project change closes the old history drawer and discards a late detail response', async () => {
  let finish!: (v: unknown) => void
  vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/history') ? Promise.resolve(response({ entries: [entry] })) : new Promise(resolve => { finish = resolve })))
  const wrapper = await setup(); await wrapper.findAll('button').find(b => b.text() === '查看历史')!.trigger('click')
  await wrapper.setProps({ projectId: 'p2' }); await flushPromises(); finish(response(item)); await flushPromises()
  expect(document.querySelector('a[download]')).toBeNull()
})
it('shows an actionable load failure and clears stale records without writes', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, json: async () => ({ message: '历史范围超过 1000 条' }) })))
  const wrapper = await setup(); expect(wrapper.text()).toContain('历史范围超过 1000 条'); expect(wrapper.emitted('count')?.at(-1)).toEqual([0])
})
