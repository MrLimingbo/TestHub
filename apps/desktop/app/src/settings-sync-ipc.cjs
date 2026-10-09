'use strict'
const { SettingsSync, SettingsSyncStore } = require('./settings-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function describe(item) {
  return item.kind === 'ENVIRONMENT' ? `${item.environmentCode} · ${item.name}\n基础地址：${item.baseUrl || '未设置'}\n说明：${item.description || '未设置'}`
    : `${item.environmentCode} / ${item.key}\n参数值：${item.value}`
}
function installSettingsSync(options) {
  return installProjectSync(options, {
    Client: SettingsSync, Store: SettingsSyncStore, channel: 'settings-sync', page: 'settings-sync.html', filename: 'settings-sync.bin', title: '项目配置同步',
    transport: { label: '项目配置', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 项、下载 ${p.counts.download} 项，${p.counts.conflictCount} 项需要核对；两端共 ${p.counts.excludedSecrets} 个密钥不复制。\n绑定后自动同步环境编码、名称、基础地址、说明和普通参数。同名不同内容等待选择；不会覆盖密钥，不同步默认环境、启停、删除、测试资产或执行历史。普通参数和基础地址将发送到上述服务，请确认其中没有应当保存为密钥的信息。`,
    unbindDetail: '两端已有环境和参数均保留。未确认的同步请求不再重试；若结果不确定，请先核对两端。重新绑定会按环境编码和参数键匹配，同名不同内容需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${describe(c.local)}\n网页：${describe(c.remote)}\n确认期间配置再次变化时会停止更新，请重新核对。`,
  })
}
module.exports = { installSettingsSync }
