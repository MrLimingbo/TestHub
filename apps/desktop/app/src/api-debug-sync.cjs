'use strict'
const crypto=require('node:crypto')
const {Buffer}=require('node:buffer')
const {normalizeServerUrl}=require('./desktop-core.cjs')
const {EncryptedSyncStore}=require('./encrypted-sync-store.cjs')
const clone=v=>globalThis.structuredClone(v),id=v=>typeof v==='string'&&/^[0-9A-HJKMNP-TV-Z]{26}$/.test(v),key=v=>typeof v==='string'&&/^[0-9A-HJKMNP-TV-Z]{26}\.[0-9A-HJKMNP-TV-Z]{26}$/.test(v),digest=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v),uuid=v=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const modes=['history','replay-drafts'],protocols=['HTTP','GRAPHQL','SOAP','WEBSOCKET','GRPC'],results=['DRAFT','COMPLETED','FAILED'],notices=['MANUAL_REVIEW','AUTH','BODY','URL','ENVIRONMENT','GRPC_DESCRIPTOR','PROJECT_FILE']
const exact=(v,fields)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===fields.length&&fields.every(f=>Object.hasOwn(v,f))
function stable(v){if(Array.isArray(v))return v.map(stable);if(v&&typeof v==='object')return Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])]));return v}
const hash=v=>crypto.createHash('sha256').update(JSON.stringify(stable(v))).digest('hex'),time=v=>typeof v==='string'&&Number.isFinite(Date.parse(v))&&Date.parse(v)>=1000&&Date.parse(v)<=2147483647000
const safeTarget=v=>typeof v==='string'&&v.length<=2000&&!/[?@#]/.test(v)&&(/^[a-zA-Z]+:\/\/[^/]+\/…$/.test(v)||protocols.some(p=>v===p+'://[目标已隐藏]'))
const entryFields=['key','revision','nativeRecord','method','protocol','result','target','createdAt']
function validateEntry(v){if(!exact(v,entryFields)||!key(v.key)||!digest(v.revision)||typeof v.nativeRecord!=='boolean'||typeof v.method!=='string'||!/^[_A-Z]{1,12}$/.test(v.method)||!protocols.includes(v.protocol)||!results.includes(v.result)||!safeTarget(v.target)||!time(v.createdAt))throw new Error('调试同步索引字段无效。');return v}
const variable=v=>typeof v==='string'&&/^(?:(?:Bearer|Basic) )?\{\{[A-Za-z_$][A-Za-z0-9_.$-]*}}$/i.test(v),sensitive=k=>/authorization|cookie|api[-_]?key|token|secret|password|passwd|session|jwt|credential/i.test(k)
function verifyTemplate(v){
 if(!exact(v,['protocol','request'])||!protocols.includes(v.protocol)||!v.request||typeof v.request!=='object'||Array.isArray(v.request))throw new Error('重放模板协议无效。')
 let count=0
 function visit(value,field='',depth=0){if(++count>20000||depth>32)throw new Error('重放模板结构超过范围。');if(sensitive(field)&&field!=='key'&&value!==null&&value!==''&&!variable(value))throw new Error('重放模板仍含敏感字面量。');if(typeof value==='string'){if(/(?:\/|%2f)api(?:\/|%2f)v1(?:\/|%2f)projects(?:\/|%2f)/i.test(value))throw new Error('重放模板仍含本端文件引用。');return}if(Array.isArray(value)){for(const part of value)visit(part,'',depth+1)}else if(value&&typeof value==='object')for(const [k,part] of Object.entries(value))visit(part,k,depth+1)}
 visit(v.request)
 const r=v.request
 if(r.environmentCode!==null&&r.environmentCode!==undefined||r.descriptorId!==null&&r.descriptorId!==undefined)throw new Error('模板环境或描述符需要在目标重新核对。')
 if(r.auth)for(const k of ['token','username','password','value'])if(r.auth[k]!=null&&r.auth[k]!==''&&!variable(r.auth[k]))throw new Error('模板认证字段未脱敏。')
 for(const field of ['headers','metadata'])if(r[field]){if(!Array.isArray(r[field])||r[field].length>48)throw new Error('模板请求头无效。');for(const h of r[field])if(sensitive(h.name||h.key||'')&&h.value&&!variable(h.value))throw new Error('模板请求头未脱敏。')}
 for(const value of [r.body,r.requestJson,r.protocol?.variables,...(r.subscriptions||[])])if(value&&!variable(value)){try{visit(JSON.parse(value))}catch(error){throw new Error('模板正文需要使用 JSON 或核对占位符。',{cause:error})}}
 for(const field of ['query','envelope'])if(r.protocol?.[field]&&!variable(r.protocol[field]))throw new Error('模板查询或 XML 需要核对占位符。')
 for(const u of [r.url,r.endpoint])if(typeof u==='string'){if(/^[a-z][a-z0-9+.-]*:\/\/[^/?#]*@/i.test(u)||u.includes('#'))throw new Error('模板 URL 未脱敏。');const q=u.indexOf('?');if(q>=0)for(const part of u.slice(q+1).split('&')){const [rawName,...rawValue]=part.split('=');let n,value;try{n=decodeURIComponent(rawName);value=decodeURIComponent(rawValue.join('='))}catch{throw new Error('模板 URL 编码无效。')}if(sensitive(n)&&value&&!variable(value))throw new Error('模板查询认证未脱敏。')}}
 if(r.reconnect?.enabled===true)throw new Error('模板不能自动重连。')
}
function validateItem(v,mode){
 if(!exact(v,['key','revision','sha256','payload'])||!key(v.key)||!digest(v.revision)||v.revision!==v.sha256||hash(v.payload)!==v.sha256||Buffer.byteLength(JSON.stringify(v))>1024*1024)throw new Error('调试同步数据或 SHA-256 无效。')
 const p=v.payload
 if(mode==='history'){
  if(!exact(p,['method','protocol','result','target','responseStatus','durationMs','responseSize','createdAt']))throw new Error('历史摘要字段无效。')
  validateEntry({key:v.key,revision:v.revision,nativeRecord:false,method:p.method,protocol:p.protocol,result:p.result,target:p.target,createdAt:p.createdAt})
  if(!Number.isSafeInteger(p.durationMs)||p.durationMs<0||!Number.isSafeInteger(p.responseSize)||p.responseSize<0||(p.responseStatus!==null&&(!Number.isInteger(p.responseStatus)||p.responseStatus<0||p.responseStatus>599)))throw new Error('历史计量字段无效。')
 }else{
  if(!exact(p,['historySha256','protocol','template','notices'])||!digest(p.historySha256)||p.protocol!==p.template?.protocol||!Array.isArray(p.notices)||p.notices.length>8||!p.notices.includes('MANUAL_REVIEW')||new Set(p.notices).size!==p.notices.length||p.notices.some(n=>!notices.includes(n)))throw new Error('重放模板核对信息无效。')
  verifyTemplate(p.template)
 }
 return v
}
const empty=mode=>({version:1,mode,binding:null,bases:[],pending:null,conflicts:[],lastSyncedAt:null,remaining:0}),same=(a,b)=>Boolean(a&&b)&&entryFields.filter(f=>f!=='nativeRecord').every(f=>a[f]===b[f])
function validateState(v){if(v?.version!==1||!modes.includes(v.mode)||!Array.isArray(v.bases)||v.bases.length>1000||!Array.isArray(v.conflicts)||v.conflicts.length>1000||!Number.isSafeInteger(v.remaining)||v.remaining<0||v.remaining>1000)throw new Error('调试同步状态损坏。');const b=v.binding
 if(b&&(normalizeServerUrl(b.server)!==b.server||!id(b.actor?.id)||!id(b.localActorId)||!id(b.source?.id)||!id(b.target?.id)||b.confirmed!==true||typeof b.enabled!=='boolean'||(b.requiresRebind!==undefined&&typeof b.requiresRebind!=='boolean')))throw new Error('调试同步绑定未确认或无效。')
 if(!b&&(v.bases.length||v.pending||v.conflicts.length||v.remaining))throw new Error('未绑定不能保留调试同步请求。')
 for(const e of v.bases)validateEntry(e);if(new Set(v.bases.map(e=>e.key)).size!==v.bases.length)throw new Error('调试同步基线重复。')
 for(const c of v.conflicts){if(!key(c.key)||typeof c.reason!=='string'||c.canResolve!==false)throw new Error('调试同步核对信息无效。');for(const e of [c.local,c.remote])if(e!==null){validateEntry(e);if(e.key!==c.key)throw new Error('调试同步冲突来源不同。')}}
 if(new Set(v.conflicts.map(c=>c.key)).size!==v.conflicts.length)throw new Error('调试同步冲突重复。')
 const p=v.pending;if(p){if(!b||!['local','remote'].includes(p.side)||!uuid(p.request?.requestKey)||!digest(p.request.expectedRevision)||p.request.expectedUserId!==(p.side==='local'?b.localActorId:b.actor.id)||!Array.isArray(p.request.items)||p.request.items.length!==1)throw new Error('待确认调试同步请求无效。');validateEntry(p.entry);validateItem(p.request.items[0],v.mode);if(p.entry.key!==p.request.items[0].key||p.entry.revision!==p.request.items[0].revision)throw new Error('调试待确认来源不符。')}
 return v
}
class ApiDebugSyncStore extends EncryptedSyncStore{constructor(directory,codec,mode){super(directory,codec,{filename:mode==='history'?'api-debug-history-sync.bin':'api-debug-replay-sync.bin',validate:validateState,empty:()=>empty(mode),label:mode==='history'?'接口调试历史':'脱敏重放草稿',maxPlain:4*1024*1024,maxEncrypted:6*1024*1024})}}
class ApiDebugSync{
 constructor({store,mode,local,remote,guard,now=()=>new Date().toISOString()}){if(!modes.includes(mode))throw new Error('调试同步范围无效。');Object.assign(this,{store,mode,local,remote,guard,now});this.busy=false}
 async load(){if(!this.state)this.state=validateState(await this.store.read());if(this.state.mode!==this.mode)throw new Error('调试同步状态范围不同。');return this.state}
 async save(v){validateState(v);await this.store.write(v);this.state=v}
 async status(){const {pending,...v}=await this.load();return {...clone(v),pending:Boolean(pending)}}
 async actors(b){await this.guard(b);const [l,r]=await Promise.all([this.local('/api/v1/auth/me'),this.remote('/api/v1/auth/me',undefined,b)]);if(l.id!==b.localActorId||r.id!==b.actor.id)throw new Error('绑定账号已变化，调试同步停止。')}
 route(side,b,mode=this.mode){return `/api/v1/projects/${side==='local'?b.source.id:b.target.id}/api-debug/${mode}/archive`}
 async rows(side,b){const actor=side==='local'?b.localActorId:b.actor.id,project=side==='local'?b.source.id:b.target.id,v=await this[side](this.route(side,b)+'/sync?expectedUserId='+actor,undefined,b)
  if(v?.version!==1||v.scope!==(this.mode==='history'?'API_DEBUG_HISTORY_SYNC':'API_DEBUG_REPLAY_SYNC')||v.projectId!==project||v.actorId!==actor||!digest(v.revision)||!Array.isArray(v.entries)||v.entries.length>1000)throw new Error('调试同步索引、账号或协议无效。');for(const e of v.entries)validateEntry(e);if(new Set(v.entries.map(e=>e.key)).size!==v.entries.length)throw new Error('同源调试记录重复。');return v
 }
 changes(l,r){const a=new Map(l.entries.map(e=>[e.key,e])),b=new Map(r.entries.map(e=>[e.key,e])),bases=new Map(this.state.bases.map(e=>[e.key,e])),push=[],pull=[],unchanged=[],conflicts=[];const keys=new Set([...a.keys(),...b.keys(),...bases.keys()]);if(keys.size>1000)throw new Error('合并调试记录超过 1000 条，不会截断。')
  for(const key of keys){const x=a.get(key),y=b.get(key),base=bases.get(key);if(base&&(!x||!y)||x&&y&&!same(x,y)){conflicts.push({key,local:x||null,remote:y||null,reason:'同源记录不同、已删除或原副本缺失，请核对；不会删除、重建或改写。',canResolve:false});continue}if(same(x,y))unchanged.push(x);else if(x)push.push(x);else if(y)pull.push(y)}return {push,pull,unchanged,conflicts,upload:push.length,download:pull.length,conflictCount:conflicts.length}
 }
 async preview(sourceId,targetId,server,confirmed){await this.load();this.plan=undefined;if(confirmed!==true)throw new Error('请单独确认当前调试同步范围。');if(this.state.binding||!id(sourceId)||!id(targetId))throw new Error('请解除旧绑定并选择有效项目。');server=normalizeServerUrl(server);const [actor,localActor,l,r]=await Promise.all([this.remote('/api/v1/auth/me',undefined,{server}),this.local('/api/v1/auth/me'),this.local('/api/v1/access/context'),this.remote('/api/v1/access/context',undefined,{server})]),projects=v=>v.organizations.flatMap(o=>(v.projects[o.id]||[]).map(p=>({id:p.id,name:p.name,organization:o.name}))),source=projects(l).find(p=>p.id===sourceId),target=projects(r).find(p=>p.id===targetId);if(!source||!target)throw new Error('项目权限变化。');const binding={server,actor:{id:actor.id,account:actor.account||actor.email},localActorId:localActor.id,source,target,enabled:true,confirmed:true};await this.actors(binding);const [localRows,remoteRows]=await Promise.all([this.rows('local',binding),this.rows('remote',binding)]),counts=this.changes(localRows,remoteRows);this.plan={token:crypto.randomUUID(),expires:Date.now()+600000,binding,localRows,remoteRows,counts};return {token:this.plan.token,binding:clone(binding),upload:counts.upload,download:counts.download,conflictCount:counts.conflictCount}}
 async bind(token){const p=this.plan;if(!p||p.token!==token||p.expires<=Date.now()||this.state.binding)throw new Error('调试绑定预览已失效。');await this.actors(p.binding);const [l,r]=await Promise.all([this.rows('local',p.binding),this.rows('remote',p.binding)]);if(l.revision!==p.localRows.revision||r.revision!==p.remoteRows.revision)throw new Error('调试记录在确认期间已变化。');await this.save({...empty(this.mode),binding:p.binding});this.plan=undefined}
 async enabled(value){await this.load();if(!this.state.binding||typeof value!=='boolean')throw new Error('尚未绑定调试同步。');if(value&&this.state.binding.requiresRebind)throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。');if(value)await this.actors(this.state.binding);const next=clone(this.state);next.binding.enabled=value;await this.save(next)}
 async unbind(){await this.save(empty(this.mode));this.plan=undefined}
 async suspendForRestore(){await this.load();if(this.state.binding){const next=clone(this.state);next.binding.enabled=false;next.binding.requiresRebind=true;await this.save(next)}}
 async parents(item,b){if(this.mode!=='replay-drafts')return;for(const side of ['local','remote']){const parent=validateItem(await this[side](this.route(side,b,'history')+'/'+item.key,undefined,b),'history');if(parent.sha256!==item.payload.historySha256)throw new Error('对应历史摘要不同，请先完成历史同步。')}}
 async recoverPending(b){const p=this.state.pending;if(!p)return;await this.actors(b);const source=p.side==='local'?'remote':'local',original=validateItem(await this[source](this.route(source,b)+'/'+p.entry.key,undefined,b),this.mode);if(original.key!==p.entry.key||original.sha256!==p.request.items[0].sha256)throw new Error('来源记录已变化，保留待确认请求，请核对。');await this.parents(p.request.items[0],b);const csrf=await this[p.side]('/api/v1/auth/csrf',undefined,b);await this.guard(b)
  // Keep an uncertain immutable write even on a 409; a committed counterpart may have been deleted.
  const result=await this[p.side](this.route(p.side,b)+'/sync',{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(p.request)},b),item=p.request.items[0]
  if(result?.requestKey!==p.request.requestKey||!Array.isArray(result.results)||result.results.length!==1||result.results[0].key!==item.key||result.results[0].revision!==item.revision||result.results[0].sha256!==item.sha256)throw new Error('调试同步回执不完整，保留原请求。')
  const next=clone(this.state);next.bases=next.bases.filter(e=>e.key!==p.entry.key).concat(p.entry);next.conflicts=next.conflicts.filter(c=>c.key!==p.entry.key);next.pending=null;await this.save(next)
 }
 async send(sourceSide,entry,b){const destination=sourceSide==='local'?'remote':'local',item=validateItem(await this[sourceSide](this.route(sourceSide,b)+'/'+entry.key,undefined,b),this.mode);if(item.key!==entry.key||item.revision!==entry.revision)throw new Error('来源调试记录已变化。');await this.parents(item,b);await this.actors(b);const target=await this.rows(destination,b),current=target.entries.find(e=>e.key===entry.key),initial=this.runTargets[destination].entries.find(e=>e.key===entry.key);if(Boolean(current)!==Boolean(initial)||current&&!same(current,initial)||current?.nativeRecord)throw new Error('目标调试记录已变化。');const next=clone(this.state);next.pending={side:destination,entry:clone(entry),request:{requestKey:crypto.randomUUID(),expectedUserId:destination==='local'?b.localActorId:b.actor.id,expectedRevision:target.revision,items:[item]}};await this.save(next);await this.recoverPending(b)}
 async run(){if(this.busy)return;this.busy=true;try{await this.load();const b=this.state.binding;if(!b?.enabled||b.requiresRebind)return;await this.actors(b);await this.recoverPending(b);const [l,r]=await Promise.all([this.rows('local',b),this.rows('remote',b)]),changes=this.changes(l,r);this.runTargets={local:l,remote:r};const next=clone(this.state),prior=new Map(next.conflicts.map(c=>[c.key,c]));next.conflicts=changes.conflicts;for(const e of changes.unchanged)next.bases=next.bases.filter(v=>v.key!==e.key).concat(e);const work=[...changes.push.map(entry=>({side:'local',entry})),...changes.pull.map(entry=>({side:'remote',entry}))].sort((a,b)=>Number(prior.has(a.entry.key))-Number(prior.has(b.entry.key)));for(const {entry} of work)if(prior.has(entry.key)&&!next.conflicts.some(c=>c.key===entry.key))next.conflicts.push({...prior.get(entry.key),local:l.entries.find(e=>e.key===entry.key)||null,remote:r.entries.find(e=>e.key===entry.key)||null});next.remaining=Math.max(0,work.length-20);await this.save(next)
  for(const {side,entry} of work.slice(0,20))try{await this.send(side,entry,b)}catch(error){if(this.state.pending||this.mode!=='replay-drafts'||![404,409].includes(error.status))throw error;const v=clone(this.state);v.conflicts=v.conflicts.filter(c=>c.key!==entry.key).concat({key:entry.key,local:l.entries.find(e=>e.key===entry.key)||null,remote:r.entries.find(e=>e.key===entry.key)||null,reason:'对应历史缺失或草稿不可读，请先完成历史同步；其他记录可继续。',canResolve:false});await this.save(v)}
  await this.save({...clone(this.state),lastSyncedAt:this.now()})
 }finally{this.runTargets=undefined;this.busy=false}}
 async resolve(){throw new Error('同步调试历史和模板只读；请在原端核对，或载入模板后另存新草稿。')}
}
function createApiDebugSync(mode){class Client extends ApiDebugSync{constructor(o){super({...o,mode})}}class Store extends ApiDebugSyncStore{constructor(directory,codec){super(directory,codec,mode)}}return {Client,Store}}
module.exports={ApiDebugSync,ApiDebugSyncStore,createApiDebugSync,validateState,validateEntry,validateItem,verifyTemplate,hash,empty}
