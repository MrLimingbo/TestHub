package io.testhub.platform.apiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiAutomationScheduleService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> FAILED_STATUSES = Set.of("FAILED", "REJECTED", "TIMED_OUT");
    private static final String QUARTZ_GROUP = "testhub-api-automation";
    private final ApiAutomationScheduleRepository schedules;
    private final ApiAutomationScenarioRepository scenarios;
    private final ApiAutomationExecutionProfileRepository profiles;
    private final ApiAutomationExecutionService executions;
    private final AccessService access;
    private final AuditService audit;
    private final Scheduler scheduler;

    public ApiAutomationScheduleService(ApiAutomationScheduleRepository schedules,
                                        ApiAutomationScenarioRepository scenarios, ApiAutomationExecutionProfileRepository profiles,
                                        ApiAutomationExecutionService executions, AccessService access,
                                        AuditService audit, Scheduler scheduler) {
        this.schedules = schedules;
        this.scenarios = scenarios;
        this.profiles = profiles;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    public List<ApiAutomationSchedule> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return schedules.findByProject(projectId);
    }

    @Transactional
    public ApiAutomationSchedule create(AuthenticatedPrincipal principal, String projectId, ScheduleInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        SchedulePayload payload = payload(projectId, input);
        String id = UlidCreator.getUlid().toString();
        try {
            schedules.insert(id, project.organizationId(), projectId, payload.scenarioId(), payload.executionProfileId(), payload.name(), payload.cronExpression(),
                    payload.timezone(), payload.timeoutSeconds(), payload.maxConsecutiveFailures(), payload.enabled(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名接口自动化定时计划");
        }
        ApiAutomationSchedule result = schedules.findById(id).orElseThrow();
        sync(result);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_SCHEDULE_CREATED",
                "API_AUTOMATION_SCHEDULE", id, auditDetail(result));
        return result;
    }

    @Transactional
    public ApiAutomationSchedule update(AuthenticatedPrincipal principal, String projectId, String scheduleId, ScheduleInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        owned(projectId, scheduleId);
        SchedulePayload payload = payload(projectId, input);
        try {
            if (schedules.update(projectId, scheduleId, payload.scenarioId(), payload.executionProfileId(), payload.name(), payload.cronExpression(),
                    payload.timezone(), payload.timeoutSeconds(), payload.maxConsecutiveFailures(), payload.enabled()) == 0) {
                throw notFound("接口自动化定时计划不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前项目已存在同名接口自动化定时计划");
        }
        ApiAutomationSchedule result = schedules.findById(scheduleId).orElseThrow();
        sync(result);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_SCHEDULE_UPDATED",
                "API_AUTOMATION_SCHEDULE", scheduleId, auditDetail(result));
        return result;
    }

    @Transactional
    public ApiAutomationSchedule updateIfUnchanged(AuthenticatedPrincipal actor, String project, String id, ScheduleInput input, Instant expectedUpdatedAt) {
        access.requireProjectParameterWrite(actor, project, WRITERS);
        schedules.lock(project, id);
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(owned(project, id).updatedAt()))
            throw conflict("计划配置或状态已变化，请刷新并核对后再保存／启用");
        return update(actor, project, id, input);
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String scheduleId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ApiAutomationSchedule schedule = owned(projectId, scheduleId);
        removeQuartz(schedule.id());
        schedules.delete(projectId, scheduleId);
        audit.record(principal, project.organizationId(), projectId, "API_AUTOMATION_SCHEDULE_DELETED",
                "API_AUTOMATION_SCHEDULE", scheduleId, Map.of("scenarioId", schedule.scenarioId()));
    }

    @Transactional
    public ApiAutomationExecution runNow(AuthenticatedPrincipal principal, String projectId, String scheduleId) {
        access.requireApiAutomationExecution(principal, projectId, WRITERS);
        ApiAutomationSchedule schedule = owned(projectId, scheduleId);
        if (!schedule.enabled()) throw conflict("已停用的定时计划不能立即执行");
        ApiAutomationExecution execution = executions.create(principal, projectId, schedule.scenarioId(), schedule.timeoutSeconds(),
                schedule.executionProfileId(), Map.of());
        schedules.markTriggered(scheduleId, execution.id(), Instant.now());
        audit.record(principal, schedule.organizationId(), projectId, "API_AUTOMATION_SCHEDULE_MANUAL_TRIGGERED",
                "API_AUTOMATION_EXECUTION", execution.id(), Map.of("scheduleId", scheduleId, "scenarioId", schedule.scenarioId()));
        return execution;
    }

    @Transactional
    public void executeQuartzJob(String scheduleId) {
        ApiAutomationSchedule schedule = schedules.findById(scheduleId).orElse(null);
        if (schedule == null || !schedule.enabled()) return;
        ScheduledAutomationPrincipal principal = new ScheduledAutomationPrincipal(schedule.createdBy(), schedule.projectId(), schedule.id());
        ApiAutomationExecution execution = executions.create(principal, schedule.projectId(), schedule.scenarioId(), schedule.timeoutSeconds(),
                schedule.executionProfileId(), Map.of());
        schedules.markTriggered(schedule.id(), execution.id(), Instant.now());
        audit.record(principal, schedule.organizationId(), schedule.projectId(), "API_AUTOMATION_SCHEDULE_TRIGGERED",
                "API_AUTOMATION_EXECUTION", execution.id(), Map.of("scheduleId", schedule.id(), "scenarioId", schedule.scenarioId()));
    }

    @Scheduled(fixedDelayString = "${testhub.automation.schedules.outcome-sweep-interval-ms:3000}")
    @Transactional
    public void reconcileOutcomes() {
        for (ApiAutomationScheduleRepository.ScheduleOutcome outcome : schedules.findTerminalOutcomesToReconcile()) {
            boolean failed = FAILED_STATUSES.contains(outcome.executionStatus());
            int consecutiveFailures = failed ? outcome.consecutiveFailures() + 1 : 0;
            boolean enabled = !failed || consecutiveFailures < outcome.maxConsecutiveFailures();
            schedules.applyOutcome(outcome.scheduleId(), outcome.executionStatus(), consecutiveFailures, enabled);
            if (!enabled) {
                removeQuartz(outcome.scheduleId());
                audit.recordSystem(outcome.organizationId(), outcome.projectId(), "API_AUTOMATION_SCHEDULE_AUTO_DISABLED",
                        "API_AUTOMATION_SCHEDULE", outcome.scheduleId(), Map.of("executionId", outcome.executionId(),
                                "status", outcome.executionStatus(), "consecutiveFailures", consecutiveFailures));
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreQuartzJobs() {
        for (ApiAutomationSchedule schedule : schedules.findEnabled()) {
            try {
                sync(schedule);
            } catch (RuntimeException ignored) {
                // Individual malformed legacy schedules must not prevent the control plane from starting.
            }
        }
    }

    @Transactional
    public ApiAutomationSchedule saveSyncedConfiguration(AuthenticatedPrincipal actor, String projectId, String id, ScheduleInput input) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        var p = payload(projectId, input);
        if (id == null) {
            id = UlidCreator.getUlid().toString();
            schedules.insert(id, project.organizationId(), projectId, p.scenarioId(), p.executionProfileId(), p.name(), p.cronExpression(),
                    p.timezone(), p.timeoutSeconds(), p.maxConsecutiveFailures(), false, actor.userId());
        } else {
            owned(projectId, id);
            schedules.updateSyncedConfiguration(projectId, id, p.scenarioId(), p.executionProfileId(), p.name(), p.cronExpression(),
                    p.timezone(), p.timeoutSeconds(), p.maxConsecutiveFailures());
        }
        var result = schedules.findById(id).orElseThrow();
        // Runtime changes must never escape a rolled-back multi-trigger sync transaction.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { sync(result); }
                });
        return result;
    }

    private void sync(ApiAutomationSchedule schedule) {
        if (!schedule.enabled()) {
            removeQuartz(schedule.id());
            return;
        }
        try {
            JobKey jobKey = jobKey(schedule.id());
            if (scheduler.checkExists(jobKey)) scheduler.deleteJob(jobKey);
            JobDetail job = JobBuilder.newJob(ApiAutomationScheduleJob.class).withIdentity(jobKey)
                    .usingJobData("scheduleId", schedule.id()).build();
            CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey(schedule.id())).forJob(job)
                    .withSchedule(CronScheduleBuilder.cronSchedule(schedule.cronExpression())
                            .inTimeZone(TimeZone.getTimeZone(schedule.timezone()))
                            .withMisfireHandlingInstructionDoNothing()).build();
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("无法同步接口自动化定时计划到 Quartz", exception);
        }
    }

    private void removeQuartz(String scheduleId) {
        try {
            JobKey key = jobKey(scheduleId);
            if (scheduler.checkExists(key)) scheduler.deleteJob(key);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("无法从 Quartz 移除接口自动化定时计划", exception);
        }
    }

    private SchedulePayload payload(String projectId, ScheduleInput input) {
        if (input == null) throw rejected("定时计划参数不能为空");
        String name = required(input.name(), "计划名称", 160);
        String scenarioId = required(input.scenarioId(), "接口自动化场景", 26);
        scenarios.findDetail(projectId, scenarioId).orElseThrow(() -> notFound("接口自动化场景不存在"));
        String executionProfileId = input.executionProfileId();
        if (executionProfileId != null && !executionProfileId.isBlank()) {
            if (executionProfileId.length() != 26 || profiles.findDetail(projectId, executionProfileId).isEmpty())
                throw rejected("请选择当前项目的运行配置集");
        } else executionProfileId = null;
        String cron = required(input.cronExpression(), "Cron 表达式", 160);
        if (!org.quartz.CronExpression.isValidExpression(cron)) throw rejected("Cron 表达式不合法（使用 Quartz 六或七段格式）");
        String timezone = required(input.timezone(), "时区", 64);
        try { ZoneId.of(timezone); } catch (Exception exception) { throw rejected("时区不合法"); }
        int timeout = input.timeoutSeconds() == null ? 300 : input.timeoutSeconds();
        if (timeout < 10 || timeout > 3600) throw rejected("执行超时时间应在 10 到 3600 秒之间");
        int maximumFailures = input.maxConsecutiveFailures() == null ? 3 : input.maxConsecutiveFailures();
        if (maximumFailures < 1 || maximumFailures > 10) throw rejected("连续失败停用阈值应在 1 到 10 之间");
        return new SchedulePayload(scenarioId, executionProfileId, name, cron, timezone, timeout, maximumFailures,
                input.enabled() == null || input.enabled());
    }

    private ApiAutomationSchedule owned(String projectId, String scheduleId) {
        ApiAutomationSchedule schedule = schedules.findById(scheduleId).orElseThrow(() -> notFound("接口自动化定时计划不存在"));
        if (!schedule.projectId().equals(projectId)) throw new org.springframework.security.access.AccessDeniedException("定时计划不属于当前项目");
        return schedule;
    }

    private Map<String, Object> auditDetail(ApiAutomationSchedule schedule) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("name", schedule.name()); detail.put("scenarioId", schedule.scenarioId());
        if (schedule.executionProfileId() != null) detail.put("executionProfileId", schedule.executionProfileId());
        detail.put("cronExpression", schedule.cronExpression()); detail.put("timezone", schedule.timezone());
        detail.put("timeoutSeconds", schedule.timeoutSeconds()); detail.put("enabled", schedule.enabled());
        detail.put("maxConsecutiveFailures", schedule.maxConsecutiveFailures());
        return detail;
    }

    private String required(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw rejected(label + "长度应在 1 到 " + maxLength + " 字符之间");
        }
        return normalized;
    }

    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    private JobKey jobKey(String scheduleId) { return new JobKey(scheduleId, QUARTZ_GROUP); }
    private TriggerKey triggerKey(String scheduleId) { return new TriggerKey(scheduleId, QUARTZ_GROUP); }

    private record SchedulePayload(String scenarioId, String executionProfileId, String name, String cronExpression, String timezone,
                                   int timeoutSeconds, int maxConsecutiveFailures, boolean enabled) {
    }
    public record ScheduleInput(String scenarioId, String executionProfileId, String name, String cronExpression, String timezone,
                                Integer timeoutSeconds, Integer maxConsecutiveFailures, Boolean enabled) {
    }
}
