package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.*;
import io.testhub.platform.account.*;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.config.SystemConfigRepository;
import io.testhub.platform.notification.*;
import io.testhub.platform.notification.NotificationTransferService.*;
import io.testhub.platform.reporting.QualityReportScheduleService;
import io.testhub.platform.security.SecretCipher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
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
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false","testhub.notifications.dispatch-interval-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class NotificationTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"notification-fixture");
        else {
            r.add("testhub.desktop.data-directory",directory::toString);
            r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"notification-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired NotificationTemplateService templates;
    @Autowired NotificationTemplateRepository templateRows;
    @Autowired NotificationTransferService transfer;
    @Autowired NotificationDeliveryService delivery;
    @Autowired NotificationDeliveryRepository deliveryRows;
    @Autowired QualityReportScheduleService schedules;
    @Autowired SystemConfigRepository configs;
    @Autowired SecretCipher cipher;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean WebhookNotificationTransport transport;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String name="notifications-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","通知验收","Notification-fixture-2026!"));
        var context=access.context(actor);return new Fixture(actor,context.projects().get(context.organizations().getFirst().id()).getFirst().id());
    }
    NotificationTemplate template(Fixture f,String name,String channel,boolean enabled) {
        configs.upsert("notifications.webhook.allowed-hosts","hooks.qa.invalid",f.actor().userId());
        return templates.create(f.actor(),f.project(),new NotificationTemplateService.TemplateInput(name,"quality.report.ready",channel,"{{project}} {{status}}","{{event}} {{task}}",
                channel.equals("WEBHOOK")?"https://hooks.qa.invalid/quality?token=private-qa-url":null,channel.equals("WEBHOOK")?"private-qa-signing":null,enabled));
    }
    ImportRequest request(Fixture f,Snapshot snapshot) {return new ImportRequest(UUID.randomUUID().toString(),f.actor().userId(),snapshot);}
    void count(Fixture f,String table,int expected) {assertEquals(expected,jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:id").param("id",f.project()).query(Integer.class).single());}
    @Test void definitionsMigrateWithNewIdentityDisabledWithoutTargetsSecretsQueuesOrSourceChanges() throws Exception {
        var source=fixture();var target=fixture();template(source,"Webhook","WEBHOOK",true);template(source,"Email","EMAIL",true);template(source,"Wechat","WECHAT",false);
        var original=templates.list(source.actor(),source.project());var snapshot=transfer.export(source.actor(),source.project());String encoded=json.writeValueAsString(snapshot);
        assertFalse(encoded.contains("private-qa"));assertFalse(encoded.contains("targetUrl"));assertFalse(encoded.contains("Ciphertext"));assertFalse(encoded.contains(source.actor().userId()));assertEquals(1,snapshot.excludedTargets());assertEquals(1,snapshot.excludedSecrets());
        var result=transfer.transfer(target.actor(),target.project(),request(target,snapshot));assertEquals(3,result.templates());assertEquals(1,result.excludedSecrets());
        var copied=templates.list(target.actor(),target.project());assertEquals(3,copied.size());
        for(var t:copied){assertFalse(t.enabled());assertNull(t.targetUrl());assertFalse(t.signingSecretConfigured());assertEquals(target.actor().userId(),t.updatedBy());assertTrue(original.stream().noneMatch(o->o.id().equals(t.id())));}
        count(target,"notification_delivery",0);assertTrue(schedules.list(target.actor()).isEmpty());assertEquals(original,templates.list(source.actor(),source.project()));
    }
    @Test void concurrentUncertainRetryKeepsSameReceiptAndDoesNotDuplicateOrOverwrite() throws Exception {
        var source=fixture();var target=fixture();template(source,"Only","EMAIL",true);var snapshot=transfer.export(source.actor(),source.project());var input=request(target,snapshot);
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->transfer.transfer(target.actor(),target.project(),input));var b=pool.submit(()->transfer.transfer(target.actor(),target.project(),input));assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        var before=templates.list(target.actor(),target.project());count(target,"notification_template",1);count(target,"notification_transfer_receipt",1);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot))).getStatusCode().value());assertEquals(before,templates.list(target.actor(),target.project()));
        var t=snapshot.templates().getFirst();var altered=new Snapshot(1,source.project(),List.of(new Template(t.id(),t.name(),t.eventCode(),t.channel(),t.subjectTemplate(),"changed",true)),0,0);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),new ImportRequest(input.requestKey(),target.actor().userId(),altered))).getStatusCode().value());
    }
    @Test void lateNameConflictRollsBackEarlierTemplatesAndReceipt() {
        var source=fixture();var target=fixture();template(source,"A","EMAIL",true);template(source,"B","EMAIL",true);template(target,"b","DINGTALK",true);
        var before=templates.list(target.actor(),target.project());var raw=transfer.export(source.actor(),source.project());var ordered=new Snapshot(1,source.project(),raw.templates().stream().sorted(Comparator.comparing(Template::name)).toList(),0,0);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,ordered))).getStatusCode().value());assertEquals(before,templates.list(target.actor(),target.project()));count(target,"notification_transfer_receipt",0);
    }
    @Test void strictUnknownFieldsInvalidCountsAndAuditFailureNeverWriteOrExposeSecrets() throws Exception {
        var source=fixture();var target=fixture();template(source,"Only","WEBHOOK",true);var snapshot=transfer.export(source.actor(),source.project());String encoded=json.writeValueAsString(request(target,snapshot));
        for(String body:List.of(encoded+" {}",encoded.replace("\"sourceEnabled\":true","\"sourceEnabled\":null"),encoded.replace("\"channel\":\"WEBHOOK\"","\"channel\":\"WEBHOOK\",\"targetUrl\":\"https://hooks.qa.invalid\""),encoded.replace("\"excludedTargets\":1","\"excludedTargets\":999"))){
            assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),transfer.decode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        }
        doThrow(new IllegalStateException("audit fixture failure")).when(audit).record(eq(target.actor()),anyString(),eq(target.project()),eq("NOTIFICATION_TEMPLATES_TRANSFERRED"),anyString(),anyString(),anyMap());
        assertThrows(IllegalStateException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot)));count(target,"notification_template",0);count(target,"notification_transfer_receipt",0);
    }
    @Test void projectReadersCannotWriteTransferOrRetryAndHttpRequiresCsrf() throws Exception {
        var source=fixture();var target=fixture();var viewer=fixture();template(source,"Only","EMAIL",true);access.upsertMember(target.actor(),target.project(),viewer.actor().getUsername(),"VIEWER");
        String route="/api/v1/projects/"+target.project()+"/notification-templates/transfer";
        mvc.perform(get(route).with(user(viewer.actor()))).andExpect(status().isForbidden());mvc.perform(get(route).with(user(source.actor()))).andExpect(status().isForbidden());
        String body=json.writeValueAsString(request(target,transfer.export(source.actor(),source.project())));
        mvc.perform(post(route).with(user(target.actor())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(route).with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/"+target.project()+"/notification-templates").with(user(viewer.actor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects/"+target.project()+"/notification-deliveries/unknown/retry").with(user(viewer.actor())).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void editedWebhookRetainsSecretWhileTemplateChannelChangesClearItAndPolicyIsStillEnforced() {
        var f=fixture();var t=template(f,"Only","WEBHOOK",true);var encrypted=templateRows.findStored(t.id(),f.project()).orElseThrow().signingSecretCiphertext();assertFalse(encrypted.contains("private-qa-signing"));
        templates.update(f.actor(),f.project(),t.id(),new NotificationTemplateService.TemplateInput("Changed",t.eventCode(),"WEBHOOK",t.subjectTemplate(),t.bodyTemplate(),t.targetUrl(),"",true));
        assertEquals(encrypted,templateRows.findStored(t.id(),f.project()).orElseThrow().signingSecretCiphertext());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->templates.update(f.actor(),f.project(),t.id(),new NotificationTemplateService.TemplateInput("Changed",t.eventCode(),"WEBHOOK",t.subjectTemplate(),t.bodyTemplate(),"http://127.0.0.1/private","",true))).getStatusCode().value());
        templates.update(f.actor(),f.project(),t.id(),new NotificationTemplateService.TemplateInput("Changed",t.eventCode(),"EMAIL",t.subjectTemplate(),t.bodyTemplate(),t.targetUrl(),"",true));assertNull(templateRows.findStored(t.id(),f.project()).orElseThrow().signingSecretCiphertext());
    }
    String queue(Fixture f) {
        var schedule=schedules.create(f.actor(),new QualityReportScheduleService.ScheduleInput("QA "+UUID.randomUUID(),List.of(f.project()),"0 0 0 1 1 ? 2099","Asia/Shanghai",7,true));
        schedules.runNow(f.actor(),schedule.id());return delivery.list(f.actor(),f.project()).getFirst().id();
    }
    @Test void renderedPayloadSignedDispatchAndRetryUseOriginalSnapshotWithoutExposingSecret() throws Exception {
        var f=fixture();template(f,"Ready","WEBHOOK",true);String id=queue(f);
        var payload=jdbc.sql("SELECT payload FROM notification_delivery WHERE id=:id").param("id",id).query(String.class).single();var body=json.readTree(payload);assertTrue(body.has("subject"));assertTrue(body.has("message"));assertFalse(body.path("subject").asText().contains("{{"));assertFalse(payload.contains("private-qa-signing"));
        doReturn(new WebhookNotificationTransport.Result(503)).when(transport).deliver(anyString(),anyMap(),anyString());delivery.dispatchDue();var retrying=deliveryRows.findById(id,f.project()).orElseThrow();assertEquals("RETRYING",retrying.status());assertEquals(1,retrying.attemptCount());
        doReturn(new WebhookNotificationTransport.Result(400)).when(transport).deliver(anyString(),anyMap(),anyString());jdbc.sql("UPDATE notification_delivery SET next_attempt_at=:now WHERE id=:id").param("now",java.sql.Timestamp.from(Instant.now().minusSeconds(1))).param("id",id).update();delivery.dispatchDue();assertEquals("FAILED",deliveryRows.findById(id,f.project()).orElseThrow().status());
        delivery.retry(f.actor(),f.project(),id);doReturn(new WebhookNotificationTransport.Result(204)).when(transport).deliver(anyString(),anyMap(),anyString());delivery.dispatchDue();assertEquals("SUCCEEDED",deliveryRows.findById(id,f.project()).orElseThrow().status());
        assertFalse(json.writeValueAsString(delivery.list(f.actor(),f.project())).contains("private-qa-signing"));
        verify(transport,atLeastOnce()).deliver(eq("https://hooks.qa.invalid/quality?token=private-qa-url"),argThat(headers->headers.get("X-TestHub-Signature").startsWith("sha256=")&&headers.get("X-TestHub-Delivery-Id").equals(id)),eq(payload));
    }
    @Test void interruptedDesktopDispatchBecomesManualFailureAndExistingPendingQueueIsNotReplayedByRecovery() {
        var f=fixture();template(f,"Ready","WEBHOOK",true);String first=queue(f),second=queue(f);deliveryRows.claim(first,Instant.now());
        assertEquals(1,deliveryRows.failInterrupted());assertEquals("FAILED",deliveryRows.findById(first,f.project()).orElseThrow().status());assertEquals("PENDING",deliveryRows.findById(second,f.project()).orElseThrow().status());assertEquals(1,deliveryRows.findById(first,f.project()).orElseThrow().attemptCount());
        delivery.retry(f.actor(),f.project(),first);assertEquals("PENDING",deliveryRows.findById(first,f.project()).orElseThrow().status());
    }
    @Test void actualHttpsTransportReceivesValidSignatureAndRenderedPayloadThenRetriesSameDelivery() throws Exception {
        var f=fixture();template(f,"HTTPS QA","WEBHOOK",true);String id=queue(f);
        Path store=directory.resolve("https-"+UUID.randomUUID()+".p12");String password="https-qa-fixture";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","qa","-keyalg","RSA","-keystore",store.toString(),"-storetype","PKCS12","-storepass",password,"-keypass",password,"-dname","CN=localhost","-validity","1","-ext","SAN=dns:localhost,ip:127.0.0.1","-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        var keys=java.security.KeyStore.getInstance("PKCS12");try(var input=java.nio.file.Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        var managers=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());
        var trusts=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());trusts.init(keys);
        var ssl=javax.net.ssl.SSLContext.getInstance("TLS");ssl.init(managers.getKeyManagers(),trusts.getTrustManagers(),null);
        var server=com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("localhost",0),0);server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ssl));
        var captured=new ConcurrentLinkedQueue<Map<String,String>>();var response=new java.util.concurrent.atomic.AtomicInteger(503);
        server.createContext("/quality",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            captured.add(Map.of("body",body,"timestamp",exchange.getRequestHeaders().getFirst("X-TestHub-Timestamp"),"signature",exchange.getRequestHeaders().getFirst("X-TestHub-Signature"),"id",exchange.getRequestHeaders().getFirst("X-TestHub-Delivery-Id")));
            exchange.sendResponseHeaders(response.get(),-1);exchange.close();
        });server.start();
        var old=javax.net.ssl.SSLContext.getDefault();
        try {
            // Only this test redirects an administrator-allowed URL to its own TLS fixture and trusts its certificate.
            var policy=new WebhookTargetPolicy(configs){@Override public String normalizeAndAssertAllowed(String url){super.normalizeAndAssertAllowed(url);return "https://localhost:"+server.getAddress().getPort()+"/quality";}};
            javax.net.ssl.SSLContext.setDefault(ssl);var http=new WebhookNotificationTransport(policy);javax.net.ssl.SSLContext.setDefault(old);
            var dispatcher=new NotificationDeliveryService(deliveryRows,templateRows,access,audit,cipher,http,json);
            dispatcher.dispatchDue();assertEquals("RETRYING",deliveryRows.findById(id,f.project()).orElseThrow().status());
            response.set(204);jdbc.sql("UPDATE notification_delivery SET next_attempt_at=:now WHERE id=:id").param("now",java.sql.Timestamp.from(Instant.now().minusSeconds(1))).param("id",id).update();dispatcher.dispatchDue();assertEquals("SUCCEEDED",deliveryRows.findById(id,f.project()).orElseThrow().status());
            var own=captured.stream().filter(m->m.get("id").equals(id)).toList();assertEquals(2,own.size());assertEquals(own.get(0).get("body"),own.get(1).get("body"));
            for(var wire:own){var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("private-qa-signing".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
                String signature="sha256="+HexFormat.of().formatHex(mac.doFinal((wire.get("timestamp")+"\n"+wire.get("body")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(signature,wire.get("signature"));
                var payload=json.readTree(wire.get("body"));assertEquals("quality.report.ready",payload.path("event").asText());assertTrue(payload.path("subject").asText().endsWith("READY"));assertTrue(payload.path("message").asText().startsWith("quality.report.ready"));}
        } finally {javax.net.ssl.SSLContext.setDefault(old);server.stop(0);java.nio.file.Files.deleteIfExists(store);}
    }

    @Test void oversizedRenderedSubjectFailsVisiblyInsteadOfSilentlyDroppingNotification() {
        var f=fixture();var t=template(f,"Long subject","WEBHOOK",true);
        templates.update(f.actor(),f.project(),t.id(),new NotificationTemplateService.TemplateInput(t.name(),t.eventCode(),t.channel(),"{{task}}".repeat(20),t.bodyTemplate(),t.targetUrl(),"",true));
        var schedule=schedules.create(f.actor(),new QualityReportScheduleService.ScheduleInput("QA "+"x".repeat(100),List.of(f.project()),"0 0 0 1 1 ? 2099","Asia/Shanghai",7,true));
        assertEquals("FAILED",schedules.runNow(f.actor(),schedule.id()).status());count(f,"notification_delivery",0);
    }

}
