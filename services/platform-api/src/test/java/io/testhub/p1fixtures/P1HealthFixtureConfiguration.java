package io.testhub.p1fixtures;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@TestConfiguration
public class P1HealthFixtureConfiguration {
    public static final AtomicReference<String> MODE = new AtomicReference<>("UP");
    public static final AtomicInteger BUSY = new AtomicInteger();
    @Bean("p1Dependency") HealthIndicator dependency() {
        return () -> {
            if (MODE.get().equals("SLOW")) {
                BUSY.incrementAndGet();
                try { Thread.sleep(10_000); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return Health.down().build(); }
                finally { BUSY.decrementAndGet(); }
            }
            return MODE.get().equals("DOWN") ? Health.down().withDetail("private", "p1-private-endpoint").build() : Health.up().build();
        };
    }
    @RestController public static class QaController {
        @GetMapping("/api/v1/p1-fixture/redis-failure") String redis() { throw new RedisConnectionFailureException("p1-private-endpoint"); }
        @GetMapping("/api/v1/p1-fixture/product-defect") String bug() { throw new IllegalStateException("p1-private-defect"); }
    }
}
