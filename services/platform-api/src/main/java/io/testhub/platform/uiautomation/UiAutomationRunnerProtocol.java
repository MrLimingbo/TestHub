package io.testhub.platform.uiautomation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class UiAutomationRunnerProtocol {
    public static final String VERSION = "1";
    public static final String TASK_CONTRACT = "testhub.playwright-runner.task";
    public static final String CANCEL_CONTRACT = "testhub.playwright-runner.cancel";
    public static final String EVENT_CONTRACT = "testhub.playwright-runner.event";
    public static final String STEP_RESULT_CONTRACT = "testhub.playwright-runner.step-result";
    public static final String TASK_STREAM = "testhub:runner:playwright:tasks:v1";
    public static final String CANCEL_STREAM = "testhub:runner:playwright:cancellations:v1";
    public static final String EVENT_STREAM = "testhub:runner:playwright:events:v1";
    public static final String STEP_RESULT_STREAM = "testhub:runner:playwright:step-results:v1";
    public static final String EVENT_GROUP = "testhub-platform-playwright-events-v1";
    public static final String STEP_RESULT_GROUP = "testhub-platform-playwright-step-results-v1";
    public static final String RUNNER_TYPE = "playwright";
    public static final Set<String> TASK_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "organizationId", "projectId", "scenarioId", "scenarioVersion", "requestedAt", "deadlineAt", "manifestRef", "traceId");
    public static final Set<String> CANCEL_FIELDS = Set.of("contract", "protocolVersion", "commandId", "taskId",
            "executionId", "requestedAt", "reasonCode");
    private static final Set<String> EVENT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId",
            "runnerId", "type", "code", "message", "occurredAt");
    private static final Set<String> STEP_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId", "runnerId",
            "stepId", "stepSequence", "stepType", "stepName", "outcome", "durationMillis", "errorMessage", "occurredAt");
    private static final Set<String> EVENT_TYPES = Set.of("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED");
    private static final Set<String> OUTCOMES = Set.of("PASSED", "FAILED", "SKIPPED");
    private static final Set<String> STEP_TYPES = Set.of("NAVIGATE", "FILL", "CLICK", "ASSERT_VISIBLE", "ASSERT_TEXT", "WAIT", "SCREENSHOT");
    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";

    private UiAutomationRunnerProtocol() { }

    public static Event event(Map<String, String> fields) {
        exact(fields, EVENT_FIELDS, "Runner 事件");
        if (!EVENT_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) throw invalid("Playwright Runner 事件协议不兼容");
        String type = required(fields, "type");
        if (!EVENT_TYPES.contains(type)) throw invalid("Playwright Runner 事件类型不受支持");
        String code = required(fields, "code");
        if (!code.matches("[A-Z0-9_]{1,80}")) throw invalid("Playwright Runner 事件代码不合法");
        String message = required(fields, "message");
        if (message.length() > 512) throw invalid("Playwright Runner 事件消息过长");
        return new Event(ulid(required(fields, "taskId"), "taskId"), ulid(required(fields, "executionId"), "executionId"),
                runner(required(fields, "runnerId")), type, code, message, instant(required(fields, "occurredAt")));
    }

    public static Cancellation cancellation(Map<String, String> fields) {
        exact(fields, CANCEL_FIELDS, "Runner 取消命令");
        if (!CANCEL_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) {
            throw invalid("Playwright Runner 取消协议不兼容");
        }
        String reason = required(fields, "reasonCode");
        if (!reason.matches("[A-Z0-9_]{1,80}")) throw invalid("Playwright Runner 取消原因不合法");
        return new Cancellation(ulid(required(fields, "commandId"), "commandId"), ulid(required(fields, "taskId"), "taskId"),
                ulid(required(fields, "executionId"), "executionId"), instant(required(fields, "requestedAt")), reason);
    }

    public static StepResult stepResult(Map<String, String> fields) {
        exact(fields, STEP_FIELDS, "Runner 步骤结果");
        if (!STEP_RESULT_CONTRACT.equals(required(fields, "contract")) || !VERSION.equals(required(fields, "protocolVersion"))) throw invalid("Playwright Runner 步骤协议不兼容");
        String type = required(fields, "stepType");
        if (!STEP_TYPES.contains(type)) throw invalid("Playwright Runner 步骤类型不受支持");
        String outcome = required(fields, "outcome");
        if (!OUTCOMES.contains(outcome)) throw invalid("Playwright Runner 步骤状态不受支持");
        int sequence = integer(required(fields, "stepSequence"), "stepSequence", 1, 300);
        int duration = integer(required(fields, "durationMillis"), "durationMillis", 0, 120_000);
        String error = fields.get("errorMessage");
        if (error == null || error.length() > 512) throw invalid("Playwright Runner 步骤错误信息不合法");
        if ("FAILED".equals(outcome) && error.isBlank()) throw invalid("失败步骤必须提供错误摘要");
        return new StepResult(ulid(required(fields, "taskId"), "taskId"), ulid(required(fields, "executionId"), "executionId"),
                runner(required(fields, "runnerId")), required(fields, "stepId"), sequence, type, required(fields, "stepName"),
                outcome, duration, error.isBlank() ? null : error, instant(required(fields, "occurredAt")));
    }

    private static void exact(Map<String, String> fields, Set<String> expected, String label) {
        if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(expected)) throw invalid(label + "字段不兼容");
    }
    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank() || value.length() > 4096) throw invalid("Runner 缺少字段：" + name);
        return value;
    }
    private static String ulid(String value, String name) { if (!value.matches(ULID)) throw invalid("Runner " + name + " 不合法"); return value; }
    private static String runner(String value) { if (!value.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("Runner 标识不合法"); return value; }
    private static int integer(String value, String name, int min, int max) {
        if (!value.matches("[0-9]{1,9}")) throw invalid("Runner " + name + " 不合法");
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) throw invalid("Runner " + name + " 超出范围");
        return parsed;
    }
    private static Instant instant(String value) {
        try { return Instant.parse(value); } catch (DateTimeParseException exception) { throw invalid("Runner 时间不合法"); }
    }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record Event(String taskId, String executionId, String runnerId, String type, String code, String message,
                        Instant occurredAt) { }
    public record Cancellation(String commandId, String taskId, String executionId, Instant requestedAt, String reasonCode) { }
    public record StepResult(String taskId, String executionId, String runnerId, String stepId, int stepSequence,
                             String stepType, String stepName, String outcome, int durationMillis, String errorMessage,
                             Instant occurredAt) { }
}
