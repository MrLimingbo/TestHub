'use strict'
const crypto=require('node:crypto')
const {Buffer}=require('node:buffer')
const {normalizeServerUrl}=require('./desktop-core.cjs')
const {EncryptedSyncStore}=require('./encrypted-sync-store.cjs')
const clone=v=>globalThis.structuredClone(v),id=v=>typeof v==='string'&&/^[0-9A-HJKMNP-TV-Z]{26}$/.test(v),digest=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const name=v=>typeof v==='string'&&v===v.trim()&&v.length>0&&v.length<=128&&![...v].some(c=>c.charCodeAt(0)<32)
const uuid=v=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const keyOf=v=>v.name.toLowerCase(),metadata=v=>({name:v.name,sha256:v.sha256,size:v.size,serviceCount:v.serviceCount,sourceCount:v.sourceCount})
const same=(a,b)=>Boolean(a&&b)&&JSON.stringify(metadata(a))===JSON.stringify(metadata(b))
const validItem=v=>v&&id(v.id)&&name(v.name)&&digest(v.sha256)&&Number.isSafeInteger(v.size)&&v.size>0&&v.size<=1024*1024&&Number.isSafeInteger(v.serviceCount)&&v.serviceCount>0&&Number.isSafeInteger(v.sourceCount)&&v.sourceCount>0&&v.sourceCount<=500
const empty=()=>({version:1,binding:null,links:[],pending:null,conflicts:[],lastSyncedAt:null})
function validateState(v){
 if(v?.version!==1||!Array.isArray(v.links)||v.links.length>20||!Array.isArray(v.conflicts)||v.conflicts.length>40)throw new Error('描述符同步记录损坏。')
 const b=v.binding
 if(b&&(normalizeServerUrl(b.server)!==b.server||!id(b.actor?.id)||!id(b.localActorId)||!id(b.source?.id)||!id(b.target?.id)||b.descriptorsConfirmed!==true||typeof b.enabled!=='boolean'||(b.requiresRebind!==undefined&&typeof b.requiresRebind!=='boolean')))throw new Error('描述符绑定无效或未单独确认。')
 if(!b&&(v.links.length||v.conflicts.length||v.pending))throw new Error('未绑定描述符不能保留请求。')
 for(const side of ['localId','remoteId'])if(new Set(v.links.map(l=>l[side])).size!==v.links.length)throw new Error('描述符配对重复。')
 if(new Set(v.links.map(keyOf)).size!==v.links.length||v.links.some(l=>!validItem({...l,id:l.localId})||!id(l.remoteId)))throw new Error('描述符配对标识无效。')
 if(v.conflicts.some(c=>!name(c.key)||c.key!==c.key.toLowerCase()||typeof c.reason!=='string'||c.canResolve!==false)||new Set(v.conflicts.map(c=>c.key)).size!==v.conflicts.length)throw new Error('描述符核对记录无效。')
 if(v.pending){const p=v.pending,r=p.request
  if(!b||!['local','remote'].includes(p.side)||!validItem(p.source)||!uuid(r?.requestKey)||!digest(r.expectedRevision)||r.expectedUserId!==(p.side==='local'?b.localActorId:b.actor.id)||r.sourceProject!==(p.side==='local'?b.target.id:b.source.id)||r.sourceId!==p.source.id||r.name!==p.source.name||r.sha256!==p.source.sha256||typeof r.content!=='string'||r.content.length!==Math.ceil(p.source.size/3)*4)throw new Error('待确认描述符请求无效。')
  const bytes=Buffer.from(r.content,'base64');if(bytes.toString('base64')!==r.content||bytes.length!==p.source.size||crypto.createHash('sha256').update(bytes).digest('hex')!==r.sha256)throw new Error('待确认描述符字节损坏。')
 }
 return v
}
class GrpcDescriptorSyncStore extends EncryptedSyncStore{constructor(directory,codec){super(directory,codec,{filename:'grpc-descriptor-sync.bin',validate:validateState,empty,label:'gRPC 描述符',maxPlain:2*1024*1024,maxEncrypted:3*1024*1024})}}
class GrpcDescriptorSync{
 constructor({store,local,remote,localBinary,remoteBinary,guard,now=()=>new Date().toISOString()}){Object.assign(this,{store,local,remote,localBinary,remoteBinary,guard,now});this.busy=false}
 async load(){if(!this.state)this.state=validateState(await this.store.read());return this.state}
 async save(v){validateState(v);await this.store.write(v);this.state=v}
 async status(){const {pending,...v}=await this.load();return {...clone(v),pending:Boolean(pending)}}
 async actors(b){await this.guard(b);const [l,r]=await Promise.all([this.local('/api/v1/auth/me'),this.remote('/api/v1/auth/me',undefined,b)]);if(l.id!==b.localActorId||r.id!==b.actor.id)throw new Error('绑定账号变化，描述符同步已停止。')}
 route(side,b){return `/api/v1/projects/${side==='local'?b.source.id:b.target.id}/api-debug/grpc/descriptors/sync`}
 async rows(side,b){const actor=side==='local'?b.localActorId:b.actor.id,project=side==='local'?b.source.id:b.target.id,v=await this[side](this.route(side,b)+'?expectedUserId='+actor,undefined,b)
  if(v?.version!==1||v.scope!=='GRPC_DESCRIPTOR_SYNC'||v.projectId!==project||v.actorId!==actor||!digest(v.revision)||!Array.isArray(v.items)||v.items.length>20||v.items.some(i=>!validItem(i))||v.items.reduce((n,i)=>n+i.size,0)>4*1024*1024||new Set(v.items.map(i=>i.id)).size!==v.items.length||new Set(v.items.map(keyOf)).size!==v.items.length)throw new Error('描述符索引、范围或协议无效。');return v
 }
 async bytes(side,item,b){const actor=side==='local'?b.localActorId:b.actor.id,bytes=await this[side+'Binary'](this.route(side,b)+`/${item.id}/content?expectedUserId=${actor}`,item.size,item.sha256,b);if(bytes.length!==item.size||crypto.createHash('sha256').update(bytes).digest('hex')!==item.sha256)throw new Error('描述符字节或摘要已变化。');return bytes}
 changes(l,r){const a=new Map(l.items.map(i=>[keyOf(i),i])),b=new Map(r.items.map(i=>[keyOf(i),i])),push=[],pull=[],pairs=[],conflicts=[]
  const keys=new Set([...a.keys(),...b.keys(),...this.state.links.map(keyOf)]);if(keys.size>20)throw new Error('合并后描述符超过 20 个，暂停同步。')
  let total=0
  for(const key of keys){const x=a.get(key),y=b.get(key),link=this.state.links.find(l=>keyOf(l)===key);total+=Math.max(x?.size||0,y?.size||0);let reason
   if(link&&(!x||!y||x.id!==link.localId||y.id!==link.remoteId||!same(x,link)||!same(y,link)))reason='已同步描述符被删除、重建或内容改变，暂停核对；不会重建或覆盖。'
   else if(x&&y&&!same(x,y))reason='同名描述符内容或名称大小写不同，请使用新名称导入；已有引用与字节保留。'
   if(reason)conflicts.push({key,local:x||null,remote:y||null,reason,canResolve:false})
   else if(x&&y)pairs.push({local:x,remote:y})
   else if(x)push.push(x);else if(y)pull.push(y)
  }
  if(total>4*1024*1024)throw new Error('合并后描述符超过 4 MiB，暂停同步。')
  return {push,pull,pairs,conflicts,upload:push.length,download:pull.length,conflictCount:conflicts.length}
 }
 async preview(sourceId,targetId,server,confirmed){await this.load();this.plan=undefined;if(confirmed!==true)throw new Error('请单独确认传输编译后的 gRPC 描述文件。');if(this.state.binding)throw new Error('已有描述符绑定，请先解除。');if(!id(sourceId)||!id(targetId))throw new Error('请选择有效项目。');server=normalizeServerUrl(server)
  const [actor,localActor,l,r]=await Promise.all([this.remote('/api/v1/auth/me',undefined,{server}),this.local('/api/v1/auth/me'),this.local('/api/v1/access/context'),this.remote('/api/v1/access/context',undefined,{server})]),projects=v=>v.organizations.flatMap(o=>(v.projects[o.id]||[]).map(p=>({id:p.id,name:p.name,organization:o.name}))),source=projects(l).find(p=>p.id===sourceId),target=projects(r).find(p=>p.id===targetId)
  if(!source||!target)throw new Error('项目权限变化。');const binding={server,actor:{id:actor.id,name:actor.displayName,account:actor.account||actor.email},localActorId:localActor.id,source,target,enabled:true,descriptorsConfirmed:true};await this.actors(binding);const [localRows,remoteRows]=await Promise.all([this.rows('local',binding),this.rows('remote',binding)]),counts=this.changes(localRows,remoteRows)
  this.plan={token:crypto.randomUUID(),binding,localRows,remoteRows,counts,expires:Date.now()+600000};return {token:this.plan.token,binding:clone(binding),upload:counts.upload,download:counts.download,conflictCount:counts.conflictCount}
 }
 async bind(token){const p=this.plan;if(!p||p.token!==token||p.expires<=Date.now()||this.state.binding)throw new Error('描述符绑定预览已失效。');await this.actors(p.binding);const [l,r]=await Promise.all([this.rows('local',p.binding),this.rows('remote',p.binding)]);if(l.revision!==p.localRows.revision||r.revision!==p.remoteRows.revision)throw new Error('描述符在确认期间变化，请重新预览。');await this.save({...empty(),binding:p.binding});this.plan=undefined}
 async enabled(value){await this.load();if(!this.state.binding||typeof value!=='boolean')throw new Error('尚未绑定描述符。');if(value&&this.state.binding.requiresRebind)throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。');if(value)await this.actors(this.state.binding);const next=clone(this.state);next.binding.enabled=value;await this.save(next)}
 async unbind(){await this.save(empty());this.plan=undefined}
 async suspendForRestore(){await this.load();if(this.state.binding){const next=clone(this.state);next.binding.enabled=false;next.binding.requiresRebind=true;await this.save(next)}}
 async linkContext(binding){await this.load();const b=this.state.binding;if(!b?.enabled||b.requiresRebind||b.server!==binding.server||b.actor.id!==binding.actor.id||b.localActorId!==binding.localActorId||b.source.id!==binding.source.id||b.target.id!==binding.target.id)return null
  await this.actors(b);const [l,r]=await Promise.all([this.rows('local',b),this.rows('remote',b)]),links=[]
  for(const link of this.state.links){const a=l.items.find(i=>i.id===link.localId),other=r.items.find(i=>i.id===link.remoteId);if(a&&other&&same(a,link)&&same(other,link)){await this.bytes('local',a,b);await this.bytes('remote',other,b);links.push({key:link.localId,localId:a.id,remoteId:other.id})}}
  return {sourceProject:b.source.id,targetProject:b.target.id,links}
 }
 async recoverPending(b){const p=this.state.pending;if(!p)return;await this.actors(b);const sourceSide=p.side==='local'?'remote':'local',rows=await this.rows(sourceSide,b),source=rows.items.find(i=>i.id===p.source.id);if(!same(source,p.source))throw new Error('待确认描述符来源已删除或变化，暂停核对。');await this.bytes(sourceSide,source,b)
  const csrf=await this[p.side]('/api/v1/auth/csrf',undefined,b);await this.guard(b)
  // Keep the original request on a 409: its committed target may have been deleted.
  const receipt=await this[p.side](this.route(p.side,b),{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(p.request)},b)
  if(receipt?.requestKey!==p.request.requestKey||receipt.sourceProject!==p.request.sourceProject||receipt.sourceId!==p.source.id||!validItem(receipt.item)||!same(receipt.item,p.source))throw new Error('描述符回执不完整，保留原请求重试。');await this.bytes(p.side,receipt.item,b)
  const link={...metadata(p.source),localId:p.side==='local'?receipt.item.id:p.source.id,remoteId:p.side==='remote'?receipt.item.id:p.source.id},next=clone(this.state);next.links=next.links.filter(l=>keyOf(l)!==keyOf(link)).concat(link);next.pending=null;await this.save(next)
 }
 async send(side,item,target,b){const bytes=await this.bytes(side,item,b);await this.actors(b);const [freshSource,freshTarget]=await Promise.all([this.rows(side,b),this.rows(side==='local'?'remote':'local',b)]);if(!same(freshSource.items.find(i=>i.id===item.id),item)||freshTarget.revision!==target.revision)throw new Error('描述符在读取期间变化，请重试。');const destination=side==='local'?'remote':'local',next=clone(this.state);next.pending={side:destination,source:clone(item),request:{requestKey:crypto.randomUUID(),expectedUserId:destination==='local'?b.localActorId:b.actor.id,expectedRevision:target.revision,sourceProject:side==='local'?b.source.id:b.target.id,sourceId:item.id,name:item.name,sha256:item.sha256,content:bytes.toString('base64')}};await this.save(next);await this.recoverPending(b)}
 async run(){if(this.busy)return;this.busy=true;try{await this.load();const b=this.state.binding;if(!b?.enabled||b.requiresRebind)return;await this.actors(b);await this.recoverPending(b)
  for(let n=0;n<=20;n++){const [l,r]=await Promise.all([this.rows('local',b),this.rows('remote',b)]),changes=this.changes(l,r),next=clone(this.state);next.conflicts=changes.conflicts
   for(const pair of changes.pairs){await this.bytes('local',pair.local,b);await this.bytes('remote',pair.remote,b);next.links=next.links.filter(i=>keyOf(i)!==keyOf(pair.local)).concat({...metadata(pair.local),localId:pair.local.id,remoteId:pair.remote.id})}await this.save(next)
   if(changes.push.length)await this.send('local',changes.push[0],r,b);else if(changes.pull.length)await this.send('remote',changes.pull[0],l,b);else{await this.save({...clone(this.state),lastSyncedAt:this.now()});return}
  }
  throw new Error('本轮描述符操作超过范围，请核对。')
 }finally{this.busy=false}}
 async resolve(){throw new Error('描述符不会覆盖或删除；请人工核对后使用新名称导入。')}
}
module.exports={GrpcDescriptorSync,GrpcDescriptorSyncStore,validateState,validItem,empty,keyOf}
