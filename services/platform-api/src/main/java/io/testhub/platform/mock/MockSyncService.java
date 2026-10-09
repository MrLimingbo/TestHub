package io.testhub.platform.mock;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Public keys, shares, smart examples and enablement remain local to each endpoint. */
@Service
public class MockSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD","TESTER");
    private final AccessService access;
    private final MockRepository repository;
    private final MockService mocks;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public MockSyncService(AccessService access,MockRepository repository,MockService mocks,AuditService audit,JdbcClient jdbc,ObjectMapper json) {
        this.access=access;this.repository=repository;this.mocks=mocks;this.audit=audit;this.jdbc=jdbc;this.json=json;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor,String projectId,String expectedUserId) {
        access.requireProjectParameterWrite(actor,projectId,WRITERS);requireActor(actor,expectedUserId);return read(projectId,actor.userId());
    }
    private Snapshot read(String projectId,String actorId) {
        int serverCount=jdbc.sql("SELECT COUNT(*) FROM mock_server WHERE project_id=:project").param("project",projectId).query(Integer.class).single();
        var ruleCounts=jdbc.sql("SELECT COUNT(*) FROM mock_rule WHERE project_id=:project GROUP BY server_id").param("project",projectId).query(Integer.class).list();
        if(serverCount>20||ruleCounts.stream().anyMatch(n->n>200)) throw invalid("每项目最多同步 20 个 Mock 服务，每服务最多 200 条规则");
        long storedBytes=jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(request_body)+OCTET_LENGTH(response_body)+OCTET_LENGTH(request_query)+OCTET_LENGTH(request_headers)+OCTET_LENGTH(response_headers)),0) FROM mock_rule WHERE project_id=:project").param("project",projectId).query(Long.class).single();
        if(storedBytes>3*1024*1024) throw invalid("Mock 资料超过 3 MiB 同步范围，请缩减资料");
        var servers=jdbc.sql("SELECT id,name,enabled,smart_example_enabled,updated_at FROM mock_server WHERE project_id=:project ORDER BY id")
                .param("project",projectId).query((rs,n)->new ServerRow(rs.getString("id"),rs.getString("name"),rs.getBoolean("enabled"),rs.getBoolean("smart_example_enabled"),rs.getString("updated_at"))).list();
        var rows=new ArrayList<Object>();var items=new ArrayList<Item>();
        for(var server:servers) {
            rows.add(server);items.add(new Item(server.name(),null,null));
            for(var rule:repository.findRules(projectId,server.id()).stream().sorted(Comparator.comparing(MockRule::id)).toList()) {
                var item=new Item(server.name(),rule.name(),new MockService.RuleInput(rule.name(),rule.httpMethod(),rule.path(),rule.priority(),rule.requestQuery(),rule.requestHeaders(),rule.requestBodyMatch(),rule.requestBody(),rule.responseStatus(),rule.responseHeaders(),rule.responseBody()));
                items.add(item);rows.add(new RuleRow(rule.id(),item,rule.enabled(),rule.updatedAt().toString()));
            }
        }
        validateItems(items);
        int shares=jdbc.sql("SELECT COUNT(*) FROM mock_share WHERE project_id=:project").param("project",projectId).query(Integer.class).single();
        var result=new Snapshot(1,"MOCK_SYNC",projectId,actorId,hash(encoded(rows)),items.stream().sorted(Comparator.comparing(this::key)).toList(),servers.size(),shares);
        if(encoded(result).length>3*1024*1024) throw invalid("Mock 资料超过 3 MiB 同步范围，请缩减资料");return result;
    }
    public Request decode(byte[] bytes) {
        if(bytes==null||bytes.length==0) throw invalid("Mock 同步请求不能为空");
        if(bytes.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Mock 同步请求不能超过 4 MiB。");
        try { return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes); }
        catch(java.io.IOException error) { throw invalid("Mock 同步 JSON 格式不合法或包含不支持的字段"); }
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String projectId,Request request) {
        try {
        var project=access.requireProjectParameterWrite(actor,projectId,WRITERS);
        if(request==null) throw invalid("Mock 同步请求不能为空");requireActor(actor,request.expectedUserId());String requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>4020) throw invalid("Mock 同步版本或操作数量无效");
        var ids=new HashSet<String>();var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");validate(op.item());
            if(!keys.add(key(op.item()))) throw invalid("同一 Mock 服务或规则不能重复提交");
        }
        byte[] payload=encoded(request);if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Mock 同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload);repository.lockProject(projectId);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM mock_sync_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key")
                .param("project",projectId).param("user",actor.userId()).param("key",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!payloadHash.equals(prior.get().getKey())) throw conflict("同步请求标识已用于其他 Mock 内容");
            try { return json.readValue(prior.get().getValue(),Receipt.class); }
            catch(java.io.IOException error) { throw new IllegalStateException("Mock 同步回执损坏",error); }
        }
        var current=read(projectId,actor.userId());if(!current.revision().equals(request.expectedRevision())) throw conflict("目标 Mock 资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>();current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {
            var i=op.item();var old=merged.get(key(i));
            if(old!=null&&(!old.serverName().equals(i.serverName())||!Objects.equals(old.ruleName(),i.ruleName()))) throw conflict("Mock 名称大小写不同，请先在两端统一名称");
            merged.put(key(i),i);
        }
        validateItems(new ArrayList<>(merged.values()));
        var servers=new HashMap<String,String>();repository.findServers(projectId).forEach(s->servers.put(fold(s.name()),s.id()));
        // Create all parent servers first, regardless of operation order. Never create active public endpoints.
        for(var op:request.operations()) if(op.item().ruleName()==null&&!servers.containsKey(fold(op.item().serverName()))) {
            var id=UlidCreator.getUlid().toString();repository.insertServer(id,project.organizationId(),projectId,UlidCreator.getUlid().toString(),op.item().serverName(),actor.userId());
            repository.updateServerEnabled(projectId,id,false);servers.put(fold(op.item().serverName()),id);
        }
        for(var op:request.operations()) if(op.item().ruleName()!=null) {
            var item=op.item();String serverId=servers.get(fold(item.serverName()));var d=item.rule();
            var old=repository.findRules(projectId,serverId).stream().filter(r->fold(r.name()).equals(fold(item.ruleName()))).findFirst();
            if(old.isPresent()) repository.updateRuleDefinition(projectId,serverId,old.get().id(),d);
            else {
                String id=UlidCreator.getUlid().toString();repository.insertRule(id,serverId,project.organizationId(),projectId,d.name(),d.httpMethod(),d.path(),d.priority(),text(d.requestQuery()),text(d.requestHeaders()),d.requestBodyMatch(),d.requestBody(),d.responseStatus(),text(d.responseHeaders()),d.responseBody(),actor.userId());
                repository.updateRuleEnabled(projectId,serverId,id,false);
            }
        }
        read(projectId,actor.userId());
        var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item())).toList());
        jdbc.sql("INSERT INTO mock_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id",UlidCreator.getUlid().toString()).param("org",project.organizationId()).param("project",projectId).param("user",actor.userId()).param("key",requestKey).param("hash",payloadHash).param("result",text(receipt)).update();
        audit.record(actor,project.organizationId(),projectId,"MOCK_SYNCED","MOCK_SYNC",requestKey,Map.of("operations",request.operations().size()));return receipt;
        } catch (org.springframework.dao.DataIntegrityViolationException error) {
            throw conflict("Mock 名称与目标数据库的唯一约束冲突，请先统一名称；整批未写入");
        }
    }
    private void validateItems(List<Item> items) {
        var servers=new HashMap<String,String>();var keys=new HashSet<String>();var counts=new HashMap<String,Integer>();
        for(var i:items) {
            validate(i);if(!keys.add(key(i))) throw invalid("Mock 服务或规则存在同名或大小写重复，请先统一名称");
            if(i.ruleName()==null) servers.put(fold(i.serverName()),i.serverName());
            else counts.merge(fold(i.serverName()),1,Integer::sum);
        }
        if(servers.size()>20||counts.values().stream().anyMatch(n->n>200)) throw invalid("每项目最多同步 20 个 Mock 服务，每服务最多 200 条规则");
        for(var i:items) if(i.ruleName()!=null&&!i.serverName().equals(servers.get(fold(i.serverName())))) throw invalid("规则缺少对应 Mock 服务或服务名称大小写不一致");
    }
    private void validate(Item i) {
        if(i==null||!name(i.serverName())||(i.ruleName()==null)!=(i.rule()==null)) throw invalid("Mock 服务或规则定义无效");
        if(i.rule()!=null&&(!name(i.ruleName())||!i.ruleName().equals(i.rule().name())||!i.rule().equals(mocks.normalizeRule(i.rule())))) throw invalid("Mock 规则字段不合法或尚未规范化");
    }
    private boolean name(String value) { return value!=null&&!value.isBlank()&&value.equals(value.trim())&&value.length()<=160; }
    private static String fold(String value) { return value.toLowerCase(Locale.ROOT); }
    private String key(Item i) { return text(Arrays.asList(fold(i.serverName()),i.ruleName()==null?null:fold(i.ruleName()))); }
    private void requireActor(AuthenticatedPrincipal actor,String expected) { if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止"); }
    private String uuid(String value) { if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid("同步请求标识必须是 UUID");return UUID.fromString(value).toString(); }
    private byte[] encoded(Object value) { try{return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException error){throw new IllegalStateException("Mock 同步序列化失败",error);} }
    private String text(Object value) { return new String(encoded(value),StandardCharsets.UTF_8); }
    private String hash(byte[] value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);} }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Item(String serverName,String ruleName,MockService.RuleInput rule) { }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,int excludedPublicKeys,int excludedShares) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record ServerRow(String id,String name,boolean enabled,boolean smartExampleEnabled,String updatedAt) { }
    private record RuleRow(String id,Item item,boolean enabled,String updatedAt) { }
}
