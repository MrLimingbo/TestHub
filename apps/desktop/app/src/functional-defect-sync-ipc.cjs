'use strict'
const { FunctionalDefectSync, FunctionalDefectSyncStore } = require('./functional-defect-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installFunctionalDefectSync(options) {
  return installProjectSync(options, {
    Client: FunctionalDefectSync, Store: FunctionalDefectSyncStore, channel: 'functional-defect-sync', page: 'functional-defect-sync.html',
    filename: 'functional-defect-sync.bin', title: '缺陷基本资料同步', transport: { label: '缺陷基本资料', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条资料、下载 ${p.counts.download} 条资料，${p.counts.conflictCount} 条需核对。\n按缺陷编号及完整交付目录路径匹配。同步标题、截止日期、Bug 类型、关键词、浏览器、操作系统、严重程度、优先级、来源、外部链接和重现步骤。新缺陷为待处理、未确认且无负责人。已有状态、确认、解决方案、人员、流转时间、创建信息、资产关联及文件保留各端。不会创建执行，删除不传播。已确认且两端完好的项目文件下载链接会映射；其他项目链接或失效文件暂停相关缺陷。每类最多 100 条，目录最多 16 层，资料不超过 3 MiB。资料内容会发送到上述账号服务，请核对业务信息。`,
    unbindDetail: '两端缺陷资料保留。未确认的请求不再重试；结果不确定时请先核对。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local,null,2)}\n网页：${JSON.stringify(c.remote,null,2)}\n只更新基本资料；状态、确认、人员、流转记录和关联保留各端。确认期间资料再次变化会停止更新。`,
  })
}
module.exports = { installFunctionalDefectSync }
