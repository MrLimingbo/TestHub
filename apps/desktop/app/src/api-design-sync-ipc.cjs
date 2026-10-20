'use strict'
const { ApiDesignSync, ApiDesignSyncStore } = require('./api-design-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installApiDesignSync(options) {
  return installProjectSync(options, {
    Client: ApiDesignSync, Store: ApiDesignSyncStore, channel: 'api-design-sync', page: 'api-design-sync.html',
    filename: 'api-design-sync.bin', title: '接口设计资料同步', transport: { label: '接口设计资料', limit: 10 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条资料、下载 ${p.counts.download} 条资料，${p.counts.conflictCount} 条需核对。\n同步接口目录、方法与路径、历史版本内容和调试用例，按完整目录路径、方法与路径、版本名及用例名匹配。同名版本两端内容不同需选择保留一端；可覆盖已有历史内容，已发布状态不会自动撤销。环境代码需在目标存在并启用；已独立确认且字节完整的 gRPC 描述符映射两端 ID；未配对或描述符绑定暂停时，相关用例暂停。已配对且完好的项目文件下载链接会映射到目标文件。资料内容将发送到上述账号服务，请核对业务信息。不创建执行，不同步调试历史或自动化场景；删除不传播。总计最多 300 条、8 MiB。`,
    unbindDetail: '两端接口设计资料均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。历史版本内容可覆盖，已发布状态与发布来源保留；执行结果和其他模块资料保持各端。`,
  })
}
module.exports = { installApiDesignSync }
