package io.testhub.platform.mobileautomation;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** An independent intent survives a rollback or killed writer; its row lock protects an in-flight object. */
@Service
public class MobileAutomationArtifactTransferStorage {
    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final String bucket;
    private final TransactionTemplate independent;
    public MobileAutomationArtifactTransferStorage(JdbcClient jdbc, ObjectStorage storage, PlatformTransactionManager manager,
            @Value("${testhub.storage.bucket}") String bucket) {
        this.jdbc = jdbc; this.storage = storage; this.bucket = bucket;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); independent.setTimeout(10);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public String copy(AuthenticatedPrincipal actor, String organization, String project, MobileAutomationArtifactSyncService.Entry entry, byte[] bytes) {
        String id = UlidCreator.getUlid().toString(), key = "mobile-automation/history-artifacts/" + project + "/" + id;
        independent.executeWithoutResult(status -> jdbc.sql("INSERT INTO mobile_artifact_transfer_intent(id,organization_id,project_id,bucket,object_key) VALUES(:id,:o,:p,:b,:k)")
                .param("id", id).param("o", organization).param("p", project).param("b", bucket).param("k", key).update());
        if (lock(id) == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "工件准备已过期，请重新同步");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { recover(id); }
        });
        try {
            storage.ensureBucket(bucket);
            storage.put(bucket, key, new ByteArrayInputStream(bytes), bytes.length, entry.contentType());
            jdbc.sql("""
                    INSERT INTO mobile_artifact_archive
                      (id,organization_id,project_id,source_project_id,source_execution_id,source_artifact_id,artifact_type,
                       size_bytes,checksum,source_archived_at,bucket,object_key,imported_by)
                    VALUES(:id,:o,:p,:sp,:se,:sa,:t,:s,:h,:at,:b,:k,:u)
                    """).param("id", id).param("o", organization).param("p", project)
                    .param("sp", entry.key().substring(0,26)).param("se", entry.key().substring(27,53)).param("sa", entry.sourceArtifactId())
                    .param("t", entry.artifactType()).param("s", entry.sizeBytes()).param("h", entry.checksum())
                    .param("at", Timestamp.from(entry.archivedAt())).param("b", bucket).param("k", key).param("u", actor.userId()).update();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "工件保存失败，已安排回收；可安全重试", error);
        }
        return id;
    }
    private Intent lock(String id) {
        return jdbc.sql("SELECT project_id,bucket,object_key FROM mobile_artifact_transfer_intent WHERE id=:id FOR UPDATE")
                .param("id", id).query((rs, row) -> new Intent(rs.getString(1), rs.getString(2), rs.getString(3))).optional().orElse(null);
    }
    public void recover(String id) {
        try {
            independent.executeWithoutResult(status -> {
                var intent = lock(id); if (intent == null) return;
                boolean exists = jdbc.sql("SELECT COUNT(*) FROM mobile_artifact_archive WHERE id=:id AND project_id=:p AND bucket=:b AND object_key=:k")
                        .param("id", id).param("p", intent.project()).param("b", intent.bucket()).param("k", intent.key()).query(Integer.class).single() > 0;
                if (!exists) try { storage.remove(intent.bucket(), intent.key()); }
                catch (Exception error) { throw new IllegalStateException("工件回收暂不可用", error); }
                jdbc.sql("DELETE FROM mobile_artifact_transfer_intent WHERE id=:id").param("id", id).update();
            });
        } catch (Exception error) {
            LoggerFactory.getLogger(MobileAutomationArtifactTransferStorage.class).warn("工件回收将重试，记录 {}，原因类型 {}", id, error.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recoverStale() {
        jdbc.sql("SELECT id FROM mobile_artifact_transfer_intent WHERE created_at<:cutoff ORDER BY created_at LIMIT 100")
                .param("cutoff", Timestamp.from(Instant.now().minusSeconds(3600))).query(String.class).list().forEach(this::recover);
    }
    private record Intent(String project, String bucket, String key) { }
}
