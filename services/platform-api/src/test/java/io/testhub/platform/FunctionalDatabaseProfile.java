package io.testhub.platform;

import java.nio.file.Path;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import org.springframework.test.context.ActiveProfilesResolver;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Opt-in isolated MySQL coverage; the default always uses a temporary desktop database. */
public class FunctionalDatabaseProfile implements ActiveProfilesResolver {
    static boolean mysql() { return System.getenv("TESTHUB_TEST_MYSQL_URL") != null; }
    @Override public String[] resolve(Class<?> testClass) { return new String[]{mysql() ? "mysql-qa" : "desktop"}; }
    static void configure(DynamicPropertyRegistry registry, Path directory, String fixturePassword) {
        registry.add("testhub.desktop.data-directory", directory::toString);
        if (!mysql()) registry.add("spring.datasource.password", () -> fixturePassword);
        else {
            if (System.getenv("TESTHUB_TEST_MYSQL_PASSWORD") == null) throw new IllegalStateException("Isolated MySQL test password required");
            String url = isolatedMysqlUrl(directory);
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> "root");
            registry.add("spring.datasource.password", () -> System.getenv("TESTHUB_TEST_MYSQL_PASSWORD"));
        }
    }

    private static String isolatedMysqlUrl(Path directory) {
        String base = System.getenv("TESTHUB_TEST_MYSQL_URL");
        URI uri = URI.create(base.substring("jdbc:".length()));
        if (!"mysql".equals(uri.getScheme()) || uri.getUserInfo() != null
                || !java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()))
            throw new IllegalStateException("MySQL acceptance requires an explicitly isolated loopback server");
        try {
            // A @TempDir belongs to one test class. Sharing its schema would invalidate
            // queue-isolation assertions when other classes leave accepted execution history.
            String schema = "testhub_qa_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(directory.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8))).substring(0, 20);
            try (var connection = DriverManager.getConnection(base, "root", System.getenv("TESTHUB_TEST_MYSQL_PASSWORD"));
                 var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + schema
                        + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            return "jdbc:mysql://" + uri.getRawAuthority() + "/" + schema
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create the owned per-class MySQL acceptance schema", exception);
        }
    }
}
