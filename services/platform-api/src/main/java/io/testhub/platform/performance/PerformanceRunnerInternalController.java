package io.testhub.platform.performance;

import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/performance-runner/v1")
public class PerformanceRunnerInternalController {
    private final PerformanceRunnerInternalAuthenticationService authentication;
    private final PerformanceRunnerRuntimeConfigurationService runtime;
    private final PerformanceTestExecutionArtifactService artifacts;

    public PerformanceRunnerInternalController(PerformanceRunnerInternalAuthenticationService authentication,
                                               PerformanceRunnerRuntimeConfigurationService runtime,
                                               PerformanceTestExecutionArtifactService artifacts) {
        this.authentication = authentication;
        this.runtime = runtime;
        this.artifacts = artifacts;
    }

    @PostMapping("/runtime-configuration")
    public ApiResponse<PerformanceRunnerRuntimeConfiguration> runtimeConfiguration(HttpServletRequest request,
                                                                                     @RequestBody JsonNode body) {
        RuntimeRequest task = task(body);
        String runnerId = authentication.authenticateRuntimeConfiguration(request, task.taskId(), task.executionId());
        return ApiResponse.of(runtime.issue(runnerId, task.taskId(), task.executionId()));
    }

    @PostMapping("/artifacts")
    public ApiResponse<PerformanceTestExecutionArtifact> archiveArtifact(HttpServletRequest request) throws IOException {
        PerformanceTestExecutionArtifactService.Upload upload = artifactUpload(request);
        String runnerId = authentication.authenticateArtifactUpload(request, upload);
        return ApiResponse.of(artifacts.archive(runnerId, upload, request.getInputStream()));
    }

    private RuntimeRequest task(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.path("taskId").isTextual()
                || !body.path("executionId").isTextual()) throw invalid();
        String taskId = body.path("taskId").asText();
        String executionId = body.path("executionId").asText();
        if (!taskId.matches("[0-9A-HJKMNP-TV-Z]{26}") || !executionId.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw invalid();
        return new RuntimeRequest(taskId, executionId);
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "JMeter Runner 运行时请求格式不合法");
    }

    private PerformanceTestExecutionArtifactService.Upload artifactUpload(HttpServletRequest request) {
        String taskId = header(request, "X-TestHub-Task-Id");
        String executionId = header(request, "X-TestHub-Execution-Id");
        String artifactType = header(request, "X-TestHub-Artifact-Type");
        String checksum = header(request, "X-TestHub-Artifact-SHA256");
        String size = header(request, "X-TestHub-Artifact-Size");
        if (!taskId.matches("[0-9A-HJKMNP-TV-Z]{26}") || !executionId.matches("[0-9A-HJKMNP-TV-Z]{26}")
                || !artifactType.matches("JMETER_(JTL|LOG)") || !checksum.matches("[0-9a-f]{64}")
                || !size.matches("[0-9]{1,8}")) throw invalidArtifact();
        long sizeBytes;
        try {
            sizeBytes = Long.parseLong(size);
        } catch (NumberFormatException exception) {
            throw invalidArtifact();
        }
        if (sizeBytes > PerformanceTestExecutionArtifactService.MAX_ARTIFACT_BYTES || request.getContentLengthLong() != sizeBytes) {
            throw invalidArtifact();
        }
        return new PerformanceTestExecutionArtifactService.Upload(taskId, executionId, artifactType, sizeBytes, checksum);
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? "" : value;
    }

    private ResponseStatusException invalidArtifact() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "JMeter Runner 工件归档请求格式不合法");
    }

    private record RuntimeRequest(String taskId, String executionId) {
    }
}
