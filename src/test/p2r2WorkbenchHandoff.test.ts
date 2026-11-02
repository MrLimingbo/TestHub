import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ArcoVue from '@arco-design/web-vue'
import ApiInterfaceWorkbench from '@/components/api-testing/ApiInterfaceWorkbench.vue'
import { useSessionStore } from '@/stores/session'
import { consumeApiDebugHandoff } from '@/utils/apiDebugHandoff'
const wrappers: VueWrapper[] = []
afterEach(async () => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; await flushPromises(); vi.unstubAllGlobals(); vi.restoreAllMocks(); sessionStorage.clear(); localStorage.clear() })
const stamp = '2026-10-30T00:00:00Z'
it('TC-P2R2-D15-02: advanced navigation carries full runtime auth in memory with no Web Storage write', async () => {
  const pinia = createPinia(), session = useSessionStore(pinia); session.projectId = 'p1'; session.signedIn = true; session.profile = { id: 'qa', account: 'qa', displayName: 'QA', email: 'qa@example.test', authenticatedAt: stamp }
  const router = createRouter({ history: createMemoryHistory(), routes: ['/api-testing/interfaces', '/api-testing/quick-request'].map(path => ({ path, component: { template: '<div />' } })) }); await router.push('/api-testing/interfaces')
  const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify({ data: url.endsWith('/parameters') ? { parameters: [] } : [] })))
  vi.stubGlobal('fetch', fetcher)
  const version = { id: 'v1', versionName: '1.0.0', summary: '', description: '', requestHeaders: JSON.stringify([{ name: 'Authorization', type: 'string', required: false, example: 'synthetic-header-secret', description: '' }]), requestQuery: '[]', requestPath: '[]', requestBody: 'synthetic-body-secret', responseBody: '{}', exampleResponse: '{"statusCode":200}', status: 'DRAFT', publishedAt: null, createdAt: stamp }
  const api = { id: 'api1', catalogId: 'c', method: 'POST', path: '/path', name: 'QA', status: 'DRAFT', currentVersionId: 'v1', versionCount: 1, createdBy: 'qa', updatedBy: 'qa', createdAt: stamp, updatedAt: stamp }
  const w = mount(ApiInterfaceWorkbench, { props: { projectId: 'p1', api, version, versions: [version], selectedVersionId: 'v1', catalogName: 'QA', createdByName: 'QA', updatedByName: 'QA' }, global: { plugins: [pinia, ArcoVue, router], stubs: { CodeEditor: true, teleport: true }, config: { warnHandler: () => {} } } }); wrappers.push(w); await flushPromises()
  w.findAllComponents({ name: 'Tabs' })[0]!.vm.$emit('update:activeKey', 'debug'); await flushPromises()
  const storage = vi.spyOn(Storage.prototype, 'setItem'); storage.mockClear()
  w.findAllComponents({ name: 'Button' }).find(b => b.text().trim() === '高级调试')!.vm.$emit('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/api-testing/quick-request'); expect(storage).not.toHaveBeenCalled()
  const token = router.currentRoute.value.query.handoff; expect(token).toEqual(expect.any(String)); expect(JSON.stringify(router.currentRoute.value.query)).not.toContain('synthetic')
  const packet = consumeApiDebugHandoff(session, token); expect(packet!.request.auth.type).toBe('NONE'); expect(packet!.request.body).toBe('synthetic-body-secret'); expect(packet!.request.headers).toContainEqual({ name: 'Authorization', value: 'synthetic-header-secret' })
  expect(fetcher.mock.calls.some(([url]) => url.endsWith('/execute') || url.endsWith('/drafts'))).toBe(false)
})
