'use strict'
const { MockSync, MockSyncStore } = require('./mock-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function describe(item) {
  return `${item.serverName}${item.ruleName ? ' / ' + item.ruleName : '（服务）'}\n${item.rule ? JSON.stringify(item.rule, null, 2) : '服务容器，不含公开访问配置'}`
}
function installMockSync(options) {
  return installProjectSync(options, {
    Client: MockSync, Store: MockSyncStore, channel: 'mock-sync', page: 'mock-sync.html', filename: 'mock-sync.bin', title: 'Mock 服务与规则同步',
    transport: { label: 'Mock 服务与规则', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 项、下载 ${p.counts.download} 项，${p.counts.conflictCount} 项需要核对；两端共 ${p.counts.excludedPublicKeys} 个公开密钥、${p.counts.excludedShares} 条分享不复制。\n绑定后自动同步服务名称及规则匹配、响应定义。按服务名和规则名匹配。公开密钥、分享、智能示例来源和启停保留各端；新服务及新规则停用。删除不传播。已启用的现有规则未来会使用更新后的定义。请求条件、响应头和正文会发送到上述账号服务，请核对其中的业务信息。`,
    unbindDetail: '两端已有 Mock 资料均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时同名不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${describe(c.local)}\n网页：${describe(c.remote)}\n确认期间规则再次变化时会停止更新，请重新核对。`,
  })
}
module.exports = { installMockSync }
