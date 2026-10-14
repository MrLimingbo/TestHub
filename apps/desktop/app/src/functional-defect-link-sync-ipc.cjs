'use strict'
const { FunctionalDefectLinkSync, FunctionalDefectLinkSyncStore } = require('./functional-defect-link-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installFunctionalDefectLinkSync(options) {
  return installProjectSync(options, {
    Client: FunctionalDefectLinkSync, Store: FunctionalDefectLinkSyncStore, channel: 'functional-defect-link-sync', page: 'functional-defect-link-sync.html',
    filename: 'functional-defect-link-sync.bin', title: '缺陷关联同步', transport: { label: '缺陷关联', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条缺陷关联、下载 ${p.counts.download} 条缺陷关联，${p.counts.conflictCount} 条需核对。\n按缺陷、需求和用例编号映射两端自己的 ID；只同步需求／用例的完整关联集合，支持主动清空。不会创建缺陷、需求或用例，请先同步基础资料。已有未变化关联的 ID、创建人和时间保留；新关联使用本端实际账号。执行用例关联、缺陷正文、状态、人员和历史保留各端。资产缺失、改名、同号重建需要核对，资产删除导致的关联移除不自动传播；旧失效关联须在缺陷详情中核对并解除，同步不会删除旧关联。每类资产最多 100 条、每缺陷每类关联最多 100 条，资料不超过 3 MiB。编号与关联会发送到上述账号服务，请核对业务信息。`,
    unbindDetail: '两端缺陷关联均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。选择版本替换该资产的完整需求／用例集合（可以为空）；资产正文、ID、创建信息和执行历史保持各端。`,
  })
}
module.exports = { installFunctionalDefectLinkSync }
