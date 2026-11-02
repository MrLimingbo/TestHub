package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "TESTHUB_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = {"spring.flyway.target=108", "testhub.bootstrap.demo-enabled=false"})
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
class AuditResourceMysqlMigrationTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry, directory, UUID.randomUUID().toString());
    }
    @Autowired DataSource source;
    @Autowired JdbcClient jdbc;

    @Test void upgradesOwnedMysql108To109WithoutChangingOldAuditValues() {
        String id = com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO audit_log(id,action,resource_type,resource_id,outcome,request_id,detail) VALUES(:id,'QA_OLD','PROJECT_PARAMETER','test:EXISTING','SUCCESS','qa',JSON_OBJECT())")
                .param("id", id).update();
        assertEquals(1, Flyway.configure().dataSource(source).locations("classpath:db/migration").target("109").load().migrate().migrationsExecuted);
        assertEquals("test:EXISTING", jdbc.sql("SELECT resource_id FROM audit_log WHERE id=:id").param("id", id).query(String.class).single());
        String longest = "e".repeat(64) + ":" + "K".repeat(128);
        jdbc.sql("UPDATE audit_log SET resource_id=:resource WHERE id=:id").param("resource", longest).param("id", id).update();
        assertEquals(longest, jdbc.sql("SELECT resource_id FROM audit_log WHERE id=:id").param("id", id).query(String.class).single());
        assertEquals(0, Flyway.configure().dataSource(source).locations("classpath:db/migration").target("109").load().migrate().migrationsExecuted);
    }
}
