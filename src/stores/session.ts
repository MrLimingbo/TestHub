import { defineStore } from 'pinia'

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

interface AccessContext {
  organizations: Organization[]
  projects: Record<string, Project[]>
}

interface ApiEnvelope<T> { data: T }
interface ApiFailure { message?: string }
const PROJECT_STORAGE_KEY = 'testhub.current-project'

export const useSessionStore = defineStore('session', {
  state: () => ({
    signedIn: false,
    initialized: false,
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
    async initialize() {
      if (this.initialized) return
      try {
        const response = await fetch('/api/v1/auth/me', { credentials: 'include' })
        if (response.ok) {
          const payload = await response.json() as ApiEnvelope<Profile>
          this.profile = payload.data
          this.signedIn = true
          await this.loadAccessContext()
        }
      } finally {
        this.initialized = true
      }
    },
    async login(account: string, password: string) {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
      if (!csrfResponse.ok) throw new Error('无法初始化安全会话')
      const csrf = await csrfResponse.json() as ApiEnvelope<{ token: string; headerName: string }>
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.data.headerName]: csrf.data.token },
        body: JSON.stringify({ account, password }),
      })
      if (!response.ok) {
        const error = await response.json().catch(() => ({})) as ApiFailure
        throw new Error(error.message ?? '登录失败，请稍后重试')
      }
      const payload = await response.json() as ApiEnvelope<Profile>
      this.profile = payload.data
      this.signedIn = true
      this.initialized = true
      await this.loadAccessContext()
    },
    async signOut() {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
      const csrf = csrfResponse.ok
        ? await csrfResponse.json() as ApiEnvelope<{ token: string; headerName: string }>
        : null
      await fetch('/api/v1/auth/logout', {
        method: 'POST',
        credentials: 'include',
        headers: csrf ? { [csrf.data.headerName]: csrf.data.token } : {},
      })
      this.signedIn = false
      this.profile = null
      this.initialized = true
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
