package io.testhub.platform.functional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.sql.Types;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionalDefectRepository {
    private final JdbcClient jdbc;

    public FunctionalDefectRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<FunctionalDefect> findByProject(String projectId) {
        return jdbc.sql(defectQuery("WHERE d.project_id = :projectId")).param("projectId", projectId).query((rs, row) -> defect(rs)).list();
    }

    public Optional<FunctionalDefect> findById(String projectId, String defectId) {
        return jdbc.sql(defectQuery("WHERE d.project_id = :projectId AND d.id = :defectId")).param("projectId", projectId)
                .param("defectId", defectId).query((rs, row) -> defect(rs)).optional();
    }

    public void insert(String id, String organizationId, String projectId, String defectCode, String title, String severity,
                       String status, String source, String externalUrl, String description, String userId) {
        jdbc.sql("""
                INSERT INTO functional_defect
                    (id, organization_id, project_id, defect_code, title, severity, status, source, external_url, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :defectCode, :title, :severity, :status, :source, :externalUrl, :description, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("defectCode", defectCode).param("title", title).param("severity", severity).param("status", status)
                .param("source", source).param("externalUrl", new SqlParameterValue(Types.VARCHAR, externalUrl)).param("description", description).param("userId", userId).update();
    }

    public int update(String projectId, String defectId, String title, String severity, String status, String source,
                      String externalUrl, String description, String userId) {
        return jdbc.sql("""
                UPDATE functional_defect SET title = :title, severity = :severity, status = :status, source = :source,
                    external_url = :externalUrl, description = :description, updated_by = :userId
                WHERE project_id = :projectId AND id = :defectId
                """).param("projectId", projectId).param("defectId", defectId).param("title", title).param("severity", severity)
                .param("status", status).param("source", source).param("externalUrl", new SqlParameterValue(Types.VARCHAR, externalUrl)).param("description", description)
                .param("userId", userId).update();
    }

    public int delete(String projectId, String defectId) {
        return jdbc.sql("DELETE FROM functional_defect WHERE project_id = :projectId AND id = :defectId")
                .param("projectId", projectId).param("defectId", defectId).update();
    }

    public List<FunctionalDefectLink> findLinksByDefect(String projectId, String defectId) {
        return jdbc.sql(linkQuery("WHERE l.project_id = :projectId AND l.defect_id = :defectId"))
                .param("projectId", projectId).param("defectId", defectId).query((rs, row) -> link(rs)).list();
    }

    public List<FunctionalDefectLink> findLinksByAsset(String projectId, String assetType, String assetId) {
        return jdbc.sql(linkQuery("WHERE l.project_id = :projectId AND l.asset_type = :assetType AND l.asset_id = :assetId"))
                .param("projectId", projectId).param("assetType", assetType).param("assetId", assetId).query((rs, row) -> link(rs)).list();
    }

    public void insertLink(String id, String projectId, String defectId, String assetType, String assetId, String userId) {
        jdbc.sql("""
                INSERT INTO functional_defect_link (id, project_id, defect_id, asset_type, asset_id, created_by)
                VALUES (:id, :projectId, :defectId, :assetType, :assetId, :userId)
                """).param("id", id).param("projectId", projectId).param("defectId", defectId).param("assetType", assetType)
                .param("assetId", assetId).param("userId", userId).update();
    }

    public int deleteLink(String projectId, String defectId, String linkId) {
        return jdbc.sql("DELETE FROM functional_defect_link WHERE project_id = :projectId AND defect_id = :defectId AND id = :linkId")
                .param("projectId", projectId).param("defectId", defectId).param("linkId", linkId).update();
    }

    private String defectQuery(String where) {
        return """
                SELECT d.id, d.organization_id, d.project_id, d.defect_code, d.title, d.severity, d.status, d.source,
                       d.external_url, d.description, d.created_by, d.updated_by, d.created_at, d.updated_at, COUNT(l.id) AS link_count
                FROM functional_defect d LEFT JOIN functional_defect_link l ON l.defect_id = d.id
                """ + where + "\n" + """
                GROUP BY d.id, d.organization_id, d.project_id, d.defect_code, d.title, d.severity, d.status, d.source,
                         d.external_url, d.description, d.created_by, d.updated_by, d.created_at, d.updated_at
                ORDER BY d.updated_at DESC, d.defect_code
                """;
    }

    private String linkQuery(String where) {
        return """
                SELECT l.id, l.defect_id, d.defect_code, d.title AS defect_title, l.asset_type, l.asset_id,
                       COALESCE(c.title, r.title, rc.case_title, l.asset_id) AS asset_label, l.created_by, l.created_at
                FROM functional_defect_link l JOIN functional_defect d ON d.id = l.defect_id
                LEFT JOIN functional_case c ON l.asset_type = 'CASE' AND c.id = l.asset_id
                LEFT JOIN functional_requirement r ON l.asset_type = 'REQUIREMENT' AND r.id = l.asset_id
                LEFT JOIN (
                    SELECT run_case.id, test_case.title AS case_title
                    FROM functional_test_run_case run_case JOIN functional_case test_case ON test_case.id = run_case.case_id
                ) rc ON l.asset_type = 'RUN_CASE' AND rc.id = l.asset_id
                """ + where + "\n" + """
                ORDER BY l.created_at DESC
                """;
    }

    private FunctionalDefect defect(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalDefect(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("defect_code"), rs.getString("title"), rs.getString("severity"), rs.getString("status"),
                rs.getString("source"), rs.getString("external_url"), rs.getString("description"), rs.getString("created_by"),
                rs.getString("updated_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("link_count"));
    }

    private FunctionalDefectLink link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalDefectLink(rs.getString("id"), rs.getString("defect_id"), rs.getString("defect_code"),
                rs.getString("defect_title"), rs.getString("asset_type"), rs.getString("asset_id"), rs.getString("asset_label"),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }

    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
