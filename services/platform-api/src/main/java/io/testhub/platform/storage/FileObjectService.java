package io.testhub.platform.storage;

import com.github.f4b6a3.ulid.UlidCreator;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetObjectResponse;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final MinioClient minio;
    private final String bucket;
    private final Set<String> ensuredBuckets = ConcurrentHashMap.newKeySet();

    public FileObjectService(FileObjectRepository files, AccessService access, AuditService audit, MinioClient minio,
                             @org.springframework.beans.factory.annotation.Value("${testhub.storage.bucket}") String bucket) {
        this.files = files;
        this.access = access;
        this.audit = audit;
        this.minio = minio;
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
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(input, multipart.getSize(), -1)
                    .contentType(contentType).build());
            files.insert(id, project.organizationId(), projectId, objectKey, originalName, contentType, multipart.getSize(), principal.userId());
        } catch (Exception exception) {
            removeObjectQuietly(objectKey);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件存储失败，请检查 MinIO 服务", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FILE_UPLOADED", "FILE_OBJECT", id,
                Map.of("name", originalName, "sizeBytes", multipart.getSize(), "contentType", contentType));
        return files.findById(projectId, id).orElseThrow();
    }

    public GetObjectResponse download(AuthenticatedPrincipal principal, String projectId, String fileId) {
        access.requireProjectRead(principal, projectId);
        FileObject file = files.findById(projectId, fileId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        try {
            ensureBucket();
            return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(file.objectKey()).build());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件读取失败，请检查 MinIO 服务", exception);
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
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(file.objectKey()).build());
            files.delete(projectId, fileId);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "文件删除失败，请检查 MinIO 服务", exception);
        }
        audit.record(principal, project.organizationId(), projectId, "FILE_DELETED", "FILE_OBJECT", fileId,
                Map.of("name", file.originalName(), "sizeBytes", file.sizeBytes()));
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
