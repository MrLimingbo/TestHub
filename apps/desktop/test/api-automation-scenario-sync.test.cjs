'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const crypto = require('node:crypto')
const { ApiAutomationScenarioSync, validateState, validateItem, empty, keyOf } = require('../app/src/api-automation-scenario-sync.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26)
const server = 'https://sync.example.test', clone = value => globalThis.structuredClone(value)
const primitive = url => [{ id: 'get', type: 'REQUEST', name: '读取', config: { method: 'GET', url: 'https://api.example.test/' + url } }]
const scene = (name, steps = primitive(name)) => ({ name, definition: { description: '', environmentCode: 'test', failureStrategy: 'STOP', steps, variables: {}, preRequestScript: '', postResponseScript: '' } })
const publicReference = () => ({ id: 'public', type: 'REUSABLE_STEP', name: '公共旧', config: { sourceName: 'Public', revision: { name: 'Public', description: 'old', steps: primitive('old-public') } } })
const childReference = name => ({ id: 'child', type: 'SUB_SCENARIO', name: '子旧', config: { sourceName: name, revision: { name, ...scene(name, primitive('old-child')).definition } } })
const parent = () => scene('Parent', [publicReference(), childReference('Child')])
function fixture() {
  const rows = { local: [scene('Child'), parent()], remote: [] }
  const publicIds = { local: new Map([['Public', 'E'.repeat(26)]]), remote: new Map([['Public', 'F'.repeat(26)]]) }
  const blockedNames = { local: {}, remote: {} }, identities = { local: new Map(), remote: new Map() }
  const environments = { local: new Set(['test']), remote: new Set(['test']) }
  const state = { writes: [], loseResponse: false, actor, links: [] }, receipts = { local: new Map(), remote: new Map() }
  const versions = { local: new Map(), remote: new Map() }
  let persisted, next = 0
  const itemIds = side => Object.fromEntries(rows[side].map(item => {
    const key = keyOf(item)
    if (!identities[side].has(key)) identities[side].set(key, 'Z'.repeat(22) + String(++next).padStart(4, '0'))
    return [key, identities[side].get(key)]
  }))
  const referenceIds = side => ({ ...Object.fromEntries([...publicIds[side]].map(([name, id]) => ['REUSABLE_STEP\n' + name, id])),
    ...Object.fromEntries(rows[side].map(item => ['SCENARIO\n' + item.name, itemIds(side)[keyOf(item)]])) })
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side], itemIds(side), referenceIds(side), blockedNames[side]])).digest('hex')
  const store = { read: async () => clone(persisted ?? empty()), write: async value => { validateState(value); persisted = clone(value) } }
  const request = side => async (route, options) => {
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '同步测试', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) return { headerName: 'X-CSRF', token: 'fixture' }
    if (route.endsWith('/environments')) return [...environments[side]].map(environmentCode => ({ environmentCode, enabled: true }))
    if (!options) return { version: 1, scope: 'API_AUTOMATION_SCENARIO_SYNC', actorId: side === 'local' ? localActor : state.actor,
      projectId: side === 'local' ? sourceId : targetId, revision: stamp(side), itemIds: itemIds(side), referenceIds: referenceIds(side),
      blockedNames: clone(blockedNames[side]), items: clone(rows[side].filter(item => !Object.hasOwn(blockedNames[side], item.name))) }
    assert.match(route, /\/scenarios\/sync$/)
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    let receipt = receipts[side].get(body.requestKey)
    if (!receipt) {
      if (body.expectedRevision !== stamp(side)) { const error = new Error('revision changed'); error.status = 409; throw error }
      receipt = { requestKey: body.requestKey, results: body.operations.map(operation => {
        const key = keyOf(operation.item), index = rows[side].findIndex(item => keyOf(item) === key)
        if (index < 0) rows[side].push(clone(operation.item)); else rows[side][index] = clone(operation.item)
        versions[side].set(key, (versions[side].get(key) || 0) + 1)
        return { operationId: operation.operationId, item: clone(operation.item) }
      }) }; receipts[side].set(body.requestKey, clone(receipt))
    }
    if (state.loseResponse) throw new Error('response lost')
    return clone(receipt)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async binding => { assert.equal(binding.server, server) },
    fileLinkContext: async () => ({ sourceProject: sourceId, targetProject: targetId, links: state.links }) }
  const client = new ApiAutomationScenarioSync(options)
  const bind = async () => { const preview = await client.preview(sourceId, targetId, server); await client.bind(preview.token); await client.run() }
  return { rows, publicIds, blockedNames, identities, environments, state, versions, options, client, bind }
}
test('parent and child copy together with immutable inline content and later edits return once', async () => {
  const f = fixture(); const preview = await f.client.preview(sourceId, targetId, server)
  assert.equal(preview.upload, 2); assert.equal(f.state.writes.length, 0)
  await f.client.bind(preview.token); await f.client.run(); assert.deepEqual(f.rows.local, f.rows.remote)
  const before = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, before)
  f.rows.remote.find(item => item.name === 'Parent').definition.description = 'web edit'; await f.client.run()
  assert.equal(f.rows.local.find(item => item.name === 'Parent').definition.description, 'web edit')
})
test('missing public source pauses parent but copies independent child, then resumes after asset sync', async () => {
  const f = fixture(); f.publicIds.remote.clear(); await f.bind()
  assert.ok(f.rows.remote.some(item => item.name === 'Child')); assert.ok(!f.rows.remote.some(item => item.name === 'Parent'))
  assert.ok(f.client.state.conflicts.some(value => value.local?.name === 'Parent' && !value.canResolve))
  f.publicIds.remote.set('Public', 'F'.repeat(26)); await f.client.run()
  assert.ok(f.rows.remote.some(item => item.name === 'Parent')); assert.equal(f.client.state.conflicts.length, 0)
})
test('blocked child pauses missing-child parent transitively and an unrelated scenario still copies', async () => {
  const f = fixture(); f.blockedNames.local.Child = '旧引用失效'; f.rows.local.push(scene('Independent')); await f.bind()
  assert.ok(f.rows.remote.some(item => item.name === 'Independent')); assert.ok(!f.rows.remote.some(item => item.name === 'Parent'))
  assert.ok(f.client.state.conflicts.some(value => value.key === 'child'))
  delete f.blockedNames.local.Child; await f.client.run()
  assert.ok(f.rows.remote.some(item => item.name === 'Parent')); assert.equal(f.client.state.conflicts.length, 0)
})
test('fixed child history lets parent sync while the existing child current head has a content conflict', async () => {
  const f = fixture(); await f.bind()
  f.rows.local.find(item => item.name === 'Child').definition.description = 'local child'
  f.rows.remote.find(item => item.name === 'Child').definition.description = 'remote child'
  f.rows.local.find(item => item.name === 'Parent').definition.description = 'parent edit'
  await f.client.run()
  assert.equal(f.rows.remote.find(item => item.name === 'Parent').definition.description, 'parent edit')
  assert.ok(f.client.state.conflicts.some(value => value.key === 'child' && value.canResolve))
})
test('same-name public source recreation requires acknowledgement even when scene content matches', async () => {
  const f = fixture(); await f.bind(); f.publicIds.remote.set('Public', 'X'.repeat(26))
  await f.client.run(); const conflict = f.client.state.conflicts.find(value => value.key === 'parent')
  assert.equal(conflict.canResolve, true)
  await f.client.run(); assert.ok(f.client.state.conflicts.some(value => value.key === 'parent'))
  await f.client.resolve('parent', 'local'); await f.client.run(); assert.equal(f.client.state.conflicts.length, 0)
})
test('two-sided scene edit resolves explicitly; a deleted scene is never recreated automatically', async () => {
  const f = fixture(); await f.bind()
  f.rows.local[1].definition.description = 'local'; f.rows.remote[1].definition.description = 'remote'; await f.client.run()
  await f.client.resolve('parent', 'remote'); assert.equal(f.rows.local[1].definition.description, 'remote')
  f.rows.remote = f.rows.remote.filter(item => item.name !== 'Parent'); f.rows.local.push(scene('Independent'))
  await f.client.run(); assert.ok(!f.rows.remote.some(item => item.name === 'Parent')); assert.ok(f.rows.remote.some(item => item.name === 'Independent'))
})
test('missing environment blocks only that scene and allows later recovery', async () => {
  const f = fixture(); f.rows.local[1].definition.environmentCode = 'qa'; await f.bind()
  assert.ok(f.client.state.conflicts.some(value => value.key === 'parent'))
  assert.ok(f.rows.remote.some(item => item.name === 'Child'))
  f.environments.remote.add('qa'); await f.client.run(); assert.ok(f.rows.remote.some(item => item.name === 'Parent'))
})
test('paired file links map recursively inside fixed history and a lost pair pauses only affected scene', async () => {
  const f = fixture(), key = 'G'.repeat(26), localId = 'H'.repeat(26), remoteId = 'J'.repeat(26)
  f.state.links = [{ key, localId, remoteId }]
  f.rows.local[1].definition.steps[0].config.revision.steps[0].config.body = `/api/v1/projects/${sourceId}/files/${localId}/download`
  await f.bind()
  assert.equal(f.rows.remote[1].definition.steps[0].config.revision.steps[0].config.body, `/api/v1/projects/${targetId}/files/${remoteId}/download`)
  f.state.links = []; f.rows.local[0].definition.description = 'independent edit'; await f.client.run()
  assert.equal(f.rows.remote[0].definition.description, 'independent edit'); assert.ok(f.client.state.conflicts.some(value => value.key === 'parent'))
})
test('uncertain response replays exact request after restart and does not add another revision', async () => {
  const f = fixture(); await f.bind(); f.rows.local[1].definition.description = 'updated'; f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = clone(f.client.state.pending), versions = clone(f.versions.remote)
  f.state.loseResponse = false; const restarted = new ApiAutomationScenarioSync(f.options); await restarted.run()
  assert.deepEqual(f.versions.remote, versions); assert.equal(restarted.state.pending, null)
  const attempts = f.state.writes.filter(value => value.body.requestKey === pending.request.requestKey)
  assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('account change and backup restore prevent writes until rebind', async () => {
  const f = fixture(); await f.bind(); const count = f.state.writes.length; f.state.actor = 'X'.repeat(26)
  await assert.rejects(f.client.run(), /账号.*变化/); assert.equal(f.state.writes.length, count)
  f.state.actor = actor; await f.client.suspendForRestore(); await f.client.run(); assert.equal(f.state.writes.length, count)
  await assert.rejects(f.client.enabled(true), /恢复过备份/); const rows = clone(f.rows); await f.client.unbind(); assert.deepEqual(f.rows, rows)
})
test('raw IDs, nested historical references, historical scripts and malformed fields fail before writes', async () => {
  for (const mutation of [rows => { rows[1].definition.version = 3 }, rows => { rows[1].definition.steps[0].config = { reusableStepId: 'X'.repeat(26), reusableStepVersion: 1 } },
    rows => { rows[1].definition.steps[1].config.revision.preRequestScript = 'console.log(1)' },
    rows => { rows[1].definition.steps[0].config.revision.steps = [childReference('Child')] }, rows => { rows[0].name = ' Child' }]) {
    const f = fixture(); mutation(f.rows.local)
    await assert.rejects(f.client.preview(sourceId, targetId, server)); assert.equal(f.state.writes.length, 0)
  }
  assert.equal(validateItem(parent()).name, 'Parent')
})
test('mutual current references to primitive fixed history can be copied in the same batch', async () => {
  const f = fixture(); f.rows.local = [scene('A', [childReference('B')]), scene('B', [childReference('A')])]
  await f.bind(); assert.deepEqual(f.rows.remote, f.rows.local)
})
