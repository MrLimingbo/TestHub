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
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { id: '01TEST', email: 'admin@testhub.local', displayName: '管理员', authenticatedAt: '2026-07-11T00:00:00Z' } }), { status: 200 }))
    const routes = ['/dashboard', '/functional', '/functional/plans', '/functional/governance', '/functional/delivery', '/api-design', '/api-debug', '/api-automation', '/mock', '/performance', '/reports', '/project-settings', '/system-settings', '/audit', '/integrations', '/tasks', '/ui-automation', '/mobile-automation', '/plugins']
    for (const path of routes) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(path)
    }
  }, 30_000)
})
