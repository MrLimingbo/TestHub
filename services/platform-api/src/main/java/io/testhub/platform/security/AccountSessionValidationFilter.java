package io.testhub.platform.security;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A persisted account version invalidates saved identities across all session stores and replicas. */
public class AccountSessionValidationFilter extends OncePerRequestFilter {
    private final UserAccountRepository accounts;
    private final SecurityApiErrorHandler errors;

    public AccountSessionValidationFilter(UserAccountRepository accounts, SecurityApiErrorHandler errors) {
        this.accounts = accounts;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/csrf") || path.equals("/api/v1/auth/capabilities")
                || path.startsWith("/actuator/") || path.startsWith("/api/v1/mock/")
                || path.startsWith("/api/v1/mock-shares/") || path.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PlatformUserPrincipal saved) {
            var current = accounts.findById(saved.userId());
            if (current.isEmpty() || !current.get().enabled()
                    || current.get().securityVersion() != saved.account().securityVersion()
                    || !current.get().passwordHash().equals(saved.account().passwordHash())) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                errors.commence(request, response, new CredentialsExpiredException("Account session invalidated"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
