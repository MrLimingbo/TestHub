package io.testhub.platform.uiautomation;

import io.testhub.platform.config.SystemConfigRepository;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Browser execution can only start at system-approved origins; Runner enforces the same list independently. */
@Component
public class UiAutomationTargetPolicy {
    public static final String ALLOWED_ORIGINS_CONFIG = "ui-automation.allowed-origins";
    private final SystemConfigRepository configs;
    private final boolean desktop;
    private final String desktopOrigins;

    public UiAutomationTargetPolicy(SystemConfigRepository configs, Environment environment) {
        this.configs = configs;
        this.desktop = environment.acceptsProfiles(Profiles.of("desktop"));
        this.desktopOrigins = environment.getProperty("testhub.desktop.allowed-origins", "");
    }

    public String normalizeAndAssertAllowed(String value) {
        URI uri;
        try { uri = URI.create(value == null ? "" : value.trim()); }
        catch (Exception exception) { throw invalid("浏览器基础地址无效"); }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || (!desktop && isIpLiteral(uri.getHost()))) throw invalid("浏览器基础地址必须是 HTTP(S) 域名，且不得包含账号或 IP 地址");
        String origin = renderOrigin(uri);
        Set<String> allowed = (desktop ? Optional.of(desktopOrigins) : configs.findNonSecretValue(ALLOWED_ORIGINS_CONFIG)).stream()
                .flatMap(raw -> java.util.Arrays.stream(raw.split("[,\\n\\r]+"))).map(String::trim)
                .filter(item -> !item.isBlank()).map(this::origin).collect(Collectors.toSet());
        if (!allowed.contains(origin)) {
            throw invalid(desktop ? "浏览器目标地址未获授权，请在本地执行设置中添加 " + origin
                    : "浏览器目标来源未获系统管理员允许；请在系统配置 " + ALLOWED_ORIGINS_CONFIG + " 中添加 " + origin);
        }
        return uri.normalize().toString();
    }

    // Format validation only, for disabled imported profiles. This never grants execution authorization.
    String normalizeForTransfer(String value) {
        String url = value == null ? "" : value.trim();
        try {
            URI uri = URI.create(url);
            if (url.length() > 2048 || !uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                throw invalid("迁入浏览器基础地址必须是无凭据的 HTTP(S) URL，且不超过 2048 字符");
            return uri.normalize().toString();
        } catch (IllegalArgumentException error) { throw invalid("迁入浏览器基础地址无效"); }
    }

    private String origin(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getPath() == null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) || uri.getQuery() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || (!desktop && isIpLiteral(uri.getHost()))) {
                throw invalid("系统配置 " + ALLOWED_ORIGINS_CONFIG + " 必须是 HTTP(S) Origin 列表");
            }
            return renderOrigin(uri);
        } catch (IllegalArgumentException exception) {
            throw invalid("系统配置 " + ALLOWED_ORIGINS_CONFIG + " 包含无效来源");
        }
    }

    private String renderOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        // The native URL parser strips default ports; use the same exact-origin representation locally.
        if (desktop && (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) port = -1;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port);
    }

    private boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || host.indexOf(':') >= 0;
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
