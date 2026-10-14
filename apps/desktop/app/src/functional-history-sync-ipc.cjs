'use strict'
const { FunctionalHistorySync, FunctionalHistorySyncStore } = require('./functional-history-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
const labels={NOT_STARTED:'未开始',RUNNING:'执行中',COMPLETED:'已完成',NOT_RUN:'未执行',PASSED:'通过',FAILED:'失败',BLOCKED:'阻塞'}
const date=v=>v?new Date(v).toLocaleString():'无'
function describe(item){if(!item)return '批次不存在';const d=item.definition;return `${d.planCode} / ${d.title} · ${labels[d.status]}\n开始：${date(d.startedAt)} · 完成：${date(d.finishedAt)}\n${d.cases.map(c=>`${c.caseCode} · ${labels[c.status]} · ${date(c.executedAt)}\n备注：${c.note||'无'}\n${c.steps.map(s=>`步骤 ${s.number}：${s.text} · ${labels[s.status]} · ${date(s.updatedAt)}\n实际结果：${s.actualResult||'无'}`).join('\n')}`).join('\n')}`}
function installFunctionalHistorySync(options){return installProjectSync(options,{
 Client:FunctionalHistorySync,Store:FunctionalHistorySyncStore,channel:'functional-history-sync',page:'functional-history-sync.html',filename:'functional-history-sync.bin',title:'执行历史同步',transport:{label:'执行历史',limit:4*1024*1024},
 preview:(c,v,s)=>v?.mode==='runs'?c.references(v.sourceId,v.targetId,s):c.preview(v?.sourceId,v?.targetId,s,v?.runPairs),
 planDetail:p=>`服务：${p.binding.server}\n已有批次对应：${p.binding.runs.map(r=>`${r.local.planCode} / ${r.local.name}（${r.local.id.slice(-8)}） ↔ ${r.remote.name}（${r.remote.id.slice(-8)}）`).join('；')||'无'}\n首次上传 ${p.counts.upload} 个批次、下载 ${p.counts.download} 个批次，${p.counts.conflictCount} 条需核对。未配对批次单独复制为新批次，不按同名合并。以后新批次按固定来源标识同步。已有批次保留执行用例／步骤标识与缺陷关联，结构不同、缺失／重建暂停整批；不跨端删除。新批次保留创建时间，创建人及同步审计记录目标账号；改动结果的原执行人标记清空，未改动的本端执行人保留。两端可编辑，冲突按整批选择，不启动测试。最多100个批次、1000条用例、5000条步骤、3 MiB。`,
 unbindDetail:'两端历史均保留。未确认请求停止重试；若结果不确定请先核对两端。重新绑定时内容不同需再次选择。',
 identifier:v=>v?.key,conflictId:c=>c.key,canResolve:c=>Boolean(c?.canResolve),conflictDetail:c=>`本地：${describe(c.local)}\n网页：${describe(c.remote)}\n选择将替换整批结果、步骤、备注和执行时间；其他批次及缺陷关联保留。确认期间对象或内容变化需重新核对。`
})}
module.exports={installFunctionalHistorySync}
