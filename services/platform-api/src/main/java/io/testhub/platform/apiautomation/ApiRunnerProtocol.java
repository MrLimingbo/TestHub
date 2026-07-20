package io.testhub.platform.apiautomation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ApiRunnerProtocol {
    public static final String VERSION = "1";
    public static final String HTTP_RESULT_VERSION = "2";
    public static final String TASK_CONTRACT = "testhub.api-runner.task";
    public static final String CANCEL_CONTRACT = "testhub.api-runner.cancel";
    public static final String EVENT_CONTRACT = "testhub.api-runner.event";
    public static final String LOG_CONTRACT = "testhub.api-runner.log";
    public static final String HTTP_RESULT_CONTRACT = "testhub.api-runner.http-result";
    public static final String STEP_RESULT_CONTRACT = "testhub.api-runner.step-result";
    public static final String SCRIPT_RESULT_CONTRACT = "testhub.api-runner.script-result";
    public static final String TASK_STREAM = "testhub:runner:api:tasks:v1";
    public static final String CANCEL_STREAM = "testhub:runner:api:cancellations:v1";
    public static final String EVENT_STREAM = "testhub:runner:api:events:v1";
    public static final String LOG_STREAM = "testhub:runner:api:logs:v1";
    public static final String HTTP_RESULT_STREAM = "testhub:runner:api:http-results:v2";
    public static final String STEP_RESULT_STREAM = "testhub:runner:api:step-results:v1";
    public static final String SCRIPT_RESULT_STREAM = "testhub:runner:api:script-results:v1";
    public static final String EVENT_GROUP = "testhub-platform-api-runner-events-v1";
    public static final String LOG_GROUP = "testhub-platform-api-runner-logs-v1";
    public static final String HTTP_RESULT_GROUP = "testhub-platform-api-runner-http-results-v2";
    public static final String STEP_RESULT_GROUP = "testhub-platform-api-runner-step-results-v1";
    public static final String SCRIPT_RESULT_GROUP = "testhub-platform-api-runner-script-results-v1";
    public static final Set<String> TASK_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "organizationId", "projectId", "scenarioId", "scenarioVersion", "attempt", "requestedAt", "deadlineAt",
            "manifestRef", "traceId");
    public static final Set<String> CANCEL_FIELDS = Set.of("contract", "protocolVersion", "commandId", "taskId",
            "executionId", "requestedAt", "reasonCode");
    public static final Set<String> EVENT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "type", "code", "message", "occurredAt");
    public static final Set<String> LOG_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "level", "code", "message", "occurredAt");
    public static final Set<String> HTTP_RESULT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "stepId", "requestSequence", "attemptNumber", "method", "outcome", "statusCode", "durationMillis", "occurredAt");
    public static final Set<String> STEP_RESULT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "stepId", "stepSequence", "type", "outcome", "observedStatusCode", "occurredAt");
    public static final Set<String> SCRIPT_RESULT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "phase", "testCount", "passedCount", "failedCount", "occurredAt");
    private static final Set<String> EVENT_TYPES = Set.of("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> HTTP_OUTCOMES = Set.of("SUCCEEDED", "FAILED");
    private static final Set<String> STEP_RESULT_TYPES = Set.of("EXTRACT", "ASSERT");
    private static final Set<String> SCRIPT_PHASES = Set.of("PRE_REQUEST", "POST_RESPONSE");
    private static final Map<String, LogDefinition> LOG_DEFINITIONS = Map.ofEntries(
            Map.entry("TASK_RECEIVED", new LogDefinition("INFO", "Task accepted for safety precheck.")),
            Map.entry("PRECHECK_WAITING", new LogDefinition("INFO", "Waiting for the safety precheck window to close.")),
            Map.entry("CANCELLATION_ACCEPTED", new LogDefinition("INFO", "Cancellation command accepted without executing user content.")),
            Map.entry("TASK_CANCELLED", new LogDefinition("INFO", "Task acknowledgement stopped because cancellation is active.")),
            Map.entry("EXECUTION_DISABLED", new LogDefinition("WARN", "Execution engine remains disabled; user content was not run.")),
            Map.entry("RUNTIME_ARTIFACT_RECEIVED", new LogDefinition("INFO", "A bounded HTTP runtime artifact was received.")),
            Map.entry("HTTP_EXECUTION_STARTED", new LogDefinition("INFO", "Bounded HTTP request execution started.")),
            Map.entry("PARALLEL_REQUESTS_STARTED", new LogDefinition("INFO", "A bounded parallel request group started without shared scripts or variables.")),
            Map.entry("PARALLEL_REQUESTS_COMPLETED", new LogDefinition("INFO", "A bounded parallel request group completed without exposing request content.")),
            Map.entry("WAIT_COMPLETED", new LogDefinition("INFO", "A bounded wait step completed without executing user code.")),
            Map.entry("CONDITION_PASSED", new LogDefinition("INFO", "A fixed status-code condition allowed its next request.")),
            Map.entry("CONDITION_SKIPPED", new LogDefinition("INFO", "A fixed status-code condition skipped its next request.")),
            Map.entry("LOOP_COMPLETED", new LogDefinition("INFO", "A bounded request loop completed without dynamic code.")),
            Map.entry("HTTP_RETRY_SCHEDULED", new LogDefinition("INFO", "A bounded retry was scheduled for a transient safe-method HTTP outcome.")),
            Map.entry("FAILURE_CONTINUED", new LogDefinition("WARN", "The configured continue strategy allowed later bounded steps to run after a failure.")),
            Map.entry("PRE_REQUEST_SCRIPT_COMPLETED", new LogDefinition("INFO", "The isolated pre-request script completed without exposing variables.")),
            Map.entry("POST_RESPONSE_SCRIPT_COMPLETED", new LogDefinition("INFO", "The isolated post-response script completed without exposing variables or response content.")),
            Map.entry("PRE_REQUEST_SCRIPT_FAILED", new LogDefinition("WARN", "The isolated pre-request script failed; no script output was persisted.")),
            Map.entry("POST_RESPONSE_SCRIPT_FAILED", new LogDefinition("WARN", "The isolated post-response script failed; no script output was persisted.")),
            Map.entry("HTTP_EXECUTION_SUCCEEDED", new LogDefinition("INFO", "Bounded HTTP request execution completed.")),
            Map.entry("HTTP_EXECUTION_FAILED", new LogDefinition("WARN", "Bounded HTTP request execution failed without persisting request or response content.")));
    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";

    private ApiRunnerProtocol() {
    }

    public static ApiRunnerEvent event(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(EVENT_FIELDS)) {
            throw invalid("Runner 事件字段不兼容");
        }
        if (!EVENT_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("Runner 事件协议不兼容");
        }
        String type = required(fields, "type");
        if (!EVENT_TYPES.contains(type)) throw invalid("Runner 事件类型不受支持");
        String taskId = ulid(required(fields, "taskId"), "taskId");
        String executionId = ulid(required(fields, "executionId"), "executionId");
        String runnerId = required(fields, "runnerId");
        if (!runnerId.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("Runner 标识不合法");
        String code = required(fields, "code");
        if (!code.matches("[A-Z0-9_]{1,80}")) throw invalid("Runner 事件代码不合法");
        String message = required(fields, "message");
        if (message.length() > 512) throw invalid("Runner 事件消息过长");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(required(fields, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalid("Runner 事件时间不合法");
        }
        return new ApiRunnerEvent(taskId, executionId, runnerId, type, code, message, occurredAt);
    }

    public static ApiRunnerLog log(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(LOG_FIELDS)) {
            throw invalid("Runner 日志字段不兼容");
        }
        if (!LOG_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("Runner 日志协议不兼容");
        }
        String code = required(fields, "code");
        LogDefinition definition = LOG_DEFINITIONS.get(code);
        if (definition == null || !definition.level().equals(required(fields, "level"))
                || !definition.message().equals(required(fields, "message"))) {
            throw invalid("Runner 日志内容不受支持");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(required(fields, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalid("Runner 日志时间不合法");
        }
        return new ApiRunnerLog(ulid(required(fields, "taskId"), "taskId"),
                ulid(required(fields, "executionId"), "executionId"), runnerId(required(fields, "runnerId")),
                definition.level(), code, definition.message(), occurredAt);
    }

    public static ApiRunnerHttpResult httpResult(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(HTTP_RESULT_FIELDS)) {
            throw invalid("Runner HTTP 结果字段不兼容");
        }
        if (!HTTP_RESULT_CONTRACT.equals(required(fields, "contract")) || !HTTP_RESULT_VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("Runner HTTP 结果协议不兼容");
        }
        String method = required(fields, "method");
        if (!HTTP_METHODS.contains(method)) throw invalid("Runner HTTP 请求方法不受支持");
        String outcome = required(fields, "outcome");
        if (!HTTP_OUTCOMES.contains(outcome)) throw invalid("Runner HTTP 结果状态不受支持");
        int statusCode = boundedInteger(required(fields, "statusCode"), "statusCode", 0, 599);
        if (("SUCCEEDED".equals(outcome) && (statusCode < 200 || statusCode >= 300))
                || ("FAILED".equals(outcome) && statusCode >= 200 && statusCode < 300)) {
            throw invalid("Runner HTTP 结果状态与 HTTP 状态码不一致");
        }
        int sequence = boundedInteger(required(fields, "requestSequence"), "requestSequence", 1, 300);
        int attemptNumber = boundedInteger(required(fields, "attemptNumber"), "attemptNumber", 1, 3);
        int durationMillis = boundedInteger(required(fields, "durationMillis"), "durationMillis", 0, 20_000);
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(required(fields, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalid("Runner HTTP 结果时间不合法");
        }
        String stepId = required(fields, "stepId");
        if (!stepId.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("Runner HTTP 结果步骤标识不合法");
        return new ApiRunnerHttpResult(ulid(required(fields, "taskId"), "taskId"),
                ulid(required(fields, "executionId"), "executionId"), runnerId(required(fields, "runnerId")),
                stepId, sequence, attemptNumber, method, outcome, statusCode, durationMillis, occurredAt);
    }

    public static ApiRunnerStepResult stepResult(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(STEP_RESULT_FIELDS)) {
            throw invalid("Runner 步骤结果字段不兼容");
        }
        if (!STEP_RESULT_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("Runner 步骤结果协议不兼容");
        }
        String type = required(fields, "type");
        if (!STEP_RESULT_TYPES.contains(type)) throw invalid("Runner 步骤结果类型不受支持");
        String outcome = required(fields, "outcome");
        if (("ASSERT".equals(type) && !("PASSED".equals(outcome) || "FAILED".equals(outcome)))
                || ("EXTRACT".equals(type) && !"COMPLETED".equals(outcome))) {
            throw invalid("Runner 步骤结果状态不受支持");
        }
        String stepId = required(fields, "stepId");
        if (!stepId.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("Runner 步骤结果步骤标识不合法");
        int sequence = boundedInteger(required(fields, "stepSequence"), "stepSequence", 1, 300);
        int observedStatusCode = boundedInteger(required(fields, "observedStatusCode"), "observedStatusCode", 100, 599);
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(required(fields, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalid("Runner 步骤结果时间不合法");
        }
        return new ApiRunnerStepResult(ulid(required(fields, "taskId"), "taskId"),
                ulid(required(fields, "executionId"), "executionId"), runnerId(required(fields, "runnerId")),
                stepId, sequence, type, outcome, observedStatusCode, occurredAt);
    }

    public static ApiRunnerScriptResult scriptResult(Map<String, String> fields) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(SCRIPT_RESULT_FIELDS)) {
            throw invalid("Runner 脚本结果字段不兼容");
        }
        if (!SCRIPT_RESULT_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("Runner 脚本结果协议不兼容");
        }
        String phase = required(fields, "phase");
        if (!SCRIPT_PHASES.contains(phase)) throw invalid("Runner 脚本结果阶段不受支持");
        int testCount = boundedInteger(required(fields, "testCount"), "testCount", 1, 100);
        int passedCount = boundedInteger(required(fields, "passedCount"), "passedCount", 0, testCount);
        int failedCount = boundedInteger(required(fields, "failedCount"), "failedCount", 0, testCount);
        if (passedCount + failedCount != testCount) throw invalid("Runner 脚本结果计数不一致");
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(required(fields, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalid("Runner 脚本结果时间不合法");
        }
        return new ApiRunnerScriptResult(ulid(required(fields, "taskId"), "taskId"),
                ulid(required(fields, "executionId"), "executionId"), runnerId(required(fields, "runnerId")), phase,
                testCount, passedCount, failedCount, occurredAt);
    }

    private static String required(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null || value.isBlank() || value.length() > 4096) throw invalid("Runner 事件缺少字段：" + field);
        return value;
    }

    private static String ulid(String value, String field) {
        if (!value.matches(ULID)) throw invalid("Runner 事件 " + field + " 不合法");
        return value;
    }

    private static String runnerId(String value) {
        if (!value.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("Runner 标识不合法");
        return value;
    }

    private static int boundedInteger(String value, String field, int minimum, int maximum) {
        if (!value.matches("[0-9]{1,6}")) throw invalid("Runner 结果 " + field + " 不合法");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw invalid("Runner 结果 " + field + " 超出范围");
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid("Runner 结果 " + field + " 不合法");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ApiRunnerEvent(String taskId, String executionId, String runnerId, String type, String code,
                                 String message, Instant occurredAt) {
    }

    public record ApiRunnerLog(String taskId, String executionId, String runnerId, String level, String code,
                               String message, Instant occurredAt) {
    }

    public record ApiRunnerHttpResult(String taskId, String executionId, String runnerId, String stepId,
                                      int requestSequence, int attemptNumber, String method, String outcome, int statusCode,
                                      int durationMillis, Instant occurredAt) {
    }

    public record ApiRunnerStepResult(String taskId, String executionId, String runnerId, String stepId,
                                      int stepSequence, String type, String outcome, int observedStatusCode,
                                      Instant occurredAt) {
    }

    public record ApiRunnerScriptResult(String taskId, String executionId, String runnerId, String phase,
                                        int testCount, int passedCount, int failedCount, Instant occurredAt) {
    }

    private record LogDefinition(String level, String message) {
    }
}
