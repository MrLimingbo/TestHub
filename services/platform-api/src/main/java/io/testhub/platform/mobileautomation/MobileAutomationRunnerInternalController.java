package io.testhub.platform.mobileautomation;

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
@RequestMapping("/internal/mobile-automation-runner/v1")
public class MobileAutomationRunnerInternalController {
    private final MobileAutomationRunnerInternalAuthenticationService authentication;
    private final MobileAutomationService automation;
    private final MobileAutomationExecutionArtifactService artifacts;
    public MobileAutomationRunnerInternalController(MobileAutomationRunnerInternalAuthenticationService authentication, MobileAutomationService automation, MobileAutomationExecutionArtifactService artifacts) { this.authentication = authentication; this.automation = automation; this.artifacts = artifacts; }
    @PostMapping("/runtime-configuration")
    public ApiResponse<MobileAutomationService.RunnerRuntimeConfiguration> runtime(HttpServletRequest request, @RequestBody JsonNode body) {
        Request task = task(body); String runnerId = authentication.authenticateRuntime(request, task.taskId(), task.executionId());
        return ApiResponse.of(automation.runtimeConfiguration(runnerId, task.taskId(), task.executionId()));
    }
    @PostMapping("/artifacts")
    public ApiResponse<MobileAutomationExecutionArtifact> artifact(HttpServletRequest request) throws IOException {
        MobileAutomationExecutionArtifactService.Upload upload = upload(request);
        return ApiResponse.of(artifacts.archive(authentication.authenticateArtifact(request, upload), upload, request.getInputStream()));
    }
    private Request task(JsonNode body) { if (body == null || !body.isObject() || body.size()!=2 || !body.path("taskId").isTextual() || !body.path("executionId").isTextual()) throw invalid("Appium Runner 运行时请求格式不合法"); String task=body.path("taskId").asText(), execution=body.path("executionId").asText(); if(!task.matches("[0-9A-HJKMNP-TV-Z]{26}")||!execution.matches("[0-9A-HJKMNP-TV-Z]{26}"))throw invalid("Appium Runner 运行时标识不合法");return new Request(task,execution); }
    private MobileAutomationExecutionArtifactService.Upload upload(HttpServletRequest request) { String task=header(request,"X-TestHub-Task-Id"),execution=header(request,"X-TestHub-Execution-Id"),type=header(request,"X-TestHub-Artifact-Type"),checksum=header(request,"X-TestHub-Artifact-SHA256"),size=header(request,"X-TestHub-Artifact-Size"); if(!task.matches("[0-9A-HJKMNP-TV-Z]{26}")||!execution.matches("[0-9A-HJKMNP-TV-Z]{26}")||!"SCREENSHOT".equals(type)||!checksum.matches("[0-9a-f]{64}")||!size.matches("[0-9]{1,8}"))throw invalid("Appium Runner 截图归档请求格式不合法"); long bytes=Long.parseLong(size);if(bytes>MobileAutomationExecutionArtifactService.MAX_ARTIFACT_BYTES||request.getContentLengthLong()!=bytes)throw invalid("Appium Runner 截图大小不合法");return new MobileAutomationExecutionArtifactService.Upload(task,execution,type,bytes,checksum); }
    private String header(HttpServletRequest request,String name){String value=request.getHeader(name);return value==null?"":value;}
    private ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private record Request(String taskId,String executionId) { }
}
