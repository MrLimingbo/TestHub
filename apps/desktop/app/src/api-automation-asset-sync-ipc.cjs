'use strict'
const { ApiAutomationAssetSync, ApiAutomationAssetSyncStore } = require('./api-automation-asset-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installApiAutomationAssetSync(options) {
  return installProjectSync(options, {
    Client: ApiAutomationAssetSync, Store: ApiAutomationAssetSyncStore, channel: 'api-automation-asset-sync',
    page: 'api-automation-asset-sync.html', filename: 'api-automation-asset-sync.bin', title: '接口自动化基础资料同步',
    transport: { label: '接口自动化基础资料', limit: 10 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条资料、下载 ${p.counts.download} 条资料，${p.counts.conflictCount} 条需核对。\n按类型和名称同步公共步骤当前内容、数据集的全部行和执行配置集。步骤配置、数据集行及变量会发送到上述账号服务，请核对业务信息。公共步骤在目标端追加修订，固定旧版本的场景引用保留。配置集版本号递增；后续使用该配置集的执行会采用新变量，已创建的执行快照保留。已配对且完好的项目文件下载链接与数据集来源文件会映射；配置集目标环境须已存在且启用。不会创建执行，不同步场景、旧修订、定时任务或 Webhook。删除不传播。每类最多 100 条，总计最多 300 条、8 MiB。`,
    unbindDetail: '两端基础资料及历史修订保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再变化时停止更新。目标已有公共步骤修订不会改写；同步内容追加为新版本，固定旧版本的场景引用保持不变。`,
  })
}
module.exports = { installApiAutomationAssetSync }
