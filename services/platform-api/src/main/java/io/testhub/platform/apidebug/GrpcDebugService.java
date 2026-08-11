package io.testhub.platform.apidebug;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.grpc.Status;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectParameterRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Project-scoped gRPC Unary debugger. Raw protobuf descriptors and request/response
 * JSON stay in process memory and are never added to debug history or audit details.
 */
@Service
public class GrpcDebugService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Pattern SERVICE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern METHOD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern METADATA_KEY = Pattern.compile("[0-9A-Za-z][0-9A-Za-z._-]{0,127}");
    private static final int MAX_RESPONSE_LENGTH = 512 * 1024;
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_METADATA_VALUE_LENGTH = 8_192;
    private static final int MAX_METADATA_LENGTH = 24 * 1024;
    private static final int MAX_DISCOVERY_ITEMS = 500;
    private static final int MAX_STREAMS_PER_USER_PROJECT = 5;
    private static final int MAX_STREAM_MESSAGES = 100;
    private static final int MAX_STREAM_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_STREAM_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_CLIENT_STREAMS_PER_USER_PROJECT = 5;
    private static final int MAX_CLIENT_STREAM_MESSAGES = 100;
    private static final int MAX_CLIENT_STREAM_REQUEST_BYTES = 512 * 1024;
    private static final int MAX_CLIENT_STREAM_MESSAGE_BYTES = 64 * 1024;
    private static final int MAX_BIDI_STREAMS_PER_USER_PROJECT = 5;
    private static final int MAX_BIDI_STREAM_MESSAGES = 100;
    private static final int MAX_BIDI_STREAM_REQUEST_BYTES = 512 * 1024;
    private static final int MAX_BIDI_STREAM_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_BIDI_STREAM_MESSAGE_BYTES = 64 * 1024;
    private final Map<String, StreamSessionState> streams = new ConcurrentHashMap<>();
    private final Map<String, ClientStreamSessionState> clientStreams = new ConcurrentHashMap<>();
    private final Map<String, BidiStreamSessionState> bidiStreams = new ConcurrentHashMap<>();
    private final GrpcDebugTransport transport;
    private final ApiDebugHistoryRepository history;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectParameterRepository parameters;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final GrpcDescriptorService descriptors;

    public GrpcDebugService(GrpcDebugTransport transport, ApiDebugHistoryRepository history, AccessService access, AuditService audit,
                            ProjectParameterRepository parameters, SecretCipher cipher, ObjectMapper json, GrpcDescriptorService descriptors) {
        this.transport = transport;
        this.history = history;
        this.access = access;
        this.audit = audit;
        this.parameters = parameters;
        this.cipher = cipher;
        this.json = json;
        this.descriptors = descriptors;
    }

    public GrpcResult unary(AuthenticatedPrincipal principal, String projectId, UnaryRequest input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolve(projectId, input);
        long started = System.nanoTime();
        try {
            GrpcDebugTransport.Response response = transport.unary(new GrpcDebugTransport.Request(request.target().host(), request.target().port(),
                    request.tls(), request.service(), request.method(), request.requestJson(), request.timeoutMs(), request.metadata(), request.descriptorProtos()));
            if (response.responseJson().length() > MAX_RESPONSE_LENGTH) return failed(project, principal, request, input, started, "RESOURCE_EXHAUSTED");
            int durationMs = elapsedMs(started);
            ApiDebugHistory item = saved(project, principal, request, input, Status.Code.OK.value(), durationMs, response.responseJson().length(), "COMPLETED", null);
            history.insert(item);
            audit.record(principal, project.organizationId(), projectId, "GRPC_DEBUG_EXECUTED", "API_DEBUG_HISTORY", item.id(),
                    auditDetails(request, "OK"));
            return new GrpcResult(item.id(), true, "OK", durationMs, response.responseJson(), false, null);
        } catch (GrpcDebugTransport.TransportException exception) {
            return failed(project, principal, request, input, started, exception.status());
        }
    }

    /**
     * Reflection discovery returns only service and method names for the current response.
     * Raw descriptors, metadata values and authentication material are not persisted.
     */
    public DiscoveryResult discover(AuthenticatedPrincipal principal, String projectId, DiscoveryInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedDiscovery request = resolveDiscovery(projectId, input);
        GrpcDebugTransport.DiscoveryRequest transportRequest = new GrpcDebugTransport.DiscoveryRequest(request.target().host(), request.target().port(),
                request.tls(), request.timeoutMs(), request.metadata());
        try {
            if (input.service() == null || input.service().isBlank()) {
                List<String> services = transport.listServices(transportRequest);
                boolean truncated = services.size() > MAX_DISCOVERY_ITEMS;
                List<String> result = services.stream().limit(MAX_DISCOVERY_ITEMS).toList();
                auditDiscovery(principal, project, projectId, request, "SERVICES", result.size(), "OK");
                return new DiscoveryResult(result, List.of(), truncated);
            }
            String service = validatedService(request.variables().resolve(input.service()));
            List<GrpcDebugTransport.MethodSummary> methods = transport.listMethods(transportRequest, service);
            boolean truncated = methods.size() > MAX_DISCOVERY_ITEMS;
            List<GrpcDebugTransport.MethodSummary> result = methods.stream().limit(MAX_DISCOVERY_ITEMS).toList();
            auditDiscovery(principal, project, projectId, request, "METHODS", result.size(), "OK");
            return new DiscoveryResult(List.of(), result, truncated);
        } catch (GrpcDebugTransport.TransportException exception) {
            String scope = input.service() == null || input.service().isBlank() ? "SERVICES" : "METHODS";
            auditDiscovery(principal, project, projectId, request, scope, 0, exception.status());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeMessage(exception.status()));
        }
    }

    /**
     * Starts a short-lived server-streaming call. Traffic is intentionally kept
     * in memory, bounded by both message count and response bytes, and never
     * enters API debug history.
     */
    public StreamSessionSummary startServerStream(AuthenticatedPrincipal principal, String projectId, UnaryRequest input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolve(projectId, input);
        cleanupExpiredStreams();
        long active = streams.values().stream().filter(session -> session.belongsTo(principal, projectId) && session.active()).count();
        if (active >= MAX_STREAMS_PER_USER_PROJECT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目最多同时运行 5 个 gRPC 服务端流，请先取消现有调用");
        }
        StreamSessionState session = new StreamSessionState(UlidCreator.getUlid().toString(), principal.userId(), project.organizationId(), project.id(), request);
        streams.put(session.id(), session);
        try {
            GrpcDebugTransport.StreamSubscription subscription = transport.serverStreaming(new GrpcDebugTransport.Request(request.target().host(), request.target().port(),
                    request.tls(), request.service(), request.method(), request.requestJson(), request.timeoutMs(), request.metadata(), request.descriptorProtos()),
                    new GrpcDebugTransport.StreamListener() {
                        @Override
                        public void onMessage(String responseJson) {
                            GrpcDebugTransport.StreamSubscription cancel = session.received(responseJson);
                            if (cancel != null) {
                                cancel.cancel("TestHub gRPC stream response limit reached");
                                auditStreamFinished(principal, project, session);
                            }
                        }

                        @Override
                        public void onCompleted() {
                            if (session.completed()) auditStreamFinished(principal, project, session);
                        }

                        @Override
                        public void onError(String status) {
                            if (session.failed(safeMessage(status))) auditStreamFinished(principal, project, session);
                        }
                    });
            session.attach(subscription);
            audit.record(principal, project.organizationId(), projectId, "GRPC_SERVER_STREAM_STARTED", "GRPC_STREAM_SESSION", session.id(),
                    streamAuditDetails(session));
            return session.summary();
        } catch (GrpcDebugTransport.TransportException exception) {
            streams.remove(session.id());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeMessage(exception.status()));
        }
    }

    public List<StreamSessionSummary> serverStreams(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        cleanupExpiredStreams();
        return streams.values().stream().filter(session -> session.belongsTo(principal, projectId))
                .sorted(Comparator.comparing(StreamSessionState::createdAt).reversed()).map(StreamSessionState::summary).toList();
    }

    public StreamSessionSummary serverStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedStream(principal, projectId, sessionId).summary();
    }

    public List<StreamMessageSummary> serverStreamMessages(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedStream(principal, projectId, sessionId).messages();
    }

    public StreamSessionSummary cancelServerStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        StreamSessionState session = ownedStream(principal, projectId, sessionId);
        if (!session.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "gRPC 服务端流已结束，无法再次取消");
        GrpcDebugTransport.StreamSubscription subscription = session.cancelled("已由 TestHub 取消");
        if (subscription != null) subscription.cancel("Cancelled by TestHub user");
        audit.record(principal, project.organizationId(), projectId, "GRPC_SERVER_STREAM_CANCELLED", "GRPC_STREAM_SESSION", session.id(),
                streamAuditDetails(session));
        return session.summary();
    }

    /**
     * Starts a client-streaming call without sending a request body. Individual
     * JSON messages are validated and transmitted through a separate endpoint.
     */
    public ClientStreamSessionSummary startClientStream(AuthenticatedPrincipal principal, String projectId, ClientStreamStartInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolveClientStreamStart(projectId, input);
        cleanupExpiredStreams();
        long active = clientStreams.values().stream().filter(session -> session.belongsTo(principal, projectId) && session.active()).count();
        if (active >= MAX_CLIENT_STREAMS_PER_USER_PROJECT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目最多同时运行 5 个 gRPC 客户端流，请先完成或取消现有调用");
        }
        ClientStreamSessionState session = new ClientStreamSessionState(UlidCreator.getUlid().toString(), principal.userId(), project.organizationId(), project.id(), request);
        clientStreams.put(session.id(), session);
        try {
            GrpcDebugTransport.ClientStreamSubscription subscription = transport.clientStreaming(new GrpcDebugTransport.Request(request.target().host(), request.target().port(),
                    request.tls(), request.service(), request.method(), "{}", request.timeoutMs(), request.metadata(), request.descriptorProtos()),
                    new GrpcDebugTransport.ClientStreamListener() {
                        @Override
                        public void onResponse(String responseJson) {
                            GrpcDebugTransport.ClientStreamSubscription cancel = session.response(responseJson);
                            if (cancel != null) {
                                cancel.cancel("TestHub client stream response limit reached");
                                auditClientStreamFinished(principal, project, session);
                            }
                        }

                        @Override
                        public void onCompleted() {
                            if (session.completed()) auditClientStreamFinished(principal, project, session);
                        }

                        @Override
                        public void onError(String status) {
                            if (session.failed(safeMessage(status))) auditClientStreamFinished(principal, project, session);
                        }
                    });
            session.attach(subscription);
            audit.record(principal, project.organizationId(), projectId, "GRPC_CLIENT_STREAM_STARTED", "GRPC_CLIENT_STREAM_SESSION", session.id(),
                    clientStreamAuditDetails(session));
            return session.summary();
        } catch (GrpcDebugTransport.TransportException exception) {
            clientStreams.remove(session.id());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeMessage(exception.status()));
        }
    }

    public List<ClientStreamSessionSummary> clientStreams(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        cleanupExpiredStreams();
        return clientStreams.values().stream().filter(session -> session.belongsTo(principal, projectId))
                .sorted(Comparator.comparing(ClientStreamSessionState::createdAt).reversed()).map(ClientStreamSessionState::summary).toList();
    }

    public ClientStreamSessionSummary clientStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedClientStream(principal, projectId, sessionId).summary();
    }

    public ClientStreamSessionSummary sendClientStreamMessage(AuthenticatedPrincipal principal, String projectId, String sessionId, ClientStreamMessageInput input) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (input == null || input.requestJson() == null) throw rejected("gRPC 客户端流消息不能为空");
        ClientStreamSessionState session = ownedClientStream(principal, projectId, sessionId);
        String requestJson = variables(projectId, session.environmentCode()).resolve(input.requestJson());
        if (requestJson.length() > 100_000) throw rejected("gRPC 客户端流单条消息不能超过 100000 字符");
        validateJsonObject(requestJson);
        try {
            return session.send(requestJson);
        } catch (GrpcDebugTransport.TransportException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, safeMessage(exception.status()));
        }
    }

    public ClientStreamSessionSummary completeClientStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ClientStreamSessionState session = ownedClientStream(principal, projectId, sessionId);
        if (!session.openForSending()) throw new ResponseStatusException(HttpStatus.CONFLICT, "gRPC 客户端流不处于可完成状态");
        session.complete();
        audit.record(principal, project.organizationId(), projectId, "GRPC_CLIENT_STREAM_HALF_CLOSED", "GRPC_CLIENT_STREAM_SESSION", session.id(),
                clientStreamAuditDetails(session));
        return session.summary();
    }

    public ClientStreamSessionSummary cancelClientStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ClientStreamSessionState session = ownedClientStream(principal, projectId, sessionId);
        if (!session.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "gRPC 客户端流已结束，无法再次取消");
        GrpcDebugTransport.ClientStreamSubscription subscription = session.cancelled("已由 TestHub 取消");
        if (subscription != null) subscription.cancel("Cancelled by TestHub user");
        audit.record(principal, project.organizationId(), projectId, "GRPC_CLIENT_STREAM_CANCELLED", "GRPC_CLIENT_STREAM_SESSION", session.id(),
                clientStreamAuditDetails(session));
        return session.summary();
    }

    /**
     * Starts a bidirectional gRPC call without transmitting a request message.
     * Both directions remain in the caller's short-lived, project-scoped memory
     * session and are independently bounded.
     */
    public BidiStreamSessionSummary startBidiStream(AuthenticatedPrincipal principal, String projectId, BidiStreamStartInput input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolveBidiStreamStart(projectId, input);
        cleanupExpiredStreams();
        long active = bidiStreams.values().stream().filter(session -> session.belongsTo(principal, projectId) && session.active()).count();
        if (active >= MAX_BIDI_STREAMS_PER_USER_PROJECT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目最多同时运行 5 个 gRPC 双向流，请先完成或取消现有调用");
        }
        BidiStreamSessionState session = new BidiStreamSessionState(UlidCreator.getUlid().toString(), principal.userId(), project.organizationId(), project.id(), request);
        bidiStreams.put(session.id(), session);
        try {
            GrpcDebugTransport.BidiStreamSubscription subscription = transport.bidiStreaming(new GrpcDebugTransport.Request(request.target().host(), request.target().port(),
                    request.tls(), request.service(), request.method(), "{}", request.timeoutMs(), request.metadata(), request.descriptorProtos()),
                    new GrpcDebugTransport.BidiStreamListener() {
                        @Override
                        public void onMessage(String responseJson) {
                            GrpcDebugTransport.BidiStreamSubscription cancel = session.received(responseJson);
                            if (cancel != null) {
                                cancel.cancel("TestHub bidirectional stream response limit reached");
                                auditBidiStreamFinished(principal, project, session);
                            }
                        }

                        @Override
                        public void onCompleted() {
                            if (session.completed()) auditBidiStreamFinished(principal, project, session);
                        }

                        @Override
                        public void onError(String status) {
                            if (session.failed(safeMessage(status))) auditBidiStreamFinished(principal, project, session);
                        }
                    });
            session.attach(subscription);
            audit.record(principal, project.organizationId(), projectId, "GRPC_BIDI_STREAM_STARTED", "GRPC_BIDI_STREAM_SESSION", session.id(),
                    bidiStreamAuditDetails(session));
            return session.summary();
        } catch (GrpcDebugTransport.TransportException exception) {
            bidiStreams.remove(session.id());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeMessage(exception.status()));
        }
    }

    public List<BidiStreamSessionSummary> bidiStreams(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        cleanupExpiredStreams();
        return bidiStreams.values().stream().filter(session -> session.belongsTo(principal, projectId))
                .sorted(Comparator.comparing(BidiStreamSessionState::createdAt).reversed()).map(BidiStreamSessionState::summary).toList();
    }

    public BidiStreamSessionSummary bidiStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedBidiStream(principal, projectId, sessionId).summary();
    }

    public List<StreamMessageSummary> bidiStreamMessages(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedBidiStream(principal, projectId, sessionId).messages();
    }

    public BidiStreamSessionSummary sendBidiStreamMessage(AuthenticatedPrincipal principal, String projectId, String sessionId, BidiStreamMessageInput input) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (input == null || input.requestJson() == null) throw rejected("gRPC 双向流消息不能为空");
        BidiStreamSessionState session = ownedBidiStream(principal, projectId, sessionId);
        String requestJson = variables(projectId, session.environmentCode()).resolve(input.requestJson());
        if (requestJson.length() > 100_000) throw rejected("gRPC 双向流单条消息不能超过 100000 字符");
        validateJsonObject(requestJson);
        try {
            return session.send(requestJson);
        } catch (GrpcDebugTransport.TransportException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, safeMessage(exception.status()));
        }
    }

    public BidiStreamSessionSummary completeBidiStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        BidiStreamSessionState session = ownedBidiStream(principal, projectId, sessionId);
        if (!session.openForSending()) throw new ResponseStatusException(HttpStatus.CONFLICT, "gRPC 双向流不处于可完成状态");
        session.complete();
        audit.record(principal, project.organizationId(), projectId, "GRPC_BIDI_STREAM_HALF_CLOSED", "GRPC_BIDI_STREAM_SESSION", session.id(),
                bidiStreamAuditDetails(session));
        return session.summary();
    }

    public BidiStreamSessionSummary cancelBidiStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        BidiStreamSessionState session = ownedBidiStream(principal, projectId, sessionId);
        if (!session.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "gRPC 双向流已结束，无法再次取消");
        GrpcDebugTransport.BidiStreamSubscription subscription = session.cancelled("已由 TestHub 取消");
        if (subscription != null) subscription.cancel("Cancelled by TestHub user");
        audit.record(principal, project.organizationId(), projectId, "GRPC_BIDI_STREAM_CANCELLED", "GRPC_BIDI_STREAM_SESSION", session.id(),
                bidiStreamAuditDetails(session));
        return session.summary();
    }

    @PreDestroy
    void stopServerStreams() {
        streams.values().forEach(StreamSessionState::stopQuietly);
        streams.clear();
        clientStreams.values().forEach(ClientStreamSessionState::stopQuietly);
        clientStreams.clear();
        bidiStreams.values().forEach(BidiStreamSessionState::stopQuietly);
        bidiStreams.clear();
    }

    private StreamSessionState ownedStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        StreamSessionState session = streams.get(sessionId);
        if (session == null || !session.belongsTo(principal, projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "gRPC 流式调用会话不存在");
        }
        return session;
    }

    private void cleanupExpiredStreams() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        streams.entrySet().removeIf(entry -> entry.getValue().canDiscard(cutoff));
        clientStreams.entrySet().removeIf(entry -> entry.getValue().canDiscard(cutoff));
        bidiStreams.entrySet().removeIf(entry -> entry.getValue().canDiscard(cutoff));
    }

    private ClientStreamSessionState ownedClientStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ClientStreamSessionState session = clientStreams.get(sessionId);
        if (session == null || !session.belongsTo(principal, projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "gRPC 客户端流会话不存在");
        }
        return session;
    }

    private BidiStreamSessionState ownedBidiStream(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        BidiStreamSessionState session = bidiStreams.get(sessionId);
        if (session == null || !session.belongsTo(principal, projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "gRPC 双向流会话不存在");
        }
        return session;
    }

    private Map<String, Object> streamAuditDetails(StreamSessionState session) {
        StreamSessionSummary summary = session.summary();
        return Map.of("service", summary.service(), "method", summary.method(), "status", summary.status(), "tls", summary.tls(),
                "environment", summary.environmentCode() == null ? "none" : summary.environmentCode(), "metadataCount", summary.metadataCount(),
                "authType", summary.authType(), "messageCount", summary.messageCount(), "responseBytes", summary.responseBytes());
    }

    private void auditStreamFinished(AuthenticatedPrincipal principal, ProjectSummary project, StreamSessionState session) {
        audit.record(principal, project.organizationId(), project.id(), "GRPC_SERVER_STREAM_FINISHED", "GRPC_STREAM_SESSION", session.id(),
                streamAuditDetails(session));
    }

    private Map<String, Object> clientStreamAuditDetails(ClientStreamSessionState session) {
        ClientStreamSessionSummary summary = session.summary();
        return Map.of("service", summary.service(), "method", summary.method(), "status", summary.status(), "tls", summary.tls(),
                "environment", summary.environmentCode() == null ? "none" : summary.environmentCode(), "metadataCount", summary.metadataCount(),
                "authType", summary.authType(), "sentMessageCount", summary.sentMessageCount(), "sentBytes", summary.sentBytes(),
                "responseBytes", summary.responseBytes());
    }

    private void auditClientStreamFinished(AuthenticatedPrincipal principal, ProjectSummary project, ClientStreamSessionState session) {
        audit.record(principal, project.organizationId(), project.id(), "GRPC_CLIENT_STREAM_FINISHED", "GRPC_CLIENT_STREAM_SESSION", session.id(),
                clientStreamAuditDetails(session));
    }

    private Map<String, Object> bidiStreamAuditDetails(BidiStreamSessionState session) {
        BidiStreamSessionSummary summary = session.summary();
        return Map.ofEntries(Map.entry("service", summary.service()), Map.entry("method", summary.method()), Map.entry("status", summary.status()),
                Map.entry("tls", summary.tls()), Map.entry("environment", summary.environmentCode() == null ? "none" : summary.environmentCode()),
                Map.entry("metadataCount", summary.metadataCount()), Map.entry("authType", summary.authType()),
                Map.entry("sentMessageCount", summary.sentMessageCount()), Map.entry("sentBytes", summary.sentBytes()),
                Map.entry("receivedMessageCount", summary.receivedMessageCount()), Map.entry("responseBytes", summary.responseBytes()));
    }

    private void auditBidiStreamFinished(AuthenticatedPrincipal principal, ProjectSummary project, BidiStreamSessionState session) {
        audit.record(principal, project.organizationId(), project.id(), "GRPC_BIDI_STREAM_FINISHED", "GRPC_BIDI_STREAM_SESSION", session.id(),
                bidiStreamAuditDetails(session));
    }

    private GrpcResult failed(ProjectSummary project, AuthenticatedPrincipal principal, ValidatedRequest request, UnaryRequest input, long started, String status) {
        String safeMessage = safeMessage(status);
        int durationMs = elapsedMs(started);
        ApiDebugHistory item = saved(project, principal, request, input, grpcCode(status), durationMs, 0, "FAILED", safeMessage);
        history.insert(item);
        audit.record(principal, project.organizationId(), project.id(), "GRPC_DEBUG_EXECUTED", "API_DEBUG_HISTORY", item.id(),
                auditDetails(request, status));
        return new GrpcResult(item.id(), false, status, durationMs, "", false, safeMessage);
    }

    private ApiDebugHistory saved(ProjectSummary project, AuthenticatedPrincipal principal, ValidatedRequest request, UnaryRequest input, Integer status,
                                  int durationMs, int responseSize, String result, String error) {
        return new ApiDebugHistory(UlidCreator.getUlid().toString(), project.organizationId(), project.id(), "GRPC", "GRPC",
                request.historyTarget(), status, durationMs, responseSize, result, error, encryptedReplayDraft(input), principal.userId(), null);
    }

    private String encryptedReplayDraft(UnaryRequest input) {
        try {
            return cipher.encrypt(json.writeValueAsString(Map.of("protocol", "GRPC", "request", input)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法加密保存 gRPC 回放草稿", exception);
        }
    }

    private ValidatedRequest resolve(String projectId, UnaryRequest input) {
        if (input == null) throw rejected("gRPC 请求不能为空");
        ValidatedDiscovery discovery = resolveDiscovery(projectId, new DiscoveryInput(input.endpoint(), input.tls(), input.timeoutMs(), input.environmentCode(),
                input.metadata(), input.auth(), null));
        String service = validatedService(discovery.variables().resolve(input.service()));
        String method = validatedMethod(discovery.variables().resolve(input.method()));
        String requestJson = discovery.variables().resolve(input.requestJson());
        if (requestJson.length() > 100_000) throw rejected("gRPC 请求体不能超过 100000 字符");
        validateJsonObject(requestJson);
        List<FileDescriptorProto> descriptorProtos = descriptors.forExecution(projectId, input.descriptorId());
        return new ValidatedRequest(discovery.target(), discovery.tls(), service, method, requestJson, discovery.timeoutMs(), discovery.metadata(), descriptorProtos,
                discovery.authType(), discovery.environmentCode(), redactedTarget(discovery.target(), discovery.tls(), service, method,
                discovery.variables().secretValues()));
    }

    private ValidatedRequest resolveClientStreamStart(String projectId, ClientStreamStartInput input) {
        if (input == null) throw rejected("gRPC 客户端流请求不能为空");
        String environmentCode = normalizeEnvironment(input.environmentCode());
        ResolutionContext variables = variables(projectId, environmentCode);
        Target target = publicTarget(variables.resolve(input.endpoint()));
        List<GrpcDebugTransport.MetadataEntry> metadata = resolveMetadata(input.metadata(), variables);
        AuthApplied auth = applyAuth(metadata, input.auth(), variables);
        int timeoutMs = input.timeoutMs() == null ? 30_000 : input.timeoutMs();
        if (timeoutMs < 500 || timeoutMs > 60_000) throw rejected("客户端流超时时间应在 500 到 60000 毫秒之间");
        boolean tls = Boolean.TRUE.equals(input.tls());
        String service = validatedService(variables.resolve(input.service()));
        String method = validatedMethod(variables.resolve(input.method()));
        List<FileDescriptorProto> descriptorProtos = descriptors.forExecution(projectId, input.descriptorId());
        return new ValidatedRequest(target, tls, service, method, "{}", timeoutMs, auth.metadata(), descriptorProtos, auth.type(), environmentCode,
                redactedTarget(target, tls, service, method, variables.secretValues()));
    }

    private ValidatedRequest resolveBidiStreamStart(String projectId, BidiStreamStartInput input) {
        if (input == null) throw rejected("gRPC 双向流请求不能为空");
        String environmentCode = normalizeEnvironment(input.environmentCode());
        ResolutionContext variables = variables(projectId, environmentCode);
        Target target = publicTarget(variables.resolve(input.endpoint()));
        List<GrpcDebugTransport.MetadataEntry> metadata = resolveMetadata(input.metadata(), variables);
        AuthApplied auth = applyAuth(metadata, input.auth(), variables);
        int timeoutMs = input.timeoutMs() == null ? 30_000 : input.timeoutMs();
        if (timeoutMs < 500 || timeoutMs > 60_000) throw rejected("双向流超时时间应在 500 到 60000 毫秒之间");
        boolean tls = Boolean.TRUE.equals(input.tls());
        String service = validatedService(variables.resolve(input.service()));
        String method = validatedMethod(variables.resolve(input.method()));
        List<FileDescriptorProto> descriptorProtos = descriptors.forExecution(projectId, input.descriptorId());
        return new ValidatedRequest(target, tls, service, method, "{}", timeoutMs, auth.metadata(), descriptorProtos, auth.type(), environmentCode,
                redactedTarget(target, tls, service, method, variables.secretValues()));
    }

    private ValidatedDiscovery resolveDiscovery(String projectId, DiscoveryInput input) {
        if (input == null) throw rejected("gRPC 发现请求不能为空");
        String environmentCode = normalizeEnvironment(input.environmentCode());
        ResolutionContext variables = variables(projectId, environmentCode);
        Target target = publicTarget(variables.resolve(input.endpoint()));
        List<GrpcDebugTransport.MetadataEntry> metadata = resolveMetadata(input.metadata(), variables);
        AuthApplied auth = applyAuth(metadata, input.auth(), variables);
        int timeoutMs = input.timeoutMs() == null ? 10_000 : input.timeoutMs();
        if (timeoutMs < 500 || timeoutMs > 10_000) throw rejected("超时时间应在 500 到 10000 毫秒之间");
        boolean tls = Boolean.TRUE.equals(input.tls());
        return new ValidatedDiscovery(target, tls, timeoutMs, auth.metadata(), auth.type(), environmentCode, variables);
    }

    private String validatedService(String value) {
        if (!SERVICE.matcher(value).matches() || value.length() > 512) throw rejected("gRPC 服务名称格式不合法");
        return value;
    }

    private String validatedMethod(String value) {
        if (!METHOD.matcher(value).matches() || value.length() > 256) throw rejected("gRPC 方法名称格式不合法");
        return value;
    }

    private List<GrpcDebugTransport.MetadataEntry> resolveMetadata(List<MetadataInput> input, ResolutionContext variables) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > MAX_METADATA_ENTRIES) throw rejected("gRPC Metadata 最多支持 32 项");
        List<GrpcDebugTransport.MetadataEntry> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int length = 0;
        for (MetadataInput item : input) {
            if (item == null) throw rejected("gRPC Metadata 格式不合法");
            String key = metadataKey(variables.resolve(item.key()), "Metadata 名称");
            String value = variables.resolve(item.value());
            validateMetadataValue(value, "Metadata 值");
            if (!names.add(key)) throw rejected("gRPC Metadata 名称不能重复：" + key);
            length += key.length() + value.length();
            if (length > MAX_METADATA_LENGTH) throw rejected("gRPC Metadata 总长度不能超过 24 KB");
            result.add(new GrpcDebugTransport.MetadataEntry(key, value));
        }
        return result;
    }

    private AuthApplied applyAuth(List<GrpcDebugTransport.MetadataEntry> metadata, AuthInput input, ResolutionContext variables) {
        AuthInput auth = input == null ? new AuthInput("NONE", "", "", "", "", "") : input;
        String type = auth.type() == null || auth.type().isBlank() ? "NONE" : auth.type().trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(type)) return new AuthApplied(metadata, type);
        List<GrpcDebugTransport.MetadataEntry> result = new ArrayList<>(metadata);
        return switch (type) {
            case "BEARER" -> {
                String token = required(variables.resolve(auth.token()), "Bearer Token");
                variables.markSensitive(token);
                yield new AuthApplied(withAuthMetadata(result, "authorization", "Bearer " + token), type);
            }
            case "BASIC" -> {
                String username = required(variables.resolve(auth.username()), "用户名");
                String password = required(variables.resolve(auth.password()), "密码");
                variables.markSensitive(password);
                String credentials = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                variables.markSensitive(credentials);
                yield new AuthApplied(withAuthMetadata(result, "authorization", "Basic " + credentials), type);
            }
            case "API_KEY" -> {
                String key = metadataKey(variables.resolve(auth.key()), "API Key 名称");
                String value = required(variables.resolve(auth.value()), "API Key 值");
                validateMetadataValue(value, "API Key 值");
                variables.markSensitive(value);
                yield new AuthApplied(withAuthMetadata(result, key, value), type);
            }
            default -> throw rejected("不支持的 gRPC 认证类型");
        };
    }

    private List<GrpcDebugTransport.MetadataEntry> withAuthMetadata(List<GrpcDebugTransport.MetadataEntry> metadata, String key, String value) {
        if (metadata.stream().anyMatch(item -> item.key().equalsIgnoreCase(key))) {
            throw rejected("认证配置与自定义 Metadata 重复：" + key);
        }
        validateMetadataValue(value, "认证值");
        if (metadata.size() >= MAX_METADATA_ENTRIES) throw rejected("gRPC Metadata 最多支持 32 项（含认证）");
        int length = key.length() + value.length();
        for (GrpcDebugTransport.MetadataEntry item : metadata) length += item.key().length() + item.value().length();
        if (length > MAX_METADATA_LENGTH) throw rejected("gRPC Metadata 总长度不能超过 24 KB（含认证）");
        metadata.add(new GrpcDebugTransport.MetadataEntry(key, value));
        return List.copyOf(metadata);
    }

    private String metadataKey(String value, String label) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!METADATA_KEY.matcher(normalized).matches()) throw rejected(label + "格式不合法");
        if (normalized.startsWith("grpc-")) throw rejected(label + "不能使用 grpc- 保留前缀");
        if (normalized.endsWith("-bin")) throw rejected("当前仅支持 ASCII Metadata，不支持 -bin 二进制 Metadata");
        return normalized;
    }

    private void validateMetadataValue(String value, String label) {
        if (value == null) throw rejected(label + "不能为空");
        if (value.length() > MAX_METADATA_VALUE_LENGTH) throw rejected(label + "不能超过 8192 字符");
        if (value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
            throw rejected(label + "仅支持可打印 ASCII 字符");
        }
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        return value;
    }

    private Map<String, Object> auditDetails(ValidatedRequest request, String status) {
        return Map.of("service", request.service(), "method", request.method(), "status", status, "tls", request.tls(),
                "environment", request.environmentCode() == null ? "none" : request.environmentCode(), "metadataCount", request.metadata().size(),
                "authType", request.authType());
    }

    private void auditDiscovery(AuthenticatedPrincipal principal, ProjectSummary project, String projectId, ValidatedDiscovery request,
                                String scope, int itemCount, String status) {
        audit.record(principal, project.organizationId(), projectId, "GRPC_DISCOVERY_EXECUTED", "PROJECT", project.id(),
                Map.of("scope", scope, "itemCount", itemCount, "status", status, "tls", request.tls(),
                        "environment", request.environmentCode() == null ? "none" : request.environmentCode(),
                        "metadataCount", request.metadata().size(), "authType", request.authType()));
    }

    private void validateJsonObject(String value) {
        try {
            JsonNode node = json.readTree(value);
            if (node == null || !node.isObject()) throw rejected("gRPC 请求体必须是 JSON 对象");
        } catch (JsonProcessingException exception) {
            throw rejected("gRPC 请求体必须是有效 JSON");
        }
    }

    private Target publicTarget(String value) {
        if (value == null || value.isBlank() || value.length() > 512) throw rejected("gRPC 目标不能为空且不能超过 512 字符");
        if (value.contains("://")) throw rejected("gRPC 目标应使用主机:端口格式");
        URI target;
        try {
            target = URI.create("grpc://" + value.trim());
        } catch (IllegalArgumentException exception) {
            throw rejected("gRPC 目标格式不合法");
        }
        if (target.getHost() == null || target.getPort() < 1 || target.getUserInfo() != null || target.getRawQuery() != null
                || target.getRawFragment() != null || (target.getRawPath() != null && !target.getRawPath().isBlank())) {
            throw rejected("gRPC 目标应使用主机:端口格式");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(target.getHost());
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(this::blockedAddress)) {
                throw rejected("不允许访问内网、回环或保留地址");
            }
        } catch (UnknownHostException exception) {
            throw rejected("无法解析 gRPC 主机");
        }
        return new Target(target.getHost(), target.getPort());
    }

    private boolean blockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19));
        }
        return bytes.length == 16 && (Byte.toUnsignedInt(bytes[0]) & 0xfe) == 0xfc;
    }

    private ResolutionContext variables(String projectId, String environmentCode) {
        Map<String, VariableValue> values = new HashMap<>();
        if (environmentCode != null) {
            for (ProjectParameterRepository.DebugParameter parameter : parameters.findForDebug(projectId, environmentCode)) {
                try {
                    values.put(parameter.key(), new VariableValue(parameter.secret() ? cipher.decrypt(parameter.value()) : parameter.value(), parameter.secret()));
                } catch (IllegalStateException exception) {
                    throw rejected("环境密钥无法解密");
                }
            }
        }
        return new ResolutionContext(values);
    }

    private String normalizeEnvironment(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw rejected("环境标识格式不合法");
        return normalized;
    }

    private String redactedTarget(Target target, boolean tls, String service, String method, Set<String> secretValues) {
        String host = target.host().contains(":") ? "[" + target.host() + "]" : target.host();
        String value = (tls ? "grpcs://" : "grpc://") + host + ":" + target.port() + "/" + service + "/" + method;
        for (String secret : secretValues) {
            if (secret != null && !secret.isBlank()) value = value.replace(secret, "***");
        }
        return value;
    }

    private Integer grpcCode(String value) {
        try {
            return Status.Code.valueOf(value).value();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String safeMessage(String status) {
        return switch (status) {
            case "DEADLINE_EXCEEDED" -> "gRPC 请求超时";
            case "UNIMPLEMENTED", "NOT_FOUND" -> "目标服务未启用反射或服务、方法不存在";
            case "UNARY_ONLY" -> "当前节点仅支持 Unary 调用";
            case "SERVER_STREAMING_ONLY" -> "当前节点仅支持服务端流调用，暂不支持客户端流或双向流";
            case "CLIENT_STREAMING_ONLY" -> "当前节点仅支持客户端流调用，暂不支持服务端流或双向流";
            case "BIDI_STREAMING_ONLY" -> "当前节点仅支持双向流调用";
            case "CLIENT_STREAM_NOT_READY" -> "gRPC 客户端暂未准备好接收下一条消息，请稍后重试";
            case "CLIENT_STREAM_CLOSED" -> "gRPC 客户端流已完成或已取消，不能继续发送消息";
            case "CLIENT_STREAM_MESSAGE_TOO_LARGE" -> "gRPC 客户端流单条消息不能超过 64 KB";
            case "CLIENT_STREAM_LIMIT" -> "gRPC 客户端流最多发送 100 条消息、总计 512 KB";
            case "BIDI_STREAM_CLOSED" -> "gRPC 双向流已完成或已取消，不能继续发送消息";
            case "BIDI_STREAM_MESSAGE_TOO_LARGE" -> "gRPC 双向流单条消息不能超过 64 KB";
            case "BIDI_STREAM_LIMIT" -> "gRPC 双向流单方向最多传输 100 条消息、总计 512 KB";
            case "INVALID_ARGUMENT" -> "请求 JSON 与 gRPC 方法参数不匹配";
            case "UNAUTHENTICATED", "PERMISSION_DENIED" -> "目标 gRPC 服务拒绝访问";
            case "CANCELLED" -> "gRPC 请求已取消";
            case "RESOURCE_EXHAUSTED" -> "gRPC 响应超过 512 KB 限制";
            default -> "目标 gRPC 服务不可用";
        };
    }

    private int elapsedMs(long started) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record UnaryRequest(String endpoint, Boolean tls, String service, String method, String requestJson, Integer timeoutMs,
                               String environmentCode, List<MetadataInput> metadata, AuthInput auth, String descriptorId) {
        public UnaryRequest {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }

    public record ClientStreamStartInput(String endpoint, Boolean tls, String service, String method, Integer timeoutMs,
                                         String environmentCode, List<MetadataInput> metadata, AuthInput auth, String descriptorId) {
        public ClientStreamStartInput {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }

    public record ClientStreamMessageInput(String requestJson) {
    }

    public record BidiStreamStartInput(String endpoint, Boolean tls, String service, String method, Integer timeoutMs,
                                       String environmentCode, List<MetadataInput> metadata, AuthInput auth, String descriptorId) {
        public BidiStreamStartInput {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }

    public record BidiStreamMessageInput(String requestJson) {
    }

    public record DiscoveryInput(String endpoint, Boolean tls, Integer timeoutMs, String environmentCode, List<MetadataInput> metadata,
                                 AuthInput auth, String service) {
        public DiscoveryInput {
            metadata = metadata == null ? List.of() : List.copyOf(metadata);
        }
    }

    public record MetadataInput(String key, String value) {
    }

    public record AuthInput(String type, String token, String username, String password, String key, String value) {
    }

    public record GrpcResult(String historyId, boolean success, String status, int durationMs, String body, boolean truncated,
                             String errorMessage) {
    }

    public record DiscoveryResult(List<String> services, List<GrpcDebugTransport.MethodSummary> methods, boolean truncated) {
        public DiscoveryResult {
            services = services == null ? List.of() : List.copyOf(services);
            methods = methods == null ? List.of() : List.copyOf(methods);
        }
    }

    public record StreamSessionSummary(String id, String status, String target, String service, String method, boolean tls,
                                       String environmentCode, String authType, int metadataCount, int messageCount, int responseBytes,
                                       Instant createdAt, Instant closedAt, String closeReason) {
    }

    public record StreamMessageSummary(String id, String payload, boolean truncated, int sizeBytes, Instant createdAt) {
    }

    public record ClientStreamSessionSummary(String id, String status, String target, String service, String method, boolean tls,
                                             String environmentCode, String authType, int metadataCount, int sentMessageCount, int sentBytes,
                                             int responseBytes, String responseBody, boolean responseTruncated, Instant createdAt,
                                             Instant closedAt, String closeReason) {
    }

    public record BidiStreamSessionSummary(String id, String status, String target, String service, String method, boolean tls,
                                           String environmentCode, String authType, int metadataCount, int sentMessageCount, int sentBytes,
                                           int receivedMessageCount, int responseBytes, Instant createdAt, Instant closedAt,
                                           String closeReason) {
    }

    private record Target(String host, int port) {
    }

    private record AuthApplied(List<GrpcDebugTransport.MetadataEntry> metadata, String type) {
    }

    private record ValidatedDiscovery(Target target, boolean tls, int timeoutMs, List<GrpcDebugTransport.MetadataEntry> metadata,
                                      String authType, String environmentCode, ResolutionContext variables) {
    }

    private record ValidatedRequest(Target target, boolean tls, String service, String method, String requestJson, int timeoutMs,
                                    List<GrpcDebugTransport.MetadataEntry> metadata, List<FileDescriptorProto> descriptorProtos, String authType,
                                    String environmentCode, String historyTarget) {
    }

    private static final class StreamSessionState {
        private final Object monitor = new Object();
        private final String id;
        private final String userId;
        private final String organizationId;
        private final String projectId;
        private final String target;
        private final String service;
        private final String method;
        private final boolean tls;
        private final String environmentCode;
        private final String authType;
        private final int metadataCount;
        private final Instant createdAt = Instant.now();
        private final List<StreamMessageSummary> messages = new ArrayList<>();
        private GrpcDebugTransport.StreamSubscription subscription;
        private String status = "OPEN";
        private Instant closedAt;
        private String closeReason;
        private int responseBytes;
        private boolean cancelPending;

        private StreamSessionState(String id, String userId, String organizationId, String projectId, ValidatedRequest request) {
            this.id = id;
            this.userId = userId;
            this.organizationId = organizationId;
            this.projectId = projectId;
            this.target = request.historyTarget();
            this.service = request.service();
            this.method = request.method();
            this.tls = request.tls();
            this.environmentCode = request.environmentCode();
            this.authType = request.authType();
            this.metadataCount = request.metadata().size();
        }

        private String id() { return id; }

        private Instant createdAt() { return createdAt; }

        private boolean belongsTo(AuthenticatedPrincipal principal, String expectedProjectId) {
            return userId.equals(principal.userId()) && projectId.equals(expectedProjectId);
        }

        private boolean active() {
            synchronized (monitor) {
                return "OPEN".equals(status);
            }
        }

        private void attach(GrpcDebugTransport.StreamSubscription value) {
            boolean cancel;
            String reason;
            synchronized (monitor) {
                subscription = value;
                cancel = cancelPending || !"OPEN".equals(status);
                reason = closeReason;
                if (cancel) subscription = null;
            }
            if (cancel) value.cancel(reason == null ? "TestHub stream already closed" : reason);
        }

        private GrpcDebugTransport.StreamSubscription received(String payload) {
            synchronized (monitor) {
                if (!"OPEN".equals(status)) return null;
                byte[] bytes = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
                if (messages.size() >= MAX_STREAM_MESSAGES) return limitReached("已达到 100 条流式消息上限");
                if (responseBytes + bytes.length > MAX_STREAM_RESPONSE_BYTES) return limitReached("已达到 512 KB 流式响应上限");
                boolean truncated = bytes.length > MAX_STREAM_MESSAGE_BYTES;
                String safePayload = truncated
                        ? new String(bytes, 0, MAX_STREAM_MESSAGE_BYTES, StandardCharsets.UTF_8) + "\n…（单条消息已截断）"
                        : payload == null ? "" : payload;
                responseBytes += bytes.length;
                messages.add(new StreamMessageSummary(UlidCreator.getUlid().toString(), safePayload, truncated, bytes.length, Instant.now()));
                return null;
            }
        }

        private GrpcDebugTransport.StreamSubscription limitReached(String reason) {
            status = "LIMIT_REACHED";
            closedAt = Instant.now();
            closeReason = reason;
            cancelPending = true;
            GrpcDebugTransport.StreamSubscription value = subscription;
            subscription = null;
            return value;
        }

        private boolean completed() {
            synchronized (monitor) {
                if (!"OPEN".equals(status)) return false;
                status = "COMPLETED";
                closedAt = Instant.now();
                closeReason = null;
                subscription = null;
                return true;
            }
        }

        private boolean failed(String reason) {
            synchronized (monitor) {
                if (!"OPEN".equals(status)) return false;
                status = "FAILED";
                closedAt = Instant.now();
                closeReason = reason;
                subscription = null;
                return true;
            }
        }

        private GrpcDebugTransport.StreamSubscription cancelled(String reason) {
            synchronized (monitor) {
                if (!"OPEN".equals(status)) return null;
                status = "CANCELLED";
                closedAt = Instant.now();
                closeReason = reason;
                cancelPending = true;
                GrpcDebugTransport.StreamSubscription value = subscription;
                subscription = null;
                return value;
            }
        }

        private boolean canDiscard(Instant cutoff) {
            synchronized (monitor) {
                return !"OPEN".equals(status) && closedAt != null && closedAt.isBefore(cutoff);
            }
        }

        private void stopQuietly() {
            GrpcDebugTransport.StreamSubscription value = cancelled("TestHub 服务正在停止");
            if (value != null) value.cancel("TestHub service stopping");
        }

        private StreamSessionSummary summary() {
            synchronized (monitor) {
                return new StreamSessionSummary(id, status, target, service, method, tls, environmentCode, authType, metadataCount,
                        messages.size(), responseBytes, createdAt, closedAt, closeReason);
            }
        }

        private List<StreamMessageSummary> messages() {
            synchronized (monitor) {
                return List.copyOf(messages);
            }
        }
    }

    private static final class ClientStreamSessionState {
        private final Object monitor = new Object();
        private final String id;
        private final String userId;
        private final String organizationId;
        private final String projectId;
        private final String target;
        private final String service;
        private final String method;
        private final boolean tls;
        private final String environmentCode;
        private final String authType;
        private final int metadataCount;
        private final Instant createdAt = Instant.now();
        private GrpcDebugTransport.ClientStreamSubscription subscription;
        private String status = "OPEN";
        private Instant closedAt;
        private String closeReason;
        private int sentMessageCount;
        private int sentBytes;
        private int responseBytes;
        private String responseBody = "";
        private boolean responseTruncated;
        private boolean cancelPending;

        private ClientStreamSessionState(String id, String userId, String organizationId, String projectId, ValidatedRequest request) {
            this.id = id;
            this.userId = userId;
            this.organizationId = organizationId;
            this.projectId = projectId;
            this.target = request.historyTarget();
            this.service = request.service();
            this.method = request.method();
            this.tls = request.tls();
            this.environmentCode = request.environmentCode();
            this.authType = request.authType();
            this.metadataCount = request.metadata().size();
        }

        private String id() { return id; }

        private Instant createdAt() { return createdAt; }

        private String environmentCode() { return environmentCode; }

        private boolean belongsTo(AuthenticatedPrincipal principal, String expectedProjectId) {
            return userId.equals(principal.userId()) && projectId.equals(expectedProjectId);
        }

        private boolean active() {
            synchronized (monitor) {
                return "OPEN".equals(status) || "HALF_CLOSED".equals(status);
            }
        }

        private boolean openForSending() {
            synchronized (monitor) {
                return "OPEN".equals(status);
            }
        }

        private void attach(GrpcDebugTransport.ClientStreamSubscription value) {
            boolean cancel;
            String reason;
            synchronized (monitor) {
                subscription = value;
                cancel = cancelPending || !active();
                reason = closeReason;
                if (cancel) subscription = null;
            }
            if (cancel) value.cancel(reason == null ? "TestHub client stream already closed" : reason);
        }

        private ClientStreamSessionSummary send(String payload) throws GrpcDebugTransport.TransportException {
            synchronized (monitor) {
                if (!"OPEN".equals(status) || subscription == null) throw new GrpcDebugTransport.TransportException("CLIENT_STREAM_CLOSED");
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_CLIENT_STREAM_MESSAGE_BYTES) {
                    throw new GrpcDebugTransport.TransportException("CLIENT_STREAM_MESSAGE_TOO_LARGE");
                }
                if (sentMessageCount >= MAX_CLIENT_STREAM_MESSAGES || sentBytes + bytes.length > MAX_CLIENT_STREAM_REQUEST_BYTES) {
                    throw new GrpcDebugTransport.TransportException("CLIENT_STREAM_LIMIT");
                }
                subscription.send(payload);
                sentMessageCount++;
                sentBytes += bytes.length;
                return summary();
            }
        }

        private void complete() {
            GrpcDebugTransport.ClientStreamSubscription value;
            synchronized (monitor) {
                if (!"OPEN".equals(status) || subscription == null) return;
                status = "HALF_CLOSED";
                value = subscription;
            }
            value.complete();
        }

        private GrpcDebugTransport.ClientStreamSubscription response(String payload) {
            synchronized (monitor) {
                if (!active()) return null;
                byte[] bytes = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_STREAM_RESPONSE_BYTES) return limitReached("gRPC 客户端流响应超过 512 KB 上限");
                responseBytes = bytes.length;
                responseTruncated = bytes.length > MAX_STREAM_MESSAGE_BYTES;
                responseBody = responseTruncated
                        ? new String(bytes, 0, MAX_STREAM_MESSAGE_BYTES, StandardCharsets.UTF_8) + "\n…（响应已截断）"
                        : payload == null ? "" : payload;
                return null;
            }
        }

        private GrpcDebugTransport.ClientStreamSubscription limitReached(String reason) {
            status = "LIMIT_REACHED";
            closedAt = Instant.now();
            closeReason = reason;
            cancelPending = true;
            GrpcDebugTransport.ClientStreamSubscription value = subscription;
            subscription = null;
            return value;
        }

        private boolean completed() {
            synchronized (monitor) {
                if (!active()) return false;
                status = "COMPLETED";
                closedAt = Instant.now();
                closeReason = null;
                subscription = null;
                return true;
            }
        }

        private boolean failed(String reason) {
            synchronized (monitor) {
                if (!active()) return false;
                status = "FAILED";
                closedAt = Instant.now();
                closeReason = reason;
                subscription = null;
                return true;
            }
        }

        private GrpcDebugTransport.ClientStreamSubscription cancelled(String reason) {
            synchronized (monitor) {
                if (!active()) return null;
                status = "CANCELLED";
                closedAt = Instant.now();
                closeReason = reason;
                cancelPending = true;
                GrpcDebugTransport.ClientStreamSubscription value = subscription;
                subscription = null;
                return value;
            }
        }

        private boolean canDiscard(Instant cutoff) {
            synchronized (monitor) {
                return !active() && closedAt != null && closedAt.isBefore(cutoff);
            }
        }

        private void stopQuietly() {
            GrpcDebugTransport.ClientStreamSubscription value = cancelled("TestHub 服务正在停止");
            if (value != null) value.cancel("TestHub service stopping");
        }

        private ClientStreamSessionSummary summary() {
            synchronized (monitor) {
                return new ClientStreamSessionSummary(id, status, target, service, method, tls, environmentCode, authType, metadataCount,
                        sentMessageCount, sentBytes, responseBytes, responseBody, responseTruncated, createdAt, closedAt, closeReason);
            }
        }
    }

    private static final class BidiStreamSessionState {
        private final Object monitor = new Object();
        private final String id;
        private final String userId;
        private final String organizationId;
        private final String projectId;
        private final String target;
        private final String service;
        private final String method;
        private final boolean tls;
        private final String environmentCode;
        private final String authType;
        private final int metadataCount;
        private final Instant createdAt = Instant.now();
        private final List<StreamMessageSummary> messages = new ArrayList<>();
        private GrpcDebugTransport.BidiStreamSubscription subscription;
        private String status = "OPEN";
        private Instant closedAt;
        private String closeReason;
        private int sentMessageCount;
        private int sentBytes;
        private int responseBytes;
        private boolean cancelPending;

        private BidiStreamSessionState(String id, String userId, String organizationId, String projectId, ValidatedRequest request) {
            this.id = id;
            this.userId = userId;
            this.organizationId = organizationId;
            this.projectId = projectId;
            this.target = request.historyTarget();
            this.service = request.service();
            this.method = request.method();
            this.tls = request.tls();
            this.environmentCode = request.environmentCode();
            this.authType = request.authType();
            this.metadataCount = request.metadata().size();
        }

        private String id() { return id; }

        private Instant createdAt() { return createdAt; }

        private String environmentCode() { return environmentCode; }

        private boolean belongsTo(AuthenticatedPrincipal principal, String expectedProjectId) {
            return userId.equals(principal.userId()) && projectId.equals(expectedProjectId);
        }

        private boolean active() {
            synchronized (monitor) {
                return "OPEN".equals(status) || "HALF_CLOSED".equals(status);
            }
        }

        private boolean openForSending() {
            synchronized (monitor) {
                return "OPEN".equals(status);
            }
        }

        private void attach(GrpcDebugTransport.BidiStreamSubscription value) {
            boolean cancel;
            String reason;
            synchronized (monitor) {
                subscription = value;
                cancel = cancelPending || !active();
                reason = closeReason;
                if (cancel) subscription = null;
            }
            if (cancel) value.cancel(reason == null ? "TestHub bidirectional stream already closed" : reason);
        }

        private BidiStreamSessionSummary send(String payload) throws GrpcDebugTransport.TransportException {
            synchronized (monitor) {
                if (!"OPEN".equals(status) || subscription == null) throw new GrpcDebugTransport.TransportException("BIDI_STREAM_CLOSED");
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BIDI_STREAM_MESSAGE_BYTES) {
                    throw new GrpcDebugTransport.TransportException("BIDI_STREAM_MESSAGE_TOO_LARGE");
                }
                if (sentMessageCount >= MAX_BIDI_STREAM_MESSAGES || sentBytes + bytes.length > MAX_BIDI_STREAM_REQUEST_BYTES) {
                    throw new GrpcDebugTransport.TransportException("BIDI_STREAM_LIMIT");
                }
                subscription.send(payload);
                sentMessageCount++;
                sentBytes += bytes.length;
                return summary();
            }
        }

        private GrpcDebugTransport.BidiStreamSubscription received(String payload) {
            synchronized (monitor) {
                if (!active()) return null;
                byte[] bytes = (payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8);
                if (messages.size() >= MAX_BIDI_STREAM_MESSAGES) return limitReached("已达到 100 条双向流响应消息上限");
                if (responseBytes + bytes.length > MAX_BIDI_STREAM_RESPONSE_BYTES) return limitReached("已达到 512 KB 双向流响应上限");
                boolean truncated = bytes.length > MAX_BIDI_STREAM_MESSAGE_BYTES;
                String safePayload = truncated
                        ? new String(bytes, 0, MAX_BIDI_STREAM_MESSAGE_BYTES, StandardCharsets.UTF_8) + "\n…（单条消息已截断）"
                        : payload == null ? "" : payload;
                responseBytes += bytes.length;
                messages.add(new StreamMessageSummary(UlidCreator.getUlid().toString(), safePayload, truncated, bytes.length, Instant.now()));
                return null;
            }
        }

        private void complete() {
            GrpcDebugTransport.BidiStreamSubscription value;
            synchronized (monitor) {
                if (!"OPEN".equals(status) || subscription == null) return;
                status = "HALF_CLOSED";
                value = subscription;
            }
            value.complete();
        }

        private GrpcDebugTransport.BidiStreamSubscription limitReached(String reason) {
            status = "LIMIT_REACHED";
            closedAt = Instant.now();
            closeReason = reason;
            cancelPending = true;
            GrpcDebugTransport.BidiStreamSubscription value = subscription;
            subscription = null;
            return value;
        }

        private boolean completed() {
            synchronized (monitor) {
                if (!active()) return false;
                status = "COMPLETED";
                closedAt = Instant.now();
                closeReason = null;
                subscription = null;
                return true;
            }
        }

        private boolean failed(String reason) {
            synchronized (monitor) {
                if (!active()) return false;
                status = "FAILED";
                closedAt = Instant.now();
                closeReason = reason;
                subscription = null;
                return true;
            }
        }

        private GrpcDebugTransport.BidiStreamSubscription cancelled(String reason) {
            synchronized (monitor) {
                if (!active()) return null;
                status = "CANCELLED";
                closedAt = Instant.now();
                closeReason = reason;
                cancelPending = true;
                GrpcDebugTransport.BidiStreamSubscription value = subscription;
                subscription = null;
                return value;
            }
        }

        private boolean canDiscard(Instant cutoff) {
            synchronized (monitor) {
                return !active() && closedAt != null && closedAt.isBefore(cutoff);
            }
        }

        private void stopQuietly() {
            GrpcDebugTransport.BidiStreamSubscription value = cancelled("TestHub 服务正在停止");
            if (value != null) value.cancel("TestHub service stopping");
        }

        private BidiStreamSessionSummary summary() {
            synchronized (monitor) {
                return new BidiStreamSessionSummary(id, status, target, service, method, tls, environmentCode, authType, metadataCount,
                        sentMessageCount, sentBytes, messages.size(), responseBytes, createdAt, closedAt, closeReason);
            }
        }

        private List<StreamMessageSummary> messages() {
            synchronized (monitor) {
                return List.copyOf(messages);
            }
        }
    }

    private record VariableValue(String value, boolean secret) {
    }

    private static final class ResolutionContext {
        private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");
        private final Map<String, VariableValue> variables;
        private final Set<String> secretValues = new HashSet<>();
        private final Instant executionTime = Instant.now();
        private String randomUuid;

        private ResolutionContext(Map<String, VariableValue> variables) {
            this.variables = Map.copyOf(variables);
        }

        private String resolve(String source) {
            if (source == null) return "";
            Matcher matcher = PLACEHOLDER.matcher(source);
            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String key = matcher.group(1);
                String replacement = dynamicValue(key);
                if (replacement == null) {
                    VariableValue variable = variables.get(key);
                    if (variable == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未解析变量：" + key);
                    replacement = variable.value();
                    if (variable.secret()) secretValues.add(replacement);
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private String dynamicValue(String key) {
            return switch (key) {
                case "$timestamp" -> Long.toString(executionTime.toEpochMilli());
                case "$isoTimestamp" -> executionTime.toString();
                case "$randomUUID", "$uuid" -> randomUuid();
                default -> null;
            };
        }

        private String randomUuid() {
            if (randomUuid == null) randomUuid = UUID.randomUUID().toString();
            return randomUuid;
        }

        private Set<String> secretValues() {
            return Set.copyOf(secretValues);
        }

        private void markSensitive(String value) {
            if (value != null && !value.isBlank()) secretValues.add(value);
        }
    }
}
