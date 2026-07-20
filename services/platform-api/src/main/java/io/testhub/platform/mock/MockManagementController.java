package io.testhub.platform.mock;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/mock")
public class MockManagementController {
    private final MockService mocks;

    public MockManagementController(MockService mocks) {
        this.mocks = mocks;
    }

    @GetMapping("/servers")
    public ApiResponse<List<MockServer>> servers(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(mocks.servers(principal(authentication), projectId));
    }

    @PostMapping("/servers")
    public ApiResponse<MockServer> createServer(Authentication authentication, @PathVariable String projectId,
                                                 @Valid @RequestBody ServerRequest request) {
        return ApiResponse.of(mocks.createServer(principal(authentication), projectId, new MockService.ServerInput(request.name())));
    }

    @PostMapping("/servers/{serverId}/enabled")
    public ApiResponse<MockServer> setServerEnabled(Authentication authentication, @PathVariable String projectId,
                                                     @PathVariable String serverId, @RequestBody EnabledRequest request) {
        return ApiResponse.of(mocks.setServerEnabled(principal(authentication), projectId, serverId, request.enabled()));
    }

    @PostMapping("/servers/{serverId}/smart-example/enabled")
    public ApiResponse<MockServer> setSmartExampleEnabled(Authentication authentication, @PathVariable String projectId,
                                                           @PathVariable String serverId, @RequestBody EnabledRequest request) {
        return ApiResponse.of(mocks.setSmartExampleEnabled(principal(authentication), projectId, serverId, request.enabled()));
    }

    @GetMapping("/servers/{serverId}/smart-examples")
    public ApiResponse<List<SmartMockExample>> smartExamples(Authentication authentication, @PathVariable String projectId,
                                                               @PathVariable String serverId) {
        return ApiResponse.of(mocks.smartExamples(principal(authentication), projectId, serverId));
    }

    @GetMapping("/servers/{serverId}/shares")
    public ApiResponse<List<MockShare>> shares(Authentication authentication, @PathVariable String projectId,
                                                @PathVariable String serverId) {
        return ApiResponse.of(mocks.shares(principal(authentication), projectId, serverId));
    }

    @PostMapping("/servers/{serverId}/shares")
    public ApiResponse<MockShare> createShare(Authentication authentication, @PathVariable String projectId,
                                               @PathVariable String serverId, @Valid @RequestBody ShareRequest request) {
        return ApiResponse.of(mocks.createShare(principal(authentication), projectId, serverId,
                new MockService.ShareInput(request.name(), request.expiresInDays())));
    }

    @PostMapping("/servers/{serverId}/shares/{shareId}/revoke")
    public ApiResponse<MockShare> revokeShare(Authentication authentication, @PathVariable String projectId,
                                               @PathVariable String serverId, @PathVariable String shareId) {
        return ApiResponse.of(mocks.revokeShare(principal(authentication), projectId, serverId, shareId));
    }

    @GetMapping("/servers/{serverId}/rules")
    public ApiResponse<List<MockRule>> rules(Authentication authentication, @PathVariable String projectId, @PathVariable String serverId) {
        return ApiResponse.of(mocks.rules(principal(authentication), projectId, serverId));
    }

    @PostMapping("/servers/{serverId}/rules")
    public ApiResponse<MockRule> createRule(Authentication authentication, @PathVariable String projectId, @PathVariable String serverId,
                                             @Valid @RequestBody RuleRequest request) {
        return ApiResponse.of(mocks.createRule(principal(authentication), projectId, serverId,
                new MockService.RuleInput(request.name(), request.httpMethod(), request.path(), request.priority(), request.requestQuery(),
                        request.requestHeaders(), request.requestBodyMatch(), request.requestBody(), request.responseStatus(),
                        request.responseHeaders(), request.responseBody())));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record ServerRequest(@NotBlank @Size(max = 160) String name) {
    }
    public record EnabledRequest(boolean enabled) {
    }
    public record ShareRequest(@NotBlank @Size(max = 120) String name, Integer expiresInDays) {
    }
    public record RuleRequest(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 12) String httpMethod,
                              @NotBlank @Size(max = 700) String path, Integer priority,
                              @Size(max = 32) Map<@NotBlank @Size(max = 128) String, @Size(max = 8192) String> requestQuery,
                              @Size(max = 32) Map<@NotBlank @Size(max = 128) String, @Size(max = 8192) String> requestHeaders,
                              @Size(max = 16) String requestBodyMatch, @Size(max = 524288) String requestBody, Integer responseStatus,
                              @Size(max = 32) Map<@NotBlank @Size(max = 128) String, @Size(max = 8192) String> responseHeaders,
                              @Size(max = 524288) String responseBody) {
    }
}
