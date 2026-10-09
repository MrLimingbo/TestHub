package io.testhub.platform.apidebug;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Native workspace settings authorize exact endpoints; online services never use this exception. */
@Component
public class DesktopApiDebugTargetPolicy {
    private final boolean desktop;
    private final String origins;

    public DesktopApiDebugTargetPolicy(Environment environment) {
        desktop = environment.acceptsProfiles(Profiles.of("desktop"));
        origins = environment.getProperty("testhub.desktop.allowed-origins", "");
    }

    boolean permits(URI target, boolean tls, InetAddress[] addresses) {
        if (!desktop || addresses.length == 0 || Arrays.stream(addresses).anyMatch(this::alwaysBlocked)) return false;
        String host = target.getHost();
        if (!host.equalsIgnoreCase("localhost") && !host.matches("[0-9.]+") && !host.contains(":")) return false;
        int targetPort = target.getPort() == -1 ? (tls ? 443 : 80) : target.getPort();
        if (targetPort < 1 || targetPort > 65535) return false;
        String scheme = tls ? "https" : "http";
        for (String item : origins.split("[,\\n\\r]+")) {
            try {
                URI origin = URI.create(item.trim());
                if (!scheme.equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                        || origin.getRawQuery() != null || origin.getRawFragment() != null
                        || (origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !origin.getRawPath().equals("/"))) continue;
                int port = origin.getPort() == -1 ? (tls ? 443 : 80) : origin.getPort();
                if (port == targetPort && origin.getHost().toLowerCase(Locale.ROOT).equals(target.getHost().toLowerCase(Locale.ROOT))) return true;
            } catch (IllegalArgumentException ignored) {
                // Invalid entries do not grant access; the native settings editor validates before saving.
            }
        }
        return false;
    }

    private boolean alwaysBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int first = Byte.toUnsignedInt(bytes[0]), second = Byte.toUnsignedInt(bytes[1]);
        return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19));
    }
}
