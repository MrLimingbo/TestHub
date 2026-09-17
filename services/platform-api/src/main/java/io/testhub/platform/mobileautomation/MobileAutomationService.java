package io.testhub.platform.mobileautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Project-scoped mobile assets and immutable execution manifests. Device interaction remains in the Appium Runner. */
@Service
public class MobileAutomationService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> PLATFORMS = Set.of("ANDROID", "IOS");
    private static final Set<String> LOCATORS = Set.of("ACCESSIBILITY_ID", "ID", "XPATH", "ANDROID_UIAUTOMATOR", "IOS_PREDICATE");
    private static final Set<String> STEP_TYPES = Set.of("LAUNCH", "TAP", "INPUT", "ASSERT_VISIBLE", "ASSERT_TEXT", "WAIT", "SCREENSHOT");
    private static final Set<String> SAFE_DEVICE_CAPABILITIES = Set.of("appium:platformVersion", "appium:deviceName", "appium:noReset", "appium:fullReset", "appium:newCommandTimeout", "appium:autoGrantPermissions", "appium:language", "appium:locale", "appium:appActivity", "appium:appWaitActivity");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AccessService access;
    private final AuditService audit;
    private final StringRedisTemplate redis;
    private volatile boolean eventGroupReady;
    private volatile boolean stepGroupReady;

    public MobileAutomationService(JdbcClient jdbc, ObjectMapper json, AccessService access, AuditService audit, StringRedisTemplate redis) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.audit = audit; this.redis = redis;
    }

    public Snapshot snapshot(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return new Snapshot(devices(projectId), applications(projectId), elements(projectId), scenarios(projectId), executions(projectId));
    }
    public List<Device> devices(AuthenticatedPrincipal principal, String projectId) { access.requireProjectRead(principal, projectId); return devices(projectId); }
    public List<Application> applications(AuthenticatedPrincipal principal, String projectId) { access.requireProjectRead(principal, projectId); return applications(projectId); }
    public List<Element> elements(AuthenticatedPrincipal principal, String projectId) { access.requireProjectRead(principal, projectId); return elements(projectId); }
    public List<Scenario> scenarios(AuthenticatedPrincipal principal, String projectId) { access.requireProjectRead(principal, projectId); return scenarios(projectId); }
    public List<Execution> executions(AuthenticatedPrincipal principal, String projectId) { access.requireProjectRead(principal, projectId); return executions(projectId); }

    @Transactional
    public Device createDevice(AuthenticatedPrincipal principal, String projectId, DeviceRequest request) {
        ProjectSummary project = writable(principal, projectId); DeviceInput input = deviceInput(request); String id = id();
        try {
            jdbc.sql("""
                    INSERT INTO mobile_automation_device (id, organization_id, project_id, name, platform, udid, automation_name, capabilities, enabled, created_by, updated_by)
                    VALUES (:id,:org,:project,:name,:platform,:udid,:automation,CAST(:capabilities AS JSON),:enabled,:user,:user)
                    """)
                    .param("id", id).param("org", project.organizationId()).param("project", projectId).param("name", input.name()).param("platform", input.platform()).param("udid", input.udid()).param("automation", input.automationName()).param("capabilities", write(input.capabilities())).param("enabled", input.enabled()).param("user", principal.userId()).update();
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名设备或相同 UDID"); }
        Device value = device(projectId, id); audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_DEVICE_CREATED", "MOBILE_AUTOMATION_DEVICE", id, Map.of("name", value.name(), "platform", value.platform())); return value;
    }
    @Transactional
    public void deleteDevice(AuthenticatedPrincipal principal, String projectId, String deviceId) {
        ProjectSummary project = writable(principal, projectId); Device value = device(projectId, deviceId);
        try { jdbc.sql("DELETE FROM mobile_automation_device WHERE project_id=:project AND id=:id").param("project", projectId).param("id", deviceId).update(); }
        catch (DataIntegrityViolationException exception) { throw conflict("设备已被移动端场景或执行记录引用，不能删除"); }
        audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_DEVICE_DELETED", "MOBILE_AUTOMATION_DEVICE", deviceId, Map.of("name", value.name()));
    }
    @Transactional
    public Application createApplication(AuthenticatedPrincipal principal, String projectId, ApplicationRequest request) {
        ProjectSummary project = writable(principal, projectId); ApplicationInput input = applicationInput(request); String id = id();
        try {
            jdbc.sql("""
                    INSERT INTO mobile_automation_application (id, organization_id, project_id, name, platform, app_identifier, version_name, app_file_id, enabled, created_by, updated_by)
                    VALUES (:id,:org,:project,:name,:platform,:identifier,:version,:file,:enabled,:user,:user)
                    """)
                    .param("id", id).param("org", project.organizationId()).param("project", projectId).param("name", input.name()).param("platform", input.platform()).param("identifier", input.appIdentifier()).param("version", input.versionName()).param("file", input.appFileId()).param("enabled", input.enabled()).param("user", principal.userId()).update();
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名移动应用"); }
        Application value = application(projectId, id); audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_APPLICATION_CREATED", "MOBILE_AUTOMATION_APPLICATION", id, Map.of("name", value.name(), "platform", value.platform())); return value;
    }
    @Transactional
    public void deleteApplication(AuthenticatedPrincipal principal, String projectId, String applicationId) {
        ProjectSummary project = writable(principal, projectId); Application value = application(projectId, applicationId);
        try { jdbc.sql("DELETE FROM mobile_automation_application WHERE project_id=:project AND id=:id").param("project", projectId).param("id", applicationId).update(); }
        catch (DataIntegrityViolationException exception) { throw conflict("应用已被移动端场景或执行记录引用，不能删除"); }
        audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_APPLICATION_DELETED", "MOBILE_AUTOMATION_APPLICATION", applicationId, Map.of("name", value.name()));
    }
    @Transactional
    public Element createElement(AuthenticatedPrincipal principal, String projectId, ElementRequest request) {
        ProjectSummary project = writable(principal, projectId); ElementInput input = elementInput(request); String id = id();
        try {
            jdbc.sql("""
                    INSERT INTO mobile_automation_element (id, organization_id, project_id, name, platform, locator_strategy, locator_value, description, created_by, updated_by)
                    VALUES (:id,:org,:project,:name,:platform,:strategy,:value,:description,:user,:user)
                    """)
                    .param("id", id).param("org", project.organizationId()).param("project", projectId).param("name", input.name()).param("platform", input.platform()).param("strategy", input.locatorStrategy()).param("value", input.locatorValue()).param("description", input.description()).param("user", principal.userId()).update();
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名移动端元素"); }
        Element value = element(projectId, id); audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_ELEMENT_CREATED", "MOBILE_AUTOMATION_ELEMENT", id, Map.of("name", value.name(), "platform", value.platform())); return value;
    }
    @Transactional
    public void deleteElement(AuthenticatedPrincipal principal, String projectId, String elementId) {
        ProjectSummary project = writable(principal, projectId); Element value = element(projectId, elementId);
        Integer used = jdbc.sql("SELECT COUNT(*) FROM mobile_automation_scenario WHERE project_id=:project AND JSON_SEARCH(steps,'one',:id,NULL,'$[*].elementId') IS NOT NULL")
                .param("project", projectId).param("id", elementId).query(Integer.class).single();
        if (used != null && used > 0) throw conflict("元素正被移动端场景引用，不能删除");
        jdbc.sql("DELETE FROM mobile_automation_element WHERE project_id=:project AND id=:id").param("project", projectId).param("id", elementId).update();
        audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_ELEMENT_DELETED", "MOBILE_AUTOMATION_ELEMENT", elementId, Map.of("name", value.name()));
    }
    @Transactional
    public Scenario createScenario(AuthenticatedPrincipal principal, String projectId, ScenarioRequest request) {
        ProjectSummary project = writable(principal, projectId); ScenarioInput input = scenarioInput(projectId, request); String id = id();
        try {
            jdbc.sql("""
                    INSERT INTO mobile_automation_scenario (id, organization_id, project_id, device_id, application_id, name, description, failure_strategy, variables, steps, created_by, updated_by)
                    VALUES (:id,:org,:project,:device,:application,:name,:description,:strategy,CAST(:variables AS JSON),CAST(:steps AS JSON),:user,:user)
                    """)
                    .param("id", id).param("org", project.organizationId()).param("project", projectId).param("device", input.deviceId()).param("application", input.applicationId()).param("name", input.name()).param("description", input.description()).param("strategy", input.failureStrategy()).param("variables", write(input.variables())).param("steps", write(input.steps())).param("user", principal.userId()).update();
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名移动端场景"); }
        Scenario value = scenario(projectId, id); audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_SCENARIO_CREATED", "MOBILE_AUTOMATION_SCENARIO", id, Map.of("name", value.name(), "stepCount", value.steps().size())); return value;
    }
    @Transactional
    public void deleteScenario(AuthenticatedPrincipal principal, String projectId, String scenarioId) {
        ProjectSummary project = writable(principal, projectId); Scenario value = scenario(projectId, scenarioId);
        try { jdbc.sql("DELETE FROM mobile_automation_scenario WHERE project_id=:project AND id=:id").param("project", projectId).param("id", scenarioId).update(); }
        catch (DataIntegrityViolationException exception) { throw conflict("场景已有执行记录，不能删除"); }
        audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_SCENARIO_DELETED", "MOBILE_AUTOMATION_SCENARIO", scenarioId, Map.of("name", value.name()));
    }
    @Transactional
    public Execution execute(AuthenticatedPrincipal principal, String projectId, String scenarioId, Integer timeoutSeconds) {
        ProjectSummary project = writable(principal, projectId); Scenario scenario = scenario(projectId, scenarioId); Device device = device(projectId, scenario.deviceId()); Application application = application(projectId, scenario.applicationId());
        if (!device.enabled() || !application.enabled()) throw conflict("设备或应用已停用，不能执行场景");
        int timeout = timeout(timeoutSeconds); String executionId = id(), taskId = id(); Instant requested = Instant.now(), deadline = requested.plusSeconds(timeout);
        String manifestRef = "mobile-automation/manifests/" + executionId + "/v1.json";
        jdbc.sql("""
                INSERT INTO mobile_automation_execution (id,task_id,organization_id,project_id,scenario_id,scenario_name,scenario_version,device_id,device_name,platform,application_id,application_name,manifest_ref,requested_by,requested_at,deadline_at)
                VALUES (:id,:task,:org,:project,:scenario,:scenarioName,:scenarioVersion,:device,:deviceName,:platform,:application,:applicationName,:manifest,:user,:requested,:deadline)
                """)
                .param("id", executionId).param("task", taskId).param("org", project.organizationId()).param("project", projectId).param("scenario", scenario.id()).param("scenarioName", scenario.name()).param("scenarioVersion", scenario.version()).param("device", device.id()).param("deviceName", device.name()).param("platform", device.platform()).param("application", application.id()).param("applicationName", application.name()).param("manifest", manifestRef).param("user", principal.userId()).param("requested", Timestamp.from(requested)).param("deadline", Timestamp.from(deadline)).update();
        Execution execution = execution(projectId, executionId);
        Manifest manifest = new Manifest("testhub.appium-runner.manifest", 1, new Manifest.Device(device.name(), device.platform(), device.udid(), device.automationName(), device.capabilities()), new Manifest.Application(application.name(), application.appIdentifier(), application.versionName(), application.appFileId()), new Manifest.Scenario(scenario.name(), scenario.failureStrategy(), scenario.variables(), resolveSteps(projectId, scenario.steps())));
        String content = write(manifest), checksum = sha256(content);
        jdbc.sql("""
                INSERT INTO mobile_automation_execution_manifest (id,execution_id,organization_id,project_id,scenario_id,scenario_version,content,checksum)
                VALUES (:id,:execution,:org,:project,:scenario,:version,CAST(:content AS JSON),:checksum)
                """)
                .param("id", id()).param("execution", executionId).param("org", project.organizationId()).param("project", projectId).param("scenario", scenario.id()).param("version", scenario.version()).param("content", content).param("checksum", checksum).update();
        Map<String, String> task = new LinkedHashMap<>();
        task.put("contract", MobileRunnerProtocol.TASK_CONTRACT); task.put("protocolVersion", MobileRunnerProtocol.VERSION); task.put("taskId", taskId); task.put("executionId", executionId); task.put("organizationId", project.organizationId()); task.put("projectId", projectId); task.put("scenarioId", scenario.id()); task.put("scenarioVersion", String.valueOf(scenario.version())); task.put("requestedAt", requested.toString()); task.put("deadlineAt", deadline.toString()); task.put("manifestRef", manifestRef); task.put("traceId", taskId);
        jdbc.sql("INSERT INTO mobile_automation_runner_outbox (id,execution_id,stream_key,payload) VALUES (:id,:execution,:stream,CAST(:payload AS JSON))")
                .param("id", id()).param("execution", executionId).param("stream", MobileRunnerProtocol.TASK_STREAM).param("payload", write(task)).update();
        audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_EXECUTION_QUEUED", "MOBILE_AUTOMATION_EXECUTION", executionId, Map.of("taskId", taskId, "scenarioId", scenario.id(), "deviceId", device.id(), "applicationId", application.id(), "checksum", checksum));
        return execution;
    }
    public Report report(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId); Execution execution = execution(projectId, executionId); List<StepResult> steps = stepResults(projectId, executionId); int passed = (int) steps.stream().filter(value -> "PASSED".equals(value.outcome())).count(); int failed = (int) steps.stream().filter(value -> "FAILED".equals(value.outcome())).count();
        return new Report(execution, new Summary(steps.size(), passed, failed, steps.stream().mapToLong(StepResult::durationMillis).sum()), steps);
    }
    public RunnerRuntimeConfiguration runtimeConfiguration(String runnerId, String taskId, String executionId) {
        Execution execution = executionByTask(taskId, executionId);
        if (execution == null || terminal(execution.status())) throw rejected("移动端执行已结束或不存在");
        if (execution.runnerId() != null && !runnerId.equals(execution.runnerId())) throw rejected("移动端执行已被其他 Runner 接收");
        if (execution.runnerId() == null || "QUEUED".equals(execution.status())) {
            jdbc.sql("UPDATE mobile_automation_execution SET runner_id=COALESCE(runner_id,:runner),status=CASE WHEN status='QUEUED' THEN 'ACCEPTED' ELSE status END,status_code=CASE WHEN status='QUEUED' THEN 'TASK_ACCEPTED' ELSE status_code END,status_message=CASE WHEN status='QUEUED' THEN '受信任 Appium Runner 已接收任务' ELSE status_message END,started_at=COALESCE(started_at,CURRENT_TIMESTAMP(3)) WHERE id=:id")
                    .param("runner", runnerId).param("id", executionId).update();
        }
        String content = jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:execution")
                .param("execution", executionId).query(String.class).optional().orElseThrow(() -> rejected("移动端执行清单不存在"));
        Manifest manifest = read(content, new TypeReference<Manifest>() { });
        return new RunnerRuntimeConfiguration("testhub.appium-runner.runtime-configuration", 1, taskId, executionId, execution.deadlineAt(), manifest);
    }
    @Transactional
    public Execution cancel(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ProjectSummary project = writable(principal, projectId); Execution current = execution(projectId, executionId);
        if (!Set.of("QUEUED", "ACCEPTED", "RUNNING", "CANCELLING").contains(current.status())) throw conflict("当前执行状态不能取消");
        Instant now = Instant.now();
        jdbc.sql("UPDATE mobile_automation_execution SET status='CANCELLING',status_code='CANCEL_REQUESTED',status_message='取消请求已发送至隔离 Appium Runner',cancel_requested_at=:now WHERE id=:id")
                .param("now", Timestamp.from(now)).param("id", executionId).update();
        enqueueCancellation(current, "USER_CANCELLED", now);
        audit.record(principal, project.organizationId(), projectId, "MOBILE_AUTOMATION_EXECUTION_CANCEL_REQUESTED", "MOBILE_AUTOMATION_EXECUTION", executionId, Map.of("taskId", current.taskId()));
        return execution(projectId, executionId);
    }
    @Transactional
    public void applyRunnerEvent(MobileRunnerProtocol.Event event) {
        String status = Map.of("ACCEPTED", "ACCEPTED", "RUNNING", "RUNNING", "SUCCEEDED", "SUCCEEDED", "FAILED", "FAILED", "CANCELLED", "CANCELLED", "TIMED_OUT", "TIMED_OUT", "REJECTED", "REJECTED").get(event.type()); if (status == null) return;
        Execution current = executionByTask(event.taskId(), event.executionId()); if (current == null || terminal(current.status())) return;
        if ("CANCELLING".equals(current.status()) && Set.of("ACCEPTED", "RUNNING").contains(status)) status = "CANCELLING";
        jdbc.sql("""
                UPDATE mobile_automation_execution SET status=:status,status_code=:code,status_message=:message,runner_id=COALESCE(runner_id,:runner),
                started_at=CASE WHEN :status IN ('ACCEPTED','RUNNING','CANCELLING') AND started_at IS NULL THEN :occurred ELSE started_at END,
                finished_at=CASE WHEN :status IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','REJECTED') THEN :occurred ELSE finished_at END
                WHERE id=:id AND task_id=:task
                """)
                .param("status", status).param("code", event.code()).param("message", event.message()).param("runner", event.runnerId()).param("occurred", Timestamp.from(event.occurredAt())).param("id", current.id()).param("task", event.taskId()).update();
        audit.recordSystem(current.organizationId(), current.projectId(), "MOBILE_AUTOMATION_EXECUTION_EVENT_RECEIVED", "MOBILE_AUTOMATION_EXECUTION", current.id(), Map.of("runnerId", event.runnerId(), "type", event.type(), "code", event.code()));
    }
    @Transactional
    public void applyStepResult(MobileRunnerProtocol.StepResult result) {
        Execution current = executionByTask(result.taskId(), result.executionId()); if (current == null || current.runnerId() == null || !current.runnerId().equals(result.runnerId())) return;
        jdbc.sql("""
                INSERT INTO mobile_automation_execution_step_result (id,execution_id,organization_id,project_id,runner_id,step_id,step_sequence,step_type,step_name,outcome,duration_millis,error_message,occurred_at)
                VALUES (:id,:execution,:org,:project,:runner,:stepId,:sequence,:type,:name,:outcome,:duration,:error,:occurred)
                ON DUPLICATE KEY UPDATE runner_id=VALUES(runner_id),step_type=VALUES(step_type),step_name=VALUES(step_name),outcome=VALUES(outcome),duration_millis=VALUES(duration_millis),error_message=VALUES(error_message),occurred_at=VALUES(occurred_at)
                """)
                .param("id", id()).param("execution", current.id()).param("org", current.organizationId()).param("project", current.projectId()).param("runner", result.runnerId()).param("stepId", result.stepId()).param("sequence", result.stepSequence()).param("type", result.stepType()).param("name", result.stepName()).param("outcome", result.outcome()).param("duration", result.durationMillis()).param("error", result.errorMessage()).param("occurred", Timestamp.from(result.occurredAt())).update();
    }

    @Scheduled(fixedDelayString = "${testhub.mobile-automation-runner.outbox.dispatch-interval-ms:500}")
    @Transactional
    public void dispatchOutbox() {
        List<Outbox> messages = jdbc.sql("SELECT id,stream_key,payload,dispatch_attempts FROM mobile_automation_runner_outbox WHERE status='PENDING' AND available_at<=CURRENT_TIMESTAMP(3) ORDER BY created_at LIMIT 20")
                .query((rs, row) -> new Outbox(rs.getString("id"), rs.getString("stream_key"), read(rs.getString("payload"), new TypeReference<Map<String, String>>() { }), rs.getInt("dispatch_attempts"))).list();
        for (Outbox message : messages) {
            if (jdbc.sql("UPDATE mobile_automation_runner_outbox SET status='SENDING',locked_at=CURRENT_TIMESTAMP(3),dispatch_attempts=dispatch_attempts+1 WHERE id=:id AND status='PENDING'").param("id", message.id()).update() == 0) continue;
            try { redis.opsForStream().add(StreamRecords.mapBacked(message.payload()).withStreamKey(message.stream())); jdbc.sql("UPDATE mobile_automation_runner_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP(3) WHERE id=:id").param("id", message.id()).update(); }
            catch (RuntimeException exception) { jdbc.sql("UPDATE mobile_automation_runner_outbox SET status='PENDING',available_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND),last_error=:error WHERE id=:id").param("id", message.id()).param("error", "Appium Runner Stream 投递失败").update(); }
        }
    }
    @Scheduled(fixedDelayString = "${testhub.mobile-automation-runner.events.poll-interval-ms:500}")
    public void consumeEvents() { consume(MobileRunnerProtocol.EVENT_STREAM, MobileRunnerProtocol.EVENT_GROUP, false); }
    @Scheduled(fixedDelayString = "${testhub.mobile-automation-runner.steps.poll-interval-ms:500}")
    public void consumeSteps() { consume(MobileRunnerProtocol.STEP_STREAM, MobileRunnerProtocol.STEP_GROUP, true); }
    @Scheduled(fixedDelayString = "${testhub.mobile-automation-runner.deadline-check-interval-ms:1000}")
    @Transactional
    public void expireDeadlines() {
        List<Execution> expired = jdbc.sql("SELECT * FROM mobile_automation_execution WHERE status IN ('QUEUED','ACCEPTED','RUNNING','CANCELLING') AND deadline_at<CURRENT_TIMESTAMP(3) ORDER BY deadline_at LIMIT 50")
                .query((rs, row) -> execution(rs)).list();
        Instant now = Instant.now();
        for (Execution execution : expired) {
            if (jdbc.sql("UPDATE mobile_automation_execution SET status='TIMED_OUT',status_code='EXECUTION_DEADLINE_EXCEEDED',status_message='执行超过限定时长，已请求 Runner 释放设备会话',finished_at=:now WHERE id=:id AND status IN ('QUEUED','ACCEPTED','RUNNING','CANCELLING')")
                    .param("now", Timestamp.from(now)).param("id", execution.id()).update() == 0) continue;
            enqueueCancellation(execution, "EXECUTION_TIMEOUT", now);
            audit.recordSystem(execution.organizationId(), execution.projectId(), "MOBILE_AUTOMATION_EXECUTION_TIMED_OUT", "MOBILE_AUTOMATION_EXECUTION", execution.id(), Map.of("taskId", execution.taskId()));
        }
    }
    private void consume(String stream, String group, boolean steps) {
        ensureGroup(stream, group, steps); List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(Consumer.from(group, "platform-mobile-automation"), StreamReadOptions.empty().count(20), StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) try { Map<String, String> fields = fields(record); if (steps) applyStepResult(MobileRunnerProtocol.step(fields)); else applyRunnerEvent(MobileRunnerProtocol.event(fields)); redis.opsForStream().acknowledge(group, record); }
        catch (ResponseStatusException | IllegalArgumentException exception) { redis.opsForStream().acknowledge(group, record); }
    }
    private void enqueueCancellation(Execution execution, String reason, Instant requestedAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("contract", MobileRunnerProtocol.CANCEL_CONTRACT); payload.put("protocolVersion", MobileRunnerProtocol.VERSION); payload.put("commandId", id()); payload.put("taskId", execution.taskId()); payload.put("executionId", execution.id()); payload.put("requestedAt", requestedAt.toString()); payload.put("reasonCode", reason);
        jdbc.sql("INSERT INTO mobile_automation_runner_outbox (id,execution_id,stream_key,payload) VALUES (:id,:execution,:stream,CAST(:payload AS JSON))")
                .param("id", id()).param("execution", execution.id()).param("stream", MobileRunnerProtocol.CANCEL_STREAM).param("payload", write(payload)).update();
    }
    private synchronized void ensureGroup(String stream, String group, boolean steps) {
        if (steps ? stepGroupReady : eventGroupReady) return;
        try { redis.opsForStream().createGroup(stream, ReadOffset.latest(), group); }
        catch (Exception first) { if (!busyGroup(first)) { redis.opsForStream().add(StreamRecords.mapBacked(Map.of("bootstrap", "1")).withStreamKey(stream)); try { redis.opsForStream().createGroup(stream, ReadOffset.latest(), group); } catch (Exception second) { if (!busyGroup(second)) throw second; } } }
        if (steps) stepGroupReady = true; else eventGroupReady = true;
    }

    private List<Device> devices(String projectId) { return jdbc.sql("SELECT * FROM mobile_automation_device WHERE project_id=:project ORDER BY updated_at DESC,name").param("project", projectId).query((rs,row) -> new Device(rs.getString("id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("name"),rs.getString("platform"),rs.getString("udid"),rs.getString("automation_name"),read(rs.getString("capabilities"),new TypeReference<Map<String,String>>(){}),rs.getBoolean("enabled"),rs.getInt("version"),rs.getString("updated_by"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")))).list(); }
    private List<Application> applications(String projectId) { return jdbc.sql("SELECT * FROM mobile_automation_application WHERE project_id=:project ORDER BY updated_at DESC,name").param("project", projectId).query((rs,row) -> new Application(rs.getString("id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("name"),rs.getString("platform"),rs.getString("app_identifier"),rs.getString("version_name"),rs.getString("app_file_id"),rs.getBoolean("enabled"),rs.getInt("version"),rs.getString("updated_by"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")))).list(); }
    private List<Element> elements(String projectId) { return jdbc.sql("SELECT * FROM mobile_automation_element WHERE project_id=:project ORDER BY updated_at DESC,name").param("project", projectId).query((rs,row) -> new Element(rs.getString("id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("name"),rs.getString("platform"),rs.getString("locator_strategy"),rs.getString("locator_value"),rs.getString("description"),rs.getString("updated_by"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")))).list(); }
    private List<Scenario> scenarios(String projectId) { return jdbc.sql("SELECT * FROM mobile_automation_scenario WHERE project_id=:project ORDER BY updated_at DESC,name").param("project", projectId).query((rs,row) -> new Scenario(rs.getString("id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("device_id"),rs.getString("application_id"),rs.getString("name"),rs.getString("description"),rs.getString("failure_strategy"),rs.getInt("version"),read(rs.getString("variables"),new TypeReference<Map<String,String>>(){}),read(rs.getString("steps"),new TypeReference<List<Step>>(){}),rs.getString("updated_by"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at")))).list(); }
    private List<Execution> executions(String projectId) { return jdbc.sql("SELECT * FROM mobile_automation_execution WHERE project_id=:project ORDER BY requested_at DESC,id DESC LIMIT 100").param("project", projectId).query((rs,row) -> execution(rs)).list(); }
    private List<StepResult> stepResults(String projectId, String executionId) { return jdbc.sql("SELECT * FROM mobile_automation_execution_step_result WHERE project_id=:project AND execution_id=:execution ORDER BY step_sequence,occurred_at,id").param("project",projectId).param("execution",executionId).query((rs,row)->new StepResult(rs.getString("id"),rs.getString("execution_id"),rs.getString("runner_id"),rs.getString("step_id"),rs.getInt("step_sequence"),rs.getString("step_type"),rs.getString("step_name"),rs.getString("outcome"),rs.getInt("duration_millis"),rs.getString("error_message"),instant(rs.getTimestamp("occurred_at")))).list(); }
    private Device device(String projectId,String id) { return devices(projectId).stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->notFound("移动设备不存在")); }
    private Application application(String projectId,String id) { return applications(projectId).stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->notFound("移动应用不存在")); }
    private Element element(String projectId,String id) { return elements(projectId).stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->notFound("移动端元素不存在")); }
    private Scenario scenario(String projectId,String id) { return scenarios(projectId).stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->notFound("移动端场景不存在")); }
    private Execution execution(String projectId,String id) { return executions(projectId).stream().filter(value->value.id().equals(id)).findFirst().orElseThrow(()->notFound("移动端执行记录不存在")); }
    private Execution executionByTask(String task,String execution) { return jdbc.sql("SELECT * FROM mobile_automation_execution WHERE task_id=:task AND id=:execution").param("task",task).param("execution",execution).query((rs,row)->execution(rs)).optional().orElse(null); }
    private Execution execution(java.sql.ResultSet rs) throws java.sql.SQLException { return new Execution(rs.getString("id"),rs.getString("task_id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("scenario_id"),rs.getString("scenario_name"),rs.getInt("scenario_version"),rs.getString("device_id"),rs.getString("device_name"),rs.getString("platform"),rs.getString("application_id"),rs.getString("application_name"),rs.getString("status"),rs.getString("status_code"),rs.getString("status_message"),rs.getString("runner_id"),rs.getString("manifest_ref"),rs.getString("requested_by"),instant(rs.getTimestamp("requested_at")),instant(rs.getTimestamp("deadline_at")),instant(rs.getTimestamp("cancel_requested_at")),instant(rs.getTimestamp("started_at")),instant(rs.getTimestamp("finished_at")),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("updated_at"))); }
    private ScenarioInput scenarioInput(String projectId,ScenarioRequest request) { if(request==null) throw invalid("场景不能为空"); Device device=device(projectId,trim(request.deviceId())); Application application=application(projectId,trim(request.applicationId())); if(!device.platform().equals(application.platform())) throw invalid("设备与应用的平台必须一致"); List<Step> steps=request.steps()==null?List.of():request.steps().stream().map(this::stepInput).toList(); if(steps.isEmpty()||steps.size()>300) throw invalid("场景步骤数应在 1 到 300 之间"); if (steps.stream().map(Step::id).distinct().count() != steps.size()) throw invalid("步骤标识不能重复"); for (Step step : steps) if (step.elementId()!=null&&!device.platform().equals(element(projectId,step.elementId()).platform())) throw invalid("步骤元素与目标设备的平台必须一致"); return new ScenarioInput(device.id(),application.id(),required(request.name(),160,"场景名称"),nullable(request.description(),2000),choice(request.failureStrategy(),Set.of("STOP","CONTINUE"),"失败策略"),variables(request.variables()),steps); }
    private Step stepInput(Step value) { if (value == null) throw invalid("步骤不能为空"); String type=choice(value.type(),STEP_TYPES,"步骤类型"); String elementId=blank(value.elementId()); if(Set.of("TAP","INPUT","ASSERT_VISIBLE","ASSERT_TEXT").contains(type)&&elementId==null) throw invalid(type+" 步骤必须引用元素"); if(elementId!=null&&!elementId.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw invalid("元素标识不合法"); return new Step(required(value.id(),64,"步骤标识"),type,required(value.name(),160,"步骤名称"),elementId,nullable(value.value(),4096),nullable(value.expectedText(),4096),timeoutMillis(value.timeoutMillis())); }
    private List<Manifest.ResolvedStep> resolveSteps(String projectId,List<Step> steps) { return steps.stream().map(step->{ Element element=step.elementId()==null?null:element(projectId,step.elementId()); return new Manifest.ResolvedStep(step.id(),step.type(),step.name(),element==null?null:element.locatorStrategy(),element==null?null:element.locatorValue(),step.value(),step.expectedText(),step.timeoutMillis()); }).toList(); }
    private DeviceInput deviceInput(DeviceRequest value) { if(value==null) throw invalid("设备不能为空");
        if (("ANDROID".equalsIgnoreCase(value.platform()) && "XCUITest".equalsIgnoreCase(value.automationName()))
                || ("IOS".equalsIgnoreCase(value.platform()) && ("UiAutomator2".equalsIgnoreCase(value.automationName()) || "Espresso".equalsIgnoreCase(value.automationName())))) {
            throw invalid("自动化引擎与设备平台不匹配");
        }
        return new DeviceInput(required(value.name(),120,"设备名称"),choice(value.platform(),PLATFORMS,"设备平台"),required(value.udid(),255,"UDID"),required(value.automationName(),64,"自动化引擎"),capabilities(value.capabilities()),Boolean.TRUE.equals(value.enabled())); }
    private ApplicationInput applicationInput(ApplicationRequest value) { if(value==null) throw invalid("应用不能为空"); String file=blank(value.appFileId()); if(file!=null&&!file.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw invalid("应用包文件标识不合法"); return new ApplicationInput(required(value.name(),120,"应用名称"),choice(value.platform(),PLATFORMS,"应用平台"),required(value.appIdentifier(),255,"应用标识"),nullable(value.versionName(),120),file,Boolean.TRUE.equals(value.enabled())); }
    private ElementInput elementInput(ElementRequest value) { if(value==null) throw invalid("元素不能为空"); return new ElementInput(required(value.name(),120,"元素名称"),choice(value.platform(),PLATFORMS,"元素平台"),choice(value.locatorStrategy(),LOCATORS,"定位策略"),required(value.locatorValue(),1024,"定位值"),nullable(value.description(),1000)); }
    private Map<String,String> variables(Map<String,String> value) { if(value==null) return Map.of(); if(value.size()>50||value.entrySet().stream().anyMatch(entry->entry.getKey()==null||!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")||entry.getValue()==null||entry.getValue().length()>4096)) throw invalid("变量或能力参数不合法"); return Map.copyOf(value); }
    private Map<String,String> capabilities(Map<String,String> value) { if(value==null) return Map.of(); if(value.size()>50||value.entrySet().stream().anyMatch(entry->entry.getKey()==null||!SAFE_DEVICE_CAPABILITIES.contains(entry.getKey())||entry.getValue()==null||entry.getValue().length()>4096)) throw invalid("设备能力参数不合法或不在白名单内"); return Map.copyOf(value); }
    private int timeout(Integer value) { int valueOrDefault=value==null?300:value; if(valueOrDefault<10||valueOrDefault>3600) throw invalid("执行超时应在 10 到 3600 秒之间"); return valueOrDefault; }
    private Integer timeoutMillis(Integer value) { int valueOrDefault=value==null?30000:value; if(valueOrDefault<1||valueOrDefault>60000) throw invalid("步骤超时应在 1 到 60000 毫秒之间"); return valueOrDefault; }
    private ProjectSummary writable(AuthenticatedPrincipal principal,String projectId) { return access.requireProjectParameterWrite(principal,projectId,WRITERS); }
    private Map<String,String> fields(MapRecord<String,Object,Object> record) { Map<String,String> result=new LinkedHashMap<>(); record.getValue().forEach((key,value)->{if(key!=null&&value!=null)result.put(String.valueOf(key),String.valueOf(value));}); return Map.copyOf(result); }
    private String id() { return UlidCreator.getUlid().toString(); }
    private Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("无法序列化移动端自动化数据",exception);} }
    private <T>T read(String value,TypeReference<T> type) { try{return json.readValue(value,type);}catch(Exception exception){throw new IllegalStateException("无法读取移动端自动化数据",exception);} }
    private String sha256(String value) { try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException("无法计算执行清单校验和",exception);} }
    private String choice(String value,Set<String> allowed,String label) { String normalized=required(value,64,label).toUpperCase(Locale.ROOT); if(!allowed.contains(normalized))throw invalid(label+"不受支持");return normalized; }
    private String required(String value,int max,String label) { String normalized=blank(value);if(normalized==null||normalized.length()>max)throw invalid(label+"长度不合法");return normalized; }
    private String nullable(String value,int max) { String normalized=blank(value);if(normalized==null)return "";if(normalized.length()>max)throw invalid("文本过长");return normalized; }
    private String trim(String value) { return required(value,26,"标识"); }
    private String blank(String value) { if(value==null)return null;String normalized=value.trim();return normalized.isEmpty()?null:normalized; }
    private boolean busyGroup(Exception exception) { for (Throwable value = exception; value != null; value = value.getCause()) if (String.valueOf(value.getMessage()).contains("BUSYGROUP")) return true; return false; }
    private boolean terminal(String value) { return Set.of("SUCCEEDED","FAILED","REJECTED","CANCELLED","TIMED_OUT").contains(value); }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND,message); }
    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }

    public record Device(String id,String organizationId,String projectId,String name,String platform,String udid,String automationName,Map<String,String> capabilities,boolean enabled,int version,String updatedBy,Instant createdAt,Instant updatedAt) { }
    public record Application(String id,String organizationId,String projectId,String name,String platform,String appIdentifier,String versionName,String appFileId,boolean enabled,int version,String updatedBy,Instant createdAt,Instant updatedAt) { }
    public record Element(String id,String organizationId,String projectId,String name,String platform,String locatorStrategy,String locatorValue,String description,String updatedBy,Instant createdAt,Instant updatedAt) { }
    public record Step(String id,String type,String name,String elementId,String value,String expectedText,Integer timeoutMillis) { }
    public record Scenario(String id,String organizationId,String projectId,String deviceId,String applicationId,String name,String description,String failureStrategy,int version,Map<String,String> variables,List<Step> steps,String updatedBy,Instant createdAt,Instant updatedAt) { }
    public record Execution(String id,String taskId,String organizationId,String projectId,String scenarioId,String scenarioName,int scenarioVersion,String deviceId,String deviceName,String platform,String applicationId,String applicationName,String status,String statusCode,String statusMessage,String runnerId,String manifestRef,String requestedBy,Instant requestedAt,Instant deadlineAt,Instant cancelRequestedAt,Instant startedAt,Instant finishedAt,Instant createdAt,Instant updatedAt) { }
    public record StepResult(String id,String executionId,String runnerId,String stepId,int stepSequence,String stepType,String stepName,String outcome,int durationMillis,String errorMessage,Instant occurredAt) { }
    public record Summary(int totalSteps,int passedSteps,int failedSteps,long totalDurationMillis) { }
    public record Report(Execution execution,Summary summary,List<StepResult> steps) { }
    public record RunnerRuntimeConfiguration(String contract,int version,String taskId,String executionId,Instant deadlineAt,Manifest manifest) { }
    public record Snapshot(List<Device> devices,List<Application> applications,List<Element> elements,List<Scenario> scenarios,List<Execution> executions) { }
    public record DeviceRequest(String name,String platform,String udid,String automationName,Map<String,String> capabilities,Boolean enabled) { }
    public record ApplicationRequest(String name,String platform,String appIdentifier,String versionName,String appFileId,Boolean enabled) { }
    public record ElementRequest(String name,String platform,String locatorStrategy,String locatorValue,String description) { }
    public record ScenarioRequest(String deviceId,String applicationId,String name,String description,String failureStrategy,Map<String,String> variables,List<Step> steps) { }
    private record DeviceInput(String name,String platform,String udid,String automationName,Map<String,String> capabilities,boolean enabled) { }
    private record ApplicationInput(String name,String platform,String appIdentifier,String versionName,String appFileId,boolean enabled) { }
    private record ElementInput(String name,String platform,String locatorStrategy,String locatorValue,String description) { }
    private record ScenarioInput(String deviceId,String applicationId,String name,String description,String failureStrategy,Map<String,String> variables,List<Step> steps) { }
    private record Outbox(String id,String stream,Map<String,String> payload,int attempts) { }
    public record Manifest(String contract,int manifestVersion,Device device,Application application,Scenario scenario) {
        public record Device(String name,String platform,String udid,String automationName,Map<String,String> capabilities) { }
        public record Application(String name,String appIdentifier,String versionName,String appFileId) { }
        public record Scenario(String name,String failureStrategy,Map<String,String> variables,List<ResolvedStep> steps) { }
        public record ResolvedStep(String id,String type,String name,String locatorStrategy,String locatorValue,String value,String expectedText,Integer timeoutMillis) { }
    }
}
