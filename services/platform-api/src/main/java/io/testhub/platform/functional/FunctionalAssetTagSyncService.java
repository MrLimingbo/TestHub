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

/** Associations travel by asset code and exact tag name; definitions remain owned by their modules. */
@Service
public class FunctionalAssetTagSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final Map<String, String> TABLES = Map.of(
            "REQUIREMENT", "functional_requirement", "CASE", "functional_case", "PLAN", "functional_test_plan");
    private static final Map<String, String> CODES = Map.of(
            "REQUIREMENT", "requirement_code", "CASE", "case_code", "PLAN", "plan_code");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalGovernanceService governance;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public FunctionalAssetTagSyncService(AccessService access, FunctionalAssetService assets,
            FunctionalGovernanceService governance, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.assets = assets; this.governance = governance;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS);
        requireActor(actor, expectedUserId);
        return read(actor, project);
    }

    private List<Asset> assetRows(String project) {
        var result = new ArrayList<Asset>();
        for (String type : List.of("REQUIREMENT", "CASE", "PLAN")) {
            String table = TABLES.get(type);
            if (jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", project).query(Long.class).single() > 100)
                throw invalid("每项目每资产类型最多同步 100 条标签关联定义");
            result.addAll(jdbc.sql("SELECT id," + CODES.get(type) + " FROM " + table + " WHERE project_id=:p ORDER BY id")
                    .param("p", project).query((rs, n) -> new Asset(type, rs.getString(1), rs.getString(2))).list());
        }
        return result;
    }

    private Snapshot read(AuthenticatedPrincipal actor, String project) {
        if (jdbc.sql("SELECT COUNT(*) FROM functional_tag WHERE project_id=:p").param("p", project).query(Long.class).single() > 100)
            throw invalid("每项目最多同步 100 个标签名称引用");
        if (jdbc.sql("SELECT COUNT(*) FROM functional_asset_tag WHERE project_id=:p").param("p", project).query(Long.class).single() > 30000)
            throw invalid("项目标签关联超过同步范围，请先核对");
        var rows = assetRows(project);
        var tags = governance.listTags(actor, project).stream().sorted(Comparator.comparing(FunctionalTag::name)).toList();
        var names = tags.stream().map(FunctionalTag::name).toList();
        validateNames(names);
        var assignments = jdbc.sql("""
                SELECT at.asset_type,at.asset_id,t.name FROM functional_asset_tag at
                LEFT JOIN functional_tag t ON t.id=at.tag_id AND t.project_id=at.project_id
                WHERE at.project_id=:p
                """).param("p", project).query((rs,n) -> new Assignment(rs.getString(1),rs.getString(2),rs.getString(3))).list();
        var items = new ArrayList<Item>();
        var revisions = new ArrayList<Map<String,Object>>();
        for (var row : rows) {
            var attached = assignments.stream().filter(a -> row.kind().equals(a.kind()) && row.id().equals(a.assetId()))
                    .map(Assignment::tag).sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
            var item = new Item(row.kind(),row.code(),Map.of("tags",attached));
            validate(item); items.add(item);
            revisions.add(Map.of("id",row.id(),"item",item));
        }
        var keys = new HashSet<String>();
        for (var item : items) if (!keys.add(key(item))) throw invalid("资产编号大小写重复，请先核对");
        var assetIds = new TreeMap<String,String>(); rows.forEach(a -> assetIds.put(a.kind()+"\n"+a.code().toLowerCase(Locale.ROOT),a.id()));
        var tagIds = new TreeMap<String,String>(); tags.forEach(t -> tagIds.put(t.name(),t.id()));
        var result = new Snapshot(1,"FUNCTIONAL_ASSET_TAG_SYNC",project,actor.userId(),
                hash(encoded(Map.of("assets",revisions,"tags",tags.stream().map(t -> Map.of("id",t.id(),"name",t.name())).toList()))),
                items.stream().sorted(Comparator.comparing(this::key)).toList(),names,assetIds,tagIds);
        if (encoded(result).length > SNAPSHOT_BYTES) throw invalid("标签关联快照超过 3 MiB 同步范围");
        return result;
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("标签关联同步请求不能为空");
        if (body.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"标签关联同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String project, Request request) {
        var context = access.requireProjectParameterWrite(actor,project,WRITERS);
        if (request == null) throw invalid("标签关联同步请求不能为空");
        requireActor(actor,request.expectedUserId());
        String requestKey = uuid(request.requestKey());
        if (request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 300)
            throw invalid("标签关联同步版本或操作数量无效");
        var ids = new HashSet<String>(); var keys = new HashSet<String>();
        for (var op : request.operations()) {
            if (op == null || !ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item());
            if (!keys.add(key(op.item()))) throw invalid("同一资产标签关联不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"标签关联同步请求不能超过 4 MiB。");
        String payloadHash = hash(payload);
        assets.lockProject(project);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM functional_asset_tag_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey)
                .query((rs,n) -> Map.entry(rs.getString(1),rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他标签关联");
            try { return json.readValue(prior.get().getValue(),Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("标签关联同步回执损坏",error); }
        }
        var current = read(actor,project);
        if (!current.revision().equals(request.expectedRevision())) throw conflict("目标资产或标签关联已变化，请重新同步并核对");
        var byKey = new HashMap<String,Item>(); current.items().forEach(i -> byKey.put(key(i),i));
        var rows = assetRows(project);
        var tags = governance.listTags(actor,project);
        // Validate every reference before replacing any associations. Never create assets or dictionary entries here.
        for (var op : request.operations()) {
            var item = op.item(); var old = byKey.get(key(item));
            if (old == null || !old.name().equals(item.name())) throw conflict("目标资产编号缺失或大小写不同，请先同步基础资料并核对");
            if (!current.availableTags().containsAll(definition(item).tags())) throw conflict("目标标签名称缺失或大小写不同，请先同步治理定义并核对");
            byKey.put(key(item),item);
        }
        var mergedSnapshot = new Snapshot(1,current.scope(),project,actor.userId(),current.revision(),
                byKey.values().stream().sorted(Comparator.comparing(this::key)).toList(),current.availableTags(),current.assetIds(),current.tagIds());
        if (encoded(mergedSnapshot).length > SNAPSHOT_BYTES) throw invalid("合并后的标签关联超过 3 MiB 同步范围");
        for (var op : request.operations()) {
            var item = op.item();
            var old = current.items().stream().filter(i -> key(i).equals(key(item))).findFirst().orElseThrow();
            if (old.equals(item)) continue;
            var row = rows.stream().filter(a -> a.kind().equals(item.kind()) && a.code().equals(item.name())).findFirst().orElseThrow();
            var tagIds = definition(item).tags().stream().map(name -> tags.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow().id()).toList();
            governance.replaceAssetTags(actor,project,item.kind(),row.id(),tagIds);
        }
        var receipt = new Receipt(requestKey,request.operations().stream().map(op -> new Result(op.operationId(),op.item())).toList());
        jdbc.sql("INSERT INTO functional_asset_tag_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId())
                .param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(actor,context.organizationId(),project,"FUNCTIONAL_ASSET_TAG_SYNCED","FUNCTIONAL_ASSET_TAG_SYNC",requestKey,Map.of("assets",request.operations().size()));
        return receipt;
    }

    private void validate(Item item) {
        if (item == null || !TABLES.containsKey(item.kind() == null ? "" : item.kind()) || item.name() == null
                || !item.name().matches("[A-Z][A-Z0-9_-]{1,39}") || item.definition() == null) throw invalid("资产类型或编号无效");
        var d = definition(item); validateNames(d.tags());
        if (!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("标签关联字段无效或尚未规范化");
    }
    private void validateNames(List<String> names) {
        if (names == null || names.size() > 100) throw invalid("每资产最多同步 100 个标签名称");
        var folded = new HashSet<String>(); String previous = null;
        for (var name : names) {
            if (name == null || name.isBlank() || !name.equals(name.trim()) || name.length() > 64
                    || !folded.add(name.toLowerCase(Locale.ROOT)) || (previous != null && previous.compareTo(name) >= 0))
                throw invalid("标签名称无效、大小写重复或未按名称排序");
            previous = name;
        }
    }
    private Definition definition(Item item) { return decodeValue(encoded(item.definition()),Definition.class); }
    private String key(Item item) { return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT); }
    private void requireActor(AuthenticatedPrincipal actor,String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止"); }
    private String uuid(String value) { if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(value).toString(); }
    private <T> T decodeValue(byte[] bytes,Class<T> type) { try { return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes); } catch (java.io.IOException error) { throw invalid("标签关联同步 JSON 不合法或包含不支持的字段"); } }
    private byte[] encoded(Object value) { try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); } catch (java.io.IOException error) { throw new IllegalStateException("标签关联同步序列化失败",error); } }
    private String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,List<String> availableTags,Map<String,String> assetIds,Map<String,String> tagIds) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Definition(List<String> tags) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record Asset(String kind,String id,String code) { }
    private record Assignment(String kind,String assetId,String tag) { }
}
