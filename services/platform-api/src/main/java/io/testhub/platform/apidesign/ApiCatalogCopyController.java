package io.testhub.platform.apidesign;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-design/catalogs/{catalogId}")
public class ApiCatalogCopyController {
    private final ApiCatalogCopyService copies;

    public ApiCatalogCopyController(ApiCatalogCopyService copies) {
        this.copies = copies;
    }

    @GetMapping("/copy-preview")
    public ApiResponse<ApiCatalogCopyService.Preview> preview(Authentication authentication, @PathVariable String projectId,
                                                             @PathVariable String catalogId) {
        return ApiResponse.of(copies.preview((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, catalogId));
    }

    @PostMapping("/copy")
    public ApiResponse<ApiCatalogCopyService.Result> copy(Authentication authentication, @PathVariable String projectId,
                                                         @PathVariable String catalogId,
                                                         @RequestBody ApiCatalogCopyService.Request request) {
        return ApiResponse.of(copies.copy((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, catalogId, request));
    }
}
