'use strict'
const { FunctionalAssetTagSync, FunctionalAssetTagSyncStore } = require('./functional-asset-tag-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installFunctionalAssetTagSync(options) {
  return installProjectSync(options, {
    Client: FunctionalAssetTagSync, Store: FunctionalAssetTagSyncStore, channel: 'functional-asset-tag-sync', page: 'functional-asset-tag-sync.html',
    filename: 'functional-asset-tag-sync.bin', title: '标签关联同步', transport: { label: '标签关联', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条关联定义、下载 ${p.counts.download} 条关联定义，${p.counts.conflictCount} 条需核对。\n仅同步需求、用例和计划的完整标签集合，按资产类型/编号和精确标签名称重映射本端 ID。请先同步基础资料及治理定义；引用缺失、改名或大小写不同会暂停该资产。两端已有不同集合首次绑定时需选择版本，后续清空集合会解除目标资产的标签关联。删除资产或标签字典不传播。不会创建资产或标签，不修改正文、人员、字段、执行、文件及历史。每类型最多 100 个资产、每资产最多 100 个标签、快照最多 3 MiB。标签名称及资产编号会发送至上述服务，请核对业务信息。`,
    unbindDetail: '两端标签关联均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。选择版本替换该资产的完整标签集合（可以为空）；资产正文、ID、创建信息和执行历史保持各端。`,
  })
}
module.exports = { installFunctionalAssetTagSync }
