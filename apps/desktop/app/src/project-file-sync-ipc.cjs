'use strict'
const { ProjectFileSync, ProjectFileSyncStore } = require('./project-file-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installProjectFileSync(options) { return installProjectSync(options, {
  Client: ProjectFileSync, Store: ProjectFileSyncStore, channel: 'project-file-sync', page: 'project-file-sync.html',
  filename: 'project-file-sync.bin', title: '项目文件同步', transport: { label: '项目文件', limit: 1024 * 1024, timeoutMs: 120000 },
  preview: (client, value, server) => value?.mode === 'files'
    ? client.references(value.sourceId, value.targetId, server)
    : client.preview(value?.sourceId, value?.targetId, server, value?.filePairs),
  planDetail: plan => `服务：${plan.binding.server}\n已确认文件对应：${plan.binding.pairs.length} 对。首次上传 ${plan.counts.upload} 个、下载 ${plan.counts.download} 个，${plan.counts.conflictCount} 个同来源文件需人工核对。两端已有文件须逐一确认；内容摘要不同不能配对。同名独立文件不会覆盖。删除或重建已同步文件会暂停该文件，不跨端删除。每项目最多 1000 个文件，单个文件最多 20 MiB。`,
  unbindDetail: '两端文件均保留。解除后重新绑定时，已有文件需要重新确认对应；未配对文件可能作为独立文件复制，请核对后再确认。',
  identifier: value => value?.key, conflictId: conflict => conflict.key, canResolve: () => false,
  conflictDetail: conflict => conflict.reason,
}) }
module.exports = { installProjectFileSync }
