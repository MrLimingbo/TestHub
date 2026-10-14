'use strict'
const crypto = require('node:crypto')
const { Buffer } = require('node:buffer')
const { normalizeServerUrl } = require('./desktop-core.cjs')
const { EncryptedSyncStore } = require('./encrypted-sync-store.cjs')
const { ProjectFileLinks, blocked } = require('./project-file-links.cjs')
const clone = value => globalThis.structuredClone(value)
const id = value => typeof value === 'string' && /^[A-Z0-9]{26}$/.test(value)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const revision = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const kinds = ['RUN']
const keyOf = item => JSON.stringify([item.kind, item.name.toLowerCase()])
function stable(value) {
  if (Array.isArray(value)) return value.map(stable)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]))
  return value
}
const content = item => ({ kind: item.kind, name: item.name, definition: stable(item.definition) })
const same = (a, b) => Boolean(a && b) && JSON.stringify(content(a)) === JSON.stringify(content(b))
const empty = () => ({ version: 1, binding: null, bases: [], pending: null, conflicts: [], lastSyncedAt: null, identities: { local: {}, remote: {} }, layouts: {local:{},remote:{}}, assetIds:{local:{},remote:{}} })
const validKey = key => { try { const pair = JSON.parse(key); return Array.isArray(pair) && pair.length === 2 && kinds.includes(pair[0]) && /^[a-z0-9]{26}$/.test(pair[1]) && pair[1] === pair[1].toLowerCase() && JSON.stringify(pair) === key } catch { return false } }
function capacity(items) {
  const counts = new Map()
  for (const i of items) { validateItem(i); counts.set(i.kind, (counts.get(i.kind) || 0) + 1) }
  if (items.reduce((n,i)=>n+i.definition.cases.length,0)>1000||items.reduce((n,i)=>n+i.definition.cases.reduce((n,c)=>n+c.steps.length,0),0)>5000||[...counts.values()].some(n => n > 100)) throw new Error('合并后（含待核对的缺失资料）超过 100 条执行历史限制，请先核对。')
}
function requireMergeCapacity(local, remote, bases = []) {
  const merged = new Map([...bases, ...local.items, ...remote.items].map(i => [keyOf(i), i])); capacity([...merged.values()])
  const largest = new Map()
  for (const i of [...local.items, ...remote.items]) { const encoded = JSON.stringify(content(i)), key = keyOf(i); if (!largest.has(key) || Buffer.byteLength(encoded) > Buffer.byteLength(largest.get(key))) largest.set(key, encoded) }
  if ([...largest.values()].reduce((n, encoded) => n + Buffer.byteLength(encoded) + 1, 1024) > 3 * 1024 * 1024) throw new Error('合并后执行历史超过 3 MiB 同步范围，请先缩减资料。')
}
function hasProjectLinks(item) { return blocked(item) }
function exact(v,fields){return v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===fields.length&&fields.every(f=>Object.hasOwn(v,f))}
const code=v=>typeof v==='string'&&/^[A-Z][A-Z0-9_-]{1,39}$/.test(v)
const result=v=>['NOT_RUN','PASSED','FAILED','BLOCKED'].includes(v)
function time(v){if(v===null)return true;if(typeof v!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(v))return false;const n=Date.parse(v);return Number.isFinite(n)&&n>=1000&&n<=2147483647499&&new Date(n).toISOString().replace(/\.000Z$/,'Z')===v}
function validateItem(item){
 const d=item?.definition,fail=()=>{throw new Error('执行历史资料无效，请核对结果、时间和步骤。')}
 if(!exact(item,['kind','name','definition'])||item.kind!=='RUN'||!id(item.name)||!exact(d,['planCode','title','status','startedAt','finishedAt','cases'])||!code(d.planCode)||typeof d.title!=='string'||!d.title.trim()||d.title.length>200||!Array.isArray(d.cases)||!d.cases.length||d.cases.length>1000||!time(d.startedAt)||!time(d.finishedAt))fail()
 let terminal=0,steps=0,previous='';for(const c of d.cases){
  if(!exact(c,['caseCode','status','note','executedAt','steps'])||!code(c.caseCode)||c.caseCode<=previous||!result(c.status)||typeof c.note!=='string'||c.note.length>20000||!time(c.executedAt)||!Array.isArray(c.steps)||c.steps.length>5000)fail();previous=c.caseCode;if(c.status!=='NOT_RUN')terminal++
  let number=0;for(const s of c.steps){if(!exact(s,['number','text','status','actualResult','updatedAt'])||!Number.isInteger(s.number)||s.number<=number||s.number>9999||typeof s.text!=='string'||!s.text.trim()||s.text.length>20000||!result(s.status)||typeof s.actualResult!=='string'||s.actualResult.length>20000||!time(s.updatedAt)||(c.status==='PASSED'&&s.status!=='PASSED'))fail();number=s.number;steps++}
 }
 const expected=terminal===0?'NOT_STARTED':terminal===d.cases.length?'COMPLETED':'RUNNING';if(d.status!==expected||steps>5000||(terminal>0&&d.startedAt===null)||(d.status==='COMPLETED')!==(d.finishedAt!==null)||(d.finishedAt!==null&&Date.parse(d.finishedAt)<Date.parse(d.startedAt)))fail();return item
}
const referenceKey=item=>item.kind+'\n'+item.name.toLowerCase()
function validateIdentities(map){if(!map||typeof map!=='object'||Array.isArray(map)||Object.keys(map).length>100||Object.entries(map).some(([k,v])=>!/^RUN\n[a-z0-9]{26}$/.test(k)||!id(v)))throw new Error('历史批次标识无效。')}
function validateAssets(map){if(!map||typeof map!=='object'||Array.isArray(map)||Object.keys(map).length>2100||Object.entries(map).some(([k,v])=>!id(v)||! /^(PLAN|CASE)\n[A-Z][A-Z0-9_-]{1,39}$/.test(k)))throw new Error('历史依赖标识无效。')}
function validateLayout(value,item){
 if(!exact(value,['planId','caseIds','stepIds'])||!id(value.planId)||!value.caseIds||!value.stepIds||Array.isArray(value.caseIds)||Array.isArray(value.stepIds)||Object.keys(value.caseIds).length>1000||Object.keys(value.stepIds).length>1000)throw new Error('执行历史对象引用无效。')
 const ids=[value.planId];for(const [c,i]of Object.entries(value.caseIds)){if(!code(c)||!id(i)||!value.stepIds[c]||Array.isArray(value.stepIds[c])||Object.entries(value.stepIds[c]).some(([n,i])=>! /^[1-9]\d{0,3}$/.test(n)||!id(i)))throw new Error('执行历史对象引用无效。');ids.push(i,...Object.values(value.stepIds[c]))}
 if(new Set(ids).size!==ids.length||Object.keys(value.stepIds).some(c=>!Object.hasOwn(value.caseIds,c)))throw new Error('执行历史引用重复。')
 if(item&&(JSON.stringify(Object.keys(value.caseIds).sort())!==JSON.stringify(item.definition.cases.map(c=>c.caseCode))||item.definition.cases.some(c=>JSON.stringify(Object.keys(value.stepIds[c.caseCode]).map(Number).sort((a,b)=>a-b))!==JSON.stringify(c.steps.map(s=>s.number)))))throw new Error('执行历史布局引用不完整。')
 return value
}
function validateRuns(runs){if(!Array.isArray(runs)||runs.length>100)throw new Error('批次对应关系无效。');for(const side of ['local','remote'])if(new Set(runs.map(r=>r?.[side]?.key)).size!==runs.length||runs.some(r=>!id(r?.[side]?.id)||!id(r[side].key)||!code(r[side].planCode)))throw new Error('已有批次必须一对一配对。');if(runs.some(r=>r.local.planCode!==r.remote.planCode))throw new Error('对应批次的计划编号必须一致。')}
function translate(item,side,b,wire=false){const p=b.runs.find(p=>p[wire?'local':side].key===item.name);return {...content(item),name:p?p[wire?side:'local'].key:item.name}}
function mapped(raw,side,b){const pairs=raw.items.map(i=>[i,translate(i,side,b)]),items=pairs.map(([,v])=>v);if(new Set(items.map(keyOf)).size!==items.length)throw new Error('批次对应关系与来源标识冲突，请重新核对。');return {...raw,items,itemIds:Object.fromEntries(pairs.map(([a,v])=>[referenceKey(v),raw.itemIds[referenceKey(a)]])),layouts:Object.fromEntries(pairs.map(([a,v])=>[referenceKey(v),raw.layouts[referenceKey(a)]])),createdAt:Object.fromEntries(pairs.map(([a,v])=>[referenceKey(v),raw.createdAt[referenceKey(a)]]))}}
const layout=d=>JSON.stringify([d.planCode,d.cases.map(c=>[c.caseCode,c.steps.map(s=>[s.number,s.text])])])
const assetsUsed=(item,snapshot)=>Object.fromEntries(['PLAN\n'+item.definition.planCode,...item.definition.cases.map(c=>'CASE\n'+c.caseCode)].map(k=>[k,snapshot.assetIds[k]]))
function validateState(state) {
  if (state?.version !== 1 || !Array.isArray(state.bases) || state.bases.length > 100 || !Array.isArray(state.conflicts) || state.conflicts.length > 200)
    throw new Error('执行历史同步记录损坏，请保留现有数据。')
  validateIdentities(state.identities?.local); validateIdentities(state.identities?.remote)
  for(const side of ['local','remote']){validateAssets(state.assetIds?.[side]);const values=state.layouts?.[side];if(!values||typeof values!=='object'||Array.isArray(values)||Object.keys(values).length>100||Object.keys(values).some(k=>!/^RUN\n[a-z0-9]{26}$/.test(k)))throw new Error('历史布局记录无效。');Object.values(values).forEach(v=>validateLayout(v))}
  const b = state.binding
  if (b && (normalizeServerUrl(b.server) !== b.server || !id(b.actor?.id) || !id(b.localActorId) || !id(b.source?.id) || !id(b.target?.id)
    || typeof b.enabled !== 'boolean' || (b.requiresRebind !== undefined && typeof b.requiresRebind !== 'boolean'))) throw new Error('执行历史同步绑定无效。')
  if (!b && (state.bases.length || state.pending || state.conflicts.length)) throw new Error('未绑定的资料不能含有同步记录。')
  if(b)validateRuns(b.runs)
  const keys = new Set()
  for (const base of state.bases) {
    validateItem(base); const key = keyOf(base)
    if (keys.has(key)) throw new Error('执行历史同步基线重复。'); keys.add(key)
  }
  const conflictKeys = new Set()
  for (const c of state.conflicts) {
    if (!validKey(c.key) || conflictKeys.has(c.key) || typeof c.reason !== 'string' || typeof c.canResolve !== 'boolean' || (c.reviewAfterMissing !== undefined && typeof c.reviewAfterMissing !== 'boolean')) throw new Error('执行历史同步冲突记录无效。')
    conflictKeys.add(c.key)
    for (const item of [c.local, c.remote]) if (item !== null) { validateItem(item); if (keyOf(item) !== c.key) throw new Error('执行历史同步冲突标识无效。') }
    if (c.canResolve && (!c.local || !c.remote || c.local.name !== c.remote.name || !id(c.localId) || !id(c.remoteId))) throw new Error('执行历史同步冲突选择无效。')
  }
  if (state.pending) {
    const p = state.pending, r = p.request
    if (!['local', 'remote'].includes(p.side) || !uuid(r?.requestKey) || !revision(r.expectedRevision)
      || r.expectedUserId !== (p.side === 'local' ? b.localActorId : b.actor.id) || !Array.isArray(r.operations) || !r.operations.length || r.operations.length > 100) throw new Error('执行历史同步待发送记录无效。')
    const ops = new Set(), targets = new Set()
    for (const op of r.operations) {
      validateItem(op.item); const key = keyOf(op.item)
      if (!time(op.createdAt)||op.createdAt===null||!uuid(op.operationId) || ops.has(op.operationId) || targets.has(key)) throw new Error('执行历史同步待发送操作重复。')
      ops.add(op.operationId); targets.add(key)
    }
  }
  return state
}
class FunctionalHistorySyncStore extends EncryptedSyncStore {
  constructor(directory, codec) { super(directory, codec, { filename: 'functional-history-sync.bin', validate: validateState, empty,
    label: '执行历史', maxPlain: 24 * 1024 * 1024, maxEncrypted: 32 * 1024 * 1024 }) }
}
class FunctionalHistorySync {
  constructor({ store, local, remote, guard, fileLinkContext, now = () => new Date().toISOString() }) { Object.assign(this, { store, local, remote, guard, fileLinkContext, now }); this.busy = false }
  async load() { if (!this.state) this.state = validateState(await this.store.read()); return this.state }
  async save(value) { await this.store.write(value); this.state = value }
  async status() { await this.load(); return { binding: clone(this.state.binding), conflicts: clone(this.state.conflicts), pending: Boolean(this.state.pending), lastSyncedAt: this.state.lastSyncedAt } }
  async actors(b) {
    await this.guard(b)
    const [local, remote] = await Promise.all([this.local('/api/v1/auth/me'), this.remote('/api/v1/auth/me', undefined, b)])
    if (local.id !== b.localActorId || remote.id !== b.actor.id) throw new Error('登录账号或本地工作区已变化，执行历史同步已停止；请切回绑定账号。')
    this.fileMapper = new ProjectFileLinks(b, await this.fileLinkContext?.(b))
  }
  async rawRows(side, b) {
    const project = side === 'local' ? b.source.id : b.target.id, actor = side === 'local' ? b.localActorId : b.actor.id
    const snapshot = await this[side](`/api/v1/projects/${project}/functional/history/sync?expectedUserId=${actor}`, undefined, b)
    if (snapshot?.version !== 1 || snapshot.scope !== 'FUNCTIONAL_HISTORY_SYNC' || snapshot.projectId !== project || snapshot.actorId !== actor
      || !revision(snapshot.revision) || !Array.isArray(snapshot.items) || snapshot.items.length > 100)
      throw new Error('服务不支持当前执行历史同步协议，请更新两端服务。')
    const keys = new Set()
    for (const item of snapshot.items) { validateItem(item); const key = keyOf(item); if (keys.has(key)) throw new Error('执行历史来源标识重复，请先核对。'); keys.add(key) }
    capacity(snapshot.items); validateIdentities(snapshot.itemIds)
    if (Object.keys(snapshot.itemIds).length !== snapshot.items.length || snapshot.items.some(i=>!Object.hasOwn(snapshot.itemIds,referenceKey(i)))) throw new Error('执行批次引用标识不完整。')
    const assetIds={};for(const [kind,map]of [['PLAN',snapshot.planIds],['CASE',snapshot.caseIds]]){if(!map||typeof map!=='object'||Array.isArray(map))throw new Error('历史依赖资料不完整。');for(const [code,value]of Object.entries(map))assetIds[kind+'\n'+code]=value}validateAssets(assetIds)
    if(!snapshot.layouts||Object.keys(snapshot.layouts).length!==snapshot.items.length)throw new Error('历史布局资料不完整。');for(const item of snapshot.items){const l=validateLayout(snapshot.layouts[referenceKey(item)],item);if(l.planId!==assetIds['PLAN\n'+item.definition.planCode]||Object.values(assetsUsed(item,{assetIds})).some(v=>!id(v)))throw new Error('历史项目依赖不完整。')}
    if(!snapshot.createdAt||Object.keys(snapshot.createdAt).length!==snapshot.items.length||snapshot.items.some(i=>!time(snapshot.createdAt[referenceKey(i)])||snapshot.createdAt[referenceKey(i)]===null))throw new Error("历史创建时间无效。");
    return {...snapshot,assetIds,items:snapshot.items.map(item=>this.fileMapper.logical(item,side))}
  }
  async pairingContext(sourceId, targetId, server) {
    await this.load(); this.plan = undefined
    if (this.state.binding) throw new Error('已有执行历史同步绑定，请先暂停并解除后再绑定其他项目。')
    if (!id(sourceId) || !id(targetId)) throw new Error('请选择有效的本地项目和账号项目。')
    server = normalizeServerUrl(server)
    const [actor, localActor, local, remote] = await Promise.all([this.remote('/api/v1/auth/me', undefined, { server }), this.local('/api/v1/auth/me'),
      this.local('/api/v1/access/context'), this.remote('/api/v1/access/context', undefined, { server })])
    const projects = context => context.organizations.flatMap(org => (context.projects[org.id] || []).map(p => ({ id: p.id, name: p.name, organization: org.name })))
    const source = projects(local).find(p => p.id === sourceId), target = projects(remote).find(p => p.id === targetId)
    if (!source || !target) throw new Error('项目权限已变化，请重新读取项目。')
    const binding = { server, actor: { id: actor.id, name: actor.displayName, account: actor.account || actor.email }, localActorId: localActor.id, source, target, enabled: true }
    await this.actors(binding)
    const [localRows, remoteRows] = await Promise.all([this.rawRows('local', binding), this.rawRows('remote', binding)])
    return {binding,localRows,remoteRows}
  }
  async rows(side,b){return mapped(await this.rawRows(side,b),side,b)}
  async references(sourceId,targetId,server){const p=await this.pairingContext(sourceId,targetId,server);const list=s=>s.items.map(i=>({id:s.itemIds[referenceKey(i)],key:i.name,planCode:i.definition.planCode,name:i.definition.title,status:i.definition.status,startedAt:i.definition.startedAt,caseCodes:i.definition.cases.map(c=>c.caseCode)}));return {local:list(p.localRows),remote:list(p.remoteRows)}}
  async preview(sourceId,targetId,server,pairs=[]){
    const p=await this.pairingContext(sourceId,targetId,server),runs=[];if(!Array.isArray(pairs)||pairs.length>100)throw new Error('批次对应关系无效。')
    for(const pair of pairs){const find=(s,id)=>{const item=s.items.find(i=>s.itemIds[referenceKey(i)]===id);return item?{id,key:item.name,planCode:item.definition.planCode,name:item.definition.title}:null};const local=find(p.localRows,pair?.localRunId),remote=find(p.remoteRows,pair?.remoteRunId);if(!local||!remote)throw new Error('所选历史批次已不存在，请重新读取。');if(local.key!==remote.key&&(p.localRows.items.some(i=>i.name===remote.key)||p.remoteRows.items.some(i=>i.name===local.key)))throw new Error('所选批次已有同来源对应记录，不能重新指向其他批次。');runs.push({local,remote})}validateRuns(runs)
    const binding={...p.binding,runs},localRows=mapped(p.localRows,'local',binding),remoteRows=mapped(p.remoteRows,'remote',binding);requireMergeCapacity(localRows,remoteRows);const counts=this.changes(localRows,remoteRows)
    this.plan={token:crypto.randomUUID(),binding,expires:Date.now()+600000,localRows,remoteRows,counts};return {token:this.plan.token,binding:clone(binding),upload:counts.upload,download:counts.download,conflictCount:counts.conflictCount}
  }
  async bind(token) {
    const p = this.plan
    if (!p || p.token !== token || p.expires <= Date.now() || this.state.binding) throw new Error('绑定预览已失效，请重新预览。')
    await this.actors(p.binding)
    const [l, r] = await Promise.all([this.rows('local', p.binding), this.rows('remote', p.binding)])
    if (l.revision !== p.localRows.revision || r.revision !== p.remoteRows.revision) throw new Error('执行历史在确认期间已变化，请重新预览。')
    await this.save({ ...empty(), binding: p.binding }); this.plan = undefined
  }
  async enabled(value) {
    await this.load()
    if (!this.state.binding || typeof value !== 'boolean') throw new Error('尚未绑定执行历史同步。')
    if (value && this.state.binding.requiresRebind) throw new Error('工作区已恢复过备份，请解除旧绑定并重新预览。')
    if (value) await this.actors(this.state.binding)
    const next = clone(this.state); next.binding.enabled = value; await this.save(next)
  }
  async unbind() { await this.load(); await this.save(empty()); this.plan = undefined }
  async suspendForRestore() {
    await this.load()
    if (this.state.binding) { const next = clone(this.state); next.binding.enabled = false; next.binding.requiresRebind = true; await this.save(next) }
  }
  changes(local, remote) {
    const l = new Map(local.items.map(i => [keyOf(i), i])), r = new Map(remote.items.map(i => [keyOf(i), i])), bases = new Map(this.state.bases.map(i => [keyOf(i), i]))
    const push = [], pull = [], conflicts = [], unchanged = []
    const issue = (key, reason) => { const a = l.get(key), b = r.get(key); const old = conflicts.find(c => c.key === key); if (old) { old.reason = reason; old.canResolve = false; old.reviewAfterMissing = true } else conflicts.push({ key, local: a ? content(a) : null, remote: b ? content(b) : null, reason, canResolve: false, reviewAfterMissing: true }) }
    for (const key of new Set([...l.keys(), ...r.keys(), ...bases.keys()])) {
      const a = l.get(key), b = r.get(key), base = bases.get(key)
      if ([a, b].some(i => i && hasProjectLinks(i))) { issue(key, '正文含本端项目或文件链接，请先显式迁移并核对引用；该资料暂停同步。'); continue }
      const item=a||b||base,ref=referenceKey(item)
      const invalid=['local','remote'].some(side=>{const snapshot=side==='local'?local:remote,record=side==='local'?a:b;if(!record)return false;const current=snapshot.layouts[ref],old=this.state.layouts[side][ref];return (Object.hasOwn(this.state.identities[side],ref)&&this.state.identities[side][ref]!==snapshot.itemIds[ref])||(old&&JSON.stringify(stable(old))!==JSON.stringify(stable(current)))||Object.entries(assetsUsed(record,snapshot)).some(([k,v])=>!id(v)||(Object.hasOwn(this.state.assetIds[side],k)&&this.state.assetIds[side][k]!==v))})
      if(invalid){issue(key,'计划、用例、执行用例或步骤已缺失／重建，请核对并重新绑定；不会继承旧对象。');continue}
      if(a&&b&&layout(a.definition)!==layout(b.definition)){issue(key,'对应历史批次的用例或步骤布局不同，暂停整批；不会删除或重写历史结构。');continue}
      const destination=!b?remote:!a?local:null;if(destination&&Object.values(assetsUsed(item,destination)).some(v=>!id(v))){issue(key,'目标计划或用例缺失，请先同步基础资料再核对历史。');continue}
      const paired=this.state.binding?.runs.some(p=>p.local.key===item.name)
      if((base||paired||Object.hasOwn(this.state.identities.local,ref)&&Object.hasOwn(this.state.identities.remote,ref))&&(!a||!b)){issue(key,'已有历史批次一端缺失，请核对；不会删除或重新创建。');continue}
      if (same(a, b)) { unchanged.push(content(a)); continue }
      if (base && (!a || !b)) { issue(key, '一端资料已删除或改名，请核对；不会自动删除或重新创建。'); continue }
      if (a && b && a.name !== b.name) { issue(key, '资料名称大小写不同，请先在两端统一名称。'); continue }
      const recreated = a && b && ['local','remote'].some(side => {
        const before=this.state.identities[side], after=(side==='local'?local:remote).itemIds, ref=referenceKey(a)
        return Object.hasOwn(before,ref) && before[ref] !== after[ref]
      })
      if (a && b && (recreated || this.state.conflicts.some(c=>c.key===key && c.reviewAfterMissing))) {
        conflicts.push({key,local:content(a),remote:content(b),reason:'实际历史批次已变化，请核对后选择完整历史。',canResolve:true,localId:local.itemIds[ref],remoteId:remote.itemIds[ref],reviewAfterMissing:true});continue
      }
      if (a && b && (!base || (!same(a, base) && !same(b, base)))) { conflicts.push({ key, local: content(a), remote: content(b), reason: base ? '同一资料在两端均已修改。' : '两端对应批次的历史内容不同。', canResolve: true, localId:local.itemIds[ref],remoteId:remote.itemIds[ref] }); continue }
      const side = !b || (a && base && !same(a, base)) ? push : pull; side.push(content(side === push ? a : b))
    }
    const blocked = new Set(conflicts.map(c => c.key))
    const uploads = push.filter(i => !blocked.has(keyOf(i))), downloads = pull.filter(i => !blocked.has(keyOf(i)))
    return { push: uploads, pull: downloads, conflicts, unchanged, upload: uploads.length, download: downloads.length, conflictCount: conflicts.length }
  }
  async send(side, items, expectedRevision, b, source) {
    if (!items.length) return
    const next = clone(this.state); next.pending = { side, request: { requestKey: crypto.randomUUID(), expectedUserId: side === 'local' ? b.localActorId : b.actor.id,
      expectedRevision, operations: items.map(item => ({ operationId: crypto.randomUUID(), item: this.fileMapper.physical(translate(item,side,b,true), side),createdAt:source.createdAt[referenceKey(item)] })) } }
    await this.save(next); await this.recoverPending(b)
  }
  async recoverPending(b) {
    const p = this.state.pending; if (!p) return
    await this.actors(b); const csrf = await this[p.side]('/api/v1/auth/csrf', undefined, b); await this.guard(b)
    const project = p.side === 'local' ? b.source.id : b.target.id
    let receipt
    try { receipt = await this[p.side](`/api/v1/projects/${project}/functional/history/sync`, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify(p.request) }, b) }
    catch (error) { if (error.status === 409) { const next = clone(this.state); next.pending = null; await this.save(next) } throw error }
    if (receipt?.requestKey !== p.request.requestKey || !Array.isArray(receipt.results) || receipt.results.length !== p.request.operations.length) throw new Error('执行历史同步回执不完整，将保留原请求供安全重试。')
    const next = clone(this.state), seen = new Set()
    for (const op of p.request.operations) {
      const result = receipt.results.find(r => r.operationId === op.operationId)
      if (!result || seen.has(result.operationId)) throw new Error('执行历史同步回执标识不匹配。')
      seen.add(result.operationId); validateItem(result.item)
      if (!id(result.itemId)) throw new Error('执行历史同步回执引用标识无效。')
      const canonical=this.fileMapper.logical(translate(op.item,p.side,b),p.side);validateLayout(result.layout,result.item);validateAssets(result.assetIds);if(result.layout.planId!==result.assetIds['PLAN\n'+canonical.definition.planCode]||Object.values(assetsUsed(canonical,{assetIds:result.assetIds})).some(v=>!id(v)))throw new Error('历史回执依赖不完整。')
      next.identities[p.side][referenceKey(canonical)] = result.itemId;next.layouts[p.side][referenceKey(canonical)]=clone(result.layout);Object.assign(next.assetIds[p.side],result.assetIds)
      if (!same(result.item, op.item)) throw new Error('执行历史同步回执内容不匹配。')
      const key = keyOf(canonical); next.bases = next.bases.filter(i => keyOf(i) !== key); next.bases.push(content(canonical))
      next.conflicts = next.conflicts.filter(c => c.key !== key)
    }
    next.pending = null; await this.save(next)
  }
  async run() {
    if (this.busy) return; this.busy = true
    try {
      await this.load(); const b = this.state.binding; if (!b?.enabled || b.requiresRebind) return
      await this.actors(b); await this.recoverPending(b)
      const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)]); await this.actors(b)
      requireMergeCapacity(l, r, this.state.bases)
      const changes = this.changes(l, r), next = clone(this.state)
      for (const item of changes.unchanged) { next.bases = next.bases.filter(i => keyOf(i) !== keyOf(item)); next.bases.push(item) }
      next.conflicts = changes.conflicts
      for (const side of ['local','remote']) { const snapshot=side==='local'?l:r; for (const i of snapshot.items) { if (!changes.conflicts.some(c=>c.key===keyOf(i))) {next.identities[side][referenceKey(i)]=snapshot.itemIds[referenceKey(i)];next.layouts[side][referenceKey(i)]=clone(snapshot.layouts[referenceKey(i)]);Object.assign(next.assetIds[side],assetsUsed(i,snapshot))} } }
      await this.save(next)
      await this.send('remote', changes.push, r.revision, b,l); await this.send('local', changes.pull, l.revision, b,r)
      await this.save({ ...clone(this.state), lastSyncedAt: this.now() })
    } finally { this.busy = false }
  }
  async resolve(key, choice) {
    await this.load(); const b = this.state.binding, c = this.state.conflicts.find(c => c.key === key)
    if (!b?.enabled || b.requiresRebind || this.state.pending || !c?.canResolve || !['local', 'remote'].includes(choice)) throw new Error('当前资料冲突无法自动处理，请先核对两端资料。')
    await this.actors(b); const [l, r] = await Promise.all([this.rows('local', b), this.rows('remote', b)])
    const a = l.items.find(i => keyOf(i) === key), other = r.items.find(i => keyOf(i) === key)
    if (!this.changes(l, r).conflicts.find(c => c.key === key)?.canResolve || !same(a, c.local) || !same(other, c.remote)||l.itemIds[referenceKey(a)]!==c.localId||r.itemIds[referenceKey(other)]!==c.remoteId) throw new Error('冲突资料已变化，请重新同步并核对后选择。')
    await this.send(choice === 'local' ? 'remote' : 'local', [choice === 'local' ? a : other], choice === 'local' ? r.revision : l.revision, b,choice==='local'?l:r)
  }
}
module.exports = { FunctionalHistorySync, FunctionalHistorySyncStore, validateState, validateItem, keyOf, empty }
