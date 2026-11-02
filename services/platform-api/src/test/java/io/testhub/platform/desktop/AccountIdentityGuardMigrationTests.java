package io.testhub.platform.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.github.f4b6a3.ulid.UlidCreator;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountIdentityGuardMigrationTests {
    @TempDir Path directory;

    @Test void version52AddsOnlyTheGuardAndPreservesExistingAccountIdentity() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        String password = java.util.UUID.randomUUID().toString();
        Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").target("51").load().migrate();
        String id = UlidCreator.getUlid().toString();
        try (var connection = DriverManager.getConnection(url, "sa", password);
             var statement = connection.prepareStatement("INSERT INTO user_account(id,account,email,display_name,password_hash,enabled) VALUES(?,?,?,?,?,FALSE)")) {
            statement.setString(1, id); statement.setString(2, "migration-account"); statement.setString(3, "migration@example.test");
            statement.setString(4, "保留姓名"); statement.setString(5, "fixture-hash"); statement.executeUpdate();
        }
        var result = Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").target("52").load().migrate();
        assertEquals(1, result.migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, "sa", password); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT account,email,display_name,password_hash,enabled FROM user_account WHERE id='" + id + "'")) {
                assertTrue(row.next()); assertEquals("migration-account", row.getString(1)); assertEquals("migration@example.test", row.getString(2));
                assertEquals("保留姓名", row.getString(3)); assertEquals("fixture-hash", row.getString(4)); assertFalse(row.getBoolean(5));
            }
            try (var row = statement.executeQuery("SELECT singleton_id FROM account_identity_guard")) {
                assertTrue(row.next()); assertEquals(1, row.getInt(1)); assertFalse(row.next());
            }
        }
        assertEquals(0, Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").target("52").load().migrate().migrationsExecuted);
    }
}
