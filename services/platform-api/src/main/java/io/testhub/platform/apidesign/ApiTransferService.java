package io.testhub.platform.apidesign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.apidebug.GrpcDescriptorTransferService;
import io.testhub.platform.apidebug.ApiHistoryTransferService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTransferFiles;
import io.testhub.platform.security.AuthenticatedPrincipal;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Independent V1 protocol for definitions. Execution history and project runtime configuration are excluded. */
@Service
public class ApiTransferService {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int FILE_SNAPSHOT_MAX_BYTES = 32 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");
    private static final Set<String> DYNAMIC_VARIABLES = Set.of("$timestamp", "$isoTimestamp", "$randomUUID", "$uuid");
    private final ApiDesignService design;
    private final ApiDesignRepository definitions;
    private final ApiTransferRepository repository;
    private final FunctionalTransferFiles files;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Validator validator;
    private final GrpcDescriptorTransferService grpc;
    private final ApiHistoryTransferService history;

    public ApiTransferService(ApiDesignService design, ApiDesignRepository definitions, ApiTransferRepository repository,
                              FunctionalTransferFiles files, AccessService access, AuditService audit,
                              ObjectMapper json, Validator validator, GrpcDescriptorTransferService grpc, ApiHistoryTransferService history) {
        this.design = design; this.definitions = definitions; this.repository = repository; this.files = files;
        this.access = access; this.audit = audit; this.json = json; this.validator = validator; this.grpc = grpc; this.history = history;
    }

    public Map<String, Object> capabilities(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return Map.of("version", 1, "scope", "API_DEFINITIONS", "versions", List.of(1, 2, 3),
                "maxBytes", MAX_BYTES, "fileSnapshotMaxBytes", FILE_SNAPSHOT_MAX_BYTES);
    }

    public ImportRequest decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("迁移请求不能为空。");
        if (body.length > FILE_SNAPSHOT_MAX_BYTES) throw tooLarge();
        try {
            ImportRequest request = json.readerFor(ImportRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
            if (request == null || request.snapshot() == null) throw invalid("迁移资料不能为空。");
            if (body.length > limit(request.snapshot())) throw tooLarge();
            return request;
        } catch (java.io.IOException error) {
            throw invalid("迁移请求 JSON 格式不合法或包含不支持的字段。");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, boolean includeFiles) {
        return export(principal, projectId, includeFiles, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, boolean includeFiles, boolean includeGrpc) {
        return export(principal, projectId, includeFiles, includeGrpc, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot export(AuthenticatedPrincipal principal, String projectId, boolean includeFiles, boolean includeGrpc, boolean includeHistory) {
        access.requireProjectRead(principal, projectId);
        var catalogs = definitions.findCatalogs(projectId).stream()
                .map(c -> new Catalog(c.id(), c.parentId(), c.name(), c.sortOrder())).toList();
        var sourceDefinitions = definitions.findDefinitions(projectId, null);
        var apis = sourceDefinitions.stream().map(d -> new Definition(d.id(), d.catalogId(), d.method(), d.path(),
                d.name(), d.status(), d.currentVersionId())).toList();
        List<Version> versions = new ArrayList<>();
        for (var d : sourceDefinitions) {
            for (var v : definitions.findVersions(projectId, d.id())) {
                versions.add(new Version(v.id(), v.apiId(), v.versionName(), v.summary(), v.description(),
                        parameters(v.requestHeaders()), parameters(v.requestQuery()), parameters(v.requestPath()),
                        v.requestBody(), v.responseBody(), object(v.exampleResponse()), v.status(), v.publishedAt(), v.createdAt()));
            }
        }
        var cases = definitions.findCasesByProject(projectId).stream().map(c -> new Case(c.id(), c.apiId(), c.name(),
                c.environmentCode(), c.requestSnapshot(), c.expectedStatus(), c.responseValidation(), c.savedResponseBody())).toList();
        var snapshot = new Snapshot(includeHistory ? 3 : includeGrpc ? 2 : 1, projectId, catalogs, apis, versions, cases, includeFiles ? files.export(principal, projectId) : null, includeGrpc ? grpc.export(principal, projectId) : null, includeHistory ? history.export(principal, projectId) : null);
        validate(snapshot);
        encoded(snapshot);
        return snapshot;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt transfer(AuthenticatedPrincipal principal, String projectId, ImportRequest request) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (request == null || request.requestKey() == null
                || !request.requestKey().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw invalid("requestKey 必须是 UUID。");
        if (!principal.userId().equals(request.expectedUserId())) throw conflict("登录账号已变化，请重新预览迁移。");
        Snapshot source = request.snapshot();
        if (source == null) throw invalid("迁移资料不能为空。");
        encoded(source);
        String hash = FunctionalTransferFiles.sha256(encodedCanonical(source));
        String key = UUID.fromString(request.requestKey()).toString();
        repository.lockProject(projectId);
        var previous = repository.receipt(projectId, principal.userId(), key);
        if (previous.isPresent()) {
            if (!previous.get().hash().equals(hash)) throw conflict("该 requestKey 已用于其他迁移资料。");
            try { return json.readValue(previous.get().result(), Receipt.class); }
            catch (JsonProcessingException error) { throw new IllegalStateException("迁移回执损坏", error); }
        }
        // Commit performs all structural, dependency and collision checks; no preview is trusted.
        validate(source);
        checkDependencies(projectId, source);
        for (var d : source.definitions()) {
            if (repository.routeExists(projectId, d.method().trim().toUpperCase(Locale.ROOT), d.path().trim()))
                throw conflict("目标项目存在相同请求方法与路径：" + d.method() + " " + d.path());
        }
        String id = newId();
        try {
            Snapshot snapshot = source.files() == null ? source : rewrite(source, files.transfer(principal, projectId, source.files()));
            // Rewriting can lengthen fields; re-run the very same domain bounds before writing assets.
            validate(snapshot);
            encoded(snapshot);
            String rootId = design.createCatalog(principal, projectId,
                    new ApiDesignService.CatalogInput("接口迁入-" + id, null, 0)).id();
            Map<String, String> catalogIds = new HashMap<>(), apiIds = new HashMap<>(), versionIds = new HashMap<>();
            for (var c : orderedCatalogs(snapshot.catalogs())) {
                var created = design.createCatalog(principal, projectId, new ApiDesignService.CatalogInput(c.name(),
                        c.parentId() == null ? rootId : catalogIds.get(c.parentId()), c.sortOrder()));
                catalogIds.put(c.id(), created.id());
            }
            for (var d : snapshot.definitions()) {
                String apiId = newId();
                definitions.insertDefinition(apiId, project.organizationId(), projectId, catalogIds.get(d.catalogId()),
                        d.method().trim().toUpperCase(Locale.ROOT), d.path().trim(), d.name().trim(), principal.userId());
                apiIds.put(d.id(), apiId);
            }
            for (var v : snapshot.versions()) {
                String apiId = apiIds.get(v.apiId());
                var created = design.createVersion(principal, projectId, apiId, versionInput(v));
                repository.restoreVersion(projectId, apiId, created.id(), v);
                versionIds.put(v.id(), created.id());
            }
            for (var d : snapshot.definitions()) {
                definitions.updateCurrentVersion(projectId, apiIds.get(d.id()), versionIds.get(d.currentVersionId()), d.status(), principal.userId());
            }
            Map<String, String> descriptorIds = snapshot.grpcDescriptors() == null ? Map.of() : grpc.transfer(principal, projectId, snapshot.grpcDescriptors());
            for (var c : snapshot.cases()) {
                if (snapshot.grpcDescriptors() != null) c = new Case(c.id(), c.apiId(), c.name(), c.environmentCode(), descriptorReferences(c.requestSnapshot(), descriptorIds), c.expectedStatus(), c.responseValidation(), c.savedResponseBody());
                // The public domain write sanitizes auth/header/query secrets. Never insert raw case snapshots.
                design.createCase(principal, projectId, apiIds.get(c.apiId()), caseInput(c));
            }
            if (snapshot.debugHistory() != null) {
                var importedHistory = snapshot.debugHistory().stream().map(h -> historyDraft(h, descriptorReferences(h.replayDraft(), descriptorIds))).toList();
                history.transfer(principal, projectId, snapshot.sourceProjectId(), importedHistory);
            }
            var counts = snapshot.files() == null ? null : new FunctionalTransferFiles.Counts(snapshot.files().items().size(),
                    snapshot.files().items().stream().mapToLong(FunctionalTransferFiles.File::size).sum());
            var receipt = new Receipt(id, snapshot.catalogs().size(), snapshot.definitions().size(), snapshot.versions().size(),
                    snapshot.cases().size(), rootId, counts, snapshot.grpcDescriptors() == null ? null : snapshot.grpcDescriptors().size(), snapshot.debugHistory() == null ? null : snapshot.debugHistory().size());
            repository.saveReceipt(id, project.organizationId(), projectId, principal.userId(), key, hash, write(receipt));
            audit.record(principal, project.organizationId(), projectId, "API_DEFINITIONS_TRANSFERRED", "API_TRANSFER", id,
                    Map.of("catalogs", receipt.catalogs(), "definitions", receipt.definitions(), "versions", receipt.versions(),
                            "cases", receipt.cases(), "publicationProvenance", "IMPORTED_TIMESTAMP_WITHOUT_PUBLISHER"));
            return receipt;
        } catch (DataIntegrityViolationException error) {
            throw conflict("迁移目录、方法路径、版本名称或用例名称冲突，未写入任何迁移数据。");
        }
    }

    private void validate(Snapshot s) {
        if ((s.version() < 1 || s.version() > 3) || !validId(s.sourceProjectId())) throw invalid("仅支持 API_DEFINITIONS V1/V2/V3 及有效 sourceProjectId。");
        if ((s.version() == 1 && s.grpcDescriptors() != null) || (s.version() == 2 && s.grpcDescriptors() == null)) throw invalid("描述符范围与迁移版本不一致。");
        if ((s.version() == 3) != (s.debugHistory() != null)) throw invalid("历史范围与迁移版本不一致。");
        bounds(s.catalogs(), 5000, "目录"); bounds(s.definitions(), 5000, "接口");
        bounds(s.versions(), 10000, "版本"); bounds(s.cases(), 10000, "用例");
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        Map<String, Catalog> catalogs = new HashMap<>();
        for (var c : s.catalogs()) {
            addId(ids, c.id()); require(c.name(), 160, "目录名称");
            if (!names.add(Objects.toString(c.parentId(), "") + "\n" + c.name().trim())) throw conflict("同级目录名称重复。");
            catalogs.put(c.id(), c);
        }
        for (var c : s.catalogs()) {
            Set<String> path = new HashSet<>(); Catalog next = c;
            while (next != null) {
                if (!path.add(next.id()) || path.size() > 64) throw invalid("目录存在循环或深度超过 64。");
                String parent = next.parentId();
                next = parent == null ? null : catalogs.get(parent);
                if (parent != null && next == null) throw invalid("目录父级不在迁移资料中。");
            }
        }
        Map<String, Definition> apis = new HashMap<>(); names.clear();
        for (var d : s.definitions()) {
            addId(ids, d.id()); status(d.status());
            if (!catalogs.containsKey(d.catalogId())) throw invalid("接口目录不在迁移资料中。");
            require(d.method(), 12, "方法"); require(d.path(), 700, "路径"); require(d.name(), 200, "接口名称");
            if (!names.add(d.method().trim().toUpperCase(Locale.ROOT) + "\n" + d.path().trim())) throw conflict("方法与路径重复。");
            apis.put(d.id(), d);
        }
        Map<String, Version> versions = new HashMap<>(); names.clear();
        for (var v : s.versions()) {
            addId(ids, v.id()); status(v.status()); Definition d = apis.get(v.apiId());
            if (d == null) throw invalid("版本所属接口不在迁移资料中。");
            require(v.versionName(), 40, "版本名称");
            if (!names.add(v.apiId() + "\n" + v.versionName().trim())) throw conflict("接口内版本名称重复。");
            if (v.createdAt() == null || ("PUBLISHED".equals(v.status()) != (v.publishedAt() != null)))
                throw invalid("版本创建时间或发布状态/时间不一致。");
            timestamp(v.createdAt()); if (v.publishedAt() != null) timestamp(v.publishedAt());
            design.validateDefinitionInput(new ApiDesignService.DefinitionInput(d.catalogId(), d.method(), d.path(), d.name(),
                    v.versionName(), v.summary(), v.description(), v.headers(), v.query(), v.pathParameters(),
                    v.requestBody(), v.responseBody(), v.exampleResponse()));
            versions.put(v.id(), v);
        }
        for (var d : s.definitions()) {
            Version current = versions.get(d.currentVersionId());
            if (current == null || !current.apiId().equals(d.id()) || !current.status().equals(d.status()))
                throw invalid("接口 currentVersionId 必须引用自身版本且发布状态一致。");
        }
        Map<String, String> descriptorIds = new HashMap<>();
        if (s.grpcDescriptors() != null) {
            grpc.validate(s.grpcDescriptors(), ids);
            for (var descriptor : s.grpcDescriptors()) descriptorIds.put(descriptor.id(), descriptor.id());
        }
        names.clear();
        for (var c : s.cases()) {
            addId(ids, c.id());
            if (!apis.containsKey(c.apiId())) throw invalid("用例所属接口不在迁移资料中。");
            design.validateCaseInput(caseInput(c));
            if (!names.add(c.apiId() + "\n" + c.name().trim())) throw conflict("接口内用例名称重复。");
            if (s.grpcDescriptors() == null) rejectDescriptor(c.requestSnapshot());
            else descriptorReferences(c.requestSnapshot(), descriptorIds);
            JsonNode embedded = c.requestSnapshot().get("environmentCode");
            if (embedded != null && !embedded.isNull() && (!embedded.isTextual()
                    || !Objects.equals(environment(embedded.asText()), environment(c.environmentCode()))))
                throw invalid("用例快照 environmentCode 与用例环境不一致。");
        }
        if (s.debugHistory() != null) {
            history.validate(s.debugHistory(), ids);
            for (var h : s.debugHistory()) {
                if (s.grpcDescriptors() == null) rejectDescriptor(h.replayDraft());
                else descriptorReferences(h.replayDraft(), descriptorIds);
            }
        }
        if (s.files() != null) {
            if (!s.sourceProjectId().equals(s.files().projectId())) throw invalid("文件 sourceProjectId 不一致。");
            if (!validator.validate(s.files()).isEmpty()) throw invalid("迁移文件元数据不合法。");
            files.validate(s.files(), ids);
        }
        // Run the same walker with identity replacements, before touching storage.
        Map<String, String> paths = new HashMap<>();
        if (s.files() != null) for (var f : s.files().items()) paths.put(f.id(), filePath(s.sourceProjectId(), f.id()));
        rewrite(s, paths);
    }

    private void checkDependencies(String projectId, Snapshot s) {
        Map<String, Set<String>> keys = new HashMap<>();
        Map<String, Set<String>> apiEnvironments = new HashMap<>();
        SortedSet<String> missing = new TreeSet<>();
        for (var c : s.cases()) {
            String env = environment(c.environmentCode());
            if (env != null && !keys.containsKey(env)) {
                if (!repository.lockEnabledEnvironment(projectId, env)) throw conflict("目标环境不存在或已停用：" + env);
                keys.put(env, new HashSet<>(repository.lockParameterKeys(projectId, env)));
            }
            apiEnvironments.computeIfAbsent(c.apiId(), ignored -> new HashSet<>()).add(env == null ? "" : env);
            collectMissing(variables(json.valueToTree(c.requestSnapshot())), env, keys, missing);
        }
        for (var d : s.definitions()) {
            Set<String> referenced = variables(json.getNodeFactory().textNode(d.path()));
            for (String env : apiEnvironments.getOrDefault(d.id(), Set.of(""))) collectMissing(referenced, env, keys, missing);
        }
        for (var v : s.versions()) {
            Set<String> referenced = variables(json.valueToTree(versionInput(v)));
            // Definitions have no environment of their own: require explicit case environments for variable dependencies.
            for (String env : apiEnvironments.getOrDefault(v.apiId(), Set.of(""))) collectMissing(referenced, env, keys, missing);
        }
        if (s.debugHistory() != null) for (var h : s.debugHistory()) {
            if (h.replayDraft() == null || h.replayDraft().isNull()) continue;
            JsonNode envNode = h.replayDraft().path("request").get("environmentCode");
            if (envNode != null && !envNode.isNull() && !envNode.isTextual()) throw invalid("历史草稿环境字段无效。");
            String env = environment(envNode == null || envNode.isNull() ? null : envNode.asText());
            if (env != null && !keys.containsKey(env)) {
                if (!repository.lockEnabledEnvironment(projectId, env)) throw conflict("目标缺少历史草稿环境或环境已停用：" + env);
                keys.put(env, new HashSet<>(repository.lockParameterKeys(projectId, env)));
            }
            collectMissing(variables(h.replayDraft()), env, keys, missing);
        }
        if (!missing.isEmpty()) throw conflict("目标缺少变量依赖（未绑定环境的版本需先配置用例环境）：" + String.join(", ", missing));
    }

    private void collectMissing(Set<String> referenced, String env, Map<String, Set<String>> keys, Set<String> missing) {
        for (String name : referenced) if (!keys.getOrDefault(env, Set.of()).contains(name))
            missing.add((env == null || env.isEmpty() ? "未绑定环境" : env) + ":" + name);
    }

    private Set<String> variables(JsonNode node) {
        Set<String> result = new TreeSet<>();
        walk(node, value -> {
            var matcher = VARIABLE.matcher(value);
            while (matcher.find()) if (!DYNAMIC_VARIABLES.contains(matcher.group(1))) result.add(matcher.group(1));
            return value;
        });
        return result;
    }

    private void rejectDescriptor(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            String name = field.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if ((name.equals("descriptorid") || name.equals("grpcdescriptorid")) && !field.getValue().isNull()
                    && (!field.getValue().isTextual() || !field.getValue().asText().isBlank()))
                throw conflict("第一阶段不支持 gRPC descriptor 跨项目引用，请先移除描述符依赖。");
            rejectDescriptor(field.getValue());
        });
        else if (node.isArray()) node.forEach(this::rejectDescriptor);
    }

    private JsonNode descriptorReferences(JsonNode node, Map<String, String> ids) {
        if (node == null) return null;
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(field -> {
                String name = field.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                JsonNode value = field.getValue();
                if ((name.equals("descriptorid") || name.equals("grpcdescriptorid")) && !value.isNull()
                        && (!value.isTextual() || !value.asText().isBlank())) {
                    String mapped = value.isTextual() ? ids.get(value.asText()) : null;
                    if (mapped == null) throw conflict("用例引用的 gRPC 描述符不在迁移范围中。");
                    result.put(field.getKey(), mapped);
                } else result.set(field.getKey(), descriptorReferences(value, ids));
            });
            return result;
        }
        if (node.isArray()) { ArrayNode result = json.createArrayNode(); node.forEach(value -> result.add(descriptorReferences(value, ids))); return result; }
        return node.deepCopy();
    }

    private Snapshot rewrite(Snapshot s, Map<String, String> paths) {
        var fileData = s.files() == null ? new FunctionalTransferFiles.Definitions(s.sourceProjectId(), List.of()) : s.files();
        UnaryOperator<String> text = value -> {
            try { return files.rewrite(value, fileData, paths); }
            catch (ResponseStatusException error) {
                if (s.files() == null) throw conflict("资料引用源项目文件；必须显式 includeFiles=true，未选文件不能留下源项目链接。");
                throw error;
            }
        };
        var versions = s.versions().stream().map(v -> new Version(v.id(), v.apiId(), v.versionName(),
                content(v.summary(), text), content(v.description(), text), rewriteParameters(v.headers(), text),
                rewriteParameters(v.query(), text), rewriteParameters(v.pathParameters(), text), content(v.requestBody(), text),
                content(v.responseBody(), text), rewriteMap(v.exampleResponse(), text), v.status(), v.publishedAt(), v.createdAt())).toList();
        var cases = s.cases().stream().map(c -> new Case(c.id(), c.apiId(), c.name(), c.environmentCode(),
                walk(c.requestSnapshot(), text), c.expectedStatus(), content(c.responseValidation(), text), content(c.savedResponseBody(), text))).toList();
        return new Snapshot(s.version(), s.sourceProjectId(), s.catalogs(), s.definitions(), versions, cases, s.files(), s.grpcDescriptors(), s.debugHistory() == null ? null : s.debugHistory().stream().map(h -> historyDraft(h, walk(h.replayDraft(), text))).toList());
    }

    private ApiHistoryTransferService.Item historyDraft(ApiHistoryTransferService.Item h, JsonNode draft) {
        return new ApiHistoryTransferService.Item(h.id(), h.method(), h.protocol(), h.requestUrl(), h.responseStatus(),
                h.durationMs(), h.responseSize(), h.result(), h.errorMessage(), draft, h.createdAt());
    }

    private List<ApiDesignService.ParameterInput> rewriteParameters(List<ApiDesignService.ParameterInput> values, UnaryOperator<String> text) {
        if (values == null) return null;
        return json.convertValue(walk(json.valueToTree(values), text), new TypeReference<>() { });
    }

    private Map<String, Object> rewriteMap(Map<String, Object> values, UnaryOperator<String> text) {
        if (values == null) return null;
        return json.convertValue(walk(json.valueToTree(values), text), new TypeReference<>() { });
    }

    private JsonNode walk(JsonNode node, UnaryOperator<String> text) {
        if (node == null) return null;
        if (node.isTextual()) return json.getNodeFactory().textNode(content(node.asText(), text));
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(field -> result.set(field.getKey(), walk(field.getValue(), text)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode(); node.forEach(value -> result.add(walk(value, text))); return result;
        }
        return node;
    }

    private String content(String value, UnaryOperator<String> text) {
        if (value == null) return null;
        String trimmed = value.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            JsonNode parsed = null;
            try { parsed = json.readTree(value); } catch (JsonProcessingException ignored) { /* Ordinary body text is valid too. */ }
            if (parsed != null && parsed.isContainerNode()) {
                JsonNode rewritten = walk(parsed, text);
                return parsed.equals(rewritten) ? value : write(rewritten);
            }
        }
        return text.apply(value);
    }

    private List<Catalog> orderedCatalogs(List<Catalog> catalogs) {
        Map<String, List<Catalog>> children = new HashMap<>();
        for (var c : catalogs) children.computeIfAbsent(c.parentId() == null ? "" : c.parentId(), ignored -> new ArrayList<>()).add(c);
        List<Catalog> result = new ArrayList<>(); ArrayDeque<Catalog> queue = new ArrayDeque<>(children.getOrDefault("", List.of()));
        while (!queue.isEmpty()) { Catalog c = queue.removeFirst(); result.add(c); queue.addAll(children.getOrDefault(c.id(), List.of())); }
        return result;
    }

    private ApiDesignService.VersionInput versionInput(Version v) {
        return new ApiDesignService.VersionInput(v.versionName(), v.summary(), v.description(), v.headers(), v.query(),
                v.pathParameters(), v.requestBody(), v.responseBody(), v.exampleResponse());
    }
    private ApiDesignService.CaseInput caseInput(Case c) {
        return new ApiDesignService.CaseInput(c.name(), c.environmentCode(), c.requestSnapshot(), c.expectedStatus(), c.responseValidation(), c.savedResponseBody());
    }
    private List<ApiDesignService.ParameterInput> parameters(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口版本参数损坏", error); }
    }
    private Map<String, Object> object(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (JsonProcessingException error) { throw new IllegalStateException("接口版本示例损坏", error); }
    }
    private byte[] encoded(Snapshot s) {
        try { byte[] bytes = json.writeValueAsBytes(s); if (bytes.length > limit(s)) throw tooLarge(); return bytes; }
        catch (JsonProcessingException error) { throw invalid("迁移资料无法编码。"); }
    }
    private byte[] encodedCanonical(Snapshot s) {
        try { return json.writeValueAsBytes(canonical(json.valueToTree(s))); }
        catch (JsonProcessingException error) { throw invalid("迁移资料无法编码。"); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode(); TreeSet<String> fields = new TreeSet<>(); node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> result.set(field, canonical(node.get(field)))); return result;
        }
        if (node.isArray()) { ArrayNode result = json.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result; }
        return node;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw invalid("迁移资料无法编码。"); }
    }
    private int limit(Snapshot s) { return s.files() == null ? MAX_BYTES : FILE_SNAPSHOT_MAX_BYTES; }
    private String newId() { return UlidCreator.getUlid().toString(); }
    private boolean validId(String id) { return id != null && id.matches("[A-Z0-9]{26}"); }
    private void addId(Set<String> ids, String id) { if (!validId(id) || !ids.add(id)) throw invalid("迁移 ID 格式错误或存在跨类型重复 ID。"); }
    private void require(String value, int max, String label) { if (value == null || value.isBlank() || value.length() > max) throw invalid(label + "为空或超过长度限制。"); }
    private void bounds(List<?> items, int max, String label) { if (items == null || items.size() > max || items.stream().anyMatch(Objects::isNull)) throw invalid(label + "列表为空、含空项或超过 " + max + " 项。"); }
    private void status(String value) { if (!"DRAFT".equals(value) && !"PUBLISHED".equals(value)) throw invalid("发布状态不合法。"); }
    private void timestamp(Instant value) {
        // MySQL TIMESTAMP(3) is narrower than java.time.Instant; avoid truncation and cross-profile failures.
        if (value.isBefore(Instant.parse("1970-01-01T00:00:01Z")) || value.isAfter(Instant.parse("2038-01-19T03:14:07.499Z"))
                || value.getNano() % 1_000_000 != 0) throw invalid("时间必须在 MySQL TIMESTAMP 范围内且精度不超过毫秒。");
    }
    private String environment(String value) { return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT); }
    private String filePath(String project, String id) { return "/api/v1/projects/" + project + "/files/" + id + "/download"; }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "迁移资料不含文件最多 8 MiB，含文件最多 32 MiB。"); }

    public record ImportRequest(String requestKey, String expectedUserId, Snapshot snapshot) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(int version, String sourceProjectId, List<Catalog> catalogs, List<Definition> definitions,
                           List<Version> versions, List<Case> cases, FunctionalTransferFiles.Definitions files,
                           List<GrpcDescriptorTransferService.Item> grpcDescriptors, List<ApiHistoryTransferService.Item> debugHistory) {
        public Snapshot(int version, String sourceProjectId, List<Catalog> catalogs, List<Definition> definitions,
                        List<Version> versions, List<Case> cases, FunctionalTransferFiles.Definitions files) {
            this(version, sourceProjectId, catalogs, definitions, versions, cases, files, null, null);
        }
        public Snapshot(int version, String sourceProjectId, List<Catalog> catalogs, List<Definition> definitions,
                        List<Version> versions, List<Case> cases, FunctionalTransferFiles.Definitions files,
                        List<GrpcDescriptorTransferService.Item> grpcDescriptors) {
            this(version, sourceProjectId, catalogs, definitions, versions, cases, files, grpcDescriptors, null);
        }
    }
    public record Catalog(String id, String parentId, String name, int sortOrder) { }
    public record Definition(String id, String catalogId, String method, String path, String name, String status, String currentVersionId) { }
    public record Version(String id, String apiId, String versionName, String summary, String description,
                          List<ApiDesignService.ParameterInput> headers, List<ApiDesignService.ParameterInput> query,
                          List<ApiDesignService.ParameterInput> pathParameters, String requestBody, String responseBody,
                          Map<String, Object> exampleResponse, String status, Instant publishedAt, Instant createdAt) { }
    public record Case(String id, String apiId, String name, String environmentCode, JsonNode requestSnapshot,
                       Integer expectedStatus, String responseValidation, String savedResponseBody) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(String id, int catalogs, int definitions, int versions, int cases, String rootId, FunctionalTransferFiles.Counts files, Integer grpcDescriptors, Integer debugHistory) { }
}
