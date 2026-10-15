package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.execution.ExecutionTaskService;
import io.testhub.platform.execution.UnifiedExecutionService;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class UnifiedExecutionFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("testhub.desktop.allowed-origins",()->"https://ui.example.test");
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"unified-fixture");
        else {
            r.add("testhub.desktop.data-directory",directory::toString);
            r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            r.add("spring.datasource.username",()->"sa"); r.add("spring.datasource.password",()->"unified-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired UnifiedExecutionService executions;
    @Autowired ExecutionTaskService tasks;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired io.testhub.platform.config.SystemConfigRepository configs;
    record Fixture(PlatformUserPrincipal actor,String org,String project) { }
    Fixture fixture() {
        String account="unified-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","统一执行验收","Unified-fixture-2026!"));
        var context=access.context(actor);String org=context.organizations().getFirst().id();
        return new Fixture(actor,org,context.projects().get(org).getFirst().id());
    }
    String createAsset(Fixture f,String route,Object body)throws Exception {
        String text=mvc.perform(post("/api/v1/projects/"+f.project()+route).with(user(f.actor())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(text).path("data").path("id").asText();
    }
    void insert(String table,Map<String,Object> values) {
        var names=values.keySet();
        jdbc.sql("INSERT INTO "+table+" ("+String.join(",",names)+") VALUES ("+String.join(",",names.stream().map(n->":"+n).toList())+")").params(values).update();
    }
    Map<String,String> assets(Fixture f)throws Exception {
        if (FunctionalDatabaseProfile.mysql()) configs.upsert("ui-automation.allowed-origins","https://ui.example.test",f.actor().userId());
        var ids=new HashMap<String,String>();
        ids.put("API",createAsset(f,"/api-automation/scenarios",Map.of("name","API QA","environmentCode","test","failureStrategy","STOP","variables",Map.of(),"steps",List.of(Map.of("id","wait","type","WAIT","name","等待","config",Map.of("delayMillis",1))))));
        String profile=createAsset(f,"/ui-automation/browser-profiles",Map.of("name","UI QA","browser","chromium","baseUrl","https://ui.example.test","viewportWidth",800,"viewportHeight",600,"enabled",true));
        ids.put("profile",profile);
        ids.put("UI",createAsset(f,"/ui-automation/scenarios",Map.of("browserProfileId",profile,"name","UI QA","failureStrategy","STOP","variables",Map.of(),"steps",List.of(Map.of("id","wait","type","WAIT","name","等待","timeoutMillis",1)))));
        String device=createAsset(f,"/mobile-automation/devices",Map.of("name","Device QA","platform","ANDROID","udid","unified-qa","automationName","UiAutomator2","capabilities",Map.of(),"enabled",true));
        String app=createAsset(f,"/mobile-automation/applications",Map.of("name","App QA","platform","ANDROID","appIdentifier","io.testhub.qa","versionName","1","enabled",true));
        ids.put("device",device);ids.put("app",app);
        ids.put("MOBILE",createAsset(f,"/mobile-automation/scenarios",Map.of("deviceId",device,"applicationId",app,"name","Mobile QA","failureStrategy","STOP","variables",Map.of(),"steps",List.of(Map.of("id","wait","type","WAIT","name","等待","timeoutMillis",1)))));
        String plan=UlidCreator.getUlid().toString();
        insert("functional_test_plan",Map.of("id",plan,"organization_id",f.org(),"project_id",f.project(),"plan_code","QA_PLAN","name","Functional QA","status","READY","description","QA","created_by",f.actor().userId(),"updated_by",f.actor().userId()));
        ids.put("FUNCTIONAL",plan);ids.put("PERFORMANCE",UlidCreator.getUlid().toString());
        return ids;
    }
    String row(Fixture f,Map<String,String> assets,String module,String state,String id,Instant time) {
        var v=new LinkedHashMap<String,Object>();
        v.put("id",id);v.put("organization_id",f.org());v.put("project_id",f.project());v.put("status",state);
        v.put("created_at",Timestamp.from(time));v.put("updated_at",Timestamp.from(time));
        if(module.equals("FUNCTIONAL")) {
            v.put("plan_id",assets.get(module));v.put("name","Functional snapshot");v.put("created_by",f.actor().userId());
            insert("functional_test_run",v);return id;
        }
        v.put("task_id",UlidCreator.getUlid().toString());v.put("requested_by",f.actor().userId());
        v.put("requested_at",Timestamp.from(time));v.put("deadline_at",Timestamp.from(time.plusSeconds(3600)));
        v.put("manifest_ref","qa-private-manifest");v.put("status_message","qa-secret-must-not-be-projected");
        if(module.equals("PERFORMANCE")){v.put("plan_id",assets.get(module));v.put("plan_name","Performance snapshot");v.put("plan_version",1);}
        else {v.put("scenario_id",assets.get(module));v.put("scenario_name",module+" snapshot");v.put("scenario_version",1);}
        if(module.equals("UI")){v.put("browser_profile_id",assets.get("profile"));v.put("browser_profile_name","UI QA");v.put("browser","chromium");}
        if(module.equals("MOBILE")){v.put("device_id",assets.get("device"));v.put("device_name","QA device");v.put("platform","ANDROID");v.put("application_id",assets.get("app"));v.put("application_name","QA app");}
        insert(table(module),v);return id;
    }
    String table(String module){return Map.of("FUNCTIONAL","functional_test_run","API","api_automation_execution","PERFORMANCE","performance_test_execution","UI","ui_automation_execution","MOBILE","mobile_automation_execution").get(module);}
    Instant timestamp(){return Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}

    @Test void combinesFiveModulesWithStableOrderingAndNoManualOrSensitiveFields()throws Exception {
        var f=fixture();var a=assets(f);String common=UlidCreator.getUlid().toString();var time=timestamp();
        for(String module:List.of("FUNCTIONAL","API","PERFORMANCE","UI","MOBILE"))row(f,a,module,module.equals("FUNCTIONAL")?"COMPLETED":"SUCCEEDED",common,time);
        tasks.create(f.actor(),f.project(),new ExecutionTaskService.TaskInput("API","手工登记不属于真实执行"));
        var result=executions.list(f.actor(),f.project());assertEquals(5,result.size());
        assertEquals(List.of("API","FUNCTIONAL","MOBILE","PERFORMANCE","UI"),result.stream().map(r->r.module()).toList());
        assertEquals(1,result.stream().map(r->r.id()).distinct().count());assertEquals(5,result.stream().map(r->r.module()+":"+r.id()).distinct().count());
        String projection=json.writeValueAsString(result);for(String hidden:List.of("qa-secret","qa-private","manifestRef","statusMessage","requestedBy","progress","手工登记"))assertFalse(projection.contains(hidden));
        assertEquals(result,executions.list(f.actor(),f.project()));
    }
    @Test void preservesNativeAcceptedCancellingAndTerminalStatesWithoutInventedProgress()throws Exception {
        var f=fixture();var a=assets(f);var time=timestamp();
        for(String state:List.of("QUEUED","ACCEPTED","RUNNING","CANCELLING","SUCCEEDED","FAILED","CANCELLED","TIMED_OUT","REJECTED"))row(f,a,"API",state,UlidCreator.getUlid().toString(),time);
        assertEquals(Set.of("QUEUED","ACCEPTED","RUNNING","CANCELLING","SUCCEEDED","FAILED","CANCELLED","TIMED_OUT","REJECTED"),new HashSet<>(executions.list(f.actor(),f.project()).stream().map(r->r.status()).toList()));
        assertTrue(executions.list(f.actor(),f.project()).stream().allMatch(r->r.totalCount()==null&&r.finishedAt()==null));
    }
    @Test void completedFunctionalBatchRetainsFailuresAndBlockedCounts()throws Exception {
        var f=fixture();var a=assets(f);String run=row(f,a,"FUNCTIONAL","COMPLETED",UlidCreator.getUlid().toString(),timestamp());
        String catalog=createAsset(f,"/functional/catalogs",Map.of("name","QA Cases","scope","CASES","sortOrder",0));
        for(String outcome:List.of("PASSED","FAILED","BLOCKED")) {
            String testCase=createAsset(f,"/functional/cases",Map.of("catalogId",catalog,"caseCode","QA_"+outcome,"title",outcome,"priority","P1","status","READY","caseType","FUNCTIONAL","precondition","QA","steps","核对","expectedResult","OK"));
            insert("functional_test_run_case",Map.of("id",UlidCreator.getUlid().toString(),"run_id",run,"case_id",testCase,"status",outcome,"note","QA"));
        }
        var record=executions.list(f.actor(),f.project()).getFirst();assertEquals("COMPLETED",record.status());assertEquals(3,record.totalCount());assertEquals(1,record.passedCount());assertEquals(1,record.failedCount());assertEquals(1,record.blockedCount());
    }
    @Test void permissionsReadOnlyScopeAndNoQueueOrAuditSideEffects()throws Exception {
        var f=fixture();var other=fixture();var a=assets(f);row(f,a,"API","FAILED",UlidCreator.getUlid().toString(),timestamp());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->executions.list(other.actor(),f.project()));
        assertTrue(executions.list(other.actor(),other.project()).isEmpty());
        mvc.perform(get("/api/v1/projects/{id}/tasks/executions",f.project()).with(user(other.actor()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/{id}/tasks/executions",f.project())).andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:id").param("id",f.actor().userId()).update();
        int before=jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:id").param("id",f.project()).query(Integer.class).single();
        mvc.perform(get("/api/v1/projects/{id}/tasks/executions",f.project()).with(user(f.actor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects/{id}/tasks/executions",f.project()).with(user(f.actor())).with(csrf())).andExpect(status().isMethodNotAllowed());
        assertEquals(before,jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:id").param("id",f.project()).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM api_runner_outbox WHERE execution_id IN (SELECT id FROM api_automation_execution WHERE project_id=:id)").param("id",f.project()).query(Integer.class).single());
    }
    @Test void limitsEachModuleIndependentlyWithDeterministicTies()throws Exception {
        var f=fixture();var a=assets(f);var time=timestamp();var ids=new ArrayList<String>();
        for(int n=0;n<101;n++){String id=UlidCreator.getUlid().toString();ids.add(id);for(String module:List.of("FUNCTIONAL","API"))row(f,a,module,module.equals("FUNCTIONAL")?"NOT_STARTED":"SUCCEEDED",id,time);}
        var result=executions.list(f.actor(),f.project());assertEquals(200,result.size());
        assertTrue(result.stream().noneMatch(r->r.id().equals(ids.stream().min(String::compareTo).orElseThrow())));
        assertEquals(100,result.stream().filter(r->r.module().equals("API")).count());assertEquals(100,result.stream().filter(r->r.module().equals("FUNCTIONAL")).count());
        assertEquals(result,executions.list(f.actor(),f.project()));
    }
    @Test void reflectsExecutionUpdatesAndRetainsSnapshotNamesWhenSourceAssetIsRenamed()throws Exception {
        var f=fixture();var a=assets(f);String id=row(f,a,"API","RUNNING",UlidCreator.getUlid().toString(),timestamp());
        jdbc.sql("UPDATE api_automation_scenario SET name='Renamed current asset' WHERE id=:id").param("id",a.get("API")).update();
        var first=executions.list(f.actor(),f.project()).getFirst();assertEquals("API snapshot",first.name());assertEquals("RUNNING",first.status());
        var finish=Timestamp.from(timestamp());jdbc.sql("UPDATE api_automation_execution SET status='CANCELLED',finished_at=:finish WHERE id=:id").param("finish",finish).param("id",id).update();
        var last=executions.list(f.actor(),f.project()).getFirst();assertEquals("CANCELLED",last.status());assertEquals(finish.toInstant(),last.finishedAt());assertEquals(first.name(),last.name());
    }
}
