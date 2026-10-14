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
import io.testhub.platform.functional.FunctionalDefectLinkSyncService.*;
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
@AutoConfigureMockMvc @ActiveProfiles(resolver=FunctionalDatabaseProfile.class) @DirtiesContext
class FunctionalDefectLinkSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"defect-link-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"defect-link-sync-fixture");}
    }
    @Autowired RegistrationService registration; @Autowired AccessService access;
    @Autowired FunctionalDefectLinkSyncService sync; @Autowired FunctionalAssetService assets;
    @Autowired FunctionalDefectService defects; @Autowired FunctionalTestExecutionService executions;
    @Autowired ObjectMapper json; @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project,FunctionalRequirement requirement,FunctionalCase testcase,FunctionalTestPlan plan,FunctionalDefect defect) { }
    Fixture fixture() {
        var name="link-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","关联验收","Sync-fixture-2026!"));var c=access.context(actor);var p=c.projects().get(c.organizations().getFirst().id()).getFirst().id();
        var rf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("需求",null,0,"REQUIREMENTS"));var cf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));
        var req=assets.createRequirement(actor,p,new FunctionalAssetService.RequirementInput(rf.id(),"REQ-1","需求","P1","APPROVED","原正文"));
        var tc=assets.createCase(actor,p,new FunctionalAssetService.CaseInput(cf.id(),req.id(),"CASE-1","用例","P1","READY","SECURITY","前置","步骤","结果"));
        var plan=executions.createPlan(actor,p,new FunctionalTestExecutionService.PlanInput(null,"PLAN-1","计划","READY","原说明",List.of(tc.id())));
        var d=defects.create(actor,p,new FunctionalDefectService.DefectInput(null,"BUG-1","原缺陷",null,"OTHER","",List.of(),List.of(),List.of(actor.userId()),"CRITICAL","P0","CLOSED",true,actor.userId(),"FIXED","TESTHUB",null,"重现步骤"));
        return new Fixture(actor,p,req,tc,plan,d);
    }
    Item item(String code,List<String> requirements,List<String> cases) {return new Item("DEFECT",code,Map.of("requirements",requirements,"cases",cases));}
    Item attached() {return item("BUG-1",List.of("REQ-1"),List.of("CASE-1"));}
    Item clear() {return item("BUG-1",List.of(),List.of());}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    Receipt apply(Fixture f,Item... items) {return sync.apply(f.actor(),f.project(),request(f,items));}
    List<FunctionalDefectLink> links(Fixture f) {return defects.links(f.actor(),f.project(),f.defect().id());}
    List<Map<String,Object>> defectRows(Fixture f) {return jdbc.sql("SELECT * FROM functional_defect WHERE project_id=:p").param("p",f.project()).query().listOfRows();}
    @Test void mapsCodesToTargetIdsAndPreservesWorkflowPeopleAssetsAndRunLinks() throws Exception {
        var source=fixture();var target=fixture();var run=executions.createRun(target.actor(),target.project(),target.plan().id(),"保留历史");var rc=executions.listRunCases(target.actor(),target.project(),run.id()).getFirst();var steps=executions.listRunSteps(target.actor(),target.project(),rc.id());
        var history=defects.link(target.actor(),target.project(),target.defect().id(),new FunctionalDefectService.DefectLinkInput("RUN_CASE",rc.id()));var rows=defectRows(target);
        apply(source,attached());apply(target,snapshot(source).items().toArray(Item[]::new));
        assertEquals(snapshot(source).items(),snapshot(target).items());assertNotEquals(source.testcase().id(),target.testcase().id());assertNotEquals(source.defect().id(),target.defect().id());assertEquals(rows,defectRows(target));
        assertEquals(target.requirement(),assets.listRequirements(target.actor(),target.project(),null).getFirst());assertEquals(target.testcase(),assets.listCases(target.actor(),target.project(),null).getFirst());assertEquals(target.plan(),executions.listPlans(target.actor(),target.project(),null).getFirst());assertEquals(steps,executions.listRunSteps(target.actor(),target.project(),rc.id()));assertTrue(links(target).contains(history));
        for(var link:links(target).stream().filter(l->!l.assetType().equals("RUN_CASE")).toList()){assertEquals(target.actor().userId(),link.createdBy());assertEquals(link.assetType().equals("CASE")?target.testcase().id():target.requirement().id(),link.assetId());}
        assertFalse(json.writeValueAsString(snapshot(source).items()).contains(source.testcase().id()));
    }
    @Test void retainedLinksKeepActorTimeAndIdentityWhileActiveClearPreservesRunLinks() {
        var f=fixture();var run=executions.createRun(f.actor(),f.project(),f.plan().id(),"历史");var rc=executions.listRunCases(f.actor(),f.project(),run.id()).getFirst();var history=defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("RUN_CASE",rc.id()));
        apply(f,attached());var before=links(f);var rows=defectRows(f);apply(f,attached());assertEquals(before,links(f));apply(f,item("BUG-1",List.of(),List.of("CASE-1")));assertTrue(links(f).containsAll(before.stream().filter(l->!l.assetType().equals("REQUIREMENT")).toList()));apply(f,clear());assertEquals(List.of(history),links(f));assertEquals(rows,defectRows(f));
    }
    @Test void missingDefinitionsAndReferencesRejectWholeBatchWithoutCreatingAssets() {
        var f=fixture();var before=snapshot(f);
        for(var bad:List.of(item("BUG-MISSING",List.of(),List.of()),item("BUG-1",List.of("REQ-MISSING"),List.of()),item("BUG-1",List.of(),List.of("CASE-MISSING")))){assertThrows(ResponseStatusException.class,()->apply(f,bad));assertEquals(before,snapshot(f));}
        var foreign=fixture();assertThrows(ResponseStatusException.class,()->defects.replaceDefinitionLinks(f.actor(),f.project(),f.defect().id(),List.of(new FunctionalDefectService.DefectLinkInput("REQUIREMENT",foreign.requirement().id()))));assertEquals(before,snapshot(f));
        assertThrows(ResponseStatusException.class,()->apply(f,attached(),item("BUG-MISSING",List.of(),List.of())));assertEquals(before,snapshot(f));
    }
    @Test void staleAssociationAndRecreatedAssetIdentityRejectPendingWrites() {
        var f=fixture();var r=request(f,attached());defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("CASE",f.testcase().id()));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));
        var g=fixture();var stale=request(g,attached());executions.deletePlan(g.actor(),g.project(),g.plan().id());assets.deleteCase(g.actor(),g.project(),g.testcase().id());var recreated=assets.createCase(g.actor(),g.project(),new FunctionalAssetService.CaseInput(g.testcase().catalogId(),g.requirement().id(),"CASE-1","用例","P1","READY","SECURITY","前置","步骤","结果"));assertNotEquals(g.testcase().id(),recreated.id());assertThrows(ResponseStatusException.class,()->sync.apply(g.actor(),g.project(),stale));assertTrue(links(g).isEmpty());
    }
    @Test void sameContentAssociationRecreationChangesRevisionWithoutModifyingDefect() {
        var f=fixture();apply(f,attached());var before=snapshot(f);var rows=defectRows(f);var old=links(f).stream().filter(l->l.assetType().equals("CASE")).findFirst().orElseThrow();defects.unlink(f.actor(),f.project(),f.defect().id(),old.id());defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("CASE",f.testcase().id()));assertEquals(before.items(),snapshot(f).items());assertNotEquals(before.revision(),snapshot(f).revision());assertEquals(rows,defectRows(f));
    }
    @Test void durableParallelReplayReturnsCommittedReferenceIdsAndDoesNotUndoLaterEdits() throws Exception {
        var f=fixture();var r=request(f,attached());Receipt receipt;try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));receipt=a.get(20,TimeUnit.SECONDS);assertEquals(receipt,b.get(20,TimeUnit.SECONDS));}
        assertEquals(f.testcase().id(),receipt.referenceIds().get("CASE\ncase-1"));apply(f,clear());executions.deletePlan(f.actor(),f.project(),f.plan().id());assets.deleteCase(f.actor(),f.project(),f.testcase().id());assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(f.testcase().catalogId(),null,"CASE-1","新用例","P1","READY","FUNCTIONAL","","步骤","结果"));assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertTrue(links(f).isEmpty());
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),clear())))));
    }
    @Test void auditFailureRollsBackLinkChangesAndReceiptTogether() {
        var f=fixture();var before=snapshot(f);var r=request(f,attached());doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_DEFECT_LINK_SYNCED"),anyString(),anyString(),anyMap());
        try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_defect_link_sync_receipt WHERE request_key=:k").param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void strictJsonRejectsWorkflowRunLinksDuplicateOperationsAndUnsortedCodes() throws Exception {
        var f=fixture();var before=snapshot(f);for(var bad:List.of(new Item("DEFECT","BUG-1",Map.of("requirements",List.of("REQ-B","REQ-A"),"cases",List.of())),new Item("DEFECT","BUG-1",Map.of("requirements",List.of("REQ-1","REQ-1"),"cases",List.of())),new Item("DEFECT","BUG-1",Map.of("requirements",List.of(),"cases",List.of(),"runCases",List.of("RUN-1"))),new Item("DEFECT","BUG-1",Map.of("requirements",List.of(),"cases",List.of(),"status","CLOSED")),item("bug-1",List.of(),List.of()),new Item("RUN_CASE","BUG-1",Map.of("requirements",List.of(),"cases",List.of())))){assertThrows(ResponseStatusException.class,()->apply(f,bad));assertEquals(before,snapshot(f));}
        assertThrows(ResponseStatusException.class,()->apply(f,clear(),attached()));var r=request(f,attached());assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(r)+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(ResponseStatusException.class,()->defects.replaceDefinitionLinks(f.actor(),f.project(),f.defect().id(),List.of(new FunctionalDefectService.DefectLinkInput("RUN_CASE",f.testcase().id()))));
    }
    @Test void rolesProjectActorCsrfAndWireLimitGuardBothDirections() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/defect-links/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get(url.replace(f.project(),other.project())).param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,attached()));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalDefectLinkSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void projectLockSerializesManualLinkChangesAndSyncRechecksAfterWaiting() throws Exception {
        var f=fixture();var r=request(f,attached());var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(s->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("REQUIREMENT",f.requirement().id()));}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals(1,links(f).size());}
    }
    @Test void assetCountAndPerDefectReferenceBoundsRejectBeforeChangingExistingData() {
        var f=fixture();for(int n=1;n<100;n++)assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(f.testcase().catalogId(),null,"C-"+n,"用例","P1","READY","FUNCTIONAL","","步骤","结果"));assertEquals(102,snapshot(f).referenceIds().size());var before=snapshot(f);var tooMany=java.util.stream.IntStream.range(0,101).mapToObj(n->"C-"+n).sorted().toList();assertThrows(ResponseStatusException.class,()->apply(f,item("BUG-1",List.of(),tooMany)));assertEquals(before,snapshot(f));assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(f.testcase().catalogId(),null,"C-OVER","用例","P1","READY","FUNCTIONAL","","步骤","结果"));assertThrows(ResponseStatusException.class,()->snapshot(f));
    }
    @Test void legacyDanglingReferencesBlockOnlyAffectedDefectAndSnapshotNeverDeletesThem() {
        var f=fixture();var extra=assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(f.testcase().catalogId(),null,"CASE-EXTRA","临时用例","P1","READY","FUNCTIONAL","","步骤","结果"));
        var link=defects.link(f.actor(),f.project(),f.defect().id(),new FunctionalDefectService.DefectLinkInput("CASE",extra.id()));assets.deleteCase(f.actor(),f.project(),extra.id());
        var other=defects.create(f.actor(),f.project(),new FunctionalDefectService.DefectInput(null,"BUG-2","其他缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MINOR","P2","OPEN",false,null,null,"TESTHUB",null,"步骤"));
        var before=links(f);var snap=snapshot(f);assertEquals(List.of("BUG-1"),snap.unavailable());assertEquals(before,links(f));assertThrows(ResponseStatusException.class,()->apply(f,item("BUG-2",List.of(),List.of("CASE-1")),attached()));assertTrue(defects.links(f.actor(),f.project(),other.id()).isEmpty());
        apply(f,item("BUG-2",List.of(),List.of("CASE-1")));assertEquals(before,links(f));assertEquals(1,defects.links(f.actor(),f.project(),other.id()).size());defects.unlink(f.actor(),f.project(),f.defect().id(),link.id());assertTrue(snapshot(f).unavailable().isEmpty());apply(f,attached());assertEquals(2,links(f).size());
    }

}
