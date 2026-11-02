import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ArcoVue, { Button, Message, Select, Spin } from '@arco-design/web-vue'
import AuditLogPage from '@/pages/AuditLogPage.vue'

interface Reply { ok: boolean; json: () => Promise<unknown> }
const entry = (action: string) => ({ id: action, userId: null, projectId: null, action, resourceType: 'PROJECT', resourceId: null, outcome: 'SUCCESS', requestId: 'qa', clientIp: null, detail: {}, createdAt: '2026-10-28T00:00:00Z' })
const ok = (...actions: string[]): Reply => ({ ok: true, json: async () => ({ data: actions.map(entry) }) })
const failed: Reply = { ok: false, json: async () => ({ message: '本轮审计服务失败' }) }
function deferred() {
  let resolve!: (value: Reply) => void
  const promise = new Promise<Reply>(r => { resolve = r })
  return { promise, resolve }
}
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers() })
async function setup(replies: Array<Reply | Promise<Reply>>) {
  vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url.endsWith('/audit-logs/actions') ? { ok: true, json: async () => ({ data: ['A', 'B', 'KEPT', 'RECOVERED', 'OLD'] }) } : replies.shift()!)))
  const error = vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const wrapper = mount(AuditLogPage, { global: { plugins: [ArcoVue], config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises()
  return { wrapper, error }
}
async function select(wrapper: ReturnType<typeof mount>, value: string) {
  const s = wrapper.findComponent(Select)
  s.vm.$emit('update:modelValue', value); s.vm.$emit('change', value); await flushPromises()
}
const refresh = (wrapper: ReturnType<typeof mount>) => wrapper.findComponent(Button).vm.$emit('click')

describe('global audit request ordering', () => {
  it('keeps the cleared-filter result when an older filtered response arrives last', async () => {
    const old = deferred(), { wrapper } = await setup([ok('A', 'B'), old.promise, ok('A', 'B')])
    await select(wrapper, 'A'); await select(wrapper, ''); expect(wrapper.text()).toContain('B')
    old.resolve(ok('A')); await flushPromises()
    expect(wrapper.findComponent(Select).props('modelValue')).toBe('')
    expect(wrapper.text()).toContain('B'); expect(wrapper.findComponent(Spin).props('loading')).toBe(false)
  })
  it('ignores an obsolete error after the latest response succeeded', async () => {
    const old = deferred(), { wrapper, error } = await setup([ok('A', 'B'), old.promise, ok('B')])
    await select(wrapper, 'A'); await select(wrapper, ''); old.resolve(failed); await flushPromises()
    expect(wrapper.text()).toContain('B'); expect(error).not.toHaveBeenCalled()
  })
  it.each([true, false])('does not clear a newer loading state when old success=%s finishes', async success => {
    const old = deferred(), current = deferred(), { wrapper, error } = await setup([ok('A'), old.promise, current.promise])
    await select(wrapper, 'A'); await select(wrapper, 'B'); old.resolve(success ? ok('A') : failed); await flushPromises()
    expect(wrapper.findComponent(Spin).props('loading')).toBe(true); expect(error).not.toHaveBeenCalled()
    current.resolve(ok('B')); await flushPromises()
    expect(wrapper.findComponent(Spin).props('loading')).toBe(false); expect(wrapper.text()).toContain('B')
  })
  it('reports the current error and allows a successful refresh', async () => {
    const { wrapper, error } = await setup([failed, ok('RECOVERED')])
    expect(error).toHaveBeenCalledWith('本轮审计服务失败')
    expect(wrapper.findComponent(Spin).props('loading')).toBe(false)
    refresh(wrapper); await flushPromises(); expect(wrapper.text()).toContain('RECOVERED')
  })
  it('ignores a pending error after the page unmounts', async () => {
    const pending = deferred(), { wrapper, error } = await setup([pending.promise])
    wrapper.unmount(); wrappers.length = 0; pending.resolve(failed); await flushPromises()
    expect(error).not.toHaveBeenCalled()
  })
})

it('[DF-A01][POS][OBS-03C-E007] shows the successful load time and clears stale feedback after retry', async () => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-29T01:00:00Z'))
  const { wrapper } = await setup([ok('KEPT'), failed, ok('RECOVERED')])
  const originalTime = wrapper.get('.audit-last-success').text()
  refresh(wrapper); await flushPromises()
  expect(wrapper.text()).toContain('KEPT'); expect(wrapper.get('.audit-load-error').text()).toContain('旧数据')
  expect(wrapper.get('.audit-last-success').text()).toBe(originalTime)
  vi.setSystemTime(new Date('2026-10-29T02:00:00Z'))
  wrapper.get('.audit-load-error').findComponent(Button).vm.$emit('click'); await flushPromises()
  expect(wrapper.text()).toContain('RECOVERED'); expect(wrapper.find('.audit-load-error').exists()).toBe(false)
  expect(wrapper.get('.audit-last-success').text()).not.toBe(originalTime)
})
it('[DF-A02][NEG][OBS-03C-E007] first-load failure does not invent a last successful result', async () => {
  const { wrapper } = await setup([failed])
  expect(wrapper.get('.audit-load-error').text()).toContain('尚无成功加载的数据')
  expect(wrapper.find('.audit-last-success').exists()).toBe(false)
})
it('[DF-A03][NEG][OBS-03C-E007] a successful empty result remains distinguishable from never loaded', async () => {
  const { wrapper } = await setup([ok(), failed]); refresh(wrapper); await flushPromises()
  expect(wrapper.get('.audit-load-error').text()).toContain('旧数据')
  expect(wrapper.get('.audit-last-success').text()).toContain('上次成功')
})
it('[DF-A04][NEG][OBS-03C-E007] a failed filter change identifies the retained result scope', async () => {
  const { wrapper } = await setup([ok('A'), failed]); await select(wrapper, 'A')
  expect(wrapper.get('.audit-load-error').text()).toContain('全部动作')
  expect(wrapper.findComponent(Select).props('modelValue')).toBe('A')
})
for (const success of [true, false]) it(`[DF-A0${success ? 5 : 6}][NEG][OBS-03C-E007] obsolete ${success ? 'success' : 'failure'} cannot replace the current stale state`, async () => {
  const old = deferred(), { wrapper } = await setup([ok('KEPT'), old.promise, failed])
  refresh(wrapper); refresh(wrapper); await flushPromises()
  const before = wrapper.get('.audit-load-error').text()
  old.resolve(success ? ok('OLD') : failed); await flushPromises()
  expect(wrapper.get('.audit-load-error').text()).toBe(before); expect(wrapper.text()).toContain('KEPT')
})
