package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiRunnerRuntimeLeaseService {
    static final String LEASE_KEY_PREFIX = "testhub:runner:api:runtime-lease:v1:";
    private static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(5);
    private static final Set<String> ELIGIBLE_STATUSES = Set.of("QUEUED", "ACCEPTED", "RUNNING");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> RETRYABLE_HTTP_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> HTTP_CONFIG_KEYS = Set.of("method", "url", "body", "contentType", "maxAttempts");
    private static final Set<String> SUPPORTED_STEP_TYPES = Set.of("REQUEST", "PARALLEL", "EXTRACT", "ASSERT", "WAIT", "CONDITION", "LOOP");
    private static final int MIN_PARALLEL_REQUESTS = 2;
    private static final int MAX_PARALLEL_REQUESTS = 5;
    private static final String LAST_STATUS_CODE = "LAST_STATUS_CODE";
    private static final Set<String> CONDITION_OPERATORS = Set.of("EQUALS", "NOT_EQUALS");
    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.-]{0,127})\\s*}}");
    private final ApiAutomationExecutionRepository executions;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationScenarioExpansionService expansions;
    private final ApiRunnerStatusService runners;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final boolean httpExecutionEnabled;
    private final SecureRandom random = new SecureRandom();

    public ApiRunnerRuntimeLeaseService(ApiAutomationExecutionRepository executions, ApiAutomationScenarioRepository scenarios,
                                        ApiAutomationScenarioExpansionService expansions, ApiRunnerStatusService runners,
                                        StringRedisTemplate redis, ObjectMapper objectMapper, AuditService audit,
                                        @Value("${testhub.runner.http-execution-enabled:false}") boolean httpExecutionEnabled) {
        this.executions = executions;
        this.scenarios = scenarios;
        this.expansions = expansions;
        this.runners = runners;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.httpExecutionEnabled = httpExecutionEnabled;
    }

    public ApiRunnerRuntimeLease issue(String runnerId, String taskId, String executionId) {
        ApiAutomationExecution execution = eligibleExecution(taskId, executionId);
        Instant now = Instant.now();
        Instant expiresAt = execution.deadlineAt().isBefore(now.plus(MAX_LEASE_DURATION))
                ? execution.deadlineAt() : now.plus(MAX_LEASE_DURATION);
        String lease = randomLease();
        StoredLease stored = new StoredLease(runnerId, taskId, executionId, leaseFingerprint(lease), expiresAt);
        Duration ttl = Duration.between(now, expiresAt);
        Boolean created = redis.opsForValue().setIfAbsent(LEASE_KEY_PREFIX + executionId, json(stored), ttl);
        if (!Boolean.TRUE.equals(created)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Runner 运行时租约已被领取");
        audit.recordSystem(execution.organizationId(), execution.projectId(), "API_AUTOMATION_RUNTIME_LEASE_ISSUED",
                "API_AUTOMATION_EXECUTION", execution.id(), Map.of("runnerId", runnerId, "taskId", taskId,
                        "expiresAt", expiresAt.toString()));
        return new ApiRunnerRuntimeLease(lease, executionId, taskId, runnerId, expiresAt, purpose(runnerId));
    }

    public ApiRunnerRuntimeLeaseIndex inspect(String runnerId, String taskId, String executionId, String lease) {
        ApiAutomationExecution execution = eligibleExecution(taskId, executionId);
        StoredLease stored = storedLease(executionId);
        String fingerprint = leaseFingerprint(lease);
        if (!runnerId.equals(stored.runnerId()) || !taskId.equals(stored.taskId()) || !executionId.equals(stored.executionId())
                || !stored.expiresAt().isAfter(Instant.now()) || !matches(stored.leaseHash(), fingerprint)) {
            throw rejected();
        }
        ApiAutomationExecutionManifest manifest = executions.findManifest(execution.projectId(), execution.id())
                .orElseThrow(this::rejected);
        ApiAutomationExecutionRuntimeSnapshot runtimeSnapshot = executions.findRuntimeSnapshot(execution.projectId(), execution.id())
                .orElse(null);
        audit.recordSystem(execution.organizationId(), execution.projectId(), "API_AUTOMATION_RUNTIME_LEASE_INSPECTED",
                "API_AUTOMATION_EXECUTION", execution.id(), Map.of("runnerId", runnerId, "taskId", taskId,
                        "expiresAt", stored.expiresAt().toString()));
        return new ApiRunnerRuntimeLeaseIndex(executionId, taskId, runnerId, stored.expiresAt(),
                purpose(runnerId), manifest.snapshot().environmentCode(), manifest.scenarioVersion(),
                manifest.checksum(), runtimeSnapshot != null,
                runtimeSnapshot == null ? null : runtimeSnapshot.checksum(),
                runtimeSnapshot == null ? 0 : runtimeSnapshot.variableCount(),
                runtimeSnapshot == null ? 0 : runtimeSnapshot.withheldSecretCount(), manifest.snapshot().variableReferences().stream()
                        .map(reference -> new ApiRunnerRuntimeLeaseIndex.VariableReference(reference.name(), reference.source(),
                                reference.secret())).toList());
    }

    public ApiRunnerRuntimeConfiguration runtimeConfiguration(String runnerId, String taskId, String executionId,
                                                              String lease) {
        if (!httpExecutionEnabled || !runners.isTrustedExecutionEnabled(runnerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Runner HTTP 执行未启用");
        }
        ApiAutomationExecution execution = verifiedLease(runnerId, taskId, executionId, lease);
        ApiAutomationExecutionRuntimeSnapshot runtimeSnapshot = executions.findRuntimeSnapshot(execution.projectId(), execution.id())
                .orElseThrow(this::rejected);
        if (!runtimeSnapshot.snapshot().withheldSecretNames().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "当前执行引用了环境密钥，HTTP 工件不会传递密钥");
        }
        ApiAutomationScenarioRevisionSnapshot scenario = scenarios.findRevisionSnapshot(execution.projectId(),
                execution.scenarioId(), execution.scenarioVersion()).orElseThrow(this::rejected);
        List<AutomationStep> expandedSteps = expansions.expand(execution.projectId(), execution.scenarioId(), scenario.steps());
        Map<String, String> values = new LinkedHashMap<>();
        runtimeSnapshot.snapshot().variables().forEach(variable -> values.put(variable.name(), variable.value()));
        Set<String> localVariables = localVariables(expandedSteps);
        ensureNoStaticVariableCollision(values, localVariables);
        List<ApiRunnerRuntimeConfiguration.Step> steps = expandedSteps.stream()
                .map(step -> step(step, values.keySet(), localVariables)).toList();
        validateSequence(steps);
        String preRequestScript = ApiAutomationScriptPolicy.normalize(scenario.preRequestScript(), "前置脚本");
        String postResponseScript = ApiAutomationScriptPolicy.normalize(scenario.postResponseScript(), "后置脚本");
        if (steps.stream().anyMatch(step -> "PARALLEL".equals(step.type()))
                && (!preRequestScript.isBlank() || !postResponseScript.isBlank())) {
            throw unsupported("并行请求组不支持场景前后置脚本，以避免共享变量竞争");
        }
        audit.recordSystem(execution.organizationId(), execution.projectId(), "API_AUTOMATION_RUNTIME_CONFIGURATION_ISSUED",
                "API_AUTOMATION_EXECUTION", execution.id(), Map.of("runnerId", runnerId, "taskId", taskId,
                        "stepCount", steps.size(), "artifactVersion", 5,
                        "failureStrategy", scenario.failureStrategy(),
                        "preRequestScript", !preRequestScript.isBlank(), "postResponseScript", !postResponseScript.isBlank()));
        return new ApiRunnerRuntimeConfiguration("testhub.api-runner.runtime-configuration", 5, executionId, taskId,
                execution.deadlineAt(), scenario.failureStrategy(), values,
                new ApiRunnerRuntimeConfiguration.Scripts(preRequestScript, postResponseScript), steps);
    }

    private ApiRunnerRuntimeConfiguration.Step step(AutomationStep step, Set<String> staticVariables,
                                                     Set<String> localVariables) {
        if (!SUPPORTED_STEP_TYPES.contains(step.type())) throw unsupported("HTTP Runner 当前不支持该步骤类型");
        if ("PARALLEL".equals(step.type())) {
            List<ApiRunnerRuntimeConfiguration.ParallelRequest> requests = parallelRequests(step.config(), staticVariables);
            return new ApiRunnerRuntimeConfiguration.Step(step.id(), "PARALLEL", null, null, null, null,
                    null, null, null, null, null, null, requests);
        }
        if ("WAIT".equals(step.type())) {
            if (!step.config().keySet().equals(Set.of("delayMillis"))) throw unsupported("等待步骤不符合受限 Runner 协议");
            int delayMillis = integer(step.config().get("delayMillis"), "delayMillis");
            if (delayMillis < 0 || delayMillis > 600_000) throw unsupported("等待步骤延迟不合法");
            return new ApiRunnerRuntimeConfiguration.Step(step.id(), "WAIT", null, null, null, null,
                    null, null, (long) delayMillis, null, null, null, null);
        }
        if ("CONDITION".equals(step.type())) {
            if (!step.config().keySet().equals(Set.of("source", "operator", "expectedStatus"))
                    || !LAST_STATUS_CODE.equals(string(step.config().get("source"), "source"))) {
                throw unsupported("条件步骤不符合受限 Runner 协议");
            }
            String operator = string(step.config().get("operator"), "operator").toUpperCase(java.util.Locale.ROOT);
            if (!CONDITION_OPERATORS.contains(operator)) throw unsupported("条件步骤操作符不受支持");
            int expected = integer(step.config().get("expectedStatus"), "expectedStatus");
            if (expected < 100 || expected > 599) throw unsupported("条件步骤期望状态码不合法");
            return new ApiRunnerRuntimeConfiguration.Step(step.id(), "CONDITION", null, null, null, null,
                    expected, null, null, operator, null, null, null);
        }
        if ("LOOP".equals(step.type())) {
            if (!step.config().keySet().equals(Set.of("totalRuns"))) throw unsupported("循环步骤不符合受限 Runner 协议");
            int totalRuns = integer(step.config().get("totalRuns"), "totalRuns");
            if (totalRuns < 1 || totalRuns > 10) throw unsupported("循环步骤总执行次数不合法");
            return new ApiRunnerRuntimeConfiguration.Step(step.id(), "LOOP", null, null, null, null,
                    null, null, null, null, totalRuns, null, null);
        }
        if ("EXTRACT".equals(step.type())) {
            if (!step.config().keySet().equals(Set.of("source", "variable"))
                    || !LAST_STATUS_CODE.equals(string(step.config().get("source"), "source"))) {
                throw unsupported("数据提取步骤不符合受限 Runner 协议");
            }
            String variable = string(step.config().get("variable"), "variable");
            if (!variable.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw unsupported("数据提取变量名不合法");
            return new ApiRunnerRuntimeConfiguration.Step(step.id(), "EXTRACT", null, null, null, null,
                    null, variable, null, null, null, null, null);
        }
        if ("ASSERT".equals(step.type())) {
            if (!step.config().keySet().equals(Set.of("source", "expectedStatus"))
                    || !LAST_STATUS_CODE.equals(string(step.config().get("source"), "source"))) {
                throw unsupported("断言步骤不符合受限 Runner 协议");
            }
            int expected = integer(step.config().get("expectedStatus"), "expectedStatus");
            if (expected < 100 || expected > 599) throw unsupported("断言期望状态码不合法");
            return new ApiRunnerRuntimeConfiguration.Step(step.id(), "ASSERT", null, null, null, null,
                    expected, null, null, null, null, null, null);
        }
        RequestConfig request = requestConfig(step.config(), staticVariables, localVariables);
        return new ApiRunnerRuntimeConfiguration.Step(step.id(), "REQUEST", request.method(), request.url(), request.body(),
                request.contentType(), null, null, null, null, null, request.maxAttempts(), null);
    }

    private List<ApiRunnerRuntimeConfiguration.ParallelRequest> parallelRequests(Map<String, Object> config,
                                                                                   Set<String> staticVariables) {
        if (!config.keySet().equals(Set.of("requests")) || !(config.get("requests") instanceof List<?> source)
                || source.size() < MIN_PARALLEL_REQUESTS || source.size() > MAX_PARALLEL_REQUESTS) {
            throw unsupported("并行请求组不符合受限 Runner 协议");
        }
        List<ApiRunnerRuntimeConfiguration.ParallelRequest> result = new java.util.ArrayList<>();
        for (Object item : source) {
            RequestConfig request = requestConfig(requestMap(item), staticVariables, Set.of());
            result.add(new ApiRunnerRuntimeConfiguration.ParallelRequest(request.method(), request.url(), request.body(),
                    request.contentType(), request.maxAttempts()));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> requestMap(Object source) {
        if (!(source instanceof Map<?, ?> values)) throw unsupported("并行请求配置不合法");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw unsupported("并行请求配置不合法");
            result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    private RequestConfig requestConfig(Map<String, Object> config, Set<String> staticVariables,
                                        Set<String> localVariables) {
        if (!HTTP_CONFIG_KEYS.containsAll(config.keySet())) throw unsupported("请求步骤包含当前 Runner 不支持的配置项");
        String method = string(config.get("method"), "method").toUpperCase(java.util.Locale.ROOT);
        if (!HTTP_METHODS.contains(method)) throw unsupported("请求方法不受支持");
        String url = string(config.get("url"), "url");
        validateTemplateReferences(url, staticVariables, localVariables);
        validateUrlTemplate(url);
        String body = config.containsKey("body") ? optionalString(config.get("body"), "body") : "";
        validateTemplateReferences(body, staticVariables, localVariables);
        if (body.length() > 64 * 1024) throw unsupported("请求体超过 HTTP Runner 限制");
        if (("GET".equals(method) || "HEAD".equals(method)) && !body.isEmpty()) {
            throw unsupported("GET 和 HEAD 请求不能携带请求体");
        }
        String contentType = config.containsKey("contentType") ? optionalString(config.get("contentType"), "contentType") : "";
        if (!contentType.isEmpty() && (!contentType.matches("[A-Za-z0-9.+-]{1,64}/[A-Za-z0-9.+-]{1,64}") || contentType.length() > 128)) {
            throw unsupported("contentType 不合法");
        }
        int maxAttempts = config.containsKey("maxAttempts") ? integer(config.get("maxAttempts"), "maxAttempts") : 1;
        if (maxAttempts < 1 || maxAttempts > 3) throw unsupported("请求最大尝试次数应在 1 到 3 次之间");
        if (maxAttempts > 1 && !RETRYABLE_HTTP_METHODS.contains(method)) {
            throw unsupported("仅 GET、HEAD 和 OPTIONS 请求支持重试");
        }
        return new RequestConfig(method, url, body, contentType, maxAttempts);
    }

    private void validateTemplateReferences(String source, Set<String> staticVariables, Set<String> localVariables) {
        Matcher matcher = VARIABLE_REFERENCE.matcher(source);
        while (matcher.find()) {
            String variable = matcher.group(1);
            if (!ApiAutomationScriptPolicy.isSafeVariableName(variable)
                    || (!staticVariables.contains(variable) && !localVariables.contains(variable))) {
                throw unsupported("请求工件存在未允许传递的变量引用");
            }
        }
    }

    private Set<String> localVariables(List<AutomationStep> steps) {
        Set<String> variables = new LinkedHashSet<>();
        for (AutomationStep step : steps) if ("EXTRACT".equals(step.type())) {
            String variable = string(step.config().get("variable"), "variable");
            if (!variables.add(variable)) throw unsupported("数据提取变量名重复");
        }
        return Set.copyOf(variables);
    }

    private void ensureNoStaticVariableCollision(Map<String, String> values, Set<String> localVariables) {
        if (localVariables.stream().anyMatch(values::containsKey)) throw unsupported("数据提取变量不能覆盖已冻结变量");
    }

    private void validateSequence(List<ApiRunnerRuntimeConfiguration.Step> steps) {
        boolean hasRequest = false;
        boolean latestRequestIsSingle = false;
        int expandedRequestCount = 0;
        for (int index = 0; index < steps.size(); index++) {
            ApiRunnerRuntimeConfiguration.Step step = steps.get(index);
            if ("REQUEST".equals(step.type())) {
                hasRequest = true;
                latestRequestIsSingle = true;
                expandedRequestCount++;
            }
            if ("PARALLEL".equals(step.type())) {
                hasRequest = true;
                latestRequestIsSingle = false;
                expandedRequestCount += step.parallelRequests().size();
            }
            if (("EXTRACT".equals(step.type()) || "ASSERT".equals(step.type())) && !latestRequestIsSingle) {
                throw unsupported("数据提取和断言必须位于请求步骤之后");
            }
            if ("CONDITION".equals(step.type())) {
                if (!hasRequest || !latestRequestIsSingle || index + 1 >= steps.size() || !"REQUEST".equals(steps.get(index + 1).type())) {
                    throw unsupported("条件步骤只能控制紧随的一个请求步骤");
                }
                if (index + 2 < steps.size() && "LOOP".equals(steps.get(index + 2).type())) {
                    throw unsupported("条件控制的请求不能紧随循环步骤");
                }
            }
            if ("LOOP".equals(step.type())) {
                if (index == 0 || !"REQUEST".equals(steps.get(index - 1).type())) {
                    throw unsupported("循环步骤必须紧随一个请求步骤");
                }
                expandedRequestCount += step.loopTotalRuns() - 1;
            }
        }
        if (!hasRequest) throw unsupported("场景没有可执行的 HTTP 请求步骤");
        if (expandedRequestCount > 300) throw unsupported("循环展开后的请求次数不能超过 300 次");
    }

    private int integer(Object value, String name) {
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
            // Translate malformed untrusted configuration into a stable 422 response.
        }
        throw unsupported("请求配置 " + name + " 不合法");
    }

    private void validateUrlTemplate(String value) {
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) throw unsupported("请求地址格式不合法");
        int authorityStart = schemeEnd + 3;
        int authorityEnd = value.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int candidate = value.indexOf(delimiter, authorityStart);
            if (candidate >= 0 && candidate < authorityEnd) authorityEnd = candidate;
        }
        if (value.substring(authorityStart, authorityEnd).contains("{{")) {
            throw unsupported("请求地址中的局部变量不能改变 Origin");
        }
        validateUrl(VARIABLE_REFERENCE.matcher(value).replaceAll("runner-local"));
    }

    private void validateUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || value.length() > 2048) {
                throw unsupported("请求地址必须是受限 HTTP(S) 地址");
            }
        } catch (java.net.URISyntaxException exception) {
            throw unsupported("请求地址格式不合法");
        }
    }

    private String string(Object value, String name) {
        if (!(value instanceof String result) || result.isBlank() || result.length() > 64 * 1024) {
            throw unsupported("请求配置 " + name + " 不合法");
        }
        return result;
    }

    private String optionalString(Object value, String name) {
        if (!(value instanceof String result) || result.length() > 64 * 1024) {
            throw unsupported("请求配置 " + name + " 不合法");
        }
        return result;
    }

    private record RequestConfig(String method, String url, String body, String contentType, int maxAttempts) {
    }

    private String randomLease() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String leaseFingerprint(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runner 运行时租约请求被拒绝");
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private String json(StoredLease stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception exception) {
            throw new IllegalStateException("Runner 租约无法序列化", exception);
        }
    }

    private ApiAutomationExecution eligibleExecution(String taskId, String executionId) {
        ApiAutomationExecution execution = executions.findByTask(taskId, executionId).orElseThrow(this::rejected);
        if (!ELIGIBLE_STATUSES.contains(execution.status()) || !executions.hasTaskPossiblyDispatched(execution.id())
                || !execution.deadlineAt().isAfter(Instant.now())) {
            throw rejected();
        }
        return execution;
    }

    private ApiAutomationExecution verifiedLease(String runnerId, String taskId, String executionId, String lease) {
        ApiAutomationExecution execution = eligibleExecution(taskId, executionId);
        StoredLease stored = storedLease(executionId);
        String fingerprint = leaseFingerprint(lease);
        if (!runnerId.equals(stored.runnerId()) || !taskId.equals(stored.taskId()) || !executionId.equals(stored.executionId())
                || !stored.expiresAt().isAfter(Instant.now()) || !matches(stored.leaseHash(), fingerprint)) {
            throw rejected();
        }
        return execution;
    }

    private String purpose(String runnerId) {
        return httpExecutionEnabled && runners.isTrustedExecutionEnabled(runnerId)
                ? "HTTP_REQUEST_EXECUTION" : "RUNTIME_CONFIGURATION_DISABLED";
    }

    private StoredLease storedLease(String executionId) {
        try {
            String value = redis.opsForValue().get(LEASE_KEY_PREFIX + executionId);
            if (value == null) throw rejected();
            return objectMapper.readValue(value, StoredLease.class);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
    }

    private boolean matches(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runner 运行时租约请求被拒绝");
    }

    private ResponseStatusException unsupported(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private record StoredLease(String runnerId, String taskId, String executionId, String leaseHash, Instant expiresAt) {
    }
}
