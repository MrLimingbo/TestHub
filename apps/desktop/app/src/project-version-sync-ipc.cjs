'use strict'
const { ProjectVersionSync, ProjectVersionSyncStore } = require('./project-version-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installProjectVersionSync(options) {
  return installProjectSync(options, {
    Client: ProjectVersionSync, Store: ProjectVersionSyncStore, channel: 'project-version-sync', page: 'project-version-sync.html',
    filename: 'project-version-sync.bin', title: '版本与构建定义同步', transport: { label: '版本与构建定义', limit: 4 * 1024 * 1024 },
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条版本、下载 ${p.counts.download} 条版本，${p.counts.conflictCount} 条需核对。\n按版本号匹配，同步完整有序构建列表、计划发布日期和描述；支持清空。新版本为未开始，实际发布日期为空。已有发布状态、实际发布日期、创建信息和本端标识保留。改名、删除或同名重建需核对，删除不传播。已确认且两端完好的项目文件下载链接会映射；其他项目链接或失效文件暂停相关版本。每项目最多 100 个版本，每版本 50 个构建，资料不超过 3 MiB。资料内容会发送到上述账号服务，请核对业务信息。`,
    unbindDetail: '两端版本资料保留。未确认的请求不再重试；结果不确定时请先核对。',
    identifier: value => value?.key, conflictId: item => item.key, canResolve: item => Boolean(item?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local,null,2)}\n网页：${JSON.stringify(c.remote,null,2)}\n只更新版本定义；发布状态、实际发布日期与创建信息保留各端。确认期间资料再次变化会停止更新。`,
  })
}
module.exports = { installProjectVersionSync }
