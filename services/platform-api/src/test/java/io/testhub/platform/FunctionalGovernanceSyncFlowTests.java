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
import io.testhub.platform.functional.FunctionalGovernanceSyncService.*;
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
class FunctionalGovernanceSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"governance-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"governance-sync-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalGovernanceSyncService sync;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalGovernanceService governance;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {var a="governance-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(a,a+"@example.test","治理验收","Sync-fixture-2026!"));var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());}
    Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    Item catalog(String path,int sort) {return item("CATALOG_GOVERNANCE",path,new Catalog(sort));}
    Item tag(String name,String color) {return item("TAG",name,new FunctionalGovernanceSyncService.Tag(color));}
    Item field(String type,String key,String title,List<String> options) {return item("FIELD_"+type,key,new Field(title,options.isEmpty()?"TEXT":"SELECT",!options.isEmpty(),options));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    void seed(Fixture f) {apply(f,catalog("[\"治理\",\"子目录\"]",9),tag("核心","#336699"),field("REQUIREMENT","business_domain","需求域",List.of("交易","会员")),field("CASE","business_domain","用例域",List.of("会员","交易")),field("PLAN","business_domain","计划域",List.of()),catalog("[\"治理\"]",1));}
    @Test void portableDefinitionsRemapIdsAndPreserveAssetTypeAndOptionOrder() throws Exception {
        var source=fixture();seed(source);var target=fixture();apply(target,snapshot(source).items().toArray(Item[]::new));
        assertEquals(snapshot(source).items(),snapshot(target).items());var tags=governance.listTags(target.actor(),target.project());
        assertNotEquals(governance.listTags(source.actor(),source.project()).getFirst().id(),tags.getFirst().id());
        assertEquals(3,governance.listFields(target.actor(),target.project(),null).size());
        assertEquals(List.of("会员","交易"),json.readerForListOf(String.class).readValue(governance.listFields(target.actor(),target.project(),"CASE").getFirst().optionsJson()));
        assertEquals(0,snapshot(target).excludedBindings());assertFalse(json.writeValueAsString(snapshot(target).items()).contains(tags.getFirst().id()));
    }
    @Test void updatesAndTagRenamePreserveIdsCreationAndExistingAssetAssignments() {
        var f=fixture();seed(f);var tag=governance.listTags(f.actor(),f.project()).getFirst();var field=governance.listFields(f.actor(),f.project(),"CASE").getFirst();
        var folder=assets.createCatalog(f.actor(),f.project(),new FunctionalAssetService.CatalogInput("用例",null,0,"CASES"));
        var c=assets.createCase(f.actor(),f.project(),new FunctionalAssetService.CaseInput(folder.id(),null,"CASE-1","原用例","P1","READY","FUNCTIONAL","","步骤","结果"));
        governance.replaceAssetTags(f.actor(),f.project(),"CASE",c.id(),List.of(tag.id()));
        apply(f,tag("核心","green"),field("CASE","business_domain","新域",List.of("交易","会员")));
        var after=governance.listTags(f.actor(),f.project()).getFirst();assertEquals(tag.id(),after.id());assertEquals(tag.createdBy(),after.createdBy());assertEquals(tag.createdAt(),after.createdAt());
        var changed=governance.listFields(f.actor(),f.project(),"CASE").getFirst();assertEquals(field.id(),changed.id());assertEquals(field.createdAt(),changed.createdAt());assertEquals(field.createdBy(),changed.createdBy());
        assertEquals(after,governance.listAssetTags(f.actor(),f.project(),"CASE",c.id()).getFirst());assertEquals(1,snapshot(f).excludedBindings());assertEquals(c,assets.listCases(f.actor(),f.project(),null).getFirst());
        var renamed=governance.updateTag(f.actor(),f.project(),tag.id(),new FunctionalGovernanceService.TagInput("核心改名","purple"));assertEquals(tag.id(),renamed.id());assertEquals(renamed,governance.listAssetTags(f.actor(),f.project(),"CASE",c.id()).getFirst());
    }
    @Test void durableParallelReplayNeverDuplicatesOrUndoesLaterChanges() throws Exception {
        var f=fixture();var r=request(f,tag("核心","arcoblue"));try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var result=sync.apply(f.actor(),f.project(),r);apply(f,tag("核心","green"));assertEquals(result,sync.apply(f.actor(),f.project(),r));assertEquals("green",governance.listTags(f.actor(),f.project()).getFirst().color());
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),tag("不同","red"))))));
    }
    @Test void manualTagWritesSerializeWithSnapshotComparisonAndRejectStaleSync() throws Exception {
        var f=fixture();seed(f);var tag=governance.listTags(f.actor(),f.project()).getFirst();var r=request(f,tag("核心","orange"));var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)) {var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(status->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return governance.updateTag(f.actor(),f.project(),tag.id(),new FunctionalGovernanceService.TagInput("核心","purple"));}));assertTrue(locked.await(10,TimeUnit.SECONDS));
            var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);var error=assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS));assertInstanceOf(ResponseStatusException.class,error.getCause());assertEquals("purple",governance.listTags(f.actor(),f.project()).getFirst().color());}
    }
    @Test void staleUnknownOrNoncanonicalDefinitionsAndDanglingParentsRejectEntireBatch() throws Exception {
        var f=fixture();seed(f);var stale=request(f,tag("核心","red"));apply(f,tag("核心","green"));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),stale));
        for(var invalid:List.of(catalog("[\"缺父\",\"子级\"]",0),catalog(null,0),catalog("[ \"目录\" ]",0),tag("核心","RED"),field("CASE","UPPER","字段",List.of()),field("CASE","key","字段",List.of("重复","重复")),item("FIELD_PLAN","invalid",new Field("字段","TEXT",false,List.of("不可用"))))) {
            assertThrows(ResponseStatusException.class,()->apply(f,tag("不可写","red"),invalid));assertEquals(before,snapshot(f));
        }
        var body=json.valueToTree(request(f,tag("核心","blue")));((com.fasterxml.jackson.databind.node.ObjectNode)body.path("operations").get(0).path("item").path("definition")).put("assetId","private");assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),sync.decode(json.writeValueAsBytes(body))));assertThrows(ResponseStatusException.class,()->sync.decode((json.writeValueAsString(stale)+" {}").getBytes()));assertEquals(before,snapshot(f));
    }
    @Test void projectLinksRemainReadableButCannotBeSentAutomatically() {
        var f=fixture();governance.createField(f.actor(),f.project(),new FunctionalGovernanceService.CustomFieldInput("CASE","链接","linked","SELECT",false,List.of("/api/v1/projects/x/files/x")));
        var target=fixture();assertThrows(ResponseStatusException.class,()->apply(target,snapshot(f).items().toArray(Item[]::new)));assertTrue(snapshot(target).items().isEmpty());
    }
    @Test void auditFailureRollsBackDefinitionsAndDurableReceipt() {
        var f=fixture();seed(f);var before=snapshot(f);var r=request(f,tag("核心","red"),field("CASE","business_domain","不能写",List.of()));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("FUNCTIONAL_GOVERNANCE_SYNCED"),anyString(),anyString(),anyMap());try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_governance_sync_receipt WHERE project_id=:p AND request_key=:k").param("p",f.project()).param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void actorProjectRoleAndCsrfProtectionRemainMandatoryForSyncAndTagEditing() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/functional/governance/sync";
        mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/functional/governance/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        var body=json.writeValueAsBytes(request(f,tag("核心","green")));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[FunctionalGovernanceSyncService.MAX_BYTES+1])).getStatusCode().value());
        var tag=governance.listTags(f.actor(),f.project()).getFirst();var edit="/api/v1/projects/"+f.project()+"/functional/tags/"+tag.id();var tagBody="{\"name\":\"核心\",\"color\":\"purple\"}";
        mvc.perform(put(edit).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(tagBody)).andExpect(status().isForbidden());mvc.perform(put(edit).with(user(other.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(tagBody)).andExpect(status().isForbidden());mvc.perform(put(edit).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(tagBody)).andExpect(status().isOk());
        assertThrows(ResponseStatusException.class,()->governance.updateTag(f.actor(),f.project(),"MISSING",new FunctionalGovernanceService.TagInput("新","red")));
    }
    @Test void countOptionDepthAndDatabaseCollationLimitsAreAtomic() {
        var f=fixture();var rows=new ArrayList<Item>();for(int n=0;n<100;n++) rows.add(tag("Tag-"+n,"green"));apply(f,rows.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,tag("OVER","red")));assertEquals(before,snapshot(f));
        var g=fixture();assertThrows(ResponseStatusException.class,()->apply(g,field("CASE","domain","字段",java.util.stream.IntStream.range(0,101).mapToObj(n->"选项"+n).toList())));assertThrows(ResponseStatusException.class,()->apply(g,catalog("["+String.join(",",Collections.nCopies(17,"\"目录\""))+"]",0)));assertTrue(snapshot(g).items().isEmpty());
        if(FunctionalDatabaseProfile.mysql()){apply(g,tag("café","green"));var old=snapshot(g);assertThrows(ResponseStatusException.class,()->apply(g,tag("cafe","red"),tag("不能写","blue")));assertEquals(old,snapshot(g));}
    }
    @Test void utf8ByteBudgetRejectsLargeMergedDefinitionsAndOversizeStoredOptionsBeforeCopying() throws Exception {
        var f=fixture();var options=java.util.stream.IntStream.range(0,100).mapToObj(n->String.format("%03d",n)+"字".repeat(60)).toList();var rows=new ArrayList<Item>();
        for(var type:List.of("REQUIREMENT","CASE","PLAN")) for(int n=0;n<60;n++) rows.add(field(type,"large_"+n,"大定义",options));
        var request=request(f,rows.toArray(Item[]::new));assertTrue(json.writeValueAsBytes(request).length<FunctionalGovernanceSyncService.MAX_BYTES);
        var error=assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request));assertEquals(400,error.getStatusCode().value());assertTrue(error.getReason().contains("3 MiB"));assertTrue(snapshot(f).items().isEmpty());
        var stored=fixture();var huge=java.util.stream.IntStream.range(0,11000).mapToObj(n->String.format("%05d",n)+"x".repeat(75)).toList();
        for(int n=0;n<4;n++) governance.createField(stored.actor(),stored.project(),new FunctionalGovernanceService.CustomFieldInput("CASE","既有大字段","large_"+n,"SELECT",false,huge));
        assertEquals(4,jdbc.sql("SELECT COUNT(*) FROM functional_custom_field WHERE project_id=:p").param("p",stored.project()).query(Integer.class).single());assertTrue(assertThrows(ResponseStatusException.class,()->snapshot(stored)).getReason().contains("3 MiB"));
    }
    @Test void tagDuplicateEditingAndFieldIdentityChangesPreserveOriginalDefinitions() {
        var f=fixture();seed(f);var original=governance.listTags(f.actor(),f.project()).getFirst();governance.createTag(f.actor(),f.project(),new FunctionalGovernanceService.TagInput("已存在","red"));
        assertThrows(ResponseStatusException.class,()->governance.updateTag(f.actor(),f.project(),original.id(),new FunctionalGovernanceService.TagInput("已存在","purple")));assertEquals(original,governance.listTags(f.actor(),f.project()).stream().filter(t->t.id().equals(original.id())).findFirst().orElseThrow());
        var field=governance.listFields(f.actor(),f.project(),"CASE").getFirst();assertThrows(ResponseStatusException.class,()->governance.updateField(f.actor(),f.project(),field.id(),new FunctionalGovernanceService.CustomFieldInput("PLAN",field.name(),field.fieldKey(),"TEXT",false,List.of())));assertThrows(ResponseStatusException.class,()->governance.updateField(f.actor(),f.project(),field.id(),new FunctionalGovernanceService.CustomFieldInput("CASE",field.name(),"changed_key","TEXT",false,List.of())));
    }
}
