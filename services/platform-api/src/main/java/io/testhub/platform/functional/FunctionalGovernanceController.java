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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/functional")
public class FunctionalGovernanceController {
    private final FunctionalGovernanceService governance;

    public FunctionalGovernanceController(FunctionalGovernanceService governance) {
        this.governance = governance;
    }

    @GetMapping("/tags")
    public ApiResponse<List<FunctionalTag>> tags(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(governance.listTags(principal(authentication), projectId));
    }

    @PostMapping("/tags")
    public ApiResponse<FunctionalTag> createTag(Authentication authentication, @PathVariable String projectId,
                                                 @Valid @RequestBody TagRequest request) {
        return ApiResponse.of(governance.createTag(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/tags/{tagId}")
    public ApiResponse<FunctionalTag> updateTag(Authentication authentication, @PathVariable String projectId,
                                                @PathVariable String tagId, @Valid @RequestBody TagRequest request) {
        return ApiResponse.of(governance.updateTag(principal(authentication), projectId, tagId, request.toInput()));
    }

    @DeleteMapping("/tags/{tagId}")
    public ResponseEntity<Void> deleteTag(Authentication authentication, @PathVariable String projectId, @PathVariable String tagId) {
        governance.deleteTag(principal(authentication), projectId, tagId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/asset-tags")
    public ApiResponse<List<FunctionalTag>> assetTags(Authentication authentication, @PathVariable String projectId,
                                                       @RequestParam String assetType, @RequestParam String assetId) {
        return ApiResponse.of(governance.listAssetTags(principal(authentication), projectId, assetType, assetId));
    }

    @PutMapping("/asset-tags/{assetType}/{assetId}")
    public ApiResponse<List<FunctionalTag>> replaceAssetTags(Authentication authentication, @PathVariable String projectId,
                                                              @PathVariable String assetType, @PathVariable String assetId,
                                                              @RequestBody TagAssignmentRequest request) {
        return ApiResponse.of(governance.replaceAssetTags(principal(authentication), projectId, assetType, assetId, request.tagIds()));
    }

    @GetMapping("/custom-fields")
    public ApiResponse<List<FunctionalCustomField>> fields(Authentication authentication, @PathVariable String projectId,
                                                            @RequestParam(required = false) String assetType) {
        return ApiResponse.of(governance.listFields(principal(authentication), projectId, assetType));
    }

    @PostMapping("/custom-fields")
    public ApiResponse<FunctionalCustomField> createField(Authentication authentication, @PathVariable String projectId,
                                                           @Valid @RequestBody CustomFieldRequest request) {
        return ApiResponse.of(governance.createField(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/custom-fields/{fieldId}")
    public ApiResponse<FunctionalCustomField> updateField(Authentication authentication, @PathVariable String projectId,
                                                           @PathVariable String fieldId, @Valid @RequestBody CustomFieldRequest request) {
        return ApiResponse.of(governance.updateField(principal(authentication), projectId, fieldId, request.toInput()));
    }

    @DeleteMapping("/custom-fields/{fieldId}")
    public ResponseEntity<Void> deleteField(Authentication authentication, @PathVariable String projectId, @PathVariable String fieldId) {
        governance.deleteField(principal(authentication), projectId, fieldId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record TagRequest(@NotBlank @Size(max = 64) String name, @Size(max = 24) String color) {
        FunctionalGovernanceService.TagInput toInput() { return new FunctionalGovernanceService.TagInput(name, color); }
    }

    public record TagAssignmentRequest(List<@Size(max = 26) String> tagIds) { }

    public record CustomFieldRequest(@NotBlank @Size(max = 24) String assetType, @NotBlank @Size(max = 64) String name,
                                     @NotBlank @Size(max = 64) String fieldKey, @NotBlank @Size(max = 24) String fieldType,
                                     boolean required, List<@NotBlank @Size(max = 80) String> options) {
        FunctionalGovernanceService.CustomFieldInput toInput() {
            return new FunctionalGovernanceService.CustomFieldInput(assetType, name, fieldKey, fieldType, required, options);
        }
    }
}
