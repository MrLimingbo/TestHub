package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class DesktopExpiringStateStoreTests {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private DriverManagerDataSource source;
    private JdbcClient jdbc;
    private DesktopExpiringStateStore state;

    @BeforeEach void initialize() {
        source = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("state"), "sa", "state-test-only");
        jdbc = JdbcClient.create(source);
        jdbc.sql("CREATE TABLE desktop_runtime_state(state_key VARCHAR(512) PRIMARY KEY, state_value CLOB NOT NULL, expires_at BIGINT NOT NULL)").update();
        state = reopen();
    }

    @Test void admitsOneConcurrentNonceAndRetainsItAfterAllConnectionsClose() throws Exception {
        try (var keeper = source.getConnection(); var threads = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 32).mapToObj(index -> (Callable<Boolean>) () ->
                    state.putIfAbsent("nonce", "accepted", Duration.ofMinutes(2))).toList();
            int admitted = 0;
            for (var future : threads.invokeAll(tasks)) if (future.get()) admitted++;
            assertThat(admitted).isEqualTo(1);
        }
        var restarted = reopen();
        assertThat(restarted.get("nonce")).isEqualTo("accepted");
        assertThat(restarted.putIfAbsent("nonce", "replayed", Duration.ofMinutes(2))).isFalse();
        clock.advance(Duration.ofMinutes(2));
        assertThat(restarted.get("nonce")).isNull();
        assertThat(restarted.putIfAbsent("nonce", "fresh", Duration.ofMinutes(2))).isTrue();
    }

    @Test void countsConcurrentAttemptsWithoutExtendingTheOriginalWindow() throws Exception {
        try (var keeper = source.getConnection(); var threads = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 32).mapToObj(index -> (Callable<Long>) () ->
                    state.incrementWindow("registration", Duration.ofMinutes(1))).toList();
            var results = new java.util.HashSet<Long>();
            for (var future : threads.invokeAll(tasks)) results.add(future.get());
            assertThat(results).hasSize(32).contains(1L, 32L);
            clock.advance(Duration.ofSeconds(59));
            assertThat(state.incrementWindow("registration", Duration.ofMinutes(1))).isEqualTo(33);
            clock.advance(Duration.ofSeconds(1));
            assertThat(state.incrementWindow("registration", Duration.ofMinutes(1))).isEqualTo(1);
        }
    }

    @Test void businessRollbackCannotUndoNonceAdmission() {
        var business = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> business.executeWithoutResult(status -> {
            assertThat(state.putIfAbsent("nonce", "used", Duration.ofMinutes(2))).isTrue();
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reopen().putIfAbsent("nonce", "retry", Duration.ofMinutes(2))).isFalse();
    }

    @Test void cleansExpiredEntriesAndRejectsInvalidLifetimeWithoutWriting() {
        assertThat(state.putIfAbsent("expired", "value", Duration.ofSeconds(1))).isTrue();
        assertThat(state.putIfAbsent("live", "value", Duration.ofMinutes(1))).isTrue();
        clock.advance(Duration.ofSeconds(1));
        state.purgeExpired();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_runtime_state").query(Integer.class).single()).isEqualTo(1);
        assertThat(state.get("live")).isEqualTo("value");
        assertThatThrownBy(() -> state.putIfAbsent("invalid", "value", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.incrementWindow("invalid", Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        state.delete("live");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_runtime_state").query(Integer.class).single()).isZero();
    }

    private DesktopExpiringStateStore reopen() {
        return new DesktopExpiringStateStore(JdbcClient.create(source), new DataSourceTransactionManager(source), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-18T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
