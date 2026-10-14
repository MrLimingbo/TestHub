package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.ProjectFileReferenceGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Imports only referenced immutable revisions, preserving existing pins and each source asset's current content. */
@Service
public class ApiAutomationScenarioSyncService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_HISTORY = 1000;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final ApiAutomationScenarioSyncProjection projection;
    private final ApiAutomationScenarioRepository scenarioRepository;
    private final ApiAutomationReusableStepRepository reusableRepository;
    private final ApiAutomationScenarioService scenarios;
    private final ApiAutomationReusableStepService reusable;
    private final ApiAutomationTransferRepository transfer;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectFileReferenceGuard fileLinks;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public ApiAutomationScenarioSyncService(ApiAutomationScenarioSyncProjection projection,
            ApiAutomationScenarioRepository scenarioRepository, ApiAutomationReusableStepRepository reusableRepository,
            ApiAutomationScenarioService scenarios, ApiAutomationReusableStepService reusable,
            ApiAutomationTransferRepository transfer, AccessService access, AuditService audit,
            ProjectFileReferenceGuard fileLinks, JdbcClient jdbc, ObjectMapper json) {
        this.projection = projection; this.scenarioRepository = scenarioRepository; this.reusableRepository = reusableRepository;
        this.scenarios = scenarios; this.reusable = reusable; this.transfer = transfer; this.access = access;
        this.audit = audit; this.fileLinks = fileLinks; this.jdbc = jdbc; this.json = json;
    }
    public ApiAutomationScenarioSyncProjection.Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        return projection.read(actor, projectId, expectedUserId);
    }
    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("场景同步请求不能为空");
        if (body.length > MAX_BYTES) throw tooLarge();
        return decoded(body, Request.class);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null || !actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，场景同步已停止");
        if (!uuid(request.requestKey()) || request.expectedRevision() == null || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.operations() == null || request.operations().isEmpty() || request.operations().size() > 200)
            throw invalid("场景同步请求标识、修订或数量无效");
        var ids = new HashSet<String>(); var names = new HashSet<String>();
        for (var operation : request.operations()) {
            if (operation == null || !uuid(operation.operationId()) || !ids.add(operation.operationId())) throw invalid("场景同步操作标识重复或无效");
            validate(operation.item());
            if (!names.add(key(operation.item().name()))) throw invalid("同名场景不能重复提交");
        }
        byte[] body = encoded(request); if (body.length > MAX_BYTES) throw tooLarge();
        String payloadHash = hash(body);
        transfer.lockProject(projectId);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM api_automation_scenario_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p", projectId).param("a", actor.userId()).param("k", request.requestKey())
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他场景资料");
            return decoded(prior.get().getValue().getBytes(StandardCharsets.UTF_8), Receipt.class);
        }
        var before = snapshot(actor, projectId, actor.userId());
        if (!before.revision().equals(request.expectedRevision())) throw conflict("目标场景或引用来源已变化，请重新同步并核对");
        var merged = new LinkedHashMap<String, ApiAutomationScenarioSyncProjection.Item>();
        before.items().forEach(item -> merged.put(key(item.name()), item));
        for (var operation : request.operations()) {
            var old = merged.get(key(operation.item().name()));
            if (old != null && !old.name().equals(operation.item().name())) throw conflict("两端场景名称大小写不同，请先统一");
            merged.put(key(operation.item().name()), operation.item());
            fileLinks.validate(operation.item().definition(), projectId);
            requireEnabledEnvironment(projectId, definition(operation.item()).environmentCode());
        }
        if (new HashSet<>(before.itemIds().keySet()).size() + names.stream().filter(name -> !before.itemIds().containsKey(name)).count() > 200
                || encoded(merged.values()).length > 8 * 1024 * 1024) throw conflict("合并后的场景超过 200 条或 8 MiB");
        try {
            var work = new Work(actor, projectId, project.organizationId(), before, names);
            for (var operation : request.operations()) work.reserve(operation.item());
            var planned = new LinkedHashMap<String, ApiAutomationScenarioService.ScenarioInput>();
            for (var operation : request.operations()) planned.put(work.scenarioId(operation.item().name()), work.remap(operation.item()));
            work.restoreReusableHeads();
            for (var operation : request.operations()) work.saveHead(operation.item(), planned.get(work.scenarioId(operation.item().name())));
            work.restoreOtherScenarioHeads(planned.keySet());
            var after = snapshot(actor, projectId, actor.userId());
            var actual = new HashMap<String, ApiAutomationScenarioSyncProjection.Item>();
            after.items().forEach(item -> actual.put(key(item.name()), item));
            var results = new ArrayList<Result>();
            for (var operation : request.operations()) {
                var result = actual.get(key(operation.item().name()));
                if (result == null || !same(result, operation.item())) throw conflict("场景在写入时被规范化或引用变化，整批未提交；请核对后重试");
                results.add(new Result(operation.operationId(), result));
            }
            var receipt = new Receipt(request.requestKey(), results);
            jdbc.sql("INSERT INTO api_automation_scenario_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id", UlidCreator.getUlid().toString()).param("o", project.organizationId()).param("p", projectId)
                    .param("a", actor.userId()).param("k", request.requestKey()).param("h", payloadHash).param("r", text(receipt)).update();
            audit.record(actor, project.organizationId(), projectId, "API_AUTOMATION_SCENARIOS_SYNCED", "API_AUTOMATION_SCENARIO_SYNC",
                    request.requestKey(), Map.of("scenarios", results.size(), "created", work.reserved.size(), "importedRevisions", work.imported));
            return receipt;
        } catch (DataIntegrityViolationException error) { throw conflict("场景名称或历史修订与目标约束冲突，整批未提交"); }
    }

    private final class Work {
        final AuthenticatedPrincipal actor; final String project; final String organization;
        final ApiAutomationScenarioSyncProjection.Snapshot before; final Set<String> submitted;
        final Map<String, String> scenarioIds = new HashMap<>(); final Map<String, String> reusableIds = new HashMap<>();
        final Set<String> reserved = new HashSet<>();
        final Map<String, ApiAutomationScenarioDetail> originalScenarios = new LinkedHashMap<>();
        final Map<String, ApiAutomationReusableStepDetail> originalReusable = new LinkedHashMap<>();
        final Map<String, Map<String, Integer>> historyIndexes = new HashMap<>(); int imported;
        Work(AuthenticatedPrincipal actor, String project, String organization, ApiAutomationScenarioSyncProjection.Snapshot before, Set<String> submitted) {
            this.actor = actor; this.project = project; this.organization = organization; this.before = before; this.submitted = submitted;
            scenarioRepository.findByProject(project).forEach(row -> scenarioIds.put(row.name(), row.id()));
            reusableRepository.findByProject(project).forEach(row -> reusableIds.put(row.name(), row.id()));
        }
        String scenarioId(String name) {
            var id = scenarioIds.get(name); if (id == null) throw conflict("子场景在目标缺失，请先同步并核对来源场景"); return id;
        }
        void reserve(ApiAutomationScenarioSyncProjection.Item item) {
            if (scenarioIds.containsKey(item.name())) return;
            if (scenarioIds.keySet().stream().anyMatch(name -> key(name).equals(key(item.name())))) throw conflict("来源场景名称大小写不同，请先统一");
            var d = definition(item); String id = UlidCreator.getUlid().toString();
            // The row is invisible outside this transaction; a validated physical head and its revision are required before commit.
            scenarioRepository.insert(id, organization, project, item.name(), d.description(), d.environmentCode(), d.failureStrategy(),
                    text(d.steps()), text(d.variables()), d.preRequestScript(), d.postResponseScript(), actor.userId());
            scenarioIds.put(item.name(), id); reserved.add(id);
        }
        ApiAutomationScenarioService.ScenarioInput remap(ApiAutomationScenarioSyncProjection.Item item) {
            var d = definition(item); var steps = new ArrayList<AutomationStep>();
            for (var step : d.steps()) {
                if (step.type().equals("REUSABLE_STEP")) {
                    String name = sourceName(step); String id = reusableIds.get(name);
                    if (id == null) throw conflict("固定引用的公共步骤在目标缺失，请先核对基础资料同步");
                    int version = reusableRevision(id, reusableSnapshot(step));
                    steps.add(new AutomationStep(step.id(), step.type(), step.name(), Map.of("reusableStepId", id, "reusableStepVersion", version)));
                } else if (step.type().equals("SUB_SCENARIO")) {
                    String name = sourceName(step); String id = scenarioId(name);
                    if (before.blockedNames().containsKey(name) && !submitted.contains(key(name)))
                        throw conflict("目标子场景当前引用失效，请先修复或同步该场景");
                    int version = scenarioRevision(id, scenarioSnapshot(step));
                    steps.add(new AutomationStep(step.id(), step.type(), step.name(), Map.of("scenarioId", id, "scenarioVersion", version)));
                } else steps.add(step);
            }
            return new ApiAutomationScenarioService.ScenarioInput(item.name(), d.description(), d.environmentCode(), d.failureStrategy(),
                    steps, d.variables(), d.preRequestScript(), d.postResponseScript(), null);
        }
        Map<String, Integer> index(String kind, String id) {
            String key = kind + id; if (historyIndexes.containsKey(key)) return historyIndexes.get(key);
            int maximum = kind.equals("REUSABLE_STEP") ? reusable.detail(actor, project, id).version() : scenarios.detail(actor, project, id).version();
            if (maximum > MAX_HISTORY) throw conflict("引用来源超过 1000 个修订，暂不支持自动历史映射");
            var result = new HashMap<String, Integer>();
            for (int version = 1; version <= maximum; version++) {
                Object value = kind.equals("REUSABLE_STEP") ? reusableRepository.findRevisionSnapshot(project, id, version).orElse(null)
                        : scenarioRepository.findRevisionSnapshot(project, id, version).orElse(null);
                if (value == null) {
                    if (reserved.contains(id) && version == 1) continue;
                    throw conflict("目标来源的历史修订缺失，请先核对");
                }
                result.putIfAbsent(hash(encoded(value)), version);
            }
            historyIndexes.put(key, result); return result;
        }
        int reusableRevision(String id, ApiAutomationReusableStepRevisionSnapshot source) {
            var index = index("REUSABLE_STEP", id); String digest = hash(encoded(source));
            if (index.containsKey(digest)) return index.get(digest);
            var current = reusable.detail(actor, project, id); originalReusable.putIfAbsent(id, current);
            if (current.version() >= MAX_HISTORY - 1) throw conflict("公共步骤修订已接近映射上限，已暂停追加");
            if (reusableRepository.update(project, id, current.version(), current.name(), current.description(), text(current.steps())) != 1)
                throw conflict("公共步骤已被其他成员更新，请重新同步");
            int version = current.version() + 1;
            reusableRepository.insertRevision(UlidCreator.getUlid().toString(), id, organization, project, version, text(source), actor.userId());
            index.put(digest, version); imported++; return version;
        }
        int scenarioRevision(String id, ApiAutomationScenarioRevisionSnapshot source) {
            var index = index("SCENARIO", id); String digest = hash(encoded(source));
            if (index.containsKey(digest)) return index.get(digest);
            var current = scenarios.detail(actor, project, id);
            int version;
            if (reserved.contains(id) && index.isEmpty()) version = 1;
            else {
                if (!reserved.contains(id)) originalScenarios.putIfAbsent(id, current);
                if (current.version() >= MAX_HISTORY - 1) throw conflict("子场景修订已接近映射上限，已暂停追加");
                if (scenarioRepository.update(project, id, current.version(), current.name(), current.description(), current.environmentCode(),
                        current.failureStrategy(), text(current.steps()), text(current.variables()), current.preRequestScript(), current.postResponseScript()) != 1)
                    throw conflict("子场景已被其他成员更新，请重新同步");
                version = current.version() + 1;
            }
            scenarioRepository.insertRevision(UlidCreator.getUlid().toString(), id, organization, project, version, text(source), actor.userId());
            index.put(digest, version); imported++; return version;
        }
        void restoreReusableHeads() {
            for (var entry : originalReusable.entrySet()) {
                var old = entry.getValue(); int version = reusable.detail(actor, project, entry.getKey()).version();
                reusable.update(actor, project, entry.getKey(), new ApiAutomationReusableStepService.ReusableStepInput(old.name(), old.description(), old.steps(), version));
            }
        }
        void saveHead(ApiAutomationScenarioSyncProjection.Item item, ApiAutomationScenarioService.ScenarioInput planned) {
            String id = scenarioId(item.name()); var current = scenarios.detail(actor, project, id);
            var old = before.items().stream().filter(value -> key(value.name()).equals(key(item.name()))).findFirst();
            boolean unchanged = old.isPresent() && same(old.get(), item);
            if (unchanged && !originalScenarios.containsKey(id)) return;
            if (unchanged && originalScenarios.containsKey(id)) planned = input(originalScenarios.get(id), null);
            planned = scenarios.normalizedSyncInput(project, id, planned);
            if (reserved.contains(id) && scenarioRepository.findRevisionSnapshot(project, id, 1).isEmpty()) {
                if (scenarioRepository.initializeSynced(project, id, planned.name(), planned.description(), planned.environmentCode(), planned.failureStrategy(),
                        text(planned.steps()), text(planned.variables()), planned.preRequestScript(), planned.postResponseScript()) != 1)
                    throw conflict("新场景已变化，整批未提交");
                scenarioRepository.insertRevision(UlidCreator.getUlid().toString(), id, organization, project, 1,
                        text(revision(planned)), actor.userId());
            } else scenarios.update(actor, project, id, withVersion(planned, current.version()));
        }
        void restoreOtherScenarioHeads(Set<String> planned) {
            for (var entry : originalScenarios.entrySet()) if (!planned.contains(entry.getKey()))
                scenarios.update(actor, project, entry.getKey(), input(entry.getValue(), scenarios.detail(actor, project, entry.getKey()).version()));
        }
    }

    private void validate(ApiAutomationScenarioSyncProjection.Item item) {
        if (item == null || !name(item.name()) || item.definition() == null) throw invalid("场景名称或定义无效");
        var d = definition(item);
        if (d.description() == null || !d.description().equals(d.description().trim()) || d.description().length() > 1200
                || d.environmentCode() == null || !d.environmentCode().matches("[a-z0-9][a-z0-9_-]{0,63}")
                || d.failureStrategy() == null || !Set.of("STOP", "CONTINUE").contains(d.failureStrategy())
                || d.variables() == null || d.steps() == null || d.steps().isEmpty() || d.steps().size() > 300
                || d.preRequestScript() == null || d.postResponseScript() == null) throw invalid("场景同步字段无效");
        ApiAutomationExecutionProfileService.validateVariables(d.variables(), "同步场景");
        if (!ApiAutomationScriptPolicy.normalize(d.preRequestScript(), "前置脚本").equals(d.preRequestScript())
                || !ApiAutomationScriptPolicy.normalize(d.postResponseScript(), "后置脚本").equals(d.postResponseScript())) throw invalid("场景脚本尚未规范化");
        var ids = new HashSet<String>();
        for (var step : d.steps()) {
            if (step == null || step.id() == null || !step.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(step.id())
                    || !name(step.name()) || step.type() == null || !Set.of("REQUEST", "PARALLEL", "EXTRACT", "ASSERT", "CONDITION", "LOOP", "WAIT", "REUSABLE_STEP", "SUB_SCENARIO").contains(step.type())
                    || step.config() == null) throw invalid("场景步骤类型、标识或配置无效");
            if (step.type().equals("REUSABLE_STEP")) reusableSnapshot(step);
            if (step.type().equals("SUB_SCENARIO")) {
                if (item.name().equals(sourceName(step))) throw invalid("子场景不能引用自身");
                scenarioSnapshot(step);
            }
        }
        if (!same(d, item.definition())) throw invalid("场景字段不完整或包含不支持的字段");
    }
    private ApiAutomationReusableStepRevisionSnapshot reusableSnapshot(AutomationStep step) {
        sourceName(step);
        var value = decoded(encoded(step.config().get("revision")), ApiAutomationReusableStepRevisionSnapshot.class);
        if (!same(value, step.config().get("revision")) || !same(value, reusable.normalizedSyncSnapshot(value))) throw invalid("固定公共步骤历史内容不合法或尚未规范化");
        return value;
    }
    private ApiAutomationScenarioRevisionSnapshot scenarioSnapshot(AutomationStep step) {
        sourceName(step);
        var value = decoded(encoded(step.config().get("revision")), ApiAutomationScenarioRevisionSnapshot.class);
        if (!same(value, step.config().get("revision")) || !same(value, scenarios.normalizedSyncPrimitiveSnapshot(value))) throw invalid("固定子场景历史不合法或尚未规范化");
        return value;
    }
    private String sourceName(AutomationStep step) {
        if (!step.config().keySet().equals(Set.of("sourceName", "revision")) || !(step.config().get("sourceName") instanceof String name)
                || !name(name) || !(step.config().get("revision") instanceof Map<?, ?>)) throw invalid("固定历史引用字段无效");
        return name;
    }
    private ApiAutomationScenarioSyncProjection.Definition definition(ApiAutomationScenarioSyncProjection.Item item) {
        return decoded(encoded(item.definition()), ApiAutomationScenarioSyncProjection.Definition.class);
    }
    private ApiAutomationScenarioService.ScenarioInput input(ApiAutomationScenarioDetail value, Integer version) {
        return new ApiAutomationScenarioService.ScenarioInput(value.name(), value.description(), value.environmentCode(), value.failureStrategy(),
                value.steps(), value.variables(), value.preRequestScript(), value.postResponseScript(), version);
    }
    private ApiAutomationScenarioService.ScenarioInput withVersion(ApiAutomationScenarioService.ScenarioInput value, int version) {
        return new ApiAutomationScenarioService.ScenarioInput(value.name(), value.description(), value.environmentCode(), value.failureStrategy(),
                value.steps(), value.variables(), value.preRequestScript(), value.postResponseScript(), version);
    }
    private ApiAutomationScenarioRevisionSnapshot revision(ApiAutomationScenarioService.ScenarioInput value) {
        return new ApiAutomationScenarioRevisionSnapshot(value.name(), value.description(), value.environmentCode(), value.failureStrategy(),
                value.steps(), value.variables(), value.preRequestScript(), value.postResponseScript());
    }
    private void requireEnabledEnvironment(String project, String code) {
        if (!jdbc.sql("SELECT enabled FROM project_environment WHERE project_id=:p AND environment_code=:code FOR UPDATE")
                .param("p", project).param("code", code).query(Boolean.class).optional().orElse(false)) throw conflict("场景目标环境缺失或已停用");
    }
    private boolean name(String value) { return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 160; }
    private String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private boolean same(Object a, Object b) { return json.valueToTree(a).equals(json.valueToTree(b)); }
    private boolean uuid(String value) {
        try { return value != null && value.equals(UUID.fromString(value).toString()); }
        catch (IllegalArgumentException error) { return false; }
    }
    private <T> T decoded(byte[] source, Class<T> type) {
        try { T result = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(source);
            if (result == null) throw invalid("场景同步资料不能为空"); return result;
        } catch (java.io.IOException error) { throw invalid("场景同步 JSON 不合法或包含不支持的字段"); }
    }
    private byte[] encoded(Object value) { return projection.encoded(value); }
    private String text(Object value) { return new String(encoded(value), StandardCharsets.UTF_8); }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "场景同步请求不能超过 10 MiB"); }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Operation> operations) { }
    public record Operation(String operationId, ApiAutomationScenarioSyncProjection.Item item) { }
    public record Result(String operationId, ApiAutomationScenarioSyncProjection.Item item) { }
    public record Receipt(String requestKey, List<Result> results) { }
}
