package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.mobileautomation.MobileAutomationService;
import io.testhub.platform.mobileautomation.MobileAutomationTransferService;
import io.testhub.platform.mobileautomation.MobileAutomationTransferService.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
class MobileAutomationTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"mobile-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa"); registry.add("spring.datasource.password",()->"mobile-transfer-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired MobileAutomationService mobile;
    @Autowired MobileAutomationTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="mobile-transfer-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","移动端迁移测试","Transfer-fixture-2026!"));
        var context=access.context(actor); return new Fixture(actor,context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    String file(Fixture f) {
        String id=UlidCreator.getUlid().toString(),org=access.context(f.actor()).organizations().getFirst().id();
        jdbc.sql("INSERT INTO file_object(id,organization_id,project_id,object_key,original_name,content_type,size_bytes,uploaded_by) VALUES(:id,:org,:project,:key,'qa.apk','application/octet-stream',1,:user)")
                .param("id",id).param("org",org).param("project",f.project()).param("key",id).param("user",f.actor().userId()).update(); return id;
    }
    Snapshot source(Fixture f) {
        for(String platform:List.of("ANDROID","IOS")) {
            var d=mobile.createDevice(f.actor(),f.project(),new MobileAutomationService.DeviceRequest(platform,platform,"qa-"+platform,platform.equals("IOS")?"XCUITest":"UiAutomator2",Map.of("appium:noReset","true"),true));
            mobile.updateDevice(f.actor(),f.project(),d.id(),1,new MobileAutomationService.DeviceRequest(d.name(),d.platform(),d.udid(),d.automationName(),Map.of("appium:noReset","false"),true));
            var a=mobile.createApplication(f.actor(),f.project(),new MobileAutomationService.ApplicationRequest(platform,platform,"io.testhub.qa","1.0",file(f),true));
            mobile.updateApplication(f.actor(),f.project(),a.id(),1,new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),"2.0",a.appFileId(),true));
            var e=mobile.createElement(f.actor(),f.project(),new MobileAutomationService.ElementRequest(platform,platform,"ACCESSIBILITY_ID","qa-input","说明"));
            var steps=List.of(new MobileAutomationService.Step("launch","LAUNCH","启动",null,"","",10000),
                    new MobileAutomationService.Step("input","INPUT","填写",e.id(),"{{order}}","",10000),
                    new MobileAutomationService.Step("tap","TAP","点击",e.id(),"","",10000),
                    new MobileAutomationService.Step("visible","ASSERT_VISIBLE","可见",e.id(),"","",10000),
                    new MobileAutomationService.Step("text","ASSERT_TEXT","断言",e.id(),"","{{order}}",10000),
                    new MobileAutomationService.Step("wait","WAIT","等待",null,"","",20),
                    new MobileAutomationService.Step("shot","SCREENSHOT","截图",null,"","",10000));
            var s=mobile.createScenario(f.actor(),f.project(),new MobileAutomationService.ScenarioRequest(d.id(),a.id(),platform,"第一版","STOP",Map.of("order","A1"),steps));
            mobile.updateScenario(f.actor(),f.project(),s.id(),1,new MobileAutomationService.ScenarioRequest(d.id(),a.id(),platform,"第二版","CONTINUE",s.variables(),steps));
        }
        return transfer.export(f.actor(),f.project());
    }
    ImportRequest request(Fixture f,Snapshot snapshot) { return new ImportRequest(UUID.randomUUID().toString(),f.actor().userId(),snapshot); }
    int count(String table,Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:project").param("project",f.project()).query(Integer.class).single(); }
    void empty(Fixture f) { for(String table:List.of("mobile_automation_device","mobile_automation_application","mobile_automation_element","mobile_automation_scenario","mobile_automation_execution","mobile_automation_transfer_receipt")) assertEquals(0,count(table,f),table); }
    @Test void copiesBothPlatformsCurrentVersionsOrderedStepsAndRemapsReferencesWithoutFilesOrExecution() {
        var local=fixture(); var target=fixture(); var original=source(local); var receipt=transfer.transfer(target.actor(),target.project(),request(target,original));
        assertEquals(2,receipt.devices()); assertEquals(2,receipt.applications()); assertEquals(2,receipt.elements()); assertEquals(2,receipt.scenarios()); assertEquals(14,receipt.steps()); assertEquals(2,receipt.detachedFiles());
        var result=mobile.snapshot(target.actor(),target.project());
        for(var scenario:result.scenarios()) {
            var device=result.devices().stream().filter(d->d.id().equals(scenario.deviceId())).findFirst().orElseThrow();
            var app=result.applications().stream().filter(a->a.id().equals(scenario.applicationId())).findFirst().orElseThrow();
            assertEquals(device.platform(),app.platform()); assertFalse(device.enabled()); assertFalse(app.enabled()); assertNull(app.appFileId());
            assertEquals(2,device.version()); assertEquals(2,app.version()); assertEquals(2,scenario.version()); assertEquals("CONTINUE",scenario.failureStrategy()); assertEquals(Map.of("order","A1"),scenario.variables());
            var old=original.scenarios().stream().filter(s->s.name().equals(scenario.name())).findFirst().orElseThrow(); assertNotEquals(old.id(),scenario.id());
            assertEquals(old.steps().stream().map(MobileAutomationService.Step::id).toList(),scenario.steps().stream().map(MobileAutomationService.Step::id).toList());
            assertTrue(result.elements().stream().anyMatch(e->e.id().equals(scenario.steps().get(1).elementId())&&e.platform().equals(device.platform())));
            assertThrows(ResponseStatusException.class,()->mobile.execute(target.actor(),target.project(),scenario.id(),60));
        }
        assertEquals(0,count("file_object",target)); assertEquals(0,count("mobile_automation_execution",target)); assertEquals(original,transfer.export(local.actor(),local.project()));
    }
    @Test void retryAndNameOrUdidConflictsNeverOverwrite() {
        var local=fixture(); var target=fixture(); var snapshot=source(local); var request=request(target,snapshot); var receipt=transfer.transfer(target.actor(),target.project(),request);
        assertEquals(receipt,transfer.transfer(target.actor(),target.project(),request));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot))).getStatusCode().value());
        var changed=new Snapshot(1,snapshot.sourceProjectId(),snapshot.devices(),snapshot.applications(),snapshot.elements(),List.of());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),new ImportRequest(request.requestKey(),target.actor().userId(),changed))).getStatusCode().value());
        var other=fixture(); var d=snapshot.devices().getFirst(); mobile.createDevice(other.actor(),other.project(),new MobileAutomationService.DeviceRequest("另一个名称",d.platform(),d.udid(),d.automationName(),d.capabilities(),false));
        assertThrows(ResponseStatusException.class,()->transfer.transfer(other.actor(),other.project(),request(other,snapshot))); assertEquals(0,count("mobile_automation_application",other));
    }
    @Test void lateInvalidScenarioRollsBackAllAssetsAndReceipt() {
        var local=fixture(); var target=fixture(); var original=source(local); var s=original.scenarios().getFirst();
        var wrongApp=original.applications().stream().filter(a->!a.platform().equals(s.name())).findFirst().orElseThrow();
        var broken=new Scenario(s.id(),s.deviceId(),wrongApp.id(),s.name(),s.description(),s.failureStrategy(),s.version(),s.variables(),s.steps());
        var snapshot=new Snapshot(1,original.sourceProjectId(),original.devices(),original.applications(),original.elements(),List.of(broken));
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot))); empty(target);
        var dangling=new Snapshot(1,original.sourceProjectId(),original.devices(),original.applications(),List.of(),original.scenarios());
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,dangling))); empty(target);
    }
    @Test void editableImportsUseOptimisticVersionsAndNeverChangeFrozenManifest() {
        var f=fixture(); source(f); var state=mobile.snapshot(f.actor(),f.project()); var d=state.devices().getFirst(); var a=state.applications().stream().filter(item->item.platform().equals(d.platform())).findFirst().orElseThrow(); var s=state.scenarios().stream().filter(item->item.deviceId().equals(d.id())).findFirst().orElseThrow();
        var execution=mobile.execute(f.actor(),f.project(),s.id(),60);
        String frozen=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",execution.id()).query(String.class).single();
        var dRequest=new MobileAutomationService.DeviceRequest(d.name(),d.platform(),d.udid()+"-new",d.automationName(),d.capabilities(),false);
        assertEquals(3,mobile.updateDevice(f.actor(),f.project(),d.id(),2,dRequest).version());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->mobile.updateDevice(f.actor(),f.project(),d.id(),2,dRequest)).getStatusCode().value());
        var aRequest=new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),"3.0",null,false);
        assertEquals(3,mobile.updateApplication(f.actor(),f.project(),a.id(),2,aRequest).version());
        var sRequest=new MobileAutomationService.ScenarioRequest(s.deviceId(),s.applicationId(),s.name(),"第三版",s.failureStrategy(),s.variables(),s.steps());
        assertEquals(3,mobile.updateScenario(f.actor(),f.project(),s.id(),2,sRequest).version());
        assertThrows(ResponseStatusException.class,()->mobile.updateScenario(f.actor(),f.project(),s.id(),2,sRequest));
        var e=state.elements().getFirst(); var eRequest=new MobileAutomationService.ElementRequest(e.name(),e.platform(),e.locatorStrategy(),"new-locator","新说明");
        // A clock rollback or two writes in one millisecond must still invalidate the old token.
        var future=java.time.Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.sql("UPDATE mobile_automation_element SET updated_at=:future WHERE id=:id").param("future",java.sql.Timestamp.from(future)).param("id",e.id()).update();
        assertTrue(mobile.updateElement(f.actor(),f.project(),e.id(),future,eRequest).updatedAt().isAfter(future));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->mobile.updateElement(f.actor(),f.project(),e.id(),future,eRequest)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->mobile.updateElement(f.actor(),f.project(),e.id(),java.time.Instant.EPOCH,eRequest));
        assertEquals(frozen,jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",execution.id()).query(String.class).single());
    }
    @Test void rejectsCrossProjectFilesAndPlatformChanges() {
        var f=fixture(); var other=fixture(); source(f); var state=mobile.snapshot(f.actor(),f.project()); var d=state.devices().getFirst(); var a=state.applications().getFirst();
        assertThrows(ResponseStatusException.class,()->mobile.updateDevice(f.actor(),f.project(),d.id(),d.version(),new MobileAutomationService.DeviceRequest(d.name(),d.platform().equals("IOS")?"ANDROID":"IOS",d.udid(),"custom",d.capabilities(),true)));
        assertThrows(ResponseStatusException.class,()->mobile.updateApplication(f.actor(),f.project(),a.id(),a.version(),new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),a.versionName(),file(other),true)));
        assertThrows(ResponseStatusException.class,()->mobile.createApplication(f.actor(),f.project(),new MobileAutomationService.ApplicationRequest("跨项目文件",a.platform(),a.appIdentifier(),"",file(other),true)));
        assertEquals(2,mobile.applications(f.actor(),f.project()).size());
    }
    @Test void strictScopeActorProjectAndUnknownFieldsAreRejected() throws Exception {
        var local=fixture(); var target=fixture(); var snapshot=source(local); String route="/api/v1/projects/"+target.project()+"/mobile-automation/transfer";
        mvc.perform(get(route+"/capabilities").with(user(target.actor()))).andExpect(status().isOk());
        mvc.perform(get(route).with(user(local.actor()))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new ImportRequest(UUID.randomUUID().toString(),local.actor().userId(),snapshot)))).andExpect(status().isConflict());
        var body=json.valueToTree(request(target,snapshot)); ((com.fasterxml.jackson.databind.node.ObjectNode)body.get("snapshot")).putArray("history");
        mvc.perform(post(route).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isBadRequest()); empty(target);
    }
}
