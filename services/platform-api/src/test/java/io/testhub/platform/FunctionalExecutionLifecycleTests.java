package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.*;
import io.testhub.platform.functional.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class) @DirtiesContext
class FunctionalExecutionLifecycleTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry,directory,"execution-fixture");
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalTestExecutionService executions;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    record Fixture(PlatformUserPrincipal actor,String project,String run,List<FunctionalTestRunCase> cases) {}
    Fixture fixture() {
        String account="execution-"+UUID.randomUUID();
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","执行回归","Execution-fixture-2026!"));
        var context=access.context(actor);String project=context.projects().get(context.organizations().getFirst().id()).getFirst().id();
        var catalog=assets.createCatalog(actor,project,new FunctionalAssetService.CatalogInput("生命周期",null,0,"CASES"));
        List<String> ids=new ArrayList<>();
        for(int i=0;i<2;i++)ids.add(assets.createCase(actor,project,new FunctionalAssetService.CaseInput(catalog.id(),null,"LIFE_"+i,"生命周期"+i,"P1","READY","FUNCTIONAL","","打开页面","页面正常")).id());
        var plan=executions.createPlan(actor,project,new FunctionalTestExecutionService.PlanInput(null,"LIFE_PLAN","生命周期计划","READY","",ids));
        var run=executions.createRun(actor,project,plan.id(),"生命周期批次");
        return new Fixture(actor,project,run.id(),executions.listRunCases(actor,project,run.id()));
    }
    void result(Fixture f,int index,String status,String note){executions.updateRunCase(f.actor(),f.project(),f.cases().get(index).id(),new FunctionalTestExecutionService.RunCaseInput(status,note));}
    FunctionalTestRun run(Fixture f){return executions.getRun(f.actor(),f.project(),f.run());}
    @Test void preservesFirstStartAndCompletionTimeAcrossEditsAndReopening() {
        var f=fixture();assertEquals("NOT_STARTED",run(f).status());assertNull(run(f).startedAt());assertNull(run(f).finishedAt());
        result(f,0,"BLOCKED","首次执行");assertEquals("RUNNING",run(f).status());assertNotNull(run(f).startedAt());
        Instant first=Instant.parse("2026-01-01T00:00:00Z");
        jdbc.sql("UPDATE functional_test_run SET started_at=:time WHERE id=:id").param("time",java.sql.Timestamp.from(first)).param("id",f.run()).update();
        result(f,0,"BLOCKED","仅改备注");assertEquals(first,run(f).startedAt());
        result(f,1,"FAILED","完成");assertEquals("COMPLETED",run(f).status());assertEquals(first,run(f).startedAt());assertNotNull(run(f).finishedAt());
        Instant completed=Instant.parse("2026-01-02T00:00:00Z");
        jdbc.sql("UPDATE functional_test_run SET finished_at=:time WHERE id=:id").param("time",java.sql.Timestamp.from(completed)).param("id",f.run()).update();
        result(f,1,"FAILED","补充备注");assertEquals(completed,run(f).finishedAt());
        result(f,1,"NOT_RUN","重新执行");assertEquals("RUNNING",run(f).status());assertNull(run(f).finishedAt());assertEquals(first,run(f).startedAt());
        result(f,0,"NOT_RUN","全部重置");assertEquals("NOT_STARTED",run(f).status());assertNull(run(f).finishedAt());assertEquals(first,run(f).startedAt());
        result(f,0,"FAILED","");result(f,1,"BLOCKED","");assertEquals(first,run(f).startedAt());assertTrue(run(f).finishedAt().isAfter(completed));
    }
    // Hold the parent row so both requests reach the same contention point, without timing-dependent sleeps.
    void contend(Fixture f,Runnable one,Runnable two,boolean allowSecondToFinish) throws Exception {
        var release=new CountDownLatch(1);var locked=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var holder=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.sql("SELECT id FROM functional_test_run WHERE id=:id FOR UPDATE").param("id",f.run()).query(String.class).single();locked.countDown();
                try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var first=pool.submit(()->withRequest(one));var second=pool.submit(()->withRequest(two));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean ready=false;
                while(System.nanoTime()<deadline) {
                    String waiters=!FunctionalDatabaseProfile.mysql()
                            ?"SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL"
                            :"SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits";
                    int blocked=jdbc.sql(waiters).query(Integer.class).single();
                    if(blocked>=2||(allowSecondToFinish&&second.isDone()&&blocked>=1)){ready=true;break;}
                    Thread.sleep(10);
                }
                assertTrue(ready,"writers must reach the locked parent or finish the independent step update");
            } finally {release.countDown();}
            holder.get(5,TimeUnit.SECONDS);first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();}
    }
    void withRequest(Runnable task){
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        try{task.run();}finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }
    @Test void concurrentCaseResultsProduceCompletedRunWithConsistentCounts() throws Exception {
        var f=fixture();contend(f,()->result(f,0,"FAILED","并发一"),()->result(f,1,"BLOCKED","并发二"),false);
        var run=run(f);assertEquals(2,run.totalCount());assertEquals(1,run.failedCount());assertEquals(1,run.blockedCount());assertEquals(0,run.notRunCount());
        assertEquals("COMPLETED",run.status());assertNotNull(run.finishedAt());
    }
    @Test void concurrentStepAndCaseResultsCannotLeavePassedCaseWithFailedStep() throws Exception {
        var f=fixture();var step=executions.listRunSteps(f.actor(),f.project(),f.cases().getFirst().id()).getFirst();
        executions.updateRunStep(f.actor(),f.project(),step.id(),new FunctionalTestExecutionService.RunStepInput("PASSED","初次通过"));
        var rejected=new java.util.concurrent.atomic.AtomicInteger();
        Runnable pass=()->{try{result(f,0,"PASSED","通过");}catch(ResponseStatusException e){assertEquals(400,e.getStatusCode().value());rejected.incrementAndGet();}};
        Runnable fail=()->{try{executions.updateRunStep(f.actor(),f.project(),step.id(),new FunctionalTestExecutionService.RunStepInput("FAILED","并发回归"));}catch(ResponseStatusException e){assertEquals(400,e.getStatusCode().value());rejected.incrementAndGet();}};
        contend(f,pass,fail,true);
        var testCase=executions.listRunCases(f.actor(),f.project(),f.run()).getFirst();var finalStep=executions.listRunSteps(f.actor(),f.project(),testCase.id()).getFirst();
        assertFalse(testCase.status().equals("PASSED")&&!finalStep.status().equals("PASSED"));assertEquals(1,rejected.get());
    }
    @Test void foreignProjectAndViewerCannotChangeCaseOrStep() {
        var f=fixture();var other=fixture();var step=executions.listRunSteps(f.actor(),f.project(),f.cases().getFirst().id()).getFirst();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->executions.updateRunCase(other.actor(),f.project(),f.cases().getFirst().id(),new FunctionalTestExecutionService.RunCaseInput("FAILED","")));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->executions.updateRunStep(other.actor(),other.project(),step.id(),new FunctionalTestExecutionService.RunStepInput("FAILED",""))).getStatusCode().value());
        jdbc.sql("INSERT INTO membership(id,user_id,organization_id,project_id,role_code) VALUES(:id,:user,:org,:project,'VIEWER')")
                .param("id",com.github.f4b6a3.ulid.UlidCreator.getUlid().toString()).param("user",other.actor().userId()).param("org",access.project(f.actor(),f.project()).organizationId()).param("project",f.project()).update();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->executions.updateRunStep(other.actor(),f.project(),step.id(),new FunctionalTestExecutionService.RunStepInput("FAILED","")));
        assertEquals("NOT_STARTED",run(f).status());assertEquals("NOT_RUN",executions.listRunSteps(f.actor(),f.project(),f.cases().getFirst().id()).getFirst().status());
    }
}
