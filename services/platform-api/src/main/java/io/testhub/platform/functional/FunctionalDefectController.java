package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/functional")
public class FunctionalDefectController {
    private final FunctionalDefectService defects;

    public FunctionalDefectController(FunctionalDefectService defects) { this.defects = defects; }

    @GetMapping("/defects")
    public ApiResponse<List<FunctionalDefect>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(defects.list(principal(authentication), projectId));
    }

    @PostMapping("/defects")
    public ApiResponse<FunctionalDefect> create(Authentication authentication, @PathVariable String projectId,
                                                @Valid @RequestBody DefectRequest request) {
        return ApiResponse.of(defects.create(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/defects/{defectId}")
    public ApiResponse<FunctionalDefect> update(Authentication authentication, @PathVariable String projectId,
                                                @PathVariable String defectId, @Valid @RequestBody DefectRequest request) {
        return ApiResponse.of(defects.update(principal(authentication), projectId, defectId, request.toInput()));
    }

    @DeleteMapping("/defects/{defectId}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable String projectId, @PathVariable String defectId) {
        defects.delete(principal(authentication), projectId, defectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/defects/{defectId}/links")
    public ApiResponse<List<FunctionalDefectLink>> links(Authentication authentication, @PathVariable String projectId,
                                                          @PathVariable String defectId) {
        return ApiResponse.of(defects.links(principal(authentication), projectId, defectId));
    }

    @PostMapping("/defects/{defectId}/links")
    public ApiResponse<FunctionalDefectLink> link(Authentication authentication, @PathVariable String projectId,
                                                   @PathVariable String defectId, @Valid @RequestBody DefectLinkRequest request) {
        return ApiResponse.of(defects.link(principal(authentication), projectId, defectId, request.toInput()));
    }

    @DeleteMapping("/defects/{defectId}/links/{linkId}")
    public ResponseEntity<Void> unlink(Authentication authentication, @PathVariable String projectId, @PathVariable String defectId,
                                       @PathVariable String linkId) {
        defects.unlink(principal(authentication), projectId, defectId, linkId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }

    public record DefectRequest(@NotBlank @Size(max = 40) String defectCode, @NotBlank @Size(max = 200) String title,
                                String severity, String status, @Size(max = 24) String source, @Size(max = 1000) String externalUrl,
                                @Size(max = 20_000) String description) {
        FunctionalDefectService.DefectInput toInput() { return new FunctionalDefectService.DefectInput(defectCode, title, severity, status, source, externalUrl, description); }
    }
    public record DefectLinkRequest(@NotBlank @Size(max = 24) String assetType, @NotBlank @Size(max = 26) String assetId) {
        FunctionalDefectService.DefectLinkInput toInput() { return new FunctionalDefectService.DefectLinkInput(assetType, assetId); }
    }
}
