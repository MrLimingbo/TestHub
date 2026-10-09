package io.testhub.platform.uiautomation;

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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Persists report artifacts outside the control-plane process with bounded, checksum-verified uploads. */
@Service
public class UiAutomationExecutionArtifactService {
    public static final long MAX_ARTIFACT_BYTES = 25L * 1024 * 1024;
    private static final Set<String> ACCEPTING = Set.of("ACCEPTED", "RUNNING", "CANCELLING");
    private final UiAutomationRepository automation;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectStorage storage;
    private final String bucket;

    public UiAutomationExecutionArtifactService(UiAutomationRepository automation, AccessService access, AuditService audit,
                                                ObjectStorage storage, @Value("${testhub.storage.bucket}") String bucket) {
        this.automation = automation;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.bucket = bucket;
    }

    public List<UiAutomationExecutionArtifact> list(AuthenticatedPrincipal principal, String projectId, String executionId) {
        access.requireProjectRead(principal, projectId);
        execution(projectId, executionId);
        return automation.findArtifacts(projectId, executionId);
    }

    public Download download(AuthenticatedPrincipal principal, String projectId, String executionId, String artifactId) {
        access.requireProjectRead(principal, projectId);
        UiAutomationRepository.StoredArtifact stored = automation.findArtifact(projectId, executionId, artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Web UI 自动化工件不存在"));
        try {
            ensureBucket();
            return new Download(stored.artifact(), storage.get(bucket, stored.objectKey()));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Web UI 自动化工件读取失败，请检查文件存储", exception);
        }
    }

    public UiAutomationExecutionArtifact archive(String runnerId, Upload upload, InputStream body) {
        ArtifactKind kind = ArtifactKind.require(upload.artifactType());
        if (upload.sizeBytes() < 0 || upload.sizeBytes() > MAX_ARTIFACT_BYTES || !upload.checksum().matches("[0-9a-f]{64}")) {
            throw invalid("Web UI 自动化工件归档元数据不合法");
        }
        UiAutomationExecution execution = automation.findExecutionByTask(upload.taskId(), upload.executionId()).orElseThrow(this::rejected);
        boolean recentTimeout = "TIMED_OUT".equals(execution.status()) && execution.finishedAt() != null
                && Instant.now().isBefore(execution.finishedAt().plusSeconds(120));
        if ((!ACCEPTING.contains(execution.status()) && !recentTimeout) || !runnerId.equals(execution.runnerId())) throw rejected();
        UiAutomationRepository.StoredArtifact duplicate = automation.findArtifactByChecksum(execution.id(), kind.name(), upload.checksum()).orElse(null);
        if (duplicate != null) {
            if (duplicate.artifact().sizeBytes() != upload.sizeBytes()) throw new ResponseStatusException(HttpStatus.CONFLICT, "同一工件校验和的尺寸不一致");
            return duplicate.artifact();
        }
        String id = UlidCreator.getUlid().toString();
        String objectKey = "ui-automation/executions/" + execution.id() + "/" + kind.name().toLowerCase(Locale.ROOT) + "-" + id;
        try {
            ensureBucket();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingInputStream counting = new CountingInputStream(body);
            try (DigestInputStream input = new DigestInputStream(counting, digest)) {
                storage.put(bucket, objectKey, input, upload.sizeBytes(), kind.contentType);
                if (counting.count != upload.sizeBytes() || !java.util.HexFormat.of().formatHex(digest.digest()).equals(upload.checksum())) {
                    throw invalid("Web UI 自动化工件内容长度或校验和不匹配");
                }
            }
            automation.insertArtifact(id, execution, kind.name(), objectKey, kind.originalName, kind.contentType, upload.sizeBytes(), upload.checksum(), runnerId);
        } catch (ResponseStatusException exception) {
            removeQuietly(objectKey);
            throw exception;
        } catch (Exception exception) {
            removeQuietly(objectKey);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Web UI 自动化工件归档失败，请检查文件存储", exception);
        }
        UiAutomationExecutionArtifact artifact = automation.findArtifact(execution.projectId(), execution.id(), id)
                .orElseThrow(() -> new IllegalStateException("工件归档后不可读取")).artifact();
        audit.recordSystem(execution.organizationId(), execution.projectId(), "UI_AUTOMATION_EXECUTION_ARTIFACT_ARCHIVED",
                "UI_AUTOMATION_EXECUTION_ARTIFACT", id, Map.of("executionId", execution.id(), "taskId", execution.taskId(),
                        "artifactType", kind.name(), "sizeBytes", upload.sizeBytes(), "runnerId", runnerId));
        return artifact;
    }

    private UiAutomationExecution execution(String projectId, String executionId) {
        return automation.findExecution(projectId, executionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Web UI 执行记录不存在"));
    }
    private void ensureBucket() {
        try {
            storage.ensureBucket(bucket);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件存储不可用", exception);
        }
    }
    private void removeQuietly(String objectKey) { try { storage.remove(bucket, objectKey); } catch (Exception ignored) { } }
    private ResponseStatusException rejected() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Playwright Runner 无权归档该执行工件"); }
    private ResponseStatusException invalid(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, text); }
    public record Upload(String taskId, String executionId, String artifactType, long sizeBytes, String checksum) { }
    public record Download(UiAutomationExecutionArtifact artifact, InputStream content) { }
    private enum ArtifactKind {
        SCREENSHOT("screenshot.png", "image/png"), TRACE("trace.zip", "application/zip"), VIDEO("video.webm", "video/webm");
        private final String originalName;
        private final String contentType;
        ArtifactKind(String originalName, String contentType) { this.originalName = originalName; this.contentType = contentType; }
        static ArtifactKind require(String value) {
            try { return ArtifactKind.valueOf(value == null ? "" : value); }
            catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Web UI 自动化工件类型不受支持"); }
        }
    }
    private static final class CountingInputStream extends FilterInputStream {
        private long count;
        private CountingInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException { int value = super.read(); if (value >= 0) count++; return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException { int value = super.read(buffer, offset, length); if (value > 0) count += value; return value; }
    }
}
