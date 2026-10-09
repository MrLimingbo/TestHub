package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.*;
import io.testhub.platform.parameter.ProjectSettingsTransferService.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
class ProjectSettingsTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"settings-fixture");
        else {
            r.add("testhub.desktop.data-directory",directory::toString);
            r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            r.add("spring.datasource.username",()->"sa"); r.add("spring.datasource.password",()->"settings-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ProjectEnvironmentService environments;
    @Autowired ProjectParameterService parameters;
    @Autowired EnvironmentSecretService secrets;
    @Autowired ProjectSettingsTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String name="settings-"+UUID.randomUUID(); var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","配置验收","Settings-fixture-2026!"));
        var context=access.context(actor);return new Fixture(actor,context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    ImportRequest request(Fixture f,Snapshot s) {return new ImportRequest(UUID.randomUUID().toString(),f.actor().userId(),s);}
    void param(Fixture f,String key,String value){parameters.upsert(f.actor(),f.project(),"test",key,value);}
    Snapshot populate(Fixture f) {
        environments.create(f.actor(),f.project(),new ProjectEnvironmentService.CreateInput("预发布","stage","https://stage.example.test","说明",true,false));
        environments.create(f.actor(),f.project(),new ProjectEnvironmentService.CreateInput("停用","disabled","","",false,false));
        param(f,"MODE","fixture");secrets.upsert(f.actor(),f.project(),"test","PASSWORD","not-exported-fixture");return transfer.export(f.actor(),f.project());
    }
    @Test void copiesDefinitionsDerivedBaseUrlAndOrdinaryParametersWithoutSecretsIdentitiesOrDefaultChanges() throws Exception {
        var from=fixture();var to=fixture();var snapshot=populate(from);var old=environments.list(to.actor(),to.project()).getFirst();
        String serialized=json.writeValueAsString(snapshot);assertFalse(serialized.contains("PASSWORD"));assertFalse(serialized.contains("not-exported-fixture"));assertFalse(serialized.contains(from.actor().userId()));
        var receipt=transfer.transfer(to.actor(),to.project(),request(to,snapshot));assertEquals(2,receipt.createdEnvironments());assertEquals(1,receipt.reusedEnvironments());assertEquals(2,receipt.createdParameters());assertEquals(1,receipt.excludedSecrets());
        var preserved=environments.list(to.actor(),to.project()).stream().filter(e->e.id().equals(old.id())).findFirst().orElseThrow();
        var oldFields=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(old);
        var newFields=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(preserved);
        oldFields.remove("variableCount");newFields.remove("variableCount");assertEquals(oldFields,newFields);assertEquals(1,preserved.variableCount());
        var imported=environments.list(to.actor(),to.project()).stream().filter(e->e.environmentCode().equals("stage")).findFirst().orElseThrow();
        assertEquals(to.actor().userId(),imported.createdBy());assertFalse(imported.defaultEnvironment());assertEquals(1,imported.version());assertTrue(snapshot.environments().stream().noneMatch(e->e.id().equals(imported.id())));
        assertTrue(secrets.list(to.actor(),to.project(),null).secrets().isEmpty());assertEquals("https://stage.example.test",parameters.list(to.actor(),to.project(),null).parameters().stream().filter(p->p.key().equals("BASE_URL")).findFirst().orElseThrow().value());
        assertEquals(snapshot,transfer.export(from.actor(),from.project()));assertEquals(1,access.members(to.actor(),to.project()).size());
    }
    @Test void conflictingEnvironmentOrdinaryValueAndSecretCollisionRollBackEntireImportWithoutOverwriting() {
        for(int variant=0;variant<3;variant++){
            var from=fixture();var to=fixture();var snapshot=populate(from);
            if(variant==0)environments.create(to.actor(),to.project(),new ProjectEnvironmentService.CreateInput("不同","stage","","",true,false));
            if(variant==1)param(to,"MODE","existing");
            if(variant==2)secrets.upsert(to.actor(),to.project(),"test","MODE","secret-existing-fixture");
            var before=transfer.export(to.actor(),to.project());int count=secrets.list(to.actor(),to.project(),null).secrets().size();
            assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(to.actor(),to.project(),request(to,snapshot))).getStatusCode().value());
            assertEquals(before,transfer.export(to.actor(),to.project()));assertEquals(count,secrets.list(to.actor(),to.project(),null).secrets().size());
            assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM project_settings_transfer_receipt WHERE project_id=:id").param("id",to.project()).query(Integer.class).single());
        }
    }
    @Test void concurrentRetryIsIdempotentAndIdenticalNewRequestsReuseWithoutWrites() throws Exception {
        var from=fixture();var to=fixture();var snapshot=populate(from);var input=request(to,snapshot);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->transfer.transfer(to.actor(),to.project(),input));var b=pool.submit(()->transfer.transfer(to.actor(),to.project(),input));
            assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        var before=transfer.export(to.actor(),to.project());var again=transfer.transfer(to.actor(),to.project(),request(to,snapshot));
        assertEquals(0,again.createdEnvironments());assertEquals(0,again.createdParameters());assertEquals(3,again.reusedEnvironments());assertEquals(2,again.reusedParameters());assertEquals(before,transfer.export(to.actor(),to.project()));
        param(from,"MODE","changed");var changed=transfer.export(from.actor(),from.project());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(to.actor(),to.project(),new ImportRequest(input.requestKey(),to.actor().userId(),changed))).getStatusCode().value());
    }
    @Test void strictPayloadAndAccountValidationAndAuditFailureLeaveNoWrites() throws Exception {
        var from=fixture();var to=fixture();var snapshot=populate(from);var input=request(to,snapshot);String encoded=json.writeValueAsString(input);
        for(String body:List.of(encoded+" {}",encoded.replace("\"value\":\"fixture\"","\"value\":\"fixture\",\"secret\":true"),encoded.replace("\"enabled\":true","\"enabled\":null"))){
            assertThrows(ResponseStatusException.class,()->transfer.transfer(to.actor(),to.project(),transfer.decode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        assertThrows(ResponseStatusException.class,()->transfer.transfer(to.actor(),to.project(),new ImportRequest(input.requestKey(),from.actor().userId(),snapshot)));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(to.actor()),anyString(),eq(to.project()),eq("PROJECT_SETTINGS_TRANSFERRED"),anyString(),anyString(),anyMap());
        var before=transfer.export(to.actor(),to.project());assertThrows(IllegalStateException.class,()->transfer.transfer(to.actor(),to.project(),input));assertEquals(before,transfer.export(to.actor(),to.project()));
    }
    @Test void concurrentDefaultChangesLeaveExactlyOneEnabledDefaultAndInvalidatePreviousVersion() throws Exception {
        var f=fixture();var old=environments.list(f.actor(),f.project()).getFirst();
        var a=environments.create(f.actor(),f.project(),new ProjectEnvironmentService.CreateInput("A","a","","",true,false));
        var b=environments.create(f.actor(),f.project(),new ProjectEnvironmentService.CreateInput("B","b","","",true,false));
        try(var pool=Executors.newFixedThreadPool(2)){
            var x=pool.submit(()->environments.makeDefault(f.actor(),f.project(),a.id()));var y=pool.submit(()->environments.makeDefault(f.actor(),f.project(),b.id()));x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);
        }
        var all=environments.list(f.actor(),f.project());assertEquals(1,all.stream().filter(ProjectEnvironment::defaultEnvironment).count());assertTrue(all.stream().filter(ProjectEnvironment::defaultEnvironment).allMatch(ProjectEnvironment::enabled));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->environments.update(f.actor(),f.project(),old.id(),new ProjectEnvironmentService.UpdateInput("旧编辑","","",true,old.version()))).getStatusCode().value());
    }
    @Test void projectReadDoesNotGrantTransferWritesAndHttpRequiresCsrf() throws Exception {
        var from=fixture();var to=fixture();var viewer=fixture();var snapshot=populate(from);String route="/api/v1/projects/"+to.project()+"/project-settings/transfer";
        access.upsertMember(to.actor(),to.project(),viewer.actor().getUsername(),"VIEWER");
        mvc.perform(get(route).with(user(viewer.actor()))).andExpect(status().isForbidden());
        mvc.perform(get(route).with(user(from.actor()))).andExpect(status().isForbidden());
        byte[] body=json.writeValueAsBytes(request(to,snapshot));
        mvc.perform(post(route).with(user(to.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(to.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
    @Test void accountProjectLogsAreScopedSummariesAndDoNotGrantGlobalAuditPermission() throws Exception {
        var from=fixture();var to=fixture();var viewer=fixture();var snapshot=populate(from);
        transfer.transfer(to.actor(),to.project(),request(to,snapshot));
        access.upsertMember(to.actor(),to.project(),viewer.actor().getUsername(),"VIEWER");
        String route="/api/v1/projects/"+to.project()+"/audit-logs";
        var response=mvc.perform(get(route).param("projectId",from.project()).with(user(viewer.actor())))
                .andExpect(status().isOk()).andReturn();
        var entries=json.readTree(response.getResponse().getContentAsByteArray()).path("data");assertFalse(entries.isEmpty());
        for(var e:entries){assertEquals(to.project(),e.path("projectId").asText());assertFalse(e.has("detail"));assertFalse(e.has("clientIp"));}
        assertTrue(java.util.stream.StreamSupport.stream(entries.spliterator(),false).anyMatch(e->e.path("action").asText().equals("PROJECT_SETTINGS_TRANSFERRED")));
        var filtered=mvc.perform(get(route).param("action","PROJECT_SETTINGS_TRANSFERRED").param("limit","1").with(user(to.actor())))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1,json.readTree(filtered.getResponse().getContentAsByteArray()).path("data").size());
        mvc.perform(get(route).with(user(from.actor()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-logs").param("projectId",to.project()).with(user(to.actor()))).andExpect(status().isForbidden());
    }

}
