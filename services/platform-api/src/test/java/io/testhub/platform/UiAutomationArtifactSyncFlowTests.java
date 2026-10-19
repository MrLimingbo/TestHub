package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.uiautomation.*;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import io.testhub.platform.storage.ObjectStorage;
import io.testhub.platform.storage.FilesystemObjectStorage;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
@org.springframework.context.annotation.Import(UiAutomationArtifactSyncFlowTests.StorageFaults.class)
class UiAutomationArtifactSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("testhub.storage.type", () -> "filesystem");
        registry.add("testhub.storage.directory", () -> directory.resolve("objects").toString());
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry, directory, "artifact-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory", directory::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa"); registry.add("spring.datasource.password", () -> "artifact-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired UiAutomationArtifactSyncService sync;
    @Autowired UiAutomationHistorySyncService histories;
    @Autowired UiAutomationService executions;
    @Autowired UiAutomationRepository repository;
    @Autowired UiAutomationExecutionArtifactService nativeArtifacts;
    @Autowired UiAutomationArtifactTransferStorage transfer;
    @Autowired ControlledStorage storage;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor,String project,String organization) { }
    Fixture fixture() {
        String account="artifact-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","原始工件验收","Artifact-fixture-2026!"));
        var context=access.context(actor);String org=context.organizations().getFirst().id();return new Fixture(actor,context.projects().get(org).getFirst().id(),org);
    }
    static final byte[] PNG="timeStamp,elapsed,label,success\r\n1,2,原始标签,true\r\n".getBytes(StandardCharsets.UTF_8);
    static final byte[] TRACE="private-fixture-url?test=raw\nfixture-authorization=raw\n\u0000完整日志\r\n".getBytes(StandardCharsets.UTF_8);
    UiAutomationExecution seed(Fixture f,boolean terminal) {
        String org=access.context(f.actor()).organizations().getFirst().id(),profileId=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),scenarioId=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        repository.insertProfile(profileId,org,f.project(),new UiAutomationRepository.ProfileInput("执行时浏览器"+profileId,"chromium","https://ui.example.test/private-fixture-url",1280,720,true),f.actor().userId());
        var steps=List.of(new UiAutomationStep("navigate","NAVIGATE","打开页面",null,null,null,"/private-page",null,30000),new UiAutomationStep("fill","FILL","填写内容","#private-selector",null,"fixture-withheld-input",null,null,30000));
        repository.insertScenario(scenarioId,org,f.project(),new UiAutomationRepository.ScenarioInput(profileId,"执行时场景"+scenarioId,"","STOP",Map.of("private-variable","fixture-withheld-variable"),steps),f.actor().userId());
        var scenario=repository.findScenario(f.project(),scenarioId).orElseThrow();var profile=repository.findProfile(f.project(),profileId).orElseThrow();
        String id=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),task=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();var now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insertExecution(id,task,org,f.project(),scenario,profile,null,null,null,null,0,"private-manifest-fixture",f.actor().userId(),now,now.plusSeconds(120));
        repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(task,id,"ui-fixture-runner","RUNNING","RUNNING","private error fixture",now),"RUNNING");
        var execution=repository.findExecution(f.project(),id).orElseThrow();
        repository.insertStepResult(execution,new UiAutomationRunnerProtocol.StepResult(task,id,"ui-fixture-runner","navigate",1,"NAVIGATE","打开页面","PASSED",12,null,now));
        nativeArtifacts.archive("ui-fixture-runner",new UiAutomationExecutionArtifactService.Upload(task,id,"SCREENSHOT",PNG.length,sync.hash(PNG)),new ByteArrayInputStream(PNG));
        nativeArtifacts.archive("ui-fixture-runner",new UiAutomationExecutionArtifactService.Upload(task,id,"TRACE",TRACE.length,sync.hash(TRACE)),new ByteArrayInputStream(TRACE));
        if(terminal)repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(task,id,"ui-fixture-runner","SUCCEEDED","EXECUTION_SUCCEEDED","private error fixture",now.plusMillis(50)),"SUCCEEDED");
        return repository.findExecution(f.project(),id).orElseThrow();
    }
    void history(Fixture source,Fixture target,UiAutomationExecution e) {
        var item=histories.read(source.actor(),source.project(),source.actor().userId(),source.project()+"."+e.id());
        histories.apply(target.actor(),target.project(),new UiAutomationHistorySyncService.Request(UUID.randomUUID().toString(),target.actor().userId(),histories.inventory(target.actor(),target.project(),target.actor().userId()).revision(),List.of(item)));
    }
    UiAutomationArtifactSyncService.Inventory inventory(Fixture f) {return sync.inventory(f.actor(),f.project(),f.actor().userId());}
    UiAutomationArtifactSyncService.Entry entry(Fixture f,String type) {return inventory(f).entries().stream().filter(e->e.artifactType().equals(type)).findFirst().orElseThrow();}
    UiAutomationArtifactSyncService.Entry imported(UiAutomationArtifactSyncService.Entry e) {return new UiAutomationArtifactSyncService.Entry(e.key(),e.sourceArtifactId(),e.artifactType(),e.originalName(),e.contentType(),e.sizeBytes(),e.checksum(),e.archivedAt(),false);}
    UiAutomationArtifactSyncService.Request request(Fixture target,UiAutomationArtifactSyncService.Entry source,byte[] bytes) {return new UiAutomationArtifactSyncService.Request(UUID.randomUUID().toString(),target.actor().userId(),inventory(target).revision(),true,imported(source),bytes);}
    int count(String table,Fixture f) {return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",f.project()).query(Integer.class).single();}
    String route(Fixture f) {return "/api/v1/projects/"+f.project()+"/ui-automation";}
    @Test void copiesExactOriginalBytesAndMetadataWithoutCreatingRunnerRecords() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);
        for(var type:List.of("SCREENSHOT","TRACE")){
            var entry=entry(source,type);byte[] bytes=type.equals("SCREENSHOT")?PNG:TRACE;var req=request(target,entry,bytes);
            var receipt=sync.apply(target.actor(),target.project(),req);assertEquals(receipt,sync.apply(target.actor(),target.project(),req));
            try(var input=sync.download(target.actor(),target.project(),entry.key()).content()){assertArrayEquals(bytes,input.readAllBytes());}
            var response=mvc.perform(get(route(target)+"/artifacts/"+entry.key()+"/download").with(user(target.actor()))).andExpect(status().isOk()).andReturn().getResponse();
            assertArrayEquals(bytes,response.getContentAsByteArray());assertEquals(entry.checksum(),response.getHeader("X-TestHub-Artifact-SHA256"));assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
        }
        assertEquals(2,sync.list(target.actor(),target.project(),source.project()+"."+e.id()).size());
        assertTrue(inventory(target).entries().stream().noneMatch(UiAutomationArtifactSyncService.Entry::nativeRecord));
        assertEquals(2,count("ui_artifact_archive",target));assertEquals(0,count("ui_automation_execution",target));assertEquals(0,count("ui_automation_execution_artifact",target));
        assertEquals(0,count("ui_artifact_transfer_intent",target));assertEquals(1,count("ui_history_archive",target));assertEquals(e,repository.findExecution(source.project(),e.id()).orElseThrow());
    }
    @Test void runningArtifactsRemainAtOriginAndMissingHistoryBlocksImport() {
        var source=fixture();var target=fixture();var e=seed(source,false);assertTrue(inventory(source).entries().isEmpty());
        repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(e.taskId(),e.id(),"ui-fixture-runner","SUCCEEDED","EXECUTION_SUCCEEDED","fixture",Instant.now().plusMillis(5).truncatedTo(ChronoUnit.MILLIS)),"SUCCEEDED");
        var req=request(target,entry(source,"SCREENSHOT"),PNG);assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),req));assertEquals(0,count("ui_artifact_archive",target));
    }
    @Test void emptyArtifactIsPreservedAndDifferentContentCanNeverOverwriteIt() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);
        var original=entry(source,"TRACE");
        var empty=new UiAutomationArtifactSyncService.Entry(original.key(),original.sourceArtifactId(),original.artifactType(),original.originalName(),original.contentType(),0,sync.hash(new byte[0]),original.archivedAt(),false);
        var req=request(target,empty,new byte[0]);sync.apply(target.actor(),target.project(),req);
        assertEquals(0,sync.download(target.actor(),target.project(),empty.key()).content().readAllBytes().length);
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),request(target,original,TRACE)));assertEquals(1,count("ui_artifact_archive",target));
    }
    @Test void confirmationAccountCsrfAndProjectPermissionsAreRequired() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);var entry=entry(source,"SCREENSHOT");var req=request(target,entry,PNG);
        var unconfirmed=new UiAutomationArtifactSyncService.Request(req.requestKey(),req.expectedUserId(),req.expectedRevision(),false,req.artifact(),req.content());
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),unconfirmed));
        assertThrows(ResponseStatusException.class,()->sync.inventory(target.actor(),target.project(),source.actor().userId()));
        mvc.perform(post(route(target)+"/artifacts/sync").with(user(target.actor())).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isForbidden());
        mvc.perform(post(route(target)+"/artifacts/sync").with(user(source.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isForbidden());
        sync.apply(target.actor(),target.project(),req);
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:u").param("u",target.actor().userId()).update();
        mvc.perform(get(route(target)+"/artifacts/"+entry.key()+"/download").with(user(target.actor()))).andExpect(status().isOk());
        mvc.perform(get(route(target)+"/artifacts/"+entry.key()+"/download").with(user(source.actor()))).andExpect(status().isForbidden());
        mvc.perform(post(route(target)+"/artifacts/sync").with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req))).andExpect(status().isForbidden());
    }
    @Test void rejectsUnknownFieldsBadBytesStaleRevisionsAndNativeOverwrite() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);var req=request(target,entry(source,"SCREENSHOT"),PNG);
        history(source,target,e);assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),req));
        var current=request(target,entry(source,"SCREENSHOT"),PNG);var tree=json.valueToTree(current);((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("extraRawField","not-supported");
        assertThrows(ResponseStatusException.class,()->sync.decode(json.writeValueAsBytes(tree)));
        var bad=new UiAutomationArtifactSyncService.Request(current.requestKey(),current.expectedUserId(),current.expectedRevision(),true,current.artifact(),TRACE);
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),bad));
        assertThrows(ResponseStatusException.class,()->sync.apply(source.actor(),source.project(),request(source,entry(source,"SCREENSHOT"),PNG)));
        assertEquals(0,count("ui_artifact_archive",target));
    }
    @Test void receiptKeyCannotBeReusedAndMissingCommittedMetadataIsNotRecreated() {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);var req=request(target,entry(source,"SCREENSHOT"),PNG);
        sync.apply(target.actor(),target.project(),req);
        var changed=new UiAutomationArtifactSyncService.Request(req.requestKey(),req.expectedUserId(),req.expectedRevision(),true,imported(entry(source,"TRACE")),TRACE);
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),changed));
        jdbc.sql("DELETE FROM ui_artifact_archive WHERE project_id=:p").param("p",target.project()).update();
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),req));assertEquals(0,count("ui_artifact_archive",target));
    }
    @Test void corruptStorageCannotBeDownloadedAndOriginalMetadataIsRetained() throws Exception {
        var source=fixture();var e=seed(source,true);var entry=entry(source,"SCREENSHOT");
        var key=jdbc.sql("SELECT object_key FROM ui_automation_execution_artifact WHERE project_id=:p AND artifact_type='SCREENSHOT'").param("p",source.project()).query(String.class).single();
        var bucket=jdbc.sql("SELECT bucket FROM ui_artifact_transfer_intent WHERE id='absent'").query(String.class).optional();assertTrue(bucket.isEmpty());
        String actualBucket=storageBucket();storage.put(actualBucket,key,new ByteArrayInputStream(TRACE),TRACE.length,"image/png");
        var error=assertThrows(ResponseStatusException.class,()->sync.download(source.actor(),source.project(),entry.key()));assertEquals(502,error.getStatusCode().value());
        assertEquals(2,count("ui_automation_execution_artifact",source));
    }
    @org.springframework.beans.factory.annotation.Value("${testhub.storage.bucket}") String bucket;
    String storageBucket(){return bucket;}
    @Test void databaseRollbackRemovesOnlyNewObjectAndLeavesRetrySafe() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);var req=request(target,entry(source,"SCREENSHOT"),PNG);
        var object=new java.util.concurrent.atomic.AtomicReference<String>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{sync.apply(target.actor(),target.project(),req);object.set(jdbc.sql("SELECT object_key FROM ui_artifact_archive WHERE project_id=:p").param("p",target.project()).query(String.class).single());status.setRollbackOnly();});
        assertThrows(Exception.class,()->storage.get(bucket,object.get()));assertEquals(0,count("ui_artifact_archive",target));assertEquals(0,count("ui_artifact_sync_receipt",target));assertEquals(0,count("ui_artifact_transfer_intent",target));
        sync.apply(target.actor(),target.project(),req);assertEquals(1,count("ui_artifact_archive",target));
    }
    @Test void failedObjectWriteIsRecoveredBeforeAnotherRetry() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);var req=request(target,entry(source,"SCREENSHOT"),PNG);
        storage.failAfterPut=true;
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),req));
        assertThrows(Exception.class,()->storage.get(bucket,storage.lastFailedKey));assertEquals(0,count("ui_artifact_transfer_intent",target));assertEquals(0,count("ui_artifact_archive",target));
        sync.apply(target.actor(),target.project(),req);
    }
    @Test void staleIntentRecoversOrphansAndNeverDeletesCommittedBytes() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);sync.apply(target.actor(),target.project(),request(target,entry(source,"SCREENSHOT"),PNG));
        var stored=jdbc.sql("SELECT id,object_key FROM ui_artifact_archive WHERE project_id=:p").param("p",target.project()).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).single();
        String orphan=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),orphanKey="owned-qa/"+orphan;
        storage.put(bucket,orphanKey,new ByteArrayInputStream(TRACE),TRACE.length,"application/zip");
        for(var row:List.of(stored,Map.entry(orphan,orphanKey)))jdbc.sql("INSERT INTO ui_artifact_transfer_intent(id,organization_id,project_id,bucket,object_key,created_at) VALUES(:id,:o,:p,:b,:k,:at)")
                .param("id",row.getKey()).param("o",target.organization()).param("p",target.project()).param("b",bucket).param("k",row.getValue()).param("at",java.sql.Timestamp.from(Instant.now().minusSeconds(7200))).update();
        transfer.recoverStale();assertThrows(Exception.class,()->storage.get(bucket,orphanKey));try(var content=storage.get(bucket,stored.getValue())){assertArrayEquals(PNG,content.readAllBytes());}assertEquals(0,count("ui_artifact_transfer_intent",target));
    }

    @Test void twentyFiveMiBBase64BodyDecodesAndPersistsWhileLargerFilesAndBodiesFail() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,true);history(source,target,e);var original=entry(source,"TRACE");
        byte[] bytes=new byte[UiAutomationArtifactSyncService.MAX_FILE];java.util.Arrays.fill(bytes,(byte)'x');
        var large=new UiAutomationArtifactSyncService.Entry(original.key(),original.sourceArtifactId(),original.artifactType(),original.originalName(),original.contentType(),bytes.length,sync.hash(bytes),original.archivedAt(),false);
        var req=request(target,large,bytes);var decoded=sync.decode(json.writeValueAsBytes(req));sync.apply(target.actor(),target.project(),decoded);
        try(var content=sync.download(target.actor(),target.project(),large.key()).content()){assertArrayEquals(bytes,content.readAllBytes());}
        var oversized=new UiAutomationArtifactSyncService.Entry(original.key(),original.sourceArtifactId(),original.artifactType(),original.originalName(),original.contentType(),bytes.length+1L,original.checksum(),original.archivedAt(),false);
        assertThrows(ResponseStatusException.class,()->sync.apply(target.actor(),target.project(),request(target,oversized,TRACE)));
        var rejected=assertThrows(ResponseStatusException.class,()->sync.decode(new byte[UiAutomationArtifactSyncService.MAX_BODY+1]));assertEquals(413,rejected.getStatusCode().value());
    }
    @Test void multipleScreenshotsAndAllThreeKindsPreserveIndependentIdsAndViewPolicy() throws Exception {
        var source=fixture();var target=fixture();var e=seed(source,false);byte[] extra="second screenshot".getBytes(StandardCharsets.UTF_8),video="video bytes".getBytes(StandardCharsets.UTF_8);
        nativeArtifacts.archive("ui-fixture-runner",new UiAutomationExecutionArtifactService.Upload(e.taskId(),e.id(),"SCREENSHOT",extra.length,sync.hash(extra)),new ByteArrayInputStream(extra));
        nativeArtifacts.archive("ui-fixture-runner",new UiAutomationExecutionArtifactService.Upload(e.taskId(),e.id(),"VIDEO",video.length,sync.hash(video)),new ByteArrayInputStream(video));
        repository.applyRunnerEvent(new UiAutomationRunnerProtocol.Event(e.taskId(),e.id(),"ui-fixture-runner","SUCCEEDED","EXECUTION_SUCCEEDED","fixture",e.requestedAt().plusMillis(50)),"SUCCEEDED");history(source,target,e);
        for(var file:inventory(source).entries()){
            byte[] data=file.artifactType().equals("VIDEO")?video:file.artifactType().equals("TRACE")?TRACE:file.checksum().equals(sync.hash(extra))?extra:PNG;
            sync.apply(target.actor(),target.project(),request(target,file,data));
            mvc.perform(get(route(target)+"/artifacts/"+file.key()+"/view").with(user(target.actor()))).andExpect(file.artifactType().equals("TRACE")?status().isNotAcceptable():status().isOk());
        }
        assertEquals(4,sync.list(target.actor(),target.project(),source.project()+"."+e.id()).size());
        assertEquals(2,inventory(target).entries().stream().filter(v->v.artifactType().equals("SCREENSHOT")).count());
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class StorageFaults {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        ControlledStorage artifactTestStorage(@org.springframework.beans.factory.annotation.Value("${testhub.storage.directory}") String path) throws IOException {
            return new ControlledStorage(new FilesystemObjectStorage(Path.of(path)));
        }
    }
    static class ControlledStorage implements ObjectStorage {
        private final ObjectStorage delegate;
        boolean failAfterPut; String lastFailedKey;
        ControlledStorage(ObjectStorage delegate){this.delegate=delegate;}
        public boolean bucketExists(String bucket)throws Exception{return delegate.bucketExists(bucket);}
        public void ensureBucket(String bucket)throws Exception{delegate.ensureBucket(bucket);}
        public void put(String bucket,String key,java.io.InputStream input,long size,String type)throws Exception{
            delegate.put(bucket,key,input,size,type);
            if(failAfterPut){failAfterPut=false;lastFailedKey=key;throw new IOException("owned QA storage failure");}
        }
        public java.io.InputStream get(String bucket,String key)throws Exception{return delegate.get(bucket,key);}
        public void remove(String bucket,String key)throws Exception{delegate.remove(bucket,key);}
        public String label(){return delegate.label();}
    }

}
