package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ApiTokenPrincipal;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiWorkspacePreferencesService {
    public static final int MAX_BODY_BYTES = 262_144;
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private final ApiWorkspacePreferencesRepository repository;
    private final AccessService access;
    private final ObjectMapper mapper;

    public ApiWorkspacePreferencesService(ApiWorkspacePreferencesRepository repository, AccessService access,
                                          ObjectMapper mapper) {
        this.repository = repository;
        this.access = access;
        this.mapper = mapper;
    }

    public record Preferences(long revision, JsonNode state) { }

    public Preferences get(AuthenticatedPrincipal principal, String projectId) {
        var project = access.requireProjectRead(principal, projectId);
        return repository.find(project.organizationId(), projectId, principal.userId())
                .map(stored -> new Preferences(stored.revision(), storedState(stored.state())))
                .orElseGet(() -> new Preferences(0, defaultState()));
    }

    private JsonNode defaultState() {
        var state = mapper.createObjectNode();
        state.putArray("folders");
        state.putArray("hiddenIds");
        state.putObject("folderIds");
        state.put("displayMode", "NAME");
        return state;
    }

    public Preferences put(AuthenticatedPrincipal principal, String projectId, byte[] body) {
        var project = access.requireProjectRead(principal, projectId);
        if (principal instanceof ApiTokenPrincipal token && !token.scopes().contains("project:write")) {
            throw new org.springframework.security.access.AccessDeniedException("令牌没有项目写入权限范围");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "接口工作区偏好请求不能超过 256 KiB");
        }
        JsonNode input;
        try {
            input = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (IOException exception) {
            throw badRequest("请求必须为合法 JSON，且不能包含重复字段");
        }
        fields(input, Set.of("revision", "state"), Set.of("revision", "state"));
        JsonNode revisionNode = input.get("revision");
        if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong()
                || revisionNode.longValue() < 0 || revisionNode.longValue() >= MAX_REVISION) {
            throw badRequest("revision 必须为 0 到 9007199254740990 的整数");
        }
        long revision = revisionNode.longValue();
        JsonNode state = input.get("state");
        validateState(state);
        if (!repository.compareAndSet(project.organizationId(), projectId, principal.userId(), revision, state.toString())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接口工作区偏好已更新，请重新读取后再保存");
        }
        return new Preferences(revision + 1, state);
    }

    private void validateState(JsonNode state) {
        fields(state, Set.of("folders", "hiddenIds", "folderIds", "displayMode"),
                Set.of("folders", "hiddenIds", "folderIds", "displayMode"));
        JsonNode folders = state.get("folders"), hidden = state.get("hiddenIds"), links = state.get("folderIds");
        if (!folders.isArray() || folders.size() > 500 || !hidden.isArray() || hidden.size() > 2_000
                || !links.isObject() || links.size() > 2_000) {
            throw badRequest("folders 最多 500 项，hiddenIds 和 folderIds 最多各 2000 项，且必须使用约定类型");
        }
        String mode = text(state.get("displayMode"), 4, "displayMode");
        if (!Set.of("NAME", "URL").contains(mode)) throw badRequest("displayMode 仅支持 NAME 或 URL");
        Map<String, String> parents = new HashMap<>();
        for (JsonNode folder : folders) {
            fields(folder, Set.of("id", "parentId", "name"), Set.of("id", "name"));
            String id = text(folder.get("id"), 160, "目录 ID");
            text(folder.get("name"), 160, "目录名称");
            JsonNode parent = folder.get("parentId");
            String parentId = parent == null || parent.isNull() ? null : text(parent, 160, "父目录 ID");
            if (parents.containsKey(id)) throw badRequest("目录 ID 不能重复");
            parents.put(id, parentId);
        }
        for (String id : parents.keySet()) {
            Set<String> seen = new HashSet<>();
            for (String cursor = id; cursor != null; cursor = parents.get(cursor)) {
                if (!parents.containsKey(cursor)) throw badRequest("父目录不存在");
                if (!seen.add(cursor)) throw badRequest("目录不能形成循环");
                if (seen.size() > 16) throw badRequest("目录深度不能超过 16 层");
            }
        }
        Set<String> hiddenIds = new HashSet<>();
        for (JsonNode id : hidden) {
            if (!hiddenIds.add(text(id, 160, "隐藏 ID"))) throw badRequest("隐藏 ID 不能重复");
        }
        // Historical request IDs are opaque UI references. Never resolve them against business assets.
        links.fields().forEachRemaining(entry -> {
            validText(entry.getKey(), 160, "请求引用 ID");
            String folderId = text(entry.getValue(), 160, "目标目录 ID");
            if (!parents.containsKey(folderId)) throw badRequest("folderIds 必须引用本次状态中存在的目录");
        });
    }

    private void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) throw badRequest("请求结构不合法");
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw badRequest("请求包含不支持的字段");
        });
        for (String field : required) if (!node.hasNonNull(field)) throw badRequest("请求缺少必需字段");
    }

    private String text(JsonNode node, int max, String label) {
        if (node == null || !node.isTextual()) throw badRequest(label + "必须为字符串");
        return validText(node.textValue(), max, label);
    }

    private String validText(String value, int max, String label) {
        if (value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest(label + "不能为空、包含控制字符或超过 " + max + " 个 UTF-16 单元");
        }
        return value;
    }

    private JsonNode storedState(String state) {
        try {
            return mapper.readTree(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored API workspace preferences are invalid", exception);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
