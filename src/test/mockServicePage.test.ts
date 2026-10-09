import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import ArcoVue, { Modal, Input, Select } from '@arco-design/web-vue'
import MockServicePage from '@/pages/MockServicePage.vue'
import PageHeader from '@/components/PageHeader.vue'
import CodeEditor from '@/components/CodeEditor.vue'

const state = reactive({ projectId: 'p1', selectProject(id: string) { this.projectId = id }, resolveProjectId: () => 'p1' })
vi.mock('@/stores/session', () => ({ useSessionStore: () => state }))
const wrappers: ReturnType<typeof shallowMount>[] = []
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.useRealTimers(); state.projectId = 'p1' })
const server = (id: string) => ({ id, name: id, publicKey: id, projectId: 'p1', enabled: true, smartExampleEnabled: false, ruleCount: 0, createdAt: '2026-09-01T00:00:00Z' })
const share = (id: string, expiresAt: string | null, revokedAt: string | null = null) => ({ id, shareKey: id, name: id, expiresAt, revokedAt, createdAt: '2026-09-01T00:00:00Z' })
const shares = [share('expired', '2020-01-01T00:00:00Z'), share('forever', null), share('revoked', null, '2026-09-01T00:00:00Z')]

type Override = (url: string, method: string) => unknown | Promise<unknown>
async function setup(override?: Override) {
  const writes: Array<{ url: string; body: Record<string, unknown> }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (method === 'POST' || method === 'PUT') writes.push({ url, body: JSON.parse(String(init?.body ?? '{}')) })
    const custom = await override?.(url, method)
    let data: unknown = custom
    if (data === undefined) {
      if (url.endsWith('/access/context')) data = { organizations: [{ id: 'o1' }], projects: { o1: [{ id: 'p1' }, { id: 'p2' }] } }
      else if (url.endsWith('/auth/csrf')) data = { token: 'test', headerName: 'X-CSRF' }
      else if (url.endsWith('/servers')) data = method === 'POST' ? server('new-server') : [server('s1'), server('s2')]
      else if (url.endsWith('/smart-examples')) data = [{ apiId: 'api1', name: 'Example' }]
      else if (url.endsWith('/shares')) data = shares
      else if (url.endsWith('/rules')) data = method === 'POST' ? { id: 'rule', name: 'Rule' } : []
      else data = []
    }
    return { ok: true, json: async () => ({ data }) }
  }))
  const wrapper = shallowMount(MockServicePage, { global: { plugins: [ArcoVue], renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(wrapper); await flushPromises()
  return { wrapper, writes }
}

function modal(wrapper: ReturnType<typeof shallowMount>, title: string) {
  return wrapper.findAllComponents(Modal).find(m => m.props('title') === title)!
}

describe('Mock service editor', () => {
  it('explains the reachability limit of local desktop Mock links', async () => {
    vi.stubGlobal('testhubDesktop', { workspaceMode: 'local' })
    const { wrapper } = await setup()
    expect(wrapper.find('.local-mock-hint').text()).toContain('仅在本机可访问')
  })
  it('keeps a required-field error visible in the create dialog', async () => {
    const { wrapper, writes } = await setup()
    wrapper.findComponent(PageHeader).vm.$emit('action'); await wrapper.vm.$nextTick()
    const dialog = modal(wrapper, '新建 Mock 服务')
    expect(await dialog.props('onBeforeOk')!(vi.fn())).toBe(false)
    expect(dialog.props('visible')).toBe(true)
    expect(writes).toHaveLength(0)
  })
  it('keeps rule input after invalid JSON instead of closing the dialog', async () => {
    const { wrapper, writes } = await setup()
    const button = wrapper.findAllComponents({ name: 'Button' }).find(b => b.text().includes('新建规则'))!
    button.vm.$emit('click'); await wrapper.vm.$nextTick()
    const dialog = modal(wrapper, '新建 Mock 规则')
    dialog.findAllComponents(Input).find(i => i.props('placeholder') === '例如：预览订单成功')!.vm.$emit('update:modelValue', 'Rule')
    dialog.findAllComponents(CodeEditor)[0].vm.$emit('update:modelValue', '{')
    await wrapper.vm.$nextTick()
    expect(await dialog.props('onBeforeOk')!(vi.fn())).toBe(false)
    expect(dialog.props('visible')).toBe(true)
    expect(writes).toHaveLength(0)
  })
  it('sends no stale body condition when switching back to ANY', async () => {
    const { wrapper, writes } = await setup()
    const dialog = modal(wrapper, '新建 Mock 规则')
    dialog.findAllComponents(Input).find(i => i.props('placeholder') === '例如：预览订单成功')!.vm.$emit('update:modelValue', 'Rule')
    const matcher = dialog.findAllComponents(Select).find(i => i.props('modelValue') === 'ANY')!
    matcher.vm.$emit('update:modelValue', 'EXACT'); await wrapper.vm.$nextTick()
    dialog.findAllComponents(CodeEditor)[2].vm.$emit('update:modelValue', 'old-body')
    matcher.vm.$emit('update:modelValue', 'ANY'); await wrapper.vm.$nextTick()
    await dialog.props('onBeforeOk')!(vi.fn()); await flushPromises()
    expect(writes.find(w => w.url.endsWith('/rules'))!.body).toMatchObject({ requestBodyMatch: 'ANY', requestBody: '' })
  })
  it('loads existing smart examples for a newly created server', async () => {
    const { wrapper } = await setup()
    const dialog = modal(wrapper, '新建 Mock 服务')
    dialog.findComponent(Input).vm.$emit('update:modelValue', 'New')
    await wrapper.vm.$nextTick(); await dialog.props('onBeforeOk')!(vi.fn()); await flushPromises()
    expect(wrapper.find('.detail-head h3').text()).toBe('new-server')
    expect(wrapper.text()).toContain('可用已发布接口：1 个')
  })
  it('excludes expired and revoked shares from the active count', async () => {
    const { wrapper } = await setup()
    expect(wrapper.find('.share-status').text()).toContain('1 条有效链接')
  })
  it('ignores old server responses after switching to another server', async () => {
    let release!: (value: unknown) => void
    let delay = false
    const { wrapper } = await setup((url, method) => {
      if (delay && method === 'GET' && url.endsWith('/s1/rules')) return new Promise(resolve => { release = resolve })
      if (url.endsWith('/s2/shares')) return []
      if (url.endsWith('/s2/smart-examples')) return []
    })
    delay = true
    await wrapper.findAll('.server-row')[0].trigger('click')
    await wrapper.findAll('.server-row')[1].trigger('click')
    await flushPromises()
    release([{ id: 'old-rule', name: 'Old' }]); await flushPromises()
    const tables = wrapper.findAllComponents({ name: 'Table' })
    expect(tables[1].props('data')).toEqual([])
    expect(wrapper.find('.detail-head h3').text()).toBe('s2')
    expect(wrapper.text()).toContain('可用已发布接口：0 个')
    expect(wrapper.find('.share-status').text()).toContain('0 条有效链接')
  })
})

const editableRule = { id: 'r1', serverId: 's1', name: 'Existing', httpMethod: 'POST', path: '/orders', priority: 30,
  requestQuery: { type: 'full' }, requestHeaders: { 'x-version': 'v1' }, requestBodyMatch: 'EXACT', requestBody: 'source', responseStatus: 202,
  responseHeaders: { 'Content-Type': 'application/json' }, responseBody: 'original', enabled: false }
const editor = (w: ReturnType<typeof shallowMount>) => w.vm as unknown as { editRule(rule: typeof editableRule): void }
describe('Mock sync and rule editing', () => {
  it('opens native sync in both desktop modes and hides it on ordinary web', async () => {
    for (const workspaceMode of ['local', 'server']) {
      const openMockSync=vi.fn().mockResolvedValue(undefined);vi.stubGlobal('testhubDesktop',{ workspaceMode, openMockSync })
      const { wrapper, writes }=await setup();const button=wrapper.findAllComponents({name:'Button'}).find(b=>b.text()==='Mock 同步')!
      button.vm.$emit('click');await flushPromises();expect(openMockSync).toHaveBeenCalledOnce();expect(writes).toHaveLength(0)
    }
    vi.stubGlobal('testhubDesktop',undefined);const {wrapper}=await setup();expect(wrapper.find('.sync-entry').exists()).toBe(false)
  })
  it('loads existing conditions and uses the scoped PUT without incrementing rule count or enablement', async () => {
    const { wrapper, writes }=await setup((url,method)=>url.endsWith('/rules/r1')&&method==='PUT'?{...editableRule,responseBody:'edited'}:url.endsWith('/s1/rules')?[editableRule]:undefined)
    editor(wrapper).editRule(editableRule);await flushPromises();const dialog=modal(wrapper,'编辑 Mock 规则')
    expect(dialog.props('visible')).toBe(true);expect(dialog.findAllComponents(CodeEditor)[2].props('modelValue')).toBe('source')
    dialog.findAllComponents(CodeEditor).at(-1)!.vm.$emit('update:modelValue','edited');await dialog.props('onBeforeOk')!(vi.fn());await flushPromises()
    expect(writes).toHaveLength(1);expect(writes[0]).toMatchObject({url:'/api/v1/projects/p1/mock/servers/s1/rules/r1',body:{name:'Existing',responseBody:'edited',requestBodyMatch:'EXACT',requestBody:'source'}})
    expect(writes[0]!.body).not.toHaveProperty('enabled');expect(wrapper.findAllComponents({name:'Table'})[1].props('data')).toEqual([{...editableRule,responseBody:'edited'}])
  })
  it('invalid JSON preserves the edit dialog and original conditions without sending', async () => {
    const {wrapper,writes}=await setup();editor(wrapper).editRule(editableRule);await flushPromises();const dialog=modal(wrapper,'编辑 Mock 规则')
    dialog.findAllComponents(CodeEditor)[0].vm.$emit('update:modelValue','{');await dialog.props('onBeforeOk')!(vi.fn());await flushPromises()
    expect(dialog.props('visible')).toBe(true);expect(writes).toHaveLength(0)
  })
  it('a project change while waiting for CSRF closes the old editor and prevents outgoing save', async () => {
    let release!: (value: unknown)=>void
    const {wrapper,writes}=await setup((url)=>url.endsWith('/auth/csrf')?new Promise(resolve=>{release=resolve}):undefined)
    editor(wrapper).editRule(editableRule);await flushPromises();const dialog=modal(wrapper,'编辑 Mock 规则');const pending=dialog.props('onBeforeOk')!(vi.fn());await flushPromises()
    state.projectId='p2';await flushPromises();release({token:'fixture',headerName:'X-CSRF'});await pending;await flushPromises();expect(writes).toHaveLength(0);expect(modal(wrapper,'新建 Mock 规则').props('visible')).toBe(false)
  })
})

describe('Mock editor cancellation',()=>{
  it('cancel before CSRF returns prevents the old outgoing edit',async()=>{
    let release!: (value: unknown)=>void
    const {wrapper,writes}=await setup(url=>url.endsWith('/auth/csrf')?new Promise(resolve=>{release=resolve}):undefined)
    editor(wrapper).editRule(editableRule);await flushPromises();const dialog=modal(wrapper,'编辑 Mock 规则'),pending=dialog.props('onBeforeOk')!(vi.fn());await flushPromises()
    dialog.vm.$emit('cancel');await flushPromises();release({token:'test',headerName:'X-CSRF'});await pending;expect(writes).toHaveLength(0)
  })
  it('a late save completion cannot close a subsequently opened rule editor',async()=>{
    let release!: (value: unknown)=>void
    const {wrapper}=await setup((url,method)=>url.endsWith('/rules/r1')&&method==='PUT'?new Promise(resolve=>{release=resolve}):undefined)
    editor(wrapper).editRule(editableRule);await flushPromises();const dialog=modal(wrapper,'编辑 Mock 规则'),pending=dialog.props('onBeforeOk')!(vi.fn());await flushPromises()
    dialog.vm.$emit('cancel');editor(wrapper).editRule({...editableRule,id:'r2',name:'Another'});await flushPromises();release({...editableRule,responseBody:'late'});await pending;await flushPromises()
    expect(modal(wrapper,'编辑 Mock 规则').props('visible')).toBe(true);expect(modal(wrapper,'编辑 Mock 规则').findAllComponents(Input)[0].props('modelValue')).toBe('Another')
  })
})
