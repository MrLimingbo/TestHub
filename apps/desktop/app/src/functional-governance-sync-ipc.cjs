'use strict'
const { FunctionalGovernanceSync, FunctionalGovernanceSyncStore } = require('./functional-governance-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installFunctionalGovernanceSync(options) {
  return installProjectSync(options, {
    Client: FunctionalGovernanceSync, Store: FunctionalGovernanceSyncStore, channel: 'functional-governance-sync', page: 'functional-governance-sync.html',
    filename: 'functional-governance-sync.bin', title: '治理定义同步', transport: { label: '治理定义', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条定义、下载 ${p.counts.download} 条定义，${p.counts.conflictCount} 条需核对；两端共 ${p.counts.excludedBindings} 个资产标签关联不复制。\n同步治理目录结构与排序、标签名称与颜色、需求/用例/计划的自定义字段名称、类型、必填及有序选项。标签按名称匹配，字段按资产类型和不可变字段标识匹配；目录按完整路径匹配，独立于字典。已有 ID、创建人与创建时间保留各端。字段填写值、资产关联、执行、文件、缺陷及历史不同步。定义内容会发送到上述账号服务，请核对业务信息。含本端项目链接的定义暂停同步。删除不传播，标签与目录改名按新名称或路径处理。每类最多 100 条，目录最多 16 层，每字段最多 100 个选项，定义最多 3 MiB。`,
    unbindDetail: '两端治理定义均保留。未确认的请求不再重试；若结果不确定，请先核对两端。重新绑定时内容不同需要重新选择。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local, null, 2)}\n网页：${JSON.stringify(c.remote, null, 2)}\n确认期间资料再次变化时停止更新，请重新核对。选择版本只更新治理定义，资产关联和创建信息保持各端。`,
  })
}
module.exports = { installFunctionalGovernanceSync }
