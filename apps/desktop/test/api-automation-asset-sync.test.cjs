'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const crypto = require('node:crypto')
const { ApiAutomationAssetSync, validateItem, keyOf, empty, validateState } = require('../app/src/api-automation-asset-sync.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26)
const server = 'https://sync.example.test', copy = value => globalThis.structuredClone(value)
const reusable = (name = '订单请求', url = '/old') => ({ kind: 'REUSABLE_STEP', name, definition: { description: '',
  steps: [{ id: 'get', type: 'REQUEST', name: '读取', config: { method: 'GET', url: 'https://api.example.test' + url } }] } })
const dataSet = (name = '订单数据') => ({ kind: 'DATA_SET', name, definition: { sourceFormat: 'JSON', columns: ['id'], rows: [{ id: '1' }], sourceFileLink: null } })
const profile = (name = '测试配置', environmentCode = 'test') => ({ kind: 'EXECUTION_PROFILE', name, definition: { environmentCode, variables: { region: 'qa' } } })
function fixture() {
  const rows = { local: [reusable(), dataSet(), profile()], remote: [] }
  const environments = { local: new Set(['test']), remote: new Set(['test']) }
  const identities = { local: new Map(), remote: new Map() }, receipts = { local: new Map(), remote: new Map() }
  const versions = { local: new Map(), remote: new Map() }
  const state = { writes: [], loseResponse: false, offline: false, actor, links: [] }
  let next = 0, persisted
  const id = () => 'Z'.repeat(22) + String(++next).padStart(4, '0')
  const itemIds = side => Object.fromEntries(rows[side].map(item => {
    const key = item.kind + '\n' + item.name.toLowerCase()
    if (!identities[side].has(key)) identities[side].set(key, id())
    return [key, identities[side].get(key)]
  }))
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side], itemIds(side)])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) return { headerName: 'X-CSRF', token: 'fixture' }
    if (route.endsWith('/environments')) return [...environments[side]].map(environmentCode => ({ environmentCode, enabled: true }))
    if (!options) return { version: 1, scope: 'API_AUTOMATION_ASSET_SYNC', projectId: side === 'local' ? sourceId : targetId,
      actorId: side === 'local' ? localActor : state.actor, revision: stamp(side), items: copy(rows[side]), itemIds: copy(itemIds(side)) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    let receipt = receipts[side].get(body.requestKey)
    if (!receipt) {
      if (body.expectedRevision !== stamp(side)) { const error = new Error('revision changed'); error.status = 409; throw error }
      receipt = { requestKey: body.requestKey, results: body.operations.map(operation => {
        const key = keyOf(operation.item), index = rows[side].findIndex(item => keyOf(item) === key)
        if (index < 0) rows[side].push(copy(operation.item)); else rows[side][index] = copy(operation.item)
        versions[side].set(key, (versions[side].get(key) || 0) + 1)
        return { operationId: operation.operationId, item: copy(operation.item) }
      }) }
      receipts[side].set(body.requestKey, copy(receipt))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(receipt)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async value => { if (value.server !== server) throw new Error('server changed') },
    fileLinkContext: async () => ({ sourceProject: sourceId, targetProject: targetId, links: state.links }) }
  const client = new ApiAutomationAssetSync(options)
  const bind = async () => { const plan = await client.preview(sourceId, targetId, server); await client.bind(plan.token); await client.run() }
  return { rows, environments, identities, state, versions, options, client, bind }
}
test('preview is read-only; current assets and full data rows copy then edits return once', async () => {
  const f = fixture(); const plan = await f.client.preview(sourceId, targetId, server)
  assert.equal(plan.upload, 3); assert.equal(f.state.writes.length, 0)
  await f.client.bind(plan.token); await f.client.run(); assert.deepEqual(f.rows.remote, f.rows.local)
  const before = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, before)
  f.rows.remote.find(item => item.kind === 'DATA_SET').definition.rows.push({ id: '2' })
  await f.client.run()
  assert.equal(f.rows.local.find(item => item.kind === 'DATA_SET').definition.rows.length, 2)
  const ids = copy(f.client.state.identities); await f.client.run(); assert.deepEqual(f.client.state.identities, ids)
})
test('two-sided public step edits need an explicit choice and do not rewrite twice', async () => {
  const f = fixture(); await f.bind()
  f.rows.local[0].definition.description = '本地'; f.rows.remote[0].definition.description = '网页'
  await f.client.run(); const conflict = f.client.state.conflicts[0]
  assert.equal(conflict.canResolve, true)
  await f.client.resolve(conflict.key, 'local'); assert.equal(f.rows.remote[0].definition.description, '本地')
  const before = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, before)
})
test('deletion and same-name recreation pause the affected item while independent data continues', async () => {
  const f = fixture(); await f.bind(); const deleted = f.rows.remote.splice(1, 1)[0]
  f.rows.local.push(reusable('独立步骤'))
  await f.client.run(); assert.ok(f.client.state.conflicts.some(value => value.local?.kind === 'DATA_SET'))
  assert.ok(!f.rows.remote.some(item => item.kind === 'DATA_SET'))
  assert.ok(f.rows.remote.some(item => item.name === '独立步骤'))
  f.rows.remote.push(deleted); f.identities.remote.set('DATA_SET\n订单数据', 'X'.repeat(26))
  await f.client.run(); const conflict = f.client.state.conflicts.find(value => value.local?.kind === 'DATA_SET')
  assert.equal(conflict.canResolve, true)
  await f.client.resolve(conflict.key, 'local'); await f.client.run(); assert.equal(f.client.state.conflicts.length, 0)
})
test('missing environment pauses only the profile and recovers when enabled', async () => {
  const f = fixture(); f.environments.remote.clear(); await f.bind()
  assert.ok(f.client.state.conflicts.some(value => value.local?.kind === 'EXECUTION_PROFILE'))
  assert.ok(f.rows.remote.some(item => item.kind === 'DATA_SET'))
  f.environments.remote.add('test'); await f.client.run()
  assert.equal(f.client.state.conflicts.length, 0)
})
test('paired source file maps physically; lost pair pauses the dataset without blocking other assets', async () => {
  const f = fixture(), key = 'E'.repeat(26), localId = 'F'.repeat(26), remoteId = 'G'.repeat(26)
  f.state.links = [{ key, localId, remoteId }]
  f.rows.local[1].definition.sourceFileLink = `/api/v1/projects/${sourceId}/files/${localId}/download`
  await f.bind()
  assert.equal(f.rows.remote.find(item => item.kind === 'DATA_SET').definition.sourceFileLink, `/api/v1/projects/${targetId}/files/${remoteId}/download`)
  assert.equal(f.client.state.bases.find(item => item.kind === 'DATA_SET').definition.sourceFileLink, `testhub-file:${key}`)
  f.state.links = []; f.rows.local.push(reusable('继续同步'))
  await f.client.run()
  assert.ok(f.client.state.conflicts.some(value => value.local?.kind === 'DATA_SET' && !value.canResolve))
  assert.ok(f.rows.remote.some(item => item.name === '继续同步'))
})
test('lost response survives restart and retries the exact original request without another revision', async () => {
  const f = fixture(); await f.bind(); f.rows.local[0].definition.description = '新内容'; f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending)
  const versions = copy(f.versions.remote); f.state.loseResponse = false
  const restarted = new ApiAutomationAssetSync(f.options); await restarted.run()
  assert.deepEqual(f.versions.remote, versions); assert.equal(restarted.state.pending, null)
  const attempts = f.state.writes.filter(value => value.body.requestKey === pending.request.requestKey)
  assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('account changes and restored backups prevent further writes until rebind', async () => {
  const f = fixture(); await f.bind(); const writes = f.state.writes.length
  f.state.actor = 'X'.repeat(26); await assert.rejects(f.client.run(), /账号.*变化/); assert.equal(f.state.writes.length, writes)
  f.state.actor = actor; await f.client.suspendForRestore(); assert.equal(f.client.state.binding.enabled, false)
  await f.client.run(); assert.equal(f.state.writes.length, writes)
  await assert.rejects(f.client.enabled(true), /恢复过备份/)
  const rows = copy(f.rows); await f.client.unbind(); assert.deepEqual(f.rows, rows)
})
test('extra fields, references in primitive steps and oversized collections fail before writes', async () => {
  for (const mutation of [rows => { rows[0].definition.version = 99 }, rows => { rows[0].definition.steps[0].type = 'REUSABLE_STEP' },
    rows => { rows[1].definition.rows = [{ unknown: '1' }] }, rows => { rows[2].definition.variables = { region: null } },
    rows => { rows.push(...Array.from({ length: 101 }, (_, i) => dataSet('extra' + i))) }]) {
    const f = fixture(); mutation(f.rows.local)
    await assert.rejects(f.client.preview(sourceId, targetId, server)); assert.equal(f.state.writes.length, 0)
  }
  assert.equal(validateItem(reusable()).kind, 'REUSABLE_STEP')
})
