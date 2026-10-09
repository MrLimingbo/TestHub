package io.testhub.platform.mobileautomation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicit configuration copy; devices and applications remain disabled and local file identities never cross services. */
@Service
public class MobileAutomationTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD","TESTER");
    private final AccessService access;
    private final MobileAutomationService mobile;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public MobileAutomationTransferService(AccessService access, MobileAutomationService mobile, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access=access; this.mobile=mobile; this.audit=audit; this.jdbc=jdbc; this.json=json;
    }
    public Map<String,Object> capabilities(AuthenticatedPrincipal principal,String projectId) {
        access.requireProjectParameterWrite(principal,projectId,WRITERS);
        return Map.of("version",1,"scope","MOBILE_AUTOMATION","maxBytes",MAX_BYTES,"includes",List.of("devices","applications","elements","scenarios"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal,String projectId) {
        access.requireProjectParameterWrite(principal,projectId,WRITERS);
        var snapshot = new Snapshot(1,projectId,
                mobile.devices(principal,projectId).stream().map(d -> new Device(d.id(),d.name(),d.platform(),d.udid(),d.automationName(),d.capabilities(),d.enabled(),d.version())).toList(),
                mobile.applications(principal,projectId).stream().map(a -> new Application(a.id(),a.name(),a.platform(),a.appIdentifier(),a.versionName(),a.enabled(),a.version(),a.appFileId()!=null)).toList(),
                mobile.elements(principal,projectId).stream().map(e -> new Element(e.id(),e.name(),e.platform(),e.locatorStrategy(),e.locatorValue(),e.description())).toList(),
                mobile.scenarios(principal,projectId).stream().map(s -> new Scenario(s.id(),s.deviceId(),s.applicationId(),s.name(),s.description(),s.failureStrategy(),s.version(),s.variables(),s.steps())).toList());
        validate(snapshot); encoded(snapshot); return snapshot;
    }
    public ImportRequest decode(byte[] body) {
        if(body==null||body.length==0||body.length>MAX_BYTES) throw invalid("移动端迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request=json.readerFor(ImportRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
            if(request==null||request.snapshot()==null) throw invalid("移动端迁移资料不能为空");
            return request;
        } catch(java.io.IOException error) { throw invalid("移动端迁移 JSON 格式不合法或包含不支持的字段"); }
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal,String projectId,ImportRequest request) {
        var project=access.requireProjectParameterWrite(principal,projectId,WRITERS);
        if(request==null||request.requestKey()==null||!request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("requestKey 必须是 UUID");
        if(!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览");
        String key=UUID.fromString(request.requestKey()).toString(),hash=sha256(encoded(request.snapshot()));
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE").param("project",projectId).query(String.class).single();
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM mobile_automation_transfer_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key FOR UPDATE")
                .param("project",projectId).param("user",principal.userId()).param("key",key).query((rs,row)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(hash)) throw conflict("该 requestKey 已用于其他迁移资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); }
            catch(JsonProcessingException error) { throw new IllegalStateException("移动端迁移回执损坏",error); }
        }
        Snapshot source=request.snapshot(); validate(source);
        if(source.devices().isEmpty()&&source.applications().isEmpty()&&source.elements().isEmpty()&&source.scenarios().isEmpty()) throw invalid("本地项目没有可迁移的移动端资料");
        conflicts(source.devices(),Device::name,mobile.devices(principal,projectId).stream().map(MobileAutomationService.Device::name).toList());
        conflicts(source.devices(),Device::udid,mobile.devices(principal,projectId).stream().map(MobileAutomationService.Device::udid).toList());
        conflicts(source.applications(),Application::name,mobile.applications(principal,projectId).stream().map(MobileAutomationService.Application::name).toList());
        conflicts(source.elements(),Element::name,mobile.elements(principal,projectId).stream().map(MobileAutomationService.Element::name).toList());
        conflicts(source.scenarios(),Scenario::name,mobile.scenarios(principal,projectId).stream().map(MobileAutomationService.Scenario::name).toList());
        Map<String,String> deviceIds=new HashMap<>(),appIds=new HashMap<>(),elementIds=new HashMap<>();
        for(Device d:source.devices()) {
            var item=mobile.createDevice(principal,projectId,new MobileAutomationService.DeviceRequest(d.name(),d.platform(),d.udid(),d.automationName(),d.capabilities(),false));
            restoreVersion("mobile_automation_device",projectId,item.id(),d.version()); deviceIds.put(d.id(),item.id());
        }
        for(Application a:source.applications()) {
            var item=mobile.createApplication(principal,projectId,new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),a.versionName(),null,false));
            restoreVersion("mobile_automation_application",projectId,item.id(),a.version()); appIds.put(a.id(),item.id());
        }
        for(Element e:source.elements()) {
            var item=mobile.createElement(principal,projectId,new MobileAutomationService.ElementRequest(e.name(),e.platform(),e.locatorStrategy(),e.locatorValue(),e.description()));
            elementIds.put(e.id(),item.id());
        }
        for(Scenario s:source.scenarios()) {
            var steps=s.steps().stream().map(step->new MobileAutomationService.Step(step.id(),step.type(),step.name(),step.elementId()==null?null:elementIds.get(step.elementId()),step.value(),step.expectedText(),step.timeoutMillis())).toList();
            var item=mobile.createScenario(principal,projectId,new MobileAutomationService.ScenarioRequest(deviceIds.get(s.deviceId()),appIds.get(s.applicationId()),s.name(),s.description(),s.failureStrategy(),s.variables(),steps));
            restoreVersion("mobile_automation_scenario",projectId,item.id(),s.version());
        }
        var receipt=new Receipt(UlidCreator.getUlid().toString(),source.devices().size(),source.applications().size(),source.elements().size(),source.scenarios().size(),
                source.scenarios().stream().mapToInt(s->s.steps().size()).sum(),(int)source.applications().stream().filter(Application::hasAppFile).count());
        jdbc.sql("""
                INSERT INTO mobile_automation_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json)
                VALUES(:id,:org,:project,:user,:key,:hash,:result)
                """).param("id",receipt.id()).param("org",project.organizationId()).param("project",projectId).param("user",principal.userId()).param("key",key)
                .param("hash",hash).param("result",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
        audit.record(principal,project.organizationId(),projectId,"MOBILE_AUTOMATION_TRANSFERRED","MOBILE_AUTOMATION_TRANSFER",receipt.id(),
                Map.of("devices",receipt.devices(),"applications",receipt.applications(),"elements",receipt.elements(),"scenarios",receipt.scenarios(),"detachedFiles",receipt.detachedFiles()));
        return receipt;
    }
    private void restoreVersion(String table,String project,String id,int version) {
        jdbc.sql("UPDATE "+table+" SET version=:version WHERE project_id=:project AND id=:id").param("version",version).param("project",project).param("id",id).update();
    }
    private <T> void conflicts(List<T> values,Function<T,String> name,List<String> existing) {
        Set<String> names=new HashSet<>(existing);
        for(T value:values) if(!names.add(name.apply(value).trim())) throw conflict("目标项目存在同名移动端资料或相同 UDID："+name.apply(value).trim());
    }
    private <T> Set<String> identities(List<T> values,Function<T,String> id,Function<T,String> name,int limit) {
        if(values==null||values.size()>1000) throw invalid("单类移动端迁移资料不能超过 1000 条");
        Set<String> ids=new HashSet<>(),names=new HashSet<>();
        for(T value:values) {
            if(value==null||!validId(id.apply(value))||!ids.add(id.apply(value))) throw invalid("移动端资料标识无效或重复");
            String label=name.apply(value);
            if(label==null||label.isBlank()||label.trim().length()>limit||!names.add(label.trim())) throw invalid("移动端资料名称无效或重复");
        }
        return ids;
    }
    private void validate(Snapshot source) {
        if(source==null||source.version()!=1||!validId(source.sourceProjectId())) throw invalid("移动端迁移协议或来源项目无效");
        var devices=identities(source.devices(),Device::id,Device::name,120);
        var apps=identities(source.applications(),Application::id,Application::name,120);
        var elements=identities(source.elements(),Element::id,Element::name,120);
        identities(source.scenarios(),Scenario::id,Scenario::name,160);
        Set<String> udids=new HashSet<>();
        for(Device d:source.devices()) {
            version(d.version());
            if(d.udid()==null||d.udid().isBlank()||d.udid().trim().length()>255||!udids.add(d.udid().trim())) throw invalid("设备 UDID 无效或重复");
        }
        for(Application a:source.applications()) version(a.version());
        for(Scenario s:source.scenarios()) {
            version(s.version());
            if(!devices.contains(s.deviceId())||!apps.contains(s.applicationId())||s.steps()==null||s.steps().isEmpty()||s.steps().size()>300) throw invalid("场景引用或步骤数量无效");
            for(var step:s.steps()) if(step==null||(step.elementId()!=null&&!elements.contains(step.elementId()))) throw invalid("场景引用未包含的移动端元素");
        }
    }
    private void version(int value) { if(value<1||value>1_000_000) throw invalid("移动端配置版本无效"); }
    private boolean validId(String value) { return value!=null&&value.matches("[A-Z0-9]{26}"); }
    private byte[] encoded(Object value) {
        try { byte[] bytes=json.writeValueAsBytes(value); if(bytes.length>MAX_BYTES) throw invalid("移动端迁移资料不能超过 32 MiB"); return bytes; }
        catch(JsonProcessingException error) { throw invalid("移动端迁移资料无法序列化"); }
    }
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Snapshot(int version,String sourceProjectId,List<Device> devices,List<Application> applications,List<Element> elements,List<Scenario> scenarios) { }
    public record Device(String id,String name,String platform,String udid,String automationName,Map<String,String> capabilities,boolean enabled,int version) { }
    public record Application(String id,String name,String platform,String appIdentifier,String versionName,boolean enabled,int version,boolean hasAppFile) { }
    public record Element(String id,String name,String platform,String locatorStrategy,String locatorValue,String description) { }
    public record Scenario(String id,String deviceId,String applicationId,String name,String description,String failureStrategy,int version,Map<String,String> variables,List<MobileAutomationService.Step> steps) { }
    public record ImportRequest(String requestKey,String expectedUserId,Snapshot snapshot) { }
    public record Receipt(String id,int devices,int applications,int elements,int scenarios,int steps,int detachedFiles) { }
}
