package io.testhub.platform.apiautomation;

import io.testhub.platform.shared.api.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api-runner/v1")
public class ApiRunnerCapabilityController {
    private final ApiRunnerInternalAuthenticationService authentication;
    private final ApiRunnerRuntimeLeaseService runtimeLeases;
    private final ApiRunnerStatusService runners;
    private final boolean httpExecutionEnabled;

    public ApiRunnerCapabilityController(ApiRunnerInternalAuthenticationService authentication,
                                         ApiRunnerRuntimeLeaseService runtimeLeases, ApiRunnerStatusService runners,
                                         @Value("${testhub.runner.http-execution-enabled:false}") boolean httpExecutionEnabled) {
        this.authentication = authentication;
        this.runtimeLeases = runtimeLeases;
        this.runners = runners;
        this.httpExecutionEnabled = httpExecutionEnabled;
    }

    @GetMapping("/capabilities")
    public ApiResponse<ApiRunnerCapability> capabilities(HttpServletRequest request) {
        String runnerId = authentication.authenticateCapabilities(request);
        boolean executionEnabled = httpExecutionEnabled && runners.isTrustedExecutionEnabled(runnerId);
        return ApiResponse.of(new ApiRunnerCapability(runnerId, ApiRunnerProtocol.VERSION, true, executionEnabled,
                executionEnabled ? "HTTP_FLOW_CONTROL_SCRIPTS_V2" : "DISABLED",
                executionEnabled ? List.of("SAFETY_PRECHECK", "SIGNED_CAPABILITY_DISCOVERY", "HTTP_REQUEST_EXECUTION", "FLOW_CONTROL", "PARALLEL_REQUEST_GROUP", "ISOLATED_SCRIPT_EXECUTION")
                        : List.of("SAFETY_PRECHECK", "SIGNED_CAPABILITY_DISCOVERY"),
                executionEnabled ? List.of("ENVIRONMENT_SECRET_DELIVERY")
                        : List.of("RUNTIME_CONFIGURATION", "USER_CONTENT_EXECUTION")));
    }

    @PostMapping("/runtime-leases")
    public ApiResponse<ApiRunnerRuntimeLease> issueRuntimeLease(HttpServletRequest request,
                                                                  @RequestBody JsonNode body) {
        RuntimeLeaseRequest leaseRequest = leaseRequest(body);
        String runnerId = authentication.authenticateRuntimeLease(request, leaseRequest.taskId(), leaseRequest.executionId());
        return ApiResponse.of(runtimeLeases.issue(runnerId, leaseRequest.taskId(), leaseRequest.executionId()));
    }

    @PostMapping("/runtime-leases/inspect")
    public ApiResponse<ApiRunnerRuntimeLeaseIndex> inspectRuntimeLease(HttpServletRequest request,
                                                                         @RequestBody JsonNode body) {
        RuntimeLeaseInspectionRequest inspection = inspectionRequest(body);
        String fingerprint = ApiRunnerRuntimeLeaseService.leaseFingerprint(inspection.lease());
        String runnerId = authentication.authenticateRuntimeLeaseInspection(request, inspection.taskId(),
                inspection.executionId(), fingerprint);
        return ApiResponse.of(runtimeLeases.inspect(runnerId, inspection.taskId(), inspection.executionId(), inspection.lease()));
    }

    @PostMapping("/runtime-configuration")
    public ApiResponse<ApiRunnerRuntimeConfiguration> runtimeConfiguration(HttpServletRequest request,
                                                                             @RequestBody JsonNode body) {
        RuntimeLeaseInspectionRequest configuration = inspectionRequest(body);
        String fingerprint = ApiRunnerRuntimeLeaseService.leaseFingerprint(configuration.lease());
        String runnerId = authentication.authenticateRuntimeConfiguration(request, configuration.taskId(),
                configuration.executionId(), fingerprint);
        return ApiResponse.of(runtimeLeases.runtimeConfiguration(runnerId, configuration.taskId(),
                configuration.executionId(), configuration.lease()));
    }

    private RuntimeLeaseRequest leaseRequest(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.path("taskId").isTextual()
                || !body.path("executionId").isTextual()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Runner 运行时租约请求格式不合法");
        }
        String taskId = body.path("taskId").asText();
        String executionId = body.path("executionId").asText();
        if (!taskId.matches("[0-9A-HJKMNP-TV-Z]{26}") || !executionId.matches("[0-9A-HJKMNP-TV-Z]{26}")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Runner 运行时租约请求格式不合法");
        }
        return new RuntimeLeaseRequest(taskId, executionId);
    }

    private RuntimeLeaseInspectionRequest inspectionRequest(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 3 || !body.path("taskId").isTextual()
                || !body.path("executionId").isTextual() || !body.path("lease").isTextual()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Runner 运行时租约请求格式不合法");
        }
        String taskId = body.path("taskId").asText();
        String executionId = body.path("executionId").asText();
        String lease = body.path("lease").asText();
        if (!taskId.matches("[0-9A-HJKMNP-TV-Z]{26}") || !executionId.matches("[0-9A-HJKMNP-TV-Z]{26}")
                || !lease.matches("[A-Za-z0-9_-]{43}")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Runner 运行时租约请求格式不合法");
        }
        return new RuntimeLeaseInspectionRequest(taskId, executionId, lease);
    }

    private record RuntimeLeaseRequest(String taskId, String executionId) {
    }

    private record RuntimeLeaseInspectionRequest(String taskId, String executionId, String lease) {
    }
}
