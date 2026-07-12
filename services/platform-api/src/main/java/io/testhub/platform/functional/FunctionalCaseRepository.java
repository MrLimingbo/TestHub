package io.testhub.platform.functional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionalCaseRepository {
    private final JdbcClient jdbc;

    public FunctionalCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FunctionalCase> findByProject(String projectId, String catalogId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, catalog_id, requirement_id, case_code, title, priority, status,
                       precondition, steps, expected_result, created_by, updated_by, created_at, updated_at
                FROM functional_case
                WHERE project_id = :projectId AND (:catalogId IS NULL OR catalog_id = :catalogId)
                ORDER BY updated_at DESC, case_code
                """).param("projectId", projectId).param("catalogId", catalogId).query((rs, row) -> map(rs)).list();
    }

    public Optional<FunctionalCase> findById(String projectId, String id) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, catalog_id, requirement_id, case_code, title, priority, status,
                       precondition, steps, expected_result, created_by, updated_by, created_at, updated_at
                FROM functional_case WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).query((rs, row) -> map(rs)).optional();
    }

    public void insert(String id, String organizationId, String projectId, String catalogId, String requirementId,
                       String code, String title, String priority, String status, String precondition, String steps,
                       String expectedResult, String userId) {
        jdbc.sql("""
                INSERT INTO functional_case
                    (id, organization_id, project_id, catalog_id, requirement_id, case_code, title, priority, status,
                     precondition, steps, expected_result, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, :requirementId, :code, :title, :priority, :status,
                        :precondition, :steps, :expectedResult, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("catalogId", catalogId).param("requirementId", requirementId).param("code", code)
                .param("title", title).param("priority", priority).param("status", status)
                .param("precondition", precondition).param("steps", steps).param("expectedResult", expectedResult)
                .param("userId", userId).update();
    }

    public int update(String projectId, String id, String catalogId, String requirementId, String code, String title,
                      String priority, String status, String precondition, String steps, String expectedResult,
                      String userId) {
        return jdbc.sql("""
                UPDATE functional_case
                SET catalog_id = :catalogId, requirement_id = :requirementId, case_code = :code, title = :title,
                    priority = :priority, status = :status, precondition = :precondition, steps = :steps,
                    expected_result = :expectedResult, updated_by = :userId
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("catalogId", catalogId)
                .param("requirementId", requirementId).param("code", code).param("title", title)
                .param("priority", priority).param("status", status).param("precondition", precondition)
                .param("steps", steps).param("expectedResult", expectedResult).param("userId", userId).update();
    }

    public int delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM functional_case WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    private FunctionalCase map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalCase(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("catalog_id"), rs.getString("requirement_id"), rs.getString("case_code"),
                rs.getString("title"), rs.getString("priority"), rs.getString("status"),
                rs.getString("precondition"), rs.getString("steps"), rs.getString("expected_result"),
                rs.getString("created_by"), rs.getString("updated_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
