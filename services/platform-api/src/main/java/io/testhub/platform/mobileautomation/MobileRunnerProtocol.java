package io.testhub.platform.mobileautomation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded transport contract for the isolated Appium Runner. */
public final class MobileRunnerProtocol {
    public static final String VERSION = "1";
    public static final String TASK_STREAM = "testhub:runner:appium:tasks:v1";
    public static final String CANCEL_STREAM = "testhub:runner:appium:cancellations:v1";
    public static final String EVENT_STREAM = "testhub:runner:appium:events:v1";
    public static final String STEP_STREAM = "testhub:runner:appium:step-results:v1";
    public static final String EVENT_GROUP = "testhub-platform-appium-events-v1";
    public static final String STEP_GROUP = "testhub-platform-appium-steps-v1";
    public static final String TASK_CONTRACT = "testhub.appium-runner.task";
    public static final String CANCEL_CONTRACT = "testhub.appium-runner.cancel";
    public static final String EVENT_CONTRACT = "testhub.appium-runner.event";
    public static final String STEP_CONTRACT = "testhub.appium-runner.step-result";
    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";
    private static final Set<String> EVENT_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId", "runnerId", "type", "code", "message", "occurredAt");
    private static final Set<String> CANCEL_FIELDS = Set.of("contract", "protocolVersion", "commandId", "taskId", "executionId", "requestedAt", "reasonCode");
    private static final Set<String> STEP_FIELDS = Set.of("contract", "protocolVersion", "taskId", "executionId", "runnerId", "stepId", "stepSequence", "stepType", "stepName", "outcome", "durationMillis", "errorMessage", "occurredAt");
    private MobileRunnerProtocol() { }

    public static Event event(Map<String, String> fields) {
        exact(fields, EVENT_FIELDS, "Appium Runner 事件");
        if (!EVENT_CONTRACT.equals(text(fields, "contract")) || !VERSION.equals(text(fields, "protocolVersion"))) throw invalid("Appium Runner 事件协议不兼容");
        String type = text(fields, "type"), code = text(fields, "code");
        if (!Set.of("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "REJECTED").contains(type) || !code.matches("[A-Z0-9_]{1,80}")) throw invalid("Appium Runner 事件类型不合法");
        return new Event(ulid(text(fields, "taskId"), "taskId"), ulid(text(fields, "executionId"), "executionId"), runner(text(fields, "runnerId")), type, code, bounded(text(fields, "message"), 512), instant(text(fields, "occurredAt")));
    }

    public static Cancellation cancellation(Map<String, String> fields) {
        exact(fields, CANCEL_FIELDS, "Appium Runner 取消命令");
        if (!CANCEL_CONTRACT.equals(text(fields, "contract")) || !VERSION.equals(text(fields, "protocolVersion"))) throw invalid("Appium Runner 取消协议不兼容");
        String reason = text(fields, "reasonCode");
        if (!reason.matches("[A-Z0-9_]{1,80}")) throw invalid("Appium Runner 取消原因不合法");
        return new Cancellation(ulid(text(fields, "commandId"), "commandId"), ulid(text(fields, "taskId"), "taskId"), ulid(text(fields, "executionId"), "executionId"), instant(text(fields, "requestedAt")), reason);
    }

    public static StepResult step(Map<String, String> fields) {
        exact(fields, STEP_FIELDS, "Appium Runner 步骤");
        if (!STEP_CONTRACT.equals(text(fields, "contract")) || !VERSION.equals(text(fields, "protocolVersion"))) throw invalid("Appium Runner 步骤协议不兼容");
        String type = text(fields, "stepType"), outcome = text(fields, "outcome"), error = fields.get("errorMessage");
        if (!Set.of("LAUNCH", "TAP", "INPUT", "ASSERT_VISIBLE", "ASSERT_TEXT", "WAIT", "SCREENSHOT").contains(type)
                || !Set.of("PASSED", "FAILED", "SKIPPED").contains(outcome) || error == null || error.length() > 512) throw invalid("Appium Runner 步骤不合法");
        if ("FAILED".equals(outcome) && error.isBlank()) throw invalid("失败移动端步骤必须提供错误摘要");
        return new StepResult(ulid(text(fields, "taskId"), "taskId"), ulid(text(fields, "executionId"), "executionId"), runner(text(fields, "runnerId")),
                bounded(text(fields, "stepId"), 64), integer(text(fields, "stepSequence"), 1, 300), type, bounded(text(fields, "stepName"), 160), outcome,
                integer(text(fields, "durationMillis"), 0, 120_000), error.isBlank() ? null : error, instant(text(fields, "occurredAt")));
    }

    private static void exact(Map<String, String> fields, Set<String> expected, String label) { if (fields == null || !new LinkedHashSet<>(fields.keySet()).equals(expected)) throw invalid(label + "字段不兼容"); }
    private static String text(Map<String, String> fields, String key) { String value = fields.get(key); if (value == null || value.isBlank() || value.length() > 4096) throw invalid("缺少字段：" + key); return value; }
    private static String bounded(String value, int max) { if (value.length() > max) throw invalid("字段过长"); return value; }
    private static String ulid(String value, String label) { if (!value.matches(ULID)) throw invalid("Appium Runner " + label + " 不合法"); return value; }
    private static String runner(String value) { if (!value.matches("[A-Za-z0-9._-]{1,64}")) throw invalid("Appium Runner 标识不合法"); return value; }
    private static int integer(String value, int min, int max) { if (!value.matches("[0-9]{1,9}")) throw invalid("Appium Runner 数值不合法"); int parsed = Integer.parseInt(value); if (parsed < min || parsed > max) throw invalid("Appium Runner 数值超出范围"); return parsed; }
    private static Instant instant(String value) { try { return Instant.parse(value); } catch (DateTimeParseException exception) { throw invalid("Appium Runner 时间不合法"); } }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record Event(String taskId, String executionId, String runnerId, String type, String code, String message, Instant occurredAt) { }
    public record Cancellation(String commandId, String taskId, String executionId, Instant requestedAt, String reasonCode) { }
    public record StepResult(String taskId, String executionId, String runnerId, String stepId, int stepSequence, String stepType, String stepName, String outcome, int durationMillis, String errorMessage, Instant occurredAt) { }
}
