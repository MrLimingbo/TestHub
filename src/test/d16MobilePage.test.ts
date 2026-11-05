import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Message, Modal, Input } from '@arco-design/web-vue'
import MobileAutomationPage from '@/pages/MobileAutomationPage.vue'
import PageHeader from '@/components/PageHeader.vue'

const native = vi.hoisted(() => ({ local: false, prepare: vi.fn() }))
vi.mock('@/utils/desktopWorkspace', () => ({ isLocalDesktop: () => native.local, isAccountDesktop: () => false, ensureLocalAppium: native.prepare, openMobileAutomationSync: vi.fn(), openMobileAutomationHistorySync: vi.fn(), openMobileAutomationArtifactSync: vi.fn(), openMobileApplicationFileSync: vi.fn(), openLocalExecutionSettings: vi.fn() }))
const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); state.projectId = 'p1'; native.local = false; native.prepare.mockReset() })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status < 400 ? { data } : { message: '受控请求失败' }), { status, headers: { 'Content-Type': 'application/json' } })
const device = { id: 'd', name: '设备A', platform: 'ANDROID', udid: 'qa-owned', automationName: 'UiAutomator2', capabilities: {}, enabled: true, version: 1 }
const app = { id: 'a', name: '应用A', platform: 'ANDROID', appIdentifier: 'io.testhub.qa', versionName: '1.0', appFileId: null, enabled: true, version: 1 }
const element = { id: 'o', name: '元素A', platform: 'ANDROID', locatorStrategy: 'ACCESSIBILITY_ID', locatorValue: 'qa-name', description: '', updatedAt: '2026-10-27T00:00:00Z' }
const scenario = { id: 's', name: '场景A', deviceId: 'd', applicationId: 'a', description: '', failureStrategy: 'STOP', variables: {}, steps: [{ id: 'launch', type: 'LAUNCH', name: '启动', timeoutMillis: 1000 }], version: 1 }
type Kind = 'device' | 'app' | 'element' | 'scenario' | 'execute'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const names = { device: '设备名称', app: '应用名称', element: '元素名称', scenario: '场景名称' }
const titles = { device: ['新建设备', '编辑设备'], app: ['新建移动应用', '编辑移动应用'], element: ['新建元素对象', '编辑元素对象'], scenario: ['新建移动端场景', '编辑移动端场景'], execute: ['执行移动端场景'] }
const modal = (w: VueWrapper, kind: Kind) => w.findAllComponents(Modal).find(c => titles[kind].includes(String(c.props('title'))))!
const field = (w: VueWrapper, kind: Exclude<Kind, 'execute'>) => modal(w, kind).findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === names[kind])!.findComponent(Input)
const save = (w: VueWrapper, kind: Kind) => (modal(w, kind).props('onBeforeOk') as () => Promise<boolean>)()
async function open(w: VueWrapper, kind: Kind, name = '草稿A', edit = false) {
  if (kind === 'scenario') {
    if (edit) w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('编辑编排'))!.vm.$emit('click')
    else w.findComponent(PageHeader).vm.$emit('action')
  } else if (kind === 'execute') w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('执行场景'))!.vm.$emit('click')
  else {
    const tab = { device: '设备', app: '应用包', element: '元素对象' }[kind]
    await w.findAll('.arco-tabs-tab').find(c => c.text() === tab)!.trigger('click'); await flushPromises()
    if (edit) w.findAllComponents({ name: 'Button' }).filter(c => c.text() === '编辑' && c.isVisible())[0]!.vm.$emit('click')
    else w.findAllComponents({ name: 'Button' }).find(c => c.text() === { device: '新建设备', app: '新建应用', element: '新建元素' }[kind])!.vm.$emit('click')
  }
  await flushPromises(); if (kind !== 'execute') { field(w, kind).vm.$emit('update:modelValue', name); await flushPromises() }
}
async function setup(initial: Hook = () => undefined) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  let hook = initial
  const fetcher = vi.fn((url: string, init?: RequestInit) => hook(url, init) ?? Promise.resolve(response(
    url.endsWith('/context') ? { organizations: [{ id: 'org' }], projects: { org: ['p1', 'p2'].map(id => ({ id, organizationId: 'org', name: id, code: id })) } }
      : url.endsWith('/csrf') ? { token: 'fixture', headerName: 'X-CSRF' }
        : init?.method === 'POST' || init?.method === 'PUT' ? { ...scenario, id: 'saved' }
          : url.endsWith('/mobile-automation') ? { devices: [device], applications: [app], elements: [element], scenarios: [scenario], executions: [] } : [])))
  vi.stubGlobal('fetch', fetcher)
  const w = mount(MobileAutomationPage, { global: { plugins: [ArcoVue], stubs: { Modal: true, Drawer: true, MobileAutomationHistoryPanel: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises(); return { w, fetcher, hook: (value: Hook) => { hook = value } }
}
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? ''))
function input(q: Awaited<ReturnType<typeof setup>>, kind: Kind, label: string, value: string) {
  const item = modal(q.w, kind).findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === label)!
  item.findComponent(Input).vm.$emit('update:modelValue', value)
}
it('MF-01/02: 原始步骤超时精确判断，非法值阻止写入', async () => {
  const q = await setup(); await open(q.w, 'scenario', '场景A', true)
  for (const value of ['0', '60001', '1000.5', '1000.0000000000000001', '1e10000', 'NaN']) {
    input(q, 'scenario', '步骤超时（毫秒）', value); await flushPromises(); await save(q.w, 'scenario'); expect(writes(q)).toHaveLength(0)
  }
  input(q, 'scenario', '步骤超时（毫秒）', '1e3'); await flushPromises(); await save(q.w, 'scenario')
  expect(JSON.parse(String(writes(q)[0]![1]!.body)).scenario.steps[0].timeoutMillis).toBe(1000)
})
it('MF-03/06/07: 空步骤超时保留默认，变量保持字符串且类型错误拒绝', async () => {
  const q = await setup(); await open(q.w, 'scenario', '场景A', true)
  input(q, 'scenario', '步骤超时（毫秒）', '')
  const vars = modal(q.w, 'scenario').findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === '变量（JSON 对象）')!.findComponent({ name: 'Textarea' })
  for (const value of ['null', '1', 'true', '[]', '{}']) {
    vars.vm.$emit('update:modelValue', `{"v":${value}}`); await flushPromises(); await save(q.w, 'scenario'); expect(writes(q)).toHaveLength(0)
  }
  vars.vm.$emit('update:modelValue', '{"v":"001","empty":"","template":"{{id}}"}'); await flushPromises(); await save(q.w, 'scenario')
  const body = JSON.parse(String(writes(q)[0]![1]!.body)).scenario
  expect(body.steps[0].timeoutMillis).toBeUndefined(); expect(body.variables).toEqual({ v: '001', empty: '', template: '{{id}}' })
})
it('MF-04/05: 执行超时在启动 Appium 前验证', async () => {
  native.local = true; native.prepare.mockResolvedValue(undefined); const q = await setup(); await open(q.w, 'execute')
  for (const value of ['9', '3601', '300.5', '300.0000000000000001', 'NaN']) {
    input(q, 'execute', '执行超时（秒）', value); await flushPromises(); await save(q.w, 'execute'); expect(writes(q)).toHaveLength(0); expect(native.prepare).not.toHaveBeenCalled()
  }
  input(q, 'execute', '执行超时（秒）', '3e2'); await flushPromises(); await save(q.w, 'execute')
  expect(JSON.parse(String(writes(q)[0]![1]!.body)).timeoutSeconds).toBe(300); expect(native.prepare).toHaveBeenCalledOnce()
})
it('MF-01/02/04: 端点、等价值以及空执行超时', async () => {
  const { webIntegerInput, webOptionalInteger } = await import('@/utils/webIntegerInput')
  for (const [raw, expected] of [['1', 1], ['60000', 60000], ['1000.0', 1000], ['1e3', 1000]] as const) expect(webIntegerInput(raw, 1, 60000, 'steps[0].timeoutMillis')).toBe(expected)
  for (const raw of ['0', '60001', '1.5', '1.0000000000000001']) expect(() => webIntegerInput(raw, 1, 60000, 'steps[0].timeoutMillis')).toThrow()
  for (const raw of ['10', '3600', '300.0', '3e2']) expect(webIntegerInput(raw, 10, 3600, 'timeoutSeconds')).toBe(Number(raw))
  expect(webOptionalInteger('', 10, 3600, 'timeoutSeconds', 300)).toBe(300)
})
it('MF-08: 多步骤输入独立，删除步骤不影响其他步骤', async () => {
  const q = await setup(); await open(q.w, 'scenario', '场景A', true)
  const heading = modal(q.w, 'scenario').find('.form-heading').findComponent({ name: 'Select' })
  heading.vm.$emit('change', 'WAIT'); await flushPromises()
  const steps = modal(q.w, 'scenario').findAll('.step-form'); expect(steps).toHaveLength(2)
  const stepInput = (index: number) => steps[index]!.findAllComponents({ name: 'FormItem' }).find((c: VueWrapper) => (c.props() as Record<string, unknown>).label === '步骤超时（毫秒）')!.findComponent(Input)
  stepInput(0).vm.$emit('update:modelValue', '1.5'); stepInput(1).vm.$emit('update:modelValue', '60000.0'); await flushPromises()
  steps[0]!.findAllComponents({ name: 'Button' }).find((c: VueWrapper) => c.text() === '删除')!.vm.$emit('click'); await flushPromises(); await save(q.w, 'scenario')
  const saved = JSON.parse(String(writes(q)[0]![1]!.body)).scenario.steps
  expect(saved).toHaveLength(1); expect(saved[0].timeoutMillis).toBe(60000); expect(saved[0].timeoutText).toBeUndefined()
})
