package io.testhub.platform.functional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionalRequirementRepository {
    private final JdbcClient jdbc;

    public FunctionalRequirementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FunctionalRequirement> findByProject(String projectId, String catalogId) {
        return jdbc.sql("""
                SELECT id, display_id, organization_id, project_id, catalog_id, requirement_code, title, priority, status,
                       description, created_by, updated_by, created_at, updated_at
                FROM functional_requirement
                WHERE project_id = :projectId AND (:catalogId IS NULL OR catalog_id = :catalogId)
                ORDER BY updated_at DESC, requirement_code
                """).param("projectId", projectId).param("catalogId", catalogId).query((rs, row) -> map(rs)).list();
    }

    public Optional<FunctionalRequirement> findById(String projectId, String id) {
        return jdbc.sql("""
                SELECT id, display_id, organization_id, project_id, catalog_id, requirement_code, title, priority, status,
                       description, created_by, updated_by, created_at, updated_at
                FROM functional_requirement WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).query((rs, row) -> map(rs)).optional();
    }

    public void insert(String id, String organizationId, String projectId, String catalogId, String code, String title,
                       String priority, String status, String description, String userId) {
        jdbc.sql("""
                INSERT INTO functional_requirement
                    (id, organization_id, project_id, catalog_id, requirement_code, title, priority, status,
                     description, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :catalogId, :code, :title, :priority, :status,
                        :description, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("catalogId", catalogId).param("code", code).param("title", title).param("priority", priority)
                .param("status", status).param("description", description).param("userId", userId).update();
    }

    public int update(String projectId, String id, String catalogId, String code, String title, String priority,
                      String status, String description, String userId) {
        return jdbc.sql("""
                UPDATE functional_requirement
                SET catalog_id = :catalogId, requirement_code = :code, title = :title, priority = :priority,
                    status = :status, description = :description, updated_by = :userId
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("catalogId", catalogId)
                .param("code", code).param("title", title).param("priority", priority).param("status", status)
                .param("description", description).param("userId", userId).update();
    }

    public int delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM functional_requirement WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    private FunctionalRequirement map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalRequirement(rs.getString("id"), rs.getLong("display_id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("catalog_id"), rs.getString("requirement_code"), rs.getString("title"),
                rs.getString("priority"), rs.getString("status"), rs.getString("description"),
                rs.getString("created_by"), rs.getString("updated_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
