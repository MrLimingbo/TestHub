'use strict'
const { FunctionalDefectExecutionSync, FunctionalDefectExecutionSyncStore } = require('./functional-defect-execution-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
const label=record=>`${record.planCode} / ${record.runName}（批次 ${record.runId.slice(-8)}） / ${record.caseCode}`
function describe(item,binding,side){
  if(!item)return '缺陷不存在'
  return `${item.name} · 执行关联：${item.definition.runCaseIds.map(id=>{const p=binding.references.find(p=>p.local.id===id);return p?label(p[side]):'未配对的执行用例'}).join('；')||'无'}`
}
function installFunctionalDefectExecutionSync(options) {
  return installProjectSync(options, {
    Client: FunctionalDefectExecutionSync, Store: FunctionalDefectExecutionSyncStore, channel: 'functional-defect-execution-sync', page: 'functional-defect-execution-sync.html',
    filename: 'functional-defect-execution-sync.bin', title: '缺陷执行关联同步', transport: { label: '缺陷执行关联', limit: 4 * 1024 * 1024 },
    preview: (client,value,server) => value?.mode === 'runs' ? client.references(value.sourceId,value.targetId,server) : client.preview(value?.sourceId,value?.targetId,server,value?.runPairs),
    planDetail: p => `服务：${p.binding.server}\n执行批次对应：\n${p.binding.runs.map(r=>`${r.local.planCode} / ${r.local.name}（${r.local.id.slice(-8)}） ↔ ${r.remote.planCode} / ${r.remote.name}（${r.remote.id.slice(-8)}）`).join('\n')}\n匹配 ${p.binding.references.length} 条执行用例。首次上传 ${p.counts.upload} 条关联集合、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n只按已确认的批次及相同用例编号同步完整执行关联，包括明确清空。未配对、缺失或重建暂停相关缺陷；不会创建历史或缺陷。需求／用例关联、状态、正文、人员和所有执行结果保留各端。保留已有执行关联的标识、时间与操作人；新增关联归目标实际同步账号。最多 100 条缺陷、1000 条执行用例、3 MiB。`,
    unbindDetail: '两端缺陷执行关联均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: (c,binding) => `本地：${describe(c.local,binding,'local')}\n网页：${describe(c.remote,binding,'remote')}\n选择将替换完整执行用例关联集合，其他关联和执行历史不变。确认期间资料或实际对象变化时需重新核对。`,
  })
}
module.exports = { installFunctionalDefectExecutionSync }
