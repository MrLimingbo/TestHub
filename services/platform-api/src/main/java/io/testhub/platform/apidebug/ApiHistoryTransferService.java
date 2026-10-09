package io.testhub.platform.apidebug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiHistoryTransferService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> PROTOCOLS = Set.of("HTTP", "GRAPHQL", "SOAP", "WEBSOCKET", "GRPC");
    private final ApiDebugHistoryRepository history;
    private final AccessService access;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final AuditService audit;
    public ApiHistoryTransferService(ApiDebugHistoryRepository history, AccessService access, SecretCipher cipher, ObjectMapper json, AuditService audit) {
        this.history = history; this.access = access; this.cipher = cipher; this.json = json; this.audit = audit;
    }
    public record Item(String id, String method, String protocol, String requestUrl, Integer responseStatus,
                       int durationMs, int responseSize, String result, String errorMessage, JsonNode replayDraft, Instant createdAt) { }
    @Transactional(readOnly = true)
    public List<Item> export(AuthenticatedPrincipal principal, String projectId) {
        // History exposes replay credentials: retain the stricter existing history permission.
        access.requireProjectParameterWrite(principal, projectId, WRITERS);
        var size = history.transferSize(projectId);
        if (size.count() > 5000 || size.storedBytes() > 8 * 1024 * 1024) throw invalid("历史超过单次 5000 条或存储估算 8 MiB 上限，未导出；不会静默截断。");
        var records = history.findRecent(projectId, 5001);
        if (records.size() > 5000) throw invalid("历史超过单次 5000 条上限，未导出；不会静默截断。");
        List<Item> result = new ArrayList<>();
        for (var h : records) {
            JsonNode draft = null;
            if (h.replayDraft() != null) {
                try { draft = json.readTree(cipher.decrypt(h.replayDraft())); }
                catch (Exception error) { throw new ResponseStatusException(HttpStatus.CONFLICT, "历史回放草稿无法解密或解析，未导出。请检查工作区密钥和数据完整性。"); }
            }
            result.add(new Item(h.id(), h.method(), h.protocol(), h.requestUrl(), h.responseStatus(), h.durationMs(), h.responseSize(), h.result(), h.errorMessage(), draft, h.createdAt()));
        }
        validate(result, new HashSet<>()); return result;
    }
    public void validate(List<Item> items, Set<String> ids) {
        if (items == null || items.size() > 5000) throw invalid("历史范围无效或超过 5000 条。");
        for (var h : items) {
            if (h == null || h.id() == null || !h.id().matches("[0-9A-HJKMNP-TV-Z]{26}") || !ids.add(h.id())) throw invalid("历史 ID 无效或重复。");
            text(h.method(), 12, true); text(h.requestUrl(), 2000, true); text(h.errorMessage(), 1000, false);
            if (!PROTOCOLS.contains(Objects.toString(h.protocol(), "")) || !Set.of("DRAFT", "COMPLETED", "FAILED").contains(Objects.toString(h.result(), ""))
                    || h.durationMs() < 0 || h.responseSize() < 0 || (h.responseStatus() != null && (h.responseStatus() < 0 || h.responseStatus() > 599))) throw invalid("历史协议、结果或计量字段无效。");
            if (h.createdAt() == null || h.createdAt().isBefore(Instant.parse("1970-01-01T00:00:01Z"))
                    || h.createdAt().isAfter(Instant.parse("2038-01-19T03:14:07Z")) || h.createdAt().getNano() % 1_000_000 != 0) throw invalid("历史时间无效。");
            JsonNode draft = h.replayDraft();
            if (draft != null && !draft.isNull()) {
                if (!draft.isObject() || !h.protocol().equals(draft.path("protocol").asText()) || !draft.path("request").isObject()
                        || draft.toString().length() > 2_000_000) throw invalid("历史回放草稿格式或大小无效。");
            }
        }
    }
    @Transactional
    public void transfer(AuthenticatedPrincipal principal, String projectId, String sourceProjectId, List<Item> items) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        validate(items, new HashSet<>());
        for (var h : items) {
            String id = UlidCreator.getUlid().toString();
            String draft = h.replayDraft() == null || h.replayDraft().isNull() ? null : cipher.encrypt(h.replayDraft().toString());
            history.insert(new ApiDebugHistory(id, project.organizationId(), projectId, h.method(), h.protocol(), h.requestUrl(), h.responseStatus(),
                    h.durationMs(), h.responseSize(), h.result(), h.errorMessage(), draft, principal.userId(), h.createdAt(), sourceProjectId, h.id()));
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEBUG_HISTORY_TRANSFERRED", "PROJECT", projectId, Map.of("count", items.size()));
    }
    private void text(String value, int max, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > max)) throw invalid("历史文本字段无效或超过限制。");
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
