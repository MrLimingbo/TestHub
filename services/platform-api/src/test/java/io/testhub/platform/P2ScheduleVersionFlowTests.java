package io.testhub.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.reporting.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class P2ScheduleVersionFlowTests extends P1FlowTestSupport {
    @Autowired QualityReportScheduleService schedules;
    @Autowired QualityReportScheduleRepository repository;
    JsonNode create(PlatformUserPrincipal a,boolean enabled) throws Exception {
        return data(mvc.perform(post("/api/v1/reports/quality/schedules").with(user(a)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(a,enabled)))).andReturn());
    }
    Map<String,Object> body(PlatformUserPrincipal a,boolean enabled) {return new LinkedHashMap<>(Map.of("name",unique(),"projectIds",List.of(project(a)),"cronExpression","0 0 0 1 1 ? 2099","timezone","UTC","periodDays",7,"enabled",enabled));}
    JsonNode data(MvcResult result) throws Exception {assertEquals(200,result.getResponse().getStatus());return json.readTree(result.getResponse().getContentAsString()).path("data");}
    MvcResult update(PlatformUserPrincipal a,String id,Map<String,Object> payload) throws Exception {return mvc.perform(put("/api/v1/reports/quality/schedules/"+id).with(user(a)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload))).andReturn();}
    JsonNode row(String id){return json.valueToTree(repository.findById(id).orElseThrow());}
    Map<String,Object> edit(PlatformUserPrincipal a,Object version){var b=body(a,false);b.put("expectedVersion",version);return b;}
    @Test @DisplayName("TC-P2-D22-01") void initialZeroAndExistingVersionsAreVisible() throws Exception {var a=actor();var s=create(a,false);assertEquals(0,s.path("version").asLong(-1));jdbc.sql("UPDATE quality_report_schedule SET config_version=8 WHERE id=:id").param("id",s.path("id").asText()).update();assertEquals(8,row(s.path("id").asText()).path("version").asLong(-1));}
    @Test @DisplayName("TC-P2-D22-02") void matchingVersionIncrementsExactlyOnce() throws Exception {var a=actor();var s=create(a,false);String id=s.path("id").asText();assertEquals(1,data(update(a,id,edit(a,0))).path("version").asLong(-1));assertEquals(2,data(update(a,id,edit(a,1))).path("version").asLong(-1));}
    @Test @DisplayName("TC-P2-D22-03") void staleEditorHasNoSideEffects() throws Exception {var a=actor();String id=create(a,false).path("id").asText();data(update(a,id,edit(a,0)));var before=row(id);long audit=auditCount();assertEquals(409,update(a,id,edit(a,0)).getResponse().getStatus());assertEquals(before,row(id));assertEquals(audit,auditCount());}
    @Test @DisplayName("TC-P2-D22-04") void concurrentEditorsProduceOneSuccess() throws Exception {var a=actor();String id=create(a,false).path("id").asText();try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var left=pool.submit(()->{start.await();return update(a,id,edit(a,0)).getResponse().getStatus();});var right=pool.submit(()->{start.await();return update(a,id,edit(a,0)).getResponse().getStatus();});start.countDown();assertEquals(Set.of(200,409),Set.of(left.get(15,TimeUnit.SECONDS),right.get(15,TimeUnit.SECONDS)));}assertEquals(1,row(id).path("version").asLong(-1));}
    @Test @DisplayName("TC-P2-D22-05") void missingOrNullPreconditionReturns428() throws Exception {var a=actor();String id=create(a,false).path("id").asText();assertEquals(428,update(a,id,body(a,false)).getResponse().getStatus());assertEquals(428,update(a,id,edit(a,null)).getResponse().getStatus());assertEquals(0,row(id).path("version").asLong(-1));}
    @Test @DisplayName("TC-P2-D22-06") void invalidTypeRangeAndFractionReturn400() throws Exception {var a=actor();String id=create(a,false).path("id").asText();for(Object value:List.of(-1,0.0,1.5,"0",true,Map.of(),List.of(),"9223372036854775808"))assertEquals(400,update(a,id,edit(a,value)).getResponse().getStatus(),"invalid version type");}
    @Test @DisplayName("TC-P2-D22-09") void runMetadataDoesNotChangeConfigVersion() throws Exception {var a=actor();String id=create(a,true).path("id").asText();data(mvc.perform(post("/api/v1/reports/quality/schedules/"+id+"/run").with(user(a)).with(csrf())).andReturn());assertNotNull(repository.findById(id).orElseThrow().lastRunId());assertEquals(0,row(id).path("version").asLong(-1));assertEquals(1,data(update(a,id,edit(a,0))).path("version").asLong(-1));}
    @Test @DisplayName("TC-P2-D22-10") void unauthorizedRequestCannotEditConfiguration() throws Exception {var a=actor();String id=create(a,false).path("id").asText();var other=actor();int status=update(other,id,edit(other,0)).getResponse().getStatus();assertTrue(status==403||status==404);assertEquals(0,row(id).path("version").asLong(-1));}
    @Test @DisplayName("TC-P2-D22-11") void failedPersistenceDoesNotBumpVersionOrEraseHistory() throws Exception {var a=actor();String id=create(a,true).path("id").asText();schedules.runNow(a,id);var duplicate=create(a,false);var before=row(id);long audit=auditCount();var payload=edit(a,0);payload.put("name",duplicate.path("name").asText());assertEquals(409,update(a,id,payload).getResponse().getStatus());assertEquals(before,row(id));assertEquals(audit,auditCount());assertEquals(1,schedules.runs(a,id).size());}
}
