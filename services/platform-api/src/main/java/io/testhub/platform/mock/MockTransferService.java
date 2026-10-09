package io.testhub.platform.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicit one-time copy. New public keys and revoked share copies prevent accidental publication. */
@Service
public class MockTransferService {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final MockRepository repository;
    private final MockService mocks;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MockTransferService(AccessService access, MockRepository repository, MockService mocks,
                               AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.repository = repository; this.mocks = mocks;
        this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    public Map<String, Object> capabilities(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        return Map.of("version", 1, "scope", "MOCK", "maxBytes", MAX_BYTES,
                "includes", List.of("servers", "rules", "shareMetadata"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        List<Server> servers = new ArrayList<>();
        for (MockServer server : repository.findServers(projectId)) {
            List<Rule> rules = repository.findRules(projectId, server.id()).stream()
                    .map(rule -> new Rule(rule.id(), rule.name(), rule.httpMethod(), rule.path(), rule.priority(),
                            rule.requestQuery(), rule.requestHeaders(), rule.requestBodyMatch(), rule.requestBody(),
                            rule.responseStatus(), rule.responseHeaders(), rule.responseBody(), rule.enabled())).toList();
            List<Share> shares = repository.findShares(projectId, server.id()).stream()
                    .map(share -> new Share(share.id(), share.name(), share.expiresAt(), share.revokedAt())).toList();
            servers.add(new Server(server.id(), server.name(), server.enabled(), server.smartExampleEnabled(), rules, shares));
        }
        Snapshot snapshot = new Snapshot(1, projectId, servers);
        validate(snapshot);
        encoded(snapshot);
        return snapshot;
    }

    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BYTES) throw invalid("Mock 迁移请求不能为空或超过 32 MiB");
        try {
            ImportRequest request = json.readerFor(ImportRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("Mock 迁移资料不能为空");
            return request;
        } catch (java.io.IOException error) {
            throw invalid("Mock 迁移请求 JSON 格式不合法或包含不支持的字段");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (request == null || request.requestKey() == null ||
                !request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw invalid("requestKey 必须是 UUID");
        String requestKey;
        try { requestKey = UUID.fromString(request.requestKey()).toString(); }
        catch (IllegalArgumentException error) { throw invalid("requestKey 必须是 UUID"); }
        if (!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览迁移");
        Snapshot source = request.snapshot();
        byte[] payload = encoded(source);
        String hash = sha256(payload);
        repository.lockProject(projectId);
        var previous = jdbc.sql("""
                SELECT payload_sha256, result_json FROM mock_transfer_receipt
                WHERE project_id = :projectId AND requested_by = :userId AND request_key = :requestKey FOR UPDATE
                """).param("projectId", projectId).param("userId", principal.userId()).param("requestKey", requestKey)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (previous.isPresent()) {
            if (!previous.get().getKey().equals(hash)) throw conflict("该 requestKey 已用于其他迁移资料");
            try { return json.readValue(previous.get().getValue(), Receipt.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Mock 迁移回执损坏", error); }
        }
        validate(source);
        if (source.servers().isEmpty()) throw invalid("本地项目没有可迁移的 Mock 服务");
        List<MockServer> existing = repository.findServers(projectId);
        if (existing.size() + source.servers().size() > 20) throw conflict("目标项目 Mock 服务总数将超过 20 个");
        Set<String> names = new HashSet<>();
        for (MockServer server : existing) names.add(server.name());
        for (Server server : source.servers()) if (!names.add(server.name())) throw conflict("目标项目存在同名 Mock 服务：" + server.name());
        int rules = 0, shares = 0;
        try {
            for (Server sourceServer : source.servers()) {
                MockServer created = mocks.createServer(principal, projectId, new MockService.ServerInput(sourceServer.name()));
                for (Rule rule : sourceServer.rules()) {
                    MockRule copied = mocks.createRule(principal, projectId, created.id(),
                            new MockService.RuleInput(rule.name(), rule.httpMethod(), rule.path(), rule.priority(),
                                    rule.requestQuery(), rule.requestHeaders(), rule.requestBodyMatch(), rule.requestBody(),
                                    rule.responseStatus(), rule.responseHeaders(), rule.responseBody()));
                    if (!rule.enabled()) repository.updateRuleEnabled(projectId, created.id(), copied.id(), false);
                    rules++;
                }
                if (sourceServer.smartExampleEnabled()) mocks.setSmartExampleEnabled(principal, projectId, created.id(), true);
                // Share metadata is retained for review, but no imported URL becomes publicly usable.
                for (Share share : sourceServer.shares()) {
                    String shareId = UlidCreator.getUlid().toString();
                    repository.insertShare(shareId, created.id(), project.organizationId(), projectId,
                            UlidCreator.getUlid().toString(), share.name(), share.expiresAt(), principal.userId());
                    repository.revokeShare(projectId, created.id(), shareId);
                    shares++;
                }
                mocks.setServerEnabled(principal, projectId, created.id(), false);
            }
            Receipt receipt = new Receipt(UlidCreator.getUlid().toString(), source.servers().size(), rules, shares);
            jdbc.sql("""
                    INSERT INTO mock_transfer_receipt
                        (id, organization_id, project_id, requested_by, request_key, payload_sha256, result_json)
                    VALUES (:id, :organizationId, :projectId, :userId, :requestKey, :hash, :result)
                    """).param("id", receipt.id()).param("organizationId", project.organizationId())
                    .param("projectId", projectId).param("userId", principal.userId()).param("requestKey", requestKey)
                    .param("hash", hash).param("result", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
            audit.record(principal, project.organizationId(), projectId, "MOCK_TRANSFERRED", "MOCK_TRANSFER",
                    receipt.id(), Map.of("servers", receipt.servers(), "rules", receipt.rules(), "revokedShares", receipt.shares()));
            return receipt;
        } catch (DataIntegrityViolationException error) {
            throw conflict("Mock 资料与目标项目冲突，未写入任何迁移数据");
        }
    }

    private void validate(Snapshot source) {
        if (source == null || source.version() != 1 || !validId(source.sourceProjectId()) || source.servers() == null
                || source.servers().size() > 20 || source.servers().stream().anyMatch(java.util.Objects::isNull))
            throw invalid("Mock 迁移协议、来源项目或服务数量无效");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Server server : source.servers()) {
            unique(ids, server.id());
            if (server.name() == null || server.name().isBlank() || server.name().length() > 160 || !names.add(server.name()))
                throw invalid("Mock 服务名称无效或重复");
            if (server.rules() == null || server.rules().size() > 200 || server.rules().stream().anyMatch(java.util.Objects::isNull)
                    || server.shares() == null || server.shares().size() > 20 || server.shares().stream().anyMatch(java.util.Objects::isNull))
                throw invalid("Mock 规则或分享数量超出上限");
            for (Rule rule : server.rules()) {
                unique(ids, rule.id());
                if (rule.name() == null || rule.httpMethod() == null || rule.path() == null || rule.requestQuery() == null
                        || rule.requestHeaders() == null || rule.requestBodyMatch() == null || rule.responseHeaders() == null
                        || rule.responseBody() == null) throw invalid("Mock 规则内容不完整");
            }
            for (Share share : server.shares()) {
                unique(ids, share.id());
                if (share.name() == null || share.name().isBlank() || share.name().length() > 120)
                    throw invalid("Mock 分享名称无效");
            }
        }
    }

    private void unique(Set<String> ids, String id) {
        if (!validId(id) || !ids.add(id)) throw invalid("Mock 资产标识无效或重复");
    }

    private boolean validId(String id) { return id != null && id.matches("[A-Z0-9]{26}"); }

    private byte[] encoded(Object value) {
        try {
            byte[] bytes = json.writeValueAsBytes(value);
            if (bytes.length > MAX_BYTES) throw invalid("Mock 迁移资料不能超过 32 MiB");
            return bytes;
        } catch (JsonProcessingException error) { throw invalid("Mock 迁移资料无法序列化"); }
    }

    private String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Snapshot(int version, String sourceProjectId, List<Server> servers) { }
    public record Server(String id, String name, boolean enabled, boolean smartExampleEnabled,
                         List<Rule> rules, List<Share> shares) { }
    public record Rule(String id, String name, String httpMethod, String path, int priority,
                       Map<String, String> requestQuery, Map<String, String> requestHeaders, String requestBodyMatch,
                       String requestBody, int responseStatus, Map<String, String> responseHeaders,
                       String responseBody, boolean enabled) { }
    public record Share(String id, String name, Instant expiresAt, Instant revokedAt) { }
    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    public record Receipt(String id, int servers, int rules, int shares) { }
}
