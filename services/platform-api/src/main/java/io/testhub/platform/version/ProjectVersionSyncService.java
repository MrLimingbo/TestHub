package io.testhub.platform.version;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.ProjectFileReferenceGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Portable release definitions preserve each endpoint's lifecycle and creation information. */
@Service
public class ProjectVersionSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final ProjectVersionService versions;
    private final ProjectVersionRepository repository;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectFileReferenceGuard fileLinks;
    public ProjectVersionSyncService(AccessService access,ProjectVersionService versions,ProjectVersionRepository repository,
                                     AuditService audit,JdbcClient jdbc,ObjectMapper json,ProjectFileReferenceGuard fileLinks) {
        this.access=access;this.versions=versions;this.repository=repository;this.audit=audit;this.jdbc=jdbc;this.json=json;this.fileLinks=fileLinks;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(actor, project);
    }
    private Snapshot read(AuthenticatedPrincipal actor,String project) {
        if(jdbc.sql("SELECT COUNT(*) FROM project_version WHERE project_id=:p").param("p",project).query(Long.class).single()>100)
            throw invalid("每项目最多同步 100 个版本");
        if(jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(description)+OCTET_LENGTH(build_numbers)),0) FROM project_version WHERE project_id=:p")
                .param("p",project).query(Long.class).single()>SNAPSHOT_BYTES) throw invalid("版本资料超过 3 MiB 同步范围");
        var items=new ArrayList<Item>();var revisions=new ArrayList<RevisionRow>();var itemIds=new TreeMap<String,String>();
        for(var v:versions.list(actor,project)) {
            var i=item("VERSION",v.versionNumber(),new Definition(v.buildNumbers(),v.plannedReleaseDate()==null?null:v.plannedReleaseDate().toString(),v.description()));
            items.add(i);revisions.add(new RevisionRow(v.id(),v.updatedAt().toString(),i));itemIds.put(key(i),v.id());
        }
        validateItems(items);revisions.sort(Comparator.comparing(RevisionRow::id));
        var result=new Snapshot(1,"PROJECT_VERSION_SYNC",project,actor.userId(),hash(encoded(revisions)),
                items.stream().sorted(Comparator.comparing(this::key)).toList(),itemIds);
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("版本资料超过 3 MiB 同步范围");return result;
    }
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("版本同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"版本同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("版本同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>100)
            throw invalid("版本同步版本或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); fileLinks.validate(op.item().definition(),project); if(!keys.add(key(op.item()))) throw invalid("同一版本资料不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"版本同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); repository.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM project_version_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他版本资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("版本同步回执损坏",error); }
        }
        var current=read(actor,project); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标版本资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>(); current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {var old=merged.get(key(op.item())); if(old!=null&&!old.name().equals(op.item().name())) throw conflict("版本号大小写不同，请先在两端统一名称"); merged.put(key(op.item()),op.item());}
        var mergedItems=new ArrayList<>(merged.values()); validateItems(mergedItems);
        if(encoded(mergedItems).length>SNAPSHOT_BYTES) throw invalid("合并后的版本资料超过 3 MiB 同步范围");
        try {
            for(var op:request.operations()) if(!current.items().contains(op.item())) write(actor,project,op.item());
            var written=read(actor,project);
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item(),written.itemIds().get(key(op.item())))).toList());
            jdbc.sql("INSERT INTO project_version_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"PROJECT_VERSION_SYNCED","PROJECT_VERSION_SYNC",requestKey,Map.of("assets",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("版本号与目标数据库唯一约束冲突，请统一名称；整批未写入");}
    }
    private void write(AuthenticatedPrincipal actor,String project,Item item) {
        var d=definition(item,Definition.class);
        var date=d.plannedReleaseDate()==null?null:java.time.LocalDate.parse(d.plannedReleaseDate());
        var old=versions.list(actor,project).stream().filter(v->v.versionNumber().equals(item.name())).findFirst();
        if(old.isPresent()) versions.updateDefinition(actor,project,old.get().id(),
                new ProjectVersionService.DefinitionInput(d.buildNumbers(),date,d.description()));
        else versions.create(actor,project,new ProjectVersionService.VersionInput(item.name(),d.buildNumbers(),"UNSTARTED",date,null,d.description()));
    }
    private void validateItems(List<Item> items) {
        if(items.size()>100) throw invalid("每项目最多同步 100 个版本");
        var keys=new HashSet<String>();for(var item:items) {validate(item);if(!keys.add(key(item))) throw invalid("版本号存在大小写重复，请先统一名称");}
    }
    private void validate(Item item) {
        if(item==null||!"VERSION".equals(item.kind())||item.definition()==null) throw invalid("版本资料类型无效");
        requireText(item.name(),100);if(item.name().contains("\n")||item.name().contains("\r")) throw invalid("版本号不能含换行符");
        var d=definition(item,Definition.class);
        if(d.buildNumbers()==null||d.buildNumbers().size()>50||new HashSet<>(d.buildNumbers()).size()!=d.buildNumbers().size()) throw invalid("构建号重复或超过 50 个");
        for(var build:d.buildNumbers()) {requireText(build,100);if(build.contains("\n")||build.contains("\r")) throw invalid("构建号不能含换行符");}
        description(d.description(),20000);
        if(d.plannedReleaseDate()!=null) {
            try {
                if(!d.plannedReleaseDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||d.plannedReleaseDate().startsWith("0000")
                    ||!java.time.LocalDate.parse(d.plannedReleaseDate()).toString().equals(d.plannedReleaseDate())) throw new IllegalArgumentException();
            } catch(java.time.DateTimeException|IllegalArgumentException e) {throw invalid("计划发布日期必须为规范日期");}
        }
        if(!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("版本定义字段不合法或尚未规范化");
    }
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireText(String v,int max) {if(v==null||v.isBlank()||!v.equals(v.trim())||v.length()>max) throw invalid("版本号或引用不合法");}
    private String description(String v,int max) {if(v==null||!v.equals(v.trim())||v.length()>max) throw invalid("版本说明不合法");return v;}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    private <T> T definition(Item item,Class<T> type) {return decodeValue(encoded(item.definition()),type);}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("版本同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("版本同步序列化失败",e);}}
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private ResponseStatusException invalid(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,Map<String,String> itemIds) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item,String itemId) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record RevisionRow(String id,String updated,Item item) { }
    public record Definition(List<String> buildNumbers,String plannedReleaseDate,String description) { }
}
