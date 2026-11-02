import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ArcoVue, { Button, Message, Popconfirm } from '@arco-design/web-vue'
import TasksPage from '@/pages/TasksPage.vue'
import NotificationTemplatesPage from '@/pages/NotificationTemplatesPage.vue'
import PluginsPage from '@/pages/PluginsPage.vue'
import { useSessionStore } from '@/stores/session'

type Kind = 'task' | 'notification' | 'plugin'
type Hook = (url: string, init?: RequestInit) => Promise<Response> | undefined
const wrappers: VueWrapper[] = []
const stamp = '2026-10-30T00:00:00Z'
const profile = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: stamp }
const csrf = { token: 'fixture', headerName: 'X-CSRF' }
const response = (data: unknown, status = 200) => new Response(JSON.stringify(status >= 400 ? { message: '受控错误' } : { data }), { status })
function deferred() { let resolve!: (r: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
async function setup(kind: Kind) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.profile = { ...profile }; session.signedIn = true; session.projectId = 'p1'
  session.organizations = [{ id: 'o', name: 'QA', code: 'QA' }]
  session.projectsByOrganization = { o: ['p1', 'p2'].map(id => ({ id, name: id, code: id, description: '', status: 'ACTIVE', createdByName: null, createdAt: stamp, updatedAt: stamp })) }
  let hook: Hook = () => undefined
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    const custom = hook(url, init); if (custom) return custom
    const p = url.includes('/p2/') ? 'p2' : 'p1'
    const data = url.endsWith('/csrf') ? csrf : url.endsWith('/context') ? { organizations: session.organizations, projects: session.projectsByOrganization }
      : url.endsWith('/capabilities') ? { systemAdministration: true }
        : url.endsWith('/tasks') ? [{ id: 'task-' + p, projectId: p, name: '任务', taskType: 'API', status: 'QUEUED', progress: 0, version: 1, createdAt: stamp, updatedAt: stamp }]
          : url.endsWith('/notification-templates') ? [{ id: 'template-' + p, name: '模板', channel: 'WEBHOOK', eventCode: 'quality.report.ready', enabled: true, updatedAt: stamp }]
            : url.endsWith('/notification-deliveries') ? [{ id: 'delivery-' + p, templateName: '投递', status: 'FAILED', channel: 'WEBHOOK', eventCode: 'quality.report.ready', attemptCount: 1, nextAttemptAt: stamp, deliveredAt: null, createdAt: stamp }]
              : url.endsWith('/plugins') ? [{ id: 'plugin', displayName: '插件', pluginKey: 'qa.plugin', pluginType: 'INTEGRATION', status: 'DISABLED', activeVersion: '1.0.0', permissions: ['integration:outbound'], installedAt: stamp, updatedAt: stamp }]
                : []
    return Promise.resolve(response(data))
  })
  vi.stubGlobal('fetch', fetcher)
  const w = mount(kind === 'task' ? TasksPage : kind === 'notification' ? NotificationTemplatesPage : PluginsPage, { global: { plugins: [pinia, ArcoVue], stubs: { UnifiedExecutionsPanel: true, Modal: true, Drawer: true }, renderStubDefaultSlot: true, config: { warnHandler: () => {} } } })
  wrappers.push(w); await flushPromises()
  const trigger = async () => {
    if (kind === 'plugin') w.findAllComponents(Popconfirm).find(p => String(p.props('content')).includes('声明权限'))!.vm.$emit('ok')
    else if (kind === 'notification') w.findAllComponents(Popconfirm).find(p => String(p.props('content')).includes('重复通知'))!.vm.$emit('ok')
    else w.findAllComponents(Button).find(b => b.text() === '标记执行中')!.vm.$emit('click')
    await flushPromises()
  }
  return { w, session, fetcher, trigger, hook: (value: Hook) => { hook = value } }
}
const writes = (q: Awaited<ReturnType<typeof setup>>) => q.fetcher.mock.calls.filter(([, init]) => init?.method && init.method !== 'GET')
for (const kind of ['task', 'notification'] as const) it(`TC-P2R2-D20-${kind === 'task' ? '01' : '02'}: scope invalidation before dispatch cancels ${kind}`, async () => {
  const q = await setup(kind), gate = deferred(); q.hook(url => url.endsWith('/csrf') ? gate.promise : undefined)
  await q.trigger(); q.session.selectProject('p2'); await flushPromises(); gate.resolve(response(csrf)); await flushPromises()
  expect(writes(q)).toHaveLength(0)
})
it('TC-P2R2-D20-03: plugin leave cancels precommit action', async () => {
  const q = await setup('plugin'), gate = deferred(); q.hook(url => url.endsWith('/csrf') ? gate.promise : undefined)
  await q.trigger(); q.w.unmount(); gate.resolve(response(csrf)); await flushPromises(); expect(writes(q)).toHaveLength(0)
})
it('TC-P2R2-D20-03: a project selection alone does not cancel a global action', async () => {
  const q = await setup('plugin'), gate = deferred(); q.hook(url => url.endsWith('/csrf') ? gate.promise : undefined)
  await q.trigger(); q.session.selectProject('p2'); gate.resolve(response(csrf)); await flushPromises(); expect(writes(q)).toHaveLength(1); expect(String(writes(q)[0]![0])).toContain('/extensions/plugins/plugin/')
})
for (const kind of ['task', 'notification'] as const) it(`TC-P2R2-D20-04: A/B/A invalidates ${kind}`, async () => {
  const q = await setup(kind), gate = deferred(); q.hook(url => url.endsWith('/csrf') ? gate.promise : undefined)
  await q.trigger(); q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises(); gate.resolve(response(csrf)); await flushPromises(); expect(writes(q)).toHaveLength(0)
})
for (const kind of ['task', 'notification', 'plugin'] as const) it(`TC-P2R2-D20-04: same-account reauthentication invalidates ${kind}`, async () => {
  const q = await setup(kind), gate = deferred(); q.hook(url => url.endsWith('/csrf') ? gate.promise : undefined)
  await q.trigger(); q.session.profile = null; q.session.signedIn = false; q.session.profile = { ...profile }; q.session.signedIn = true; await flushPromises()
  gate.resolve(response(csrf)); await flushPromises(); expect(writes(q)).toHaveLength(0)
})
it('TC-P2R2-D20-05: dispatched work keeps its original URL and payload', async () => {
  const q = await setup('task'), gate = deferred(); q.hook((_url, init) => init?.method === 'PUT' ? gate.promise : undefined)
  await q.trigger(); q.session.selectProject('p2'); await flushPromises(); gate.resolve(response({})); await flushPromises()
  expect(writes(q)).toHaveLength(1); expect(String(writes(q)[0]![0])).toContain('/p1/tasks/task-p1'); expect(JSON.parse(String(writes(q)[0]![1]!.body))).toMatchObject({ status: 'RUNNING', expectedVersion: 1 })
})
it('TC-P2R2-D20-06: late completion after A/B/A cannot decorate the replacement view', async () => {
  const q = await setup('task'), gate = deferred(); q.hook((_url, init) => init?.method === 'PUT' ? gate.promise : undefined)
  await q.trigger(); q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises(); const calls = q.fetcher.mock.calls.length
  gate.resolve(response({})); await flushPromises(); expect(q.fetcher.mock.calls.length).toBe(calls); expect(Message.success).not.toHaveBeenCalled()
})
it('TC-P2R2-D20-07: a cancelled token cannot clear a newer pending action', async () => {
  const q = await setup('task'), first = deferred(), second = deferred(); let count = 0
  q.hook(url => url.endsWith('/csrf') ? (++count === 1 ? first.promise : second.promise) : undefined)
  await q.trigger(); await q.trigger(); expect(count).toBe(1)
  q.session.selectProject('p2'); q.session.selectProject('p1'); await flushPromises(); await q.trigger(); expect(count).toBe(2)
  first.resolve(response(csrf)); await flushPromises(); await q.trigger(); expect(count).toBe(2)
  second.resolve(response(csrf)); await flushPromises(); expect(writes(q)).toHaveLength(1)
})
it('TC-P2R2-D20-08: CSRF authorization refusal never produces a business write', async () => {
  const q = await setup('task'); q.hook(url => url.endsWith('/csrf') ? Promise.resolve(response({}, 403)) : undefined)
  await q.trigger(); expect(writes(q)).toHaveLength(0); expect(Message.error).toHaveBeenCalledWith('受控错误')
})
