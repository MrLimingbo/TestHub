'use strict'
const {Buffer}=require('node:buffer')
const test=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto')
const {MobileAutomationArtifactSync,MobileAutomationArtifactSyncStore,validateState,empty}=require('../app/src/mobile-automation-artifact-sync.cjs')
const {syncBinary}=require('../app/src/project-sync-ipc.cjs')
const localProject='A'.repeat(26),remoteProject='B'.repeat(26),localActor='C'.repeat(26),remoteActor='D'.repeat(26),server='https://sync.example.test'
const clone=v=>globalThis.structuredClone(v),sha=v=>crypto.createHash('sha256').update(v).digest('hex')
function entry(project,n=1,bytes=Buffer.from('原始日志\r\nfixture-auth=raw\0')){return {key:project+'.'+'E'.repeat(22)+String(n).padStart(4,'0')+'.'+'F'.repeat(22)+String(n).padStart(4,'0'),sourceArtifactId:'F'.repeat(22)+String(n).padStart(4,'0'),artifactType:'SCREENSHOT',originalName:'screenshot.png',contentType:'image/png',sizeBytes:bytes.length,checksum:sha(bytes),archivedAt:'2026-10-19T01:00:00Z',nativeRecord:true}}
function fixture(){
  const bytes=Buffer.from('原始日志\r\nfixture-auth=raw\0'),rows={local:[entry(localProject)],remote:[entry(remoteProject)]},histories={local:[],remote:[]},contents=new Map([...rows.local,...rows.remote].map(e=>[e.key,bytes]))
  function history(){const keys=[...new Set([...rows.local,...rows.remote].map(e=>e.key.slice(0,53)))];histories.local=[...keys];histories.remote=[...keys]};history()
  const state={actor:remoteActor,writes:[],reads:[],lose:false,incomplete:false,corrupt:new Set(),stale:false,targetChanged:false},receipts={local:new Map(),remote:new Map()};let saved
  const store={read:async()=>clone(saved??empty()),write:async v=>{validateState(v);saved=clone(v)}}
  const entries=side=>rows[side].map(e=>({...clone(e),nativeRecord:e.key.startsWith((side==='local'?localProject:remoteProject)+'.')}))
  const revision=side=>sha(JSON.stringify([histories[side],entries(side)]))
  const request=side=>async(route,options)=>{
    if(route.endsWith('/me'))return {id:side==='local'?localActor:state.actor,account:'fixture'}
    if(route.endsWith('/context'))return {organizations:[{id:'org',name:'组织'}],projects:{org:[{id:side==='local'?localProject:remoteProject,name:side}]}}
    if(route.endsWith('/csrf'))return {headerName:'X-CSRF',token:'fixture'}
    if(!options)return {version:1,scope:'MOBILE_ARTIFACT_SYNC',projectId:side==='local'?localProject:remoteProject,actorId:side==='local'?localActor:state.actor,revision:revision(side),historyKeys:clone(histories[side]),entries:entries(side)}
    const body=JSON.parse(options.body);state.writes.push({side,body});let result=receipts[side].get(body.requestKey)
    if(!result){
      if(body.expectedRevision!==revision(side)||rows[side].some(e=>e.key===body.artifact.key)){const error=new Error('revision changed');error.status=409;throw error}
      const bytes=Buffer.from(body.content,'base64');assert.equal(sha(bytes),body.artifact.checksum);assert.equal(body.rawFilesConfirmed,true)
      rows[side].push(clone(body.artifact));contents.set(body.artifact.key,bytes)
      result={requestKey:body.requestKey,key:body.artifact.key,sourceArtifactId:body.artifact.sourceArtifactId,checksum:body.artifact.checksum};receipts[side].set(body.requestKey,result)
    }
    if(state.lose)throw new Error('response lost');return state.incomplete?{requestKey:body.requestKey}:clone(result)
  }
  const binary=side=>async(route,size,hash)=>{
    const e=rows[side].find(e=>route.includes(e.key));state.reads.push(e.key)
    if(state.corrupt.has(e.key))return Buffer.from('corrupt')
    const body=Buffer.from(contents.get(e.key));assert.equal(body.length,size);assert.equal(sha(body),hash)
    if(state.stale){e.checksum='f'.repeat(64);state.stale=false}
    if(state.targetChanged){const other=side==='local'?'remote':'local';rows[other].push({...clone(e),checksum:'f'.repeat(64)});state.targetChanged=false}
    return body
  }
  const options={store,local:request('local'),remote:request('remote'),localBinary:binary('local'),remoteBinary:binary('remote'),guard:async b=>assert.equal(b.server,server)}
  const client=new MobileAutomationArtifactSync(options)
  const bind=async()=>{const p=await client.preview(localProject,remoteProject,server,true);await client.bind(p.token);await client.run()}
  return {client,options,rows,histories,state,contents,history,bind,bytes}
}
test('separate raw-file confirmation is required and preview never reads bytes',async()=>{const f=fixture();await assert.rejects(f.client.preview(localProject,remoteProject,server),/单独勾选/);const p=await f.client.preview(localProject,remoteProject,server,true);assert.equal(p.upload,1);assert.equal(p.download,1);assert.equal(f.state.reads.length,0);assert.equal(f.state.writes.length,0)})
test('immutable original screenshot bytes copy both ways once and receipts retain original identity',async()=>{const f=fixture();await f.bind();assert.equal(f.rows.local.length,2);assert.equal(f.rows.remote.length,2);assert.equal(Buffer.from(f.state.writes[0].body.content,'base64').equals(f.bytes),true);await f.client.run();assert.equal(f.state.writes.length,2)})
test('zero-byte artifacts round-trip without inventing content',async()=>{const f=fixture(),v=entry(localProject,3,Buffer.alloc(0));f.rows.local=[v];f.rows.remote=[];f.contents.set(v.key,Buffer.alloc(0));f.history();await f.bind();assert.equal(f.state.writes[0].body.content,'');assert.equal(f.rows.remote[0].sizeBytes,0)})
test('missing corresponding history pauses that artifact and does not create an execution',async()=>{const f=fixture();f.histories.remote=[];await f.bind();assert.equal(f.state.writes.length,0);assert.equal(f.client.state.conflicts.length,2);assert.match(f.client.state.conflicts[0].reason,/执行历史/)})
test('response loss replays identical durable request after restart without duplicate bytes',async()=>{const f=fixture(),p=await f.client.preview(localProject,remoteProject,server,true);await f.client.bind(p.token);f.state.lose=true;await assert.rejects(f.client.run(),/response lost/);const pending=clone(f.client.state.pending);assert.equal(JSON.stringify(await f.client.status()).includes(pending.request.content),false);f.state.lose=false;const client=new MobileAutomationArtifactSync(f.options);await client.run();const writes=f.state.writes.filter(w=>w.body.requestKey===pending.request.requestKey);assert.equal(writes.length,2);assert.deepEqual(writes[0],writes[1]);assert.equal(f.rows.remote.length,2)})
test('incomplete receipt stays pending until exact retry is acknowledged',async()=>{const f=fixture(),p=await f.client.preview(localProject,remoteProject,server,true);await f.client.bind(p.token);f.state.incomplete=true;await assert.rejects(f.client.run(),/回执不完整/);assert.ok(f.client.state.pending);f.state.incomplete=false;await f.client.run();assert.equal(f.client.state.pending,null);assert.equal(f.rows.remote.length,2)})
test('changed source or destination during byte transfer prevents an import',async()=>{for(const flag of ['stale','targetChanged']){const f=fixture(),p=await f.client.preview(localProject,remoteProject,server,true);await f.client.bind(p.token);f.state[flag]=true;await assert.rejects(f.client.run(),/工件已变化/);assert.equal(f.state.writes.length,0)}})
test('corrupt source pauses one file while an independent source still copies',async()=>{const f=fixture();f.state.corrupt.add(f.rows.local[0].key);await f.bind();assert.equal(f.state.writes.length,1);assert.equal(f.rows.local.length,2);assert.equal(f.client.state.conflicts.length,1);assert.equal(f.client.state.conflicts[0].key,f.rows.local[0].key)})
test('deletion and changed identity never recreate or overwrite a synchronized artifact',async()=>{for(const change of ['delete','replace']){const f=fixture();await f.bind();const key=f.rows.local[0].key;if(change==='delete')f.rows.remote=f.rows.remote.filter(e=>e.key!==key);else f.rows.remote.find(e=>e.key===key).checksum='f'.repeat(64);const n=f.state.writes.length;await f.client.run();assert.equal(f.state.writes.length,n);assert.ok(f.client.state.conflicts.some(c=>c.key===key));await assert.rejects(f.client.resolve(),/只读/)}})
test('account change and backup restore persist suspension until rebind',async()=>{const f=fixture();await f.bind();f.state.actor='X'.repeat(26);await assert.rejects(f.client.run(),/账号已变化/);f.state.actor=remoteActor;await f.client.suspendForRestore();const client=new MobileAutomationArtifactSync(f.options);await client.run();await assert.rejects(client.enabled(true),/恢复过备份/);await client.unbind();assert.equal(f.rows.remote.length,2)})
test('large histories progress in twenty-file rounds without storing original screenshot bytes in baselines',async()=>{const f=fixture();f.rows.local=Array.from({length:45},(_,n)=>entry(localProject,n+1));f.rows.remote=[];for(const e of f.rows.local)f.contents.set(e.key,f.bytes);f.history();await f.bind();assert.equal(f.rows.remote.length,20);assert.equal(f.client.state.remaining,25);await f.client.run();assert.equal(f.rows.remote.length,40);await f.client.run();assert.equal(f.rows.remote.length,45);assert.equal(f.client.state.remaining,0);assert.ok(f.client.state.bases.every(e=>!Object.hasOwn(e,'content')))})
test('binding revision changes require a new explicit preview',async()=>{const f=fixture(),p=await f.client.preview(localProject,remoteProject,server,true);f.histories.remote=[];await assert.rejects(f.client.bind(p.token),/确认期间已变化/);assert.equal(f.state.writes.length,0)})
test('durable state is encrypted and rejects corrupted pending bytes',async()=>{
 const fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path'),dir=await fs.mkdtemp(path.join(os.tmpdir(),'testhub-artifact-store-')),key=crypto.randomBytes(32),iv=crypto.randomBytes(12)
 const codec={encrypt:async text=>{const c=crypto.createCipheriv('aes-256-gcm',key,iv);return Buffer.concat([c.update(text),c.final(),c.getAuthTag()])},decrypt:async bytes=>{const d=crypto.createDecipheriv('aes-256-gcm',key,iv);d.setAuthTag(bytes.subarray(-16));return Buffer.concat([d.update(bytes.subarray(0,-16)),d.final()]).toString()}}
 try{const f=fixture(),p=await f.client.preview(localProject,remoteProject,server,true);await f.client.bind(p.token);f.state.lose=true;await assert.rejects(f.client.run());const store=new MobileAutomationArtifactSyncStore(dir,codec);await store.write(f.client.state);const bytes=await fs.readFile(store.file);assert.equal(bytes.includes(f.bytes),false);assert.deepEqual(await store.read(),f.client.state);const corrupt=clone(f.client.state);corrupt.pending.request.content=Buffer.from('wrong').toString('base64');assert.throws(()=>validateState(corrupt));if(process.platform!=='win32')assert.equal((await fs.stat(store.file)).mode&0o777,0o600)}finally{await fs.rm(dir,{recursive:true,force:true});key.fill(0)}
})
test('binary transport opts empty files in without weakening project-file minimum or digest checks',async()=>{const emptySha=sha(Buffer.alloc(0));assert.equal((await syncBinary(async()=>new globalThis.Response(''),'https://example.test',0,emptySha,{allowEmpty:true})).length,0);await assert.rejects(syncBinary(async()=>new globalThis.Response(''),'https://example.test',0,emptySha),/大小或摘要无效/);await assert.rejects(syncBinary(async()=>new globalThis.Response('bad'),'https://example.test',3,'0'.repeat(64)),e=>e.code==='SYNC_BINARY_INTEGRITY');await assert.rejects(syncBinary(async()=>new globalThis.Response('',{status:404}),'https://example.test',1,'0'.repeat(64)),e=>e.status===404)})

test('multiple screenshots from one source execution stay separate and 25 MiB transport is explicitly scoped',async()=>{
 const f=fixture(),first=entry(localProject,1),second=entry(localProject,2);second.key=first.key.slice(0,54)+second.sourceArtifactId;f.rows.local=[first,second];f.rows.remote=[];f.contents.set(second.key,f.bytes);f.history();await f.bind();assert.equal(f.rows.remote.length,2);assert.equal(new Set(f.rows.remote.map(e=>e.key)).size,2)
 const bytes=Buffer.alloc(25*1024*1024,120),options={allowEmpty:true,maxBytes:25*1024*1024};assert.equal((await syncBinary(async()=>new globalThis.Response(bytes),'https://example.test',bytes.length,sha(bytes),options)).length,bytes.length)
 await assert.rejects(syncBinary(async()=>new globalThis.Response(bytes),'https://example.test',bytes.length,sha(bytes)),/大小或摘要无效/)
})
