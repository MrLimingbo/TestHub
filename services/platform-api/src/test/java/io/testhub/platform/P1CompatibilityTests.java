package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.testhub.platform.account.UserAccount;
import java.io.*;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import javax.tools.ToolProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class P1CompatibilityTests {
    @TempDir Path directory;

    @Test void h2UpgradePreservesLegacyCiphertextAndDefaultsSecurityVersion() throws Exception {
        verifyMigration("jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", UUID.randomUUID().toString(), "db/desktop", "53", "55");
    }

    @Test @EnabledIfEnvironmentVariable(named = "TESTHUB_TEST_MYSQL_URL", matches = ".+")
    void mysqlUpgradePreservesLegacyCiphertextAndDefaultsSecurityVersion() throws Exception {
        String base = System.getenv("TESTHUB_TEST_MYSQL_URL"), password = System.getenv("TESTHUB_TEST_MYSQL_PASSWORD");
        URI uri = URI.create(base.substring(5));
        assertEquals("127.0.0.1", uri.getHost(), "Migration acceptance must use owned loopback infrastructure");
        String schema = "p1_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(base, "root", password); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema);
            try {
                String url = "jdbc:mysql://" + uri.getRawAuthority() + "/" + schema + "?" + uri.getRawQuery();
                verifyMigration(url, "root", password, "db/migration", "109", "111");
            } finally { statement.execute("DROP DATABASE " + schema); }
        }
    }

    private void verifyMigration(String url, String user, String password, String location, String before, String after) throws Exception {
        Flyway.configure().dataSource(url, user, password).locations("classpath:" + location).target(before).load().migrate();
        String ciphertext = "sealed-legacy-fixture-" + UUID.randomUUID();
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO user_account(id,account,email,display_name,password_hash) VALUES('u','p1-migration','p1@example.test','QA','fixture-hash')");
            sql.executeUpdate("INSERT INTO organization(id,name,code) VALUES('o','QA','P1')");
            sql.executeUpdate("INSERT INTO project(id,organization_id,name,code) VALUES('p','o','QA','P1')");
            try (var insert = connection.prepareStatement("INSERT INTO api_debug_history(id,organization_id,project_id,method,request_url,duration_ms,response_size,result,created_by,replay_draft_ciphertext,created_at) VALUES('h','o','p','GET','https://example.test',1,0,'DRAFT','u',?,'2020-01-01 00:00:00')")) {
                insert.setString(1, ciphertext); insert.executeUpdate();
            }
        }
        assertEquals(2, Flyway.configure().dataSource(url, user, password).locations("classpath:" + location).target(after).load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT security_version,password_hash FROM user_account WHERE id='u'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); assertEquals("fixture-hash", rows.getString(2));
            }
            try (var rows = sql.executeQuery("SELECT replay_draft_ciphertext,replay_expires_at,replay_retention_managed,created_at FROM api_debug_history WHERE id='h'")) {
                assertTrue(rows.next()); assertEquals(ciphertext, rows.getString(1)); assertNull(rows.getObject(2));
                assertFalse(rows.getBoolean(3)); assertEquals("2020-01-01", rows.getTimestamp(4).toLocalDateTime().toLocalDate().toString());
            }
            sql.executeUpdate("UPDATE api_debug_history SET replay_draft_ciphertext=NULL WHERE replay_retention_managed=TRUE AND replay_expires_at<=CURRENT_TIMESTAMP");
            try (var rows = sql.executeQuery("SELECT replay_draft_ciphertext FROM api_debug_history WHERE id='h'")) { assertTrue(rows.next()); assertEquals(ciphertext, rows.getString(1)); }
        }
        assertEquals(0, Flyway.configure().dataSource(url, user, password).locations("classpath:" + location).target(after).load().migrate().migrationsExecuted);
    }

    @Test void previouslySerializedAccountDefaultsItsMissingSecurityVersionToZero() throws Exception {
        Path source = directory.resolve("io/testhub/platform/account/UserAccount.java"); Files.createDirectories(source.getParent());
        Files.writeString(source, "package io.testhub.platform.account; public record UserAccount(String id,String account,String email,String displayName,String passwordHash,boolean enabled) implements java.io.Serializable {private static final long serialVersionUID=1L;}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()));
        byte[] serialized;
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, null); var bytes = new ByteArrayOutputStream(); var out = new ObjectOutputStream(bytes)) {
            var old = loader.loadClass("io.testhub.platform.account.UserAccount").getConstructors()[0].newInstance("u", "qa", "qa@example.test", "QA", "fixture-hash", true);
            out.writeObject(old); out.flush(); serialized = bytes.toByteArray();
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            UserAccount restored = (UserAccount) in.readObject();
            assertEquals(0, restored.securityVersion()); assertTrue(restored.enabled()); assertEquals("fixture-hash", restored.passwordHash());
        }
    }
}
