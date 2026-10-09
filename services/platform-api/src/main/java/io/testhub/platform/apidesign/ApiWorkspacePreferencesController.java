package io.testhub.platform.apidesign;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-design/preferences")
public class ApiWorkspacePreferencesController {
    private final ApiWorkspacePreferencesService preferences;

    public ApiWorkspacePreferencesController(ApiWorkspacePreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public ApiResponse<ApiWorkspacePreferencesService.Preferences> get(Authentication authentication,
                                                                       @PathVariable String projectId) {
        return ApiResponse.of(preferences.get((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ApiWorkspacePreferencesService.Preferences> put(Authentication authentication,
            @PathVariable String projectId, HttpServletRequest request) throws IOException {
        // Bound the stream itself, including chunked requests without Content-Length.
        byte[] body = request.getInputStream().readNBytes(ApiWorkspacePreferencesService.MAX_BODY_BYTES + 1);
        return ApiResponse.of(preferences.put((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, body));
    }
}
