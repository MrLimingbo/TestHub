package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.ProjectFileReferenceGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/** Applies small, independently versioned interface-design changes without copying execution history. */
@Service
public class ApiDesignSyncService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final List<String> KINDS = List.of("CATALOG", "DEFINITION", "VERSION", "CASE");
    private final ApiDesignSyncProjection projection;
    private final ApiDesignService design;
    private final ApiDesignRepository repository;
    private final ApiTransferRepository transfer;
    private final AccessService access;
    private final AuditService audit;
    private final ProjectFileReferenceGuard fileLinks;
    private final io.testhub.platform.apidebug.GrpcDescriptorSyncService descriptors;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiDesignSyncService(ApiDesignSyncProjection projection, ApiDesignService design, ApiDesignRepository repository,
                                ApiTransferRepository transfer, AccessService access, AuditService audit,
                                ProjectFileReferenceGuard fileLinks, JdbcClient jdbc, ObjectMapper json, io.testhub.platform.apidebug.GrpcDescriptorSyncService descriptors) {
        this.projection = projection; this.design = design; this.repository = repository; this.transfer = transfer;
        this.descriptors = descriptors;
        this.access = access; this.audit = audit; this.fileLinks = fileLinks; this.jdbc = jdbc; this.json = json;
    }

    public ApiDesignSyncProjection.Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        return projection.read(actor, projectId, expectedUserId);
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("接口设计同步请求不能为空");
        if (body.length > MAX_BYTES) throw tooLarge();
        try { return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body); }
        catch (java.io.IOException error) { throw invalid("接口设计同步请求 JSON 不合法或包含不支持的字段"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null || !actor.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，接口设计同步已停止");
        String requestKey;
        try { requestKey = UUID.fromString(request.requestKey()).toString(); }
        catch (RuntimeException error) { throw invalid("同步请求标识必须是 UUID"); }
        if (!requestKey.equals(request.requestKey()) || request.expectedRevision() == null
                || !request.expectedRevision().matches("[a-f0-9]{64}") || request.operations() == null
                || request.operations().isEmpty() || request.operations().size() > 300) throw invalid("接口设计同步版本或操作数量无效");
        var operationIds = new HashSet<String>();
        var keys = new HashSet<String>();
        for (var operation : request.operations()) {
            if (operation == null || operation.operationId() == null || !operation.operationId().matches("[a-f0-9-]{36}")
                    || !operationIds.add(operation.operationId())) throw invalid("接口设计同步操作标识重复或无效");
            validate(operation.item());
            fileLinks.validate(operation.item().definition(), projectId);
            if (!keys.add(key(operation.item()))) throw invalid("同一接口设计资料不能重复提交");
        }
        byte[] payload = encoded(request);
        if (payload.length > MAX_BYTES) throw tooLarge();
        String payloadHash = digest(payload);
        transfer.lockProject(projectId);
        var prior = jdbc.sql("SELECT payload_sha256,result_json FROM api_design_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p", projectId).param("a", actor.userId()).param("k", requestKey)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他接口资料");
            return decodeReceipt(prior.get().getValue());
        }
        var current = snapshot(actor, projectId, actor.userId());
        if (!current.revision().equals(request.expectedRevision())) throw conflict("目标接口设计资料已变化，请重新同步并核对冲突");
        var merged = new LinkedHashMap<String, ApiDesignSyncProjection.Item>();
        current.items().forEach(item -> merged.put(key(item), item));
        request.operations().forEach(operation -> {
            if (operation.item().kind().equals("CASE")) validateDescriptorReferences(json.valueToTree(operation.item().definition()), projectId);
            merged.put(key(operation.item()), operation.item());
        });
        validateReferences(merged.values());
        if (merged.size() > 300 || encoded(merged.values()).length > 8 * 1024 * 1024) throw invalid("合并后的接口资料超过同步范围");
        try {
            for (var operation : ordered(request.operations())) write(actor, projectId, project.organizationId(), operation.item());
            restoreCurrentVersions(actor, projectId, merged);
            var after = snapshot(actor, projectId, actor.userId());
            var afterItems = new HashMap<String, ApiDesignSyncProjection.Item>();
            after.items().forEach(item -> afterItems.put(key(item), item));
            var results = new ArrayList<Result>();
            for (var operation : request.operations()) {
                var actual = afterItems.get(key(operation.item()));
                if (actual == null || !json.valueToTree(actual).equals(json.valueToTree(operation.item())))
                    throw conflict("接口资料在写入时被规范化或依赖已变化，整批未提交；请核对后重试");
                results.add(new Result(operation.operationId(), actual));
            }
            var receipt = new Receipt(requestKey, results);
            jdbc.sql("INSERT INTO api_design_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id", UlidCreator.getUlid().toString()).param("o", project.organizationId()).param("p", projectId)
                    .param("a", actor.userId()).param("k", requestKey).param("h", payloadHash)
                    .param("r", new String(encoded(receipt), StandardCharsets.UTF_8)).update();
            audit.record(actor, project.organizationId(), projectId, "API_DESIGN_SYNCED", "API_DESIGN_SYNC", requestKey,
                    Map.of("assets", results.size()));
            return receipt;
        } catch (DataIntegrityViolationException error) { throw conflict("接口目录、路径、版本或用例与目标唯一约束冲突，整批未提交"); }
    }

    private List<Operation> ordered(List<Operation> operations) {
        return operations.stream().sorted(Comparator.comparingInt((Operation operation) -> KINDS.indexOf(operation.item().kind()))
                .thenComparingInt(operation -> operation.item().kind().equals("CATALOG") ? pathNames(operation.item().name()).size() : 0))
                .toList();
    }

    private void write(AuthenticatedPrincipal actor, String projectId, String organizationId, ApiDesignSyncProjection.Item item) {
        switch (item.kind()) {
            case "CATALOG" -> {
                var names = pathNames(item.name());
                var data = data(item, ApiDesignSyncProjection.Catalog.class);
                String parent = names.size() == 1 ? null : path(names.subList(0, names.size() - 1));
                String parentId = parent == null ? null : catalogId(actor, projectId, parent);
                var old = catalogByPath(actor, projectId, item.name());
                var input = new ApiDesignService.CatalogInput(names.getLast(), parentId, data.sortOrder());
                if (old == null) design.createCatalog(actor, projectId, input);
                else design.updateCatalog(actor, projectId, old.id(), input);
            }
            case "DEFINITION" -> {
                var route = route(item.name());
                var data = data(item, ApiDesignSyncProjection.Definition.class);
                String catalogId = catalogId(actor, projectId, data.catalogPath());
                var old = definitionByRoute(projectId, route);
                if (old == null) repository.insertDefinition(UlidCreator.getUlid().toString(), organizationId, projectId,
                        catalogId, route.method(), route.path(), data.displayName(), actor.userId());
                else design.updateDefinition(actor, projectId, old.id(),
                        new ApiDesignService.DefinitionMetadataInput(catalogId, route.method(), route.path(), data.displayName()));
            }
            case "VERSION" -> {
                var names = child(item.name());
                var parent = definitionByRoute(projectId, route(names.parent()));
                if (parent == null) throw conflict("版本所属接口缺失");
                var data = data(item, ApiDesignSyncProjection.Version.class);
                var old = repository.findVersions(projectId, parent.id()).stream().filter(value -> value.versionName().equals(names.name())).findFirst();
                if (old.isEmpty()) {
                    var created = design.createVersion(actor, projectId, parent.id(), versionInput(names.name(), data));
                    if ("PUBLISHED".equals(data.status())) design.publish(actor, projectId, parent.id(), created.id());
                } else if (!sameVersion(old.get(), data)) {
                    if ("PUBLISHED".equals(old.get().status()) && "DRAFT".equals(data.status()))
                        throw conflict("已发布的历史版本不可撤销发布；请核对后保留发布状态");
                    if (!sameVersionContent(old.get(), data) && repository.updateVersionContentForSync(projectId, parent.id(),
                            old.get().id(), data.summary(), data.description(), encodedText(data.headers()),
                            encodedText(data.query()), encodedText(data.pathParameters()), data.requestBody(),
                            data.responseBody(), encodedText(data.exampleResponse())) != 1)
                        throw conflict("目标历史版本已变化，请重新核对");
                    if ("DRAFT".equals(old.get().status()) && "PUBLISHED".equals(data.status()))
                        design.publish(actor, projectId, parent.id(), old.get().id());
                }
            }
            case "CASE" -> {
                var names = child(item.name());
                var parent = definitionByRoute(projectId, route(names.parent()));
                if (parent == null) throw conflict("用例所属接口缺失");
                var data = data(item, ApiDesignSyncProjection.Case.class);
                if (data.environmentCode() != null && !transfer.lockEnabledEnvironment(projectId, data.environmentCode()))
                    throw conflict("用例引用的目标环境不存在或已停用，请先核对环境同步");
                var input = new ApiDesignService.CaseInput(names.name(), data.environmentCode(), data.requestSnapshot(),
                        data.expectedStatus(), data.responseValidation(), data.savedResponseBody());
                var old = repository.findCases(projectId, parent.id()).stream().filter(value -> value.name().equals(names.name())).findFirst();
                if (old.isEmpty()) design.createCase(actor, projectId, parent.id(), input);
                else design.updateCase(actor, projectId, parent.id(), old.get().id(), input);
            }
            default -> throw invalid("接口设计资料类型无效");
        }
    }

    private void restoreCurrentVersions(AuthenticatedPrincipal actor, String projectId,
                                        Map<String, ApiDesignSyncProjection.Item> merged) {
        for (var item : merged.values()) {
            if (!item.kind().equals("DEFINITION")) continue;
            var definition = definitionByRoute(projectId, route(item.name()));
            if (definition == null) throw conflict("接口定义缺失");
            var data = data(item, ApiDesignSyncProjection.Definition.class);
            var version = repository.findVersions(projectId, definition.id()).stream()
                    .filter(value -> value.versionName().equals(data.currentVersionName())).findFirst()
                    .orElseThrow(() -> conflict("接口当前版本缺失"));
            if (!version.id().equals(definition.currentVersionId()) || !version.status().equals(definition.status()))
                repository.updateCurrentVersion(projectId, definition.id(), version.id(), version.status(), actor.userId());
        }
    }

    private void validateReferences(java.util.Collection<ApiDesignSyncProjection.Item> items) {
        var keys = new HashSet<String>();
        items.forEach(item -> keys.add(key(item)));
        for (var item : items) {
            if (item.kind().equals("CATALOG")) {
                var names = pathNames(item.name());
                if (names.size() > 1) require(keys, "CATALOG", path(names.subList(0, names.size() - 1)));
            } else if (item.kind().equals("DEFINITION")) {
                var data = data(item, ApiDesignSyncProjection.Definition.class);
                require(keys, "CATALOG", data.catalogPath());
                require(keys, "VERSION", item.name() + "\n" + data.currentVersionName());
            } else require(keys, "DEFINITION", child(item.name()).parent());
        }
    }

    private void require(Set<String> keys, String kind, String name) {
        if (!keys.contains(kind + "\n" + name.toLowerCase(Locale.ROOT))) throw conflict("接口目录、版本或用例引用缺失，请先核对");
    }

    private void validate(ApiDesignSyncProjection.Item item) {
        if (item == null || !KINDS.contains(item.kind()) || item.definition() == null) throw invalid("接口设计资料类型无效");
        Object value;
        switch (item.kind()) {
            case "CATALOG" -> {
                pathNames(item.name()); var catalog = data(item, ApiDesignSyncProjection.Catalog.class);
                if (catalog.sortOrder() < 0 || catalog.sortOrder() > 9999) throw invalid("接口目录排序无效"); value = catalog;
            }
            case "DEFINITION" -> {
                var route = route(item.name()); var definition = data(item, ApiDesignSyncProjection.Definition.class);
                pathNames(definition.catalogPath());
                if (definition.displayName() == null || definition.displayName().isBlank() || definition.displayName().length() > 200
                        || definition.currentVersionName() == null || !definition.currentVersionName().matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?"))
                    throw invalid("接口名称或当前版本无效");
                design.validateDefinitionInput(new ApiDesignService.DefinitionInput("A".repeat(26), route.method(), route.path(),
                        definition.displayName(), definition.currentVersionName(), "", "", List.of(), List.of(), List.of(), "", "", Map.of()));
                value = definition;
            }
            case "VERSION" -> {
                var names = child(item.name()); var route = route(names.parent());
                var version = data(item, ApiDesignSyncProjection.Version.class);
                if (!Set.of("DRAFT", "PUBLISHED").contains(version.status())) throw invalid("接口版本状态无效");
                design.validateDefinitionInput(new ApiDesignService.DefinitionInput("A".repeat(26), route.method(), route.path(),
                        "同步校验", names.name(), version.summary(), version.description(), version.headers(), version.query(),
                        version.pathParameters(), version.requestBody(), version.responseBody(), version.exampleResponse()));
                value = version;
            }
            case "CASE" -> {
                var names = child(item.name()); route(names.parent());
                var data = data(item, ApiDesignSyncProjection.Case.class);
                design.validateCaseInput(new ApiDesignService.CaseInput(names.name(), data.environmentCode(), data.requestSnapshot(),
                        data.expectedStatus(), data.responseValidation(), data.savedResponseBody()));
                value = data;
            }
            default -> throw invalid("接口设计资料类型无效");
        }
        if (!json.valueToTree(value).equals(json.valueToTree(item.definition()))) throw invalid("接口设计字段不合法或尚未规范化");
    }

    private void validateDescriptorReferences(com.fasterxml.jackson.databind.JsonNode node, String project) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            String name = field.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if ((name.equals("descriptorid") || name.equals("grpcdescriptorid")) && !field.getValue().isNull()
                    && (!field.getValue().isTextual() || !field.getValue().asText().isBlank())) {
                if (!field.getValue().isTextual()) throw conflict("描述符引用无效，请先确认描述符同步");
                descriptors.validateReference(project, field.getValue().asText());
            }
            validateDescriptorReferences(field.getValue(), project);
        });
        else if (node.isArray()) node.forEach(value -> validateDescriptorReferences(value, project));
    }

    private ApiCatalog catalogByPath(AuthenticatedPrincipal actor, String projectId, String path) {
        var catalogs = repository.findCatalogs(projectId);
        var paths = projection.catalogPaths(catalogs);
        return catalogs.stream().filter(value -> path.equals(paths.get(value.id()))).findFirst().orElse(null);
    }
    private String catalogId(AuthenticatedPrincipal actor, String projectId, String path) {
        var catalog = catalogByPath(actor, projectId, path);
        if (catalog == null) throw conflict("接口目录引用缺失");
        return catalog.id();
    }
    private ApiDefinition definitionByRoute(String projectId, Route route) {
        return repository.findDefinitions(projectId, null).stream()
                .filter(value -> value.method().equals(route.method()) && value.path().equals(route.path())).findFirst().orElse(null);
    }
    private boolean sameVersion(ApiDefinitionVersion old, ApiDesignSyncProjection.Version value) {
        return old.status().equals(value.status()) && sameVersionContent(old, value);
    }
    private boolean sameVersionContent(ApiDefinitionVersion old, ApiDesignSyncProjection.Version value) {
        var actual = repositoryVersion(old);
        return json.valueToTree(actual).equals(json.valueToTree(new ApiDesignSyncProjection.Version(value.summary(), value.description(),
                value.headers(), value.query(), value.pathParameters(), value.requestBody(), value.responseBody(),
                value.exampleResponse(), old.status())));
    }
    private ApiDesignSyncProjection.Version repositoryVersion(ApiDefinitionVersion value) {
        try {
            var type = new com.fasterxml.jackson.core.type.TypeReference<List<ApiDesignService.ParameterInput>>() {};
            return new ApiDesignSyncProjection.Version(value.summary(), value.description(), json.readValue(value.requestHeaders(), type),
                    json.readValue(value.requestQuery(), type), json.readValue(value.requestPath(), type), value.requestBody(),
                    value.responseBody(), json.readValue(value.exampleResponse(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}),
                    value.status());
        } catch (JsonProcessingException error) { throw new IllegalStateException("接口版本 JSON 损坏", error); }
    }
    private ApiDesignService.VersionInput versionInput(String name, ApiDesignSyncProjection.Version data) {
        return new ApiDesignService.VersionInput(name, data.summary(), data.description(), data.headers(), data.query(),
                data.pathParameters(), data.requestBody(), data.responseBody(), data.exampleResponse());
    }
    private List<String> pathNames(String value) {
        if (value == null || value.length() > 4096) throw invalid("接口目录路径无效");
        try {
            List<String> names = json.readerForListOf(String.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value);
            if (names == null || names.isEmpty() || names.size() > 32 || names.stream().anyMatch(name -> name == null
                    || name.isBlank() || name.length() > 160 || !name.equals(name.trim())) || !path(names).equals(value))
                throw invalid("接口目录路径尚未规范化");
            return names;
        } catch (java.io.IOException error) { throw invalid("接口目录路径 JSON 不合法"); }
    }
    private String path(List<String> names) { return new String(encoded(names), StandardCharsets.UTF_8); }
    private Route route(String value) {
        if (value == null || value.contains("\n") || value.contains("\r")) throw invalid("请求方法与路径无效");
        int split = value.indexOf(' ');
        if (split < 1 || split == value.length() - 1 || value.indexOf(' ', split + 1) >= 0) throw invalid("请求方法与路径无效");
        String method = value.substring(0, split), path = value.substring(split + 1);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method) || !path.startsWith("/")
                || path.length() > 700) throw invalid("请求方法与路径无效");
        return new Route(method, path);
    }
    private Child child(String value) {
        if (value == null) throw invalid("接口子资料名称无效");
        int split = value.indexOf('\n');
        if (split < 1 || split == value.length() - 1 || value.indexOf('\n', split + 1) >= 0 || value.contains("\r"))
            throw invalid("接口子资料名称无效");
        return new Child(value.substring(0, split), value.substring(split + 1));
    }
    private String key(ApiDesignSyncProjection.Item item) { return item.kind() + "\n" + item.name().toLowerCase(Locale.ROOT); }
    private <T> T data(ApiDesignSyncProjection.Item item, Class<T> type) {
        try { return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(encoded(item.definition())); }
        catch (java.io.IOException error) { throw invalid("接口设计资料字段不合法"); }
    }
    private Receipt decodeReceipt(String text) {
        try { return json.readValue(text, Receipt.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口同步回执损坏", error); }
    }
    private byte[] encoded(Object value) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口同步序列化失败", error); }
    }
    private String encodedText(Object value) { return new String(encoded(value), StandardCharsets.UTF_8); }
    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "接口设计同步请求不能超过 10 MiB"); }

    public record Request(String requestKey, String expectedUserId, String expectedRevision, List<Operation> operations) { }
    public record Operation(String operationId, ApiDesignSyncProjection.Item item) { }
    public record Result(String operationId, ApiDesignSyncProjection.Item item) { }
    public record Receipt(String requestKey, List<Result> results) { }
    private record Route(String method, String path) { }
    private record Child(String parent, String name) { }
}
