package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.*;
import io.testhub.platform.account.*;
import io.testhub.platform.config.*;
import io.testhub.platform.parameter.ProjectEnvironmentService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class SystemSettingsFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"administration-fixture");
        else {
            r.add("testhub.desktop.data-directory",directory::toString);
            r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"administration-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired AccessRepository memberships;
    @Autowired AccountManagementService accounts;
    @Autowired UserAccountRepository users;
    @Autowired SystemConfigService configs;
    @Autowired ProjectEnvironmentService environments;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor,String organization,String project) { }
    Fixture fixture(boolean admin) {
        String name="administration-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","系统验收","Settings-fixture-2026!"));
        var context=access.context(actor);String organization=context.organizations().getFirst().id(),project=context.projects().get(organization).getFirst().id();
        if(admin)memberships.insertMembership(com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),actor.userId(),organization,null,"SYSTEM_ADMIN");
        return new Fixture(actor,organization,project);
    }
    @Test void capabilityIsAuthenticatedAndDoesNotGrantRegisteredUsersGlobalManagement() throws Exception {
        var ordinary=fixture(false);var admin=fixture(true);
        mvc.perform(get("/api/v1/administration/capabilities")).andExpect(status().isUnauthorized());
        for(var f:List.of(ordinary,admin)) {
            var response=mvc.perform(get("/api/v1/administration/capabilities").with(user(f.actor()))).andExpect(status().isOk()).andReturn();
            assertEquals(f==admin,json.readTree(response.getResponse().getContentAsByteArray()).path("data").path("systemAdministration").asBoolean());
        }
        for(String route:List.of("/api/v1/accounts","/api/v1/system-config"))mvc.perform(get(route).with(user(ordinary.actor()))).andExpect(status().isForbidden());
        assertThrows(AccessDeniedException.class,()->accounts.create(ordinary.actor(),"blocked","","","",true));
        assertThrows(AccessDeniedException.class,()->configs.upsert(ordinary.actor(),"blocked.config","value"));
        var token=new ApiTokenPrincipal(users.findByAccountOrEmail(admin.actor().getUsername()).orElseThrow(),admin.project(),List.of("api:read"));
        assertFalse(access.canManageSystem(token));assertThrows(AccessDeniedException.class,()->configs.list(token));assertThrows(AccessDeniedException.class,()->accounts.options(token));
    }
    @Test void registeredWorkspaceCanManageOwnEnvironmentsWithoutGlobalPermissions() throws Exception {
        var a=fixture(false);var b=fixture(false);String route="/api/v1/projects/"+a.project()+"/environments";
        mvc.perform(get(route).with(user(a.actor()))).andExpect(status().isOk());mvc.perform(get(route).with(user(b.actor()))).andExpect(status().isForbidden());
        String input="{\"name\":\"预发布\",\"environmentCode\":\"stage\",\"baseUrl\":\"https://stage.example.test\",\"description\":\"\",\"enabled\":true,\"defaultEnvironment\":false}";
        mvc.perform(post(route).with(user(a.actor())).contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(a.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isOk());
        var env=environments.list(a.actor(),a.project()).stream().filter(e->e.environmentCode().equals("stage")).findFirst().orElseThrow();
        assertThrows(AccessDeniedException.class,()->environments.delete(b.actor(),a.project(),env.id()));
        environments.delete(a.actor(),a.project(),env.id());assertEquals(1,environments.list(a.actor(),a.project()).size());
    }
    @Test void adminAccountLifecyclePreservesHashingSelfProtectionAndScopedOptions() {
        var admin=fixture(true);var stranger=fixture(false);String name="qa-"+UUID.randomUUID();
        var created=accounts.create(admin.actor(),name,"QA",name+"@example.test","",true);
        assertTrue(encoder.matches("admin",users.findByAccountOrEmail(name).orElseThrow().passwordHash()));
        assertEquals(1,accounts.list(admin.actor(),name,1,10).total());
        assertTrue(accounts.options(stranger.actor()).stream().noneMatch(a->a.id().equals(created.id())));
        accounts.update(admin.actor(),created.id(),name,"更新 QA",created.email(),"12345",false);
        assertFalse(users.findByAccountOrEmail(name).orElseThrow().enabled());
        accounts.updatePassword(admin.actor(),created.id(),"QA-new-password!");assertTrue(encoder.matches("QA-new-password!",users.findByAccountOrEmail(name).orElseThrow().passwordHash()));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->accounts.delete(admin.actor(),admin.actor().userId())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->accounts.update(admin.actor(),admin.actor().userId(),admin.actor().getUsername(),"QA","qa@example.test","",false)).getStatusCode().value());
        accounts.delete(admin.actor(),created.id());assertTrue(users.findByAccountOrEmail(name).isEmpty());assertEquals(0,accounts.list(admin.actor(),name,1,10).total());
    }
    @Test void accountMutationsRequireCsrfAndPasswordNeverAppearsInResponsesOrAudit() throws Exception {
        var f=fixture(true);String name="qa-"+UUID.randomUUID();var account=accounts.create(f.actor(),name,"QA","","",true);
        String route="/api/v1/accounts/"+account.id()+"/password",password="QA-password-fixture!",body="{\"password\":\""+password+"\"}";
        mvc.perform(put(route).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put(route).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        var response=mvc.perform(get("/api/v1/accounts").param("keyword",name).with(user(f.actor()))).andExpect(status().isOk()).andReturn();String text=response.getResponse().getContentAsString();
        assertFalse(text.contains(password));assertFalse(text.contains("passwordHash"));
        var details=jdbc.sql("SELECT detail FROM audit_log WHERE resource_id=:id").param("id",account.id()).query(String.class).list();assertFalse(details.isEmpty());assertTrue(details.stream().noneMatch(d->d.contains(password)));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->accounts.updatePassword(f.actor(),account.id(),"密".repeat(25))).getStatusCode().value());
    }
    @Test void configLifecycleRequiresCsrfAndAuditContainsKeysWithoutValues() throws Exception {
        var f=fixture(true);String key="qa."+UUID.randomUUID(),value="non-sensitive-fixture-"+UUID.randomUUID();String body=json.writeValueAsString(java.util.Map.of("key",key,"value",value));
        mvc.perform(post("/api/v1/system-config").with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/system-config").with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        assertEquals(value,configs.list(f.actor()).stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().value());configs.upsert(f.actor(),key,"");
        assertEquals("",configs.list(f.actor()).stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().value());
        var details=jdbc.sql("SELECT detail FROM audit_log WHERE resource_id=:key").param("key",key).query(String.class).list();assertFalse(details.isEmpty());assertTrue(details.stream().noneMatch(d->d.contains(value)));
        mvc.perform(delete("/api/v1/system-config/"+key).with(user(f.actor())).with(csrf())).andExpect(status().isNoContent());assertTrue(configs.list(f.actor()).stream().noneMatch(c->c.key().equals(key)));
    }
    @Test void secretSystemConfigCannotBeReadOverwrittenOrDeletedViaOrdinaryConfigApi() {
        var f=fixture(true);String key="qa.secret."+UUID.randomUUID(),value="private-fixture";
        jdbc.sql("INSERT INTO system_config(config_key,config_value,secret,updated_by) VALUES(:key,:value,TRUE,:user)").param("key",key).param("value",value).param("user",f.actor().userId()).update();
        assertTrue(configs.list(f.actor()).stream().noneMatch(c->c.key().equals(key)));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->configs.upsert(f.actor(),key,"replacement")).getStatusCode().value());configs.delete(f.actor(),key);
        assertEquals(value,jdbc.sql("SELECT config_value FROM system_config WHERE config_key=:key AND secret=TRUE").param("key",key).query(String.class).single());
    }
    @Test void networkPolicyRequiresStrictBooleanAndDoesNotGrantEnvironmentAdminGlobalRights() {
        var f=fixture(true);String key="api-debug.allow-configured-private-targets";
        configs.upsert(f.actor(),key," TRUE ");assertEquals("true",configs.list(f.actor()).stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->configs.upsert(f.actor(),key,"1")).getStatusCode().value());
        assertEquals("true",configs.list(f.actor()).stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().value());configs.upsert(f.actor(),key,"false");
        var ordinary=fixture(false);assertThrows(AccessDeniedException.class,()->configs.upsert(ordinary.actor(),key,"true"));
        assertEquals("false",configs.list(f.actor()).stream().filter(c->c.key().equals(key)).findFirst().orElseThrow().value());
    }
}
