'use strict'
const { NotificationSync, NotificationSyncStore } = require('./notification-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function describe(item) {
  return `${item.name} · ${item.channel} · ${item.eventCode}\n主题：${item.subjectTemplate}\n正文：${item.bodyTemplate}`
}
function installNotificationSync(options) {
  return installProjectSync(options, {
    Client: NotificationSync, Store: NotificationSyncStore, channel: 'notification-sync', page: 'notification-sync.html', filename: 'notification-sync.bin', title: '通知模板同步',
    transport: { label: '通知模板', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 项、下载 ${p.counts.download} 项，${p.counts.conflictCount} 项需要核对；两端共 ${p.counts.excludedTargets} 个接收地址、${p.counts.excludedSecrets} 个密钥不复制。\n绑定后自动同步模板名称、事件、主题和正文。现有模板的通道、地址、密钥与启停保留；新模板停用且没有投递配置。通道或名称大小写不同需在各端手动统一。删除、投递历史、网络授权不复制，同步本身不发送通知，已启用模板未来会按本端配置使用同步后的定义。模板内容会发送到上述账号服务，请核对其中的业务信息。`,
    unbindDetail: '两端已有模板与投递配置均保留。未确认的同步请求不再重试；若结果不确定，请先核对两端。重新绑定按模板名称匹配，同名不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${describe(c.local)}\n网页：${describe(c.remote)}\n确认期间模板再次变化时会停止更新，请重新核对。`,
  })
}
module.exports = { installNotificationSync }
