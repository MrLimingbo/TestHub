package io.testhub.platform.notification;

import io.testhub.platform.config.SystemConfigRepository;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Explicit administrator allow-list prevents notification templates becoming an SSRF primitive. */
@Component
public class WebhookTargetPolicy {
    static final String ALLOWED_HOSTS_CONFIG = "notifications.webhook.allowed-hosts";
    private final SystemConfigRepository configs;

    public WebhookTargetPolicy(SystemConfigRepository configs) {
        this.configs = configs;
    }

    public String normalizeAndAssertAllowed(String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (Exception exception) {
            throw invalid("Webhook 地址无效");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank() || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || isIpLiteral(host)) {
            throw invalid("Webhook 必须使用 HTTPS 域名地址，且不得包含账号或非标准端口");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        Set<String> allowed = configs.findNonSecretValue(ALLOWED_HOSTS_CONFIG).stream()
                .flatMap(valueFromConfig -> java.util.Arrays.stream(valueFromConfig.split("[,\\n\\r]+")))
                .map(item -> item.trim().toLowerCase(Locale.ROOT)).filter(item -> !item.isBlank()).collect(Collectors.toSet());
        if (allowed.stream().noneMatch(rule -> matches(rule, normalizedHost))) {
            throw invalid("Webhook 主机未获系统管理员允许；请在系统配置 " + ALLOWED_HOSTS_CONFIG + " 中添加该域名");
        }
        return uri.normalize().toString();
    }

    private boolean matches(String rule, String host) {
        return rule.equals(host) || (rule.startsWith("*.") && host.endsWith(rule.substring(1)) && host.length() > rule.length() - 1);
    }

    private boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || host.indexOf(':') >= 0;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
