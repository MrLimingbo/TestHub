package io.testhub.platform.functional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FunctionalGovernanceRepository {
    private final JdbcClient jdbc;

    public FunctionalGovernanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FunctionalTag> findTags(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, color, created_by, created_at
                FROM functional_tag WHERE project_id = :projectId ORDER BY name
                """).param("projectId", projectId).query((rs, row) -> tag(rs)).list();
    }

    public Optional<FunctionalTag> findTag(String projectId, String tagId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, name, color, created_by, created_at
                FROM functional_tag WHERE project_id = :projectId AND id = :tagId
                """).param("projectId", projectId).param("tagId", tagId).query((rs, row) -> tag(rs)).optional();
    }

    public void insertTag(String id, String organizationId, String projectId, String name, String color, String userId) {
        jdbc.sql("""
                INSERT INTO functional_tag (id, organization_id, project_id, name, color, created_by)
                VALUES (:id, :organizationId, :projectId, :name, :color, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("name", name).param("color", color).param("userId", userId).update();
    }

    public int deleteTag(String projectId, String tagId) {
        return jdbc.sql("DELETE FROM functional_tag WHERE project_id = :projectId AND id = :tagId")
                .param("projectId", projectId).param("tagId", tagId).update();
    }

    public List<FunctionalTag> findAssetTags(String projectId, String assetType, String assetId) {
        return jdbc.sql("""
                SELECT t.id, t.organization_id, t.project_id, t.name, t.color, t.created_by, t.created_at
                FROM functional_asset_tag at JOIN functional_tag t ON t.id = at.tag_id
                WHERE at.project_id = :projectId AND at.asset_type = :assetType AND at.asset_id = :assetId
                ORDER BY t.name
                """).param("projectId", projectId).param("assetType", assetType).param("assetId", assetId)
                .query((rs, row) -> tag(rs)).list();
    }

    public void clearAssetTags(String projectId, String assetType, String assetId) {
        jdbc.sql("""
                DELETE FROM functional_asset_tag
                WHERE project_id = :projectId AND asset_type = :assetType AND asset_id = :assetId
                """).param("projectId", projectId).param("assetType", assetType).param("assetId", assetId).update();
    }

    public void insertAssetTag(String id, String projectId, String assetType, String assetId, String tagId) {
        jdbc.sql("""
                INSERT INTO functional_asset_tag (id, project_id, asset_type, asset_id, tag_id)
                VALUES (:id, :projectId, :assetType, :assetId, :tagId)
                """).param("id", id).param("projectId", projectId).param("assetType", assetType)
                .param("assetId", assetId).param("tagId", tagId).update();
    }

    public List<FunctionalCustomField> findFields(String projectId, String assetType) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, asset_type, name, field_key, field_type, required, options_json,
                       created_by, updated_by, created_at, updated_at
                FROM functional_custom_field
                WHERE project_id = :projectId AND (:assetType IS NULL OR asset_type = :assetType)
                ORDER BY asset_type, created_at
                """).param("projectId", projectId).param("assetType", assetType).query((rs, row) -> field(rs)).list();
    }

    public Optional<FunctionalCustomField> findField(String projectId, String fieldId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, asset_type, name, field_key, field_type, required, options_json,
                       created_by, updated_by, created_at, updated_at
                FROM functional_custom_field WHERE project_id = :projectId AND id = :fieldId
                """).param("projectId", projectId).param("fieldId", fieldId).query((rs, row) -> field(rs)).optional();
    }

    public void insertField(String id, String organizationId, String projectId, String assetType, String name, String fieldKey,
                            String fieldType, boolean required, String optionsJson, String userId) {
        jdbc.sql("""
                INSERT INTO functional_custom_field
                    (id, organization_id, project_id, asset_type, name, field_key, field_type, required, options_json, created_by, updated_by)
                VALUES (:id, :organizationId, :projectId, :assetType, :name, :fieldKey, :fieldType, :required, :optionsJson, :userId, :userId)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("assetType", assetType).param("name", name).param("fieldKey", fieldKey).param("fieldType", fieldType)
                .param("required", required).param("optionsJson", optionsJson).param("userId", userId).update();
    }

    public int updateField(String projectId, String fieldId, String name, String fieldType, boolean required,
                           String optionsJson, String userId) {
        return jdbc.sql("""
                UPDATE functional_custom_field
                SET name = :name, field_type = :fieldType, required = :required, options_json = :optionsJson,
                    updated_by = :userId
                WHERE project_id = :projectId AND id = :fieldId
                """).param("projectId", projectId).param("fieldId", fieldId).param("name", name)
                .param("fieldType", fieldType).param("required", required).param("optionsJson", optionsJson)
                .param("userId", userId).update();
    }

    public int deleteField(String projectId, String fieldId) {
        return jdbc.sql("DELETE FROM functional_custom_field WHERE project_id = :projectId AND id = :fieldId")
                .param("projectId", projectId).param("fieldId", fieldId).update();
    }

    private FunctionalTag tag(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalTag(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("color"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }

    private FunctionalCustomField field(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FunctionalCustomField(rs.getString("id"), rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("asset_type"), rs.getString("name"), rs.getString("field_key"), rs.getString("field_type"),
                rs.getBoolean("required"), rs.getString("options_json"), rs.getString("created_by"), rs.getString("updated_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
