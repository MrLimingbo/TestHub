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
import io.testhub.platform.functional.*;
import io.testhub.platform.functional.FunctionalDefectExecutionSyncService.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
@AutoConfigureMockMvc @ActiveProfiles(resolver=FunctionalDatabaseProfile.class) @DirtiesContext
class FunctionalDefectExecutionSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"defect-execution-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"defect-execution-sync-fixture");}
    }
    @Autowired RegistrationService registration; @Autowired AccessService access;
    @Autowired FunctionalDefectExecutionSyncService sync; @Autowired FunctionalAssetService assets;
    @Autowired FunctionalDefectService defects; @Autowired FunctionalTestExecutionService executions;
    @Autowired ObjectMapper json; @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project,FunctionalRequirement requirement,FunctionalCase testcase,FunctionalTestPlan plan,FunctionalDefect defect) { }
    Fixture fixture() {
        var name="execution-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","执行关联验收","Sync-fixture-2026!"));var c=access.context(actor);var p=c.projects().get(c.organizations().getFirst().id()).getFirst().id();
        var rf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("需求",null,0,"REQUIREMENTS"));var cf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));
        var req=assets.createRequirement(actor,p,new FunctionalAssetService.RequirementInput(rf.id(),"REQ-1","需求","P1","APPROVED","原正文"));
        var tc=assets.createCase(actor,p,new FunctionalAssetService.CaseInput(cf.id(),req.id(),"CASE-1","用例","P1","READY","SECURITY","前置","步骤","结果"));
        var plan=executions.createPlan(actor,p,new FunctionalTestExecutionService.PlanInput(null,"PLAN-1","计划","READY","原说明",List.of(tc.id())));
        var d=defects.create(actor,p,new FunctionalDefectService.DefectInput(null,"BUG-1","原缺陷",null,"OTHER","",List.of(),List.of(),List.of(actor.userId()),"CRITICAL","P0","CLOSED",true,actor.userId(),"FIXED","TESTHUB",null,"重现步骤"));
        return new Fixture(actor,p,req,tc,plan,d);
    }
    Item item(String code,String... ids) {return new Item("DEFECT",code,Map.of("runCaseIds",Arrays.stream(ids).sorted().toList()));}
    Item clear() {return item("BUG-1");}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    Receipt apply(Fixture f,Item... items) {return sync.apply(f.actor(),f.project(),request(f,items));}
    FunctionalTestRunCase runCase(Fixture f) {var r=executions.createRun(f.actor(),f.project(),f.plan().id(),"同名批次");return executions.listRunCases(f.actor(),f.project(),r.id()).getFirst();}
    void link(Fixture f,String id) {defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("RUN_CASE",id));}
    List<FunctionalDefectLink> links(Fixture f) {return defects.links(f.actor(),f.project(),f.defect().id());}
    List<Map<String,Object>> history(Fixture f) {return jdbc.sql("SELECT rc.* FROM functional_test_run_case rc JOIN functional_test_run r ON r.id=rc.run_id WHERE r.project_id=:p ORDER BY rc.id").param("p",f.project()).query().listOfRows();}
    @Test void snapshotExposesScopedExecutionIdentityWithoutActorsNotesOrResults() throws Exception {
        var f=fixture();var rc=runCase(f);var outsider=fixture();var foreign=runCase(outsider);var snap=snapshot(f);assertEquals(1,snap.references().size());var ref=snap.references().getFirst();assertEquals(rc.id(),ref.id());assertEquals(f.testcase().id(),ref.caseId());assertEquals("CASE-1",ref.caseCode());assertEquals("PLAN-1",ref.planCode());var text=json.writeValueAsString(snap);for(var secret:List.of("updatedBy","createdBy","note","actualResult",foreign.id(),outsider.actor().userId()))assertFalse(text.contains(secret));
    }
    @Test void replaceOnlyRunCaseLinksKeepsProtectedDefectAndExecutionRows() {
        var f=fixture();var old=runCase(f);var target=runCase(f);link(f,old.id());for(var in:List.of(new FunctionalDefectService.DefectLinkInput("CASE",f.testcase().id()),new FunctionalDefectService.DefectLinkInput("REQUIREMENT",f.requirement().id())))defects.link(f.actor(),f.project(),f.defect().id(),in);var protectedLinks=links(f).stream().filter(l->!l.assetType().equals("RUN_CASE")).toList();var before=jdbc.sql("SELECT * FROM functional_defect WHERE id=:i").param("i",f.defect().id()).query().singleRow();var runs=history(f);var steps=executions.listRunSteps(f.actor(),f.project(),target.id());apply(f,item("BUG-1",target.id()));assertEquals(before,jdbc.sql("SELECT * FROM functional_defect WHERE id=:i").param("i",f.defect().id()).query().singleRow());assertEquals(runs,history(f));assertEquals(steps,executions.listRunSteps(f.actor(),f.project(),target.id()));assertEquals(protectedLinks,links(f).stream().filter(l->!l.assetType().equals("RUN_CASE")).toList());var added=links(f).stream().filter(l->l.assetType().equals("RUN_CASE")).findFirst().orElseThrow();assertEquals(target.id(),added.assetId());assertEquals(f.actor().userId(),added.createdBy());
    }
    @Test void noOpAndRetainedAssociationsKeepOriginalIdsTimesAndActors() {
        var f=fixture();var a=runCase(f);var b=runCase(f);link(f,a.id());var original=links(f).getFirst();apply(f,item("BUG-1",a.id()));assertEquals(List.of(original),links(f));apply(f,item("BUG-1",a.id(),b.id()));assertTrue(links(f).contains(original));apply(f,item("BUG-1",a.id()));assertEquals(List.of(original),links(f));
    }
    @Test void explicitClearRemovesOnlyExecutionAssociations() {
        var f=fixture();var rc=runCase(f);link(f,rc.id());defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("CASE",f.testcase().id()));var h=history(f);apply(f,clear());assertEquals(1,links(f).size());assertEquals("CASE",links(f).getFirst().assetType());assertEquals(h,history(f));
    }
    @Test void missingDefectAndForeignExecutionRejectWholeBatchWithoutCreatingHistory() {
        var f=fixture();var rc=runCase(f);var outsider=fixture();var foreign=runCase(outsider);var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,item("BUG-1",rc.id()),item("BUG-MISSING")));assertEquals(before,snapshot(f));assertThrows(ResponseStatusException.class,()->apply(f,item("BUG-1",foreign.id())));assertEquals(before,snapshot(f));assertEquals(1,history(f).size());
    }
    @Test void deletedPlanLeavesUnavailableLegacyLinkAndNeverSilentlyClearsIt() {
        var f=fixture();var rc=runCase(f);link(f,rc.id());var r=request(f,clear());executions.deletePlan(f.actor(),f.project(),f.plan().id());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(List.of("DEFECT\nbug-1"),snapshot(f).unavailable());assertThrows(ResponseStatusException.class,()->apply(f,clear()));assertEquals(rc.id(),links(f).getFirst().assetId());
    }
    @Test void ordinaryUnlinkAndSameCodeDefectRecreationInvalidateRevision() {
        var f=fixture();var rc=runCase(f);link(f,rc.id());var r=request(f,clear());defects.unlink(f.actor(),f.project(),f.defect().id(),links(f).getFirst().id());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));var stale=request(f,clear());defects.delete(f.actor(),f.project(),f.defect().id());defects.create(f.actor(),f.project(),new FunctionalDefectService.DefectInput(null,"BUG-1","新缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MAJOR","P1","OPEN",false,null,null,"TESTHUB",null,"步骤"));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),stale));
    }
    @Test void executionResultEditsDoNotInvalidateAssociationIdentityOrGetOverwritten() {
        var f=fixture();var rc=runCase(f);var r=request(f,item("BUG-1",rc.id()));executions.updateRunCase(f.actor(),f.project(),rc.id(),new FunctionalTestExecutionService.RunCaseInput("FAILED","保留新结果"));assertEquals(r.expectedRevision(),snapshot(f).revision());var h=history(f);sync.apply(f.actor(),f.project(),r);assertEquals(h,history(f));
    }
    @Test void durableParallelReceiptRetainsCommittedIdentityAndLaterManualEdit() throws Exception {
        var f=fixture();var rc=runCase(f);var r=request(f,item("BUG-1",rc.id()));Receipt receipt;try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));receipt=a.get(20,TimeUnit.SECONDS);assertEquals(receipt,b.get(20,TimeUnit.SECONDS));}assertEquals(f.defect().id(),receipt.results().getFirst().itemId());defects.unlink(f.actor(),f.project(),f.defect().id(),links(f).getFirst().id());assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertTrue(links(f).isEmpty());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),clear())))));
    }
    @Test void auditFailureRollsBackLinksAndReceiptAtomically() {
        var f=fixture();var rc=runCase(f);link(f,rc.id());var before=links(f);var r=request(f,clear());doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_DEFECT_EXECUTION_SYNCED"),anyString(),anyString(),anyMap());try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,links(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_defect_execution_sync_receipt WHERE request_key=:k").param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void strictProtocolRejectsExtraHistoryFieldsMalformedIdsAndUnsortedSets() throws Exception {
        var f=fixture();var before=snapshot(f);var bad=new ArrayList<Item>();bad.add(item("bug-1"));bad.add(item("BUG-1","invalid"));bad.add(item("BUG-1","Z".repeat(26),"Z".repeat(26)));bad.add(new Item("DEFECT","BUG-1",Map.of("runCaseIds",List.of("Z".repeat(26),"A".repeat(26)))));for(var field:List.of("status","title","note","createdBy","assigneeId")){var d=new HashMap<>(clear().definition());d.put(field,"protected");bad.add(new Item("DEFECT","BUG-1",d));}bad.add(new Item("DEFECT","BUG-1",Map.of()));for(var i:bad){assertThrows(ResponseStatusException.class,()->apply(f,i));assertEquals(before,snapshot(f));}assertThrows(ResponseStatusException.class,()->apply(f,clear(),clear()));assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(request(f,clear()))+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void rolesProjectActorCsrfAndPayloadLimitGuardBothDirections() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/defect-execution/sync";mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get(url.replace(f.project(),other.project())).param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());var body=json.writeValueAsBytes(request(f,clear()));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalDefectExecutionSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void planDeletionAndSyncShareLockAndRecheckAfterWaiting() throws Exception {
        var f=fixture();var rc=runCase(f);var r=request(f,item("BUG-1",rc.id()));var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(s->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}executions.deletePlan(f.actor(),f.project(),f.plan().id());return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertTrue(links(f).isEmpty());}
    }
    @Test void countLimitRejectsUnboundedProjectSnapshot() {
        var f=fixture();for(int n=1;n<=100;n++)defects.create(f.actor(),f.project(),new FunctionalDefectService.DefectInput(null,"B-"+n,"缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MAJOR","P1","OPEN",false,null,null,"TESTHUB",null,"步骤"));assertThrows(ResponseStatusException.class,()->snapshot(f));
    }
}
