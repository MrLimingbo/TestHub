package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.mobileautomation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class P2D16MobileFlowTests extends P1FlowTestSupport {
    @Autowired MobileAutomationService mobile;
    @Autowired MobileAutomationTransferService transfer;
    @Autowired MobileAutomationSyncService sync;
    record F(PlatformUserPrincipal actor,String project,MobileAutomationService.Device device,MobileAutomationService.Application app) { }
    F fixture() { var a=actor();var p=project(a);var d=mobile.createDevice(a,p,new MobileAutomationService.DeviceRequest(unique(),"ANDROID",unique(),"UiAutomator2",Map.of(),true));var app=mobile.createApplication(a,p,new MobileAutomationService.ApplicationRequest(unique(),"ANDROID","io.testhub.demo","1",null,true));return new F(a,p,d,app); }
    MobileAutomationService.Scenario scene(F f) {return mobile.createScenario(f.actor,f.project,new MobileAutomationService.ScenarioRequest(f.device.id(),f.app.id(),unique(),"","STOP",Map.of("x","001"),List.of(new MobileAutomationService.Step("one","LAUNCH","start",null,"","",1000))));}
    String base(F f) {return "/api/v1/projects/"+f.project+"/mobile-automation";}
    String body(F f,String field,String token)throws Exception {var step=new LinkedHashMap<String,Object>();step.put("id","one");step.put("type","LAUNCH");step.put("name","start");step.put("timeoutMillis",1000);step.put("value","");step.put("expectedText","");var data=new LinkedHashMap<String,Object>();data.put("deviceId",f.device.id());data.put("applicationId",f.app.id());data.put("name",unique());data.put("description","");data.put("failureStrategy","STOP");data.put("variables",Map.of("x","001"));data.put("steps",List.of(step));if(field.equals("variables")) data.put(field,"RAW");else step.put(field,"RAW");return json.writeValueAsString(data).replace("\"RAW\"",token);}
    Map<String,Long> counts(){var c=new LinkedHashMap<String,Long>();for(String t:List.of("mobile_automation_scenario","mobile_automation_execution","mobile_automation_execution_manifest","mobile_automation_runner_outbox","mobile_automation_transfer_receipt","mobile_automation_sync_receipt","audit_log"))c.put(t,jdbc.sql("SELECT COUNT(*) FROM "+t).query(Long.class).single());return c;}
    void rejected(Runnable r){assertEquals(400,assertThrows(ResponseStatusException.class,r::run).getStatusCode().value());}
    @Test void createUpdateExecuteAndLocation()throws Exception {
        var f=fixture();var s=scene(f);var before=counts();
        for(String t:List.of("1000.5","1000.0000000000000001","\"1000\"","true","[]","{}")) {
            var r=mvc.perform(post(base(f)+"/scenarios").with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(f,"timeoutMillis",t))).andReturn();assertEquals(400,r.getResponse().getStatus(),r.getResponse().getContentAsString());assertEquals(before,counts());
            r=mvc.perform(post(base(f)+"/scenarios/"+s.id()+"/executions").with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"timeoutSeconds\":"+t+"}")).andReturn();assertEquals(400,r.getResponse().getStatus());assertEquals(before,counts());
        }
        var invalid=body(f,"value","{\"PRIVATE_MARKER\":true}");var r=mvc.perform(post(base(f)+"/scenarios").with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invalid)).andReturn();assertEquals(400,r.getResponse().getStatus());assertTrue(r.getResponse().getContentAsString().contains("steps[0].value"));assertFalse(r.getResponse().getContentAsString().contains("PRIVATE_MARKER"));
        String update="{\"expectedVersion\":"+s.version()+",\"scenario\":"+body(f,"timeoutMillis","1000.5")+"}";
        r=mvc.perform(put(base(f)+"/scenarios/"+s.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(update)).andReturn();assertEquals(400,r.getResponse().getStatus());assertEquals(before,counts());
        r=mvc.perform(post(base(f)+"/scenarios").with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(f,"timeoutMillis","1e3"))).andReturn();assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString());
    }
    @Test void oldScenarioAndManifestAreRejectedBeforeWrites()throws Exception {
        var f=fixture();var s=scene(f);var x=mobile.execute(f.actor,f.project,s.id(),300);String raw=jdbc.sql("SELECT steps FROM mobile_automation_scenario WHERE id=:id").param("id",s.id()).query(String.class).single();String bad=raw.replaceAll("\"timeoutMillis\"\\s*:\\s*1000","\"timeoutMillis\":1000.5");assertNotEquals(raw,bad);jdbc.sql("UPDATE mobile_automation_scenario SET steps=CAST(:v AS JSON) WHERE id=:id").param("v",bad).param("id",s.id()).update();var b=counts();rejected(()->mobile.execute(f.actor,f.project,s.id(),300));assertEquals(b,counts());
        String manifest=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",x.id()).query(String.class).single();bad=manifest.replaceAll("\"timeoutMillis\"\\s*:\\s*1000","\"timeoutMillis\":1000.5");assertNotEquals(manifest,bad);jdbc.sql("UPDATE mobile_automation_execution_manifest SET content=CAST(:v AS JSON) WHERE execution_id=:id").param("v",bad).param("id",x.id()).update();rejected(()->mobile.runtimeConfiguration("fixture",x.taskId(),x.id()));assertEquals("QUEUED",jdbc.sql("SELECT status FROM mobile_automation_execution WHERE id=:id").param("id",x.id()).query(String.class).single());assertEquals(b,counts());
    }
    @Test void transferAndSyncRejectBeforePartialWrites()throws Exception {
        var source=fixture();scene(source);var target=fixture();var snap=transfer.export(source.actor,source.project);String raw=json.writeValueAsString(new MobileAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),snap));String bad=raw.replace("\"timeoutMillis\":1000","\"timeoutMillis\":1000.0000000000000001");assertNotEquals(raw,bad);var before=counts();String transferBad=bad;rejected(()->transfer.transfer(target.actor,target.project,transfer.decode(transferBad.getBytes(StandardCharsets.UTF_8))));assertEquals(before,counts());
        var snapshot=sync.snapshot(source.actor,source.project,source.actor.userId());var targetSnapshot=sync.snapshot(target.actor,target.project,target.actor.userId());var request=new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),targetSnapshot.revision(),snapshot.items().stream().map(i->new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),i)).toList());raw=json.writeValueAsString(request);bad=raw.replace("\"timeoutMillis\":1000","\"timeoutMillis\":1000.0000000000000001");assertNotEquals(raw,bad);String syncBad=bad;rejected(()->sync.apply(target.actor,target.project,sync.decode(syncBad.getBytes(StandardCharsets.UTF_8))));assertEquals(before,counts());
    }
    @Test void httpTransferAndSyncAcceptExactNumbersAndRejectLossyValues() throws Exception {
        var source=fixture();scene(source);
        for (String spelling : List.of("1000", "1000.0", "1e3")) {
            var target=fixture();
            String raw=json.writeValueAsString(new MobileAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project)));
            String transferBody=raw.replace("\"timeoutMillis\":1000","\"timeoutMillis\":"+spelling);
            var response=mvc.perform(post(base(target)+"/transfer").with(user(target.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(transferBody)).andReturn();
            assertEquals(200,response.getResponse().getStatus(),response.getResponse().getContentAsString());
            var imported=mobile.scenarios(target.actor,target.project).getFirst();
            assertEquals(1000,imported.steps().getFirst().timeoutMillis());assertEquals(1,imported.version());
            var syncTarget=fixture();var sourceSnapshot=sync.snapshot(source.actor,source.project,source.actor.userId());var targetSnapshot=sync.snapshot(syncTarget.actor,syncTarget.project,syncTarget.actor.userId());
            var request=new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),syncTarget.actor.userId(),targetSnapshot.revision(),sourceSnapshot.items().stream().map(i->new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),i)).toList());
            raw=json.writeValueAsString(request);String syncBody=raw.replace("\"timeoutMillis\":1000","\"timeoutMillis\":"+spelling);
            response=mvc.perform(post(base(syncTarget)+"/sync").with(user(syncTarget.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(syncBody)).andReturn();
            assertEquals(200,response.getResponse().getStatus(),response.getResponse().getContentAsString());
            var synced=mobile.scenarios(syncTarget.actor,syncTarget.project).getFirst();assertEquals(1000,synced.steps().getFirst().timeoutMillis());assertEquals(1,synced.version());
        }
        var target=fixture();var snapshot=sync.snapshot(source.actor,source.project,source.actor.userId());var revision=sync.snapshot(target.actor,target.project,target.actor.userId()).revision();
        var request=new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),revision,snapshot.items().stream().map(i->new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),i)).toList());
        String transferRaw=json.writeValueAsString(new MobileAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project)));
        String syncRaw=json.writeValueAsString(request);var before=counts();
        for (String route:List.of("/transfer","/sync")) {
            String original=route.equals("/transfer")?transferRaw:syncRaw;String bad=original.replace("\"timeoutMillis\":1000","\"timeoutMillis\":1000.0000000000000001");assertNotEquals(original,bad);
            var response=mvc.perform(post(base(target)+route).with(user(target.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bad)).andReturn();assertEquals(400,response.getResponse().getStatus(),response.getResponse().getContentAsString());assertEquals(before,counts());
        }
    }
    @Test void oldRecordCanBeReadAndValidFrozenManifestStillIssued() throws Exception {
        var f=fixture();var scenario=scene(f);var execution=mobile.execute(f.actor,f.project,scenario.id(),null);
        String original=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",execution.id()).query(String.class).single();
        String steps=jdbc.sql("SELECT steps FROM mobile_automation_scenario WHERE id=:id").param("id",scenario.id()).query(String.class).single();
        String legacy=steps.replaceAll("\"timeoutMillis\"\\s*:\\s*1000","\"timeoutMillis\":1000.5");assertNotEquals(steps,legacy);
        jdbc.sql("UPDATE mobile_automation_scenario SET steps=CAST(:v AS JSON) WHERE id=:id").param("v",legacy).param("id",scenario.id()).update();
        assertEquals(1000,mobile.scenarios(f.actor,f.project).getFirst().steps().getFirst().timeoutMillis());
        assertEquals(1000,mobile.runtimeConfiguration("fixture",execution.taskId(),execution.id()).manifest().scenario().steps().getFirst().timeoutMillis());
        assertEquals(original,jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",execution.id()).query(String.class).single());
    }

}
