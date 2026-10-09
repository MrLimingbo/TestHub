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
import io.testhub.platform.notification.*;
import io.testhub.platform.notification.NotificationSyncService.*;
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
class NotificationSyncFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"notification-sync-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa");registry.add("spring.datasource.password",()->"notification-sync-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired NotificationSyncService sync;
    @Autowired NotificationTemplateService templates;
    @Autowired NotificationTemplateRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired io.testhub.platform.config.SystemConfigRepository configs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate batchJdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoSpyBean AuditService audit;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="nt-sync-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","同步测试","Notification-fixture-2026!"));
        var c=access.context(actor);return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());
    }
    Item item(String name,String body) { return new Item(name,"quality.report.ready","WEBHOOK","测试 {{event}}",body); }
    Snapshot snapshot(Fixture f) { return sync.snapshot(f.actor(),f.project(),f.actor().userId()); }
    Request request(Fixture f,Item... items) { return new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),
            Arrays.stream(items).map(i->new Operation(UUID.randomUUID().toString(),i)).toList()); }
    void apply(Fixture f,Item... items) { sync.apply(f.actor(),f.project(),request(f,items)); }
    NotificationTemplate create(Fixture f,String name,boolean configured) {
        if(configured) configs.upsert("notifications.webhook.allowed-hosts","example.test",f.actor().userId());
        return templates.create(f.actor(),f.project(),new NotificationTemplateService.TemplateInput(name,"quality.report.ready","WEBHOOK","原主题","原正文",
                configured?"https://example.test/hooks":null,configured?"fixture-signature-never-export":null,configured));
    }
    @Test void updatesPreserveIdentityDeliveryConfigurationCiphertextEnabledAndQueueHistory() throws Exception {
        var f=fixture();var original=create(f,"已配置",true);var stored=repository.findStored(original.id(),f.project()).orElseThrow();
        var before=snapshot(f);String encoded=json.writeValueAsString(before);
        assertEquals(1,before.excludedTargets());assertEquals(1,before.excludedSecrets());
        assertFalse(encoded.contains("https://example.test/hooks"));assertFalse(encoded.contains("fixture-signature-never-export"));assertFalse(encoded.contains(stored.signingSecretCiphertext()));
        apply(f,item("已配置","同步正文 {{status}}"),item("新模板","新正文"));
        var after=repository.findStored(original.id(),f.project()).orElseThrow();
        assertEquals(stored.signingSecretCiphertext(),after.signingSecretCiphertext());assertEquals(original.targetUrl(),after.template().targetUrl());assertTrue(after.template().enabled());
        assertEquals("同步正文 {{status}}",after.template().bodyTemplate());
        var added=templates.list(f.actor(),f.project()).stream().filter(t->t.name().equals("新模板")).findFirst().orElseThrow();
        assertFalse(added.enabled());assertNull(added.targetUrl());assertFalse(added.signingSecretConfigured());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE project_id=:id").param("id",f.project()).query(Integer.class).single());
    }
    @Test void concurrentRetriesAndLostResponseReplayReturnOriginalReceiptWithoutDuplicateOrLaterOverwrite() throws Exception {
        var f=fixture();var r=request(f,item("重试","第一次"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->sync.apply(f.actor(),f.project(),r));var b=pool.submit(()->sync.apply(f.actor(),f.project(),r));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        var first=sync.apply(f.actor(),f.project(),r);apply(f,item("重试","后续编辑"));assertEquals(first,sync.apply(f.actor(),f.project(),r));
        assertEquals("后续编辑",snapshot(f).items().getFirst().bodyTemplate());assertEquals(1,snapshot(f).items().size());
        var changed=new Request(r.requestKey(),r.expectedUserId(),r.expectedRevision(),List.of(new Operation(r.operations().getFirst().operationId(),item("重试","更换载荷"))));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),changed)).getStatusCode().value());
    }
    @Test void existingCrudAndDeletionRecreationInvalidateWholeBatchWithoutPartialWrites() {
        for(boolean recreate:List.of(false,true)) {
            var f=fixture();var t=create(f,"并发",false);var r=request(f,item("新增","不可出现"),item("并发","旧预览"));
            if(recreate) { templates.delete(f.actor(),f.project(),t.id());create(f,"并发",false); }
            else templates.update(f.actor(),f.project(),t.id(),new NotificationTemplateService.TemplateInput(t.name(),t.eventCode(),t.channel(),"并发主题","并发正文",null,null,true));
            var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),r)).getStatusCode().value());assertEquals(before,snapshot(f));
        }
    }
    @Test void channelAndCaseDifferencesRejectWholeBatchAndPreserveLocalDelivery() {
        var f=fixture();create(f,"Notify",true);
        for(Item bad:List.of(item("notify","大小写"),new Item("Notify","quality.report.ready","EMAIL","主题","正文"))) {
            var before=snapshot(f);assertEquals(409,assertThrows(ResponseStatusException.class,()->apply(f,item("新增","不得出现"),bad)).getStatusCode().value());assertEquals(before,snapshot(f));
        }
    }
    @Test void duplicateExistingNamesAreReportedWithoutPickingAnArbitraryRow() {
        var f=fixture();create(f,"重复",false);create(f,"重复",false);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->snapshot(f)).getStatusCode().value());assertEquals(2,templates.list(f.actor(),f.project()).size());
    }
    @Test void strictJsonInvalidDefinitionsAndDuplicateOperationsCannotWrite() throws Exception {
        var f=fixture();String body=json.writeValueAsString(request(f,item("合法","正文")));
        for(String invalid:List.of(body+" {}",body.replace("\"bodyTemplate\":\"正文\"","\"bodyTemplate\":\"正文\",\"targetUrl\":\"https://example.test\"")))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->sync.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatusCode().value());
        assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[NotificationSyncService.MAX_BYTES+1])).getStatusCode().value());
        for(Item bad:List.of(item(" 未规范 ","正文"),item("空白"," "),new Item("坏通道","bad","SMS","主题","正文"),item("超长","x".repeat(20001)))) {
            assertThrows(ResponseStatusException.class,()->apply(f,bad));assertTrue(snapshot(f).items().isEmpty());
        }
        assertThrows(ResponseStatusException.class,()->apply(f,item("Name","一"),item("name","二")));
        var op=new Operation(UUID.randomUUID().toString(),item("重复","正文"));
        assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),new Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot(f).revision(),List.of(op,op))));
    }
    @Test void auditFailureRollsBackUpdatesNewTemplatesAndReceipt() {
        var f=fixture();create(f,"原模板",true);var before=snapshot(f);var r=request(f,item("原模板","不得保存"),item("新增","不得保存"));
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(f.actor()),anyString(),eq(f.project()),eq("NOTIFICATION_TEMPLATES_SYNCED"),anyString(),anyString(),anyMap());
        try { assertThrows(IllegalStateException.class,()->sync.apply(f.actor(),f.project(),r));assertEquals(before,snapshot(f));
            assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM notification_sync_receipt WHERE project_id=:id").param("id",f.project()).query(Integer.class).single()); }
        finally { reset(audit); }
    }
    @Test void writerScopeActorAndCsrfRequiredBeforeReadingOrApplyingEvenOldReceipts() throws Exception {
        var f=fixture();var other=fixture();access.upsertMember(f.actor(),f.project(),other.actor().getUsername(),"VIEWER");
        String route="/api/v1/projects/"+f.project()+"/notification-templates/sync";var r=request(f,item("权限","正文"));var body=json.writeValueAsBytes(r);
        mvc.perform(get(route).param("expectedUserId",other.actor().userId()).with(user(other.actor()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/"+other.project()+"/notification-templates/sync").param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(f.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),other.actor().userId())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(other.actor(),other.project(),r)).getStatusCode().value());
    }
    @Test void snapshotByteLimitAndThousandTemplateLimitRejectWholeWrites() {
        var f=fixture();String org=access.context(f.actor()).organizations().getFirst().id();var args=new ArrayList<Object[]>();
        for(int n=0;n<1000;n++) args.add(new Object[]{com.github.f4b6a3.ulid.UlidCreator.getUlid().toString(),org,f.project(),"模板"+n,f.actor().userId()});
        batchJdbc.batchUpdate("INSERT INTO notification_template(id,organization_id,project_id,name,event_code,channel,subject_template,body_template,enabled,updated_by) VALUES(?,?,?,?,'quality.report.ready','EMAIL','主题','正文',false,?)",args);
        var before=snapshot(f);assertEquals(1000,before.items().size());assertThrows(ResponseStatusException.class,()->apply(f,item("超额","正文")));assertEquals(before,snapshot(f));
        var g=fixture();var inputs=new ArrayList<Item>();for(int n=0;n<170;n++) inputs.add(item("大模板"+n,"x".repeat(20000)));
        assertThrows(ResponseStatusException.class,()->apply(g,inputs.toArray(Item[]::new)));assertTrue(snapshot(g).items().isEmpty());
    }
}
