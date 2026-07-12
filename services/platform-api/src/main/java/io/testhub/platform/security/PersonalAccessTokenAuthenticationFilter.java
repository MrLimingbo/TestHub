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
import org.springframework.security.core.context.SecurityContextHolder;
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
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            errors.commence(request, response, new BadCredentialsException("Invalid access token"));
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String hash = sha256(authorization.substring(7).trim());
            var token = tokens.findActiveByHash(hash);
            if (token.isEmpty()) {
                errors.commence(request, response, new BadCredentialsException("Invalid access token"));
                return;
            }
            var authenticated = token.get();
            ApiTokenPrincipal principal = new ApiTokenPrincipal(authenticated.account(), authenticated.projectId(),
                    authenticated.scopes());
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
            tokens.markUsed(authenticated.tokenId());
        }
        filterChain.doFilter(request, response);
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
