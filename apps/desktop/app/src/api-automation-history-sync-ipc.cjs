'use strict'
const { ApiAutomationHistorySync, ApiAutomationHistorySyncStore } = require('./api-automation-history-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installApiAutomationHistorySync(options) {
  return installProjectSync(options, {
    Client: ApiAutomationHistorySync, Store: ApiAutomationHistorySyncStore, channel: 'api-automation-history-sync',
    page: 'api-automation-history-sync.html', filename: 'api-automation-history-sync.bin', title: '接口自动化执行历史同步',
    transport: { label: '接口自动化执行历史', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条历史报告、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n同步已结束执行的只读脱敏报告，包括原场景名称、版本、结果、耗时、时间、来源项目／执行标识及清单摘要。这些报告会发送到上述账号服务。不会发送请求地址、正文、响应正文、变量值、密钥或日志。运行中的任务由原执行端继续处理；原端后续收到的结果会更新对应副本。历史不依赖当前场景，不以目标端场景版本替代来源版本。最多 1000 条来源历史，单条 1 MiB、每批最多 20 条和 4 MiB；结果明细沿用现有报告的每类 300 条范围，汇总保留完整计数。不同副本或历史缺失暂停核对；不删除、重建或重新执行。`,
    unbindDetail: '两端原有执行和只读历史保留。未确认的请求不再重试；重新绑定时会按原来源标识核对，避免重复复制。',
    identifier: v => v?.key, conflictId: item => item.key, canResolve: () => false, conflictDetail: () => '',
  })
}
module.exports = { installApiAutomationHistorySync }
