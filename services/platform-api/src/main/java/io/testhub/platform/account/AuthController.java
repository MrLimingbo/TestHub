package io.testhub.platform.account;

import io.testhub.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
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
    private final RegistrationService registration;
    private final RegistrationRateLimiter registrationRateLimiter;

    public AuthController(AuthenticationManager authenticationManager, RegistrationService registration,
                          RegistrationRateLimiter registrationRateLimiter) {
        this.authenticationManager = authenticationManager;
        this.registration = registration;
        this.registrationRateLimiter = registrationRateLimiter;
    }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Boolean>> capabilities() {
        return ApiResponse.of(Map.of("registrationEnabled", registration.enabled()));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<ProfileResponse>> register(@Valid @RequestBody RegistrationRequest request,
                                                                HttpServletRequest servletRequest) {
        if (!registration.enabled()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "当前服务未开放注册");
        }
        registrationRateLimiter.requireAllowed(servletRequest.getRemoteAddr());
        UserAccount created = registration.register(request.account(), request.email(), request.displayName(), request.password());
        PlatformUserPrincipal principal = new PlatformUserPrincipal(created);
        saveSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()), servletRequest);
        return ResponseEntity.status(201).body(ApiResponse.of(ProfileResponse.from(principal)));
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken csrfToken) {
        return ApiResponse.of(Map.of("token", csrfToken.getToken(), "headerName", csrfToken.getHeaderName()));
    }

    @PostMapping("/login")
    public ApiResponse<ProfileResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String identifier = request.identifier();
        if (identifier == null || identifier.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "账号不能为空");
        }
        Authentication authenticated = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(identifier.trim().toLowerCase(java.util.Locale.ROOT), request.password()));
        saveSession(authenticated, servletRequest);
        return ApiResponse.of(ProfileResponse.from((PlatformUserPrincipal) authenticated.getPrincipal()));
    }

    private void saveSession(Authentication authenticated, HttpServletRequest servletRequest) {
        // Controller-based authentication must rotate the pre-authentication session explicitly.
        if (servletRequest.getSession(false) != null) servletRequest.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        servletRequest.getSession(true).setAttribute(SECURITY_CONTEXT_KEY, context);
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

    public record LoginRequest(String account, String email, @NotBlank String password) {
        String identifier() {
            return account == null || account.isBlank() ? email : account;
        }
    }

    public record RegistrationRequest(@NotBlank @Size(max = 64) String account,
                                      @NotBlank @Email @Size(max = 190) String email,
                                      @Size(max = 80) String displayName,
                                      @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record ProfileResponse(String id, String account, String email, String displayName, Instant authenticatedAt) {
        static ProfileResponse from(PlatformUserPrincipal principal) {
            return new ProfileResponse(principal.account().id(), principal.account().account(), principal.account().email(),
                    principal.account().displayName(), Instant.now());
        }
    }
}
