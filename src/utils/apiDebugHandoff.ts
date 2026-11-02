import type { useSessionStore } from '@/stores/session'
import type { ApiDebugRequestAuth, ApiDebugRequestHeader } from '@/utils/apiDebugDraft'

type Session = ReturnType<typeof useSessionStore>
export interface ApiDebugHandoffRequest {
  name?: string
  method: string
  url: string
  headers: ApiDebugRequestHeader[]
  body: string
  environmentCode: string | null
  auth: ApiDebugRequestAuth
}
export interface ApiDebugHandoff { protocol: 'HTTP'; sourceApiId: string; request: ApiDebugHandoffRequest }
interface Vault { identity: string; packet: { key: string; value: ApiDebugHandoff } | null }
// Weak ownership and a single pending packet bound memory to the current SPA login session.
const vaults = new WeakMap<Session, Vault>()
const identity = (session: Session) => JSON.stringify([session.signedIn, session.profile?.id, session.profile?.authenticatedAt, session.authGeneration, session.projectId])
function vault(session: Session): Vault {
  let value = vaults.get(session)
  if (!value) {
    value = { identity: identity(session), packet: null }
    const owned = value
    session.$subscribe(() => {
      const next = identity(session)
      if (next !== owned.identity) { owned.packet = null; owned.identity = next }
    }, { detached: true, flush: 'sync' })
    vaults.set(session, owned)
  }
  return value
}
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function validate(request: ApiDebugHandoffRequest) {
  const auth = request?.auth
  if (!auth || !['NONE', 'BEARER', 'BASIC', 'API_KEY'].includes(auth.type)
    || !['HEADER', 'QUERY'].includes(auth.placement)
    || !['token', 'username', 'password', 'key', 'value'].every(field => typeof auth[field as keyof ApiDebugRequestAuth] === 'string')) {
    throw new Error('当前认证模式或结构无法交接，请重新配置后继续')
  }
  if (!request || !['method', 'url', 'body'].every(field => typeof request[field as keyof ApiDebugHandoffRequest] === 'string')
    || !Array.isArray(request.headers) || !request.headers.every(header => header && typeof header.name === 'string' && typeof header.value === 'string')
    || (request.environmentCode !== null && typeof request.environmentCode !== 'string')) {
    throw new Error('运行草稿结构无法交接，请返回源接口核对')
  }
}
export function createApiDebugHandoff(session: Session, projectId: string, sourceApiId: string, request: ApiDebugHandoffRequest): string {
  if (!session.signedIn || !session.profile?.id || !projectId || projectId !== session.projectId) throw new Error('登录会话或项目已变化，请返回源接口重新打开高级调试')
  validate(request)
  if (!globalThis.crypto?.randomUUID) throw new Error('当前环境无法生成安全交接标识，请检查浏览器安全上下文')
  const key = crypto.randomUUID()
  vault(session).packet = { key, value: { protocol: 'HTTP', sourceApiId, request: clone(request) } }
  return key
}
export function consumeApiDebugHandoff(session: Session, key: unknown): ApiDebugHandoff | null {
  const owner = vault(session), packet = owner.packet
  if (!session.signedIn || !session.profile?.id || typeof key !== 'string' || packet?.key !== key || owner.identity !== identity(session)) return null
  owner.packet = null
  return clone(packet.value)
}
export function discardApiDebugHandoff(session: Session, key: string) {
  const owner = vaults.get(session)
  if (owner?.packet?.key === key) owner.packet = null
}
