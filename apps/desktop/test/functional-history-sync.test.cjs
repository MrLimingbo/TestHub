'use strict'
const assert=require('node:assert/strict'),test=require('node:test'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto')
const {FunctionalHistorySync,FunctionalHistorySyncStore,keyOf,empty,validateState}=require('../app/src/functional-history-sync.cjs')
const {installFunctionalHistorySync}=require('../app/src/functional-history-sync-ipc.cjs')
const {syncRequest}=require('../app/src/project-sync-ipc.cjs')
const sourceId='A'.repeat(26),targetId='B'.repeat(26),localActor='C'.repeat(26),actor='D'.repeat(26),server='https://sync.example.test',L='L'.repeat(26),R='R'.repeat(26),N='N'.repeat(26)
const {Buffer}=require('node:buffer')
const copy=v=>globalThis.structuredClone(v),ref=i=>'RUN\n'+i.name.toLowerCase(),identity=k=>crypto.createHash('sha256').update(k).digest('hex').slice(0,26).toUpperCase(),at='2026-10-10T10:00:00.120Z'
const item=(name,note='原备注')=>({kind:'RUN',name,definition:{planCode:'PLAN-1',title:'同名执行',status:'NOT_STARTED',startedAt:null,finishedAt:null,cases:[{caseCode:'CASE-1',status:'NOT_RUN',note,executedAt:null,steps:[{number:1,text:'原历史步骤',status:'NOT_RUN',actualResult:'',updatedAt:null}]}]}})
function fixture(){
 const state={actor,server,writes:[],offline:false,loseResponse:false,failSave:false,identities:{local:{},remote:{}},layoutOverrides:{local:{},remote:{}},assets:{local:{planIds:{'PLAN-1':identity('localplan')},caseIds:{'CASE-1':identity('localcase')}},remote:{planIds:{'PLAN-1':identity('remoteplan')},caseIds:{'CASE-1':identity('remotecase')}}}}
 const rows={local:[item(L)],remote:[item(R)]},receipts={local:new Map(),remote:new Map()};let persisted
 const actual=(side,i)=>state.identities[side][ref(i)]||identity(side+ref(i))
 const layout=(side,i)=>state.layoutOverrides[side][ref(i)]||{planId:state.assets[side].planIds[i.definition.planCode],caseIds:Object.fromEntries(i.definition.cases.map(c=>[c.caseCode,identity(actual(side,i)+c.caseCode)])),stepIds:Object.fromEntries(i.definition.cases.map(c=>[c.caseCode,Object.fromEntries(c.steps.map(s=>[s.number,identity(actual(side,i)+c.caseCode+s.number)]))]))}
 const stamp=side=>crypto.createHash('sha256').update(JSON.stringify([rows[side],state.identities[side],state.layoutOverrides[side],state.assets[side]])).digest('hex')
 const store={read:async()=>copy(persisted??empty()),write:async v=>{validateState(v);if(v.pending&&state.failSave)throw Error('disk full');persisted=copy(v)}}
 const request=side=>async(route,options)=>{
  if(side==='remote'&&state.offline)throw Error('offline')
  if(route.endsWith('/me'))return {id:side==='local'?localActor:state.actor,displayName:'测试账号',account:'qa'}
  if(route.endsWith('/context'))return {organizations:[{id:'org',name:'QA'}],projects:{org:[{id:side==='local'?sourceId:targetId,name:side}]}}
  if(route.endsWith('/csrf')){state.onCsrf?.();return {headerName:'X-CSRF',token:'fixture'}}
  if(!options)return {version:1,scope:'FUNCTIONAL_HISTORY_SYNC',projectId:side==='local'?sourceId:targetId,actorId:side==='local'?localActor:state.actor,revision:stamp(side),items:copy(rows[side]),itemIds:Object.fromEntries(rows[side].map(i=>[ref(i),actual(side,i)])),layouts:Object.fromEntries(rows[side].map(i=>[ref(i),layout(side,i)])),...copy(state.assets[side]),createdAt:Object.fromEntries(rows[side].map(i=>[ref(i),at]))}
  const body=JSON.parse(options.body);state.writes.push({side,body});const conflict=()=>{const e=Error('revision or actor changed');e.status=409;throw e}
  if(body.expectedUserId!==(side==='local'?localActor:state.actor))conflict()
  let receipt=receipts[side].get(body.requestKey)
  if(!receipt){if(body.expectedRevision!==stamp(side))conflict();const next=copy(rows[side]);receipt={requestKey:body.requestKey,results:body.operations.map(op=>{const n=next.findIndex(i=>keyOf(i)===keyOf(op.item));if(n<0)next.push(copy(op.item));else next[n]=copy(op.item);return {operationId:op.operationId,item:copy(op.item),itemId:actual(side,op.item),layout:layout(side,op.item),assetIds:{['PLAN\n'+op.item.definition.planCode]:state.assets[side].planIds[op.item.definition.planCode],...Object.fromEntries(op.item.definition.cases.map(c=>['CASE\n'+c.caseCode,state.assets[side].caseIds[c.caseCode]]))}}})};rows[side]=next;receipts[side].set(body.requestKey,copy(receipt))}
  if(state.loseResponse)throw Error('response lost');return copy(receipt)
 }
 const options={store,local:request('local'),remote:request('remote'),guard:async b=>{if(b.server!==state.server)throw Error('server changed')}};const client=new FunctionalHistorySync(options)
 const bind=async(pairs=[])=>{const p=await client.preview(sourceId,targetId,server,pairs);await client.bind(p.token);await client.run()}
 const pair=()=>[{localRunId:actual('local',rows.local[0]),remoteRunId:actual('remote',rows.remote[0])}]
 const edit=(side,name,note)=>{rows[side].find(i=>i.name===name).definition.cases[0].note=note}
 return {state,rows,receipts,store,client,options,bind,pair,edit,actual,layout}
}
test('readonly preview keeps same-name unpaired batches separate and new copies do not repeat',async()=>{const f=fixture();await f.client.run();const p=await f.client.preview(sourceId,targetId,server);assert.equal(p.upload,1);assert.equal(p.download,1);assert.equal(f.state.writes.length,0);await f.client.bind(p.token);await f.client.run();assert.equal(f.rows.local.length,2);assert.equal(f.rows.remote.length,2);const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count);assert.equal(f.state.writes[0].body.operations[0].createdAt,at)})
test('manual existing pairing uses actual ids and plan and translates destination origin',async()=>{const f=fixture();const refs=await f.client.references(sourceId,targetId,server);assert.equal(refs.local[0].id,f.actual('local',f.rows.local[0]));await f.bind(f.pair());assert.equal(f.rows.local.length,1);assert.equal(f.rows.remote.length,1);f.edit('local',L,'本地编辑');await f.client.run();assert.equal(f.rows.remote[0].name,R);assert.equal(f.rows.remote[0].definition.cases[0].note,'本地编辑');assert.equal(f.state.writes[0].body.operations[0].item.name,R)})
test('manual pairs are one to one and cannot steal an existing shared origin',async()=>{const f=fixture();await assert.rejects(f.client.preview(sourceId,targetId,server,[...f.pair(),...f.pair()]),/一对一/);f.rows.remote.push(item(L));await assert.rejects(f.client.preview(sourceId,targetId,server,f.pair()),/同来源/);assert.equal(f.state.writes.length,0)})
test('empty projects can bind and later newly created histories synchronize',async()=>{const f=fixture();f.rows.local=[];f.rows.remote=[];await f.bind();f.rows.local.push(item(N));await f.client.run();assert.equal(f.rows.remote[0].name,N)})
test('offline local and independent web edits reconcile both ways on reconnect',async()=>{const f=fixture();await f.bind();f.state.offline=true;f.edit('local',L,'离线备注');f.rows.local.push(item(N));await assert.rejects(f.client.run(),/offline/);f.edit('remote',R,'网页结果');f.state.offline=false;await f.client.run();assert.equal(f.rows.remote.find(i=>i.name===L).definition.cases[0].note,'离线备注');assert.equal(f.rows.local.find(i=>i.name===R).definition.cases[0].note,'网页结果');assert.ok(f.rows.remote.some(i=>i.name===N))})
test('whole-batch conflicts show both editable histories and reject stale decisions',async()=>{const f=fixture();f.edit('remote',R,'网页原备注');await f.bind(f.pair());assert.equal(f.client.state.conflicts[0].canResolve,true);await f.client.resolve(keyOf(item(L)),'local');f.edit('local',L,'本地修改');f.edit('remote',R,'网页修改');await f.client.run();f.edit('remote',R,'网页再改');await assert.rejects(f.client.resolve(keyOf(item(L)),'local'),/已变化/);await f.client.run();await f.client.resolve(keyOf(item(L)),'remote');assert.equal(f.rows.local[0].definition.cases[0].note,'网页再改')})
test('steps actual results statuses notes and execution times propagate and can reopen',async()=>{const f=fixture();await f.bind(f.pair());const d=f.rows.local[0].definition;Object.assign(d,{status:'COMPLETED',startedAt:at,finishedAt:at});Object.assign(d.cases[0],{status:'PASSED',executedAt:at,note:'完成'});Object.assign(d.cases[0].steps[0],{status:'PASSED',actualResult:'实际通过',updatedAt:at});await f.client.run();assert.deepEqual(f.rows.remote[0].definition,d);d.status='NOT_STARTED';d.finishedAt=null;d.cases[0].status='NOT_RUN';d.cases[0].executedAt=null;await f.client.run();assert.deepEqual(f.rows.remote[0].definition,d)})
test('history layout mismatch blocks instead of replacing cases or steps',async()=>{const f=fixture();f.rows.remote[0].definition.cases[0].steps[0].text='不同历史步骤';await f.bind(f.pair());assert.equal(f.state.writes.length,0);assert.equal(f.client.state.conflicts[0].canResolve,false);assert.match(f.client.state.conflicts[0].reason,/布局不同/)})
test('missing destination dependency blocks new copies and resumes when explicitly prepared',async()=>{const f=fixture();delete f.state.assets.remote.caseIds['CASE-1'];f.rows.remote=[];await f.bind();assert.equal(f.state.writes.length,0);assert.match(f.client.state.conflicts[0].reason,/缺失/);f.state.assets.remote.caseIds['CASE-1']=identity('remotecase');await f.client.run();assert.equal(f.rows.remote.length,1)})
for(const object of ['run','plan','case','step'])test(`recreated ${object} identity blocks even identical history before equality`,async()=>{const f=fixture();await f.bind(f.pair());const original=copy(f.rows.local);if(object==='run')f.state.identities.remote[ref(f.rows.remote[0])]='E'.repeat(26);if(object==='plan')f.state.assets.remote.planIds['PLAN-1']='E'.repeat(26);if(object==='case')f.state.assets.remote.caseIds['CASE-1']='E'.repeat(26);if(object==='step'){const l=copy(f.layout('remote',f.rows.remote[0]));l.stepIds['CASE-1'][1]='E'.repeat(26);f.state.layoutOverrides.remote[ref(f.rows.remote[0])]=l}await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,false);assert.deepEqual(f.rows.local,original);assert.equal(f.state.writes.length,0)})
test('deleted synced batch never deletes or recreates either side',async()=>{const f=fixture();await f.bind(f.pair());f.rows.remote=[];await f.client.run();assert.equal(f.rows.local.length,1);assert.equal(f.rows.remote.length,0);assert.equal(f.client.state.conflicts[0].canResolve,false)})
test('durable lost response across restart replays exact operations once',async()=>{const f=fixture();await f.bind();f.rows.local.push(item(N));f.state.loseResponse=true;await assert.rejects(f.client.run(),/response lost/);const pending=copy(f.client.state.pending),count=f.rows.remote.length;f.state.loseResponse=false;const next=new FunctionalHistorySync(f.options);await next.run();assert.equal(next.state.pending,null);assert.equal(f.rows.remote.length,count);const writes=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(writes.length,2);assert.deepEqual(writes[0],writes[1])})
test('receipt remembers committed child identities and rejects recreation before retry',async()=>{const f=fixture();await f.bind();f.rows.local.push(item(N));f.state.loseResponse=true;await assert.rejects(f.client.run());f.state.loseResponse=false;f.state.identities.remote[ref(item(N))]='E'.repeat(26);const next=new FunctionalHistorySync(f.options);await next.run();assert.equal(next.state.conflicts.find(c=>c.key===keyOf(item(N))).canResolve,false)})
test('revision race atomically rejects request then shows whole batch conflict',async()=>{const f=fixture();await f.bind(f.pair());f.edit('local',L,'local');f.state.onCsrf=()=>{f.edit('remote',R,'race');f.state.onCsrf=undefined};await assert.rejects(f.client.run(),/revision/);assert.equal(f.client.state.pending,null);await f.client.run();assert.equal(f.client.state.conflicts[0].canResolve,true)})
test('account server csrf races and durable write failure prevent outgoing history',async()=>{for(const change of [f=>f.state.actor='E'.repeat(26),f=>f.state.server='https://other.test',f=>f.state.onCsrf=()=>{f.state.actor='E'.repeat(26)},f=>f.state.failSave=true]){const f=fixture();await f.bind();f.rows.local.push(item(N));change(f);await assert.rejects(f.client.run());assert.ok(!f.rows.remote.some(i=>i.name===N))}})
test('pause restore and unbind preserve history and require explicit rebind',async()=>{const f=fixture();await f.bind();f.rows.local.push(item(N));await f.client.enabled(false);const count=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,count);await f.client.enabled(true);await f.client.suspendForRestore();await assert.rejects(f.client.enabled(true),/恢复过备份/);await f.client.run();assert.equal(f.state.writes.length,count);await f.client.unbind();assert.equal(f.client.state.binding,null);assert.equal(f.rows.local.length,3)})
test('merged history cap includes missing old bases before any post',async()=>{const f=fixture();await f.bind();for(let n=0;n<98;n++)f.rows.local.push(item(String(n).padStart(26,'X')));await f.client.run();f.rows.local=f.rows.local.filter(i=>!i.name.endsWith('0'));f.rows.remote=f.rows.remote.filter(i=>!i.name.endsWith('0'));f.rows.local.push(item('Z'.repeat(26)));const count=f.state.writes.length;await assert.rejects(f.client.run(),/缺失资料/);assert.equal(f.state.writes.length,count)})
test('project links block affected history while unrelated batches sync',async()=>{const f=fixture();f.rows.local.push(item(N,'/api/v1/projects/'+sourceId+'/files/'+actor+'/download'));await f.bind();assert.ok(f.rows.remote.some(i=>i.name===L));assert.ok(!f.rows.remote.some(i=>i.name===N));assert.equal(f.client.state.conflicts[0].canResolve,false)})
test('confirmed file links in execution notes map both ways and stop after file deletion', async () => {
 const f=fixture(), localFile='F'.repeat(26), remoteFile='G'.repeat(26)
 const localPath=`/api/v1/projects/${sourceId}/files/${localFile}/download`,remotePath=`/api/v1/projects/${targetId}/files/${remoteFile}/download`
 const context={sourceProject:sourceId,targetProject:targetId,links:[{key:'K'.repeat(26),localId:localFile,remoteId:remoteFile}]}
 f.client.fileLinkContext=async()=>context
 f.rows.local[0].definition.cases[0].note=`截图 ${localPath}`
 await f.bind()
 assert.equal(f.rows.remote.find(i=>i.name===L).definition.cases[0].note,`截图 ${remotePath}`)
 f.edit('remote',L,`网页截图 ${remotePath}`);await f.client.run()
 assert.equal(f.rows.local[0].definition.cases[0].note,`网页截图 ${localPath}`)
 context.links=[];await f.client.run()
 assert.equal(f.client.state.conflicts.find(c=>c.local?.name===L).canResolve,false)
})
test('invalid actors time precision impossible results unknown fields and step order never post',async()=>{for(const change of [d=>d.createdBy=actor,d=>d.status='COMPLETED',d=>d.startedAt='2026-10-10T10:00:00.1234Z',d=>d.startedAt='2026-02-30T00:00:00Z',d=>d.cases[0].status='PASSED',d=>d.cases[0].steps[0].number=0,d=>d.cases[0].executedBy=actor]){const f=fixture();change(f.rows.local[0].definition);await assert.rejects(f.client.preview(sourceId,targetId,server));assert.equal(f.state.writes.length,0)}})

test('encrypted store hides asset content, detects corruption and refuses duplicate or unsafe definitions', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-functional-history-sync-')), key = crypto.randomBytes(32)
  const codec = { encrypt: async value => { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); return Buffer.concat([iv, c.update(value, 'utf8'), c.final(), c.getAuthTag()]) },
    decrypt: async bytes => { const c = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12)); c.setAuthTag(bytes.subarray(-16)); return Buffer.concat([c.update(bytes.subarray(12, -16)), c.final()]).toString() } }
  try {
    const f = fixture(); await f.bind(); const store = new FunctionalHistorySyncStore(directory, codec); await store.write(f.client.state); assert.deepEqual(await store.read(), f.client.state)
    const bytes = await fs.readFile(store.file); assert.ok(!bytes.includes(Buffer.from('原备注'))); assert.equal((await fs.stat(store.file)).mode & 0o777, 0o600)
    bytes[20] ^= 1; await fs.writeFile(store.file, bytes); await assert.rejects(store.read(), /无法解密/)
    const bad = copy(f.client.state); bad.bases.push(copy(bad.bases[0])); await assert.rejects(store.write(bad), /基线重复/)
    const unsafe = copy(f.client.state); unsafe.bases[0].definition.createdBy = 'must not persist'; await assert.rejects(store.write(unsafe), /资料无效/)
  } finally { key.fill(0); await fs.rm(directory, { recursive: true, force: true }) }
})

test('native frame remains isolated and absent metadata does not start local services', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'testhub-performance-ipc-')); let starts = 0
  try {
    const handlers = new Map(), setup = { webContents: { mainFrame: { url: 'testhub-desktop://setup/settings-sync.html' } } }
    const c = installFunctionalHistorySync({ ipcMain: { handle: (n, h) => handlers.set(n, h) }, windows: () => ({ setup }), origin: 'testhub-desktop://setup', directory,
      runtime: async () => { starts++; throw new Error('must not start') }, available: () => true })
    assert.throws(() => handlers.get('testhub-desktop:functional-history-sync:status')({ sender: setup.webContents, senderFrame: setup.webContents.mainFrame }), /不受信任/)
    await c.tick(); assert.equal(starts, 0); c.stop()
  } finally { await fs.rm(directory, { recursive: true, force: true }) }
})

test('bounded history HTTP transport hides raw backend traces and rejects oversized responses',async()=>{
 const profile={label:'版本资料',limit:4*1024*1024};await assert.rejects(syncRequest(async()=>new globalThis.Response('private backend trace',{status:500}),'https://example.test',{},profile),e=>e.message.includes('HTTP 500')&&!e.message.includes('trace'))
 await assert.rejects(syncRequest(async()=>new globalThis.Response('x'.repeat(profile.limit+1)),'https://example.test',{},profile),/超过 4 MiB/)
})
