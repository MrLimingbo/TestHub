package io.testhub.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.mock.*;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class P2D16MockFlowTests extends P1FlowTestSupport {
    @Autowired MockService mocks;
    @Autowired MockTransferService transfer;
    @Autowired MockSyncService sync;
    record F(PlatformUserPrincipal actor,String project,MockServer server) { }
    F fixture(){var a=actor();var p=project(a);var s=mocks.createServer(a,p,new MockService.ServerInput(unique()));return new F(a,p,s);}
    String base(F f){return "/api/v1/projects/"+f.project+"/mock/servers/"+f.server.id();}
    Map<String,Object> definition(){var d=new LinkedHashMap<String,Object>();d.put("name",unique());d.put("httpMethod","POST");d.put("path","/qa");d.put("priority",10);d.put("requestQuery",Map.of());d.put("requestHeaders",Map.of());d.put("requestBodyMatch","ANY");d.put("requestBody","");d.put("responseStatus",210);d.put("responseHeaders",Map.of("Content-Type","application/json; charset=utf-8"));d.put("responseBody","reply");return d;}
    String raw(String field,String token)throws Exception{var d=definition();d.put(field,"RAW_TOKEN");return json.writeValueAsString(d).replace("\"RAW_TOKEN\"",token);}
    MvcResult submit(F f,String suffix,String body)throws Exception{return mvc.perform(post(base(f)+suffix).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();}
    JsonNode data(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString()).path("data");}
    void status(int expected,MvcResult r)throws Exception{assertEquals(expected,r.getResponse().getStatus(),r.getResponse().getContentAsString());}
    Map<String,Long> counts(){var result=new LinkedHashMap<String,Long>();for(String t:List.of("mock_server","mock_rule","mock_share","mock_transfer_receipt","mock_sync_receipt","audit_log"))result.put(t,jdbc.sql("SELECT COUNT(*) FROM "+t).query(Long.class).single());return result;}
    void invalid(F f,String field,String token)throws Exception{var before=counts();status(400,field.equals("expiresInDays")?submit(f,"/shares","{\"name\":\"QA\",\"expiresInDays\":"+token+"}"):submit(f,"/rules",raw(field,token)));assertEquals(before,counts());}
    void invalidRange(F f,String field,String token)throws Exception{var before=counts();var r=field.equals("expiresInDays")?submit(f,"/shares","{\"name\":\"QA\",\"expiresInDays\":"+token+"}"):submit(f,"/rules",raw(field,token));status(400,r);assertEquals("REQUEST_REJECTED",json.readTree(r.getResponse().getContentAsString()).path("code").asText());assertEquals(before,counts());}
    MockRule rule(F f){return mocks.createRule(f.actor,f.project,f.server.id(),new MockService.RuleInput(unique(),"POST","/qa",10,Map.of(),Map.of(),"ANY","",210,Map.of(),"reply"));}
    void rejected(Runnable action){assertEquals(400,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}
    MockSyncService.Request request(F source,F target){var items=sync.snapshot(source.actor,source.project,source.actor.userId()).items();return new MockSyncService.Request(UUID.randomUUID().toString(),target.actor.userId(),sync.snapshot(target.actor,target.project,target.actor.userId()).revision(),items.stream().map(x->new MockSyncService.Operation(UUID.randomUUID().toString(),x)).toList());}

    List<String> invalidExchangePayloads(String original) {
        var variants = new java.util.ArrayList<String>();
        for (String field : List.of("priority", "responseStatus")) {
            String source = "\"" + field + "\":" + (field.equals("priority") ? "10" : "210");
            for (String token : List.of("1.5", "1.0000000000000001", "\"10\"", "true", "[]", "{}")) {
                String changed = original.replace(source, "\"" + field + "\":" + token);
                assertNotEquals(original, changed); variants.add(changed);
            }
        }
        for (String field : List.of("requestQuery", "requestHeaders", "responseHeaders")) {
            for (String token : List.of("1", "true", "[]", "{}")) {
                String changed = original.replaceAll("\"" + field + "\":\\{[^}]*\\}", "\"" + field + "\":{\"X-Code\":" + token + "}");
                assertNotEquals(original, changed); variants.add(changed);
            }
        }
        for (String field : List.of("requestBody", "responseBody")) {
            String source = "\"" + field + "\":\"" + (field.equals("requestBody") ? "" : "reply") + "\"";
            String changed = original.replace(source, "\"" + field + "\":true");
            assertNotEquals(original, changed); variants.add(changed);
        }
        return variants;
    }

    @Test @DisplayName("TC-D16MOCK-01") void priorityExact()throws Exception{var f=fixture();for(String v:List.of("10","10.0","1e1")){var r=submit(f,"/rules",raw("priority",v));status(200,r);assertEquals(10,data(r).path("priority").asInt());}}
    @Test @DisplayName("TC-D16MOCK-02") void priorityLower()throws Exception{var f=fixture();status(200,submit(f,"/rules",raw("priority","0")));invalidRange(f,"priority","-1");}
    @Test @DisplayName("TC-D16MOCK-03") void priorityUpper()throws Exception{var f=fixture();status(200,submit(f,"/rules",raw("priority","1000")));invalidRange(f,"priority","1001");}
    @Test @DisplayName("TC-D16MOCK-04") void priorityFractions()throws Exception{var f=fixture();for(String v:List.of("1.5","1.0000000000000001"))invalid(f,"priority",v);}
    @Test @DisplayName("TC-D16MOCK-05") void numericStrings()throws Exception{var f=fixture();for(String field:List.of("priority","expiresInDays","responseStatus"))for(String v:List.of(field.equals("responseStatus")?"\"200\"":"\"10\"","\" 10 \"","\"null\""))invalid(f,field,v);}
    @Test @DisplayName("TC-D16MOCK-06") void numericTypes()throws Exception{var f=fixture();for(String field:List.of("priority","expiresInDays","responseStatus"))for(String v:List.of("true","[]","{}"))invalid(f,field,v);}
    @Test @DisplayName("TC-D16MOCK-07") void overflowAndRawNonFinite()throws Exception{var f=fixture();for(String field:List.of("priority","expiresInDays","responseStatus"))for(String v:List.of("4294967296","18446744073709551616","1e10000","NaN","Infinity","-Infinity"))invalid(f,field,v);}
    @Test @DisplayName("TC-D16MOCK-08") void expiryExact()throws Exception{var f=fixture();for(String v:List.of("1","1.0","1e0")){var r=submit(f,"/shares","{\"name\":\"QA\",\"expiresInDays\":"+v+"}");status(200,r);assertFalse(data(r).path("expiresAt").isNull());}}
    @Test @DisplayName("TC-D16MOCK-09") void expiryUpper()throws Exception{var f=fixture();status(200,submit(f,"/shares","{\"name\":\"QA\",\"expiresInDays\":365}"));invalidRange(f,"expiresInDays","366");}
    @Test @DisplayName("TC-D16MOCK-10") void expiryInvalid()throws Exception{var f=fixture();for(String v:List.of("0","-1","1.5","1.0000000000000001"))invalid(f,"expiresInDays",v);}
    @Test @DisplayName("TC-D16MOCK-11") void expiryPermanent()throws Exception{var f=fixture();for(String body:List.of("{\"name\":\"QA\"}","{\"name\":\"QA\",\"expiresInDays\":null}")){var r=submit(f,"/shares",body);status(200,r);assertTrue(data(r).path("expiresAt").isNull());}}
    @Test @DisplayName("TC-D16MOCK-12") void statusExact()throws Exception{var f=fixture();for(String v:List.of("200","200.0","2e2")){var r=submit(f,"/rules",raw("responseStatus",v));status(200,r);assertEquals(200,data(r).path("responseStatus").asInt());}}
    @Test @DisplayName("TC-D16MOCK-13") void statusUpper()throws Exception{var f=fixture();status(200,submit(f,"/rules",raw("responseStatus","599")));}
    @Test @DisplayName("TC-D16MOCK-14") void statusFractions()throws Exception{var f=fixture();for(String v:List.of("200.5","200.0000000000000001"))invalid(f,"responseStatus",v);}
    @Test @DisplayName("TC-D16MOCK-15") void statusRange()throws Exception{var f=fixture();for(String v:List.of("199","600"))invalidRange(f,"responseStatus",v);}
    @Test @DisplayName("TC-D16MOCK-16") void ruleDefaults()throws Exception{var f=fixture();for(boolean missing:List.of(true,false)){var d=definition();if(missing){d.remove("priority");d.remove("responseStatus");}else{d.put("priority",null);d.put("responseStatus",null);}var r=submit(f,"/rules",json.writeValueAsString(d));status(200,r);assertEquals(0,data(r).path("priority").asInt());assertEquals(200,data(r).path("responseStatus").asInt());}}
    @Test @DisplayName("TC-D16MOCK-17") void queryStrings()throws Exception{var f=fixture();var d=definition();d.put("requestQuery",Map.of("id","001","flag","false","literal","null","blank",""));var r=submit(f,"/rules",json.writeValueAsString(d));status(200,r);assertEquals(json.valueToTree(d.get("requestQuery")),data(r).path("requestQuery"));}
    @Test @DisplayName("TC-D16MOCK-18") void headerStrings()throws Exception{var f=fixture();var d=definition();d.put("requestHeaders",Map.of("X-Code","001","X-Flag","false"));d.put("responseHeaders",Map.of("X-Code","001","X-Literal","null"));var r=submit(f,"/rules",json.writeValueAsString(d));status(200,r);assertEquals("001",data(r).path("requestHeaders").path("x-code").asText());assertEquals("null",data(r).path("responseHeaders").path("X-Literal").asText());}
    @Test @DisplayName("TC-D16MOCK-19") void bodyStrings()throws Exception{var f=fixture();var d=definition();d.put("requestBodyMatch","JSON_SUBSET");d.put("requestBody","{\"n\":1}");d.put("responseBody","001");var r=submit(f,"/rules",json.writeValueAsString(d));status(200,r);assertEquals("{\"n\":1}",data(r).path("requestBody").asText());assertEquals("001",data(r).path("responseBody").asText());}
    @Test @DisplayName("TC-D16MOCK-20") void mapTypes()throws Exception{var f=fixture();for(String field:List.of("requestQuery","requestHeaders","responseHeaders"))for(String v:List.of("1","true","[]","{}"))invalid(f,field,"{\"X-Code\":"+v+"}");}
    @Test @DisplayName("TC-D16MOCK-21") void bodyTypes()throws Exception{var f=fixture();for(String field:List.of("requestBody","responseBody"))for(String v:List.of("1","true","[]","{}"))invalid(f,field,v);}
    @Test @DisplayName("TC-D16MOCK-22") void nullTextPolicy()throws Exception{var f=fixture();var d=definition();d.put("requestQuery",Collections.singletonMap("id",null));d.put("requestHeaders",Collections.singletonMap("X-Null",null));d.put("responseHeaders",Collections.singletonMap("X-Null",null));d.put("requestBody",null);d.put("responseBody",null);var r=submit(f,"/rules",json.writeValueAsString(d));status(200,r);assertEquals("",data(r).path("requestQuery").path("id").asText());assertEquals("",data(r).path("requestHeaders").path("x-null").asText());assertEquals("",data(r).path("responseHeaders").path("X-Null").asText());assertEquals("",data(r).path("responseBody").asText());}
    @Test @DisplayName("TC-D16MOCK-23") void editValid()throws Exception{var f=fixture();var original=rule(f);mocks.setRuleEnabled(f.actor,f.project,f.server.id(),original.id(),false);var d=definition();d.put("name",original.name());d.put("priority",100);var r=mvc.perform(put(base(f)+"/rules/"+original.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(d))).andReturn();status(200,r);assertEquals(100,data(r).path("priority").asInt());assertFalse(data(r).path("enabled").asBoolean());}
    @Test @DisplayName("TC-D16MOCK-24") void editInvalid()throws Exception{var f=fixture();var original=rule(f);var before=counts();for(String token:List.of("1.5","1.0000000000000001")){var r=mvc.perform(put(base(f)+"/rules/"+original.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(raw("priority",token))).andReturn();status(400,r);assertEquals(before,counts());assertEquals(original,mocks.rules(f.actor,f.project,f.server.id()).getFirst());}}
    @Test @DisplayName("TC-D16MOCK-25") void runtimeMatching(){var f=fixture();mocks.setServerEnabled(f.actor,f.project,f.server.id(),true);mocks.createRule(f.actor,f.project,f.server.id(),new MockService.RuleInput(unique(),"POST","/qa",0,Map.of(),Map.of(),"ANY","",201,Map.of(),"low"));mocks.createRule(f.actor,f.project,f.server.id(),new MockService.RuleInput(unique(),"POST","/qa",1000,Map.of("code","001"),Map.of("X-Flag","false"),"EXACT","{\"v\":1}",202,Map.of(),"high"));var r=mocks.runtime(f.server.publicKey(),new MockRuntimeRequest("POST","/qa",Map.of("code",List.of("001")),Map.of("x-flag","false"),"{\"v\":1}")).orElseThrow();assertEquals(202,r.status());assertEquals("high",r.body());}
    @Test @DisplayName("TC-D16MOCK-26") void shareAtomic()throws Exception{var f=fixture();var before=counts();var r=submit(f,"/shares","{\"name\":\"QA\",\"expiresInDays\":1.5}");status(400,r);assertFalse(r.getResponse().getContentAsString().contains("shareKey"));assertEquals(before,counts());}
    @Test @DisplayName("TC-D16MOCK-27") void transferValid()throws Exception{var source=fixture();rule(source);mocks.createShare(source.actor,source.project,source.server.id(),new MockService.ShareInput("share",null));for(String token:List.of("10","10.0","1e1")){var target=fixture();String raw=json.writeValueAsString(new MockTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project))).replace("\"priority\":10","\"priority\":"+token).replace("\"responseStatus\":210", "\"responseStatus\":" + (token.equals("10") ? "210" : token.equals("10.0") ? "210.0" : "2.1e2"));var receipt=transfer.transfer(target.actor,target.project,transfer.decode(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(1,receipt.rules());var imported=mocks.servers(target.actor,target.project).stream().filter(s->s.name().equals(source.server.name())).findFirst().orElseThrow();assertFalse(imported.enabled());assertEquals(10,mocks.rules(target.actor,target.project,imported.id()).getFirst().priority());assertNotNull(mocks.shares(target.actor,target.project,imported.id()).getFirst().revokedAt());}}
    @Test @DisplayName("TC-D16MOCK-28") void transferInvalid()throws Exception {
        var source=fixture(); rule(source); var target=fixture();
        String original=json.writeValueAsString(new MockTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),transfer.export(source.actor,source.project)));
        for (String raw : invalidExchangePayloads(original)) {
            var before=counts(); rejected(()->transfer.transfer(target.actor,target.project,transfer.decode(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))); assertEquals(before,counts());
        }
    }
    @Test @DisplayName("TC-D16MOCK-29") void syncValid()throws Exception{var source=fixture();rule(source);for(String token:List.of("10","10.0","1e1")){var target=fixture();String raw=json.writeValueAsString(request(source,target)).replace("\"priority\":10","\"priority\":"+token).replace("\"responseStatus\":210", "\"responseStatus\":" + (token.equals("10") ? "210" : token.equals("10.0") ? "210.0" : "2.1e2"));assertEquals(2,sync.apply(target.actor,target.project,sync.decode(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))).results().size());}}
    @Test @DisplayName("TC-D16MOCK-30") void syncInvalid()throws Exception {
        var source=fixture(); rule(source); var target=fixture(); String original=json.writeValueAsString(request(source,target));
        for (String raw : invalidExchangePayloads(original)) {
            var before=counts(); rejected(()->sync.apply(target.actor,target.project,sync.decode(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))); assertEquals(before,counts());
        }
    }
    @Test @DisplayName("TC-D16MOCK-31") void oldReadOnly(){var f=fixture();var r=rule(f);var share=mocks.createShare(f.actor,f.project,f.server.id(),new MockService.ShareInput("history",null));var before=counts();assertEquals(r,mocks.rules(f.actor,f.project,f.server.id()).getFirst());assertEquals(share,mocks.shares(f.actor,f.project,f.server.id()).getFirst());transfer.export(f.actor,f.project);sync.snapshot(f.actor,f.project,f.actor.userId());assertEquals(before,counts());}
    @Test @DisplayName("TC-D16MOCK-32") void permission()throws Exception{var f=fixture();var other=actor();var before=counts();var r=mvc.perform(post(base(f)+"/rules").with(user(other)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(raw("priority","10"))).andReturn();status(403,r);assertEquals(before,counts());}
    @Test @DisplayName("TC-D16MOCK-33") void revisionConflict(){var source=fixture();rule(source);var target=fixture();var req=request(source,target);rule(target);var before=counts();assertEquals(409,assertThrows(ResponseStatusException.class,()->sync.apply(target.actor,target.project,req)).getStatusCode().value());assertEquals(before,counts());}
    @Test @DisplayName("TC-D16MOCK-34") void receipts(){var source=fixture();rule(source);var target=fixture();var req=request(source,target);var first=sync.apply(target.actor,target.project,req);var before=counts();assertEquals(first,sync.apply(target.actor,target.project,req));assertEquals(before,counts());var other=fixture();var input=new MockTransferService.ImportRequest(UUID.randomUUID().toString(),other.actor.userId(),transfer.export(source.actor,source.project));var receipt=transfer.transfer(other.actor,other.project,input);before=counts();assertEquals(receipt,transfer.transfer(other.actor,other.project,input));assertEquals(before,counts());}
    @Test @DisplayName("TC-D16MOCK-39") void errorLocationAndPrivacy()throws Exception{var f=fixture();var r=submit(f,"/rules",raw("priority","\"D16_SYNTHETIC_PRIVATE_VALUE\""));status(400,r);var body=json.readTree(r.getResponse().getContentAsString());assertTrue(body.path("details").has("priority"));assertFalse(body.toString().contains("D16_SYNTHETIC_PRIVATE_VALUE"));r=submit(f,"/rules",raw("requestBody","{\"token\":\"D16_SYNTHETIC_BODY_VALUE\"}"));status(400,r);body=json.readTree(r.getResponse().getContentAsString());assertTrue(body.path("details").has("requestBody"));assertFalse(body.toString().contains("D16_SYNTHETIC_BODY_VALUE"));}
}
