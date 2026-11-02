import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import MockSharePage from '@/pages/MockSharePage.vue'

const route = reactive({ params: { shareKey: 'first' } })
vi.mock('vue-router', () => ({ useRoute: () => route }))
let wrapper: ReturnType<typeof shallowMount> | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); vi.restoreAllMocks(); route.params.shareKey = 'first' })
const document = (name: string) => ({ shareName: name, serviceName: name, publicKey: name, serviceEnabled: true, smartExampleEnabled: false, expiresAt: null, rules: [] })
const mount = () => {
  wrapper = shallowMount(MockSharePage, { global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  return wrapper
}

describe('Public Mock share navigation', () => {
  it('keeps the current document when an earlier link returns late', async () => {
    let resolveFirst!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/first')
      ? new Promise(resolve => { resolveFirst = resolve })
      : Promise.resolve({ ok: true, json: async () => ({ data: document('Current') }) })))
    const page = mount()
    route.params.shareKey = 'second'; await flushPromises()
    expect(page.find('h2').text()).toBe('Current')
    resolveFirst({ ok: true, json: async () => ({ data: document('Old') }) }); await flushPromises()
    expect(page.find('h2').text()).toBe('Current')
  })
  it('keeps an invalid current link unavailable when an older valid link returns', async () => {
    let resolveFirst!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn((url: string) => url.endsWith('/first')
      ? new Promise(resolve => { resolveFirst = resolve })
      : Promise.resolve({ ok: false, json: async () => ({ message: 'Expired' }) })))
    const page = mount()
    route.params.shareKey = 'second'; await flushPromises()
    resolveFirst({ ok: true, json: async () => ({ data: document('Old') }) }); await flushPromises()
    expect(page.find('h2').exists()).toBe(false)
    expect(page.findComponent({ name: 'Result' }).props('subtitle')).toBe('Expired')
  })
})

async function clipboardPage(clipboard: unknown) {
  vi.stubGlobal('navigator', new Proxy(navigator, { get: (target, key) => key === 'clipboard' ? clipboard : Reflect.get(target, key, target) }))
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ data: document('QA') }) })))
  const page = mount(); await flushPromises(); return page
}
const copy = (page: ReturnType<typeof mount>) => page.findComponent({ name: 'Button' }).vm.$emit('click')
it('[DF-M01][POS][OBS-09-E012] copies the complete runtime URL through an isolated clipboard double', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined), page = await clipboardPage({ writeText })
  copy(page); await flushPromises()
  expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/api/v1/mock/QA`)
  expect(page.get('[role="status"]').text()).toContain('已复制')
  expect((page.get('input[readonly]').element as HTMLInputElement).value).toBe(`${window.location.origin}/api/v1/mock/QA`)
})
for (const kind of ['rejected', 'missing', 'throws'] as const) it(`[DF-M0${{ rejected: 2, missing: 3, throws: 4 }[kind]}][NEG][OBS-09-E012] ${kind} clipboard gives manual-copy feedback without false success`, async () => {
  const clipboard = kind === 'missing' ? undefined : { writeText: kind === 'throws' ? () => { throw new Error('Denied') } : vi.fn().mockRejectedValue(new Error('Denied')) }
  const page = await clipboardPage(clipboard); copy(page); await flushPromises()
  expect(page.get('[role="alert"]').text()).toContain('手动复制'); expect(page.text()).not.toContain('已复制')
  expect(page.find('input[readonly]').exists()).toBe(true)
})
it('[DF-M05][POS][OBS-09-E012] a rejected copy can be retried successfully', async () => {
  const writeText = vi.fn().mockRejectedValueOnce(new Error('Denied')).mockResolvedValue(undefined), page = await clipboardPage({ writeText })
  copy(page); await flushPromises(); copy(page); await flushPromises()
  expect(page.find('[role="alert"]').exists()).toBe(false); expect(page.get('[role="status"]').text()).toContain('已复制')
})
it('[DF-M06][NEG][OBS-09-E012] copy completion for a previous share cannot label a new share as copied', async () => {
  let finish!: () => void
  const page = await clipboardPage({ writeText: () => new Promise<void>(resolve => { finish = resolve }) })
  copy(page); await flushPromises(); route.params.shareKey = 'second'; await flushPromises(); finish(); await flushPromises()
  expect(page.find('[role="status"]').exists()).toBe(false); expect(page.find('[role="alert"]').exists()).toBe(false)
})
