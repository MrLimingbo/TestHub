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
                SELECT c.id, c.display_id, c.organization_id, c.project_id, c.catalog_id, c.requirement_id, c.case_code, c.case_type,
                       c.title, c.priority, c.status, c.precondition, c.steps, c.expected_result, c.created_by,
                       COALESCE(creator.display_name, c.created_by) AS created_by_name, c.updated_by, c.created_at, c.updated_at,
                       latest_run_case.executed_by, COALESCE(executor.display_name, latest_run_case.executed_by) AS executed_by_name,
                       latest_run_case.executed_at
                FROM functional_case c
                LEFT JOIN user_account creator ON creator.id = c.created_by
                LEFT JOIN functional_test_run_case latest_run_case ON latest_run_case.id = (
                    SELECT run_case.id
                    FROM functional_test_run_case run_case
                    JOIN functional_test_run run ON run.id = run_case.run_id
                    WHERE run.project_id = c.project_id AND run_case.case_id = c.id AND run_case.executed_at IS NOT NULL
                    ORDER BY run_case.executed_at DESC, run_case.id DESC
                    LIMIT 1
                )
                LEFT JOIN user_account executor ON executor.id = latest_run_case.executed_by
                WHERE c.project_id = :projectId AND (:catalogId IS NULL OR c.catalog_id = :catalogId)
                ORDER BY c.updated_at DESC, c.case_code
                """).param("projectId", projectId).param("catalogId", catalogId).query((rs, row) -> map(rs)).list();
    }

    public Optional<FunctionalCase> findById(String projectId, String id) {
        return jdbc.sql("""
                SELECT c.id, c.display_id, c.organization_id, c.project_id, c.catalog_id, c.requirement_id, c.case_code, c.case_type,
                       c.title, c.priority, c.status, c.precondition, c.steps, c.expected_result, c.created_by,
                       COALESCE(creator.display_name, c.created_by) AS created_by_name, c.updated_by, c.created_at, c.updated_at,
                       latest_run_case.executed_by, COALESCE(executor.display_name, latest_run_case.executed_by) AS executed_by_name,
                       latest_run_case.executed_at
                FROM functional_case c
                LEFT JOIN user_account creator ON creator.id = c.created_by
                LEFT JOIN functional_test_run_case latest_run_case ON latest_run_case.id = (
                    SELECT run_case.id
                    FROM functional_test_run_case run_case
                    JOIN functional_test_run run ON run.id = run_case.run_id
                    WHERE run.project_id = c.project_id AND run_case.case_id = c.id AND run_case.executed_at IS NOT NULL
                    ORDER BY run_case.executed_at DESC, run_case.id DESC
                    LIMIT 1
                )
                LEFT JOIN user_account executor ON executor.id = latest_run_case.executed_by
                WHERE c.project_id = :projectId AND c.id = :id
                """).param("projectId", projectId).param("id", id).query((rs, row) -> map(rs)).optional();
    }

    public void insert(String id, String organizationId, String projectId, String catalogId, String requirementId,
                       String code, String caseType, String title, String priority, String status, String precondition, String steps,
                       String expectedResult, String userId) {
        jdbc.sql("""
                INSERT INTO functional_case
                    (id, organization_id, project_id, catalog_id, requirement_id, case_code, case_type, title, priority, status,
                     precondition, steps, expected_result, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, :requirementId, :code, :caseType, :title, :priority, :status,
                        :precondition, :steps, :expectedResult, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("catalogId", catalogId).param("requirementId", requirementId).param("code", code).param("caseType", caseType)
                .param("title", title).param("priority", priority).param("status", status)
                .param("precondition", precondition).param("steps", steps).param("expectedResult", expectedResult)
                .param("userId", userId).update();
    }

    public int update(String projectId, String id, String catalogId, String requirementId, String code, String caseType, String title,
                      String priority, String status, String precondition, String steps, String expectedResult,
                      String userId) {
        return jdbc.sql("""
                UPDATE functional_case
                SET catalog_id = :catalogId, requirement_id = :requirementId, case_code = :code, case_type = :caseType, title = :title,
                    priority = :priority, status = :status, precondition = :precondition, steps = :steps,
                    expected_result = :expectedResult, updated_by = :userId
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("catalogId", catalogId)
                .param("requirementId", requirementId).param("code", code).param("caseType", caseType).param("title", title)
                .param("priority", priority).param("status", status).param("precondition", precondition)
                .param("steps", steps).param("expectedResult", expectedResult).param("userId", userId).update();
    }

    public int delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM functional_case WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    private FunctionalCase map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalCase(rs.getString("id"), rs.getLong("display_id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("catalog_id"), rs.getString("requirement_id"), rs.getString("case_code"), rs.getString("case_type"),
                rs.getString("title"), rs.getString("priority"), rs.getString("status"),
                rs.getString("precondition"), rs.getString("steps"), rs.getString("expected_result"),
                rs.getString("created_by"), rs.getString("created_by_name"), rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getString("executed_by"),
                rs.getString("executed_by_name"), instant(rs.getTimestamp("executed_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
