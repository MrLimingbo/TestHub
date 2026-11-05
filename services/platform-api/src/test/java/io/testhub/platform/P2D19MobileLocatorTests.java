package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.mobileautomation.*;
import com.github.f4b6a3.ulid.UlidCreator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class P2D19MobileLocatorTests extends P1FlowTestSupport {
    @Autowired MobileAutomationService mobile;
    @Autowired MobileAutomationTransferService transfer;
    @Autowired MobileAutomationSyncService sync;
    record F(PlatformUserPrincipal actor,String project,MobileAutomationService.Device device,MobileAutomationService.Application app) { }
    List<String[]> matrix() { return List.of(new String[]{"ANDROID","ACCESSIBILITY_ID"},new String[]{"ANDROID","ID"},new String[]{"ANDROID","XPATH"},new String[]{"ANDROID","ANDROID_UIAUTOMATOR"},new String[]{"IOS","ACCESSIBILITY_ID"},new String[]{"IOS","ID"},new String[]{"IOS","XPATH"},new String[]{"IOS","IOS_PREDICATE"}); }
    F fixture(String platform) {var a=actor();var p=project(a);return new F(a,p,mobile.createDevice(a,p,new MobileAutomationService.DeviceRequest(unique(),platform,unique(),platform.equals("IOS")?"XCUITest":"UiAutomator2",Map.of(),true)),mobile.createApplication(a,p,new MobileAutomationService.ApplicationRequest(unique(),platform,"io.testhub.qa","1",null,true)));}
    String base(F f) {return "/api/v1/projects/"+f.project+"/mobile-automation";}
    MobileAutomationService.ElementRequest input(String platform,String strategy) {return new MobileAutomationService.ElementRequest(unique(),platform,strategy,"PRIVATE_LOCATOR","");}
    MobileAutomationService.Element element(F f,String strategy) {return mobile.createElement(f.actor,f.project,input(f.device.platform(),strategy));}
    MobileAutomationService.ScenarioRequest scenario(F f,String element) {return new MobileAutomationService.ScenarioRequest(f.device.id(),f.app.id(),unique(),"","STOP",Map.of(),List.of(new MobileAutomationService.Step("tap","TAP","tap",element,null,null,1000)));}
    MobileAutomationService.Scenario scene(F f,String element) {return mobile.createScenario(f.actor,f.project,scenario(f,element));}
    Map<String,Long> counts() {var result=new LinkedHashMap<String,Long>();for(String t:List.of("mobile_automation_element","mobile_automation_device","mobile_automation_application","mobile_automation_scenario","mobile_automation_execution","mobile_automation_execution_manifest","mobile_automation_runner_outbox","mobile_automation_transfer_receipt","mobile_automation_sync_receipt","audit_log"))result.put(t,jdbc.sql("SELECT COUNT(*) FROM "+t).query(Long.class).single());return result;}
    int sendPost(F f,String path,Object body)throws Exception {return mvc.perform(post(base(f)+path).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse().getStatus();}
    void corrupt(F f,MobileAutomationService.Element e) {jdbc.sql("UPDATE mobile_automation_element SET locator_strategy=:s WHERE project_id=:p AND id=:id").param("s",f.device.platform().equals("IOS")?"ANDROID_UIAUTOMATOR":"IOS_PREDICATE").param("p",f.project).param("id",e.id()).update();}
    void rejected(Runnable call) {assertEquals(400,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());}

    @Test void B01_matrixCreate()throws Exception {var f=fixture("ANDROID");for(var m:matrix()){assertEquals(200,sendPost(f,"/elements",input(m[0],m[1])));assertTrue(mobile.elements(f.actor,f.project).stream().anyMatch(e->e.platform().equals(m[0])&&e.locatorStrategy().equals(m[1])));}}
    @Test void B02_invalidCreateAndUpdateAtomic()throws Exception {
        for(String p:List.of("ANDROID","IOS")){var f=fixture(p);var e=element(f,"ID");var before=counts();for(String strategy:List.of(p.equals("IOS")?"ANDROID_UIAUTOMATOR":"IOS_PREDICATE","UNKNOWN","")){
            assertEquals(400,sendPost(f,"/elements",input(p,strategy)));assertEquals(before,counts());
            var response=mvc.perform(put(base(f)+"/elements/"+e.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedUpdatedAt",e.updatedAt(),"element",input(p,strategy))))).andReturn().getResponse();
            assertEquals(400,response.getStatus());assertFalse(response.getContentAsString().contains("PRIVATE_LOCATOR"));assertEquals(e,mobile.elements(f.actor,f.project).getFirst());assertEquals(before,counts());
        }}
    }
    @Test void B03_legacyReadButScenarioWritesRejected()throws Exception {var f=fixture("ANDROID");var e=element(f,"ID");var s=scene(f,e.id());corrupt(f,e);assertEquals("IOS_PREDICATE",mobile.elements(f.actor,f.project).getFirst().locatorStrategy());var before=counts();assertEquals(400,sendPost(f,"/scenarios",scenario(f,e.id())));var response=mvc.perform(put(base(f)+"/scenarios/"+s.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedVersion",s.version(),"scenario",scenario(f,e.id()))))).andReturn();assertEquals(400,response.getResponse().getStatus());assertEquals(before,counts());assertEquals(s,mobile.scenarios(f.actor,f.project).getFirst());}
    @Test void B04_legacyNewQueueRejectedWithoutAssets(){for(String p:List.of("ANDROID","IOS")){var f=fixture(p);var e=element(f,"ID");var s=scene(f,e.id());corrupt(f,e);var before=counts();rejected(()->mobile.execute(f.actor,f.project,s.id(),300));assertEquals(before,counts());assertEquals(s,mobile.scenarios(f.actor,f.project).getFirst());}}
    @Test void B05_matrixQueuesFrozenManifest(){for(var m:matrix()){var f=fixture(m[0]);var e=element(f,m[1]);var s=scene(f,e.id());var x=mobile.execute(f.actor,f.project,s.id(),300);var runtime=mobile.runtimeConfiguration("qa",x.taskId(),x.id());assertEquals(m[0],runtime.manifest().device().platform());assertEquals(m[1],runtime.manifest().scenario().steps().getFirst().locatorStrategy());}}
    @Test void B06_badQueuedManifestRejectedBeforeClaim()throws Exception {
        for(String p:List.of("ANDROID","IOS")){var f=fixture(p);var e=element(f,"ID");var s=scene(f,e.id());var x=mobile.execute(f.actor,f.project,s.id(),300);String original=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",x.id()).query(String.class).single();var before=counts();
            for(String bad:List.of("cross","unknown","missingStrategy","missingPlatform","unknownPlatform","missingValue")){ObjectNode manifest=(ObjectNode)json.readTree(original);var step=(ObjectNode)manifest.path("scenario").path("steps").get(0);var device=(ObjectNode)manifest.path("device");switch(bad){case "cross"->step.put("locatorStrategy",p.equals("IOS")?"ANDROID_UIAUTOMATOR":"IOS_PREDICATE");case "unknown"->step.put("locatorStrategy","UNKNOWN");case "missingStrategy"->step.remove("locatorStrategy");case "missingPlatform"->device.remove("platform");case "unknownPlatform"->device.put("platform","UNKNOWN");case "missingValue"->step.remove("locatorValue");default->throw new AssertionError();}
                String raw=json.writeValueAsString(manifest);jdbc.sql("UPDATE mobile_automation_execution_manifest SET content=CAST(:v AS JSON) WHERE execution_id=:id").param("v",raw).param("id",x.id()).update();rejected(()->mobile.runtimeConfiguration("qa",x.taskId(),x.id()));assertEquals("QUEUED",mobile.executions(f.actor,f.project).getFirst().status());assertEquals(before,counts());
            }
        }
    }
    @Test void B07_frozenValidManifestIgnoresLaterSourceEdit(){var f=fixture("ANDROID");var e=element(f,"ID");var s=scene(f,e.id());var x=mobile.execute(f.actor,f.project,s.id(),300);String before=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",x.id()).query(String.class).single();corrupt(f,e);assertEquals("ID",mobile.runtimeConfiguration("qa",x.taskId(),x.id()).manifest().scenario().steps().getFirst().locatorStrategy());assertEquals(before,jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:id").param("id",x.id()).query(String.class).single());}
    @Test void B08_transferMatrixAndLaterInvalidAtomic()throws Exception {
        var source=fixture("ANDROID");for(var m:matrix())mobile.createElement(source.actor,source.project,input(m[0],m[1]));var snapshot=transfer.export(source.actor,source.project);var dest=fixture("ANDROID");assertEquals(200,sendPost(dest,"/transfer",new MobileAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),dest.actor.userId(),snapshot)));assertEquals(8,mobile.elements(dest.actor,dest.project).size());
        for(String p:List.of("ANDROID","IOS")){var target=fixture(p);var elements=new ArrayList<>(snapshot.elements());elements.add(new MobileAutomationTransferService.Element(UlidCreator.getUlid().toString(),unique(),p,p.equals("IOS")?"ANDROID_UIAUTOMATOR":"IOS_PREDICATE","PRIVATE_LOCATOR",""));var bad=new MobileAutomationTransferService.Snapshot(snapshot.version(),snapshot.sourceProjectId(),snapshot.devices(),snapshot.applications(),elements,snapshot.scenarios());var before=counts();assertEquals(400,sendPost(target,"/transfer",new MobileAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),bad)));assertEquals(before,counts());}
    }
    @Test void B09_syncMatrixAndLaterInvalidAtomic()throws Exception {
        var source=fixture("ANDROID");for(var m:matrix())mobile.createElement(source.actor,source.project,input(m[0],m[1]));var items=sync.snapshot(source.actor,source.project,source.actor.userId()).items().stream().filter(i->i.kind().equals("ELEMENT")).toList();var target=fixture("ANDROID");var rev=sync.snapshot(target.actor,target.project,target.actor.userId()).revision();var ops=items.stream().map(i->new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),i)).toList();assertEquals(200,sendPost(target,"/sync",new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),rev,ops)));assertEquals(8,mobile.elements(target.actor,target.project).size());
        for(String p:List.of("ANDROID","IOS")){var dest=fixture(p);var definition=new LinkedHashMap<>(items.getFirst().definition());definition.put("platform",p);definition.put("locatorStrategy",p.equals("IOS")?"ANDROID_UIAUTOMATOR":"IOS_PREDICATE");var invalid=new MobileAutomationSyncService.Item("ELEMENT",unique(),definition);var batch=List.of(new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),items.getFirst()),new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),invalid));var before=counts();assertEquals(400,sendPost(dest,"/sync",new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),dest.actor.userId(),sync.snapshot(dest.actor,dest.project,dest.actor.userId()).revision(),batch)));assertEquals(before,counts());}
    }
    @Test void B10_permissionAndStaleTimestamp()throws Exception {var f=fixture("ANDROID");var e=element(f,"ID");var other=fixture("IOS");var request=Map.of("expectedUpdatedAt",e.updatedAt().minusSeconds(1),"element",input("ANDROID","ID"));var before=counts();assertEquals(409,mvc.perform(put(base(f)+"/elements/"+e.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(post(base(f)+"/elements").with(user(other.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("ANDROID","ID")))).andReturn().getResponse().getStatus());assertEquals(before,counts());}
}
