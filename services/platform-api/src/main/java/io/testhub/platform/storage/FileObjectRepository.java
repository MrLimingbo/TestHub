package io.testhub.platform.storage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FileObjectRepository {
    private final JdbcClient jdbc;

    public FileObjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FileObject> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, object_key, original_name, content_type, size_bytes, uploaded_by, created_at
                FROM file_object WHERE project_id = :projectId ORDER BY created_at DESC
                """).param("projectId", projectId).query((rs, rowNumber) -> map(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("object_key"), rs.getString("original_name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("uploaded_by"), rs.getTimestamp("created_at"))).list();
    }

    public Optional<FileObject> findById(String projectId, String fileId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, object_key, original_name, content_type, size_bytes, uploaded_by, created_at
                FROM file_object WHERE project_id = :projectId AND id = :fileId
                """).param("projectId", projectId).param("fileId", fileId).query((rs, rowNumber) -> map(rs.getString("id"),
                rs.getString("organization_id"), rs.getString("project_id"), rs.getString("object_key"), rs.getString("original_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("uploaded_by"), rs.getTimestamp("created_at"))).optional();
    }

    public void insert(String id, String organizationId, String projectId, String objectKey, String originalName,
                       String contentType, long sizeBytes, String uploadedBy) {
        jdbc.sql("""
                INSERT INTO file_object (id, organization_id, project_id, object_key, original_name, content_type, size_bytes, uploaded_by)
                VALUES (:id, :organizationId, :projectId, :objectKey, :originalName, :contentType, :sizeBytes, :uploadedBy)
                """).param("id", id).param("organizationId", organizationId).param("projectId", projectId)
                .param("objectKey", objectKey).param("originalName", originalName).param("contentType", contentType)
                .param("sizeBytes", sizeBytes).param("uploadedBy", uploadedBy).update();
    }

    public void delete(String projectId, String fileId) {
        jdbc.sql("DELETE FROM file_object WHERE project_id = :projectId AND id = :fileId")
                .param("projectId", projectId).param("fileId", fileId).update();
    }

    private FileObject map(String id, String organizationId, String projectId, String objectKey, String originalName,
                           String contentType, long sizeBytes, String uploadedBy, Timestamp createdAt) {
        return new FileObject(id, organizationId, projectId, objectKey, originalName, contentType, sizeBytes, uploadedBy,
                createdAt == null ? null : Instant.ofEpochMilli(createdAt.getTime()));
    }
}
