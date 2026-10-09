package io.testhub.platform.desktop;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("desktop")
@RequestMapping("/internal/desktop/v1")
public class DesktopWorkspaceController {
    private final DesktopWorkspaceService workspace;
    private final String sessionKey;
    private final io.testhub.platform.apiautomation.ApiRunnerStatusService runners;
    private final io.testhub.platform.performance.PerformanceRunnerStatusService performanceRunners;
    private final io.testhub.platform.uiautomation.UiAutomationRunnerStatusService browserRunners;
    private final io.testhub.platform.mobileautomation.MobileAutomationRunnerStatusService mobileRunners;
    public DesktopWorkspaceController(DesktopWorkspaceService workspace, @Value("${testhub.desktop.session-key:}") String sessionKey,
            io.testhub.platform.apiautomation.ApiRunnerStatusService runners,
            io.testhub.platform.performance.PerformanceRunnerStatusService performanceRunners,
            io.testhub.platform.uiautomation.UiAutomationRunnerStatusService browserRunners,
            io.testhub.platform.mobileautomation.MobileAutomationRunnerStatusService mobileRunners) {
        this.workspace = workspace; this.sessionKey = sessionKey; this.runners = runners; this.performanceRunners = performanceRunners; this.browserRunners = browserRunners; this.mobileRunners = mobileRunners;
    }
    private void authorize(HttpServletRequest request) {
        String supplied = request.getHeader("X-TestHub-Desktop-Session");
        if (!loopback(request.getRemoteAddr()) || !loopback(request.getServerName()) || request.getHeader("Origin") != null
                || !sessionKey.matches("[0-9a-f]{64}") || supplied == null || !supplied.matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(sessionKey.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本地工作区请求未获授权");
    }
    @GetMapping("/status")
    public ApiResponse<Map<String, Boolean>> status(HttpServletRequest request, @RequestParam String startedAfter) {
        authorize(request);
        java.time.Instant since;
        try { since = java.time.Instant.parse(startedAfter); }
        catch (java.time.format.DateTimeParseException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "启动时间无效"); }
        return ApiResponse.of(Map.of("apiRunnerReady", runners.isVerifiedSince("desktop-api", since),
                "jmeterRunnerReady", performanceRunners.isVerifiedSince("desktop-jmeter", since),
                "playwrightRunnerReady", browserRunners.isVerifiedSince("desktop-playwright", since),
                "appiumRunnerReady", mobileRunners.isVerifiedSince("desktop-appium", since)));
    }
    @PostMapping("/session")
    public ApiResponse<Map<String, String>> session(HttpServletRequest request) {
        authorize(request);
        var principal = new PlatformUserPrincipal(workspace.owner());
        if (request.getSession(false) != null) request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute("SPRING_SECURITY_CONTEXT", context);
        return ApiResponse.of(Map.of("mode", "local", "userId", principal.userId()));
    }
    private boolean loopback(String host) {
        return Set.of("127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost").contains(host == null ? "" : host);
    }
}
