package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.*;
import io.testhub.platform.parameter.ProjectSettingsSyncService.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class ProjectSettingsSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"settings-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa"); registry.add("spring.datasource.password",()->"settings-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ProjectSettingsSyncService sync;
    @Autowired ProjectEnvironmentService environments;
    @Autowired ProjectParameterService parameters;
    @Autowired EnvironmentSecretService secrets;
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate batchJdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="cfg-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","同步测试","Settings-fixture-2026!"));
        var context=access.context(actor);return new Fixture(actor,context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    Item env(String code,String name,String url) { return new Item("ENVIRONMENT",code,null,name,url,"同步说明",null); }
    Item param(String code,String key,String value) { return new Item("PARAMETER",code,key,null,null,null,value); }
    Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(),f.project(),f.actor().userId()); }
    Request request(Fixture f,Item... items) { return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),
            java.util.Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList()); }
    void apply(Fixture f,Item... items) { sync.apply(f.actor(),f.project(),request(f,items)); }
    @Test void preservesExistingEnvironmentIdsReferencesPreferencesAndDerivedBaseUrl() {
        var f=fixture(); var before=environments.list(f.actor(),f.project()); var target=before.getFirst();
        apply(f,env(target.environmentCode(),"同步新名称","https://api.example.test/v1"),param(target.environmentCode(),"MODE","离线"));
        var after=environments.list(f.actor(),f.project());assertEquals(before.size(),after.size());var current=after.stream().filter(e->e.id().equals(target.id())).findFirst().orElseThrow();
        assertEquals(target.enabled(),current.enabled());assertEquals(target.defaultEnvironment(),current.defaultEnvironment());assertEquals("同步新名称",current.name());
        assertEquals("https://api.example.test/v1",parameters.list(f.actor(),f.project(),target.environmentCode()).parameters().stream().filter(p->p.key().equals("BASE_URL")).findFirst().orElseThrow().value());
        assertEquals("PROJECT_SETTINGS_SYNC",snapshot(f).scope());assertEquals(0,snapshot(f).excludedSecrets());
    }
    @Test void appliesNewEnvironmentBeforeDependentParametersRegardlessOfOperationOrder() {
        var f=fixture();var initial=environments.list(f.actor(),f.project());
        apply(f,param("offline","MODE","测试值"),env("offline","离线环境","http://localhost:8123"));
        var all=environments.list(f.actor(),f.project());assertEquals(initial.size()+1,all.size());assertEquals(1,all.stream().filter(ProjectEnvironment::defaultEnvironment).count());
        assertEquals(initial.stream().filter(ProjectEnvironment::defaultEnvironment).findFirst().orElseThrow().id(),all.stream().filter(ProjectEnvironment::defaultEnvironment).findFirst().orElseThrow().id());
        assertEquals("测试值",parameters.list(f.actor(),f.project(),"offline").parameters().stream().filter(p->p.key().equals("MODE")).findFirst().orElseThrow().value());
    }
    @Test void secretValuesNeverAppearAndSecretCollisionsRollBackEntireBatch() throws Exception {
        var f=fixture();secrets.upsert(f.actor(),f.project(),"test","TOKEN","secret-fixture-must-not-export");
        var before=snapshot(f);assertEquals(1,before.excludedSecrets());assertTrue(before.blockedKeys().contains("P:test:token"));
        assertFalse(json.writeValueAsString(before).contains("secret-fixture-must-not-export"));
        var r=request(f,env("newenv","必须回滚",""),param("test","token","不得替代密钥"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r)).getStatusCode().value());assertEquals(before,snapshot(f));
        secrets.upsert(f.actor(),f.project(),"test","BASE_URL","secret-base-fixture");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,env("test","必须拒绝","https://example.test"))).getStatusCode().value());
        assertEquals(2,secrets.list(f.actor(),f.project(),null).secrets().size());
    }
    @Test void concurrentRetryReturnsSameReceiptAndChangedPayloadCannotReuseRequestKey() throws Exception {
        var f=fixture();var input=request(f,env("syncenv","同步新增",""),param("syncenv","MODE","value"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->sync.apply(f.actor(),f.project(),input));var b=pool.submit(()->sync.apply(f.actor(),f.project(),input));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        var first=sync.apply(f.actor(),f.project(),input);parameters.upsert(f.actor(),f.project(),"syncenv","MODE","later");
        assertEquals(first,sync.apply(f.actor(),f.project(),input));assertEquals("later",snapshot(f).items().stream().filter(i->"MODE".equals(i.key())).findFirst().orElseThrow().value());
        var changed=new Request(input.requestKey(),input.expectedUserId(),input.expectedRevision(),List.of(new Operation(input.operations().getFirst().operationId(),env("syncenv","修改内容",""))));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),changed)).getStatusCode().value());
    }
    @Test void concurrentOrdinaryEditAndSecretConversionInvalidateWholeBatchWithoutLostWrites() {
        for(boolean convert:List.of(false,true)) {
            var f=fixture();parameters.upsert(f.actor(),f.project(),"test","MODE","base");var input=request(f,env("newenv","不应出现",""),param("test","MODE","overwrite"));
            if(convert) secrets.upsert(f.actor(),f.project(),"test","MODE","new-secret-fixture"); else parameters.upsert(f.actor(),f.project(),"test","MODE","concurrent");
            var current=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),input)).getStatusCode().value());assertEquals(current,snapshot(f));
        }
    }
    @Test void deleteRecreateAndDefaultChangesAlsoInvalidateEarlierRevision() {
        var f=fixture();parameters.upsert(f.actor(),f.project(),"test","MODE","same");var input=request(f,param("test","MODE","stale"));
        String id=parameters.list(f.actor(),f.project(),"test").parameters().stream().filter(p->p.key().equals("MODE")).findFirst().orElseThrow().id();
        parameters.delete(f.actor(),f.project(),id);parameters.upsert(f.actor(),f.project(),"test","MODE","same");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),input)).getStatusCode().value());
        var other=environments.create(f.actor(),f.project(),new ProjectEnvironmentService.CreateInput("other","other","","",true,false));
        var pending=request(f,param("test","MODE","stale"));environments.makeDefault(f.actor(),f.project(),other.id());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),pending)).getStatusCode().value());
    }
    @Test void strictBodyInvalidDefinitionsAndOrphanParameterNeverWrite() throws Exception {
        var f=fixture();String body=json.writeValueAsString(request(f,param("test","MODE","x")));
        for(String invalid:List.of(body+" {}",body.replace("\"value\":\"x\"","\"value\":\"x\",\"secret\":true")))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->sync.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatusCode().value());
        assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[ProjectSettingsSyncService.MAX_BYTES+1])).getStatusCode().value());
        for(Item invalid:List.of(param("test","BASE_URL","x"),env("test","bad","https://user:pass@example.test"),param("absent","MODE","x"))) {
            var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,invalid));assertEquals(before,snapshot(f));
        }
        assertThrows(ResponseStatusException.class,()->apply(f,param("test","MODE","one"),param("test","mode","two")));
        parameters.upsert(f.actor(),f.project(),"test","MODE","upper");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,param("test","mode","lower"))).getStatusCode().value());
    }
    @Test void auditFailureRollsBackDefinitionsParametersAndReceipt() {
        var f=fixture();var before=snapshot(f);var input=request(f,env("audit","回滚","https://example.test"),param("audit","MODE","rollback"));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("PROJECT_SETTINGS_SYNCED"),anyString(),anyString(),anyMap());
        try { assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),input));assertEquals(before,snapshot(f));
            assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM project_settings_sync_receipt WHERE project_id=:id").param("id",f.project()).query(Integer.class).single()); }
        finally { reset(audit); }
    }
    @Test void scopeWriterRoleAccountAndCsrfAreRequiredForSnapshotsAndWrites() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");
        String route="/api/v1/projects/"+f.project()+"/project-settings/sync";var body=json.writeValueAsBytes(request(f,param("test","MODE","x")));
        mvc.perform(get(route).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/"+other.project()+"/project-settings/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId())).getStatusCode().value());
    }
    @Test void maximumOrdinaryAndSecretCountsAllowDerivedBaseUrlsAndFurtherCreatesRollback() {
        var f=fixture();var initial=environments.list(f.actor(),f.project());
        for(var e:initial) environments.update(f.actor(),f.project(),e.id(),new ProjectEnvironmentService.UpdateInput(e.name(),"https://example.test",e.description(),e.enabled(),e.version()));
        for(int n=initial.size();n<100;n++) environments.create(f.actor(),f.project(),new ProjectEnvironmentService.CreateInput("环境"+n,"e"+n,"https://example.test","",true,false));
        var args=new java.util.ArrayList<Object[]>();String org=initial.getFirst().organizationId();
        for(int n=0;n<1000;n++) for(boolean secret:List.of(false,true)) args.add(new Object[]{com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),org,f.project(),"test",(secret?"S":"P")+n,"fixture",secret,f.actor().userId()});
        batchJdbc.batchUpdate("INSERT INTO project_parameter(id,organization_id,project_id,environment_code,parameter_key,parameter_value,secret,updated_by) VALUES(?,?,?,?,?,?,?,?)",args);
        var before=snapshot(f);assertEquals(1100,before.items().size());assertEquals(1000,before.excludedSecrets());
        for(Item extra:List.of(env("over","超限",""),param("test","OVER","超限"))) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->apply(f,extra)).getStatusCode().value());assertEquals(before,snapshot(f));
        }
    }
}
