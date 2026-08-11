package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiDesignRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ApiDesignRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ApiCatalog> findCatalogs(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, parent_id, name, sort_order, created_by, created_at
                FROM api_catalog WHERE project_id = :projectId ORDER BY sort_order, name
                """).param("projectId", projectId).query((rs, row) -> catalog(rs)).list();
    }

    public Optional<ApiCatalog> findCatalog(String projectId, String catalogId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, parent_id, name, sort_order, created_by, created_at
                FROM api_catalog WHERE project_id = :projectId AND id = :catalogId
                """).param("projectId", projectId).param("catalogId", catalogId).query((rs, row) -> catalog(rs)).optional();
    }

    public void insertCatalog(String id, String organizationId, String projectId, String parentId, String name, int sortOrder, String userId) {
        jdbc.sql("""
                INSERT INTO api_catalog (id, organization_id, project_id, parent_id, name, sort_order, created_by)
                VALUES (:id, :organizationId, :projectId, :parentId, :name, :sortOrder, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("parentId", parentId).param("name", name).param("sortOrder", sortOrder).param("userId", userId).update();
    }

    public boolean catalogNameExists(String projectId, String parentId, String name, String excludedId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM api_catalog
                WHERE project_id = :projectId AND name = :name AND id <> :excludedId
                  AND ((parent_id IS NULL AND :parentId IS NULL) OR parent_id = :parentId)
                """).param("projectId", projectId).param("name", name).param("excludedId", excludedId)
                .param("parentId", parentId).query(Integer.class).single() > 0;
    }

    public int updateCatalog(String projectId, String catalogId, String parentId, String name, int sortOrder) {
        return jdbc.sql("""
                UPDATE api_catalog SET parent_id = :parentId, name = :name, sort_order = :sortOrder
                WHERE project_id = :projectId AND id = :catalogId
                """).param("projectId", projectId).param("catalogId", catalogId).param("parentId", parentId)
                .param("name", name).param("sortOrder", sortOrder).update();
    }

    public boolean catalogHasChildren(String projectId, String catalogId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM api_catalog
                WHERE project_id = :projectId AND parent_id = :catalogId
                """).param("projectId", projectId).param("catalogId", catalogId).query(Integer.class).single() > 0;
    }

    public boolean catalogHasDefinitions(String projectId, String catalogId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM api_definition
                WHERE project_id = :projectId AND catalog_id = :catalogId
                """).param("projectId", projectId).param("catalogId", catalogId).query(Integer.class).single() > 0;
    }

    public int deleteCatalog(String projectId, String catalogId) {
        return jdbc.sql("DELETE FROM api_catalog WHERE project_id = :projectId AND id = :catalogId")
                .param("projectId", projectId).param("catalogId", catalogId).update();
    }

    public List<ApiDefinition> findDefinitions(String projectId, String catalogId) {
        return jdbc.sql(definitionQuery("WHERE d.project_id = :projectId AND (:catalogId IS NULL OR d.catalog_id = :catalogId)"))
                .param("projectId", projectId).param("catalogId", catalogId).query((rs, row) -> definition(rs)).list();
    }

    public Optional<ApiDefinition> findDefinition(String projectId, String apiId) {
        return jdbc.sql(definitionQuery("WHERE d.project_id = :projectId AND d.id = :apiId")).param("projectId", projectId)
                .param("apiId", apiId).query((rs, row) -> definition(rs)).optional();
    }

    public void insertDefinition(String id, String organizationId, String projectId, String catalogId, String method, String path,
                                 String name, String userId) {
        jdbc.sql("""
                INSERT INTO api_definition (id, organization_id, project_id, catalog_id, method, path, name, status, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, :method, :path, :name, 'DRAFT', :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId).param("catalogId", catalogId)
                .param("method", method).param("path", path).param("name", name).param("userId", userId).update();
    }

    public int updateDefinition(String projectId, String apiId, String catalogId, String method, String path, String name,
                                String userId) {
        return jdbc.sql("""
                UPDATE api_definition
                SET catalog_id = :catalogId, method = :method, path = :path, name = :name, updated_by = :userId
                WHERE project_id = :projectId AND id = :apiId
                """).param("projectId", projectId).param("apiId", apiId).param("catalogId", catalogId)
                .param("method", method).param("path", path).param("name", name).param("userId", userId).update();
    }

    public int updateDefinitionMetadata(String projectId, String apiId, String catalogId, String method, String path,
                                        String name, String userId) {
        return jdbc.sql("""
                UPDATE api_definition
                SET catalog_id = :catalogId, method = :method, path = :path, name = :name, updated_by = :userId
                WHERE project_id = :projectId AND id = :apiId
                  AND (status <> 'PUBLISHED' OR (method = :method AND path = :path))
                """).param("projectId", projectId).param("apiId", apiId).param("catalogId", catalogId)
                .param("method", method).param("path", path).param("name", name).param("userId", userId).update();
    }

    public int deleteDefinition(String projectId, String apiId) {
        return jdbc.sql("DELETE FROM api_definition WHERE project_id = :projectId AND id = :apiId")
                .param("projectId", projectId).param("apiId", apiId).update();
    }

    public void updateCurrentVersion(String projectId, String apiId, String versionId, String status, String userId) {
        jdbc.sql("""
                UPDATE api_definition SET current_version_id = :versionId, status = :status, updated_by = :userId
                WHERE project_id = :projectId AND id = :apiId
                """).param("projectId", projectId).param("apiId", apiId).param("versionId", versionId).param("status", status).param("userId", userId).update();
    }

    public List<ApiDefinitionVersion> findVersions(String projectId, String apiId) {
        return jdbc.sql(versionQuery("WHERE d.project_id = :projectId AND v.api_id = :apiId")).param("projectId", projectId)
                .param("apiId", apiId).query((rs, row) -> version(rs)).list();
    }

    public Optional<ApiDefinitionVersion> findVersion(String projectId, String apiId, String versionId) {
        return jdbc.sql(versionQuery("WHERE d.project_id = :projectId AND v.api_id = :apiId AND v.id = :versionId"))
                .param("projectId", projectId).param("apiId", apiId).param("versionId", versionId).query((rs, row) -> version(rs)).optional();
    }

    public List<ApiMockExampleSource> findPublishedMockExamples(String projectId, String method) {
        return jdbc.sql("""
                SELECT d.id, d.name, d.method, d.path, v.version_name, v.response_body, v.example_response
                FROM api_definition d JOIN api_definition_version v ON v.id = d.current_version_id
                WHERE d.project_id = :projectId AND d.method = :method
                  AND d.status = 'PUBLISHED' AND v.status = 'PUBLISHED'
                ORDER BY d.created_at ASC, d.id ASC
                """).param("projectId", projectId).param("method", method)
                .query((rs, row) -> new ApiMockExampleSource(rs.getString("id"), rs.getString("name"), rs.getString("method"),
                        rs.getString("path"), rs.getString("version_name"), rs.getString("response_body"),
                        rs.getString("example_response"))).list();
    }

    public void insertVersion(String id, String apiId, String versionName, String summary, String description,
                              String headers, String query, String path, String requestBody, String responseBody,
                              String exampleResponse, String userId) {
        jdbc.sql("""
                INSERT INTO api_definition_version
                    (id, api_id, version_name, summary, description, request_headers, request_query, request_path, request_body, response_body, example_response, status, created_by)
                VALUES (:id, :apiId, :versionName, :summary, :description, :headers, :query, :path, :requestBody, :responseBody, :exampleResponse, 'DRAFT', :userId)
                """).param("id", id).param("apiId", apiId).param("versionName", versionName).param("summary", summary)
                .param("description", description).param("headers", headers).param("query", query).param("path", path)
                .param("requestBody", requestBody).param("responseBody", responseBody).param("exampleResponse", exampleResponse).param("userId", userId).update();
    }

    public int publishVersion(String projectId, String apiId, String versionId, String userId) {
        return jdbc.sql("""
                UPDATE api_definition_version v JOIN api_definition d ON d.id = v.api_id
                SET v.status = 'PUBLISHED', v.published_by = :userId, v.published_at = CURRENT_TIMESTAMP(3)
                WHERE d.project_id = :projectId AND v.api_id = :apiId AND v.id = :versionId
                """).param("projectId", projectId).param("apiId", apiId).param("versionId", versionId).param("userId", userId).update();
    }

    public List<ApiDefinitionCase> findCases(String projectId, String apiId) {
        return jdbc.sql(caseQuery("""
                WHERE c.project_id = :projectId AND c.api_id = :apiId
                ORDER BY c.updated_at DESC, c.name
                """)).param("projectId", projectId).param("apiId", apiId)
                .query((rs, row) -> definitionCase(rs)).list();
    }

    public List<ApiDefinitionCase> findCasesByProject(String projectId) {
        return jdbc.sql(caseQuery("""
                WHERE c.project_id = :projectId
                ORDER BY c.updated_at DESC, c.name
                """)).param("projectId", projectId)
                .query((rs, row) -> definitionCase(rs)).list();
    }

    public Optional<ApiDefinitionCase> findCase(String projectId, String apiId, String caseId) {
        return jdbc.sql(caseQuery("""
                WHERE c.project_id = :projectId AND c.api_id = :apiId AND c.id = :caseId
                """)).param("projectId", projectId).param("apiId", apiId).param("caseId", caseId)
                .query((rs, row) -> definitionCase(rs)).optional();
    }

    public void insertCase(String id, String organizationId, String projectId, String apiId, String name,
                           String environmentCode, String requestSnapshot, Integer expectedStatus,
                           String responseValidation, String savedResponseBody, String userId) {
        jdbc.sql("""
                INSERT INTO api_definition_case
                    (id, organization_id, project_id, api_id, name, environment_code, request_snapshot,
                     expected_status, response_validation, saved_response_body, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :apiId, :name, :environmentCode, :requestSnapshot,
                        :expectedStatus, :responseValidation, :savedResponseBody, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("apiId", apiId).param("name", name).param("environmentCode", environmentCode)
                .param("requestSnapshot", requestSnapshot).param("expectedStatus", expectedStatus)
                .param("responseValidation", responseValidation).param("savedResponseBody", savedResponseBody)
                .param("userId", userId).update();
    }

    public int updateCase(String projectId, String apiId, String caseId, String name, String environmentCode,
                          String requestSnapshot, Integer expectedStatus, String responseValidation,
                          String savedResponseBody, String userId) {
        return jdbc.sql("""
                UPDATE api_definition_case
                SET name = :name, environment_code = :environmentCode, request_snapshot = :requestSnapshot,
                    expected_status = :expectedStatus, response_validation = :responseValidation,
                    saved_response_body = :savedResponseBody, updated_by = :userId
                WHERE project_id = :projectId AND api_id = :apiId AND id = :caseId
                """).param("projectId", projectId).param("apiId", apiId).param("caseId", caseId)
                .param("name", name).param("environmentCode", environmentCode).param("requestSnapshot", requestSnapshot)
                .param("expectedStatus", expectedStatus).param("responseValidation", responseValidation)
                .param("savedResponseBody", savedResponseBody).param("userId", userId).update();
    }

    public int deleteCase(String projectId, String apiId, String caseId) {
        return jdbc.sql("""
                DELETE FROM api_definition_case
                WHERE project_id = :projectId AND api_id = :apiId AND id = :caseId
                """).param("projectId", projectId).param("apiId", apiId).param("caseId", caseId).update();
    }

    private String definitionQuery(String where) {
        return """
                SELECT d.id, d.organization_id, d.project_id, d.catalog_id, d.method, d.path, d.name, d.status, d.current_version_id,
                       d.created_by, d.updated_by, d.created_at, d.updated_at, COUNT(v.id) AS version_count
                FROM api_definition d LEFT JOIN api_definition_version v ON v.api_id = d.id
                """ + where + "\n" + """
                GROUP BY d.id, d.organization_id, d.project_id, d.catalog_id, d.method, d.path, d.name, d.status, d.current_version_id,
                         d.created_by, d.updated_by, d.created_at, d.updated_at
                ORDER BY d.updated_at DESC, d.method, d.path
                """;
    }

    private String versionQuery(String where) {
        return """
                SELECT v.id, v.api_id, v.version_name, v.summary, v.description, v.request_headers, v.request_query, v.request_path,
                       v.request_body, v.response_body, v.example_response, v.status, v.published_by, v.published_at, v.created_by, v.created_at
                FROM api_definition_version v JOIN api_definition d ON d.id = v.api_id
                """ + where + "\n" + """
                ORDER BY v.created_at DESC
                """;
    }

    private String caseQuery(String where) {
        return """
                SELECT c.id, c.organization_id, c.project_id, c.api_id, c.name, c.environment_code,
                       c.request_snapshot, c.expected_status, c.response_validation, c.saved_response_body,
                       c.created_by, c.updated_by, c.created_at, c.updated_at
                FROM api_definition_case c
                """ + where;
    }

    private ApiCatalog catalog(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiCatalog(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("parent_id"),
                rs.getString("name"), rs.getInt("sort_order"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }
    private ApiDefinition definition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiDefinition(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("catalog_id"),
                rs.getString("method"), rs.getString("path"), rs.getString("name"), rs.getString("status"), rs.getString("current_version_id"),
                rs.getString("created_by"), rs.getString("updated_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("version_count"));
    }
    private ApiDefinitionVersion version(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiDefinitionVersion(rs.getString("id"), rs.getString("api_id"), rs.getString("version_name"), rs.getString("summary"), rs.getString("description"),
                rs.getString("request_headers"), rs.getString("request_query"), rs.getString("request_path"), rs.getString("request_body"),
                rs.getString("response_body"), rs.getString("example_response"), rs.getString("status"), rs.getString("published_by"),
                instant(rs.getTimestamp("published_at")), rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }
    private ApiDefinitionCase definitionCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiDefinitionCase(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("api_id"), rs.getString("name"), rs.getString("environment_code"),
                jsonNode(rs.getString("request_snapshot")), rs.getObject("expected_status", Integer.class),
                rs.getString("response_validation"), rs.getString("saved_response_body"), rs.getString("created_by"),
                rs.getString("updated_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }
    private JsonNode jsonNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的接口调试用例快照不是有效 JSON", exception);
        }
    }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
