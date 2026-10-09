'use strict'
const { PerformanceSync, PerformanceSyncStore } = require('./performance-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installPerformanceSync(options) {
  return installProjectSync(options, {
    Client: PerformanceSync, Store: PerformanceSyncStore, channel: 'performance-sync', page: 'performance-sync.html',
    filename: 'performance-sync.bin', title: '性能计划同步', transport: { label: '性能计划', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 个计划、下载 ${p.counts.download} 个计划，${p.counts.conflictCount} 个需核对；两端共 ${p.counts.excludedExecutions} 条执行历史及报告工件不复制。\n按计划名称匹配，同步说明、线程组顺序、负载、请求、断言、质量阈值及内联 CSV。现有计划状态保留各端，更新递增本端版本；新计划为草稿。同步不会提交压测，已提交任务继续使用冻结配置，现有待执行计划后续提交时会使用同步后的定义。目标 URL（包括 localhost）、请求内容及 CSV 会发送到上述账号服务，请核对业务信息和负载。目标运行器、本地目标授权、执行历史和报告工件不复制，删除不传播。最多 100 个计划，资料最多 3 MiB。`,
    unbindDetail: '两端性能计划均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时同名不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间计划再次变化时停止更新，请重新核对。选择版本不会提交压测；状态与已有执行快照保持。`,
  })
}
module.exports = { installPerformanceSync }
