'use strict'
const assert=require('node:assert/strict'),test=require('node:test'),crypto=require('node:crypto')
const {FunctionalHistorySync,keyOf,empty,validateState}=require('../app/src/functional-history-sync.cjs')
const sourceId='A'.repeat(26),targetId='B'.repeat(26),localActor='C'.repeat(26),actor='D'.repeat(26),server='https://sync.example.test',L='L'.repeat(26),R='R'.repeat(26)
const copy=v=>globalThis.structuredClone(v),ref=i=>'RUN\n'+i.name.toLowerCase(),identity=k=>crypto.createHash('sha256').update(k).digest('hex').slice(0,26).toUpperCase(),at='2026-10-10T10:00:00.120Z'
const item=(name,note='原备注')=>({kind:'RUN',name,definition:{planCode:'PLAN-1',title:'同名执行',status:'NOT_STARTED',startedAt:null,finishedAt:null,cases:[{caseCode:'CASE-1',status:'NOT_RUN',note,executedAt:null,steps:[{number:1,text:'原历史步骤',status:'NOT_RUN',actualResult:'',updatedAt:null}]}]}})
function fixture(){
 const state={support:{local:true,remote:true},actor,server,writes:[],offline:false,loseResponse:false,failSave:false,identities:{local:{},remote:{}},layoutOverrides:{local:{},remote:{}},assets:{local:{planIds:{'PLAN-1':identity('localplan')},caseIds:{'CASE-1':identity('localcase')}},remote:{planIds:{'PLAN-1':identity('remoteplan')},caseIds:{'CASE-1':identity('remotecase')}}}}
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
  if(!options)return {titleSnapshotsSupported:state.support[side],version:1,scope:'FUNCTIONAL_HISTORY_SYNC',projectId:side==='local'?sourceId:targetId,actorId:side==='local'?localActor:state.actor,revision:stamp(side),items:copy(rows[side]),itemIds:Object.fromEntries(rows[side].map(i=>[ref(i),actual(side,i)])),layouts:Object.fromEntries(rows[side].map(i=>[ref(i),layout(side,i)])),...copy(state.assets[side]),createdAt:Object.fromEntries(rows[side].map(i=>[ref(i),at]))}
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

test('N01 title snapshots survive real client transport while legacy data stays missing',async()=>{const f=fixture();f.rows.remote=[];f.rows.local[0].definition.cases[0].titleSnapshot='历史标题';await f.bind();assert.equal(f.rows.remote[0].definition.cases[0].titleSnapshot,'历史标题');const old=fixture();await old.bind();assert.equal(old.rows.remote[0].definition.cases[0].titleSnapshot,undefined)})
test('N02 unsupported peers stop sync and transfer before sending snapshot data',async()=>{const f=fixture();f.rows.local[0].definition.cases[0].titleSnapshot='历史标题';f.state.support.remote=false;await assert.rejects(f.client.preview(sourceId,targetId,server),/不支持历史标题快照/);assert.equal(f.state.writes.length,0);
 const {FunctionalTransfer}=require('../app/src/functional-transfer.cjs');const context=id=>({organizations:[{id:'org',name:'org'}],projects:{org:[{id,name:'project'}]}});const client=new FunctionalTransfer({current:()=>server,remote:async route=>route.endsWith('/me')?{id:actor,account:'qa'}:route.endsWith('/context')?context(targetId):{version:1,scope:'FUNCTIONAL_DEFINITIONS',versions:[6]},local:async route=>route.endsWith('/context')?context(sourceId):{version:6,catalogs:[],requirements:[],cases:[{}],plans:[],history:{runs:[{cases:[{steps:[],titleSnapshot:'历史标题'}]}]}}});await assert.rejects(client.prepare(sourceId,targetId,true,false,false,false,true),/不支持历史标题快照/)
})
test('N03 different immutable titles produce a non-resolvable layout conflict',async()=>{const f=fixture();f.rows.local[0].definition.cases[0].titleSnapshot='A';f.rows.remote[0].definition.cases[0].titleSnapshot='B';const p=await f.client.preview(sourceId,targetId,server,f.pair());assert.equal(p.conflictCount,1);assert.equal(p.upload,0);assert.equal(p.download,0);await f.client.bind(p.token);await f.client.run();const conflicts=(await f.client.status()).conflicts;assert.equal(conflicts[0].canResolve,false);assert.equal(f.state.writes.length,0)})
