package io.testhub.platform.reporting;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.notification.NotificationDeliveryService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
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
import org.springframework.web.server.ResponseStatusException;

@Service
public class QualityReportScheduleService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private static final String QUARTZ_GROUP = "testhub-quality-report";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final QualityReportScheduleRepository schedules;
    private final QualityReportService reports;
    private final NotificationDeliveryService deliveries;
    private final AccessService access;
    private final AuditService audit;
    private final Scheduler scheduler;

    public QualityReportScheduleService(QualityReportScheduleRepository schedules, QualityReportService reports,
                                        NotificationDeliveryService deliveries, AccessService access,
                                        AuditService audit, Scheduler scheduler) {
        this.schedules = schedules;
        this.reports = reports;
        this.deliveries = deliveries;
        this.access = access;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    public List<QualityReportSchedule> list(AuthenticatedPrincipal principal) {
        return access.context(principal).organizations().stream().flatMap(organization -> schedules.findByOrganization(organization.id()).stream())
                .filter(schedule -> readable(principal, schedule)).toList();
    }

    @Transactional
    public QualityReportSchedule create(AuthenticatedPrincipal principal, ScheduleInput input) {
        SchedulePayload payload = payload(principal, input);
        String id = UlidCreator.getUlid().toString();
        try {
            schedules.insert(id, payload.organizationId(), payload.name(), payload.projectIds(), payload.cronExpression(),
                    payload.timezone(), payload.periodDays(), payload.enabled(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前组织已存在同名质量报告计划");
        }
        QualityReportSchedule result = schedules.findById(id).orElseThrow();
        sync(result);
        audit.record(principal, result.organizationId(), payload.projectIds().getFirst(), "QUALITY_REPORT_SCHEDULE_CREATED",
                "QUALITY_REPORT_SCHEDULE", result.id(), auditDetail(result));
        return result;
    }

    @Transactional
    public QualityReportSchedule update(AuthenticatedPrincipal principal, String scheduleId, ScheduleInput input) {
        QualityReportSchedule existing = owned(principal, scheduleId, true);
        SchedulePayload payload = payload(principal, input);
        if (!existing.organizationId().equals(payload.organizationId())) {
            throw rejected("定时报告不可跨组织变更项目范围");
        }
        try {
            if (schedules.update(scheduleId, existing.organizationId(), payload.name(), payload.projectIds(), payload.cronExpression(),
                    payload.timezone(), payload.periodDays(), payload.enabled()) == 0) {
                throw notFound("质量报告计划不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("当前组织已存在同名质量报告计划");
        }
        QualityReportSchedule result = schedules.findById(scheduleId).orElseThrow();
        sync(result);
        audit.record(principal, result.organizationId(), result.projectIds().getFirst(), "QUALITY_REPORT_SCHEDULE_UPDATED",
                "QUALITY_REPORT_SCHEDULE", result.id(), auditDetail(result));
        return result;
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String scheduleId) {
        QualityReportSchedule schedule = owned(principal, scheduleId, true);
        removeQuartz(schedule.id());
        schedules.delete(schedule.id(), schedule.organizationId());
        audit.record(principal, schedule.organizationId(), schedule.projectIds().getFirst(), "QUALITY_REPORT_SCHEDULE_DELETED",
                "QUALITY_REPORT_SCHEDULE", schedule.id(), Map.of("name", schedule.name()));
    }

    @Transactional
    public QualityReportScheduleRun runNow(AuthenticatedPrincipal principal, String scheduleId) {
        QualityReportSchedule schedule = owned(principal, scheduleId, true);
        if (!schedule.enabled()) throw conflict("已停用的定时报告不能立即执行");
        return execute(schedule, principal);
    }

    public List<QualityReportScheduleRun> runs(AuthenticatedPrincipal principal, String scheduleId) {
        owned(principal, scheduleId, false);
        return schedules.findRuns(scheduleId, 50);
    }

    @Transactional
    public void executeQuartzJob(String scheduleId) {
        QualityReportSchedule schedule = schedules.findById(scheduleId).orElse(null);
        if (schedule == null || !schedule.enabled()) return;
        execute(schedule, new ScheduledQualityReportPrincipal(schedule.createdBy(), schedule.id()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreQuartzJobs() {
        for (QualityReportSchedule schedule : schedules.findEnabled()) {
            try {
                sync(schedule);
            } catch (RuntimeException ignored) {
                // A malformed historical schedule must not prevent startup.
            }
        }
    }

    private QualityReportScheduleRun execute(QualityReportSchedule schedule, AuthenticatedPrincipal principal) {
        Instant startedAt = Instant.now();
        LocalDate to = startedAt.atZone(REPORTING_ZONE).toLocalDate();
        LocalDate from = to.minusDays(schedule.periodDays() - 1L);
        String runId = UlidCreator.getUlid().toString();
        schedules.insertRun(runId, schedule, from, to, startedAt);
        try {
            QualityReport report = reports.get(principal, schedule.projectIds(), from, to);
            int deliveryCount = deliveries.enqueueQualityReport(runId, schedule, report);
            Instant finishedAt = Instant.now();
            schedules.completeRun(runId, "SUCCEEDED", deliveryCount, report.summary(), null, finishedAt);
            schedules.markScheduleRun(schedule, runId, "SUCCEEDED", finishedAt);
            audit.record(principal, schedule.organizationId(), schedule.projectIds().getFirst(), "QUALITY_REPORT_SCHEDULE_TRIGGERED",
                    "QUALITY_REPORT_SCHEDULE_RUN", runId, Map.of("scheduleId", schedule.id(), "deliveryCount", deliveryCount,
                            "from", from.toString(), "to", to.toString()));
        } catch (Exception exception) {
            Instant finishedAt = Instant.now();
            schedules.completeRun(runId, "FAILED", 0, emptySummary(), error(exception), finishedAt);
            schedules.markScheduleRun(schedule, runId, "FAILED", finishedAt);
            audit.recordSystem(schedule.organizationId(), schedule.projectIds().getFirst(), "QUALITY_REPORT_SCHEDULE_FAILED",
                    "QUALITY_REPORT_SCHEDULE_RUN", runId, Map.of("scheduleId", schedule.id(), "error", error(exception)));
        }
        return schedules.findRuns(schedule.id(), 50).stream().filter(run -> run.id().equals(runId)).findFirst().orElseThrow();
    }

    private SchedulePayload payload(AuthenticatedPrincipal principal, ScheduleInput input) {
        if (input == null) throw rejected("定时报告参数不能为空");
        String name = required(input.name(), "计划名称", 160);
        List<String> projectIds = normalizeProjectIds(input.projectIds());
        List<ProjectSummary> projects = projectIds.stream()
                .map(projectId -> access.requireProjectParameterWrite(principal, projectId, WRITERS)).toList();
        String organizationId = projects.getFirst().organizationId();
        if (projects.stream().anyMatch(project -> !organizationId.equals(project.organizationId()))) {
            throw rejected("所选项目暂无法合并到同一定时报告");
        }
        String cron = required(input.cronExpression(), "Cron 表达式", 160);
        if (!org.quartz.CronExpression.isValidExpression(cron)) throw rejected("Cron 表达式不合法（使用 Quartz 六或七段格式）");
        String timezone = required(input.timezone(), "时区", 64);
        try { ZoneId.of(timezone); } catch (Exception exception) { throw rejected("时区不合法"); }
        if (input.periodDays() == null || input.periodDays() < 1 || input.periodDays() > 90) {
            throw rejected("统计周期应在 1 到 90 天之间");
        }
        return new SchedulePayload(organizationId, name, projectIds, cron, timezone, input.periodDays(),
                input.enabled() == null || input.enabled());
    }

    private List<String> normalizeProjectIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) throw rejected("至少选择一个项目");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : raw) {
            String id = value == null ? "" : value.trim();
            if (id.isEmpty() || id.length() > 26) throw rejected("项目范围不合法");
            ids.add(id);
        }
        if (ids.size() > 50) throw rejected("单个定时报告最多选择 50 个项目");
        return List.copyOf(ids);
    }

    private boolean readable(AuthenticatedPrincipal principal, QualityReportSchedule schedule) {
        try {
            schedule.projectIds().forEach(projectId -> access.requireProjectRead(principal, projectId));
            return true;
        } catch (org.springframework.security.access.AccessDeniedException | ResponseStatusException exception) {
            return false;
        }
    }

    private QualityReportSchedule owned(AuthenticatedPrincipal principal, String scheduleId, boolean write) {
        QualityReportSchedule schedule = schedules.findById(scheduleId).orElseThrow(() -> notFound("质量报告计划不存在"));
        for (String projectId : schedule.projectIds()) {
            if (write) access.requireProjectParameterWrite(principal, projectId, WRITERS);
            else access.requireProjectRead(principal, projectId);
        }
        return schedule;
    }

    private void sync(QualityReportSchedule schedule) {
        if (!schedule.enabled()) {
            removeQuartz(schedule.id());
            return;
        }
        try {
            JobKey jobKey = jobKey(schedule.id());
            if (scheduler.checkExists(jobKey)) scheduler.deleteJob(jobKey);
            JobDetail job = JobBuilder.newJob(QualityReportScheduleJob.class).withIdentity(jobKey)
                    .usingJobData("scheduleId", schedule.id()).build();
            CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey(schedule.id())).forJob(job)
                    .withSchedule(CronScheduleBuilder.cronSchedule(schedule.cronExpression())
                            .inTimeZone(TimeZone.getTimeZone(schedule.timezone()))
                            .withMisfireHandlingInstructionDoNothing()).build();
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("无法同步质量报告定时计划到 Quartz", exception);
        }
    }

    private void removeQuartz(String scheduleId) {
        try {
            JobKey key = jobKey(scheduleId);
            if (scheduler.checkExists(key)) scheduler.deleteJob(key);
        } catch (SchedulerException exception) {
            throw new IllegalStateException("无法从 Quartz 移除质量报告定时计划", exception);
        }
    }

    private Map<String, Object> auditDetail(QualityReportSchedule schedule) {
        return Map.of("name", schedule.name(), "projectIds", schedule.projectIds(), "cronExpression", schedule.cronExpression(),
                "timezone", schedule.timezone(), "periodDays", schedule.periodDays(), "enabled", schedule.enabled());
    }

    private QualityReport.Summary emptySummary() { return new QualityReport.Summary(0, 0, 0, 0, 0, 0, 0, 0, null); }
    private String required(String value, String label, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) throw rejected(label + "长度应在 1 到 " + max + " 字符之间");
        return normalized;
    }
    private String error(Exception exception) {
        String message = exception instanceof ResponseStatusException status ? status.getReason() : exception.getClass().getSimpleName();
        return message == null ? "质量报告运行失败" : message.substring(0, Math.min(512, message.length()));
    }
    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private JobKey jobKey(String scheduleId) { return new JobKey(scheduleId, QUARTZ_GROUP); }
    private TriggerKey triggerKey(String scheduleId) { return new TriggerKey(scheduleId, QUARTZ_GROUP); }

    private record SchedulePayload(String organizationId, String name, List<String> projectIds, String cronExpression,
                                   String timezone, int periodDays, boolean enabled) { }
    public record ScheduleInput(String name, List<String> projectIds, String cronExpression, String timezone,
                                Integer periodDays, Boolean enabled) { }
}
