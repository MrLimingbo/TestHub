import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, shallowMount } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import MockSharePage from '@/pages/MockSharePage.vue'

const route = reactive({ params: { shareKey: 'first' } })
vi.mock('vue-router', () => ({ useRoute: () => route }))
let wrapper: ReturnType<typeof shallowMount> | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); route.params.shareKey = 'first' })
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
