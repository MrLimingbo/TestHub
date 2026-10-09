'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const { Buffer } = require('node:buffer')
const { FunctionalTransfer, jsonRequest } = require('../app/src/functional-transfer.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26)
function fixture() {
  const context = id => ({ organizations: [{ id: 'org', name: '工作区' }], projects: { org: [{ id, name: '项目' }] } })
  const state = { user: 'alice', endpoint: 'https://example.test', now: 1000, writes: [], reads: [], fail: false, versions: [1, 2] }
  const snapshot = { version: 1, catalogs: [{ id: 'catalog' }], requirements: [{ id: 'req' }], cases: [] }
  const client = new FunctionalTransfer({ current: () => state.endpoint, now: () => state.now,
    local: async route => { state.reads.push(route); return route.endsWith('/context') ? context(sourceId) : snapshot },
    remote: async (route, options) => {
      if (route.endsWith('/me')) return { id: state.user, account: state.user, displayName: '当前账号' }
      if (route.endsWith('/context')) return context(targetId)
      if (route.endsWith('/capabilities')) return { version: 1, versions: state.versions, scope: 'FUNCTIONAL_DEFINITIONS' }
      if (route.endsWith('/csrf')) return { headerName: 'X-XSRF-TOKEN', token: 'test-xsrf' }
      state.writes.push(JSON.parse(options.body))
      if (state.fail) throw new Error('response lost')
      return { id: 'receipt', requirements: 1, cases: 0 }
    } })
  return { client, state, snapshot }
}
test('preview reads both scopes without uploading the local payload', async () => {
  const { client, state } = fixture(), preview = await client.prepare(sourceId, targetId)
  assert.deepEqual(preview.counts, { catalogs: 1, requirements: 1, cases: 0, plans: 0 }); assert.equal(preview.snapshot, undefined)
  assert.deepEqual(state.writes, [])
  await client.commit(preview.token)
  assert.equal(state.writes[0].expectedUserId, 'alice'); assert.equal(client.plan, undefined)
})
test('changed account, server or expired preview cannot post assets', async () => {
  for (const change of [state => { state.user = 'bob' }, state => { state.endpoint = 'https://other.test' }, state => { state.now += 600001 }]) {
    const { client, state } = fixture(), preview = await client.prepare(sourceId, targetId)
    change(state); await assert.rejects(client.commit(preview.token)); assert.deepEqual(state.writes, [])
  }
})
test('retry after an uncertain result retains request key, expected actor and exact snapshot', async () => {
  const { client, state } = fixture(), preview = await client.prepare(sourceId, targetId)
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token)
  assert.equal(state.writes.length, 2); assert.deepEqual(state.writes[0], state.writes[1])
})
test('unknown source or destination and stale preview are rejected before upload', async () => {
  const { client, state } = fixture(), preview = await client.prepare(sourceId, targetId)
  await assert.rejects(client.prepare('../private', targetId))
  await assert.rejects(client.commit(preview.token))
  await assert.rejects(client.prepare(sourceId, 'C'.repeat(26)))
  assert.deepEqual(state.writes, [])
})
test('network adapter forbids redirects and handles error/non-JSON/oversize responses', async () => {
  const response = body => new globalThis.Response(body)
  assert.deepEqual(await jsonRequest(async (_url, options) => { assert.equal(options.redirect, 'error'); return response('{"data":{"ok":true}}') }, 'https://example.test'), { ok: true })
  await assert.rejects(jsonRequest(async () => response('<html>login</html>'), 'https://example.test'), /有效迁移数据/)
  await assert.rejects(jsonRequest(async () => new globalThis.Response('{"message":"conflict"}', { status: 409 }), 'https://example.test'), /conflict/)
  await assert.rejects(jsonRequest(async () => response('x'.repeat(9 * 1024 * 1024 + 1)), 'https://example.test'), /大小限制/)
})
test('plans are opt-in and require negotiated target support before reading source assets', async () => {
  const { client, state } = fixture()
  state.versions = undefined
  await assert.rejects(client.prepare(sourceId, targetId, true), /尚不支持测试计划/)
  assert.ok(state.reads.every(route => route.endsWith('/context')))
  const preview = await client.prepare(sourceId, targetId, false)
  await client.commit(preview.token)
  assert.equal(state.writes[0].snapshot.version, 1)
  assert.equal(state.writes[0].snapshot.plans, undefined)
  await assert.rejects(client.prepare(sourceId, targetId, 'true'), /迁移范围/)
})
test('explicit V2 preview and retry retain ordered plan associations, including a plans-only project', async () => {
  const { client, state, snapshot } = fixture()
  Object.assign(snapshot, { version: 2, catalogs: [], requirements: [], plans: [{ id: 'plan', caseIds: [] }] })
  const preview = await client.prepare(sourceId, targetId, true)
  assert.equal(preview.counts.plans, 1)
  assert.ok(state.reads.some(route => route.endsWith('?version=2')))
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token)
  assert.deepEqual(state.writes[0], state.writes[1])
  assert.deepEqual(state.writes[1].snapshot.plans, snapshot.plans)
})
test('a source cannot silently omit selected plans or include them without consent', async () => {
  const { client, state, snapshot } = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, true), /版本不兼容/)
  snapshot.plans = []
  await assert.rejects(client.prepare(sourceId, targetId, false), /版本不兼容/)
  assert.deepEqual(state.writes, [])
})
test('governance requires explicit consent and V3 support before source export', async () => {
  const { client, state, snapshot } = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, true), /尚不支持标签与字段/)
  assert.ok(state.reads.every(route => route.endsWith('/context')))
  await assert.rejects(client.prepare(sourceId, targetId, false, 'true'), /迁移范围/)
  snapshot.governance = { tags: [], fields: [], bindings: [] }
  await assert.rejects(client.prepare(sourceId, targetId), /超出所选范围/)
  assert.deepEqual(state.writes, [])
})
test('V3 independently selects plans and keeps exact governance on uncertain retry', async () => {
  for (const includePlans of [false, true]) {
    const { client, state, snapshot } = fixture()
    state.versions.push(3)
    Object.assign(snapshot, { version: 3, governance: { tags: [{ id: 'tag' }], fields: [{ id: 'field' }], bindings: [{ assetType: 'REQUIREMENT', assetId: 'req', tagIds: ['tag'] }] } })
    if (includePlans) snapshot.plans = [{ id: 'plan', caseIds: [] }]
    const preview = await client.prepare(sourceId, targetId, includePlans, true)
    assert.deepEqual(preview.counts.governance, { tags: 1, fields: 1, bindings: 1 })
    assert.equal(preview.counts.plans, includePlans ? 1 : 0)
    assert.ok(state.reads.some(route => route.endsWith(`?version=3&includePlans=${includePlans}`)))
    state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
    state.fail = false; await client.commit(preview.token)
    assert.deepEqual(state.writes[0], state.writes[1])
    assert.deepEqual(state.writes[1].snapshot, snapshot)
  }
})
test('metadata-only V3 projects can transfer; malformed or missing selected metadata cannot', async () => {
  const { client, state, snapshot } = fixture()
  state.versions.push(3)
  Object.assign(snapshot, { version: 3, catalogs: [], requirements: [], governance: { tags: [{ id: 'tag' }], fields: [], bindings: [] } })
  const preview = await client.prepare(sourceId, targetId, false, true)
  assert.equal(preview.counts.requirements, 0)
  assert.equal(preview.counts.governance.tags, 1)
  await client.commit(preview.token)
  for (const invalid of [undefined, { tags: [], fields: [] }, { tags: [], fields: [], bindings: [null] }, { tags: [], fields: [], bindings: [{ tagIds: 'tag' }] }]) {
    snapshot.governance = invalid
    await assert.rejects(client.prepare(sourceId, targetId, false, true), /标签与字段资料不完整/)
  }
  snapshot.governance = { tags: [], fields: [], bindings: [] }
  await assert.rejects(client.prepare(sourceId, targetId, false, true), /没有可迁移/)
  assert.equal(state.writes.length, 1)
})
test('defects are opt-in and need target V4 support before reading local assets', async () => {
  const { client, state, snapshot } = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, false, true), /尚不支持缺陷迁移/)
  assert.ok(state.reads.every(route => route.endsWith('/context')))
  await assert.rejects(client.prepare(sourceId, targetId, false, false, 'true'), /迁移范围/)
  snapshot.defects = { items: [] }
  await assert.rejects(client.prepare(sourceId, targetId), /超出所选范围/)
  assert.deepEqual(state.writes, [])
})
test('V4 independently selects plans and governance and retries the same defect snapshot', async () => {
  for (const plans of [false, true]) for (const governance of [false, true]) {
    const { client, state, snapshot } = fixture()
    state.versions = [1, 4]
    Object.assign(snapshot, { version: 4, defects: { items: [{ links: [{ assetType: 'REQUIREMENT', assetId: 'req' }], omittedExecutionLinks: 2 }] } })
    if (plans) snapshot.plans = []
    if (governance) snapshot.governance = { tags: [], fields: [], bindings: [] }
    const preview = await client.prepare(sourceId, targetId, plans, governance, true)
    assert.deepEqual(preview.counts.defects, { items: 1, links: 1, omittedExecutionLinks: 2 })
    assert.ok(state.reads.some(route => route.endsWith(`?version=4&includePlans=${plans}&includeGovernance=${governance}`)))
    state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
    state.fail = false; await client.commit(preview.token)
    assert.deepEqual(state.writes[0], state.writes[1]); assert.deepEqual(state.writes[1].snapshot, snapshot)
  }
})
test('defect-only projects work; malformed defects or exclusion counts cannot be previewed', async () => {
  const { client, state, snapshot } = fixture()
  state.versions.push(4)
  Object.assign(snapshot, { version: 4, catalogs: [], requirements: [], defects: { items: [{ links: [], omittedExecutionLinks: 0 }] } })
  const preview = await client.prepare(sourceId, targetId, false, false, true)
  assert.equal(preview.counts.defects.items, 1); await client.commit(preview.token)
  for (const invalid of [undefined, {}, { items: [null] }, { items: [{ links: [], omittedExecutionLinks: -1 }] }, { items: [{ links: [], omittedExecutionLinks: 1.5 }] }]) {
    snapshot.defects = invalid
    await assert.rejects(client.prepare(sourceId, targetId, false, false, true), /缺陷资料不完整/)
  }
  snapshot.defects = { items: [] }
  await assert.rejects(client.prepare(sourceId, targetId, false, false, true), /没有可迁移/)
  assert.equal(state.writes.length, 1)
})
test('project files require explicit V5 support and cannot appear outside the selected scope', async () => {
  const { client, state, snapshot } = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, true), /尚不支持项目文件/)
  assert.ok(state.reads.every(route => route.endsWith('/context')))
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, 'true'), /迁移范围/)
  snapshot.files = { projectId: sourceId, items: [] }
  await assert.rejects(client.prepare(sourceId, targetId), /超出所选范围/)
})
test('file-only V5 previews verify bytes and retry an identical payload for every optional scope', async () => {
  const { createHash } = require('node:crypto'), bytes = Buffer.from([0, 1, 255])
  for (const plans of [false, true]) for (const governance of [false, true]) for (const defects of [false, true]) {
    const { client, state, snapshot } = fixture(); state.versions = [1, 5]
    Object.assign(snapshot, { version: 5, catalogs: [], requirements: [], files: { projectId: sourceId, items: [{ id: 'file', size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), content: bytes.toString('base64') }] } })
    if (plans) snapshot.plans = []
    if (governance) snapshot.governance = { tags: [], fields: [], bindings: [] }
    if (defects) snapshot.defects = { items: [] }
    const preview = await client.prepare(sourceId, targetId, plans, governance, defects, true)
    assert.deepEqual(preview.counts.files, { items: 1, bytes: 3 })
    assert.ok(state.reads.some(route => route.endsWith(`?version=5&includePlans=${plans}&includeGovernance=${governance}&includeDefects=${defects}`)))
    state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
    state.fail = false; await client.commit(preview.token)
    assert.deepEqual(state.writes[0], state.writes[1]); assert.deepEqual(state.writes[1].snapshot.files, snapshot.files)
  }
})
test('corrupt file bytes, hashes, sizes and scope are rejected; response ceiling is explicit', async () => {
  const { createHash } = require('node:crypto'), bytes = Buffer.from([1, 2, 3])
  const file = { size: 3, sha256: createHash('sha256').update(bytes).digest('hex'), content: bytes.toString('base64') }
  const { client, state, snapshot } = fixture(); state.versions.push(5); snapshot.version = 5
  for (const item of [null, { ...file, size: 4 }, { ...file, size: -1 }, { ...file, content: 'garbage' }, { ...file, sha256: '0'.repeat(64) }]) {
    snapshot.files = { projectId: sourceId, items: [item] }
    await assert.rejects(client.prepare(sourceId, targetId, false, false, false, true), /项目文件/)
  }
  snapshot.files = { projectId: targetId, items: [file] }
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, true), /项目文件/)
  const response = () => new globalThis.Response(JSON.stringify({ data: 'x'.repeat(10 * 1024 * 1024) }))
  await assert.rejects(jsonRequest(async () => response(), 'https://example.test'), /大小限制/)
  assert.equal((await jsonRequest(async () => response(), 'https://example.test', {}, 11 * 1024 * 1024)).length, 10 * 1024 * 1024)
  assert.deepEqual(state.writes, [])
})
test('history requires plans and V6 support without silently downgrading or writing', async () => {
  const { client, state } = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, true), /同时选择测试计划/)
  await assert.rejects(client.prepare(sourceId, targetId, true, false, false, false, true), /尚不支持执行历史/)
  await assert.rejects(client.prepare(sourceId, targetId, true, false, false, false, 'true'), /迁移范围/)
  assert.ok(state.reads.every(route => route.endsWith('/context'))); assert.equal(state.writes.length, 0)
})
test('V6 history preview counts snapshots and uncertain retries retain the exact request', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 6]
  Object.assign(snapshot, { version: 6, plans: [{ id: 'plan' }], history: { runs: [{ cases: [{ steps: [{ text: 'historical step' }, { text: 'second' }] }] }] } })
  const preview = await client.prepare(sourceId, targetId, true, false, false, false, true)
  assert.deepEqual(preview.counts.history, { runs: 1, cases: 1, steps: 2 })
  assert.ok(state.reads.some(route => route.endsWith('?version=6&includePlans=true&includeGovernance=false&includeDefects=false&includeFiles=false&includeHistory=true')))
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token)
  assert.deepEqual(state.writes[0], state.writes[1]); assert.equal(state.writes[0].snapshot.files, undefined)
})
test('unselected, missing, malformed and oversized history cannot be submitted', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 6]
  snapshot.history = { runs: [] }
  await assert.rejects(client.prepare(sourceId, targetId), /执行历史资料/)
  snapshot.version = 6; snapshot.plans = []
  for (const history of [undefined, { runs: [{}] }, { runs: Array(1001).fill({ cases: [] }) }, { runs: [{ cases: Array(10001).fill({ steps: [] }) }] }, { runs: [{ cases: [{ steps: Array(50001).fill({}) }] }] }]) {
    snapshot.history = history
    await assert.rejects(client.prepare(sourceId, targetId, true, false, false, false, true), /执行历史/)
  }
  assert.equal(state.writes.length, 0)
})
test('changing the history scope clears an existing preview before a failed re-preview', async () => {
  const { client, state } = fixture(); const preview = await client.prepare(sourceId, targetId)
  await assert.rejects(client.prepare(sourceId, targetId, true, false, false, false, true))
  await assert.rejects(client.commit(preview.token), /预览已失效/); assert.equal(state.writes.length, 0)
})
test('versions require explicit V7 support, consent and invalidate stale previews', async () => {
  const { client, state, snapshot } = fixture(), preview = await client.prepare(sourceId, targetId)
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, true), /尚不支持项目版本/)
  await assert.rejects(client.commit(preview.token), /预览已失效/)
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, 'true'), /迁移范围/)
  snapshot.projectVersions = { items: [] }
  await assert.rejects(client.prepare(sourceId, targetId), /项目版本资料/)
  assert.equal(state.writes.length, 0)
})
test('versions-only V7 previews and uncertain retries preserve definitions without enabling other scopes', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 7]
  Object.assign(snapshot, { version: 7, catalogs: [], requirements: [], projectVersions: { items: [{ number: 'V1', builds: ['z', 'a'], status: 'RELEASED', actualReleaseDate: '2025-01-03' }] } })
  const preview = await client.prepare(sourceId, targetId, false, false, false, false, false, true)
  assert.equal(preview.counts.projectVersions, 1)
  assert.ok(state.reads.some(route => route.endsWith('?version=7&includePlans=false&includeGovernance=false&includeDefects=false&includeFiles=false&includeHistory=false&includeProjectVersions=true')))
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token)
  assert.deepEqual(state.writes[0], state.writes[1]); assert.deepEqual(state.writes[0].snapshot, snapshot)
})
test('V7 independently selects previous scopes and still requires plans for history', async () => {
  for (const plans of [false, true]) for (const history of plans ? [false, true] : [false]) for (const files of [false, true]) {
    const { client, state, snapshot } = fixture(); state.versions = [1, 7]
    Object.assign(snapshot, { version: 7, projectVersions: { items: [] }, governance: { tags: [], fields: [], bindings: [] }, defects: { items: [] } })
    if (plans) snapshot.plans = []
    if (history) snapshot.history = { runs: [] }
    if (files) snapshot.files = { projectId: sourceId, items: [] }
    const preview = await client.prepare(sourceId, targetId, plans, true, true, files, history, true)
    assert.equal(preview.counts.projectVersions, 0); await client.commit(preview.token)
    assert.deepEqual(state.writes[0].snapshot, snapshot)
  }
  const { client } = fixture()
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, true, true), /同时选择测试计划/)
})
test('missing malformed oversized and empty version-only snapshots cannot upload', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 7]
  Object.assign(snapshot, { version: 7, catalogs: [], requirements: [] })
  for (const value of [undefined, {}, { items: 'invalid' }, { items: Array(5001).fill({}) }]) {
    snapshot.projectVersions = value
    await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, true), /项目版本资料/)
  }
  snapshot.projectVersions = { items: [] }
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, true), /没有可迁移/)
  assert.equal(state.writes.length, 0)
})
test('governance catalogs default off and require explicit V8 capability before export', async () => {
  const { client, state, snapshot } = fixture(), preview = await client.prepare(sourceId, targetId)
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, false, true), /尚不支持治理目录/)
  await assert.rejects(client.commit(preview.token), /预览已失效/)
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, false, 'true'), /迁移范围/)
  snapshot.catalogs.push({ id: 'gov', scope: 'GOVERNANCE' })
  await assert.rejects(client.prepare(sourceId, targetId), /治理目录超出/)
  assert.equal(state.writes.length, 0)
})
test('V8 can copy empty nested governance catalogs without any other optional asset', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 8]
  Object.assign(snapshot, { version: 8, catalogs: [{ id: 'root', scope: 'GOVERNANCE' }, { id: 'child', parentId: 'root', scope: 'GOVERNANCE' }], requirements: [] })
  const preview = await client.prepare(sourceId, targetId, false, false, false, false, false, false, true)
  assert.equal(preview.counts.catalogs, 2); assert.equal(preview.counts.governanceCatalogs, 2)
  assert.equal(preview.counts.governance, undefined); assert.equal(preview.counts.projectVersions, undefined)
  assert.ok(state.reads.some(route => route.endsWith('?version=8&includePlans=false&includeGovernance=false&includeDefects=false&includeFiles=false&includeHistory=false&includeProjectVersions=false&includeGovernanceCatalogs=true')))
  state.fail = true; await assert.rejects(client.commit(preview.token), /response lost/)
  state.fail = false; await client.commit(preview.token); assert.deepEqual(state.writes[0], state.writes[1]); assert.deepEqual(state.writes[0].snapshot, snapshot)
})
test('V8 includes prior scopes only when selected and preserves the history plan dependency', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 8]
  Object.assign(snapshot, { version: 8, plans: [], history: { runs: [] }, files: { projectId: sourceId, items: [] }, governance: { tags: [], fields: [], bindings: [] }, defects: { items: [] }, projectVersions: { items: [] } })
  const preview = await client.prepare(sourceId, targetId, true, true, true, true, true, true, true)
  assert.equal(preview.counts.governanceCatalogs, 0); await client.commit(preview.token)
  assert.deepEqual(state.writes[0].snapshot, snapshot)
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, true, false, true), /同时选择测试计划/)
})
test('V8 permits selected empty asset directories but rejects a completely empty project', async () => {
  const { client, state, snapshot } = fixture(); state.versions = [1, 8]
  Object.assign(snapshot, { version: 8, catalogs: [{ id: 'req-folder', scope: 'REQUIREMENTS' }], requirements: [] })
  const preview = await client.prepare(sourceId, targetId, false, false, false, false, false, false, true)
  assert.equal(preview.counts.catalogs, 1); assert.equal(preview.counts.governanceCatalogs, 0)
  snapshot.catalogs = []
  await assert.rejects(client.prepare(sourceId, targetId, false, false, false, false, false, false, true), /没有可迁移/)
  assert.equal(state.writes.length, 0)
})
