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
import io.testhub.platform.version.*;
import io.testhub.platform.version.ProjectVersionSyncService.*;
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
class ProjectVersionSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"project-version-sync-fixture");
        else {r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"project-version-sync-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired ProjectVersionSyncService sync;
    @Autowired ProjectVersionService versions;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {var a="project-version-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(a,a+"@example.test","同步验收","Sync-fixture-2026!"));var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());}
    Item item(String kind,String name,Object definition) {return new Item(kind,name,json.convertValue(definition,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}
    Item version(String number,String description) {return item("VERSION",number,new Definition(List.of("build-2","build-1"),"2026-12-31",description));}
    Snapshot snapshot(Fixture f) {return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
    Request request(Fixture f,Item... items) {return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList());}
    void apply(Fixture f,Item... items) {sync.apply(f.actor(),f.project(),request(f,items));}
    void seed(Fixture f) {apply(f,version("v1.0","原文"));}
    ProjectVersion row(Fixture f) {return versions.list(f.actor(),f.project()).getFirst();}
    ProjectVersionService.VersionInput manual(ProjectVersion v,String number,String description) {return new ProjectVersionService.VersionInput(number,v.buildNumbers(),v.status(),v.plannedReleaseDate(),v.actualReleaseDate(),description);}
    @Test void portableOrderedBuildsAndDateUseNewIdsWithoutCopyingReleaseState() throws Exception {
        var source=fixture();seed(source);var old=row(source);
        versions.updateStatus(source.actor(),source.project(),old.id(),new ProjectVersionService.StatusInput("RELEASED",java.time.LocalDate.of(2026,10,1)));
        var target=fixture();apply(target,snapshot(source).items().toArray(Item[]::new));var copy=row(target);
        assertEquals(snapshot(source).items(),snapshot(target).items());assertNotEquals(old.id(),copy.id());assertEquals("UNSTARTED",copy.status());assertNull(copy.actualReleaseDate());assertEquals(target.actor().userId(),copy.createdBy());assertEquals(List.of("build-2","build-1"),copy.buildNumbers());assertFalse(json.writeValueAsString(snapshot(target).items()).contains(copy.id()));
    }
    @Test void existingReleaseStateActualDateCreationAndIdentitySurviveDefinitionUpdates() {
        var f=fixture();seed(f);versions.updateStatus(f.actor(),f.project(),row(f).id(),new ProjectVersionService.StatusInput("RELEASED",java.time.LocalDate.of(2026,10,1)));var before=row(f);
        apply(f,item("VERSION","v1.0",new Definition(List.of("build-3","build-2"),"2027-01-02","更新")));var after=row(f);
        assertEquals(before.id(),after.id());assertEquals(before.displayId(),after.displayId());assertEquals(before.createdAt(),after.createdAt());assertEquals(before.createdBy(),after.createdBy());assertEquals(before.status(),after.status());assertEquals(before.actualReleaseDate(),after.actualReleaseDate());assertEquals(List.of("build-3","build-2"),after.buildNumbers());assertEquals(java.time.LocalDate.of(2027,1,2),after.plannedReleaseDate());assertEquals("更新",after.description());
    }
    @Test void explicitClearsAndIdenticalDefinitionsPreserveRowsAndRevision() {
        var f=fixture();seed(f);apply(f,item("VERSION","v1.0",new Definition(List.of(),null,"")));var before=row(f);assertTrue(before.buildNumbers().isEmpty());assertNull(before.plannedReleaseDate());assertEquals("",before.description());var snapshot=snapshot(f);apply(f,snapshot.items().toArray(Item[]::new));assertEquals(before,row(f));assertEquals(snapshot,snapshot(f));
    }
    @Test void parallelDurableReplayDoesNotDuplicateOrUndoLaterEdits() throws Exception {
        var f=fixture();var r=request(f,version("v1.0","初值"));try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var result=sync.apply(f.actor(),f.project(),r);apply(f,version("v1.0","后改"));assertEquals(result,sync.apply(f.actor(),f.project(),r));assertEquals("后改",row(f).description());assertEquals(row(f).id(),result.results().getFirst().itemId());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(UUID.randomUUID().toString(),version("v2.0","不同请求"))))));
    }
    @Test void releaseWritesSerializeWithRevisionComparison() throws Exception {
        var f=fixture();seed(f);var r=request(f,version("v1.0","同步"));var tx=new TransactionTemplate(transactionManager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        try(var pool=Executors.newFixedThreadPool(2)){var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var a=pool.submit(()->tx.execute(status->{jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",f.project()).query(String.class).single();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}return versions.updateStatus(f.actor(),f.project(),row(f).id(),new ProjectVersionService.StatusInput("RELEASED",null));}));assertTrue(locked.await(10,TimeUnit.SECONDS));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));try{assertThrows(TimeoutException.class,()->b.get(200,TimeUnit.MILLISECONDS));}finally{release.countDown();}a.get(20,TimeUnit.SECONDS);assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->b.get(20,TimeUnit.SECONDS)).getCause());assertEquals("RELEASED",row(f).status());assertEquals("原文",row(f).description());}
    }
    @Test void invalidProtectedFieldsDatesAndBuildsRejectTheEntireBatch() throws Exception {
        var f=fixture();seed(f);var before=snapshot(f);
        for(var changed:List.of(Map.of("status","RELEASED"),Map.of("actualReleaseDate","2026-10-10"),Map.of("plannedReleaseDate","2026-02-30"),Map.of("plannedReleaseDate","0000-01-01"),Map.of("buildNumbers",List.of("x","x")),Map.of("buildNumbers",List.of("x\ny")),Map.of("description"," untrimmed "))) {
            var data=new HashMap<>(version("v1.0","失败").definition());data.putAll(changed);assertThrows(ResponseStatusException.class,()->apply(f,version("v-new","不得写"),new Item("VERSION","v1.0",data)));assertEquals(before,snapshot(f));
        }
        assertThrows(ResponseStatusException.class,()->apply(f,version("bad\nversion","失败")));var body=json.writeValueAsString(request(f,version("v1.0","失败")));assertThrows(ResponseStatusException.class,()->sync.decode((body+" {}").getBytes()));
    }
    @Test void fileLinksStayReadableButCannotBeAutomaticallyCopied() {
        var f=fixture();seed(f);versions.update(f.actor(),f.project(),row(f).id(),manual(row(f),"v1.0","/api/v1/projects/"+f.project()+"/files/file/download"));var target=fixture();assertThrows(ResponseStatusException.class,()->apply(target,snapshot(f).items().toArray(Item[]::new)));assertTrue(snapshot(target).items().isEmpty());apply(f,version("unrelated","正常"));assertEquals(2,snapshot(f).items().size());
    }
    @Test void auditFailureRollsBackDefinitionsAndReceipt() {
        var f=fixture();seed(f);var before=snapshot(f);var r=request(f,version("v1.0","不能写"));doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("PROJECT_VERSION_SYNCED"),anyString(),anyString(),anyMap());try{assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM project_version_sync_receipt WHERE project_id=:p AND request_key=:k").param("p",f.project()).param("k",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void projectRoleActorCsrfAndByteLimitsRemainMandatory() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");var url="/api/v1/projects/"+f.project()+"/versions/sync";mvc.perform(get(url).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/versions/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());var body=json.writeValueAsBytes(request(f,version("v1","正常")));mvc.perform(post(url).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(url).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId()));assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[ProjectVersionSyncService.MAX_BYTES+1])).getStatusCode().value());
    }
    @Test void countBytesAndCollationBoundsAreAtomic() {
        var f=fixture();var rows=new ArrayList<Item>();for(int n=0;n<100;n++) rows.add(version("v"+n,"短"));apply(f,rows.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,version("over","超额")));assertEquals(before,snapshot(f));
        var g=fixture();rows.clear();for(int n=0;n<60;n++) rows.add(version("v"+n,"大".repeat(20000)));assertThrows(ResponseStatusException.class,()->apply(g,rows.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
        if(FunctionalDatabaseProfile.mysql()){apply(g,version("café","原值"));var old=snapshot(g);assertThrows(ResponseStatusException.class,()->apply(g,version("cafe","冲突"),version("other","不得写")));assertEquals(old,snapshot(g));}
    }
    @Test void renameAndSameNumberRecreationInvalidateOldRevisions() {
        var f=fixture();seed(f);var r=request(f,version("v1.0","过期"));var old=row(f);versions.delete(f.actor(),f.project(),old.id());versions.create(f.actor(),f.project(),manual(old,"v1.0",old.description()));assertNotEquals(old.id(),row(f).id());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r));var rename=request(f,version("v1.0","过期"));versions.update(f.actor(),f.project(),row(f).id(),manual(row(f),"v-renamed",old.description()));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),rename));assertEquals("v-renamed",row(f).versionNumber());
    }
}
