'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { UiAutomationSync, UiAutomationSyncStore, keyOf, empty, validateState } = require('../app/src/ui-automation-sync.cjs')
const { installUiAutomationSync } = require('../app/src/ui-automation-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, description) => ({kind:'PAGE_OBJECT',name,definition:{selectorValue:'#submit',description}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('LOCAL', '本地原值')], remote: [item('REMOTE', '网页原值')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify(rows[side])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'UI_AUTOMATION_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), functionalPlanCodes: ['PLAN-UI'], excludedExecutions: 0 }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const e = new Error('revision or actor changed'); e.status = 409; throw e }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== stamp(side)) conflict()
      const next = copy(rows[side])
      result = { requestKey: body.requestKey, results: body.operations.map(op => {
        const index = next.findIndex(item => keyOf(item) === keyOf(op.item)); if (index < 0) next.push(copy(op.item)); else next[index] = copy(op.item)
        return { operationId: op.operationId, item: copy(op.item) }
      }) }; rows[side] = next; receipts[side].set(body.requestKey, copy(result))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async b => { if (b.server !== state.server) throw new Error('server changed') } }
  const client = new UiAutomationSync(options)
  const bind = async () => { const p = await client.preview(sourceId, targetId, server); await client.bind(p.token); await client.run() }
  const edit = (side, key, value) => { const item = rows[side].find(i => i.name === key); assert.ok(item); item.definition.description = value }
  return { state, rows, store, client, options, bind, edit }
}
test('preview is read only, matching names merge once and later identical rounds do not write', async () => {
  const f = fixture(); await f.client.run(); assert.equal(f.state.writes.length, 0)
  const p = await f.client.preview(sourceId, targetId, server); assert.equal(p.upload, 1); assert.equal(p.download, 1); assert.equal(f.state.writes.length, 0)
  await f.client.bind(p.token); await f.client.run(); assert.equal(f.rows.local.length, 2); assert.equal(f.rows.remote.length, 2)
  const count = f.state.writes.length; await f.client.run(); assert.equal(f.state.writes.length, count)
})
test('same name different content requires choice and two-sided edits reject stale human decisions', async () => {
  const f = fixture(); f.rows.remote.push(item('LOCAL', '网页版本')); await f.bind()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, true)
  await f.client.resolve(keyOf(item('LOCAL','')), 'local'); assert.equal(f.rows.remote.find(i => i.name === 'LOCAL').definition.description, '本地原值')
  f.edit('local', 'LOCAL', '本地冲突'); f.edit('remote', 'LOCAL', '网页冲突'); await f.client.run(); f.edit('remote', 'LOCAL', '网页再次修改')
  await assert.rejects(f.client.resolve(keyOf(item('LOCAL','')), 'local'), /已变化/); await f.client.run(); await f.client.resolve(keyOf(item('LOCAL','')), 'remote')
  assert.equal(f.rows.local.find(i => i.name === 'LOCAL').definition.description, '网页再次修改')
})
test('offline editing and independent web changes reconcile both ways on reconnect', async () => {
  const f = fixture(); await f.bind(); f.state.offline = true; f.edit('local', 'LOCAL', '离线正文'); f.rows.local.push(item('NEW', '离线新增'))
  await assert.rejects(f.client.run(), /offline/); f.edit('remote', 'REMOTE', '网页正文'); f.rows.remote.push(item('WEB', '网页新增'))
  f.state.offline = false; await f.client.run(); assert.equal(f.rows.remote.find(i => i.name === 'LOCAL').definition.description, '离线正文')
  assert.equal(f.rows.local.find(i => i.name === 'REMOTE').definition.description, '网页正文'); assert.ok(f.rows.local.some(i => i.name === 'WEB')); assert.ok(f.rows.remote.some(i => i.name === 'NEW'))
})
test('a committed response lost across restart retries the exact encrypted durable request once', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('NEW', '新增')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false; const next = new UiAutomationSync(f.options); await next.run(); assert.equal(f.rows.remote.length, count); assert.equal(next.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('target revision rejects the complete batch and a later round shows concurrent changes', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'LOCAL', '本地修改'); f.rows.local.push(item('NEW', '新增'))
  f.state.onCsrf = () => { f.edit('remote', 'LOCAL', '网页并发'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /revision/); assert.equal(f.client.state.pending, null); assert.ok(!f.rows.remote.some(i => i.name === 'NEW'))
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.ok(f.rows.remote.some(i => i.name === 'NEW'))
})
test('case spelling differences require manual correction without choosing an arbitrary asset', async () => {
  const f=fixture();f.rows.remote.push(item('local','网页大小写'));await f.bind()
  const c=f.client.state.conflicts.find(c=>c.key===keyOf(item('LOCAL','')));assert.equal(c.canResolve,false)
  await assert.rejects(f.client.resolve(keyOf(item('LOCAL','')),'local'));assert.equal(f.rows.remote.find(i=>i.name==='local').definition.description,'网页大小写')
})
test('deletion and rename preserve the other side without silently recreating or deleting', async () => {
  const f = fixture(); await f.bind(); f.rows.remote = f.rows.remote.filter(i => i.name !== 'LOCAL'); await f.client.run()
  assert.equal(f.client.state.conflicts.length, 1); assert.equal(f.client.state.conflicts[0].canResolve, false); assert.ok(f.rows.local.some(i => i.name === 'LOCAL'))
  assert.ok(!f.rows.remote.some(i => i.name === 'LOCAL'))
})
test('account, server, csrf races and failed durable writes prevent outgoing edits', async () => {
  for (const change of [f => { f.state.actor = 'E'.repeat(26) }, f => { f.state.server = 'https://other.test' }, f => { f.state.onCsrf = () => { f.state.actor = 'E'.repeat(26) } }, f => { f.state.failSave = true }]) {
    const f = fixture(); await f.bind(); f.rows.local.push(item('PRIVATE', '不得发送')); change(f); await assert.rejects(f.client.run()); assert.ok(!f.rows.remote.some(i => i.name === 'PRIVATE'))
  }
})
test('pause, restore and unbind preserve business data and require a fresh binding after restore', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('PRIVATE', '尚未发送')); await f.client.enabled(false); const count = f.state.writes.length
  await f.client.run(); assert.equal(f.state.writes.length, count); await f.client.enabled(true); await f.client.suspendForRestore(); await f.client.run()
  assert.equal(f.state.writes.length, count); await assert.rejects(f.client.enabled(true), /恢复过备份/); await f.client.unbind(); assert.equal(f.client.state.binding, null); assert.equal(f.rows.local.length, 3)
})
test('merged count and missing baseline budgets reject before any POST', async () => {
  const f = fixture(); const p = await f.client.preview(sourceId, targetId, server); f.edit('local', 'LOCAL', '预览后修改'); await assert.rejects(f.client.bind(p.token), /已变化/)
  await f.bind(); f.rows.local.push(...Array.from({ length: 98 }, (_, n) => item('N' + n, 'x'))); await f.client.run()
  f.rows.local = f.rows.local.filter(i => !/^N[0-9]$/.test(i.name)); f.rows.remote = f.rows.remote.filter(i => !/^N[0-9]$/.test(i.name)); f.rows.local.push(item('OVER', '超额'))
  const count = f.state.writes.length; await assert.rejects(f.client.run(), /缺失资料/); assert.equal(f.state.writes.length, count)
})
test('encrypted store hides asset content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-ui-automation-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new UiAutomationSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('本地原值'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const bad = copy(f.client.state); bad.bases.push(copy(bad.bases[0])); await assert.rejects(store.write(bad), /基线重复/)
    const unsafe = copy(f.client.state); unsafe.bases[0].executionHistory = 'must not persist'; await assert.rejects(store.write(unsafe), /资料无效/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})
test('native frame remains isolated and absent metadata does not start local services', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-performance-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
    const c = installUiAutomationSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:ui-automation-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})
const profile = name => ({kind:'PROFILE',name,definition:{browser:'chromium',baseUrl:'http://localhost:19957',viewportWidth:1440,viewportHeight:900}})
const scene = (name, objectName='LOCAL') => ({kind:'SCENARIO',name,definition:{browserProfileName:'BROWSER',description:'',failureStrategy:'STOP',variables:{b:'2',a:'1'},steps:[{id:'click',type:'CLICK',name:'点击',selector:null,pageObjectName:objectName,value:null,url:null,expectedText:null,timeoutMillis:10000}]}})
const schedule = code => ({kind:'SCHEDULE',name:'TIMER',definition:{scenarioName:'SCENE',functionalPlanCode:code,cronExpression:'0 0 0 1 1 ? 2099',timezone:'Asia/Shanghai',timeoutSeconds:120,maxRetryAttempts:1,retryBackoffSeconds:30}})
test('portable graph resolves references and map order is insignificant while step order stays meaningful', async () => {
  const f=fixture(); f.rows.local.push(profile('BROWSER'),scene('SCENE'),schedule(null)); await f.bind()
  const count=f.state.writes.length, target=f.rows.remote.find(i=>i.kind==='SCENARIO'); target.definition.variables={a:'1',b:'2'}
  await f.client.run();assert.equal(f.state.writes.length,count)
  f.rows.local.find(i=>i.kind==='SCENARIO').definition.steps.push({id:'wait',type:'WAIT',name:'等待',selector:null,pageObjectName:null,value:null,url:null,expectedText:null,timeoutMillis:20})
  await f.client.run(); assert.equal(f.rows.remote.find(i=>i.kind==='SCENARIO').definition.steps.length,2)
})
test('conflicting parent blocks new children and schedules until the parent is reviewed', async () => {
  const f=fixture(); f.rows.remote.push(item('LOCAL','网页不同')); f.rows.local.push(profile('BROWSER'),scene('SCENE'),schedule(null)); await f.bind()
  assert.equal(f.rows.remote.some(i=>i.kind==='SCENARIO'),false);assert.equal(f.rows.remote.some(i=>i.kind==='SCHEDULE'),false)
  assert.equal(f.client.state.conflicts.find(c=>c.local?.kind==='SCENARIO').canResolve,false)
  await f.client.resolve(keyOf(item('LOCAL','')),'local');await f.client.run();assert.equal(f.rows.remote.some(i=>i.kind==='SCENARIO'),true);assert.equal(f.rows.remote.some(i=>i.kind==='SCHEDULE'),true)
})
test('deleted baseline parent and name casing differences block dependent assets without restoration', async () => {
  const f=fixture(); f.rows.local.push(profile('BROWSER'));await f.bind();f.rows.remote=f.rows.remote.filter(i=>i.kind!=='PROFILE');f.rows.local.push(scene('SCENE'));await f.client.run()
  assert.equal(f.rows.remote.some(i=>i.kind==='PROFILE'),false);assert.equal(f.rows.remote.some(i=>i.kind==='SCENARIO'),false)
  const g=fixture();g.rows.local.push(profile('BROWSER'),scene('SCENE'));g.rows.remote.push(profile('browser'));await g.bind();assert.equal(g.rows.remote.some(i=>i.kind==='SCENARIO'),false)
})
test('missing target functional plan prevents schedule copy while unrelated assets remain syncable', async () => {
  const f=fixture(); f.rows.local.push(profile('BROWSER'),scene('SCENE'),schedule('PLAN-MISSING'));await f.bind()
  assert.equal(f.rows.remote.some(i=>i.kind==='SCENARIO'),true);assert.equal(f.rows.remote.some(i=>i.kind==='SCHEDULE'),false);assert.match(f.client.state.conflicts.find(c=>c.local?.kind==='SCHEDULE').reason,/功能测试计划/)
})
test('unknown scripts, unsafe URL credentials, dangling references and duplicate steps are refused before POST', async () => {
  for(const change of [rows=>{rows.push(profile('BROWSER'));rows.at(-1).definition.baseUrl='https://user:password@example.test'},rows=>{rows.push(profile('BROWSER'),scene('SCENE'));rows.at(-1).definition.steps[0].type='SCRIPT'},rows=>{rows.push(scene('SCENE'))},rows=>{rows.push(profile('BROWSER'),scene('SCENE'));rows.at(-1).definition.steps.push(copy(rows.at(-1).definition.steps[0]))}]) {
    const f=fixture();change(f.rows.local);await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)
  }
})
test('Web UI response bound and safe errors do not expose server internals', async () => {
  const profile = { label: 'Web UI 资料', limit: 4 * 1024 * 1024 }
  await assert.rejects(syncRequest(async () => new globalThis.Response(JSON.stringify({ data: 'x'.repeat(profile.limit) })), server, {}, profile), /4 MiB/)
  await assert.rejects(syncRequest(async () => new globalThis.Response('private detail', { status: 409 }), server, {}, profile), e => e.status === 409 && !e.message.includes('private detail'))
})

test('two valid endpoint snapshots with oversized combined variables cannot bind or write', async () => {
  const f=fixture(), variables=Object.fromEntries(Array.from({length:50},(_,n)=>['v'+n,'x'.repeat(4096)]))
  for(const side of ['local','remote']) {
    f.rows[side].push(profile('BROWSER'))
    for(let n=0;n<8;n++) {const value=scene(side+n,side==='local'?'LOCAL':'REMOTE');value.definition.variables=variables;f.rows[side].push(value)}
  }
  await assert.rejects(f.client.preview(sourceId,targetId,server),/3 MiB/);assert.equal(f.state.writes.length,0)
})
