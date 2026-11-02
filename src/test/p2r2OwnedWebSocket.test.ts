import { expect, it, vi } from 'vitest'
import { OwnedWebSocketSessions } from '@/utils/ownedWebSocket'
const scope = (projectId = 'p1') => ({ projectId, key: projectId + ':page', current: () => true })
function setup() {
  let id = 0
  const connect = vi.fn(async () => ({ id: 'socket-' + ++id, status: 'OPEN', autoReconnect: true }))
  const close = vi.fn(async (_project: string, sessionId: string) => ({ id: sessionId, status: 'CLOSED', autoReconnect: false }))
  return { manager: new OwnedWebSocketSessions({ connect, close }), connect, close }
}
it('TC-P2R2-D14-02: unchanged page configuration reuses only its own active session', async () => {
  const q = setup(), config = { url: 'wss://fixture.test', auth: { type: 'NONE' } }
  const first = await q.manager.connect(scope(), config); expect(await q.manager.connect(scope(), config)).toEqual(first)
  expect(q.connect).toHaveBeenCalledTimes(1); expect(q.close).not.toHaveBeenCalled()
})
it('TC-P2R2-D14-06: manual close is terminal, repeat close does not reopen or resubscribe', async () => {
  const q = setup(); await q.manager.connect(scope(), { reconnect: true, subscriptions: ['fixture'] }); const closed = await q.manager.close()
  expect(closed).toMatchObject({ status: 'CLOSED', autoReconnect: false }); await q.manager.close(); expect(q.close).toHaveBeenCalledTimes(1); expect(q.connect).toHaveBeenCalledTimes(1)
})
it('TC-P2R2-D14-07: foreign session updates cannot change which owned session gets closed', async () => {
  const q = setup(); const other = new OwnedWebSocketSessions({ connect: q.connect, close: q.close })
  const a = await q.manager.connect(scope(), {}), b = await other.connect(scope('p2'), {})
  q.manager.update(b!); await q.manager.close(); expect(q.close).toHaveBeenCalledWith('p1', a!.id); expect(q.close).not.toHaveBeenCalledWith('p2', b!.id); expect(other.session).toEqual(b)
})
it('TC-P2R2-D14-04: a failed close retains the exact old owner for explicit retry', async () => {
  const q = setup(); const old = await q.manager.connect(scope(), { url: 'old' }); q.close.mockRejectedValueOnce(new Error('controlled close failure'))
  await expect(q.manager.connect(scope(), { url: 'new' })).rejects.toThrow('controlled close failure'); expect(q.manager.session).toEqual(old); expect(q.connect).toHaveBeenCalledTimes(1)
  await q.manager.connect(scope(), { url: 'new' }); expect(q.close).toHaveBeenLastCalledWith('p1', old!.id); expect(q.connect).toHaveBeenCalledTimes(2)
})
it('TC-P2R2-D14-08: invalid or failed creation never takes ownership of a foreign handle', async () => {
  const q = setup(); q.connect.mockRejectedValueOnce(new Error('controlled invalid target'))
  await expect(q.manager.connect(scope(), { url: 'bad' })).rejects.toThrow('controlled invalid target'); expect(q.manager.session).toBeNull(); expect(q.close).not.toHaveBeenCalled()
})
