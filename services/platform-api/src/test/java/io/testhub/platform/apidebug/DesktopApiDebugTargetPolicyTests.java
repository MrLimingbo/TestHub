package io.testhub.platform.apidebug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DesktopApiDebugTargetPolicyTests {
    private final InetAddress[] loopback = { InetAddress.getLoopbackAddress() };

    @Test
    void desktopRequiresMatchingSchemeHostPortAndLiteralOrLocalhost() {
        var environment = new MockEnvironment()
                .withProperty("testhub.desktop.allowed-origins", "http://127.0.0.1:8123,https://localhost:8443,https://service.example:8443");
        environment.setActiveProfiles("desktop");
        var policy = new DesktopApiDebugTargetPolicy(environment);
        assertTrue(policy.permits(URI.create("ws://127.0.0.1:8123/echo"), false, loopback));
        assertTrue(policy.permits(URI.create("grpcs://localhost:8443"), true, loopback));
        assertFalse(policy.permits(URI.create("ws://127.0.0.1:8124"), false, loopback));
        assertFalse(policy.permits(URI.create("wss://127.0.0.1:8123"), true, loopback));
        assertFalse(policy.permits(URI.create("grpc://service.example:8443"), true, loopback));
    }

    @Test
    void onlineAndUnsafeAddressesRemainBlocked() throws Exception {
        var environment = new MockEnvironment().withProperty("testhub.desktop.allowed-origins", "http://127.0.0.1:8123");
        var online = new DesktopApiDebugTargetPolicy(environment);
        assertFalse(online.permits(URI.create("ws://127.0.0.1:8123"), false, loopback));

        environment.setActiveProfiles("desktop");
        var desktop = new DesktopApiDebugTargetPolicy(environment);
        assertFalse(desktop.permits(URI.create("ws://127.0.0.1:8123"), false,
                new InetAddress[] { InetAddress.getByName("169.254.169.254") }));
        assertFalse(desktop.permits(URI.create("ws://127.0.0.1:8123"), false,
                new InetAddress[] { InetAddress.getByName("0.0.0.0") }));
    }
}
