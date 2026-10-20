'use strict'
const {GrpcDescriptorSync,GrpcDescriptorSyncStore}=require('./grpc-descriptor-sync.cjs')
const {installProjectSync}=require('./project-sync-ipc.cjs')
function installGrpcDescriptorSync(options){return installProjectSync(options,{
 Client:GrpcDescriptorSync,Store:GrpcDescriptorSyncStore,channel:'grpc-descriptor-sync',page:'grpc-descriptor-sync.html',filename:'grpc-descriptor-sync.bin',title:'gRPC 描述文件同步',transport:{label:'gRPC 描述文件',limit:2*1024*1024,timeoutMs:120000},
 preview:(client,value,server)=>client.preview(value?.sourceId,value?.targetId,server,value?.descriptorsConfirmed),
 planDetail:p=>`服务：${p.binding.server}\n首次上传 ${p.counts.upload} 个、下载 ${p.counts.download} 个，${p.counts.pairs.length} 对相同名称及摘要需校验字节，${p.counts.conflictCount} 个需人工核对。仅传编译后的描述文件原字节，可能包含业务接口定义、SourceCodeInfo 注释及自定义选项；不额外复制原始 .proto 源文件、账号认证配置或调试历史，不保证文件内容已经脱敏。最多 20 个、单个 1 MiB、合计 4 MiB。已有描述符不可覆盖，同名不同内容请以新名称导入；删除或重建暂停核对。接口用例仍由单独的接口设计同步绑定传输，仅映射已核对描述符的两端 ID；不会发起网络调用。`,
 unbindDetail:'两端描述符与用例均保留；未确认复制停止重试。接口用例的描述符引用将暂停，重新绑定需核对已有文件。',identifier:v=>v?.key,conflictId:c=>c.key,canResolve:()=>false,conflictDetail:c=>c.reason,
})}
module.exports={installGrpcDescriptorSync}
