package io.testhub.platform.apiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiAutomationScenarioService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> STEP_TYPES = Set.of("REQUEST", "PARALLEL", "EXTRACT", "ASSERT", "CONDITION", "LOOP", "WAIT",
            ApiAutomationScenarioExpansionService.REUSABLE_STEP, ApiAutomationScenarioExpansionService.SUB_SCENARIO);
    private static final Set<String> REQUEST_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> RETRYABLE_REQUEST_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String LAST_STATUS_CODE = "LAST_STATUS_CODE";
    private static final Set<String> CONDITION_OPERATORS = Set.of("EQUALS", "NOT_EQUALS");
    private static final Set<String> FAILURE_STRATEGIES = Set.of("STOP", "CONTINUE");
    private static final int MAX_SCENARIOS_PER_PROJECT = 200;
    private static final int MAX_STEPS = 300;
    private static final int MIN_PARALLEL_REQUESTS = 2;
    private static final int MAX_PARALLEL_REQUESTS = 5;
    private static final int MAX_VARIABLES = 100;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationScenarioExpansionService expansions;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectEnvironmentRepository environments;

    public ApiAutomationScenarioService(ApiAutomationScenarioRepository scenarios,
                                        ApiAutomationScenarioExpansionService expansions, AccessService access,
                                        AuditService audit, ProjectEnvironmentRepository environments) {
        this.scenarios = scenarios;
        this.expansions = expansions;
        this.access = access;
        this.audit = audit;
        this.environments = environments;
    }

    public List<ApiAutomationScenario> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return scenarios.findByProject(projectId);
    }

    public ApiAutomationScenarioDetail detail(AuthenticatedPrincipal principal, String projectId, String scenarioId) {
        access.requireProjectRead(principal, projectId);
        return requireScenario(projectId, scenarioId);
    }

    public List<ApiAutomationScenarioRevision> revisions(AuthenticatedPrincipal principal, String projectId, String scenarioId) {
        access.requireProjectRead(principal, projectId);
        requireScenario(projectId, scenarioId);
        return scenarios.findRevisions(projectId, scenarioId);
    }

    @Transactional
    public ApiAutomationScenarioDetail create(AuthenticatedPrincipal principal, String projectId, ScenarioInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (scenarios.countByProject(projectId) >= MAX_SCENARIOS_PER_PROJECT) {
            throw conflict("每个项目最多创建 200 个接口自动化场景");
        }
        ScenarioPayload payload = payload(projectId, null, input);
        String id = UlidCreator.getUlid().toString();
        try {
            scenarios.insert(id, project.organizationId(), projectId, payload.name(), payload.description(), payload.environmentCode(),
                    payload.failureStrategy(), scenarios.json(payload.steps()), scenarios.json(payload.variables()),
                    payload.preRequestScript(), payload.postResponseScript(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名接口自动化场景");
        }
        ApiAutomationScenarioDetail result = requireScenario(projectId, id);
        saveRevision(project, result, principal);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_SCENARIO_CREATED", "API_AUTOMATION_SCENARIO", id,
                Map.of("name", result.name(), "version", result.version(), "stepCount", result.steps().size()));
        return result;
    }

    @Transactional
    public ApiAutomationScenarioDetail update(AuthenticatedPrincipal principal, String projectId, String scenarioId, ScenarioInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 1) {
            throw rejected("保存场景时必须提供当前版本号");
        }
        ScenarioPayload payload = payload(projectId, scenarioId, input);
        int updated;
        try {
            updated = scenarios.update(projectId, scenarioId, input.expectedVersion(), payload.name(), payload.description(),
                    payload.environmentCode(), payload.failureStrategy(), scenarios.json(payload.steps()), scenarios.json(payload.variables()),
                    payload.preRequestScript(), payload.postResponseScript());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名接口自动化场景");
        }
        if (updated == 0) {
            ApiAutomationScenarioDetail current = scenarios.findDetail(projectId, scenarioId).orElseThrow(() -> notFound("接口自动化场景不存在"));
            throw conflict("场景已被其他成员更新，请刷新后再保存（当前版本 v" + current.version() + "）");
        }
        ApiAutomationScenarioDetail result = requireScenario(projectId, scenarioId);
        saveRevision(project, result, principal);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_SCENARIO_UPDATED", "API_AUTOMATION_SCENARIO", scenarioId,
                Map.of("name", result.name(), "version", result.version(), "stepCount", result.steps().size()));
        return result;
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String scenarioId) {
        ProjectSummary project = writeAccess(principal, projectId);
        ApiAutomationScenarioDetail current = requireScenario(projectId, scenarioId);
        try {
            if (scenarios.delete(projectId, scenarioId) == 0) throw notFound("接口自动化场景不存在");
        } catch (DataIntegrityViolationException exception) {
            throw conflict("场景已有执行任务、定时计划或 Webhook 引用，不能删除");
        }
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_SCENARIO_DELETED", "API_AUTOMATION_SCENARIO",
                scenarioId, Map.of("name", current.name(), "version", current.version()));
    }

    private void saveRevision(ProjectSummary project, ApiAutomationScenarioDetail scenario, AuthenticatedPrincipal principal) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", scenario.name());
        snapshot.put("description", scenario.description());
        snapshot.put("environmentCode", scenario.environmentCode());
        snapshot.put("failureStrategy", scenario.failureStrategy());
        snapshot.put("steps", scenario.steps());
        snapshot.put("variables", scenario.variables());
        snapshot.put("preRequestScript", scenario.preRequestScript());
        snapshot.put("postResponseScript", scenario.postResponseScript());
        scenarios.insertRevision(UlidCreator.getUlid().toString(), scenario.id(), project.organizationId(), project.id(),
                scenario.version(), scenarios.json(snapshot), principal.userId());
    }

    ScenarioInput normalizedSyncInput(String projectId, String scenarioId, ScenarioInput input) {
        var value = payload(projectId, scenarioId, input);
        return new ScenarioInput(value.name(), value.description(), value.environmentCode(), value.failureStrategy(),
                value.steps(), value.variables(), value.preRequestScript(), value.postResponseScript(), input.expectedVersion());
    }

    ApiAutomationScenarioRevisionSnapshot normalizedSyncPrimitiveSnapshot(ApiAutomationScenarioRevisionSnapshot source) {
        var steps = validateReusableSteps(source.steps());
        if (!ApiAutomationScriptPolicy.normalize(source.preRequestScript(), "引用前置脚本").isBlank()
                || !ApiAutomationScriptPolicy.normalize(source.postResponseScript(), "引用后置脚本").isBlank())
            throw rejected("固定子场景历史不支持前后置脚本");
        if (scenarios.json(steps).length() > 512 * 1024) throw rejected("历史子场景步骤超过 512 KB");
        return new ApiAutomationScenarioRevisionSnapshot(required(source.name(), "历史场景名称", 160),
                optional(source.description(), "历史场景说明", 1200), environment(source.environmentCode()),
                failureStrategy(source.failureStrategy()), steps, variables(source.variables()), "", "");
    }

    private ScenarioPayload payload(String projectId, String scenarioId, ScenarioInput input) {
        if (input == null) throw rejected("场景参数不能为空");
        String name = required(input.name(), "场景名称", 160);
        String description = optional(input.description(), "场景说明", 1200);
        String environmentCode = environment(input.environmentCode());
        if (environments.findByCode(projectId, environmentCode).isEmpty()) {
            throw rejected("请选择当前项目中已存在的运行环境");
        }
        String failureStrategy = failureStrategy(input.failureStrategy());
        List<AutomationStep> steps = steps(input.steps());
        List<AutomationStep> expandedSteps = expansions.validateCandidate(projectId, scenarioId, steps);
        Map<String, String> variables = variables(input.variables());
        String preRequestScript = ApiAutomationScriptPolicy.normalize(input.preRequestScript(), "前置脚本");
        String postResponseScript = ApiAutomationScriptPolicy.normalize(input.postResponseScript(), "后置脚本");
        if (expandedSteps.stream().anyMatch(step -> "PARALLEL".equals(step.type()))
                && (!preRequestScript.isBlank() || !postResponseScript.isBlank())) {
            throw rejected("并行请求组不支持场景前后置脚本，以避免共享变量竞争");
        }
        if (scenarios.json(steps).length() > 512 * 1024) throw rejected("场景步骤配置不能超过 512 KB");
        return new ScenarioPayload(name, description, environmentCode, failureStrategy, steps, variables,
                preRequestScript, postResponseScript);
    }

    private List<AutomationStep> steps(List<AutomationStep> input) {
        if (input == null || input.isEmpty()) throw rejected("场景至少需要一个步骤");
        if (input.size() > MAX_STEPS) throw rejected("场景最多支持 300 个步骤");
        Set<String> ids = new LinkedHashSet<>();
        List<AutomationStep> result = input.stream().map(step -> {
            if (step == null) throw rejected("场景步骤不能为空");
            String id = required(step.id(), "步骤标识", 64);
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) throw rejected("步骤标识不合法或重复：" + id);
            String type = step.type() == null ? "" : step.type().trim().toUpperCase(Locale.ROOT);
            if (!STEP_TYPES.contains(type)) throw rejected("当前节点不支持的步骤类型：" + type);
            String name = required(step.name(), "步骤名称", 160);
            Map<String, Object> config = config(type, step.config());
            validateStepConfig(type, config);
            return new AutomationStep(id, type, name, config);
        }).toList();
        if (result.stream().noneMatch(step -> expansions.isReferenceType(step.type()))) validateFlowSequence(result);
        return result;
    }

    /** Shared grammar for project-level public steps. References are forbidden so expansion stays finite. */
    List<AutomationStep> validateReusableSteps(List<AutomationStep> input) {
        List<AutomationStep> result = steps(input);
        if (result.stream().anyMatch(step -> expansions.isReferenceType(step.type()))) {
            throw rejected("公共步骤不能嵌套公共步骤或子场景引用");
        }
        return result;
    }

    private Map<String, Object> config(String type, Map<String, Object> input) {
        Map<String, Object> source = input == null ? Map.of() : input;
        if (source.size() > 50) throw rejected("步骤配置最多支持 50 项");
        if ("PARALLEL".equals(type)) return parallelConfig(source);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw rejected("步骤配置名称不合法：" + key);
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw rejected("步骤配置仅支持字符串、数值或布尔值：" + key);
            }
            if (String.valueOf(value).length() > 10_000) throw rejected("步骤配置值过长：" + key);
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> parallelConfig(Map<String, Object> source) {
        if (!source.keySet().equals(Set.of("requests")) || !(source.get("requests") instanceof List<?> requests)) {
            throw rejected("并行请求组只支持 requests 配置");
        }
        if (requests.size() < MIN_PARALLEL_REQUESTS || requests.size() > MAX_PARALLEL_REQUESTS) {
            throw rejected("并行请求组应包含 2 到 5 条请求");
        }
        List<Map<String, Object>> normalized = new java.util.ArrayList<>();
        for (Object request : requests) {
            if (!(request instanceof Map<?, ?> raw)) throw rejected("并行请求组中的请求配置不合法");
            Map<String, Object> item = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
                    throw rejected("并行请求配置名称不合法");
                }
                Object value = entry.getValue();
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw rejected("并行请求配置仅支持字符串、数值或布尔值：" + key);
                }
                if (String.valueOf(value).length() > 10_000) throw rejected("并行请求配置值过长：" + key);
                item.put(key, value);
            }
            validateRequestConfig(item, "并行请求", false);
            normalized.add(Map.copyOf(item));
        }
        return Map.of("requests", List.copyOf(normalized));
    }

    private void validateStepConfig(String type, Map<String, Object> config) {
        if ("REQUEST".equals(type)) {
            validateRequestConfig(config, "请求步骤", true);
        }
        if ("WAIT".equals(type)) {
            if (!config.keySet().equals(Set.of("delayMillis"))) throw rejected("等待步骤只支持 delayMillis 配置");
            Object delay = config.get("delayMillis");
            long delayMillis;
            try { delayMillis = delay instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(delay)); }
            catch (Exception exception) { throw rejected("等待步骤必须配置 delayMillis"); }
            if (delayMillis < 0 || delayMillis > 600_000) throw rejected("等待步骤延迟应在 0 到 600000 毫秒之间");
        }
        if ("CONDITION".equals(type)) {
            if (!config.keySet().equals(Set.of("source", "operator", "expectedStatus"))
                    || !LAST_STATUS_CODE.equals(value(config, "source"))) {
                throw rejected("条件步骤当前只支持最近 HTTP 状态码条件");
            }
            String operator = value(config, "operator").toUpperCase(Locale.ROOT);
            if (!CONDITION_OPERATORS.contains(operator)) throw rejected("条件步骤操作符仅支持 EQUALS 或 NOT_EQUALS");
            int expected = integer(config.get("expectedStatus"), "条件步骤期望状态码");
            if (expected < 100 || expected > 599) throw rejected("条件步骤期望状态码应在 100 到 599 之间");
        }
        if ("LOOP".equals(type)) {
            if (!config.keySet().equals(Set.of("totalRuns"))) throw rejected("循环步骤只支持 totalRuns 配置");
            int totalRuns = integer(config.get("totalRuns"), "循环总执行次数");
            if (totalRuns < 1 || totalRuns > 10) throw rejected("循环总执行次数应在 1 到 10 之间");
        }
        if ("EXTRACT".equals(type)) {
            if (!config.keySet().equals(Set.of("source", "variable")) || !LAST_STATUS_CODE.equals(value(config, "source"))) {
                throw rejected("数据提取步骤当前只支持 LAST_STATUS_CODE 来源");
            }
            String variable = value(config, "variable");
            if (!variable.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw rejected("数据提取变量名不合法");
        }
        if ("ASSERT".equals(type)) {
            if (!config.keySet().equals(Set.of("source", "expectedStatus")) || !LAST_STATUS_CODE.equals(value(config, "source"))) {
                throw rejected("断言步骤当前只支持 LAST_STATUS_CODE 精确断言");
            }
            int expected = integer(config.get("expectedStatus"), "断言期望状态码");
            if (expected < 100 || expected > 599) throw rejected("断言期望状态码应在 100 到 599 之间");
        }
    }

    private void validateRequestConfig(Map<String, Object> config, String label, boolean allowAdditionalConfig) {
        if (!config.containsKey("method") || !config.containsKey("url")) {
            throw rejected(label + "必须配置 method 和 url");
        }
        if (!allowAdditionalConfig && !Set.of("method", "url", "body", "contentType", "maxAttempts").containsAll(config.keySet())) {
            throw rejected(label + "包含不受支持的配置项");
        }
        String method = value(config, "method").toUpperCase(Locale.ROOT);
        String url = value(config, "url");
        if (!REQUEST_METHODS.contains(method)) throw rejected(label + "方法不受支持");
        if (url.isBlank() || url.length() > 2048) throw rejected(label + "地址长度应在 1 到 2048 字符之间");
        String body = optionalValue(config, "body");
        if (("GET".equals(method) || "HEAD".equals(method)) && !body.isBlank()) {
            throw rejected("GET 和 HEAD 请求不能携带请求正文");
        }
        String contentType = optionalValue(config, "contentType");
        if (!contentType.isEmpty() && !contentType.matches("[A-Za-z0-9.+-]{1,64}/[A-Za-z0-9.+-]{1,64}")) {
            throw rejected(label + " contentType 不合法");
        }
        int maxAttempts = config.containsKey("maxAttempts") ? integer(config.get("maxAttempts"), label + "最大尝试次数") : 1;
        if (maxAttempts < 1 || maxAttempts > 3) throw rejected(label + "最大尝试次数应在 1 到 3 次之间");
        if (maxAttempts > 1 && !RETRYABLE_REQUEST_METHODS.contains(method)) {
            throw rejected("仅 GET、HEAD 和 OPTIONS 请求支持重试");
        }
    }

    private void validateFlowSequence(List<AutomationStep> steps) {
        boolean requestSeen = false;
        boolean latestRequestIsSingle = false;
        int expandedRequestCount = 0;
        for (int index = 0; index < steps.size(); index++) {
            AutomationStep step = steps.get(index);
            if ("REQUEST".equals(step.type())) {
                requestSeen = true;
                latestRequestIsSingle = true;
                expandedRequestCount++;
            }
            if ("PARALLEL".equals(step.type())) {
                requestSeen = true;
                latestRequestIsSingle = false;
                expandedRequestCount += parallelRequestCount(step);
            }
            if (("EXTRACT".equals(step.type()) || "ASSERT".equals(step.type())) && !latestRequestIsSingle) {
                throw rejected("数据提取和断言不能直接作用于并行请求组");
            }
            if ("CONDITION".equals(step.type())) {
                if (!requestSeen || !latestRequestIsSingle || index + 1 >= steps.size() || !"REQUEST".equals(steps.get(index + 1).type())) {
                    throw rejected("条件步骤必须位于已有请求之后，并且只控制紧随的一个请求步骤");
                }
                if (index + 2 < steps.size() && "LOOP".equals(steps.get(index + 2).type())) {
                    throw rejected("条件控制的请求不能紧随循环步骤");
                }
            }
            if ("LOOP".equals(step.type())) {
                if (index == 0 || !"REQUEST".equals(steps.get(index - 1).type())) {
                    throw rejected("循环步骤必须紧随一个请求步骤，用于重复该请求");
                }
                expandedRequestCount += integer(step.config().get("totalRuns"), "循环总执行次数") - 1;
            }
        }
        if (expandedRequestCount > MAX_STEPS) throw rejected("循环展开后的请求次数不能超过 300 次");
    }

    private int parallelRequestCount(AutomationStep step) {
        Object requests = step.config().get("requests");
        return requests instanceof List<?> list ? list.size() : 0;
    }

    private Map<String, String> variables(Map<String, String> input) {
        Map<String, String> source = input == null ? Map.of() : input;
        if (source.size() > MAX_VARIABLES) throw rejected("场景变量最多支持 100 项");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || value == null || value.length() > 10_000) {
                throw rejected("场景变量不合法：" + key);
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private String environment(String value) {
        String normalized = value == null || value.isBlank() ? "test" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw rejected("环境标识不合法");
        return normalized;
    }

    private String failureStrategy(String value) {
        String normalized = value == null || value.isBlank() ? "STOP" : value.trim().toUpperCase(Locale.ROOT);
        if (!FAILURE_STRATEGIES.contains(normalized)) throw rejected("失败策略仅支持 STOP 或 CONTINUE");
        return normalized;
    }

    private String value(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof String string ? string.trim() : "";
    }

    private String optionalValue(Map<String, Object> config, String key) {
        if (!config.containsKey(key)) return "";
        Object value = config.get(key);
        if (!(value instanceof String string)) throw rejected("请求步骤 " + key + " 必须是字符串");
        return string;
    }

    private int integer(Object value, String label) {
        try {
            if (value instanceof Number number) {
                double numeric = number.doubleValue();
                if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                        || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
                    throw new NumberFormatException();
                }
                return (int) numeric;
            }
            String raw = String.valueOf(value).trim();
            if (!raw.matches("[+-]?\\d+")) throw new NumberFormatException();
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            // Translate every parsing failure into the same API validation error.
        }
        throw rejected(label + "不合法");
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private ApiAutomationScenarioDetail requireScenario(String projectId, String scenarioId) {
        return scenarios.findDetail(projectId, scenarioId).orElseThrow(() -> notFound("接口自动化场景不存在"));
    }

    private String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return normalized;
    }

    private String optional(String value, String label, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return normalized;
    }

    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record ScenarioInput(String name, String description, String environmentCode, String failureStrategy,
                                List<AutomationStep> steps, Map<String, String> variables, String preRequestScript,
                                String postResponseScript, Integer expectedVersion) {
    }
    private record ScenarioPayload(String name, String description, String environmentCode, String failureStrategy,
                                   List<AutomationStep> steps, Map<String, String> variables, String preRequestScript,
                                   String postResponseScript) {
    }
}
