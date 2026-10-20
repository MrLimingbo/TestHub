'use strict'
const {MobileApplicationFileSync,MobileApplicationFileSyncStore}=require('./mobile-application-file-sync.cjs')
const {installProjectSync}=require('./project-sync-ipc.cjs')
function installMobileApplicationFileSync(options){return installProjectSync(options,{
 Client:MobileApplicationFileSync,Store:MobileApplicationFileSyncStore,channel:'mobile-application-file-sync',page:'mobile-application-file-sync.html',filename:'mobile-application-file-sync.bin',title:'移动应用文件关联同步',transport:{label:'应用文件关联',limit:1024*1024,timeoutMs:120000},
 preview:(client,value,server)=>client.preview(value?.sourceId,value?.targetId,server,value?.fileAssociationsConfirmed),
 planDetail:p=>`服务：${p.binding.server}\n首次更新网页 ${p.counts.upload} 个应用关联、更新本地 ${p.counts.download} 个，${p.counts.conflictCount} 个需核对。\n必须先确认相同账号、服务及项目的项目文件同步和移动端资料。文件原字节由该文件绑定传输，内容可能敏感；这里只将应用关联到目标端已配对的文件，不以名称猜测文件 ID。继承文件单个 20 MiB、项目 1000 个以及应用 100 个限制。保留启停、应用标识及版本标签，递增目标本端编辑版本；不同关联需选择版本。文件或应用缺失、清空关联均暂停核对，不删除、不重建、不自动清空，不安装应用或触发设备执行。`,
 unbindDetail:'两端应用文件及关联保留；未确认请求停止重试。重新绑定需核对已有不同关联。',identifier:v=>v?.key,conflictId:c=>c.key,canResolve:c=>Boolean(c?.canResolve),
 conflictDetail:c=>`本地：${JSON.stringify(c.local,null,2)}\n网页：${JSON.stringify(c.remote,null,2)}\n仅更改另一端的应用文件关联，不替换文件、不安装应用；确认期间应用或文件变化时停止。`,
})}
module.exports={installMobileApplicationFileSync}
