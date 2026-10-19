'use strict'
const { PerformanceHistorySync, PerformanceHistorySyncStore } = require('./performance-history-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installPerformanceHistorySync(options) {
  return installProjectSync(options, {
    Client: PerformanceHistorySync, Store: PerformanceHistorySyncStore, channel: 'performance-history-sync',
    page: 'performance-history-sync.html', filename: 'performance-history-sync.bin', title: '性能执行历史同步',
    transport: { label: '性能执行历史', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条历史报告、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n同步已结束性能执行的只读脱敏报告，包括执行时的计划名称、版本、状态、时间、数值汇总、指标、资源采样和质量阈值。报告将发送到上述账号服务。最多 1000 条来源历史，单条 1 MiB、每批最多 20 条及 4 MiB；指标和资源采样沿用原报告各最多 2000 条范围。不会传输目标地址、请求头或正文、数据集、密钥、Runner 身份、原始日志或 JTL，不创建和改写真实任务，不影响原有统计。运行中任务留在原端，迟到指标由原端更新对应副本。缺失或不同副本暂停核对，不删除或自动重建。`,
    unbindDetail: '两端原有执行和只读历史保留。未确认的请求不再重试；重新绑定时会按原来源标识核对，避免重复复制。',
    identifier: v => v?.key, conflictId: item => item.key, canResolve: () => false, conflictDetail: () => '',
  })
}
module.exports = { installPerformanceHistorySync }
