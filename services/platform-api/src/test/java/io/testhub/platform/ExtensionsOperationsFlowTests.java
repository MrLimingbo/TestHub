package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.*;
import io.testhub.platform.account.*;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.extension.*;
import io.testhub.platform.extension.ExtensionRegistryService.*;
import io.testhub.platform.operations.*;
import io.testhub.platform.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
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

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class ExtensionsOperationsFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if(FunctionalDatabaseProfile.mysql())FunctionalDatabaseProfile.configure(r,directory,"extensions-fixture");
        else{r.add("testhub.desktop.data-directory",directory::toString);r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"extensions-fixture");}
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired AccessRepository memberships;
    @Autowired ExtensionRegistryService extensions;
    @Autowired ExtensionRegistryRepository rows;
    @Autowired OperationsService operations;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AuditService audit;
    @Autowired ObjectStorage storage;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired io.testhub.platform.shared.runtime.RuntimeStreams streams;
    @Autowired io.testhub.platform.security.SecretCipher cipher;
    @Autowired io.testhub.platform.security.SecretRekeyService rekey;
    @Autowired org.springframework.core.env.Environment environment;
    PlatformUserPrincipal fixture(boolean admin){String name="extensions-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(name,name+"@example.test","插件运维验收","Extensions-fixture-2026!"));if(admin){String org=access.context(actor).organizations().getFirst().id();memberships.insertMembership(UlidCreator.getUlid().toString(),actor.userId(),org,null,"SYSTEM_ADMIN");}return actor;}
    record Signer(KeyPair pair,String keyId,PluginSigningKey key) { }
    Signer signer(PlatformUserPrincipal actor)throws Exception{var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();String id="qa."+UUID.randomUUID();return new Signer(pair,id,extensions.registerSigningKey(actor,new SigningKeyInput(id,"QA 公钥",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))));}
    String manifest(String key,String version)throws Exception{return json.writeValueAsString(Map.of("pluginKey",key,"displayName","QA 插件","pluginType","INTEGRATION","version",version,"entrypoint","adapter/index.js","permissions",List.of("integration:outbound")));}
    PackageInput signed(Signer signer,String raw)throws Exception{var signature=Signature.getInstance("Ed25519");signature.initSign(signer.pair().getPrivate());signature.update(raw.getBytes(StandardCharsets.UTF_8));return new PackageInput(signer.keyId(),raw,Base64.getEncoder().encodeToString(signature.sign()));}
    AdapterInput adapter(String name,Map<String,String> config){return new AdapterInput("OIDC",name+" "+UUID.randomUUID(),"https://login.qa.invalid",config,false);}
    Map<String,String> config(String secret){return Map.of("clientId","qa-client","clientSecret",secret,"redirectUri","https://testhub.qa.invalid/login");}
    @Test void exactWhitespaceSignedManifestInstallsAndChecksumMatchesOriginalCharacters()throws Exception{
        var actor=fixture(true);var signer=signer(actor);String raw=" \n"+manifest("qa."+UUID.randomUUID(),"1.0.0")+"\n \t";
        var installed=extensions.installPackage(actor,signed(signer,raw));assertEquals("DISABLED",installed.status());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))),installed.packageChecksum());
        var input=signed(signer,raw);assertEquals(400,assertThrows(ResponseStatusException.class,()->extensions.installPackage(actor,new PackageInput(input.signerKeyId(),raw.trim(),input.signatureBase64()))).getStatusCode().value());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM platform_plugin_release WHERE plugin_id=:id").param("id",installed.id()).query(Integer.class).single());
    }
    @Test void largeSemanticVersionsUpgradeWithoutIntegerOverflowAndRemainDisabled()throws Exception{
        var actor=fixture(true);var signer=signer(actor);String key="qa."+UUID.randomUUID();var installed=extensions.installPackage(actor,signed(signer,manifest(key,"99999999999999999999.0.0")));extensions.setPluginEnabled(actor,installed.id(),true);
        var upgraded=extensions.installPackage(actor,signed(signer,manifest(key,"100000000000000000000.0.0")));assertEquals(installed.id(),upgraded.id());assertEquals("DISABLED",upgraded.status());assertEquals("100000000000000000000.0.0",upgraded.activeVersion());
        var old=signed(signer,manifest(key,"99999999999999999999.1.0"));assertEquals(409,assertThrows(ResponseStatusException.class,()->extensions.installPackage(actor,old)).getStatusCode().value());
    }
    @Test void revokedSignerBlocksNewInstallsWithoutChangingRegisteredPluginStatus()throws Exception{
        var actor=fixture(true);var signer=signer(actor);var installed=extensions.installPackage(actor,signed(signer,manifest("qa."+UUID.randomUUID(),"1.0.0")));extensions.setPluginEnabled(actor,installed.id(),true);extensions.setSigningKeyEnabled(actor,signer.key().id(),false);
        var next=signed(signer,manifest(installed.pluginKey(),"2.0.0"));assertEquals(400,assertThrows(ResponseStatusException.class,()->extensions.installPackage(actor,next)).getStatusCode().value());assertEquals("ENABLED",rows.findPlugin(installed.id()).orElseThrow().status());
        extensions.setSigningKeyEnabled(actor,signer.key().id(),true);assertEquals("DISABLED",extensions.installPackage(actor,next).status());
    }
    @Test void adapterMetadataEditPreservesEncryptedConfigAndReplacementDoesNotExposePlaintext()throws Exception{
        var actor=fixture(true);var created=extensions.saveAdapter(actor,null,adapter("QA 原配置",config("qa-secret-original")));String encrypted=rows.findAdapter(created.id()).orElseThrow().configurationCiphertext();
        var edited=extensions.saveAdapter(actor,created.id(),adapter("QA 新名称",null));assertTrue(edited.displayName().startsWith("QA 新名称 "));assertEquals(encrypted,rows.findAdapter(created.id()).orElseThrow().configurationCiphertext());
        extensions.saveAdapter(actor,created.id(),adapter("QA 新名称",config("qa-secret-replaced")));assertNotEquals(encrypted,rows.findAdapter(created.id()).orElseThrow().configurationCiphertext());String projection=json.writeValueAsString(extensions.adapterConfigurations(actor));assertFalse(projection.contains("qa-secret-"));assertFalse(projection.contains("configurationCiphertext"));assertTrue(projection.contains("clientSecret"));
        var auditRows=jdbc.sql("SELECT detail FROM audit_log WHERE resource_id=:id").param("id",created.id()).query(String.class).list();assertTrue(auditRows.stream().noneMatch(d->d.contains("qa-secret-")));
    }
    @Test void concurrentMetadataEditCannotOverwriteAReplacementSecret()throws Exception{
        var actor=fixture(true);var created=extensions.saveAdapter(actor,null,adapter("Concurrent",config("qa-initial")));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            for(int n=0;n<5;n++){
                String replacement="qa-concurrent-"+n;var start=new java.util.concurrent.CountDownLatch(1);
                var metadata=pool.submit(()->{start.await();return extensions.saveAdapter(actor,created.id(),adapter("Metadata",null));});
                var credentials=pool.submit(()->{start.await();return extensions.saveAdapter(actor,created.id(),adapter("Credentials",config(replacement)));});
                start.countDown();metadata.get(20,java.util.concurrent.TimeUnit.SECONDS);credentials.get(20,java.util.concurrent.TimeUnit.SECONDS);
                var payload=json.readTree(cipher.decrypt(rows.findAdapter(created.id()).orElseThrow().configurationCiphertext()));assertEquals(replacement,payload.path("clientSecret").asText());
            }
        }
    }
    @Test void nullOrIncompleteConfigurationCannotCreateOrChangeAdapterType(){
        var actor=fixture(true);assertThrows(ResponseStatusException.class,()->extensions.saveAdapter(actor,null,adapter("Missing",null)));var created=extensions.saveAdapter(actor,null,adapter("QA",config("qa-private")));String encrypted=rows.findAdapter(created.id()).orElseThrow().configurationCiphertext();
        for(var input:List.of(new AdapterInput("JIRA","QA","https://jira.qa.invalid",null,false),adapter("QA",Map.of("clientId","partial")),adapter("QA",Map.of())))assertThrows(ResponseStatusException.class,()->extensions.saveAdapter(actor,created.id(),input));
        assertEquals(encrypted,rows.findAdapter(created.id()).orElseThrow().configurationCiphertext());
    }
    @Test void ordinaryRegisteredUsersReadProjectionsButCannotManageGlobalExtensionsOrOperations()throws Exception{
        var actor=fixture(false);for(String route:List.of("/plugins","/adapters","/adapter-definitions"))mvc.perform(get("/api/v1/extensions"+route).with(user(actor))).andExpect(status().isOk());for(String route:List.of("/api/v1/extensions/signing-keys","/api/v1/operations/overview"))mvc.perform(get(route).with(user(actor))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/extensions/adapters").with(user(actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(adapter("QA",config("qa-private"))))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/extensions/plugins/unknown/enable").with(user(actor)).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void administratorWritesRequireCsrfAndAuditFailureRollsBackAdapterChanges()throws Exception{
        var actor=fixture(true);mvc.perform(post("/api/v1/extensions/adapters").with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(adapter("QA",config("qa-private"))))).andExpect(status().isForbidden());
        var created=extensions.saveAdapter(actor,null,adapter("QA",config("qa-private")));var before=rows.findAdapter(created.id()).orElseThrow();doThrow(new IllegalStateException("QA audit failure")).when(audit).record(eq(actor),isNull(),isNull(),eq("ENTERPRISE_ADAPTER_UPDATED"),anyString(),eq(created.id()),anyMap());assertThrows(IllegalStateException.class,()->extensions.saveAdapter(actor,created.id(),adapter("Changed",null)));assertEquals(before,rows.findAdapter(created.id()).orElseThrow());
    }
    @Test void runtimeProjectionReflectsProfileAndRedactsStorageFailures()throws Exception{
        var actor=fixture(true);var overview=operations.overview(actor);assertEquals(FunctionalDatabaseProfile.mysql()?"SERVER":"DESKTOP",overview.runtimeMode());assertEquals("UP",overview.status());assertEquals(3,overview.components().size());
        if(FunctionalDatabaseProfile.mysql())assertTrue(overview.controls().stream().anyMatch(c->c.key().equals("helm-chart")));else{assertTrue(overview.controls().stream().noneMatch(c->c.key().equals("helm-chart")));assertTrue(overview.controls().stream().anyMatch(c->c.key().equals("desktop-distribution")&&c.status().equals("ATTENTION")));assertTrue(overview.dataProtection().controls().stream().anyMatch(c->c.detail().contains("桌面")));}
        var unavailable = mock(ObjectStorage.class);when(unavailable.label()).thenReturn("QA storage");doThrow(new java.io.IOException("QA private endpoint/token/secret")).when(unavailable).bucketExists(anyString());
        var failureProjection = new OperationsService(access,dataSource,streams,unavailable,environment.getProperty("testhub.storage.bucket"),false,List.of(),60,6,500,1,cipher,rekey,environment);
        var failed=failureProjection.overview(actor);assertEquals("ATTENTION",failed.status());assertTrue(failed.components().stream().anyMatch(c->c.key().equals("storage")&&c.status().equals("DOWN")));assertFalse(json.writeValueAsString(failed).contains("QA private"));
    }
}
