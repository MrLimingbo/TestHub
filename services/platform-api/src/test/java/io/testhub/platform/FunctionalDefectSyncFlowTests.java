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
import io.testhub.platform.functional.FunctionalDefectSyncService.*;
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
class FunctionalDefectSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"functional-defect-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"functional-defect-sync-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalDefectSyncService sync;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalTestExecutionService executions;
    @Autowired FunctionalGovernanceService governance;
    @Autowired FunctionalDefectService defects;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {var a="functional-defect-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(a,a+"@example.test","同步验收","Sync-fixture-2026!"));var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());}
    Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    Item catalog(String path,int order) {return item("CATALOG_DELIVERY",path,new Catalog(order));}
    Item defect(String code,String title) {return item("DEFECT",code,new Defect("[\"交付\",\"订单\"]",title,"2026-12-31","SECURITY","支付",List.of("CHROME","SAFARI"),List.of("LINUX","MAC_OS"),"CRITICAL","P0","TRACKER","https://bugs.example.test/42","<p>1. 登录</p><p>2. 下单</p>"));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    void seed(Fixture f) {apply(f,defect("BUG-1","原标题"),catalog("[\"交付\",\"订单\"]",9),catalog("[\"交付\"]",2));}
    FunctionalDefect row(Fixture f) {return defects.list(f.actor(),f.project()).getFirst();}
    FunctionalDefectService.DefectInput manual(FunctionalDefect d,String title) {return new FunctionalDefectService.DefectInput(d.catalogId(),d.defectCode(),title,d.dueDate(),d.bugType(),d.keywords(),d.browsers(),d.operatingSystems(),d.ccUserIds(),d.severity(),d.priority(),d.status(),d.confirmed(),d.assigneeId(),d.resolution(),d.source(),d.externalUrl(),d.description());}
    List<Object> protectedLink(FunctionalDefectLink link) {return List.of(link.id(),link.defectId(),link.defectCode(),link.assetType(),link.assetId(),link.assetLabel(),link.createdBy(),link.createdAt());}
    @Test void portableGraphRemapsIdsWithoutCopyingWorkflowPeopleOrCreatingExecutions() throws Exception {
        var source=fixture();seed(source);var old=row(source);
        defects.update(source.actor(),source.project(),old.id(),new FunctionalDefectService.DefectInput(old.catalogId(),old.defectCode(),old.title(),old.dueDate(),old.bugType(),old.keywords(),old.browsers(),old.operatingSystems(),List.of(source.actor().userId()),old.severity(),old.priority(),"CLOSED",true,source.actor().userId(),"FIXED",old.source(),old.externalUrl(),old.description()));
        var target=fixture();apply(target,snapshot(source).items().toArray(Item[]::new));assertEquals(snapshot(source).items(),snapshot(target).items());var copy=row(target);
        assertNotEquals(old.id(),copy.id());assertNotEquals(old.catalogId(),copy.catalogId());assertEquals("OPEN",copy.status());assertFalse(copy.confirmed());assertNull(copy.assigneeId());assertNull(copy.assignedBy());assertNull(copy.resolution());assertNull(copy.resolvedAt());assertNull(copy.closedBy());assertTrue(copy.ccUserIds().isEmpty());assertEquals(target.actor().userId(),copy.createdBy());assertTrue(executions.listRuns(target.actor(),target.project(),null).isEmpty());assertFalse(json.writeValueAsString(snapshot(target).items()).contains(copy.id()));
    }
    @Test void existingIdentityWorkflowHistoryPeopleAndAllAssetLinksSurviveUpdates() {
        var f=fixture();seed(f);var d=row(f);d=defects.update(f.actor(),f.project(),d.id(),new FunctionalDefectService.DefectInput(d.catalogId(),d.defectCode(),d.title(),d.dueDate(),d.bugType(),d.keywords(),d.browsers(),d.operatingSystems(),List.of(f.actor().userId()),d.severity(),d.priority(),"CLOSED",true,f.actor().userId(),"DUPLICATE",d.source(),d.externalUrl(),d.description()));
        var reqCatalog=assets.createCatalog(f.actor(),f.project(),new FunctionalAssetService.CatalogInput("需求",null,0,"REQUIREMENTS"));var caseCatalog=assets.createCatalog(f.actor(),f.project(),new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));var req=assets.createRequirement(f.actor(),f.project(),new FunctionalAssetService.RequirementInput(reqCatalog.id(),"REQ-1","关联需求","P1","APPROVED","原文"));var c=assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(caseCatalog.id(),req.id(),"CASE-1","关联用例","P1","READY","FUNCTIONAL","前置","步骤","结果"));var p=executions.createPlan(f.actor(),f.project(),new FunctionalTestExecutionService.PlanInput(null,"PLAN-1","计划","READY","",List.of(c.id())));var run=executions.createRun(f.actor(),f.project(),p.id(),"历史");var rc=executions.listRunCases(f.actor(),f.project(),run.id()).getFirst();var steps=executions.listRunSteps(f.actor(),f.project(),rc.id());
        for(var link:List.of(new FunctionalDefectService.DefectLinkInput("REQUIREMENT",req.id()),new FunctionalDefectService.DefectLinkInput("CASE",c.id()),new FunctionalDefectService.DefectLinkInput("RUN_CASE",rc.id()))) defects.link(f.actor(),f.project(),d.id(),link);
        var links=defects.links(f.actor(),f.project(),d.id());var before=d;apply(f,defect("BUG-1","同步标题"));var after=row(f);
        assertEquals(before.id(),after.id());assertEquals(before.displayId(),after.displayId());assertEquals(before.createdAt(),after.createdAt());assertEquals(before.createdBy(),after.createdBy());assertEquals(before.status(),after.status());assertEquals(before.confirmed(),after.confirmed());assertEquals(before.assigneeId(),after.assigneeId());assertEquals(before.assignedBy(),after.assignedBy());assertEquals(before.ccUserIds(),after.ccUserIds());assertEquals(before.resolution(),after.resolution());assertEquals(before.resolvedBy(),after.resolvedBy());assertEquals(before.resolvedAt(),after.resolvedAt());assertEquals(before.closedBy(),after.closedBy());assertEquals(before.closedAt(),after.closedAt());assertEquals(links.stream().map(this::protectedLink).toList(),defects.links(f.actor(),f.project(),d.id()).stream().map(this::protectedLink).toList());assertTrue(defects.links(f.actor(),f.project(),d.id()).stream().allMatch(link->link.defectTitle().equals("同步标题")));assertEquals(steps,executions.listRunSteps(f.actor(),f.project(),rc.id()));assertEquals("同步标题",after.title());
    }
    @Test void explicitNullableClearsDoNotReuseOldCatalogDateOrExternalLink() {
        var f=fixture();seed(f);var old=row(f);apply(f,item("DEFECT","BUG-1",new Defect(null,"清空选填",null,"OTHER","",List.of(),List.of(),"MINOR","P2","TESTHUB",null,"重现")));var d=row(f);assertNull(d.catalogId());assertNull(d.dueDate());assertNull(d.externalUrl());assertTrue(d.browsers().isEmpty());assertEquals(old.id(),d.id());assertEquals(old.createdAt(),d.createdAt());
    }
    @Test void identicalOperationsAndReadOnlySnapshotPreserveOriginalRowsAndRevision() {
        var f=fixture();seed(f);var before=row(f);var snapshot=snapshot(f);apply(f,snapshot.items().toArray(Item[]::new));assertEquals(before,row(f));assertEquals(snapshot,snapshot(f));
    }
    @Test void durableParallelReplayNeverDuplicatesOrUndoesLaterEdits() throws Exception {
        var f=fixture();var r=request(f,catalog("[\"交付\"]",1));try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var result=sync.apply(f.actor(),f.project(),r);apply(f,catalog("[\"交付\"]",2));assertEquals(result,sync.apply(f.actor(),f.project(),r));assertEquals(2,assets.listCatalogs(f.actor(),f.project(),"DELIVERY").getFirst().sortOrder());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),catalog("[\"其他\"]",0))))));
    }
    @Test void manualLifecycleWritesSerializeWithSnapshotComparison() throws Exception {
        var f=fixture();seed(f);var d=row(f);var r=request(f,defect("BUG-1","同步"));var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(status->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return defects.update(f.actor(),f.project(),d.id(),manual(d,"手工"));}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals("手工",row(f).title());}
    }
    @Test void invalidReferencesAndProtectedFieldsRollBackEntireBatch() throws Exception {
        var f=fixture();seed(f);var before=snapshot(f);
        for(var changed:List.of(Map.of("status","CLOSED"),Map.of("confirmed",true),Map.of("assigneeId",f.actor().userId()),Map.of("dueDate","2026-02-30"),Map.of("description","<p>&nbsp;</p>"),Map.of("browsers",List.of("SAFARI","CHROME")),Map.of("catalogPath","[\"缺失\"]"))) {
            var data=new HashMap<>(defect("BUG-1","失败").definition());data.putAll(changed);assertThrows(ResponseStatusException.class,()->apply(f,new Item("DEFECT","BUG-1",data),catalog("[\"不可写\"]",0)));assertEquals(before,snapshot(f));
        }
        assertThrows(ResponseStatusException.class,()->apply(f,catalog("[\"不存在\",\"子目录\"]",0)));var body=json.writeValueAsString(request(f,defect("BUG-1","失败")));assertThrows(ResponseStatusException.class,()->sync.decode((body+" {}").getBytes()));
    }
    @Test void projectLinksRemainReadableButAreNeverAutomaticallyCopied() {
        var f=fixture();seed(f);var d=row(f);var input=manual(d,d.title());defects.update(f.actor(),f.project(),d.id(),new FunctionalDefectService.DefectInput(input.catalogId(),input.defectCode(),input.title(),input.dueDate(),input.bugType(),input.keywords(),input.browsers(),input.operatingSystems(),input.ccUserIds(),input.severity(),input.priority(),input.status(),input.confirmed(),input.assigneeId(),input.resolution(),input.source(),input.externalUrl(),"/api/v1/projects/"+f.project()+"/files/01ARZ3NDEKTSV4RRFFQ69G5FAV/download"));var target=fixture();assertThrows(ResponseStatusException.class,()->apply(target,snapshot(f).items().toArray(Item[]::new)));assertTrue(snapshot(target).items().isEmpty());apply(f,catalog("[\"无关\"]",0));assertEquals(4,snapshot(f).items().size());
    }
    @Test void auditFailureRollsBackPortableFieldsAndDurableReceipt() {
        var f=fixture();seed(f);var before=snapshot(f);var r=request(f,defect("BUG-1","不能写"));doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_DEFECT_SYNCED"),anyString(),anyString(),anyMap());try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_defect_sync_receipt WHERE project_id=:p AND request_key=:k").param("p",f.project()).param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void actorProjectRoleCsrfAndStrictByteLimitsRemainMandatory() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/defects/sync";mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/functional/defects/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());var body=json.writeValueAsBytes(request(f,catalog("[\"交付\"]",0)));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalDefectSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void countDepthByteAndDatabaseCollationGuardsAreAtomic() {
        var f=fixture();var rows=new ArrayList<Item>();for(int n=0;n<100;n++) rows.add(item("DEFECT","BUG-"+n,new Defect(null,"缺陷",null,"CODE_ERROR","",List.of(),List.of(),"MAJOR","P1","TESTHUB",null,"复现")));apply(f,rows.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,item("DEFECT","BUG-OVER",new Defect(null,"超额",null,"CODE_ERROR","",List.of(),List.of(),"MAJOR","P1","TESTHUB",null,"复现"))));assertEquals(before,snapshot(f));
        var g=fixture();rows.clear();for(int n=0;n<60;n++) rows.add(item("DEFECT","BUG-"+n,new Defect(null,"缺陷",null,"CODE_ERROR","",List.of(),List.of(),"MAJOR","P1","TESTHUB",null,"大".repeat(20000))));assertThrows(ResponseStatusException.class,()->apply(g,rows.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());assertThrows(ResponseStatusException.class,()->apply(g,catalog("[ \"非规范\" ]",0)));assertThrows(ResponseStatusException.class,()->apply(g,catalog("["+String.join(",",Collections.nCopies(17,"\"层\""))+"]",0)));
        if(FunctionalDatabaseProfile.mysql()){apply(g,catalog("[\"ROOT\"]",0),catalog("[\"ROOT\",\"café\"]",0));var old=snapshot(g);assertThrows(ResponseStatusException.class,()->apply(g,catalog("[\"ROOT\",\"cafe\"]",0),catalog("[\"不可写\"]",0)));assertEquals(old,snapshot(g));}
    }
    @Test void identityChangesInvalidateOldRevisionsEvenWithTheSameCodeAndDefinition() {
        var f=fixture();seed(f);var r=request(f,defect("BUG-1","过期"));var old=row(f);defects.delete(f.actor(),f.project(),old.id());defects.create(f.actor(),f.project(),manual(old,old.title()));assertNotEquals(old.id(),row(f).id());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(old.title(),row(f).title());
    }
}
