'use strict'
const { TaskSync, TaskSyncStore } = require('./task-sync.cjs')
const { installProjectSync, syncRequest } = require('./project-sync-ipc.cjs')
function installTaskSync(options) {
  return installProjectSync(options, {
    Client: TaskSync, Store: TaskSyncStore, channel: 'task-sync', page: 'task-sync.html', filename: 'task-sync.bin', title: '任务登记同步',
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.localRows.length} 条、下载 ${p.remoteRows.length} 条登记；此后这两个项目的新建与修改会自动双向同步。同名登记分别保留，不自动合并。两端同时修改时保留冲突，等待你选择。状态和进度按登记保存，不会启动任何测试。本轮只同步手工任务登记，不同步真实执行、文件或其他模块。`,
    unbindDetail: '两端已有登记均保留。未发送的同步请求不再重试；若其结果不确定，请先刷新两端核对。重新绑定会把现有登记作为独立资料合并，可能新增同名登记。',
    identifier: value => value?.localId, conflictId: item => item.localId, canResolve: item => Boolean(item?.local && item.remote),
    conflictDetail: c => `本地：${c.local.name} / ${c.local.status} / ${c.local.progress}%\n网页：${c.remote.name} / ${c.remote.status} / ${c.remote.progress}%\n确认期间资料再次变化时会停止本次更新，请重新核对。`,
  })
}
module.exports = { installTaskSync, syncRequest }
