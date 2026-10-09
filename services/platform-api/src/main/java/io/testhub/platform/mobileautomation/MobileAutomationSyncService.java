package io.testhub.platform.mobileautomation;

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

/** Portable definitions use names for references. Execution history and authority remain per endpoint. */
@Service
public class MobileAutomationSyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final List<String> KINDS = List.of("DEVICE", "APPLICATION", "ELEMENT", "SCENARIO");
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final MobileAutomationService automation;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public MobileAutomationSyncService(AccessService access,MobileAutomationService automation,AuditService audit,JdbcClient jdbc,ObjectMapper json) {
        this.access=access;this.automation=automation;this.audit=audit;this.jdbc=jdbc;this.json=json;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(actor, project);
    }
    private Snapshot read(AuthenticatedPrincipal actor,String project) {
        for(String table:List.of("mobile_automation_device","mobile_automation_application","mobile_automation_element","mobile_automation_scenario"))
            if(jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",project).query(Long.class).single()>100) throw invalid("每项目每类移动端同步资料最多 100 条");
        long bytes=jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(variables)+OCTET_LENGTH(steps)),0) FROM mobile_automation_scenario WHERE project_id=:p").param("p",project).query(Long.class).single()
                +jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(capabilities)),0) FROM mobile_automation_device WHERE project_id=:p").param("p",project).query(Long.class).single();
        if(bytes>SNAPSHOT_BYTES) throw invalid("移动端资料超过 3 MiB 同步范围");
        var devices=automation.devices(actor,project);var applications=automation.applications(actor,project);
        var elements=automation.elements(actor,project);var scenarios=automation.scenarios(actor,project);
        var deviceNames=new HashMap<String,String>();devices.forEach(d->deviceNames.put(d.id(),d.name()));
        var appNames=new HashMap<String,String>();applications.forEach(d->appNames.put(d.id(),d.name()));
        var elementNames=new HashMap<String,String>();elements.forEach(d->elementNames.put(d.id(),d.name()));
        var items=new ArrayList<Item>();var revisions=new ArrayList<RevisionRow>();
        for(var d:devices) add(items,revisions,d.id(),d.version(),d.enabled(),null,d.updatedAt().toString(),item("DEVICE",d.name(),new Device(d.platform(),d.udid(),d.automationName(),d.capabilities())));
        for(var d:applications) add(items,revisions,d.id(),d.version(),d.enabled(),d.appFileId(),d.updatedAt().toString(),item("APPLICATION",d.name(),new Application(d.platform(),d.appIdentifier(),d.versionName())));
        for(var d:elements) add(items,revisions,d.id(),null,null,null,d.updatedAt().toString(),item("ELEMENT",d.name(),new Element(d.platform(),d.locatorStrategy(),d.locatorValue(),d.description())));
        for(var d:scenarios) add(items,revisions,d.id(),d.version(),null,null,d.updatedAt().toString(),item("SCENARIO",d.name(),new Scenario(reference(deviceNames,d.deviceId()),reference(appNames,d.applicationId()),d.description(),d.failureStrategy(),d.variables(),d.steps().stream().map(step->
                new Step(step.id(),step.type(),step.name(),step.elementId()==null?null:reference(elementNames,step.elementId()),step.value(),step.expectedText(),step.timeoutMillis())).toList())));
        validateItems(items);validateReferences(items);revisions.sort(Comparator.comparing(RevisionRow::id));
        long executions=jdbc.sql("SELECT COUNT(*) FROM mobile_automation_execution WHERE project_id=:p").param("p",project).query(Long.class).single();
        long files=applications.stream().filter(d->d.appFileId()!=null).count();
        var result=new Snapshot(1,"MOBILE_AUTOMATION_SYNC",project,actor.userId(),hash(encoded(revisions)),items.stream().sorted(Comparator.comparing(this::key)).toList(),executions,files);
        if(encoded(result).length>SNAPSHOT_BYTES) throw invalid("移动端资料超过 3 MiB 同步范围");return result;
    }
    private void add(List<Item> items,List<RevisionRow> rows,String id,Integer version,Boolean enabled,String fileId,String updated,Item item) {
        items.add(item);rows.add(new RevisionRow(id,version,enabled,fileId,updated,item));
    }
    private String reference(Map<String,String> names,String id) { var name=names.get(id); if(name==null) throw invalid("移动端资料引用缺失，请先核对"); return name; }
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("移动端同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"移动端同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("移动端同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>400)
            throw invalid("移动端同步版本或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item()); if(!keys.add(key(op.item()))) throw invalid("同一 移动端资料不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"移动端同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); automation.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM mobile_automation_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他 移动端资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("移动端同步回执损坏",error); }
        }
        var current=read(actor,project); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标 移动端资料已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>(); current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {var old=merged.get(key(op.item())); if(old!=null&&!old.name().equals(op.item().name())) throw conflict("移动端名称大小写不同，请先在两端统一名称"); merged.put(key(op.item()),op.item());}
        var mergedItems=new ArrayList<>(merged.values()); validateItems(mergedItems); validateReferences(mergedItems); validatePlatforms(current.items(),request.operations());
        if(encoded(mergedItems).length>SNAPSHOT_BYTES) throw invalid("合并后的 移动端资料超过 3 MiB 同步范围");
        try {
            for(String kind:KINDS) for(var op:request.operations()) if(kind.equals(op.item().kind())) write(actor,project,op.item());
            read(actor,project);
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item())).toList());
            jdbc.sql("INSERT INTO mobile_automation_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"MOBILE_AUTOMATION_SYNCED","MOBILE_AUTOMATION_SYNC",requestKey,Map.of("assets",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("移动端名称与目标数据库唯一约束冲突，请统一名称；整批未写入");}
    }
    private void write(AuthenticatedPrincipal actor,String project,Item item) {
        switch(item.kind()) {
            case "DEVICE" -> {var d=definition(item,Device.class);var old=automation.devices(actor,project).stream().filter(p->p.name().equals(item.name())).findFirst();
                var input=new MobileAutomationService.DeviceRequest(item.name(),d.platform(),d.udid(),d.automationName(),d.capabilities(),old.map(MobileAutomationService.Device::enabled).orElse(false));
                if(old.isPresent()) automation.updateDevice(actor,project,old.get().id(),old.get().version(),input);else automation.createDevice(actor,project,input);}
            case "APPLICATION" -> {var d=definition(item,Application.class);var old=automation.applications(actor,project).stream().filter(p->p.name().equals(item.name())).findFirst();
                var input=new MobileAutomationService.ApplicationRequest(item.name(),d.platform(),d.appIdentifier(),d.versionName(),old.map(MobileAutomationService.Application::appFileId).orElse(null),old.map(MobileAutomationService.Application::enabled).orElse(false));
                if(old.isPresent()) automation.updateApplication(actor,project,old.get().id(),old.get().version(),input);else automation.createApplication(actor,project,input);}
            case "ELEMENT" -> {var d=definition(item,Element.class);var old=automation.elements(actor,project).stream().filter(p->p.name().equals(item.name())).findFirst();
                var input=new MobileAutomationService.ElementRequest(item.name(),d.platform(),d.locatorStrategy(),d.locatorValue(),d.description());
                if(old.isPresent()) automation.updateElement(actor,project,old.get().id(),old.get().updatedAt(),input);else automation.createElement(actor,project,input);}
            case "SCENARIO" -> {var d=definition(item,Scenario.class);var devices=new HashMap<String,String>();automation.devices(actor,project).forEach(p->devices.put(p.name(),p.id()));
                var apps=new HashMap<String,String>();automation.applications(actor,project).forEach(p->apps.put(p.name(),p.id()));var elements=new HashMap<String,String>();automation.elements(actor,project).forEach(p->elements.put(p.name(),p.id()));
                var steps=d.steps().stream().map(p->new MobileAutomationService.Step(p.id(),p.type(),p.name(),p.elementName()==null?null:reference(elements,p.elementName()),p.value(),p.expectedText(),p.timeoutMillis())).toList();
                var input=new MobileAutomationService.ScenarioRequest(reference(devices,d.deviceName()),reference(apps,d.applicationName()),item.name(),d.description(),d.failureStrategy(),d.variables(),steps);
                var old=automation.scenarios(actor,project).stream().filter(p->p.name().equals(item.name())).findFirst();
                if(old.isPresent()) automation.updateScenario(actor,project,old.get().id(),old.get().version(),input);else automation.createScenario(actor,project,input);}
            default -> throw invalid("移动端资料类型无效");
        }
    }
    private void validateItems(List<Item> items) {
        var counts=new HashMap<String,Integer>(); var keys=new HashSet<String>();
        for(var item:items) {validate(item); if(!keys.add(key(item))) throw invalid("移动端资料存在同名或大小写重复，请先统一名称"); if(counts.merge(item.kind(),1,Integer::sum)>100) throw invalid("每项目每类 移动端同步资料最多 100 条");}
    }
    private void validate(Item item) {
        if(item==null||item.kind()==null||!KINDS.contains(item.kind())||item.definition()==null) throw invalid("移动端资料类型无效");
        requireText(item.name(),item.kind().equals("SCENARIO")?160:120);
        Object normalized;
        switch(item.kind()) {
            case "DEVICE" -> {var d=definition(item,Device.class);var p=automation.normalizeSyncDevice(new MobileAutomationService.DeviceRequest(item.name(),d.platform(),d.udid(),d.automationName(),d.capabilities(),false));normalized=new Device(p.platform(),p.udid(),p.automationName(),p.capabilities());}
            case "APPLICATION" -> {var d=definition(item,Application.class);var p=automation.normalizeSyncApplication(new MobileAutomationService.ApplicationRequest(item.name(),d.platform(),d.appIdentifier(),d.versionName(),null,false));normalized=new Application(p.platform(),p.appIdentifier(),p.versionName());}
            case "ELEMENT" -> {var d=definition(item,Element.class);var p=automation.normalizeSyncElement(new MobileAutomationService.ElementRequest(item.name(),d.platform(),d.locatorStrategy(),d.locatorValue(),d.description()));normalized=new Element(p.platform(),p.locatorStrategy(),p.locatorValue(),p.description());}
            case "SCENARIO" -> {var d=definition(item,Scenario.class);requireText(d.deviceName(),120);requireText(d.applicationName(),120);
                if(!Set.of("STOP","CONTINUE").contains(d.failureStrategy()==null?"":d.failureStrategy())||d.steps()==null||d.steps().isEmpty()||d.steps().size()>300) throw invalid("移动端场景失败策略或步骤无效");
                var steps=new ArrayList<Step>();var ids=new HashSet<String>();
                for(var s:d.steps()) {if(s==null) throw invalid("移动端步骤不能为空");if(s.elementName()!=null) requireText(s.elementName(),120);
                    var p=automation.normalizeSyncStep(new MobileAutomationService.Step(s.id(),s.type(),s.name(),s.elementName()==null?null:"01ARZ3NDEKTSV4RRFFQ69G5FAV",s.value(),s.expectedText(),s.timeoutMillis()));
                    if(!ids.add(p.id())) throw invalid("步骤标识重复");steps.add(new Step(p.id(),p.type(),p.name(),s.elementName(),p.value(),p.expectedText(),p.timeoutMillis()));}
                normalized=new Scenario(d.deviceName(),d.applicationName(),description(d.description(),2000),d.failureStrategy(),automation.normalizeSyncVariables(d.variables()),steps);}
            default -> throw invalid("移动端类型无效");
        }
        if(!json.valueToTree(normalized).equals(json.valueToTree(item.definition()))) throw invalid("移动端字段不合法或尚未规范化");
    }
    private void validateReferences(List<Item> items) {
        var names=new HashMap<String,Item>();items.forEach(i->names.put(i.kind()+"\n"+i.name(),i));
        var udids=new HashSet<String>();for(var i:items) if(i.kind().equals("DEVICE")&&!udids.add(definition(i,Device.class).udid().toLowerCase(Locale.ROOT))) throw conflict("设备 UDID 重复，请先核对不同名称的设备");
        for(var i:items) if(i.kind().equals("SCENARIO")) {var d=definition(i,Scenario.class);
            var device=requireReference(names,"DEVICE",d.deviceName());var app=requireReference(names,"APPLICATION",d.applicationName());
            String platform=definition(device,Device.class).platform();if(!platform.equals(definition(app,Application.class).platform())) throw conflict("设备与应用的平台必须一致");
            for(var step:d.steps()) if(step.elementName()!=null&&!platform.equals(definition(requireReference(names,"ELEMENT",step.elementName()),Element.class).platform())) throw conflict("步骤元素与设备的平台必须一致");
        }
    }
    private Item requireReference(Map<String,Item> names,String kind,String name) {var item=names.get(kind+"\n"+name);if(item==null) throw conflict("移动端引用缺失或名称大小写不同，请先统一相关资料");return item;}
    private void validatePlatforms(List<Item> current,List<Operation> operations) {
        var existing=new HashMap<String,Item>();current.forEach(i->existing.put(key(i),i));
        for(var op:operations) {var old=existing.get(key(op.item()));if(old!=null&&!op.item().kind().equals("SCENARIO")&&!Objects.equals(old.definition().get("platform"),op.item().definition().get("platform"))) throw conflict("同名移动端资料平台不同，请手动统一名称；不能覆盖平台");}
    }
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireText(String v,int max) {if(v==null||v.isBlank()||!v.equals(v.trim())||v.length()>max) throw invalid("移动端名称或引用不合法");}
    private String description(String v,int max) {if(v==null||!v.equals(v.trim())||v.length()>max) throw invalid("移动端说明不合法");return v;}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    private <T> T definition(Item item,Class<T> type) {return decodeValue(encoded(item.definition()),type);}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("移动端同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("移动端同步序列化失败",e);}}
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private ResponseStatusException invalid(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Device(String platform,String udid,String automationName,Map<String,String> capabilities) { }
    public record Application(String platform,String appIdentifier,String versionName) { }
    public record Element(String platform,String locatorStrategy,String locatorValue,String description) { }
    public record Scenario(String deviceName,String applicationName,String description,String failureStrategy,Map<String,String> variables,List<Step> steps) { }
    public record Step(String id,String type,String name,String elementName,String value,String expectedText,Integer timeoutMillis) { }
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,long excludedExecutions,long excludedFiles) { }
    public record Operation(String operationId,Item item) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item) { }
    public record Receipt(String requestKey,List<Result> results) { }
    private record RevisionRow(String id,Integer version,Boolean enabled,String fileId,String updatedAt,Item item) { }
}
