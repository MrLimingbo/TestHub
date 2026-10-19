'use strict'
const { UiAutomationArtifactSync, UiAutomationArtifactSyncStore } = require('./ui-automation-artifact-sync.cjs')
const { installProjectSync } = require('./project-sync-ipc.cjs')
function installUiAutomationArtifactSync(options) { return installProjectSync(options, {
  Client:UiAutomationArtifactSync,Store:UiAutomationArtifactSyncStore,channel:'ui-automation-artifact-sync',page:'ui-automation-artifact-sync.html',filename:'ui-automation-artifact-sync.bin',title:'Web UI 报告产物同步',
  transport:{label:'原始工件',limit:4*1024*1024,timeoutMs:120000,allowEmpty:true,maxBytes:25*1024*1024},
  preview:(client,value,server)=>client.preview(value?.sourceId,value?.targetId,server,value?.rawFilesConfirmed),
  planDetail:p=>`服务：${p.binding.server}\n首次上传 ${p.counts.upload} 个原始产物、下载 ${p.counts.download} 个，${p.counts.conflictCount} 个需核对。\n截图、Trace、视频包含原始页面内容，可能含账号、输入、响应、访问地址和敏感数据；原样发送到上述账号服务，不脱敏。历史报告绑定不会自动开启它。两端必须已有对应的 Web UI 执行历史，缺少时暂停该文件。单文件最多 25 MiB、每项目最多 2000 个、每轮最多 20 个；大小和 SHA-256 校验，不覆盖、不自动重建、不传播删除，不触发执行。`,
  unbindDetail:'两端已有原始工件保留；未确认的原请求停止重试。重新绑定时按原始来源及摘要核对，不覆盖不同内容。',
  identifier:v=>v?.key,conflictId:v=>v.key,canResolve:()=>false,conflictDetail:()=>'',
}) }
module.exports={installUiAutomationArtifactSync}
