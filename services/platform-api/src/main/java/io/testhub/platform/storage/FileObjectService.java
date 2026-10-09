package io.testhub.platform.storage;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileObjectService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final FileObjectRepository files;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectStorage storage;
    private final String bucket;

    public FileObjectService(FileObjectRepository files, AccessService access, AuditService audit, ObjectStorage storage,
                             @org.springframework.beans.factory.annotation.Value("${testhub.storage.bucket}") String bucket) {
        this.files = files;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.bucket = bucket;
    }

    public List<FileObject> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return files.findByProject(projectId);
    }

    public FileObject upload(AuthenticatedPrincipal principal, String projectId, MultipartFile multipart) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        if (multipart == null || multipart.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件不能为空");
        String originalName = safeName(multipart.getOriginalFilename());
        String contentType = multipart.getContentType() == null || multipart.getContentType().isBlank()
                ? "application/octet-stream" : multipart.getContentType();
        String id = UlidCreator.getUlid().toString();
        String objectKey = projectId + "/" + id;
        ensureBucket();
        try (InputStream input = multipart.getInputStream()) {
            storage.put(bucket, objectKey, input, multipart.getSize(), contentType);
            files.insert(id, project.organizationId(), projectId, objectKey, originalName, contentType, multipart.getSize(), principal.userId());
        } catch (Exception exception) {
            removeObjectQuietly(objectKey);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件存储失败，请检查文件存储", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FILE_UPLOADED", "FILE_OBJECT", id,
                Map.of("name", originalName, "sizeBytes", multipart.getSize(), "contentType", contentType));
        return files.findById(projectId, id).orElseThrow();
    }

    public InputStream download(AuthenticatedPrincipal principal, String projectId, String fileId) {
        access.requireProjectRead(principal, projectId);
        FileObject file = files.findById(projectId, fileId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        try {
            ensureBucket();
            return storage.get(bucket, file.objectKey());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件读取失败，请检查文件存储", exception);
        }
    }

    public FileObject metadata(AuthenticatedPrincipal principal, String projectId, String fileId) {
        access.requireProjectRead(principal, projectId);
        return files.findById(projectId, fileId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
    }

    public void delete(AuthenticatedPrincipal principal, String projectId, String fileId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        FileObject file = files.findById(projectId, fileId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        try {
            ensureBucket();
            storage.remove(bucket, file.objectKey());
            files.delete(projectId, fileId);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件删除失败，请检查文件存储", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FILE_DELETED", "FILE_OBJECT", fileId,
                Map.of("name", file.originalName(), "sizeBytes", file.sizeBytes()));
    }

    private void ensureBucket() {
        try {
            storage.ensureBucket(bucket);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件存储不可用", exception);
        }
    }

    private void removeObjectQuietly(String objectKey) {
        try {
            storage.remove(bucket, objectKey);
        } catch (Exception ignored) {
            // The original storage error is more actionable than cleanup failure.
        }
    }

    private String safeName(String value) {
        String name = value == null ? "unnamed-file" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isBlank() || name.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不合法");
        return name;
    }
}
