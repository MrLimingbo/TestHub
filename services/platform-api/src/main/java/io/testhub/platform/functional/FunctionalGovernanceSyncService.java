package io.testhub.platform.functional;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Portable governance definitions keep asset assignments and authority per endpoint. */
@Service
public class FunctionalGovernanceSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final List<String> KINDS = List.of("CATALOG_GOVERNANCE", "TAG", "FIELD_REQUIREMENT", "FIELD_CASE", "FIELD_PLAN");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalGovernanceService governance;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public FunctionalGovernanceSyncService(AccessService access,FunctionalAssetService assets, FunctionalGovernanceService governance,AuditService audit,JdbcClient jdbc,ObjectMapper json) {
        this.access=access;this.assets=assets;this.governance=governance;this.audit=audit;this.jdbc=jdbc;this.json=json;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(actor, project);
    }
    private Snapshot read(AuthenticatedPrincipal actor,String project) {
        if(jdbc.sql("SELECT COUNT(*) FROM functional_tag WHERE project_id=:p").param("p",project).query(Long.class).single()>100)
            throw invalid("每项目最多同步 100 个标签");
        for(String type:List.of("REQUIREMENT","CASE","PLAN"))
            if(jdbc.sql("SELECT COUNT(*) FROM functional_custom_field WHERE project_id=:p AND asset_type=:t").param("p",project).param("t",type).query(Long.class).single()>100)
                throw invalid("每资产类型最多同步 100 个字段定义");
        if(jdbc.sql("SELECT COUNT(*) FROM functional_catalog WHERE project_id=:p AND scope='GOVERNANCE'").param("p",project).query(Long.class).single()>100)
            throw invalid("治理目录最多同步 100 条");
        if(jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(options_json)),0) FROM functional_custom_field WHERE project_id=:p").param("p",project).query(Long.class).single()>SNAPSHOT_BYTES)
            throw invalid("治理定义超过 3 MiB 同步范围");
        var items=new ArrayList<Item>();var revisions=new ArrayList<RevisionRow>();var paths=catalogPaths(actor,project);
        for(var c:assets.listCatalogs(actor,project,"GOVERNANCE"))
            add(items,revisions,c.id(),null,item("CATALOG_GOVERNANCE",paths.get(c.id()),new Catalog(c.sortOrder())));
        for(var tag:governance.listTags(actor,project))
            add(items,revisions,tag.id(),null,item("TAG",tag.name(),new Tag(tag.color())));
        for(var field:governance.listFields(actor,project,null)) {
            List<String> options;
            try {options=json.readerForListOf(String.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(field.optionsJson());}
            catch(java.io.IOException error) {throw invalid("字段选项格式损坏，请先核对");}
            add(items,revisions,field.id(),field.updatedAt().toString(),item("FIELD_"+field.assetType(),field.fieldKey(),new Field(field.name(),field.fieldType(),field.required(),options)));
        }
        validateItems(items);validateReferences(items);revisions.sort(Comparator.comparing(RevisionRow::id));
        long bindings=jdbc.sql("SELECT COUNT(*) FROM functional_asset_tag WHERE project_id=:p").param("p",project).query(Long.class).single();
        var result=new Snapshot(1,"FUNCTIONAL_GOVERNANCE_SYNC",project,actor.userId(),hash(encoded(revisions)),items.stream().sorted(Comparator.comparing(this::key)).toList(),bindings);
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("治理定义超过 3 MiB 同步范围");return result;
    }
    private Map<String,String> catalogPaths(AuthenticatedPrincipal actor,String project) {
        var catalogs=new HashMap<String,FunctionalCatalog>();for(String scope:List.of("GOVERNANCE")) assets.listCatalogs(actor,project,scope).forEach(c->catalogs.put(c.id(),c));
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
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("治理定义同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"治理定义同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("治理定义同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>500)
            throw invalid("治理定义同步版本或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); rejectFileLinks(op.item()); if(!keys.add(key(op.item()))) throw invalid("同一 治理定义资料不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"治理定义同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); assets.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM functional_governance_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他 治理定义资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("治理定义同步回执损坏",error); }
        }
        var current=read(actor,project); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标 治理定义资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>(); current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {var old=merged.get(key(op.item())); if(old!=null&&!old.name().equals(op.item().name())) throw conflict("治理定义名称大小写不同，请先在两端统一名称"); merged.put(key(op.item()),op.item());}
        var mergedItems=new ArrayList<>(merged.values()); validateItems(mergedItems); validateReferences(mergedItems);
        if(encoded(mergedItems).length>SNAPSHOT_BYTES) throw invalid("合并后的 治理定义资料超过 3 MiB 同步范围");
        try {
            for(String kind:KINDS) for(var op:request.operations().stream().sorted(Comparator.comparingInt((Operation o)->o.item().kind().startsWith("CATALOG_")?pathNames(o.item().name()).size():0)).toList()) if(kind.equals(op.item().kind())) write(actor,project,op.item());
            read(actor,project);
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item())).toList());
            jdbc.sql("INSERT INTO functional_governance_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"FUNCTIONAL_GOVERNANCE_SYNCED","FUNCTIONAL_GOVERNANCE_SYNC",requestKey,Map.of("assets",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("治理定义名称与目标数据库唯一约束冲突，请统一名称；整批未写入");}
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
        if(item.kind().equals("TAG")) {
            var d=definition(item,Tag.class);var old=governance.listTags(actor,project).stream().filter(t->t.name().equals(item.name())).findFirst();
            var input=new FunctionalGovernanceService.TagInput(item.name(),d.color());
            if(old.isPresent()) governance.updateTag(actor,project,old.get().id(),input);else governance.createTag(actor,project,input);
        } else {
            String type=item.kind().substring(6);var d=definition(item,Field.class);
            var old=governance.listFields(actor,project,type).stream().filter(f->f.fieldKey().equals(item.name())).findFirst();
            var input=new FunctionalGovernanceService.CustomFieldInput(type,d.title(),item.name(),d.fieldType(),d.required(),d.options());
            if(old.isPresent()) governance.updateField(actor,project,old.get().id(),input);else governance.createField(actor,project,input);
        }
    }
    private void validateItems(List<Item> items) {
        var counts=new HashMap<String,Integer>();var keys=new HashSet<String>();for(var item:items) {validate(item);if(!keys.add(key(item))) throw invalid("治理定义资料存在大小写重复，请先统一名称");if(counts.merge(item.kind(),1,Integer::sum)>100) throw invalid("每项目每类治理定义同步资料最多 100 条");}
    }
    private void validate(Item item) {
        if(item==null||!KINDS.contains(item.kind()==null?"":item.kind())||item.definition()==null) throw invalid("治理定义资料类型无效");
        Object d;
        if(item.kind().startsWith("CATALOG_")) {pathNames(item.name());var c=definition(item,Catalog.class);if(c.sortOrder()<0||c.sortOrder()>9999) throw invalid("目录排序无效");d=c;}
        else if(item.kind().equals("TAG")) {
            requireText(item.name(),64);var tag=definition(item,Tag.class);
            if(tag.color()==null||!tag.color().matches("[a-z]{3,16}|#[0-9a-f]{6}")) throw invalid("标签颜色无效");d=tag;
        } else {
            if(item.name()==null||!item.name().matches("[a-z][a-z0-9_]{1,63}")) throw invalid("字段标识无效");
            var f=definition(item,Field.class);requireText(f.title(),64);
            if(!Set.of("TEXT","NUMBER","DATE","SELECT").contains(f.fieldType()==null?"":f.fieldType())) throw invalid("字段类型无效");
            if(f.options()==null||f.options().size()>100||new HashSet<>(f.options()).size()!=f.options().size()) throw invalid("字段选项数量无效或重复，最多 100 个");
            for(var option:f.options()) requireText(option,80);
            if(f.fieldType().equals("SELECT")==f.options().isEmpty()) throw invalid("下拉字段必须有选项，其他字段不支持选项");d=f;
        }
        if(!json.valueToTree(d).equals(json.valueToTree(item.definition()))) throw invalid("治理字段不合法或尚未规范化");
    }
    private void validateReferences(List<Item> items) {
        var names=new HashSet<String>();items.forEach(i->names.add(i.kind()+"\n"+i.name()));
        for(var i:items) if(i.kind().equals("CATALOG_GOVERNANCE")) {
            String parent=parentPath(i.name());if(parent!=null) requireReference(names,i.kind(),parent);
        }
    }
    private void requireReference(Set<String> names,String kind,String name) {if(!names.contains(kind+"\n"+name)) throw conflict("治理定义引用缺失或目录名称大小写不同，请先统一相关资料");}
    private void rejectFileLinks(Item item) {
        String text=new String(encoded(item.definition()),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if(text.contains("/api/v1/projects/")||text.contains("%2fapi%2fv1%2fprojects%2f")) throw invalid("正文含项目文件或其他本端项目链接，请先使用显式迁移并核对引用；该资料不能自动同步");
    }
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireText(String v,int max) {if(v==null||v.isBlank()||!v.equals(v.trim())||v.length()>max) throw invalid("治理定义名称或引用不合法");}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    private <T> T definition(Item item,Class<T> type) {return decodeValue(encoded(item.definition()),type);}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("治理定义同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("治理定义同步序列化失败",e);}}
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private ResponseStatusException invalid(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,long excludedBindings) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record RevisionRow(String id,String updated,Item item) { }
    public record Catalog(int sortOrder) { }
    public record Tag(String color) { }
    public record Field(String title,String fieldType,boolean required,List<String> options) { }
}
