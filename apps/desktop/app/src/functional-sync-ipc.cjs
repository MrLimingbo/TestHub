'use strict'
const { FunctionalSync, FunctionalSyncStore } = require('./functional-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installFunctionalSync(options) {
  return installProjectSync(options, {
    Client: FunctionalSync, Store: FunctionalSyncStore, channel: 'functional-sync', page: 'functional-sync.html',
    filename: 'functional-sync.bin', title: '功能测试资料同步', transport: { label: '功能测试资料', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条资料、下载 ${p.counts.download} 条资料，${p.counts.conflictCount} 条需核对；两端共 ${p.counts.excludedExecutions} 条执行历史、${p.counts.excludedFiles} 个项目文件不复制。\n同步需求、用例、计划的目录结构及排序；按完整目录路径和需求/用例/计划编号匹配，关联在目标重映射，计划用例顺序保留。同步标题、优先级、资料状态、用例类型和正文。人员、显示编号和执行记录保留各端，不创建执行。已确认且两端完好的项目文件下载链接会映射到对应文件；其他项目链接或失效文件暂停相关资料。所有同步资料内容会发送到上述账号服务，请核对业务信息。治理资料、缺陷、版本及文件按各自绑定独立同步。删除不传播，改名按新路径或编号处理。每类最多 100 条，目录最多 16 层，资料最多 3 MiB。`,
    unbindDetail: '两端功能测试资料均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。选择版本不会创建执行；执行结果、人员和治理关联保持各端。`,
  })
}
module.exports = { installFunctionalSync }
