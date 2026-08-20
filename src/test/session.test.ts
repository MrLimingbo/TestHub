import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionStore } from '@/stores/session'

describe('session login recovery guidance', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('explains how to recover when Platform API cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(useSessionStore().login('admin', 'secret')).rejects.toThrow(
      '无法连接 Platform API，请重新运行 ./start.sh 后刷新页面',
    )
  })

  it('includes the HTTP status when CSRF bootstrap fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))

    await expect(useSessionStore().login('admin', 'secret')).rejects.toThrow(
      '安全会话初始化失败（HTTP 503），请重新运行 ./start.sh 后刷新页面',
    )
  })

  it('rejects malformed CSRF responses with recovery guidance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {} }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })))

    await expect(useSessionStore().login('admin', 'secret')).rejects.toThrow(
      '安全会话响应无效，请重新运行 ./start.sh 后刷新页面',
    )
  })
})
