package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.ulid.UlidCreator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.transaction.support.TransactionTemplate;

class DesktopSessionPersistenceTests {
    @TempDir Path directory;

    @Test void upgradesVersionOneWithoutRebuildingTheWorkspace() {
        var source = source();
        Flyway.configure().dataSource(source).locations("classpath:db/desktop").target("1").load().migrate();
        JdbcClient jdbc = JdbcClient.create(source);
        String id = UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO organization(id,name,code) VALUES(:id,'Existing local workspace','existing')").param("id", id).update();
        var migrated = Flyway.configure().dataSource(source).locations("classpath:db/desktop").target("2").load().migrate();
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.sql("SELECT name FROM organization WHERE id=:id").param("id", id).query(String.class).single())
                .isEqualTo("Existing local workspace");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_session").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_runtime_state").query(Integer.class).single()).isZero();
    }

    @Test void installsBrokerWithoutInvalidatingExistingSessionsOrSecurityState() {
        var source = source();
        Flyway.configure().dataSource(source).locations("classpath:db/desktop").target("2").load().migrate();
        var sessions = contract(repository(source));
        var session = sessions.createSession();
        session.setAttribute("workspace", "preserved");
        sessions.save(session);
        var jdbc = JdbcClient.create(source);
        jdbc.sql("INSERT INTO desktop_runtime_state(state_key,state_value,expires_at) VALUES('nonce','used',?)")
                .param(System.currentTimeMillis() + 60000).update();
        var migrated = Flyway.configure().dataSource(source).locations("classpath:db/desktop").target("3").load().migrate();
        assertThat(migrated.migrationsExecuted).isEqualTo(1);
        assertThat((String) sessions.findById(session.getId()).getAttribute("workspace")).isEqualTo("preserved");
        assertThat(jdbc.sql("SELECT state_value FROM desktop_runtime_state WHERE state_key='nonce'").query(String.class).single()).isEqualTo("used");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_stream_message").query(Integer.class).single()).isZero();
    }

    @Test void sessionsSurviveReopenAndHonorRotationInvalidationAndExpiry() {
        var source = source();
        new ResourceDatabasePopulator(new ClassPathResource("db/desktop/V2__local_sessions_and_runtime_state.sql")).execute(source);
        var implementation = repository(source);
        var repository = contract(implementation);
        var session = repository.createSession();
        session.setAttribute("workspace", "local-workspace");
        repository.save(session);
        String oldId = session.getId();
        var reopened = contract(repository(source()));
        var restored = reopened.findById(oldId);
        assertThat(restored).isNotNull();
        assertThat((String) restored.getAttribute("workspace")).isEqualTo("local-workspace");
        String nextId = restored.changeSessionId();
        reopened.save(restored);
        assertThat(repository.findById(oldId)).isNull();
        assertThat(repository.findById(nextId)).isNotNull();
        repository.deleteById(nextId);
        assertThat(reopened.findById(nextId)).isNull();

        var expired = reopened.createSession();
        expired.setAttribute("workspace", "expired");
        expired.setMaxInactiveInterval(Duration.ofSeconds(1));
        expired.setLastAccessedTime(Instant.now().minusSeconds(60));
        reopened.save(expired);
        assertThat(repository.findById(expired.getId())).isNull();
        implementation.cleanUpExpiredSessions();
        assertThat(JdbcClient.create(source).sql("SELECT COUNT(*) FROM desktop_session_attributes").query(Integer.class).single()).isZero();
    }

    // JDBC's concrete session type is package-private; exercise its public Session contract.
    @SuppressWarnings("unchecked")
    private SessionRepository<Session> contract(JdbcIndexedSessionRepository repository) {
        return (SessionRepository<Session>) (SessionRepository<?>) repository;
    }

    private DriverManagerDataSource source() {
        return new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("workspace")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "session-test-only");
    }

    private JdbcIndexedSessionRepository repository(DriverManagerDataSource source) {
        var repository = new JdbcIndexedSessionRepository(new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        repository.setTableName("desktop_session");
        return repository;
    }
}
