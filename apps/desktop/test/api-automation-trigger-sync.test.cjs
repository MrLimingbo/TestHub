'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const crypto = require('node:crypto')
const { ApiAutomationTriggerSync, validateState, validateItem, empty, keyOf } = require('../app/src/api-automation-trigger-sync.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26)
const server = 'https://sync.example.test', clone = v => globalThis.structuredClone(v)
const schedule = name => ({ kind: 'SCHEDULE', name, definition: { scenarioName: 'Scene', executionProfileName: 'Profile', cronExpression: '0 0 9 * * ?', timezone: 'Asia/Shanghai', timeoutSeconds: 30, maxConsecutiveFailures: 3 } })
const hook = name => ({ kind: 'WEBHOOK', name, definition: { scenarioName: 'Scene' } })
function fixture() {
  const rows = { local: [schedule('Daily'), hook('CI')], remote: [] }
  const assetIds = { local: new Map([['SCENARIO\nScene', 'E'.repeat(26)], ['EXECUTION_PROFILE\nProfile', 'G'.repeat(26)]]), remote: new Map([['SCENARIO\nScene', 'F'.repeat(26)], ['EXECUTION_PROFILE\nProfile', 'H'.repeat(26)]]) }
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
  const referenceIds = side => Object.fromEntries(assetIds[side])
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify([rows[side], itemIds(side), referenceIds(side), blockedNames[side]])).digest('hex')
  const store = { read: async () => clone(persisted ?? empty()), write: async value => { validateState(value); persisted = clone(value) } }
  const request = side => async (route, options) => {
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '同步测试', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) return { headerName: 'X-CSRF', token: 'fixture' }
        if (!options) return { version: 1, scope: 'API_AUTOMATION_TRIGGER_SYNC', actorId: side === 'local' ? localActor : state.actor,
      projectId: side === 'local' ? sourceId : targetId, revision: stamp(side), itemIds: itemIds(side), referenceIds: referenceIds(side),
      blockedKeys: clone(blockedNames[side]), items: clone(rows[side].filter(item => !Object.hasOwn(blockedNames[side], keyOf(item)))) }
    assert.match(route, /\/triggers\/sync$/)
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
  const client = new ApiAutomationTriggerSync(options)
  const bind = async () => { const preview = await client.preview(sourceId, targetId, server); await client.bind(preview.token); await client.run() }
  return { rows, assetIds, blockedNames, identities, environments, state, versions, options, client, bind }
}

test('explicit binding copies definitions and later edits return once, without activation or secret fields', async () => {
  const f = fixture(), p = await f.client.preview(sourceId, targetId, server)
  assert.equal(p.upload, 2); assert.equal(f.state.writes.length, 0); await f.client.bind(p.token); await f.client.run()
  assert.deepEqual(f.rows.local, f.rows.remote); await f.client.run(); assert.equal(f.state.writes.length, 1)
  f.rows.remote[0].definition.timeoutSeconds = 90; await f.client.run(); assert.equal(f.rows.local[0].definition.timeoutSeconds, 90)
  assert.ok(!JSON.stringify(f.state.writes).includes('secretReady')); assert.ok(!JSON.stringify(f.state.writes).includes('enabled'))
})
test('missing profile pauses only schedule while independent webhook copies and later resumes', async () => {
  const f = fixture(); f.assetIds.remote.delete('EXECUTION_PROFILE\nProfile'); await f.bind()
  assert.deepEqual(f.rows.remote.map(i => i.kind), ['WEBHOOK']); assert.ok(f.client.state.conflicts.some(c => c.local?.kind === 'SCHEDULE'))
  f.assetIds.remote.set('EXECUTION_PROFILE\nProfile', 'H'.repeat(26)); await f.client.run(); assert.equal(f.rows.remote.length, 2)
})
test('duplicate or invalid source key pauses only its trigger', async () => {
  const f = fixture(); f.blockedNames.local['WEBHOOK\nci'] = '名称重复'; await f.bind()
  assert.deepEqual(f.rows.remote.map(i => i.kind), ['SCHEDULE']); assert.ok(f.client.state.conflicts.some(c => c.key === 'WEBHOOK\nci' && !c.canResolve))
})
test('same-name source recreation pauses an unchanged trigger until explicit acknowledgement', async () => {
  const f = fixture(); await f.bind(); f.assetIds.remote.set('SCENARIO\nScene', 'X'.repeat(26))
  await f.client.run(); const c = f.client.state.conflicts.find(c => c.key === 'SCHEDULE\ndaily'); assert.equal(c.canResolve, true)
  await f.client.run(); assert.ok(f.client.state.conflicts.length); await f.client.resolve(c.key, 'local'); await f.client.run()
  assert.ok(!f.client.state.conflicts.some(c => c.key === 'SCHEDULE\ndaily'))
})
test('two-sided definition conflict requires selection and deleted hooks are not recreated', async () => {
  const f = fixture(); await f.bind(); f.rows.local[0].definition.timeoutSeconds = 40; f.rows.remote[0].definition.timeoutSeconds = 50
  await f.client.run(); await f.client.resolve('SCHEDULE\ndaily', 'remote'); assert.equal(f.rows.local[0].definition.timeoutSeconds, 50)
  f.rows.remote = f.rows.remote.filter(i => i.kind !== 'WEBHOOK'); f.rows.local.push(schedule('Independent')); await f.client.run()
  assert.ok(!f.rows.remote.some(i => i.kind === 'WEBHOOK')); assert.ok(f.rows.remote.some(i => i.name === 'Independent'))
})
test('missing references also prevent conflict selection', async () => {
  const f = fixture(); await f.bind(); f.rows.local[0].definition.timeoutSeconds = 40; f.rows.remote[0].definition.timeoutSeconds = 50
  await f.client.run(); f.assetIds.remote.clear(); await assert.rejects(f.client.resolve('SCHEDULE\ndaily', 'local'), /目标场景/)
})
test('uncertain response retries original request after restart without duplicate versions', async () => {
  const f = fixture(); await f.bind(); f.rows.local[0].definition.timeoutSeconds = 40; f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = clone(f.client.state.pending), versions = clone(f.versions.remote)
  f.state.loseResponse = false; const restarted = new ApiAutomationTriggerSync(f.options); await restarted.run(); assert.deepEqual(f.versions.remote, versions)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('account change and backup restore persistently stop mutations until rebind', async () => {
  const f = fixture(); await f.bind(); const n = f.state.writes.length; f.state.actor = 'X'.repeat(26)
  await assert.rejects(f.client.run(), /账号/); assert.equal(f.state.writes.length, n); f.state.actor = actor
  await f.client.suspendForRestore(); await new ApiAutomationTriggerSync(f.options).run(); assert.equal(f.state.writes.length, n)
  await assert.rejects(f.client.enabled(true), /恢复过备份/); const rows = clone(f.rows); await f.client.unbind(); assert.deepEqual(f.rows, rows)
})
test('closed schema rejects secret, enablement, physical references and malformed limits before writes', async () => {
  for (const change of [v => { v.definition.secret = 'fixture' }, v => { v.definition.enabled = true }, v => { v.definition.scenarioId = 'E'.repeat(26) }, v => { v.definition.timeoutSeconds = 0 }]) {
    const v = schedule('Bad'); change(v); assert.throws(() => validateItem(v))
  }
  assert.throws(() => validateState({ ...empty(), bases: [schedule('not-bound')] }))
})
test('confirmation is invalidated when target references change', async () => {
  const f = fixture(), p = await f.client.preview(sourceId, targetId, server); f.assetIds.remote.set('SCENARIO\nScene', 'X'.repeat(26))
  await assert.rejects(f.client.bind(p.token), /确认期间/); assert.equal(f.state.writes.length, 0)
})
