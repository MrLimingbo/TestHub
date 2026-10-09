package io.testhub.platform;

import java.nio.file.Path;
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
            registry.add("spring.datasource.url", () -> System.getenv("TESTHUB_TEST_MYSQL_URL"));
            registry.add("spring.datasource.username", () -> "root");
            registry.add("spring.datasource.password", () -> System.getenv("TESTHUB_TEST_MYSQL_PASSWORD"));
        }
    }
}
