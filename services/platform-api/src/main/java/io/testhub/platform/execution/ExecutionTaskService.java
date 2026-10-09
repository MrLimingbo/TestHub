package io.testhub.platform.execution;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionTaskService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    static final Set<String> TYPES = Set.of("API", "FUNCTIONAL", "PERFORMANCE", "UI", "MOBILE", "OTHER");
    static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private final ExecutionTaskRepository tasks;
    private final AccessService access;
    private final AuditService audit;

    public ExecutionTaskService(ExecutionTaskRepository tasks, AccessService access, AuditService audit) {
        this.tasks = tasks;
        this.access = access;
        this.audit = audit;
    }

    public List<ExecutionTask> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return tasks.findByProject(projectId);
    }

    @Transactional
    public ExecutionTask create(AuthenticatedPrincipal principal, String projectId, TaskInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务参数不能为空");
        validateName(input.name());
        String type = normalizeType(input.taskType());
        String id = UlidCreator.getUlid().toString();
        tasks.insert(id, project.organizationId(), projectId, type, input.name().trim(), principal.userId());
        audit.record(principal, project.organizationId(), projectId, "EXECUTION_TASK_CREATED", "EXECUTION_TASK", id,
                Map.of("name", input.name().trim(), "taskType", type));
        return tasks.findById(projectId, id).orElseThrow();
    }

    @Transactional
    public ExecutionTask update(AuthenticatedPrincipal principal, String projectId, String taskId, TaskUpdate input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务参数不能为空");
        validateName(input.name());
        ExecutionTask current = locked(projectId, taskId);
        requireVersion(current, input.expectedVersion());
        String status = normalizeStatus(input.status());
        int progress = input.progress();
        if (progress < 0 || progress > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "进度应在 0 到 100 之间");
        if (status.equals("SUCCEEDED")) progress = 100;
        if (status.equals("QUEUED")) progress = 0;
        if (tasks.update(projectId, taskId, input.name().trim(), status, progress) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        }
        audit.record(principal, project.organizationId(), projectId, "EXECUTION_TASK_UPDATED", "EXECUTION_TASK", taskId,
                Map.of("status", status, "progress", progress));
        return tasks.findById(projectId, taskId).orElseThrow();
    }

    @Transactional
    public ExecutionTask cancel(AuthenticatedPrincipal principal, String projectId, String taskId) {
        return cancel(principal, projectId, taskId, null);
    }

    @Transactional
    public ExecutionTask cancel(AuthenticatedPrincipal principal, String projectId, String taskId, Long expectedVersion) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ExecutionTask task = locked(projectId, taskId);
        requireVersion(task, expectedVersion);
        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(task.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已结束，不能取消");
        }
        tasks.update(projectId, taskId, task.name(), "CANCELLED", task.progress());
        audit.record(principal, project.organizationId(), projectId, "EXECUTION_TASK_CANCELLED", "EXECUTION_TASK", taskId, Map.of());
        return tasks.findById(projectId, taskId).orElseThrow();
    }

    private ExecutionTask locked(String projectId, String taskId) {
        return tasks.lock(projectId, taskId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
    }
    private void requireVersion(ExecutionTask task, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务版本不合法");
        if (expectedVersion != null && expectedVersion != task.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已更新，请刷新后核对再操作");
    }

    private void validateName(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务名称长度应在 1 到 160 字符之间");
        }
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务类型");
        return type;
    }

    private String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务状态");
        return status;
    }

    public record TaskInput(String taskType, String name) {}
    public record TaskUpdate(String name, String status, int progress, Long expectedVersion) {
        public TaskUpdate(String name, String status, int progress) { this(name, status, progress, null); }
    }
}
