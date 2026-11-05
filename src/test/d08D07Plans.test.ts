import { computed, ref } from 'vue'
import { createPinia } from 'pinia'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ArcoVue, { Message } from '@arco-design/web-vue'
import { afterEach, expect, it, vi } from 'vitest'
import FunctionalPlansPage from '@/pages/FunctionalPlansPage.vue'
import { functionalWorkspaceKey, type FunctionalWorkspace } from '@/composables/useFunctionalWorkspace'
import { useSessionStore } from '@/stores/session'

const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.unstubAllGlobals(); vi.restoreAllMocks(); localStorage.clear() })
async function setup(titleSource: string) {
  vi.spyOn(Message, 'success').mockImplementation(() => ({ close: vi.fn() })); vi.spyOn(Message, 'error').mockImplementation(() => ({ close: vi.fn() }))
  const pinia = createPinia(); useSessionStore(pinia).projectId = 'p1'
  const workspace: FunctionalWorkspace = { catalogs: ref([]), requirements: ref([]), cases: ref([]), defects: ref([]), plans: ref([]), selectedCatalogId: ref(''), selectedCatalogName: computed(() => '全部'), loading: ref(false), reload: vi.fn(async () => {}) }
  const plan = { id: 'plan', planCode: 'PLAN-1', name: '计划', caseCount: 1, status: 'READY', description: '', catalogId: null }
  const row = { id: 'rc', caseId: 'c1', caseCode: 'CASE-1', title: titleSource === 'SNAPSHOT' ? '历史标题A' : '当前标题B', titleSource, status: 'NOT_RUN', note: '' }
  const fetcher = vi.fn((url: string, init?: RequestInit) => Promise.resolve(new Response(JSON.stringify({ data:
    url.endsWith('/csrf') ? { headerName: 'X-CSRF', token: 'fixture' } : init?.method === 'PUT' ? plan
      : url.endsWith('/context') ? { organizations: [{ id: 'org' }], projects: { org: [{ id: 'p1', name: '项目', code: 'P1' }] } }
        : url.endsWith('/functional/plans') ? [plan] : url.endsWith('/plans/plan/cases') ? [{ id: 'pc', caseId: 'c1', caseCode: 'CASE-1', title: '当前标题B', priority: 'P1', status: 'READY' }]
          : url.includes('/runs?') ? [{ id: 'run', planId: 'plan', name: '批次', status: 'NOT_STARTED', totalCount: 1 }]
            : url.endsWith('/runs/run/cases') ? [row] : []
  }), { headers: { 'Content-Type': 'application/json' } })))
  vi.stubGlobal('fetch', fetcher)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] }); await router.push('/')
  const w = mount(FunctionalPlansPage, { global: { plugins: [pinia, ArcoVue, router], provide: { [functionalWorkspaceKey as symbol]: workspace }, stubs: { teleport: true }, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises(); return { w, fetcher }
}
it('U01 missing historical snapshot is explicitly labelled as current title', async () => { const { w } = await setup('CURRENT_ASSET'); expect(w.get('.run-case-item').text()).toContain('当前标题B'); expect(w.get('.run-case-item').text()).toContain('旧记录未保存标题快照') })
it('U02 new history displays its snapshot source and original title', async () => { const { w } = await setup('SNAPSHOT'); expect(w.get('.run-case-item').text()).toContain('历史标题A'); expect(w.get('.run-case-item').text()).toContain('执行时标题快照'); expect(w.get('.run-case-item').text()).not.toContain('旧记录未保存') })
it('U03 plan code is readonly and original code accompanies edit', async () => {
  const { w, fetcher } = await setup('SNAPSHOT'); w.findAllComponents({ name: 'Button' }).find(c => c.text().includes('编辑计划'))!.vm.$emit('click'); await flushPromises()
  const modal = w.findAllComponents({ name: 'Modal' }).find(c => c.props('title') === '编辑测试计划')!
  const code = modal.findAllComponents({ name: 'FormItem' }).find(c => c.props('label') === '计划编号')!.findComponent({ name: 'Input' }); expect(code.props('disabled')).toBe(true)
  await (modal.props('onBeforeOk') as () => Promise<boolean>)(); const call = fetcher.mock.calls.find(([, init]) => init?.method === 'PUT')!; expect(JSON.parse(String(call[1]!.body)).planCode).toBe('PLAN-1')
})
