package io.testhub.platform.functional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    public void insert(String id, String organizationId, String projectId, String catalogId, String defectCode,
                       String title, LocalDate dueDate, String bugType, String keywords, List<String> browsers,
                       List<String> operatingSystems, List<String> ccUserIds,
                       String severity, String priority, String status, boolean confirmed,
                       String assigneeId, String assignedBy, String resolution, String resolvedBy, Instant resolvedAt,
                       String closedBy, Instant closedAt, String source, String externalUrl, String description,
                       String userId) {
        jdbc.sql("""
                INSERT INTO functional_defect
                    (id, organization_id, project_id, catalog_id, defect_code, title, due_date, bug_type, keywords,
                     browsers, operating_systems, cc_user_ids, severity, priority, status,
                     confirmed, assignee_id, assigned_by, resolution, resolved_by, resolved_at, closed_by, closed_at,
                     source, external_url, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, :defectCode, :title, :dueDate, :bugType,
                        :keywords, :browsers, :operatingSystems, :ccUserIds, :severity, :priority, :status,
                        :confirmed, :assigneeId, :assignedBy, :resolution, :resolvedBy, :resolvedAt, :closedBy, :closedAt,
                        :source, :externalUrl, :description, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("catalogId", new SqlParameterValue(Types.CHAR, catalogId)).param("defectCode", defectCode)
                .param("title", title).param("dueDate", new SqlParameterValue(Types.DATE, sqlDate(dueDate)))
                .param("bugType", bugType).param("keywords", keywords).param("browsers", csv(browsers))
                .param("operatingSystems", csv(operatingSystems)).param("ccUserIds", csv(ccUserIds))
                .param("severity", severity).param("priority", priority).param("status", status)
                .param("confirmed", confirmed).param("assigneeId", new SqlParameterValue(Types.CHAR, assigneeId))
                .param("assignedBy", new SqlParameterValue(Types.CHAR, assignedBy))
                .param("resolution", new SqlParameterValue(Types.VARCHAR, resolution))
                .param("resolvedBy", new SqlParameterValue(Types.CHAR, resolvedBy))
                .param("resolvedAt", new SqlParameterValue(Types.TIMESTAMP, resolvedAt == null ? null : Timestamp.from(resolvedAt)))
                .param("closedBy", new SqlParameterValue(Types.CHAR, closedBy))
                .param("closedAt", new SqlParameterValue(Types.TIMESTAMP, closedAt == null ? null : Timestamp.from(closedAt)))
                .param("source", source).param("externalUrl", new SqlParameterValue(Types.VARCHAR, externalUrl))
                .param("description", description).param("userId", userId).update();
    }

    public int update(String projectId, String defectId, String catalogId, String title, LocalDate dueDate,
                      String bugType, String keywords, List<String> browsers, List<String> operatingSystems,
                      List<String> ccUserIds, String severity, String priority, String status, boolean confirmed,
                      String assigneeId, String assignedBy,
                      String resolution, String resolvedBy, Instant resolvedAt, String closedBy, Instant closedAt,
                      String source, String externalUrl, String description, String userId) {
        return jdbc.sql("""
                UPDATE functional_defect
                SET catalog_id = :catalogId, title = :title, due_date = :dueDate, bug_type = :bugType,
                    keywords = :keywords, browsers = :browsers, operating_systems = :operatingSystems,
                    cc_user_ids = :ccUserIds, severity = :severity, priority = :priority,
                    status = :status, confirmed = :confirmed, assignee_id = :assigneeId, assigned_by = :assignedBy,
                    resolution = :resolution, resolved_by = :resolvedBy, resolved_at = :resolvedAt,
                    closed_by = :closedBy, closed_at = :closedAt, source = :source,
                    external_url = :externalUrl, description = :description, updated_by = :userId
                WHERE project_id = :projectId AND id = :defectId
                """).param("projectId", projectId).param("defectId", defectId)
                .param("catalogId", new SqlParameterValue(Types.CHAR, catalogId)).param("title", title)
                .param("dueDate", new SqlParameterValue(Types.DATE, sqlDate(dueDate))).param("bugType", bugType)
                .param("keywords", keywords).param("browsers", csv(browsers))
                .param("operatingSystems", csv(operatingSystems)).param("ccUserIds", csv(ccUserIds))
                .param("severity", severity).param("priority", priority).param("status", status)
                .param("confirmed", confirmed).param("assigneeId", new SqlParameterValue(Types.CHAR, assigneeId))
                .param("assignedBy", new SqlParameterValue(Types.CHAR, assignedBy))
                .param("resolution", new SqlParameterValue(Types.VARCHAR, resolution))
                .param("resolvedBy", new SqlParameterValue(Types.CHAR, resolvedBy))
                .param("resolvedAt", new SqlParameterValue(Types.TIMESTAMP, resolvedAt == null ? null : Timestamp.from(resolvedAt)))
                .param("closedBy", new SqlParameterValue(Types.CHAR, closedBy))
                .param("closedAt", new SqlParameterValue(Types.TIMESTAMP, closedAt == null ? null : Timestamp.from(closedAt)))
                .param("source", source).param("externalUrl", new SqlParameterValue(Types.VARCHAR, externalUrl))
                .param("description", description).param("userId", userId).update();
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
                SELECT d.id, d.display_id, d.organization_id, d.project_id, d.catalog_id, d.defect_code, d.title,
                       d.due_date, d.bug_type, d.keywords, d.browsers, d.operating_systems, d.cc_user_ids,
                       d.severity, d.priority, d.status, d.confirmed, d.assignee_id,
                       COALESCE(assignee.display_name, assignee.account) AS assignee_name,
                       d.assigned_by, COALESCE(assigner.display_name, assigner.account) AS assigned_by_name,
                       d.resolution, d.resolved_by,
                       COALESCE(resolver.display_name, resolver.account) AS resolved_by_name,
                       d.resolved_at, d.closed_by, d.closed_at, d.source, d.external_url, d.description,
                       d.created_by, COALESCE(creator.display_name, creator.account) AS created_by_name,
                       d.updated_by, d.created_at, d.updated_at,
                       (SELECT COUNT(*) FROM functional_defect_link link_count WHERE link_count.defect_id = d.id) AS link_count
                FROM functional_defect d
                LEFT JOIN user_account creator ON creator.id = d.created_by
                LEFT JOIN user_account assignee ON assignee.id = d.assignee_id
                LEFT JOIN user_account assigner ON assigner.id = d.assigned_by
                LEFT JOIN user_account resolver ON resolver.id = d.resolved_by
                """ + where + "\n" + """
                ORDER BY d.updated_at DESC, d.display_id DESC
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
        return new FunctionalDefect(rs.getString("id"), rs.getLong("display_id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("catalog_id"), rs.getString("defect_code"),
                rs.getString("title"), localDate(rs.getDate("due_date")), rs.getString("bug_type"),
                rs.getString("keywords"), csv(rs.getString("browsers")), csv(rs.getString("operating_systems")),
                csv(rs.getString("cc_user_ids")), rs.getString("severity"), rs.getString("priority"), rs.getString("status"),
                rs.getBoolean("confirmed"), rs.getString("assignee_id"), rs.getString("assignee_name"),
                rs.getString("assigned_by"), rs.getString("assigned_by_name"), rs.getString("resolution"),
                rs.getString("resolved_by"), rs.getString("resolved_by_name"),
                instant(rs.getTimestamp("resolved_at")), rs.getString("closed_by"), instant(rs.getTimestamp("closed_at")),
                rs.getString("source"), rs.getString("external_url"), rs.getString("description"),
                rs.getString("created_by"), rs.getString("created_by_name"), rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getInt("link_count"));
    }

    private FunctionalDefectLink link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalDefectLink(rs.getString("id"), rs.getString("defect_id"), rs.getString("defect_code"),
                rs.getString("defect_title"), rs.getString("asset_type"), rs.getString("asset_id"), rs.getString("asset_label"),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }

    private Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private LocalDate localDate(Date date) { return date == null ? null : date.toLocalDate(); }
    private Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }
    private String csv(List<String> values) { return values == null || values.isEmpty() ? "" : String.join(",", values); }
    private List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
}
