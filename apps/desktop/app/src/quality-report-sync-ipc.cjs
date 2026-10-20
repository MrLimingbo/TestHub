'use strict'
const { QualityReportSync, QualityReportSyncStore } = require('./quality-report-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installQualityReportSync(options) {
  return installProjectSync(options, { Client: QualityReportSync, Store: QualityReportSyncStore, channel: 'quality-report-sync', page: 'quality-report-sync.html', filename: 'quality-report-sync.bin', title: '质量报告计划同步', transport: { label: '质量报告计划', limit: 4 * 1024 * 1024, timeoutMs: 120000 },
    preview: (c, v, server) => c.preview(v?.sourceId, v?.targetId, server, v?.projectLinks, v?.confirmed),
    planDetail: p => `服务：${p.binding.server}\n首次上传 ${p.counts.upload} 项、下载 ${p.counts.download} 项，${p.counts.conflictCount} 项需核对。\n已明确配对 ${p.binding.projectLinks.length} 组项目：\n${p.projectDetail}\n只同步名称、项目范围、Cron、时区和统计天数。新副本及收到配置变更的目标计划先停用，请核对后在目标手动启用；没有配置变化时保留本端启用状态。不复制运行历史、统计结果、通知凭据，不生成报告、测试任务或投递通知。删除、改名、重建和未配对项目暂停，不自动删除或重建。每组织最多 1000 个计划，每个计划最多 50 个项目，每轮最多 20 项。`,
    unbindDetail: '保留两端计划和运行历史。停止待确认请求；如果其结果不确定，请先核对两端。重新绑定需要重新核对项目对应关系。', identifier: v => v?.key, conflictId: c => c.key, canResolve: c => Boolean(c?.canResolve),
    conflictDetail: c => `本地：${JSON.stringify(c.local)}\n网页：${JSON.stringify(c.remote)}\n项目标识按已确认的对应关系解释。选择后仅更新目标配置；配置变化的目标先停用，核对后手动启用。已有运行记录保留。` })
}
module.exports = { installQualityReportSync }
