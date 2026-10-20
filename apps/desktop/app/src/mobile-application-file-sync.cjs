'use strict'
const crypto=require('node:crypto')
const {Buffer}=require('node:buffer')
const {normalizeServerUrl}=require('./desktop-core.cjs')
const {EncryptedSyncStore}=require('./encrypted-sync-store.cjs')
const {validItem:validFile}=require('./project-file-sync.cjs')
const clone=v=>globalThis.structuredClone(v)
const id=v=>typeof v==='string'&&/^[A-Z0-9]{26}$/.test(v)
const digest=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const uuid=v=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const name=v=>typeof v==='string'&&v.length>0&&v===v.trim()&&v.length<=120
const platform=v=>['ANDROID','IOS'].includes(v)
const integer=v=>Number.isSafeInteger(v)&&v>=1&&v<=2147483647
const size=v=>Number.isSafeInteger(v)&&v>=1&&v<=20*1024*1024
const exact=(v,fields)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===fields.length&&fields.every(f=>Object.hasOwn(v,f))
const keyOf=v=>v.name.toLowerCase()
const fields=['name','platform','fileKey','fileSha','fileSize','attached','fileAvailable']
function validateEntry(v){
 if(!exact(v,fields)||!name(v.name)||!platform(v.platform)||typeof v.attached!=='boolean'||typeof v.fileAvailable!=='boolean'
  ||!(v.fileKey===null||id(v.fileKey))||!(v.fileSha===null||digest(v.fileSha))||!(v.fileSize===null||size(v.fileSize))
  ||(!v.attached&&(v.fileKey!==null||v.fileSha!==null||v.fileSize!==null||!v.fileAvailable))
  ||(v.attached&&v.fileAvailable&&(!id(v.fileKey)||!digest(v.fileSha)||!size(v.fileSize))))throw new Error('移动应用文件关联资料无效。')
 return v
}
const same=(a,b)=>Boolean(a&&b)&&fields.every(f=>a[f]===b[f])
const empty=()=>({version:1,binding:null,bases:[],pending:null,conflicts:[],lastSyncedAt:null,remaining:0})
function validateState(v){
 if(v?.version!==1||!Array.isArray(v.bases)||v.bases.length>100||!Array.isArray(v.conflicts)||v.conflicts.length>200||!Number.isSafeInteger(v.remaining)||v.remaining<0||v.remaining>100)throw new Error('应用文件关联同步记录损坏。')
 const b=v.binding
 if(b&&(normalizeServerUrl(b.server)!==b.server||!id(b.actor?.id)||!id(b.localActorId)||!id(b.source?.id)||!id(b.target?.id)||b.fileAssociationsConfirmed!==true||typeof b.enabled!=='boolean'||(b.requiresRebind!==undefined&&typeof b.requiresRebind!=='boolean')))throw new Error('应用文件关联绑定无效或未单独确认。')
 if(!b&&(v.bases.length||v.pending||v.conflicts.length||v.remaining))throw new Error('未绑定的应用文件不能保留同步请求。')
 const keys=new Set();for(const e of v.bases){validateEntry(e);if(!e.fileAvailable||keys.has(keyOf(e)))throw new Error('关联基线重复或文件尚未确认。');keys.add(keyOf(e))}
 const conflicts=new Set();for(const c of v.conflicts){if(typeof c.key!=='string'||!name(c.key)||c.key!==c.key.toLowerCase()||conflicts.has(c.key)||typeof c.reason!=='string'||typeof c.canResolve!=='boolean')throw new Error('关联核对记录无效。');conflicts.add(c.key);for(const e of [c.local,c.remote])if(e!==null){validateEntry(e);if(keyOf(e)!==c.key)throw new Error('关联核对名称不匹配。')}}
 const p=v.pending
 if(p){validateEntry(p.entry);const r=p.request
  if(!b||!['local','remote'].includes(p.side)||!p.entry.attached||!p.entry.fileAvailable||!exact(r,['requestKey','expectedUserId','expectedRevision','applicationId','expectedVersion','expectedFileId','fileId','size','sha256'])||!uuid(r?.requestKey)||!digest(r.expectedRevision)||!id(r.applicationId)||!integer(r.expectedVersion)||!(r.expectedFileId===null||id(r.expectedFileId))||!id(r.fileId)||r.size!==p.entry.fileSize||r.sha256!==p.entry.fileSha||r.expectedUserId!==(p.side==='local'?b.localActorId:b.actor.id)||Buffer.byteLength(JSON.stringify(r))>1024*1024)throw new Error('待确认应用文件关联请求无效。')
 }
 return v
}
class MobileApplicationFileSyncStore extends EncryptedSyncStore {
 constructor(directory,codec){super(directory,codec,{filename:'mobile-application-file-sync.bin',validate:validateState,empty,label:'移动应用文件关联',maxPlain:2*1024*1024,maxEncrypted:3*1024*1024})}
}
class MobileApplicationFileSync {
 constructor({store,local,remote,localBinary,remoteBinary,guard,fileLinkContext,now=()=>new Date().toISOString()}){Object.assign(this,{store,local,remote,localBinary,remoteBinary,guard,fileLinkContext,now});this.busy=false}
 async load(){if(!this.state)this.state=validateState(await this.store.read());return this.state}
 async save(v){validateState(v);await this.store.write(v);this.state=v}
 async status(){const {pending,...v}=await this.load();return {...clone(v),pending:Boolean(pending)}}
 async actors(b){await this.guard(b);const [l,r]=await Promise.all([this.local('/api/v1/auth/me'),this.remote('/api/v1/auth/me',undefined,b)]);if(l.id!==b.localActorId||r.id!==b.actor.id)throw new Error('绑定账号已变化，应用文件关联同步已停止。')}
 async mapping(b){
  const value=await this.fileLinkContext?.(b)
  if(!value||value.sourceProject!==b.source.id||value.targetProject!==b.target.id||!Array.isArray(value.links)||value.links.length>1000)throw new Error('请先启用同账号、服务和项目范围的项目文件同步；其暂停或恢复核对期间不更新应用关联。')
  const keys=new Set(),local=new Set(),remote=new Set()
  for(const p of value.links){if(!id(p.key)||!id(p.localId)||!id(p.remoteId)||keys.has(p.key)||local.has(p.localId)||remote.has(p.remoteId))throw new Error('项目文件配对标识无效或重复。');keys.add(p.key);local.add(p.localId);remote.add(p.remoteId)}return value
 }
 async rows(side,b,mapping){
  const project=side==='local'?b.source.id:b.target.id,actor=side==='local'?b.localActorId:b.actor.id
  const v=await this[side](`/api/v1/projects/${project}/mobile-automation/application-files/sync?expectedUserId=${actor}`,undefined,b)
  if(v?.version!==1||v.scope!=='MOBILE_APPLICATION_FILE_SYNC'||v.projectId!==project||v.actorId!==actor||!digest(v.revision)||!Array.isArray(v.applications)||v.applications.length>100||!Array.isArray(v.files)||v.files.length>1000||v.files.some(f=>!validFile(f))||new Set(v.files.map(f=>f.id)).size!==v.files.length||new Set(v.files.map(f=>f.key)).size!==v.files.length)throw new Error('应用文件关联服务协议或文件索引无效。')
  const ids=new Set(),names=new Set(),records=[]
  for(const app of v.applications){
   if(!exact(app,['id','name','platform','version','enabled','fileId'])||!id(app.id)||!name(app.name)||!platform(app.platform)||!integer(app.version)||typeof app.enabled!=='boolean'||!(app.fileId===null||id(app.fileId))||ids.has(app.id)||names.has(keyOf(app)))throw new Error('移动应用名称、平台或标识重复／无效。')
   ids.add(app.id);names.add(keyOf(app));const file=v.files.find(f=>f.id===app.fileId),link=mapping.links.find(p=>p[side+'Id']===app.fileId)
   const entry={name:app.name,platform:app.platform,fileKey:link?.key??null,fileSha:file?.sha256??null,fileSize:file?.size??null,attached:app.fileId!==null,fileAvailable:app.fileId===null||Boolean(file&&link)}
   validateEntry(entry);records.push({app,file,entry})
  }
  return {...v,records,items:records.map(r=>r.entry)}
 }
 changes(l,r){
  const local=new Map(l.items.map(e=>[keyOf(e),e])),remote=new Map(r.items.map(e=>[keyOf(e),e])),bases=new Map(this.state.bases.map(e=>[keyOf(e),e]))
  const keys=new Set([...local.keys(),...remote.keys(),...bases.keys()]);if(keys.size>100)throw new Error('合并后移动应用超过 100 个，暂停关联同步。')
  const push=[],pull=[],conflicts=[],unchanged=[]
  for(const key of keys){const a=local.get(key),b=remote.get(key),base=bases.get(key),issue=(reason,canResolve=false)=>conflicts.push({key,local:a??null,remote:b??null,reason,canResolve})
   if(!a||!b){issue('一端应用缺失或已改名，请先同步移动端资料；不会重建应用。');continue}
   if(a.name!==b.name||a.platform!==b.platform){issue('同名应用的名称大小写或平台不同，请先统一应用资料。');continue}
   if(!a.fileAvailable||!b.fileAvailable){issue('关联文件缺失、未配对或内容已变化，请先核对项目文件同步；不会重建文件。');continue}
   if(base?.attached&&(!a.attached||!b.attached)){issue('一端已清除应用文件关联，请手动核对；不跨端清除或自动恢复。');continue}
   if(same(a,b)){unchanged.push(a);continue}
   if(a.attached&&b.attached&&(!base||(!same(a,base)&&!same(b,base)))){issue('两端应用文件关联不同，请选择已经核对的版本。',true);continue}
   const source=!b.attached||(base&&!same(a,base))?a:b
   if(!source.attached){issue('空文件关联不会自动覆盖另一端，请手动核对。');continue}
   ;(source===a?push:pull).push(source)
  }
  return {push,pull,conflicts,unchanged,upload:push.length,download:pull.length,conflictCount:conflicts.length}
 }
 async preview(sourceId,targetId,server,fileAssociationsConfirmed){
  if(fileAssociationsConfirmed!==true)throw new Error('请单独确认同步应用文件关联；文件字节由已确认的项目文件绑定传输。')
  await this.load();this.plan=undefined;if(this.state.binding)throw new Error('已有应用文件关联绑定，请先解除。');if(!id(sourceId)||!id(targetId))throw new Error('请选择有效项目。')
  server=normalizeServerUrl(server)
  const [actor,localActor,l,r]=await Promise.all([this.remote('/api/v1/auth/me',undefined,{server}),this.local('/api/v1/auth/me'),this.local('/api/v1/access/context'),this.remote('/api/v1/access/context',undefined,{server})])
  const projects=v=>v.organizations.flatMap(o=>(v.projects[o.id]||[]).map(p=>({id:p.id,name:p.name,organization:o.name}))),source=projects(l).find(p=>p.id===sourceId),target=projects(r).find(p=>p.id===targetId)
  if(!source||!target)throw new Error('项目权限已变化，请重新核对。')
  const binding={server,actor:{id:actor.id,account:actor.account||actor.email},localActorId:localActor.id,source,target,enabled:true,fileAssociationsConfirmed:true}
  await this.actors(binding);const mapping=await this.mapping(binding),[localRows,remoteRows]=await Promise.all([this.rows('local',binding,mapping),this.rows('remote',binding,mapping)]),counts=this.changes(localRows,remoteRows)
  this.plan={token:crypto.randomUUID(),expires:Date.now()+600000,binding,mapping,localRows,remoteRows,counts}
  return {token:this.plan.token,binding:clone(binding),upload:counts.upload,download:counts.download,conflictCount:counts.conflictCount}
 }
 async bind(token){
  const p=this.plan;if(!p||p.token!==token||p.expires<=Date.now()||this.state.binding)throw new Error('绑定预览已失效。')
  await this.actors(p.binding);const mapping=await this.mapping(p.binding),[l,r]=await Promise.all([this.rows('local',p.binding,mapping),this.rows('remote',p.binding,mapping)])
  if(JSON.stringify(mapping)!==JSON.stringify(p.mapping)||l.revision!==p.localRows.revision||r.revision!==p.remoteRows.revision)throw new Error('应用或文件配对在确认期间已变化，请重新预览。')
  await this.save({...empty(),binding:p.binding});this.plan=undefined
 }
 async enabled(value){await this.load();if(!this.state.binding||typeof value!=='boolean')throw new Error('尚未绑定应用文件关联。');if(value&&this.state.binding.requiresRebind)throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。');if(value){await this.actors(this.state.binding);await this.mapping(this.state.binding)}const next=clone(this.state);next.binding.enabled=value;await this.save(next)}
 async unbind(){await this.save(empty());this.plan=undefined}
 async suspendForRestore(){await this.load();if(this.state.binding){const next=clone(this.state);next.binding.enabled=false;next.binding.requiresRebind=true;await this.save(next)}}
 async verifyPair(entry,b,mapping){
  const pair=mapping.links.find(p=>p.key===entry.fileKey);if(!pair)throw new Error('关联文件尚未配对、已删除或内容已变化。')
  for(const side of ['local','remote']){
   const project=side==='local'?b.source.id:b.target.id;let bytes
   try{bytes=await this[side+'Binary'](`/api/v1/projects/${project}/files/${pair[side+'Id']}/download`,entry.fileSize,entry.fileSha,b)}catch(error){if([404,502].includes(error.status)||error.code==='SYNC_BINARY_INTEGRITY')error.appFileUnavailable=true;throw error}
   if(bytes.length!==entry.fileSize||crypto.createHash('sha256').update(bytes).digest('hex')!==entry.fileSha){const error=new Error('应用文件字节或 SHA-256 已变化，关联未提交。');error.appFileUnavailable=true;throw error}
  }
  return pair
 }
 async recoverPending(b){
  const p=this.state.pending;if(!p)return
  await this.actors(b);const mapping=await this.mapping(b),pair=await this.verifyPair(p.entry,b,mapping)
  if(pair[p.side+'Id']!==p.request.fileId)throw new Error('待确认关联的项目文件配对已变化，请人工核对。')
  const csrf=await this[p.side]('/api/v1/auth/csrf',undefined,b);await this.guard(b)
  const project=p.side==='local'?b.source.id:b.target.id;let result
  try{result=await this[p.side](`/api/v1/projects/${project}/mobile-automation/application-files/sync`,{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(p.request)},b)}catch(e){if(e.status===409){const next=clone(this.state);next.pending=null;await this.save(next)}throw e}
  if(result?.requestKey!==p.request.requestKey||result.applicationId!==p.request.applicationId||result.fileId!==p.request.fileId||result.size!==p.entry.fileSize||result.sha256!==p.entry.fileSha||result.version!==p.request.expectedVersion+(p.request.expectedFileId===p.request.fileId?0:1))throw new Error('应用文件关联回执不完整，保留原请求供安全重试。')
  const next=clone(this.state);next.bases=next.bases.filter(e=>keyOf(e)!==keyOf(p.entry)).concat(p.entry);next.conflicts=next.conflicts.filter(c=>c.key!==keyOf(p.entry));next.pending=null;await this.save(next)
 }
 async send(sourceSide,entry,b,initial){
  const targetSide=sourceSide==='local'?'remote':'local',mapping=await this.mapping(b)
  await this.verifyPair(entry,b,mapping);await this.actors(b)
  const freshMapping=await this.mapping(b),[l,r]=await Promise.all([this.rows('local',b,freshMapping),this.rows('remote',b,freshMapping)]),fresh={local:l,remote:r}
  for(const side of ['local','remote']){const before=initial[side].records.find(r=>keyOf(r.entry)===keyOf(entry)),after=fresh[side].records.find(r=>keyOf(r.entry)===keyOf(entry));if(!before||!after||!same(before.entry,after.entry)||JSON.stringify(before.app)!==JSON.stringify(after.app))throw new Error('应用关联在文件核对期间已变化，请重新同步。')}
  const source=fresh[sourceSide].records.find(r=>keyOf(r.entry)===keyOf(entry));if(!same(source.entry,entry))throw new Error('来源应用文件关联已变化。')
  const target=fresh[targetSide],app=target.records.find(r=>keyOf(r.entry)===keyOf(entry)).app,pair=freshMapping.links.find(p=>p.key===entry.fileKey)
  if(!pair)throw new Error('文件配对已变化。')
  const next=clone(this.state);next.pending={side:targetSide,entry:clone(entry),request:{requestKey:crypto.randomUUID(),expectedUserId:targetSide==='local'?b.localActorId:b.actor.id,expectedRevision:target.revision,applicationId:app.id,expectedVersion:app.version,expectedFileId:app.fileId,fileId:pair[targetSide+'Id'],size:entry.fileSize,sha256:entry.fileSha}}
  await this.save(next);await this.recoverPending(b)
 }
 async run(){
  if(this.busy)return;this.busy=true
  try{
   await this.load();const b=this.state.binding;if(!b?.enabled||b.requiresRebind)return
   await this.actors(b);await this.recoverPending(b);const mapping=await this.mapping(b),[l,r]=await Promise.all([this.rows('local',b,mapping),this.rows('remote',b,mapping)]),changes=this.changes(l,r)
   const next=clone(this.state);next.conflicts=changes.conflicts;for(const e of changes.unchanged)next.bases=next.bases.filter(v=>keyOf(v)!==keyOf(e)).concat(e)
   const work=[...changes.push.map(entry=>({side:'local',entry})),...changes.pull.map(entry=>({side:'remote',entry}))];next.remaining=Math.max(0,work.length-20);await this.save(next)
   for(const {side,entry} of work.slice(0,20))try{await this.send(side,entry,b,{local:l,remote:r})}catch(error){
    if(!error.appFileUnavailable||this.state.pending)throw error
    const value=clone(this.state),key=keyOf(entry);value.conflicts=value.conflicts.filter(c=>c.key!==key).concat({key,local:l.items.find(e=>keyOf(e)===key)||null,remote:r.items.find(e=>keyOf(e)===key)||null,reason:'应用文件缺失、损坏或不可读，已暂停该关联；其他应用可继续同步。',canResolve:false});await this.save(value)
   }
   await this.save({...clone(this.state),lastSyncedAt:this.now()})
  }finally{this.busy=false}
 }
 async resolve(key,choice){
  await this.load();const b=this.state.binding,c=this.state.conflicts.find(c=>c.key===key)
  if(!b?.enabled||b.requiresRebind||this.state.pending||!c?.canResolve||!['local','remote'].includes(choice))throw new Error('当前关联不能自动处理，请核对文件与应用。')
  await this.actors(b);const mapping=await this.mapping(b),[l,r]=await Promise.all([this.rows('local',b,mapping),this.rows('remote',b,mapping)]),a=l.items.find(v=>keyOf(v)===key),other=r.items.find(v=>keyOf(v)===key)
  if(!this.changes(l,r).conflicts.some(c=>c.key===key&&c.canResolve)||!same(a,c.local)||!same(other,c.remote))throw new Error('冲突关联已变化，请刷新核对后再选择。')
  await this.send(choice,choice==='local'?a:other,b,{local:l,remote:r})
 }
}
module.exports={MobileApplicationFileSync,MobileApplicationFileSyncStore,empty,validateEntry,validateState,keyOf}
