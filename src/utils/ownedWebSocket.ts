/** One page owns only sessions returned by its own connect operation. */
export interface OwnedWebSocketSession { id: string; status: string }
export interface OwnedWebSocketScope { projectId: string; key: string; current: () => boolean }
interface Owner<T> { scope: OwnedWebSocketScope; configuration: string; session: T; closing?: Promise<T> }
const active = (session: OwnedWebSocketSession) => ['OPEN', 'CONNECTING', 'RECONNECTING'].includes(session.status)

export class OwnedWebSocketSessions<T extends OwnedWebSocketSession> {
  private owner: Owner<T> | null = null
  private connecting = false

  constructor(private readonly transport: {
    connect: (projectId: string, payload: Record<string, unknown>, current: () => boolean) => Promise<T>
    close: (projectId: string, sessionId: string) => Promise<T>
  }) {}

  get session(): T | null { return this.owner?.session ?? null }

  matches(scopeKey: string, payload: Record<string, unknown>): boolean {
    return this.owner?.scope.key === scopeKey && active(this.owner.session)
      && this.owner.configuration === JSON.stringify(payload)
  }

  update(session: T) {
    if (this.owner?.session.id === session.id) this.owner.session = session
  }

  async connect(scope: OwnedWebSocketScope, payload: Record<string, unknown>): Promise<T | null> {
    if (this.connecting) throw new Error('正在处理本页WebSocket连接，请等待当前操作结束')
    this.connecting = true
    const configuration = JSON.stringify(payload)
    try {
      if (this.owner && active(this.owner.session)) {
        if (scope.current() && this.owner.scope.key === scope.key && this.owner.configuration === configuration) return this.owner.session
        await this.closeOwner(this.owner)
      }
      if (!scope.current()) return null
      const session = await this.transport.connect(scope.projectId, payload, scope.current)
      if (!session || typeof session.id !== 'string' || !session.id || !['OPEN', 'CONNECTING', 'RECONNECTING', 'CLOSED', 'ERROR'].includes(session.status)) {
        throw new Error('WebSocket连接回执无效，未确认已连接')
      }
      const owner = { scope, configuration, session }
      this.owner = owner
      if (!scope.current()) { await this.closeOwner(owner); return null }
      return session
    } finally { this.connecting = false }
  }

  async close(): Promise<T | null> {
    const owner = this.owner
    return owner && active(owner.session) ? this.closeOwner(owner) : owner?.session ?? null
  }

  private async closeOwner(owner: Owner<T>): Promise<T> {
    if (owner.closing) return owner.closing
    const closing = this.transport.close(owner.scope.projectId, owner.session.id).then(session => {
      if (session?.id !== owner.session.id || !['CLOSED', 'ERROR'].includes(session.status)) throw new Error('旧WebSocket会话关闭尚未确认，请核对后重试')
      owner.session = session
      if (this.owner === owner) this.owner = null
      return session
    })
    owner.closing = closing
    try { return await closing } finally { owner.closing = undefined }
  }
}
