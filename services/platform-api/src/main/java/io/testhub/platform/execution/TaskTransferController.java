package io.testhub.platform.execution;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/transfer")
public class TaskTransferController {
    private final TaskTransferService transfer;
    public TaskTransferController(TaskTransferService transfer) { this.transfer = transfer; }
    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(transfer.capabilities((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }
    @GetMapping
    public ApiResponse<TaskTransferService.Snapshot> export(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(transfer.export((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }
    @PostMapping
    public ApiResponse<TaskTransferService.Receipt> transfer(Authentication authentication, @PathVariable String projectId,
                                                                   @RequestBody byte[] body) {
        return ApiResponse.of(transfer.transfer((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, transfer.decode(body)));
    }
}
