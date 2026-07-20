package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiDesignService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
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
        String id = UlidCreator.getUlid().toString();
        try {
            repository.insertCatalog(id, project.organizationId(), projectId, parentId, name, input.sortOrder(), principal.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同级接口目录名称已存在", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "API_CATALOG_CREATED", "API_CATALOG", id, Map.of("name", name));
        return repository.findCatalog(projectId, id).orElseThrow();
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
    private ApiCatalog requireCatalog(String projectId, String catalogId) {
        return repository.findCatalog(projectId, catalogId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口目录不存在"));
    }
    private ApiDefinition requireDefinition(String projectId, String apiId) {
        return repository.findDefinition(projectId, apiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在"));
    }
    private ValidatedDefinition validate(DefinitionInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口定义参数不能为空");
        return new ValidatedDefinition(required(input.catalogId(), 26, "接口目录"), method(input.method()), path(input.path()), required(input.name(), 200, "接口名称"),
                validateVersion(new VersionInput(input.versionName(), input.summary(), input.description(), input.headers(), input.query(), input.pathParameters(), input.requestBody(), input.responseBody(), input.exampleResponse())));
    }
    private ValidatedVersion validateVersion(VersionInput input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口版本参数不能为空");
        String versionName = required(input.versionName(), 40, "版本名称");
        if (!versionName.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本名称需符合语义化版本，例如 1.0.0");
        return new ValidatedVersion(versionName, text(input.summary(), 500, "摘要"), text(input.description(), 20_000, "接口说明"),
                input.headers() == null ? List.of() : input.headers(), input.query() == null ? List.of() : input.query(),
                input.pathParameters() == null ? List.of() : input.pathParameters(), text(input.requestBody(), 100_000, "请求体"),
                text(input.responseBody(), 100_000, "响应体"), input.exampleResponse() == null ? Map.of() : input.exampleResponse());
    }
    private String method(String value) { String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (!METHODS.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP 方法不合法"); return normalized; }
    private String path(String value) { String normalized = required(value, 700, "请求路径"); if (!normalized.startsWith("/") || normalized.contains(" ")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求路径必须以 / 开头且不能包含空格"); return normalized; }
    private String required(String value, int max, String label) { if (value == null || value.isBlank() || value.trim().length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空且不能超过" + max + "个字符"); return value.trim(); }
    private String text(String value, int max, String label) { if (value != null && value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + max + "个字符"); return value == null ? "" : value.trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口参数 JSON 格式不合法", exception); } }

    public record CatalogInput(String name, String parentId, int sortOrder) { }
    public record ParameterInput(String name, String type, boolean required, String example, String description) { }
    public record DefinitionInput(String catalogId, String method, String path, String name, String versionName, String summary, String description,
                                  List<ParameterInput> headers, List<ParameterInput> query, List<ParameterInput> pathParameters,
                                  String requestBody, String responseBody, Map<String, Object> exampleResponse) { }
    public record VersionInput(String versionName, String summary, String description, List<ParameterInput> headers, List<ParameterInput> query,
                               List<ParameterInput> pathParameters, String requestBody, String responseBody, Map<String, Object> exampleResponse) { }
    public record MockExample(String apiId, String name, String method, String path, String versionName, int responseStatus,
                              String responseBody) { }
    private record ValidatedDefinition(String catalogId, String method, String path, String name, ValidatedVersion version) {
        String versionName() { return version.versionName(); } String summary() { return version.summary(); } String description() { return version.description(); }
        List<ParameterInput> headers() { return version.headers(); } List<ParameterInput> query() { return version.query(); } List<ParameterInput> pathParameters() { return version.pathParameters(); }
        String requestBody() { return version.requestBody(); } String responseBody() { return version.responseBody(); } Map<String, Object> exampleResponse() { return version.exampleResponse(); }
    }
    private record ValidatedVersion(String versionName, String summary, String description, List<ParameterInput> headers, List<ParameterInput> query,
                                    List<ParameterInput> pathParameters, String requestBody, String responseBody, Map<String, Object> exampleResponse) { }
}
