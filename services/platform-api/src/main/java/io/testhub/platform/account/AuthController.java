package io.testhub.platform.account;

import io.testhub.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";
    private final AuthenticationManager authenticationManager;

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken csrfToken) {
        return ApiResponse.of(Map.of("token", csrfToken.getToken(), "headerName", csrfToken.getHeaderName()));
    }

    @PostMapping("/login")
    public ApiResponse<ProfileResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email().trim().toLowerCase(), request.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        servletRequest.getSession(true).setAttribute(SECURITY_CONTEXT_KEY, context);
        return ApiResponse.of(ProfileResponse.from((PlatformUserPrincipal) authenticated.getPrincipal()));
    }

    @GetMapping("/me")
    public ApiResponse<ProfileResponse> me(Authentication authentication) {
        return ApiResponse.of(ProfileResponse.from((PlatformUserPrincipal) authentication.getPrincipal()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record ProfileResponse(String id, String email, String displayName, Instant authenticatedAt) {
        static ProfileResponse from(PlatformUserPrincipal principal) {
            return new ProfileResponse(principal.account().id(), principal.account().email(), principal.account().displayName(), Instant.now());
        }
    }
}
