package io.testhub.platform.apidebug;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectEnvironment;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import io.testhub.platform.parameter.ProjectParameterRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ApiDebugFixesTests {
    private final AccessService access = mock(AccessService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApiDebugHistoryRepository history = mock(ApiDebugHistoryRepository.class);
    private final ProjectParameterRepository parameters = mock(ProjectParameterRepository.class);
    private final ProjectEnvironmentRepository environments = mock(ProjectEnvironmentRepository.class);
    private final SecretCipher cipher = mock(SecretCipher.class);
    private final AuthenticatedPrincipal principal = mock(AuthenticatedPrincipal.class);
    private final GrpcDebugTransport transport = mock(GrpcDebugTransport.class);
    private final WebSocketDebugClient wsTransport = mock(WebSocketDebugClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final GrpcDebugService grpc;
    private final WebSocketDebugService ws;

    ApiDebugFixesTests() {
        when(access.requireProjectParameterWrite(eq(principal), eq("p1"), anySet()))
                .thenReturn(new ProjectSummary("p1", "o1", "QA", "QA", "ACTIVE", "", null, null, null));
        when(principal.userId()).thenReturn("qa-user");
        when(cipher.encrypt(anyString())).thenReturn("sealed-test-draft");
        grpc = new GrpcDebugService(transport, history, access, audit, parameters, environments, cipher, json,
                mock(GrpcDescriptorService.class), mock(DesktopApiDebugTargetPolicy.class));
        ws = new WebSocketDebugService(wsTransport, history, access, audit, parameters, environments, cipher, json,
                mock(DesktopApiDebugTargetPolicy.class));
    }

    @AfterEach void close() { grpc.stopServerStreams(); ws.stopReconnects(); }

    static Stream<Arguments> queryTargets() {
        return Stream.of("HTTP", "WS").flatMap(protocol -> Stream.of("", "?", "?plain=a%20b&x=%25", "?plain=a%20b#fragment")
                .map(query -> Arguments.of(protocol, query)));
    }

    @ParameterizedTest @MethodSource("queryTargets")
    void queryApiKeyPreservesExistingEscapesAndDecodesOnce(String protocol, String query) throws Exception {
        String value = "中文 空&%", key = "X QA&Key";
        URI sent;
        if (protocol.equals("HTTP")) {
            ApiDebugTransport http = mock(ApiDebugTransport.class);
            ApiDebugTargetPolicy policy = mock(ApiDebugTargetPolicy.class);
            when(policy.normalizeAndAssertAllowed(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
            when(http.execute(anyString(), any(), anyList(), anyString(), anyInt()))
                    .thenReturn(new ApiDebugTransport.TransportResponse(200, "OK", List.of(), "{}", false));
            ApiDebugService service = new ApiDebugService(history, http, access, audit, parameters, cipher, json, environments, policy);
            assertTrue(service.execute(principal, "p1", new ApiDebugService.DebugRequest("GET", "https://[2001:4860:4860::8888]/echo%20path" + query,
                    List.of(), "", 1000, null, new ApiDebugService.AuthInput("API_KEY", null, null, null, key, value, "QUERY"), null, "QA")).success());
            var captor = org.mockito.ArgumentCaptor.forClass(URI.class);
            verify(http).execute(eq("GET"), captor.capture(), anyList(), eq(""), eq(1000));
            sent = captor.getValue();
        } else {
            when(wsTransport.connect(any(), anyList(), anyList(), any())).thenReturn(mock(WebSocketDebugClient.Connection.class));
            ws.connect(principal, "p1", new WebSocketDebugService.ConnectInput("wss://8.8.8.8/echo%20path" + query, List.of(), null,
                    new WebSocketDebugService.AuthInput("API_KEY", null, null, null, key, value, "QUERY"), List.of(), List.of(), null));
            var captor = org.mockito.ArgumentCaptor.forClass(URI.class);
            verify(wsTransport).connect(captor.capture(), anyList(), anyList(), any());
            sent = captor.getValue();
        }
        assertEquals("/echo%20path", sent.getRawPath());
        assertNull(sent.getRawFragment());
        if (query.startsWith("?plain=")) assertTrue(sent.getRawQuery().startsWith(query.substring(1).split("#")[0] + "&"));
        String pair = sent.getRawQuery().substring(sent.getRawQuery().lastIndexOf('&') + 1);
        assertEquals(key, URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8));
        assertEquals(value, URLDecoder.decode(pair.split("=", 2)[1], StandardCharsets.UTF_8));
    }

    static Stream<Arguments> invalidEnvironments() {
        return Stream.of("WS", "SERVICES", "METHODS", "UNARY", "SERVER", "CLIENT", "BIDI")
                .flatMap(mode -> Stream.of(true, false).map(missing -> Arguments.of(mode, missing)));
    }

    @ParameterizedTest @MethodSource("invalidEnvironments")
    void rejectsMissingOrDisabledEnvironmentBeforeSecretsTransportHistoryAndAudit(String mode, boolean missing) {
        ProjectEnvironment disabled = new ProjectEnvironment("qa", "o1", "p1", "QA", "qa", "", "", false, false, 0, 0, 1, "qa-user", "qa-user", null, null);
        when(environments.findByCode("p1", "qa")).thenReturn(missing ? Optional.empty() : Optional.of(disabled));
        var unary = new GrpcDebugService.UnaryRequest("8.8.8.8:443", true, "qa.Echo", "Unary", "{}", 1000, "qa", List.of(), null, null);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> {
            switch (mode) {
                case "WS" -> ws.connect(principal, "p1", new WebSocketDebugService.ConnectInput("wss://8.8.8.8/echo", List.of(), "qa", null, List.of(), List.of(), null));
                case "SERVICES", "METHODS" -> grpc.discover(principal, "p1", new GrpcDebugService.DiscoveryInput("8.8.8.8:443", true, 1000, "qa", List.of(), null, mode.equals("SERVICES") ? null : "qa.Echo"));
                case "UNARY" -> grpc.unary(principal, "p1", unary);
                case "SERVER" -> grpc.startServerStream(principal, "p1", unary);
                case "CLIENT" -> grpc.startClientStream(principal, "p1", new GrpcDebugService.ClientStreamStartInput("8.8.8.8:443", true, "qa.Echo", "Collect", 1000, "qa", List.of(), null, null));
                case "BIDI" -> grpc.startBidiStream(principal, "p1", new GrpcDebugService.BidiStreamStartInput("8.8.8.8:443", true, "qa.Echo", "Bidi", 1000, "qa", List.of(), null, null));
                default -> fail("Unknown mode");
            }
        });
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(missing ? "请选择当前项目中已存在的运行环境" : "所选运行环境已停用", exception.getReason());
        verifyNoInteractions(parameters, cipher, transport, wsTransport, history, audit);
    }

    @ParameterizedTest @CsvSource({"CLIENT,COUNT", "CLIENT,BYTES", "BIDI,COUNT", "BIDI,BYTES"})
    void sendQuotaCancelsExactlyOnceAndCannotBeOverwrittenByLateCallbacks(String mode, String limit) throws Exception {
        boolean client = mode.equals("CLIENT"), count = limit.equals("COUNT");
        GrpcDebugTransport.StreamSubscription subscription = client ? mock(GrpcDebugTransport.ClientStreamSubscription.class) : mock(GrpcDebugTransport.BidiStreamSubscription.class);
        var clientListener = org.mockito.ArgumentCaptor.forClass(GrpcDebugTransport.ClientStreamListener.class);
        var bidiListener = org.mockito.ArgumentCaptor.forClass(GrpcDebugTransport.BidiStreamListener.class);
        String sessionId;
        if (client) {
            when(transport.clientStreaming(any(), clientListener.capture())).thenReturn((GrpcDebugTransport.ClientStreamSubscription) subscription);
            sessionId = grpc.startClientStream(principal, "p1", new GrpcDebugService.ClientStreamStartInput("8.8.8.8:443", true, "qa.Echo", "Collect", 60000, null, List.of(), null, null)).id();
        } else {
            when(transport.bidiStreaming(any(), bidiListener.capture())).thenReturn((GrpcDebugTransport.BidiStreamSubscription) subscription);
            sessionId = grpc.startBidiStream(principal, "p1", new GrpcDebugService.BidiStreamStartInput("8.8.8.8:443", true, "qa.Echo", "Bidi", 60000, null, List.of(), null, null)).id();
        }
        String payload = count ? "{}" : "{\"text\":\"" + "a".repeat(65000) + "\"}";
        int accepted = count ? 100 : 8;
        for (int i = 0; i < accepted; i++) send(client, sessionId, payload);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> send(client, sessionId, payload)).getStatusCode());
        verify(subscription).cancel(anyString());
        if (client) verify((GrpcDebugTransport.ClientStreamSubscription) subscription, times(accepted)).send(payload);
        else verify((GrpcDebugTransport.BidiStreamSubscription) subscription, times(accepted)).send(payload);
        if (client) { clientListener.getValue().onCompleted(); clientListener.getValue().onError("CANCELLED"); }
        else { bidiListener.getValue().onCompleted(); bidiListener.getValue().onError("CANCELLED"); }
        if (client) {
            var summary = grpc.clientStream(principal, "p1", sessionId);
            assertEquals("LIMIT_REACHED", summary.status()); assertEquals(accepted, summary.sentMessageCount()); assertNotNull(summary.closedAt());
        } else {
            var summary = grpc.bidiStream(principal, "p1", sessionId);
            assertEquals("LIMIT_REACHED", summary.status()); assertEquals(accepted, summary.sentMessageCount()); assertNotNull(summary.closedAt());
        }
        assertThrows(ResponseStatusException.class, () -> send(client, sessionId, "{}"));
        verify(subscription, times(1)).cancel(anyString());
        verify(audit, times(1)).record(eq(principal), eq("o1"), eq("p1"), eq(client ? "GRPC_CLIENT_STREAM_FINISHED" : "GRPC_BIDI_STREAM_FINISHED"), anyString(), eq(sessionId), anyMap());
    }

    private void send(boolean client, String sessionId, String payload) {
        if (client) grpc.sendClientStreamMessage(principal, "p1", sessionId, new GrpcDebugService.ClientStreamMessageInput(payload));
        else grpc.sendBidiStreamMessage(principal, "p1", sessionId, new GrpcDebugService.BidiStreamMessageInput(payload));
    }
}
