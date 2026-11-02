import { createPinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DashboardPage from '@/pages/DashboardPage.vue'
import { useSessionStore } from '@/stores/session'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => {
  wrappers.forEach(wrapper => wrapper.unmount())
  wrappers.length = 0
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
})
const response = (data: unknown) => new Response(JSON.stringify({ data }))
const failure = () => new Response(JSON.stringify({ message: '过期请求失败' }), { status: 503 })
const context = { organizations: [{ id: 'o1', name: '组织' }], projects: { o1: [{ id: 'p1', organizationId: 'o1', name: '项目', code: 'QA' }] } }
const dashboard = (days: number) => ({
  periodDays: days, generatedAt: '', activities: [], trend: [], distribution: [],
  summary: { functionalCaseCount: days, apiDefinitionCount: 0, automationScenarioCount: 0, openDefectCount: 0, criticalDefectCount: 0, completedExecutions: 2, passedExecutions: 2, failedExecutions: 0, passRatePercent: days === 7 ? 100 : 50, healthStatus: 'HEALTHY' },
})
function deferred() {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>(release => { resolve = release })
  return { promise, resolve }
}
async function setup(override: (url: string) => Promise<Response> | undefined) {
  const pinia = createPinia()
  useSessionStore(pinia).projectId = 'p1'
  vi.spyOn(Message, 'error').mockImplementation(() => ({} as ReturnType<typeof Message.error>))
  const fetchMock = vi.fn((url: string) => override(url) ?? Promise.resolve(response(url.endsWith('/context') ? context : dashboard(url.endsWith('days=30') ? 30 : 7))))
  vi.stubGlobal('fetch', fetchMock)
  const wrapper = mount(DashboardPage, { global: { plugins: [pinia, ArcoVue], stubs: { DashboardTrendChart: true, 'router-link': true }, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, fetchMock }
}
async function period(wrapper: ReturnType<typeof mount>, days: number) {
  wrapper.findComponent({ name: 'RadioGroup' }).vm.$emit('update:modelValue', days)
  await flushPromises()
}

describe('Dashboard current query', () => {
  for (const outcome of ['success', 'failure']) {
    it(`ignores a stale ${outcome} after the selected period has loaded`, async () => {
      const old = deferred()
      const { wrapper } = await setup(url => url.endsWith('days=30') ? old.promise : undefined)
      await period(wrapper, 30)
      await period(wrapper, 7)
      old.resolve(outcome === 'success' ? response(dashboard(30)) : failure())
      await flushPromises()
      expect(wrapper.get('.quality-score strong').text()).toBe('100%')
      expect(wrapper.findAll('.metric-card')).toHaveLength(4)
      expect(wrapper.get('.metric-card > strong').text()).toBe('7')
      expect(Message.error).not.toHaveBeenCalled()
    })

    it(`keeps the current request loading when a stale ${outcome} completes`, async () => {
      const old = deferred(), current = deferred()
      let holdCurrent = false
      const { wrapper } = await setup(url => url.endsWith('days=30') ? old.promise : holdCurrent && url.endsWith('days=7') ? current.promise : undefined)
      await period(wrapper, 30)
      holdCurrent = true
      await period(wrapper, 7)
      old.resolve(outcome === 'success' ? response(dashboard(30)) : failure())
      await flushPromises()
      expect(wrapper.findComponent({ name: 'Spin' }).props('loading')).toBe(true)
      expect(Message.error).not.toHaveBeenCalled()
      current.resolve(response(dashboard(7)))
      await flushPromises()
      expect(wrapper.findComponent({ name: 'Spin' }).props('loading')).toBe(false)
      expect(wrapper.get('.quality-score strong').text()).toBe('100%')
    })
  }

  it('ignores a dashboard failure after leaving the page', async () => {
    const pending = deferred()
    const { wrapper } = await setup(url => url.endsWith('days=30') ? pending.promise : undefined)
    await period(wrapper, 30)
    wrapper.unmount()
    pending.resolve(failure())
    await flushPromises()
    expect(Message.error).not.toHaveBeenCalled()
  })

  it('does not start a dashboard request after its context resolves on an unmounted page', async () => {
    const pending = deferred()
    const { wrapper, fetchMock } = await setup(url => url.endsWith('/context') ? pending.promise : undefined)
    wrapper.unmount()
    pending.resolve(response(context))
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(Message.error).not.toHaveBeenCalled()
  })
})
