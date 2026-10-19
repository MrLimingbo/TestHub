'use strict'
const { UiAutomationHistorySync, UiAutomationHistorySyncStore } = require('./ui-automation-history-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installUiAutomationHistorySync(options) {
  return installProjectSync(options, {
    Client: UiAutomationHistorySync, Store: UiAutomationHistorySyncStore, channel: 'ui-automation-history-sync',
    page: 'ui-automation-history-sync.html', filename: 'ui-automation-history-sync.bin', title: 'Web UI 执行历史同步',
    transport: { label: 'Web UI 执行历史', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条历史报告、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n同步已结束 Web UI 执行的只读结构化报告，保留来源场景版本、浏览器名称、状态、时间、重试来源及步骤结果汇总。最多 1000 条来源历史，每份 1 MiB、最多 300 步，每批最多 20 条和 4 MiB。不会传输页面地址、选择器、输入内容、变量、错误文本、Runner 身份或截图／Trace／视频；原始产物需要另行绑定确认。不创建或重放任务，缺失或不同副本暂停核对，不删除或自动重建。`,
    unbindDetail: '两端原有执行和只读历史保留。未确认的请求不再重试；重新绑定时会按原来源标识核对，避免重复复制。',
    identifier: v => v?.key, conflictId: item => item.key, canResolve: () => false, conflictDetail: () => '',
  })
}
module.exports = { installUiAutomationHistorySync }
