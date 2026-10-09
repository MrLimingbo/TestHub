package io.testhub.platform.uiautomation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTestExecutionRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Copies editable Web UI assets with remapped references, never execution authorization or queued work. */
@Service
public class UiAutomationTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final UiAutomationRepository assets;
    private final UiAutomationService automation;
    private final UiAutomationScheduleRepository schedules;
    private final UiAutomationScheduleService scheduleService;
    private final FunctionalTestExecutionRepository functionalPlans;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public UiAutomationTransferService(AccessService access, UiAutomationRepository assets, UiAutomationService automation,
            UiAutomationScheduleRepository schedules, UiAutomationScheduleService scheduleService,
            FunctionalTestExecutionRepository functionalPlans, AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.assets = assets; this.automation = automation; this.schedules = schedules;
        this.scheduleService = scheduleService; this.functionalPlans = functionalPlans;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    public Map<String, Object> capabilities(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        return Map.of("version", 1, "scope", "UI_AUTOMATION", "maxBytes", MAX_BYTES,
                "includes", List.of("profiles", "pageObjects", "scenarios", "schedules"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        var profiles = assets.findProfiles(projectId).stream().map(p -> new Profile(p.id(), p.name(), p.browser(), p.baseUrl(),
                p.viewportWidth(), p.viewportHeight(), p.enabled(), p.version())).toList();
        var objects = assets.findPageObjects(projectId).stream().map(p -> new PageObject(p.id(), p.name(), p.selectorValue(), p.description())).toList();
        var scenarios = assets.findScenarios(projectId).stream().map(s -> new Scenario(s.id(), s.browserProfileId(), s.name(),
                s.description(), s.failureStrategy(), s.version(), s.variables(), s.steps())).toList();
        var timers = schedules.findByProject(projectId).stream().map(s -> new Schedule(s.id(), s.scenarioId(), s.name(),
                s.cronExpression(), s.timezone(), s.timeoutSeconds(), s.maxRetryAttempts(), s.retryBackoffSeconds(), s.enabled(),
                s.functionalPlanId() == null ? null : functionalPlans.findPlan(projectId, s.functionalPlanId())
                        .orElseThrow(() -> invalid("关联的功能测试计划不存在")).planCode())).toList();
        Snapshot snapshot = new Snapshot(1, projectId, profiles, objects, scenarios, timers);
        validate(snapshot); encoded(snapshot); return snapshot;
    }

    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) throw invalid("Web UI 迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request = json.readerFor(ImportRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("Web UI 迁移资料不能为空");
            return request;
        } catch (java.io.IOException error) { throw invalid("Web UI 迁移 JSON 格式不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (request == null || request.requestKey() == null ||
                !request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("requestKey 必须是 UUID");
        String key = UUID.fromString(request.requestKey()).toString();
        if (!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览迁移");
        Snapshot source = request.snapshot(); String hash = sha256(encoded(source));
        jdbc.sql("SELECT id FROM project WHERE id = :project FOR UPDATE").param("project", projectId).query(String.class).single();
        var prior = jdbc.sql("""
                SELECT payload_sha256, result_json FROM ui_automation_transfer_receipt
                WHERE project_id = :project AND requested_by = :actor AND request_key = :key FOR UPDATE
                """).param("project", projectId).param("actor", principal.userId()).param("key", key)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(hash)) throw conflict("该 requestKey 已用于其他迁移资料");
            try { return json.readValue(prior.get().getValue(), Receipt.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Web UI 迁移回执损坏", error); }
        }
        validate(source);
        if (source.profiles().isEmpty() && source.pageObjects().isEmpty() && source.scenarios().isEmpty() && source.schedules().isEmpty())
            throw invalid("本地项目没有可迁移的 Web UI 资料");
        conflicts(source.profiles(), Profile::name, assets.findProfiles(projectId).stream().map(UiAutomationBrowserProfile::name).toList());
        conflicts(source.pageObjects(), PageObject::name, assets.findPageObjects(projectId).stream().map(UiAutomationPageObject::name).toList());
        conflicts(source.scenarios(), Scenario::name, assets.findScenarios(projectId).stream().map(UiAutomationScenario::name).toList());
        conflicts(source.schedules(), Schedule::name, schedules.findByProject(projectId).stream().map(UiAutomationSchedule::name).toList());
        Map<String, String> plans = new HashMap<>();
        for (Schedule timer : source.schedules()) if (timer.functionalPlanCode() != null) {
            String code = timer.functionalPlanCode().toUpperCase(Locale.ROOT);
            var matches = functionalPlans.findPlans(projectId, null).stream().filter(p -> p.planCode().equalsIgnoreCase(code)).toList();
            if (matches.size() != 1) throw conflict("目标项目缺少唯一的功能测试计划编号 " + code + "，请先迁移该计划");
            plans.put(code, matches.getFirst().id());
        }
        Map<String, String> profileIds = new HashMap<>(), objectIds = new HashMap<>(), scenarioIds = new HashMap<>();
        try {
            for (Profile p : source.profiles()) {
                var created = automation.createTransferredProfile(principal, projectId, new UiAutomationService.ProfileRequest(
                        p.name(), p.browser(), p.baseUrl(), p.viewportWidth(), p.viewportHeight(), false));
                restoreVersion("ui_automation_browser_profile", projectId, created.id(), p.version()); profileIds.put(p.id(), created.id());
            }
            for (PageObject p : source.pageObjects()) {
                var created = automation.createPageObject(principal, projectId, new UiAutomationService.PageObjectRequest(p.name(), p.selectorValue(), p.description()));
                objectIds.put(p.id(), created.id());
            }
            for (Scenario s : source.scenarios()) {
                var steps = s.steps().stream().map(step -> new UiAutomationStep(step.id(), step.type(), step.name(), step.selector(),
                        step.pageObjectId() == null ? null : objectIds.get(step.pageObjectId()), step.value(), step.url(), step.expectedText(), step.timeoutMillis())).toList();
                var created = automation.createScenario(principal, projectId, new UiAutomationService.ScenarioRequest(profileIds.get(s.browserProfileId()),
                        s.name(), s.description(), s.failureStrategy(), s.variables(), steps));
                restoreVersion("ui_automation_scenario", projectId, created.id(), s.version()); scenarioIds.put(s.id(), created.id());
            }
            for (Schedule s : source.schedules()) scheduleService.create(principal, projectId, new UiAutomationScheduleService.ScheduleInput(
                    scenarioIds.get(s.scenarioId()), s.functionalPlanCode() == null ? null : plans.get(s.functionalPlanCode().toUpperCase(Locale.ROOT)),
                    s.name(), s.cronExpression(), s.timezone(), s.timeoutSeconds(), s.maxRetryAttempts(), s.retryBackoffSeconds(), false));
            Receipt receipt = new Receipt(UlidCreator.getUlid().toString(), source.profiles().size(), source.pageObjects().size(), source.scenarios().size(),
                    source.scenarios().stream().mapToInt(s -> s.steps().size()).sum(), source.schedules().size());
            jdbc.sql("""
                    INSERT INTO ui_automation_transfer_receipt(id, organization_id, project_id, requested_by, request_key, payload_sha256, result_json)
                    VALUES (:id, :org, :project, :actor, :key, :hash, :result)
                    """).param("id", receipt.id()).param("org", project.organizationId()).param("project", projectId).param("actor", principal.userId())
                    .param("key", key).param("hash", hash).param("result", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
            audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_TRANSFERRED", "UI_AUTOMATION_TRANSFER", receipt.id(),
                    Map.of("profiles", receipt.profiles(), "pageObjects", receipt.pageObjects(), "scenarios", receipt.scenarios(), "schedules", receipt.schedules()));
            return receipt;
        } catch (DataIntegrityViolationException error) { throw conflict("Web UI 资料与目标项目冲突，未写入任何迁移数据"); }
    }

    private void restoreVersion(String table, String project, String id, int version) {
        jdbc.sql("UPDATE " + table + " SET version = :version WHERE project_id = :project AND id = :id")
                .param("version", version).param("project", project).param("id", id).update();
    }
    private <T> void conflicts(List<T> items, Function<T, String> name, List<String> existing) {
        Set<String> names = new HashSet<>(existing);
        for (T item : items) if (!names.add(name.apply(item).trim())) throw conflict("目标项目存在同名 Web UI 资料：" + name.apply(item).trim());
    }
    private <T> Set<String> identities(List<T> items, Function<T, String> id, Function<T, String> name, int nameLimit) {
        if (items == null || items.size() > 1000) throw invalid("单类 Web UI 迁移资料不能超过 1000 条");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (T item : items) {
            if (item == null || !validId(id.apply(item)) || !ids.add(id.apply(item))) throw invalid("Web UI 资料标识无效或重复");
            String value = name.apply(item);
            if (value == null || value.isBlank() || value.trim().length() > nameLimit || !names.add(value.trim())) throw invalid("Web UI 资料名称无效或重复");
        }
        return ids;
    }
    private void validate(Snapshot source) {
        if (source == null || source.version() != 1 || !validId(source.sourceProjectId())) throw invalid("Web UI 迁移协议或来源项目无效");
        var profiles = identities(source.profiles(), Profile::id, Profile::name, 120);
        var objects = identities(source.pageObjects(), PageObject::id, PageObject::name, 120);
        var scenarios = identities(source.scenarios(), Scenario::id, Scenario::name, 160);
        identities(source.schedules(), Schedule::id, Schedule::name, 160);
        for (Profile p : source.profiles()) if (p.version() < 1 || p.version() > 1_000_000) throw invalid("浏览器配置版本无效");
        for (Scenario s : source.scenarios()) {
            if (s.version() < 1 || s.version() > 1_000_000 || !profiles.contains(s.browserProfileId()) || s.steps() == null || s.steps().isEmpty() || s.steps().size() > 300)
                throw invalid("Web UI 场景版本、浏览器关联或步骤数量无效");
            for (UiAutomationStep step : s.steps()) {
                if (step == null || (step.pageObjectId() != null && !objects.contains(step.pageObjectId()))) throw invalid("场景引用未包含的页面对象");
                if (tooLong(step.selector(), 1024) || tooLong(step.value(), 4096) || tooLong(step.url(), 2048) || tooLong(step.expectedText(), 4096))
                    throw invalid("Web UI 步骤字段超出长度限制");
            }
        }
        for (Schedule s : source.schedules()) {
            if (!scenarios.contains(s.scenarioId())) throw invalid("定时计划引用未包含的场景");
            if (s.functionalPlanCode() != null && (s.functionalPlanCode().isBlank() || s.functionalPlanCode().length() > 40 || !s.functionalPlanCode().equals(s.functionalPlanCode().trim())))
                throw invalid("关联功能测试计划编号无效");
        }
    }
    private boolean tooLong(String value, int max) { return value != null && value.length() > max; }
    private boolean validId(String value) { return value != null && value.matches("[A-Z0-9]{26}"); }
    private byte[] encoded(Object value) {
        try { byte[] bytes = json.writeValueAsBytes(value); if (bytes.length > MAX_BYTES) throw invalid("Web UI 迁移资料不能超过 32 MiB"); return bytes; }
        catch (JsonProcessingException error) { throw invalid("Web UI 迁移资料无法序列化"); }
    }
    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Snapshot(int version, String sourceProjectId, List<Profile> profiles, List<PageObject> pageObjects, List<Scenario> scenarios, List<Schedule> schedules) { }
    public record Profile(String id, String name, String browser, String baseUrl, int viewportWidth, int viewportHeight, boolean enabled, int version) { }
    public record PageObject(String id, String name, String selectorValue, String description) { }
    public record Scenario(String id, String browserProfileId, String name, String description, String failureStrategy, int version, Map<String, String> variables, List<UiAutomationStep> steps) { }
    public record Schedule(String id, String scenarioId, String name, String cronExpression, String timezone, int timeoutSeconds, int maxRetryAttempts, int retryBackoffSeconds, boolean enabled, String functionalPlanCode) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int profiles, int pageObjects, int scenarios, int steps, int schedules) { }
}
