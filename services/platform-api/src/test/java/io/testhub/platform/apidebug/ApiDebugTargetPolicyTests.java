package io.testhub.platform.apidebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiDebugTargetPolicyTests {
    @Test
    void publicTargetsRemainAllowedAndPrivateTargetsRequireAnExactConfiguredOrigin() {
        AtomicReference<String> configured = new AtomicReference<>("");
        ApiDebugTargetPolicy policy = policy(configured, host -> addresses("8.8.8.8"));

        assertEquals(URI.create("https://8.8.8.8/public"), policy.normalizeAndAssertAllowed("https://8.8.8.8/public"));
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://172.27.15.153:8888/private"));
        assertTrue(denied.getReason().contains(ApiDebugTargetPolicy.ALLOWED_PRIVATE_ORIGINS_CONFIG));

        configured.set("http://172.27.15.153:8888");
        assertEquals(URI.create("http://172.27.15.153:8888/private"),
                policy.normalizeAndAssertAllowed("http://172.27.15.153:8888/private"));
        assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://172.27.15.153:8889/private"));
        assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("https://172.27.15.153:8888/private"));
        assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://172.27.15.154:8888/private"));
    }

    @Test
    void acceptsCommaOrLineSeparatedPrivateIpOriginsButNeverPrivateDnsNames() {
        AtomicReference<String> configured = new AtomicReference<>("http://10.1.2.3:8080,\nhttps://[fd00::1234]:8443");
        ApiDebugTargetPolicy policy = policy(configured, host -> {
            if (host.equals("internal.test")) return addresses("10.1.2.3");
            throw new UnknownHostException(host);
        });

        policy.normalizeAndAssertAllowed("http://10.1.2.3:8080/health");
        policy.normalizeAndAssertAllowed("https://[fd00::1234]:8443/health");
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://internal.test:8080/health"));
        assertTrue(denied.getReason().contains("精确 Origin"));
    }

    @Test
    void administratorCanAllowAnyConfiguredPrivateIpWhilePrivateDnsStillFailsClosed() {
        AtomicReference<String> allowAny = new AtomicReference<>("true");
        ApiDebugTargetPolicy policy = new ApiDebugTargetPolicy(Optional::<String>empty,
                () -> Optional.ofNullable(allowAny.get()), host -> addresses("10.1.2.3"));

        assertEquals(URI.create("http://10.20.30.40:3210/health"),
                policy.normalizeAndAssertAllowed("http://10.20.30.40:3210/health"));
        ResponseStatusException dnsDenied = assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://internal.test:3210/health"));
        assertTrue(dnsDenied.getReason().contains("DNS 重绑定"));

        allowAny.set("false");
        ResponseStatusException disabled = assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://10.20.30.40:3210/health"));
        assertTrue(disabled.getReason().contains(ApiDebugTargetPolicy.ALLOW_CONFIGURED_PRIVATE_TARGETS_CONFIG));
    }

    @Test
    void arbitraryPrivateTargetSettingFailsClosedForInvalidBooleanValues() {
        ApiDebugTargetPolicy policy = new ApiDebugTargetPolicy(Optional::<String>empty,
                () -> Optional.of("yes"), host -> addresses("10.1.2.3"));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("http://10.1.2.3:8080/health"));
        assertTrue(failure.getReason().contains("true 或 false"));
    }

    @Test
    void invalidAdministratorConfigurationFailsClosed() {
        for (String configured : new String[] {
                "http://internal.test:8888",
                "http://172.27.15.153",
                "http://172.27.15.153:8888/api",
                "http://8.8.8.8:8888",
                "http://127.0.0.1:8888"
        }) {
            ApiDebugTargetPolicy policy = policy(new AtomicReference<>(configured), host -> addresses("8.8.8.8"));
            ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                    () -> policy.normalizeAndAssertAllowed("http://172.27.15.153:8888/private"), configured);
            assertTrue(failure.getReason().contains(ApiDebugTargetPolicy.ALLOWED_PRIVATE_ORIGINS_CONFIG), configured);
            assertTrue(failure.getReason().contains("无效项"), configured);
        }
    }

    @Test
    void loopbackMetadataMulticastUnspecifiedAndReservedTargetsCanNeverBeAllowed() {
        String[] permanentlyBlocked = {
                "127.0.0.1",
                "169.254.169.254",
                "224.0.0.1",
                "0.0.0.0",
                "100.64.0.1",
                "192.0.2.1",
                "198.18.0.1",
                "203.0.113.1",
                "::",
                "::1",
                "64:ff9b::1",
                "64:ff9b:1::1",
                "100::1",
                "2001::1",
                "2001:db8::1",
                "2002::1",
                "fe80::1",
                "ff02::1"
        };
        for (String address : permanentlyBlocked) {
            String host = address.indexOf(':') >= 0 ? "[" + address + "]" : address;
            AtomicReference<String> configured = new AtomicReference<>("http://" + host + ":8888");
            ApiDebugTargetPolicy policy = policy(configured, ignored -> addresses("8.8.8.8"));
            ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                    () -> policy.normalizeAndAssertAllowed("http://" + host + ":8888/private"), address);
            assertTrue(denied.getReason().contains("永久禁止"), address);
        }
    }

    @Test
    void everyDnsAnswerIsValidatedSoMixedAnswersCannotReachABlockedAddress() {
        ApiDebugTargetPolicy policy = policy(new AtomicReference<>(""),
                host -> addresses("8.8.8.8", "169.254.169.254"));

        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> policy.normalizeAndAssertAllowed("https://mixed-answer.test/resource"));
        assertTrue(denied.getReason().contains("永久禁止"));
    }

    private ApiDebugTargetPolicy policy(AtomicReference<String> configured, ApiDebugTargetPolicy.HostResolver resolver) {
        return new ApiDebugTargetPolicy(() -> Optional.ofNullable(configured.get()), resolver);
    }

    private static InetAddress[] addresses(String... values) throws UnknownHostException {
        InetAddress[] result = new InetAddress[values.length];
        for (int index = 0; index < values.length; index++) result[index] = InetAddress.getByName(values[index]);
        return result;
    }
}
