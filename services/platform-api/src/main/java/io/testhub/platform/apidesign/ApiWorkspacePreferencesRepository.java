package io.testhub.platform.apidesign;

import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiWorkspacePreferencesRepository {
    private final JdbcClient jdbc;

    public ApiWorkspacePreferencesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(long revision, String state) { }

    public Optional<Stored> find(String organizationId, String projectId, String userId) {
        return jdbc.sql("""
                SELECT revision, state_json FROM api_workspace_preferences
                WHERE organization_id = :organizationId AND project_id = :projectId AND user_id = :userId
                """).param("organizationId", organizationId).param("projectId", projectId).param("userId", userId)
                .query((rs, row) -> new Stored(rs.getLong("revision"), rs.getString("state_json"))).optional();
    }

    public boolean compareAndSet(String organizationId, String projectId, String userId, long revision, String state) {
        if (revision == 0) {
            try {
                jdbc.sql("""
                        INSERT INTO api_workspace_preferences (organization_id, project_id, user_id, revision, state_json)
                        VALUES (:organizationId, :projectId, :userId, 1, :state)
                        """).param("organizationId", organizationId).param("projectId", projectId)
                        .param("userId", userId).param("state", state).update();
                return true;
            } catch (DuplicateKeyException conflict) {
                return false;
            }
        }
        // One SQL statement changes both the revision and state; no read/write race or merge.
        return jdbc.sql("""
                UPDATE api_workspace_preferences SET revision = revision + 1, state_json = :state,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE organization_id = :organizationId AND project_id = :projectId AND user_id = :userId
                    AND revision = :revision
                """).param("organizationId", organizationId).param("projectId", projectId).param("userId", userId)
                .param("revision", revision).param("state", state).update() == 1;
    }
}
