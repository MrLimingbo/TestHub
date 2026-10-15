package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.reporting.QualityReportScheduleService;
import io.testhub.platform.reporting.QualityReportScheduleRepository;
import org.quartz.Scheduler;
import org.quartz.JobKey;
import io.testhub.platform.reporting.QualityReportTransferService;
import io.testhub.platform.reporting.QualityReportTransferService.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class QualityReportTransferFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if(FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(registry,directory,"report-transfer-fixture");
        else {
            registry.add("testhub.desktop.data-directory",directory::toString);
            registry.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username",()->"sa"); registry.add("spring.datasource.password",()->"report-transfer-fixture");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired QualityReportScheduleService schedules;
    @Autowired QualityReportScheduleRepository repository;
    @Autowired QualityReportTransferService transfer;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Scheduler quartz;
    record Fixture(PlatformUserPrincipal actor,String project,String org) { }
    Fixture fixture() {
        String account="report-transfer-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","报告迁移测试","Transfer-fixture-2026!"));
        var context=access.context(actor); String org=context.organizations().getFirst().id();
        return new Fixture(actor,context.projects().get(org).getFirst().id(),org);
    }
    QualityReportScheduleService.ScheduleInput input(String name,List<String> projects,boolean enabled) {
        return new QualityReportScheduleService.ScheduleInput(name,projects,"0 0 0 1 1 ? 2099","UTC",7,enabled);
    }
    Snapshot source(Fixture f,boolean multiple) {
        var projects=multiple?List.of(access.createProject(f.actor(),"关联项目","QA_LINK","").id(),f.project()):List.of(f.project());
        schedules.create(f.actor(),input("周报",projects,true)); return transfer.export(f.actor(),f.project());
    }
    ImportRequest request(Fixture f,Snapshot snapshot) { return new ImportRequest(UUID.randomUUID().toString(),f.actor().userId(),snapshot); }
    @Test void copiesOrderedProjectScopeAndRulesDisabledWithoutRunsJobsOrNotifications() throws Exception {
        var local=fixture();var target=fixture();var snapshot=source(local,true);
        String related=access.createProject(target.actor(),"目标关联项目","QA_LINK","").id();
        var receipt=transfer.transfer(target.actor(),target.project(),request(target,snapshot));
        assertEquals(1,receipt.schedules());assertEquals(2,receipt.projects());
        var result=schedules.list(target.actor()).getFirst();
        assertNotEquals(snapshot.schedules().getFirst().id(),result.id());assertEquals(List.of(related,target.project()),result.projectIds());
        assertEquals("UTC",result.timezone());assertEquals(7,result.periodDays());assertFalse(result.enabled());assertNull(result.lastRunId());
        assertEquals(target.actor().userId(),result.createdBy());assertFalse(quartz.checkExists(new JobKey(result.id(),"testhub-quality-report")));
        assertTrue(schedules.runs(target.actor(),result.id()).isEmpty());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE organization_id=:org").param("org",target.org()).query(Integer.class).single());
        assertThrows(ResponseStatusException.class,()->schedules.runNow(target.actor(),result.id()));
        assertEquals(snapshot,transfer.export(local.actor(),local.project()));
    }
    @Test void missingRelatedProjectFailsRatherThanNarrowingOrGuessingNames() {
        var local=fixture();var target=fixture();var snapshot=source(local,true);
        access.createProject(target.actor(),"关联项目","DIFFERENT_CODE","");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot))).getStatusCode().value());
        assertTrue(schedules.list(target.actor()).isEmpty());
    }
    @Test void replayIsIdempotentAndNamesConflictWithoutOverwrite() {
        var local=fixture();var target=fixture();var snapshot=source(local,false);var request=request(target,snapshot);
        var receipt=transfer.transfer(target.actor(),target.project(),request);
        assertEquals(receipt,transfer.transfer(target.actor(),target.project(),request));
        assertEquals(1,schedules.list(target.actor()).size());
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot)));
        var changed=new Snapshot(1,snapshot.sourceProjectId(),snapshot.projects(),List.of());
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),new ImportRequest(request.requestKey(),target.actor().userId(),changed)));
    }
    @Test void lateInvalidRuleRollsBackEntireImportAndReceipt() {
        var local=fixture();var target=fixture();var original=source(local,false);var first=original.schedules().getFirst();
        var bad=new Schedule(UlidCreator.getUlid().toString(),"无效报告",first.projectIds(),"invalid","UTC",7);
        var snapshot=new Snapshot(1,original.sourceProjectId(),original.projects(),List.of(first,bad));
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,snapshot)));
        assertTrue(schedules.list(target.actor()).isEmpty());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM quality_report_transfer_receipt WHERE project_id=:id").param("id",target.project()).query(Integer.class).single());
    }
    @Test void strictPayloadReferenceAndActorValidation() throws Exception {
        var local=fixture();var target=fixture();var original=source(local,false);var request=request(target,original);
        byte[] valid=json.writeValueAsBytes(request);assertEquals(request,transfer.decode(valid));
        String unknown=json.writeValueAsString(request).replace("\"snapshot\":{","\"snapshot\":{\"history\":[],");
        assertThrows(ResponseStatusException.class,()->transfer.decode(unknown.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(ResponseStatusException.class,()->transfer.decode((new String(valid,java.nio.charset.StandardCharsets.UTF_8)+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),new ImportRequest(request.requestKey(),local.actor().userId(),original)));
        var first=original.schedules().getFirst();var bad=new Schedule(first.id(),first.name(),List.of(UlidCreator.getUlid().toString()),first.cronExpression(),first.timezone(),first.periodDays());
        assertThrows(ResponseStatusException.class,()->transfer.transfer(target.actor(),target.project(),request(target,new Snapshot(1,original.sourceProjectId(),original.projects(),List.of(bad)))));
    }
    @Test void projectAndReadOnlyRolesEnforcedAtHttpBoundary() throws Exception {
        var local=fixture();var target=fixture();var snapshot=source(local,false);
        mvc.perform(get("/api/v1/projects/{id}/quality-report/transfer",local.project()).with(user(target.actor()))).andExpect(status().isForbidden());
        jdbc.sql("UPDATE membership SET role_code='VIEWER' WHERE user_id=:id").param("id",local.actor().userId()).update();
        mvc.perform(get("/api/v1/projects/{id}/quality-report/transfer",local.project()).with(user(local.actor()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{id}/quality-report/transfer",local.project()).with(user(local.actor())).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request(local,snapshot))))
                .andExpect(status().isForbidden());
    }
}
