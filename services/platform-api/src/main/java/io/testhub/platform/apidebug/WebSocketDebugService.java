package io.testhub.platform.apidebug;

import com.github.f4b6a3.ulid.UlidCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectParameterRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Holds short-lived debugging sessions in process memory. Authentication data,
 * subscription frames and WebSocket traffic never enter the database or audit details.
 */
@Service
public class WebSocketDebugService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "content-length", "connection", "expect", "upgrade",
            "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-protocol");
    private static final int MAX_SESSIONS_PER_USER_PROJECT = 10;
    private static final int MAX_MESSAGES_PER_SESSION = 100;
    private static final int MAX_MESSAGE_LENGTH = 32_000;
    private static final int MAX_SUBSCRIPTIONS = 12;
    private static final int MAX_SUBPROTOCOLS = 8;
    private static final int DEFAULT_RECONNECT_ATTEMPTS = 3;
    private static final int DEFAULT_RECONNECT_DELAY_MS = 1_000;
    private static final Pattern SUBPROTOCOL = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "testhub-websocket-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final WebSocketDebugClient client;
    private final ApiDebugHistoryRepository history;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectParameterRepository parameters;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final DesktopApiDebugTargetPolicy desktopTargets;

    public WebSocketDebugService(WebSocketDebugClient client, ApiDebugHistoryRepository history, AccessService access, AuditService audit,
                                 ProjectParameterRepository parameters, SecretCipher cipher, ObjectMapper json, DesktopApiDebugTargetPolicy desktopTargets) {
        this.client = client;
        this.history = history;
        this.access = access;
        this.audit = audit;
        this.parameters = parameters;
        this.cipher = cipher;
        this.json = json;
        this.desktopTargets = desktopTargets;
    }

    public List<SessionSummary> sessions(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        cleanupExpiredSessions();
        return sessions.values().stream()
                .filter(session -> session.belongsTo(principal, projectId))
                .sorted(Comparator.comparing(SessionState::createdAt).reversed())
                .map(SessionState::summary)
                .toList();
    }

    public SessionSummary session(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedSession(principal, projectId, sessionId).summary();
    }

    public SessionSummary connect(AuthenticatedPrincipal principal, String projectId, ConnectInput input) {
        ProjectSummary project = writableProject(principal, projectId);
        ResolvedConnect resolved = resolve(projectId, input);
        cleanupExpiredSessions();
        long existing = sessions.values().stream().filter(session -> session.belongsTo(principal, projectId)).count();
        if (existing >= MAX_SESSIONS_PER_USER_PROJECT) {
            throw conflict("同一项目最多保留 10 个 WebSocket 调试会话，请先关闭并等待旧会话过期");
        }
        long started = System.nanoTime();
        SessionState session = new SessionState(UlidCreator.getUlid().toString(), principal.userId(), project.organizationId(), project.id(), resolved);
        sessions.put(session.id(), session);
        try {
            openInitial(session);
            audit.record(principal, project.organizationId(), projectId, "WEBSOCKET_DEBUG_CONNECTED", "WEBSOCKET_SESSION", session.id(),
                    Map.of("environment", resolved.environmentCode() == null ? "none" : resolved.environmentCode(),
                            "autoReconnect", resolved.autoReconnect(), "subprotocols", resolved.subprotocols().size(),
                            "subscriptions", resolved.subscriptions().size()));
            SessionSummary summary = session.summary();
            history.insert(new ApiDebugHistory(UlidCreator.getUlid().toString(), project.organizationId(), project.id(), "WEBSOCKET", "WEBSOCKET",
                    summary.target(), null, (int) ((System.nanoTime() - started) / 1_000_000), 0, "COMPLETED", null,
                    encryptedReplayDraft(input), principal.userId(), null));
            return summary;
        } catch (Exception exception) {
            sessions.remove(session.id());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "连接 WebSocket 目标服务失败");
        }
    }

    private String encryptedReplayDraft(ConnectInput input) {
        try {
            return cipher.encrypt(json.writeValueAsString(java.util.Map.of("protocol", "WEBSOCKET", "request", input)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法加密保存 WebSocket 回放草稿", exception);
        }
    }

    public List<MessageSummary> messages(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        access.requireProjectRead(principal, projectId);
        return ownedSession(principal, projectId, sessionId).messages();
    }

    public SessionSummary send(AuthenticatedPrincipal principal, String projectId, String sessionId, SendInput input) {
        writableProject(principal, projectId);
        if (input == null || input.message() == null) throw rejected("WebSocket 消息不能为空");
        if (input.message().length() > MAX_MESSAGE_LENGTH) throw rejected("单条 WebSocket 消息不能超过 32000 字符");
        SessionState session = ownedSession(principal, projectId, sessionId);
        WebSocketDebugClient.Connection connection = session.openConnection();
        try {
            session.sent(input.message());
            connection.sendText(input.message());
            return session.summary();
        } catch (Exception exception) {
            disconnected(session, session.generation(), "发送消息失败，连接已中断");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发送 WebSocket 消息失败");
        }
    }

    public SessionSummary close(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        ProjectSummary project = writableProject(principal, projectId);
        SessionState session = ownedSession(principal, projectId, sessionId);
        WebSocketDebugClient.Connection connection = session.closeManually();
        if (connection != null) {
            try {
                connection.close(1000, "Closed by TestHub");
            } catch (Exception ignored) {
                // The session is closed locally and automatic reconnect has already been disabled.
            }
        }
        audit.record(principal, project.organizationId(), projectId, "WEBSOCKET_DEBUG_CLOSED", "WEBSOCKET_SESSION", session.id(),
                Map.of("status", session.status()));
        return session.summary();
    }

    private void openInitial(SessionState session) throws Exception {
        int generation = session.beginInitialConnection();
        WebSocketDebugClient.Connection connection = client.connect(session.endpoint(), session.headers(), session.subprotocols(), listener(session, generation));
        if (!session.attach(generation, connection)) {
            closeQuietly(connection);
            return;
        }
        try {
            sendSubscriptions(session, generation, connection);
        } catch (Exception exception) {
            disconnected(session, generation, "订阅帧发送失败，连接已中断");
        }
    }

    private void reconnect(SessionState session) {
        int generation = session.beginReconnect();
        if (generation < 0) return;
        try {
            WebSocketDebugClient.Connection connection = client.connect(session.endpoint(), session.headers(), session.subprotocols(), listener(session, generation));
            if (!session.attach(generation, connection)) {
                closeQuietly(connection);
                return;
            }
            sendSubscriptions(session, generation, connection);
        } catch (Exception exception) {
            disconnected(session, generation, "第 " + session.reconnectAttempts() + " 次重连失败");
        }
    }

    private WebSocketDebugClient.Listener listener(SessionState session, int generation) {
        return new WebSocketDebugClient.Listener() {
            @Override
            public void onText(String payload) {
                session.received(generation, payload);
            }

            @Override
            public void onClosed(int statusCode, String reason) {
                disconnected(session, generation, "目标服务已关闭连接（" + statusCode + "）");
            }

            @Override
            public void onError() {
                disconnected(session, generation, "连接异常关闭");
            }
        };
    }

    private void sendSubscriptions(SessionState session, int generation, WebSocketDebugClient.Connection connection) throws Exception {
        for (String subscription : session.subscriptions()) {
            if (!session.isOpen(generation)) return;
            session.subscriptionSent(generation, subscription);
            connection.sendText(subscription);
        }
    }

    private void disconnected(SessionState session, int generation, String reason) {
        ReconnectPlan plan = session.disconnected(generation, reason);
        if (plan != null) reconnectExecutor.schedule(() -> reconnect(session), plan.delayMs(), TimeUnit.MILLISECONDS);
    }

    private void closeQuietly(WebSocketDebugClient.Connection connection) {
        try {
            connection.close(1000, "Superseded by TestHub");
        } catch (Exception ignored) {
            // A stale connection is already unreachable from the active session.
        }
    }

    private ResolvedConnect resolve(String projectId, ConnectInput input) {
        if (input == null) throw rejected("WebSocket 连接不能为空");
        String environmentCode = normalizeEnvironment(input.environmentCode());
        ResolutionContext variables = variables(projectId, environmentCode);
        URI endpoint = safeWebSocketUrl(variables.resolve(input.url()));
        List<HeaderInput> rawHeaders = new ArrayList<>();
        if (input.headers() != null) {
            input.headers().forEach(header -> rawHeaders.add(new HeaderInput(
                    header == null ? null : variables.resolve(header.name()), header == null ? null : variables.resolve(header.value()))));
        }
        AuthInput rawAuth = input.auth() == null ? new AuthInput("NONE", "", "", "", "", "", "HEADER") : input.auth();
        AuthInput auth = new AuthInput(rawAuth.type(), variables.resolve(rawAuth.token()), variables.resolve(rawAuth.username()),
                variables.resolve(rawAuth.password()), variables.resolve(rawAuth.key()), variables.resolve(rawAuth.value()), rawAuth.placement());
        AuthApplied applied = applyAuth(endpoint, validateHeaders(rawHeaders), auth);
        endpoint = safeWebSocketUrl(applied.url().toString());
        return new ResolvedConnect(endpoint, applied.headers(), subprotocols(input.subprotocols()), subscriptions(input.subscriptions(), variables),
                environmentCode, reconnect(input.reconnect()));
    }

    private List<String> subprotocols(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_SUBPROTOCOLS) throw rejected("WebSocket 子协议不能超过 8 项");
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String protocol = value == null ? "" : value.trim();
            if (!SUBPROTOCOL.matcher(protocol).matches()) throw rejected("WebSocket 子协议格式不合法");
            if (result.stream().anyMatch(protocol::equalsIgnoreCase)) throw rejected("WebSocket 子协议不能重复");
            result.add(protocol);
        }
        return List.copyOf(result);
    }

    private List<String> subscriptions(List<String> values, ResolutionContext variables) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_SUBSCRIPTIONS) throw rejected("订阅帧不能超过 12 条");
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String payload = variables.resolve(value);
            if (payload.isBlank()) throw rejected("订阅帧不能为空");
            if (payload.length() > MAX_MESSAGE_LENGTH) throw rejected("单条订阅帧不能超过 32000 字符");
            result.add(payload);
        }
        return List.copyOf(result);
    }

    private ReconnectSettings reconnect(ReconnectInput input) {
        if (input == null || !Boolean.TRUE.equals(input.enabled())) return new ReconnectSettings(false, 0, 0);
        int maxAttempts = input.maxAttempts() == null ? DEFAULT_RECONNECT_ATTEMPTS : input.maxAttempts();
        int delayMs = input.delayMs() == null ? DEFAULT_RECONNECT_DELAY_MS : input.delayMs();
        if (maxAttempts < 1 || maxAttempts > 5) throw rejected("自动重连次数应在 1 到 5 次之间");
        if (delayMs < 100 || delayMs > 10_000) throw rejected("自动重连间隔应在 100 到 10000 毫秒之间");
        return new ReconnectSettings(true, maxAttempts, delayMs);
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

    private AuthApplied applyAuth(URI url, List<ApiDebugTransport.Header> headers, AuthInput auth) {
        String type = auth.type() == null ? "NONE" : auth.type().trim().toUpperCase(Locale.ROOT);
        if (type.equals("NONE")) return new AuthApplied(url, headers);
        List<ApiDebugTransport.Header> result = new ArrayList<>(headers);
        return switch (type) {
            case "BEARER" -> new AuthApplied(url, withAuthHeader(result, "Authorization", "Bearer " + required(auth.token(), "Bearer Token")));
            case "BASIC" -> {
                String user = required(auth.username(), "用户名");
                String password = required(auth.password(), "密码");
                String credentials = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                yield new AuthApplied(url, withAuthHeader(result, "Authorization", "Basic " + credentials));
            }
            case "API_KEY" -> {
                String key = required(auth.key(), "API Key 名称");
                String value = required(auth.value(), "API Key 值");
                String placement = auth.placement() == null ? "HEADER" : auth.placement().trim().toUpperCase(Locale.ROOT);
                if (placement.equals("HEADER")) yield new AuthApplied(url, withAuthHeader(result, key, value));
                if (!placement.equals("QUERY")) throw rejected("API Key 位置仅支持 HEADER 或 QUERY");
                yield new AuthApplied(appendQuery(url, key, value), result);
            }
            default -> throw rejected("不支持的认证类型");
        };
    }

    private List<ApiDebugTransport.Header> withAuthHeader(List<ApiDebugTransport.Header> headers, String name, String value) {
        if (headers.stream().anyMatch(header -> header.name().equalsIgnoreCase(name))) throw rejected("认证配置与手工请求头重复：" + name);
        headers.addAll(validateHeaders(List.of(new HeaderInput(name, value))));
        return headers;
    }

    private URI appendQuery(URI url, String key, String value) {
        String query = url.getRawQuery();
        String item = URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
        try {
            return new URI(url.getScheme(), null, url.getHost(), url.getPort(), url.getRawPath(),
                    query == null || query.isBlank() ? item : query + "&" + item, null);
        } catch (Exception exception) {
            throw rejected("API Key 查询参数格式不合法");
        }
    }

    private ProjectSummary writableProject(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private SessionState ownedSession(AuthenticatedPrincipal principal, String projectId, String sessionId) {
        SessionState session = sessions.get(sessionId);
        if (session == null || !session.belongsTo(principal, projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WebSocket 调试会话不存在");
        }
        return session;
    }

    private void cleanupExpiredSessions() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        sessions.entrySet().removeIf(entry -> entry.getValue().canDiscard(cutoff));
    }

    private URI safeWebSocketUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2_000) throw rejected("WebSocket URL 不能为空且不能超过 2000 字符");
        URI url;
        try {
            url = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw rejected("WebSocket URL 格式不合法");
        }
        if (url.getScheme() == null || !Set.of("ws", "wss").contains(url.getScheme().toLowerCase(Locale.ROOT))
                || url.getHost() == null || url.getUserInfo() != null || url.getPort() == 0 || url.getPort() > 65535) {
            throw rejected("仅支持不含用户信息的 WS 或 WSS URL");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
            if (addresses.length == 0 || (java.util.Arrays.stream(addresses).anyMatch(this::blockedAddress)
                    && !desktopTargets.permits(url, "wss".equalsIgnoreCase(url.getScheme()), addresses))) {
                throw rejected("目标未获允许：本地工作区请在本地执行设置中授权精确 HTTP(S) 目标（WS 对应 HTTP，WSS 对应 HTTPS）；在线服务不允许内网或回环目标");
            }
        } catch (UnknownHostException exception) {
            throw rejected("无法解析 WebSocket 主机");
        }
        return url;
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

    private List<ApiDebugTransport.Header> validateHeaders(List<HeaderInput> inputs) {
        if (inputs == null) return List.of();
        if (inputs.size() > 48) throw rejected("请求头不能超过 48 项");
        List<ApiDebugTransport.Header> headers = new ArrayList<>();
        for (HeaderInput input : inputs) {
            String name = input == null || input.name() == null ? "" : input.name().trim();
            String value = input == null || input.value() == null ? "" : input.value();
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,200}") || value.length() > 16_000 || value.contains("\r") || value.contains("\n")) {
                throw rejected("请求头格式不合法");
            }
            if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) throw rejected("不允许设置请求头：" + name);
            headers.add(new ApiDebugTransport.Header(name, value));
        }
        return headers;
    }

    private String normalizeEnvironment(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw rejected("环境标识格式不合法");
        return normalized;
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        return value;
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    @PreDestroy
    void stopReconnects() {
        reconnectExecutor.shutdownNow();
    }

    public record HeaderInput(String name, String value) {
    }

    public record AuthInput(String type, String token, String username, String password, String key, String value, String placement) {
    }

    public record ReconnectInput(Boolean enabled, Integer maxAttempts, Integer delayMs) {
    }

    public record ConnectInput(String url, List<HeaderInput> headers, String environmentCode, AuthInput auth,
                               List<String> subprotocols, List<String> subscriptions, ReconnectInput reconnect) {
    }

    public record SendInput(String message) {
    }

    public record SessionSummary(String id, String status, String target, int messageCount, Instant connectedAt, Instant closedAt,
                                 String closeReason, boolean autoReconnect, int reconnectAttempts, int maxReconnectAttempts,
                                 String selectedSubprotocol) {
    }

    public record MessageSummary(String id, String direction, String payload, boolean truncated, Instant createdAt) {
    }

    private record ResolvedConnect(URI endpoint, List<ApiDebugTransport.Header> headers, List<String> subprotocols,
                                   List<String> subscriptions, String environmentCode, ReconnectSettings reconnect) {
        private boolean autoReconnect() {
            return reconnect.enabled();
        }
    }

    private record ReconnectSettings(boolean enabled, int maxAttempts, int delayMs) {
    }

    private record ReconnectPlan(int delayMs) {
    }

    private record AuthApplied(URI url, List<ApiDebugTransport.Header> headers) {
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
    }

    private static final class SessionState {
        private final Object monitor = new Object();
        private final String id;
        private final String userId;
        private final String organizationId;
        private final String projectId;
        private final URI endpoint;
        private final List<ApiDebugTransport.Header> headers;
        private final List<String> subprotocols;
        private final List<String> subscriptions;
        private final ReconnectSettings reconnect;
        private final Instant createdAt = Instant.now();
        private final List<MessageSummary> messages = new ArrayList<>();
        private WebSocketDebugClient.Connection connection;
        private String status = "CONNECTING";
        private Instant closedAt;
        private String closeReason;
        private String selectedSubprotocol = "";
        private int generation;
        private int reconnectAttempts;
        private boolean reconnectPending;
        private boolean manuallyClosed;

        private SessionState(String id, String userId, String organizationId, String projectId, ResolvedConnect resolved) {
            this.id = id;
            this.userId = userId;
            this.organizationId = organizationId;
            this.projectId = projectId;
            this.endpoint = resolved.endpoint();
            this.headers = List.copyOf(resolved.headers());
            this.subprotocols = List.copyOf(resolved.subprotocols());
            this.subscriptions = List.copyOf(resolved.subscriptions());
            this.reconnect = resolved.reconnect();
        }

        private String id() { return id; }
        private Instant createdAt() { return createdAt; }
        private URI endpoint() { return endpoint; }
        private List<ApiDebugTransport.Header> headers() { return headers; }
        private List<String> subprotocols() { return subprotocols; }
        private List<String> subscriptions() { return subscriptions; }

        private boolean belongsTo(AuthenticatedPrincipal principal, String expectedProjectId) {
            return userId.equals(principal.userId()) && projectId.equals(expectedProjectId);
        }

        private int beginInitialConnection() {
            synchronized (monitor) {
                generation++;
                status = "CONNECTING";
                return generation;
            }
        }

        private int beginReconnect() {
            synchronized (monitor) {
                if (manuallyClosed || !status.equals("RECONNECTING") || !reconnectPending) return -1;
                reconnectPending = false;
                generation++;
                return generation;
            }
        }

        private boolean attach(int expectedGeneration, WebSocketDebugClient.Connection value) {
            synchronized (monitor) {
                if (manuallyClosed || generation != expectedGeneration || !status.equals("CONNECTING") && !status.equals("RECONNECTING")) return false;
                connection = value;
                selectedSubprotocol = value.subprotocol() == null ? "" : value.subprotocol();
                status = "OPEN";
                closedAt = null;
                closeReason = null;
                return true;
            }
        }

        private boolean isOpen(int expectedGeneration) {
            synchronized (monitor) {
                return generation == expectedGeneration && status.equals("OPEN") && !manuallyClosed;
            }
        }

        private WebSocketDebugClient.Connection openConnection() {
            synchronized (monitor) {
                if (!status.equals("OPEN") || connection == null) throw conflictStatic("WebSocket 连接未处于可发送状态");
                return connection;
            }
        }

        private int generation() {
            synchronized (monitor) {
                return generation;
            }
        }

        private ReconnectPlan disconnected(int expectedGeneration, String reason) {
            synchronized (monitor) {
                if (expectedGeneration != generation || manuallyClosed || status.equals("CLOSED")) return null;
                connection = null;
                selectedSubprotocol = "";
                closedAt = Instant.now();
                if (reconnect.enabled() && reconnectAttempts < reconnect.maxAttempts()) {
                    reconnectAttempts++;
                    reconnectPending = true;
                    status = "RECONNECTING";
                    closeReason = "连接已中断，准备第 " + reconnectAttempts + "/" + reconnect.maxAttempts() + " 次重连";
                    return new ReconnectPlan(reconnect.delayMs());
                }
                status = "CLOSED";
                closeReason = reason;
                return null;
            }
        }

        private void sent(String payload) {
            message("OUT", payload);
        }

        private void subscriptionSent(int expectedGeneration, String payload) {
            if (isOpen(expectedGeneration)) message("OUT", payload);
        }

        private void received(int expectedGeneration, String payload) {
            synchronized (monitor) {
                if (generation != expectedGeneration || manuallyClosed || status.equals("CLOSED") || status.equals("RECONNECTING")) return;
            }
            message("IN", payload);
        }

        private void message(String direction, String payload) {
            synchronized (monitor) {
                String safePayload = payload == null ? "" : payload;
                boolean truncated = safePayload.length() > MAX_MESSAGE_LENGTH;
                if (truncated) safePayload = safePayload.substring(0, MAX_MESSAGE_LENGTH) + "\n…（已截断）";
                messages.add(new MessageSummary(UlidCreator.getUlid().toString(), direction, safePayload, truncated, Instant.now()));
                if (messages.size() > MAX_MESSAGES_PER_SESSION) messages.remove(0);
            }
        }

        private WebSocketDebugClient.Connection closeManually() {
            synchronized (monitor) {
                if (manuallyClosed || status.equals("CLOSED")) return null;
                manuallyClosed = true;
                reconnectPending = false;
                status = "CLOSED";
                closedAt = Instant.now();
                closeReason = "已由 TestHub 关闭";
                WebSocketDebugClient.Connection value = connection;
                connection = null;
                return value;
            }
        }

        private int reconnectAttempts() {
            synchronized (monitor) {
                return reconnectAttempts;
            }
        }

        private boolean canDiscard(Instant cutoff) {
            synchronized (monitor) {
                return status.equals("CLOSED") && closedAt != null && closedAt.isBefore(cutoff);
            }
        }

        private String status() {
            synchronized (monitor) {
                return status;
            }
        }

        private SessionSummary summary() {
            synchronized (monitor) {
                String authority = endpoint.getPort() < 0 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort();
                String target = endpoint.getScheme() + "://" + authority + (endpoint.getRawPath() == null || endpoint.getRawPath().isBlank() ? "/" : endpoint.getRawPath());
                return new SessionSummary(id, status, target, messages.size(), createdAt, closedAt, closeReason,
                        reconnect.enabled(), reconnectAttempts, reconnect.maxAttempts(), selectedSubprotocol);
            }
        }

        private List<MessageSummary> messages() {
            synchronized (monitor) {
                return List.copyOf(messages);
            }
        }

        private static ResponseStatusException conflictStatic(String message) {
            return new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }
}
