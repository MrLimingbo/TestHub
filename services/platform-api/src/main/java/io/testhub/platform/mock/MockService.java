package io.testhub.platform.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.apidesign.ApiDesignService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MockService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> BODY_MATCHES = Set.of("ANY", "EXACT", "JSON_SUBSET");
    private static final Set<String> RESERVED_RESPONSE_HEADERS = Set.of("connection", "content-length", "keep-alive", "set-cookie",
            "te", "trailer", "transfer-encoding", "upgrade", "x-request-id");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final int MAX_SERVERS_PER_PROJECT = 20;
    private static final int MAX_RULES_PER_SERVER = 200;
    private static final int MAX_SHARES_PER_SERVER = 20;
    private static final int MAX_PRIORITY = 1_000;
    private static final int MAX_REQUEST_BODY = 512 * 1024;
    private final MockRepository repository;
    private final AccessService access;
    private final AuditService audit;
    private final ApiDesignService apiDesign;
    private final ObjectMapper json;

    public MockService(MockRepository repository, AccessService access, AuditService audit, ApiDesignService apiDesign, ObjectMapper json) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.apiDesign = apiDesign;
        this.json = json;
    }

    public List<MockServer> servers(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return repository.findServers(projectId);
    }

    public MockServer createServer(AuthenticatedPrincipal principal, String projectId, ServerInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (repository.countServers(projectId) >= MAX_SERVERS_PER_PROJECT) {
            throw conflict("每个项目最多创建 20 个 Mock 服务");
        }
        String name = required(input == null ? null : input.name(), "Mock 服务名称", 160);
        String id = UlidCreator.getUlid().toString();
        repository.insertServer(id, project.organizationId(), projectId, UlidCreator.getUlid().toString(), name, principal.userId());
        MockServer result = requireServer(projectId, id);
        audit.record(principal, project.organizationId(), projectId, "MOCK_SERVER_CREATED", "MOCK_SERVER", id,
                Map.of("name", result.name(), "enabled", result.enabled()));
        return result;
    }

    public MockServer setServerEnabled(AuthenticatedPrincipal principal, String projectId, String serverId, boolean enabled) {
        ProjectSummary project = writeAccess(principal, projectId);
        MockServer server = requireServer(projectId, serverId);
        if (server.enabled() == enabled) return server;
        repository.updateServerEnabled(projectId, serverId, enabled);
        MockServer result = requireServer(projectId, serverId);
        audit.record(principal, project.organizationId(), projectId, "MOCK_SERVER_UPDATED", "MOCK_SERVER", serverId,
                Map.of("enabled", enabled));
        return result;
    }

    public MockServer setSmartExampleEnabled(AuthenticatedPrincipal principal, String projectId, String serverId, boolean enabled) {
        ProjectSummary project = writeAccess(principal, projectId);
        MockServer server = requireServer(projectId, serverId);
        if (server.smartExampleEnabled() == enabled) return server;
        repository.updateSmartExampleEnabled(projectId, serverId, enabled);
        MockServer result = requireServer(projectId, serverId);
        audit.record(principal, project.organizationId(), projectId, "MOCK_SMART_EXAMPLE_UPDATED", "MOCK_SERVER", serverId,
                Map.of("enabled", enabled));
        return result;
    }

    public List<SmartMockExample> smartExamples(AuthenticatedPrincipal principal, String projectId, String serverId) {
        access.requireProjectRead(principal, projectId);
        requireServer(projectId, serverId);
        return apiDesign.publishedMockExamples(principal, projectId).stream().map(this::smartExample).toList();
    }

    public List<MockShare> shares(AuthenticatedPrincipal principal, String projectId, String serverId) {
        access.requireProjectRead(principal, projectId);
        requireServer(projectId, serverId);
        return repository.findShares(projectId, serverId);
    }

    public MockShare createShare(AuthenticatedPrincipal principal, String projectId, String serverId, ShareInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireServer(projectId, serverId);
        if (repository.countShares(serverId) >= MAX_SHARES_PER_SERVER) {
            throw conflict("每个 Mock 服务最多创建 20 条分享链接");
        }
        if (input == null) throw rejected("分享链接参数不能为空");
        String name = required(input.name(), "分享名称", 120);
        Integer expiresInDays = input.expiresInDays();
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 365)) {
            throw rejected("分享有效期应在 1 到 365 天之间");
        }
        Instant expiresAt = expiresInDays == null ? null : Instant.now().plus(expiresInDays, ChronoUnit.DAYS);
        String id = UlidCreator.getUlid().toString();
        repository.insertShare(id, serverId, project.organizationId(), projectId, UlidCreator.getUlid().toString(), name, expiresAt,
                principal.userId());
        MockShare result = requireShare(projectId, serverId, id);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("serverId", serverId);
        details.put("name", name);
        if (expiresAt != null) details.put("expiresAt", expiresAt.toString());
        audit.record(principal, project.organizationId(), projectId, "MOCK_SHARE_CREATED", "MOCK_SHARE", id, details);
        return result;
    }

    public MockShare revokeShare(AuthenticatedPrincipal principal, String projectId, String serverId, String shareId) {
        ProjectSummary project = writeAccess(principal, projectId);
        MockShare share = requireShare(projectId, serverId, shareId);
        if (share.revokedAt() != null) return share;
        repository.revokeShare(projectId, serverId, shareId);
        MockShare result = requireShare(projectId, serverId, shareId);
        audit.record(principal, project.organizationId(), projectId, "MOCK_SHARE_REVOKED", "MOCK_SHARE", shareId,
                Map.of("serverId", serverId, "name", share.name()));
        return result;
    }

    public MockShareDocument publicShare(String shareKey) {
        if (shareKey == null || !shareKey.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw shareNotFound();
        PublicMockShare share = repository.findActiveShare(shareKey).orElseThrow(this::shareNotFound);
        List<MockShareDocument.RuleSummary> rules = repository.findSharedRules(share.serverId()).stream()
                .map(rule -> new MockShareDocument.RuleSummary(rule.name(), rule.httpMethod(), rule.path(), rule.responseStatus(),
                        matchSummary(rule))).toList();
        return new MockShareDocument(share.shareKey(), share.shareName(), share.serverName(), share.publicKey(), share.serverEnabled(),
                share.smartExampleEnabled(), share.expiresAt(), rules);
    }

    public List<MockRule> rules(AuthenticatedPrincipal principal, String projectId, String serverId) {
        access.requireProjectRead(principal, projectId);
        requireServer(projectId, serverId);
        return repository.findRules(projectId, serverId);
    }

    public MockRule createRule(AuthenticatedPrincipal principal, String projectId, String serverId, RuleInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireServer(projectId, serverId);
        if (repository.countRules(serverId) >= MAX_RULES_PER_SERVER) {
            throw conflict("每个 Mock 服务最多创建 200 条规则");
        }
        if (input == null) throw rejected("Mock 规则不能为空");
        String name = required(input.name(), "规则名称", 160);
        String method = method(input.httpMethod());
        String path = path(input.path());
        int priority = input.priority() == null ? 0 : input.priority();
        if (priority < 0 || priority > MAX_PRIORITY) throw rejected("Mock 规则优先级应在 0 到 1000 之间");
        Map<String, String> requestQuery = queryConditions(input.requestQuery());
        Map<String, String> requestHeaders = requestHeaderConditions(input.requestHeaders());
        String requestBodyMatch = bodyMatch(input.requestBodyMatch());
        String requestBody = requestBody(requestBodyMatch, input.requestBody());
        int responseStatus = input.responseStatus() == null ? 200 : input.responseStatus();
        if (responseStatus < 200 || responseStatus > 599) throw rejected("Mock 响应状态码应在 200 到 599 之间");
        String body = input.responseBody() == null ? "" : input.responseBody();
        if (body.length() > 512 * 1024) throw rejected("Mock 响应体不能超过 512 KB");
        Map<String, String> headers = responseHeaders(input.responseHeaders());
        String id = UlidCreator.getUlid().toString();
        repository.insertRule(id, serverId, project.organizationId(), projectId, name, method, path, priority, mapJson(requestQuery),
                mapJson(requestHeaders), requestBodyMatch, requestBody, responseStatus, mapJson(headers), body, principal.userId());
        MockRule result = repository.findRules(projectId, serverId).stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow();
        audit.record(principal, project.organizationId(), projectId, "MOCK_RULE_CREATED", "MOCK_RULE", id,
                Map.of("serverId", serverId, "name", name, "method", method, "path", path, "priority", priority,
                        "bodyMatch", requestBodyMatch, "status", responseStatus));
        return result;
    }

    public Optional<MockRuntimeResponse> runtime(String publicKey, MockRuntimeRequest request) {
        if (publicKey == null || !publicKey.matches("[0-9A-HJKMNP-TV-Z]{26}")) return Optional.empty();
        if (request == null || request.body().length() > MAX_REQUEST_BODY) return Optional.empty();
        MockServer server = repository.findRuntimeServer(publicKey).orElse(null);
        if (server == null) return Optional.empty();
        String path = runtimePath(request.path());
        Optional<MockRuntimeResponse> manual = repository.findRuntimeRules(publicKey, request.method(), path).stream()
                .filter(rule -> matches(rule, request))
                .findFirst()
                .map(rule -> new MockRuntimeResponse(rule.responseStatus(), rule.responseHeaders(), rule.responseBody()));
        if (manual.isPresent() || !server.smartExampleEnabled()) return manual;
        return apiDesign.runtimeMockExample(server.projectId(), request.method(), path).map(this::smartResponse);
    }

    private SmartMockExample smartExample(ApiDesignService.MockExample example) {
        String preview = example.responseBody().replaceAll("\\s+", " ").trim();
        if (preview.length() > 240) preview = preview.substring(0, 240) + "…";
        return new SmartMockExample(example.apiId(), example.name(), example.method(), example.path(), example.versionName(),
                example.responseStatus(), preview);
    }

    private MockRuntimeResponse smartResponse(ApiDesignService.MockExample example) {
        String contentType = jsonBody(example.responseBody()) ? "application/json; charset=utf-8" : "text/plain; charset=utf-8";
        return new MockRuntimeResponse(example.responseStatus(), Map.of("Content-Type", contentType,
                "X-TestHub-Mock-Source", "api-example", "X-TestHub-Api-Version", example.versionName()), example.responseBody());
    }

    private String matchSummary(SharedMockRule rule) {
        List<String> conditions = new ArrayList<>();
        conditions.add("优先级 " + rule.priority());
        if (!rule.requestQuery().isEmpty()) conditions.add(rule.requestQuery().size() + " 个参数");
        if (!rule.requestHeaders().isEmpty()) conditions.add(rule.requestHeaders().size() + " 个请求头");
        if (!"ANY".equals(rule.requestBodyMatch())) {
            conditions.add("EXACT".equals(rule.requestBodyMatch()) ? "请求体精确匹配" : "JSON 子集匹配");
        }
        return String.join(" · ", conditions);
    }

    private boolean jsonBody(String body) {
        try {
            json.readTree(body);
            return true;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private MockServer requireServer(String projectId, String serverId) {
        return repository.findServer(projectId, serverId).orElseThrow(() -> rejected("Mock 服务不存在"));
    }

    private MockShare requireShare(String projectId, String serverId, String shareId) {
        return repository.findShare(projectId, serverId, shareId).orElseThrow(() -> rejected("Mock 分享链接不存在"));
    }

    private String required(String value, String label, int maximum) {
        if (value == null || value.isBlank()) throw rejected(label + "不能为空");
        String normalized = value.trim();
        if (normalized.length() > maximum) throw rejected(label + "不能超过 " + maximum + " 个字符");
        return normalized;
    }

    private String method(String value) {
        if (value == null || value.isBlank()) throw rejected("Mock 请求方法不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(normalized)) throw rejected("不支持的 Mock 请求方法");
        return normalized;
    }

    private String path(String value) {
        if (value == null || value.isBlank()) throw rejected("Mock 路径不能为空");
        String normalized = value.trim();
        if (!normalized.startsWith("/") || normalized.length() > 700 || normalized.contains("?") || normalized.contains("#")
                || normalized.contains("\\") || normalized.contains("//") || normalized.contains("..")) {
            throw rejected("Mock 路径应以 / 开头，且不能包含查询、片段或相对路径");
        }
        return normalized;
    }

    private String runtimePath(String value) {
        if (value == null || value.isBlank()) return "/";
        return path(value);
    }

    private Map<String, String> responseHeaders(Map<String, String> input) {
        Map<String, String> source = input == null ? Map.of() : input;
        if (source.size() > 32) throw rejected("Mock 响应头最多支持 32 项");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!HEADER_NAME.matcher(name).matches() || RESERVED_RESPONSE_HEADERS.contains(normalized)) {
                throw rejected("Mock 响应头名称不合法或受保护：" + name);
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.length() > 8192 || value.contains("\r") || value.contains("\n")) throw rejected("Mock 响应头值不合法");
            if (result.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) throw rejected("Mock 响应头不能重复：" + name);
            result.put(name, value);
        }
        if (result.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            result.put("Content-Type", "application/json; charset=utf-8");
        }
        return Map.copyOf(result);
    }

    private Map<String, String> queryConditions(Map<String, String> input) {
        return conditions(input, "请求参数", false);
    }

    private Map<String, String> requestHeaderConditions(Map<String, String> input) {
        Map<String, String> result = conditions(input, "请求头", true);
        Map<String, String> normalized = new LinkedHashMap<>();
        result.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
        return Map.copyOf(normalized);
    }

    private Map<String, String> conditions(Map<String, String> input, String label, boolean header) {
        Map<String, String> source = input == null ? Map.of() : input;
        if (source.size() > 32) throw rejected(label + "最多支持 32 项");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isBlank() || name.length() > 128 || (header && !HEADER_NAME.matcher(name).matches())) {
                throw rejected(label + "名称不合法：" + name);
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.length() > 8192 || value.contains("\r") || value.contains("\n")) throw rejected(label + "值不合法：" + name);
            boolean duplicate = header
                    ? result.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))
                    : result.containsKey(name);
            if (duplicate) throw rejected(label + "名称不能重复：" + name);
            result.put(name, value);
        }
        return Map.copyOf(result);
    }

    private String bodyMatch(String value) {
        String normalized = value == null || value.isBlank() ? "ANY" : value.trim().toUpperCase(Locale.ROOT);
        if (!BODY_MATCHES.contains(normalized)) throw rejected("不支持的请求体匹配方式");
        return normalized;
    }

    private String requestBody(String match, String value) {
        String body = value == null ? "" : value;
        if (body.length() > MAX_REQUEST_BODY) throw rejected("Mock 请求体条件不能超过 512 KB");
        if ("ANY".equals(match)) {
            if (!body.isBlank()) throw rejected("任意请求体匹配不能填写请求体条件");
            return "";
        }
        if (body.isBlank()) throw rejected("当前请求体匹配方式需要填写请求体条件");
        if ("JSON_SUBSET".equals(match)) {
            try {
                json.readTree(body);
            } catch (JsonProcessingException exception) {
                throw rejected("JSON 子集匹配的请求体条件必须是合法 JSON");
            }
        }
        return body;
    }

    private boolean matches(MockRule rule, MockRuntimeRequest request) {
        for (Map.Entry<String, String> condition : rule.requestQuery().entrySet()) {
            List<String> values = request.query().get(condition.getKey());
            if (values == null || !values.contains(condition.getValue())) return false;
        }
        for (Map.Entry<String, String> condition : rule.requestHeaders().entrySet()) {
            if (!condition.getValue().equals(request.headers().get(condition.getKey().toLowerCase(Locale.ROOT)))) return false;
        }
        return matchesBody(rule, request.body());
    }

    private boolean matchesBody(MockRule rule, String actual) {
        return switch (rule.requestBodyMatch()) {
            case "ANY" -> true;
            case "EXACT" -> rule.requestBody().equals(actual);
            case "JSON_SUBSET" -> jsonSubset(rule.requestBody(), actual);
            default -> false;
        };
    }

    private boolean jsonSubset(String expectedSource, String actualSource) {
        try {
            return jsonSubset(json.readTree(expectedSource), json.readTree(actualSource));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private boolean jsonSubset(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isObject()) {
            if (!actual.isObject()) return false;
            var fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!actual.has(field.getKey()) || !jsonSubset(field.getValue(), actual.get(field.getKey()))) return false;
            }
            return true;
        }
        if (expected.isArray()) return expected.equals(actual);
        return expected.equals(actual);
    }

    private String mapJson(Map<String, String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw rejected("Mock 规则条件无法序列化");
        }
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException shareNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "分享链接不存在、已撤销或已过期");
    }

    public record ServerInput(String name) {
    }

    public record ShareInput(String name, Integer expiresInDays) {
    }

    public record RuleInput(String name, String httpMethod, String path, Integer priority, Map<String, String> requestQuery,
                            Map<String, String> requestHeaders, String requestBodyMatch, String requestBody, Integer responseStatus,
                            Map<String, String> responseHeaders, String responseBody) {
        public RuleInput {
            requestQuery = requestQuery == null ? Map.of() : Map.copyOf(requestQuery);
            requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
            responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        }
    }
}
