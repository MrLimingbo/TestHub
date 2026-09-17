package io.testhub.platform.performance;

import com.github.f4b6a3.ulid.UlidCreator;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Keeps raw JMeter output in MinIO while preserving project-scoped, auditable metadata in MySQL. */
@Service
public class PerformanceTestExecutionArtifactService {
    public static final long MAX_ARTIFACT_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ARCHIVABLE_STATUSES = Set.of("ACCEPTED", "RUNNING", "CANCELLING");
    private static final Duration TIMEOUT_ARCHIVE_GRACE = Duration.ofMinutes(2);
    private final PerformanceTestExecutionArtifactRepository artifacts;
    private final PerformanceTestExecutionRepository executions;
    private final AccessService access;
    private final AuditService audit;
    private final MinioClient minio;
    private final String bucket;
    private final Set<String> ensuredBuckets = ConcurrentHashMap.newKeySet();

    public PerformanceTestExecutionArtifactService(PerformanceTestExecutionArtifactRepository artifacts,
                                                   PerformanceTestExecutionRepository executions, AccessService access,
                                                   AuditService audit, MinioClient minio,
                                                   @Value("${testhub.storage.bucket}") String bucket) {
        this.artifacts = artifacts;
        this.executions = executions;
        this.access = access;
        this.audit = audit;
        this.minio = minio;
        this.bucket = bucket;
    }

    public List<PerformanceTestExecutionArtifact> list(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId);
        requireExecution(projectId, executionId);
        return artifacts.findByExecution(projectId, executionId);
    }

    public Download download(AuthenticatedPrincipal principal, String projectId, String executionId, String artifactId) {
        access.requireProjectRead(principal, projectId);
        requireExecution(projectId, executionId);
        PerformanceTestExecutionArtifactRepository.StoredArtifact stored = artifacts.findStored(projectId, executionId, artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "压测执行工件不存在"));
        try {
            ensureBucket();
            GetObjectResponse content = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(stored.objectKey()).build());
            return new Download(stored.artifact(), content);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "压测执行工件读取失败，请检查 MinIO 服务", exception);
        }
    }

    public PerformanceTestExecutionArtifact archive(String runnerId, Upload upload, InputStream body) {
        ArtifactKind kind = ArtifactKind.require(upload.artifactType());
        if (upload.sizeBytes() < 0 || upload.sizeBytes() > MAX_ARTIFACT_BYTES || !upload.checksum().matches("[0-9a-f]{64}")) {
            throw invalid("JMeter 工件归档元数据不合法");
        }
        PerformanceTestExecution execution = executions.findByTask(upload.taskId(), upload.executionId()).orElseThrow(this::rejected);
        boolean recentTimeout = "TIMED_OUT".equals(execution.status()) && execution.finishedAt() != null
                && Instant.now().isBefore(execution.finishedAt().plus(TIMEOUT_ARCHIVE_GRACE));
        if ((!ARCHIVABLE_STATUSES.contains(execution.status()) && !recentTimeout)
                || !runnerId.equals(execution.runnerId())) throw rejected();
        PerformanceTestExecutionArtifactRepository.StoredArtifact existing = artifacts.findByType(execution.id(), kind.name()).orElse(null);
        if (existing != null) {
            if (existing.artifact().sizeBytes() != upload.sizeBytes() || !existing.artifact().checksum().equals(upload.checksum())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同类 JMeter 工件已归档且校验和不一致");
            }
            return existing.artifact();
        }

        String id = UlidCreator.getUlid().toString();
        String objectKey = "performance-test/executions/" + execution.id() + "/" + kind.name().toLowerCase(Locale.ROOT) + "-" + id;
        try {
            ensureBucket();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingInputStream counting = new CountingInputStream(body);
            try (DigestInputStream input = new DigestInputStream(counting, digest)) {
                minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, upload.sizeBytes(), -1)
                        .contentType(kind.contentType).build());
                String checksum = java.util.HexFormat.of().formatHex(digest.digest());
                if (counting.count() != upload.sizeBytes() || !checksum.equals(upload.checksum())) {
                    throw invalid("JMeter 工件内容长度或校验和不匹配");
                }
            }
            artifacts.insert(id, execution, kind.name(), objectKey, kind.originalName, kind.contentType, upload.sizeBytes(),
                    upload.checksum(), runnerId);
        } catch (ResponseStatusException exception) {
            removeObjectQuietly(objectKey);
            throw exception;
        } catch (Exception exception) {
            removeObjectQuietly(objectKey);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "JMeter 工件归档失败，请检查 MinIO 服务", exception);
        }
        PerformanceTestExecutionArtifactRepository.StoredArtifact archived = artifacts.findStored(execution.projectId(), execution.id(), id)
                .orElseThrow(() -> new IllegalStateException("JMeter 工件元数据写入后不可读取"));
        audit.recordSystem(execution.organizationId(), execution.projectId(), "PERFORMANCE_TEST_EXECUTION_ARTIFACT_ARCHIVED",
                "PERFORMANCE_TEST_EXECUTION_ARTIFACT", id, Map.of("executionId", execution.id(), "taskId", execution.taskId(),
                        "artifactType", kind.name(), "sizeBytes", upload.sizeBytes(), "checksum", upload.checksum(),
                        "runnerId", runnerId));
        return archived.artifact();
    }

    private PerformanceTestExecution requireExecution(String projectId, String executionId) {
        return executions.findById(projectId, executionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "压测执行任务不存在"));
    }

    private void ensureBucket() {
        if (ensuredBuckets.contains(bucket)) return;
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            ensuredBuckets.add(bucket);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MinIO 存储桶不可用", exception);
        }
    }

    private void removeObjectQuietly(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception ignored) {
            // Keep the original archive error; a later lifecycle job can clean an orphaned object.
        }
    }

    private ResponseStatusException rejected() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "JMeter Runner 无权归档该执行工件");
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Upload(String taskId, String executionId, String artifactType, long sizeBytes, String checksum) {
    }

    public record Download(PerformanceTestExecutionArtifact artifact, GetObjectResponse content) {
    }

    private enum ArtifactKind {
        JMETER_JTL("results.jtl", "text/csv"),
        JMETER_LOG("jmeter.log", "text/plain");

        private final String originalName;
        private final String contentType;

        ArtifactKind(String originalName, String contentType) {
            this.originalName = originalName;
            this.contentType = contentType;
        }

        private static ArtifactKind require(String value) {
            try {
                return ArtifactKind.valueOf(value == null ? "" : value);
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JMeter 工件类型不受支持");
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }

        private long count() {
            return count;
        }
    }
}
