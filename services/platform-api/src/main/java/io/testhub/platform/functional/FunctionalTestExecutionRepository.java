package io.testhub.platform.functional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionalTestExecutionRepository {
    private final JdbcClient jdbc;

    public FunctionalTestExecutionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FunctionalTestPlan> findPlans(String projectId, String catalogId) {
        return jdbc.sql("""
                SELECT p.id, p.organization_id, p.project_id, p.catalog_id, p.plan_code, p.name, p.status, p.description,
                       p.created_by, p.updated_by, p.created_at, p.updated_at, COUNT(pc.id) AS case_count
                FROM functional_test_plan p
                LEFT JOIN functional_test_plan_case pc ON pc.plan_id = p.id
                WHERE p.project_id = :projectId AND (:catalogId IS NULL OR p.catalog_id = :catalogId)
                GROUP BY p.id, p.organization_id, p.project_id, p.catalog_id, p.plan_code, p.name, p.status, p.description,
                         p.created_by, p.updated_by, p.created_at, p.updated_at
                ORDER BY p.updated_at DESC
                """).param("projectId", projectId).param("catalogId", catalogId).query((rs, row) -> plan(rs)).list();
    }

    public Optional<FunctionalTestPlan> findPlan(String projectId, String planId) {
        return jdbc.sql("""
                SELECT p.id, p.organization_id, p.project_id, p.catalog_id, p.plan_code, p.name, p.status, p.description,
                       p.created_by, p.updated_by, p.created_at, p.updated_at, COUNT(pc.id) AS case_count
                FROM functional_test_plan p
                LEFT JOIN functional_test_plan_case pc ON pc.plan_id = p.id
                WHERE p.project_id = :projectId AND p.id = :planId
                GROUP BY p.id, p.organization_id, p.project_id, p.catalog_id, p.plan_code, p.name, p.status, p.description,
                         p.created_by, p.updated_by, p.created_at, p.updated_at
                """).param("projectId", projectId).param("planId", planId).query((rs, row) -> plan(rs)).optional();
    }

    public void insertPlan(String id, String organizationId, String projectId, String catalogId, String planCode, String name,
                            String status, String description, String userId) {
        jdbc.sql("""
                INSERT INTO functional_test_plan
                    (id, organization_id, project_id, catalog_id, plan_code, name, status, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, :planCode, :name, :status, :description, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("catalogId", catalogId).param("planCode", planCode).param("name", name).param("status", status).param("description", description)
                .param("userId", userId).update();
    }

    public int updatePlan(String projectId, String planId, String catalogId, String name, String status, String description, String userId) {
        return jdbc.sql("""
                UPDATE functional_test_plan SET catalog_id = :catalogId, name = :name, status = :status, description = :description, updated_by = :userId
                WHERE project_id = :projectId AND id = :planId
                """).param("projectId", projectId).param("planId", planId).param("catalogId", catalogId).param("name", name).param("status", status)
                .param("description", description).param("userId", userId).update();
    }

    public void deleteRunsByPlan(String projectId, String planId) {
        jdbc.sql("DELETE FROM functional_test_run WHERE project_id = :projectId AND plan_id = :planId")
                .param("projectId", projectId).param("planId", planId).update();
    }

    public int deletePlan(String projectId, String planId) {
        return jdbc.sql("DELETE FROM functional_test_plan WHERE project_id = :projectId AND id = :planId")
                .param("projectId", projectId).param("planId", planId).update();
    }

    public void clearPlanCases(String planId) {
        jdbc.sql("DELETE FROM functional_test_plan_case WHERE plan_id = :planId").param("planId", planId).update();
    }

    public void insertPlanCase(String id, String planId, String caseId, int sortOrder) {
        jdbc.sql("""
                INSERT INTO functional_test_plan_case (id, plan_id, case_id, sort_order)
                VALUES (:id, :planId, :caseId, :sortOrder)
                """).param("id", id).param("planId", planId).param("caseId", caseId).param("sortOrder", sortOrder).update();
    }

    public List<FunctionalTestPlanCase> findPlanCases(String projectId, String planId) {
        return jdbc.sql("""
                SELECT pc.id, pc.plan_id, pc.case_id, pc.sort_order, c.case_code, c.title, c.priority, c.status
                FROM functional_test_plan_case pc
                JOIN functional_case c ON c.id = pc.case_id
                WHERE pc.plan_id = :planId AND c.project_id = :projectId
                ORDER BY pc.sort_order, c.case_code
                """).param("projectId", projectId).param("planId", planId).query((rs, row) -> new FunctionalTestPlanCase(
                rs.getString("id"), rs.getString("plan_id"), rs.getString("case_id"), rs.getInt("sort_order"),
                rs.getString("case_code"), rs.getString("title"), rs.getString("priority"), rs.getString("status"))).list();
    }

    public List<FunctionalTestRun> findRuns(String projectId, String planId) {
        return jdbc.sql(runQuery("WHERE r.project_id = :projectId AND (:planId IS NULL OR r.plan_id = :planId)"))
                .param("projectId", projectId).param("planId", planId).query((rs, row) -> run(rs)).list();
    }

    public List<FunctionalTestRun> findRecentRuns(String projectId) {
        return jdbc.sql(runQuery("WHERE r.project_id = :projectId").stripTrailing()
                        + ", r.id DESC LIMIT 100")
                .param("projectId", projectId).query((rs, row) -> run(rs)).list();
    }

    public Optional<FunctionalTestRun> findRun(String projectId, String runId) {
        return jdbc.sql(runQuery("WHERE r.project_id = :projectId AND r.id = :runId"))
                .param("projectId", projectId).param("runId", runId)
                .query((rs, row) -> run(rs)).optional();
    }

    public void insertRun(String id, String organizationId, String projectId, String planId, String name,
                           String status, String userId) {
        jdbc.sql("""
                INSERT INTO functional_test_run (id, organization_id, project_id, plan_id, name, status, created_by)
                VALUES (:id, :organizationId, :projectId, :planId, :name, :status, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("planId", planId).param("name", name).param("status", status).param("userId", userId).update();
    }

    public int updateRunStatus(String runId, String status, Instant startedAt, Instant finishedAt) {
        return jdbc.sql("""
                UPDATE functional_test_run SET status = :status,
                    started_at = COALESCE(started_at, :startedAt),
                    finished_at = CASE WHEN :status = 'COMPLETED' THEN COALESCE(finished_at, :finishedAt) ELSE NULL END
                WHERE id = :runId
                """).param("runId", runId).param("status", status).param("startedAt", startedAt)
                .param("finishedAt", finishedAt).update();
    }

    public Optional<String> lockRunForCase(String projectId, String runCaseId) {
        return jdbc.sql("""
                SELECT id FROM functional_test_run
                WHERE project_id = :projectId
                  AND id = (SELECT run_id FROM functional_test_run_case WHERE id = :caseId)
                FOR UPDATE
                """).param("projectId", projectId).param("caseId", runCaseId).query(String.class).optional();
    }

    public void insertRunCase(String id, String runId, String caseId, String status, String note) {
        jdbc.sql("""
                INSERT INTO functional_test_run_case (id, run_id, case_id, status, note)
                VALUES (:id, :runId, :caseId, :status, :note)
                """).param("id", id).param("runId", runId).param("caseId", caseId).param("status", status)
                .param("note", note).update();
    }

    public List<FunctionalTestRunCase> findRunCases(String projectId, String runId) {
        return jdbc.sql("""
                SELECT rc.id, rc.run_id, rc.case_id, c.case_code, c.title, rc.status, rc.note
                FROM functional_test_run_case rc
                JOIN functional_test_run r ON r.id = rc.run_id
                JOIN functional_case c ON c.id = rc.case_id
                WHERE r.project_id = :projectId AND rc.run_id = :runId
                ORDER BY c.case_code
                """).param("projectId", projectId).param("runId", runId).query((rs, row) -> new FunctionalTestRunCase(
                rs.getString("id"), rs.getString("run_id"), rs.getString("case_id"), rs.getString("case_code"),
                rs.getString("title"), rs.getString("status"), rs.getString("note"))).list();
    }

    public List<FunctionalTestRunCase> findRunCasesByRunId(String runId) {
        return jdbc.sql("""
                SELECT rc.id, rc.run_id, rc.case_id, c.case_code, c.title, rc.status, rc.note
                FROM functional_test_run_case rc
                JOIN functional_case c ON c.id = rc.case_id
                WHERE rc.run_id = :runId
                ORDER BY c.case_code
                """).param("runId", runId).query((rs, row) -> new FunctionalTestRunCase(
                rs.getString("id"), rs.getString("run_id"), rs.getString("case_id"), rs.getString("case_code"),
                rs.getString("title"), rs.getString("status"), rs.getString("note"))).list();
    }

    public Optional<FunctionalTestRunCase> findRunCase(String projectId, String runCaseId) {
        return jdbc.sql("""
                SELECT rc.id, rc.run_id, rc.case_id, c.case_code, c.title, rc.status, rc.note
                FROM functional_test_run_case rc
                JOIN functional_test_run r ON r.id = rc.run_id
                JOIN functional_case c ON c.id = rc.case_id
                WHERE r.project_id = :projectId AND rc.id = :runCaseId
                """).param("projectId", projectId).param("runCaseId", runCaseId).query((rs, row) -> new FunctionalTestRunCase(
                rs.getString("id"), rs.getString("run_id"), rs.getString("case_id"), rs.getString("case_code"),
                rs.getString("title"), rs.getString("status"), rs.getString("note"))).optional();
    }

    public int updateRunCase(String runCaseId, String status, String note, String userId, Instant executedAt) {
        return jdbc.sql("""
                UPDATE functional_test_run_case SET status = :status, note = :note, executed_by = :userId, executed_at = :executedAt
                WHERE id = :runCaseId
                """).param("runCaseId", runCaseId).param("status", status).param("note", note)
                .param("userId", userId).param("executedAt", executedAt).update();
    }

    public void insertRunStep(String id, String runCaseId, int stepNo, String stepText) {
        jdbc.sql("""
                INSERT INTO functional_test_run_step (id, run_case_id, step_no, step_text, status, actual_result)
                VALUES (:id, :runCaseId, :stepNo, :stepText, 'NOT_RUN', '')
                """).param("id", id).param("runCaseId", runCaseId).param("stepNo", stepNo).param("stepText", stepText).update();
    }

    public List<FunctionalTestRunStep> findRunSteps(String projectId, String runCaseId) {
        return jdbc.sql("""
                SELECT s.id, s.run_case_id, s.step_no, s.step_text, s.status, s.actual_result
                FROM functional_test_run_step s
                JOIN functional_test_run_case rc ON rc.id = s.run_case_id
                JOIN functional_test_run r ON r.id = rc.run_id
                WHERE r.project_id = :projectId AND s.run_case_id = :runCaseId
                ORDER BY s.step_no
                """).param("projectId", projectId).param("runCaseId", runCaseId).query((rs, row) -> new FunctionalTestRunStep(
                rs.getString("id"), rs.getString("run_case_id"), rs.getInt("step_no"), rs.getString("step_text"),
                rs.getString("status"), rs.getString("actual_result"))).list();
    }

    public int updateRunStep(String projectId, String stepId, String status, String actualResult, String userId) {
        return jdbc.sql("""
                UPDATE functional_test_run_step
                SET status = :status, actual_result = :actualResult, updated_by = :userId, updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :stepId AND run_case_id IN (
                    SELECT rc.id FROM functional_test_run_case rc
                    JOIN functional_test_run r ON r.id = rc.run_id WHERE r.project_id = :projectId
                )
                """).param("projectId", projectId).param("stepId", stepId).param("status", status)
                .param("actualResult", actualResult).param("userId", userId).update();
    }

    public Optional<String> findRunCaseIdByStep(String projectId, String stepId) {
        return jdbc.sql("""
                SELECT s.run_case_id
                FROM functional_test_run_step s
                JOIN functional_test_run_case rc ON rc.id = s.run_case_id
                JOIN functional_test_run r ON r.id = rc.run_id
                WHERE r.project_id = :projectId AND s.id = :stepId
                """).param("projectId", projectId).param("stepId", stepId).query(String.class).optional();
    }

    private String runQuery(String where) {
        return """
                SELECT r.id, r.organization_id, r.project_id, r.plan_id, r.name, r.status, r.created_by,
                       r.started_at, r.finished_at, r.created_at, r.updated_at,
                       COUNT(rc.id) AS total_count,
                       COALESCE(SUM(rc.status = 'PASSED'), 0) AS passed_count,
                       COALESCE(SUM(rc.status = 'FAILED'), 0) AS failed_count,
                       COALESCE(SUM(rc.status = 'BLOCKED'), 0) AS blocked_count,
                       COALESCE(SUM(rc.status = 'NOT_RUN'), 0) AS not_run_count
                FROM functional_test_run r
                LEFT JOIN functional_test_run_case rc ON rc.run_id = r.id
                """ + where + "\n" + """
                GROUP BY r.id, r.organization_id, r.project_id, r.plan_id, r.name, r.status,
                         r.created_by, r.started_at, r.finished_at, r.created_at, r.updated_at
                ORDER BY r.created_at DESC
                """;
    }

    private FunctionalTestPlan plan(ResultSet rs) throws SQLException {
        return new FunctionalTestPlan(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"), rs.getString("catalog_id"),
                rs.getString("plan_code"), rs.getString("name"), rs.getString("status"), rs.getString("description"),
                rs.getString("created_by"), rs.getString("updated_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")), rs.getInt("case_count"));
    }

    private FunctionalTestRun run(ResultSet rs) throws SQLException {
        return new FunctionalTestRun(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("plan_id"), rs.getString("name"), rs.getString("status"), rs.getString("created_by"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("total_count"),
                rs.getInt("passed_count"), rs.getInt("failed_count"), rs.getInt("blocked_count"), rs.getInt("not_run_count"));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
