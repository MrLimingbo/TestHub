export interface ApiDesignPreferencesState {
  folders: { id: string; parentId: string | null; name: string }[]
  hiddenIds: string[]
  folderIds: Record<string, string>
  displayMode: 'NAME' | 'URL'
}
export interface ApiDesignPreferences { revision: number; state: ApiDesignPreferencesState }
export const emptyApiDesignPreferences = (): ApiDesignPreferencesState => ({ folders: [], hiddenIds: [], folderIds: {}, displayMode: 'NAME' })

// Explicit allowlist: request bodies, copies and replay drafts must never enter this payload.
export function copyPreferences(state: ApiDesignPreferencesState): ApiDesignPreferencesState {
  return {
    folders: state.folders.map(({ id, parentId, name }) => ({ id, parentId, name })),
    hiddenIds: [...state.hiddenIds], folderIds: { ...state.folderIds }, displayMode: state.displayMode,
  }
}
export function readLegacyPreferences(storage: Pick<Storage, 'getItem'>, projectId: string): ApiDesignPreferencesState | null {
  const key = (name: string) => `testhub:quick-request:${projectId}:${name}`
  const raw = [storage.getItem(key('folders')), storage.getItem(key('hidden')), storage.getItem(key('folder-ids')), storage.getItem(`testhub:api-tree:${projectId}:display-mode`)]
  if (raw.every(value => value === null)) return null
  const folders = JSON.parse(raw[0] ?? '[]') as unknown
  const hiddenIds = JSON.parse(raw[1] ?? '[]') as unknown
  const folderIds = JSON.parse(raw[2] ?? '{}') as unknown
  if (!Array.isArray(folders) || !folders.every(f => f && typeof f.id === 'string' && typeof f.name === 'string' && (f.parentId == null || typeof f.parentId === 'string'))
    || !Array.isArray(hiddenIds) || !hiddenIds.every(id => typeof id === 'string')
    || !folderIds || typeof folderIds !== 'object' || Array.isArray(folderIds) || !Object.values(folderIds).every(id => typeof id === 'string')) {
    throw new Error('当前 origin 的旧目录数据格式无效，未导入；原数据已保留')
  }
  return copyPreferences({ folders: folders.map(f => ({ ...f, parentId: f.parentId ?? null })), hiddenIds, folderIds: folderIds as Record<string, string>, displayMode: raw[3] === 'URL' ? 'URL' : 'NAME' })
}

export function validatePreferences(value: ApiDesignPreferences) {
  const { state, revision } = value
  if (!Number.isSafeInteger(revision) || revision < 0) throw new Error('目录/UI 状态 revision 无效')
  if (!state || !Array.isArray(state.folders) || !Array.isArray(state.hiddenIds) || !state.folderIds || typeof state.folderIds !== 'object' || Array.isArray(state.folderIds)
    || !['NAME', 'URL'].includes(state.displayMode)) throw new Error('目录/UI 状态格式无效')
  if (state.folders.length > 500 || state.hiddenIds.length > 2000 || Object.keys(state.folderIds).length > 2000) throw new Error('目录最多 500 项，隐藏项和请求归属最多各 2000 项')
  const text = (value: unknown, label: string) => {
    if (typeof value !== 'string' || !value.trim() || value.length > 160 || Array.from(value).some(char => { const code = char.charCodeAt(0); return code < 32 || (code >= 127 && code <= 159) })) throw new Error(`${label}不能为空、包含控制字符或超过 160 个 UTF-16 单元`)
  }
  const parents = new Map<string, string | null>()
  for (const folder of state.folders) {
    if (!folder) throw new Error('目录格式无效')
    text(folder.id, '目录 ID'); text(folder.name, '目录名称')
    if (folder.parentId !== null) text(folder.parentId, '父目录 ID')
    if (parents.has(folder.id)) throw new Error('目录 ID 不能重复')
    parents.set(folder.id, folder.parentId)
  }
  for (const id of parents.keys()) {
    const seen = new Set<string>()
    for (let cursor: string | null = id; cursor !== null; cursor = parents.get(cursor)!) {
      if (!parents.has(cursor)) throw new Error('父目录不存在')
      if (seen.has(cursor)) throw new Error('目录不能形成循环')
      seen.add(cursor)
      if (seen.size > 16) throw new Error('目录深度不能超过 16 层')
    }
  }
  for (const id of state.hiddenIds) text(id, '隐藏 ID')
  if (new Set(state.hiddenIds).size !== state.hiddenIds.length) throw new Error('隐藏 ID 不能重复')
  for (const [id, folderId] of Object.entries(state.folderIds)) {
    text(id, '请求引用 ID'); text(folderId, '目标目录 ID')
    if (!parents.has(folderId)) throw new Error('请求归属必须引用存在的目录')
  }
  if (new TextEncoder().encode(JSON.stringify(value)).byteLength > 262144) throw new Error('目录/UI 状态请求体不能超过 256 KiB')
}

export class PreferencesConflictError extends Error {
  constructor() { super('目录/UI 状态已被其他会话修改（409）。当前输入已保留，请重新加载服务端状态后核对并重试。') }
}

// One instance per authenticated identity. Reads and writes share a per-project queue.
export function createApiDesignPreferencesClient(transport: {
  read: (projectId: string) => Promise<ApiDesignPreferences>
  write: (projectId: string, value: ApiDesignPreferences) => Promise<ApiDesignPreferences>
}) {
  const values = new Map<string, ApiDesignPreferences>()
  const queues = new Map<string, Promise<unknown>>()
  const conflicts = new Set<string>()
  let disposed = false
  function active() { if (disposed) throw new Error('登录身份已变化，请重新加载目录/UI 状态') }
  function enqueue<T>(projectId: string, task: () => Promise<T>): Promise<T> {
    if (!projectId) return Promise.reject(new Error('请先选择项目'))
    const result = (queues.get(projectId) ?? Promise.resolve()).catch(() => undefined).then(() => { active(); return task() })
    const tail = result.catch(() => undefined)
    queues.set(projectId, tail)
    void tail.then(() => { if (queues.get(projectId) === tail) queues.delete(projectId) })
    return result
  }
  async function write(projectId: string, state: ApiDesignPreferencesState) {
    if (conflicts.has(projectId)) throw new PreferencesConflictError()
    const current = values.get(projectId)
    if (!current) throw new Error('请先加载目录/UI 状态')
    const payload = { revision: current.revision, state: copyPreferences(state) }
    validatePreferences(payload)
    active()
    try {
      const result = await transport.write(projectId, payload)
      active()
      validatePreferences(result)
      if (result.revision !== current.revision + 1) throw new Error('服务端未返回递增 revision，请重新加载后核对保存结果')
      values.set(projectId, { revision: result.revision, state: copyPreferences(result.state) })
      return { revision: result.revision, state: copyPreferences(result.state) }
    } catch (error) {
      if (typeof error === 'object' && error !== null && 'status' in error && error.status === 409) {
        conflicts.add(projectId)
        throw new PreferencesConflictError()
      }
      throw error
    }
  }
  return {
    dispose: () => { disposed = true },
    load: (projectId: string, legacy?: () => ApiDesignPreferencesState | null) => enqueue(projectId, async () => {
      const result = await transport.read(projectId)
      active()
      validatePreferences(result)
      values.set(projectId, { revision: result.revision, state: copyPreferences(result.state) })
      conflicts.delete(projectId)
      const imported = result.revision === 0 ? legacy?.() : null
      return imported ? write(projectId, imported) : { revision: result.revision, state: copyPreferences(result.state) }
    }),
    save: (projectId: string, update: (state: ApiDesignPreferencesState) => ApiDesignPreferencesState) => enqueue(projectId, () => {
      const current = values.get(projectId)
      if (!current) throw new Error('请先加载目录/UI 状态')
      return write(projectId, update(copyPreferences(current.state)))
    }),
  }
}
