'use strict'
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { FunctionalDefectSync, FunctionalDefectSyncStore, keyOf, empty, validateState } = require('../app/src/functional-defect-sync.cjs')
const { installFunctionalDefectSync } = require('../app/src/functional-defect-sync-ipc.cjs')
const { syncRequest } = require('../app/src/project-sync-ipc.cjs')
const sourceId = 'A'.repeat(26), targetId = 'B'.repeat(26), localActor = 'C'.repeat(26), actor = 'D'.repeat(26), server = 'https://sync.example.test'
const copy = value => globalThis.structuredClone(value)
const item = (name, description) => ({kind:'DEFECT',name,definition:{catalogPath:null,title:'缺陷',dueDate:null,bugType:'CODE_ERROR',keywords:'',browsers:[],operatingSystems:[],severity:'MAJOR',priority:'P1',source:'TESTHUB',externalUrl:null,description}})
function fixture() {
  const state = { actor, server, writes: [], offline: false, loseResponse: false, failSave: false }
  const rows = { local: [item('LOCAL', '本地原值')], remote: [item('REMOTE', '网页原值')] }
  const receipts = { local: new Map(), remote: new Map() }; let persisted
  const identity = (side,i) => state.identities?.[side]?.[keyOf(i)] || crypto.createHash('sha256').update(side+keyOf(i)).digest('hex').slice(0,26).toUpperCase()
  const stamp = side => crypto.createHash('sha256').update(JSON.stringify(rows[side])).digest('hex')
  const store = { read: async () => copy(persisted ?? empty()), write: async value => { validateState(value); if (value.pending && state.failSave) throw new Error('disk full'); persisted = copy(value) } }
  const request = side => async (route, options) => {
    if (side === 'remote' && state.offline) throw new Error('offline')
    if (route.endsWith('/me')) return { id: side === 'local' ? localActor : state.actor, displayName: '测试账号', account: 'qa' }
    if (route.endsWith('/context')) return { organizations: [{ id: 'org', name: '测试组织' }], projects: { org: [{ id: side === 'local' ? sourceId : targetId, name: side }] } }
    if (route.endsWith('/csrf')) { state.onCsrf?.(); return { headerName: 'X-CSRF', token: 'fixture' } }
    if (!options) return { version: 1, scope: 'FUNCTIONAL_DEFECT_SYNC', projectId: side === 'local' ? sourceId : targetId, actorId: side === 'local' ? localActor : state.actor,
      revision: stamp(side), items: copy(rows[side]), itemIds: Object.fromEntries(rows[side].map(i => [i.kind+'\n'+i.name.toLowerCase(), identity(side,i)])) }
    const body = JSON.parse(options.body); state.writes.push({ side, body })
    const conflict = () => { const e = new Error('revision or actor changed'); e.status = 409; throw e }
    if (body.expectedUserId !== (side === 'local' ? localActor : state.actor)) conflict()
    let result = receipts[side].get(body.requestKey)
    if (!result) {
      if (body.expectedRevision !== stamp(side)) conflict()
      const next = copy(rows[side])
      result = { requestKey: body.requestKey, results: body.operations.map(op => {
        const index = next.findIndex(item => keyOf(item) === keyOf(op.item)); if (index < 0) next.push(copy(op.item)); else next[index] = copy(op.item)
        return { operationId: op.operationId, item: copy(op.item), itemId: identity(side,op.item) }
      }) }; rows[side] = next; receipts[side].set(body.requestKey, copy(result))
    }
    if (state.loseResponse) throw new Error('response lost')
    return copy(result)
  }
  const options = { store, local: request('local'), remote: request('remote'), guard: async b => { if (b.server !== state.server) throw new Error('server changed') } }
  const client = new FunctionalDefectSync(options)
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
test('confirmed file links in defect description map both ways and stop after file deletion', async () => {
  const f = fixture(), localFile = 'L'.repeat(26), remoteFile = 'R'.repeat(26)
  const localPath = `/api/v1/projects/${sourceId}/files/${localFile}/download`, remotePath = `/api/v1/projects/${targetId}/files/${remoteFile}/download`
  const context = { sourceProject: sourceId, targetProject: targetId, links: [{ key: 'K'.repeat(26), localId: localFile, remoteId: remoteFile }] }
  f.client.fileLinkContext = async () => context
  f.rows.local.push(item('LINKED', `复现 ${localPath}`)); await f.bind()
  assert.equal(f.rows.remote.find(i => i.name === 'LINKED').definition.description, `复现 ${remotePath}`)
  f.edit('remote', 'LINKED', `网页复现 ${remotePath}`); await f.client.run()
  assert.equal(f.rows.local.find(i => i.name === 'LINKED').definition.description, `网页复现 ${localPath}`)
  context.links = []; await f.client.run()
  assert.equal(f.client.state.conflicts.find(c => c.local?.name === 'LINKED').canResolve, false)
})
test('a committed response lost across restart retries the exact encrypted durable request once', async () => {
  const f = fixture(); await f.bind(); f.rows.local.push(item('NEW', '新增')); f.state.loseResponse = true
  await assert.rejects(f.client.run(), /response lost/); const pending = copy(f.client.state.pending), count = f.rows.remote.length
  f.state.loseResponse = false; const next = new FunctionalDefectSync(f.options); await next.run(); assert.equal(f.rows.remote.length, count); assert.equal(next.state.pending, null)
  const attempts = f.state.writes.filter(w => w.body.requestKey === pending.request.requestKey); assert.equal(attempts.length, 2); assert.deepEqual(attempts[0], attempts[1])
})
test('target revision rejects the complete batch and a later round shows concurrent changes', async () => {
  const f = fixture(); await f.bind(); f.edit('local', 'LOCAL', '本地修改'); f.rows.local.push(item('NEW', '新增'))
  f.state.onCsrf = () => { f.edit('remote', 'LOCAL', '网页并发'); f.state.onCsrf = undefined }
  await assert.rejects(f.client.run(), /revision/); assert.equal(f.client.state.pending, null); assert.ok(!f.rows.remote.some(i => i.name === 'NEW'))
  await f.client.run(); assert.equal(f.client.state.conflicts.length, 1); assert.ok(f.rows.remote.some(i => i.name === 'NEW'))
})
test('catalog path spelling differences require manual correction', async () => {
  const f=fixture();f.rows.local.push(catalog('DELIVERY',['Name'],1));f.rows.remote.push(catalog('DELIVERY',['name'],2));await f.bind()
  const c=f.client.state.conflicts.find(c=>c.local?.kind==='CATALOG_DELIVERY');assert.equal(c.canResolve,false)
  await assert.rejects(f.client.resolve(c.key,'local'))
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
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-functional-defect-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new FunctionalDefectSyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
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
    const c = installFunctionalDefectSync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:functional-defect-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})


const catalog=(scope,parts,sortOrder=0)=>({kind:'CATALOG_'+scope,name:JSON.stringify(parts),definition:{sortOrder}})
test('nested delivery folders remap references and preserve portable arrays and nullable clear operations',async()=>{
 const f=fixture(),row=item('BUG-1','<p>复现</p>');row.definition.catalogPath='["交付","订单"]';row.definition.browsers=['CHROME','SAFARI'];row.definition.operatingSystems=['LINUX','MAC_OS'];row.definition.dueDate='2026-12-31';row.definition.externalUrl='https://bugs.example.test/42';f.rows.local.push(row,catalog('DELIVERY',['交付','订单'],9),catalog('DELIVERY',['交付'],2));await f.bind();assert.deepEqual(f.rows.remote.find(i=>i.name==='BUG-1'),row)
 const edited=f.rows.local.find(i=>i.name==='BUG-1');edited.definition.dueDate=null;edited.definition.externalUrl=null;edited.definition.catalogPath=null;await f.client.run();assert.deepEqual(f.rows.remote.find(i=>i.name==='BUG-1').definition,edited.definition)
})
test('conflicting delivery folders block defects and restored dependencies require an explicit new choice',async()=>{
 const f=fixture(),row=item('BUG-1','复现');row.definition.catalogPath='["交付","订单"]';f.rows.local.push(row,catalog('DELIVERY',['交付','订单']),catalog('DELIVERY',['交付'],2));f.rows.remote.push(catalog('DELIVERY',['交付'],3));await f.bind();assert.equal(f.rows.remote.some(i=>i.name==='BUG-1'),false)
 await f.client.resolve(keyOf(catalog('DELIVERY',['交付'])),'local');await f.client.run();assert.ok(f.rows.remote.some(i=>i.name==='BUG-1'))
 f.rows.remote=f.rows.remote.filter(i=>i.name!=='BUG-1');await f.client.run();f.rows.remote.push(item('BUG-1','恢复后不同'));await f.client.run();assert.equal(f.client.state.conflicts.find(c=>c.key===keyOf(row)).canResolve,true);assert.equal(row.definition.description,'复现');await f.client.resolve(keyOf(row),'remote');assert.equal(f.rows.local.find(i=>i.name==='BUG-1').definition.description,'恢复后不同')
})
test('same-code deletion and recreation between polls requires review rather than an ordinary download',async()=>{
 const f=fixture();await f.bind();await f.client.run();f.state.identities={remote:{[keyOf(f.rows.remote.find(i=>i.name==='LOCAL'))]:'E'.repeat(26)}};f.edit('remote','LOCAL','同号新缺陷');await f.client.run();assert.equal(f.rows.local.find(i=>i.name==='LOCAL').definition.description,'本地原值');const c=f.client.state.conflicts.find(c=>c.key===keyOf(item('LOCAL','')));assert.equal(c.canResolve,true);await f.client.resolve(c.key,'remote');await f.client.run();assert.equal(f.client.state.conflicts.length,0)
})
test('file references pause affected defects while unrelated ones continue',async()=>{
 const f=fixture();f.rows.local.push(item('LINKED','/api/v1/projects/'+sourceId+'/files/'+actor+'/download'));await f.bind();assert.equal(f.rows.remote.some(i=>i.name==='LINKED'),false);assert.equal(f.client.state.conflicts.find(c=>c.local?.name==='LINKED').canResolve,false);assert.ok(f.rows.remote.some(i=>i.name==='LOCAL'))
})
test('workflow personnel unknown fields invalid dates and noncanonical selections never post',async()=>{
 for(const change of [d=>{d.status='CLOSED'},d=>{d.assigneeId=actor},d=>{d.dueDate='2026-02-30'},d=>{d.description='<p>&nbsp;</p>'},d=>{d.browsers=['SAFARI','CHROME']},d=>{d.operatingSystems=['LINUX','LINUX']},d=>{d.catalogPath='["不存在"]'},d=>{d.externalUrl='file:///tmp/private'}]){const f=fixture();change(f.rows.local[0].definition);await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)}
})

test('bounded defect HTTP transport hides raw backend traces and rejects oversized responses',async()=>{
 const profile={label:'缺陷基本资料',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private backend trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'))
 await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})

test('replayed receipt retains committed identity when a target was recreated before retry',async()=>{
 const f=fixture();await f.bind();f.rows.local.push(item('NEW','原创建'));f.state.loseResponse=true;await assert.rejects(f.client.run());f.state.loseResponse=false;f.state.identities={remote:{[keyOf(item('NEW',''))]:'F'.repeat(26)}};f.edit('remote','NEW','重建正文');const next=new FunctionalDefectSync(f.options);await next.run();assert.equal(f.rows.local.find(i=>i.name==='NEW').definition.description,'原创建');assert.equal(next.state.conflicts.find(c=>c.key===keyOf(item('NEW',''))).canResolve,true)
})
