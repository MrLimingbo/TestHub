package io.testhub.platform.apidebug;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTransferFiles;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Copies immutable compiled descriptors; existing references and records are never overwritten. */
@Service
public class GrpcDescriptorSyncService {
    public static final int MAX_BODY=2*1024*1024;
    private static final Set<String> WRITERS=Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD","TESTER");
    private final GrpcDescriptorRepository repository;
    private final GrpcDescriptorTransferService transfer;
    private final AccessService access;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public GrpcDescriptorSyncService(GrpcDescriptorRepository repository,GrpcDescriptorTransferService transfer,AccessService access,AuditService audit,JdbcClient jdbc,ObjectMapper json){this.repository=repository;this.transfer=transfer;this.access=access;this.audit=audit;this.jdbc=jdbc;this.json=json;}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor,String project,String expectedUser){
        access.requireProjectParameterWrite(actor,project,WRITERS);actor(actor,expectedUser);
        var rows=repository.findByProject(project);if(rows.size()>20)throw conflict("描述符超过 20 个，暂停同步；不会截断资料");
        var items=new ArrayList<Item>();long total=0;var names=new HashSet<String>();
        for(var row:rows){verify(row);if((total+=row.sizeBytes())>4*1024*1024||!names.add(row.name().toLowerCase(Locale.ROOT)))throw conflict("描述符合计超过 4 MiB 或名称大小写重复，请先核对");items.add(item(row));}
        items.sort(Comparator.comparing(Item::id));return new Snapshot(1,"GRPC_DESCRIPTOR_SYNC",project,actor.userId(),hash(encoded(items)),items);
    }
    public byte[] content(AuthenticatedPrincipal actor,String project,String expectedUser,String id){
        access.requireProjectParameterWrite(actor,project,WRITERS);actor(actor,expectedUser);var row=required(project,id);verify(row);return row.descriptorBytes().clone();
    }
    public void validateReference(String project,String id){verify(required(project,id));}
    public Request decode(byte[] body){
        if(body==null||body.length==0)throw invalid("描述符同步请求不能为空");if(body.length>MAX_BODY)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"描述符同步请求不能超过 2 MiB");
        try{var r=json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).<Request>readValue(body);if(r==null)throw invalid("描述符请求不能为空");return r;}
        catch(java.io.IOException e){throw invalid("描述符同步 JSON 不合法或含不支持的字段");}
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request r){
        var scope=access.requireProjectParameterWrite(actor,project,WRITERS);if(r==null)throw invalid("描述符请求不能为空");actor(actor,r.expectedUserId());
        if(!uuid(r.requestKey())||!digest(r.expectedRevision())||!id(r.sourceId())||!id(r.sourceProject())||r.content()==null||r.content().length<1||r.content().length>1024*1024)throw invalid("描述符标识、版本或大小无效");
        transfer.validate(List.of(new GrpcDescriptorTransferService.Item(r.sourceId(),r.name(),r.sha256(),r.content())),new HashSet<>());
        if(!r.name().equals(r.name().trim())||encoded(r).length>MAX_BODY)throw invalid("描述符名称或请求大小无效");
        String payloadHash=hash(encoded(r));repository.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM grpc_descriptor_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k").param("p",project).param("u",actor.userId()).param("k",r.requestKey()).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()){
            if(!prior.get().getKey().equals(payloadHash))throw conflict("该请求标识已用于其他描述符");
            try{var receipt=json.readValue(prior.get().getValue(),Receipt.class);var actual=required(project,receipt.item().id());verify(actual);if(!item(actual).equals(receipt.item()))throw conflict("已确认描述符变化，不会恢复或覆盖");return receipt;}
            catch(java.io.IOException e){throw new IllegalStateException("描述符同步回执损坏",e);}
        }
        var before=snapshot(actor,project,actor.userId());if(!before.revision().equals(r.expectedRevision()))throw conflict("目标描述符已变化，请重新同步");
        var existing=before.items().stream().filter(i->i.name().equalsIgnoreCase(r.name())).findFirst();String targetId;
        if(existing.isPresent()){
            var old=existing.get();if(!old.name().equals(r.name())||!old.sha256().equals(r.sha256())||old.size()!=r.content().length)throw conflict("同名描述符内容不同，不会覆盖；请使用新名称导入");targetId=old.id();
        }else{
            if(before.items().size()>=20||before.items().stream().mapToLong(Item::size).sum()+r.content().length>4*1024*1024)throw conflict("合并后描述符超过 20 个或 4 MiB");
            targetId=transfer.transfer(actor,project,List.of(new GrpcDescriptorTransferService.Item(r.sourceId(),r.name(),r.sha256(),r.content()))).get(r.sourceId());
        }
        var receipt=new Receipt(r.requestKey(),r.sourceProject(),r.sourceId(),item(required(project,targetId)));
        jdbc.sql("INSERT INTO grpc_descriptor_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:u,:k,:h,:r)").param("id",UlidCreator.getUlid().toString()).param("o",scope.organizationId()).param("p",project).param("u",actor.userId()).param("k",r.requestKey()).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(actor,scope.organizationId(),project,"GRPC_DESCRIPTOR_SYNCED","GRPC_DESCRIPTOR",targetId,Map.of("sizeBytes",r.content().length,"sha256",r.sha256()));return receipt;
    }
    private GrpcDescriptor required(String project,String id){if(!id(id))throw invalid("描述符标识无效");return repository.findById(project,id).orElseThrow(()->conflict("描述符缺失或已删除，不会自动重建，请核对"));}
    private void verify(GrpcDescriptor row){
        try{transfer.validate(List.of(new GrpcDescriptorTransferService.Item(row.id(),row.name(),row.descriptorHash(),row.descriptorBytes())),new HashSet<>());var set=com.google.protobuf.DescriptorProtos.FileDescriptorSet.parseFrom(row.descriptorBytes());if(row.sizeBytes()!=row.descriptorBytes().length||row.sourceCount()!=set.getFileCount()||row.serviceCount()!=GrpcDescriptorRuntime.services(set.getFileList()).size())throw conflict("描述符计数或大小损坏，暂停同步");}
        catch(java.io.IOException|GrpcDescriptorRuntime.InvalidDescriptorException|ResponseStatusException e){throw conflict("描述符字节、摘要或依赖无效，暂停同步；原资料保留");}
    }
    private Item item(GrpcDescriptor row){return new Item(row.id(),row.name(),row.descriptorHash(),row.sizeBytes(),row.serviceCount(),row.sourceCount());}
    private byte[] encoded(Object v){try{return json.writeValueAsBytes(v);}catch(java.io.IOException e){throw new IllegalStateException("描述符无法序列化",e);}}
    private String hash(byte[] v){return FunctionalTransferFiles.sha256(v);}
    private boolean id(String v){return v!=null&&v.matches("[0-9A-HJKMNP-TV-Z]{26}");}
    private boolean digest(String v){return v!=null&&v.matches("[a-f0-9]{64}");}
    private boolean uuid(String v){try{return v!=null&&v.equals(UUID.fromString(v).toString());}catch(IllegalArgumentException e){return false;}}
    private void actor(AuthenticatedPrincipal a,String expected){if(!a.userId().equals(expected))throw conflict("登录账号变化，描述符同步已停止");}
    private ResponseStatusException invalid(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
    public record Item(String id,String name,String sha256,int size,int serviceCount,int sourceCount){}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items){}
    public record Request(String requestKey,String expectedUserId,String expectedRevision,String sourceProject,String sourceId,String name,String sha256,byte[] content){public Request{content=content==null?null:content.clone();}@Override public byte[] content(){return content==null?null:content.clone();}}
    public record Receipt(String requestKey,String sourceProject,String sourceId,Item item){}
}
