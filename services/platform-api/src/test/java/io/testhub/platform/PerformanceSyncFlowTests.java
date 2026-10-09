package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.performance.*;
import io.testhub.platform.performance.PerformanceSyncService.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class PerformanceSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"performance-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa");registry.add("spring.datasource.password",()->"performance-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired PerformanceSyncService sync;
    @Autowired PerformanceTestPlanService plans;
    @Autowired PerformanceTestExecutionService executions;
    @Autowired PerformanceTestPlanRepository repository;
    @Autowired PerformanceTestExecutionRepository executionRepository;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="performance-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","同步测试","Performance-fixture-2026!"));
        var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());
    }
    PerformanceTestPlanService.ThreadGroupInput group(String name,String model,String body) {
        boolean stepped=model.equals("STEPPED_RAMP");
        return new PerformanceTestPlanService.ThreadGroupInput(name,"http://localhost:19457/probe/${id}",model,4,
                model.equals("RAMP_UP")?2:0,stepped?2:null,stepped?1:null,10,5,500,new BigDecimal("1.50"),
                "POST",Map.of("Content-Type","application/json","X-Data","${id}"),Map.of("id","${id}"),body,200,"${id}","id,name\n1,测试",stepped?null:2);
    }
    Item item(String name,String description) { return new Item(name,description,List.of(group("恒定","STEADY","{}"),group("线性","RAMP_UP","{}"),group("阶梯","STEPPED_RAMP","{}"))); }
    Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(),f.project(),f.actor().userId()); }
    Request request(Fixture f,Item... items) { return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList()); }
    void apply(Fixture f,Item... items) { sync.apply(f.actor(),f.project(),request(f,items)); }
    PerformanceTestPlanDetail create(Fixture f,Item item,String status) { return plans.create(f.actor(),f.project(),new PerformanceTestPlanService.PlanInput(item.name(),item.description(),status,item.threadGroups(),null)); }
    @Test void allDefinitionsPreserveIdentityStatusAndFrozenExecutionWithoutQueueingCopies() {
        var f=fixture();var original=create(f,item("READY","原计划"),"READY");var queued=executions.create(f.actor(),f.project(),original.id(),60);
        var frozen=executionRepository.findManifest(f.project(),queued.id()).orElseThrow();long outbox=count("performance_runner_outbox",f.project());
        var before=snapshot(f);assertEquals(1,before.excludedExecutions());
        apply(f,item("READY","同步修改"),item("NEW","新增计划"));
        var kept=plans.detail(f.actor(),f.project(),original.id());assertEquals(original.id(),kept.id());assertEquals(original.createdAt(),kept.createdAt());assertEquals(original.createdBy(),kept.createdBy());assertEquals("READY",kept.status());assertEquals(2,kept.version());
        assertEquals(List.of("恒定","线性","阶梯"),kept.threadGroups().stream().map(PerformanceTestThreadGroup::name).toList());assertEquals("id,name\n1,测试",kept.threadGroups().getFirst().dataSetCsv());assertEquals("${id}",kept.threadGroups().getFirst().requestHeaders().get("X-Data"));
        assertEquals(frozen,executionRepository.findManifest(f.project(),queued.id()).orElseThrow());assertEquals(outbox,count("performance_runner_outbox",f.project()));assertEquals(1,executions.list(f.actor(),f.project()).size());
        var copied=plans.list(f.actor(),f.project()).stream().filter(p->p.name().equals("NEW")).findFirst().orElseThrow();assertEquals("DRAFT",copied.status());assertEquals(1,copied.version());assertNotEquals(original.id(),copied.id());assertEquals(409,assertThrows(ResponseStatusException.class,()->executions.create(f.actor(),f.project(),copied.id(),60)).getStatusCode().value());
        assertFalse(jsonString(snapshot(f)).contains(queued.id()));
    }
    long count(String table,String project) { return jdbc.sql("SELECT COUNT(*) FROM "+table+(table.equals("performance_runner_outbox")?" WHERE execution_id IN (SELECT id FROM performance_test_execution WHERE project_id=:p)":" WHERE project_id=:p")).param("p",project).query(Long.class).single(); }
    String jsonString(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new AssertionError(e);} }
    @Test void archivedAndDraftStayIndependentAndVersionIncrementsOnlyOnWrittenTarget() {
        var f=fixture();var a=create(f,item("ARCHIVED","原"),"ARCHIVED");var d=create(f,item("DRAFT","原"),"DRAFT");apply(f,item("ARCHIVED","改"),item("DRAFT","改"));assertEquals("ARCHIVED",plans.detail(f.actor(),f.project(),a.id()).status());assertEquals("DRAFT",plans.detail(f.actor(),f.project(),d.id()).status());assertEquals(2,plans.detail(f.actor(),f.project(),a.id()).version());
    }
    @Test void concurrentAndLostResponseRetriesDoNotDuplicateIncrementOrOverwriteLaterEdits() throws Exception {
        var f=fixture();var r=request(f,item("QA","第一次"));
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var receipt=sync.apply(f.actor(),f.project(),r);apply(f,item("QA","后续编辑"));assertEquals(receipt,sync.apply(f.actor(),f.project(),r));assertEquals(1,plans.list(f.actor(),f.project()).size());assertEquals(2,plans.list(f.actor(),f.project()).getFirst().version());assertEquals("后续编辑",snapshot(f).items().getFirst().description());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(r.operations().getFirst().operationId(),item("OTHER","改")))))).getStatusCode().value());
    }
    @Test void versionOrReadinessChangeAndDeleteInvalidateEntireOldBatch() {
        var f=fixture();var p=create(f,item("QA","原"),"DRAFT");var r=request(f,item("QA","旧预览"),item("不可出现","新增"));plans.update(f.actor(),f.project(),p.id(),new PerformanceTestPlanService.PlanInput(p.name(),p.description(),"READY",item("QA","原").threadGroups(),p.version()));var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r)).getStatusCode().value());assertEquals(before,snapshot(f));
        var deleted=request(f,item("NEW","新增"));plans.delete(f.actor(),f.project(),p.id());assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),deleted)).getStatusCode().value());assertTrue(snapshot(f).items().isEmpty());
    }
    @Test void caseAndDatabaseCollationConflictsRollbackWholeBatch() {
        var f=fixture();create(f,item("QA","原"),"DRAFT");var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,item("qa","修改"),item("不得写入","新增")));assertEquals(before,snapshot(f));
        if(FunctionalDatabaseProfile.mysql()) {create(f,item("café","原"),"DRAFT");var accented=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,item("cafe","修改"),item("不得写入","新增"))).getStatusCode().value());assertEquals(accented,snapshot(f));}
        else {create(f,item("qa","另一个"),"DRAFT");assertThrows(ResponseStatusException.class,()->snapshot(f));}
    }
    @Test void unknownFieldsInvalidCsvTemplateHeaderAndDuplicateGroupsNeverWrite() throws Exception {
        var f=fixture();var body=json.writeValueAsString(request(f,item("QA","原")));
        for(String invalid:List.of(body+" {}",body.replace("\"description\":\"原\"","\"description\":\"原\",\"status\":\"READY\""),body.replace("\"queryParameters\":","\"executionHistory\":[],\"queryParameters\":")))assertEquals(400,assertThrows(ResponseStatusException.class,()->sync.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatusCode().value());
        assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[PerformanceSyncService.MAX_BYTES+1])).getStatusCode().value());
        var good=group("QA","STEADY","{}");
        for(String invalid:List.of(body.replace("id,name\\n1,测试","id,id\\n1,2"),body.replace("X-Data","Authorization"),body.replace("${id}","${__groovy(1)}"))) {var bad=sync.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),bad));assertTrue(snapshot(f).items().isEmpty());}
        assertThrows(ResponseStatusException.class,()->apply(f,new Item(" QA ","",List.of(good))));assertThrows(ResponseStatusException.class,()->apply(f,new Item("QA","",List.of(good,good))));assertThrows(ResponseStatusException.class,()->apply(f,item("A",""),item("a","")));
    }
    @Test void auditFailureRollsBackPlanVersionThreadsAndReceipt() {
        var f=fixture();create(f,item("QA","原"),"READY");var before=snapshot(f);var r=request(f,item("QA","不得写入"),item("NEW","新增"));doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("PERFORMANCE_SYNCED"),anyString(),anyString(),anyMap());
        try {assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,count("performance_sync_receipt",f.project()));}finally{reset(audit);}
    }
    @Test void writersExactActorProjectAndCsrfRequired() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");String route="/api/v1/projects/"+f.project()+"/performance/sync";byte[] body=json.writeValueAsBytes(request(f,item("QA","原")));
        mvc.perform(get(route).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/"+other.project()+"/performance/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());mvc.perform(post(route).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());mvc.perform(post(route).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId())).getStatusCode().value());
    }
    @Test void mergedCountAndFinalBytesAreBoundedBeforeAnyDefinitionWrite() {
        var f=fixture();var items=new ArrayList<Item>();for(int n=0;n<100;n++)items.add(item("P"+n,""));apply(f,items.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,item("OVER","")));assertEquals(before,snapshot(f));
        var g=fixture();items.clear();for(int n=0;n<7;n++){var groups=new ArrayList<PerformanceTestPlanService.ThreadGroupInput>();for(int j=0;j<8;j++)groups.add(group("G"+j,"STEADY","x".repeat(65536)));items.add(new Item("P"+n,"",groups));}assertThrows(ResponseStatusException.class,()->apply(g,items.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
    }
}
