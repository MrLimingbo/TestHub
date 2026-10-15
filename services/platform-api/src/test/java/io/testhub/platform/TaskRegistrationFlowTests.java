package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.execution.ExecutionTaskService;
import io.testhub.platform.execution.TaskTransferService;
import io.testhub.platform.execution.TaskTransferService.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
class TaskRegistrationFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"task-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "task-transfer-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ExecutionTaskService tasks;
    @Autowired TaskTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor, String project, String org) { }
    Fixture fixture() {
        String account="task-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","任务测试","Transfer-fixture-2026!"));
        var context=access.context(actor);String org=context.organizations().getFirst().id();
        return new Fixture(actor,context.projects().get(org).getFirst().id(),org);
    }
    io.testhub.platform.execution.ExecutionTask create(Fixture f,String type,String name) {
        return tasks.create(f.actor(),f.project(),new ExecutionTaskService.TaskInput(type,name));
    }
    ImportRequest request(Fixture f,Snapshot source) { return new ImportRequest(UUID.randomUUID().toString(),f.actor().userId(),source); }
    int count(String table,String project) { return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:project").param("project",project).query(Integer.class).single(); }
    @Test void copiesAllTypesStatesAndProvenanceWithActiveRecordsCancelledWithoutExecution() {
        var source=fixture();var target=fixture();
        String[] types={"API","FUNCTIONAL","PERFORMANCE","UI","MOBILE","OTHER"};
        String[] states={"QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED","QUEUED"};
        for(int i=0;i<types.length;i++) {
            var t=create(source,types[i],"同名登记");
            tasks.update(source.actor(),source.project(),t.id(),new ExecutionTaskService.TaskUpdate(t.name(),states[i],i==1?45:i==3?68:20,1L));
        }
        var existing=create(target,"OTHER","同名登记");var snapshot=transfer.export(source.actor(),source.project());
        var receipt=transfer.transfer(target.actor(),target.project(),request(target,snapshot));
        assertEquals(6,receipt.tasks());assertEquals(3,receipt.cancelledActiveTasks());assertEquals(7,tasks.list(target.actor(),target.project()).size());
        for(var input:snapshot.tasks()) {
            var copied=tasks.list(target.actor(),target.project()).stream().filter(t->input.id().equals(t.sourceTaskId())).findFirst().orElseThrow();
            assertNotEquals(input.id(),copied.id());assertEquals(target.actor().userId(),copied.requestedBy());assertEquals(1,copied.version());
            assertEquals(input.status(),copied.sourceStatus());assertEquals(input.version(),copied.sourceVersion());
            assertEquals(input.createdAt(),copied.sourceCreatedAt());assertEquals(input.updatedAt(),copied.sourceUpdatedAt());
            assertEquals(input.taskType(),copied.taskType());assertEquals(input.progress(),copied.progress());
            assertEquals(List.of("QUEUED","RUNNING").contains(input.status())?"CANCELLED":input.status(),copied.status());
        }
        assertEquals(existing,tasks.list(target.actor(),target.project()).stream().filter(t->t.id().equals(existing.id())).findFirst().orElseThrow());
        assertEquals(snapshot,transfer.export(source.actor(),source.project()));
        for(String table:List.of("api_automation_execution","performance_test_execution","ui_automation_execution","mobile_automation_execution")) assertEquals(0,count(table,target.project()));
        for(String table:List.of("api_runner_outbox","performance_runner_outbox","ui_automation_runner_outbox","mobile_automation_runner_outbox"))
            assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM "+table).query(Integer.class).single());
    }
    @Test void concurrentIdenticalRetryReturnsOneReceiptAndChangedPayloadConflicts() throws Exception {
        var source=fixture();var target=fixture();create(source,"API","来源任务");var snapshot=transfer.export(source.actor(),source.project());var request=request(target,snapshot);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Receipt> call=()->transfer.transfer(target.actor(),target.project(),request);
            var a=pool.submit(call);var b=pool.submit(call);assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,tasks.list(target.actor(),target.project()).size());assertEquals(1,count("task_transfer_receipt",target.project()));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),
                new ImportRequest(request.requestKey(),target.actor().userId(),new Snapshot(1,snapshot.sourceProjectId(),List.of())))).getStatusCode().value());
    }
    @Test void strictInvalidPayloadAndActorChangeWriteNothing() throws Exception {
        var source=fixture();var target=fixture();create(source,"API","任务");var snapshot=transfer.export(source.actor(),source.project());var request=request(target,snapshot);
        byte[] valid=json.writeValueAsBytes(request);assertEquals(request,transfer.decode(valid));
        assertThrows(ResponseStatusException.class,()->transfer.decode((new String(valid,java.nio.charset.StandardCharsets.UTF_8)+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(ResponseStatusException.class,()->transfer.decode(json.writeValueAsString(request).replace("\"snapshot\":{","\"snapshot\":{\"runner\":{},").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var missingProgress=transfer.decode(json.writeValueAsString(request).replace("\"progress\":0","\"progress\":null").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),missingProgress));
        var t=snapshot.tasks().getFirst();var bad=new Task(UlidCreator.getUlid().toString(),null,"无效任务",null,0,1,t.createdAt(),t.updatedAt());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,new Snapshot(1,snapshot.sourceProjectId(),List.of(t,bad))))).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),new ImportRequest(request.requestKey(),source.actor().userId(),snapshot)));
        assertEquals(0,count("execution_task",target.project()));assertEquals(0,count("task_transfer_receipt",target.project()));
    }
    @Test void auditFailureRollsBackInsertedTasksAndReceipt() {
        var source=fixture();var target=fixture();create(source,"API","任务一");create(source,"OTHER","任务二");var snapshot=transfer.export(source.actor(),source.project());
        doThrow(new IllegalStateException("audit fixture unavailable")).when(audit).record(eq(target.actor()),eq(target.org()),eq(target.project()),eq("TASK_REGISTRATIONS_TRANSFERRED"),anyString(),anyString(),anyMap());
        assertThrows(IllegalStateException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot)));
        assertEquals(0,count("execution_task",target.project()));assertEquals(0,count("task_transfer_receipt",target.project()));
    }
    @Test void optimisticVersionAndLegacyUpdatesPreserveNormalizationAndImportedOrigin() {
        var source=fixture();var target=fixture();create(source,"API","原登记");transfer.transfer(target.actor(),target.project(),request(target,transfer.export(source.actor(),source.project())));
        var t=tasks.list(target.actor(),target.project()).getFirst();var updated=tasks.update(target.actor(),target.project(),t.id(),new ExecutionTaskService.TaskUpdate("改名","RUNNING",45,1L));
        assertEquals(2,updated.version());assertEquals(t.sourceStatus(),updated.sourceStatus());assertEquals(t.sourceCreatedAt(),updated.sourceCreatedAt());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->tasks.update(target.actor(),target.project(),t.id(),new ExecutionTaskService.TaskUpdate("迟到编辑","SUCCEEDED",100,1L))).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->tasks.cancel(target.actor(),target.project(),t.id(),1L)).getStatusCode().value());
        assertEquals(45,tasks.list(target.actor(),target.project()).getFirst().progress());
        var legacy=tasks.update(target.actor(),target.project(),t.id(),new ExecutionTaskService.TaskUpdate("兼容旧客户端","SUCCEEDED",9));assertEquals(100,legacy.progress());assertEquals(3,legacy.version());
        assertThrows(ResponseStatusException.class,()->tasks.cancel(target.actor(),target.project(),t.id()));
        var queued=tasks.update(target.actor(),target.project(),t.id(),new ExecutionTaskService.TaskUpdate(legacy.name(),"QUEUED",99));assertEquals(0,queued.progress());
        assertEquals("CANCELLED",tasks.cancel(target.actor(),target.project(),t.id()).status());
    }
    @Test void completionAndCancellationRaceCannotOverwriteTheWinner() throws Exception {
        var f=fixture();
        for(int i=0;i<3;i++) {
            var task=create(f,"API","并发任务");
            try(var pool=Executors.newFixedThreadPool(2)) {
                var gate=new java.util.concurrent.CountDownLatch(1);
                var a=pool.submit(()->{gate.await();try{return tasks.update(f.actor(),f.project(),task.id(),new ExecutionTaskService.TaskUpdate(task.name(),"SUCCEEDED",100,1L)).status();}catch(ResponseStatusException e){return "CONFLICT";}});
                var b=pool.submit(()->{gate.await();try{return tasks.cancel(f.actor(),f.project(),task.id(),1L).status();}catch(ResponseStatusException e){return "CONFLICT";}});
                gate.countDown();var outcomes=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter("CONFLICT"::equals).count());
            }
            var current=tasks.list(f.actor(),f.project()).stream().filter(t->t.id().equals(task.id())).findFirst().orElseThrow();assertEquals(2,current.version());
            assertTrue(List.of("SUCCEEDED","CANCELLED").contains(current.status()));assertEquals(current.status().equals("SUCCEEDED")?100:0,current.progress());
        }
    }
    @Test void httpScopePermissionsCsrfAndVersionValidationAreEnforced() throws Exception {
        var f=fixture();var outsider=fixture();var t=create(f,"API","任务");var snapshot=transfer.export(f.actor(),f.project());
        mvc.perform(get("/api/v1/projects/{id}/tasks/transfer",f.project()).with(user(outsider.actor()))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/projects/{id}/tasks/{task}",f.project(),t.id()).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"任务\",\"status\":\"RUNNING\",\"progress\":1,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/projects/{id}/tasks/{task}/cancel",f.project(),t.id()).with(user(f.actor()))).andExpect(status().isForbidden());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:id").param("id",f.actor().userId()).update();
        mvc.perform(get("/api/v1/projects/{id}/tasks",f.project()).with(user(f.actor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects/{id}/tasks/transfer",f.project()).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request(f,snapshot))))
                .andExpect(status().isForbidden());
    }
}
