package io.testhub.platform.desktop;

import io.testhub.platform.shared.runtime.ExpiringStateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("desktop")
public class DesktopExpiringStateStore implements ExpiringStateStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public DesktopExpiringStateStore(HikariDataSource businessDataSource) {
        this(new DriverManagerDataSource(businessDataSource.getJdbcUrl(), businessDataSource.getUsername(),
                businessDataSource.getPassword()));
    }

    private DesktopExpiringStateStore(DataSource source) {
        // Short-lived embedded connections keep independent nonce commits from exhausting the
        // business pool when all request threads already own an outer transaction connection.
        this(JdbcClient.create(source), new DataSourceTransactionManager(source), Clock.systemUTC());
    }

    DesktopExpiringStateStore(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        // As with Redis, accepting a nonce must survive a later rollback of the business operation.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public String get(String key) {
        return jdbc.sql("SELECT state_value FROM desktop_runtime_state WHERE state_key=:key AND expires_at>:now")
                .param("key", key).param("now", clock.millis()).query(String.class).optional().orElse(null);
    }

    // The embedded file database has one owning JVM. Serialize writes through its singleton store;
    // the transaction commits while holding the monitor, including concurrent servlet requests.
    @Override public synchronized boolean putIfAbsent(String key, String value, Duration lifetime) {
        long now = clock.millis();
        long expiresAt = deadline(now, lifetime);
        Objects.requireNonNull(value, "State value is required");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            removeExpired(key, now);
            if (jdbc.sql("SELECT COUNT(*) FROM desktop_runtime_state WHERE state_key=:key")
                    .param("key", key).query(Integer.class).single() > 0) return false;
            insert(key, value, expiresAt);
            return true;
        }));
    }

    @Override public synchronized long incrementWindow(String key, Duration lifetime) {
        long now = clock.millis();
        long expiresAt = deadline(now, lifetime);
        return Objects.requireNonNull(transaction.execute(status -> {
            removeExpired(key, now);
            var previous = jdbc.sql("SELECT state_value FROM desktop_runtime_state WHERE state_key=:key")
                    .param("key", key).query(String.class).optional();
            if (previous.isEmpty()) { insert(key, "1", expiresAt); return 1L; }
            long next = Math.addExact(Long.parseLong(previous.get()), 1);
            // The first attempt owns the window deadline; later attempts do not extend it.
            jdbc.sql("UPDATE desktop_runtime_state SET state_value=:value WHERE state_key=:key")
                    .param("value", Long.toString(next)).param("key", key).update();
            return next;
        }));
    }

    @Override public synchronized void delete(String key) {
        transaction.executeWithoutResult(status -> jdbc.sql("DELETE FROM desktop_runtime_state WHERE state_key=:key")
                .param("key", key).update());
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public synchronized void purgeExpired() {
        transaction.executeWithoutResult(status -> jdbc.sql("DELETE FROM desktop_runtime_state WHERE expires_at<=:now")
                .param("now", clock.millis()).update());
    }

    private void removeExpired(String key, long now) {
        jdbc.sql("DELETE FROM desktop_runtime_state WHERE state_key=:key AND expires_at<=:now")
                .param("key", key).param("now", now).update();
    }

    private void insert(String key, String value, long expiresAt) {
        jdbc.sql("INSERT INTO desktop_runtime_state(state_key,state_value,expires_at) VALUES(:key,:value,:expires)")
                .param("key", key).param("value", value).param("expires", expiresAt).update();
    }

    private long deadline(long now, Duration lifetime) {
        if (lifetime == null || lifetime.toMillis() <= 0) throw new IllegalArgumentException("State lifetime must be positive");
        return Math.addExact(now, lifetime.toMillis());
    }
}
