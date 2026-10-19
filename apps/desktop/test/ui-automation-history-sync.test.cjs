'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { UiAutomationHistorySync, validateItem, validateState, empty } = require('../app/src/ui-automation-history-sync.cjs')
const localProject = 'A'.repeat(26), remoteProject = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26)
const server = 'https://sync.example.test', clone = v => globalThis.structuredClone(v)
const digest = v => crypto.createHash('sha256').update(JSON.stringify(v)).digest('hex')
const timestamp = '2026-10-13T01:00:00Z'
function report(name='历史') {
  return {scenarioName:name,scenarioVersion:1,browserProfileName:'Chromium',browser:'chromium',functionalPlanName:null,retryOfExecutionId:null,retryAttempt:0,status:'SUCCEEDED',statusCode:'EXECUTION_SUCCEEDED',requestedAt:timestamp,deadlineAt:'2026-10-13T01:01:00Z',startedAt:timestamp,finishedAt:timestamp,summary:{totalSteps:0,passedSteps:0,failedSteps:0,totalDurationMillis:0},steps:[]}
}
function item(project, index = 1) {
  const p = report('历史 ' + index)
  return { key: project + '.' + 'E'.repeat(22) + String(index).padStart(4, '0'), revision: digest([project, index]), sha256: digest(p), report: p }
}
function fixture() {
  const rows = { local: [item(localProject)], remote: [item(remoteProject)] }
  const state = { actor, writes: [], lose: false, stale: false, targetChanged: false, incomplete: false }, receipts = { local: new Map(), remote: new Map() }
  let saved
  const store = { read: async () => clone(saved ?? empty()), write: async v => { validateState(v); saved = clone(v) } }
  const entries = side => rows[side].map(v => ({ key: v.key, revision: v.revision, nativeRecord: v.key.startsWith((side === 'local' ? localProject : remoteProject) + '.'), scenarioName: v.report.scenarioName, status: v.report.status, requestedAt: v.report.requestedAt, finishedAt: v.report.finishedAt }))
  const revision = side => digest(entries(side))
  const request = side => async (route, options) => {
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, account: 'fixture' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '组织' }], projects: { org: [{ id: side === 'local' ? localProject : remoteProject, name: side }] } }
    if (route.endsWith('/csrf')) return { headerName: 'X-CSRF', token: 'fixture' }
    if (!options && route.includes('/history/sync?')) return { version: 1, scope: 'UI_HISTORY_SYNC', projectId: side === 'local' ? localProject : remoteProject, actorId: side === 'local' ? localActor : state.actor, revision: revision(side), entries: entries(side) }
    if (!options) {
      const value = clone(rows[side].find(v => route.endsWith('/' + v.key)))
      if (state.stale) value.revision = 'f'.repeat(64)
      if (state.targetChanged) { const other = side === 'local' ? 'remote' : 'local'; rows[other].push({ ...clone(value), revision: 'e'.repeat(64) }); state.targetChanged = false }
      return value
    }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== revision(side)) { const error = new Error('revision changed'); error.status = 409; throw error }
      body.items.forEach(v => { const index = rows[side].findIndex(e => e.key === v.key); if (index < 0) rows[side].push(clone(v)); else rows[side][index] = clone(v) })
      result = { requestKey: body.requestKey, results: body.items.map(v => ({ key: v.key, revision: v.revision, sha256: v.sha256 })) }; receipts[side].set(body.requestKey, clone(result))
    }
    if (state.lose) throw new Error('response lost')
    if (state.incomplete) result = { ...result, results: [] }
    return clone(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async b => { assert.equal(b.server, server) } }
  const client = new UiAutomationHistorySync(options)
  const bind = async () => { const preview = await client.preview(localProject, remoteProject, server); await client.bind(preview.token); await client.run() }
  return { rows, state, options, client, bind }
}
test('explicit binding copies terminal reports both ways once, retaining origin and excluding task dispatch', async () => {
  const f = fixture(), preview = await f.client.preview(localProject, remoteProject, server)
  assert.equal(preview.upload, 1); assert.equal(preview.download, 1); assert.equal(f.state.writes.length, 0)
  await f.client.bind(preview.token); await f.client.run(); assert.equal(f.rows.local.length, 2); assert.equal(f.rows.remote.length, 2)
  await f.client.run(); assert.equal(f.state.writes.length, 2); assert.equal(f.client.state.bases.length, 2)
})
test('late source steps update the same archive with the original scene version',async()=>{
  const f=fixture();await f.bind();const source=f.rows.local[0]
  source.report.steps=[{stepId:'step1',stepSequence:1,stepType:'NAVIGATE',stepName:'打开',outcome:'PASSED',durationMillis:12,occurredAt:timestamp}]
  source.report.summary={totalSteps:1,passedSteps:1,failedSteps:0,totalDurationMillis:12};source.revision='f'.repeat(64);source.sha256=digest(source.report)
  await f.client.run();assert.equal(f.rows.remote.find(v=>v.key===source.key).report.summary.totalSteps,1);assert.equal(f.rows.remote.length,2);assert.equal(source.report.scenarioVersion,1)
})
test('response loss retries the exact durable request after restart without duplicate archive', async () => {
  const f = fixture(); const p = await f.client.preview(localProject, remoteProject, server); await f.client.bind(p.token)
  f.state.lose = true; await assert.rejects(f.client.run(), /response lost/); const pending = clone(f.client.state.pending)
  f.state.lose = false; const restarted = new UiAutomationHistorySync(f.options); await restarted.run()
  assert.equal(f.rows.remote.length, 2); assert.equal(restarted.state.pending, null)
  const repeated = f.state.writes.filter(v => v.body.requestKey === pending.request.requestKey)
  assert.equal(repeated.length, 2); assert.deepEqual(repeated[0], repeated[1])
})
test('incomplete receipt remains pending and a later replay confirms it', async () => {
  const f = fixture(); const p = await f.client.preview(localProject, remoteProject, server); await f.client.bind(p.token)
  f.state.incomplete = true; await assert.rejects(f.client.run(), /回执不完整/); assert.ok(f.client.state.pending)
  f.state.incomplete = false; await f.client.run(); assert.equal(f.client.state.pending, null); assert.equal(f.rows.remote.length, 2)
})
test('changed source between index and body fetch prevents any import', async () => {
  const f = fixture(); const p = await f.client.preview(localProject, remoteProject, server); await f.client.bind(p.token)
  f.state.stale = true; await assert.rejects(f.client.run(), /来源历史已变化/); assert.equal(f.state.writes.length, 0)
})
test('destination changes while fetching body cannot be overwritten with a fresh overall revision', async () => {
  const f = fixture(); const p = await f.client.preview(localProject, remoteProject, server); await f.client.bind(p.token)
  f.state.targetChanged = true; await assert.rejects(f.client.run(), /目标历史已变化/); assert.equal(f.state.writes.length, 0)
})
test('missing previously synced history pauses that origin and an independent report still copies', async () => {
  const f = fixture(); await f.bind(); const key = f.rows.local[0].key
  f.rows.remote = f.rows.remote.filter(v => v.key !== key); f.rows.local.push(item(localProject, 2)); await f.client.run()
  assert.ok(!f.rows.remote.some(v => v.key === key)); assert.ok(f.rows.remote.some(v => v.key === f.rows.local[2].key))
  assert.ok(f.client.state.conflicts.some(v => v.key === key && !v.canResolve))
  await assert.rejects(f.client.resolve(), /只读/)
})
test('different orphaned copies pause without an original execution or manual result editing', async () => {
  const f = fixture(), orphan = item('F'.repeat(26), 9)
  f.rows.local = [clone(orphan)]; f.rows.remote = [{ ...clone(orphan), revision: 'f'.repeat(64) }]
  await f.bind(); assert.equal(f.state.writes.length, 0); assert.equal(f.client.state.conflicts.length, 1)
})
test('account change and persisted backup-restore suspension block all writes', async () => {
  const f = fixture(); await f.bind(); const count = f.state.writes.length; f.state.actor = 'X'.repeat(26)
  await assert.rejects(f.client.run(), /账号已变化/); assert.equal(f.state.writes.length, count)
  f.state.actor = actor; await f.client.suspendForRestore(); const restarted = new UiAutomationHistorySync(f.options); await restarted.run()
  assert.equal(f.state.writes.length, count); await assert.rejects(restarted.enabled(true), /恢复过备份/)
  const before = clone(f.rows); await restarted.unbind(); assert.deepEqual(f.rows, before)
})
test('large history inventory sends bounded batches with lightweight encrypted bases', async () => {
  const f = fixture(); f.rows.local = Array.from({ length: 45 }, (_, n) => item(localProject, n + 1)); f.rows.remote = []
  await f.bind(); assert.equal(f.rows.remote.length, 45); assert.deepEqual(f.state.writes.map(v => v.body.items.length), [20, 20, 5])
  assert.ok(f.client.state.bases.every(v => !Object.hasOwn(v, 'report'))); await f.client.run(); assert.equal(f.state.writes.length, 3)
})
test('unknown value-bearing fields, bad digest, active status and invalid stored state fail before writes', async () => {
  for (const change of [v => { v.report.responseBody = 'fixture' }, v => { v.sha256 = '0'.repeat(64) }, v => { v.report.status = 'RUNNING'; v.sha256 = digest(v.report) }]) {
    const v = item(localProject); change(v); assert.throws(() => validateItem(v))
  }
  assert.throws(() => validateState({ ...empty(), bases: [{ key: 'bad' }] }))
})
test('binding confirmation is invalidated by a new completed execution', async () => {
  const f = fixture(), p = await f.client.preview(localProject, remoteProject, server)
  f.rows.local.push(item(localProject, 2)); await assert.rejects(f.client.bind(p.token), /确认期间已变化/); assert.equal(f.state.writes.length, 0)
})

test('raw fields, inconsistent counts, duplicate steps and excessive durations are rejected',()=>{
  for(const mutate of [p=>{p.inputValue='private'},p=>{p.summary.totalSteps=1},p=>{p.steps=[{stepId:'s',stepSequence:1,stepType:'FILL',stepName:'填写',outcome:'PASSED',durationMillis:120001,occurredAt:timestamp}]},p=>{p.browser='unknown'},p=>{p.finishedAt='2026-10-12T00:00:00Z'}]){
    const v=item(localProject);mutate(v.report);v.sha256=digest(v.report);assert.throws(()=>validateItem(v))
  }
  const v=item(localProject),step={stepId:'s',stepSequence:1,stepType:'CLICK',stepName:'点击',outcome:'FAILED',durationMillis:1,occurredAt:timestamp};v.report.steps=[step,clone(step)];v.report.summary={totalSteps:2,passedSteps:0,failedSteps:2,totalDurationMillis:2};v.sha256=digest(v.report);assert.throws(()=>validateItem(v))
})
test('one millisecond database rounding preserves raw times while larger reversals fail', () => {
  const v = item(localProject); v.report.requestedAt = '2026-10-13T01:00:00.001Z'; v.sha256 = digest(v.report)
  assert.equal(validateItem(v).report.finishedAt, timestamp)
  v.report.requestedAt = '2026-10-13T01:00:00.002Z'; v.sha256 = digest(v.report); assert.throws(() => validateItem(v))
})

// Bridge visibility is a trust boundary: the account page may open a window but cannot invoke transfers.
test('each UI history/artifact bridge is complete only inside its trusted setup page',async()=>{
 const fs=require('node:fs/promises'),path=require('node:path'),vm=require('node:vm')
 for(const [file,count] of [['setup-preload.cjs',9],['remote-preload.cjs',1]]){
  let bridge;vm.runInNewContext(await fs.readFile(path.join(__dirname,'../app/src',file),'utf8'),{require:name=>{assert.equal(name,'electron');return {contextBridge:{exposeInMainWorld:(_name,value)=>{bridge=value}},ipcRenderer:{invoke:(...args)=>args}}},process:{env:{},argv:[]}})
  for(const kind of ['History','Artifact']){
   const names=Object.keys(bridge).filter(n=>new RegExp('UiAutomation'+kind+'Sync|uiAutomation'+kind+'Sync').test(n));assert.equal(names.length,count)
   assert.deepEqual(Array.from(bridge['openUiAutomation'+kind+'Sync']()),['testhub-desktop:ui-automation-'+kind.toLowerCase()+'-sync:open'])
   if(count===1)assert.equal(bridge['runUiAutomation'+kind+'Sync'],undefined)
  }
 }
})
