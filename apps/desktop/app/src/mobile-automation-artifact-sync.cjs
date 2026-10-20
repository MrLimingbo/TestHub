'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const clone = v => globalThis.structuredClone(v)
const id = v => typeof v === 'string' && /^[A-Z0-9]{26}$/.test(v)
const digest = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const key = v => typeof v === 'string' && /^[A-Z0-9]{26}\.[A-Z0-9]{26}\.[A-Z0-9]{26}$/.test(v)
const historyKey = v => v.slice(0,53)
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const hash = v => crypto.createHash('sha256').update(v).digest('hex')
const fields = ['key','sourceArtifactId','artifactType','originalName','contentType','sizeBytes','checksum','archivedAt','nativeRecord']
function validateEntry(v) {
  if (!v || Object.keys(v).length !== fields.length || !fields.every(k => Object.hasOwn(v,k)) || !key(v.key) || !id(v.sourceArtifactId)
    || v.key.slice(54) !== v.sourceArtifactId || v.artifactType!=='SCREENSHOT'
    || v.originalName !== 'screenshot.png'
    || v.contentType !== 'image/png'
    || !Number.isSafeInteger(v.sizeBytes)||v.sizeBytes<0||v.sizeBytes>25*1024*1024
    || !digest(v.checksum) || typeof v.archivedAt !== 'string' || !Number.isFinite(Date.parse(v.archivedAt)) || Date.parse(v.archivedAt) < 1000 || Date.parse(v.archivedAt) > 2147483647499
    || typeof v.nativeRecord !== 'boolean') throw new Error('原始工件索引字段不完整或无效。')
  return v
}
const same = (a,b) => Boolean(a&&b) && fields.filter(k => k !== 'nativeRecord').every(k => a[k] === b[k])
const empty = () => ({version:1,binding:null,bases:[],pending:null,conflicts:[],lastSyncedAt:null,remaining:0})
function validateState(v) {
  if (v?.version !== 1 || !Array.isArray(v.bases) || v.bases.length > 2000 || !Array.isArray(v.conflicts) || v.conflicts.length > 2000
    || !Number.isSafeInteger(v.remaining) || v.remaining < 0 || v.remaining > 2000) throw new Error('原始工件同步记录损坏，请保留数据。')
  const b=v.binding
  if (b && (normalizeServerUrl(b.server)!==b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || b.rawFilesConfirmed!==true || typeof b.enabled!=='boolean' || (b.requiresRebind!==undefined&&typeof b.requiresRebind!=='boolean'))) throw new Error('工件绑定未单独确认或账号无效。')
  if (!b && (v.bases.length || v.conflicts.length || v.pending || v.remaining)) throw new Error('未绑定的工件不能含有同步记录。')
  const keys=new Set();for(const entry of v.bases){validateEntry(entry);if(keys.has(entry.key))throw new Error('工件基线重复。');keys.add(entry.key)}
  const conflicts=new Set();for(const c of v.conflicts){if(!key(c.key)||conflicts.has(c.key)||typeof c.reason!=='string'||c.canResolve!==false)throw new Error('工件核对记录无效。');conflicts.add(c.key);for(const e of [c.local,c.remote])if(e!==null){validateEntry(e);if(e.key!==c.key)throw new Error('工件核对来源不匹配。')}}
  const p=v.pending
  if(p){const r=p.request;validateEntry(p.entry);validateEntry(r?.artifact)
    if(!b||!['local','remote'].includes(p.side)||!uuid(r.requestKey)||!digest(r.expectedRevision)||r.rawFilesConfirmed!==true||r.artifact.nativeRecord!==false
      ||r.expectedUserId!==(p.side==='local'?b.localActorId:b.actor.id)||!same(p.entry,r.artifact)||typeof r.content!=='string'
      ||r.content.length>34*1024*1024||Buffer.byteLength(JSON.stringify(r))>35*1024*1024)throw new Error('工件待确认请求无效。')
    const bytes=Buffer.from(r.content,'base64');if(bytes.toString('base64')!==r.content||bytes.length!==r.artifact.sizeBytes||hash(bytes)!==r.artifact.checksum)throw new Error('工件待确认字节或摘要不一致。')
  }
  return v
}
class MobileAutomationArtifactSyncStore extends EncryptedSyncStore {
  constructor(directory,codec){super(directory,codec,{filename:'mobile-automation-artifact-sync.bin',validate:validateState,empty,label:'移动端报告产物',maxPlain:50*1024*1024,maxEncrypted:64*1024*1024})}
}
class MobileAutomationArtifactSync {
  constructor({store,local,remote,localBinary,remoteBinary,guard,now=()=>new Date().toISOString()}){Object.assign(this,{store,local,remote,localBinary,remoteBinary,guard,now});this.busy=false}
  async load(){if(!this.state)this.state=validateState(await this.store.read());return this.state}
  async save(v){validateState(v);await this.store.write(v);this.state=v}
  async status(){const {pending,...safe}=await this.load();return {...clone(safe),pending:pending?{key:pending.entry.key,side:pending.side}:null}}
  async actors(b){await this.guard(b);const [l,r]=await Promise.all([this.local('/api/v1/auth/me'),this.remote('/api/v1/auth/me',undefined,b)]);if(l.id!==b.localActorId||r.id!==b.actor.id)throw new Error('绑定账号已变化，工件同步已停止。')}
  async rows(side,b){
    const project=side==='local'?b.source.id:b.target.id,actor=side==='local'?b.localActorId:b.actor.id
    const v=await this[side](`/api/v1/projects/${project}/mobile-automation/artifacts/sync?expectedUserId=${actor}`,undefined,b)
    if(v?.version!==1||v.scope!=='MOBILE_ARTIFACT_SYNC'||v.projectId!==project||v.actorId!==actor||!digest(v.revision)||!Array.isArray(v.entries)||v.entries.length>2000
      ||!Array.isArray(v.historyKeys)||v.historyKeys.length>1000||new Set(v.historyKeys).size!==v.historyKeys.length||v.historyKeys.some(k=>typeof k!=='string'||!/^[A-Z0-9]{26}\.[A-Z0-9]{26}$/.test(k)))throw new Error('工件服务、执行历史范围或账号无效。')
    const keys=new Set();for(const e of v.entries){validateEntry(e);if(keys.has(e.key)||e.nativeRecord!==e.key.startsWith(project+'.'))throw new Error('工件来源重复或归属不合法。');keys.add(e.key)}return v
  }
  changes(l,r){
    const local=new Map(l.entries.map(e=>[e.key,e])),remote=new Map(r.entries.map(e=>[e.key,e])),keys=new Set([...local.keys(),...remote.keys(),...this.state.bases.map(e=>e.key)])
    if(keys.size>2000)throw new Error('合并工件超过 2000 个，现有文件保留。')
    const push=[],pull=[],conflicts=[],unchanged=[]
    for(const key of keys){const a=local.get(key),b=remote.get(key),base=this.state.bases.find(e=>e.key===key),issue=reason=>conflicts.push({key,local:a||null,remote:b||null,reason,canResolve:false})
      if(!l.historyKeys.includes(historyKey(key))||!r.historyKeys.includes(historyKey(key))){issue('一端尚无对应的执行历史，请先同步 移动端历史；不会创建替代执行。');continue}
      if(base&&(!a||!b)){issue('已同步工件在一端缺失，暂停核对，不会自动删除或重建。');continue}
      if(base&&(!same(a,base)||!same(b,base))){issue('已同步的原始工件标识或摘要已变化，暂停核对，不会覆盖。');continue}
      if(same(a,b)){unchanged.push(a);continue}
      if(a&&b){issue('同来源工件内容或标识不同，暂停核对，不会覆盖。');continue}
      if(a)push.push(a);else if(b)pull.push(b)
    }
    return {push,pull,conflicts,unchanged,upload:push.length,download:pull.length,conflictCount:conflicts.length}
  }
  async preview(sourceId, targetId, server, rawFilesConfirmed) {
    if (rawFilesConfirmed !== true) throw new Error('请先单独勾选确认移动端报告产物会发送至绑定账号服务。')
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有工件同步绑定，请先解除后再绑定其他项目。')
    if (!id(sourceId) || !id(targetId)) throw new Error('请选择有效项目。')
    server = normalizeServerUrl(server)
    const [actor, localActor, l, r] = await Promise.all([this.remote('/api/v1/auth/me', undefined, { server }), this.local('/api/v1/auth/me'), this.local('/api/v1/access/context'), this.remote('/api/v1/access/context', undefined, { server })])
    const projects = v => v.organizations.flatMap(org => (v.projects[org.id] || []).map(p => ({ id: p.id, name: p.name, organization: org.name })))
    const source = projects(l).find(p => p.id === sourceId), target = projects(r).find(p => p.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取。')
    const binding = { server, actor: { id: actor.id, account: actor.account || actor.email }, localActorId: localActor.id, source, target, enabled: true, rawFilesConfirmed: true }
    await this.actors(binding)
    const [localRows, remoteRows] = await Promise.all([this.rows('local', binding), this.rows('remote', binding)])
    const counts = this.changes(localRows, remoteRows)
    this.plan = { token: crypto.randomUUID(), expires: Date.now() + 600000, binding, localRows, remoteRows, counts }
    return { token: this.plan.token, binding: clone(binding), upload: counts.upload, download: counts.download, conflictCount: counts.conflictCount }
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('工件绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('工件在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定工件同步。')
    if (value && this.state.binding.requiresRebind) throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。')
    if (value) await this.actors(this.state.binding)
    const next = clone(this.state); next.binding.enabled = value; await this.save(next)
  }
  async suspendForRestore() {
    await this.load()
    if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) }
  }
  async unbind() { await this.save(empty()); this.plan = undefined }
  async recoverPending(b){
    const p=this.state.pending;if(!p)return
    await this.actors(b);const csrf=await this[p.side]('/api/v1/auth/csrf',undefined,b);await this.guard(b)
    const project=p.side==='local'?b.source.id:b.target.id;let result
    try{result=await this[p.side](`/api/v1/projects/${project}/mobile-automation/artifacts/sync`,{method:'POST',headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token},body:JSON.stringify(p.request)},b)}
    catch(error){if(error.status===409){const next=clone(this.state);next.pending=null;await this.save(next)}throw error}
    if(result?.requestKey!==p.request.requestKey||result.key!==p.entry.key||result.sourceArtifactId!==p.entry.sourceArtifactId||result.checksum!==p.entry.checksum)throw new Error('工件回执不完整，保留原请求安全重试。')
    const next=clone(this.state);next.bases=next.bases.filter(e=>e.key!==p.entry.key).concat(p.entry);next.conflicts=next.conflicts.filter(e=>e.key!==p.entry.key);next.pending=null;await this.save(next)
  }
  async send(sourceSide,entry,b){
    const sourceProject=sourceSide==='local'?b.source.id:b.target.id,targetSide=sourceSide==='local'?'remote':'local'
    let bytes
    try{bytes=await this[sourceSide+'Binary'](`/api/v1/projects/${sourceProject}/mobile-automation/artifacts/${entry.key}/download`,entry.sizeBytes,entry.checksum,b)}
    catch(error){if([404,502].includes(error.status)||error.code==='SYNC_BINARY_INTEGRITY'){error.artifactUnavailable=true}throw error}
    if(bytes.length!==entry.sizeBytes||hash(bytes)!==entry.checksum){const error=new Error('来源工件大小或 SHA-256 已变化，暂停该工件。');error.artifactUnavailable=true;throw error}
    await this.actors(b)
    const source=await this.rows(sourceSide,b);if(!same(source.entries.find(e=>e.key===entry.key),entry))throw new Error('来源工件已变化，请重新同步。')
    const target=await this.rows(targetSide,b),old=this.runTargets[targetSide].entries.find(e=>e.key===entry.key),current=target.entries.find(e=>e.key===entry.key)
    if(Boolean(old)!==Boolean(current)||(old&&!same(old,current)))throw new Error('目标工件已变化，请重新同步。')
    if(!target.historyKeys.includes(historyKey(entry.key)))throw new Error('目标执行历史已变化，请先核对。')
    const request={requestKey:crypto.randomUUID(),expectedUserId:targetSide==='local'?b.localActorId:b.actor.id,expectedRevision:target.revision,rawFilesConfirmed:true,artifact:{...clone(entry),nativeRecord:false},content:bytes.toString('base64')}
    const next=clone(this.state);next.pending={side:targetSide,entry:clone(entry),request};await this.save(next);await this.recoverPending(b)
  }
  async run(){
    if(this.busy)return;this.busy=true
    try{
      await this.load();const b=this.state.binding;if(!b?.enabled||b.requiresRebind)return
      await this.actors(b);await this.recoverPending(b)
      const [l,r]=await Promise.all([this.rows('local',b),this.rows('remote',b)]),changes=this.changes(l,r)
      this.runTargets={local:l,remote:r}
      const next=clone(this.state);next.conflicts=changes.conflicts
      for(const e of changes.unchanged)next.bases=next.bases.filter(v=>v.key!==e.key).concat(e)
      const work=[...changes.push.map(entry=>({side:'local',entry})),...changes.pull.map(entry=>({side:'remote',entry}))]
      next.remaining=Math.max(0,work.length-20);await this.save(next)
      for(const {side,entry} of work.slice(0,20)){
        try{await this.send(side,entry,b)}catch(error){
          if(!error.artifactUnavailable)throw error
          const value=clone(this.state);value.conflicts=value.conflicts.filter(e=>e.key!==entry.key).concat({key:entry.key,local:l.entries.find(e=>e.key===entry.key)||null,remote:r.entries.find(e=>e.key===entry.key)||null,reason:'来源工件缺失、损坏或不可读，请核对存储；其他工件可继续同步。',canResolve:false});await this.save(value)
        }
      }
      await this.save({...clone(this.state),lastSyncedAt:this.now()})
    }finally{this.runTargets=undefined;this.busy=false}
  }
  async resolve(){throw new Error('原始工件只读，不会覆盖或自动删除，请核对来源。')}
}
module.exports={MobileAutomationArtifactSync,MobileAutomationArtifactSyncStore,validateEntry,validateState,empty}
