package io.testhub.platform.functional;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Synchronizes mapped project members only; workflow and historical attribution remain endpoint-owned. */
@Service
public class FunctionalDefectPeopleSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalDefectService defects;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public FunctionalDefectPeopleSyncService(AccessService access, FunctionalAssetService assets,
            FunctionalDefectService defects, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.assets = assets; this.defects = defects;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS);
        requireActor(actor, expectedUserId);
        return read(actor, project);
    }

    private Snapshot read(AuthenticatedPrincipal actor, String project) {
        if (jdbc.sql("SELECT COUNT(*) FROM functional_defect WHERE project_id=:p").param("p",project).query(Long.class).single()>100)
            throw invalid("每项目最多同步 100 条缺陷人员");
        var members=members(actor,project);
        var eligible=members.stream().map(Member::id).collect(java.util.stream.Collectors.toSet());
        var rows=jdbc.sql("SELECT id,defect_code,assignee_id,assigned_by,cc_user_ids FROM functional_defect WHERE project_id=:p ORDER BY id")
                .param("p",project).query().listOfRows();
        var items=new ArrayList<Item>();var itemIds=new TreeMap<String,String>();var unavailable=new TreeSet<String>();
        for(var row:rows) {
            var d=new LinkedHashMap<String,Object>();d.put("assigneeId",row.get("assignee_id"));
            var csv=(String)row.get("cc_user_ids");
            d.put("ccUserIds",csv==null||csv.isBlank()?List.of():Arrays.stream(csv.split(",")).distinct().sorted().toList());
            var item=new Item("DEFECT",(String)row.get("defect_code"),d);validate(item);
            if(itemIds.put(key(item),(String)row.get("id"))!=null) throw invalid("缺陷编号大小写重复，请先核对");
            var def=definition(item);
            if((def.assigneeId()!=null&&!eligible.contains(def.assigneeId()))||!eligible.containsAll(def.ccUserIds())) unavailable.add(key(item));
            items.add(item);
        }
        var memberships=jdbc.sql("SELECT id,user_id FROM membership WHERE project_id=:p ORDER BY id").param("p",project).query().listOfRows();
        var snapshot=new Snapshot(1,"FUNCTIONAL_DEFECT_PEOPLE_SYNC",project,actor.userId(),hash(encoded(Map.of("defects",rows,"members",members,"memberships",memberships))),
                items.stream().sorted(Comparator.comparing(this::key)).toList(),itemIds,members,List.copyOf(unavailable));
        if(encoded(snapshot).length>3*1024*1024) throw invalid("缺陷人员快照超过 3 MiB 同步范围");
        return snapshot;
    }

    private List<Member> members(AuthenticatedPrincipal actor,String project) {
        if(jdbc.sql("SELECT COUNT(*) FROM membership WHERE project_id=:p").param("p",project).query(Long.class).single()>100)
            throw invalid("每项目最多支持 100 个成员对应关系");
        var members=jdbc.sql("SELECT DISTINCT u.id,u.account,u.email,u.display_name FROM user_account u WHERE u.enabled=true AND u.deleted_at IS NULL AND (u.id=:a OR EXISTS(SELECT 1 FROM membership m WHERE m.user_id=u.id AND m.project_id=:p)) ORDER BY u.id")
                .param("a",actor.userId()).param("p",project).query((rs,n)->new Member(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))).list();
        if(members.stream().noneMatch(m->m.id().equals(actor.userId()))) throw conflict("登录账号已停用或删除，同步已停止");
        return members;
    }

    // Account edits and member removal must not invalidate eligibility between validation and the write.
    private void lockMembers(AuthenticatedPrincipal actor,String project,Request request) {
        var ids=new TreeSet<String>();ids.add(actor.userId());
        for(var op:request.operations()) {var d=definition(op.item());if(d.assigneeId()!=null)ids.add(d.assigneeId());ids.addAll(d.ccUserIds());}
        if(ids.size()>101) throw invalid("同步人员超出项目成员数量限制");
        var existing=jdbc.sql("SELECT user_id FROM membership WHERE project_id=:p ORDER BY user_id").param("p",project).query(String.class).list();
        if(existing.size()>100) throw invalid("每项目最多支持 100 个成员对应关系");ids.addAll(existing);
        jdbc.sql("SELECT id FROM user_account WHERE id IN (:ids) ORDER BY id FOR UPDATE").param("ids",ids).query(String.class).list();
        jdbc.sql("SELECT id FROM membership WHERE project_id=:p ORDER BY id FOR UPDATE").param("p",project).query(String.class).list();
    }

    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("缺陷人员同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"缺陷人员同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("缺陷人员同步请求不能为空");
        requireActor(actor,request.expectedUserId());String requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")
                ||request.operations()==null||request.operations().isEmpty()||request.operations().size()>100)
            throw invalid("缺陷人员同步版本或操作数量无效");
        var ids=new HashSet<String>();var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item());if(!keys.add(key(op.item()))) throw invalid("同一缺陷人员不能重复提交");
        }
        byte[] payload=encoded(request);
        if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"缺陷人员同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload);assets.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM functional_defect_people_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey)
                .query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他缺陷人员");
            try{return json.readValue(prior.get().getValue(),Receipt.class);}
            catch(java.io.IOException error){throw new IllegalStateException("缺陷人员同步回执损坏",error);}
        }
        lockMembers(actor,project,request);
        var current=read(actor,project);
        if(!current.revision().equals(request.expectedRevision())) throw conflict("目标缺陷人员已变化，请重新同步并核对");
        // Validate the complete batch before writing; this module never creates a missing defect.
        for(var op:request.operations()) if(!current.itemIds().containsKey(key(op.item())))
            throw conflict("目标缺陷编号缺失，请先同步缺陷基本资料并核对");
        var eligible=current.members().stream().map(Member::id).collect(java.util.stream.Collectors.toSet());
        for(var op:request.operations()) {
            var d=definition(op.item());
            if(current.unavailable().contains(key(op.item()))) throw conflict("缺陷现有人员已不可用，请先在缺陷编辑页面核对");
            if((d.assigneeId()!=null&&!eligible.contains(d.assigneeId()))||!eligible.containsAll(d.ccUserIds())) throw conflict("同步人员不是目标项目已有且可用的成员");
        }
        for(var op:request.operations()) if(!current.items().contains(op.item())) {
            var d=definition(op.item());
            defects.updatePeopleFromSync(actor,project,current.itemIds().get(key(op.item())),d.assigneeId(),d.ccUserIds());
        }
        var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item(),current.itemIds().get(key(op.item())))).toList());
        jdbc.sql("INSERT INTO functional_defect_people_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId())
                .param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(actor,context.organizationId(),project,"FUNCTIONAL_DEFECT_PEOPLE_SYNCED","FUNCTIONAL_DEFECT_PEOPLE_SYNC",requestKey,Map.of("defects",request.operations().size()));
        return receipt;
    }

    private void validate(Item item) {
        if(item==null||!"DEFECT".equals(item.kind())||item.name()==null||!item.name().matches("[A-Z][A-Z0-9_-]{1,39}")||item.definition()==null)
            throw invalid("缺陷类型或编号无效");
        var d=definition(item);
        if((d.assigneeId()!=null&&!ulid(d.assigneeId()))||d.ccUserIds()==null||d.ccUserIds().size()>100
                ||d.ccUserIds().stream().anyMatch(v->!ulid(v))||!d.ccUserIds().equals(d.ccUserIds().stream().distinct().sorted().toList()))
            throw invalid("缺陷人员标识无效，抄送人员须去重并排序");
        if(!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("缺陷人员字段无效或尚未规范化");
    }
    private boolean ulid(String value){return value!=null&&value.matches("[A-Z0-9]{26}");}
    private Definition definition(Item item){return decodeValue(encoded(item.definition()),Definition.class);}
    private String key(Item item){return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireActor(AuthenticatedPrincipal actor,String expected){if(!actor.userId().equals(expected))throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid("同步请求标识必须是 UUID");return UUID.fromString(value).toString();}
    private <T>T decodeValue(byte[] bytes,Class<T> type){try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException error){throw invalid("缺陷人员同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value){try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException error){throw new IllegalStateException("缺陷人员同步序列化失败",error);}}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,Map<String,String> itemIds,List<Member> members,List<String> unavailable) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Member(String id,String account,String email,String name) { }
    public record Definition(String assigneeId,List<String> ccUserIds) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item,String itemId) { }
    public record Receipt(String requestKey,List<Result> results) { }
}
