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
import io.testhub.platform.functional.FunctionalDefectPeopleSyncService.*;
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
class FunctionalDefectPeopleSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"defect-people-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"defect-people-sync-fixture");}
    }
    @Autowired RegistrationService registration; @Autowired AccessService access;
    @Autowired FunctionalDefectPeopleSyncService sync; @Autowired FunctionalAssetService assets;
    @Autowired FunctionalDefectService defects; @Autowired FunctionalTestExecutionService executions;
    @Autowired ObjectMapper json; @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project,FunctionalRequirement requirement,FunctionalCase testcase,FunctionalTestPlan plan,FunctionalDefect defect) { }
    Fixture fixture() {
        var name="people-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","人员验收","Sync-fixture-2026!"));var c=access.context(actor);var p=c.projects().get(c.organizations().getFirst().id()).getFirst().id();
        var rf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("需求",null,0,"REQUIREMENTS"));var cf=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));
        var req=assets.createRequirement(actor,p,new FunctionalAssetService.RequirementInput(rf.id(),"REQ-1","需求","P1","APPROVED","原正文"));
        var tc=assets.createCase(actor,p,new FunctionalAssetService.CaseInput(cf.id(),req.id(),"CASE-1","用例","P1","READY","SECURITY","前置","步骤","结果"));
        var plan=executions.createPlan(actor,p,new FunctionalTestExecutionService.PlanInput(null,"PLAN-1","计划","READY","原说明",List.of(tc.id())));
        var d=defects.create(actor,p,new FunctionalDefectService.DefectInput(null,"BUG-1","原缺陷",null,"OTHER","",List.of(),List.of(),List.of(actor.userId()),"CRITICAL","P0","CLOSED",true,actor.userId(),"FIXED","TESTHUB",null,"重现步骤"));
        return new Fixture(actor,p,req,tc,plan,d);
    }
    Item item(String code,String assignee,List<String> cc) {var d=new LinkedHashMap<String,Object>();d.put("assigneeId",assignee);d.put("ccUserIds",cc);return new Item("DEFECT",code,d);}
    Item clear() {return item("BUG-1",null,List.of());}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    Receipt apply(Fixture f,Item... items) {return sync.apply(f.actor(),f.project(),request(f,items));}
    FunctionalDefect row(Fixture f) {return defects.list(f.actor(),f.project()).stream().filter(d->d.id().equals(f.defect().id())).findFirst().orElseThrow();}
    Map<String,Object> protectedRow(Fixture f) {var d=new HashMap<>(jdbc.sql("SELECT * FROM functional_defect WHERE id=:i").param("i",f.defect().id()).query().singleRow());for(var k:List.of("assignee_id","assigned_by","cc_user_ids","updated_by","updated_at"))d.remove(k);return d;}
    void manual(Fixture f,String assignee,List<String> cc) {var d=row(f);defects.update(f.actor(),f.project(),d.id(),new FunctionalDefectService.DefectInput(d.catalogId(),d.defectCode(),d.title(),d.dueDate(),d.bugType(),d.keywords(),d.browsers(),d.operatingSystems(),cc,d.severity(),d.priority(),d.status(),d.confirmed(),assignee==null?"":assignee,d.resolution(),d.source(),d.externalUrl(),d.description()));}
    Fixture member(Fixture f) {var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"TESTER");return other;}
    @Test void snapshotExposesOnlyProjectActiveMembersAndNoCredentials() throws Exception {
        var f=fixture();var m=member(f);var outsider=fixture();var snap=snapshot(f);assertEquals(Set.of(f.actor().userId(),m.actor().userId()),snap.members().stream().map(Member::id).collect(java.util.stream.Collectors.toSet()));
        var text=json.writeValueAsString(snap);assertFalse(text.contains("password"));assertFalse(text.contains(outsider.actor().userId()));assertEquals(f.defect().id(),snap.itemIds().get("DEFECT\nbug-1"));
        jdbc.sql("UPDATE user_account SET enabled=false WHERE id=:i").param("i",m.actor().userId()).update();assertEquals(1,snapshot(f).members().size());
    }
    @Test void explicitTargetMemberIdsReplacePeopleWithoutChangingWorkflowLinksOrHistory() {
        var source=fixture();var f=fixture();var m=member(f);var before=protectedRow(f);var run=executions.createRun(f.actor(),f.project(),f.plan().id(),"历史");var rc=executions.listRunCases(f.actor(),f.project(),run.id()).getFirst();
        for(var input:List.of(new FunctionalDefectService.DefectLinkInput("REQUIREMENT",f.requirement().id()),new FunctionalDefectService.DefectLinkInput("CASE",f.testcase().id()),new FunctionalDefectService.DefectLinkInput("RUN_CASE",rc.id())))defects.link(f.actor(),f.project(),f.defect().id(),input);
        var links=defects.links(f.actor(),f.project(),f.defect().id());var steps=executions.listRunSteps(f.actor(),f.project(),rc.id());
        assertThrows(ResponseStatusException.class,()->apply(f,item("BUG-1",source.actor().userId(),List.of())));
        apply(f,item("BUG-1",m.actor().userId(),List.of(m.actor().userId())));assertEquals(m.actor().userId(),row(f).assigneeId());assertEquals(f.actor().userId(),row(f).assignedBy());assertEquals(before,protectedRow(f));assertEquals(links,defects.links(f.actor(),f.project(),f.defect().id()));assertEquals(steps,executions.listRunSteps(f.actor(),f.project(),rc.id()));
    }
    @Test void unchangedPeopleDoNotChurnMetadataAndCcOnlyPreservesOriginalAssigner() {
        var f=fixture();var m=member(f);var original=row(f);apply(f,snapshot(f).items().toArray(Item[]::new));assertEquals(original,row(f));
        jdbc.sql("UPDATE functional_defect SET assigned_by=:a WHERE id=:i").param("a",m.actor().userId()).param("i",f.defect().id()).update();apply(f,item("BUG-1",f.actor().userId(),List.of()));assertEquals(m.actor().userId(),row(f).assignedBy());
    }
    @Test void explicitClearClearsAssignmentAttributionButPreservesTerminalHistory() {
        var f=fixture();var before=protectedRow(f);apply(f,clear());assertNull(row(f).assigneeId());assertNull(row(f).assignedBy());assertEquals(List.of(),row(f).ccUserIds());assertEquals(before,protectedRow(f));
    }
    @Test void missingDefectOrInvalidMemberRejectsWholeBatchWithoutCreatingAccounts() {
        var f=fixture();var before=snapshot(f);var count=jdbc.sql("SELECT COUNT(*) FROM user_account").query(Long.class).single();assertThrows(ResponseStatusException.class,()->apply(f,clear(),item("BUG-MISSING",null,List.of())));assertEquals(before,snapshot(f));assertThrows(ResponseStatusException.class,()->apply(f,item("BUG-1","Z".repeat(26),List.of())));assertEquals(before,snapshot(f));assertEquals(count,jdbc.sql("SELECT COUNT(*) FROM user_account").query(Long.class).single());
    }
    @Test void removedDisabledDeletedMembersAndAccountRenameInvalidateRevisionAndNeverAutoClear() {
        for(var action:List.of("remove","disable","delete","rename")){
            var f=fixture();var m=member(f);manual(f,m.actor().userId(),List.of(m.actor().userId()));var before=row(f);var r=request(f,clear());
            if(action.equals("remove"))access.removeMember(f.actor(),f.project(),access.members(f.actor(),f.project()).stream().filter(v->v.userId().equals(m.actor().userId())).findFirst().orElseThrow().membershipId());else jdbc.sql("UPDATE user_account SET "+(action.equals("disable")?"enabled=false":action.equals("delete")?"deleted_at=CURRENT_TIMESTAMP":"account=CONCAT(account,'-renamed')")+" WHERE id=:i").param("i",m.actor().userId()).update();
            assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before.assigneeId(),row(f).assigneeId());assertEquals(before.ccUserIds(),row(f).ccUserIds());
            if(!action.equals("rename")){assertEquals(List.of("DEFECT\nbug-1"),snapshot(f).unavailable());assertThrows(ResponseStatusException.class,()->apply(f,clear()));}
        }
    }
    @Test void ordinaryAssignmentAndSameCodeRecreationInvalidateStaleRevision() {
        var f=fixture();var r=request(f,clear());manual(f,null,List.of(f.actor().userId()));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));
        var g=fixture();var stale=request(g,clear());defects.delete(g.actor(),g.project(),g.defect().id());defects.create(g.actor(),g.project(),new FunctionalDefectService.DefectInput(null,"BUG-1","新缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MAJOR","P1","OPEN",false,null,null,"TESTHUB",null,"步骤"));assertThrows(ResponseStatusException.class,()->sync.apply(g.actor(),g.project(),stale));
    }
    @Test void durableParallelReceiptKeepsCommittedIdentityAndNeverUndoesLaterManualAssignment() throws Exception {
        var f=fixture();var r=request(f,clear());Receipt receipt;try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));receipt=a.get(20,TimeUnit.SECONDS);assertEquals(receipt,b.get(20,TimeUnit.SECONDS));}
        assertEquals(f.defect().id(),receipt.results().getFirst().itemId());manual(f,f.actor().userId(),List.of());var edited=row(f);assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertEquals(edited,row(f));
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),item("BUG-1",f.actor().userId(),List.of()))))));
    }
    @Test void auditFailureRollsBackPeopleAndReceiptAtomically() {
        var f=fixture();var before=row(f);var r=request(f,clear());doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_DEFECT_PEOPLE_SYNCED"),anyString(),anyString(),anyMap());
        try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,row(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_defect_people_sync_receipt WHERE request_key=:k").param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void strictProtocolRejectsHistoryStatusTextMalformedIdsAndNonCanonicalCc() throws Exception {
        var f=fixture();var before=snapshot(f);var bad=new ArrayList<Item>();bad.add(item("bug-1",null,List.of()));bad.add(item("BUG-1","invalid",List.of()));bad.add(item("BUG-1",null,List.of(f.actor().userId(),f.actor().userId())));bad.add(item("BUG-1",null,List.of("Z".repeat(26),"A".repeat(26))));
        for(var field:List.of("status","assignedBy","resolvedAt","closedBy","title")){var d=new HashMap<>(clear().definition());d.put(field,"protected");bad.add(new Item("DEFECT","BUG-1",d));}var d=new HashMap<>(clear().definition());d.remove("assigneeId");bad.add(new Item("DEFECT","BUG-1",d));
        for(var i:bad){assertThrows(ResponseStatusException.class,()->apply(f,i));assertEquals(before,snapshot(f));}assertThrows(ResponseStatusException.class,()->apply(f,clear(),clear()));assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(request(f,clear()))+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void rolesProjectActorCsrfAndPayloadLimitGuardBothDirections() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/defect-people/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get(url.replace(f.project(),other.project())).param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,clear()));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalDefectPeopleSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void manualEditAndSyncShareProjectLockAndRecheckAfterWaiting() throws Exception {
        var f=fixture();var r=request(f,clear());var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(s->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}manual(f,null,List.of(f.actor().userId()));return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals(List.of(f.actor().userId()),row(f).ccUserIds());}
    }
    @Test void accountDisableRaceWaitsForRowLockAndRechecksEligibility() throws Exception {
        var f=fixture();var m=member(f);var r=request(f,item("BUG-1",m.actor().userId(),List.of()));var tx=new TransactionTemplate(transactionManager);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(s->{jdbc.sql("SELECT id FROM user_account WHERE id=:i FOR UPDATE").param("i",m.actor().userId()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}jdbc.sql("UPDATE user_account SET enabled=false WHERE id=:i").param("i",m.actor().userId()).update();return null;}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals(f.actor().userId(),row(f).assigneeId());}
    }
    @Test void countLimitRejectsSnapshotBeforeReadingUnboundedBodies() {
        var f=fixture();for(int n=1;n<=100;n++)defects.create(f.actor(),f.project(),new FunctionalDefectService.DefectInput(null,"B-"+n,"缺陷",null,"OTHER","",List.of(),List.of(),List.of(),"MAJOR","P1","OPEN",false,null,null,"TESTHUB",null,"步骤"));assertThrows(ResponseStatusException.class,()->snapshot(f));
    }
}
