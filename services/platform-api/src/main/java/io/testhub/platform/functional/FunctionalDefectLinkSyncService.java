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

/** Requirement/case associations use immutable codes; execution links remain owned by each endpoint. */
@Service
public class FunctionalDefectLinkSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final Map<String, String> TABLES = Map.of(
            "REQUIREMENT", "functional_requirement", "CASE", "functional_case", "DEFECT", "functional_defect");
    private static final Map<String, String> CODES = Map.of(
            "REQUIREMENT", "requirement_code", "CASE", "case_code", "DEFECT", "defect_code");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalDefectService defects;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public FunctionalDefectLinkSyncService(AccessService access, FunctionalAssetService assets,
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

    private List<Asset> assetRows(String project) {
        var result = new ArrayList<Asset>();
        for (String type : List.of("REQUIREMENT", "CASE", "DEFECT")) {
            String table = TABLES.get(type);
            if (jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", project).query(Long.class).single() > 100)
                throw invalid("每项目每资产类型最多同步 100 条缺陷关联定义");
            result.addAll(jdbc.sql("SELECT id," + CODES.get(type) + " FROM " + table + " WHERE project_id=:p ORDER BY id")
                    .param("p", project).query((rs, n) -> new Asset(type, rs.getString(1), rs.getString(2))).list());
        }
        return result;
    }

    private Snapshot read(AuthenticatedPrincipal actor, String project) {
        var rows = assetRows(project);
        if (jdbc.sql("SELECT COUNT(*) FROM functional_defect_link WHERE project_id=:p AND asset_type IN ('REQUIREMENT','CASE')")
                .param("p",project).query(Long.class).single()>20000) throw invalid("项目缺陷关联超过同步范围");
        var links=jdbc.sql("SELECT id,defect_id,asset_type,asset_id FROM functional_defect_link WHERE project_id=:p AND asset_type IN ('REQUIREMENT','CASE') ORDER BY id")
                .param("p",project).query((rs,n)->new Link(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))).list();
        var byId=new HashMap<String,Asset>();rows.forEach(row->byId.put(row.kind()+"\n"+row.id(),row));
        var unavailable = new TreeSet<String>();
        for (var link : links) {
            var defect = byId.get("DEFECT\n" + link.defectId());
            if (defect == null) throw invalid("本端缺陷关联跨项目，请先核对");
            if (!byId.containsKey(link.kind() + "\n" + link.assetId())) unavailable.add(defect.code());
        }
        var items=new ArrayList<Item>();
        for(var row:rows) if(row.kind().equals("DEFECT")) {
            var attached=links.stream().filter(link->link.defectId().equals(row.id()) && byId.containsKey(link.kind()+"\n"+link.assetId())).toList();
            var requirements=attached.stream().filter(link->link.kind().equals("REQUIREMENT")).map(link->byId.get("REQUIREMENT\n"+link.assetId()).code()).sorted().toList();
            var cases=attached.stream().filter(link->link.kind().equals("CASE")).map(link->byId.get("CASE\n"+link.assetId()).code()).sorted().toList();
            var item=new Item("DEFECT",row.code(),Map.of("requirements",requirements,"cases",cases));validate(item);items.add(item);
        }
        var referenceIds=new TreeMap<String,String>();var keys=new HashSet<String>();
        for(var row:rows) {
            if(row.code()==null||!row.code().matches("[A-Z][A-Z0-9_-]{1,39}")||!keys.add(row.kind()+"\n"+row.code().toLowerCase(Locale.ROOT)))
                throw invalid("资产编号无效或大小写重复，请先核对");
            referenceIds.put(row.kind()+"\n"+row.code().toLowerCase(Locale.ROOT),row.id());
        }
        var result=new Snapshot(1,"FUNCTIONAL_DEFECT_LINK_SYNC",project,actor.userId(),
                hash(encoded(Map.of("references",rows,"links",links))),items.stream().sorted(Comparator.comparing(this::key)).toList(),referenceIds,List.copyOf(unavailable));
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("缺陷关联快照超过 3 MiB 同步范围");return result;
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("缺陷关联同步请求不能为空");
        if (body.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"缺陷关联同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String project, Request request) {
        var context = access.requireProjectParameterWrite(actor,project,WRITERS);
        if (request == null) throw invalid("缺陷关联同步请求不能为空");
        requireActor(actor,request.expectedUserId());
        String requestKey = uuid(request.requestKey());
        if (request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 100)
            throw invalid("缺陷关联同步版本或操作数量无效");
        var ids = new HashSet<String>(); var keys = new HashSet<String>();
        for (var op : request.operations()) {
            if (op == null || !ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item());
            if (!keys.add(key(op.item()))) throw invalid("同一资产缺陷关联不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"缺陷关联同步请求不能超过 4 MiB。");
        String payloadHash = hash(payload);
        assets.lockProject(project);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM functional_defect_link_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey)
                .query((rs,n) -> Map.entry(rs.getString(1),rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他缺陷关联");
            try { return json.readValue(prior.get().getValue(),Receipt.class); }
            catch (java.io.IOException error) { throw new IllegalStateException("缺陷关联同步回执损坏",error); }
        }
        var current = read(actor,project);
        if (!current.revision().equals(request.expectedRevision())) throw conflict("目标资产或缺陷关联已变化，请重新同步并核对");
        var byKey=new HashMap<String,Item>();current.items().forEach(item->byKey.put(key(item),item));
        for(var op:request.operations()) {
            var item=op.item();var old=byKey.get(key(item));
            if (current.unavailable().contains(item.name())) throw conflict("目标缺陷仍有失效关联，请在缺陷详情中核对并解除后重新同步");
            if(old==null||!old.name().equals(item.name())) throw conflict("目标缺陷编号缺失，请先同步缺陷基本资料并核对");
            for(var ref:references(item)) if(!current.referenceIds().containsKey(ref)) throw conflict("目标需求／用例编号缺失，请先同步基础资料并核对");
            byKey.put(key(item),item);
        }
        if(encoded(new Snapshot(1,current.scope(),project,actor.userId(),current.revision(),byKey.values().stream().sorted(Comparator.comparing(this::key)).toList(),current.referenceIds(),current.unavailable())).length>SNAPSHOT_BYTES)
            throw invalid("合并后的缺陷关联超过 3 MiB 同步范围");
        for(var op:request.operations()) {
            var item=op.item();if(current.items().contains(item)) continue;
            var requested=new ArrayList<FunctionalDefectService.DefectLinkInput>();var d=definition(item);
            for(var code:d.requirements()) requested.add(new FunctionalDefectService.DefectLinkInput("REQUIREMENT",current.referenceIds().get("REQUIREMENT\n"+code.toLowerCase(Locale.ROOT))));
            for(var code:d.cases()) requested.add(new FunctionalDefectService.DefectLinkInput("CASE",current.referenceIds().get("CASE\n"+code.toLowerCase(Locale.ROOT))));
            defects.replaceDefinitionLinks(actor,project,current.referenceIds().get(key(item)),requested);
        }
        var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item())).toList(),current.referenceIds());
        jdbc.sql("INSERT INTO functional_defect_link_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId())
                .param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(actor,context.organizationId(),project,"FUNCTIONAL_DEFECT_LINK_SYNCED","FUNCTIONAL_DEFECT_LINK_SYNC",requestKey,Map.of("assets",request.operations().size()));
        return receipt;
    }

    private void validate(Item item) {
        if (item == null || !"DEFECT".equals(item.kind()) || item.name() == null
                || !item.name().matches("[A-Z][A-Z0-9_-]{1,39}") || item.definition() == null) throw invalid("资产类型或编号无效");
        var d = definition(item); validateNames(d.requirements());validateNames(d.cases());
        if (!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("缺陷关联字段无效或尚未规范化");
    }
    private void validateNames(List<String> names) {
        if (names == null || names.size() > 100) throw invalid("每缺陷每类最多同步 100 个需求／用例编号");
        var folded = new HashSet<String>(); String previous = null;
        for (var name : names) {
            if (name == null || !name.matches("[A-Z][A-Z0-9_-]{1,39}")
                    || !folded.add(name.toLowerCase(Locale.ROOT)) || (previous != null && previous.compareTo(name) >= 0))
                throw invalid("需求／用例编号无效、大小写重复或未按名称排序");
            previous = name;
        }
    }
    private List<String> references(Item item) {
        var d=definition(item);var result=new ArrayList<String>();
        d.requirements().forEach(code->result.add("REQUIREMENT\n"+code.toLowerCase(Locale.ROOT)));
        d.cases().forEach(code->result.add("CASE\n"+code.toLowerCase(Locale.ROOT)));return result;
    }
    private Definition definition(Item item) { return decodeValue(encoded(item.definition()),Definition.class); }
    private String key(Item item) { return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT); }
    private void requireActor(AuthenticatedPrincipal actor,String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止"); }
    private String uuid(String value) { if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(value).toString(); }
    private <T> T decodeValue(byte[] bytes,Class<T> type) { try { return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes); } catch (java.io.IOException error) { throw invalid("缺陷关联同步 JSON 不合法或包含不支持的字段"); } }
    private byte[] encoded(Object value) { try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); } catch (java.io.IOException error) { throw new IllegalStateException("缺陷关联同步序列化失败",error); } }
    private String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,Map<String,String> referenceIds,List<String> unavailable) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Definition(List<String> requirements,List<String> cases) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results,Map<String,String> referenceIds) { }
    private record Asset(String kind,String id,String code) { }
    private record Link(String id,String defectId,String kind,String assetId) { }
}
