package io.testhub.platform.functional;

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

/** Portable definitions use names for references. Execution history and authority remain per endpoint. */
@Service
public class FunctionalSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final List<String> KINDS = List.of("CATALOG_REQUIREMENTS", "CATALOG_CASES", "CATALOG_PLANS", "REQUIREMENT", "CASE", "PLAN");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalTestExecutionService executions;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectFileReferenceGuard fileLinks;
    public FunctionalSyncService(AccessService access,FunctionalAssetService assets, FunctionalTestExecutionService executions,AuditService audit,JdbcClient jdbc,ObjectMapper json,ProjectFileReferenceGuard fileLinks) {
        this.access=access;this.assets=assets;this.executions=executions;this.audit=audit;this.jdbc=jdbc;this.json=json;this.fileLinks=fileLinks;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(actor, project);
    }
    private Snapshot read(AuthenticatedPrincipal actor,String project) {
        for(String table:List.of("functional_requirement","functional_case","functional_test_plan"))
            if(jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",project).query(Long.class).single()>100) throw invalid("每项目每类功能测试同步资料最多 100 条");
        for(String scope:List.of("REQUIREMENTS","CASES","PLANS"))
            if(jdbc.sql("SELECT COUNT(*) FROM functional_catalog WHERE project_id=:p AND scope=:s").param("p",project).param("s",scope).query(Long.class).single()>100) throw invalid("每种目录最多 100 条");
        long bytes=jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(description)),0) FROM functional_requirement WHERE project_id=:p").param("p",project).query(Long.class).single()
            +jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(precondition)+OCTET_LENGTH(steps)+OCTET_LENGTH(expected_result)),0) FROM functional_case WHERE project_id=:p").param("p",project).query(Long.class).single()
            +jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(description)),0) FROM functional_test_plan WHERE project_id=:p").param("p",project).query(Long.class).single();
        if(bytes>SNAPSHOT_BYTES) throw invalid("功能测试资料超过 3 MiB 同步范围");
        var items=new ArrayList<Item>();var revisions=new ArrayList<RevisionRow>();var paths=catalogPaths(actor,project);
        for(String scope:List.of("REQUIREMENTS","CASES","PLANS")) for(var c:assets.listCatalogs(actor,project,scope))
            add(items,revisions,c.id(),null,item("CATALOG_"+scope,paths.get(c.id()),new Catalog(c.sortOrder())));
        var requirements=assets.listRequirements(actor,project,null);var cases=assets.listCases(actor,project,null);
        var requirementCodes=new HashMap<String,String>();requirements.forEach(r->requirementCodes.put(r.id(),r.requirementCode()));
        var caseCodes=new HashMap<String,String>();cases.forEach(c->caseCodes.put(c.id(),c.caseCode()));
        for(var r:requirements) add(items,revisions,r.id(),r.updatedAt().toString(),item("REQUIREMENT",r.requirementCode(),new Requirement(reference(paths,r.catalogId()),r.title(),r.priority(),r.status(),r.description())));
        for(var c:cases) add(items,revisions,c.id(),c.updatedAt().toString(),item("CASE",c.caseCode(),new Case(reference(paths,c.catalogId()),c.requirementId()==null?null:reference(requirementCodes,c.requirementId()),c.title(),c.priority(),c.status(),c.caseType(),c.precondition(),c.steps(),c.expectedResult())));
        for(var plan:executions.listPlans(actor,project,null)) add(items,revisions,plan.id(),plan.updatedAt().toString(),item("PLAN",plan.planCode(),new Plan(plan.catalogId()==null?null:reference(paths,plan.catalogId()),plan.name(),plan.status(),plan.description(),executions.listPlanCases(actor,project,plan.id()).stream().map(c->reference(caseCodes,c.caseId())).toList())));
        validateItems(items);validateReferences(items);revisions.sort(Comparator.comparing(RevisionRow::id));
        long runs=jdbc.sql("SELECT COUNT(*) FROM functional_test_run WHERE project_id=:p").param("p",project).query(Long.class).single();
        long files=jdbc.sql("SELECT COUNT(*) FROM file_object WHERE project_id=:p").param("p",project).query(Long.class).single();
        var result=new Snapshot(1,"FUNCTIONAL_SYNC",project,actor.userId(),hash(encoded(revisions)),items.stream().sorted(Comparator.comparing(this::key)).toList(),runs,files);
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("功能测试资料超过 3 MiB 同步范围");return result;
    }
    private Map<String,String> catalogPaths(AuthenticatedPrincipal actor,String project) {
        var catalogs=new HashMap<String,FunctionalCatalog>();for(String scope:List.of("REQUIREMENTS","CASES","PLANS")) assets.listCatalogs(actor,project,scope).forEach(c->catalogs.put(c.id(),c));
        var result=new HashMap<String,String>();for(var catalog:catalogs.values()) {
            var names=new ArrayList<String>();var visited=new HashSet<String>();var c=catalog;
            while(c!=null) {if(!visited.add(c.id())||names.size()>=16) throw invalid("目录循环或层级超过 16 层，请先核对");names.addFirst(c.name());if(c.parentId()==null) break;
                c=catalogs.get(c.parentId());if(c==null||!c.scope().equals(catalog.scope())) throw invalid("目录父引用缺失或跨类型");}
            result.put(catalog.id(),pathName(names));
        }return result;
    }
    private String pathName(List<String> names) {return new String(encoded(names),StandardCharsets.UTF_8);}
    private List<String> pathNames(String name) {
        if(name==null||name.length()>4096) throw invalid("目录路径无效");
        List<String> parts;try {parts=json.readerForListOf(String.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(name);}catch(java.io.IOException e){throw invalid("目录路径无效");}
        if(parts==null||parts.isEmpty()||parts.size()>16) throw invalid("目录路径层级无效");for(var part:parts) requireText(part,160);
        if(!pathName(parts).equals(name)||name.length()>4096) throw invalid("目录路径尚未规范化");return parts;
    }
    private String parentPath(String name) {var names=pathNames(name);return names.size()==1?null:pathName(names.subList(0,names.size()-1));}
    private void add(List<Item> items,List<RevisionRow> rows,String id,String updated,Item item) {items.add(item);rows.add(new RevisionRow(id,updated,item));}
    private String reference(Map<String,String> names,String id) {var name=names.get(id);if(name==null) throw invalid("功能测试资料引用缺失，请先核对");return name;}
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("功能测试同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"功能测试同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("功能测试同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>600)
            throw invalid("功能测试同步版本或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); fileLinks.validate(op.item().definition(),project); if(!keys.add(key(op.item()))) throw invalid("同一 功能测试资料不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"功能测试同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); assets.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM functional_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他 功能测试资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("功能测试同步回执损坏",error); }
        }
        var current=read(actor,project); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标 功能测试资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>(); current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {var old=merged.get(key(op.item())); if(old!=null&&!old.name().equals(op.item().name())) throw conflict("功能测试名称大小写不同，请先在两端统一名称"); merged.put(key(op.item()),op.item());}
        var mergedItems=new ArrayList<>(merged.values()); validateItems(mergedItems); validateReferences(mergedItems);
        if(encoded(mergedItems).length>SNAPSHOT_BYTES) throw invalid("合并后的 功能测试资料超过 3 MiB 同步范围");
        try {
            for(String kind:KINDS) for(var op:request.operations().stream().sorted(Comparator.comparingInt((Operation o)->o.item().kind().startsWith("CATALOG_")?pathNames(o.item().name()).size():0)).toList()) if(kind.equals(op.item().kind())) write(actor,project,op.item());
            read(actor,project);
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item())).toList());
            jdbc.sql("INSERT INTO functional_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"FUNCTIONAL_SYNCED","FUNCTIONAL_SYNC",requestKey,Map.of("assets",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("功能测试名称与目标数据库唯一约束冲突，请统一名称；整批未写入");}
    }
    private void write(AuthenticatedPrincipal actor,String project,Item item) {
        var paths=catalogPaths(actor,project);
        if(item.kind().startsWith("CATALOG_")) {
            String scope=item.kind().substring(8);var names=pathNames(item.name());var d=definition(item,Catalog.class);
            var old=assets.listCatalogs(actor,project,scope).stream().filter(c->item.name().equals(paths.get(c.id()))).findFirst();
            String parent=parentPath(item.name());String parentId=parent==null?null:assets.listCatalogs(actor,project,scope).stream().filter(c->parent.equals(paths.get(c.id()))).map(FunctionalCatalog::id).findFirst().orElseThrow(()->conflict("目录父级缺失"));
            var input=new FunctionalAssetService.CatalogInput(names.getLast(),parentId,d.sortOrder(),scope);
            if(old.isPresent()) assets.updateCatalog(actor,project,old.get().id(),input);else assets.createCatalog(actor,project,input);return;
        }
        switch(item.kind()) {
            case "REQUIREMENT" -> {var d=definition(item,Requirement.class);var old=assets.listRequirements(actor,project,null).stream().filter(r->r.requirementCode().equals(item.name())).findFirst();
                var input=new FunctionalAssetService.RequirementInput(catalogId(actor,project,"REQUIREMENTS",d.catalogPath()),item.name(),d.title(),d.priority(),d.status(),d.description());
                if(old.isPresent()) assets.updateRequirement(actor,project,old.get().id(),input);else assets.createRequirement(actor,project,input);}
            case "CASE" -> {var d=definition(item,Case.class);String req=d.requirementCode()==null?null:assets.listRequirements(actor,project,null).stream().filter(r->r.requirementCode().equals(d.requirementCode())).map(FunctionalRequirement::id).findFirst().orElseThrow(()->conflict("需求引用缺失"));
                var old=assets.listCases(actor,project,null).stream().filter(c->c.caseCode().equals(item.name())).findFirst();
                var input=new FunctionalAssetService.CaseInput(catalogId(actor,project,"CASES",d.catalogPath()),req,item.name(),d.title(),d.priority(),d.status(),d.caseType(),d.precondition(),d.steps(),d.expectedResult());
                if(old.isPresent()) assets.updateCase(actor,project,old.get().id(),input);else assets.createCase(actor,project,input);}
            case "PLAN" -> {var d=definition(item,Plan.class);var codes=new HashMap<String,String>();assets.listCases(actor,project,null).forEach(c->codes.put(c.caseCode(),c.id()));
                var input=new FunctionalTestExecutionService.PlanInput(d.catalogPath()==null?null:catalogId(actor,project,"PLANS",d.catalogPath()),item.name(),d.title(),d.status(),d.description(),d.caseCodes().stream().map(c->reference(codes,c)).toList());
                var old=executions.listPlans(actor,project,null).stream().filter(c->c.planCode().equals(item.name())).findFirst();
                if(old.isPresent()) executions.updatePlan(actor,project,old.get().id(),input);else executions.createPlan(actor,project,input);}
            default -> throw invalid("功能测试资料类型无效");
        }
    }
    private String catalogId(AuthenticatedPrincipal actor,String project,String scope,String path) {
        var paths=catalogPaths(actor,project);return assets.listCatalogs(actor,project,scope).stream().filter(c->path.equals(paths.get(c.id()))).map(FunctionalCatalog::id).findFirst().orElseThrow(()->conflict("目录引用缺失"));
    }
    private void validateItems(List<Item> items) {
        var counts=new HashMap<String,Integer>();var keys=new HashSet<String>();for(var item:items) {validate(item);if(!keys.add(key(item))) throw invalid("功能测试资料存在大小写重复，请先统一名称");if(counts.merge(item.kind(),1,Integer::sum)>100) throw invalid("每项目每类功能测试同步资料最多 100 条");}
    }
    private void validate(Item item) {
        if(item==null||!KINDS.contains(item.kind()==null?"":item.kind())||item.definition()==null) throw invalid("功能测试资料类型无效");
        Object d;
        if(item.kind().startsWith("CATALOG_")) {pathNames(item.name());var c=definition(item,Catalog.class);if(c.sortOrder()<0||c.sortOrder()>9999) throw invalid("目录排序无效");d=c;}
        else {code(item.name());switch(item.kind()) {
            case "REQUIREMENT" -> {var r=definition(item,Requirement.class);pathNames(r.catalogPath());requireText(r.title(),200);priority(r.priority());status(r.status(),Set.of("DRAFT","REVIEWING","APPROVED","ARCHIVED"));description(r.description(),20000);d=r;}
            case "CASE" -> {var c=definition(item,Case.class);pathNames(c.catalogPath());if(c.requirementCode()!=null) code(c.requirementCode());requireText(c.title(),200);priority(c.priority());status(c.status(),Set.of("DRAFT","READY","BLOCKED","ARCHIVED"));
                if(!FunctionalAssetService.CASE_TYPES.contains(c.caseType()==null?"":c.caseType())) throw invalid("用例类型无效");description(c.precondition(),20000);requireText(c.steps(),20000);requireText(c.expectedResult(),20000);d=c;}
            case "PLAN" -> {var p=definition(item,Plan.class);if(p.catalogPath()!=null) pathNames(p.catalogPath());requireText(p.title(),200);status(p.status(),Set.of("DRAFT","READY","ARCHIVED"));description(p.description(),20000);
                if(p.caseCodes()==null||p.caseCodes().size()>100||new HashSet<>(p.caseCodes()).size()!=p.caseCodes().size()) throw invalid("计划用例关联数量无效或重复");p.caseCodes().forEach(this::code);d=p;}
            default -> throw invalid("功能测试类型无效");
        }}
        if(!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("功能测试字段不合法或尚未规范化");
    }
    private void code(String code) {if(code==null||!code.matches("[A-Z][A-Z0-9_-]{1,39}")) throw invalid("需求、用例或计划编号无效");}
    private void priority(String value) {status(value,Set.of("P0","P1","P2","P3"));}
    private void status(String value,Set<String> values) {if(!values.contains(value==null?"":value)) throw invalid("资料优先级或状态无效");}
    private void validateReferences(List<Item> items) {
        var names=new HashSet<String>();items.forEach(i->names.add(i.kind()+"\n"+i.name()));
        for(var i:items) {
            if(i.kind().startsWith("CATALOG_")) {String parent=parentPath(i.name());if(parent!=null) requireReference(names,i.kind(),parent);}
            else switch(i.kind()) {
                case "REQUIREMENT" -> requireReference(names,"CATALOG_REQUIREMENTS",definition(i,Requirement.class).catalogPath());
                case "CASE" -> {var c=definition(i,Case.class);requireReference(names,"CATALOG_CASES",c.catalogPath());if(c.requirementCode()!=null) requireReference(names,"REQUIREMENT",c.requirementCode());}
                case "PLAN" -> {var p=definition(i,Plan.class);if(p.catalogPath()!=null) requireReference(names,"CATALOG_PLANS",p.catalogPath());p.caseCodes().forEach(c->requireReference(names,"CASE",c));}
                default -> throw invalid("功能测试类型无效");
            }
        }
    }
    private void requireReference(Set<String> names,String kind,String name) {if(!names.contains(kind+"\n"+name)) throw conflict("功能测试引用缺失或目录名称大小写不同，请先统一相关资料");}
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireText(String v,int max) {if(v==null||v.isBlank()||!v.equals(v.trim())||v.length()>max) throw invalid("功能测试名称或引用不合法");}
    private String description(String v,int max) {if(v==null||!v.equals(v.trim())||v.length()>max) throw invalid("功能测试说明不合法");return v;}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    private <T> T definition(Item item,Class<T> type) {return decodeValue(encoded(item.definition()),type);}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("功能测试同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("功能测试同步序列化失败",e);}}
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private ResponseStatusException invalid(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,long excludedExecutions,long excludedFiles) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record RevisionRow(String id,String updated,Item item) { }
    public record Catalog(int sortOrder) { }
    public record Requirement(String catalogPath,String title,String priority,String status,String description) { }
    public record Case(String catalogPath,String requirementCode,String title,String priority,String status,String caseType,String precondition,String steps,String expectedResult) { }
    public record Plan(String catalogPath,String title,String status,String description,List<String> caseCodes) { }
}
