import { defineStore } from 'pinia'
import { isLocalDesktop, resumeLocalWorkspace } from '@/utils/desktopWorkspace'

export interface Profile {
  id: string
  account: string
  email: string
  displayName: string
  authenticatedAt: string
}

export interface Organization {
  id: string
  name: string
  code: string
}

export interface Project {
  id: string
  name: string
  code: string
  status: string
  description: string
  createdByName: string | null
  createdAt: string
  updatedAt: string
}

export interface RegistrationInput {
  account: string
  email: string
  displayName: string
  password: string
}

interface AccessContext {
  organizations: Organization[]
  projects: Record<string, Project[]>
}

interface ApiEnvelope<T> { data: T }
interface ApiFailure { message?: string }
const PROJECT_STORAGE_KEY = 'testhub.current-project'
const CONNECTION_ERROR = '无法连接 Platform API，请重新运行 ./start.sh 后刷新页面'

async function readCsrfToken() {
  let response: Response
  try {
    response = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  } catch {
    throw new Error(CONNECTION_ERROR)
  }
  if (!response.ok) {
    throw new Error(`安全会话初始化失败（HTTP ${response.status}），请重新运行 ./start.sh 后刷新页面`)
  }
  const body = await response.json().catch(() => null) as ApiEnvelope<{ token: string; headerName: string }> | null
  if (!body?.data?.token || !body.data.headerName) {
    throw new Error('安全会话响应无效，请重新运行 ./start.sh 后刷新页面')
  }
  return body.data
}

export const useSessionStore = defineStore('session', {
  state: () => ({
    signedIn: false,
    authGeneration: 0,
    initialized: false,
    initializationError: '',
    profile: null as Profile | null,
    organizations: [] as Organization[],
    projectsByOrganization: {} as Record<string, Project[]>,
    projectId: typeof window === 'undefined' ? '' : window.localStorage.getItem(PROJECT_STORAGE_KEY) ?? '',
  }),
  getters: {
    projects: (state): Project[] => state.organizations.flatMap(
      (organization) => state.projectsByOrganization[organization.id] ?? [],
    ),
    currentProject(): Project | null {
      return this.projects.find((project) => project.id === this.projectId) ?? null
    },
  },
  actions: {
    async initialize(revalidate = false) {
      if (this.initialized && !revalidate) return
      this.initializationError = ''
      try {
        let response = await fetch('/api/v1/auth/me', { credentials: 'include' })
        if (response.status === 401 && isLocalDesktop()) {
          await resumeLocalWorkspace()
          response = await fetch('/api/v1/auth/me', { credentials: 'include' })
        }
        if (response.status === 401) {
          this.clearSession()
          return
        }
        if (!response.ok) throw new Error(`读取登录状态失败（HTTP ${response.status}），请稍后重试`)
        const payload = await response.json() as ApiEnvelope<Profile>
        if (!payload.data?.id) throw new Error('登录状态响应无效，请刷新页面重试')
        const sameUser = this.signedIn && this.profile?.id === payload.data.id
        if (!sameUser || this.profile?.authenticatedAt !== payload.data.authenticatedAt) this.authGeneration++
        this.profile = payload.data
        this.signedIn = true
        if (!sameUser) await this.loadAccessContext()
      } catch (error) {
        this.initializationError = error instanceof TypeError ? CONNECTION_ERROR
          : error instanceof Error ? error.message : '无法读取登录状态，请刷新页面重试'
      } finally {
        this.initialized = true
      }
    },
    async login(account: string, password: string) {
      return this.authenticate('login', { account, password })
    },
    async register(input: RegistrationInput) {
      return this.authenticate('register', input)
    },
    async authenticate(action: 'login' | 'register', input: { account: string; password: string }) {
      const csrf = await readCsrfToken()
      const response = await fetch(`/api/v1/auth/${action}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify(input),
      })
      if (!response.ok) {
        const error = await response.json().catch(() => ({})) as ApiFailure
        throw new Error(error.message ?? (action === 'register' ? '注册失败，请稍后重试' : '登录失败，请稍后重试'))
      }
      const payload = await response.json() as ApiEnvelope<Profile>
      this.authGeneration++
      this.profile = payload.data
      this.signedIn = true
      this.initialized = true
      this.initializationError = ''
      this.organizations = []
      this.projectsByOrganization = {}
      await this.loadAccessContext()
    },
    async signOut() {
      const csrf = await readCsrfToken()
      let response: Response
      try {
        response = await fetch('/api/v1/auth/logout', {
          method: 'POST',
          credentials: 'include',
          headers: { [csrf.headerName]: csrf.token },
        })
      } catch {
        throw new Error('退出失败，无法连接 Platform API，请重试')
      }
      // A 401 also confirms that the server no longer has an authenticated session.
      if (!response.ok && response.status !== 401) {
        throw new Error(`退出失败（HTTP ${response.status}），请重试`)
      }
      this.clearSession()
    },
    clearSession() {
      this.authGeneration++
      this.signedIn = false
      this.profile = null
      this.initialized = true
      this.initializationError = ''
      this.organizations = []
      this.projectsByOrganization = {}
      this.projectId = ''
      window.localStorage.removeItem(PROJECT_STORAGE_KEY)
    },
    async loadAccessContext() {
      if (!this.signedIn) return
      const response = await fetch('/api/v1/access/context', { credentials: 'include' })
      const body = await response.json().catch(() => ({})) as ApiEnvelope<AccessContext> & ApiFailure
      if (!response.ok) throw new Error(body.message ?? '无法加载项目上下文')
      this.organizations = body.data.organizations
      this.projectsByOrganization = body.data.projects
      const availableProjects = this.projects
      const current = availableProjects.find((project) => project.id === this.projectId)
      this.projectId = current?.id ?? availableProjects[0]?.id ?? ''
      this.persistProject()
    },
    selectProject(projectId: string) {
      this.projectId = projectId
      this.persistProject()
    },
    resolveProjectId(projects: Array<{ id: string }>) {
      const resolved = projects.some((project) => project.id === this.projectId)
        ? this.projectId
        : projects[0]?.id ?? ''
      if (resolved !== this.projectId) this.selectProject(resolved)
      return resolved
    },
    persistProject() {
      if (typeof window === 'undefined') return
      if (this.projectId) window.localStorage.setItem(PROJECT_STORAGE_KEY, this.projectId)
      else window.localStorage.removeItem(PROJECT_STORAGE_KEY)
    },
  },
})
