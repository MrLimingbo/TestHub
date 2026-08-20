package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiDesignService {
    private static final int MAX_DEFINITION_BODY_LENGTH = 2_000_000;
    private static final int MAX_CASE_SNAPSHOT_LENGTH = 2_000_000;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern SENSITIVE_CASE_FIELD = Pattern.compile(
            "(authorization|cookie|api[-_]?key|token|secret|password|passwd|session|jwt|access[-_]?token|refresh[-_]?token)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VARIABLE_REFERENCE = Pattern.compile(
            "^(?:(?:Bearer|Basic)\\s+)?\\{\\{[A-Za-z_][A-Za-z0-9_.-]*}}$",
            Pattern.CASE_INSENSITIVE);
    private final ApiDesignRepository repository;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ApiDesignService(ApiDesignRepository repository, AccessService access, AuditService audit, ObjectMapper objectMapper) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<ApiCatalog> catalogs(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return repository.findCatalogs(projectId);
    }

    @Transactional
    public ApiCatalog createCatalog(AuthenticatedPrincipal principal, String projectId, CatalogInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口目录参数不能为空");
        String name = required(input.name(), 160, "目录名称");
        String parentId = blankToNull(input.parentId());
        if (parentId != null) requireCatalog(projectId, parentId);
        if (repository.catalogNameExists(projectId, parentId, name, "")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级接口目录名称已存在");
        }
        String id = UlidCreator.getUlid().toString();
        try {
            repository.insertCatalog(id, project.organizationId(), projectId, parentId, name, input.sortOrder(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级接口目录名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_CATALOG_CREATED", "API_CATALOG", id, Map.of("name", name));
        return repository.findCatalog(projectId, id).orElseThrow();
    }

    @Transactional
    public ApiCatalog updateCatalog(AuthenticatedPrincipal principal, String projectId, String catalogId, CatalogInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireCatalogResource(projectId, catalogId);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口目录参数不能为空");
        String name = required(input.name(), 160, "目录名称");
        String parentId = blankToNull(input.parentId());
        validateCatalogParent(projectId, catalogId, parentId);
        if (repository.catalogNameExists(projectId, parentId, name, catalogId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级接口目录名称已存在");
        }
        try {
            if (repository.updateCatalog(projectId, catalogId, parentId, name, input.sortOrder()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口目录不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级接口目录名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_CATALOG_UPDATED", "API_CATALOG", catalogId,
                Map.of("name", name, "parentId", parentId == null ? "root" : parentId, "sortOrder", input.sortOrder()));
        return repository.findCatalog(projectId, catalogId).orElseThrow();
    }

    @Transactional
    public void deleteCatalog(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        ProjectSummary project = writeAccess(principal, projectId);
        ApiCatalog catalog = requireCatalogResource(projectId, catalogId);
        if (repository.catalogHasChildren(projectId, catalogId) || repository.catalogHasDefinitions(projectId, catalogId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接口目录非空，请先移动或删除其中的目录与接口");
        }
        try {
            if (repository.deleteCatalog(projectId, catalogId) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口目录不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "接口目录非空，请先移动或删除其中的目录与接口", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_CATALOG_DELETED", "API_CATALOG", catalogId,
                Map.of("name", catalog.name()));
    }

    public List<ApiDefinition> definitions(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        access.requireProjectRead(principal, projectId);
        if (catalogId != null && !catalogId.isBlank()) requireCatalog(projectId, catalogId);
        return repository.findDefinitions(projectId, blankToNull(catalogId));
    }

    public ApiDefinition definition(AuthenticatedPrincipal principal, String projectId, String apiId) {
        access.requireProjectRead(principal, projectId);
        return requireDefinition(projectId, apiId);
    }

    @Transactional
    public ApiDefinition createDefinition(AuthenticatedPrincipal principal, String projectId, DefinitionInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        ValidatedDefinition definition = validate(input);
        requireCatalog(projectId, definition.catalogId());
        String apiId = UlidCreator.getUlid().toString();
        String versionId = UlidCreator.getUlid().toString();
        try {
            repository.insertDefinition(apiId, project.organizationId(), projectId, definition.catalogId(), definition.method(), definition.path(), definition.name(), principal.userId());
            repository.insertVersion(versionId, apiId, definition.versionName(), definition.summary(), definition.description(), json(definition.headers()),
                    json(definition.query()), json(definition.pathParameters()), definition.requestBody(), definition.responseBody(), json(definition.exampleResponse()), principal.userId());
            repository.updateCurrentVersion(projectId, apiId, versionId, "DRAFT", principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目中请求方法与路径组合已存在，或版本名称重复", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_CREATED", "API_DEFINITION", apiId,
                Map.of("method", definition.method(), "path", definition.path(), "version", definition.versionName()));
        return requireDefinition(projectId, apiId);
    }

    @Transactional
    public ApiDefinition updateDefinition(AuthenticatedPrincipal principal, String projectId, String apiId,
                                          DefinitionMetadataInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        ApiDefinition existing = requireDefinition(projectId, apiId);
        ValidatedDefinitionMetadata metadata = validateMetadata(input);
        requireCatalog(projectId, metadata.catalogId());
        rejectPublishedRouteChange(existing, metadata.method(), metadata.path());
        try {
            if (repository.updateDefinitionMetadata(projectId, apiId, metadata.catalogId(), metadata.method(), metadata.path(),
                    metadata.name(), principal.userId()) == 0) {
                ApiDefinition current = requireDefinition(projectId, apiId);
                rejectPublishedRouteChange(current, metadata.method(), metadata.path());
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目中请求方法与路径组合已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_UPDATED", "API_DEFINITION", apiId,
                Map.of("catalogId", metadata.catalogId(), "method", metadata.method(), "path", metadata.path(),
                        "name", metadata.name()));
        return requireDefinition(projectId, apiId);
    }

    @Transactional
    public ApiDefinition reviseDefinition(AuthenticatedPrincipal principal, String projectId, String apiId,
                                          DefinitionInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefinition(projectId, apiId);
        ValidatedDefinition revision = validate(input);
        requireCatalog(projectId, revision.catalogId());
        String versionId = UlidCreator.getUlid().toString();
        try {
            if (repository.updateDefinition(projectId, apiId, revision.catalogId(), revision.method(), revision.path(),
                    revision.name(), principal.userId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在");
            }
            repository.insertVersion(versionId, apiId, revision.versionName(), revision.summary(), revision.description(),
                    json(revision.headers()), json(revision.query()), json(revision.pathParameters()),
                    revision.requestBody(), revision.responseBody(), json(revision.exampleResponse()), principal.userId());
            repository.updateCurrentVersion(projectId, apiId, versionId, "DRAFT", principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一项目中请求方法与路径组合已存在，或版本名称重复", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_REVISED", "API_DEFINITION", apiId,
                Map.of("catalogId", revision.catalogId(), "method", revision.method(), "path", revision.path(),
                        "name", revision.name(), "version", revision.versionName()));
        return requireDefinition(projectId, apiId);
    }

    @Transactional
    public void deleteDefinition(AuthenticatedPrincipal principal, String projectId, String apiId) {
        ProjectSummary project = writeAccess(principal, projectId);
        ApiDefinition definition = requireDefinition(projectId, apiId);
        if (repository.deleteDefinition(projectId, apiId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在");
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_DELETED", "API_DEFINITION", apiId,
                Map.of("method", definition.method(), "path", definition.path(), "name", definition.name(),
                        "versionCount", definition.versionCount()));
    }

    @Transactional
    public BatchDeleteResult deleteDefinitions(AuthenticatedPrincipal principal, String projectId, List<String> apiIds) {
        ProjectSummary project = writeAccess(principal, projectId);
        List<String> normalizedIds = definitionIds(apiIds);
        List<ApiDefinition> definitions = normalizedIds.stream()
                .map(apiId -> requireDefinition(projectId, apiId))
                .toList();
        for (ApiDefinition definition : definitions) {
            if (repository.deleteDefinition(projectId, definition.id()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在：" + definition.id());
            }
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITIONS_DELETED",
                "API_DEFINITION_BATCH", projectId,
                Map.of("ids", normalizedIds, "deletedCount", definitions.size()));
        return new BatchDeleteResult(definitions.size());
    }

    public List<ApiDefinitionVersion> versions(AuthenticatedPrincipal principal, String projectId, String apiId) {
        access.requireProjectRead(principal, projectId);
        requireDefinition(projectId, apiId);
        return repository.findVersions(projectId, apiId);
    }

    @Transactional
    public ApiDefinitionVersion createVersion(AuthenticatedPrincipal principal, String projectId, String apiId, VersionInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefinition(projectId, apiId);
        ValidatedVersion version = validateVersion(input);
        String versionId = UlidCreator.getUlid().toString();
        try {
            repository.insertVersion(versionId, apiId, version.versionName(), version.summary(), version.description(), json(version.headers()), json(version.query()),
                    json(version.pathParameters()), version.requestBody(), version.responseBody(), json(version.exampleResponse()), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "版本名称已存在", exception);
        }
        repository.updateCurrentVersion(projectId, apiId, versionId, "DRAFT", principal.userId());
        audit.record(principal, project.organizationId(), projectId, "API_VERSION_CREATED", "API_DEFINITION_VERSION", versionId,
                Map.of("apiId", apiId, "version", version.versionName()));
        return repository.findVersion(projectId, apiId, versionId).orElseThrow();
    }

    @Transactional
    public ApiDefinitionVersion publish(AuthenticatedPrincipal principal, String projectId, String apiId, String versionId) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefinition(projectId, apiId);
        if (repository.publishVersion(projectId, apiId, versionId, principal.userId()) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口版本不存在");
        repository.updateCurrentVersion(projectId, apiId, versionId, "PUBLISHED", principal.userId());
        audit.record(principal, project.organizationId(), projectId, "API_VERSION_PUBLISHED", "API_DEFINITION_VERSION", versionId, Map.of("apiId", apiId));
        return repository.findVersion(projectId, apiId, versionId).orElseThrow();
    }

    public List<ApiDefinitionCase> cases(AuthenticatedPrincipal principal, String projectId, String apiId) {
        access.requireProjectRead(principal, projectId);
        requireDefinition(projectId, apiId);
        return repository.findCases(projectId, apiId);
    }

    public List<ApiDefinitionCase> cases(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return repository.findCasesByProject(projectId);
    }

    @Transactional
    public ApiDefinitionCase createCase(AuthenticatedPrincipal principal, String projectId, String apiId, CaseInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefinition(projectId, apiId);
        ValidatedCase definitionCase = validateCase(input);
        String caseId = UlidCreator.getUlid().toString();
        try {
            repository.insertCase(caseId, project.organizationId(), projectId, apiId, definitionCase.name(),
                    definitionCase.environmentCode(), definitionCase.requestSnapshot(), definitionCase.expectedStatus(),
                    definitionCase.responseValidation(), definitionCase.savedResponseBody(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一接口中调试用例名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_CASE_CREATED", "API_DEFINITION_CASE",
                caseId, caseAudit(apiId, definitionCase));
        return requireCase(projectId, apiId, caseId);
    }

    @Transactional
    public ApiDefinitionCase updateCase(AuthenticatedPrincipal principal, String projectId, String apiId, String caseId,
                                        CaseInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefinition(projectId, apiId);
        requireCase(projectId, apiId, caseId);
        ValidatedCase definitionCase = validateCase(input);
        try {
            if (repository.updateCase(projectId, apiId, caseId, definitionCase.name(), definitionCase.environmentCode(),
                    definitionCase.requestSnapshot(), definitionCase.expectedStatus(), definitionCase.responseValidation(),
                    definitionCase.savedResponseBody(), principal.userId()) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口调试用例不存在");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一接口中调试用例名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_CASE_UPDATED", "API_DEFINITION_CASE",
                caseId, caseAudit(apiId, definitionCase));
        return requireCase(projectId, apiId, caseId);
    }

    @Transactional
    public void deleteCase(AuthenticatedPrincipal principal, String projectId, String apiId, String caseId) {
        ProjectSummary project = writeAccess(principal, projectId);
        requireDefinition(projectId, apiId);
        ApiDefinitionCase definitionCase = requireCase(projectId, apiId, caseId);
        if (repository.deleteCase(projectId, apiId, caseId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口调试用例不存在");
        }
        audit.record(principal, project.organizationId(), projectId, "API_DEFINITION_CASE_DELETED", "API_DEFINITION_CASE",
                caseId, Map.of("apiId", apiId, "name", definitionCase.name()));
    }

    public List<MockExample> publishedMockExamples(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return METHODS.stream()
                .flatMap(method -> repository.findPublishedMockExamples(projectId, method).stream())
                .map(this::mockExample)
                .sorted(Comparator.comparing(MockExample::method).thenComparing(MockExample::path).thenComparing(MockExample::apiId))
                .toList();
    }

    public Optional<MockExample> runtimeMockExample(String projectId, String method, String path) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(normalized) || path == null || path.isBlank()) return Optional.empty();
        return repository.findPublishedMockExamples(projectId, normalized).stream()
                .map(this::mockExample)
                .filter(example -> pathMatches(example.path(), path))
                .sorted(Comparator.comparingInt((MockExample example) -> pathSpecificity(example.path())).reversed()
                        .thenComparing(MockExample::path).thenComparing(MockExample::apiId))
                .findFirst();
    }

    private MockExample mockExample(ApiMockExampleSource source) {
        String responseBody = source.responseBody() == null || source.responseBody().isBlank()
                ? "{\"code\":0,\"message\":\"Mock response\"}" : source.responseBody();
        return new MockExample(source.apiId(), source.name(), source.method(), source.path(), source.versionName(),
                responseStatus(source.exampleResponse()), responseBody);
    }

    private int responseStatus(String value) {
        try {
            JsonNode node = objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
            int status = node.path("statusCode").asInt(200);
            return status >= 200 && status <= 599 ? status : 200;
        } catch (JsonProcessingException exception) {
            return 200;
        }
    }

    private boolean pathMatches(String template, String path) {
        String[] expected = template.split("/", -1);
        String[] actual = path.split("/", -1);
        if (expected.length != actual.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (pathParameter(expected[index])) {
                if (actual[index].isBlank()) return false;
            } else if (!expected[index].equals(actual[index])) return false;
        }
        return true;
    }

    private int pathSpecificity(String template) {
        int specificity = 0;
        for (String segment : template.split("/", -1)) if (!pathParameter(segment)) specificity++;
        return specificity;
    }

    private boolean pathParameter(String value) {
        return value.startsWith("{") && value.endsWith("}") && value.length() > 2;
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }
    private void validateCatalogParent(String projectId, String catalogId, String parentId) {
        String candidate = parentId;
        Set<String> visited = new java.util.HashSet<>();
        while (candidate != null) {
            if (candidate.equals(catalogId) || !visited.add(candidate)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口目录不能移动到自身或其子目录");
            }
            candidate = blankToNull(requireCatalog(projectId, candidate).parentId());
        }
    }
    private ApiCatalog requireCatalog(String projectId, String catalogId) {
        return repository.findCatalog(projectId, catalogId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口目录不存在"));
    }
    private ApiCatalog requireCatalogResource(String projectId, String catalogId) {
        return repository.findCatalog(projectId, catalogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口目录不存在"));
    }
    private ApiDefinition requireDefinition(String projectId, String apiId) {
        return repository.findDefinition(projectId, apiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在"));
    }
    private void rejectPublishedRouteChange(ApiDefinition definition, String method, String path) {
        if ("PUBLISHED".equals(definition.status())
                && (!definition.method().equals(method) || !definition.path().equals(path))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "已发布接口不能直接修改请求方法或路径，请通过新版本修订");
        }
    }
    private List<String> definitionIds(List<String> apiIds) {
        if (apiIds == null || apiIds.isEmpty() || apiIds.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量删除接口数量应在1到200之间");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String apiId : apiIds) {
            normalized.add(required(apiId, 26, "接口ID"));
        }
        if (normalized.size() != apiIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批量删除接口ID不能重复");
        }
        return List.copyOf(normalized);
    }
    private ApiDefinitionCase requireCase(String projectId, String apiId, String caseId) {
        return repository.findCase(projectId, apiId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口调试用例不存在"));
    }
    private ValidatedDefinition validate(DefinitionInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口定义参数不能为空");
        return new ValidatedDefinition(required(input.catalogId(), 26, "接口目录"), method(input.method()), path(input.path()), required(input.name(), 200, "接口名称"),
                validateVersion(new VersionInput(input.versionName(), input.summary(), input.description(), input.headers(), input.query(), input.pathParameters(), input.requestBody(), input.responseBody(), input.exampleResponse())));
    }
    private ValidatedDefinitionMetadata validateMetadata(DefinitionMetadataInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口元数据参数不能为空");
        return new ValidatedDefinitionMetadata(required(input.catalogId(), 26, "接口目录"), method(input.method()),
                path(input.path()), required(input.name(), 200, "接口名称"));
    }
    private ValidatedVersion validateVersion(VersionInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口版本参数不能为空");
        String versionName = required(input.versionName(), 40, "版本名称");
        if (!versionName.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本名称需符合语义化版本，例如 1.0.0");
        return new ValidatedVersion(versionName, text(input.summary(), 500, "摘要"), text(input.description(), 20_000, "接口说明"),
                parameters(input.headers(), "请求头"), parameters(input.query(), "查询参数"),
                parameters(input.pathParameters(), "路径参数"), text(input.requestBody(), MAX_DEFINITION_BODY_LENGTH, "请求体"),
                text(input.responseBody(), MAX_DEFINITION_BODY_LENGTH, "响应体"), input.exampleResponse() == null ? Map.of() : input.exampleResponse());
    }
    private List<ParameterInput> parameters(List<ParameterInput> values, String label) {
        if (values == null) return List.of();
        if (values.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过200项");
        }
        List<ParameterInput> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            ParameterInput value = values.get(index);
            if (value == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        label + "第" + (index + 1) + "项不能为空");
            }
            result.add(new ParameterInput(
                    required(value.name(), 120, label + "名称"),
                    required(value.type(), 40, label + "类型"),
                    value.required(),
                    text(value.example(), 1_000, label + "示例"),
                    text(value.description(), 2_000, label + "说明")));
        }
        return List.copyOf(result);
    }
    private String method(String value) { String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (!METHODS.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP 方法不合法"); return normalized; }
    private String path(String value) { String normalized = required(value, 700, "请求路径"); if (!normalized.startsWith("/") || normalized.contains(" ")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求路径必须以 / 开头且不能包含空格"); return normalized; }
    private ValidatedCase validateCase(CaseInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口调试用例参数不能为空");
        String name = required(input.name(), 200, "用例名称");
        String environmentCode = blankToNull(input.environmentCode());
        if (environmentCode != null) {
            environmentCode = environmentCode.toLowerCase(Locale.ROOT);
            if (!environmentCode.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "环境标识格式不合法");
            }
        }
        if (input.requestSnapshot() == null || !input.requestSnapshot().isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求快照必须是 JSON 对象");
        }
        String requestSnapshot = json(sanitizeCaseSnapshot(input.requestSnapshot()));
        if (requestSnapshot.length() > MAX_CASE_SNAPSHOT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求快照不能超过" + MAX_CASE_SNAPSHOT_LENGTH + "个字符");
        }
        Integer expectedStatus = input.expectedStatus();
        if (expectedStatus != null && (expectedStatus < 100 || expectedStatus > 599)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预期状态码应在100到599之间");
        }
        return new ValidatedCase(name, environmentCode, requestSnapshot, expectedStatus,
                text(input.responseValidation(), 20_000, "响应校验"), content(input.savedResponseBody(), 1_000_000, "保存的响应体"));
    }
    private JsonNode sanitizeCaseSnapshot(JsonNode source) {
        ObjectNode snapshot = ((ObjectNode) source).deepCopy();
        validateCaseBaseUrl(snapshot.get("baseUrl"));
        JsonNode auth = snapshot.get("auth");
        if (auth instanceof ObjectNode authObject) {
            List<String> fields = new ArrayList<>();
            authObject.fieldNames().forEachRemaining(fields::add);
            fields.stream().filter(this::sensitiveCaseField)
                    .forEach(field -> sanitizeCaseSecret(authObject, field));
            sanitizeCaseSecret(authObject, "value");
        }
        sanitizeCaseValues(snapshot.get("headerValues"));
        sanitizeCaseValues(snapshot.get("queryValues"));
        return snapshot;
    }
    private void validateCaseBaseUrl(JsonNode baseUrlNode) {
        if (baseUrlNode == null || !baseUrlNode.isTextual() || baseUrlNode.asText().isBlank()) return;
        String baseUrl = baseUrlNode.asText().trim();
        int schemeSeparator = baseUrl.indexOf("://");
        int authorityStart = schemeSeparator >= 0 ? schemeSeparator + 3 : (baseUrl.startsWith("//") ? 2 : 0);
        int authorityEnd = firstDelimiter(baseUrl, authorityStart, '/', '?', '#');
        String authority = decodeUrlComponent(baseUrl.substring(authorityStart, authorityEnd));
        if (authority.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求服务地址不能包含用户名或密码");
        }
        int queryStart = baseUrl.indexOf('?', authorityStart);
        if (queryStart < 0) return;
        int fragmentStart = baseUrl.indexOf('#', queryStart + 1);
        String query = baseUrl.substring(queryStart + 1, fragmentStart < 0 ? baseUrl.length() : fragmentStart);
        for (String parameter : query.split("[&;]")) {
            if (parameter.isBlank()) continue;
            int separator = parameter.indexOf('=');
            String name = decodeUrlComponent(separator < 0 ? parameter : parameter.substring(0, separator));
            String value = separator < 0 ? "" : decodeUrlComponent(parameter.substring(separator + 1)).trim();
            if (sensitiveCaseField(name) && !value.isBlank() && !VARIABLE_REFERENCE.matcher(value).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "请求服务地址中的敏感查询参数必须使用变量引用");
            }
        }
    }
    private int firstDelimiter(String value, int start, char... delimiters) {
        int result = value.length();
        for (char delimiter : delimiters) {
            int candidate = value.indexOf(delimiter, start);
            if (candidate >= 0 && candidate < result) result = candidate;
        }
        return result;
    }
    private String decodeUrlComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求服务地址编码格式不合法", exception);
        }
    }
    private boolean sensitiveCaseField(String name) {
        return SENSITIVE_CASE_FIELD.matcher(name == null ? "" : name).find();
    }
    private void sanitizeCaseValues(JsonNode values) {
        if (values == null || !values.isArray()) return;
        values.forEach(value -> {
            if (value instanceof ObjectNode valueObject
                    && sensitiveCaseField(value.path("name").asText(""))) {
                sanitizeCaseSecret(valueObject, "value");
            }
        });
    }
    private void sanitizeCaseSecret(ObjectNode container, String field) {
        JsonNode value = container.get(field);
        if (value != null && !value.isNull() && (!value.isTextual()
                || (!value.asText().isBlank() && !VARIABLE_REFERENCE.matcher(value.asText().trim()).matches()))) {
            container.put(field, "");
        }
    }
    void validateDefinitionInput(DefinitionInput input) { validate(input); }
    void validateCaseInput(CaseInput input) { validateCase(input); }
    private String required(String value, int max, String label) { if (value == null || value.isBlank() || value.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符"); return value.trim(); }
    private String text(String value, int max, String label) { if (value != null && value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + max + "个字符"); return value == null ? "" : value.trim(); }
    private String content(String value, int max, String label) { if (value != null && value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + max + "个字符"); return value == null ? "" : value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口参数 JSON 格式不合法", exception); } }
    private Map<String, Object> caseAudit(String apiId, ValidatedCase definitionCase) {
        return Map.of("apiId", apiId, "name", definitionCase.name(),
                "environment", definitionCase.environmentCode() == null ? "none" : definitionCase.environmentCode(),
                "expectedStatus", definitionCase.expectedStatus() == null ? "none" : definitionCase.expectedStatus());
    }

    public record CatalogInput(String name, String parentId, int sortOrder) { }
    public record ParameterInput(String name, String type, boolean required, String example, String description) { }
    public record DefinitionMetadataInput(String catalogId, String method, String path, String name) { }
    public record DefinitionInput(String catalogId, String method, String path, String name, String versionName, String summary, String description,
                                  List<ParameterInput> headers, List<ParameterInput> query, List<ParameterInput> pathParameters,
                                  String requestBody, String responseBody, Map<String, Object> exampleResponse) { }
    public record VersionInput(String versionName, String summary, String description, List<ParameterInput> headers, List<ParameterInput> query,
                               List<ParameterInput> pathParameters, String requestBody, String responseBody, Map<String, Object> exampleResponse) { }
    public record CaseInput(String name, String environmentCode, JsonNode requestSnapshot, Integer expectedStatus,
                            String responseValidation, String savedResponseBody) { }
    public record BatchDeleteResult(int deletedCount) { }
    public record MockExample(String apiId, String name, String method, String path, String versionName, int responseStatus,
                              String responseBody) { }
    private record ValidatedDefinition(String catalogId, String method, String path, String name, ValidatedVersion version) {
        String versionName() { return version.versionName(); } String summary() { return version.summary(); } String description() { return version.description(); }
        List<ParameterInput> headers() { return version.headers(); } List<ParameterInput> query() { return version.query(); } List<ParameterInput> pathParameters() { return version.pathParameters(); }
        String requestBody() { return version.requestBody(); } String responseBody() { return version.responseBody(); } Map<String, Object> exampleResponse() { return version.exampleResponse(); }
    }
    private record ValidatedDefinitionMetadata(String catalogId, String method, String path, String name) { }
    private record ValidatedVersion(String versionName, String summary, String description, List<ParameterInput> headers, List<ParameterInput> query,
                                    List<ParameterInput> pathParameters, String requestBody, String responseBody, Map<String, Object> exampleResponse) { }
    private record ValidatedCase(String name, String environmentCode, String requestSnapshot, Integer expectedStatus,
                                 String responseValidation, String savedResponseBody) { }
}
