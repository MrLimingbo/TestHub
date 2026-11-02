package io.testhub.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.*;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.io.TempDir;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
abstract class P1FlowTestSupport {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "p1-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "p1-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired UserAccountRepository users;
    @Autowired AccountManagementService accounts;
    @Autowired AccessService access;
    @Autowired AccessRepository memberships;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    String unique() { return "p1-" + UUID.randomUUID(); }
    String password() { return UUID.randomUUID() + "Aa1!"; }
    PlatformUserPrincipal actor() {
        String name = unique();
        return new PlatformUserPrincipal(registration.register(name, name + "@example.test", "P1隔离QA", password()));
    }
    PlatformUserPrincipal admin() {
        var actor = actor();
        memberships.insertMembership(UlidCreator.getUlid().toString(), actor.userId(),
                access.context(actor).organizations().getFirst().id(), null, "SYSTEM_ADMIN");
        return actor;
    }
    String project(PlatformUserPrincipal actor) {
        var context = access.context(actor);
        return context.projects().get(context.organizations().getFirst().id()).getFirst().id();
    }
    long auditCount() { return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single(); }
}
