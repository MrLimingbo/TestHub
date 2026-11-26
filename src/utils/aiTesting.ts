export type ReasoningLevel = 'none' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max'
export interface ModelSelection { providerId: string; modelId: string; reasoningLevel: ReasoningLevel | null }
export interface ChatModel { id: string; displayName: string; reasoningLevels: ReasoningLevel[] }
export interface ChatProvider { id: string; displayName: string; models: ChatModel[] }
export interface AiWorkspace { id: string; name: string; archived: boolean; version: number }
export interface AiAgent {
  id: string
  workspaceId: string
  name: string
  description: string
  systemPrompt: string
  model: ModelSelection
  archived: boolean
  version: number
}
export interface Conversation {
  id: string
  agentId: string | null
  workspaceId: string
  title: string
  updatedAt: string
  archived: boolean
  version: number
}
export type TurnStatus = 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'FAILED'
export interface Turn {
  id: string
  number: number
  attemptId: string
  model: ModelSelection
  userText: string
  reply: string | null
  status: TurnStatus
  failureMessage: string | null
  startedAt: string
  sourceSummary: string
}
export interface ConversationDetail { conversation: Conversation; turns: Turn[] }
export interface AiOverview { canManage: boolean; workspaces: AiWorkspace[]; agents: AiAgent[]; modelProviders: ChatProvider[] }
export interface Attachment { name: string; text: string; truncated: boolean }
export interface AssetRef { kind: 'REQUIREMENT' | 'CASE'; id: string }
export interface ProjectAsset extends AssetRef { name: string }
export interface CaseCatalog { id: string; name: string; parentId: string | null }
export interface CaseDraft { title: string; priority: string; precondition: string; steps: string; expectedResult: string }
export interface SavedCase { id: string; caseCode: string; title: string }

export const draftInstruction = '请根据当前消息、会话和引用资料生成最多 10 条可执行的功能测试用例。' +
  '只输出一个 JSON 对象，键为 cases，对应数组。每项包含 title、priority、precondition、steps、expectedResult，' +
  '字段值均为字符串，priority 取 P0、P1、P2 或 P3。缺少资料时请在用例描述中标明假设。'

export function parseCaseDrafts(reply: string): CaseDraft[] {
  const fenced = reply.match(/```(?:json)?\s*([\s\S]*?)```/i)
  try {
    const value = JSON.parse(fenced?.[1] ?? reply.trim()) as { cases?: unknown[] } | unknown[]
    const rows = Array.isArray(value) ? value : value?.cases
    if (!Array.isArray(rows) || rows.length < 1 || rows.length > 10) return []
    return rows.map(row => {
      if (!row || typeof row !== 'object') throw new Error('invalid draft')
      const item = row as Partial<CaseDraft>
      const field = (key: keyof CaseDraft, max: number) => {
        const candidate = item[key]
        return typeof candidate === 'string' ? candidate.trim().slice(0, max) : ''
      }
      return { title: field('title', 200), priority: /^P[0-3]$/.test(item.priority ?? '') ? item.priority! : 'P2',
        precondition: field('precondition', 20000), steps: field('steps', 20000), expectedResult: field('expectedResult', 20000) }
    })
  } catch {
    return []
  }
}

type TurnEvent =
  | { type: 'turn'; data: Turn }
  | { type: 'delta'; data: { text: string } }
  | { type: 'done'; data: { status: 'COMPLETED' | 'STOPPED' } }
  | { type: 'failure'; data: { message: string } }

async function json<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => null) as { data?: T; message?: string } | null
  if (!response.ok || body?.data === undefined) throw new Error(body?.message ?? `请求失败（HTTP ${response.status}）`)
  return body.data
}

export function aiTestingApi(projectId: string, pageSignal: AbortSignal) {
  const project = `/api/v1/projects/${encodeURIComponent(projectId)}`
  const base = project + '/ai-testing'

  function read<T>(path: string) {
    return fetch(base + path, { credentials: 'include', signal: pageSignal }).then(json<T>)
  }

  function functional<T>(path: string) {
    return fetch(project + '/functional' + path, { credentials: 'include', signal: pageSignal }).then(json<T>)
  }

  async function write(path: string, method: 'POST' | 'PUT', body: unknown, signal = pageSignal, stream = false) {
    const csrf = await fetch('/api/v1/auth/csrf', { credentials: 'include', signal })
      .then(json<{ headerName: string; token: string }>)
    const response = await fetch(base + path, {
      method, credentials: 'include', signal,
      headers: {
        [csrf.headerName]: csrf.token, ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        Accept: stream ? 'text/event-stream, application/json' : 'application/json',
      },
      body: body instanceof FormData ? body : JSON.stringify(body),
    })
    if (!response.ok) await json(response)
    return response
  }

  return {
    read,
    save: <T>(path: string, body: unknown, edit = false) => write(path, edit ? 'PUT' : 'POST', body).then(json<T>),
    upload: (file: File) => {
      const form = new FormData()
      form.set('file', file)
      return write('/attachments/preview', 'POST', form).then(json<Attachment>)
    },
    assets: async (): Promise<ProjectAsset[]> => {
      const [requirements, cases] = await Promise.all([
        functional<{ id: string; requirementCode: string; title: string }[]>('/requirements'),
        functional<{ id: string; caseCode: string; title: string }[]>('/cases'),
      ])
      return [
        ...requirements.map(row => ({ kind: 'REQUIREMENT' as const, id: row.id, name: row.requirementCode + ' ' + row.title })),
        ...cases.map(row => ({ kind: 'CASE' as const, id: row.id, name: row.caseCode + ' ' + row.title })),
      ]
    },
    caseCatalogs: () => functional<CaseCatalog[]>('/catalogs?scope=CASES'),
    stop: (conversationId: string, turn: Turn) => write(`/conversations/${conversationId}/turns/${turn.id}/stop`, 'POST', {
      attemptId: turn.attemptId,
    }),
    send: async (conversationId: string, body: { text: string | null; previousTurnId: string | null; retryTurnId: string | null;
      model: ModelSelection; attachments: Attachment[]; assets: AssetRef[] },
      signal: AbortSignal, onEvent: (event: TurnEvent) => void) => {
      const response = await write(`/conversations/${conversationId}/turns`, 'POST', body, signal, true)
      if (!response.body || !response.headers.get('Content-Type')?.startsWith('text/event-stream')) {
        throw new Error('智能体接口未返回对话流')
      }
      await readTurnEvents(response.body, onEvent)
    },
  }
}

export async function readTurnEvents(stream: ReadableStream<Uint8Array>, onEvent: (event: TurnEvent) => void) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let ended = false
  try {
    while (!ended) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = /\r?\n\r?\n/.exec(buffer)
      while (boundary) {
        const lines = buffer.slice(0, boundary.index).split(/\r?\n/)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        const eventName = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
        const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
        if (data && (eventName === 'turn' || eventName === 'delta' || eventName === 'done' || eventName === 'failure')) {
          onEvent({ type: eventName, data: JSON.parse(data) } as TurnEvent)
          ended = eventName === 'done' || eventName === 'failure'
        }
        if (ended) break
        boundary = /\r?\n\r?\n/.exec(buffer)
      }
    }
    if (!ended) throw new Error('对话连接已中断，请刷新后重试')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
