package io.testhub.platform.functional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionalCatalogRepository {
    private final JdbcClient jdbc;

    public FunctionalCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FunctionalCatalog> findByProject(String projectId, String scope) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, scope, parent_id, name, sort_order, created_by, created_at
                FROM functional_catalog WHERE project_id = :projectId AND scope = :scope
                ORDER BY sort_order, name
                """).param("projectId", projectId).param("scope", scope).query((rs, row) -> new FunctionalCatalog(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("scope"), rs.getString("parent_id"), rs.getString("name"), rs.getInt("sort_order"),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")))).list();
    }

    public Optional<FunctionalCatalog> findById(String projectId, String id) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, scope, parent_id, name, sort_order, created_by, created_at
                FROM functional_catalog WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).query((rs, row) -> new FunctionalCatalog(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("scope"), rs.getString("parent_id"), rs.getString("name"), rs.getInt("sort_order"),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")))).optional();
    }

    public void insert(String id, String organizationId, String projectId, String scope, String parentId,
                       String name, int sortOrder, String createdBy) {
        jdbc.sql("""
                INSERT INTO functional_catalog
                    (id, organization_id, project_id, scope, parent_id, name, sort_order, created_by)
                VALUES (:id, :organizationId, :projectId, :scope, :parentId, :name, :sortOrder, :createdBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("scope", scope).param("parentId", parentId).param("name", name).param("sortOrder", sortOrder)
                .param("createdBy", createdBy).update();
    }

    public int update(String projectId, String id, String parentId, String name, int sortOrder) {
        return jdbc.sql("""
                UPDATE functional_catalog SET parent_id = :parentId, name = :name, sort_order = :sortOrder
                WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).param("parentId", parentId).param("name", name)
                .param("sortOrder", sortOrder).update();
    }

    public int delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM functional_catalog WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
