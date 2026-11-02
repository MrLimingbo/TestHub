import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSessionStore } from '@/stores/session'

describe('session login recovery guidance', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  afterEach(() => {
    Reflect.deleteProperty(window, 'testhubDesktop')
    vi.unstubAllGlobals()
    localStorage.clear()
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

  const profile = { id: 'qa-user', account: 'qa-user', email: 'qa@example.test', displayName: '测试用户', authenticatedAt: '2026-10-23T00:00:00Z' }
  const payload = (data: unknown) => new Response(JSON.stringify({ data }))
  const csrf = () => payload({ token: 'qa-csrf', headerName: 'X-XSRF-TOKEN' })
  const signedIn = () => {
    const store = useSessionStore()
    store.signedIn = true
    store.initialized = true
    store.profile = profile
    store.organizations = [{ id: 'qa-org', name: '测试组织', code: 'qa' }]
    store.projectsByOrganization = { 'qa-org': [{ id: 'qa-project', name: '测试项目', code: 'qa', status: 'ACTIVE', description: '', createdByName: null, createdAt: '', updatedAt: '' }] }
    store.selectProject('qa-project')
    return store
  }

  it('keeps the login page available and reports an initial session network failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const store = useSessionStore()
    await expect(store.initialize()).resolves.toBeUndefined()
    expect(store.signedIn).toBe(false)
    expect(store.initialized).toBe(true)
    expect(store.initializationError).toContain('无法连接 Platform API')
  })

  it('clears initialization errors after the user retries authentication', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(payload(profile))
      .mockResolvedValueOnce(payload({ organizations: [], projects: {} })))
    const store = useSessionStore()
    await store.initialize()
    await store.login('qa-user', 'qa-password')
    expect(store.signedIn).toBe(true)
    expect(store.initializationError).toBe('')
  })

  it('reports an HTTP session lookup failure instead of treating it as a valid identity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))
    const store = useSessionStore()
    await store.initialize()
    expect(store.signedIn).toBe(false)
    expect(store.initializationError).toContain('HTTP 503')
  })

  it('rejects a malformed current-user response without authenticating', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(payload({})))
    const store = useSessionStore()
    await store.initialize()
    expect(store.signedIn).toBe(false)
    expect(store.initializationError).toContain('登录状态响应无效')
  })

  it('revalidates cached authentication and clears expired identity and project data on 401', async () => {
    const store = signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    await store.initialize(true)
    expect(store.signedIn).toBe(false)
    expect(store.profile).toBeNull()
    expect(store.organizations).toEqual([])
    expect(store.projects).toEqual([])
    expect(store.projectId).toBe('')
    expect(localStorage.getItem('testhub.current-project')).toBeNull()
  })

  it('preserves a valid cached project when revalidation confirms the same user', async () => {
    const store = signedIn()
    const fetchMock = vi.fn().mockResolvedValue(payload(profile))
    vi.stubGlobal('fetch', fetchMock)
    await store.initialize(true)
    expect(store.signedIn).toBe(true)
    expect(store.projectId).toBe('qa-project')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('retains identity on a transient revalidation failure and can retry the check', async () => {
    const store = signedIn()
    vi.stubGlobal('fetch', vi.fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(payload(profile)))
    await store.initialize(true)
    expect(store.signedIn).toBe(true)
    expect(store.projectId).toBe('qa-project')
    expect(store.initializationError).toContain('无法连接')
    await store.initialize(true)
    expect(store.initializationError).toBe('')
  })

  it.each([403, 500])('retains the authenticated workspace when logout returns HTTP %s', async (status) => {
    const store = signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(new Response('', { status })))
    await expect(store.signOut()).rejects.toThrow(`退出失败（HTTP ${status}），请重试`)
    expect(store.signedIn).toBe(true)
    expect(store.profile?.id).toBe(profile.id)
    expect(store.projects).toHaveLength(1)
    expect(localStorage.getItem('testhub.current-project')).toBe('qa-project')
  })

  it('retains the authenticated workspace when the logout request is interrupted', async () => {
    const store = signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(csrf()).mockRejectedValueOnce(new TypeError('Failed to fetch')))
    await expect(store.signOut()).rejects.toThrow('退出失败，无法连接 Platform API，请重试')
    expect(store.signedIn).toBe(true)
    expect(store.projectId).toBe('qa-project')
  })

  it.each([503, 200])('does not submit logout after an unusable CSRF response with status %s', async (status) => {
    const store = signedIn()
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {} }), { status }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(store.signOut()).rejects.toThrow(status === 503 ? 'HTTP 503' : '安全会话响应无效')
    expect(store.signedIn).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it.each([204, 401])('clears local identity only when logout succeeds or confirms an expired session (%s)', async (status) => {
    const store = signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(new Response(null, { status })))
    await store.signOut()
    expect(store.signedIn).toBe(false)
    expect(store.profile).toBeNull()
    expect(store.projects).toEqual([])
    expect(localStorage.getItem('testhub.current-project')).toBeNull()
  })
})
