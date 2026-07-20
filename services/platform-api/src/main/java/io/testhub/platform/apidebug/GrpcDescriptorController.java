package io.testhub.platform.apidebug;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/projects/{projectId}/api-debug/grpc/descriptors")
public class GrpcDescriptorController {
    private final GrpcDescriptorService descriptors;

    public GrpcDescriptorController(GrpcDescriptorService descriptors) {
        this.descriptors = descriptors;
    }

    @GetMapping
    public ApiResponse<List<GrpcDescriptorService.Summary>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(descriptors.list(principal(authentication), projectId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<GrpcDescriptorService.Summary> importFiles(Authentication authentication, @PathVariable String projectId,
                                                                    @RequestParam @NotBlank @Size(max = 128) String name,
                                                                    @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.of(descriptors.importFiles(principal(authentication), projectId, name, files));
    }

    @GetMapping("/{descriptorId}/discovery")
    public ApiResponse<GrpcDescriptorService.Discovery> discover(Authentication authentication, @PathVariable String projectId,
                                                                  @PathVariable String descriptorId,
                                                                  @RequestParam(required = false) @Size(max = 512) String service) {
        return ApiResponse.of(descriptors.discover(principal(authentication), projectId, descriptorId, service));
    }

    @DeleteMapping("/{descriptorId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId, @PathVariable String descriptorId) {
        descriptors.delete(principal(authentication), projectId, descriptorId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }
}
