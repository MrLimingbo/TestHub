import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Message } from '@arco-design/web-vue'
import UiAutomationPage from '@/pages/UiAutomationPage.vue'
import { useSessionStore } from '@/stores/session'
const desktop = vi.hoisted(() => ({ local: false, prepare: vi.fn(async () => {}) }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => desktop.local, isAccountDesktop: () => false, ensureLocalBrowser: desktop.prepare, localBrowserStatus: async () => ({}), cancelBrowserPreparation: vi.fn(), openUiAutomationSync: vi.fn(), openUiAutomationHistorySync: vi.fn(), openUiAutomationArtifactSync: vi.fn(), openLocalExecutionSettings: vi.fn() }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); desktop.local = false; desktop.prepare.mockClear(); localStorage.clear() })
const profile = { id: 'p', name: 'QA', browser: 'chromium', baseUrl: 'https://ui.example.test', viewportWidth: 800, viewportHeight: 600, enabled: true, version: 1 }
const scenario = { id: 's', name: 'Scene', browserProfileId: 'p', description: '', failureStrategy: 'STOP', variables: {}, steps: [{ id: 'wait', type: 'WAIT', name: 'Wait', timeoutMillis: 1000 }, { id: 'fill', type: 'FILL', name: 'Fill', selector: '#input', value: '001', timeoutMillis: 1000 }], version: 1 }
async function setup(kind: 'profile' | 'scenario' | 'execute') {
  for (const k of ['success', 'error'] as const) vi.spyOn(Message, k).mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(); useSessionStore(pinia).projectId = 'project'
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const data = ['POST', 'PUT'].includes(init?.method ?? '') ? { id: 'saved', ...JSON.parse(String(init!.body)) }
      : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' } : url.endsWith('/context') ? { organizations: [{ id: 'o' }], projects: { o: [{ id: 'project', name: 'QA', code: 'QA' }] } }
        : url.endsWith('/browser-profiles') ? [profile] : url.endsWith('/scenarios') ? [scenario] : url.endsWith('/runner-status') ? [{ runnerId: 'r', trusted: true, executionEnabled: true }] : []
    return new Response(JSON.stringify({ data }))
  }); vi.stubGlobal('fetch', fetcher)
  const w = shallowMount(UiAutomationPage, { global: { plugins: [pinia, ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  const name = kind === 'profile' ? '新建浏览器项目' : kind === 'scenario' ? '编辑编排' : '执行场景'
  w.findAllComponents({ name: 'Button' }).find(b => b.text().includes(name))!.vm.$emit('click'); await flushPromises()
  const d = w.findAllComponents({ name: 'Modal' }).find(m => String(m.props('title')).includes(kind === 'profile' ? '浏览器项目' : kind === 'scenario' ? 'Web UI 场景' : '执行 Web UI 场景') && (kind !== 'scenario' || !String(m.props('title')).includes('执行')))!
  if (kind === 'profile') { const f = d.findAllComponents({ name: 'FormItem' }); f.find(i => i.props('label') === '配置名称')!.findComponent({ name: 'Input' }).vm.$emit('update:modelValue', 'QA'); f.find(i => i.props('label') === '基准 URL')!.findComponent({ name: 'Input' }).vm.$emit('update:modelValue', 'https://ui.example.test'); await flushPromises() }
  return { w, d, fetcher }
}
async function number(q: Awaited<ReturnType<typeof setup>>, label: string, raw: string) {
  const item = q.d.findAllComponents({ name: 'FormItem' }).find(i => i.props('label') === label)!, old = item.findComponent({ name: 'InputNumber' })
  if (old.exists()) old.vm.$emit('update:modelValue', raw ? Number(raw) : undefined)
  else item.findComponent({ name: 'Input' }).vm.$emit('update:modelValue', raw)
  await flushPromises()
}
async function save(q: Awaited<ReturnType<typeof setup>>) { await ((q.d.props() as Record<string, unknown>).onBeforeOk as () => Promise<boolean>)(); await flushPromises() }
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, i]) => ['POST', 'PUT'].includes(i?.method ?? ''))
it('TC-D16WEB-46: profile invalid raw values never submit', async () => { const q = await setup('profile'); for (const value of ['800.5', '800.0000000000000001', '3841']) { await number(q, '视口宽度', value); await save(q); expect(writes(q)).toHaveLength(0); expect(q.d.props('visible')).toBe(true) } })
it('TC-D16WEB-47: profile exact spellings send numeric dimensions', async () => { const q = await setup('profile'); await number(q, '视口宽度', '8e2'); await number(q, '视口高度', '600.0'); await save(q); const body = JSON.parse(String(writes(q)[0]![1]!.body)); expect(body.viewportWidth).toBe(800); expect(body.viewportHeight).toBe(600) })
it('TC-D16WEB-48: WAIT and action timeouts validate original text', async () => { const q = await setup('scenario'); await number(q, '等待毫秒', '1000.5'); await save(q); expect(writes(q)).toHaveLength(0); await number(q, '等待毫秒', '1e3'); await number(q, '步骤超时（毫秒）', '99'); await save(q); expect(writes(q)).toHaveLength(0); await number(q, '步骤超时（毫秒）', '100.0'); await save(q); const steps = JSON.parse(String(writes(q)[0]![1]!.body)).steps; expect(steps[0].timeoutMillis).toBe(1000); expect(steps[1].timeoutMillis).toBe(100) })
it('TC-D16WEB-49: invalid execution stops before local preparation', async () => { desktop.local = true; const q = await setup('execute'); await number(q, '执行超时（秒）', '300.5'); await save(q); expect(writes(q)).toHaveLength(0); expect(desktop.prepare).not.toHaveBeenCalled(); expect(Message.error).toHaveBeenCalledWith(expect.stringContaining('timeoutSeconds')) })
it('TC-D16WEB-50: optional action blank and required WAIT stay distinct', async () => { const q = await setup('scenario'); await number(q, '等待毫秒', ''); await save(q); expect(writes(q)).toHaveLength(0); await number(q, '等待毫秒', '50'); await number(q, '步骤超时（毫秒）', ''); const type = q.d.findAll('.step-form')[1]!.findComponent({ name: 'Select' }); type.vm.$emit('update:modelValue', 'WAIT'); await flushPromises(); await save(q); expect(writes(q)).toHaveLength(0); type.vm.$emit('update:modelValue', 'FILL'); await flushPromises(); await save(q); const steps = JSON.parse(String(writes(q)[0]![1]!.body)).steps; expect(steps[0].timeoutMillis).toBe(50); expect(steps[1].timeoutMillis).toBeUndefined() })
it('TC-D16WEB-51: variable guard and removed step input ownership remain', async () => { const q = await setup('scenario'); const vars = q.d.findAllComponents({ name: 'FormItem' }).find(i => i.props('label') === '变量（JSON 对象）')!.findComponent({ name: 'Textarea' }); vars.vm.$emit('update:modelValue', '{"id":1}'); await flushPromises(); await save(q); expect(writes(q)).toHaveLength(0); vars.vm.$emit('update:modelValue', '{}'); await number(q, '等待毫秒', '1.5'); q.d.findAll('.step-form')[0]!.findComponent({ name: 'Button' }).vm.$emit('click'); await flushPromises(); await number(q, '步骤超时（毫秒）', '100.0'); await save(q); expect(JSON.parse(String(writes(q)[0]![1]!.body)).steps[0].timeoutMillis).toBe(100) })
