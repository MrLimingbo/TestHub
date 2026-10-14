'use strict'
const { ApiAutomationTriggerSync, ApiAutomationTriggerSyncStore } = require('./api-automation-trigger-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installApiAutomationTriggerSync(options) {
  return installProjectSync(options, {
    Client: ApiAutomationTriggerSync, Store: ApiAutomationTriggerSyncStore, channel: 'api-automation-trigger-sync',
    page: 'api-automation-trigger-sync.html', filename: 'api-automation-trigger-sync.bin', title: '接口自动化触发配置同步',
    transport: { label: '接口自动化触发配置', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条触发配置、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n按类型和名称匹配，同步计划的场景／配置集名称、Cron、时区、超时和失败阈值，以及 Webhook 的名称和场景。这些配置会发送到上述服务。场景与配置集须先在目标准备，引用按目标 ID 重新对应。新副本和收到配置修改的目标触发器一律停用，请在目标核对后再启用；没有配置修改时保留本端启用状态。已有失败计数和执行信息保留，不复制执行历史或创建任务。密钥、签名地址、启用状态不跨端复制；Webhook 新副本先在目标单独生成并保存密钥，原有密钥不替换。删除、重建或重复名称暂停核对。每类最多 200 条、2 MiB，需两端项目管理员权限。`,
    unbindDetail: '两端现有触发器和本端密钥保留。未确认请求不再重试；如结果不确定请核对两端。重新绑定时同名不同内容需重新选择。',
    identifier: v => v?.key, conflictId: c => c.key, canResolve: c => Boolean(c?.canResolve),
    conflictDetail: c => `本地：${c.local?.name || '缺失'}\n网页：${c.remote?.name || '缺失'}\n请在同步页核对完整配置。选择后暂停收到修改的目标触发器，已有本端密钥和执行信息保留；核对后再在目标启用。`,
  })
}
module.exports = { installApiAutomationTriggerSync }
