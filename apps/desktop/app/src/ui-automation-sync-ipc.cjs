'use strict'
const { UiAutomationSync, UiAutomationSyncStore } = require('./ui-automation-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installUiAutomationSync(options) {
  return installProjectSync(options, {
    Client: UiAutomationSync, Store: UiAutomationSyncStore, channel: 'ui-automation-sync', page: 'ui-automation-sync.html',
    filename: 'ui-automation-sync.bin', title: 'Web UI 资料同步', transport: { label: 'Web UI 资料', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条资料、下载 ${p.counts.download} 条资料，${p.counts.conflictCount} 条需核对；两端共 ${p.counts.excludedExecutions} 条执行历史及截图、视频、Trace 不复制。\n按类型及名称匹配，同步浏览器定义、页面对象、场景变量/有序步骤和定时计划定义。引用在目标重映射；关联功能测试计划按编号匹配，缺失需先迁移。浏览器配置和定时计划的启停保留各端，新建均停用；更新递增浏览器及场景本端版本。已有启用计划未来会使用更新后的定义；已提交任务使用冻结配置。URL（含 localhost）、变量及步骤内容会发送到上述账号服务，请核对业务信息。目标授权、运行器、历史及工件不复制，不自动提交执行，删除不传播。每类最多 100 条，资料最多 3 MiB。`,
    unbindDetail: '两端 Web UI 资料均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时同名不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。选择版本不会提交执行；启停与已有执行快照保持。`,
  })
}
module.exports = { installUiAutomationSync }
