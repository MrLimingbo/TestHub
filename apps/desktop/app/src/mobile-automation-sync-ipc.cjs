'use strict'
const { MobileAutomationSync, MobileAutomationSyncStore } = require('./mobile-automation-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installMobileAutomationSync(options) {
  return installProjectSync(options, {
    Client: MobileAutomationSync, Store: MobileAutomationSyncStore, channel: 'mobile-automation-sync', page: 'mobile-automation-sync.html',
    filename: 'mobile-automation-sync.bin', title: '移动端资料同步', transport: { label: '移动端资料', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条资料、下载 ${p.counts.download} 条资料，${p.counts.conflictCount} 条需核对；两端共 ${p.counts.excludedExecutions} 条执行历史、${p.counts.excludedFiles} 个应用包关联不复制。\n按类型及名称匹配，同步设备能力参数、应用标识/版本、元素定位及场景变量/有序步骤。引用在目标重映射；同名平台不同或 UDID 重复需手动核对。启停与已有应用包关联保留各端，新设备/应用停用且无文件。更新递增设备、应用及场景本端版本。已提交任务使用冻结配置。UDID、能力参数、变量和步骤内容会发送到上述账号服务，请核对业务信息。不复制应用安装文件、历史、截图、Appium 配置或授权，不提交执行，删除不传播。每类最多 100 条，资料最多 3 MiB。`,
    unbindDetail: '两端 移动端资料均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时同名不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。选择版本不会提交执行；启停、应用包关联与已有执行快照保持。`,
  })
}
module.exports = { installMobileAutomationSync }
