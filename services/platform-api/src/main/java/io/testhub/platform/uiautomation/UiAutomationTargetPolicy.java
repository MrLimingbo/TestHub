package io.testhub.platform.uiautomation;

import io.testhub.platform.config.SystemConfigRepository;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Browser execution can only start at system-approved origins; Runner enforces the same list independently. */
@Component
public class UiAutomationTargetPolicy {
    public static final String ALLOWED_ORIGINS_CONFIG = "ui-automation.allowed-origins";
    private final SystemConfigRepository configs;

    public UiAutomationTargetPolicy(SystemConfigRepository configs) {
        this.configs = configs;
    }

    public String normalizeAndAssertAllowed(String value) {
        URI uri;
        try { uri = URI.create(value == null ? "" : value.trim()); }
        catch (Exception exception) { throw invalid("浏览器基础地址无效"); }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || isIpLiteral(uri.getHost())) throw invalid("浏览器基础地址必须是 HTTP(S) 域名，且不得包含账号或 IP 地址");
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        Set<String> allowed = configs.findNonSecretValue(ALLOWED_ORIGINS_CONFIG).stream()
                .flatMap(raw -> java.util.Arrays.stream(raw.split("[,\\n\\r]+"))).map(String::trim)
                .filter(item -> !item.isBlank()).map(this::origin).collect(Collectors.toSet());
        if (!allowed.contains(origin)) {
            throw invalid("浏览器目标来源未获系统管理员允许；请在系统配置 " + ALLOWED_ORIGINS_CONFIG + " 中添加 " + origin);
        }
        return uri.normalize().toString();
    }

    private String origin(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getPath() == null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) || uri.getQuery() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || isIpLiteral(uri.getHost())) {
                throw invalid("系统配置 " + ALLOWED_ORIGINS_CONFIG + " 必须是 HTTP(S) Origin 列表");
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException exception) {
            throw invalid("系统配置 " + ALLOWED_ORIGINS_CONFIG + " 包含无效来源");
        }
    }

    private boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || host.indexOf(':') >= 0;
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
