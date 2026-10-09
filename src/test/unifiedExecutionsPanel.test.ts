import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue, { Button, InputSearch, Select, Table } from '@arco-design/web-vue'
import UnifiedExecutionsPanel from '@/components/UnifiedExecutionsPanel.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
const record = (module = 'API', status = 'SUCCEEDED', name = '接口场景', projectId = 'p1') => ({
  id: 'same-id', projectId, module, status, name, createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z',
  startedAt: null, finishedAt: null, totalCount: null, passedCount: null, failedCount: null, blockedCount: null,
})
const response = (data: unknown, ok = true) => ({ ok, json: async () => ok ? { data } : { message: data } }) as Response
async function setup(data: unknown[] = [record()], projectId = 'p1') {
  vi.stubGlobal('fetch', vi.fn(async () => response(data)))
  const wrapper = mount(UnifiedExecutionsPanel, { props: { projectId }, global: { plugins: [ArcoVue], config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
const refresh = (wrapper: ReturnType<typeof mount>) => wrapper.findComponent(Button).vm.$emit('click')
describe('Unified execution list', () => {
  it('shows real modules with composite keys, no hand-written progress or mutation actions', async () => {
    const wrapper = await setup([record(), record('MOBILE', 'CANCELLING', '设备场景')])
    expect(wrapper.findComponent(Table).props('data')?.map(r => r.rowKey)).toEqual(['API:same-id', 'MOBILE:same-id'])
    expect(wrapper.text()).toContain('接口自动化'); expect(wrapper.text()).toContain('移动端'); expect(wrapper.text()).toContain('取消中')
    expect(wrapper.text()).not.toContain('标记成功'); expect(wrapper.text()).not.toContain('100%')
    expect(wrapper.findAll('a').map(a => a.attributes('href'))).toEqual(expect.arrayContaining(['/api-automation', '/mobile-automation']))
    expect(vi.mocked(fetch).mock.calls.every(([, init]) => !init?.method)).toBe(true)
  })
  it('completed functional batches retain failures and are not labelled as successful', async () => {
    const wrapper = await setup([{ ...record('FUNCTIONAL', 'COMPLETED', '回归批次'), totalCount: 4, passedCount: 2, failedCount: 1, blockedCount: 1 }])
    expect(wrapper.text()).toContain('已结束'); expect(wrapper.text()).toContain('2 通过 / 1 失败 / 1 阻塞'); expect(wrapper.text()).not.toContain('执行成功')
  })
  it('filters the explicitly bounded range by module, native state and case-insensitive name', async () => {
    const wrapper = await setup([record('API', 'FAILED', 'Order A'), record('UI', 'SUCCEEDED', 'Order B')])
    const selects = wrapper.findAllComponents(Select)
    selects[0]!.vm.$emit('update:modelValue', 'API'); selects[1]!.vm.$emit('update:modelValue', 'FAILED')
    await wrapper.findComponent(InputSearch).find('input').setValue('order'); await flushPromises()
    expect(wrapper.findComponent(Table).props('data')).toHaveLength(1); expect(wrapper.text()).toContain('最近 100 条')
    expect(wrapper.text()).not.toContain('Order B')
  })
  it('project changes clear filters and ignore a previous project response', async () => {
    const wrapper = await setup(); let release!: (v: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    refresh(wrapper); await flushPromises()
    vi.mocked(fetch).mockResolvedValue(response([record('UI', 'RUNNING', '新项目', 'p2')]) as Response)
    await wrapper.setProps({ projectId: 'p2' }); await flushPromises()
    release(response([record('API', 'FAILED', '旧项目迟到')])); await flushPromises()
    expect(wrapper.text()).toContain('新项目'); expect(wrapper.text()).not.toContain('旧项目迟到')
    expect(String(vi.mocked(fetch).mock.calls.at(-1)![0])).toBe('/api/v1/projects/p2/tasks/executions')
  })
  it('failed refresh clears old successes, displays retryable errors and recovers', async () => {
    const wrapper = await setup()
    vi.mocked(fetch).mockResolvedValueOnce(response('读取失败', false) as Response); refresh(wrapper); await flushPromises()
    expect(wrapper.text()).toContain('读取失败'); expect(wrapper.text()).not.toContain('接口场景')
    refresh(wrapper); await flushPromises(); expect(wrapper.text()).toContain('接口场景'); expect(wrapper.text()).not.toContain('读取失败')
  })
  it('empty project issues no request and removes the previous project records', async () => {
    const wrapper = await setup(); vi.mocked(fetch).mockClear(); await wrapper.setProps({ projectId: '' }); await flushPromises()
    expect(fetch).not.toHaveBeenCalled(); expect(wrapper.findComponent(Button).props('disabled')).toBe(true); expect(wrapper.text()).not.toContain('接口场景')
  })
  it('polls visible pages without overlapping requests and stops on unmount', async () => {
    vi.useFakeTimers(); vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const wrapper = await setup(); let release!: (v: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    await vi.advanceTimersByTimeAsync(5000); expect(fetch).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(10000); expect(fetch).toHaveBeenCalledTimes(2)
    release(response([record('API', 'RUNNING', '实时状态')])); await flushPromises(); expect(wrapper.text()).toContain('实时状态')
    wrapper.unmount(); await vi.advanceTimersByTimeAsync(5000); expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('suspends polling on hidden pages and after failures until explicit retry', async () => {
    vi.useFakeTimers(); const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const wrapper = await setup(); await vi.advanceTimersByTimeAsync(10000); expect(fetch).toHaveBeenCalledTimes(1)
    visibility.mockReturnValue('visible'); vi.mocked(fetch).mockResolvedValueOnce(response('断网', false) as Response)
    await vi.advanceTimersByTimeAsync(5000); await vi.advanceTimersByTimeAsync(10000); expect(fetch).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('断网'); refresh(wrapper); await flushPromises(); expect(wrapper.text()).not.toContain('断网')
  })
  it('late response after unmount has no visible effect', async () => {
    const wrapper = await setup(); let release!: (v: Response) => void
    vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve })); refresh(wrapper); await flushPromises()
    wrapper.unmount(); release(response([record('UI', 'SUCCEEDED', '销毁后返回')])); await flushPromises(); expect(wrapper.exists()).toBe(false)
  })
})
