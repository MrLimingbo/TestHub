import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionStore } from '@/stores/session'

describe('session login recovery guidance', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    Reflect.deleteProperty(window, 'testhubDesktop')
    vi.unstubAllGlobals()
  })

  it('renews an expired local session through the native bridge before loading its workspace', async () => {
    const resumeLocalWorkspace = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window, 'testhubDesktop', { configurable: true, value: { workspaceMode: 'local', resumeLocalWorkspace } })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { id: 'local-owner', displayName: '本地用户' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { organizations: [], projects: {} } })))
    vi.stubGlobal('fetch', fetchMock)
    const store = useSessionStore()
    await store.initialize()
    expect(resumeLocalWorkspace).toHaveBeenCalledOnce()
    expect(store.signedIn).toBe(true)
    expect(store.profile?.id).toBe('local-owner')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('does not renew online sessions using local workspace credentials', async () => {
    const resumeLocalWorkspace = vi.fn()
    Object.defineProperty(window, 'testhubDesktop', { configurable: true, value: { workspaceMode: 'server', resumeLocalWorkspace } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    const store = useSessionStore()
    await store.initialize()
    expect(resumeLocalWorkspace).not.toHaveBeenCalled()
    expect(store.signedIn).toBe(false)
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

  it('registers with CSRF and loads only the new account workspace', async () => {
    const payload = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(payload({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(payload({ id: 'new-user', account: 'new-user', email: 'new@example.test' }))
      .mockResolvedValueOnce(payload({ organizations: [{ id: 'personal-org', name: '个人工作区' }], projects: {
        'personal-org': [{ id: 'personal-project', name: '我的项目' }],
      } }))
    vi.stubGlobal('fetch', fetchMock)
    const store = useSessionStore()
    store.projectId = 'previous-account-project'
    const input = { account: 'new-user', email: 'new@example.test', displayName: '新用户', password: 'password-2026' }
    await store.register(input)
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/register', expect.objectContaining({
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'csrf-token' },
      body: JSON.stringify(input),
    }))
    expect(store.signedIn).toBe(true)
    expect(store.projectId).toBe('personal-project')
    expect(store.projects).toHaveLength(1)
  })

  it('shows duplicate registration error without marking the user signed in', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { token: 'csrf', headerName: 'X-XSRF-TOKEN' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: '账号或邮箱已存在' }), { status: 409 })))
    const store = useSessionStore()
    await expect(store.register({ account: 'taken', email: 'taken@example.test', displayName: '', password: 'password-2026' }))
      .rejects.toThrow('账号或邮箱已存在')
    expect(store.signedIn).toBe(false)
    expect(store.profile).toBeNull()
  })
})
