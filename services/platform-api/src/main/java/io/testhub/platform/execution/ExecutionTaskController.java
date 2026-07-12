package io.testhub.platform.execution;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class ExecutionTaskController {
    private final ExecutionTaskService tasks;

    public ExecutionTaskController(ExecutionTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<List<ExecutionTask>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(tasks.list(principal(authentication), projectId));
    }

    @PostMapping
    public ApiResponse<ExecutionTask> create(Authentication authentication, @PathVariable String projectId,
                                             @Valid @RequestBody TaskRequest request) {
        return ApiResponse.of(tasks.create(principal(authentication), projectId, new ExecutionTaskService.TaskInput(request.taskType(), request.name())));
    }

    @PutMapping("/{taskId}")
    public ApiResponse<ExecutionTask> update(Authentication authentication, @PathVariable String projectId,
                                             @PathVariable String taskId, @Valid @RequestBody TaskUpdateRequest request) {
        return ApiResponse.of(tasks.update(principal(authentication), projectId, taskId,
                new ExecutionTaskService.TaskUpdate(request.name(), request.status(), request.progress())));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<ExecutionTask> cancel(Authentication authentication, @PathVariable String projectId,
                                             @PathVariable String taskId) {
        return ApiResponse.of(tasks.cancel(principal(authentication), projectId, taskId));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record TaskRequest(@NotBlank String taskType, @NotBlank @Size(max = 160) String name) {}
    public record TaskUpdateRequest(@NotBlank @Size(max = 160) String name, @NotBlank String status, int progress) {}
}
