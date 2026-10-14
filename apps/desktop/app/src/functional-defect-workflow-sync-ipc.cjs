'use strict'
const { FunctionalDefectWorkflowSync, FunctionalDefectWorkflowSyncStore } = require('./functional-defect-workflow-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function describe(item) {
  if (!item) return '缺陷不存在'
  const d = item.definition
  const states = { OPEN: '激活', IN_PROGRESS: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }
  const resolutions = { FIXED: '已修复', DUPLICATE: '重复缺陷', BY_DESIGN: '设计如此', CANNOT_REPRODUCE: '无法重现', POSTPONED: '延期处理', EXTERNAL: '外部原因' }
  return `${item.name} · ${states[d.status]} · ${d.confirmed ? '已确认' : '未确认'} · ${d.resolution ? resolutions[d.resolution] : '无解决方案'}`
}
function installFunctionalDefectWorkflowSync(options) {
  return installProjectSync(options, {
    Client: FunctionalDefectWorkflowSync, Store: FunctionalDefectWorkflowSyncStore, channel: 'functional-defect-workflow-sync', page: 'functional-defect-workflow-sync.html',
    filename: 'functional-defect-workflow-sync.bin', title: '缺陷工作流同步', transport: { label: '缺陷工作流', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条工作流、下载 ${p.counts.download} 条工作流，${p.counts.conflictCount} 条需核对。\n只同步状态、是否确认与解决方案，缺陷须先存在于两端。负责人、抄送、正文与关联保留各端。相同状态保留本端操作历史；重新打开清除失效历史。新同步的解决／关闭状态不生成历史操作人或时间，解决方案变化后旧归因失效。最多 100 条缺陷、资料不超过 3 MiB。编号与工作流将发送到上述账号服务。`,
    unbindDetail: '两端缺陷工作流均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${describe(c.local)}\n网页：${describe(c.remote)}\n选择将替换完整状态、确认与解决方案。新同步状态不生成历史操作人或时间；重新打开清除失效历史。确认期间资料变化时要求重新核对。`,
  })
}
module.exports = { installFunctionalDefectWorkflowSync }
