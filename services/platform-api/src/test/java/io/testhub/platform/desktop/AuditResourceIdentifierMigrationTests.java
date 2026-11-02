package io.testhub.platform.desktop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditResourceIdentifierMigrationTests {
    @TempDir Path directory;

    @Test void widensVersion52AuditIdentifiersWithoutChangingExistingEntries() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        String password = UUID.randomUUID().toString();
        Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").target("52").load().migrate();
        String id = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        try (var connection = DriverManager.getConnection(url, "sa", password);
             var statement = connection.prepareStatement("INSERT INTO audit_log(id,action,resource_type,resource_id,outcome,request_id,detail) VALUES(?,'QA_BEFORE','PROJECT_PARAMETER',?,'SUCCESS','qa','{}')")) {
            statement.setString(1, id); statement.setString(2, "test:EXISTING"); statement.executeUpdate();
        }
        assertEquals(1, Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").target("53").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", password);
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT resource_id,action FROM audit_log WHERE id='" + id + "'")) {
                assertTrue(rows.next()); assertEquals("test:EXISTING", rows.getString(1)); assertEquals("QA_BEFORE", rows.getString(2));
            }
            String longest = "e".repeat(64) + ":" + "K".repeat(128);
            try (var update = connection.prepareStatement("UPDATE audit_log SET resource_id=? WHERE id=?")) {
                update.setString(1, longest); update.setString(2, id); assertEquals(1, update.executeUpdate());
            }
            try (var rows = statement.executeQuery("SELECT resource_id FROM audit_log WHERE id='" + id + "'")) {
                assertTrue(rows.next()); assertEquals(longest, rows.getString(1));
            }
        }
        assertEquals(0, Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").target("53").load().migrate().migrationsExecuted);
    }
}
