'use strict'
const {createApiDebugSync}=require('./api-debug-sync.cjs')
const {installProjectSync}=require('./project-sync-ipc.cjs')
function installApiDebugSync(options,mode){const draft=mode==='replay-drafts',{Client,Store}=createApiDebugSync(mode),channel=draft?'api-debug-replay-sync':'api-debug-history-sync',title=draft?'脱敏重放草稿同步':'接口调试历史同步'
 return installProjectSync(options,{Client,Store,channel,page:channel+'.html',filename:channel+'.bin',title,transport:{label:title,limit:4*1024*1024,timeoutMs:120000},preview:(client,value,server)=>client.preview(value?.sourceId,value?.targetId,server,value?.confirmed),
  planDetail:p=>`服务：${p.binding.server}\n首次上传 ${p.counts.upload} 条、下载 ${p.counts.download} 条，${p.counts.conflictCount} 条需核对。\n${draft?'只传脱敏请求模板，仍可能包含业务请求内容。认证、Cookie、敏感字段转为占位符；环境、项目文件和 gRPC 本端引用需在目标重新选择，非 JSON 正文、GraphQL 查询和 XML 需重新核对。先完成对应历史同步；载入模板不会发送，补齐并勾选核对后手动发送。':'只读摘要保留来源、方法、协议、状态、时间、耗时、响应字节和目标主机；路径、查询值、错误原文、认证、请求及响应正文不传。历史绑定不会自动启用重放草稿同步。'}\n每项目最多 1000 条；原生历史存储估算 8 MiB，副本加密存储 12 MiB；单项最多 1 MiB、每轮最多 20 条。删除、不同副本和来源缺失暂停，不删除、重建或改写；不创建执行或自动请求。`,
  unbindDetail:'保留两端历史、脱敏模板和本地原稿；停止待确认请求。重新绑定需重新核对现状。',identifier:v=>v?.key,conflictId:c=>c.key,canResolve:()=>false,conflictDetail:c=>c.reason,
 })}
module.exports={installApiDebugSync}
