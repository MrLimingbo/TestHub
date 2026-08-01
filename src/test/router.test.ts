import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import router from '@/router'

describe('router access', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
  })

  it('redirects anonymous users to login', async () => {
    await router.push('/dashboard')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('allows anonymous users to open a Mock share link', async () => {
    await router.push('/mock/share/01J7F6WHVXGFQ3WJ41GXZW0SNP')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/mock/share/01J7F6WHVXGFQ3WJ41GXZW0SNP')
  })

  it('allows signed-in users to open every product module', async () => {
    globalThis.fetch = vi.fn(async (input: string | URL | Request) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      if (url.endsWith('/api/v1/access/context')) {
        return new Response(JSON.stringify({
          data: {
            organizations: [{ id: 'ORG-1', name: '星云科技', code: 'nebula' }],
            projects: { 'ORG-1': [{ id: 'PROJECT-1', organizationId: 'ORG-1', name: '交易中台 V3', code: 'trade-v3', status: 'ACTIVE' }] },
          },
        }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: { id: '01TEST', email: 'admin@testhub.local', displayName: '管理员', authenticatedAt: '2026-07-11T00:00:00Z' } }), { status: 200 })
    })
    const routes = ['/dashboard', '/functional', '/functional/requirements', '/functional/cases', '/functional/plans', '/functional/governance', '/functional/delivery', '/functional/versions', '/api-testing/interfaces', '/api-testing/quick-request', '/api-automation', '/mock', '/performance', '/reports', '/project-settings', '/project-settings/members', '/project-settings/environments', '/project-settings/files', '/project-settings/logs', '/system-settings/accounts', '/system-settings/parameters', '/audit', '/integrations', '/tasks', '/ui-automation', '/mobile-automation', '/plugins', '/operations']
    for (const path of routes) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(path)
    }

    await router.push('/api-testing')
    expect(router.currentRoute.value.path).toBe('/api-testing/interfaces')

    await router.push('/api-design?source=legacy&catalog=orders')
    expect(router.currentRoute.value.path).toBe('/api-testing/interfaces')
    expect(router.currentRoute.value.query).toEqual({ source: 'legacy', catalog: 'orders' })

    await router.push('/api-debug?source=legacy&environment=test')
    expect(router.currentRoute.value.path).toBe('/api-testing/quick-request')
    expect(router.currentRoute.value.query).toEqual({ source: 'legacy', environment: 'test' })
  }, 30_000)
})
