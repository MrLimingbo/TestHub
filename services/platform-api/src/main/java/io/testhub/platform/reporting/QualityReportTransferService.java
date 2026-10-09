package io.testhub.platform.reporting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
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

/** Copies schedule configuration only; report values are derived from target execution records. */
@Service
public class QualityReportTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private final AccessService access;
    private final QualityReportScheduleRepository repository;
    private final QualityReportScheduleService schedules;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public QualityReportTransferService(AccessService access, QualityReportScheduleRepository repository,
            QualityReportScheduleService schedules, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access=access; this.repository=repository; this.schedules=schedules; this.audit=audit; this.jdbc=jdbc; this.json=json;
    }
    public Map<String,Object> capabilities(AuthenticatedPrincipal actor,String projectId) {
        writable(actor,projectId);
        return Map.of("version",1,"scope","QUALITY_REPORT","maxBytes",MAX_BYTES,"includes",List.of("schedules"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal actor,String projectId) {
        var anchor=writable(actor,projectId);
        var selected=repository.findByOrganization(anchor.organizationId()).stream().filter(s->s.projectIds().contains(projectId)).toList();
        Map<String,Project> projects=new LinkedHashMap<>();
        projects.put(projectId,new Project(projectId,anchor.code()));
        for(var schedule:selected) for(String id:schedule.projectIds()) {
            var project=writable(actor,id);
            if(!project.organizationId().equals(anchor.organizationId())) throw invalid("定时报告项目组织不一致");
            projects.put(id,new Project(id,project.code()));
        }
        var result=new Snapshot(1,projectId,List.copyOf(projects.values()),selected.stream().map(s->
                new Schedule(s.id(),s.name(),s.projectIds(),s.cronExpression(),s.timezone(),s.periodDays())).toList());
        validate(result); encoded(result); return result;
    }
    public ImportRequest decode(byte[] body) {
        if(body==null||body.length==0||body.length>MAX_BYTES) throw invalid("质量报告迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request=json.readerFor(ImportRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
            if(request==null||request.snapshot()==null) throw invalid("质量报告迁移资料不能为空");
            return request;
        } catch(java.io.IOException error) { throw invalid("质量报告迁移 JSON 格式不合法或包含不支持的字段"); }
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal actor,String projectId,ImportRequest request) {
        var anchor=writable(actor,projectId);
        if(request==null||request.requestKey()==null||!request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("requestKey 必须是 UUID");
        if(!actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览");
        String key=UUID.fromString(request.requestKey()).toString(),hash=hash(encoded(request.snapshot()));
        // Schedules are organization-scoped, so imports from different anchor projects share this lock.
        jdbc.sql("SELECT id FROM organization WHERE id=:org FOR UPDATE").param("org",anchor.organizationId()).query(String.class).single();
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM quality_report_transfer_receipt WHERE project_id=:project AND requested_by=:user AND request_key=:key FOR UPDATE")
                .param("project",projectId).param("user",actor.userId()).param("key",key).query((rs,row)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!hash.equals(prior.get().getKey())) throw conflict("该 requestKey 已用于其他迁移资料");
            try { return json.readValue(prior.get().getValue(),Receipt.class); }
            catch(java.io.IOException error) { throw new IllegalStateException("质量报告迁移回执损坏",error); }
        }
        var snapshot=request.snapshot(); validate(snapshot);
        if(snapshot.schedules().isEmpty()) throw invalid("来源项目没有可迁移的定时质量报告");
        var targetProjects=access.context(actor).projects().getOrDefault(anchor.organizationId(),List.of());
        Map<String,String> mapped=new LinkedHashMap<>(); mapped.put(snapshot.sourceProjectId(),projectId);
        for(var project:snapshot.projects()) if(!project.id().equals(snapshot.sourceProjectId())) {
            var matches=targetProjects.stream().filter(p->p.code().equals(project.code())).toList();
            if(matches.size()!=1) throw conflict("目标组织缺少唯一的关联项目编码："+project.code());
            var target=writable(actor,matches.getFirst().id());
            if(mapped.containsValue(target.id())) throw conflict("多个来源项目不能映射到同一目标项目");
            mapped.put(project.id(),target.id());
        }
        Set<String> names=new HashSet<>(repository.findByOrganization(anchor.organizationId()).stream().map(QualityReportSchedule::name).toList());
        for(var schedule:snapshot.schedules()) {
            if(!names.add(schedule.name().trim())) throw conflict("目标组织已存在同名质量报告计划");
            schedules.create(actor,new QualityReportScheduleService.ScheduleInput(schedule.name(),schedule.projectIds().stream().map(mapped::get).toList(),
                    schedule.cronExpression(),schedule.timezone(),schedule.periodDays(),false));
        }
        var result=new Receipt(UlidCreator.getUlid().toString(),snapshot.schedules().size(),mapped.size());
        jdbc.sql("INSERT INTO quality_report_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:hash,:result)")
                .param("id",result.id()).param("org",anchor.organizationId()).param("project",projectId).param("user",actor.userId()).param("key",key).param("hash",hash)
                .param("result",new String(encoded(result),StandardCharsets.UTF_8)).update();
        audit.record(actor,anchor.organizationId(),projectId,"QUALITY_REPORT_TRANSFERRED","QUALITY_REPORT_TRANSFER",result.id(),Map.of("schedules",result.schedules(),"projects",result.projects()));
        return result;
    }
    private void validate(Snapshot value) {
        if(value==null||value.version()!=1||!id(value.sourceProjectId())||value.projects()==null||value.projects().isEmpty()||value.projects().size()>1000||value.schedules()==null||value.schedules().size()>1000) throw invalid("质量报告迁移协议或资料数量不合法");
        Set<String> projectIds=new HashSet<>(),codes=new HashSet<>(),scheduleIds=new HashSet<>(),names=new HashSet<>(),used=new HashSet<>();
        for(var project:value.projects()) if(project==null||!id(project.id())||!projectIds.add(project.id())||project.code()==null||project.code().isBlank()||project.code().length()>64||!codes.add(project.code())) throw invalid("关联项目标识或编码不合法");
        if(!projectIds.contains(value.sourceProjectId())) throw invalid("缺少来源项目关联");
        used.add(value.sourceProjectId());
        for(var schedule:value.schedules()) {
            if(schedule==null||!id(schedule.id())||!scheduleIds.add(schedule.id())||schedule.name()==null||schedule.name().isBlank()||schedule.name().trim().length()>160||!names.add(schedule.name().trim())||schedule.projectIds()==null||schedule.projectIds().isEmpty()||schedule.projectIds().size()>50||new HashSet<>(schedule.projectIds()).size()!=schedule.projectIds().size()||!projectIds.containsAll(schedule.projectIds())||!schedule.projectIds().contains(value.sourceProjectId())) throw invalid("定时质量报告标识、名称或项目引用不合法");
            used.addAll(schedule.projectIds());
        }
        if(!used.equals(projectIds)) throw invalid("迁移资料包含未被定时报告引用的关联项目");
    }
    private ProjectSummary writable(AuthenticatedPrincipal actor,String projectId) { return access.requireProjectParameterWrite(actor,projectId,WRITERS); }
    private boolean id(String value) { return value!=null&&value.matches("[0-9A-HJKMNP-TV-Z]{26}"); }
    private byte[] encoded(Object value) {
        try { byte[] result=json.writeValueAsBytes(value); if(result.length>MAX_BYTES) throw invalid("质量报告资料超过 32 MiB"); return result; }
        catch(java.io.IOException error) { throw invalid("质量报告资料无法编码"); }
    }
    private String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public record Project(String id,String code) { }
    public record Schedule(String id,String name,List<String> projectIds,String cronExpression,String timezone,Integer periodDays) { }
    public record Snapshot(int version,String sourceProjectId,List<Project> projects,List<Schedule> schedules) { }
    public record ImportRequest(String requestKey,String expectedUserId,Snapshot snapshot) { }
    public record Receipt(String id,int schedules,int projects) { }
}
