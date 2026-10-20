package io.testhub.platform.mobileautomation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.ProjectFileSyncService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Independently opted-in references to verified project files; never installs or starts an application. */
@Service
public class MobileApplicationFileSyncService {
    public static final int MAX_BYTES=1024*1024;
    private static final Set<String> WRITERS=Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD","TESTER");
    private final AccessService access;
    private final MobileAutomationService mobile;
    private final ProjectFileSyncService fileSync;
    private final FileObjectService files;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    public MobileApplicationFileSyncService(AccessService access,MobileAutomationService mobile,ProjectFileSyncService fileSync,
            FileObjectService files,JdbcClient jdbc,ObjectMapper json,AuditService audit) {
        this.access=access;this.mobile=mobile;this.fileSync=fileSync;this.files=files;this.jdbc=jdbc;this.json=json;this.audit=audit;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor,String project,String expectedUser) {
        access.requireProjectParameterWrite(actor,project,WRITERS);actor(actor,expectedUser);
        var applications=mobile.applications(actor,project).stream().map(a->new Application(a.id(),a.name(),a.platform(),a.version(),a.enabled(),a.appFileId())).sorted(java.util.Comparator.comparing(Application::id)).toList();
        if(applications.size()>100)throw conflict("移动应用超过 100 个，请先整理；不会截断同步");
        var inventory=fileSync.snapshot(actor,project,actor.userId());
        String revision=hash(encoded(List.of(applications,inventory.items())));
        var result=new Snapshot(1,"MOBILE_APPLICATION_FILE_SYNC",project,actor.userId(),revision,applications,inventory.items());
        if(encoded(result).length>MAX_BYTES)throw conflict("应用文件索引超过 1 MiB，暂停同步");
        return result;
    }
    public Request decode(byte[] body) {
        if(body==null||body.length==0)throw invalid("应用文件关联请求不能为空");
        if(body.length>MAX_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"应用文件关联请求不能超过 1 MiB");
        try {
            var request=json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).<Request>readValue(body);
            if(request==null)throw invalid("应用文件关联请求不能为空");return request;
        }catch(java.io.IOException e){throw invalid("应用文件关联 JSON 不合法或含有不支持的字段");}
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null)throw invalid("应用文件关联请求不能为空");actor(actor,request.expectedUserId());
        if(!uuid(request.requestKey())||!digest(request.expectedRevision())||!id(request.applicationId())||request.expectedVersion()<1
                ||(request.expectedFileId()!=null&&!id(request.expectedFileId()))||!id(request.fileId())||!digest(request.sha256())
                ||request.size()<1||request.size()>ProjectFileSyncService.MAX_FILE)throw invalid("应用文件关联标识、版本、大小或摘要无效");
        var payload=encoded(request);if(payload.length>MAX_BYTES)throw invalid("应用文件关联请求过大");
        String payloadHash=hash(payload);mobile.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM mobile_application_file_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k")
                .param("p",project).param("u",actor.userId()).param("k",request.requestKey()).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash))throw conflict("该请求标识已用于其他应用文件关联");
            if(mobile.applications(actor,project).stream().noneMatch(a->a.id().equals(request.applicationId())))throw conflict("已确认的应用已删除，请人工核对；不会重建");
            verify(actor,project,request);
            try{return json.readValue(prior.get().getValue(),Receipt.class);}catch(java.io.IOException e){throw new IllegalStateException("应用文件关联回执损坏",e);}
        }
        var before=snapshot(actor,project,actor.userId());
        if(!before.revision().equals(request.expectedRevision()))throw conflict("应用或文件索引已变化，请重新同步并核对");
        var app=before.applications().stream().filter(a->a.id().equals(request.applicationId())).findFirst().orElseThrow(()->conflict("目标应用不存在，请先同步移动端资料"));
        if(app.version()!=request.expectedVersion()||!Objects.equals(app.fileId(),request.expectedFileId()))throw conflict("目标应用文件关联已变化，请重新核对");
        var file=before.files().stream().filter(f->f.id().equals(request.fileId())).findFirst().orElseThrow(()->conflict("目标文件缺失，请先完成项目文件同步"));
        if(file.size()!=request.size()||!file.sha256().equals(request.sha256()))throw conflict("目标文件与已确认的内容摘要不同");
        verify(actor,project,request);
        int version=app.version();
        if(!request.fileId().equals(app.fileId())) {
            int changed=jdbc.sql("UPDATE mobile_automation_application SET app_file_id=:file,version=version+1,updated_by=:u,updated_at=CURRENT_TIMESTAMP(3) WHERE project_id=:p AND id=:a AND version=:v")
                    .param("file",request.fileId()).param("u",actor.userId()).param("p",project).param("a",app.id()).param("v",app.version()).update();
            if(changed!=1)throw conflict("目标应用已变化，请重新核对");version++;
        }
        var result=new Receipt(request.requestKey(),app.id(),request.fileId(),request.size(),request.sha256(),version);
        jdbc.sql("INSERT INTO mobile_application_file_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:u,:k,:h,:r)")
                .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("u",actor.userId())
                .param("k",request.requestKey()).param("h",payloadHash).param("r",new String(encoded(result),StandardCharsets.UTF_8)).update();
        audit.record(actor,context.organizationId(),project,"MOBILE_APPLICATION_FILE_SYNCED","MOBILE_AUTOMATION_APPLICATION",app.id(),Map.of("fileId",request.fileId(),"sizeBytes",request.size(),"sha256",request.sha256()));
        return result;
    }
    private void verify(AuthenticatedPrincipal actor,String project,Request request) {
        var metadata=files.metadata(actor,project,request.fileId());
        if(metadata.sizeBytes()!=request.size())throw conflict("目标文件大小已变化，请核对文件存储");
        try(var input=files.download(actor,project,request.fileId())) {
            var digest=MessageDigest.getInstance("SHA-256");long count=0;int n;byte[] block=new byte[64*1024];
            while((n=input.read(block))!=-1){count+=n;if(count>request.size())throw conflict("目标文件大小不符，关联未修改");digest.update(block,0,n);}
            if(count!=request.size()||!HexFormat.of().formatHex(digest.digest()).equals(request.sha256()))throw conflict("目标文件损坏或摘要不符，关联未修改");
        }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"目标应用文件读取失败，关联未修改；请检查存储",e);}
    }
    private byte[] encoded(Object value){try{return json.writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("应用文件关联无法序列化",e);}}
    private String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private boolean id(String value){return value!=null&&value.matches("[A-Z0-9]{26}");}
    private boolean digest(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private boolean uuid(String value){try{return value!=null&&value.equals(UUID.fromString(value).toString());}catch(IllegalArgumentException e){return false;}}
    private void actor(AuthenticatedPrincipal actor,String expected){if(!actor.userId().equals(expected))throw conflict("登录账号已变化，应用文件关联同步已停止");}
    private ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Application(String id,String name,String platform,int version,boolean enabled,String fileId) { }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Application> applications,List<ProjectFileSyncService.Item> files) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,String applicationId,int expectedVersion,String expectedFileId,String fileId,long size,String sha256) { }
    public record Receipt(String requestKey,String applicationId,String fileId,long size,String sha256,int version) { }
}
