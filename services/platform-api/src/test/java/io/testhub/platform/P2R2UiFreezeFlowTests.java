package io.testhub.platform;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.config.SystemConfigRepository;
import io.testhub.platform.uiautomation.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class P2R2UiFreezeFlowTests extends P1FlowTestSupport {
    @Autowired UiAutomationService ui;
    @Autowired UiAutomationRepository rows;
    @Autowired UiAutomationScheduleService schedules;
    @Autowired SystemConfigRepository configs;
    record Fixture(PlatformUserPrincipal actor,String project,UiAutomationBrowserProfile profile,UiAutomationPageObject object,UiAutomationScenario scenario) { }
    Fixture fixture(boolean reference) {
        var a=actor();String p=project(a);configs.upsert("ui-automation.allowed-origins","https://ui.example.test",a.userId());
        var profile=ui.createProfile(a,p,new UiAutomationService.ProfileRequest(unique(),"chromium","https://ui.example.test",1280,720,true));
        var object=ui.createPageObject(a,p,new UiAutomationService.PageObjectRequest(unique(),"#saved",""));
        var step=new UiAutomationStep("click","CLICK","点击",reference?null:"#direct",reference?object.id():null,null,null,null,1000);
        var scenario=ui.createScenario(a,p,new UiAutomationService.ScenarioRequest(profile.id(),unique(),"","STOP",Map.of(),List.of(step)));
        return new Fixture(a,p,profile,object,scenario);
    }
    void edit(Fixture f,String selector){ui.updatePageObject(f.actor,f.project,f.object.id(),new UiAutomationService.PageObjectRequest(f.object.name(),selector,""));}
    UiAutomationExecution queue(Fixture f){return ui.execute(f.actor,f.project,f.scenario.id(),30,null);}
    UiAutomationManifest manifest(Fixture f,UiAutomationExecution e){return rows.findManifest(f.project,e.id()).orElseThrow();}
    String selector(Fixture f,UiAutomationExecution e){return manifest(f,e).snapshot().scenario().steps().getFirst().selector();}
    long count(String table,Fixture f){return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",f.project).query(Long.class).single();}
    @Test @DisplayName("TC-P2R2-D18-01") void objectIsResolvedAtQueueTime() {var f=fixture(true);assertEquals(f.object.id(),f.scenario.steps().getFirst().pageObjectId());edit(f,"#before-queue");assertEquals("#before-queue",selector(f,queue(f)));}
    @Test @DisplayName("TC-P2R2-D18-02") void manifestAndChecksumDescribeTheActualQueuedSnapshot() {var f=fixture(true);var e=queue(f);var m=manifest(f,e);assertEquals(f.scenario.id(),m.scenarioId());assertEquals(f.scenario.version(),m.scenarioVersion());assertEquals(f.profile.name(),m.snapshot().browserProfile().name());assertEquals("#saved",m.snapshot().scenario().steps().getFirst().selector());assertTrue(m.checksum().matches("[a-f0-9]{64}"));assertEquals(1,count("ui_automation_execution",f));assertTrue(jdbc.sql("SELECT COUNT(*) FROM ui_automation_runner_outbox WHERE execution_id=:id").param("id",e.id()).query(Long.class).single()>0);}
    @Test @DisplayName("TC-P2R2-D18-03") void objectEditsNeverRewriteExistingManifest() {var f=fixture(true);var first=queue(f);var before=manifest(f,first);edit(f,"#after-queue");assertEquals(before,manifest(f,first));assertEquals("#after-queue",selector(f,queue(f)));assertEquals(before,manifest(f,first));}
    @Test @DisplayName("TC-P2R2-D18-04") void aDirectSelectorDoesNotFollowAnUnrelatedObject() {var f=fixture(false);edit(f,"#other-object");assertEquals("#direct",selector(f,queue(f)));}
    @Test @DisplayName("TC-P2R2-D18-06") void invalidScopeOrObjectAndDisabledProfileLeaveNoPartialQueue() {
        var f=fixture(true);var other=actor();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->ui.execute(other,f.project,f.scenario.id(),30,null));
        ui.updateProfile(f.actor,f.project,f.profile.id(),new UiAutomationService.ProfileRequest(f.profile.name(),"chromium",f.profile.baseUrl(),1280,720,false));assertEquals(409,assertThrows(ResponseStatusException.class,()->queue(f)).getStatusCode().value());
        ui.updateProfile(f.actor,f.project,f.profile.id(),new UiAutomationService.ProfileRequest(f.profile.name(),"chromium",f.profile.baseUrl(),1280,720,true));
        jdbc.sql("DELETE FROM ui_automation_page_object WHERE id=:id").param("id",f.object.id()).update();assertThrows(ResponseStatusException.class,()->queue(f));assertEquals(0,count("ui_automation_execution",f));assertEquals(0,count("ui_automation_execution_manifest",f));
    }
    @Test @DisplayName("TC-P2R2-D18-07") void newScheduledAndRetryExecutionsResolveCurrentObjects() {
        var f=fixture(true);var schedule=schedules.create(f.actor,f.project,new UiAutomationScheduleService.ScheduleInput(f.scenario.id(),null,unique(),"0 0 0 1 1 ? 2099","UTC",30,1,5,true));
        var original=ui.executeFromSchedule(schedule);var old=manifest(f,original);edit(f,"#auto-retry");
        ui.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(original.taskId(),original.id(),"qa-runner","FAILED","QA_FAILURE","controlled fixture",Instant.now()));
        var automatic=rows.findExecutions(f.project).stream().filter(e->original.id().equals(e.retryOfExecutionId())).findFirst().orElseThrow();assertEquals("#auto-retry",selector(f,automatic));assertEquals(old,manifest(f,original));
        edit(f,"#manual-retry");var manual=ui.retry(f.actor,f.project,original.id());assertEquals(original.id(),manual.retryOfExecutionId());assertEquals("#manual-retry",selector(f,manual));assertEquals(old,manifest(f,original));
    }
}
