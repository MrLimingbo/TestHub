'use strict'
const { ApiAutomationScenarioSync, ApiAutomationScenarioSyncStore } = require('./api-automation-scenario-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installApiAutomationScenarioSync(options) {
  return installProjectSync(options, {
    Client: ApiAutomationScenarioSync, Store: ApiAutomationScenarioSyncStore, channel: 'api-automation-scenario-sync',
    page: 'api-automation-scenario-sync.html', filename: 'api-automation-scenario-sync.bin', title: '接口自动化场景同步',
    transport: { label: '接口自动化场景', limit: 10 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 个场景、下载 ${p.counts.download} 个场景，${p.counts.conflictCount} 个需核对。\n同步当前场景、脚本、变量以及实际固定引用的历史内容，这些内容会发送到上述账号服务。按场景和引用来源名称匹配，并按历史内容映射本端版本号。缺少的公共步骤请先运行基础资料同步；子场景可同批复制。已有历史和旧引用不改写，新增内容追加修订。为保存引用历史，来源资料可能增加本端版本号，但当前内容保留；两端版本号不保证相同。未配对文件、失效引用或环境暂停相关场景；删除不传播。最多 200 个场景、8 MiB，单个引用来源至多核对 1000 个修订。不会创建执行，不同步执行历史、触发器或未引用的旧修订。`,
    unbindDetail: '两端场景及历史修订保留。未确认请求不再重试；如结果不确定，请核对两端。重新绑定时同名不同内容需重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地场景：${c.local?.name || '缺失'}\n网页场景：${c.remote?.name || '缺失'}\n请在同步页核对两端完整内容。确认后以所选当前内容更新另一端，旧历史和固定引用保留。确认期间场景或来源再次变化时会停止更新。`,
  })
}
module.exports = { installApiAutomationScenarioSync }
