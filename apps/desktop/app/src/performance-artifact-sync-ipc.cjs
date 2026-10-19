'use strict'
const { PerformanceArtifactSync, PerformanceArtifactSyncStore } = require('./performance-artifact-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installPerformanceArtifactSync(options) { return installProjectSync(options, {
  Client:PerformanceArtifactSync,Store:PerformanceArtifactSyncStore,channel:'performance-artifact-sync',page:'performance-artifact-sync.html',filename:'performance-artifact-sync.bin',title:'原始 JTL／日志同步',
  transport:{label:'原始工件',limit:4*1024*1024,timeoutMs:120000,allowEmpty:true},
  preview:(client,value,server)=>client.preview(value?.sourceId,value?.targetId,server,value?.rawFilesConfirmed),
  planDetail:p=>`服务：${p.binding.server}\n首次上传 ${p.counts.upload} 个原始工件、下载 ${p.counts.download} 个，${p.counts.conflictCount} 个需核对。\n这会把原始 JTL／日志的完整字节发送到上述账号服务，可能包含请求地址、参数、请求或响应内容、日志路径及其他敏感信息，不进行脱敏。原始文件只在单独确认此绑定后同步；脱敏报告绑定不会自动开启它。\n只关联两端已有的对应执行历史，缺少历史时暂停该文件；请先完成性能历史同步。每文件最多 20 MiB，每项目最多 2000 个，每轮最多 20 个，剩余下轮继续。校验大小和 SHA-256，不覆盖、不跨端删除，不重建缺失文件，不触发执行。`,
  unbindDetail:'两端已有原始工件保留；未确认的原请求停止重试。重新绑定时按原始来源及摘要核对，不覆盖不同内容。',
  identifier:v=>v?.key,conflictId:v=>v.key,canResolve:()=>false,conflictDetail:()=>'',
}) }
module.exports={installPerformanceArtifactSync}
