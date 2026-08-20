package io.testhub.platform.apidebug;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-debug")
public class ApiDebugController {
    private final ApiDebugService debug;
    private final WebSocketDebugService webSockets;
    private final GrpcDebugService grpc;

    public ApiDebugController(ApiDebugService debug, WebSocketDebugService webSockets, GrpcDebugService grpc) {
        this.debug = debug;
        this.webSockets = webSockets;
        this.grpc = grpc;
    }

    @GetMapping("/history")
    public ApiResponse<List<ApiDebugHistory>> history(Authentication authentication, @PathVariable String projectId,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.of(debug.history(principal(authentication), projectId, limit));
    }

    @PostMapping("/execute")
    public ApiResponse<ApiDebugService.DebugResult> execute(Authentication authentication, @PathVariable String projectId,
                                                             @Valid @RequestBody ExecuteRequest request) {
        return ApiResponse.of(debug.execute(principal(authentication), projectId, debugRequest(request)));
    }

    @PostMapping("/local-executions")
    public ApiResponse<ApiDebugService.LocalExecutionReceipt> recordLocalExecution(Authentication authentication,
                                                                                     @PathVariable String projectId,
                                                                                     @Valid @RequestBody LocalExecutionRequest request) {
        return ApiResponse.of(debug.recordLocalExecution(principal(authentication), projectId,
                debugRequest(request.request()), new ApiDebugService.LocalExecutionResult(request.result().success(),
                        request.result().statusCode(), request.result().durationMs(), request.result().responseSize(),
                        request.result().errorMessage())));
    }

    @PostMapping("/drafts")
    public ApiResponse<ApiDebugHistory> saveDraft(Authentication authentication, @PathVariable String projectId,
                                                   @Valid @RequestBody ExecuteRequest request) {
        return ApiResponse.of(debug.saveDraft(principal(authentication), projectId, debugRequest(request)));
    }

    @PostMapping("/grpc/unary")
    public ApiResponse<GrpcDebugService.GrpcResult> grpcUnary(Authentication authentication, @PathVariable String projectId,
                                                               @Valid @RequestBody GrpcUnaryRequest request) {
        return ApiResponse.of(grpc.unary(principal(authentication), projectId, new GrpcDebugService.UnaryRequest(request.endpoint(), request.tls(),
                request.service(), request.method(), request.requestJson(), request.timeoutMs(), request.environmentCode(), request.metadata().stream()
                .map(item -> new GrpcDebugService.MetadataInput(item.key(), item.value())).toList(), request.auth() == null ? null
                : new GrpcDebugService.AuthInput(request.auth().type(), request.auth().token(), request.auth().username(), request.auth().password(),
                request.auth().key(), request.auth().value()), request.descriptorId())));
    }

    @PostMapping("/grpc/server-stream")
    public ApiResponse<GrpcDebugService.StreamSessionSummary> startGrpcServerStream(Authentication authentication, @PathVariable String projectId,
                                                                                      @Valid @RequestBody GrpcUnaryRequest request) {
        return ApiResponse.of(grpc.startServerStream(principal(authentication), projectId, new GrpcDebugService.UnaryRequest(request.endpoint(), request.tls(),
                request.service(), request.method(), request.requestJson(), request.timeoutMs(), request.environmentCode(), request.metadata().stream()
                .map(item -> new GrpcDebugService.MetadataInput(item.key(), item.value())).toList(), request.auth() == null ? null
                : new GrpcDebugService.AuthInput(request.auth().type(), request.auth().token(), request.auth().username(), request.auth().password(),
                request.auth().key(), request.auth().value()), request.descriptorId())));
    }

    @GetMapping("/grpc/streams")
    public ApiResponse<List<GrpcDebugService.StreamSessionSummary>> grpcServerStreams(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(grpc.serverStreams(principal(authentication), projectId));
    }

    @GetMapping("/grpc/streams/{sessionId}")
    public ApiResponse<GrpcDebugService.StreamSessionSummary> grpcServerStream(Authentication authentication, @PathVariable String projectId,
                                                                                @PathVariable String sessionId) {
        return ApiResponse.of(grpc.serverStream(principal(authentication), projectId, sessionId));
    }

    @GetMapping("/grpc/streams/{sessionId}/messages")
    public ApiResponse<List<GrpcDebugService.StreamMessageSummary>> grpcServerStreamMessages(Authentication authentication, @PathVariable String projectId,
                                                                                               @PathVariable String sessionId) {
        return ApiResponse.of(grpc.serverStreamMessages(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/streams/{sessionId}/cancel")
    public ApiResponse<GrpcDebugService.StreamSessionSummary> cancelGrpcServerStream(Authentication authentication, @PathVariable String projectId,
                                                                                       @PathVariable String sessionId) {
        return ApiResponse.of(grpc.cancelServerStream(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/client-streams")
    public ApiResponse<GrpcDebugService.ClientStreamSessionSummary> startGrpcClientStream(Authentication authentication, @PathVariable String projectId,
                                                                                            @Valid @RequestBody GrpcClientStreamStartRequest request) {
        return ApiResponse.of(grpc.startClientStream(principal(authentication), projectId,
                new GrpcDebugService.ClientStreamStartInput(request.endpoint(), request.tls(), request.service(), request.method(), request.timeoutMs(),
                        request.environmentCode(), request.metadata().stream().map(item -> new GrpcDebugService.MetadataInput(item.key(), item.value())).toList(),
                        request.auth() == null ? null : new GrpcDebugService.AuthInput(request.auth().type(), request.auth().token(), request.auth().username(),
                                request.auth().password(), request.auth().key(), request.auth().value()), request.descriptorId())));
    }

    @GetMapping("/grpc/client-streams")
    public ApiResponse<List<GrpcDebugService.ClientStreamSessionSummary>> grpcClientStreams(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(grpc.clientStreams(principal(authentication), projectId));
    }

    @GetMapping("/grpc/client-streams/{sessionId}")
    public ApiResponse<GrpcDebugService.ClientStreamSessionSummary> grpcClientStream(Authentication authentication, @PathVariable String projectId,
                                                                                      @PathVariable String sessionId) {
        return ApiResponse.of(grpc.clientStream(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/client-streams/{sessionId}/messages")
    public ApiResponse<GrpcDebugService.ClientStreamSessionSummary> sendGrpcClientStreamMessage(Authentication authentication, @PathVariable String projectId,
                                                                                                  @PathVariable String sessionId,
                                                                                                  @Valid @RequestBody GrpcClientStreamMessageRequest request) {
        return ApiResponse.of(grpc.sendClientStreamMessage(principal(authentication), projectId, sessionId,
                new GrpcDebugService.ClientStreamMessageInput(request.requestJson())));
    }

    @PostMapping("/grpc/client-streams/{sessionId}/complete")
    public ApiResponse<GrpcDebugService.ClientStreamSessionSummary> completeGrpcClientStream(Authentication authentication, @PathVariable String projectId,
                                                                                              @PathVariable String sessionId) {
        return ApiResponse.of(grpc.completeClientStream(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/client-streams/{sessionId}/cancel")
    public ApiResponse<GrpcDebugService.ClientStreamSessionSummary> cancelGrpcClientStream(Authentication authentication, @PathVariable String projectId,
                                                                                            @PathVariable String sessionId) {
        return ApiResponse.of(grpc.cancelClientStream(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/bidi-streams")
    public ApiResponse<GrpcDebugService.BidiStreamSessionSummary> startGrpcBidiStream(Authentication authentication, @PathVariable String projectId,
                                                                                        @Valid @RequestBody GrpcBidiStreamStartRequest request) {
        return ApiResponse.of(grpc.startBidiStream(principal(authentication), projectId,
                new GrpcDebugService.BidiStreamStartInput(request.endpoint(), request.tls(), request.service(), request.method(), request.timeoutMs(),
                        request.environmentCode(), request.metadata().stream().map(item -> new GrpcDebugService.MetadataInput(item.key(), item.value())).toList(),
                        request.auth() == null ? null : new GrpcDebugService.AuthInput(request.auth().type(), request.auth().token(), request.auth().username(),
                                request.auth().password(), request.auth().key(), request.auth().value()), request.descriptorId())));
    }

    @GetMapping("/grpc/bidi-streams")
    public ApiResponse<List<GrpcDebugService.BidiStreamSessionSummary>> grpcBidiStreams(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(grpc.bidiStreams(principal(authentication), projectId));
    }

    @GetMapping("/grpc/bidi-streams/{sessionId}")
    public ApiResponse<GrpcDebugService.BidiStreamSessionSummary> grpcBidiStream(Authentication authentication, @PathVariable String projectId,
                                                                                  @PathVariable String sessionId) {
        return ApiResponse.of(grpc.bidiStream(principal(authentication), projectId, sessionId));
    }

    @GetMapping("/grpc/bidi-streams/{sessionId}/messages")
    public ApiResponse<List<GrpcDebugService.StreamMessageSummary>> grpcBidiStreamMessages(Authentication authentication, @PathVariable String projectId,
                                                                                             @PathVariable String sessionId) {
        return ApiResponse.of(grpc.bidiStreamMessages(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/bidi-streams/{sessionId}/messages")
    public ApiResponse<GrpcDebugService.BidiStreamSessionSummary> sendGrpcBidiStreamMessage(Authentication authentication, @PathVariable String projectId,
                                                                                              @PathVariable String sessionId,
                                                                                              @Valid @RequestBody GrpcBidiStreamMessageRequest request) {
        return ApiResponse.of(grpc.sendBidiStreamMessage(principal(authentication), projectId, sessionId,
                new GrpcDebugService.BidiStreamMessageInput(request.requestJson())));
    }

    @PostMapping("/grpc/bidi-streams/{sessionId}/complete")
    public ApiResponse<GrpcDebugService.BidiStreamSessionSummary> completeGrpcBidiStream(Authentication authentication, @PathVariable String projectId,
                                                                                          @PathVariable String sessionId) {
        return ApiResponse.of(grpc.completeBidiStream(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/bidi-streams/{sessionId}/cancel")
    public ApiResponse<GrpcDebugService.BidiStreamSessionSummary> cancelGrpcBidiStream(Authentication authentication, @PathVariable String projectId,
                                                                                        @PathVariable String sessionId) {
        return ApiResponse.of(grpc.cancelBidiStream(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/grpc/discovery")
    public ApiResponse<GrpcDebugService.DiscoveryResult> grpcDiscovery(Authentication authentication, @PathVariable String projectId,
                                                                        @Valid @RequestBody GrpcDiscoveryRequest request) {
        return ApiResponse.of(grpc.discover(principal(authentication), projectId, new GrpcDebugService.DiscoveryInput(request.endpoint(), request.tls(),
                request.timeoutMs(), request.environmentCode(), request.metadata().stream().map(item -> new GrpcDebugService.MetadataInput(item.key(), item.value()))
                .toList(), request.auth() == null ? null : new GrpcDebugService.AuthInput(request.auth().type(), request.auth().token(),
                request.auth().username(), request.auth().password(), request.auth().key(), request.auth().value()), request.service())));
    }

    @GetMapping("/websocket/sessions")
    public ApiResponse<List<WebSocketDebugService.SessionSummary>> webSocketSessions(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(webSockets.sessions(principal(authentication), projectId));
    }

    @PostMapping("/websocket/connect")
    public ApiResponse<WebSocketDebugService.SessionSummary> connectWebSocket(Authentication authentication, @PathVariable String projectId,
                                                                               @Valid @RequestBody WebSocketConnectRequest request) {
        return ApiResponse.of(webSockets.connect(principal(authentication), projectId, new WebSocketDebugService.ConnectInput(request.url(), request.headers().stream()
                .map(header -> new WebSocketDebugService.HeaderInput(header.name(), header.value())).toList(), request.environmentCode(),
                request.auth() == null ? null : new WebSocketDebugService.AuthInput(request.auth().type(), request.auth().token(), request.auth().username(),
                        request.auth().password(), request.auth().key(), request.auth().value(), request.auth().placement()), request.subprotocols(),
                request.subscriptions(), request.reconnect() == null ? null : new WebSocketDebugService.ReconnectInput(request.reconnect().enabled(),
                        request.reconnect().maxAttempts(), request.reconnect().delayMs()))));
    }

    @GetMapping("/websocket/sessions/{sessionId}")
    public ApiResponse<WebSocketDebugService.SessionSummary> webSocketSession(Authentication authentication, @PathVariable String projectId,
                                                                               @PathVariable String sessionId) {
        return ApiResponse.of(webSockets.session(principal(authentication), projectId, sessionId));
    }

    @GetMapping("/websocket/sessions/{sessionId}/messages")
    public ApiResponse<List<WebSocketDebugService.MessageSummary>> webSocketMessages(Authentication authentication, @PathVariable String projectId,
                                                                                       @PathVariable String sessionId) {
        return ApiResponse.of(webSockets.messages(principal(authentication), projectId, sessionId));
    }

    @PostMapping("/websocket/sessions/{sessionId}/messages")
    public ApiResponse<WebSocketDebugService.SessionSummary> sendWebSocketMessage(Authentication authentication, @PathVariable String projectId,
                                                                                    @PathVariable String sessionId, @Valid @RequestBody WebSocketMessageRequest request) {
        return ApiResponse.of(webSockets.send(principal(authentication), projectId, sessionId, new WebSocketDebugService.SendInput(request.message())));
    }

    @PostMapping("/websocket/sessions/{sessionId}/close")
    public ApiResponse<WebSocketDebugService.SessionSummary> closeWebSocket(Authentication authentication, @PathVariable String projectId,
                                                                              @PathVariable String sessionId) {
        return ApiResponse.of(webSockets.close(principal(authentication), projectId, sessionId));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    private ApiDebugService.DebugRequest debugRequest(ExecuteRequest request) {
        return new ApiDebugService.DebugRequest(request.method(), request.url(), request.headers().stream()
                .map(header -> new ApiDebugService.HeaderInput(header.name(), header.value())).toList(), request.body(), request.timeoutMs(),
                request.environmentCode(), request.auth() == null ? null : new ApiDebugService.AuthInput(request.auth().type(), request.auth().token(),
                        request.auth().username(), request.auth().password(), request.auth().key(), request.auth().value(), request.auth().placement()),
                request.protocol() == null ? null : new ApiDebugService.ProtocolInput(request.protocol().type(), request.protocol().query(), request.protocol().variables(),
                        request.protocol().operationName(), request.protocol().envelope(), request.protocol().soapAction()), request.name());
    }

    public record HeaderRequest(@NotBlank @Size(max = 200) String name, @NotNull @Size(max = 16_000) String value) {
    }
    public record AuthRequest(@Size(max = 20) String type, @Size(max = 20_000) String token, @Size(max = 2_000) String username,
                              @Size(max = 20_000) String password, @Size(max = 200) String key, @Size(max = 20_000) String value,
                              @Size(max = 20) String placement) {
    }
    public record ProtocolRequest(@Size(max = 20) String type, @Size(max = 100_000) String query, @Size(max = 100_000) String variables,
                                  @Size(max = 2_000) String operationName, @Size(max = 100_000) String envelope,
                                  @Size(max = 2_000) String soapAction) {
    }
    public record ReconnectRequest(Boolean enabled, @Min(1) @Max(5) Integer maxAttempts, @Min(100) @Max(10_000) Integer delayMs) {
    }
    public record WebSocketConnectRequest(@NotBlank @Size(max = 2_000) String url, @Size(max = 48) List<@Valid HeaderRequest> headers,
                                          @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String environmentCode, @Valid AuthRequest auth,
                                          @Size(max = 8) List<@NotBlank @Size(max = 128) String> subprotocols,
                                          @Size(max = 12) List<@NotBlank @Size(max = 32_000) String> subscriptions,
                                          @Valid ReconnectRequest reconnect) {
        public WebSocketConnectRequest {
            headers = headers == null ? List.of() : List.copyOf(headers);
            subprotocols = subprotocols == null ? List.of() : List.copyOf(subprotocols);
            subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        }
    }
    public record WebSocketMessageRequest(@NotNull @Size(max = 32_000) String message) {
    }
    public record GrpcUnaryRequest(@NotBlank @Size(max = 512) String endpoint, Boolean tls,
                                   @NotBlank @Size(max = 512) String service, @NotBlank @Size(max = 256) String method,
                                   @NotBlank @Size(max = 100_000) String requestJson, @Min(500) @Max(10_000) Integer timeoutMs,
                                   @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String environmentCode,
                                   @Size(max = 32) List<@Valid GrpcMetadataRequest> metadata, @Valid GrpcAuthRequest auth,
                                   @Size(max = 26) String descriptorId) {
        public GrpcUnaryRequest {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }
    public record GrpcClientStreamStartRequest(@NotBlank @Size(max = 512) String endpoint, Boolean tls,
                                               @NotBlank @Size(max = 512) String service, @NotBlank @Size(max = 256) String method,
                                               @Min(500) @Max(60_000) Integer timeoutMs,
                                               @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String environmentCode,
                                               @Size(max = 32) List<@Valid GrpcMetadataRequest> metadata, @Valid GrpcAuthRequest auth,
                                               @Size(max = 26) String descriptorId) {
        public GrpcClientStreamStartRequest {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }
    public record GrpcClientStreamMessageRequest(@NotBlank @Size(max = 100_000) String requestJson) {
    }
    public record GrpcBidiStreamStartRequest(@NotBlank @Size(max = 512) String endpoint, Boolean tls,
                                             @NotBlank @Size(max = 512) String service, @NotBlank @Size(max = 256) String method,
                                             @Min(500) @Max(60_000) Integer timeoutMs,
                                             @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String environmentCode,
                                             @Size(max = 32) List<@Valid GrpcMetadataRequest> metadata, @Valid GrpcAuthRequest auth,
                                             @Size(max = 26) String descriptorId) {
        public GrpcBidiStreamStartRequest {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }
    public record GrpcBidiStreamMessageRequest(@NotBlank @Size(max = 100_000) String requestJson) {
    }
    public record GrpcDiscoveryRequest(@NotBlank @Size(max = 512) String endpoint, Boolean tls, @Size(max = 512) String service,
                                       @Min(500) @Max(10_000) Integer timeoutMs, @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String environmentCode,
                                       @Size(max = 32) List<@Valid GrpcMetadataRequest> metadata, @Valid GrpcAuthRequest auth) {
        public GrpcDiscoveryRequest {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }
    public record GrpcMetadataRequest(@NotBlank @Size(max = 128) String key, @NotNull @Size(max = 8_192) String value) {
    }
    public record GrpcAuthRequest(@Size(max = 20) String type, @Size(max = 20_000) String token, @Size(max = 2_000) String username,
                                  @Size(max = 20_000) String password, @Size(max = 128) String key, @Size(max = 20_000) String value) {
    }
    public record ExecuteRequest(@NotBlank @Size(max = 12) String method, @NotBlank @Size(max = 2_000) String url,
                                 @Size(max = 48) List<@Valid HeaderRequest> headers, @Size(max = 100_000) String body,
                                 @Min(500) @Max(10_000) Integer timeoutMs,
                                 @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String environmentCode, @Valid AuthRequest auth,
                                 @Valid ProtocolRequest protocol, @Size(max = 128) String name) {
        public ExecuteRequest {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }
    public record LocalExecutionRequest(@NotNull @Valid ExecuteRequest request,
                                        @NotNull @Valid LocalExecutionResultRequest result) {
    }
    public record LocalExecutionResultRequest(boolean success, @Min(100) @Max(599) Integer statusCode,
                                              @Min(0) @Max(600_000) int durationMs,
                                              @Min(0) @Max(10_000_000) int responseSize,
                                              @Size(max = 1_000) String errorMessage) {
    }
}
