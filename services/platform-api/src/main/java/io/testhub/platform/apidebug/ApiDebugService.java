package io.testhub.platform.apidebug;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectParameterRepository;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiDebugService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "content-length", "connection", "expect", "upgrade");
    private static final Set<String> SENSITIVE_QUERY_PARTS = Set.of("token", "secret", "password", "apikey", "api-key", "authorization", "signature");
    private final ApiDebugHistoryRepository history;
    private final ApiDebugTransport transport;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectParameterRepository parameters;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final ProjectEnvironmentRepository environments;
    private final ApiDebugTargetPolicy targetPolicy;

    public ApiDebugService(ApiDebugHistoryRepository history, ApiDebugTransport transport, AccessService access, AuditService audit,
                           ProjectParameterRepository parameters, SecretCipher cipher, ObjectMapper json,
                           ProjectEnvironmentRepository environments, ApiDebugTargetPolicy targetPolicy) {
        this.history = history;
        this.transport = transport;
        this.access = access;
        this.audit = audit;
        this.parameters = parameters;
        this.cipher = cipher;
        this.json = json;
        this.environments = environments;
        this.targetPolicy = targetPolicy;
    }

    public List<ApiDebugHistory> history(AuthenticatedPrincipal principal, String projectId, int limit) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        return history.findRecent(projectId, Math.min(Math.max(limit, 1), 100)).stream().map(this::withReplayDraft).toList();
    }

    public DebugResult execute(AuthenticatedPrincipal principal, String projectId, DebugRequest input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolveAndValidate(projectId, input, true);
        long started = System.nanoTime();
        try {
            ApiDebugTransport.TransportResponse response = transport.execute(request.method(), request.url(), request.headers(), request.body(), request.timeoutMs());
            int durationMs = elapsedMs(started);
            ApiDebugHistory item = saved(project, principal, request, input, response.statusCode(), durationMs, response.body().length(), "COMPLETED", null);
            history.insert(item);
            audit.record(principal, project.organizationId(), projectId, "API_DEBUG_EXECUTED", "API_DEBUG_HISTORY", item.id(),
                    Map.of("method", request.method(), "environment", request.environmentCode() == null ? "none" : request.environmentCode(), "status", response.statusCode()));
            return new DebugResult(item.id(), true, response.statusCode(), response.statusText(), durationMs, response.headers(),
                    response.body(), response.truncated(), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(project, principal, request, input, started, "请求已被中断");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            return failed(project, principal, request, input, started, "连接目标服务失败");
        }
    }

    public ApiDebugHistory saveDraft(AuthenticatedPrincipal principal, String projectId, DebugRequest input) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolveAndValidate(projectId, input, true);
        ApiDebugHistory item = saved(project, principal, request, input, null, 0, 0, "DRAFT", null);
        history.insert(item);
        audit.record(principal, project.organizationId(), projectId, "API_DEBUG_DRAFT_SAVED", "API_DEBUG_HISTORY", item.id(),
                Map.of("method", request.method(), "protocol", request.protocol(),
                        "environment", request.environmentCode() == null ? "none" : request.environmentCode()));
        return withReplayDraft(item);
    }

    public LocalExecutionReceipt recordLocalExecution(AuthenticatedPrincipal principal, String projectId,
                                                       DebugRequest input, LocalExecutionResult result) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        ValidatedRequest request = resolveAndValidate(projectId, input, false);
        if (result == null) throw rejected("桌面端执行结果不能为空");
        Integer statusCode = result.statusCode();
        if (result.success() && (statusCode == null || statusCode < 100 || statusCode > 599)) {
            throw rejected("桌面端成功执行结果必须包含合法 HTTP 状态码");
        }
        if (!result.success()) statusCode = null;
        int durationMs = Math.min(Math.max(result.durationMs(), 0), 600_000);
        int responseSize = Math.min(Math.max(result.responseSize(), 0), 10_000_000);
        String error = result.success() ? null : normalizeLocalError(result.errorMessage());
        ApiDebugHistory item = saved(project, principal, request, input, statusCode, durationMs, responseSize,
                result.success() ? "COMPLETED" : "FAILED", error);
        history.insert(item);
        audit.record(principal, project.organizationId(), projectId, "API_DEBUG_EXECUTED_LOCALLY",
                "API_DEBUG_HISTORY", item.id(), Map.of("method", request.method(),
                        "environment", request.environmentCode() == null ? "none" : request.environmentCode(),
                        "status", statusCode == null ? "FAILED" : statusCode));
        return new LocalExecutionReceipt(item.id());
    }

    private DebugResult failed(ProjectSummary project, AuthenticatedPrincipal principal, ValidatedRequest request, DebugRequest input, long started, String message) {
        int durationMs = elapsedMs(started);
        ApiDebugHistory item = saved(project, principal, request, input, null, durationMs, 0, "FAILED", message);
        history.insert(item);
        audit.record(principal, project.organizationId(), project.id(), "API_DEBUG_EXECUTED", "API_DEBUG_HISTORY", item.id(),
                Map.of("method", request.method(), "environment", request.environmentCode() == null ? "none" : request.environmentCode(), "status", "FAILED"));
        return new DebugResult(item.id(), false, null, null, durationMs, List.of(), "", false, message);
    }

    private ApiDebugHistory saved(ProjectSummary project, AuthenticatedPrincipal principal, ValidatedRequest request, DebugRequest input,
                                  Integer status, int durationMs, int responseSize, String result, String error) {
        return new ApiDebugHistory(UlidCreator.getUlid().toString(), project.organizationId(), project.id(), request.method(), request.protocol(),
                request.historyUrl(), status, durationMs, responseSize, result, error, encryptedReplayDraft(request.protocol(), input), principal.userId(), null);
    }

    private ApiDebugHistory withReplayDraft(ApiDebugHistory entry) {
        String draft = entry.replayDraft() == null ? null : cipher.decrypt(entry.replayDraft());
        return new ApiDebugHistory(entry.id(), entry.organizationId(), entry.projectId(), entry.method(), entry.protocol(), entry.requestUrl(),
                entry.responseStatus(), entry.durationMs(), entry.responseSize(), entry.result(), entry.errorMessage(), draft, entry.createdBy(), entry.createdAt(), entry.importSourceProjectId(), entry.importSourceId());
    }

    private String encryptedReplayDraft(String protocol, DebugRequest input) {
        try {
            return cipher.encrypt(json.writeValueAsString(Map.of("protocol", protocol, "request", input)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法加密保存快捷请求回放草稿", exception);
        }
    }

    private ValidatedRequest resolveAndValidate(String projectId, DebugRequest input, boolean enforceNetworkPolicy) {
        if (input == null) throw rejected("调试请求不能为空");
        String environmentCode = normalizeEnvironment(input.environmentCode());
        if (environmentCode != null) {
            var selectedEnvironment = environments.findByCode(projectId, environmentCode)
                    .orElseThrow(() -> rejected("请选择当前项目中已存在的运行环境"));
            if (!selectedEnvironment.enabled()) throw rejected("所选运行环境已停用");
        }
        ResolutionContext variables = variables(projectId, environmentCode);
        ProtocolRequest protocol = protocol(input.protocol(), variables, input.body());
        String method = input.method() == null ? "" : input.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw rejected("不支持的 HTTP 方法");
        if (!protocol.type().equals("HTTP") && !method.equals("POST")) throw rejected(protocol.type() + " 调试仅支持 POST 请求");
        String body = protocol.body();
        if (body.length() > 100_000) throw rejected("请求体不能超过 100000 字符");
        List<HeaderInput> rawHeaders = new ArrayList<>();
        if (input.headers() != null) input.headers().forEach(header -> rawHeaders.add(new HeaderInput(
                header == null ? null : variables.resolve(header.name()), header == null ? null : variables.resolve(header.value()))));
        List<ApiDebugTransport.Header> headers = withProtocolHeaders(validateHeaders(rawHeaders), protocol.headers());
        URI url = normalizedUrl(variables.resolve(input.url()), enforceNetworkPolicy);
        AuthInput auth = input.auth() == null ? new AuthInput("NONE", "", "", "", "", "", "HEADER") : new AuthInput(
                input.auth().type(), variables.resolve(input.auth().token()), variables.resolve(input.auth().username()),
                variables.resolve(input.auth().password()), variables.resolve(input.auth().key()), variables.resolve(input.auth().value()), input.auth().placement());
        AuthApplied applied = applyAuth(url, headers, auth, variables);
        url = normalizedUrl(applied.url().toString(), enforceNetworkPolicy);
        int timeoutMs = input.timeoutMs() == null ? 10_000 : input.timeoutMs();
        if (timeoutMs < 500 || timeoutMs > 10_000) throw rejected("超时时间应在 500 到 10000 毫秒之间");
        return new ValidatedRequest(method, protocol.type(), url, applied.headers(), body, timeoutMs, environmentCode, historyUrl(url, variables.secretValues()));
    }

    private URI normalizedUrl(String value, boolean enforceNetworkPolicy) {
        return enforceNetworkPolicy ? targetPolicy.normalizeAndAssertAllowed(value) : targetPolicy.normalizeWithoutNetworkPolicy(value);
    }

    private String normalizeLocalError(String value) {
        String normalized = value == null || value.isBlank() ? "桌面端连接目标服务失败" : value.trim();
        return normalized.length() <= 1_000 ? normalized : normalized.substring(0, 1_000);
    }

    private ProtocolRequest protocol(ProtocolInput input, ResolutionContext variables, String httpBody) {
        String type = input == null || input.type() == null || input.type().isBlank() ? "HTTP" : input.type().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "HTTP" -> new ProtocolRequest("HTTP", variables.resolve(httpBody), List.of());
            case "GRAPHQL" -> graphql(input, variables);
            case "SOAP" -> soap(input, variables);
            default -> throw rejected("不支持的调试协议");
        };
    }

    private ProtocolRequest graphql(ProtocolInput input, ResolutionContext variables) {
        String query = required(variables.resolve(input.query()), "GraphQL 查询");
        String rawVariables = variables.resolve(input.variables());
        JsonNode variableValues;
        try {
            variableValues = json.readTree(rawVariables == null || rawVariables.isBlank() ? "{}" : rawVariables);
        } catch (JsonProcessingException exception) {
            throw rejected("GraphQL variables 必须是有效 JSON");
        }
        if (!variableValues.isObject()) throw rejected("GraphQL variables 必须是 JSON 对象");
        com.fasterxml.jackson.databind.node.ObjectNode body = json.createObjectNode();
        body.put("query", query);
        body.set("variables", variableValues);
        String operationName = variables.resolve(input.operationName());
        if (!operationName.isBlank()) body.put("operationName", operationName);
        try {
            return new ProtocolRequest("GRAPHQL", json.writeValueAsString(body), List.of(new HeaderInput("Content-Type", "application/json")));
        } catch (JsonProcessingException exception) {
            throw rejected("GraphQL 请求体序列化失败");
        }
    }

    private ProtocolRequest soap(ProtocolInput input, ResolutionContext variables) {
        String envelope = required(variables.resolve(input.envelope()), "SOAP Envelope");
        if (!envelope.trim().startsWith("<") || !envelope.contains("Envelope")) throw rejected("SOAP Envelope 格式不合法");
        List<HeaderInput> headers = new ArrayList<>();
        headers.add(new HeaderInput("Content-Type", "text/xml; charset=utf-8"));
        String action = variables.resolve(input.soapAction());
        if (!action.isBlank()) headers.add(new HeaderInput("SOAPAction", action));
        return new ProtocolRequest("SOAP", envelope, headers);
    }

    private List<ApiDebugTransport.Header> withProtocolHeaders(List<ApiDebugTransport.Header> headers, List<HeaderInput> defaults) {
        List<ApiDebugTransport.Header> result = new ArrayList<>(headers);
        for (HeaderInput header : defaults) {
            if (header.name().equalsIgnoreCase("Content-Type")) {
                result.removeIf(item -> item.name().equalsIgnoreCase("Content-Type"));
                result.addAll(validateHeaders(List.of(header)));
            } else if (result.stream().noneMatch(item -> item.name().equalsIgnoreCase(header.name()))) {
                result.addAll(validateHeaders(List.of(header)));
            }
        }
        return result;
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

    private AuthApplied applyAuth(URI url, List<ApiDebugTransport.Header> headers, AuthInput auth, ResolutionContext variables) {
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
                variables.markSensitive(value);
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

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        return value;
    }

    private String normalizeEnvironment(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw rejected("环境标识格式不合法");
        return normalized;
    }

    private List<ApiDebugTransport.Header> validateHeaders(List<HeaderInput> values) {
        if (values == null) return List.of();
        if (values.size() > 48) throw rejected("请求头不能超过 48 项");
        List<ApiDebugTransport.Header> headers = new ArrayList<>();
        for (HeaderInput value : values) {
            String name = value == null || value.name() == null ? "" : value.name().trim();
            String headerValue = value == null || value.value() == null ? "" : value.value();
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,200}") || headerValue.length() > 16_000
                    || headerValue.contains("\r") || headerValue.contains("\n")) throw rejected("请求头格式不合法");
            if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) throw rejected("不允许设置请求头：" + name);
            headers.add(new ApiDebugTransport.Header(name, headerValue));
        }
        return headers;
    }

    private String redactedUrl(URI url) {
        if (url.getRawQuery() == null || url.getRawQuery().isBlank()) return url.toString();
        List<String> parts = new ArrayList<>();
        for (String item : url.getRawQuery().split("&")) {
            String[] pair = item.split("=", 2);
            String key = pair[0];
            String decoded = java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            boolean sensitive = SENSITIVE_QUERY_PARTS.stream().anyMatch(decoded::contains);
            parts.add(key + (pair.length == 1 ? "" : "=" + (sensitive ? "***" : pair[1])));
        }
        String authority = url.getPort() < 0 ? url.getHost() : url.getHost() + ":" + url.getPort();
        return url.getScheme() + "://" + authority + (url.getRawPath() == null ? "/" : url.getRawPath()) + "?" + String.join("&", parts);
    }

    private String historyUrl(URI url, Set<String> secretValues) {
        String value = redactedUrl(url);
        for (String secret : secretValues) {
            if (secret == null || secret.isBlank()) continue;
            value = value.replace(secret, "***");
            value = value.replace(URLEncoder.encode(secret, StandardCharsets.UTF_8), "***");
        }
        return value;
    }

    private int elapsedMs(long started) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record HeaderInput(String name, String value) {
    }
    public record AuthInput(String type, String token, String username, String password, String key, String value, String placement) {
    }
    public record ProtocolInput(String type, String query, String variables, String operationName, String envelope, String soapAction) {
    }
    public record DebugRequest(String method, String url, List<HeaderInput> headers, String body, Integer timeoutMs,
                               String environmentCode, AuthInput auth, ProtocolInput protocol, String name) {
    }
    public record DebugResult(String historyId, boolean success, Integer statusCode, String statusText, int durationMs,
                              List<ApiDebugTransport.Header> responseHeaders, String body, boolean truncated, String errorMessage) {
    }
    public record LocalExecutionResult(boolean success, Integer statusCode, int durationMs, int responseSize,
                                       String errorMessage) {
    }
    public record LocalExecutionReceipt(String historyId) {
    }
    private record ValidatedRequest(String method, String protocol, URI url, List<ApiDebugTransport.Header> headers, String body, int timeoutMs,
                                    String environmentCode, String historyUrl) {
    }
    private record AuthApplied(URI url, List<ApiDebugTransport.Header> headers) {
    }
    private record ProtocolRequest(String type, String body, List<HeaderInput> headers) {
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
                    if (variable.secret()) markSensitive(replacement);
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

        private void markSensitive(String value) {
            if (value != null && !value.isBlank()) secretValues.add(value);
        }

        private Set<String> secretValues() {
            return Set.copyOf(secretValues);
        }
    }
}
