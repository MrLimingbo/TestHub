package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.reporting.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.CronTrigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class P2D16QualityScheduleTests extends P1FlowTestSupport {
    @Autowired QualityReportScheduleService schedules;
    @Autowired QualityReportTransferService transfer;
    @Autowired QualityReportSyncService sync;
    @Autowired Scheduler quartz;
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() { var actor=actor(); return new Fixture(actor,project(actor)); }
    String payload(Fixture f,String value) throws Exception {
        var map=new LinkedHashMap<String,Object>();
        map.put("name",unique());map.put("projectIds",List.of(f.project()));map.put("cronExpression","0 0 0 1 1 ? 2099");
        map.put("timezone","UTC");map.put("periodDays","RAW");map.put("enabled",true);
        return json.writeValueAsString(map).replace("\"RAW\"",value);
    }
    long count(String table) { return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single(); }
    Map<String,Long> counts() {
        var m=new LinkedHashMap<String,Long>();
        for(String t:List.of("quality_report_schedule","quality_report_sync_receipt","quality_report_transfer_receipt","audit_log"))m.put(t,count(t));
        return m;
    }
    String create(Fixture f,String token) throws Exception {
        var response=mvc.perform(post("/api/v1/reports/quality/schedules").with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(f,token))).andReturn().getResponse();
        assertEquals(200,response.getStatus(),response.getContentAsString()); return json.readTree(response.getContentAsString()).path("data").path("id").asText();
    }
    @Test void exactPeriodBoundsAndNumericForms() throws Exception {
        var f=fixture();
        for(String token:List.of("1","90","10","10.0","1e1")) {
            String id=create(f,token);var row=schedules.list(f.actor()).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
            assertEquals(new java.math.BigDecimal(token).intValueExact(),row.periodDays());assertTrue(quartz.checkExists(new JobKey(id,"testhub-quality-report")));
        }
    }
    @Test void invalidCreateAndUpdateDoNotChangePlanVersionQuartzOrAudit() throws Exception {
        var f=fixture();String id=create(f,"7");var row=schedules.list(f.actor()).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
        var before=counts();var job=new JobKey(id,"testhub-quality-report");var triggerKey=new TriggerKey(id,"testhub-quality-report");assertTrue(quartz.checkExists(job));
        var original=(CronTrigger)quartz.getTrigger(triggerKey);assertNotNull(original);String cron=original.getCronExpression(), zone=original.getTimeZone().getID();
        for(String token:List.of("0","91","-1","1.5","10.0000000000000001","1e10000","2147483648","\"10\"","true","[]","{}","null")) {
            var response=mvc.perform(post("/api/v1/reports/quality/schedules").with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(f,token))).andReturn().getResponse();
            assertEquals(400,response.getStatus(),token+" "+response.getContentAsString());assertEquals(before,counts());
            String body=payload(f,token);body=body.substring(0,body.length()-1)+",\"expectedVersion\":"+row.version()+"}";
            response=mvc.perform(put("/api/v1/reports/quality/schedules/"+id).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertEquals(400,response.getStatus(),token+" "+response.getContentAsString());assertEquals(before,counts());
            assertEquals(row.version(),schedules.list(f.actor()).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow().version());assertTrue(quartz.checkExists(job));
            var trigger=(CronTrigger)quartz.getTrigger(triggerKey);assertNotNull(trigger);assertEquals(cron,trigger.getCronExpression());assertEquals(zone,trigger.getTimeZone().getID());
        }
        String missing=payload(f,"7").replace(",\"periodDays\":7","");
        assertEquals(400,mvc.perform(post("/api/v1/reports/quality/schedules").with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(missing)).andReturn().getResponse().getStatus());assertEquals(before,counts());
    }
    @Test void validEditAcceptsExactNumericFormsAndIncrementsVersion() throws Exception {
        var f=fixture();String id=create(f,"7");
        for(String token:List.of("10.0","1e1","1","90")) {
            var before=schedules.list(f.actor()).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
            String body=payload(f,token);body=body.substring(0,body.length()-1)+",\"expectedVersion\":"+before.version()+"}";
            var response=mvc.perform(put("/api/v1/reports/quality/schedules/"+id).with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());
            var after=schedules.list(f.actor()).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();
            assertEquals(new java.math.BigDecimal(token).intValueExact(),after.periodDays());assertEquals(before.version()+1,after.version());
            assertTrue(quartz.checkExists(new JobKey(id,"testhub-quality-report")));
        }
    }
    @Test void controllerErrorLocatesFieldWithoutEchoingValue() throws Exception {
        var f=fixture();var response=mvc.perform(post("/api/v1/reports/quality/schedules").with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(f,"{\"PRIVATE_MARKER\":true}"))).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertTrue(response.getContentAsString().contains("periodDays"));assertFalse(response.getContentAsString().contains("PRIVATE_MARKER"));
    }
    @Test void transferHttpUsesExactPeriodAndRejectsWithoutWrites() throws Exception {
        var source=fixture();create(source,"10");
        for(String token:List.of("10","10.0","1e1")) {
            var target=fixture();var request=new QualityReportTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor().userId(),transfer.export(source.actor(),source.project()));
            String body=json.writeValueAsString(request).replace("\"periodDays\":10","\"periodDays\":"+token);
            var response=mvc.perform(post("/api/v1/projects/"+target.project()+"/quality-report/transfer").with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals(10,schedules.list(target.actor()).getFirst().periodDays());
        }
        var target=fixture();var request=new QualityReportTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor().userId(),transfer.export(source.actor(),source.project()));
        String raw=json.writeValueAsString(request);var before=counts();
        for(String token:List.of("10.0000000000000001","1.5","0","91","NaN","Infinity","-Infinity","\"10\"","true","[]","{}","null","MISSING")) {
            String body=token.equals("MISSING")?raw.replace(",\"periodDays\":10",""):raw.replace("\"periodDays\":10","\"periodDays\":"+token);assertNotEquals(raw,body);
            var response=mvc.perform(post("/api/v1/projects/"+target.project()+"/quality-report/transfer").with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            assertEquals(400,response.getStatus(),token+" "+response.getContentAsString());assertEquals(before,counts());
        }
    }
    @Test void syncHttpUsesExactPeriodAndRejectsWithoutWrites() throws Exception {
        for(String token:List.of("10","10.0","1e1","10.0000000000000001","1.5","0","91","NaN","Infinity","-Infinity","\"10\"","true","[]","{}","null","MISSING")) {
            var f=fixture();var snapshot=sync.snapshot(f.actor(),f.project(),f.actor().userId());
            var item=new QualityReportSyncService.Item("SCHEDULE",unique(),new QualityReportSyncService.Definition(List.of(f.project()),"0 0 0 1 1 ? 2099","UTC",10));
            var request=new QualityReportSyncService.Request(UUID.randomUUID().toString(),f.actor().userId(),snapshot.revision(),List.of(new QualityReportSyncService.Operation(UUID.randomUUID().toString(),item)));
            String raw=json.writeValueAsString(request);assertTrue(raw.contains("\"periodDays\":10"));String body=token.equals("MISSING")?raw.replace(",\"periodDays\":10",""):raw.replace("\"periodDays\":10","\"periodDays\":"+token);if(!token.equals("10"))assertNotEquals(raw,body);
            var before=counts();var response=mvc.perform(post("/api/v1/projects/"+f.project()+"/quality-report/sync").with(user(f.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
            if(List.of("10","10.0","1e1").contains(token)) { assertEquals(200,response.getStatus(),token+" "+response.getContentAsString()); assertEquals(10,schedules.list(f.actor()).getFirst().periodDays()); }
            else { assertEquals(400,response.getStatus(),token+" "+response.getContentAsString());assertEquals(before,counts()); }
        }
    }
    @Test void exchangeBatchWithLaterInvalidPeriodLeavesNoPartialWrites() throws Exception {
        var source=fixture();create(source,"10");create(source,"7");var target=fixture();
        var request=new QualityReportTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor().userId(),transfer.export(source.actor(),source.project()));
        String raw=json.writeValueAsString(request);assertTrue(raw.contains("\"periodDays\":7"));String body=raw.replace("\"periodDays\":7","\"periodDays\":7.0000000000000001");var before=counts();
        var response=mvc.perform(post("/api/v1/projects/"+target.project()+"/quality-report/transfer").with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertEquals(before,counts());
        var snapshot=sync.snapshot(target.actor(),target.project(),target.actor().userId());
        var first=new QualityReportSyncService.Item("SCHEDULE",unique(),new QualityReportSyncService.Definition(List.of(target.project()),"0 0 0 1 1 ? 2099","UTC",10));
        var second=new QualityReportSyncService.Item("SCHEDULE",unique(),new QualityReportSyncService.Definition(List.of(target.project()),"0 0 0 1 1 ? 2099","UTC",7));
        var syncRequest=new QualityReportSyncService.Request(UUID.randomUUID().toString(),target.actor().userId(),snapshot.revision(),List.of(new QualityReportSyncService.Operation(UUID.randomUUID().toString(),first),new QualityReportSyncService.Operation(UUID.randomUUID().toString(),second)));
        raw=json.writeValueAsString(syncRequest);assertTrue(raw.contains("\"periodDays\":7"));body=raw.replace("\"periodDays\":7","\"periodDays\":7.0000000000000001");
        response=mvc.perform(post("/api/v1/projects/"+target.project()+"/quality-report/sync").with(user(target.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertEquals(before,counts());
    }
}
