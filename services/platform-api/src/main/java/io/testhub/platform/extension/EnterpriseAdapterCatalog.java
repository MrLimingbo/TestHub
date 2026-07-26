package io.testhub.platform.extension;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Provider-neutral validation rules. This catalog deliberately validates configuration only: loading third-party
 * libraries or opening provider connections in the control plane would make an extension failure platform-wide.
 */
@Component
public class EnterpriseAdapterCatalog {
    private static final Map<String, EnterpriseAdapterDefinition> DEFINITIONS = builtInDefinitions();

    public List<EnterpriseAdapterDefinition> definitions() {
        return List.copyOf(DEFINITIONS.values());
    }

    public EnterpriseAdapterDefinition requireDefinition(String value) {
        EnterpriseAdapterDefinition definition = DEFINITIONS.get(normalizeType(value));
        if (definition == null) throw rejected("不支持的企业适配器类型");
        return definition;
    }

    public String validateAndNormalizeEndpoint(String type, String value) {
        String normalizedType = normalizeType(type);
        URI endpoint;
        try {
            endpoint = URI.create(value == null ? "" : value.trim()).normalize();
        } catch (IllegalArgumentException exception) {
            throw rejected("适配器地址无效");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank() || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw rejected("适配器地址必须使用不含账号、查询参数或片段的主机地址");
        }
        if ("LDAP".equals(normalizedType)) {
            if (!"ldaps".equalsIgnoreCase(endpoint.getScheme()) || (endpoint.getPort() != -1 && endpoint.getPort() != 636)) {
                throw rejected("LDAP 仅支持标准端口的 LDAPS 地址");
            }
        } else if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            throw rejected("外部适配器必须使用标准端口的 HTTPS 地址");
        }
        return endpoint.toString();
    }

    public Map<String, String> validateConfiguration(String type, Map<String, String> input) {
        EnterpriseAdapterDefinition definition = requireDefinition(type);
        if (input == null) throw rejected("适配器配置不能为空");
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!key.matches("[a-z][a-zA-Z0-9_.-]{0,79}") || value.isBlank() || value.length() > 20_000) {
                throw rejected("适配器配置字段不合法");
            }
            normalized.put(key, value);
        }
        if (!normalized.keySet().containsAll(definition.requiredFields())) {
            throw rejected("适配器缺少必填配置字段");
        }
        return Map.copyOf(normalized);
    }

    public String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, EnterpriseAdapterDefinition> builtInDefinitions() {
        Map<String, EnterpriseAdapterDefinition> definitions = new LinkedHashMap<>();
        definitions.put("OIDC", new EnterpriseAdapterDefinition("OIDC", "OpenID Connect", "身份认证",
                List.of("clientId", "clientSecret", "redirectUri"), "通过发现文档接入企业身份提供方；客户端密钥加密保存。"));
        definitions.put("LDAP", new EnterpriseAdapterDefinition("LDAP", "LDAP / Active Directory", "身份认证",
                List.of("bindDn", "bindPassword", "userSearchBase", "userFilter"), "仅支持 LDAPS；目录查询由独立身份适配器执行。"));
        definitions.put("WECHAT_WORK", new EnterpriseAdapterDefinition("WECHAT_WORK", "企业微信", "通知",
                List.of("webhookKey"), "用于将质量事件投递至企业微信机器人。"));
        definitions.put("DINGTALK", new EnterpriseAdapterDefinition("DINGTALK", "钉钉", "通知",
                List.of("webhookToken", "signingSecret"), "用于将质量事件投递至钉钉机器人。"));
        definitions.put("JIRA", new EnterpriseAdapterDefinition("JIRA", "Jira", "缺陷管理",
                List.of("username", "apiToken", "projectKey"), "为需求、缺陷和测试结果提供双向映射契约。"));
        definitions.put("ZENTAO", new EnterpriseAdapterDefinition("ZENTAO", "禅道", "缺陷管理",
                List.of("account", "token", "productId"), "为需求、缺陷和测试结果提供双向映射契约。"));
        return Map.copyOf(definitions);
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
