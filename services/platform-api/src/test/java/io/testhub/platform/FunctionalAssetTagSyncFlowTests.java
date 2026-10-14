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
import io.testhub.platform.functional.FunctionalAssetTagSyncService.*;
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
class FunctionalAssetTagSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"asset-tag-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"asset-tag-sync-fixture");}
    }
    @Autowired RegistrationService registration; @Autowired AccessService access;
    @Autowired FunctionalAssetTagSyncService sync; @Autowired FunctionalAssetService assets;
    @Autowired FunctionalGovernanceService governance; @Autowired FunctionalTestExecutionService executions;
    @Autowired ObjectMapper json; @Autowired JdbcClient jdbc; @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project,FunctionalRequirement requirement,FunctionalCase testcase,FunctionalTestPlan plan) { }
    Fixture fixture() {
        var name="tag-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","标签验收","Sync-fixture-2026!"));var c=access.context(actor);var p=c.projects().get(c.organizations().getFirst().id()).getFirst().id();
        var reqFolder=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("需求",null,0,"REQUIREMENTS"));var caseFolder=assets.createCatalog(actor,p,new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));
        var req=assets.createRequirement(actor,p,new FunctionalAssetService.RequirementInput(reqFolder.id(),"REQ-1","需求","P1","APPROVED","原正文"));
        var testcase=assets.createCase(actor,p,new FunctionalAssetService.CaseInput(caseFolder.id(),req.id(),"CASE-1","用例","P1","READY","SECURITY","前置","步骤","结果"));
        var plan=executions.createPlan(actor,p,new FunctionalTestExecutionService.PlanInput(null,"PLAN-1","计划","READY","原说明",List.of(testcase.id())));
        governance.createTag(actor,p,new FunctionalGovernanceService.TagInput("核心","arcoblue"));governance.createTag(actor,p,new FunctionalGovernanceService.TagInput("冒烟","green"));
        return new Fixture(actor,p,req,testcase,plan);
    }
    Item item(String kind,String code,String... tags) {return new Item(kind,code,Map.of("tags",Arrays.stream(tags).sorted().toList()));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    List<String> tagged(Fixture f,String type,String id) {return governance.listAssetTags(f.actor(),f.project(),type,id).stream().map(FunctionalTag::name).sorted().toList();}
    @Test void referencesRemapAcrossEndpointsWithoutChangingAssetsOrOrderedHistory() throws Exception {
        var source=fixture();var target=fixture();var run=executions.createRun(target.actor(),target.project(),target.plan().id(),"保留执行");
        var before=executions.listRunCases(target.actor(),target.project(),run.id());
        apply(source,item("REQUIREMENT","REQ-1","核心"),item("CASE","CASE-1","冒烟","核心"),item("PLAN","PLAN-1","冒烟"));apply(target,snapshot(source).items().toArray(Item[]::new));
        assertEquals(snapshot(source).items(),snapshot(target).items());assertNotEquals(source.testcase().id(),target.testcase().id());
        assertEquals(target.requirement(),assets.listRequirements(target.actor(),target.project(),null).getFirst());assertEquals(target.testcase(),assets.listCases(target.actor(),target.project(),null).getFirst());assertEquals(target.plan(),executions.listPlans(target.actor(),target.project(),null).getFirst());assertEquals(before,executions.listRunCases(target.actor(),target.project(),run.id()));
        assertFalse(json.writeValueAsString(snapshot(source).items()).contains(source.testcase().id()));
    }
    @Test void replacingAndClearingCompleteTagSetsPreservesDictionaryAndOtherAssetTypes() {
        var f=fixture();apply(f,item("REQUIREMENT","REQ-1","核心"),item("CASE","CASE-1","核心"),item("PLAN","PLAN-1","冒烟"));var dictionary=governance.listTags(f.actor(),f.project());
        apply(f,item("CASE","CASE-1"));assertTrue(tagged(f,"CASE",f.testcase().id()).isEmpty());assertEquals(List.of("核心"),tagged(f,"REQUIREMENT",f.requirement().id()));assertEquals(List.of("冒烟"),tagged(f,"PLAN",f.plan().id()));assertEquals(dictionary,governance.listTags(f.actor(),f.project()));
    }
    @Test void missingAndWrongCaseReferencesRejectAllWritesWithoutCreatingDefinitions() {
        var f=fixture();var before=snapshot(f);
        for(var bad:List.of(item("CASE","CASE-MISSING","核心"),item("CASE","CASE-1","缺失"),item("CASE","CASE-1","CORE"))) {
            assertThrows(ResponseStatusException.class,()->apply(f,item("REQUIREMENT","REQ-1","核心"),bad));assertEquals(before,snapshot(f));
        }
        governance.createTag(f.actor(),f.project(),new FunctionalGovernanceService.TagInput("Core","red"));assertThrows(ResponseStatusException.class,()->apply(f,item("CASE","CASE-1","core")));assertTrue(tagged(f,"CASE",f.testcase().id()).isEmpty());
    }
    @Test void staleManualAssociationAndDictionaryChangesRejectPendingBatch() {
        var f=fixture();var firstRequest=request(f,item("CASE","CASE-1","核心"));var tag=governance.listTags(f.actor(),f.project()).getFirst();governance.replaceAssetTags(f.actor(),f.project(),"CASE",f.testcase().id(),List.of(tag.id()));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),firstRequest));
        var r=request(f,item("CASE","CASE-1","核心"));var stale=r;governance.updateTag(f.actor(),f.project(),tag.id(),new FunctionalGovernanceService.TagInput("已改名","green"));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),stale));
    }
    @Test void concurrentDurableReplayDoesNotRepeatReplacementOrUndoLaterEdits() throws Exception {
        var f=fixture();var r=request(f,item("CASE","CASE-1","核心"));Receipt first;
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));first=a.get(20,TimeUnit.SECONDS);assertEquals(first,b.get(20,TimeUnit.SECONDS));}
        apply(f,item("CASE","CASE-1"));assertEquals(first,sync.apply(f.actor(),f.project(),r));assertTrue(tagged(f,"CASE",f.testcase().id()).isEmpty());
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),item("PLAN","PLAN-1","核心"))))));
    }
    @Test void auditFailureRollsBackAssociationsAndReceiptTogether() {
        var f=fixture();var before=snapshot(f);var r=request(f,item("CASE","CASE-1","核心"),item("PLAN","PLAN-1","冒烟"));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_ASSET_TAG_SYNCED"),anyString(),anyString(),anyMap());
        try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_asset_tag_sync_receipt WHERE request_key=:k").param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void strictJsonRejectsUnknownFieldsDuplicateOperationsUnsortedNamesAndUnsafeCodes() throws Exception {
        var f=fixture();var before=snapshot(f);
        for(var bad:List.of(new Item("CASE","CASE-1",Map.of("tags",List.of("核心","冒烟"))),new Item("CASE","CASE-1",Map.of("tags",List.of("核心","核心"))),new Item("CASE","CASE-1",Map.of("tags",List.of("核心"),"values","private")),item("CASE","case-1","核心"),item("UNKNOWN","CASE-1","核心"))) {
            assertThrows(ResponseStatusException.class,()->apply(f,bad));assertEquals(before,snapshot(f));
        }
        var r=request(f,item("CASE","CASE-1"),item("CASE","CASE-1","核心"));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(r)+" {}").getBytes()));
    }
    @Test void projectRoleActorAndCsrfGuardsApplyToBothProtocolDirections() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/asset-tags/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get(url.replace(f.project(),other.project())).param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,item("CASE","CASE-1","核心")));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalAssetTagSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void writerWaitsForProjectLockThenRechecksAssociations() throws Exception {
        var f=fixture();var firstRequest=request(f,item("CASE","CASE-1","核心"));var tag=governance.listTags(f.actor(),f.project()).stream().filter(t->t.name().equals("冒烟")).findFirst().orElseThrow();var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(s->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return governance.replaceAssetTags(f.actor(),f.project(),"CASE",f.testcase().id(),List.of(tag.id()));}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),firstRequest));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals(List.of("冒烟"),tagged(f,"CASE",f.testcase().id()));}
    }
    @Test void countAndUtf8MergedByteLimitsRejectWithoutChangingExistingData() {
        var f=fixture();var folder=f.testcase().catalogId();for(int n=1;n<100;n++)assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(folder,null,"C-"+n,"用例","P1","READY","FUNCTIONAL","","步骤","结果"));assertEquals(102,snapshot(f).items().size());assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(folder,null,"C-OVER","用例","P1","READY","FUNCTIONAL","","步骤","结果"));assertThrows(ResponseStatusException.class,()->snapshot(f));
        var g=fixture();var tags=new ArrayList<String>();for(int n=0;n<98;n++){var name=String.format("%03d",n)+"字".repeat(60);governance.createTag(g.actor(),g.project(),new FunctionalGovernanceService.TagInput(name,"green"));tags.add(name);}tags.addAll(List.of("冒烟","核心"));Collections.sort(tags);
        for(int n=1;n<100;n++){assets.createRequirement(g.actor(),g.project(),new FunctionalAssetService.RequirementInput(g.requirement().catalogId(),"R-"+n,"需求","P1","DRAFT",""));assets.createCase(g.actor(),g.project(),new FunctionalAssetService.CaseInput(g.testcase().catalogId(),null,"C-"+n,"用例","P1","READY","FUNCTIONAL","","步骤","结果"));executions.createPlan(g.actor(),g.project(),new FunctionalTestExecutionService.PlanInput(null,"P-"+n,"计划","DRAFT","",List.of()));}
        var before=snapshot(g);var items=before.items().stream().limit(200).map(i->new Item(i.kind(),i.name(),Map.of("tags",tags))).toArray(Item[]::new);var error=assertThrows(ResponseStatusException.class,()->apply(g,items));assertTrue(error.getReason().contains("4 MiB")||error.getReason().contains("3 MiB"));assertEquals(before,snapshot(g));
    }
    @Test void identicalOperationKeepsJoinIdentifiersAndDeletedAssetsAreNotRecreated() {
        var f=fixture();apply(f,item("CASE","CASE-1","核心"));var ids=jdbc.sql("SELECT id FROM functional_asset_tag WHERE project_id=:p ORDER BY id").param("p",f.project()).query(String.class).list();apply(f,item("CASE","CASE-1","核心"));assertEquals(ids,jdbc.sql("SELECT id FROM functional_asset_tag WHERE project_id=:p ORDER BY id").param("p",f.project()).query(String.class).list());
        executions.deletePlan(f.actor(),f.project(),f.plan().id());assertThrows(ResponseStatusException.class,()->apply(f,item("PLAN","PLAN-1","核心")));assertTrue(executions.listPlans(f.actor(),f.project(),null).isEmpty());
    }
}
