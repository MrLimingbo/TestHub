package io.testhub.platform;

import io.testhub.platform.performance.*;
import io.testhub.platform.uiautomation.*;
import io.testhub.platform.extension.*;
import io.testhub.platform.account.PlatformUserPrincipal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class P2NormalizedKeysFlowTests extends P1FlowTestSupport {
    @Autowired PerformanceTestPlanService performance;
    @Autowired UiAutomationService ui;
    @Autowired ExtensionRegistryService extensions;
    @Autowired ExtensionRegistryRepository adapters;
    @Autowired io.testhub.platform.apidesign.ApiInterchangeService interchange;
    @Autowired io.testhub.platform.apidesign.ApiDesignService design;
    Map<String,String> collision(boolean reverse, boolean equal) {
        var result=new LinkedHashMap<String,String>();
        result.put(reverse?" key ":"key","one");result.put(reverse?"key":" key ",equal?"one":"two");return result;
    }
    PerformanceTestPlanService.PlanInput plan(Map<String,String> query,String url) {
        return json.convertValue(Map.of("name",unique(),"status","DRAFT","threadGroups",List.of(Map.of(
                "name","group","targetUrl",url,"loadModel","RAMP_UP","threadCount",1,"rampUpSeconds",0,
                "durationSeconds",10,"queryParameters",query))),PerformanceTestPlanService.PlanInput.class);
    }
    UiAutomationService.ScenarioRequest scenario(PlatformUserPrincipal actor,Map<String,String> variables) {
        var profile=ui.createTransferredProfile(actor,project(actor),new UiAutomationService.ProfileRequest(unique(),"CHROMIUM","https://example.test",1280,720,false));
        return new UiAutomationService.ScenarioRequest(profile.id(),unique(),"","STOP",variables,List.of(
                new UiAutomationStep("wait","WAIT","等待",null,null,null,null,null,1)));
    }
    ExtensionRegistryService.AdapterInput adapter(Map<String,String> config) {
        return new ExtensionRegistryService.AdapterInput("WECHAT_WORK",unique(),"https://example.test",config,false);
    }
    Map<String,String> adapterCollision(boolean reverse,boolean equal) {
        var value=new LinkedHashMap<String,String>();value.put(reverse?" webhookKey ":"webhookKey","one");value.put(reverse?"webhookKey":" webhookKey ",equal?"one":"two");return value;
    }
    void bad(Runnable work) { assertEquals(400,assertThrows(ResponseStatusException.class,work::run).getStatusCode().value()); }
    @Test @DisplayName("TC-P2-D17-01") void performanceRejectsTrimCollision() {var a=actor();bad(()->performance.create(a,project(a),plan(collision(false,false),"https://example.test")));assertTrue(performance.list(a,project(a)).isEmpty());}
    @Test @DisplayName("TC-P2-D17-02") void webRejectsTrimCollision() {var a=actor();var input=scenario(a,collision(false,false));bad(()->ui.createScenario(a,project(a),input));}
    @Test @DisplayName("TC-P2-D17-03") void adapterRejectsTrimCollision() {var a=admin();bad(()->extensions.saveAdapter(a,null,adapter(adapterCollision(false,false))));}
    @Test @DisplayName("TC-P2-D17-04") void collisionIsIndependentOfOrderAndValue() {var a=admin();for(boolean reverse:List.of(false,true))for(boolean equal:List.of(false,true)) {bad(()->performance.create(a,project(a),plan(collision(reverse,equal),"https://example.test")));var input=scenario(a,collision(reverse,equal));bad(()->ui.createScenario(a,project(a),input));bad(()->extensions.saveAdapter(a,null,adapter(adapterCollision(reverse,equal))));}}
    @Test @DisplayName("TC-P2-D17-05") void separateObjectsMayReuseKeys() {var a=actor();for(int i=0;i<2;i++){assertNotNull(performance.create(a,project(a),plan(Map.of("key","v"),"https://example.test")));assertNotNull(ui.createScenario(a,project(a),scenario(a,Map.of("key","v"))));}}
    @Test @DisplayName("TC-P2-D17-06") void caseRulesRemainDistinct() {var a=admin();assertNotNull(performance.create(a,project(a),plan(Map.of("Key","v","key","w"),"https://example.test")));assertNotNull(ui.createScenario(a,project(a),scenario(a,Map.of("Key","v","key","w"))));assertNotNull(extensions.saveAdapter(a,null,adapter(Map.of("webhookKey","v","webhookkey","w"))));}
    @Test @DisplayName("TC-P2-D17-07") void failedEditsKeepRowsCipherAndAudits() {
        var a=admin();var created=extensions.saveAdapter(a,null,adapter(Map.of("webhookKey","v")));var previous=adapters.findAdapter(created.id()).orElseThrow();long audit=auditCount();
        bad(()->extensions.saveAdapter(a,created.id(),adapter(adapterCollision(false,true))));assertEquals(previous,adapters.findAdapter(created.id()).orElseThrow());assertEquals(audit,auditCount());
        var saved=performance.create(a,project(a),plan(Map.of("key","old"),"https://example.test"));var broken=plan(collision(false,false),"https://example.test");
        var input=new PerformanceTestPlanService.PlanInput(broken.name(),broken.description(),broken.status(),broken.threadGroups(),saved.version());
        bad(()->performance.update(a,project(a),saved.id(),input));assertEquals(saved,performance.detail(a,project(a),saved.id()));
        var valid=scenario(a,Map.of("key","old"));var web=ui.createScenario(a,project(a),valid);long webAudit=auditCount();
        var badWeb=new UiAutomationService.ScenarioRequest(valid.browserProfileId(),valid.name(),valid.description(),valid.failureStrategy(),collision(false,false),valid.steps());
        bad(()->ui.updateScenario(a,project(a),web.id(),badWeb));assertEquals(web,ui.scenarios(a,project(a)).stream().filter(r->r.id().equals(web.id())).findFirst().orElseThrow());assertEquals(webAudit,auditCount());

    }
    @Test @DisplayName("TC-P2-D17-08") void validationAndAuthorizationStillApply() {var a=actor();bad(()->performance.create(a,project(a),plan(Map.of("bad key","v"),"https://example.test")));var input=scenario(a,Map.of("bad key","v"));bad(()->ui.createScenario(a,project(a),input));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->extensions.saveAdapter(a,null,adapter(adapterCollision(false,false))));}
    @Test @DisplayName("TC-P2-D17-09") void omittedConfigurationKeepsCipher() {var a=admin();var row=extensions.saveAdapter(a,null,adapter(Map.of("webhookKey","v")));String cipher=adapters.findAdapter(row.id()).orElseThrow().configurationCiphertext();extensions.saveAdapter(a,row.id(),adapter(null));assertEquals(cipher,adapters.findAdapter(row.id()).orElseThrow().configurationCiphertext());}
    @Test @DisplayName("TC-P2-D17-10") void repeatedUrlQueryRemainsLegal() throws Exception {var a=actor();String p=project(a);var c=design.createCatalog(a,p,new io.testhub.platform.apidesign.ApiDesignService.CatalogInput(unique(),null,0));var result=interchange.importCurl(a,p,new io.testhub.platform.apidesign.ApiInterchangeService.CurlImportInput(c.id(),unique(),"curl 'https://example.test/search?k=1&k=2'"));assertEquals(1,result.importedCount());var api=design.definitions(a,p,c.id()).getFirst();assertEquals(2,json.readTree(design.versions(a,p,api.id()).getFirst().requestQuery()).size());}
}
