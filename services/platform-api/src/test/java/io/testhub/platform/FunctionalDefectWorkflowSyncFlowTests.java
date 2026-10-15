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
import io.testhub.platform.functional.FunctionalDefectWorkflowSyncService.*;
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
class FunctionalDefectWorkflowSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"defect-workflow-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"defect-workflow-sync-fixture");}
    }
    @Autowired RegistrationService registration; @Autowired AccessService access;
    @Autowired FunctionalDefectWorkflowSyncService sync; @Autowired FunctionalAssetService assets;
    @Autowired FunctionalDefectService defects; @Autowired FunctionalTestExecutionService executions;
    @Autowired ObjectMapper json; @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project,FunctionalRequirement requirement,FunctionalCase testcase,FunctionalTestPlan plan,FunctionalDefect defect) { }
    Fixture fixture() {
        var name="workflow-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","工作流验收","Sync-fixture-2026!"));var c=access.context(actor);var p=c.projects().get(c.organizations().getFirst().id()).getFirst().id();
        var rf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("需求",null,0,"REQUIREMENTS"));var cf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));
        var req=assets.createRequirement(actor,p,new FunctionalAssetService.RequirementInput(rf.id(),"REQ-1","需求","P1","APPROVED","原正文"));
        var tc=assets.createCase(actor,p,new FunctionalAssetService.CaseInput(cf.id(),req.id(),"CASE-1","用例","P1","READY","SECURITY","前置","步骤","结果"));
        var plan=executions.createPlan(actor,p,new FunctionalTestExecutionService.PlanInput(null,"PLAN-1","计划","READY","原说明",List.of(tc.id())));
        var d=defects.create(actor,p,new FunctionalDefectService.DefectInput(null,"BUG-1","原缺陷",null,"OTHER","",List.of(),List.of(),List.of(actor.userId()),"CRITICAL","P0","CLOSED",true,actor.userId(),"FIXED","TESTHUB",null,"重现步骤"));
        return new Fixture(actor,p,req,tc,plan,d);
    }
    Item item(String code,String status,boolean confirmed,String resolution) {var d=new LinkedHashMap<String,Object>();d.put("status",status);d.put("confirmed",confirmed);d.put("resolution",resolution);return new Item("DEFECT",code,d);}
    Item open() {return item("BUG-1","OPEN",false,null);}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    Receipt apply(Fixture f,Item... items) {return sync.apply(f.actor(),f.project(),request(f,items));}
    FunctionalDefect row(Fixture f) {return defects.list(f.actor(),f.project()).stream().filter(d->d.id().equals(f.defect().id())).findFirst().orElseThrow();}
    Map<String,Object> protectedRow(Fixture f) {var d=new HashMap<>(jdbc.sql("SELECT * FROM functional_defect WHERE id=:i").param("i",f.defect().id()).query().singleRow());for(var k:List.of("status","confirmed","resolution","resolved_by","resolved_at","closed_by","closed_at","updated_by","updated_at"))d.remove(k);return d;}
    void manual(Fixture f,String status,boolean confirmed,String resolution) {var d=row(f);defects.update(f.actor(),f.project(),d.id(),new FunctionalDefectService.DefectInput(d.catalogId(),d.defectCode(),d.title(),d.dueDate(),d.bugType(),d.keywords(),d.browsers(),d.operatingSystems(),d.ccUserIds(),d.severity(),d.priority(),status,confirmed,d.assigneeId(),resolution,d.source(),d.externalUrl(),d.description()));}
    @Test void portableWorkflowMapsCodesButNeverCopiesSourcePeopleOrHistoricalTimes() throws Exception {
        var source=fixture();var target=fixture();apply(target,open());var before=protectedRow(target);
        apply(target,snapshot(source).items().toArray(Item[]::new));var d=row(target);
        assertEquals("CLOSED",d.status());assertTrue(d.confirmed());assertEquals("FIXED",d.resolution());
        assertNull(d.resolvedBy());assertNull(d.resolvedAt());assertNull(d.closedBy());assertNull(d.closedAt());
        assertEquals(before,protectedRow(target));assertEquals(target.actor().userId(),d.updatedBy());assertFalse(json.writeValueAsString(snapshot(source).items()).contains(source.actor().userId()));
        assertNotEquals(source.defect().id(),target.defect().id());assertEquals(target.defect().id(),snapshot(target).itemIds().get("DEFECT\nbug-1"));
    }
    @Test void unchangedWorkflowIsNoOpAndConfirmationOnlyKeepsGenuineLocalHistory() {
        var f=fixture();var before=row(f);apply(f,snapshot(f).items().toArray(Item[]::new));assertEquals(before,row(f));
        apply(f,item("BUG-1","CLOSED",false,"FIXED"));var d=row(f);assertFalse(d.confirmed());assertEquals(before.resolvedBy(),d.resolvedBy());assertEquals(before.resolvedAt(),d.resolvedAt());assertEquals(before.closedBy(),d.closedBy());assertEquals(before.closedAt(),d.closedAt());
    }
    @Test void reopeningClearsObsoleteHistoryAndDoesNotChangeAssignmentsOrDefinitionLinks() {
        var f=fixture();var run=executions.createRun(f.actor(),f.project(),f.plan().id(),"历史");var rc=executions.listRunCases(f.actor(),f.project(),run.id()).getFirst();
        for(var input:List.of(new FunctionalDefectService.DefectLinkInput("REQUIREMENT",f.requirement().id()),new FunctionalDefectService.DefectLinkInput("CASE",f.testcase().id()),new FunctionalDefectService.DefectLinkInput("RUN_CASE",rc.id())))defects.link(f.actor(),f.project(),f.defect().id(),input);
        var before=protectedRow(f);var links=defects.links(f.actor(),f.project(),f.defect().id());var steps=executions.listRunSteps(f.actor(),f.project(),rc.id());apply(f,open());var d=row(f);
        assertNull(d.resolution());assertNull(d.resolvedBy());assertNull(d.resolvedAt());assertNull(d.closedBy());assertNull(d.closedAt());assertEquals(before,protectedRow(f));assertEquals(links,defects.links(f.actor(),f.project(),f.defect().id()));assertEquals(steps,executions.listRunSteps(f.actor(),f.project(),rc.id()));assertEquals(f.testcase(),assets.listCases(f.actor(),f.project(),null).getFirst());
    }
    @Test void compatibleResolutionHistorySurvivesClosureButChangedResolutionLosesOldAttribution() {
        var f=fixture();var original=row(f);apply(f,item("BUG-1","RESOLVED",true,"FIXED"));assertEquals(original.resolvedAt(),row(f).resolvedAt());assertNull(row(f).closedAt());
        apply(f,item("BUG-1","CLOSED",true,"FIXED"));assertEquals(original.resolvedAt(),row(f).resolvedAt());assertNull(row(f).closedAt());assertNull(row(f).closedBy());
        apply(f,item("BUG-1","CLOSED",true,"DUPLICATE"));assertNull(row(f).resolvedAt());assertNull(row(f).resolvedBy());assertNull(row(f).closedAt());
        manual(f,"OPEN",false,null);manual(f,"RESOLVED",true,"FIXED");assertEquals(f.actor().userId(),row(f).resolvedBy());assertNotNull(row(f).resolvedAt());
    }
    @Test void missingDefinitionsRejectWholeBatchAndSyncNeverCreatesDefects() {
        var f=fixture();var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,open(),item("BUG-MISSING","OPEN",false,null)));assertEquals(before,snapshot(f));assertEquals(1,defects.list(f.actor(),f.project()).size());
    }
    @Test void staleManualTransitionAndSameCodeRecreationInvalidateRevision() {
        var f=fixture();var r=request(f,open());manual(f,"IN_PROGRESS",false,null);assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals("IN_PROGRESS",row(f).status());
        var g=fixture();var prior=snapshot(g);var stale=request(g,open());defects.delete(g.actor(),g.project(),g.defect().id());defects.create(g.actor(),g.project(),new FunctionalDefectService.DefectInput(null,"BUG-1","新缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MAJOR","P1","CLOSED",true,null,"FIXED","TESTHUB",null,"步骤"));assertEquals(prior.items(),snapshot(g).items());assertNotEquals(prior.revision(),snapshot(g).revision());assertThrows(ResponseStatusException.class,()->sync.apply(g.actor(),g.project(),stale));
    }
    @Test void durableParallelReceiptReplayKeepsCommittedIdentityAndDoesNotUndoLaterEdits() throws Exception {
        var f=fixture();var r=request(f,open());Receipt receipt;try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));receipt=a.get(20,TimeUnit.SECONDS);assertEquals(receipt,b.get(20,TimeUnit.SECONDS));}
        assertEquals(f.defect().id(),receipt.results().getFirst().itemId());manual(f,"CLOSED",true,"EXTERNAL");var edited=row(f);assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertEquals(edited,row(f));
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),item("BUG-1","OPEN",true,null))))));
    }
    @Test void auditFailureRollsBackWorkflowHistoryAndReceiptAtomically() {
        var f=fixture();var before=row(f);var r=request(f,open());doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_DEFECT_WORKFLOW_SYNCED"),anyString(),anyString(),anyMap());
        try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,row(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_defect_workflow_sync_receipt WHERE request_key=:k").param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void strictProtocolRejectsProtectedFieldsInvalidStateResolutionAndDuplicates() throws Exception {
        var f=fixture();var before=snapshot(f);var bad=new ArrayList<Item>();bad.add(item("BUG-1","OPEN",false,"FIXED"));bad.add(item("BUG-1","CLOSED",true,null));bad.add(item("BUG-1","resolved",true,"FIXED"));bad.add(item("bug-1","OPEN",false,null));
        for(var field:List.of("assigneeId","ccUserIds","resolvedAt","closedBy","title")){var d=new HashMap<>(open().definition());d.put(field,"protected");bad.add(new Item("DEFECT","BUG-1",d));}var d=new HashMap<>(open().definition());d.put("confirmed","false");bad.add(new Item("DEFECT","BUG-1",d));d=new HashMap<>(open().definition());d.remove("confirmed");bad.add(new Item("DEFECT","BUG-1",d));
        for(var i:bad){assertThrows(ResponseStatusException.class,()->apply(f,i));assertEquals(before,snapshot(f));}assertThrows(ResponseStatusException.class,()->apply(f,open(),open()));assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(request(f,open()))+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void rolesProjectActorCsrfAndPayloadLimitGuardBothDirections() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/defect-workflow/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get(url.replace(f.project(),other.project())).param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,open()));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalDefectWorkflowSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void manualWorkflowAndSyncShareProjectLockAndRecheckAfterWaiting() throws Exception {
        var f=fixture();var r=request(f,open());var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(s->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}manual(f,"IN_PROGRESS",false,null);return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals("IN_PROGRESS",row(f).status());}
    }
    @Test void countLimitRejectsSnapshotBeforeReadingUnboundedDefectBodies() {
        var f=fixture();for(int n=1;n<=100;n++)defects.create(f.actor(),f.project(),new FunctionalDefectService.DefectInput(null,"B-"+n,"缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MAJOR","P1","OPEN",false,null,null,"TESTHUB",null,"步骤"));assertThrows(ResponseStatusException.class,()->snapshot(f));
    }
}
