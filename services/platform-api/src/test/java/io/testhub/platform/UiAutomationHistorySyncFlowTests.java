package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.uiautomation.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
class UiAutomationHistorySyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "history-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "history-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired UiAutomationHistorySyncService sync;
    @Autowired io.testhub.platform.execution.UnifiedExecutionService unified;
    @Autowired UiAutomationService executions;
    @Autowired UiAutomationRepository repository;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() {
        String account = "history-sync-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "历史同步测试", "History-fixture-2026!"));
        var context = access.context(actor); return new Fixture(actor, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    UiAutomationExecution seed(Fixture f,boolean terminal) {
        String org=access.context(f.actor()).organizations().getFirst().id(),profileId=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),scenarioId=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        repository.insertProfile(profileId,org,f.project(),new UiAutomationRepository.ProfileInput("执行时浏览器"+profileId,"chromium","https://ui.example.test/private-fixture-url",1280,720,true),f.actor().userId());
        var steps=List.of(new UiAutomationStep("navigate","NAVIGATE","打开页面",null,null,null,"/private-page",null,30000),new UiAutomationStep("fill","FILL","填写内容","#private-selector",null,"fixture-withheld-input",null,null,30000));
        repository.insertScenario(scenarioId,org,f.project(),new UiAutomationRepository.ScenarioInput(profileId,"执行时场景"+scenarioId,"","STOP",Map.of("private-variable","fixture-withheld-variable"),steps),f.actor().userId());
        var scenario=repository.findScenario(f.project(),scenarioId).orElseThrow();var profile=repository.findProfile(f.project(),profileId).orElseThrow();
        String id=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),task=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();var now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insertExecution(id,task,org,f.project(),scenario,profile,null,null,null,null,0,"private-manifest-fixture",f.actor().userId(),now,now.plusSeconds(120));
        repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(task,id,"ui-fixture-runner","RUNNING","RUNNING","private error fixture",now),"RUNNING");
        var execution=repository.findExecution(f.project(),id).orElseThrow();
        repository.insertStepResult(execution,new UiAutomationRunnerProtocol.StepResult(task,id,"ui-fixture-runner","navigate",1,"NAVIGATE","打开页面","PASSED",12,null,now));
        if(terminal)repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(task,id,"ui-fixture-runner","SUCCEEDED","EXECUTION_SUCCEEDED","private error fixture",now.plusMillis(50)),"SUCCEEDED");
        return repository.findExecution(f.project(),id).orElseThrow();
    }
    UiAutomationHistorySyncService.Inventory inventory(Fixture f) { return sync.inventory(f.actor(), f.project(), f.actor().userId()); }
    UiAutomationHistorySyncService.Item item(Fixture f, UiAutomationExecution e) { return sync.read(f.actor(), f.project(), f.actor().userId(), f.project() + "." + e.id()); }
    UiAutomationHistorySyncService.Request request(Fixture f, UiAutomationHistorySyncService.Item... items) { return new UiAutomationHistorySyncService.Request(UUID.randomUUID().toString(), f.actor().userId(), inventory(f).revision(), List.of(items)); }
    int count(String table, Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:p").param("p", f.project()).query(Integer.class).single(); }
    UiAutomationHistorySyncService.Item changed(UiAutomationHistorySyncService.Item item, String name, String revision) {
        var tree = json.valueToTree(item.report()); ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("scenarioName", name);
        var p = json.convertValue(tree, UiAutomationHistorySyncService.Payload.class);
        return new UiAutomationHistorySyncService.Item(item.key(), revision, sync.hash(sync.encoded(p)), p);
    }
    @Test void copiesSafeTerminalStepsWithoutCurrentScenarioOrRunnerSideEffects() {
        var source=fixture();var target=fixture();var e=seed(source,true);var item=item(source,e);
        String payload=new String(sync.encoded(item),java.nio.charset.StandardCharsets.UTF_8);
        for(String hidden:List.of("private-fixture-url","private-selector","fixture-withheld-input","private-variable","private error","runnerId","requestedBy","manifestRef"))assertFalse(payload.contains(hidden));
        var req=request(target,item);var receipt=sync.apply(target.actor(),target.project(),req);
        assertEquals(item,sync.read(target.actor(),target.project(),target.actor().userId(),item.key()));assertEquals(receipt,sync.apply(target.actor(),target.project(),req));
        assertEquals(0,count("ui_automation_execution",target));assertEquals(0,count("ui_automation_scenario",target));assertEquals(0,count("ui_automation_execution_step_result",target));
        assertEquals(1,count("ui_history_archive",target));assertEquals("UI_HISTORY",unified.list(target.actor(),target.project()).getFirst().module());
        assertEquals(1,item.report().summary().totalSteps());assertEquals(e.scenarioVersion(),item.report().scenarioVersion());assertEquals(e.requestedAt(),item.report().requestedAt());
    }
    @Test void ignoresActiveTasksAndRetainsOriginalVersionAfterScenarioEdits() {
        var f=fixture();var active=seed(f,false);assertTrue(inventory(f).entries().isEmpty());var e=seed(f,true);var original=item(f,e);
        jdbc.sql("UPDATE ui_automation_scenario SET name='当前场景',version=version+1 WHERE id=:id").param("id",e.scenarioId()).update();
        assertEquals(original,item(f,e));assertFalse(inventory(f).entries().stream().anyMatch(v->v.key().endsWith(active.id())));
    }
    @Test void lateStepUpsertsChangeSourceRevisionEvenAtIdenticalTimestamp() {
        var source=fixture();var target=fixture();var e=seed(source,true);var first=item(source,e);sync.apply(target.actor(),target.project(),request(target,first));
        repository.insertStepResult(e,new UiAutomationRunnerProtocol.StepResult(e.taskId(),e.id(),"ui-fixture-runner","navigate",1,"NAVIGATE","打开页面","FAILED",13,"private-updated-error",first.report().steps().getFirst().occurredAt()));
        var updated=item(source,e);assertNotEquals(first.revision(),updated.revision());assertEquals(1,updated.report().summary().failedSteps());
        sync.apply(target.actor(),target.project(),request(target,updated));assertEquals(updated,sync.read(target.actor(),target.project(),target.actor().userId(),first.key()));assertEquals(1,count("ui_history_archive",target));
    }
    @Test void inventoryIncludesBeyondLegacyHundredAndRejectsThousandAndOne() {
        var f=fixture();var e=seed(f,true);
        for(int n=0;n<1000;n++){
            var id=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();var task=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
            repository.insertExecution(id,task,e.organizationId(),f.project(),repository.findScenario(f.project(),e.scenarioId()).orElseThrow(),repository.findProfile(f.project(),e.browserProfileId()).orElseThrow(),null,null,null,null,0,"fixture",f.actor().userId(),e.requestedAt(),e.deadlineAt());
            repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(task,id,"ui-fixture-runner","CANCELLED","CANCELLED","fixture",e.requestedAt().plusMillis(100)),"CANCELLED");
            if(n==100)assertEquals(102,inventory(f).entries().size());
        }
        assertThrows(ResponseStatusException.class,()->inventory(f));
    }
    @Test void invalidSourceDigestUnknownFieldsAndSummaryMismatchAreRejected() throws Exception {
        var f=fixture();var e=seed(f,true);var good=item(f,e);var target=fixture();
        var tree=json.valueToTree(good.report());((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("summary")).put("totalSteps",7);
        var p=json.convertValue(tree,UiAutomationHistorySyncService.Payload.class);var bad=new UiAutomationHistorySyncService.Item(good.key(),good.revision(),sync.hash(sync.encoded(p)),p);
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),request(target,bad)));
        var requestTree=json.valueToTree(request(target,good));((com.fasterxml.jackson.databind.node.ObjectNode)requestTree.path("items").get(0).path("report")).put("inputValue","unsupported");
        assertThrows(ResponseStatusException.class,()->sync.decode(json.writeValueAsBytes(requestTree)));
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),request(target,new UiAutomationHistorySyncService.Item(good.key(),good.revision(),"0".repeat(64),good.report()))));
    }
    @Test void accountCsrfProjectAndViewerPermissionsAreEnforced() throws Exception {
        var s=fixture();var t=fixture();var e=seed(s,true);var req=request(t,item(s,e));String route="/api/v1/projects/"+t.project()+"/ui-automation/history/sync";
        assertThrows(ResponseStatusException.class,()->sync.inventory(t.actor(),t.project(),s.actor().userId()));
        mvc.perform(post(route).with(user(t.actor())).contentType(MediaType.APPLICATION_JSON).content(sync.encoded(req))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(s.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(sync.encoded(req))).andExpect(status().isForbidden());
        sync.apply(t.actor(),t.project(),req);jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:u").param("u",t.actor().userId()).update();
        mvc.perform(get("/api/v1/projects/"+t.project()+"/ui-automation/history/"+req.items().getFirst().key()).with(user(t.actor()))).andExpect(status().isOk());
        mvc.perform(post(route).with(user(t.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(sync.encoded(req))).andExpect(status().isForbidden());
    }
    @Test void nativeRecordsStaleRevisionsAndReusedRequestKeysCannotBeOverwritten() {
        var s=fixture();var t=fixture();var e=seed(s,true);var item=item(s,e);var stale=request(t,item);sync.apply(t.actor(),t.project(),request(t,item));
        assertThrows(ResponseStatusException.class,()->sync.apply(t.actor(),t.project(),stale));assertThrows(ResponseStatusException.class,()->sync.apply(s.actor(),s.project(),request(s,item)));
        var req=request(t,item);sync.apply(t.actor(),t.project(),req);var changed=changed(item,"更改副本","f".repeat(64));
        assertThrows(ResponseStatusException.class,()->sync.apply(t.actor(),t.project(),new UiAutomationHistorySyncService.Request(req.requestKey(),req.expectedUserId(),req.expectedRevision(),List.of(changed))));
    }
    @Test void downloadHasVerifiedDigestAndOriginalSourceAndNoRawFields() throws Exception {
        var s=fixture();var e=seed(s,true);var item=item(s,e);
        var response=mvc.perform(get("/api/v1/projects/"+s.project()+"/ui-automation/history/"+item.key()+"/export").with(user(s.actor()))).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(sync.hash(response.getContentAsByteArray()),response.getHeader("X-TestHub-Report-SHA256"));assertEquals("private, no-store",response.getHeader("Cache-Control"));
    }
}
