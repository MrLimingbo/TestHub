package io.testhub.platform.uiautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTestExecutionRepository;
import io.testhub.platform.functional.FunctionalTestPlan;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UiAutomationScheduleService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final String QUARTZ_GROUP = "testhub-ui-automation";
    private final UiAutomationScheduleRepository schedules;
    private final UiAutomationRepository automation;
    private final UiAutomationService executions;
    private final FunctionalTestExecutionRepository plans;
    private final AccessService access;
    private final AuditService audit;
    private final Scheduler scheduler;

    public UiAutomationScheduleService(UiAutomationScheduleRepository schedules, UiAutomationRepository automation,
                                       UiAutomationService executions, FunctionalTestExecutionRepository plans,
                                       AccessService access, AuditService audit, Scheduler scheduler) {
        this.schedules = schedules; this.automation = automation; this.executions = executions; this.plans = plans;
        this.access = access; this.audit = audit; this.scheduler = scheduler;
    }

    public List<UiAutomationSchedule> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return schedules.findByProject(projectId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UiAutomationSchedule create(AuthenticatedPrincipal principal, String projectId, ScheduleInput input) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationScheduleRepository.ScheduleInput payload = payload(projectId, input);
        String id = UlidCreator.getUlid().toString();
        try { schedules.insert(id, project.organizationId(), projectId, payload, principal.userId()); }
        catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名 Web UI 自动化定时计划"); }
        UiAutomationSchedule schedule = schedules.findById(id).orElseThrow();
        sync(schedule);
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_SCHEDULE_CREATED", "UI_AUTOMATION_SCHEDULE", id, detail(schedule));
        return schedule;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UiAutomationSchedule update(AuthenticatedPrincipal principal, String projectId, String scheduleId, ScheduleInput input) {
        ProjectSummary project = writable(principal, projectId);
        owned(projectId, scheduleId);
        UiAutomationScheduleRepository.ScheduleInput payload = payload(projectId, input);
        try {
            if (schedules.update(projectId, scheduleId, payload, principal.userId()) == 0) throw notFound("Web UI 自动化定时计划不存在");
        } catch (DataIntegrityViolationException exception) { throw conflict("当前项目已存在同名 Web UI 自动化定时计划"); }
        UiAutomationSchedule schedule = schedules.findById(scheduleId).orElseThrow();
        sync(schedule);
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_SCHEDULE_UPDATED", "UI_AUTOMATION_SCHEDULE", scheduleId, detail(schedule));
        return schedule;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthenticatedPrincipal principal, String projectId, String scheduleId) {
        ProjectSummary project = writable(principal, projectId);
        UiAutomationSchedule schedule = owned(projectId, scheduleId);
        schedules.delete(projectId, scheduleId);
        afterCommit(() -> syncCurrent(scheduleId));
        audit.record(principal, project.organizationId(), projectId, "UI_AUTOMATION_SCHEDULE_DELETED", "UI_AUTOMATION_SCHEDULE", scheduleId,
                Map.of("scenarioId", schedule.scenarioId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UiAutomationExecution runNow(AuthenticatedPrincipal principal, String projectId, String scheduleId) {
        writable(principal, projectId);
        UiAutomationSchedule schedule = owned(projectId, scheduleId);
        if (!schedule.enabled()) throw conflict("已停用的定时计划不能立即执行");
        UiAutomationExecution execution = executions.executeFromSchedule(schedule);
        audit.record(principal, schedule.organizationId(), projectId, "UI_AUTOMATION_SCHEDULE_MANUAL_TRIGGERED", "UI_AUTOMATION_EXECUTION",
                execution.id(), Map.of("scheduleId", scheduleId, "scenarioId", schedule.scenarioId()));
        return execution;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void executeQuartzJob(String scheduleId) {
        UiAutomationSchedule schedule = schedules.findById(scheduleId).orElse(null);
        if (schedule == null) return;
        automation.lockProject(schedule.projectId());
        schedule = schedules.findById(scheduleId).orElse(null);
        if (schedule == null || !schedule.enabled()) return;
        UiAutomationExecution execution = executions.executeFromSchedule(schedule);
        audit.recordSystem(schedule.organizationId(), schedule.projectId(), "UI_AUTOMATION_SCHEDULE_TRIGGERED", "UI_AUTOMATION_EXECUTION",
                execution.id(), Map.of("scheduleId", schedule.id(), "scenarioId", schedule.scenarioId()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreQuartzJobs() {
        for (UiAutomationSchedule schedule : schedules.findEnabled()) {
            try { sync(schedule); } catch (RuntimeException ignored) { /* a malformed legacy entry must not block startup */ }
        }
    }

    private UiAutomationScheduleRepository.ScheduleInput payload(String projectId, ScheduleInput input) {
        if (input == null) throw rejected("定时计划参数不能为空");
        String scenarioId = required(input.scenarioId(), "Web UI 场景", 26);
        automation.findScenario(projectId, scenarioId).orElseThrow(() -> notFound("Web UI 场景不存在"));
        String functionalPlanId = optionalId(input.functionalPlanId());
        planName(projectId, functionalPlanId);
        return normalizeSyncFields(new ScheduleInput(scenarioId, functionalPlanId, input.name(), input.cronExpression(), input.timezone(), input.timeoutSeconds(), input.maxRetryAttempts(), input.retryBackoffSeconds(), input.enabled()));
    }

    UiAutomationScheduleRepository.ScheduleInput normalizeSyncFields(ScheduleInput input) {
        String scenarioId = input.scenarioId(), functionalPlanId = input.functionalPlanId();
        String cron = required(input.cronExpression(), "Cron 表达式", 160);
        if (!org.quartz.CronExpression.isValidExpression(cron)) throw rejected("Cron 表达式不合法（使用 Quartz 六或七段格式）");
        String timezone = required(input.timezone(), "时区", 64);
        try { ZoneId.of(timezone); } catch (Exception exception) { throw rejected("时区不合法"); }
        int timeout = bounded(input.timeoutSeconds(), 300, 10, 3600, "执行超时");
        int retries = bounded(input.maxRetryAttempts(), 0, 0, 5, "失败重试次数");
        int backoff = bounded(input.retryBackoffSeconds(), 30, 5, 3600, "失败重试间隔");
        return new UiAutomationScheduleRepository.ScheduleInput(scenarioId, functionalPlanId, required(input.name(), "计划名称", 160),
                cron, timezone, timeout, retries, backoff, input.enabled() == null || input.enabled());
    }

    private String planName(String projectId, String planId) {
        if (planId == null) return null;
        FunctionalTestPlan plan = plans.findPlan(projectId, planId).orElseThrow(() -> notFound("功能测试计划不存在"));
        return plan.name();
    }

    private UiAutomationSchedule owned(String projectId, String scheduleId) {
        UiAutomationSchedule schedule = schedules.findById(scheduleId).orElseThrow(() -> notFound("Web UI 自动化定时计划不存在"));
        if (!projectId.equals(schedule.projectId())) throw new org.springframework.security.access.AccessDeniedException("定时计划不属于当前项目");
        return schedule;
    }

    void refreshSyncedSchedule(String id) { afterCommit(() -> syncCurrent(id)); }

    private void sync(UiAutomationSchedule schedule) { refreshSyncedSchedule(schedule.id()); }

    private void afterCommit(Runnable operation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { operation.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { operation.run(); }
        });
    }

    private synchronized void syncCurrent(String id) {
        UiAutomationSchedule schedule = schedules.findById(id).orElse(null);
        if (schedule == null) { removeQuartz(id); return; }
        if (!schedule.enabled()) { removeQuartz(schedule.id()); return; }
        try {
            JobKey jobKey = jobKey(schedule.id());
            if (scheduler.checkExists(jobKey)) scheduler.deleteJob(jobKey);
            JobDetail job = JobBuilder.newJob(UiAutomationScheduleJob.class).withIdentity(jobKey).usingJobData("scheduleId", schedule.id()).build();
            CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey(schedule.id())).forJob(job)
                    .withSchedule(CronScheduleBuilder.cronSchedule(schedule.cronExpression()).inTimeZone(TimeZone.getTimeZone(schedule.timezone()))
                            .withMisfireHandlingInstructionDoNothing()).build();
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException exception) { throw new IllegalStateException("无法同步 Web UI 自动化定时计划到 Quartz", exception); }
    }

    private void removeQuartz(String scheduleId) {
        try { if (scheduler.checkExists(jobKey(scheduleId))) scheduler.deleteJob(jobKey(scheduleId)); }
        catch (SchedulerException exception) { throw new IllegalStateException("无法从 Quartz 移除 Web UI 自动化定时计划", exception); }
    }
    private ProjectSummary writable(AuthenticatedPrincipal principal, String projectId) { var project = access.requireProjectParameterWrite(principal, projectId, WRITERS); automation.lockProject(projectId); return project; }
    private String required(String value, String label, int max) { String result = value == null ? "" : value.trim(); if (result.isEmpty() || result.length() > max) throw rejected(label + "长度应在 1 到 " + max + " 字符之间"); return result; }
    private String optionalId(String value) { if (value == null || value.isBlank()) return null; String result = value.trim(); if (!result.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw rejected("功能测试计划标识不合法"); return result; }
    private int bounded(Integer value, int fallback, int min, int max, String label) { int result = value == null ? fallback : value; if (result < min || result > max) throw rejected(label + "应在 " + min + " 到 " + max + " 之间"); return result; }
    private Map<String, Object> detail(UiAutomationSchedule value) { return Map.of("name", value.name(), "scenarioId", value.scenarioId(), "functionalPlanId", value.functionalPlanId() == null ? "" : value.functionalPlanId(), "cronExpression", value.cronExpression(), "timezone", value.timezone(), "timeoutSeconds", value.timeoutSeconds(), "maxRetryAttempts", value.maxRetryAttempts(), "enabled", value.enabled()); }
    private JobKey jobKey(String scheduleId) { return new JobKey(scheduleId, QUARTZ_GROUP); }
    private TriggerKey triggerKey(String scheduleId) { return new TriggerKey(scheduleId, QUARTZ_GROUP); }
    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record ScheduleInput(String scenarioId, String functionalPlanId, String name, String cronExpression, String timezone,
                                Integer timeoutSeconds, Integer maxRetryAttempts, Integer retryBackoffSeconds, Boolean enabled) { }
}
