'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const crypto = require('node:crypto')
const { ApiDesignSync, validateItem, keyOf, empty, validateState } = require('../app/src/api-design-sync.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26)
const server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const catalog = (path, sortOrder = 0) => ({ kind: 'CATALOG', name: JSON.stringify(path), definition: { sortOrder } })
const definition = (current = '1.0.0') => ({ kind: 'DEFINITION', name: 'GET /orders/{id}', definition: { catalogPath: '["接口"]', displayName: '查询订单', currentVersionName: current } })
const version = (name = '1.0.0', status = 'DRAFT', summary = '') => ({ kind: 'VERSION', name: 'GET /orders/{id}\n' + name,
  definition: { summary, description: '', headers: [], query: [], pathParameters: [], requestBody: '', responseBody: '{}', exampleResponse: {}, status } })
const testCase = (name = '成功查询') => ({ kind: 'CASE', name: 'GET /orders/{id}\n' + name,
  definition: { environmentCode: null, requestSnapshot: { path: '/orders/1' }, expectedStatus: 200, responseValidation: '', savedResponseBody: '' } })
const graph = () => [catalog(['接口']), definition(), version(), testCase()]

function fixture() {
  const state = { writes: [], offline: false, loseResponse: false }, rows = { local: graph(), remote: [] }
  const environments = { local: new Set(), remote: new Set() }
  const identities = { local: new Map(), remote: new Map() }, receipts = { local: new Map(), remote: new Map() }
  let nextId = 0, persisted
  const id = () => 'Z'.repeat(24) + String(++nextId).padStart(2, '0')
  const itemIds = side => {
    const result = {}
    for (const item of rows[side]) {
      const key = item.kind + '\n' + item.name.toLowerCase()
      if (!identities[side].has(key)) identities[side].set(key, id())
      result[key] = identities[side].get(key)
    }
    return result
  }
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side], itemIds(side)])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) return { headerName: 'X-CSRF', token: 'fixture' }
    if (route.endsWith('/environments')) return [...environments[side]].map(environmentCode => ({ environmentCode, enabled: true }))
    if (!options) return { version: 1, scope: 'API_DESIGN_SYNC', projectId: side === 'local' ? sourceId : targetId,
      actorId: side === 'local' ? localActor : actor, revision: stamp(side), items: copy(rows[side]), itemIds: copy(itemIds(side)) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    let receipt = receipts[side].get(body.requestKey)
    if (!receipt) {
      if (body.expectedRevision !== stamp(side)) { const error = new Error('revision changed'); error.status = 409; throw error }
      receipt = { requestKey: body.requestKey, results: body.operations.map(operation => {
        const index = rows[side].findIndex(item => keyOf(item) === keyOf(operation.item))
        if (index < 0) rows[side].push(copy(operation.item)); else rows[side][index] = copy(operation.item)
        return { operationId: operation.operationId, item: copy(operation.item) }
      }) }
      receipts[side].set(body.requestKey, copy(receipt))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(receipt)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async value => { if (value.server !== server) throw new Error('server changed') } }
  const client = new ApiDesignSync(options)
  const bind = async () => { const preview = await client.preview(sourceId, targetId, server); await client.bind(preview.token); await client.run() }
  return { state, rows, identities, environments, client, options, bind }
}

test('portable interface graph copies in one batch and later case edits return without new IDs', async () => {
  const fixtureData = fixture(), { client, rows, state } = fixtureData
  const preview = await client.preview(sourceId, targetId, server)
  assert.equal(preview.upload, 4); assert.equal(state.writes.length, 0)
  await client.bind(preview.token); await client.run()
  assert.deepEqual(rows.remote, rows.local)
  const writes = state.writes.length
  await client.run(); assert.equal(state.writes.length, writes)
  rows.remote.find(item => item.kind === 'CASE').definition.requestSnapshot.path = '/orders/2'
  await client.run()
  assert.equal(rows.local.find(item => item.kind === 'CASE').definition.requestSnapshot.path, '/orders/2')
})

test('historical version mismatch asks for a side and unrelated new catalog still syncs', async () => {
  const f = fixture(); await f.bind()
  f.rows.local.find(item => item.kind === 'VERSION').definition.summary = '本地历史修改'
  f.rows.remote.find(item => item.kind === 'VERSION').definition.summary = '网页历史修改'
  f.rows.local.push(catalog(['无关']))
  await f.client.run()
  const conflict = f.client.state.conflicts.find(value => value.local?.kind === 'VERSION')
  assert.equal(conflict.canResolve, true)
  assert.ok(f.rows.remote.some(item => item.name === '["无关"]'))
  await f.client.resolve(conflict.key, 'local')
  assert.equal(f.rows.remote.find(item => item.kind === 'VERSION').definition.summary, '本地历史修改')
})

test('choosing draft content keeps published status on both sides', async () => {
  const f = fixture(); await f.bind()
  f.rows.local.find(item => item.kind === 'VERSION').definition.summary = '本地修订'
  f.rows.remote.find(item => item.kind === 'VERSION').definition.status = 'PUBLISHED'
  await f.client.run()
  const conflict = f.client.state.conflicts.find(value => value.local?.kind === 'VERSION')
  assert.equal(conflict.canResolve, true)
  await f.client.resolve(conflict.key, 'local')
  for (const side of ['local', 'remote']) {
    assert.equal(f.rows[side].find(item => item.kind === 'VERSION').definition.summary, '本地修订')
    assert.equal(f.rows[side].find(item => item.kind === 'VERSION').definition.status, 'PUBLISHED')
  }
  await f.client.run()
  assert.equal(f.client.state.conflicts.length, 0)
})

test('deleted parent pauses dependent interface and a new unrelated item continues', async () => {
  const f = fixture(); await f.bind()
  f.rows.remote = []
  f.rows.local.push(catalog(['其他']))
  await f.client.run()
  assert.ok(f.client.state.conflicts.some(value => value.local?.kind === 'CATALOG'))
  assert.ok(f.rows.remote.some(item => item.name === '["其他"]'))
  assert.ok(!f.rows.remote.some(item => item.kind === 'CATALOG' && item.name === '["接口"]'))
})

test('draft to published promotion propagates; unrelated descriptor case pauses only itself', async () => {
  const f = fixture(); await f.bind()
  f.rows.local.find(item => item.kind === 'VERSION').definition.status = 'PUBLISHED'
  f.rows.local.push({ ...testCase('gRPC'), definition: { ...testCase('gRPC').definition, requestSnapshot: { grpcDescriptorId: 'X'.repeat(26) } } })
  await f.client.run()
  assert.equal(f.rows.remote.find(item => item.kind === 'VERSION').definition.status, 'PUBLISHED')
  assert.ok(!f.rows.remote.some(item => item.name.endsWith('gRPC')))
  assert.ok(f.client.state.conflicts.some(value => value.local?.name.endsWith('gRPC')))
})

test('case with missing destination environment pauses while unrelated catalog continues', async () => {
  const f = fixture(); await f.bind()
  f.environments.local.add('qa')
  f.rows.local.push({ ...testCase('需环境'), definition: { ...testCase('需环境').definition, environmentCode: 'qa' } })
  f.rows.local.push(catalog(['独立']))
  await f.client.run()
  assert.ok(f.client.state.conflicts.some(value => value.local?.name.endsWith('需环境') && /qa/.test(value.reason)))
  assert.ok(f.rows.remote.some(item => item.name === '["独立"]'))
  assert.ok(!f.rows.remote.some(item => item.name.endsWith('需环境')))
  f.environments.remote.add('qa'); await f.client.run()
  assert.ok(f.rows.remote.some(item => item.name.endsWith('需环境')))
})

test('lost response retries the same durable request after restart', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(catalog(['新增']))
  f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/)
  const pending = copy(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false
  const restarted = new ApiDesignSync(f.options); await restarted.run()
  assert.equal(f.rows.remote.length, count)
  assert.equal(restarted.state.pending, null)
  const attempts = f.state.writes.filter(value => value.body.requestKey === pending.request.requestKey)
  assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})

test('invalid graph and extra fields fail before any write', async () => {
  for (const change of [rows => { rows[0].name = '[ "接口" ]' }, rows => { rows[1].definition.currentVersionName = '9.9.9' },
    rows => { rows[2].definition.publishedBy = actor }, rows => { rows[3].definition.requestSnapshot = [] }]) {
    const f = fixture(); change(f.rows.local)
    await assert.rejects(f.client.preview(sourceId, targetId, server))
    assert.equal(f.state.writes.length, 0)
  }
  assert.equal(validateItem(version()).kind, 'VERSION')
})
