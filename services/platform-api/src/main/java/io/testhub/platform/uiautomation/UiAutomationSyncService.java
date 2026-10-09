package io.testhub.platform.uiautomation;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTestExecutionRepository;
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

/** Portable definitions use names for references. Execution history and authority remain per endpoint. */
@Service
public class UiAutomationSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final List<String> KINDS = List.of("PROFILE", "PAGE_OBJECT", "SCENARIO", "SCHEDULE");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final UiAutomationRepository assets;
    private final UiAutomationService automation;
    private final UiAutomationScheduleRepository schedules;
    private final UiAutomationScheduleService scheduleService;
    private final FunctionalTestExecutionRepository plans;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public UiAutomationSyncService(AccessService access, UiAutomationRepository assets, UiAutomationService automation,
            UiAutomationScheduleRepository schedules, UiAutomationScheduleService scheduleService,
            FunctionalTestExecutionRepository plans, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access=access; this.assets=assets; this.automation=automation; this.schedules=schedules;
        this.scheduleService=scheduleService; this.plans=plans; this.audit=audit; this.jdbc=jdbc; this.json=json;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(project, actor.userId());
    }
    private Snapshot read(String project, String actor) {
        for (String table : List.of("ui_automation_browser_profile","ui_automation_page_object","ui_automation_scenario","ui_automation_schedule"))
            if (jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",project).query(Long.class).single()>100)
                throw invalid("每项目每类 Web UI 同步资料最多 100 条");
        long bytes=jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(variables)+OCTET_LENGTH(steps)),0) FROM ui_automation_scenario WHERE project_id=:p")
                .param("p",project).query(Long.class).single();
        if(bytes>SNAPSHOT_BYTES) throw invalid("Web UI 资料超过 3 MiB 同步范围");
        var profileRows=assets.findProfiles(project); var objects=assets.findPageObjects(project); var scenarios=assets.findScenarios(project);
        var profileNames=new HashMap<String,String>(); profileRows.forEach(p->profileNames.put(p.id(),p.name()));
        var objectNames=new HashMap<String,String>(); objects.forEach(o->objectNames.put(o.id(),o.name()));
        var scenarioNames=new HashMap<String,String>(); scenarios.forEach(s->scenarioNames.put(s.id(),s.name()));
        if(jdbc.sql("SELECT COUNT(*) FROM functional_test_plan WHERE project_id=:p").param("p",project).query(Long.class).single()>5000) throw invalid("功能测试计划引用范围超过 5000 条");
        var planRows=plans.findPlans(project,null); var planCodes=new HashMap<String,String>(); planRows.forEach(p->planCodes.put(p.id(),p.planCode().toUpperCase(Locale.ROOT)));
        var items=new ArrayList<Item>(); var revisions=new ArrayList<RevisionRow>();
        for(var p:profileRows) add(items,revisions,p.id(),p.version(),p.enabled(),p.updatedAt().toString(),
                item("PROFILE",p.name(),new Profile(p.browser(),p.baseUrl(),p.viewportWidth(),p.viewportHeight())));
        for(var p:objects) add(items,revisions,p.id(),null,null,p.updatedAt().toString(),item("PAGE_OBJECT",p.name(),new PageObject(p.selectorValue(),p.description())));
        for(var s:scenarios) add(items,revisions,s.id(),s.version(),null,s.updatedAt().toString(),item("SCENARIO",s.name(),
                new Scenario(reference(profileNames,s.browserProfileId()),s.description(),s.failureStrategy(),s.variables(),s.steps().stream().map(step->
                    new Step(step.id(),step.type(),step.name(),step.pageObjectId()==null?step.selector():null,
                            step.pageObjectId()==null?null:reference(objectNames,step.pageObjectId()),step.value(),step.url(),step.expectedText(),step.timeoutMillis())).toList())));
        for(var s:schedules.findByProject(project)) add(items,revisions,s.id(),null,s.enabled(),s.updatedAt().toString(),item("SCHEDULE",s.name(),
                new Schedule(reference(scenarioNames,s.scenarioId()),s.functionalPlanId()==null?null:reference(planCodes,s.functionalPlanId()),
                        s.cronExpression(),s.timezone(),s.timeoutSeconds(),s.maxRetryAttempts(),s.retryBackoffSeconds())));
        validateItems(items); validateReferences(items,planRows.stream().map(p->p.planCode().toUpperCase(Locale.ROOT)).toList());
        revisions.sort(Comparator.comparing(RevisionRow::id));
        var codes=planRows.stream().map(p->p.planCode().toUpperCase(Locale.ROOT)).sorted().toList();
        if(codes.size()>5000) throw invalid("功能测试计划引用范围超过 5000 条，请核对资料");
        long executions=jdbc.sql("SELECT COUNT(*) FROM ui_automation_execution WHERE project_id=:p").param("p",project).query(Long.class).single();
        var result=new Snapshot(1,"UI_AUTOMATION_SYNC",project,actor,hash(encoded(List.of(revisions,codes))),
                items.stream().sorted(Comparator.comparing(this::key)).toList(),codes,executions);
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("Web UI 资料超过 3 MiB 同步范围");
        return result;
    }
    private void add(List<Item> items,List<RevisionRow> rows,String id,Integer version,Boolean enabled,String updated,Item item) {
        items.add(item); rows.add(new RevisionRow(id,version,enabled,updated,item));
    }
    private String reference(Map<String,String> names,String id) { var name=names.get(id); if(name==null) throw invalid("Web UI 资料引用缺失，请先核对"); return name; }
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("Web UI 同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Web UI 同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("Web UI 同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>400)
            throw invalid("Web UI 同步版本或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); if(!keys.add(key(op.item()))) throw invalid("同一 Web UI 资料不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Web UI 同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); assets.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM ui_automation_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他 Web UI 资料");
            for(var op:request.operations()) if(op.item().kind().equals("SCHEDULE")) schedules.findByProject(project).stream()
                    .filter(s->s.name().equals(op.item().name())).forEach(s->scheduleService.refreshSyncedSchedule(s.id()));
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("Web UI 同步回执损坏",error); }
        }
        var current=read(project,actor.userId()); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标 Web UI 资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>(); current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {var old=merged.get(key(op.item())); if(old!=null&&!old.name().equals(op.item().name())) throw conflict("Web UI 名称大小写不同，请先在两端统一名称"); merged.put(key(op.item()),op.item());}
        var mergedItems=new ArrayList<>(merged.values()); validateItems(mergedItems); validateReferences(mergedItems,current.functionalPlanCodes());
        if(encoded(mergedItems).length>SNAPSHOT_BYTES) throw invalid("合并后的 Web UI 资料超过 3 MiB 同步范围");
        try {
            for(String kind:KINDS) for(var op:request.operations()) if(kind.equals(op.item().kind())) write(actor,project,op.item());
            read(project,actor.userId());
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item())).toList());
            jdbc.sql("INSERT INTO ui_automation_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"UI_AUTOMATION_SYNCED","UI_AUTOMATION_SYNC",requestKey,Map.of("assets",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("Web UI 名称与目标数据库唯一约束冲突，请统一名称；整批未写入");}
    }
    private void write(AuthenticatedPrincipal actor,String project,Item item) {
        switch(item.kind()) {
            case "PROFILE" -> {var d=definition(item,Profile.class); var input=new UiAutomationService.ProfileRequest(item.name(),d.browser(),d.baseUrl(),d.viewportWidth(),d.viewportHeight(),false);
                var old=assets.findProfiles(project).stream().filter(p->p.name().equals(item.name())).findFirst();
                if(old.isPresent()) automation.updateSyncedProfile(actor,project,old.get().id(),input); else automation.createTransferredProfile(actor,project,input);}
            case "PAGE_OBJECT" -> {var d=definition(item,PageObject.class); var input=new UiAutomationService.PageObjectRequest(item.name(),d.selectorValue(),d.description());
                var old=assets.findPageObjects(project).stream().filter(p->p.name().equals(item.name())).findFirst();
                if(old.isPresent()) automation.updatePageObject(actor,project,old.get().id(),input); else automation.createPageObject(actor,project,input);}
            case "SCENARIO" -> {var d=definition(item,Scenario.class); var profiles=new HashMap<String,String>(); assets.findProfiles(project).forEach(p->profiles.put(p.name(),p.id()));
                var objects=new HashMap<String,String>(); assets.findPageObjects(project).forEach(p->objects.put(p.name(),p.id()));
                var steps=d.steps().stream().map(s->new UiAutomationStep(s.id(),s.type(),s.name(),s.selector(),s.pageObjectName()==null?null:reference(objects,s.pageObjectName()),s.value(),s.url(),s.expectedText(),s.timeoutMillis())).toList();
                var input=new UiAutomationService.ScenarioRequest(reference(profiles,d.browserProfileName()),item.name(),d.description(),d.failureStrategy(),d.variables(),steps);
                var old=assets.findScenarios(project).stream().filter(s->s.name().equals(item.name())).findFirst();
                if(old.isPresent()) automation.updateScenario(actor,project,old.get().id(),input); else automation.createScenario(actor,project,input);}
            case "SCHEDULE" -> {var d=definition(item,Schedule.class); var scenarios=new HashMap<String,String>(); assets.findScenarios(project).forEach(s->scenarios.put(s.name(),s.id()));
                String plan=null; if(d.functionalPlanCode()!=null) {var matches=plans.findPlans(project,null).stream().filter(p->p.planCode().equalsIgnoreCase(d.functionalPlanCode())).toList(); if(matches.size()!=1) throw conflict("目标缺少唯一功能测试计划编号，请先迁移计划"); plan=matches.getFirst().id();}
                var old=schedules.findByProject(project).stream().filter(s->s.name().equals(item.name())).findFirst();
                var input=new UiAutomationScheduleService.ScheduleInput(reference(scenarios,d.scenarioName()),plan,item.name(),d.cronExpression(),d.timezone(),d.timeoutSeconds(),d.maxRetryAttempts(),d.retryBackoffSeconds(),old.map(UiAutomationSchedule::enabled).orElse(false));
                if(old.isPresent()) scheduleService.update(actor,project,old.get().id(),input); else scheduleService.create(actor,project,input);}
            default -> throw invalid("Web UI 资料类型无效");
        }
    }
    private void validateItems(List<Item> items) {
        var counts=new HashMap<String,Integer>(); var keys=new HashSet<String>();
        for(var item:items) {validate(item); if(!keys.add(key(item))) throw invalid("Web UI 资料存在同名或大小写重复，请先统一名称"); if(counts.merge(item.kind(),1,Integer::sum)>100) throw invalid("每项目每类 Web UI 同步资料最多 100 条");}
    }
    private void validate(Item item) {
        if(item==null||item.kind()==null||!KINDS.contains(item.kind())||item.definition()==null) throw invalid("Web UI 资料类型无效");
        requireText(item.name(),item.kind().equals("PROFILE")||item.kind().equals("PAGE_OBJECT")?120:160);
        Object normalized;
        switch(item.kind()) {
            case "PROFILE" -> {var d=definition(item,Profile.class); var p=automation.normalizeSyncProfile(new UiAutomationService.ProfileRequest(item.name(),d.browser(),d.baseUrl(),d.viewportWidth(),d.viewportHeight(),false)); normalized=new Profile(p.browser(),p.baseUrl(),p.viewportWidth(),p.viewportHeight());}
            case "PAGE_OBJECT" -> {var d=definition(item,PageObject.class); var p=automation.normalizeSyncPageObject(new UiAutomationService.PageObjectRequest(item.name(),d.selectorValue(),d.description())); normalized=new PageObject(p.selectorValue(),p.description());}
            case "SCENARIO" -> {var d=definition(item,Scenario.class); requireText(d.browserProfileName(),120);
                if(!Set.of("STOP","CONTINUE").contains(d.failureStrategy()==null?"":d.failureStrategy())||d.steps()==null) throw invalid("Web UI 场景失败策略或步骤无效");
                var raw=new ArrayList<UiAutomationStep>(); for(var s:d.steps()) {if(s==null) throw invalid("Web UI 步骤不能为空"); if(s.pageObjectName()!=null) {requireText(s.pageObjectName(),120); if(s.selector()!=null) throw invalid("页面对象引用不能含派生定位器");}
                    raw.add(new UiAutomationStep(s.id(),s.type(),s.name(),s.pageObjectName()==null?s.selector():"testhub-reference",null,s.value(),s.url(),s.expectedText(),s.timeoutMillis()));}
                var steps=automation.normalizeSyncSteps(raw); var portable=new ArrayList<Step>();
                for(int n=0;n<steps.size();n++) {var s=steps.get(n);var ref=d.steps().get(n).pageObjectName(); portable.add(new Step(s.id(),s.type(),s.name(),ref==null?s.selector():null,ref,s.value(),s.url(),s.expectedText(),s.timeoutMillis()));}
                normalized=new Scenario(d.browserProfileName(),description(d.description(),2000),d.failureStrategy(),automation.normalizeSyncVariables(d.variables()),portable);}
            case "SCHEDULE" -> {var d=definition(item,Schedule.class);requireText(d.scenarioName(),160);
                if(d.functionalPlanCode()!=null) requireText(d.functionalPlanCode(),40);
                var p=scheduleService.normalizeSyncFields(new UiAutomationScheduleService.ScheduleInput("",null,item.name(),d.cronExpression(),d.timezone(),d.timeoutSeconds(),d.maxRetryAttempts(),d.retryBackoffSeconds(),false));
                normalized=new Schedule(d.scenarioName(),d.functionalPlanCode()==null?null:d.functionalPlanCode().toUpperCase(Locale.ROOT),p.cronExpression(),p.timezone(),p.timeoutSeconds(),p.maxRetryAttempts(),p.retryBackoffSeconds());}
            default -> throw invalid("Web UI 类型无效");
        }
        if(!json.valueToTree(normalized).equals(json.valueToTree(item.definition()))) throw invalid("Web UI 字段不合法或尚未规范化");
    }
    private void validateReferences(List<Item> items,List<String> codes) {
        var names=new HashSet<String>();items.forEach(i->names.add(i.kind()+"\n"+i.name()));
        for(var i:items) {
            if(i.kind().equals("SCENARIO")) {var d=definition(i,Scenario.class); requireReference(names,"PROFILE",d.browserProfileName()); for(var s:d.steps()) if(s.pageObjectName()!=null) requireReference(names,"PAGE_OBJECT",s.pageObjectName());}
            if(i.kind().equals("SCHEDULE")) {var d=definition(i,Schedule.class);requireReference(names,"SCENARIO",d.scenarioName()); if(d.functionalPlanCode()!=null&&codes.stream().filter(c->c.equalsIgnoreCase(d.functionalPlanCode())).count()!=1) throw conflict("目标缺少唯一功能测试计划编号 "+d.functionalPlanCode()+"，请先迁移该计划");}
        }
    }
    private void requireReference(Set<String> names,String kind,String name) {if(!names.contains(kind+"\n"+name)) throw conflict("Web UI 引用缺失或名称大小写不同，请先统一相关资料");}
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireText(String v,int max) {if(v==null||v.isBlank()||!v.equals(v.trim())||v.length()>max) throw invalid("Web UI 名称或引用不合法");}
    private String description(String v,int max) {if(v==null||!v.equals(v.trim())||v.length()>max) throw invalid("Web UI 说明不合法");return v;}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    private <T> T definition(Item item,Class<T> type) {return decodeValue(encoded(item.definition()),type);}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("Web UI 同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("Web UI 同步序列化失败",e);}}
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private ResponseStatusException invalid(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Profile(String browser,String baseUrl,Integer viewportWidth,Integer viewportHeight) { }
    public record PageObject(String selectorValue,String description) { }
    public record Scenario(String browserProfileName,String description,String failureStrategy,Map<String,String> variables,List<Step> steps) { }
    public record Step(String id,String type,String name,String selector,String pageObjectName,String value,String url,String expectedText,Integer timeoutMillis) { }
    public record Schedule(String scenarioName,String functionalPlanCode,String cronExpression,String timezone,Integer timeoutSeconds,Integer maxRetryAttempts,Integer retryBackoffSeconds) { }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,List<String> functionalPlanCodes,long excludedExecutions) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record RevisionRow(String id,Integer version,Boolean enabled,String updatedAt,Item item) { }
}
