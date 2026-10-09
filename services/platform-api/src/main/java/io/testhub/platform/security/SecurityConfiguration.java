package io.testhub.platform.security;

import io.testhub.platform.account.AccountUserDetailsService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(AccountUserDetailsService accounts, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DaoAuthenticationProvider provider,
                                            SecurityApiErrorHandler errorHandler,
                                            PersonalAccessTokenAuthenticationFilter tokenAuthenticationFilter,
                                            org.springframework.core.env.Environment environment,
                                            @Value("${testhub.security.cors.allowed-origins}") List<String> allowedOrigins) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        return http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(request -> {
                            String authorization = request.getHeader("Authorization");
                            return (authorization != null && authorization.startsWith("Bearer "))
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/internal/desktop/v1/")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/internal/desktop-broker/")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/internal/api-runner/")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/internal/performance-runner/")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/internal/ui-automation-runner/")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/internal/mobile-automation-runner/")
                                    || request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/mock/")
                                    || request.getRequestURI().startsWith(request.getContextPath()
                                    + "/api/v1/integrations/api-automation/webhooks/");
                        }))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(allowedOrigins)))
                .headers(headers -> headers
                        .contentSecurityPolicy(policy -> policy.policyDirectives(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("desktop"))
                                ? "default-src 'self'; base-uri 'self'; frame-ancestors 'self'; form-action 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:"
                                : "default-src 'self'; base-uri 'self'; frame-ancestors 'self'; form-action 'self'"))
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.sameOrigin())
                        .permissionsPolicy(policy -> policy.policy("camera=(), geolocation=(), microphone=(), payment=()")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.requireExplicitSave(false))
                .requestCache(requestCache -> requestCache.disable())
                .authenticationProvider(provider)
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        // Health is intentionally detail-free and expected to be reachable by container/Kubernetes probes.
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        // Prometheus must only be reachable on the internal API network in production Compose/Kubernetes.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/capabilities").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                        .requestMatchers("/api/v1/mock/**").permitAll()
                        .requestMatchers("/api/v1/mock-shares/**").permitAll()
                        .requestMatchers("/api/v1/integrations/api-automation/webhooks/**").permitAll()
                        .requestMatchers("/internal/desktop/v1/**").permitAll()
                        .requestMatchers("/internal/desktop-broker/**").permitAll()
                        .requestMatchers("/internal/api-runner/**").permitAll()
                        .requestMatchers("/internal/performance-runner/**").permitAll()
                        .requestMatchers("/internal/ui-automation-runner/**").permitAll()
                        .requestMatchers("/internal/mobile-automation-runner/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // The local UI must load after session expiry so its native bridge can renew the session.
                        // Business APIs and internal controls still require their existing authentication.
                        .requestMatchers(request -> environment.acceptsProfiles(org.springframework.core.env.Profiles.of("desktop"))
                                && "GET".equals(request.getMethod())
                                && isDesktopUiPath(request.getRequestURI().substring(request.getContextPath().length()))).permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    private boolean isDesktopUiPath(String path) {
        return !path.equals("/api") && !path.startsWith("/api/")
                && !path.equals("/internal") && !path.startsWith("/internal/")
                && !path.equals("/actuator") && !path.startsWith("/actuator/");
    }

    private CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(value -> !value.isBlank()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-XSRF-TOKEN", "X-Request-Id",
                "X-TestHub-Webhook-Timestamp", "X-TestHub-Webhook-Nonce", "X-TestHub-Webhook-Signature"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
