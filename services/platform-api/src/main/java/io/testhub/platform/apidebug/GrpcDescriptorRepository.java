package io.testhub.platform.apidebug;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GrpcDescriptorRepository {
    private final JdbcClient jdbc;

    public GrpcDescriptorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<GrpcDescriptor> findByProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, display_name, source_format, descriptor_hash, descriptor_bytes,
                       size_bytes, service_count, source_count, created_by, created_at
                FROM grpc_descriptor WHERE project_id = :projectId ORDER BY created_at DESC
                """).param("projectId", projectId).query((rs, row) -> map(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("display_name"), rs.getString("source_format"), rs.getString("descriptor_hash"),
                rs.getBytes("descriptor_bytes"), rs.getInt("size_bytes"), rs.getInt("service_count"), rs.getInt("source_count"),
                rs.getString("created_by"), rs.getTimestamp("created_at"))).list();
    }

    public Optional<GrpcDescriptor> findById(String projectId, String id) {
        return jdbc.sql("""
                SELECT id, organization_id, project_id, display_name, source_format, descriptor_hash, descriptor_bytes,
                       size_bytes, service_count, source_count, created_by, created_at
                FROM grpc_descriptor WHERE project_id = :projectId AND id = :id
                """).param("projectId", projectId).param("id", id).query((rs, row) -> map(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("project_id"), rs.getString("display_name"), rs.getString("source_format"), rs.getString("descriptor_hash"),
                rs.getBytes("descriptor_bytes"), rs.getInt("size_bytes"), rs.getInt("service_count"), rs.getInt("source_count"),
                rs.getString("created_by"), rs.getTimestamp("created_at"))).optional();
    }

    public void insert(GrpcDescriptor descriptor) {
        jdbc.sql("""
                INSERT INTO grpc_descriptor (id, organization_id, project_id, display_name, source_format, descriptor_hash,
                                             descriptor_bytes, size_bytes, service_count, source_count, created_by)
                VALUES (:id, :organizationId, :projectId, :name, :sourceFormat, :descriptorHash, :descriptorBytes,
                        :sizeBytes, :serviceCount, :sourceCount, :createdBy)
                """).param("id", descriptor.id()).param("organizationId", descriptor.organizationId()).param("projectId", descriptor.projectId())
                .param("name", descriptor.name()).param("sourceFormat", descriptor.sourceFormat()).param("descriptorHash", descriptor.descriptorHash())
                .param("descriptorBytes", descriptor.descriptorBytes()).param("sizeBytes", descriptor.sizeBytes()).param("serviceCount", descriptor.serviceCount())
                .param("sourceCount", descriptor.sourceCount()).param("createdBy", descriptor.createdBy()).update();
    }

    public void delete(String projectId, String id) {
        jdbc.sql("DELETE FROM grpc_descriptor WHERE project_id = :projectId AND id = :id")
                .param("projectId", projectId).param("id", id).update();
    }

    private GrpcDescriptor map(String id, String organizationId, String projectId, String name, String sourceFormat, String descriptorHash,
                               byte[] descriptorBytes, int sizeBytes, int serviceCount, int sourceCount, String createdBy, Timestamp createdAt) {
        return new GrpcDescriptor(id, organizationId, projectId, name, sourceFormat, descriptorHash, descriptorBytes, sizeBytes, serviceCount,
                sourceCount, createdBy, createdAt == null ? null : Instant.ofEpochMilli(createdAt.getTime()));
    }
}
