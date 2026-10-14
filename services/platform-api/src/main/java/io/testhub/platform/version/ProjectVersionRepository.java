package io.testhub.platform.version;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectVersionRepository {
    private final JdbcClient jdbc;

    public ProjectVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProjectVersion> findByProject(String projectId) {
        return jdbc.sql(query("WHERE pv.project_id = :projectId"))
                .param("projectId", projectId)
                .query((rs, row) -> version(rs))
                .list();
    }

    public Optional<ProjectVersion> findById(String projectId, String versionId) {
        return jdbc.sql(query("WHERE pv.project_id = :projectId AND pv.id = :versionId"))
                .param("projectId", projectId)
                .param("versionId", versionId)
                .query((rs, row) -> version(rs))
                .optional();
    }

    public void insert(String id, String organizationId, String projectId, String versionNumber,
                       List<String> buildNumbers, String status, LocalDate plannedReleaseDate,
                       LocalDate actualReleaseDate, String description, String userId) {
        jdbc.sql("""
                INSERT INTO project_version
                    (id, organization_id, project_id, version_number, build_numbers, status,
                     planned_release_date, actual_release_date, description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :versionNumber, :buildNumbers, :status,
                        :plannedReleaseDate, :actualReleaseDate, :description, :userId, :userId)
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("projectId", projectId)
                .param("versionNumber", versionNumber)
                .param("buildNumbers", encodeBuilds(buildNumbers))
                .param("status", status)
                .param("plannedReleaseDate", sqlDate(plannedReleaseDate))
                .param("actualReleaseDate", sqlDate(actualReleaseDate))
                .param("description", description)
                .param("userId", userId)
                .update();
    }

    public int update(String projectId, String versionId, String versionNumber, List<String> buildNumbers,
                      String status, LocalDate plannedReleaseDate, LocalDate actualReleaseDate,
                      String description, String userId) {
        return jdbc.sql("""
                UPDATE project_version
                SET version_number = :versionNumber, build_numbers = :buildNumbers, status = :status,
                    planned_release_date = :plannedReleaseDate, actual_release_date = :actualReleaseDate,
                    description = :description, updated_by = :userId
                WHERE project_id = :projectId AND id = :versionId
                """)
                .param("projectId", projectId)
                .param("versionId", versionId)
                .param("versionNumber", versionNumber)
                .param("buildNumbers", encodeBuilds(buildNumbers))
                .param("status", status)
                .param("plannedReleaseDate", sqlDate(plannedReleaseDate))
                .param("actualReleaseDate", sqlDate(actualReleaseDate))
                .param("description", description)
                .param("userId", userId)
                .update();
    }

    public void lockProject(String projectId) {
        jdbc.sql("SELECT id FROM project WHERE id=:projectId FOR UPDATE").param("projectId", projectId)
                .query(String.class).single();
    }

    public int updateDefinition(String projectId, String versionId, List<String> buildNumbers,
                                LocalDate plannedReleaseDate, String description, String userId) {
        return jdbc.sql("""
                UPDATE project_version
                SET build_numbers=:buildNumbers, planned_release_date=:plannedReleaseDate,
                    description=:description, updated_by=:userId
                WHERE project_id=:projectId AND id=:versionId
                """)
                .param("projectId",projectId).param("versionId",versionId)
                .param("buildNumbers",encodeBuilds(buildNumbers)).param("plannedReleaseDate",sqlDate(plannedReleaseDate))
                .param("description",description).param("userId",userId).update();
    }

    public int updateStatus(String projectId, String versionId, String status, LocalDate actualReleaseDate,
                            String userId) {
        return jdbc.sql("""
                UPDATE project_version
                SET status = :status, actual_release_date = :actualReleaseDate, updated_by = :userId
                WHERE project_id = :projectId AND id = :versionId
                """)
                .param("projectId", projectId)
                .param("versionId", versionId)
                .param("status", status)
                .param("actualReleaseDate", sqlDate(actualReleaseDate))
                .param("userId", userId)
                .update();
    }

    public int delete(String projectId, String versionId) {
        return jdbc.sql("DELETE FROM project_version WHERE project_id = :projectId AND id = :versionId")
                .param("projectId", projectId)
                .param("versionId", versionId)
                .update();
    }

    private String query(String where) {
        return """
                SELECT pv.id, pv.display_id, pv.organization_id, pv.project_id,
                       project.name AS project_name, pv.version_number, pv.build_numbers,
                       pv.status, pv.planned_release_date, pv.actual_release_date,
                       pv.description, pv.created_by,
                       COALESCE(creator.display_name, creator.account) AS created_by_name,
                       pv.updated_by, pv.created_at, pv.updated_at
                FROM project_version pv
                JOIN project ON project.id = pv.project_id
                LEFT JOIN user_account creator ON creator.id = pv.created_by
                """ + where + "\n" + """
                ORDER BY pv.display_id DESC
                """;
    }

    private ProjectVersion version(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectVersion(rs.getString("id"), rs.getLong("display_id"),
                rs.getString("organization_id"), rs.getString("project_id"), rs.getString("project_name"),
                rs.getString("version_number"), decodeBuilds(rs.getString("build_numbers")), rs.getString("status"),
                localDate(rs.getDate("planned_release_date")), localDate(rs.getDate("actual_release_date")),
                rs.getString("description"), rs.getString("created_by"), rs.getString("created_by_name"),
                rs.getString("updated_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private SqlParameterValue sqlDate(LocalDate value) {
        return new SqlParameterValue(Types.DATE, value == null ? null : Date.valueOf(value));
    }

    private String encodeBuilds(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join("\n", values);
    }

    private List<String> decodeBuilds(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("\\n"));
    }

    private LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
