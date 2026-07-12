import { defineStore } from 'pinia'

export interface Profile {
  id: string
  email: string
  displayName: string
  authenticatedAt: string
}

interface ApiEnvelope<T> { data: T }
interface ApiFailure { message?: string }

export const useSessionStore = defineStore('session', {
  state: () => ({
    signedIn: false,
    initialized: false,
    profile: null as Profile | null,
    organization: '星云科技',
    project: '交易中台 V3',
  }),
  actions: {
    async initialize() {
      if (this.initialized) return
      try {
        const response = await fetch('/api/v1/auth/me', { credentials: 'include' })
        if (response.ok) {
          const payload = await response.json() as ApiEnvelope<Profile>
          this.profile = payload.data
          this.signedIn = true
        }
      } finally {
        this.initialized = true
      }
    },
    async login(email: string, password: string) {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
      if (!csrfResponse.ok) throw new Error('无法初始化安全会话')
      const csrf = await csrfResponse.json() as ApiEnvelope<{ token: string; headerName: string }>
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.data.headerName]: csrf.data.token },
        body: JSON.stringify({ email, password }),
      })
      if (!response.ok) {
        const error = await response.json().catch(() => ({})) as ApiFailure
        throw new Error(error.message ?? '登录失败，请稍后重试')
      }
      const payload = await response.json() as ApiEnvelope<Profile>
      this.profile = payload.data
      this.signedIn = true
      this.initialized = true
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
    },
  },
})
