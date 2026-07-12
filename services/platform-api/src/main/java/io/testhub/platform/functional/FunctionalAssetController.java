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
public class FunctionalAssetController {
    private final FunctionalAssetService assets;

    public FunctionalAssetController(FunctionalAssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/catalogs")
    public ApiResponse<List<FunctionalCatalog>> catalogs(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(assets.listCatalogs(principal(authentication), projectId));
    }

    @PostMapping("/catalogs")
    public ApiResponse<FunctionalCatalog> createCatalog(Authentication authentication, @PathVariable String projectId,
                                                          @Valid @RequestBody CatalogRequest request) {
        return ApiResponse.of(assets.createCatalog(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/catalogs/{catalogId}")
    public ApiResponse<FunctionalCatalog> updateCatalog(Authentication authentication, @PathVariable String projectId,
                                                          @PathVariable String catalogId,
                                                          @Valid @RequestBody CatalogRequest request) {
        return ApiResponse.of(assets.updateCatalog(principal(authentication), projectId, catalogId, request.toInput()));
    }

    @DeleteMapping("/catalogs/{catalogId}")
    public ResponseEntity<Void> deleteCatalog(Authentication authentication, @PathVariable String projectId,
                                               @PathVariable String catalogId) {
        assets.deleteCatalog(principal(authentication), projectId, catalogId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/requirements")
    public ApiResponse<List<FunctionalRequirement>> requirements(Authentication authentication, @PathVariable String projectId,
                                                                 @RequestParam(required = false) String catalogId) {
        return ApiResponse.of(assets.listRequirements(principal(authentication), projectId, catalogId));
    }

    @PostMapping("/requirements")
    public ApiResponse<FunctionalRequirement> createRequirement(Authentication authentication, @PathVariable String projectId,
                                                                 @Valid @RequestBody RequirementRequest request) {
        return ApiResponse.of(assets.createRequirement(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/requirements/{requirementId}")
    public ApiResponse<FunctionalRequirement> updateRequirement(Authentication authentication, @PathVariable String projectId,
                                                                 @PathVariable String requirementId,
                                                                 @Valid @RequestBody RequirementRequest request) {
        return ApiResponse.of(assets.updateRequirement(principal(authentication), projectId, requirementId, request.toInput()));
    }

    @DeleteMapping("/requirements/{requirementId}")
    public ResponseEntity<Void> deleteRequirement(Authentication authentication, @PathVariable String projectId,
                                                   @PathVariable String requirementId) {
        assets.deleteRequirement(principal(authentication), projectId, requirementId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cases")
    public ApiResponse<List<FunctionalCase>> cases(Authentication authentication, @PathVariable String projectId,
                                                    @RequestParam(required = false) String catalogId) {
        return ApiResponse.of(assets.listCases(principal(authentication), projectId, catalogId));
    }

    @PostMapping("/cases")
    public ApiResponse<FunctionalCase> createCase(Authentication authentication, @PathVariable String projectId,
                                                   @Valid @RequestBody CaseRequest request) {
        return ApiResponse.of(assets.createCase(principal(authentication), projectId, request.toInput()));
    }

    @PutMapping("/cases/{caseId}")
    public ApiResponse<FunctionalCase> updateCase(Authentication authentication, @PathVariable String projectId,
                                                   @PathVariable String caseId, @Valid @RequestBody CaseRequest request) {
        return ApiResponse.of(assets.updateCase(principal(authentication), projectId, caseId, request.toInput()));
    }

    @DeleteMapping("/cases/{caseId}")
    public ResponseEntity<Void> deleteCase(Authentication authentication, @PathVariable String projectId,
                                            @PathVariable String caseId) {
        assets.deleteCase(principal(authentication), projectId, caseId);
        return ResponseEntity.noContent().build();
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record CatalogRequest(@NotBlank @Size(max = 160) String name, String parentId, int sortOrder) {
        FunctionalAssetService.CatalogInput toInput() { return new FunctionalAssetService.CatalogInput(name, parentId, sortOrder); }
    }

    public record RequirementRequest(@NotBlank @Size(max = 26) String catalogId,
                                     @NotBlank @Size(max = 40) String requirementCode,
                                     @NotBlank @Size(max = 200) String title,
                                     String priority, String status, @Size(max = 20_000) String description) {
        FunctionalAssetService.RequirementInput toInput() {
            return new FunctionalAssetService.RequirementInput(catalogId, requirementCode, title, priority, status, description);
        }
    }

    public record CaseRequest(@NotBlank @Size(max = 26) String catalogId, String requirementId,
                              @NotBlank @Size(max = 40) String caseCode,
                              @NotBlank @Size(max = 200) String title,
                              String priority, String status, @Size(max = 20_000) String precondition,
                              @NotBlank @Size(max = 20_000) String steps,
                              @NotBlank @Size(max = 20_000) String expectedResult) {
        FunctionalAssetService.CaseInput toInput() {
            return new FunctionalAssetService.CaseInput(catalogId, requirementId, caseCode, title, priority, status,
                    precondition, steps, expectedResult);
        }
    }
}
