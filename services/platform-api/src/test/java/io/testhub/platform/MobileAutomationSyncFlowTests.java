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
import io.testhub.platform.mobileautomation.*;
import io.testhub.platform.mobileautomation.MobileAutomationSyncService.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class MobileAutomationSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"mobile-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"mobile-sync-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired MobileAutomationSyncService sync;
    @Autowired MobileAutomationService automation;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {var a="mobile-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(a,a+"@example.test","同步验收","Sync-fixture-2026!"));var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());}
    Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    Item device(String name,String platform,String udid) {return item("DEVICE",name,new Device(platform,udid,platform.equals("IOS")?"XCUITest":"UiAutomator2",Map.of("appium:noReset","true")));}
    Item app(String platform) {return item("APPLICATION","应用",new Application(platform,"io.testhub.qa","1"));}
    Item element(String name,String platform,String locator) {return item("ELEMENT",name,new Element(platform,"ACCESSIBILITY_ID",locator,"对象说明"));}
    Item scene(String description) {return item("SCENARIO","场景",new Scenario("设备","应用",description,"CONTINUE",Map.of("value","测试值"),List.of(
        new Step("launch","LAUNCH","启动",null,"","",10000),new Step("input","INPUT","输入","按钮","${value}","",10000),new Step("tap","TAP","点击","按钮","","",10000),
        new Step("visible","ASSERT_VISIBLE","可见","按钮","","",10000),new Step("text","ASSERT_TEXT","文本","按钮","","${value}",10000),new Step("wait","WAIT","等待",null,"","",20),new Step("capture","SCREENSHOT","截图",null,"","",10000))));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    void seed(Fixture f,String platform) {apply(f,scene("原始"),element("按钮",platform,"submit"),app(platform),device("设备",platform,"qa-"+f.project()));}
    @Test void bothPlatformsRemapUnorderedGraphWithoutFilesActivationOrExecution() throws Exception {
        for(String platform:List.of("ANDROID","IOS")) {var f=fixture();seed(f,platform);var state=automation.snapshot(f.actor(),f.project());var s=state.scenarios().getFirst();
            assertFalse(state.devices().getFirst().enabled());assertFalse(state.applications().getFirst().enabled());assertNull(state.applications().getFirst().appFileId());assertEquals(1,s.version());assertEquals(7,s.steps().size());
            assertEquals(state.devices().getFirst().id(),s.deviceId());assertEquals(state.applications().getFirst().id(),s.applicationId());assertEquals(state.elements().getFirst().id(),s.steps().get(1).elementId());
            assertEquals(0,snapshot(f).excludedExecutions());assertEquals(0,snapshot(f).excludedFiles());assertTrue(state.executions().isEmpty());assertFalse(json.writeValueAsString(snapshot(f).items()).contains(s.deviceId()));}
    }
    @Test void existingIdsFilesEnabledFlagsAndQueuedManifestSurviveSync() {
        var f=fixture();seed(f,"ANDROID");var d=automation.devices(f.actor(),f.project()).getFirst();var a=automation.applications(f.actor(),f.project()).getFirst();
        String file=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        jdbc.sql("INSERT INTO file_object(id,organization_id,project_id,object_key,original_name,content_type,size_bytes,uploaded_by) VALUES(:id,:org,:project,:key,'qa.apk','application/octet-stream',1,:user)").param("id",file).param("org",a.organizationId()).param("project",f.project()).param("key","mobile-sync/"+file).param("user",f.actor().userId()).update();
        automation.updateDevice(f.actor(),f.project(),d.id(),d.version(),new MobileAutomationService.DeviceRequest(d.name(),d.platform(),d.udid(),d.automationName(),d.capabilities(),true));
        automation.updateApplication(f.actor(),f.project(),a.id(),a.version(),new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),a.versionName(),file,true));
        var s=automation.scenarios(f.actor(),f.project()).getFirst();var e=automation.execute(f.actor(),f.project(),s.id(),120);String frozen=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",e.id()).query(String.class).single();
        apply(f,scene("修改说明"),element("按钮","ANDROID","changed"),item("APPLICATION","应用",new Application("ANDROID","io.testhub.new","2")),device("设备","ANDROID","new-udid"));
        var state=automation.snapshot(f.actor(),f.project());assertEquals(s.id(),state.scenarios().getFirst().id());assertEquals(s.createdAt(),state.scenarios().getFirst().createdAt());assertEquals(2,state.scenarios().getFirst().version());assertEquals(3,state.devices().getFirst().version());assertEquals(3,state.applications().getFirst().version());assertTrue(state.devices().getFirst().enabled());assertTrue(state.applications().getFirst().enabled());assertEquals(file,state.applications().getFirst().appFileId());assertEquals(1,snapshot(f).excludedFiles());assertEquals(1,snapshot(f).excludedExecutions());assertEquals(frozen,jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",e.id()).query(String.class).single());
    }
    @Test void elementUpdateUsesMonotonicTokenWithoutChangingPortableScenario() {
        var f=fixture();seed(f,"ANDROID");var before=snapshot(f).items().stream().filter(i->i.kind().equals("SCENARIO")).findFirst().orElseThrow();var element=automation.elements(f.actor(),f.project()).getFirst();apply(f,element("按钮","ANDROID","next"));var after=automation.elements(f.actor(),f.project()).getFirst();assertTrue(after.updatedAt().isAfter(element.updatedAt()));assertEquals(before,snapshot(f).items().stream().filter(i->i.kind().equals("SCENARIO")).findFirst().orElseThrow());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->automation.updateElement(f.actor(),f.project(),element.id(),element.updatedAt(),new MobileAutomationService.ElementRequest(element.name(),element.platform(),element.locatorStrategy(),"stale",element.description()))).getStatusCode().value());
    }
    @Test void parallelReplayAndLostResponsesNeverDuplicateOrUndoLaterEdits() throws Exception {
        var f=fixture();var r=request(f,device("设备","ANDROID","qa"));try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var receipt=sync.apply(f.actor(),f.project(),r);apply(f,device("设备","ANDROID","new"));assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertEquals(1,automation.devices(f.actor(),f.project()).size());assertEquals("new",automation.devices(f.actor(),f.project()).getFirst().udid());assertEquals(2,automation.devices(f.actor(),f.project()).getFirst().version());
        var reused=new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),device("其他","ANDROID","other"))));assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),reused)).getStatusCode().value());
    }
    @Test void manualEditWaitsForSyncProjectLockAndThenRejectsStaleVersion() throws Exception {
        var f=fixture();seed(f,"ANDROID");var d=automation.devices(f.actor(),f.project()).getFirst();var request=request(f,device("设备","ANDROID","sync"));var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)) {var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            var a=pool.submit(()->tx.execute(status->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return sync.apply(f.actor(),f.project(),request);}));assertTrue(locked.await(10,TimeUnit.SECONDS));
            var b=pool.submit(()->automation.updateDevice(f.actor(),f.project(),d.id(),d.version(),new MobileAutomationService.DeviceRequest(d.name(),d.platform(),"manual",d.automationName(),d.capabilities(),true)));
            try {assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);var error=assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS));assertInstanceOf(ResponseStatusException.class,error.getCause());assertEquals("sync",automation.devices(f.actor(),f.project()).getFirst().udid());}
    }
    @Test void platformMismatchAndDuplicateUdidsRollbackEveryKind() {
        var f=fixture();seed(f,"ANDROID");var before=snapshot(f);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,device("设备","IOS","ios"),element("新元素","ANDROID","new"))).getStatusCode().value());assertEquals(before,snapshot(f));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,app("IOS"),element("新元素","ANDROID","new"))).getStatusCode().value());assertEquals(before,snapshot(f));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,device("另一个","ANDROID","qa-"+f.project()),element("新元素","ANDROID","new"))).getStatusCode().value());assertEquals(before,snapshot(f));
        var g=fixture();assertThrows(ResponseStatusException.class,()->apply(g,device("设备","ANDROID","qa"),app("IOS"),element("按钮","ANDROID","submit"),scene("bad")));assertTrue(snapshot(g).items().isEmpty());
    }
    @Test void auditFailureRollsBackVersionsDefinitionsAndReceipt() {
        var f=fixture();seed(f,"ANDROID");var before=snapshot(f);var request=request(f,scene("不能写"),element("新元素","ANDROID","new"));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("MOBILE_AUTOMATION_SYNCED"),anyString(),anyString(),anyMap());
        try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),request));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM mobile_automation_sync_receipt WHERE project_id=:p AND request_key=:k").param("p",f.project()).param("k",request.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void staleSnapshotsCaseAndMysqlCollationsRejectWholeBatch() {
        var f=fixture();apply(f,element("QA","ANDROID","one"));var request=request(f,element("NEW","ANDROID","two"));apply(f,element("CHANGED","ANDROID","three"));assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request)).getStatusCode().value());assertThrows(ResponseStatusException.class,()->apply(f,element("qa","ANDROID","four")));
        if(FunctionalDatabaseProfile.mysql()){apply(f,element("café","ANDROID","x"));var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,element("cafe","ANDROID","y"),element("不可出现","ANDROID","z"))).getStatusCode().value());assertEquals(before,snapshot(f));}
    }
    @Test void unknownFilesScriptsCapabilitiesAndReferencesRejectBeforeWrites() throws Exception {
        var f=fixture();var request=request(f,app("ANDROID"));var body=json.writeValueAsString(request);assertThrows(ResponseStatusException.class,()->sync.decode((body+" {}").getBytes()));
        var bad=json.readTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("operations").get(0).path("item").path("definition")).put("appFileId","01ARZ3NDEKTSV4RRFFQ69G5FAV");assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),sync.decode(json.writeValueAsBytes(bad))));
        assertThrows(ResponseStatusException.class,()->apply(f,item("DEVICE","设备",new Device("ANDROID","qa","UiAutomator2",Map.of("appium:app","/private/app.apk")))));assertThrows(ResponseStatusException.class,()->apply(f,scene("missing refs")));
        var s=json.valueToTree(scene(""));((com.fasterxml.jackson.databind.node.ObjectNode)s.path("definition").path("steps").get(0)).put("type","SCRIPT");assertThrows(ResponseStatusException.class,()->apply(f,json.treeToValue(s,Item.class)));assertTrue(snapshot(f).items().isEmpty());
    }
    @Test void projectActorRoleCsrfAndRequestLimitsAreEnforced() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/mobile-automation/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/mobile-automation/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,app("ANDROID")));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[MobileAutomationSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void perKindAndMergedByteBoundsLeaveNoPartialData() {
        var f=fixture();var items=new ArrayList<Item>();for(int n=0;n<100;n++)items.add(element("E"+n,"ANDROID","x"));apply(f,items.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,element("OVER","ANDROID","x")));assertEquals(before,snapshot(f));
        var g=fixture();var variables=new HashMap<String,String>();for(int n=0;n<50;n++)variables.put("v"+n,"x".repeat(4096));items.clear();items.add(device("设备","ANDROID","qa"));items.add(app("ANDROID"));for(int n=0;n<16;n++)items.add(item("SCENARIO","S"+n,new Scenario("设备","应用","","STOP",variables,List.of(new Step("launch","LAUNCH","启动",null,"","",10000)))));assertThrows(ResponseStatusException.class,()->apply(g,items.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
    }
}
