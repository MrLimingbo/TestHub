package io.testhub.platform.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import io.testhub.platform.shared.runtime.ExpiringStateStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RegistrationRateLimiter {
    private final ExpiringStateStore state;
    private final int attemptsPerMinute;

    public RegistrationRateLimiter(ExpiringStateStore state,
                                   @Value("${testhub.registration.attempts-per-minute:10}") int attemptsPerMinute) {
        if (attemptsPerMinute < 1) throw new IllegalArgumentException("Registration limit must be positive");
        this.state = state;
        this.attemptsPerMinute = attemptsPerMinute;
    }

    public void requireAllowed(String remoteAddress) {
        // Do not trust caller-provided forwarding headers or store raw addresses in state keys.
        String key = "testhub:registration:attempts:" + digest(remoteAddress);
        long attempts = state.incrementWindow(key, Duration.ofMinutes(1));
        if (attempts > attemptsPerMinute) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "注册请求过于频繁，请一分钟后重试");
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
