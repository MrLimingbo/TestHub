import { afterEach, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { createApiDebugHandoff, consumeApiDebugHandoff, discardApiDebugHandoff, type ApiDebugHandoffRequest } from '@/utils/apiDebugHandoff'
import { storeApiDebugRouteDraft, readApiDebugRouteDraft } from '@/utils/apiDebugDraft'
import { useSessionStore } from '@/stores/session'
afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); sessionStorage.clear() })
function setup() {
  const session = useSessionStore(createPinia()); session.signedIn = true; session.profile = { id: 'qa', account: 'qa', email: 'qa@example.test', displayName: 'QA', authenticatedAt: '2026-10-30T00:00:00Z' }; session.projectId = 'p1'; return session
}
function request(type: ApiDebugHandoffRequest['auth']['type'] = 'NONE', placement: 'HEADER' | 'QUERY' = 'HEADER'): ApiDebugHandoffRequest {
  return { method: 'POST', url: 'https://fixture.test/path', headers: [{ name: 'Authorization', value: 'synthetic-header' }], body: 'synthetic-body', environmentCode: 'test', auth: { type, token: '{{TOKEN}}', username: 'synthetic-user', password: 'synthetic-password', key: 'X-API-Key', value: '{{KEY}}', placement } }
}
it('TC-P2R2-D15-01: all supported auth configurations preserve the full runtime request', () => {
  const session = setup()
  for (const [type, placement] of [['NONE', 'HEADER'], ['BEARER', 'HEADER'], ['BASIC', 'HEADER'], ['API_KEY', 'HEADER'], ['API_KEY', 'QUERY']] as const) {
    const original = request(type, placement); const key = createApiDebugHandoff(session, 'p1', 'api', original); const result = consumeApiDebugHandoff(session, key)
    expect(result).toEqual({ protocol: 'HTTP', sourceApiId: 'api', request: original }); original.body = 'modified later'; expect(result!.request.body).toBe('synthetic-body')
  }
})
it('TC-P2R2-D15-02: a complete handoff never writes credentials to Web Storage', () => {
  const session = setup(), spy = vi.spyOn(Storage.prototype, 'setItem'); spy.mockClear()
  const key = createApiDebugHandoff(session, 'p1', 'api', request('BASIC')); expect(key).toMatch(/^[a-f0-9-]{36}$/); expect(spy).not.toHaveBeenCalled(); expect(key).not.toContain('synthetic'); expect(consumeApiDebugHandoff(session, key)).not.toBeNull()
})
it('TC-P2R2-D15-03: project A/B/A and same-account reauthentication invalidate pending packets', () => {
  const session = setup(); let key = createApiDebugHandoff(session, 'p1', 'api', request()); session.projectId = 'p2'; session.projectId = 'p1'; expect(consumeApiDebugHandoff(session, key)).toBeNull()
  key = createApiDebugHandoff(session, 'p1', 'api', request()); const profile = session.profile; session.profile = null; session.signedIn = false; session.profile = profile; session.signedIn = true; expect(consumeApiDebugHandoff(session, key)).toBeNull()
  expect(() => createApiDebugHandoff(session, 'p2', 'api', request())).toThrow('项目已变化')
})
it('TC-P2R2-D15-04: single use, another window/store and missing keys cannot restore old auth', () => {
  const session = setup(), key = createApiDebugHandoff(session, 'p1', 'api', request()); expect(consumeApiDebugHandoff(setup(), key)).toBeNull(); expect(consumeApiDebugHandoff(session, key)).not.toBeNull(); expect(consumeApiDebugHandoff(session, key)).toBeNull(); expect(consumeApiDebugHandoff(session, 'missing')).toBeNull()
})
it('TC-P2R2-D15-05: creating and consuming a handoff perform no request or history write', () => {
  const session = setup(), network = vi.spyOn(globalThis, 'fetch'); const key = createApiDebugHandoff(session, 'p1', 'api', request()); consumeApiDebugHandoff(session, key); expect(network).not.toHaveBeenCalled()
})
it('TC-P2R2-D15-06: header/query placement and variable references are never resolved during navigation', () => {
  const session = setup(); for (const placement of ['HEADER', 'QUERY'] as const) { const original = request('API_KEY', placement), key = createApiDebugHandoff(session, 'p1', 'api', original); expect(consumeApiDebugHandoff(session, key)!.request.auth).toEqual(original.auth) }
})
it('TC-P2R2-D15-07: unknown auth and malformed fields are rejected before creating a packet', () => {
  const session = setup(), bad = request(); bad.auth.type = 'UNSUPPORTED' as never; expect(() => createApiDebugHandoff(session, 'p1', 'api', bad)).toThrow('认证模式')
  const badBody = request(); badBody.body = 42 as never; expect(() => createApiDebugHandoff(session, 'p1', 'api', badBody)).toThrow('草稿结构')
})
it('TC-P2R2-D15-08: discard invalidates only the selected packet and legacy route storage stays compatible', () => {
  const session = setup(), old = createApiDebugHandoff(session, 'p1', 'api', request()), key = createApiDebugHandoff(session, 'p1', 'api', request()); expect(consumeApiDebugHandoff(session, old)).toBeNull(); discardApiDebugHandoff(session, key); expect(consumeApiDebugHandoff(session, key)).toBeNull()
  const legacy = { method: 'GET', url: 'https://fixture.test/legacy' }, stored = storeApiDebugRouteDraft(sessionStorage, legacy); expect(readApiDebugRouteDraft(sessionStorage, stored)).toEqual(legacy)
})
