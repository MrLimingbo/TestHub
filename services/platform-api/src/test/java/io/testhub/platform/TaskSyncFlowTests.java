package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.execution.ExecutionTaskService;
import io.testhub.platform.execution.TaskSyncService;
import io.testhub.platform.execution.TaskSyncService.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class TaskSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"task-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa"); registry.add("spring.datasource.password",()->"task-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired TaskSyncService sync;
    @Autowired ExecutionTaskService tasks;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","同步测试","Sync-fixture-2026!"));
        var context=access.context(actor);return new Fixture(actor,context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    Operation create(String name,String status,int progress) { return new Operation(UUID.randomUUID().toString(),null,null,"API",name,status,progress); }
    Request request(Fixture f,Operation... ops) { return new Request(UUID.randomUUID().toString(),f.actor().userId(),List.of(ops)); }
    Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(),f.project(),f.actor().userId()); }
    @Test void preservesAllRegistrationStatesAndSameNamesWithoutInvokingRunners() {
        var f=fixture();
        for(var status:List.of("QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED")) sync.apply(f.actor(),f.project(),request(f,create("同名登记",status,status.equals("QUEUED")?0:status.equals("SUCCEEDED")?100:47)));
        var result=snapshot(f);assertEquals(5,result.tasks().size());assertEquals("TASK_REGISTRATION",result.scope());
        assertEquals(5,result.tasks().stream().map(Item::status).distinct().count());
        for(String table:List.of("api_automation_execution","performance_test_execution","ui_automation_execution","mobile_automation_execution"))
            assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:project").param("project",f.project()).query(Integer.class).single());
    }
    @Test void identicalConcurrentRetryReturnsTheSameIdsAndChangedPayloadIsRejected() throws Exception {
        var f=fixture();var request=request(f,create("离线新增","RUNNING",29));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->sync.apply(f.actor(),f.project(),request));var b=pool.submit(()->sync.apply(f.actor(),f.project(),request));
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,snapshot(f).tasks().size());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(request.requestKey(),request.expectedUserId(),List.of(create("另一内容","QUEUED",0))))).getStatusCode().value());
    }
    @Test void staleVersionsAndMissingIdsRollBackTheWholeBatchIncludingCreates() {
        var f=fixture();var t=tasks.create(f.actor(),f.project(),new ExecutionTaskService.TaskInput("API","原登记"));
        tasks.update(f.actor(),f.project(),t.id(),new ExecutionTaskService.TaskUpdate("网页新版本","RUNNING",36,t.version()));
        var stale=new Operation(UUID.randomUUID().toString(),t.id(),t.version(),"API","旧本地修改","FAILED",22);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request(f,create("不能落库","QUEUED",0),stale))).getStatusCode().value());
        assertEquals(1,snapshot(f).tasks().size());assertEquals("网页新版本",snapshot(f).tasks().getFirst().name());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM task_sync_receipt WHERE project_id=:p").param("p",f.project()).query(Integer.class).single());
    }
    @Test void actorChangesAndOtherProjectsCannotReadOrWriteBoundData() throws Exception {
        var owner=fixture();var outsider=fixture();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.snapshot(owner.actor(),owner.project(),outsider.actor().userId())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(owner.actor(),owner.project(),request(outsider,create("不能写入","QUEUED",0)))).getStatusCode().value());
        String url="/api/v1/projects/"+owner.project()+"/tasks/sync";
        mvc.perform(get(url).with(user(outsider.actor())).param("expectedUserId",outsider.actor().userId())).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(outsider.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(outsider,create("越权","QUEUED",0))))).andExpect(status().isForbidden());
        mvc.perform(get(url).param("expectedUserId",owner.actor().userId())).andExpect(status().isUnauthorized());
        assertTrue(snapshot(owner).tasks().isEmpty());
    }
    @Test void strictPayloadValidationCsrfAndByteLimitRejectWithoutMutations() throws Exception {
        var f=fixture();String url="/api/v1/projects/"+f.project()+"/tasks/sync";
        mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"extra\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(TaskSyncService.MAX_BYTES+1))).andExpect(status().isPayloadTooLarge());
        for(var op:List.of(create("bad","SUCCEEDED",20),create("bad","QUEUED",1),create("  trim  ","QUEUED",0)))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request(f,op))).getStatusCode().value());
        assertTrue(snapshot(f).tasks().isEmpty());
    }
    @Test void updatedTaskUsesExactExpectedVersionAndBoundProjectOwnsReceipt() {
        var f=fixture();var first=sync.apply(f.actor(),f.project(),request(f,create("来源","QUEUED",0))).results().getFirst().task();
        var op=new Operation(UUID.randomUUID().toString(),first.id(),first.version(),first.taskType(),"本地改名","FAILED",62);
        var updated=sync.apply(f.actor(),f.project(),request(f,op)).results().getFirst().task();
        assertEquals(first.version()+1,updated.version());assertEquals("本地改名",updated.name());assertEquals(62,updated.progress());
        assertEquals(f.project(),jdbc.sql("SELECT project_id FROM task_sync_receipt WHERE requested_by=:user LIMIT 1").param("user",f.actor().userId()).query(String.class).single());
    }
    @Test void viewerCannotEnableSyncAndCrossProjectTaskIdsAreRejected() throws Exception {
        var f=fixture();var other=fixture();var t=tasks.create(other.actor(),other.project(),new ExecutionTaskService.TaskInput("API","其他项目"));
        var op=new Operation(UUID.randomUUID().toString(),t.id(),t.version(),t.taskType(),"越项目更新",t.status(),t.progress());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request(f,op))).getStatusCode().value());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:id").param("id",f.actor().userId()).update();
        mvc.perform(get("/api/v1/projects/"+f.project()+"/tasks/sync").with(user(f.actor())).param("expectedUserId",f.actor().userId())).andExpect(status().isForbidden());
        assertEquals("其他项目",snapshot(other).tasks().getFirst().name());
    }
}
