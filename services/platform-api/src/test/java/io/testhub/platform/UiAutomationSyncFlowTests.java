package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTestExecutionService;
import io.testhub.platform.uiautomation.*;
import io.testhub.platform.uiautomation.UiAutomationSyncService.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class UiAutomationSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"ui-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"ui-sync-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired UiAutomationSyncService sync;
    @Autowired UiAutomationService automation;
    @Autowired UiAutomationRepository assets;
    @Autowired UiAutomationScheduleService schedules;
    @Autowired FunctionalTestExecutionService functional;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Scheduler quartz;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        var a="ui-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(a,a+"@example.test","同步验收","Sync-fixture-2026!"));var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());
    }
    Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    Item profile(String name) {return item("PROFILE",name,new Profile("chromium","https://ui.example.test",1200,800));}
    Item object(String name,String selector) {return item("PAGE_OBJECT",name,new PageObject(selector,"对象说明"));}
    Item scene(String name,String description) {return item("SCENARIO",name,new Scenario("浏览器",description,"CONTINUE",Map.of("order","测试值"),List.of(
        new Step("open","NAVIGATE","打开",null,null,null,"/",null,10000),new Step("fill","FILL","填写","#input",null,"{{order}}",null,null,10000),
        new Step("click","CLICK","点击",null,"按钮",null,null,null,10000),new Step("visible","ASSERT_VISIBLE","可见","#result",null,null,null,null,10000),
        new Step("text","ASSERT_TEXT","文本","#result",null,null,null,"{{order}}",10000),new Step("wait","WAIT","等待",null,null,null,null,null,20),new Step("capture","SCREENSHOT","截图",null,null,null,null,null,10000))));}
    Item timer(String cron,String plan) {return item("SCHEDULE","年检",new Schedule("场景",plan,cron,"Asia/Shanghai",120,2,10));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    void seed(Fixture f) {apply(f,timer("0 0 0 1 1 ? 2099",null),scene("场景","原始"),object("按钮","#submit"),profile("浏览器"));}
    @BeforeEach void allowed() {jdbc.sql("DELETE FROM system_config WHERE config_key='ui-automation.allowed-origins'").update();jdbc.sql("INSERT INTO system_config(config_key,config_value,secret,updated_by) VALUES('ui-automation.allowed-origins','https://ui.example.test',FALSE,'fixture')").update();}
    @Test void unorderedDefinitionsRemapReferencesWithoutExecutionOrActivation() throws Exception {
        var f=fixture();seed(f);var p=automation.profiles(f.actor(),f.project()).getFirst();var s=automation.scenarios(f.actor(),f.project()).getFirst();var o=automation.pageObjects(f.actor(),f.project()).getFirst();var t=schedules.list(f.actor(),f.project()).getFirst();
        assertFalse(p.enabled());assertFalse(t.enabled());assertEquals(1,p.version());assertEquals(1,s.version());assertEquals(p.id(),s.browserProfileId());assertEquals(o.id(),s.steps().get(2).pageObjectId());assertEquals("#submit",s.steps().get(2).selector());assertEquals(s.id(),t.scenarioId());
        assertEquals(7,s.steps().size());assertEquals(0,snapshot(f).excludedExecutions());assertTrue(automation.executions(f.actor(),f.project()).isEmpty());assertFalse(quartz.checkExists(new JobKey(t.id(),"testhub-ui-automation")));
        var d=(Map<?,?>)snapshot(f).items().stream().filter(i->i.kind().equals("SCENARIO")).findFirst().orElseThrow().definition();assertFalse(json.writeValueAsString(d).contains(o.id()));
    }
    @Test void existingIdentityEnabledStateAndQueuedManifestStayLocal() throws Exception {
        var f=fixture();seed(f);var p=automation.profiles(f.actor(),f.project()).getFirst();automation.updateProfile(f.actor(),f.project(),p.id(),new UiAutomationService.ProfileRequest(p.name(),p.browser(),p.baseUrl(),p.viewportWidth(),p.viewportHeight(),true));
        var t=schedules.list(f.actor(),f.project()).getFirst();schedules.update(f.actor(),f.project(),t.id(),new UiAutomationScheduleService.ScheduleInput(t.scenarioId(),null,t.name(),t.cronExpression(),t.timezone(),120,2,10,true));
        var s=automation.scenarios(f.actor(),f.project()).getFirst();var e=automation.execute(f.actor(),f.project(),s.id(),120,null);var frozen=assets.findManifest(f.project(),e.id()).orElseThrow();
        apply(f,scene("场景","同步修改"),object("按钮","#changed"),item("PROFILE","浏览器",new Profile("chromium","https://ui.example.test/orders",1440,900)),timer("0 0 0 2 1 ? 2099",null));
        var kept=automation.scenarios(f.actor(),f.project()).getFirst();assertEquals(s.id(),kept.id());assertEquals(s.createdAt(),kept.createdAt());assertEquals(2,kept.version());assertEquals(p.id(),kept.browserProfileId());assertTrue(automation.profiles(f.actor(),f.project()).getFirst().enabled());assertTrue(schedules.list(f.actor(),f.project()).getFirst().enabled());assertEquals(frozen,assets.findManifest(f.project(),e.id()).orElseThrow());assertEquals(1,snapshot(f).excludedExecutions());
        var trigger=quartz.getTrigger(new TriggerKey(t.id(),"testhub-ui-automation"));assertEquals("0 0 0 2 1 ? 2099",((CronTrigger)trigger).getCronExpression());
    }
    @Test void derivedObjectSelectorIsNotASeparateScenarioEdit() {
        var f=fixture();seed(f);var before=snapshot(f).items().stream().filter(i->i.kind().equals("SCENARIO")).findFirst().orElseThrow();apply(f,object("按钮","#new"));assertEquals(before,snapshot(f).items().stream().filter(i->i.kind().equals("SCENARIO")).findFirst().orElseThrow());
    }
    @Test void parallelAndLostResponseReplayNeverDuplicatesOrOverwritesLaterEdits() throws Exception {
        var f=fixture();var r=request(f,profile("浏览器"));try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var receipt=sync.apply(f.actor(),f.project(),r);apply(f,item("PROFILE","浏览器",new Profile("firefox","https://ui.example.test",1200,800)));assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertEquals(1,automation.profiles(f.actor(),f.project()).size());assertEquals(2,automation.profiles(f.actor(),f.project()).getFirst().version());assertEquals("firefox",automation.profiles(f.actor(),f.project()).getFirst().browser());
    }
    @Test void staleSnapshotsCaseAndDatabaseCollationsRejectWholeBatch() {
        var f=fixture();apply(f,profile("QA"));var r=request(f,object("NEW","#x"));apply(f,object("CHANGED","#y"));assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r)).getStatusCode().value());assertThrows(ResponseStatusException.class,()->apply(f,profile("qa"),object("不可出现","#z")));
        if(FunctionalDatabaseProfile.mysql()) {apply(f,profile("café"));var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,profile("cafe"),object("不可出现","#z"))).getStatusCode().value());assertEquals(before,snapshot(f));}
    }
    @Test void functionalPlanNamesMustExistAndUseTargetIdentity() {
        var f=fixture();var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,profile("浏览器"),object("按钮","#x"),scene("场景",""),timer("0 0 0 1 1 ? 2099","PLAN-UI"))).getStatusCode().value());assertEquals(before,snapshot(f));
        var plan=functional.createPlan(f.actor(),f.project(),new FunctionalTestExecutionService.PlanInput(null,"PLAN-UI","关联计划","DRAFT","",List.of()));seed(f);apply(f,timer("0 0 0 1 1 ? 2099","PLAN-UI"));assertEquals(plan.id(),schedules.list(f.actor(),f.project()).getFirst().functionalPlanId());
    }
    @Test void auditRollbackCannotChangeQuartzOrPartialVersionsAndReceipts() throws Exception {
        var f=fixture();seed(f);var t=schedules.list(f.actor(),f.project()).getFirst();schedules.update(f.actor(),f.project(),t.id(),new UiAutomationScheduleService.ScheduleInput(t.scenarioId(),null,t.name(),t.cronExpression(),t.timezone(),120,2,10,true));var before=snapshot(f);
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("UI_AUTOMATION_SYNCED"),anyString(),anyString(),anyMap());
        try {assertThrows(IllegalStateException.class,()->apply(f,scene("场景","不能写"),timer("0 0 0 2 1 ? 2099",null)));assertEquals(before,snapshot(f));assertEquals(t.cronExpression(),((CronTrigger)quartz.getTrigger(new TriggerKey(t.id(),"testhub-ui-automation"))).getCronExpression());}finally{reset(audit);}
    }
    @Test void unknownFieldsScriptStepsInvalidCronAndForeignReferencesNeverWrite() throws Exception {
        var f=fixture();var r=request(f,profile("浏览器"));var body=json.writeValueAsString(r);assertThrows(ResponseStatusException.class,()->sync.decode((body+" {}").getBytes()));
        var bad=json.readTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("operations").get(0).path("item").path("definition")).put("enabled",true);assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),sync.decode(json.writeValueAsBytes(bad))));
        assertThrows(ResponseStatusException.class,()->apply(f,timer("invalid",null)));assertThrows(ResponseStatusException.class,()->apply(f,scene("场景","缺少引用")));assertTrue(snapshot(f).items().isEmpty());
        var s=json.valueToTree(scene("场景",""));((com.fasterxml.jackson.databind.node.ObjectNode)s.path("definition").path("steps").get(0)).put("type","SCRIPT");assertThrows(ResponseStatusException.class,()->apply(f,json.treeToValue(s,Item.class)));
    }
    @Test void roleProjectActorCsrfAndTransferBodyLimitAreEnforced() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/ui-automation/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/ui-automation/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,profile("浏览器")));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[UiAutomationSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void perKindCountMergeAndFinalBytesHaveAtomicBounds() {
        var f=fixture();var items=new ArrayList<Item>();for(int n=0;n<100;n++)items.add(profile("P"+n));apply(f,items.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,profile("OVER")));assertEquals(before,snapshot(f));
        var g=fixture();var variables=new HashMap<String,String>();for(int n=0;n<50;n++)variables.put("v"+n,"x".repeat(4096));items.clear();items.add(profile("浏览器"));for(int n=0;n<16;n++)items.add(item("SCENARIO","S"+n,new Scenario("浏览器","","STOP",variables,List.of(new Step("open","NAVIGATE","打开",null,null,null,"/",null,10000)))));assertThrows(ResponseStatusException.class,()->apply(g,items.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
    }
    @Test void activeProfileTargetUpdatesRequireExistingAuthorityAndRollbackEveryKind() {
        var f=fixture();seed(f);var p=automation.profiles(f.actor(),f.project()).getFirst();automation.updateProfile(f.actor(),f.project(),p.id(),new UiAutomationService.ProfileRequest(p.name(),p.browser(),p.baseUrl(),1200,800,true));var before=snapshot(f);
        assertThrows(ResponseStatusException.class,()->apply(f,object("NEW","#x"),item("PROFILE","浏览器",new Profile("chromium","https://unapproved.example.test",1200,800))));assertEquals(before,snapshot(f));
        var target=fixture();apply(target,item("PROFILE","本地地址",new Profile("webkit","http://localhost:18088",1200,800)));var disabled=automation.profiles(target.actor(),target.project()).getFirst();assertFalse(disabled.enabled());apply(target,item("PROFILE","本地地址",new Profile("firefox","http://127.0.0.1:18089",1440,900)));assertFalse(automation.profiles(target.actor(),target.project()).getFirst().enabled());
    }
}
