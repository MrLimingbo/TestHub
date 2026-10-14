package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Projects interface design into logical names without exporting endpoint-specific IDs. */
@Component
public class ApiDesignSyncProjection {
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private final ApiDesignRepository repository;
    private final AccessService access;
    private final ObjectMapper json;

    public ApiDesignSyncProjection(ApiDesignRepository repository, AccessService access, ObjectMapper json) {
        this.repository = repository;
        this.access = access;
        this.json = json;
    }

    public Snapshot read(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        if (!actor.userId().equals(expectedUserId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "登录账号已变化，接口资料同步已停止");
        access.requireProjectRead(actor, projectId);
        var catalogs = repository.findCatalogs(projectId);
        var paths = catalogPaths(catalogs);
        var items = new ArrayList<Item>();
        var identities = new HashMap<String, String>();
        for (var catalog : catalogs) {
            String name = path(paths, catalog.id());
            add(items, identities, catalog.id(), new Item("CATALOG", name, value(new Catalog(catalog.sortOrder()))));
        }
        for (var definition : repository.findDefinitions(projectId, null)) {
            String route = definition.method() + " " + definition.path();
            var versions = repository.findVersions(projectId, definition.id());
            var current = versions.stream().filter(version -> version.id().equals(definition.currentVersionId())).findFirst()
                    .orElseThrow(() -> invalid("接口当前版本引用缺失，请先核对"));
            var cases = repository.findCases(projectId, definition.id());
            add(items, identities, definition.id(), new Item("DEFINITION", route,
                    value(new Definition(path(paths, definition.catalogId()), definition.name(), current.versionName()))));
            for (var version : versions) {
                var data = new Version(version.summary(), version.description(), parameters(version.requestHeaders()),
                        parameters(version.requestQuery()), parameters(version.requestPath()), version.requestBody(),
                        version.responseBody(), object(version.exampleResponse()), version.status());
                add(items, identities, version.id(), new Item("VERSION", route + "\n" + version.versionName(), value(data)));
            }
            for (var row : cases) {
                var data = new Case(row.environmentCode(), row.requestSnapshot(), row.expectedStatus(),
                        row.responseValidation(), row.savedResponseBody());
                add(items, identities, row.id(), new Item("CASE", route + "\n" + row.name(), value(data)));
            }
        }
        items.sort(Comparator.comparing(Item::kind).thenComparing(Item::name));
        if (items.size() > 300 || encoded(items).length > MAX_SNAPSHOT_BYTES) throw invalid("接口设计资料超过 300 条或 8 MiB 同步范围，请先缩减资料");
        return new Snapshot(1, "API_DESIGN_SYNC", projectId, actor.userId(),
                digest(encoded(List.of(items, new java.util.TreeMap<>(identities)))), items, identities);
    }

    Map<String, String> catalogPaths(List<ApiCatalog> catalogs) {
        var byId = new HashMap<String, ApiCatalog>();
        catalogs.forEach(catalog -> byId.put(catalog.id(), catalog));
        var paths = new HashMap<String, String>();
        for (var catalog : catalogs) {
            var parts = new ArrayList<String>();
            var visited = new HashSet<String>();
            var current = catalog;
            while (current != null) {
                if (!visited.add(current.id()) || parts.size() >= 32) throw invalid("接口目录存在循环或深度超过 32 层");
                parts.addFirst(current.name());
                String parentId = current.parentId();
                current = parentId == null ? null : byId.get(parentId);
                if (parentId != null && current == null) throw invalid("接口目录父引用缺失");
            }
            paths.put(catalog.id(), new String(encoded(parts), StandardCharsets.UTF_8));
        }
        return paths;
    }

    private String path(Map<String, String> paths, String id) {
        var result = paths.get(id);
        if (result == null) throw invalid("接口目录引用缺失，请先核对");
        return result;
    }

    private void add(List<Item> items, Map<String, String> identities, String id, Item item) {
        String key = item.kind() + "\n" + item.name().toLowerCase(java.util.Locale.ROOT);
        if (identities.putIfAbsent(key, id) != null) throw invalid("接口资料存在重复的逻辑名称，请先统一");
        items.add(item);
    }

    private List<ApiDesignService.ParameterInput> parameters(String raw) {
        try { return json.readValue(raw, new TypeReference<>() {}); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口参数 JSON 损坏", error); }
    }

    private Map<String, Object> object(String raw) {
        try { return json.readValue(raw, new TypeReference<>() {}); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口示例响应 JSON 损坏", error); }
    }

    private Map<String, Object> value(Object object) { return json.convertValue(object, new TypeReference<>() {}); }
    private byte[] encoded(Object value) {
        try { return json.writeValueAsBytes(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口资料序列化失败", error); }
    }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Snapshot(int version, String scope, String projectId, String actorId, String revision,
                           List<Item> items, Map<String, String> itemIds) { }
    public record Item(String kind, String name, Map<String, Object> definition) { }
    public record Catalog(int sortOrder) { }
    public record Definition(String catalogPath, String displayName, String currentVersionName) { }
    public record Version(String summary, String description,
                          List<ApiDesignService.ParameterInput> headers, List<ApiDesignService.ParameterInput> query,
                          List<ApiDesignService.ParameterInput> pathParameters, String requestBody, String responseBody,
                          Map<String, Object> exampleResponse, String status) { }
    public record Case(String environmentCode, com.fasterxml.jackson.databind.JsonNode requestSnapshot,
                       Integer expectedStatus, String responseValidation, String savedResponseBody) { }
}
