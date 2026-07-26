package io.testhub.platform.uiautomation;

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
@RequestMapping("/internal/ui-automation-runner/v1")
public class UiAutomationRunnerInternalController {
    private final UiAutomationRunnerInternalAuthenticationService authentication;
    private final UiAutomationService automation;
    private final UiAutomationExecutionArtifactService artifacts;
    public UiAutomationRunnerInternalController(UiAutomationRunnerInternalAuthenticationService authentication, UiAutomationService automation,
                                                UiAutomationExecutionArtifactService artifacts) {
        this.authentication = authentication; this.automation = automation; this.artifacts = artifacts;
    }
    @PostMapping("/runtime-configuration")
    public ApiResponse<UiAutomationRunnerRuntimeConfiguration> runtime(HttpServletRequest request, @RequestBody JsonNode body) {
        RuntimeRequest task = task(body);
        return ApiResponse.of(automation.runtimeConfiguration(authentication.authenticateRuntime(request, task.taskId(), task.executionId()), task.taskId(), task.executionId()));
    }
    @PostMapping("/artifacts")
    public ApiResponse<UiAutomationExecutionArtifact> artifact(HttpServletRequest request) throws IOException {
        UiAutomationExecutionArtifactService.Upload upload = upload(request);
        return ApiResponse.of(artifacts.archive(authentication.authenticateArtifact(request, upload), upload, request.getInputStream()));
    }
    private RuntimeRequest task(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.path("taskId").isTextual() || !body.path("executionId").isTextual()) throw invalid();
        String task = body.path("taskId").asText(), execution = body.path("executionId").asText();
        if (!task.matches("[0-9A-HJKMNP-TV-Z]{26}") || !execution.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw invalid();
        return new RuntimeRequest(task, execution);
    }
    private UiAutomationExecutionArtifactService.Upload upload(HttpServletRequest request) {
        String task = header(request, "X-TestHub-Task-Id"), execution = header(request, "X-TestHub-Execution-Id"), type = header(request, "X-TestHub-Artifact-Type"),
                checksum = header(request, "X-TestHub-Artifact-SHA256"), size = header(request, "X-TestHub-Artifact-Size");
        if (!task.matches("[0-9A-HJKMNP-TV-Z]{26}") || !execution.matches("[0-9A-HJKMNP-TV-Z]{26}") || !type.matches("(SCREENSHOT|TRACE|VIDEO)")
                || !checksum.matches("[0-9a-f]{64}") || !size.matches("[0-9]{1,8}")) throw invalidArtifact();
        long bytes;
        try { bytes = Long.parseLong(size); } catch (NumberFormatException exception) { throw invalidArtifact(); }
        if (bytes > UiAutomationExecutionArtifactService.MAX_ARTIFACT_BYTES || request.getContentLengthLong() != bytes) throw invalidArtifact();
        return new UiAutomationExecutionArtifactService.Upload(task, execution, type, bytes, checksum);
    }
    private String header(HttpServletRequest request, String name) { String value = request.getHeader(name); return value == null ? "" : value; }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playwright Runner 运行时请求格式不合法"); }
    private ResponseStatusException invalidArtifact() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playwright Runner 工件归档请求格式不合法"); }
    private record RuntimeRequest(String taskId, String executionId) { }
}
