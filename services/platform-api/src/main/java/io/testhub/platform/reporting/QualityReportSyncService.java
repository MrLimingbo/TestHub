package io.testhub.platform.reporting;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
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

/** Configuration only. Destination copies/edits are disabled; execution and deliveries remain local. */
@Service
public class QualityReportSyncService {
    public static final int MAX_BYTES=4*1024*1024;
    private static final Set<String> WRITERS=Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD");
    private final AccessService access;
    private final QualityReportScheduleRepository repository;
    private final QualityReportScheduleService schedules;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    public QualityReportSyncService(AccessService access,QualityReportScheduleRepository repository,QualityReportScheduleService schedules,JdbcClient jdbc,ObjectMapper json,AuditService audit) {
        this.access=access;this.repository=repository;this.schedules=schedules;this.jdbc=jdbc;this.json=json;this.audit=audit;
    }
    private ProjectSummary scope(AuthenticatedPrincipal actor,String project,String expected) {
        var anchor=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(!actor.userId().equals(expected))throw conflict("登录账号变化，质量报告计划同步停止");
        return anchor;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor,String project,String expected) {
        var anchor=scope(actor,project,expected);
        long[] budget=jdbc.sql("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(project_ids)),0) FROM quality_report_schedule WHERE organization_id=:o")
                .param("o",anchor.organizationId()).query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2)}).single();
        if(budget[0]>1000||budget[1]>2*1024*1024)throw conflict("组织计划超过 1000 条或项目引用 2 MiB，暂停同步；不会截断");
        var available=new TreeSet<String>();
        for(var p:access.context(actor).projects().getOrDefault(anchor.organizationId(),List.of()))try {
            access.requireProjectParameterWrite(actor,p.id(),WRITERS);available.add(p.id());
        } catch(org.springframework.security.access.AccessDeniedException | ResponseStatusException denied) { /* Not eligible for a writable mapping. */ }
        if(available.size()>1000)throw conflict("组织可写项目超过 1000 个，暂停同步");
        var versions=versions(anchor.organizationId());
        var items=new ArrayList<Item>();var ids=new TreeMap<String,String>();var stamps=new TreeMap<String,Long>();var blocked=new TreeMap<String,String>();
        for(var row:repository.findByOrganization(anchor.organizationId()))if(row.projectIds().contains(project)) {
            String key=key(row.name());
            if(ids.put(key,row.id())!=null) {blocked.put(key,"计划名称大小写重复，请在原端核对");continue;}
            stamps.put(key,versions.get(row.id()));
            if(!available.containsAll(row.projectIds())) {blocked.put(key,"计划引用项目缺失或无写入权限，请核对范围");continue;}
            var item=item(row);validate(item,project);items.add(item);
        }
        items.removeIf(i->blocked.containsKey(key(i.name())));items.sort(Comparator.comparing(i->key(i.name())));
        String revision=hash(encoded(List.of(versions,available,items,ids,stamps,blocked)));
        var result=new Snapshot(1,"QUALITY_REPORT_PLAN_SYNC",project,actor.userId(),revision,items,ids,stamps,List.copyOf(available),blocked);
        encoded(result);return result;
    }
    public Request decode(byte[] bytes) {
        if(bytes==null||bytes.length==0)throw invalid("同步请求不能为空");
        if(bytes.length>MAX_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"质量报告计划同步请求不能超过 4 MiB");
        try {
            JsonNode body=json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(bytes);
            if(body!=null&&body.path("operations").isArray())for(var op:body.path("operations")) {
                var i=op.path("item");var d=i.path("definition");
                if(!op.path("operationId").isTextual()||!i.path("kind").isTextual()||!i.path("name").isTextual()||!d.path("cronExpression").isTextual()||!d.path("timezone").isTextual()||!d.path("periodDays").isIntegralNumber()||!d.path("periodDays").canConvertToInt()||!d.path("projectIds").isArray())throw invalid("计划 JSON 字段类型无效");
                for(var p:d.path("projectIds"))if(!p.isTextual())throw invalid("项目标识必须是字符串");
            }
            var value=json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).<Request>readValue(bytes);
            if(value==null)throw invalid("同步请求不能为空");return value;
        }catch(java.io.IOException error){throw invalid("质量报告计划同步 JSON 无效或含未知字段");}
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var anchor=scope(actor,project,request==null?"":request.expectedUserId());
        if(request==null||!uuid(request.requestKey())||!digest(request.expectedRevision())||request.operations()==null||request.operations().isEmpty()||request.operations().size()>20)throw invalid("同步标识或批次无效（最多 20 项）");
        encoded(request);var operations=new HashSet<String>();var names=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!uuid(op.operationId())||!operations.add(op.operationId()))throw invalid("同步操作标识重复或无效");
            validate(op.item(),project);if(!names.add(key(op.item().name())))throw invalid("同步计划名称重复");
        }
        schedules.lockOrganization(anchor.organizationId());String fingerprint=hash(encoded(request));
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM quality_report_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k")
                .param("p",project).param("u",actor.userId()).param("k",request.requestKey()).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(fingerprint))throw conflict("该 UUID 已用于其他请求");
            try {
                var receipt=json.readValue(prior.get().getValue(),Receipt.class);
                for(var r:receipt.results()) {
                    var row=repository.findById(r.id()).orElseThrow(()->conflict("已确认计划已删除；不会重建"));
                    requireReferences(actor,anchor,row.projectIds());
                    if(!row.organizationId().equals(anchor.organizationId())||!item(row).equals(r.item())||version(row.id())!=r.version())throw conflict("已确认计划变化或已重新启用，保留原请求并停止覆盖");
                }
                return receipt;
            }catch(java.io.IOException error){throw new IllegalStateException("质量报告同步回执损坏",error);}
        }
        var before=snapshot(actor,project,actor.userId());
        if(!before.revision().equals(request.expectedRevision()))throw conflict("目标组织计划或项目权限变化，请重新核对");
        var results=new ArrayList<Result>();
        for(var op:request.operations()) {
            var i=op.item();var d=i.definition();requireReferences(actor,anchor,d.projectIds());
            var payload=schedules.payload(actor,new QualityReportScheduleService.ScheduleInput(i.name(),d.projectIds(),d.cronExpression(),d.timezone(),d.periodDays(),false));
            String key=key(i.name());if(before.blockedKeys().containsKey(key))throw conflict("目标计划名称或项目范围需要核对");
            String id=before.itemIds().get(key);
            if(id==null) {
                // A same-name organization plan outside this anchor must never be silently adopted.
                boolean occupied=repository.findByOrganization(anchor.organizationId()).stream().anyMatch(r->key(r.name()).equals(key));
                if(occupied)throw conflict("目标组织同名计划属于其他项目范围，请人工核对");
                long count=jdbc.sql("SELECT COUNT(*) FROM quality_report_schedule WHERE organization_id=:o").param("o",anchor.organizationId()).query(Long.class).single();
                if(count>=1000)throw conflict("组织计划数量达到 1000 条");
                id=UlidCreator.getUlid().toString();
                try{repository.insert(id,anchor.organizationId(),payload.name(),payload.projectIds(),payload.cronExpression(),payload.timezone(),payload.periodDays(),false,actor.userId());}
                catch(org.springframework.dao.DataIntegrityViolationException error){throw conflict("目标组织存在冲突名称，请核对数据库名称比较规则");}
                schedules.reconcileAfterCommit(id);
            }else {
                var old=repository.findById(id).orElseThrow(()->conflict("目标计划已删除"));
                if(!old.name().equals(i.name()))throw conflict("计划名称大小写不同，请先统一");
                requireReferences(actor,anchor,old.projectIds());
                if(!item(old).equals(i)) {
                    repository.update(id,anchor.organizationId(),payload.name(),payload.projectIds(),payload.cronExpression(),payload.timezone(),payload.periodDays(),false);
                    schedules.reconcileAfterCommit(id);
                }
            }
            results.add(new Result(op.operationId(),id,version(id),item(repository.findById(id).orElseThrow())));
        }
        snapshot(actor,project,actor.userId());var receipt=new Receipt(request.requestKey(),List.copyOf(results));
        jdbc.sql("INSERT INTO quality_report_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:u,:k,:h,:r)")
                .param("id",UlidCreator.getUlid().toString()).param("o",anchor.organizationId()).param("p",project).param("u",actor.userId()).param("k",request.requestKey())
                .param("h",fingerprint).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(actor,anchor.organizationId(),project,"QUALITY_REPORT_PLANS_SYNCED","QUALITY_REPORT_SYNC",request.requestKey(),Map.of("plans",results.size()));return receipt;
    }
    private void requireReferences(AuthenticatedPrincipal actor,ProjectSummary anchor,List<String> projects) {
        for(String id:projects)if(!access.requireProjectParameterWrite(actor,id,WRITERS).organizationId().equals(anchor.organizationId()))throw invalid("计划项目必须属于同一组织");
    }
    private Map<String,Long> versions(String organization) {
        var result=new TreeMap<String,Long>();jdbc.sql("SELECT id,config_version FROM quality_report_schedule WHERE organization_id=:o ORDER BY id").param("o",organization)
                .query((rs,n)->{result.put(rs.getString(1),rs.getLong(2));return 0;}).list();return result;
    }
    private long version(String id){return jdbc.sql("SELECT config_version FROM quality_report_schedule WHERE id=:id").param("id",id).query(Long.class).single();}
    private Item item(QualityReportSchedule row){return new Item("SCHEDULE",row.name(),new Definition(row.projectIds(),row.cronExpression(),row.timezone(),row.periodDays()));}
    private void validate(Item i,String anchor) {
        if(i==null||!"SCHEDULE".equals(i.kind())||i.name()==null||!i.name().equals(i.name().trim())||i.name().isBlank()||i.name().length()>160||i.definition()==null)throw invalid("计划类型或名称无效");
        var d=i.definition();
        if(d.projectIds()==null||d.projectIds().isEmpty()||d.projectIds().size()>50||d.projectIds().stream().anyMatch(p->p==null||!p.matches("[0-9A-HJKMNP-TV-Z]{26}"))||new HashSet<>(d.projectIds()).size()!=d.projectIds().size()||!d.projectIds().contains(anchor))throw invalid("计划项目范围必须包含绑定项目且不能重复");
        if(d.cronExpression()==null||d.cronExpression().length()>160||!d.cronExpression().equals(d.cronExpression().trim())||!org.quartz.CronExpression.isValidExpression(d.cronExpression()))throw invalid("计划 Cron 无效");
        try{if(d.timezone()==null||d.timezone().length()>64)throw new IllegalArgumentException();java.time.ZoneId.of(d.timezone());}catch(RuntimeException e){throw invalid("计划时区无效");}
        if(d.periodDays()==null||d.periodDays()<1||d.periodDays()>90)throw invalid("计划周期应为 1 到 90 天");
    }
    public byte[] encoded(Object value) {
        try{byte[] bytes=json.writeValueAsBytes(canonical(json.valueToTree(value)));if(bytes.length>MAX_BYTES)throw invalid("同步资料超过 4 MiB");return bytes;}catch(java.io.IOException error){throw invalid("同步资料无法序列化");}
    }
    private Object canonical(JsonNode v){if(v.isObject()){var m=new TreeMap<String,Object>();v.fields().forEachRemaining(e->m.put(e.getKey(),canonical(e.getValue())));return m;}if(v.isArray()){var a=new ArrayList<Object>();for(var n:v)a.add(canonical(n));return a;}return v;}
    private String key(String name){return "SCHEDULE\n"+name.toLowerCase(Locale.ROOT);}
    private String hash(byte[] bytes){return FunctionalTransferFiles.sha256(bytes);}
    private boolean digest(String v){return v!=null&&v.matches("[a-f0-9]{64}");}
    private boolean uuid(String v){try{return v!=null&&v.equals(UUID.fromString(v).toString());}catch(IllegalArgumentException error){return false;}}
    private ResponseStatusException invalid(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
    public record Definition(List<String> projectIds,String cronExpression,String timezone,Integer periodDays){}
    public record Item(String kind,String name,Definition definition){}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,Map<String,String> itemIds,Map<String,Long> itemVersions,List<String> projectIds,Map<String,String> blockedKeys){}
    public record Operation(String operationId,Item item){}
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations){}
    public record Result(String operationId,String id,long version,Item item){}
    public record Receipt(String requestKey,List<Result> results){}
}
