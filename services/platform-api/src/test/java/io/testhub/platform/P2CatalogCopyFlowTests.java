package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.apidesign.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

class P2CatalogCopyFlowTests extends P1FlowTestSupport {
    @Autowired ApiDesignService design;
    @MockitoSpyBean ApiDesignRepository repository;
    @AfterEach void restore() { reset(repository); }
    record Fixture(PlatformUserPrincipal actor,String project,ApiCatalog root) { }
    Fixture fixture(int definitions,int children) {
        var a=actor();String p=project(a);var root=design.createCatalog(a,p,new ApiDesignService.CatalogInput("源目录",null,0));
        for(int n=0;n<children;n++)design.createCatalog(a,p,new ApiDesignService.CatalogInput("子目录"+n,root.id(),n));
        for(int n=0;n<definitions;n++)design.createDefinition(a,p,new ApiDesignService.DefinitionInput(root.id(),"GET","/p2/"+n,"接口"+n,"1.0.0","","",List.of(),List.of(),List.of(),"","{\"kept\":true}",Map.of("statusCode",201)));
        return new Fixture(a,p,root);
    }
    String path(Fixture f){return "/api/v1/projects/"+f.project+"/api-design/catalogs/"+f.root.id();}
    JsonNode preview(Fixture f) throws Exception {return json.readTree(mvc.perform(get(path(f)+"/copy-preview").with(user(f.actor))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    MvcResult copy(Fixture f,String key,String digest) throws Exception {return mvc.perform(post(path(f)+"/copy").with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("requestKey",key,"expectedSourceDigest",digest)))).andReturn();}
    JsonNode result(MvcResult result){try{return json.readTree(result.getResponse().getContentAsString()).path("data");}catch(Exception e){throw new IllegalStateException(e);}}
    long count(String table,Fixture f){return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE project_id=:p").param("p",f.project).query(Long.class).single();}
    void failInMiddle(int position){var calls=new AtomicInteger();doAnswer(call->{if(calls.incrementAndGet()==position)throw new org.springframework.dao.DataIntegrityViolationException("controlled copy failure");return call.callRealMethod();}).when(repository).insertDefinition(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());}
    @Test @DisplayName("[TC-P2-D10-01][POS] one server operation copies the source tree and current versions")
    void successfulTree() throws Exception {var f=fixture(2,1);var original=design.definitions(f.actor,f.project,null).getFirst();
        design.createVersion(f.actor,f.project,original.id(),new ApiDesignService.VersionInput("2.0.0","current","latest only",List.of(),List.of(),List.of(),"","{\"latest\":true}",Map.of("statusCode",204)));
        var p=preview(f);assertEquals(4,p.path("itemCount").asInt());var r=copy(f,UUID.randomUUID().toString(),p.path("sourceDigest").asText());assertEquals(200,r.getResponse().getStatus());var receipt=result(r);assertEquals(2,receipt.path("catalogCount").asInt());assertEquals(2,receipt.path("definitionCount").asInt());assertEquals(4,count("api_catalog",f));assertEquals(4,count("api_definition",f));assertTrue(design.definitions(f.actor,f.project,null).stream().anyMatch(a->a.path().endsWith("-copy")));var copiedApis=design.definitions(f.actor,f.project,null).stream().filter(a->a.path().contains("-copy")).toList();assertTrue(copiedApis.stream().allMatch(a->design.versions(f.actor,f.project,a.id()).size()==1));assertTrue(copiedApis.stream().flatMap(a->design.versions(f.actor,f.project,a.id()).stream()).anyMatch(v->v.versionName().equals("2.0.0")&&v.responseBody().contains("latest")));}
    @Test @DisplayName("[TC-P2-D10-02][NEG] failure after an earlier copied definition rolls back the entire branch")
    void middleFailure() throws Exception {var f=fixture(2,1);String digest=preview(f).path("sourceDigest").asText();long audit=auditCount();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("controlled first write failure")).when(repository).insertCatalog(anyString(),anyString(),anyString(),nullable(String.class),anyString(),anyInt(),anyString());
        assertTrue(copy(f,UUID.randomUUID().toString(),digest).getResponse().getStatus()>=400);assertEquals(2,count("api_catalog",f));assertEquals(0,count("api_catalog_copy_receipt",f));assertEquals(audit,auditCount());reset(repository);for(int position:List.of(1,2)){failInMiddle(position);var r=copy(f,UUID.randomUUID().toString(),digest);assertTrue(r.getResponse().getStatus()>=400);assertEquals(2,count("api_catalog",f));assertEquals(2,count("api_definition",f));assertEquals(0,count("api_catalog_copy_receipt",f));assertEquals(audit,auditCount());reset(repository);}}
    @Test @DisplayName("[TC-P2-D10-03][NEG] receipt persistence failure leaves no copied assets or successful audit")
    void receiptFailure() throws Exception {var f=fixture(1,0);String digest=preview(f).path("sourceDigest").asText(),key=UUID.randomUUID().toString(),constraint="p2_copy_"+UUID.randomUUID().toString().replace("-","").substring(0,12);jdbc.sql("ALTER TABLE api_catalog_copy_receipt ADD CONSTRAINT "+constraint+" CHECK(request_key <> '"+key+"')").update();long audit=auditCount();var r=copy(f,key,digest);assertTrue(r.getResponse().getStatus()>=400);assertEquals(1,count("api_catalog",f));assertEquals(1,count("api_definition",f));assertEquals(audit,auditCount());}
    @Test @DisplayName("[TC-P2-D10-04][POS] replaying the same accepted request returns the original copy IDs")
    void lostResponse() throws Exception {var f=fixture(1,0);String digest=preview(f).path("sourceDigest").asText(),key=UUID.randomUUID().toString();var first=copy(f,key,digest);assertEquals(200,first.getResponse().getStatus());var second=copy(f,key,digest);assertEquals(200,second.getResponse().getStatus());assertEquals(result(first),result(second));assertEquals(2,count("api_catalog",f));assertEquals(1,count("api_catalog_copy_receipt",f));}
    @Test @DisplayName("[TC-P2-D10-05][NEG] reusing a request key with a changed payload is a conflict")
    void differentPayload() throws Exception {var f=fixture(0,0);String digest=preview(f).path("sourceDigest").asText(),key=UUID.randomUUID().toString();assertEquals(200,copy(f,key,digest).getResponse().getStatus());assertEquals(409,copy(f,key,"0".repeat(64)).getResponse().getStatus());assertEquals(2,count("api_catalog",f));}
    @Test @DisplayName("[TC-P2-D10-06][POS] concurrent retries of one request commit only one copy")
    void concurrentRetry() throws Exception {var f=fixture(1,0);String digest=preview(f).path("sourceDigest").asText(),key=UUID.randomUUID().toString();try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var one=pool.submit(()->{gate.await();return copy(f,key,digest);});var two=pool.submit(()->{gate.await();return copy(f,key,digest);});gate.countDown();var a=one.get(15,TimeUnit.SECONDS);var b=two.get(15,TimeUnit.SECONDS);assertEquals(200,a.getResponse().getStatus());assertEquals(200,b.getResponse().getStatus());assertEquals(result(a),result(b));}assertEquals(2,count("api_catalog",f));}
    @Test @DisplayName("[TC-P2-D10-07][POS] receipt and its response are persisted independently of browser state")
    void durableReceipt() throws Exception {var f=fixture(0,0);String key=UUID.randomUUID().toString(),digest=preview(f).path("sourceDigest").asText();var r=copy(f,key,digest);assertEquals(200,r.getResponse().getStatus());String saved=jdbc.sql("SELECT result_json FROM api_catalog_copy_receipt WHERE project_id=:p AND request_key=:k").param("p",f.project).param("k",key).query(String.class).single();assertEquals(result(r),json.readTree(saved));assertEquals(result(r),result(copy(f,key,digest)));}
    @Test @DisplayName("[TC-P2-D10-08][POS] an explicitly new request key creates a separate sibling copy")
    void explicitNewCopy() throws Exception {var f=fixture(0,0);String digest=preview(f).path("sourceDigest").asText();var a=copy(f,UUID.randomUUID().toString(),digest);var b=copy(f,UUID.randomUUID().toString(),digest);assertEquals(200,a.getResponse().getStatus());assertEquals(200,b.getResponse().getStatus());assertNotEquals(result(a).path("rootCatalogId"),result(b).path("rootCatalogId"));assertEquals(3,count("api_catalog",f));}
    @Test @DisplayName("[TC-P2-D10-09][NEG] stale source preview cannot copy a changed subtree")
    void staleSource() throws Exception {var f=fixture(1,0);String digest=preview(f).path("sourceDigest").asText();design.createCatalog(f.actor,f.project,new ApiDesignService.CatalogInput("后来新增",f.root.id(),1));assertEquals(409,copy(f,UUID.randomUUID().toString(),digest).getResponse().getStatus());assertEquals(2,count("api_catalog",f));}
    @Test @DisplayName("[TC-P2-D10-10][NEG] 201 source items cannot leave a partially copied branch")
    void overLimit() throws Exception {var f=fixture(0,200);var other=actor();
        int denied=mvc.perform(get(path(f)+"/copy-preview").with(user(other))).andReturn().getResponse().getStatus();assertTrue(denied==403||denied==404);
        denied=mvc.perform(post(path(f)+"/copy").with(user(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("requestKey",UUID.randomUUID().toString(),"expectedSourceDigest","a".repeat(64))))).andReturn().getResponse().getStatus();assertTrue(denied==403||denied==404);
        var p=preview(f);assertEquals(201,p.path("itemCount").asInt());assertEquals(400,copy(f,UUID.randomUUID().toString(),p.path("sourceDigest").asText()).getResponse().getStatus());assertEquals(201,count("api_catalog",f));}
    @Test @DisplayName("[TC-P2-D10-11][POS] exactly 200 source items are accepted atomically")
    void atLimit() throws Exception {var f=fixture(0,199);var p=preview(f);assertEquals(200,p.path("itemCount").asInt());assertEquals(200,copy(f,UUID.randomUUID().toString(),p.path("sourceDigest").asText()).getResponse().getStatus());assertEquals(400,count("api_catalog",f));}
    @Test @DisplayName("[TC-P2-D10-12][NEG] old partial copies are not cleaned as part of a new request")
    void oldPartialRetained() throws Exception {var f=fixture(1,0);var old=design.createCatalog(f.actor,f.project,new ApiDesignService.CatalogInput("源目录 副本",null,0));String digest=preview(f).path("sourceDigest").asText();assertEquals(200,copy(f,UUID.randomUUID().toString(),digest).getResponse().getStatus());assertTrue(design.catalogs(f.actor,f.project).stream().anyMatch(c->c.id().equals(old.id())));}
}
