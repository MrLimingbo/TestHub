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
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.functional.FunctionalSyncService.*;
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
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class FunctionalSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"functional-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"functional-sync-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalSyncService sync;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalTestExecutionService executions;
    @Autowired FunctionalGovernanceService governance;
    @Autowired FileObjectService files;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {var a="functional-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(a,a+"@example.test","同步验收","Sync-fixture-2026!"));var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());}
    Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    Item catalog(String scope,String path,int sort) {return item("CATALOG_"+scope,path,new Catalog(sort));}
    Item requirement(String title) {return item("REQUIREMENT","REQ-1",new Requirement("[\"需求\",\"订单\"]",title,"P0","APPROVED","需求正文"));}
    Item testCase(String code,String title) {return item("CASE",code,new Case("[\"用例\"]","REQ-1",title,"P1","READY","SECURITY","前置","1. 登录\n2. 下单","成功"));}
    Item plan(String description,List<String> codes) {return item("PLAN","PLAN-1",new Plan("[\"计划\"]","回归计划","READY",description,codes));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    void seed(Fixture f) {apply(f,plan("原始",List.of("CASE-2","CASE-1")),testCase("CASE-1","用例一"),testCase("CASE-2","用例二"),requirement("订单需求"),catalog("REQUIREMENTS","[\"需求\",\"订单\"]",9),catalog("REQUIREMENTS","[\"需求\"]",2),catalog("CASES","[\"用例\"]",3),catalog("PLANS","[\"计划\"]",4));}
    @Test void unorderedPortableGraphRemapsIdsNestedPathsAndOrderedCases() throws Exception {
        var source=fixture();seed(source);var target=fixture();apply(target,snapshot(source).items().toArray(Item[]::new));
        assertEquals(snapshot(source).items(),snapshot(target).items());var r=assets.listRequirements(target.actor(),target.project(),null).getFirst();var c=assets.listCases(target.actor(),target.project(),null).getFirst();
        assertEquals(r.id(),c.requirementId());assertNotEquals(assets.listRequirements(source.actor(),source.project(),null).getFirst().id(),r.id());assertEquals("SECURITY",c.caseType());
        var p=executions.listPlans(target.actor(),target.project(),null).getFirst();assertEquals(List.of("CASE-2","CASE-1"),executions.listPlanCases(target.actor(),target.project(),p.id()).stream().map(FunctionalTestPlanCase::caseCode).toList());assertTrue(executions.listRuns(target.actor(),target.project(),null).isEmpty());assertFalse(json.writeValueAsString(snapshot(target).items()).contains(r.id()));
    }
    @Test void existingIdsHistorySnapshotsUsersAndGovernanceLinksSurviveUpdates() {
        var f=fixture();seed(f);var c=assets.listCases(f.actor(),f.project(),null).getFirst();var p=executions.listPlans(f.actor(),f.project(),null).getFirst();var run=executions.createRun(f.actor(),f.project(),p.id(),"历史");var rc=executions.listRunCases(f.actor(),f.project(),run.id()).getFirst();var before=executions.listRunSteps(f.actor(),f.project(),rc.id());var tag=governance.createTag(f.actor(),f.project(),new FunctionalGovernanceService.TagInput("保持","#336699"));var tags=governance.replaceAssetTags(f.actor(),f.project(),"CASE",c.id(),List.of(tag.id()));
        apply(f,testCase(c.caseCode(),"新标题"),plan("修改",List.of("CASE-1","CASE-2")),requirement("新需求"));var after=assets.listCases(f.actor(),f.project(),null).stream().filter(x->x.id().equals(c.id())).findFirst().orElseThrow();assertEquals(c.createdAt(),after.createdAt());assertEquals(c.createdBy(),after.createdBy());assertEquals(p.id(),executions.listPlans(f.actor(),f.project(),null).getFirst().id());assertEquals(before,executions.listRunSteps(f.actor(),f.project(),rc.id()));assertEquals(1,snapshot(f).excludedExecutions());assertEquals(tags,governance.listAssetTags(f.actor(),f.project(),"CASE",c.id()));
    }
    @Test void durableParallelReplayNeverDuplicatesOrUndoesLaterChanges() throws Exception {
        var f=fixture();var r=request(f,catalog("CASES","[\"用例\"]",1));try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var result=sync.apply(f.actor(),f.project(),r);apply(f,catalog("CASES","[\"用例\"]",2));assertEquals(result,sync.apply(f.actor(),f.project(),r));assertEquals(2,assets.listCatalogs(f.actor(),f.project(),"CASES").getFirst().sortOrder());
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),catalog("CASES","[\"不同\"]",0))))));
    }
    @Test void manualWritesSerializeWithSnapshotComparisonAndRejectStaleSync() throws Exception {
        var f=fixture();seed(f);var req=assets.listRequirements(f.actor(),f.project(),null).getFirst();var r=request(f,requirement("同步"));var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)) {var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(status->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return assets.updateRequirement(f.actor(),f.project(),req.id(),new FunctionalAssetService.RequirementInput(req.catalogId(),req.requirementCode(),"手工修改",req.priority(),req.status(),req.description()));}));assertTrue(locked.await(10,TimeUnit.SECONDS));
            var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);var error=assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS));assertInstanceOf(ResponseStatusException.class,error.getCause());assertEquals("手工修改",assets.listRequirements(f.actor(),f.project(),null).getFirst().title());}
    }
    @Test void staleRevisionUnknownFieldsDuplicateCasesAndDanglingReferencesRollBack() throws Exception {
        var f=fixture();seed(f);var r=request(f,requirement("过期"));apply(f,requirement("新版本"));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));
        assertThrows(ResponseStatusException.class,()->apply(f,plan("失败",List.of("CASE-1","CASE-1")),catalog("CASES","[\"不可写\"]",0)));assertThrows(ResponseStatusException.class,()->apply(f,plan("失败",List.of("MISSING"))));assertThrows(ResponseStatusException.class,()->apply(f,catalog("CASES","[\"缺父级\",\"子级\"]",0)));assertEquals(before,snapshot(f));
        var body=json.valueToTree(request(f,requirement("bad")));((com.fasterxml.jackson.databind.node.ObjectNode)body.path("operations").get(0).path("item").path("definition")).put("createdBy",f.actor().userId());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),sync.decode(json.writeValueAsBytes(body))));assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(r)+" {}").getBytes()));
    }
    @Test void projectFileLinksRemainReadableButNeverCopiedAutomatically() {
        var f=fixture();seed(f);var r=assets.listRequirements(f.actor(),f.project(),null).getFirst();assets.updateRequirement(f.actor(),f.project(),r.id(),new FunctionalAssetService.RequirementInput(r.catalogId(),r.requirementCode(),r.title(),r.priority(),r.status(),"/api/v1/projects/"+f.project()+"/files/01ARZ3NDEKTSV4RRFFQ69G5FAV/download"));var source=snapshot(f);assertEquals(8,source.items().size());
        var target=fixture();assertThrows(ResponseStatusException.class,()->apply(target,source.items().toArray(Item[]::new)));assertTrue(snapshot(target).items().isEmpty());apply(f,catalog("CASES","[\"无关\"]",0));assertEquals(9,snapshot(f).items().size());
    }
    @Test void confirmedTargetFileReferencesCanBeWrittenWithoutAcceptingExternalLinks() {
        var f=fixture();apply(f,catalog("PLANS","[\"计划\"]",0));
        var file=files.upload(f.actor(),f.project(),new org.springframework.mock.web.MockMultipartFile("file","note.txt","text/plain","note".getBytes()));
        String route="/api/v1/projects/"+f.project()+"/files/"+file.id()+"/download";
        apply(f,plan("[附件]("+route+")",List.of()));
        assertEquals("[附件]("+route+")",snapshot(f).items().stream().filter(i->i.kind().equals("PLAN")).findFirst().orElseThrow().definition().get("description"));
        assertThrows(ResponseStatusException.class,()->apply(f,plan("https://outside.test"+route,List.of())));
        assertThrows(ResponseStatusException.class,()->apply(f,plan(route.replace(file.id(),"Z".repeat(26)),List.of())));
    }
    @Test void nullCatalogPathsReturnBadRequestWithoutWriting() throws Exception {
        var f=fixture();var url="/api/v1/projects/"+f.project()+"/functional/sync";
        for(var item:List.of(catalog("CASES",null,0),item("REQUIREMENT","REQ-NULL",new Requirement(null,"需求","P0","DRAFT","")),item("CASE","CASE-NULL",new Case(null,null,"用例","P0","DRAFT","FUNCTIONAL","","步骤","结果")))) {
            var body=json.writeValueAsBytes(request(f,item));
            mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            assertTrue(snapshot(f).items().isEmpty());
        }
    }
    @Test void auditFailureRollsBackDefinitionsOrderAndReceipt() {
        var f=fixture();seed(f);var before=snapshot(f);var r=request(f,requirement("不能写"),plan("不能写",List.of("CASE-1")));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_SYNCED"),anyString(),anyString(),anyMap());try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_sync_receipt WHERE project_id=:p AND request_key=:k").param("p",f.project()).param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void actorProjectRoleAndCsrfProtectionRemainMandatory() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/sync";mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/functional/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,catalog("CASES","[\"用例\"]",0)));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void countByteDepthAndDatabaseCollationLimitsAreAtomic() {
        var f=fixture();var rows=new ArrayList<Item>();for(int n=0;n<100;n++) rows.add(catalog("CASES","[\"C"+n+"\"]",0));apply(f,rows.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,catalog("CASES","[\"OVER\"]",0)));assertEquals(before,snapshot(f));
        var g=fixture();rows.clear();rows.add(catalog("CASES","[\"用例\"]",0));for(int n=0;n<60;n++) rows.add(item("CASE","CASE-"+n,new Case("[\"用例\"]",null,"大正文","P1","DRAFT","FUNCTIONAL","x".repeat(20000),"x".repeat(20000),"x".repeat(20000))));assertThrows(ResponseStatusException.class,()->apply(g,rows.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
        assertThrows(ResponseStatusException.class,()->apply(g,catalog("CASES","[ \\\"尚未规范\\\" ]",0)));
        if(FunctionalDatabaseProfile.mysql()){apply(g,catalog("CASES","[\"ROOT\"]",0),catalog("CASES","[\"ROOT\",\"café\"]",0));var old=snapshot(g);assertThrows(ResponseStatusException.class,()->apply(g,catalog("CASES","[\"ROOT\",\"cafe\"]",0),catalog("CASES","[\"不可写\"]",0)));assertEquals(old,snapshot(g));}
    }
}
