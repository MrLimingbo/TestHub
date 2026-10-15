package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.*;
import io.testhub.platform.functional.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles(resolver=FunctionalDatabaseProfile.class) @DirtiesContext
class FunctionalTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry,directory,"transfer-test-only");
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalTestExecutionService executions;
    @Autowired FunctionalGovernanceService governance;
    @Autowired FunctionalTransferService transfer;
    @Autowired FunctionalDefectService defects;
    @Autowired io.testhub.platform.storage.FileObjectService projectFiles;
    @Autowired io.testhub.platform.storage.FileTransferStorage fileStorage;
    @org.springframework.test.context.bean.override.convention.TestBean
    io.testhub.platform.storage.ObjectStorage objectStorage;
    static io.testhub.platform.storage.ObjectStorage objectStorage() throws Exception {
        if (FunctionalDatabaseProfile.mysql()) {
            var client=io.minio.MinioClient.builder().endpoint(System.getenv("TESTHUB_MINIO_ENDPOINT"))
                    .credentials(System.getenv("TESTHUB_MINIO_ACCESS_KEY"),System.getenv("TESTHUB_MINIO_SECRET_KEY")).build();
            return org.mockito.Mockito.spy(new StorageDelegate(new io.testhub.platform.storage.MinioObjectStorage(client)));
        }
        return org.mockito.Mockito.spy(new StorageDelegate(new io.testhub.platform.storage.FilesystemObjectStorage(directory.resolve("objects"))));
    }
    @org.junit.jupiter.api.AfterEach void resetStorageFaults() { org.mockito.Mockito.reset(objectStorage); }
    static class StorageDelegate implements io.testhub.platform.storage.ObjectStorage {
        private final io.testhub.platform.storage.ObjectStorage delegate;
        StorageDelegate(io.testhub.platform.storage.ObjectStorage delegate) { this.delegate=delegate; }
        public boolean bucketExists(String bucket) throws Exception { return delegate.bucketExists(bucket); }
        public void ensureBucket(String bucket) throws Exception { delegate.ensureBucket(bucket); }
        public void put(String bucket,String key,java.io.InputStream input,long size,String type) throws Exception { delegate.put(bucket,key,input,size,type); }
        public java.io.InputStream get(String bucket,String key) throws Exception { return delegate.get(bucket,key); }
        public void remove(String bucket,String key) throws Exception { delegate.remove(bucket,key); }
        public String label() { return delegate.label(); }
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Value("${testhub.storage.bucket}") String bucket;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    record Workspace(PlatformUserPrincipal user, String project) {}
    Workspace workspace() {
        String account = "transfer-" + UUID.randomUUID();
        var user = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "迁移测试", "Transfer-test-2026!"));
        var context = access.context(user);
        return new Workspace(user, context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    FunctionalTransferService.Snapshot snapshot(Workspace workspace) {
        var parent = assets.createCatalog(workspace.user(), workspace.project(), new FunctionalAssetService.CatalogInput("重复名称", null, 3, "REQUIREMENTS"));
        var nested = assets.createCatalog(workspace.user(), workspace.project(), new FunctionalAssetService.CatalogInput("重复名称", parent.id(), 7, "REQUIREMENTS"));
        var cases = assets.createCatalog(workspace.user(), workspace.project(), new FunctionalAssetService.CatalogInput("用例目录", null, 1, "CASES"));
        var requirement = assets.createRequirement(workspace.user(), workspace.project(), new FunctionalAssetService.RequirementInput(nested.id(), "REQ_TRANSFER", "保留中文需求", "P0", "APPROVED", "需求说明"));
        assets.createCase(workspace.user(), workspace.project(), new FunctionalAssetService.CaseInput(cases.id(), requirement.id(), "CASE_TRANSFER", "保留关联", "P1", "READY", "SECURITY", "前置条件", "执行步骤", "预期结果"));
        return transfer.export(workspace.user(), workspace.project());
    }
    FunctionalTransferService.ImportRequest request(Workspace target, FunctionalTransferService.Snapshot snapshot) {
        return new FunctionalTransferService.ImportRequest(UUID.randomUUID().toString(), target.user().userId(), snapshot);
    }
    int count(String table, String project) { return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id=:project").param("project",project).query(Integer.class).single(); }
    @Test void preservesHierarchyAssociationsAndTypeUsingNewScopedIdsAndIdempotentReceipt() {
        var source = workspace(); var target = workspace(); var snapshot = snapshot(source); var request = request(target, snapshot);
        var result = transfer.transfer(target.user(), target.project(), request);
        assertEquals(1, result.requirements()); assertEquals(1, result.cases()); assertEquals(3, result.catalogs());
        var imported = transfer.export(target.user(), target.project());
        var requirement = imported.requirements().getFirst(); var testCase = imported.cases().getFirst();
        assertEquals(requirement.id(), testCase.requirementId()); assertNotEquals(snapshot.requirements().getFirst().id(), requirement.id());
        assertEquals("SECURITY", testCase.caseType()); assertEquals("前置条件", testCase.precondition());
        var child = imported.catalogs().stream().filter(item -> item.id().equals(requirement.catalogId())).findFirst().orElseThrow();
        var parent = imported.catalogs().stream().filter(item -> item.id().equals(child.parentId())).findFirst().orElseThrow();
        assertEquals("重复名称", child.name()); assertEquals("重复名称", parent.name()); assertEquals(7, child.sortOrder());
        assertEquals(result, transfer.transfer(target.user(), target.project(), request));
        assertEquals(1, count("functional_case", target.project())); assertEquals(1, count("functional_transfer_receipt", target.project()));
        assertEquals(snapshot, transfer.export(source.user(), source.project()));
    }
    @Test void conflictAndLateValidationFailureLeaveNoPartialAssetsOrReceipt() {
        var source = workspace(); var target = workspace(); var snapshot = snapshot(source);
        var item = snapshot.cases().getFirst();
        var invalid = new FunctionalTransferService.Case(item.id(), item.catalogId(), item.requirementId(), item.code(), item.title(), item.priority(), item.status(), "INVALID", item.precondition(), item.steps(), item.expectedResult());
        var bad = new FunctionalTransferService.Snapshot(1, snapshot.catalogs(), snapshot.requirements(), List.of(invalid));
        assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.user(), target.project(), request(target,bad)));
        for (var table : List.of("functional_case","functional_requirement","functional_catalog","functional_transfer_receipt")) assertEquals(0, count(table,target.project()));
        transfer.transfer(target.user(), target.project(), request(target,snapshot));
        var error = assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.user(), target.project(), request(target,snapshot)));
        assertEquals(409, error.getStatusCode().value()); assertEquals(1,count("functional_transfer_receipt",target.project())); assertEquals(5,count("functional_catalog",target.project()));
    }
    @Test void deniesOtherAccountReadWriteAndChangedActor() {
        var source = workspace(); var target = workspace(); var snapshot = snapshot(source);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> transfer.export(target.user(), source.project()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> transfer.transfer(source.user(), target.project(), request(target,snapshot)));
        var request = new FunctionalTransferService.ImportRequest(UUID.randomUUID().toString(), source.user().userId(), snapshot);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.user(), target.project(), request)).getStatusCode().value());
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void rejectsCyclesForeignReferencesAndReusedKeyWithDifferentPayload() {
        var source = workspace(); var target = workspace(); var snapshot = snapshot(source);
        var catalog = snapshot.catalogs().getFirst(); var catalogs = new ArrayList<>(snapshot.catalogs());
        catalogs.set(0,new FunctionalTransferService.Catalog(catalog.id(),catalog.id(),catalog.scope(),catalog.name(),0));
        assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.user(),target.project(),request(target,new FunctionalTransferService.Snapshot(1,catalogs,snapshot.requirements(),snapshot.cases()))));
        var item = snapshot.cases().getFirst();
        var invalid = new FunctionalTransferService.Case(item.id(),item.catalogId(),"foreign-reference",item.code(),item.title(),item.priority(),item.status(),item.caseType(),item.precondition(),item.steps(),item.expectedResult());
        assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.user(),target.project(),request(target,new FunctionalTransferService.Snapshot(1,snapshot.catalogs(),snapshot.requirements(),List.of(invalid)))));
        var request = request(target,snapshot); transfer.transfer(target.user(),target.project(),request);
        var changed = new FunctionalTransferService.Snapshot(1,snapshot.catalogs(),snapshot.requirements(),List.of());
        assertEquals(409,assertThrows(ResponseStatusException.class, () -> transfer.transfer(target.user(),target.project(),new FunctionalTransferService.ImportRequest(request.requestKey(),target.user().userId(),changed))).getStatusCode().value());
    }
    @Test void httpRequiresLoginCsrfAndValidNestedPayload() throws Exception {
        var target=workspace(); var source=workspace(); var snapshot=snapshot(source);
        var path="/api/v1/projects/"+target.project()+"/functional/transfer";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var csrfResult=mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var login=mvc.perform(post("/api/v1/auth/login").cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account",target.user().account().account(),"password","Transfer-test-2026!"))))
                .andExpect(status().isOk()).andReturn();
        var session=login.getResponse().getCookie("TESTHUB_SESSION");
        mvc.perform(post(path).cookie(session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request(target,snapshot)))).andExpect(status().isForbidden());
        csrfResult=mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andReturn(); csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var body=json.valueToTree(request(target,snapshot)); ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("snapshot").path("cases").get(0)).put("steps", "");
        mvc.perform(post(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        var invalidPlan=new FunctionalTransferService.Plan("P".repeat(26),null,"PLAN_HTTP","","READY","",List.of(snapshot.cases().getFirst().id()));
        var v2=new FunctionalTransferService.Snapshot(2,snapshot.catalogs(),snapshot.requirements(),snapshot.cases(),List.of(invalidPlan));
        mvc.perform(post(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(target,v2)))).andExpect(status().isBadRequest());
        var invalidField=new FunctionalTransferGovernance.Field("F".repeat(26),"CASE","字段","field_key","SELECT",false,List.of(""));
        var v3=withGovernance(snapshot,new FunctionalTransferGovernance.Definitions(List.of(),List.of(invalidField),List.of()));
        mvc.perform(post(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(target,v3)))).andExpect(status().isBadRequest());
        var invalidDefect=transferTree(snapshotWithDefects(workspace()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)invalidDefect.path("defects").path("items").get(0)).put("title", "");
        mvc.perform(post(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(target,json.convertValue(invalidDefect,FunctionalTransferService.Snapshot.class))))).andExpect(status().isBadRequest());
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void aReadOnlyProjectMemberCanExportButCannotImport() {
        var reader=workspace(); var target=workspace(); var snapshot=snapshot(target);
        var project=access.project(target.user(),target.project());
        jdbc.sql("INSERT INTO membership(id,user_id,organization_id,project_id,role_code) VALUES(:id,:user,:org,:project,'VIEWER')")
                .param("id",com.github.f4b6a3.ulid.UlidCreator.getUlid().toString()).param("user",reader.user().userId())
                .param("org",project.organizationId()).param("project",target.project()).update();
        assertEquals(snapshot,transfer.export(reader.user(),target.project()));
        assertTrue(transfer.export(reader.user(),target.project(),4,false,false).defects().items().isEmpty());
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> transfer.capabilities(reader.user(),target.project()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> transfer.transfer(reader.user(),target.project(),
                new FunctionalTransferService.ImportRequest(UUID.randomUUID().toString(),reader.user().userId(),snapshot)));
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void concurrentRetriesCommitExactlyOneCopy() throws Exception {
        var source=workspace(); var target=workspace(); var request=request(target,snapshot(source));
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<FunctionalTransferService.Receipt>>();
            for(int i=0;i<2;i++) futures.add(executor.submit(() -> {
                start.await();
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                        new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
                try { return transfer.transfer(target.user(),target.project(),request); }
                catch(ResponseStatusException error) { assertEquals(409,error.getStatusCode().value()); return null; }
                finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
            }));
            start.countDown();
            var first=futures.get(0).get(15,java.util.concurrent.TimeUnit.SECONDS);
            var second=futures.get(1).get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(first!=null || second!=null);
            assertEquals(first!=null ? first : second,transfer.transfer(target.user(),target.project(),request));
        }
        assertEquals(1,count("functional_transfer_receipt",target.project()));
        assertEquals(1,count("functional_requirement",target.project())); assertEquals(1,count("functional_case",target.project()));
    }
    FunctionalTransferService.Snapshot snapshotWithPlans(Workspace source) {
        var definitions=snapshot(source); var first=definitions.cases().getFirst();
        var second=assets.createCase(source.user(),source.project(),new FunctionalAssetService.CaseInput(first.catalogId(),null,
                "CASE_SECOND","第二个用例","P2","READY","FUNCTIONAL","","步骤","预期"));
        var root=assets.createCatalog(source.user(),source.project(),new FunctionalAssetService.CatalogInput("计划目录",null,2,"PLANS"));
        var child=assets.createCatalog(source.user(),source.project(),new FunctionalAssetService.CatalogInput("计划子目录",root.id(),5,"PLANS"));
        var plan=executions.createPlan(source.user(),source.project(),new FunctionalTestExecutionService.PlanInput(child.id(),"PLAN_TRANSFER",
                "本地测试计划","READY","计划说明",List.of(second.id(),first.id())));
        executions.createRun(source.user(),source.project(),plan.id(),"仅留在来源的历史批次");
        executions.createPlan(source.user(),source.project(),new FunctionalTestExecutionService.PlanInput(null,"PLAN_EMPTY","未分类空计划","DRAFT","",List.of()));
        return transfer.export(source.user(),source.project(),2);
    }
    @Test void transfersPlansWithOrderedRemappedCasesAndDirectoriesButNotRuns() {
        var source=workspace(); var target=workspace(); var snapshot=snapshotWithPlans(source); var request=request(target,snapshot);
        var result=transfer.transfer(target.user(),target.project(),request);
        assertEquals(2,result.plans()); assertTrue(result.roots().containsKey("PLANS"));
        var imported=transfer.export(target.user(),target.project(),2);
        var plan=imported.plans().stream().filter(item -> item.code().equals("PLAN_TRANSFER")).findFirst().orElseThrow();
        assertEquals("READY",plan.status()); assertEquals("计划说明",plan.description());
        assertNotEquals(snapshot.plans().stream().filter(item -> item.code().equals("PLAN_TRANSFER")).findFirst().orElseThrow().id(),plan.id());
        assertEquals(List.of("CASE_SECOND","CASE_TRANSFER"),executions.listPlanCases(target.user(),target.project(),plan.id()).stream().map(FunctionalTestPlanCase::caseCode).toList());
        assertTrue(plan.caseIds().stream().allMatch(id -> imported.cases().stream().anyMatch(item -> item.id().equals(id))));
        var child=imported.catalogs().stream().filter(item -> item.id().equals(plan.catalogId())).findFirst().orElseThrow();
        assertEquals("计划子目录",child.name()); assertEquals(5,child.sortOrder());
        assertEquals("计划目录",imported.catalogs().stream().filter(item -> item.id().equals(child.parentId())).findFirst().orElseThrow().name());
        var empty=imported.plans().stream().filter(item -> item.code().equals("PLAN_EMPTY")).findFirst().orElseThrow();
        assertNull(empty.catalogId()); assertTrue(empty.caseIds().isEmpty());
        assertTrue(executions.listRuns(target.user(),target.project(),null).isEmpty());
        assertEquals(result,transfer.transfer(target.user(),target.project(),request));
        var run=executions.createRun(target.user(),target.project(),plan.id(),"账号中新建的批次");
        assertEquals(2,executions.listRunCases(target.user(),target.project(),run.id()).size());
        assertTrue(executions.listRunCases(target.user(),target.project(),run.id()).stream().allMatch(item -> item.status().equals("NOT_RUN")));
        assertEquals(snapshot,transfer.export(source.user(),source.project(),2));
        assertEquals(1,executions.listRuns(source.user(),source.project(),null).size());
    }
    @Test void defaultV1ExportExcludesPlansAndRetainsLegacyReceiptBytes() throws Exception {
        var source=workspace(); var target=workspace(); snapshotWithPlans(source);
        var snapshot=transfer.export(source.user(),source.project());
        assertNull(snapshot.plans()); assertTrue(snapshot.catalogs().stream().noneMatch(item -> item.scope().equals("PLANS")));
        // Reconstruct the exact original four-field shape, independent of the new Snapshot serializer.
        var original=new LinkedHashMap<String,Object>(); original.put("version",1); original.put("catalogs",snapshot.catalogs());
        original.put("requirements",snapshot.requirements()); original.put("cases",snapshot.cases());
        byte[] bytes=json.writeValueAsBytes(original); assertArrayEquals(bytes,json.writeValueAsBytes(snapshot));
        var request=request(target,snapshot); var project=access.project(target.user(),target.project());
        String receiptId=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
        String oldResult="{\"id\":\""+receiptId+"\",\"catalogs\":3,\"requirements\":1,\"cases\":2,\"roots\":{}}";
        jdbc.sql("INSERT INTO functional_transfer_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:org,:project,:user,:key,:digest,:result)")
                .param("id",receiptId).param("org",project.organizationId()).param("project",target.project()).param("user",target.user().userId())
                .param("key",request.requestKey()).param("digest",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))).param("result",oldResult).update();
        var result=transfer.transfer(target.user(),target.project(),request);
        assertEquals(receiptId,result.id()); assertNull(result.plans()); assertEquals(oldResult,json.writeValueAsString(result));
        assertEquals(0,count("functional_test_plan",target.project()));
        assertEquals(List.of(1,2,3,4,5,6,7,8),transfer.capabilities(target.user(),target.project()).get("versions"));
    }
    @Test void planOnlySnapshotWorksAndPlanCodeConflictDoesNotImportDefinitions() {
        var source=workspace(); var target=workspace();
        executions.createPlan(source.user(),source.project(),new FunctionalTestExecutionService.PlanInput(null,"PLAN_ONLY","空计划","ARCHIVED","说明",List.of()));
        var snapshot=transfer.export(source.user(),source.project(),2);
        assertEquals(1,transfer.transfer(target.user(),target.project(),request(target,snapshot)).plans());
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> transfer.transfer(target.user(),target.project(),request(target,snapshot))).getStatusCode().value());
        assertEquals(1,count("functional_test_plan",target.project())); assertEquals(1,count("functional_transfer_receipt",target.project()));
        var source2=workspace(); var target2=workspace(); var withPlans=snapshotWithPlans(source2);
        executions.createPlan(target2.user(),target2.project(),new FunctionalTestExecutionService.PlanInput(null,"PLAN_TRANSFER","已有计划","DRAFT","",List.of()));
        assertEquals(409,assertThrows(ResponseStatusException.class,() -> transfer.transfer(target2.user(),target2.project(),request(target2,withPlans))).getStatusCode().value());
        for(var table:List.of("functional_catalog","functional_requirement","functional_case","functional_transfer_receipt")) assertEquals(0,count(table,target2.project()));
        assertEquals("已有计划",executions.listPlans(target2.user(),target2.project(),null).getFirst().name());
    }
    @Test void invalidPlanAfterCreatingDefinitionsRollsBackEveryAsset() {
        var source=workspace(); var target=workspace(); var snapshot=snapshotWithPlans(source); var plan=snapshot.plans().getFirst();
        var invalid=new FunctionalTransferService.Plan(plan.id(),plan.catalogId(),plan.code(),plan.name(),"INVALID",plan.description(),plan.caseIds());
        var bad=new FunctionalTransferService.Snapshot(2,snapshot.catalogs(),snapshot.requirements(),snapshot.cases(),List.of(invalid));
        assertEquals(400,assertThrows(ResponseStatusException.class,() -> transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        for(var table:List.of("functional_catalog","functional_requirement","functional_case","functional_test_plan","functional_transfer_receipt")) assertEquals(0,count(table,target.project()));
    }
    @Test void rejectsForeignDuplicatePlanReferencesAndVersionDowngrades() {
        var source=workspace(); var target=workspace(); var snapshot=snapshotWithPlans(source); var plan=snapshot.plans().getFirst();
        String caseId=snapshot.cases().getFirst().id();
        for(var references:List.of(List.of("foreign-case"),List.of(caseId,caseId))) {
            var badPlan=new FunctionalTransferService.Plan(plan.id(),plan.catalogId(),plan.code(),plan.name(),plan.status(),plan.description(),references);
            var bad=new FunctionalTransferService.Snapshot(2,snapshot.catalogs(),snapshot.requirements(),snapshot.cases(),List.of(badPlan));
            assertEquals(400,assertThrows(ResponseStatusException.class,() -> transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        }
        for(var bad:List.of(new FunctionalTransferService.Snapshot(1,snapshot.catalogs(),snapshot.requirements(),snapshot.cases(),snapshot.plans()),
                new FunctionalTransferService.Snapshot(2,snapshot.catalogs(),snapshot.requirements(),snapshot.cases()),
                new FunctionalTransferService.Snapshot(3,snapshot.catalogs(),snapshot.requirements(),snapshot.cases(),snapshot.plans()))) {
            assertEquals(400,assertThrows(ResponseStatusException.class,() -> transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        }
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void enforcesPlanAssociationBudgetBeforeWritingAnyAssets() {
        var source=workspace(); var target=workspace(); var original=snapshot(source); var first=original.cases().getFirst();
        var cases=new ArrayList<FunctionalTransferService.Case>();
        for(int i=0;i<501;i++) cases.add(new FunctionalTransferService.Case(com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),
                first.catalogId(),first.requirementId(),"CASE_"+i,first.title(),first.priority(),first.status(),first.caseType(),first.precondition(),first.steps(),first.expectedResult()));
        var caseIds=cases.stream().map(FunctionalTransferService.Case::id).toList();
        var plans=new ArrayList<FunctionalTransferService.Plan>();
        for(int i=0;i<100;i++) plans.add(new FunctionalTransferService.Plan(com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),null,"PLAN_"+i,"计划","READY","",caseIds));
        var oversized=new FunctionalTransferService.Snapshot(2,original.catalogs(),original.requirements(),cases,plans);
        assertTrue(assertThrows(ResponseStatusException.class,() -> transfer.transfer(target.user(),target.project(),request(target,oversized))).getReason().contains("50000"));
        for(var table:List.of("functional_catalog","functional_requirement","functional_case","functional_test_plan","functional_transfer_receipt")) assertEquals(0,count(table,target.project()));
    }
    FunctionalTransferService.Snapshot snapshotWithGovernance(Workspace source) {
        var snapshot=snapshotWithPlans(source);
        var tag=governance.createTag(source.user(),source.project(),new FunctionalGovernanceService.TagInput("迁移标签","#165dff"));
        governance.createTag(source.user(),source.project(),new FunctionalGovernanceService.TagInput("未绑定标签","orange"));
        for(var type:List.of("REQUIREMENT","CASE","PLAN")) {
            String id=switch(type) {case "REQUIREMENT" -> snapshot.requirements().getFirst().id(); case "CASE" -> snapshot.cases().getFirst().id(); default -> snapshot.plans().getFirst().id();};
            governance.replaceAssetTags(source.user(),source.project(),type,id,List.of(tag.id()));
            governance.createField(source.user(),source.project(),new FunctionalGovernanceService.CustomFieldInput(type,"迁移字段", "transfer_field","SELECT",true,List.of("高","中","低")));
        }
        return transfer.export(source.user(),source.project(),3,true);
    }
    FunctionalTransferService.Snapshot withGovernance(FunctionalTransferService.Snapshot base, FunctionalTransferGovernance.Definitions definitions) {
        return new FunctionalTransferService.Snapshot(3,base.catalogs(),base.requirements(),base.cases(),base.plans(),definitions);
    }
    @Test void governanceTransferRemapsSharedTagsPreservesDefinitionsAndRetriesWithoutDuplicates() {
        var source=workspace(); var target=workspace(); var snapshot=snapshotWithGovernance(source); var request=request(target,snapshot);
        var receipt=transfer.transfer(target.user(),target.project(),request);
        assertEquals(new FunctionalTransferGovernance.Counts(2,3,3),receipt.governance());
        var imported=transfer.export(target.user(),target.project(),3,true);
        assertEquals(2,imported.governance().tags().size()); assertEquals(3,imported.governance().fields().size());
        var tag=imported.governance().tags().stream().filter(item->item.name().equals("迁移标签")).findFirst().orElseThrow();
        assertEquals("#165dff",tag.color());assertFalse(snapshot.governance().tags().stream().anyMatch(item->item.id().equals(tag.id())));
        assertEquals(Set.of("REQUIREMENT","CASE","PLAN"),new HashSet<>(imported.governance().bindings().stream().map(FunctionalTransferGovernance.Binding::assetType).toList()));
        for(var binding:imported.governance().bindings()) {
            assertEquals(List.of(tag.id()),binding.tagIds());
            assertEquals(List.of("迁移标签"),governance.listAssetTags(target.user(),target.project(),binding.assetType(),binding.assetId()).stream().map(FunctionalTag::name).toList());
            assertFalse(snapshot.governance().bindings().stream().anyMatch(item->item.assetId().equals(binding.assetId())));
        }
        for(var field:imported.governance().fields()) {assertTrue(field.required());assertEquals("SELECT",field.type());assertEquals(List.of("高","中","低"),field.options());}
        assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(3,count("functional_asset_tag",target.project()));
        assertEquals(snapshot,transfer.export(source.user(),source.project(),3,true));
    }
    @Test void unselectedPlansExcludePlanFieldsAndBindingsAndMetadataOnlyProjectsWork() {
        var source=workspace();snapshotWithGovernance(source);
        var selected=transfer.export(source.user(),source.project(),3,false);
        assertNull(selected.plans());assertEquals(2,selected.governance().fields().size());assertEquals(2,selected.governance().bindings().size());
        assertTrue(selected.governance().fields().stream().noneMatch(item->item.assetType().equals("PLAN")));
        assertTrue(selected.catalogs().stream().noneMatch(item->item.scope().equals("PLANS")));
        var target=workspace();transfer.transfer(target.user(),target.project(),request(target,selected));
        assertEquals(0,count("functional_test_plan",target.project()));assertEquals(2,count("functional_asset_tag",target.project()));
        var metadataOnly=workspace();var destination=workspace();
        governance.createTag(metadataOnly.user(),metadataOnly.project(),new FunctionalGovernanceService.TagInput("独立标签","green"));
        governance.createField(metadataOnly.user(),metadataOnly.project(),new FunctionalGovernanceService.CustomFieldInput("CASE","数字字段","numeric_field","NUMBER",false,List.of()));
        var metadata=transfer.export(metadataOnly.user(),metadataOnly.project(),3,false);
        assertEquals(new FunctionalTransferGovernance.Counts(1,1,0),transfer.transfer(destination.user(),destination.project(),request(destination,metadata)).governance());
    }
    @Test void tagOrFieldConflictsDoNotOverwriteTargetsOrLeavePartialAssets() {
        var source=workspace();var snapshot=snapshotWithGovernance(source);
        for(boolean tagConflict:List.of(true,false)) {
            var target=workspace();
            if(tagConflict) governance.createTag(target.user(),target.project(),new FunctionalGovernanceService.TagInput("迁移标签","red"));
            else governance.createField(target.user(),target.project(),new FunctionalGovernanceService.CustomFieldInput("CASE","已有字段","transfer_field","TEXT",false,List.of()));
            var error=assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,snapshot)));
            assertEquals(409,error.getStatusCode().value());
            for(var table:List.of("functional_catalog","functional_requirement","functional_case","functional_test_plan","functional_asset_tag","functional_transfer_receipt")) assertEquals(0,count(table,target.project()));
            if(tagConflict) assertEquals("red",governance.listTags(target.user(),target.project()).getFirst().color());
            else assertEquals("TEXT",governance.listFields(target.user(),target.project(),"CASE").getFirst().fieldType());
        }
    }
    @Test void lateFieldValidationRollsBackTagsDefinitionsAndReceipt() {
        var source=workspace();var target=workspace();var snapshot=snapshotWithGovernance(source);var field=snapshot.governance().fields().getFirst();
        var badField=new FunctionalTransferGovernance.Field(field.id(),field.assetType(),field.name(),field.key(),"INVALID",field.required(),field.options());
        var bad=withGovernance(snapshot,new FunctionalTransferGovernance.Definitions(snapshot.governance().tags(),List.of(badField),snapshot.governance().bindings()));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        for(var table:List.of("functional_catalog","functional_requirement","functional_case","functional_test_plan","functional_tag","functional_custom_field","functional_asset_tag","functional_transfer_receipt")) assertEquals(0,count(table,target.project()));
    }
    @Test void rejectsOutOfScopeTagsAssetsDuplicateBindingsAndProtocolDowngrade() {
        var source=workspace();var target=workspace();var snapshot=snapshotWithGovernance(source);var binding=snapshot.governance().bindings().getFirst();
        for(var bindings:List.of(List.of(new FunctionalTransferGovernance.Binding(binding.assetType(),"foreign",binding.tagIds())),
                List.of(new FunctionalTransferGovernance.Binding(binding.assetType(),binding.assetId(),List.of("foreign"))),List.of(binding,binding),
                List.of(new FunctionalTransferGovernance.Binding(binding.assetType(),binding.assetId(),List.of(binding.tagIds().getFirst(),binding.tagIds().getFirst()))))) {
            var bad=withGovernance(snapshot,new FunctionalTransferGovernance.Definitions(snapshot.governance().tags(),snapshot.governance().fields(),bindings));
            assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        }
        var withoutPlans=new FunctionalTransferService.Snapshot(3,snapshot.catalogs().stream().filter(item->!item.scope().equals("PLANS")).toList(),snapshot.requirements(),snapshot.cases(),null,snapshot.governance());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,withoutPlans))).getStatusCode().value());
        var downgrade=new FunctionalTransferService.Snapshot(2,snapshot.catalogs(),snapshot.requirements(),snapshot.cases(),snapshot.plans(),snapshot.governance());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,downgrade))).getStatusCode().value());
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void legacyV2SerializationAndReceiptsStillMatchAfterGovernanceUpgrade() throws Exception {
        var source=workspace();var target=workspace();snapshotWithGovernance(source);var v2=transfer.export(source.user(),source.project(),2);
        assertNull(v2.governance());var original=new LinkedHashMap<String,Object>();original.put("version",2);original.put("catalogs",v2.catalogs());
        original.put("requirements",v2.requirements());original.put("cases",v2.cases());original.put("plans",v2.plans());
        assertArrayEquals(json.writeValueAsBytes(original),json.writeValueAsBytes(v2));
        var request=request(target,v2);var receipt=transfer.transfer(target.user(),target.project(),request);
        var originalReceipt=new LinkedHashMap<String,Object>();originalReceipt.put("id",receipt.id());originalReceipt.put("catalogs",receipt.catalogs());
        originalReceipt.put("requirements",receipt.requirements());originalReceipt.put("cases",receipt.cases());originalReceipt.put("roots",receipt.roots());originalReceipt.put("plans",receipt.plans());
        assertEquals(json.writeValueAsString(originalReceipt),json.writeValueAsString(receipt));
        assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(0,count("functional_tag",target.project()));
    }
    FunctionalDefectService.DefectInput defectInput(String catalog, String code, String title, String status, String actor) {
        return new FunctionalDefectService.DefectInput(catalog,code,title,java.time.LocalDate.of(2026,10,1),"SECURITY","中文关键字",
                List.of("CHROME","SAFARI"),List.of("MAC_OS","WINDOWS"),actor==null?List.of():List.of(actor),"CRITICAL","P0",status,true,actor,
                "FIXED","TESTHUB","https://example.test/issue/42","<p>完整重现步骤</p>");
    }
    FunctionalTransferService.Snapshot snapshotWithDefects(Workspace source) {
        var base=snapshotWithGovernance(source);
        var root=assets.createCatalog(source.user(),source.project(),new FunctionalAssetService.CatalogInput("缺陷根目录",null,2,"DELIVERY"));
        var folder=assets.createCatalog(source.user(),source.project(),new FunctionalAssetService.CatalogInput("缺陷子目录",root.id(),5,"DELIVERY"));
        var defect=defects.create(source.user(),source.project(),defectInput(folder.id(),"BUG_TRANSFER","迁入的缺陷","CLOSED",source.user().userId()));
        defects.link(source.user(),source.project(),defect.id(),new FunctionalDefectService.DefectLinkInput("REQUIREMENT",base.requirements().getFirst().id()));
        defects.link(source.user(),source.project(),defect.id(),new FunctionalDefectService.DefectLinkInput("CASE",base.cases().getFirst().id()));
        var run=executions.createRun(source.user(),source.project(),base.plans().stream().filter(item->!item.caseIds().isEmpty()).findFirst().orElseThrow().id(),"保留本地执行");
        var runCase=executions.listRunCases(source.user(),source.project(),run.id()).getFirst();
        defects.link(source.user(),source.project(),defect.id(),new FunctionalDefectService.DefectLinkInput("RUN_CASE",runCase.id()));
        return transfer.export(source.user(),source.project(),4,true,true);
    }
    com.fasterxml.jackson.databind.node.ObjectNode transferTree(FunctionalTransferService.Snapshot snapshot) {
        return json.valueToTree(snapshot);
    }
    @Test void defectsPreserveDefinitionsRemapLinksClearPeopleAndRetryExactly() {
        var source=workspace();var target=workspace();var snapshot=snapshotWithDefects(source);var request=request(target,snapshot);
        var sourceDefect=defects.list(source.user(),source.project()).getFirst();assertNotNull(sourceDefect.resolvedAt());
        var result=transfer.transfer(target.user(),target.project(),request);
        assertEquals(new FunctionalTransferDefects.Counts(1,2,1),result.defects());
        var copied=defects.list(target.user(),target.project()).getFirst();assertNotEquals(sourceDefect.id(),copied.id());
        assertEquals(sourceDefect.title(),copied.title());assertEquals(sourceDefect.dueDate(),copied.dueDate());assertEquals(sourceDefect.description(),copied.description());
        assertEquals(sourceDefect.browsers(),copied.browsers());assertEquals(sourceDefect.operatingSystems(),copied.operatingSystems());
        assertEquals(sourceDefect.keywords(),copied.keywords());assertEquals(sourceDefect.bugType(),copied.bugType());assertEquals(sourceDefect.externalUrl(),copied.externalUrl());
        assertEquals("CLOSED",copied.status());assertEquals("FIXED",copied.resolution());assertTrue(copied.confirmed());
        assertNull(copied.assigneeId());assertNull(copied.assignedBy());assertEquals(List.of(),copied.ccUserIds());assertNull(copied.resolvedBy());assertNull(copied.resolvedAt());assertNull(copied.closedBy());assertNull(copied.closedAt());
        assertEquals(target.user().userId(),copied.createdBy());
        var imported=transfer.export(target.user(),target.project(),4,true,true);
        assertEquals("缺陷子目录",imported.catalogs().stream().filter(item->item.id().equals(copied.catalogId())).findFirst().orElseThrow().name());
        var links=defects.links(target.user(),target.project(),copied.id());assertEquals(2,links.size());
        assertTrue(links.stream().allMatch(link->link.assetType().equals("REQUIREMENT")?link.assetId().equals(imported.requirements().getFirst().id()):imported.cases().stream().anyMatch(item->item.id().equals(link.assetId()))));
        assertEquals(0,count("functional_test_run",target.project()));
        assertEquals(result,transfer.transfer(target.user(),target.project(),request));assertEquals(1,count("functional_defect",target.project()));
        assertEquals(snapshot,transfer.export(source.user(),source.project(),4,true,true));assertEquals(sourceDefect,defects.list(source.user(),source.project()).getFirst());
    }
    @Test void editingTransferredClosedDefectDoesNotInventHistoricalActorsButReopeningResetsState() {
        var source=workspace();var target=workspace();transfer.transfer(target.user(),target.project(),request(target,snapshotWithDefects(source)));
        var copied=defects.list(target.user(),target.project()).getFirst();
        var edited=defects.update(target.user(),target.project(),copied.id(),defectInput(copied.catalogId(),copied.defectCode(),"更新标题","CLOSED",null));
        assertEquals("更新标题",edited.title());assertNull(edited.resolvedBy());assertNull(edited.closedAt());
        var reopened=defects.update(target.user(),target.project(),copied.id(),defectInput(copied.catalogId(),copied.defectCode(),"重新打开","OPEN",null));
        assertNull(reopened.resolution());
        var resolved=defects.update(target.user(),target.project(),copied.id(),defectInput(copied.catalogId(),copied.defectCode(),"真实重新解决","RESOLVED",null));
        assertEquals(target.user().userId(),resolved.resolvedBy());assertNotNull(resolved.resolvedAt());assertNull(resolved.closedAt());
    }
    @Test void defectsOnlyAndIndependentV4ScopesWorkWithoutLeakingUnselectedDefinitions() {
        var source=workspace();snapshotWithDefects(source);
        for(boolean plans:List.of(false,true)) for(boolean fields:List.of(false,true)) {
            var selected=transfer.export(source.user(),source.project(),4,plans,fields);
            assertEquals(plans,selected.plans()!=null);assertEquals(fields,selected.governance()!=null);
            var target=workspace();assertEquals(1,transfer.transfer(target.user(),target.project(),request(target,selected)).defects().items());
        }
        var only=workspace();var destination=workspace();defects.create(only.user(),only.project(),defectInput(null,"BUG_ONLY","无目录缺陷","OPEN",null));
        var data=transfer.export(only.user(),only.project(),4,false,false);
        assertTrue(data.requirements().isEmpty());assertTrue(data.catalogs().isEmpty());
        assertEquals(1,transfer.transfer(destination.user(),destination.project(),request(destination,data)).defects().items());
    }
    @Test void defectCodeConflictAndLateInvalidContentRollBackEntireTransfer() {
        var source=workspace();var snapshot=snapshotWithDefects(source);var conflicting=workspace();
        defects.create(conflicting.user(),conflicting.project(),defectInput(null,"BUG_TRANSFER","已存在记录","OPEN",null));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(conflicting.user(),conflicting.project(),request(conflicting,snapshot))).getStatusCode().value());
        assertEquals("已存在记录",defects.list(conflicting.user(),conflicting.project()).getFirst().title());assertEquals(0,count("functional_requirement",conflicting.project()));
        var target=workspace();var tree=transferTree(snapshot);((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("defects").path("items").get(0)).put("bugType","INVALID");
        var invalid=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,invalid))).getStatusCode().value());
        for(var table:List.of("functional_defect","functional_defect_link","functional_catalog","functional_requirement","functional_case","functional_test_plan","functional_tag","functional_custom_field","functional_asset_tag","functional_transfer_receipt")) assertEquals(0,count(table,target.project()));
    }
    @Test void defectsRejectForeignDuplicateAndExecutionLinksAndOldProtocolPayloads() {
        var source=workspace();var target=workspace();var snapshot=snapshotWithDefects(source);
        for(var mutation:List.of("foreign","duplicate","execution","catalog","version","missing","negative","id")) {
            var tree=transferTree(snapshot);var item=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("defects").path("items").get(0);
            var links=(com.fasterxml.jackson.databind.node.ArrayNode)item.path("links");
            switch(mutation) {
                case "foreign" -> ((com.fasterxml.jackson.databind.node.ObjectNode)links.get(0)).put("assetId","foreign");
                case "duplicate" -> links.add(links.get(0).deepCopy());
                case "execution" -> ((com.fasterxml.jackson.databind.node.ObjectNode)links.get(0)).put("assetType","RUN_CASE");
                case "catalog" -> item.put("catalogId",snapshot.requirements().getFirst().catalogId());
                case "version" -> tree.put("version",3);
                case "missing" -> tree.remove("defects");
                case "negative" -> item.put("omittedExecutionLinks",-1);
                case "id" -> item.put("id",snapshot.cases().getFirst().id());
            }
            var invalid=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,invalid))).getStatusCode().value(),mutation);
        }
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void defectAssociationAndTotalAssetBudgetsApplyBeforeWrites() {
        var source=workspace();var target=workspace();var snapshot=snapshotWithDefects(source);var tree=transferTree(snapshot);
        var items=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("defects").path("items");
        var first=(com.fasterxml.jackson.databind.node.ObjectNode)items.get(0);first.put("omittedExecutionLinks",50000);
        var second=first.deepCopy();second.put("id","D".repeat(26));second.put("code","BUG_SECOND");second.put("omittedExecutionLinks",1);items.add(second);
        var oversized=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        assertTrue(assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,oversized))).getReason().contains("50000"));
        assertEquals(0,count("functional_defect",target.project()));
        tree=transferTree(snapshot);items=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("defects").path("items");first=(com.fasterxml.jackson.databind.node.ObjectNode)items.get(0);items.removeAll();
        for(int i=0;i<5000;i++) {var copy=first.deepCopy();copy.put("id","D"+i);copy.put("code","BUG_"+i);copy.putArray("links");copy.put("omittedExecutionLinks",0);items.add(copy);}
        var tooMany=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        assertTrue(assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,tooMany))).getReason().contains("5000"));
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void legacyV3BytesAndReceiptsRemainCompatibleAndExportNeverIncludesDefects() throws Exception {
        var source=workspace();var target=workspace();snapshotWithDefects(source);var v3=transfer.export(source.user(),source.project(),3);
        assertNull(v3.defects());assertTrue(v3.catalogs().stream().noneMatch(item->item.scope().equals("DELIVERY")));
        var old=new LinkedHashMap<String,Object>();old.put("version",3);old.put("catalogs",v3.catalogs());old.put("requirements",v3.requirements());old.put("cases",v3.cases());old.put("plans",v3.plans());old.put("governance",v3.governance());
        assertArrayEquals(json.writeValueAsBytes(old),json.writeValueAsBytes(v3));
        var request=request(target,v3);var receipt=transfer.transfer(target.user(),target.project(),request);assertNull(receipt.defects());
        assertFalse(json.writeValueAsString(receipt).contains("defects"));assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(0,count("functional_defect",target.project()));
    }

    io.testhub.platform.storage.FileObject upload(Workspace source,byte[] content) {
        return projectFiles.upload(source.user(),source.project(),new org.springframework.mock.web.MockMultipartFile("file","中文 附件.bin","application/octet-stream",content));
    }
    String filePath(String project,String file){return "/api/v1/projects/"+project+"/files/"+file+"/download";}
    @Test void filesCopyBytesRewriteRelativeLinksKeepExternalLinksAndRetryWithoutDuplicates() throws Exception {
        var source=workspace();var target=workspace();var base=snapshotWithDefects(source);byte[] bytes={0,1,2,-1,20};var original=upload(source,bytes);
        upload(source,new byte[]{9,8}); // An unreferenced project file is also explicitly included.
        upload(target,new byte[]{77}); // Same filename must never overwrite an existing object.
        var snapshot=transfer.export(source.user(),source.project(),5,true,true,true);var tree=transferTree(snapshot);
        String relative=filePath(source.project(),original.id());
        String text="<a href=\""+relative+"?download=1\">附件</a> https://external.test"+relative+" <img src='"+relative+"'>";
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("requirements").get(0)).put("description",text);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("cases").get(0)).put("steps",relative);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("plans").get(0)).put("description",relative);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("defects").path("items").get(0)).put("description","<p>"+relative+"</p>");
        var selected=json.convertValue(tree,FunctionalTransferService.Snapshot.class);var request=request(target,selected);
        var receipt=transfer.transfer(target.user(),target.project(),request);assertEquals(new FunctionalTransferFiles.Counts(2,7),receipt.files());
        var copied=projectFiles.list(target.user(),target.project()).stream().filter(item->item.sizeBytes()==bytes.length).findFirst().orElseThrow();
        assertNotEquals(original.id(),copied.id());assertEquals(original.originalName(),copied.originalName());assertEquals(original.contentType(),copied.contentType());
        try(var input=projectFiles.download(target.user(),target.project(),copied.id())){assertArrayEquals(bytes,input.readAllBytes());}
        var imported=transfer.export(target.user(),target.project(),5,true,true,true);String mapped=filePath(target.project(),copied.id());
        assertEquals(text.replace("href=\""+relative,"href=\""+mapped).replace("src='"+relative,"src='"+mapped),imported.requirements().getFirst().description());
        assertTrue(imported.cases().stream().anyMatch(item->mapped.equals(item.steps())));
        assertTrue(imported.plans().stream().anyMatch(item->mapped.equals(item.description())));
        assertTrue(imported.defects().items().getFirst().description().contains(mapped));
        assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(3,projectFiles.list(target.user(),target.project()).size());
        assertEquals(0,count("file_transfer_intent",target.project()));
        assertArrayEquals(json.writeValueAsBytes(snapshot),json.writeValueAsBytes(transfer.export(source.user(),source.project(),5,true,true,true)));
        try(var input=projectFiles.download(source.user(),source.project(),original.id())){assertArrayEquals(bytes,input.readAllBytes());}
    }
    @Test void fileOnlyProjectsWorkAndV5ScopesRemainIndependent() {
        var source=workspace();upload(source,new byte[]{1,2,3});
        for(boolean plans:List.of(false,true)) for(boolean fields:List.of(false,true)) for(boolean includeDefects:List.of(false,true)) {
            var target=workspace();var snapshot=transfer.export(source.user(),source.project(),5,plans,fields,includeDefects);
            assertEquals(plans,snapshot.plans()!=null);assertEquals(fields,snapshot.governance()!=null);assertEquals(includeDefects,snapshot.defects()!=null);
            assertEquals(1,transfer.transfer(target.user(),target.project(),request(target,snapshot)).files().items());
        }
    }
    @Test void fileDigestSizeMissingReferencesAndOldVersionsRejectBeforeWrites() {
        var source=workspace();var target=workspace();snapshot(source);upload(source,new byte[]{1,2,3});
        var snapshot=transfer.export(source.user(),source.project(),5,false,false,false);
        for(var mutation:List.of("hash","size","duplicate","missingReference","version","missingFiles","count")) {
            var tree=transferTree(snapshot);var items=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("files").path("items");var file=(com.fasterxml.jackson.databind.node.ObjectNode)items.get(0);
            switch(mutation){
                case "hash" -> file.put("sha256","0".repeat(64));
                case "size" -> file.put("size",4);
                case "duplicate" -> items.add(file.deepCopy());
                case "missingReference" -> ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("cases").get(0)).put("steps",filePath(source.project(),"X".repeat(26)));
                case "version" -> tree.put("version",1);
                case "missingFiles" -> tree.remove("files");
                case "count" -> {while(items.size()<101)items.add(file.deepCopy());}
            }
            var invalid=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,invalid))).getStatusCode().value(),mutation);
        }
        assertEquals(0,count("file_object",target.project()));assertEquals(0,count("file_transfer_intent",target.project()));
    }
    @Test void rollbackRemovesFileMetadataAndPhysicalObjectsAfterLateBusinessFailure() throws Exception {
        var source=workspace();var target=workspace();snapshotWithDefects(source);upload(source,new byte[]{4,5});
        var tree=transferTree(transfer.export(source.user(),source.project(),5,true,true,true));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("defects").path("items").get(0)).put("bugType","INVALID");
        var invalid=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        org.mockito.Mockito.clearInvocations(objectStorage);
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,invalid)));
        var keys=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(objectStorage).put(org.mockito.ArgumentMatchers.eq(bucket),keys.capture(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        assertThrows(Exception.class,()->objectStorage.get(bucket,keys.getValue()));
        for(var table:List.of("file_object","file_transfer_intent","functional_catalog","functional_requirement","functional_defect","functional_transfer_receipt"))assertEquals(0,count(table,target.project()));
    }
    @Test void failedPhysicalCleanupKeepsDurableIntentAndRetriesWithoutDeletingCommittedFiles() throws Exception {
        var target=workspace();var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        org.mockito.Mockito.doThrow(new java.io.IOException("fixture storage offline")).when(objectStorage).remove(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{fileStorage.copy(target.user(),target.project(),"fixture.txt","text/plain",new byte[]{1});throw new IllegalStateException("rollback fixture");}));
        assertEquals(0,count("file_object",target.project()));assertEquals(1,count("file_transfer_intent",target.project()));
        String id=jdbc.sql("SELECT id FROM file_transfer_intent WHERE project_id=:project").param("project",target.project()).query(String.class).single();
        org.mockito.Mockito.doCallRealMethod().when(objectStorage).remove(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        fileStorage.recover(id);assertEquals(0,count("file_transfer_intent",target.project()));assertThrows(Exception.class,()->objectStorage.get(bucket,target.project()+"/"+id));
        var committed=upload(target,new byte[]{6});
        insertStaleIntent(target,committed.id(),committed.objectKey());
        String orphan=com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();String key=target.project()+"/"+orphan;
        objectStorage.put(bucket,key,new java.io.ByteArrayInputStream(new byte[]{7}),1,"text/plain");insertStaleIntent(target,orphan,key);
        fileStorage.recoverStale();assertEquals(0,count("file_transfer_intent",target.project()));
        try(var input=projectFiles.download(target.user(),target.project(),committed.id())){assertArrayEquals(new byte[]{6},input.readAllBytes());}
        assertThrows(Exception.class,()->objectStorage.get(bucket,key));
    }
    void insertStaleIntent(Workspace target,String id,String key){
        jdbc.sql("INSERT INTO file_transfer_intent(id,organization_id,project_id,bucket,object_key,created_at) VALUES(:id,:org,:project,:bucket,:key,:time)")
                .param("id",id).param("org",access.project(target.user(),target.project()).organizationId()).param("project",target.project()).param("bucket",bucket).param("key",key)
                .param("time",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7200))).update();
    }
    @Test void recoveryWaitsForActiveFileTransactionAndPreservesItsCommittedObject() throws Exception {
        var target=workspace();var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var ready=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);var id=new java.util.concurrent.atomic.AtomicReference<String>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var writer=pool.submit(()->transaction.execute(status->{
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
                try{var file=fileStorage.copy(target.user(),target.project(),"并发.txt","text/plain",new byte[]{8});id.set(file.id());ready.countDown();if(!finish.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return file;}
                catch(InterruptedException error){throw new RuntimeException(error);}finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
            }));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));var recovery=pool.submit(()->fileStorage.recover(id.get()));
            assertThrows(java.util.concurrent.TimeoutException.class,()->recovery.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));finish.countDown();
            var file=writer.get(5,java.util.concurrent.TimeUnit.SECONDS);recovery.get(5,java.util.concurrent.TimeUnit.SECONDS);
            try(var input=projectFiles.download(target.user(),target.project(),file.id())){assertArrayEquals(new byte[]{8},input.readAllBytes());}
            assertEquals(0,count("file_transfer_intent",target.project()));
        }finally{finish.countDown();}
    }
    @Test void legacyV4SerializationAndReceiptsStillExcludeFiles() throws Exception {
        var source=workspace();var target=workspace();var old=snapshotWithDefects(source);upload(source,new byte[]{1});
        var v4=transfer.export(source.user(),source.project(),4);assertNull(v4.files());assertArrayEquals(json.writeValueAsBytes(old),json.writeValueAsBytes(v4));
        var shape=new LinkedHashMap<String,Object>();shape.put("version",4);shape.put("catalogs",v4.catalogs());shape.put("requirements",v4.requirements());shape.put("cases",v4.cases());shape.put("plans",v4.plans());shape.put("governance",v4.governance());shape.put("defects",v4.defects());
        assertArrayEquals(json.writeValueAsBytes(shape),json.writeValueAsBytes(v4));var request=request(target,v4);var receipt=transfer.transfer(target.user(),target.project(),request);
        assertNull(receipt.files());assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(0,count("file_object",target.project()));
    }
    @Test void twentyMiBFileUsesHttpBinaryDecoderAndRejectsOversizedRequest() throws Exception {
        var source=workspace();var target=workspace();upload(source,new byte[20*1024*1024]);
        var snapshot=transfer.export(source.user(),source.project(),5,false,false,false);
        var csrfResult=mvc.perform(get("/api/v1/auth/csrf")).andReturn();var csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var login=mvc.perform(post("/api/v1/auth/login").cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account",target.user().account().account(),"password","Transfer-test-2026!")))).andExpect(status().isOk()).andReturn();
        var session=login.getResponse().getCookie("TESTHUB_SESSION");csrfResult=mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andReturn();csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var path="/api/v1/projects/"+target.project()+"/functional/transfer";
        mvc.perform(post(path).servletPath(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN")).header(csrf.path("headerName").asText(),csrf.path("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(target,snapshot)))).andExpect(status().isOk());
        assertEquals(20*1024*1024,projectFiles.list(target.user(),target.project()).getFirst().sizeBytes());
        var copied=projectFiles.list(target.user(),target.project()).getFirst();
        var download=mvc.perform(get(filePath(target.project(),copied.id())).cookie(session)).andExpect(status().isOk()).andReturn().getResponse();
        String disposition=download.getHeader("Content-Disposition");
        assertNotNull(disposition);assertTrue(disposition.chars().allMatch(value->value<128));
        assertEquals("中文 附件.bin",org.springframework.http.ContentDisposition.parse(disposition).getFilename());
        assertEquals(20*1024*1024,download.getContentAsByteArray().length);
        mvc.perform(post(path).servletPath(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN")).header(csrf.path("headerName").asText(),csrf.path("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content(new byte[33*1024*1024+1])).andExpect(status().isPayloadTooLarge());
        assertEquals(1,count("file_object",target.project()));
    }

    FunctionalTransferService.Snapshot snapshotWithHistory(Workspace source) {
        var base=snapshot(source);var c=base.cases().getFirst();
        var second=assets.createCase(source.user(),source.project(),new FunctionalAssetService.CaseInput(c.catalogId(),null,"HISTORY_SECOND","历史用例","P2","READY","FUNCTIONAL","","旧步骤\n\n保留编号3","预期"));
        var plan=executions.createPlan(source.user(),source.project(),new FunctionalTestExecutionService.PlanInput(null,"HISTORY_PLAN","历史计划","READY","",List.of(c.id(),second.id())));
        for(int i=0;i<3;i++) {
            var run=executions.createRun(source.user(),source.project(),plan.id(),"历史批次"+i);
            var runCases=executions.listRunCases(source.user(),source.project(),run.id());
            if(i<2) executions.updateRunCase(source.user(),source.project(),runCases.getFirst().id(),new FunctionalTestExecutionService.RunCaseInput("FAILED","历史备注"));
            if(i==0) {
                var last=runCases.getLast();
                for(var step:executions.listRunSteps(source.user(),source.project(),last.id()))executions.updateRunStep(source.user(),source.project(),step.id(),new FunctionalTestExecutionService.RunStepInput("PASSED","历史实际结果"));
                executions.updateRunCase(source.user(),source.project(),last.id(),new FunctionalTestExecutionService.RunCaseInput("PASSED",""));
            }
        }
        // Membership and step text may change after a run: snapshots must not be recreated from either.
        executions.updatePlan(source.user(),source.project(),plan.id(),new FunctionalTestExecutionService.PlanInput(null,"HISTORY_PLAN","历史计划","READY","",List.of(c.id())));
        assets.updateCase(source.user(),source.project(),second.id(),new FunctionalAssetService.CaseInput(c.catalogId(),null,"HISTORY_SECOND","历史用例","P2","READY","FUNCTIONAL","","现在已修改步骤","预期"));
        return transfer.export(source.user(),source.project(),6,true,false,false,false,true);
    }
    @Test void historyPreservesSnapshotsTimesNewScopesAndIdempotenceThenAllowsContinuation() throws Exception {
        var source=workspace();var target=workspace();var original=snapshotWithHistory(source);var request=request(target,original);
        var receipt=transfer.transfer(target.user(),target.project(),request);assertEquals(new FunctionalTransferHistory.Counts(3,6,9),receipt.history());
        assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));
        var copy=transfer.export(target.user(),target.project(),6,true,false,false,false,true);
        assertEquals(1,copy.plans().getFirst().caseIds().size());assertEquals(3,copy.history().runs().size());
        for(var run:original.history().runs()) {
            var actual=copy.history().runs().stream().filter(r->r.name().equals(run.name())).findFirst().orElseThrow();
            assertNotEquals(run.id(),actual.id());assertEquals(copy.plans().getFirst().id(),actual.planId());
            assertEquals(run.status(),actual.status());assertEquals(run.startedAt(),actual.startedAt());assertEquals(run.finishedAt(),actual.finishedAt());assertEquals(run.createdAt(),actual.createdAt());assertEquals(run.updatedAt(),actual.updatedAt());
            for(var c:run.cases()) {
                String code=original.cases().stream().filter(x->x.id().equals(c.caseId())).findFirst().orElseThrow().code();
                String mapped=copy.cases().stream().filter(x->x.code().equals(code)).findFirst().orElseThrow().id();
                var copied=actual.cases().stream().filter(x->x.caseId().equals(mapped)).findFirst().orElseThrow();assertNotEquals(c.id(),copied.id());
                assertEquals(c.status(),copied.status());assertEquals(c.note(),copied.note());assertEquals(c.executedAt(),copied.executedAt());
                assertEquals(c.steps().size(),copied.steps().size());
                for(int n=0;n<c.steps().size();n++) {var a=c.steps().get(n);var b=copied.steps().get(n);assertNotEquals(a.id(),b.id());assertEquals(a.number(),b.number());assertEquals(a.text(),b.text());assertEquals(a.status(),b.status());assertEquals(a.actualResult(),b.actualResult());assertEquals(a.updatedAt(),b.updatedAt());}
            }
        }
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_test_run_case c JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p AND c.executed_by IS NOT NULL").param("p",target.project()).query(Integer.class).single());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM functional_test_run_step s JOIN functional_test_run_case c ON c.id=s.run_case_id JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p AND s.updated_by IS NOT NULL").param("p",target.project()).query(Integer.class).single());
        for(var r:executions.listRuns(target.user(),target.project(),null)){assertEquals(target.user().userId(),r.createdBy());assertEquals(target.project(),r.projectId());}
        var pending=copy.history().runs().stream().filter(r->r.status().equals("NOT_STARTED")).findFirst().orElseThrow();
        executions.updateRunCase(target.user(),target.project(),pending.cases().getFirst().id(),new FunctionalTestExecutionService.RunCaseInput("BLOCKED","迁入后继续执行"));
        assertEquals("RUNNING",executions.listRuns(target.user(),target.project(),null).stream().filter(r->r.id().equals(pending.id())).findFirst().orElseThrow().status());
        assertEquals(original,transfer.export(source.user(),source.project(),6,true,false,false,false,true));
    }
    @Test void historyRejectsBrokenGraphStatusesTimesAndScopeWithoutPartialWrites() {
        var source=workspace();var target=workspace();var original=snapshotWithHistory(source);
        List<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> changes=List.of(
            tree->tree.put("version",5),tree->tree.remove("plans"),tree->tree.remove("history"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0")).put("planId","foreign"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0")).put("caseId","foreign"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0")).put("id",tree.at("/history/runs/0/id").asText()),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0")).put("status","BAD"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0")).put("status","RUNNING"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0")).put("finishedAt","2000-01-01T00:00:00Z"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0/steps/0")).put("number",0),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0")).put("executedAt","1900-01-01T00:00:00Z"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0")).put("executedAt","2040-01-01T00:00:00Z"),
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0")).put("executedAt","2026-01-01T00:00:00.000001Z"),
            tree->{ for(var run:tree.at("/history/runs")) for(var c:run.path("cases")) if(c.path("status").asText().equals("PASSED")) ((com.fasterxml.jackson.databind.node.ObjectNode)c.path("steps").get(0)).put("status","FAILED"); },
            tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/history/runs/0/cases/0")).put("note","x".repeat(20001)));
        for(var change:changes) {
            var tree=transferTree(original);change.accept(tree);var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
            for(var table:List.of("functional_test_run","functional_test_plan","functional_case","functional_catalog","functional_transfer_receipt"))assertEquals(0,count(table,target.project()));
        }
        assertThrows(ResponseStatusException.class,()->transfer.export(source.user(),source.project(),6,false,false,false,false,true));
        assertThrows(ResponseStatusException.class,()->transfer.export(source.user(),source.project(),5,true,false,false,false,true));
    }
    @Test void historyRemapsDefectExecutionLinksAndFileReferencesInSavedResults() throws Exception {
        var source=workspace();var target=workspace();snapshotWithHistory(source);
        var run=executions.listRuns(source.user(),source.project(),null).getFirst();var runCase=executions.listRunCases(source.user(),source.project(),run.id()).getFirst();
        var file=upload(source,new byte[]{1,4,7});String link=filePath(source.project(),file.id());
        executions.updateRunCase(source.user(),source.project(),runCase.id(),new FunctionalTestExecutionService.RunCaseInput("FAILED",link));
        var step=executions.listRunSteps(source.user(),source.project(),runCase.id()).getFirst();
        executions.updateRunStep(source.user(),source.project(),step.id(),new FunctionalTestExecutionService.RunStepInput("FAILED",link));
        jdbc.sql("UPDATE functional_test_run_step SET step_text=:link WHERE id=:id").param("link",link).param("id",step.id()).update();
        var defect=defects.create(source.user(),source.project(),defectInput(null,"HISTORY_BUG","历史关联","OPEN",null));
        defects.link(source.user(),source.project(),defect.id(),new FunctionalDefectService.DefectLinkInput("RUN_CASE",runCase.id()));
        var snapshot=transfer.export(source.user(),source.project(),6,true,false,true,true,true);
        var receipt=transfer.transfer(target.user(),target.project(),request(target,snapshot));assertEquals(0,receipt.defects().omittedExecutionLinks());assertEquals(1,receipt.defects().links());
        var copiedFile=projectFiles.list(target.user(),target.project()).getFirst();String mapped=filePath(target.project(),copiedFile.id());
        var imported=transfer.export(target.user(),target.project(),6,true,false,true,true,true);
        var saved=imported.history().runs().stream().flatMap(r->r.cases().stream()).filter(c->c.note().equals(mapped)).findFirst().orElseThrow();
        assertTrue(saved.steps().stream().anyMatch(s->s.text().equals(mapped)&&s.actualResult().equals(mapped)));
        assertEquals(saved.id(),imported.defects().items().getFirst().links().getFirst().assetId());
        assertNotEquals(runCase.id(),saved.id());
        var old=transfer.export(source.user(),source.project(),5,true,false,true);assertNull(old.history());assertEquals(1,old.defects().items().getFirst().omittedExecutionLinks());
        var badTree=transferTree(snapshot);((com.fasterxml.jackson.databind.node.ArrayNode)badTree.at("/files/items")).removeAll();var bad=json.convertValue(badTree,FunctionalTransferService.Snapshot.class);
        var empty=workspace();assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(empty.user(),empty.project(),request(empty,bad))).getStatusCode().value());assertEquals(0,count("file_object",empty.project()));
    }
    @Test void historyAndFilesRollBackWhenLaterDefectValidationFails() {
        var source=workspace();var target=workspace();snapshotWithHistory(source);upload(source,new byte[]{9});
        defects.create(source.user(),source.project(),defectInput(null,"ROLLBACK_HISTORY","回滚历史","OPEN",null));
        var snapshot=transfer.export(source.user(),source.project(),6,true,false,true,true,true);var tree=transferTree(snapshot);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/defects/items/0")).put("status","INVALID");var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad)));
        for(var table:List.of("functional_test_run","functional_test_plan","functional_case","functional_catalog","functional_transfer_receipt","file_object"))assertEquals(0,count(table,target.project()));
    }
    @Test void legacyV5ShapeAndReceiptRemainUnchangedAfterHistoryUpgrade() throws Exception {
        var source=workspace();var target=workspace();snapshotWithHistory(source);var old=transfer.export(source.user(),source.project(),5);
        assertNull(old.history());var tree=transferTree(old);assertFalse(tree.has("history"));
        var expected=new LinkedHashMap<String,Object>();expected.put("version",old.version());expected.put("catalogs",old.catalogs());expected.put("requirements",old.requirements());expected.put("cases",old.cases());expected.put("plans",old.plans());expected.put("governance",old.governance());expected.put("defects",old.defects());expected.put("files",old.files());
        assertArrayEquals(json.writeValueAsBytes(expected),json.writeValueAsBytes(old));
        var request=request(target,old);var receipt=transfer.transfer(target.user(),target.project(),request);assertFalse(json.writeValueAsString(receipt).contains("history"));assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(0,count("functional_test_run",target.project()));
    }
    @Test void historyLimitsRejectOversizeRunsCasesAndStepsBeforeWrites() {
        var source=workspace();var target=workspace();var original=snapshotWithHistory(source);
        for(var path:List.of("/history/runs","/history/runs/0/cases","/history/runs/0/cases/0/steps")) {
            var tree=transferTree(original);var array=(com.fasterxml.jackson.databind.node.ArrayNode)tree.at(path);var first=array.get(0);array.removeAll();
            int count=path.endsWith("runs")?1001:path.endsWith("cases")?10001:50001;
            for(int i=0;i<count;i++)array.add(first);
            var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        }
        assertEquals(0,count("functional_transfer_receipt",target.project()));assertEquals(0,count("functional_test_run",target.project()));
    }
    @Test void historyHonorsProjectAndReadOnlyBoundaries() {
        var source=workspace();var target=workspace();var reader=workspace();var snapshot=snapshotWithHistory(source);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transfer.export(reader.user(),source.project(),6,true,false,false,false,true));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transfer.transfer(source.user(),target.project(),request(source,snapshot)));
        var project=access.project(source.user(),source.project());
        jdbc.sql("INSERT INTO membership(id,user_id,organization_id,project_id,role_code) VALUES(:id,:user,:org,:project,'VIEWER')")
                .param("id",com.github.f4b6a3.ulid.UlidCreator.getUlid().toString()).param("user",reader.user().userId()).param("org",project.organizationId()).param("project",source.project()).update();
        assertEquals(snapshot,transfer.export(reader.user(),source.project(),6,true,false,false,false,true));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transfer.transfer(reader.user(),source.project(),request(reader,snapshot)));
        assertEquals(0,count("functional_transfer_receipt",target.project()));assertEquals(0,count("functional_transfer_receipt",source.project()));
    }
    @Test void httpV6SelectionAndNestedHistoryValidationUseExistingAuthentication() throws Exception {
        var source=workspace();var target=workspace();var snapshot=snapshotWithHistory(source);
        var csrfResult=mvc.perform(get("/api/v1/auth/csrf")).andReturn();var csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var login=mvc.perform(post("/api/v1/auth/login").cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN"))
                .header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account",target.user().account().account(),"password","Transfer-test-2026!")))).andExpect(status().isOk()).andReturn();
        var session=login.getResponse().getCookie("TESTHUB_SESSION");csrfResult=mvc.perform(get("/api/v1/auth/csrf").cookie(session)).andReturn();csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        String path="/api/v1/projects/"+target.project()+"/functional/transfer";
        var body=transferTree(snapshot);((com.fasterxml.jackson.databind.node.ObjectNode)body.at("/history/runs/0/cases/0/steps/0")).put("text","");
        mvc.perform(post(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN")).header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(target,json.convertValue(body,FunctionalTransferService.Snapshot.class))))).andExpect(status().isBadRequest());
        assertEquals(0,count("functional_transfer_receipt",target.project()));
        mvc.perform(post(path).cookie(session,csrfResult.getResponse().getCookie("XSRF-TOKEN")).header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(target,snapshot)))).andExpect(status().isOk());
        var response=mvc.perform(get(path+"?version=6&includePlans=true&includeGovernance=false&includeDefects=false&includeFiles=false&includeHistory=true").cookie(session)).andExpect(status().isOk()).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).path("data");assertEquals(3,data.path("history").path("runs").size());assertFalse(data.has("files"));assertFalse(data.has("defects"));assertFalse(data.has("governance"));
        mvc.perform(get(path+"?version=6&includePlans=false").cookie(session)).andExpect(status().isBadRequest());
    }
    @Autowired io.testhub.platform.version.ProjectVersionService projectVersions;
    FunctionalTransferService.Snapshot versionsSnapshot(Workspace source, boolean files) {
        return transfer.export(source.user(),source.project(),7,false,false,false,files,false,true);
    }
    void createVersions(Workspace source, String description) {
        for (var state : List.of("UNSTARTED","RELEASED","FAILED","STOPPED")) {
            projectVersions.create(source.user(),source.project(),new io.testhub.platform.version.ProjectVersionService.VersionInput(
                    "V-"+state,List.of("build-z","build-a"),state,java.time.LocalDate.of(2025,1,2),
                    Set.of("RELEASED","STOPPED").contains(state)?java.time.LocalDate.of(2025,1,3):null,description));
        }
    }
    @Test void versionOnlyTransferPreservesReleaseDefinitionsWithNewOwnershipAndIdempotence() {
        var source=workspace();var target=workspace();createVersions(source,"版本说明");
        projectVersions.create(target.user(),target.project(),new io.testhub.platform.version.ProjectVersionService.VersionInput("existing",List.of(),"UNSTARTED",null,null,"保留"));
        var original=versionsSnapshot(source,false);var request=request(target,original);
        var receipt=transfer.transfer(target.user(),target.project(),request);assertEquals(4,receipt.projectVersions());
        assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));
        var copied=versionsSnapshot(target,false);assertEquals(5,copied.projectVersions().items().size());
        for(var item:original.projectVersions().items()) {
            var saved=copied.projectVersions().items().stream().filter(v->v.number().equals(item.number())).findFirst().orElseThrow();
            assertNotEquals(item.id(),saved.id());assertEquals(item.builds(),saved.builds());assertEquals(item.status(),saved.status());
            assertEquals(item.plannedReleaseDate(),saved.plannedReleaseDate());assertEquals(item.actualReleaseDate(),saved.actualReleaseDate());assertEquals(item.description(),saved.description());
            assertEquals(target.user().userId(),jdbc.sql("SELECT created_by FROM project_version WHERE id=:id").param("id",saved.id()).query(String.class).single());
        }
        assertEquals(original,versionsSnapshot(source,false));assertEquals(0,count("functional_test_plan",target.project()));assertEquals(1,count("functional_transfer_receipt",target.project()));
    }
    @Test void versionsRejectConflictsInvalidDefinitionsAndBudgetsWithoutPartialWrites() {
        var source=workspace();var target=workspace();createVersions(source,"定义");var original=versionsSnapshot(source,false);
        for(var mutation:List.<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>of(
                tree->tree.put("version",6),tree->tree.remove("projectVersions"),
                tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/1")).put("id",tree.at("/projectVersions/items/0/id").asText()),
                tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/1")).put("number"," "+tree.at("/projectVersions/items/0/number").asText().toLowerCase()+" "),
                tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/0")).put("status","INVALID"),
                tree->{var item=(com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/0");item.put("status","RELEASED");item.remove("actualReleaseDate");},
                tree->{var item=(com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/0");item.put("status","FAILED");item.put("actualReleaseDate","2025-01-03");},
                tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/0")).put("plannedReleaseDate","0999-01-01"),
                tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/0")).putArray("builds").add("a").add(" a "),
                tree->((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/projectVersions/items/0")).putArray("builds").add("a\nb"),
                tree->{var items=(com.fasterxml.jackson.databind.node.ArrayNode)tree.at("/projectVersions/items");var item=items.get(0);items.removeAll();for(int i=0;i<5001;i++)items.add(item);}
        )) {
            var tree=transferTree(original);mutation.accept(tree);var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
            assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());
        }
        assertEquals(0,count("project_version",target.project()));assertEquals(0,count("functional_transfer_receipt",target.project()));
        projectVersions.create(target.user(),target.project(),new io.testhub.platform.version.ProjectVersionService.VersionInput("v-released",List.of(),"UNSTARTED",null,null,"existing"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,original))).getStatusCode().value());
        assertEquals(1,count("project_version",target.project()));assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void versionDescriptionsRewriteOnlySelectedFilesAndRejectMissingReferences() {
        var source=workspace();var target=workspace();var file=upload(source,new byte[]{1,2,3});var path=filePath(source.project(),file.id());createVersions(source,path);
        var noFiles=workspace();transfer.transfer(noFiles.user(),noFiles.project(),request(noFiles,versionsSnapshot(source,false)));
        assertTrue(versionsSnapshot(noFiles,false).projectVersions().items().stream().allMatch(v->v.description().equals(path)));
        var selected=versionsSnapshot(source,true);transfer.transfer(target.user(),target.project(),request(target,selected));
        var copied=projectFiles.list(target.user(),target.project()).getFirst();var expected=filePath(target.project(),copied.id());
        assertTrue(versionsSnapshot(target,false).projectVersions().items().stream().allMatch(v->v.description().equals(expected)));
        var empty=workspace();var tree=transferTree(selected);((com.fasterxml.jackson.databind.node.ArrayNode)tree.at("/files/items")).removeAll();var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(empty.user(),empty.project(),request(empty,bad))).getStatusCode().value());
        assertEquals(0,count("project_version",empty.project()));assertEquals(0,count("file_object",empty.project()));
    }
    @Test void versionsAndFilesRollBackIfLaterDefectCreationFails() {
        var source=workspace();var target=workspace();snapshotWithHistory(source);createVersions(source,"回滚版本");upload(source,new byte[]{4});
        defects.create(source.user(),source.project(),defectInput(null,"VERSION_ROLLBACK","回滚","OPEN",null));
        var tree=transferTree(transfer.export(source.user(),source.project(),7));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/defects/items/0")).put("status","INVALID");var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad)));
        for(var table:List.of("project_version","functional_test_run","functional_case","functional_test_plan","functional_transfer_receipt","file_object"))assertEquals(0,count(table,target.project()));
    }
    @Test void legacyV6ExactShapeAndReceiptRemainStableAndNeverCopyVersions() throws Exception {
        var source=workspace();var target=workspace();snapshotWithHistory(source);createVersions(source,"excluded");var old=transfer.export(source.user(),source.project(),6);
        var expected=new LinkedHashMap<String,Object>();expected.put("version",old.version());expected.put("catalogs",old.catalogs());expected.put("requirements",old.requirements());expected.put("cases",old.cases());expected.put("plans",old.plans());expected.put("governance",old.governance());expected.put("defects",old.defects());expected.put("files",old.files());expected.put("history",old.history());
        assertArrayEquals(json.writeValueAsBytes(expected),json.writeValueAsBytes(old));assertNull(old.projectVersions());
        var request=request(target,old);var receipt=transfer.transfer(target.user(),target.project(),request);assertFalse(json.writeValueAsString(receipt).contains("projectVersions"));assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));assertEquals(0,count("project_version",target.project()));
    }
    @Test void httpV7IndependentSelectionAndPermissionsRemainEnforced() throws Exception {
        var source=workspace();var target=workspace();createVersions(source,"HTTP");var snapshot=versionsSnapshot(source,false);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->versionsSnapshot(new Workspace(target.user(),source.project()),false));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transfer.transfer(source.user(),target.project(),request(source,snapshot)));
        var csrfResult=mvc.perform(get("/api/v1/auth/csrf")).andReturn();var csrf=json.readTree(csrfResult.getResponse().getContentAsString()).path("data");
        var login=mvc.perform(post("/api/v1/auth/login").cookie(csrfResult.getResponse().getCookie("XSRF-TOKEN")).header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account",source.user().account().account(),"password","Transfer-test-2026!")))).andExpect(status().isOk()).andReturn();
        var session=login.getResponse().getCookie("TESTHUB_SESSION");String path="/api/v1/projects/"+source.project()+"/functional/transfer";
        var response=mvc.perform(get(path+"?version=7&includePlans=false&includeGovernance=false&includeDefects=false&includeFiles=false&includeHistory=false&includeProjectVersions=true").cookie(session)).andExpect(status().isOk()).andReturn();
        var data=json.readTree(response.getResponse().getContentAsString()).path("data");assertEquals(4,data.at("/projectVersions/items").size());assertFalse(data.has("history"));assertFalse(data.has("plans"));assertFalse(data.has("files"));
        mvc.perform(get(path+"?version=7&includeProjectVersions=false").cookie(session)).andExpect(status().isBadRequest());
        mvc.perform(get(path+"?version=6&includeProjectVersions=true").cookie(session)).andExpect(status().isBadRequest());
    }
    FunctionalTransferService.Snapshot catalogSnapshot(Workspace source) {
        return transfer.export(source.user(),source.project(),8,false,false,false,false,false,false,true);
    }
    void createGovernanceCatalogs(Workspace source) {
        var root=assets.createCatalog(source.user(),source.project(),new FunctionalAssetService.CatalogInput("治理目录",null,3,"GOVERNANCE"));
        assets.createCatalog(source.user(),source.project(),new FunctionalAssetService.CatalogInput("治理目录",root.id(),7,"GOVERNANCE"));
    }
    @Test void emptyGovernanceCatalogsTransferIndependentlyWithHierarchyOwnershipAndRetry() {
        var source=workspace();var target=workspace();createGovernanceCatalogs(source);
        var existing=assets.createCatalog(target.user(),target.project(),new FunctionalAssetService.CatalogInput("治理目录",null,1,"GOVERNANCE"));
        var original=catalogSnapshot(source);var request=request(target,original);var receipt=transfer.transfer(target.user(),target.project(),request);
        assertEquals(2,receipt.catalogs());assertEquals(2,receipt.governanceCatalogs());assertNull(receipt.governance());assertNull(receipt.projectVersions());
        assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));
        var copied=assets.listCatalogs(target.user(),target.project(),"GOVERNANCE");assertEquals(4,copied.size());assertTrue(copied.stream().anyMatch(c->c.id().equals(existing.id())));
        var parent=copied.stream().filter(c->c.sortOrder()==3).findFirst().orElseThrow();var child=copied.stream().filter(c->c.sortOrder()==7).findFirst().orElseThrow();
        assertEquals(parent.id(),child.parentId());assertEquals(receipt.roots().get("GOVERNANCE"),parent.parentId());
        assertNotEquals(original.catalogs().getFirst().id(),parent.id());assertEquals("治理目录",parent.name());assertEquals("治理目录",child.name());
        assertEquals(target.user().userId(),jdbc.sql("SELECT created_by FROM functional_catalog WHERE id=:id").param("id",parent.id()).query(String.class).single());
        assertEquals(original,catalogSnapshot(source));assertEquals(0,count("functional_tag",target.project()));assertEquals(0,count("functional_custom_field",target.project()));
    }
    @Test void governanceCatalogGraphScopeAndCountRejectInvalidDataBeforeWrites() {
        var source=workspace();var target=workspace();createGovernanceCatalogs(source);var original=catalogSnapshot(source);
        for(var change:List.<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>>of(
                t->t.put("version",7),
                t->{var a=(com.fasterxml.jackson.databind.node.ArrayNode)t.path("catalogs");var first=a.get(0);((com.fasterxml.jackson.databind.node.ObjectNode)first).put("parentId",first.path("id").asText());},
                t->((com.fasterxml.jackson.databind.node.ObjectNode)t.path("catalogs").get(1)).put("scope","CASES"),
                t->((com.fasterxml.jackson.databind.node.ObjectNode)t.path("catalogs").get(1)).put("id",t.path("catalogs").get(0).path("id").asText()),
                t->{var a=(com.fasterxml.jackson.databind.node.ArrayNode)t.path("catalogs");var item=a.get(0);a.removeAll();for(int i=0;i<5001;i++)a.add(item);},
                t->{var a=(com.fasterxml.jackson.databind.node.ArrayNode)t.path("catalogs");var item=(com.fasterxml.jackson.databind.node.ObjectNode)a.get(0);a.removeAll();for(int i=0;i<65;i++){var c=item.deepCopy();c.put("id","depth-"+i);if(i==0)c.putNull("parentId");else c.put("parentId","depth-"+(i-1));a.add(c);}}
        )) {var tree=transferTree(original);change.accept(tree);var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad))).getStatusCode().value());}
        assertEquals(0,count("functional_catalog",target.project()));assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
    @Test void legacyV1ToV7NeverIncludeGovernanceCatalogsOrNewReceiptField() throws Exception {
        var source=workspace();snapshotWithHistory(source);createGovernanceCatalogs(source);createVersions(source,"legacy");
        for(int version=1;version<=7;version++) {
            var old=transfer.export(source.user(),source.project(),version);assertTrue(old.catalogs().stream().noneMatch(c->c.scope().equals("GOVERNANCE")));
            var target=workspace();var request=request(target,old);var receipt=transfer.transfer(target.user(),target.project(),request);
            assertFalse(json.writeValueAsString(receipt).contains("governanceCatalogs"));assertEquals(receipt,transfer.transfer(target.user(),target.project(),request));
            assertTrue(assets.listCatalogs(target.user(),target.project(),"GOVERNANCE").isEmpty());
        }
    }
    @Test void governanceCatalogsRollBackTogetherWithFilesHistoryAndVersionsOnLateFailure() {
        var source=workspace();var target=workspace();snapshotWithHistory(source);createGovernanceCatalogs(source);createVersions(source,"all scopes");upload(source,new byte[]{1});
        defects.create(source.user(),source.project(),defectInput(null,"CAT_ROLLBACK","回滚","OPEN",null));
        var tree=transferTree(transfer.export(source.user(),source.project(),8));((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/defects/items/0")).put("status","INVALID");
        var bad=json.convertValue(tree,FunctionalTransferService.Snapshot.class);assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),request(target,bad)));
        for(var table:List.of("functional_catalog","functional_case","functional_test_run","project_version","file_object","functional_transfer_receipt"))assertEquals(0,count(table,target.project()));
    }
    @Test void governanceCatalogsRespectExplicitVersionFlagsAndActorPermissions() {
        var source=workspace();var target=workspace();createGovernanceCatalogs(source);var original=catalogSnapshot(source);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transfer.export(target.user(),source.project(),8));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->transfer.transfer(source.user(),target.project(),request(source,original)));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.user(),target.project(),new FunctionalTransferService.ImportRequest(UUID.randomUUID().toString(),source.user().userId(),original))).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.export(source.user(),source.project(),7,false,false,false,false,false,true,true)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.export(source.user(),source.project(),8,false,false,false,false,false,false,false)).getStatusCode().value());
        assertEquals(0,count("functional_transfer_receipt",target.project()));
    }
}
