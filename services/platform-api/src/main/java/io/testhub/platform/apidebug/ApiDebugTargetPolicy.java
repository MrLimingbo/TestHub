package io.testhub.platform.apidebug;

import io.testhub.platform.config.SystemConfigRepository;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * SSRF policy for HTTP API debugging. Public targets remain available, while a private target must use an
 * administrator-approved, exact IP origin. Addresses that are unsafe even inside a private network can never be
 * approved.
 */
@Component
public class ApiDebugTargetPolicy {
    public static final String ALLOWED_PRIVATE_ORIGINS_CONFIG = "api-debug.allowed-private-origins";
    public static final String ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG = "api-debug.allow-configured-private-targets";
    private static final int MAX_URL_LENGTH = 2_000;
    private static final int MAX_ALLOWED_ORIGINS = 128;

    private final Supplier<Optional<String>> configuredOrigins;
    private final Supplier<Optional<String>> allowConfiguredPrivateTargets;
    private final HostResolver resolver;

    @Autowired
    public ApiDebugTargetPolicy(SystemConfigRepository configs) {
        this(() -> configs.findNonSecretValue(ALLOWED_PRIVATE_ORIGINS_CONFIG),
                () -> configs.findNonSecretValue(ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG), InetAddress::getAllByName);
    }

    ApiDebugTargetPolicy(Supplier<Optional<String>> configuredOrigins, HostResolver resolver) {
        this(configuredOrigins, Optional::<String>empty, resolver);
    }

    ApiDebugTargetPolicy(Supplier<Optional<String>> configuredOrigins,
                         Supplier<Optional<String>> allowConfiguredPrivateTargets, HostResolver resolver) {
        this.configuredOrigins = configuredOrigins;
        this.allowConfiguredPrivateTargets = allowConfiguredPrivateTargets;
        this.resolver = resolver;
    }

    public URI normalizeAndAssertAllowed(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
            throw rejected("请求 URL 不能为空且不能超过 2000 字符");
        }
        URI target;
        try {
            target = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw rejected("请求 URL 格式不合法");
        }
        assertAllowed(target);
        return target;
    }

    /** Re-check immediately before the real transport sends the request. */
    public void assertAllowed(URI target) {
        if (target == null || target.getScheme() == null
                || !("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null || target.getHost().isBlank() || target.getUserInfo() != null
                || target.getPort() == 0 || target.getPort() < -1 || target.getPort() > 65_535) {
            throw rejected("仅支持不含用户信息的 HTTP 或 HTTPS URL");
        }

        InetAddress[] addresses = resolve(target.getHost());
        if (addresses.length == 0) throw rejected("无法解析请求主机");
        boolean privateAddress = false;
        for (InetAddress address : addresses) {
            if (alwaysBlocked(address)) throw permanentlyBlocked(address);
            privateAddress |= isAllowlistEligiblePrivate(address);
        }
        if (!privateAddress) return;

        if (configuredPrivateTargetsAllowed()) {
            if (ipLiteral(target.getHost()).isEmpty()) {
                throw rejected("任意私网目标模式仅允许请求 URL 使用私网 IP；为避免 DNS 重绑定，在线调试不放行解析到私网的域名");
            }
            return;
        }

        Origin origin = targetOrigin(target);
        if (!allowedPrivateOrigins().contains(origin)) {
            throw rejected("目标 Origin " + origin.render() + " 属于私网且默认禁止访问；如确有需要，请由系统管理员在系统配置 "
                    + ALLOWED_PRIVATE_ORIGINS_CONFIG + " 中添加该精确 Origin，或启用 "
                    + ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG + "（仅支持私网 IP；回环、链路本地和保留地址始终禁止）");
        }
    }

    public URI normalizeWithoutNetworkPolicy(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
            throw rejected("请求 URL 不能为空且不能超过 2000 字符");
        }
        URI target;
        try {
            target = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw rejected("请求 URL 格式不合法");
        }
        if (target.getScheme() == null
                || !("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))
                || target.getHost() == null || target.getHost().isBlank() || target.getUserInfo() != null
                || target.getPort() == 0 || target.getPort() < -1 || target.getPort() > 65_535) {
            throw rejected("仅支持不含用户信息的 HTTP 或 HTTPS URL");
        }
        return target;
    }

    private InetAddress[] resolve(String host) {
        Optional<InetAddress> literal = ipLiteral(host);
        if (literal.isPresent()) return new InetAddress[] {literal.get()};
        try {
            return resolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw rejected("无法解析请求主机");
        }
    }

    private Set<Origin> allowedPrivateOrigins() {
        String raw = configuredOrigins.get().orElse("");
        if (raw.isBlank()) return Set.of();
        Set<Origin> values = new LinkedHashSet<>();
        for (String item : raw.split("[,\\n\\r]+")) {
            if (item.isBlank()) continue;
            values.add(configuredOrigin(item.trim()));
            if (values.size() > MAX_ALLOWED_ORIGINS) throw invalidConfiguration();
        }
        return Set.copyOf(values);
    }

    private boolean configuredPrivateTargetsAllowed() {
        String value = allowConfiguredPrivateTargets.get().orElse("").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw rejected("系统配置 " + ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG + " 仅允许 true 或 false");
    }

    private Origin configuredOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
        String path = uri.getRawPath();
        if (uri.getScheme() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() < 1 || uri.getPort() > 65_535
                || (path != null && !path.isEmpty() && !"/".equals(path)) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalidConfiguration();
        }
        InetAddress address = ipLiteral(uri.getHost()).orElseThrow(this::invalidConfiguration);
        if (alwaysBlocked(address) || !isAllowlistEligiblePrivate(address)) throw invalidConfiguration();
        return new Origin(uri.getScheme().toLowerCase(Locale.ROOT), canonicalAddress(address), uri.getPort());
    }

    private Origin targetOrigin(URI target) {
        Optional<InetAddress> literal = ipLiteral(target.getHost());
        String host = literal.map(this::canonicalAddress).orElseGet(() -> target.getHost().toLowerCase(Locale.ROOT));
        int port = target.getPort() == -1 ? ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80) : target.getPort();
        return new Origin(target.getScheme().toLowerCase(Locale.ROOT), host, port);
    }

    private Optional<InetAddress> ipLiteral(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) return Optional.empty();
        String host = rawHost.startsWith("[") && rawHost.endsWith("]") ? rawHost.substring(1, rawHost.length() - 1) : rawHost;
        byte[] ipv4 = ipv4Bytes(host);
        if (ipv4 != null) {
            try {
                return Optional.of(InetAddress.getByAddress(ipv4));
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        if (host.indexOf(':') < 0 || !host.matches("[0-9A-Fa-f:.]+")) return Optional.empty();
        try {
            InetAddress parsed = InetAddress.getByName(host);
            return parsed.getAddress().length == 16 ? Optional.of(parsed) : Optional.empty();
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private byte[] ipv4Bytes(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] result = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) return null;
            int value;
            try {
                value = Integer.parseInt(parts[index]);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (value > 255) return null;
            result[index] = (byte) value;
        }
        return result;
    }

    private boolean isAllowlistEligiblePrivate(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = unsigned(bytes[0]);
            int second = unsigned(bytes[1]);
            return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
        }
        return bytes.length == 16 && (unsigned(bytes[0]) & 0xfe) == 0xfc;
    }

    private boolean alwaysBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) return true;
        if (isAllowlistEligiblePrivate(address)) return false;
        if (address.isSiteLocalAddress()) return true;

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = unsigned(bytes[0]);
            int second = unsigned(bytes[1]);
            int third = unsigned(bytes[2]);
            return first == 0 || first >= 224
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 192 && second == 88 && third == 99)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113);
        }
        return bytes.length != 16
                || inPrefix(bytes, new byte[] {0x00, 0x64, (byte) 0xff, (byte) 0x9b}, 96)
                || inPrefix(bytes, new byte[] {0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0x00, 0x01}, 48)
                || inPrefix(bytes, new byte[] {0x01, 0x00}, 64)
                || inPrefix(bytes, new byte[] {0x20, 0x01}, 23)
                || inPrefix(bytes, new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8}, 32)
                || inPrefix(bytes, new byte[] {0x20, 0x02}, 16);
    }

    private boolean inPrefix(byte[] address, byte[] prefix, int bits) {
        int wholeBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int index = 0; index < wholeBytes; index++) {
            byte expected = index < prefix.length ? prefix[index] : 0;
            if (address[index] != expected) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        int expected = wholeBytes < prefix.length ? unsigned(prefix[wholeBytes]) : 0;
        return (unsigned(address[wholeBytes]) & mask) == (expected & mask);
    }

    private String canonicalAddress(InetAddress address) {
        return address.getHostAddress().toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException permanentlyBlocked(InetAddress address) {
        return rejected("目标地址 " + canonicalAddress(address)
                + " 属于永久禁止的回环、链路本地、元数据、组播、未指定或保留地址，不能通过系统配置放行");
    }

    private ResponseStatusException invalidConfiguration() {
        return rejected("系统配置 " + ALLOWED_PRIVATE_ORIGINS_CONFIG
                + " 包含无效项；每项必须是含显式端口的 HTTP(S) 私网 IP Origin，且不能使用永久禁止地址");
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private record Origin(String scheme, String host, int port) {
        private String render() {
            return scheme + "://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }
}
