package io.testhub.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.apiautomation.*;
import io.testhub.platform.parameter.ProjectEnvironmentService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class P2D16ApiWaitFlowTests extends P1FlowTestSupport {
    @Autowired ApiAutomationScenarioService scenarios;
    @Autowired ApiAutomationReusableStepService reusable;
    @Autowired ApiAutomationExecutionService executions;
    @Autowired ApiAutomationTransferService transfer;
    @Autowired ApiAutomationScenarioSyncService sync;
    @Autowired ApiAutomationAssetSyncService assetSync;
    @Autowired ProjectEnvironmentService environments;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture(){var a=actor();var p=project(a);environments.create(a,p,new ProjectEnvironmentService.CreateInput("D16环境","qa","https://8.8.8.8","",true,false));return new Fixture(a,p);}
    String base(Fixture f){return "/api/v1/projects/"+f.project+"/api-automation";}
    List<AutomationStep> steps(Object delay){return List.of(new AutomationStep("request","REQUEST","请求",Map.of("method","GET","url","https://8.8.8.8/qa")),new AutomationStep("wait","WAIT","等待",Collections.singletonMap("delayMillis",delay)));}
    ApiAutomationScenarioDetail create(Fixture f,Object delay){return scenarios.create(f.actor,f.project,new ApiAutomationScenarioService.ScenarioInput(unique(),"","qa","STOP",steps(delay),Map.of(),"","",null));}
    String body(String name,String token) throws Exception{return json.writeValueAsString(Map.of("name",name,"description","","environmentCode","qa","failureStrategy","STOP","steps",steps("TOKEN"),"variables",Map.of(),"preRequestScript","","postResponseScript","")).replace("\"TOKEN\"",token);}
    MvcResult postBody(Fixture f,String path,String body) throws Exception{return mvc.perform(post(base(f)+path).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();}
    JsonNode data(MvcResult r) throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
    void status(int expected,MvcResult result) throws Exception {assertEquals(expected,result.getResponse().getStatus(),result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    long count(String table){return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single();}
    Map<String,Long> writes(){var result=new LinkedHashMap<String,Long>();for(String t:List.of("api_automation_scenario","api_automation_scenario_revision","api_automation_reusable_step","api_automation_reusable_step_revision","api_automation_execution","audit_log"))result.put(t,count(t));return result;}
    void invalid(Fixture f,String token) throws Exception {var before=writes();status(400,postBody(f,"/scenarios",body(unique(),token)));assertEquals(before,writes());}
    void legacy(Fixture f,ApiAutomationScenarioDetail s,Object value) throws Exception{jdbc.sql("UPDATE api_automation_scenario SET steps=CAST(:steps AS JSON) WHERE id=:id").param("steps",json.writeValueAsString(steps(value))).param("id",s.id()).update();}
    void rejected(Runnable action){assertEquals(400,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}
    ApiAutomationReusableStepDetail publicStep(Fixture f){return reusable.create(f.actor,f.project,new ApiAutomationReusableStepService.ReusableStepInput(unique(),"",steps(1),null));}
    ApiAutomationScenarioDetail reference(Fixture f,String type,Map<String,Object> config){return scenarios.create(f.actor,f.project,new ApiAutomationScenarioService.ScenarioInput(unique(),"","qa","STOP",List.of(new AutomationStep("ref",type,"引用",config)),Map.of(),"","",null));}

    @Test @DisplayName("TC-D16API-01") void exactIntegralSpellings() throws Exception{var f=fixture();for(String v:List.of("10","10.0","1e1")){var r=postBody(f,"/scenarios",body(unique(),v));status(200,r);assertEquals(0,new BigDecimal("10").compareTo(data(r).path("steps").get(1).path("config").path("delayMillis").decimalValue()));}}
    @Test @DisplayName("TC-D16API-02") void lowerBoundary() throws Exception{var f=fixture();status(200,postBody(f,"/scenarios",body(unique(),"0")));invalid(f,"-1");}
    @Test @DisplayName("TC-D16API-03") void upperBoundary() throws Exception{var f=fixture();status(200,postBody(f,"/scenarios",body(unique(),"600000")));invalid(f,"600001");}
    @Test @DisplayName("TC-D16API-04") void fractions() throws Exception{var f=fixture();for(String v:List.of("1.5","-0.5"))invalid(f,v);}
    @Test @DisplayName("TC-D16API-05") void preciseFraction() throws Exception{invalid(fixture(),"1.0000000000000001");}
    @Test @DisplayName("TC-D16API-06") void overflow() throws Exception{var f=fixture();for(String v:List.of("18446744073709551616","1e10000"))invalid(f,v);}
    @Test @DisplayName("TC-D16API-07") void strings() throws Exception{var f=fixture();for(String v:List.of("\"10\"","\" 10 \"","\"10.0\"","\"NaN\""))invalid(f,v);}
    @Test @DisplayName("TC-D16API-08") void wrongTypes() throws Exception{var f=fixture();for(String v:List.of("true","[]","{}","\"\""))invalid(f,v);}
    @Test @DisplayName("TC-D16API-09") void missingAndNull() throws Exception{var f=fixture();invalid(f,"null");var before=writes();status(400,postBody(f,"/scenarios",body(unique(),"1").replace("\"delayMillis\":1","\"other\":1")));assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-10") void rawNonFinite() throws Exception{var f=fixture();for(String v:List.of("NaN","Infinity","-Infinity"))invalid(f,v);}
    @Test @DisplayName("TC-D16API-11") void updateValid() throws Exception{var f=fixture();var s=create(f,1);var r=mvc.perform(put(base(f)+"/scenarios/"+s.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"scenario\":"+body(s.name(),"1e1")+"}")).andReturn();status(200,r);assertEquals(2,data(r).path("version").asInt());assertEquals(2,scenarios.revisions(f.actor,f.project,s.id()).size());}
    @Test @DisplayName("TC-D16API-12") void updateInvalid() throws Exception{var f=fixture();var s=create(f,1);var before=writes();var r=mvc.perform(put(base(f)+"/scenarios/"+s.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"scenario\":"+body(s.name(),"1.5")+"}")).andReturn();status(400,r);assertEquals(before,writes());assertEquals(1,scenarios.detail(f.actor,f.project,s.id()).version());}
    @Test @DisplayName("TC-D16API-13") void reusableValid() throws Exception{var f=fixture();String b=json.writeValueAsString(Map.of("name",unique(),"description","","steps",steps("TOKEN"))).replace("\"TOKEN\"","10.0");status(200,postBody(f,"/reusable-steps",b));}
    @Test @DisplayName("TC-D16API-14") void reusableInvalid() throws Exception{var f=fixture();var s=publicStep(f);for(String token:List.of("1.5","1.0000000000000001","\"10\"")){String b=json.writeValueAsString(Map.of("name",unique(),"description","","steps",steps("TOKEN"))).replace("\"TOKEN\"",token);var before=writes();status(400,postBody(f,"/reusable-steps",b));var r=mvc.perform(put(base(f)+"/reusable-steps/"+s.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"reusableStep\":"+b+"}")).andReturn();status(400,r);assertEquals(before,writes());}}
    @Test @DisplayName("TC-D16API-15") void transferValid() throws Exception{var source=fixture();create(source,10);var target=fixture();var r=transfer.transfer(target.actor,target.project,new ApiAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project)));assertEquals(1,r.scenarios());}
    @Test @DisplayName("TC-D16API-16") void transferInvalid() throws Exception{var source=fixture();create(source,1);publicStep(source);var target=fixture();for(String token:List.of("1.5","1.0000000000000001","\"10\"")){String bytes=json.writeValueAsString(new ApiAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project))).replace("\"delayMillis\":1","\"delayMillis\":"+token);var before=writes();rejected(()->transfer.transfer(target.actor,target.project,transfer.decode(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8))));assertEquals(before,writes());}}
    @Test @DisplayName("TC-D16API-17") void syncValid() throws Exception {
        var source=fixture();create(source,1);publicStep(source);
        for(String token:List.of("1","1.0","1e0")) {
            var target=fixture();var item=sync.snapshot(source.actor,source.project,source.actor.userId()).items().getFirst();
            String body=json.writeValueAsString(new ApiAutomationScenarioSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),sync.snapshot(target.actor,target.project,target.actor.userId()).revision(),List.of(new ApiAutomationScenarioSyncService.Operation(UUID.randomUUID().toString(),item)))).replace("\"delayMillis\":1","\"delayMillis\":"+token);
            assertEquals(1,sync.apply(target.actor,target.project,sync.decode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))).results().size());
            var publicItem=assetSync.snapshot(source.actor,source.project,source.actor.userId()).items().stream().filter(x->x.kind().equals("REUSABLE_STEP")).findFirst().orElseThrow();
            String assets=json.writeValueAsString(new ApiAutomationAssetSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),assetSync.snapshot(target.actor,target.project,target.actor.userId()).revision(),List.of(new ApiAutomationAssetSyncService.Operation(UUID.randomUUID().toString(),publicItem)))).replace("\"delayMillis\":1","\"delayMillis\":"+token);
            assertEquals(1,assetSync.apply(target.actor,target.project,assetSync.decode(assets.getBytes(java.nio.charset.StandardCharsets.UTF_8))).results().size());
        }
    }
    @Test @DisplayName("TC-D16API-18") void syncInvalid() throws Exception{var source=fixture();create(source,1);publicStep(source);var target=fixture();var item=sync.snapshot(source.actor,source.project,source.actor.userId()).items().getFirst();for(String token:List.of("1.5","1.0000000000000001","\"10\"")){String bytes=json.writeValueAsString(new ApiAutomationScenarioSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),sync.snapshot(target.actor,target.project,target.actor.userId()).revision(),List.of(new ApiAutomationScenarioSyncService.Operation(UUID.randomUUID().toString(),item)))).replace("\"delayMillis\":1","\"delayMillis\":"+token);var before=writes();rejected(()->sync.apply(target.actor,target.project,sync.decode(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8))));assertEquals(before,writes());}
        var publicItem=assetSync.snapshot(source.actor,source.project,source.actor.userId()).items().stream().filter(x->x.kind().equals("REUSABLE_STEP")).findFirst().orElseThrow();String bytes=json.writeValueAsString(new ApiAutomationAssetSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),assetSync.snapshot(target.actor,target.project,target.actor.userId()).revision(),List.of(new ApiAutomationAssetSyncService.Operation(UUID.randomUUID().toString(),publicItem)))).replace("\"delayMillis\":1","\"delayMillis\":1.0000000000000001");var before=writes();rejected(()->assetSync.apply(target.actor,target.project,assetSync.decode(bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8))));assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-19") void legacyReadable() throws Exception{var f=fixture();var s=create(f,1);legacy(f,s,new BigDecimal("1.5"));var before=writes();assertEquals("1.5",scenarios.detail(f.actor,f.project,s.id()).steps().get(1).config().get("delayMillis").toString());assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-20") void legacyQueueRejected() throws Exception{var f=fixture();var s=create(f,1);legacy(f,s,new BigDecimal("1.5"));var before=writes();rejected(()->executions.previewConfiguration(f.actor,f.project,s.id(),null,Map.of()));rejected(()->executions.create(f.actor,f.project,s.id(),30,null,Map.of()));assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-21") void invalidReferencesBlocked() throws Exception{var f=fixture();var child=create(f,1);var pub=publicStep(f);var root=reference(f,"SUB_SCENARIO",Map.of("scenarioId",child.id(),"scenarioVersion",1));var publicRoot=reference(f,"REUSABLE_STEP",Map.of("reusableStepId",pub.id(),"reusableStepVersion",1));String scenario=json.writeValueAsString(new ApiAutomationScenarioRevisionSnapshot(child.name(),"","qa","STOP",steps(new BigDecimal("1.5")),Map.of(),"",""));jdbc.sql("UPDATE api_automation_scenario_revision SET snapshot=CAST(:v AS JSON) WHERE scenario_id=:id").param("v",scenario).param("id",child.id()).update();String publicSnapshot=json.writeValueAsString(new ApiAutomationReusableStepRevisionSnapshot(pub.name(),"",steps(new BigDecimal("1.5"))));jdbc.sql("UPDATE api_automation_reusable_step_revision SET snapshot=CAST(:v AS JSON) WHERE reusable_step_id=:id").param("v",publicSnapshot).param("id",pub.id()).update();var before=writes();for(var s:List.of(root,publicRoot))rejected(()->executions.create(f.actor,f.project,s.id(),30,null,Map.of()));assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-26") void nonWaitUntouched() throws Exception{var f=fixture();String b=body(unique(),"10").replace("\"method\":\"GET\"","\"method\":\"GET\",\"extraNumber\":1.5,\"extraString\":\"10\",\"extraBoolean\":true");var r=postBody(f,"/scenarios",b);status(200,r);var config=data(r).path("steps").get(0).path("config");assertEquals(1.5,config.path("extraNumber").asDouble());assertEquals("10",config.path("extraString").asText());assertTrue(config.path("extraBoolean").asBoolean());}
    @Test @DisplayName("TC-D16API-27") void projectDenied() throws Exception{var f=fixture();var other=actor();var before=writes();var r=mvc.perform(post(base(f)+"/scenarios").with(user(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(unique(),"10"))).andReturn();status(403,r);assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-28") void staleVersion() {var f=fixture();var s=create(f,1);var first=new ApiAutomationScenarioService.ScenarioInput(s.name(),"","qa","STOP",steps(10),Map.of(),"","",1);scenarios.update(f.actor,f.project,s.id(),first);var before=writes();assertEquals(409,assertThrows(ResponseStatusException.class,()->scenarios.update(f.actor,f.project,s.id(),first)).getStatusCode().value());assertEquals(before,writes());}
    @Test @DisplayName("TC-D16API-29") void oldManifestUnchanged() {var f=fixture();var s=create(f,1);var execution=executions.create(f.actor,f.project,s.id(),30,null,Map.of());var before=executions.manifest(f.actor,f.project,execution.id());scenarios.update(f.actor,f.project,s.id(),new ApiAutomationScenarioService.ScenarioInput(s.name(),"","qa","STOP",steps(10),Map.of(),"","",1));assertEquals(before,executions.manifest(f.actor,f.project,execution.id()));}
    @Test @DisplayName("TC-D16API-30") void explicitLegacyRepair() throws Exception{var f=fixture();var s=create(f,1);legacy(f,s,new BigDecimal("1.5"));scenarios.update(f.actor,f.project,s.id(),new ApiAutomationScenarioService.ScenarioInput(s.name(),"","qa","STOP",steps(10),Map.of(),"","",1));assertNotNull(executions.create(f.actor,f.project,s.id(),30,null,Map.of()).id());}
}
