package io.testhub.platform.mobileautomation;

import io.testhub.platform.storage.ObjectStorage;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MobileAutomationExecutionArtifactService {
    public static final long MAX_ARTIFACT_BYTES = 25L * 1024 * 1024;
    private static final Set<String> ACCEPTING = Set.of("ACCEPTED", "RUNNING", "CANCELLING");
    private final JdbcClient jdbc;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectStorage storage;
    private final String bucket;
    public MobileAutomationExecutionArtifactService(JdbcClient jdbc, AccessService access, AuditService audit, ObjectStorage storage, @Value("${testhub.storage.bucket}") String bucket) {
        this.jdbc = jdbc; this.access = access; this.audit = audit; this.storage = storage; this.bucket = bucket;
    }
    public List<MobileAutomationExecutionArtifact> list(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId); execution(projectId, executionId);
        return jdbc.sql("SELECT id,execution_id,artifact_type,original_name,content_type,size_bytes,checksum,runner_id,created_at FROM mobile_automation_execution_artifact WHERE project_id=:project AND execution_id=:execution ORDER BY created_at,id")
                .param("project", projectId).param("execution", executionId).query((rs, row) -> artifact(rs)).list();
    }
    public Download download(AuthenticatedPrincipal principal, String projectId, String executionId, String artifactId) {
        access.requireProjectRead(principal, projectId);
        StoredArtifact stored = jdbc.sql("SELECT a.id,a.execution_id,a.artifact_type,a.original_name,a.content_type,a.size_bytes,a.checksum,a.runner_id,a.created_at,a.object_key FROM mobile_automation_execution_artifact a WHERE a.project_id=:project AND a.execution_id=:execution AND a.id=:id")
                .param("project", projectId).param("execution", executionId).param("id", artifactId).query((rs, row) -> new StoredArtifact(artifact(rs), rs.getString("object_key"))).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "移动端执行工件不存在"));
        try { ensureBucket(); return new Download(stored.artifact(), storage.get(bucket, stored.objectKey())); }
        catch (Exception exception) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "移动端执行工件读取失败，请检查文件存储", exception); }
    }
    public MobileAutomationExecutionArtifact archive(String runnerId, Upload upload, InputStream body) {
        if (!"SCREENSHOT".equals(upload.artifactType()) || upload.sizeBytes() < 0 || upload.sizeBytes() > MAX_ARTIFACT_BYTES || !upload.checksum().matches("[0-9a-f]{64}")) throw invalid("移动端截图归档元数据不合法");
        ExecutionReference execution = jdbc.sql("SELECT id,task_id,organization_id,project_id,status,runner_id FROM mobile_automation_execution WHERE id=:execution AND task_id=:task")
                .param("execution", upload.executionId()).param("task", upload.taskId()).query((rs, row) -> new ExecutionReference(rs.getString("id"),rs.getString("task_id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("status"),rs.getString("runner_id"))).optional().orElseThrow(this::rejected);
        if (!ACCEPTING.contains(execution.status()) || !runnerId.equals(execution.runnerId())) throw rejected();
        StoredArtifact duplicate = jdbc.sql("SELECT id,execution_id,artifact_type,original_name,content_type,size_bytes,checksum,runner_id,created_at,object_key FROM mobile_automation_execution_artifact WHERE execution_id=:execution AND artifact_type='SCREENSHOT' AND checksum=:checksum")
                .param("execution", execution.id()).param("checksum", upload.checksum()).query((rs, row) -> new StoredArtifact(artifact(rs),rs.getString("object_key"))).optional().orElse(null);
        if (duplicate != null) { if (duplicate.artifact().sizeBytes() != upload.sizeBytes()) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一截图校验和的尺寸不一致"); return duplicate.artifact(); }
        String id = UlidCreator.getUlid().toString(), objectKey = "mobile-automation/executions/" + execution.id() + "/screenshot-" + id;
        try {
            ensureBucket(); MessageDigest digest = MessageDigest.getInstance("SHA-256"); CountingInputStream counting = new CountingInputStream(body);
            try (DigestInputStream input = new DigestInputStream(counting, digest)) {
                storage.put(bucket, objectKey, input, upload.sizeBytes(), "image/png");
                if (counting.count != upload.sizeBytes() || !java.util.HexFormat.of().formatHex(digest.digest()).equals(upload.checksum())) throw invalid("移动端截图内容长度或校验和不匹配");
            }
            jdbc.sql("INSERT INTO mobile_automation_execution_artifact (id,execution_id,organization_id,project_id,artifact_type,original_name,content_type,size_bytes,checksum,object_key,runner_id) VALUES (:id,:execution,:org,:project,'SCREENSHOT','screenshot.png','image/png',:size,:checksum,:object,:runner)")
                    .param("id", id).param("execution", execution.id()).param("org", execution.organizationId()).param("project", execution.projectId()).param("size", upload.sizeBytes()).param("checksum", upload.checksum()).param("object", objectKey).param("runner", runnerId).update();
        } catch (ResponseStatusException exception) { removeQuietly(objectKey); throw exception; }
        catch (Exception exception) { removeQuietly(objectKey); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "移动端截图归档失败，请检查文件存储", exception); }
        MobileAutomationExecutionArtifact value = jdbc.sql("SELECT id,execution_id,artifact_type,original_name,content_type,size_bytes,checksum,runner_id,created_at FROM mobile_automation_execution_artifact WHERE id=:id").param("id", id).query((rs,row) -> artifact(rs)).single();
        audit.recordSystem(execution.organizationId(), execution.projectId(), "MOBILE_AUTOMATION_EXECUTION_ARTIFACT_ARCHIVED", "MOBILE_AUTOMATION_EXECUTION_ARTIFACT", id, Map.of("executionId", execution.id(), "taskId", execution.taskId(), "sizeBytes", upload.sizeBytes(), "runnerId", runnerId));
        return value;
    }
    private ExecutionReference execution(String projectId, String executionId) { return jdbc.sql("SELECT id,task_id,organization_id,project_id,status,runner_id FROM mobile_automation_execution WHERE project_id=:project AND id=:id").param("project",projectId).param("id",executionId).query((rs,row) -> new ExecutionReference(rs.getString("id"),rs.getString("task_id"),rs.getString("organization_id"),rs.getString("project_id"),rs.getString("status"),rs.getString("runner_id"))).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"移动端执行记录不存在")); }
    private MobileAutomationExecutionArtifact artifact(java.sql.ResultSet rs) throws java.sql.SQLException { return new MobileAutomationExecutionArtifact(rs.getString("id"),rs.getString("execution_id"),rs.getString("artifact_type"),rs.getString("original_name"),rs.getString("content_type"),rs.getLong("size_bytes"),rs.getString("checksum"),rs.getString("runner_id"),rs.getTimestamp("created_at").toInstant()); }
    private void ensureBucket() {
        try {
            storage.ensureBucket(bucket);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件存储不可用", exception);
        }
    }
    private void removeQuietly(String key) { try { storage.remove(bucket, key); } catch (Exception ignored) { } }
    private ResponseStatusException rejected() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Appium Runner 无权归档该执行截图"); }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public record Upload(String taskId,String executionId,String artifactType,long sizeBytes,String checksum) { }
    public record Download(MobileAutomationExecutionArtifact artifact, InputStream content) { }
    private record StoredArtifact(MobileAutomationExecutionArtifact artifact,String objectKey) { }
    private record ExecutionReference(String id,String taskId,String organizationId,String projectId,String status,String runnerId) { }
    private static final class CountingInputStream extends FilterInputStream { private long count; private CountingInputStream(InputStream input) { super(input); } @Override public int read() throws IOException { int value=super.read(); if(value>=0)count++; return value; } @Override public int read(byte[] buffer,int offset,int length)throws IOException { int value=super.read(buffer,offset,length); if(value>0)count+=value; return value; } }
}
