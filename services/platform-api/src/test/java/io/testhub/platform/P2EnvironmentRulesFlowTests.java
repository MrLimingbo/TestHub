package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.parameter.*;
import io.testhub.platform.apiautomation.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class P2EnvironmentRulesFlowTests extends P1FlowTestSupport {
    @Autowired ProjectParameterService parameters;
    @Autowired EnvironmentSecretService secrets;
    @Autowired ProjectEnvironmentService environments;
    @Autowired ApiAutomationScenarioService scenarios;
    @Autowired ApiAutomationExecutionService executions;
    String env(PlatformUserPrincipal a) { return environments.list(a, project(a)).getFirst().environmentCode(); }
    ProjectEnvironment add(PlatformUserPrincipal a, String code, boolean enabled) {
        return environments.create(a, project(a), new ProjectEnvironmentService.CreateInput("P2 " + code, code, "", "", enabled, false));
    }
    void reject(int code, Runnable run) { assertEquals(code, assertThrows(ResponseStatusException.class, run::run).getStatusCode().value()); }
    long count(String p) { return jdbc.sql("SELECT COUNT(*) FROM project_parameter WHERE project_id=:p").param("p", p).query(Long.class).single(); }
    void seedLegacy(PlatformUserPrincipal a, boolean secret) {
        jdbc.sql("""
                INSERT INTO project_parameter(id,organization_id,project_id,environment_code,parameter_key,parameter_value,secret,updated_by)
                VALUES(:id,:o,:p,'orphan',:k,:v,:s,:u)
                """).param("id", com.github.f4b6a3.ulid.UlidCreator.getUlid().toString()).param("o", access.context(a).organizations().getFirst().id())
                .param("p", project(a)).param("k", secret ? "LEGACY_SECRET" : "LEGACY").param("v", secret ? "legacy-sealed-fixture" : "preserved").param("s", secret).param("u", a.userId()).update();
    }
    @Test @DisplayName("[TC-P2-D01-01][POS] defined environments accept ordinary and secret updates")
    void definedWrites() { var a = actor(); String p = project(a), e = env(a); parameters.upsert(a,p,e,"NORMAL","one"); parameters.upsert(a,p,e,"NORMAL","two"); secrets.upsert(a,p,e,"SECRET",password()); assertEquals("two",parameters.list(a,p,e).parameters().getFirst().value()); assertEquals(1,secrets.list(a,p,e).secrets().size()); }
    @Test @DisplayName("[TC-P2-D01-02][POS] existing environment normalization is retained")
    void normalization() { var a=actor();add(a,"p2-qa",true);parameters.upsert(a,project(a)," P2-QA "," KEY ","one");assertEquals("KEY",parameters.list(a,project(a),"p2-qa").parameters().getFirst().key()); }
    @Test @DisplayName("[TC-P2-D01-03][NEG] undefined ordinary writes cannot create a row or success audit")
    void undefinedOrdinary() {var a=actor();String p=project(a);long rows=count(p),audit=auditCount();reject(400,()->parameters.upsert(a,p,"missing","KEY","no"));assertEquals(rows,count(p));assertEquals(audit,auditCount());}
    @Test @DisplayName("[TC-P2-D01-04][NEG] undefined secret writes cannot create ciphertext")
    void undefinedSecret() {var a=actor();String p=project(a);long rows=count(p),audit=auditCount();reject(400,()->secrets.upsert(a,p,"missing","KEY",password()));assertEquals(rows,count(p));assertEquals(audit,auditCount());}
    @Test @DisplayName("[TC-P2-D01-05][NEG] another project cannot supply the missing definition")
    void projectScope() {var a=actor();var b=actor();add(b,"only-other",true);reject(400,()->parameters.upsert(a,project(a),"only-other","KEY","no"));assertThrows(AccessDeniedException.class,()->parameters.upsert(b,project(a),env(a),"KEY","no"));}
    @Test @DisplayName("[TC-P2-D01-06][POS] legacy orphan parameters and secret summaries remain readable")
    void legacyRead() {var a=actor();seedLegacy(a,false);seedLegacy(a,true);assertEquals("preserved",parameters.list(a,project(a),"orphan").parameters().getFirst().value());assertEquals("LEGACY_SECRET",secrets.list(a,project(a),"orphan").secrets().getFirst().key());assertFalse(json.valueToTree(secrets.list(a,project(a),"orphan")).toString().contains("legacy-sealed-fixture"));}
    @Test @DisplayName("[TC-P2-D01-07][NEG] orphan updates fail without changing existing values or metadata")
    void legacyWrite() {var a=actor();seedLegacy(a,false);var before=parameters.list(a,project(a),"orphan");reject(400,()->parameters.upsert(a,project(a),"orphan","LEGACY","changed"));assertEquals(before,parameters.list(a,project(a),"orphan"));}
    @Test @DisplayName("[TC-P2-D01-08][POS] disabled but defined configuration remains maintainable")
    void disabledDefined() {var a=actor();add(a,"p2-off",false);parameters.upsert(a,project(a),"p2-off","KEY","kept");secrets.upsert(a,project(a),"p2-off","SECRET",password());assertEquals("kept",parameters.list(a,project(a),"p2-off").parameters().getFirst().value());}
    @Test @DisplayName("[TC-P2-D01-09][NEG] concurrent environment deletion and writes leave no new orphan")
    void deleteRace() throws Exception {var a=actor();var e=add(a,"p2-race",true);var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var write=pool.submit(()->{gate.await();try{parameters.upsert(a,project(a),e.environmentCode(),"KEY","value");}catch(ResponseStatusException error){assertEquals(400,error.getStatusCode().value());}return null;});var deletion=pool.submit(()->{gate.await();environments.delete(a,project(a),e.id());return null;});gate.countDown();write.get(10,TimeUnit.SECONDS);deletion.get(10,TimeUnit.SECONDS);}assertTrue(parameters.list(a,project(a),e.environmentCode()).parameters().isEmpty());}
    @Test @DisplayName("[TC-P2-D01-10][NEG] legacy callers reject missing definitions while established guards remain")
    void compatibility() {var a=actor();String p=project(a),e=env(a);reject(400,()->parameters.upsert(a,p,"missing","KEY","value"));add(a,"missing",true);parameters.upsert(a,p,"missing","KEY","value");secrets.upsert(a,p,e,"SEALED",password());reject(409,()->parameters.upsert(a,p,e,"SEALED","no"));reject(400,()->parameters.upsert(a,p,e,"BASE_URL","no"));}
    @Test @DisplayName("[TC-P2-D03-CFG-01][POS] enabled environment configuration lifecycle stays available")
    void enabledLifecycle() {var a=actor();String p=project(a),e=env(a);parameters.upsert(a,p,e,"KEY","one");parameters.upsert(a,p,e,"KEY","two");var row=parameters.list(a,p,e).parameters().getFirst();parameters.delete(a,p,row.id());assertTrue(parameters.list(a,p,e).parameters().isEmpty());}
    @Test @DisplayName("[TC-P2-D03-CFG-02][POS] disabled environment has the same authorized maintenance API")
    void disabledLifecycle() {var a=actor();var e=add(a,"p2-maintain",false);parameters.upsert(a,project(a),e.environmentCode(),"KEY","one");parameters.upsert(a,project(a),e.environmentCode(),"KEY","two");var row=parameters.list(a,project(a),e.environmentCode()).parameters().getFirst();parameters.delete(a,project(a),row.id());assertTrue(parameters.list(a,project(a),e.environmentCode()).parameters().isEmpty());}
    @Test @DisplayName("[TC-P2-D03-CFG-03][POS] toggling enabled does not erase a committed configuration")
    void toggle() {var a=actor();var e=add(a,"p2-toggle",true);parameters.upsert(a,project(a),e.environmentCode(),"KEY","one");environments.update(a,project(a),e.id(),new ProjectEnvironmentService.UpdateInput(e.name(),"","",false,e.version()));parameters.upsert(a,project(a),e.environmentCode(),"KEY","two");assertEquals("two",parameters.list(a,project(a),e.environmentCode()).parameters().getFirst().value());}
    @Test @DisplayName("[TC-P2-D03-CFG-04][NEG] disabled maintenance grants no new cross-project privileges")
    void disabledPermissions() {var a=actor();var b=actor();add(a,"p2-private",false);assertThrows(AccessDeniedException.class,()->secrets.upsert(b,project(a),"p2-private","KEY",password()));reject(400,()->secrets.upsert(a,project(a),"p2-private","BASE_URL",password()));}
    @Test @DisplayName("[TC-P2-D03-CFG-05][NEG] maintaining disabled configuration cannot queue an execution")
    void executionStillDisabled() {var a=actor();add(a,"p2-no-run",false);parameters.upsert(a,project(a),"p2-no-run","KEY","value");var scene=scenarios.create(a,project(a),new ApiAutomationScenarioService.ScenarioInput("P2", "", "p2-no-run", "STOP", List.of(new AutomationStep("wait","WAIT","等待",Map.of("delayMillis",1))), Map.of(), "", "", null));reject(409,()->executions.create(a,project(a),scene.id(),30,null,Map.of()));}
    @Test @DisplayName("[TC-P2-D03-CFG-06][NEG] missing configuration environments cannot masquerade as disabled ones")
    void missingStillRejected() {var a=actor();reject(400,()->parameters.upsert(a,project(a),"p2-absent","KEY","no"));}
}
