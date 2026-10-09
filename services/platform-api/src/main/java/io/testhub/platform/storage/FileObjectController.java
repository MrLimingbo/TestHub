package io.testhub.platform.storage;

import java.io.InputStream;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
public class FileObjectController {
    private final FileObjectService files;

    public FileObjectController(FileObjectService files) {
        this.files = files;
    }

    @GetMapping
    public ApiResponse<List<FileObject>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(files.list(principal(authentication), projectId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileObject> upload(Authentication authentication, @PathVariable String projectId,
                                          @RequestParam("file") MultipartFile file) {
        return ApiResponse.of(files.upload(principal(authentication), projectId, file));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileObject> metadata(Authentication authentication, @PathVariable String projectId,
                                            @PathVariable String fileId) {
        return ApiResponse.of(files.metadata(principal(authentication), projectId, fileId));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(Authentication authentication, @PathVariable String projectId,
                                                        @PathVariable String fileId) {
        FileObject file = files.metadata(principal(authentication), projectId, fileId);
        InputStream object = files.download(principal(authentication), projectId, fileId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentLength(file.sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.originalName(), java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(object));
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId,
                                       @PathVariable String fileId) {
        files.delete(principal(authentication), projectId, fileId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }
}
