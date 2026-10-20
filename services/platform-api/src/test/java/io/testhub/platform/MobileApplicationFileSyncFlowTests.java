package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.mobileautomation.*;
import io.testhub.platform.storage.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false","server.address=127.0.0.1"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class MobileApplicationFileSyncFlowTests {
 @TempDir static Path directory;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
  registry.add("testhub.storage.type",()->"filesystem");registry.add("testhub.storage.directory",()->directory.resolve("objects").toString());
  if(FunctionalDatabaseProfile.mysql())FunctionalDatabaseProfile.configure(registry,directory,"application-file-fixture");
  else{registry.add("testhub.desktop.data-directory",directory::toString);registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");registry.add("spring.datasource.username",()->"sa");registry.add("spring.datasource.password",()->"application-file-fixture");}
 }
 @org.springframework.boot.test.web.server.LocalServerPort int port;
 @Autowired RegistrationService registration;
 @Autowired AccessService access;
 @Autowired MobileAutomationService mobile;
 @Autowired MobileAutomationSyncService definitions;
 @Autowired MobileApplicationFileSyncService sync;
 @Autowired ProjectFileSyncService fileSync;
 @Autowired FileObjectService files;
 @Autowired ObjectStorage storage;
 @Autowired JdbcClient jdbc;
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired PlatformTransactionManager manager;
 @org.springframework.beans.factory.annotation.Value("${testhub.storage.bucket}") String bucket;
 record Fixture(PlatformUserPrincipal actor,String project){}
 Fixture fixture(){String account="appfile-"+UUID.randomUUID();var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","文件关联验收","File-association-2026!"));var context=access.context(actor);return new Fixture(actor,context.projects().get(context.organizations().getFirst().id()).getFirst().id());}
 MobileAutomationService.Application app(Fixture f,String name,String file,boolean enabled){return mobile.createApplication(f.actor(),f.project(),new MobileAutomationService.ApplicationRequest(name,"ANDROID","owned.application.id","v-original",file,enabled));}
 FileObject upload(Fixture f,String name,byte[] bytes){return files.upload(f.actor(),f.project(),new MockMultipartFile("file",name,"application/octet-stream",bytes));}
 MobileApplicationFileSyncService.Snapshot snapshot(Fixture f){return sync.snapshot(f.actor(),f.project(),f.actor().userId());}
 MobileApplicationFileSyncService.Request request(Fixture f,MobileAutomationService.Application app,FileObject file){var before=snapshot(f);var metadata=before.files().stream().filter(v->v.id().equals(file.id())).findFirst().orElseThrow();return new MobileApplicationFileSyncService.Request(UUID.randomUUID().toString(),f.actor().userId(),before.revision(),app.id(),app.version(),app.appFileId(),file.id(),metadata.size(),metadata.sha256());}
 FileObject copy(Fixture s,Fixture t,FileObject file,byte[] bytes){var source=fileSync.snapshot(s.actor(),s.project(),s.actor().userId()).items().stream().filter(v->v.id().equals(file.id())).findFirst().orElseThrow();var target=fileSync.snapshot(t.actor(),t.project(),t.actor().userId());var receipt=fileSync.apply(t.actor(),t.project(),new ProjectFileSyncService.Request(UUID.randomUUID().toString(),t.actor().userId(),target.revision(),source.key(),source.name(),source.type(),source.size(),source.sha256(),bytes));return files.metadata(t.actor(),t.project(),receipt.fileId());}
 MobileAutomationService.Application current(Fixture f,String id){return mobile.applications(f.actor(),f.project()).stream().filter(a->a.id().equals(id)).findFirst().orElseThrow();}
 long count(Fixture f,String table){return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",f.project()).query(Long.class).single();}
 String route(Fixture f){return "/api/v1/projects/"+f.project()+"/mobile-automation/application-files/sync";}
 @Test void mappedTargetIdentityAttachesWithoutInstallingDispatchingOrChangingApplicationConfiguration()throws Exception{
  var source=fixture();var target=fixture();byte[] bytes="owned APK byte fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);var original=upload(source,"fixture.apk",bytes);var sourceApp=app(source,"移动应用",original.id(),true);var targetApp=app(target,"移动应用",null,false);var copied=copy(source,target,original,bytes);assertNotEquals(original.id(),copied.id());
  var request=request(target,targetApp,copied);var receipt=sync.apply(target.actor(),target.project(),request);assertEquals(receipt,sync.apply(target.actor(),target.project(),request));
  var actual=current(target,targetApp.id());assertEquals(copied.id(),actual.appFileId());assertEquals(2,actual.version());assertFalse(actual.enabled());assertEquals("owned.application.id",actual.appIdentifier());assertEquals("v-original",actual.versionName());assertEquals(sourceApp,current(source,sourceApp.id()));
  assertEquals(0,count(target,"mobile_automation_execution"));assertEquals(0,count(target,"mobile_automation_scenario"));assertEquals(1,count(target,"mobile_application_file_sync_receipt"));assertArrayEquals(bytes,files.download(target.actor(),target.project(),copied.id()).readAllBytes());
 }
 @Test void targetAppOrFileRevisionChangeRejectsTheWholeAssociation(){
  var f=fixture();var a=app(f,"应用",null,true);var file=upload(f,"fixture.apk",new byte[]{1});var request=request(f,a,file);
  mobile.updateApplication(f.actor(),f.project(),a.id(),a.version(),new MobileAutomationService.ApplicationRequest(a.name(),a.platform(),a.appIdentifier(),"edited",null,false));
  assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request));assertNull(current(f,a.id()).appFileId());assertEquals(0,count(f,"mobile_application_file_sync_receipt"));
 }
 @Test void requestKeyCannotBeReusedForAnotherFileAndAFileAssociationCannotBeCleared(){
  var f=fixture();var a=app(f,"应用",null,true);var first=upload(f,"first.apk",new byte[]{1});var second=upload(f,"second.apk",new byte[]{2});var request=request(f,a,first);sync.apply(f.actor(),f.project(),request);
  var changed=request(f,current(f,a.id()),second);var reused=new MobileApplicationFileSyncService.Request(request.requestKey(),changed.expectedUserId(),changed.expectedRevision(),changed.applicationId(),changed.expectedVersion(),changed.expectedFileId(),changed.fileId(),changed.size(),changed.sha256());
  assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),reused));var clear=new MobileApplicationFileSyncService.Request(changed.requestKey(),changed.expectedUserId(),changed.expectedRevision(),changed.applicationId(),changed.expectedVersion(),changed.expectedFileId(),null,changed.size(),changed.sha256());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),clear));assertEquals(first.id(),current(f,a.id()).appFileId());
 }
 @Test void csrfActorProjectAndViewerPermissionsAreEnforced()throws Exception{
  var f=fixture();var stranger=fixture();var a=app(f,"应用",null,false);var file=upload(f,"fixture.apk",new byte[]{1});var request=request(f,a,file);
  mvc.perform(post(route(f)).with(user(f.actor())).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
  mvc.perform(post(route(f)).with(user(stranger.actor())).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
  assertThrows(ResponseStatusException.class,()->sync.snapshot(f.actor(),f.project(),stranger.actor().userId()));
  jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:u").param("u",f.actor().userId()).update();mvc.perform(get(route(f)).param("expectedUserId",f.actor().userId()).with(user(f.actor()))).andExpect(status().isForbidden());mvc.perform(post(route(f)).with(user(f.actor())).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().isForbidden());
 }
 @Test void cachedDigestCannotAuthorizeCorruptedPhysicalBytes()throws Exception{
  var f=fixture();var a=app(f,"应用",null,true);var file=upload(f,"fixture.apk",new byte[]{1,2,3});var request=request(f,a,file);
  storage.put(bucket,file.objectKey(),new ByteArrayInputStream(new byte[]{3,2,1}),3,file.contentType());
  assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request));assertNull(current(f,a.id()).appFileId());assertEquals(0,count(f,"mobile_application_file_sync_receipt"));
 }
 @Test void transactionRollbackRestoresOnlyTheAssociationAndItsReceipt(){
  var f=fixture();var a=app(f,"应用",null,true);var file=upload(f,"fixture.apk",new byte[]{1});var request=request(f,a,file);
  new TransactionTemplate(manager).executeWithoutResult(status->{sync.apply(f.actor(),f.project(),request);status.setRollbackOnly();});
  assertEquals(a,current(f,a.id()));assertEquals(0,count(f,"mobile_application_file_sync_receipt"));assertEquals(1,files.list(f.actor(),f.project()).size());sync.apply(f.actor(),f.project(),request);assertEquals(2,current(f,a.id()).version());
 }
 @Test void missingAppsOrFilesAreNeverRecreatedAndReceiptReplayCannotRecreateAFile(){
  var f=fixture();var a=app(f,"应用",null,false);var file=upload(f,"fixture.apk",new byte[]{1});var request=request(f,a,file);sync.apply(f.actor(),f.project(),request);files.delete(f.actor(),f.project(),file.id());
  assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request));assertTrue(files.list(f.actor(),f.project()).isEmpty());assertEquals(file.id(),current(f,a.id()).appFileId());
  mobile.deleteApplication(f.actor(),f.project(),a.id());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request));assertTrue(mobile.applications(f.actor(),f.project()).isEmpty());
 }
 @Test void legacyMobileDefinitionSyncPreservesTheNewFileAssociation(){
  var f=fixture();var a=app(f,"应用",null,false);var file=upload(f,"fixture.apk",new byte[]{1});sync.apply(f.actor(),f.project(),request(f,a,file));
  var before=definitions.snapshot(f.actor(),f.project(),f.actor().userId());assertEquals(1,before.version());var item=new MobileAutomationSyncService.Item("APPLICATION",a.name(),Map.of("platform","ANDROID","appIdentifier","owned.changed.id","versionName","v-next"));
  definitions.apply(f.actor(),f.project(),new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),f.actor().userId(),before.revision(),List.of(new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),item))));
  var actual=current(f,a.id());assertEquals(file.id(),actual.appFileId());assertFalse(actual.enabled());assertEquals("v-next",actual.versionName());assertEquals("owned.changed.id",actual.appIdentifier());
 }
 @Test void changingAnApplicationFileCannotRewriteAnAlreadyQueuedExecutionManifest(){
  var f=fixture();var first=upload(f,"first.apk",new byte[]{1});var second=upload(f,"second.apk",new byte[]{2});var a=app(f,"应用",first.id(),true);
  var device=mobile.createDevice(f.actor(),f.project(),new MobileAutomationService.DeviceRequest("隔离协议设备","ANDROID","owned-fixture-udid","UiAutomator2",Map.of(),true));
  var scenario=mobile.createScenario(f.actor(),f.project(),new MobileAutomationService.ScenarioRequest(device.id(),a.id(),"冻结配置","","STOP",Map.of(),List.of(new MobileAutomationService.Step("launch","LAUNCH","启动",null,null,null,30000))));
  var execution=mobile.execute(f.actor(),f.project(),scenario.id(),120);String manifest=jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:e").param("e",execution.id()).query(String.class).single();
  sync.apply(f.actor(),f.project(),request(f,a,second));assertEquals(second.id(),current(f,a.id()).appFileId());
  assertEquals(manifest,jdbc.sql("SELECT content FROM mobile_automation_execution_manifest WHERE execution_id=:e").param("e",execution.id()).query(String.class).single());assertEquals(execution,mobile.report(f.actor(),f.project(),execution.id()).execution());assertEquals(1,count(f,"mobile_automation_execution"));
 }
 @Test void rejectsUnknownFieldsOversizedBodiesAndForeignPhysicalFileIdentities()throws Exception{
  var f=fixture();var other=fixture();var a=app(f,"应用",null,false);var own=upload(f,"own.apk",new byte[]{1});var foreign=upload(other,"foreign.apk",new byte[]{1});var request=request(f,a,own);var tree=json.valueToTree(request);((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("rawContent","unsupported");assertThrows(ResponseStatusException.class,()->sync.decode(json.writeValueAsBytes(tree)));
  assertEquals(413,assertThrows(ResponseStatusException.class,()->sync.decode(new byte[MobileApplicationFileSyncService.MAX_BYTES+1])).getStatusCode().value());
  var forged=new MobileApplicationFileSyncService.Request(request.requestKey(),request.expectedUserId(),request.expectedRevision(),request.applicationId(),request.expectedVersion(),request.expectedFileId(),foreign.id(),request.size(),request.sha256());assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),forged));assertNull(current(f,a.id()).appFileId());
 }
 @Test void snapshotRejectsApplicationOverflowAndKeepsAllRecords(){
  var f=fixture();for(int n=0;n<101;n++)app(f,"应用"+n,null,false);assertThrows(ResponseStatusException.class,()->snapshot(f));assertEquals(101,mobile.applications(f.actor(),f.project()).size());
 }
 @Test void multipartAcceptsExactTwentyMiBButRejectsOneExtraFileByte()throws Exception{
  var f=fixture();var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(java.time.Duration.ofSeconds(5)).build();String origin="http://127.0.0.1:"+port;
  var csrfResponse=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/v1/auth/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());var token=json.readTree(csrfResponse.body()).path("data");
  var login=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/v1/auth/login")).header("Content-Type","application/json").header(token.path("headerName").asText(),token.path("token").asText()).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("email",f.actor().getUsername(),"password","File-association-2026!")))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,login.statusCode());
  var fresh=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/v1/auth/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());token=json.readTree(fresh.body()).path("data");
  for(int extra:new int[]{0,1}){
   String boundary="owned-fixture-"+UUID.randomUUID();byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"boundary.apk\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII),suffix=("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
   var body=java.net.http.HttpRequest.BodyPublishers.concat(java.net.http.HttpRequest.BodyPublishers.ofByteArray(prefix),java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[ProjectFileSyncService.MAX_FILE+extra]),java.net.http.HttpRequest.BodyPublishers.ofByteArray(suffix));
   var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/v1/projects/"+f.project()+"/files")).timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).header(token.path("headerName").asText(),token.path("token").asText()).POST(body).build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(extra==0?200:413,response.statusCode());if(extra==1)assertEquals("REQUEST_REJECTED",json.readTree(response.body()).path("code").asText());
  }
  assertEquals(1,files.list(f.actor(),f.project()).size());assertEquals(ProjectFileSyncService.MAX_FILE,files.list(f.actor(),f.project()).getFirst().sizeBytes());
 }

}
