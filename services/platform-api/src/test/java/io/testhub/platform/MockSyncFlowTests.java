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
import io.testhub.platform.mock.*;
import io.testhub.platform.mock.MockSyncService.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class MockSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"mock-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa");registry.add("spring.datasource.password",()->"mock-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired MockSyncService sync;
    @Autowired MockService mocks;
    @Autowired MockRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate batchJdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="mock-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","同步测试","Notification-fixture-2026!"));
        var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());
    }
    Item server(String name) { return new Item(name,null,null); }
    Item rule(String server,String name,String body) { return new Item(server,name,new MockService.RuleInput(name,"GET","/orders",0,Map.of(),Map.of(),"ANY","",200,Map.of("Content-Type","application/json; charset=utf-8"),body)); }
    Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(),f.project(),f.actor().userId()); }
    Request request(Fixture f,Item... items) { return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList()); }
    void apply(Fixture f,Item... items) { sync.apply(f.actor(),f.project(),request(f,items)); }
    MockServer create(Fixture f,String name) { return mocks.createServer(f.actor(),f.project(),new MockService.ServerInput(name)); }
    @Test void definitionsPreservePublicKeySharesRuntimeEnablementAndRuleIdentityNewCopiesDisabled() throws Exception {
        var f=fixture();var original=create(f,"QA");var r=mocks.createRule(f.actor(),f.project(),original.id(),rule("QA","RULE","原响应").rule());
        var share=mocks.createShare(f.actor(),f.project(),original.id(),new MockService.ShareInput("原分享",null));mocks.setSmartExampleEnabled(f.actor(),f.project(),original.id(),true);
        var before=snapshot(f);var encoded=json.writeValueAsString(before);assertEquals(1,before.excludedPublicKeys());assertEquals(1,before.excludedShares());
        assertFalse(encoded.contains(original.publicKey()));assertFalse(encoded.contains(share.shareKey()));
        apply(f,rule("QA","RULE","同步响应"),rule("QA","NEW","新增规则"),rule("NEW-SERVER","COPY","新增服务规则"),server("NEW-SERVER"));
        var after=repository.findServer(f.project(),original.id()).orElseThrow();assertEquals(original.publicKey(),after.publicKey());assertTrue(after.enabled());assertTrue(after.smartExampleEnabled());
        assertEquals(share,repository.findShare(f.project(),original.id(),share.id()).orElseThrow());
        var kept=repository.findRules(f.project(),original.id()).stream().filter(i->i.id().equals(r.id())).findFirst().orElseThrow();assertTrue(kept.enabled());assertEquals("同步响应",kept.responseBody());
        var runtime=new MockRuntimeRequest("GET","/orders",Map.of(),Map.of(),"");assertEquals("同步响应",mocks.runtime(original.publicKey(),runtime).orElseThrow().body());
        assertFalse(repository.findRules(f.project(),original.id()).stream().filter(i->i.name().equals("NEW")).findFirst().orElseThrow().enabled());
        var copied=mocks.servers(f.actor(),f.project()).stream().filter(i->i.name().equals("NEW-SERVER")).findFirst().orElseThrow();assertFalse(copied.enabled());assertFalse(copied.smartExampleEnabled());
        assertNotEquals(original.publicKey(),copied.publicKey());assertTrue(mocks.shares(f.actor(),f.project(),copied.id()).isEmpty());assertTrue(mocks.runtime(copied.publicKey(),runtime).isEmpty());assertFalse(mocks.rules(f.actor(),f.project(),copied.id()).getFirst().enabled());
    }
    @Test void concurrentRetriesReturnOriginalReceiptWithoutDuplicateOrLaterOverwrite() throws Exception {
        var f=fixture();var r=request(f,rule("QA","R","第一次"),server("QA"));
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var first=sync.apply(f.actor(),f.project(),r);apply(f,rule("QA","R","后续编辑"));assertEquals(first,sync.apply(f.actor(),f.project(),r));assertEquals(2,snapshot(f).items().size());assertEquals("后续编辑",snapshot(f).items().stream().filter(i->i.rule()!=null).findFirst().orElseThrow().rule().responseBody());
        var changed=new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(r.operations().getFirst().operationId(),server("OTHER"))));assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),changed)).getStatusCode().value());
    }
    @Test void existingCrudEditAndEnablementInvalidateWholeBatch() {
        var f=fixture();var s=create(f,"QA");var r=mocks.createRule(f.actor(),f.project(),s.id(),rule("QA","R","原响应").rule());
        for(boolean edit:List.of(true,false)) {
            var request=request(f,server("不可出现"),rule("QA","R","旧预览"));
            if(edit) mocks.updateRule(f.actor(),f.project(),s.id(),r.id(),rule("QA","R","并发修改").rule());else mocks.setServerEnabled(f.actor(),f.project(),s.id(),false);
            var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request)).getStatusCode().value());assertEquals(before,snapshot(f));
        }
    }
    @Test void caseDifferencesMissingParentAndDuplicateExistingNamesNeverChooseArbitraryRows() {
        var f=fixture();create(f,"QA");var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,server("新增"),server("qa")));assertEquals(before,snapshot(f));
        assertThrows(ResponseStatusException.class,()->apply(f,rule("missing","R","响应")));assertEquals(before,snapshot(f));
        if(FunctionalDatabaseProfile.mysql()) {
            assertThrows(org.springframework.dao.DuplicateKeyException.class,()->create(f,"qa"));assertEquals(before,snapshot(f));
            create(f,"café");var accented=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,server("cafe"),server("不可出现"))).getStatusCode().value());assertEquals(accented,snapshot(f));
        } else {create(f,"qa");assertThrows(ResponseStatusException.class,()->snapshot(f));}
        var g=fixture();var s=create(g,"QA");mocks.createRule(g.actor(),g.project(),s.id(),rule("QA","R","一").rule());mocks.createRule(g.actor(),g.project(),s.id(),rule("QA","r","二").rule());assertThrows(ResponseStatusException.class,()->snapshot(g));
    }
    @Test void strictJsonInvalidHeadersAndUnnormalizedDefinitionsCannotWrite() throws Exception {
        var f=fixture();String body=json.writeValueAsString(request(f,server("QA")));
        for(String invalid:List.of(body+" {}",body.replace("\"rule\":null","\"rule\":null,\"publicKey\":\"private\""))) assertEquals(400,assertThrows(ResponseStatusException.class,()->sync.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatusCode().value());
        assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[MockSyncService.MAX_BYTES+1])).getStatusCode().value());
        for(var bad:List.of(server(" QA "),new Item("QA","R",new MockService.RuleInput("R","GET","/orders",0,Map.of(),Map.of(),"ANY","",200,Map.of("Set-Cookie","private"),"正文")),new Item("QA","R",new MockService.RuleInput("R","GET","/../orders",0,Map.of(),Map.of(),"ANY","",200,Map.of(),"正文")))) {assertThrows(ResponseStatusException.class,()->apply(f,server("QA"),bad));assertTrue(snapshot(f).items().isEmpty());}
        assertThrows(ResponseStatusException.class,()->apply(f,server("Name"),server("name")));
    }
    @Test void auditFailureRollsBackDefinitionNewServerRulesAndReceipt() {
        var f=fixture();create(f,"QA");apply(f,rule("QA","R","原响应"));var before=snapshot(f);var r=request(f,rule("QA","R","不得写入"),server("新增"));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("MOCK_SYNCED"),anyString(),anyString(),anyMap());
        try {assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM mock_sync_receipt WHERE project_id=:p AND request_key=:r").param("p",f.project()).param("r",r.requestKey()).query(Integer.class).single());}finally{reset(audit);}
    }
    @Test void writerScopeActorCsrfAndRuleScopeRequired() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");String route="/api/v1/projects/"+f.project()+"/mock/sync";var r=request(f,server("QA"));var body=json.writeValueAsBytes(r);
        mvc.perform(get(route).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/"+other.project()+"/mock/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId())).getStatusCode().value());
        var server=mocks.servers(f.actor(),f.project()).getFirst();var rule=mocks.createRule(f.actor(),f.project(),server.id(),rule("QA","R","原响应").rule());
        mvc.perform(put("/api/v1/projects/"+f.project()+"/mock/servers/"+server.id()+"/rules/"+rule.id()).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(rule("QA","R","更新").rule()))).andExpect(status().isForbidden());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->mocks.updateRule(other.actor(),f.project(),server.id(),rule.id(),rule("QA","R","不得写入").rule()));
    }
    @Test void mergedLimitsAndFinalSnapshotSizeRollbackAllWrites() {
        var f=fixture();var inputs=new ArrayList<Item>();for(int n=0;n<20;n++)inputs.add(server("S"+n));apply(f,inputs.toArray(Item[]::new));var before=snapshot(f);assertThrows(ResponseStatusException.class,()->apply(f,server("OVER")));assertEquals(before,snapshot(f));
        var g=fixture();inputs.clear();inputs.add(server("QA"));for(int n=0;n<201;n++)inputs.add(rule("QA","R"+n,"正文"));assertThrows(ResponseStatusException.class,()->apply(g,inputs.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
        inputs.clear();inputs.add(server("QA"));for(int n=0;n<7;n++)inputs.add(rule("QA","R"+n,"x".repeat(524288)));assertThrows(ResponseStatusException.class,()->apply(g,inputs.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
    }
    @Test void enabledRuleEditorChangesRuntimeAndInvalidEditKeepsOriginal() {
        var f=fixture();var s=create(f,"QA");var r=mocks.createRule(f.actor(),f.project(),s.id(),rule("QA","R","原响应").rule());var runtime=new MockRuntimeRequest("GET","/orders",Map.of(),Map.of(),"");
        var changed=mocks.updateRule(f.actor(),f.project(),s.id(),r.id(),rule("QA","R","编辑响应").rule());assertEquals(r.id(),changed.id());assertTrue(changed.enabled());assertEquals("编辑响应",mocks.runtime(s.publicKey(),runtime).orElseThrow().body());
        assertThrows(ResponseStatusException.class,()->mocks.updateRule(f.actor(),f.project(),s.id(),r.id(),new MockService.RuleInput("R","GET","/orders",0,Map.of(),Map.of(),"ANY","",200,Map.of("Connection","close"),"不得写入")));assertEquals("编辑响应",mocks.runtime(s.publicKey(),runtime).orElseThrow().body());
    }
}
