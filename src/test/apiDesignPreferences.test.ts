import { describe, expect, it, vi } from 'vitest'
import { createApiDesignPreferencesClient, emptyApiDesignPreferences, readLegacyPreferences, validatePreferences, type ApiDesignPreferences, type ApiDesignPreferencesState } from '@/utils/apiDesignPreferences'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const initial = (): ApiDesignPreferences => ({ revision: 0, state: emptyApiDesignPreferences() })
const folder = (id: string, parentId: string | null = null) => ({ id, parentId, name: id })
const addFolder = (id: string) => (state: ApiDesignPreferencesState) => ({ ...state, folders: [...state.folders, folder(id)] })
function setup() {
  const records = new Map<string, ApiDesignPreferences>()
  const read = vi.fn(async (id: string) => structuredClone(records.get(id) ?? initial()))
  const write = vi.fn(async (id: string, value: ApiDesignPreferences) => {
    if (value.revision !== (records.get(id)?.revision ?? 0)) throw { status: 409 }
    const result = structuredClone({ ...value, revision: value.revision + 1 })
    records.set(id, result)
    return result
  })
  return { client: createApiDesignPreferencesClient({ read, write }), records, read, write }
}

describe('API design preferences state transitions', () => {
  it('queues saves after load and uses each acknowledged revision and state', async () => {
    const { client, read, write } = setup()
    const gate = deferred<ApiDesignPreferences>()
    read.mockReturnValueOnce(gate.promise)
    const loading = client.load('a')
    const first = client.save('a', addFolder('one'))
    const second = client.save('a', addFolder('two'))
    expect(write).not.toHaveBeenCalled()
    gate.resolve(initial())
    await loading
    await first
    expect((await second).state.folders.map(f => f.id)).toEqual(['one', 'two'])
    expect(write.mock.calls.map(([, value]) => value.revision)).toEqual([0, 1])
  })

  it('serializes delayed writes while allowing another project to save independently', async () => {
    const { client, write } = setup()
    await Promise.all([client.load('a'), client.load('b')])
    const gate = deferred<ApiDesignPreferences>()
    write.mockReturnValueOnce(gate.promise)
    const first = client.save('a', addFolder('one'))
    const second = client.save('a', addFolder('two'))
    // This write uses its own project revision despite project a still being pending.
    expect((await client.save('b', addFolder('other'))).state.folders).toEqual([folder('other')])
    expect(write.mock.calls.map(([id]) => id)).toEqual(['a', 'b'])
    // The mocked first write did not update records; acknowledge both a writes explicitly.
    write.mockImplementationOnce(async (_id, value) => ({ ...value, revision: value.revision + 1 }))
    gate.resolve({ revision: 1, state: addFolder('one')(emptyApiDesignPreferences()) })
    await first
    const result = await second
    expect(result.revision).toBe(2)
    expect(result.state.folders.map(f => f.id)).toEqual(['one', 'two'])
  })

  it('does not advance saved state on failure and permits a retry with the original revision', async () => {
    const { client, write } = setup()
    await client.load('a')
    write.mockRejectedValueOnce(new Error('offline'))
    await expect(client.save('a', addFolder('one'))).rejects.toThrow('offline')
    const result = await client.save('a', addFolder('one'))
    expect(result.state.folders).toEqual([folder('one')])
    expect(write.mock.calls.map(([, value]) => value.revision)).toEqual([0, 0])
  })

  it('blocks queued and later saves after 409 until an explicit fresh load', async () => {
    const { client, records, write } = setup()
    await client.load('a')
    records.set('a', { revision: 1, state: addFolder('remote')(emptyApiDesignPreferences()) })
    const first = client.save('a', addFolder('local'))
    const second = client.save('a', addFolder('queued'))
    await expect(first).rejects.toThrow('409')
    await expect(second).rejects.toThrow('409')
    expect(write).toHaveBeenCalledTimes(1)
    expect((await client.load('a')).state.folders).toEqual([folder('remote')])
    expect((await client.save('a', addFolder('local'))).state.folders.map(f => f.id)).toEqual(['remote', 'local'])
  })

  it('serializes a reload behind a pending save', async () => {
    const { client, write, read } = setup()
    await client.load('a')
    const gate = deferred<ApiDesignPreferences>()
    write.mockReturnValueOnce(gate.promise)
    const save = client.save('a', addFolder('one'))
    const reload = client.load('a')
    await Promise.resolve(); await Promise.resolve()
    expect(read).toHaveBeenCalledTimes(1)
    gate.resolve({ revision: 1, state: addFolder('one')(emptyApiDesignPreferences()) })
    await save; await reload
    expect(read).toHaveBeenCalledTimes(2)
  })

  it('imports current-origin legacy data only at revision zero and leaves all old values intact', async () => {
    const { client, records, write } = setup()
    const old = new Map([
      ['testhub:quick-request:a:folders', JSON.stringify([{ ...folder('old'), replayDraft: 'secret' }])],
      ['testhub:quick-request:a:hidden', '["h"]'],
      ['testhub:quick-request:a:folder-ids', '{"h":"old"}'],
      ['testhub:api-tree:a:display-mode', 'URL'],
      ['testhub:quick-request:a:copies', 'must not read'],
    ])
    const getItem = vi.fn((key: string) => old.get(key) ?? null)
    const legacy = vi.fn(() => readLegacyPreferences({ getItem }, 'a'))
    const loaded = await client.load('a', legacy)
    expect(loaded.revision).toBe(1)
    expect(loaded.state).toEqual({ folders: [folder('old')], hiddenIds: ['h'], folderIds: { h: 'old' }, displayMode: 'URL' })
    expect(JSON.stringify(write.mock.calls)).not.toContain('secret')
    expect(getItem).not.toHaveBeenCalledWith('testhub:quick-request:a:copies')
    expect(old.size).toBe(5)
    records.set('a', { revision: 2, state: emptyApiDesignPreferences() })
    expect((await client.load('a', legacy)).state.folders).toEqual([])
    expect(legacy).toHaveBeenCalledTimes(1)
  })

  it('reports migration races without retrying or overwriting the other session', async () => {
    const { client, write } = setup()
    write.mockRejectedValueOnce({ status: 409 })
    await expect(client.load('a', () => addFolder('old')(emptyApiDesignPreferences()))).rejects.toThrow('409')
    await expect(client.save('a', addFolder('old'))).rejects.toThrow('409')
    expect(write).toHaveBeenCalledTimes(1)
  })

  it('never calls transport for empty projects or save-before-load', async () => {
    const { client, read, write } = setup()
    await expect(client.load('')).rejects.toThrow('选择项目')
    await expect(client.save('', addFolder('x'))).rejects.toThrow('选择项目')
    await expect(client.save('a', addFolder('x'))).rejects.toThrow('先加载')
    expect(read).not.toHaveBeenCalled(); expect(write).not.toHaveBeenCalled()
  })

  it('invalidates queued work on identity change, including automatic legacy migration', async () => {
    const { client, read, write } = setup()
    const gate = deferred<ApiDesignPreferences>()
    read.mockReturnValueOnce(gate.promise)
    const legacy = vi.fn(() => emptyApiDesignPreferences())
    const loading = client.load('a', legacy)
    const saving = client.save('a', addFolder('old-user'))
    await Promise.resolve(); await Promise.resolve()
    client.dispose()
    gate.resolve(initial())
    await expect(loading).rejects.toThrow('登录身份')
    await expect(saving).rejects.toThrow('登录身份')
    expect(write).not.toHaveBeenCalled(); expect(legacy).not.toHaveBeenCalled()
  })

  it('does not accept malformed or unacknowledged server responses as success', async () => {
    const { client, write } = setup()
    await client.load('a')
    write.mockResolvedValueOnce(initial())
    await expect(client.save('a', addFolder('x'))).rejects.toThrow('revision')
    expect((await client.save('a', addFolder('x'))).revision).toBe(1)
  })

  it('isolates consumer mutations from acknowledged state and strips non-tree properties', async () => {
    const { client, write } = setup()
    const loaded = await client.load('a')
    loaded.state.folders.push(folder('accidental'))
    await client.save('a', state => ({ ...state, copies: ['secret'], replayDraft: 'secret', folders: [{ ...folder('safe'), body: 'secret' }] }))
    expect(write.mock.calls[0][1].state).toEqual({ ...emptyApiDesignPreferences(), folders: [folder('safe')] })
  })
})

describe('preferences bounds', () => {
  it('checks UTF-16 lengths, hierarchy integrity, depth and counts before a request', () => {
    const value = initial()
    value.state.folders = [{ ...folder('a'), name: '😀'.repeat(80) }]
    expect(() => validatePreferences(value)).not.toThrow()
    value.state.folders[0].name += 'x'
    expect(() => validatePreferences(value)).toThrow('160')
    value.state.folders = [folder('a', 'b'), folder('b', 'a')]
    expect(() => validatePreferences(value)).toThrow('循环')
    value.state.folders = [folder('a', 'missing')]
    expect(() => validatePreferences(value)).toThrow('父目录不存在')
    value.state.folders = Array.from({ length: 17 }, (_, i) => folder(String(i), i ? String(i - 1) : null))
    expect(() => validatePreferences(value)).toThrow('16')
    value.state.folders = Array.from({ length: 501 }, (_, i) => folder(String(i)))
    expect(() => validatePreferences(value)).toThrow('500')
    value.state = { ...emptyApiDesignPreferences(), hiddenIds: Array.from({ length: 2001 }, (_, i) => String(i)) }
    expect(() => validatePreferences(value)).toThrow('2000')
    value.state = { ...emptyApiDesignPreferences(), folderIds: { request: 'missing' } }
    expect(() => validatePreferences(value)).toThrow('引用存在')
    value.state = { ...emptyApiDesignPreferences(), hiddenIds: Array.from({ length: 2000 }, (_, i) => `${i}${'汉'.repeat(150)}`) }
    expect(() => validatePreferences(value)).toThrow('256 KiB')
  })
})
