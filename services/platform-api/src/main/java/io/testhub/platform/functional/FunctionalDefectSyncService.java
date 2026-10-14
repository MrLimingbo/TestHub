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
public class FunctionalDefectSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final List<String> KINDS = List.of("CATALOG_DELIVERY", "DEFECT");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalDefectService defects;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectFileReferenceGuard fileLinks;
    public FunctionalDefectSyncService(AccessService access,FunctionalAssetService assets, FunctionalDefectService defects,AuditService audit,JdbcClient jdbc,ObjectMapper json,ProjectFileReferenceGuard fileLinks) {
        this.access=access;this.assets=assets;this.defects=defects;this.audit=audit;this.jdbc=jdbc;this.json=json;this.fileLinks=fileLinks;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(actor, project);
    }
    private Snapshot read(AuthenticatedPrincipal actor,String project) {
        if(jdbc.sql("SELECT COUNT(*) FROM functional_defect WHERE project_id=:p").param("p",project).query(Long.class).single()>100
            ||jdbc.sql("SELECT COUNT(*) FROM functional_catalog WHERE project_id=:p AND scope='DELIVERY'").param("p",project).query(Long.class).single()>100)
            throw invalid("每项目最多同步 100 条缺陷和 100 条交付目录");
        if(jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(description)),0) FROM functional_defect WHERE project_id=:p").param("p",project).query(Long.class).single()>SNAPSHOT_BYTES)
            throw invalid("缺陷基本资料超过 3 MiB 同步范围");
        var items=new ArrayList<Item>();var revisions=new ArrayList<RevisionRow>();var paths=catalogPaths(actor,project);
        var itemIds=new TreeMap<String,String>();
        for(var c:assets.listCatalogs(actor,project,"DELIVERY")) {
            var i=item("CATALOG_DELIVERY",paths.get(c.id()),new Catalog(c.sortOrder()));
            add(items,revisions,c.id(),null,i);itemIds.put(key(i),c.id());
        }
        for(var d:defects.list(actor,project)) {
            var i=item("DEFECT",d.defectCode(),new Defect(d.catalogId()==null?null:reference(paths,d.catalogId()),d.title(),
                d.dueDate()==null?null:d.dueDate().toString(),d.bugType(),d.keywords(),sorted(d.browsers()),sorted(d.operatingSystems()),
                d.severity(),d.priority(),d.source(),d.externalUrl(),d.description()));
            add(items,revisions,d.id(),d.updatedAt().toString(),i);itemIds.put(key(i),d.id());
        }
        validateItems(items);validateReferences(items);revisions.sort(Comparator.comparing(RevisionRow::id));
        var result=new Snapshot(1,"FUNCTIONAL_DEFECT_SYNC",project,actor.userId(),hash(encoded(revisions)),
            items.stream().sorted(Comparator.comparing(this::key)).toList(),itemIds);
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("缺陷基本资料超过 3 MiB 同步范围");return result;
    }
    private List<String> sorted(List<String> values) {return values.stream().sorted().toList();}
    private Map<String,String> catalogPaths(AuthenticatedPrincipal actor,String project) {
        var catalogs=new HashMap<String,FunctionalCatalog>();assets.listCatalogs(actor,project,"DELIVERY").forEach(c->catalogs.put(c.id(),c));
        var result=new HashMap<String,String>();for(var catalog:catalogs.values()) {
            var names=new ArrayList<String>();var visited=new HashSet<String>();var c=catalog;
            while(c!=null) {if(!visited.add(c.id())||names.size()>=16) throw invalid("目录循环或层级超过 16 层，请先核对");names.addFirst(c.name());if(c.parentId()==null) break;
                c=catalogs.get(c.parentId());if(c==null) throw invalid("目录父引用缺失或跨类型");}
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
    private String reference(Map<String,String> names,String id) {var name=names.get(id);if(name==null) throw invalid("缺陷基本资料引用缺失，请先核对");return name;}
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("缺陷基本同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"缺陷基本同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("缺陷基本同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>200)
            throw invalid("缺陷基本同步版本或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); fileLinks.validate(op.item().definition(),project); if(!keys.add(key(op.item()))) throw invalid("同一 缺陷基本资料不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"缺陷基本同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); assets.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM functional_defect_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他 缺陷基本资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("缺陷基本同步回执损坏",error); }
        }
        var current=read(actor,project); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标 缺陷基本资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>(); current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {var old=merged.get(key(op.item())); if(old!=null&&!old.name().equals(op.item().name())) throw conflict("缺陷基本名称大小写不同，请先在两端统一名称"); merged.put(key(op.item()),op.item());}
        var mergedItems=new ArrayList<>(merged.values()); validateItems(mergedItems); validateReferences(mergedItems);
        if(encoded(mergedItems).length>SNAPSHOT_BYTES) throw invalid("合并后的 缺陷基本资料超过 3 MiB 同步范围");
        try {
            for(String kind:KINDS) for(var op:request.operations().stream().sorted(Comparator.comparingInt((Operation o)->o.item().kind().startsWith("CATALOG_")?pathNames(o.item().name()).size():0)).toList()) if(kind.equals(op.item().kind()) && !current.items().contains(op.item())) write(actor,project,op.item());
            var written=read(actor,project);
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item(),written.itemIds().get(key(op.item())))).toList());
            jdbc.sql("INSERT INTO functional_defect_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"FUNCTIONAL_DEFECT_SYNCED","FUNCTIONAL_DEFECT_SYNC",requestKey,Map.of("assets",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("缺陷基本名称与目标数据库唯一约束冲突，请统一名称；整批未写入");}
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
        var d=definition(item,Defect.class);
        String catalogId=d.catalogPath()==null?null:paths.entrySet().stream().filter(e->e.getValue().equals(d.catalogPath()))
            .map(Map.Entry::getKey).findFirst().orElseThrow(()->conflict("交付目录引用缺失"));
        var input=new FunctionalDefectService.DefinitionInput(catalogId,item.name(),d.title(),
            d.dueDate()==null?null:java.time.LocalDate.parse(d.dueDate()),d.bugType(),d.keywords(),d.browsers(),
            d.operatingSystems(),d.severity(),d.priority(),d.source(),d.externalUrl(),d.description());
        var old=defects.list(actor,project).stream().filter(x->x.defectCode().equals(item.name())).findFirst();
        // An identical sync round must preserve timestamps and all endpoint-owned lifecycle fields.
        if(old.isPresent()) defects.updateDefinition(actor,project,old.get().id(),input);
        else defects.createDefinition(actor,project,input);
    }
    private void validateItems(List<Item> items) {
        var counts=new HashMap<String,Integer>();var keys=new HashSet<String>();for(var item:items) {validate(item);if(!keys.add(key(item))) throw invalid("缺陷基本资料存在大小写重复，请先统一名称");if(counts.merge(item.kind(),1,Integer::sum)>100) throw invalid("每项目每类缺陷基本同步资料最多 100 条");}
    }
    private void validate(Item item) {
        if(item==null||!KINDS.contains(item.kind()==null?"":item.kind())||item.definition()==null) throw invalid("缺陷基本资料类型无效");
        Object d;
        if(item.kind().startsWith("CATALOG_")) {pathNames(item.name());var c=definition(item,Catalog.class);if(c.sortOrder()<0||c.sortOrder()>9999) throw invalid("目录排序无效");d=c;}
        else {
            code(item.name());var f=definition(item,Defect.class);
            if(f.catalogPath()!=null) pathNames(f.catalogPath());requireText(f.title(),200);
            if(f.dueDate()!=null) {try {if(!f.dueDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();if(!java.time.LocalDate.parse(f.dueDate()).toString().equals(f.dueDate())) throw new IllegalArgumentException();}catch(java.time.DateTimeException|IllegalArgumentException e){throw invalid("截止日期必须为规范日期");}}
            selection(f.bugType(),Set.of("CODE_ERROR","CONFIGURATION","INSTALLATION","SECURITY","PERFORMANCE","STANDARD","TEST_SCRIPT","DESIGN_DEFECT","OTHER"));
            description(f.keywords(),500);selections(f.browsers(),Set.of("CHROME","EDGE","IE","SAFARI","360_BROWSER","QQ_BROWSER","OTHER"));
            selections(f.operatingSystems(),Set.of("WINDOWS","MAC_OS","LINUX","UBUNTU","OTHER"));
            selection(f.severity(),Set.of("CRITICAL","MAJOR","MINOR","TRIVIAL"));selection(f.priority(),Set.of("P0","P1","P2","P3"));
            if(f.source()==null||!f.source().matches("[A-Z][A-Z0-9_-]{1,23}")) throw invalid("缺陷来源无效");
            if(f.externalUrl()!=null) {requireText(f.externalUrl(),1000);try {var uri=java.net.URI.create(f.externalUrl());if(!Set.of("http","https").contains(uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw invalid("外部链接必须为 HTTP 或 HTTPS 地址");}}
            requireText(f.description(),20000);
            if(f.description().replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&#160;"," ").isBlank()) throw invalid("重现步骤不能为空");d=f;
        }
        if(!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("缺陷字段不合法或尚未规范化");
    }
    private void code(String code) {if(code==null||!code.matches("[A-Z][A-Z0-9_-]{1,39}")) throw invalid("缺陷编号无效");}
    private void selection(String value,Set<String> allowed) {if(!allowed.contains(value==null?"":value)) throw invalid("缺陷选项无效");}
    private void selections(List<String> values,Set<String> allowed) {
        if(values==null||values.size()>allowed.size()||new HashSet<>(values).size()!=values.size()) throw invalid("缺陷选项数量无效或重复");
        for(var value:values) selection(value,allowed);if(!values.equals(sorted(values))) throw invalid("缺陷选项必须按名称排序");
    }
    private void validateReferences(List<Item> items) {
        var names=new HashSet<String>();items.forEach(i->names.add(i.kind()+"\n"+i.name()));
        for(var i:items) {
            if(i.kind().equals("CATALOG_DELIVERY")) {String parent=parentPath(i.name());if(parent!=null) requireReference(names,i.kind(),parent);}
            else {var path=definition(i,Defect.class).catalogPath();if(path!=null) requireReference(names,"CATALOG_DELIVERY",path);}
        }
    }
    private void requireReference(Set<String> names,String kind,String name) {if(!names.contains(kind+"\n"+name)) throw conflict("缺陷基本引用缺失或目录名称大小写不同，请先统一相关资料");}
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireText(String v,int max) {if(v==null||v.isBlank()||!v.equals(v.trim())||v.length()>max) throw invalid("缺陷基本名称或引用不合法");}
    private String description(String v,int max) {if(v==null||!v.equals(v.trim())||v.length()>max) throw invalid("缺陷基本说明不合法");return v;}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    private <T> T definition(Item item,Class<T> type) {return decodeValue(encoded(item.definition()),type);}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("缺陷基本同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("缺陷基本同步序列化失败",e);}}
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
    public record Catalog(int sortOrder) { }
    public record Defect(String catalogPath,String title,String dueDate,String bugType,String keywords,List<String> browsers,
                         List<String> operatingSystems,String severity,String priority,String source,String externalUrl,String description) { }
}
