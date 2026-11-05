package io.testhub.platform;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.uiautomation.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
class P2D16WebFlowTests extends P1FlowTestSupport {
    @org.junit.jupiter.api.BeforeEach void approvedFixtureOrigin() {
        jdbc.sql("DELETE FROM system_config WHERE config_key='ui-automation.allowed-origins'").update();
        jdbc.sql("INSERT INTO system_config(config_key,config_value,secret,updated_by) VALUES('ui-automation.allowed-origins','https://ui.example.test',FALSE,'fixture')").update();
    }
    @Autowired UiAutomationService ui;
    @Autowired UiAutomationRepository rows;
    @Autowired UiAutomationSyncService sync;
    @Autowired UiAutomationTransferService transfer;
    @Autowired UiAutomationScheduleService schedules;
    record F(PlatformUserPrincipal actor,String project,UiAutomationBrowserProfile profile) { }
    F fixture(){var a=actor();String p=project(a);var profile=ui.createProfile(a,p,new UiAutomationService.ProfileRequest(unique(),"chromium","https://ui.example.test",800,600,true));return new F(a,p,profile);}
    String base(F f){return "/api/v1/projects/"+f.project+"/ui-automation";}
    Map<String,Object> profile(){var d=new LinkedHashMap<String,Object>();d.put("name",unique());d.put("browser","chromium");d.put("baseUrl","https://ui.example.test");d.put("enabled",true);d.put("viewportWidth",800);d.put("viewportHeight",600);return d;}
    Map<String,Object> step(String type){var d=new LinkedHashMap<String,Object>();d.put("id","one");d.put("type",type);d.put("name",type);d.put("selector",Set.of("FILL","ASSERT_TEXT","CLICK","ASSERT_VISIBLE").contains(type)?"#input":null);d.put("value","FILL".equals(type)?"001":null);d.put("expectedText","ASSERT_TEXT".equals(type)?"001":null);d.put("url","NAVIGATE".equals(type)?"/":null);d.put("timeoutMillis",1000);return d;}
    Map<String,Object> scenario(F f,Map<String,Object> s){var d=new LinkedHashMap<String,Object>();d.put("name",unique());d.put("description","");d.put("browserProfileId",f.profile.id());d.put("failureStrategy","STOP");d.put("variables",Map.of("id","001"));d.put("steps",List.of(s));return d;}
    String profileRaw(String field,String token)throws Exception{var d=profile();d.put(field,"RAW");return json.writeValueAsString(d).replace("\"RAW\"",token);}
    String scenarioRaw(F f,String type,String field,String token)throws Exception{var s=step(type);var d=scenario(f,s);if(field.equals("variables"))d.put(field,"RAW");else s.put(field,"RAW");return json.writeValueAsString(d).replace("\"RAW\"",token);}
    MvcResult postTo(F f,String suffix,String body)throws Exception{return mvc.perform(post(base(f)+suffix).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();}
    MvcResult putTo(F f,String suffix,String body)throws Exception{return mvc.perform(put(base(f)+suffix).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();}
    void status(int n,MvcResult r)throws Exception{assertEquals(n,r.getResponse().getStatus(),r.getResponse().getContentAsString());}
    JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
    Map<String,Long> counts(){var r=new LinkedHashMap<String,Long>();for(String t:List.of("ui_automation_browser_profile","ui_automation_scenario","ui_automation_schedule","ui_automation_execution","ui_automation_execution_manifest","ui_automation_runner_outbox","ui_automation_transfer_receipt","ui_automation_sync_receipt","audit_log"))r.put(t,jdbc.sql("SELECT COUNT(*) FROM "+t).query(Long.class).single());return r;}
    void bad(F f,String suffix,String body)throws Exception{var b=counts();status(400,postTo(f,suffix,body));assertEquals(b,counts());}
    void rejected(Runnable action){assertEquals(400,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}
    UiAutomationScenario scene(F f){return ui.createScenario(f.actor,f.project,new UiAutomationService.ScenarioRequest(f.profile.id(),unique(),"","STOP",Map.of("id","001"),List.of(new UiAutomationStep("wait","WAIT","Wait",null,null,null,null,null,1000),new UiAutomationStep("fill","FILL","Fill","#input",null,"001",null,null,1000),new UiAutomationStep("text","ASSERT_TEXT","Text","#input",null,null,null,"001",1000))));}
    Map<String,Object> timer(UiAutomationScenario s){var d=new LinkedHashMap<String,Object>();d.put("name",unique());d.put("scenarioId",s.id());d.put("cronExpression","0 0 0 1 1 ? 2099");d.put("timezone","UTC");d.put("timeoutSeconds",300);d.put("maxRetryAttempts",1);d.put("retryBackoffSeconds",30);d.put("enabled",false);return d;}
    UiAutomationSchedule timer(F f,UiAutomationScenario s,boolean enabled){return schedules.create(f.actor,f.project,new UiAutomationScheduleService.ScheduleInput(s.id(),null,unique(),"0 0 0 1 1 ? 2099","UTC",300,1,30,enabled));}
    F source(){var f=fixture();var s=scene(f);timer(f,s,false);return f;}
    UiAutomationSyncService.Request request(F source,F target){var items=sync.snapshot(source.actor,source.project,source.actor.userId()).items();return new UiAutomationSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),sync.snapshot(target.actor,target.project,target.actor.userId()).revision(),items.stream().map(i->new UiAutomationSyncService.Operation(UUID.randomUUID().toString(),i)).toList());}
    String equivalent(String raw,int mode){return mode==0?raw:raw.replaceAll("(\"(?:viewportWidth|viewportHeight|timeoutMillis|timeoutSeconds)\":)([0-9]+)","$1$2"+(mode==1?".0":"e0"));}
    List<String> invalidPayloads(String original){var result=new ArrayList<String>();for(String field:List.of("viewportWidth","viewportHeight","timeoutMillis","timeoutSeconds")){String value=field.equals("viewportWidth")?"800":field.equals("viewportHeight")?"600":field.equals("timeoutMillis")?"1000":"300";for(String t:List.of(value+".5",value+".0000000000000001","\""+value+"\"","true")){String changed=original.replaceAll("\""+field+"\":[0-9]+","\""+field+"\":"+t);assertNotEquals(original,changed);result.add(changed);}}
        for(String t:List.of("1","true","[]","{}")){String changed=original.replace("\"variables\":{\"id\":\"001\"}","\"variables\":{\"id\":"+t+"}");assertNotEquals(original,changed);result.add(changed);for(String field:List.of("value","expectedText")){changed=original.replace("\""+field+"\":\"001\"","\""+field+"\":"+t);assertNotEquals(original,changed);result.add(changed);}}return result;}
    void widthExact(String field,int n)throws Exception{var f=fixture();for(String t:List.of(""+n,n+".0",n+"e0")){var r=postTo(f,"/browser-profiles",profileRaw(field,t));status(200,r);assertEquals(n,data(r).path(field).asInt());}}
    void widthBounds(String field,int max)throws Exception{var f=fixture();for(int n:List.of(320,max))status(200,postTo(f,"/browser-profiles",profileRaw(field,""+n)));for(int n:List.of(319,max+1))bad(f,"/browser-profiles",profileRaw(field,""+n));}
    void stepExact(String type)throws Exception{var f=fixture();for(String t:List.of("1000","1000.0","1e3")){var r=postTo(f,"/scenarios",scenarioRaw(f,type,"timeoutMillis",t));status(200,r);assertEquals(1000,data(r).path("steps").get(0).path("timeoutMillis").asInt());}}
    void stepBounds(String type,int min)throws Exception{var f=fixture();for(int n:List.of(min,60000))status(200,postTo(f,"/scenarios",scenarioRaw(f,type,"timeoutMillis",""+n)));for(int n:List.of(min-1,60001))bad(f,"/scenarios",scenarioRaw(f,type,"timeoutMillis",""+n));}
    void corruptSteps(F f,UiAutomationScenario s,String token)throws Exception{String raw=json.writeValueAsString(s.steps()).replaceAll("\"timeoutMillis\":1000","\"timeoutMillis\":"+token);jdbc.sql("UPDATE ui_automation_scenario SET steps=CAST(:v AS JSON) WHERE project_id=:p AND id=:i").param("v",raw).param("p",f.project).param("i",s.id()).update();}
    String dataId(MvcResult r){try{return data(r).path("id").asText();}catch(Exception e){throw new AssertionError(e);}}

    @Test @DisplayName("TC-D16WEB-01") void widthExact()throws Exception{widthExact("viewportWidth",800);}
    @Test @DisplayName("TC-D16WEB-02") void heightExact()throws Exception{widthExact("viewportHeight",600);}
    @Test @DisplayName("TC-D16WEB-03") void widthBounds()throws Exception{widthBounds("viewportWidth",3840);}
    @Test @DisplayName("TC-D16WEB-04") void heightBounds()throws Exception{widthBounds("viewportHeight",2160);}
    @Test @DisplayName("TC-D16WEB-05") void viewportFractions()throws Exception{var f=fixture();for(String field:List.of("viewportWidth","viewportHeight"))for(String t:List.of("800.5","800.0000000000000001"))bad(f,"/browser-profiles",profileRaw(field,t));}
    @Test @DisplayName("TC-D16WEB-06") void viewportTypes()throws Exception{var f=fixture();for(String field:List.of("viewportWidth","viewportHeight"))for(String t:List.of("\"800\"","true","[]","{}"))bad(f,"/browser-profiles",profileRaw(field,t));}
    @Test @DisplayName("TC-D16WEB-07") void viewportDefaults()throws Exception{var f=fixture();for(boolean missing:List.of(true,false)){var d=profile();if(missing){d.remove("viewportWidth");d.remove("viewportHeight");}else{d.put("viewportWidth",null);d.put("viewportHeight",null);}var r=postTo(f,"/browser-profiles",json.writeValueAsString(d));status(200,r);assertEquals(1440,data(r).path("viewportWidth").asInt());assertEquals(900,data(r).path("viewportHeight").asInt());}}
    @Test @DisplayName("TC-D16WEB-08") void waitExact()throws Exception{stepExact("WAIT");}
    @Test @DisplayName("TC-D16WEB-09") void waitBounds()throws Exception{stepBounds("WAIT",1);}
    @Test @DisplayName("TC-D16WEB-10") void actionExact()throws Exception{stepExact("FILL");}
    @Test @DisplayName("TC-D16WEB-11") void actionBounds()throws Exception{stepBounds("FILL",100);}
    @Test @DisplayName("TC-D16WEB-12") void stepFractions()throws Exception{var f=fixture();for(String type:List.of("WAIT","FILL"))for(String t:List.of("1000.5","1000.0000000000000001"))bad(f,"/scenarios",scenarioRaw(f,type,"timeoutMillis",t));}
    @Test @DisplayName("TC-D16WEB-13") void stepTypes()throws Exception{var f=fixture();for(String type:List.of("WAIT","FILL"))for(String t:List.of("\"1000\"","true","[]","{}"))bad(f,"/scenarios",scenarioRaw(f,type,"timeoutMillis",t));}
    @Test @DisplayName("TC-D16WEB-14") void overflow()throws Exception{var f=fixture();var s=scene(f);for(String t:List.of("18446744073709551616","1e10000","NaN","Infinity","-Infinity")){bad(f,"/browser-profiles",profileRaw("viewportWidth",t));bad(f,"/scenarios",scenarioRaw(f,"WAIT","timeoutMillis",t));bad(f,"/scenarios/"+s.id()+"/executions","{\"timeoutSeconds\":"+t+"}");}}
    @Test @DisplayName("TC-D16WEB-15") void waitRequired()throws Exception{var f=fixture();bad(f,"/scenarios",scenarioRaw(f,"WAIT","timeoutMillis","null"));var st=step("WAIT");st.remove("timeoutMillis");bad(f,"/scenarios",json.writeValueAsString(scenario(f,st)));}
    @Test @DisplayName("TC-D16WEB-16") void actionDefault()throws Exception{var f=fixture();for(boolean missing:List.of(true,false)){var st=step("FILL");if(missing)st.remove("timeoutMillis");else st.put("timeoutMillis",null);var r=postTo(f,"/scenarios",json.writeValueAsString(scenario(f,st)));status(200,r);assertTrue(data(r).path("steps").get(0).path("timeoutMillis").isNull());}}
    @Test @DisplayName("TC-D16WEB-17") void executeExact()throws Exception{var f=fixture();var s=scene(f);for(String t:List.of("300","300.0","3e2")){var r=postTo(f,"/scenarios/"+s.id()+"/executions","{\"timeoutSeconds\":"+t+"}");status(200,r);var x=ui.executions(f.actor,f.project).stream().filter(y->y.id().equals(dataId(r))).findFirst().orElseThrow();assertEquals(300,Duration.between(x.requestedAt(),x.deadlineAt()).toSeconds());}}
    @Test @DisplayName("TC-D16WEB-18") void executeBounds()throws Exception{var f=fixture();var s=scene(f);for(int n:List.of(10,3600))status(200,postTo(f,"/scenarios/"+s.id()+"/executions","{\"timeoutSeconds\":"+n+"}"));for(int n:List.of(9,3601))bad(f,"/scenarios/"+s.id()+"/executions","{\"timeoutSeconds\":"+n+"}");}
    @Test @DisplayName("TC-D16WEB-19") void executeTypes()throws Exception{var f=fixture();var s=scene(f);for(String t:List.of("300.5","300.0000000000000001","\"300\"","true","[]","{}"))bad(f,"/scenarios/"+s.id()+"/executions","{\"timeoutSeconds\":"+t+"}");}
    @Test @DisplayName("TC-D16WEB-20") void executeDefaults()throws Exception{var f=fixture();var s=scene(f);for(String body:List.of("","{}","{\"timeoutSeconds\":null}")){var r=postTo(f,"/scenarios/"+s.id()+"/executions",body);status(200,r);var x=ui.executions(f.actor,f.project).stream().filter(y->y.id().equals(dataId(r))).findFirst().orElseThrow();assertEquals(300,Duration.between(x.requestedAt(),x.deadlineAt()).toSeconds());}}
    @Test @DisplayName("TC-D16WEB-21") void scheduleAlias()throws Exception{var f=fixture();var s=scene(f);var d=timer(s);d.put("timeoutSeconds","RAW");String raw=json.writeValueAsString(d);status(200,postTo(f,"/schedules",raw.replace("\"RAW\"","300.0")));for(String t:List.of("300.5","300.0000000000000001","\"300\""))bad(f,"/schedules",raw.replace("\"RAW\"",t).replace(String.valueOf(d.get("name")),unique()));}
    @Test @DisplayName("TC-D16WEB-22") void variableStrings()throws Exception{var f=fixture();var r=postTo(f,"/scenarios",scenarioRaw(f,"WAIT","variables","{\"id\":\"001\",\"flag\":\"false\",\"literal\":\"null\",\"empty\":\"\"}"));status(200,r);assertEquals("001",data(r).path("variables").path("id").asText());}
    @Test @DisplayName("TC-D16WEB-23") void variableTypes()throws Exception{var f=fixture();for(String t:List.of("1","true","[]","{}"))bad(f,"/scenarios",scenarioRaw(f,"WAIT","variables","{\"id\":"+t+"}"));}
    @Test @DisplayName("TC-D16WEB-24") void variableNull()throws Exception{var f=fixture();for(String t:List.of("null","{\"id\":null}")){var r=postTo(f,"/scenarios",scenarioRaw(f,"WAIT","variables",t));status(200,r);if(t.startsWith("{"))assertEquals("",data(r).path("variables").path("id").asText());else assertEquals(0,data(r).path("variables").size());}}
    @Test @DisplayName("TC-D16WEB-25") void stepStrings()throws Exception{var f=fixture();for(String type:List.of("FILL","ASSERT_TEXT")){String field=type.equals("FILL")?"value":"expectedText";var r=postTo(f,"/scenarios",scenarioRaw(f,type,field,"\" {{id}} \""));status(200,r);assertEquals("{{id}}",data(r).path("steps").get(0).path(field).asText());}}
    @Test @DisplayName("TC-D16WEB-26") void stepTextTypes()throws Exception{var f=fixture();for(String field:List.of("value","expectedText"))for(String t:List.of("1","true","[]","{}"))bad(f,"/scenarios",scenarioRaw(f,field.equals("value")?"FILL":"ASSERT_TEXT",field,t));}
    @Test @DisplayName("TC-D16WEB-27") void stepTextRequired()throws Exception{var f=fixture();for(String type:List.of("FILL","ASSERT_TEXT")){String field=type.equals("FILL")?"value":"expectedText";bad(f,"/scenarios",scenarioRaw(f,type,field,"null"));}status(200,postTo(f,"/scenarios",scenarioRaw(f,"WAIT","value","null")));}
    @Test @DisplayName("TC-D16WEB-28") void editFreeze()throws Exception{var f=fixture();var s=scene(f);var x=ui.execute(f.actor,f.project,s.id(),300,null);var frozen=rows.findManifest(f.project,x.id()).orElseThrow();var r=putTo(f,"/browser-profiles/"+f.profile.id(),profileRaw("viewportWidth","1000.0"));status(200,r);assertEquals(2,data(r).path("version").asInt());r=putTo(f,"/scenarios/"+s.id(),scenarioRaw(f,"WAIT","timeoutMillis","2000.0"));status(200,r);assertEquals(2,data(r).path("version").asInt());assertEquals(frozen,rows.findManifest(f.project,x.id()).orElseThrow());}
    @Test @DisplayName("TC-D16WEB-29") void editProfileInvalid()throws Exception{var f=fixture();var before=counts();status(400,putTo(f,"/browser-profiles/"+f.profile.id(),profileRaw("viewportWidth","800.5")));assertEquals(f.profile,ui.profiles(f.actor,f.project).getFirst());assertEquals(before,counts());}
    @Test @DisplayName("TC-D16WEB-30") void editScenarioInvalid()throws Exception{var f=fixture();var s=scene(f);var before=counts();status(400,putTo(f,"/scenarios/"+s.id(),scenarioRaw(f,"WAIT","timeoutMillis","1000.5")));assertEquals(s,ui.scenarios(f.actor,f.project).getFirst());assertEquals(before,counts());}
    @Test @DisplayName("TC-D16WEB-31") void lateStepInvalid()throws Exception{var f=fixture();var st=step("WAIT");st.put("id","second");st.put("timeoutMillis","RAW");var d=scenario(f,step("WAIT"));d.put("steps",List.of(step("WAIT"),st));bad(f,"/scenarios",json.writeValueAsString(d).replace("\"RAW\"","1000.5"));}
    @Test @DisplayName("TC-D16WEB-32") void transferValid()throws Exception{var source=source();for(int n=0;n<3;n++){var target=fixture();String raw=equivalent(json.writeValueAsString(new UiAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project))),n);var r=transfer.transfer(target.actor,target.project,transfer.decode(raw.getBytes(StandardCharsets.UTF_8)));assertEquals(1,r.scenarios());assertTrue(ui.profiles(target.actor,target.project).stream().filter(p->p.name().equals(source.profile.name())).noneMatch(UiAutomationBrowserProfile::enabled));assertTrue(ui.executions(target.actor,target.project).isEmpty());}}
    @Test @DisplayName("TC-D16WEB-33") void transferInvalid()throws Exception{var source=source();var target=fixture();String raw=json.writeValueAsString(new UiAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project)));for(String value:invalidPayloads(raw)){var b=counts();rejected(()->transfer.transfer(target.actor,target.project,transfer.decode(value.getBytes(StandardCharsets.UTF_8))));assertEquals(b,counts());}}
    @Test @DisplayName("TC-D16WEB-34") void syncValid()throws Exception{var source=source();for(int n=0;n<3;n++){var target=fixture();String raw=equivalent(json.writeValueAsString(request(source,target)),n);assertEquals(3,assertDoesNotThrow(()->sync.apply(target.actor,target.project,sync.decode(raw.getBytes(StandardCharsets.UTF_8)))).results().size());}}
    @Test @DisplayName("TC-D16WEB-35") void syncInvalid()throws Exception{var source=source();var target=fixture();String raw=json.writeValueAsString(request(source,target));for(String value:invalidPayloads(raw)){var b=counts();rejected(()->sync.apply(target.actor,target.project,sync.decode(value.getBytes(StandardCharsets.UTF_8))));assertEquals(b,counts());}}
    @Test @DisplayName("TC-D16WEB-36") void replay()throws Exception{var source=source();var target=fixture();var r=request(source,target);var first=sync.apply(target.actor,target.project,r);var b=counts();assertEquals(first,sync.apply(target.actor,target.project,r));assertEquals(b,counts());var other=fixture();var tr=new UiAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),other.actor.userId(),transfer.export(source.actor,source.project));var receipt=transfer.transfer(other.actor,other.project,tr);b=counts();assertEquals(receipt,transfer.transfer(other.actor,other.project,tr));assertEquals(b,counts());}
    @Test @DisplayName("TC-D16WEB-37") void revisionConflict()throws Exception{var source=source();var target=fixture();var r=request(source,target);scene(target);var b=counts();assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(target.actor,target.project,r)).getStatusCode().value());assertEquals(b,counts());}
    @Test @DisplayName("TC-D16WEB-38") void permission()throws Exception{var f=fixture();var other=actor();var b=counts();var r=mvc.perform(post(base(f)+"/browser-profiles").with(user(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(profileRaw("viewportWidth","800"))).andReturn();status(403,r);assertEquals(b,counts());}
    @Test @DisplayName("TC-D16WEB-39") void oldReadOnly()throws Exception{var f=fixture();var s=scene(f);var x=ui.execute(f.actor,f.project,s.id(),300,null);var frozen=rows.findManifest(f.project,x.id()).orElseThrow();corruptSteps(f,s,"1000.5");var b=counts();assertEquals(1000,ui.scenarios(f.actor,f.project).getFirst().steps().getFirst().timeoutMillis());assertEquals(frozen,rows.findManifest(f.project,x.id()).orElseThrow());assertEquals(b,counts());}
    @Test @DisplayName("TC-D16WEB-40") void oldManualQueue()throws Exception {
        var f=fixture();var s=scene(f);var x=ui.execute(f.actor,f.project,s.id(),300,null);
        var frozen=rows.findManifest(f.project,x.id()).orElseThrow();
        // Use an exactly representable fraction for a persisted legacy fixture. Raw tiny fractions are covered before storage.
        corruptSteps(f,s,"1000.5");
        String stored=jdbc.sql("SELECT steps FROM ui_automation_scenario WHERE id=:i").param("i",s.id()).query(String.class).single();
        assertTrue(json.readTree(stored).get(0).path("timeoutMillis").decimalValue().stripTrailingZeros().scale()>0);
        var before=counts();rejected(()->ui.execute(f.actor,f.project,s.id(),300,null));assertEquals(before,counts());
        corruptSteps(f,s,"1000");
        for(String token:List.of("123","true","[]","{}")) {
            jdbc.sql("UPDATE ui_automation_scenario SET variables=CAST(:v AS JSON) WHERE id=:i").param("v","{\"id\":"+token+"}").param("i",s.id()).update();
            if(token.equals("123"))assertEquals("123",ui.scenarios(f.actor,f.project).getFirst().variables().get("id"));
            rejected(()->ui.execute(f.actor,f.project,s.id(),300,null));assertEquals(before,counts());
        }
        jdbc.sql("UPDATE ui_automation_scenario SET variables=CAST(:v AS JSON) WHERE id=:i").param("v","{\"id\":\"001\"}").param("i",s.id()).update();
        jdbc.sql("UPDATE ui_automation_browser_profile SET viewport_width=319 WHERE id=:i").param("i",f.profile.id()).update();
        rejected(()->ui.execute(f.actor,f.project,s.id(),300,null));assertEquals(before,counts());
        assertEquals(frozen,rows.findManifest(f.project,x.id()).orElseThrow());assertEquals(x.status(),ui.executions(f.actor,f.project).getFirst().status());
    }
    @Test @DisplayName("TC-D16WEB-41") void oldOtherQueue()throws Exception {
        var f=fixture();var s=scene(f);var timer=timer(f,s,true);
        var manual=ui.execute(f.actor,f.project,s.id(),300,null);
        jdbc.sql("UPDATE ui_automation_execution SET status='FAILED' WHERE id=:i").param("i",manual.id()).update();
        var automatic=ui.executeFromSchedule(timer);
        ui.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(automatic.taskId(),automatic.id(),"d16-fixture","ACCEPTED","TASK_ACCEPTED","accepted",java.time.Instant.now()));
        corruptSteps(f,s,"1000.5");var before=counts();
        rejected(()->ui.executeFromSchedule(timer));rejected(()->ui.retry(f.actor,f.project,manual.id()));assertEquals(before,counts());
        assertDoesNotThrow(()->ui.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(automatic.taskId(),automatic.id(),"d16-fixture","FAILED","PLAYWRIGHT_EXECUTION_FAILED","failed",java.time.Instant.now())));
        var after=counts();for(String table:before.keySet())if(!table.equals("audit_log"))assertEquals(before.get(table),after.get(table),table);
        assertEquals("FAILED",rows.findExecution(f.project,automatic.id()).orElseThrow().status());
        assertEquals(1L,jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:p AND resource_id=:i AND action='UI_AUTOMATION_RETRY_PRECHECK_REJECTED'").param("p",f.project).param("i",automatic.id()).query(Long.class).single());
    }
    @Test @DisplayName("TC-D16WEB-42") void oldRuntime()throws Exception{var f=fixture();var s=scene(f);var x=ui.execute(f.actor,f.project,s.id(),300,null);String content=jdbc.sql("SELECT content FROM ui_automation_execution_manifest WHERE execution_id=:i").param("i",x.id()).query(String.class).single();String bad=content.replaceAll("\"timeoutMillis\"\\s*:\\s*1000","\"timeoutMillis\":1000.5");assertNotEquals(content,bad);jdbc.sql("UPDATE ui_automation_execution_manifest SET content=CAST(:c AS JSON) WHERE execution_id=:i").param("c",bad).param("i",x.id()).update();var b=counts();rejected(()->ui.runtimeConfiguration("d16-fixture",x.taskId(),x.id()));assertEquals(b,counts());assertEquals(x.status(),ui.executions(f.actor,f.project).getFirst().status());}
    @Test @DisplayName("TC-D16WEB-43") void errorLocation()throws Exception{var f=fixture();var st=step("FILL");st.put("id","second");st.put("value","RAW");var d=scenario(f,step("WAIT"));d.put("steps",List.of(step("WAIT"),st));var r=postTo(f,"/scenarios",json.writeValueAsString(d).replace("\"RAW\"","{\"D16_PRIVATE_MARKER\":true}"));status(400,r);var b=json.readTree(r.getResponse().getContentAsString());assertTrue(b.path("details").has("steps[1].value"));assertFalse(b.toString().contains("D16_PRIVATE_MARKER"));}
    @Test @DisplayName("TC-D16WEB-44") void nonTargetContract()throws Exception{var f=fixture();var s=scene(f);var d=timer(s);d.put("maxRetryAttempts","RAW");var r=postTo(f,"/schedules",json.writeValueAsString(d).replace("\"RAW\"","1.5"));status(200,r);assertEquals(1,data(r).path("maxRetryAttempts").asInt());}
    @Test @DisplayName("TC-D16WEB-45") void objectReference()throws Exception{var f=fixture();var p=ui.createPageObject(f.actor,f.project,new UiAutomationService.PageObjectRequest(unique(),"#owned",""));var s=ui.createScenario(f.actor,f.project,new UiAutomationService.ScenarioRequest(f.profile.id(),unique(),"","STOP",Map.of(),List.of(new UiAutomationStep("fill","FILL","Fill",null,p.id(),"001",null,null,null))));var x=ui.execute(f.actor,f.project,s.id(),300,null);assertEquals("#owned",rows.findManifest(f.project,x.id()).orElseThrow().snapshot().scenario().steps().getFirst().selector());}
}
