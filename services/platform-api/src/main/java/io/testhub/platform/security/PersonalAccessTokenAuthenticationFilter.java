package io.testhub.platform.security;

import io.testhub.platform.access.ApiTokenPrincipal;
import io.testhub.platform.access.TokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Transient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PersonalAccessTokenAuthenticationFilter extends OncePerRequestFilter {
    private final TokenRepository tokens;
    private final SecurityApiErrorHandler errors;

    public PersonalAccessTokenAuthenticationFilter(TokenRepository tokens, SecurityApiErrorHandler errors) {
        this.tokens = tokens;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        var cached = SecurityContextHolder.getContext().getAuthentication();
        if (cached != null && cached.getPrincipal() instanceof ApiTokenPrincipal) {
            // Older releases persisted PAT identities in sessions. They must not authenticate without a token.
            var session = request.getSession(false);
            if (session != null) session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            SecurityContextHolder.clearContext();
        }
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        // A fresh, transient context gives Bearer precedence without replacing the browser's saved identity.
        var context = new TokenRequestContext();
        SecurityContextHolder.setContext(context);
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            errors.commence(request, response, new BadCredentialsException("Invalid access token"));
            return;
        }
        String hash = sha256(authorization.substring(7).trim());
        var token = tokens.findActiveByHash(hash);
        if (token.isEmpty()) {
            errors.commence(request, response, new BadCredentialsException("Invalid access token"));
            return;
        }
        var authenticated = token.get();
        ApiTokenPrincipal principal = new ApiTokenPrincipal(authenticated.account(), authenticated.projectId(),
                authenticated.scopes());
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        tokens.markUsed(authenticated.tokenId());
        filterChain.doFilter(request, response);
    }

    @Transient
    private static final class TokenRequestContext extends SecurityContextImpl {
        private static final long serialVersionUID = 1L;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("无法校验访问令牌", exception);
        }
    }
}
