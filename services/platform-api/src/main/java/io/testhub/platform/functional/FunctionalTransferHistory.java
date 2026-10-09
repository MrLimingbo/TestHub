package io.testhub.platform.functional;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Execution snapshots are copied directly; regenerating them from current plan membership loses history. */
@Service
public class FunctionalTransferHistory {
    private final JdbcClient jdbc;
    private final Validator validator;
    public FunctionalTransferHistory(JdbcClient jdbc, Validator validator) { this.jdbc=jdbc; this.validator=validator; }

    public Definitions export(String project) {
        var runs=jdbc.sql("SELECT * FROM functional_test_run WHERE project_id=:project ORDER BY created_at,id LIMIT 1001")
                .param("project",project).query((rs,n)->new Run(rs.getString("id"),rs.getString("plan_id"),rs.getString("name"),rs.getString("status"),
                        time(rs,"started_at"),time(rs,"finished_at"),time(rs,"created_at"),time(rs,"updated_at"),List.of())).list();
        if(runs.size()>1000)throw invalid("单次最多迁移 1000 个执行批次。");
        Map<String,List<RunCase>> cases=new HashMap<>();
        var rows=jdbc.sql("SELECT c.* FROM functional_test_run_case c JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:project ORDER BY c.id LIMIT 10001")
                .param("project",project).query((rs,n)->Map.entry(rs.getString("run_id"),new RunCase(rs.getString("id"),rs.getString("case_id"),rs.getString("status"),rs.getString("note"),time(rs,"executed_at"),List.of()))).list();
        if(rows.size()>10000)throw invalid("单次最多迁移 10000 条执行用例。");
        Map<String,List<Step>> steps=new HashMap<>();
        var stepRows=jdbc.sql("SELECT s.* FROM functional_test_run_step s JOIN functional_test_run_case c ON c.id=s.run_case_id JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:project ORDER BY s.step_no,s.id LIMIT 50001")
                .param("project",project).query((rs,n)->Map.entry(rs.getString("run_case_id"),new Step(rs.getString("id"),rs.getInt("step_no"),rs.getString("step_text"),rs.getString("status"),rs.getString("actual_result"),time(rs,"updated_at")))).list();
        if(stepRows.size()>50000)throw invalid("单次最多迁移 50000 条执行步骤。");
        stepRows.forEach(row->steps.computeIfAbsent(row.getKey(),key->new ArrayList<>()).add(row.getValue()));
        for(var row:rows){var c=row.getValue();cases.computeIfAbsent(row.getKey(),key->new ArrayList<>()).add(new RunCase(c.id(),c.caseId(),c.status(),c.note(),c.executedAt(),steps.getOrDefault(c.id(),List.of())));}
        return new Definitions(runs.stream().map(r->new Run(r.id(),r.planId(),r.name(),r.status(),r.startedAt(),r.finishedAt(),r.createdAt(),r.updatedAt(),cases.getOrDefault(r.id(),List.of()))).toList());
    }

    public void validate(Definitions data, FunctionalTransferService.Snapshot snapshot, Set<String> ids) {
        if(data==null||!validator.validate(data).isEmpty()||snapshot.plans()==null)throw invalid("执行历史格式或范围无效，请同时选择测试计划。");
        Set<String> plans=new HashSet<>(snapshot.plans().stream().map(FunctionalTransferService.Plan::id).toList());
        Set<String> cases=new HashSet<>(snapshot.cases().stream().map(FunctionalTransferService.Case::id).toList());
        long caseCount=0,stepCount=0;
        for(var run:data.runs()) {
            timestamps(run.startedAt(),run.finishedAt(),run.createdAt(),run.updatedAt());
            if(!ids.add(run.id())||!plans.contains(run.planId()))throw invalid("执行批次标识重复或关联计划不在迁移范围内。");
            Set<String> members=new HashSet<>(); int terminal=0;
            for(var c:run.cases()) {
                timestamps(c.executedAt());
                if(!ids.add(c.id())||!cases.contains(c.caseId())||!members.add(c.caseId()))throw invalid("执行用例重复或关联用例不在迁移范围内。");
                if(!c.status().equals("NOT_RUN"))terminal++;
                Set<Integer> numbers=new HashSet<>();
                for(var step:c.steps()) {
                    timestamps(step.updatedAt());
                    if(!ids.add(step.id())||!numbers.add(step.number()))throw invalid("执行步骤标识或序号重复。");
                    if(c.status().equals("PASSED")&&!step.status().equals("PASSED"))throw invalid("通过的执行用例包含未通过的步骤。");
                }
                stepCount+=c.steps().size();
            }
            caseCount+=run.cases().size();
            String expected=terminal==0?"NOT_STARTED":terminal==run.cases().size()?"COMPLETED":"RUNNING";
            if(!run.status().equals(expected))throw invalid("执行批次状态与用例结果不一致，请先修复来源记录。");
            if((terminal>0&&run.startedAt()==null)||(run.status().equals("COMPLETED")!=(run.finishedAt()!=null))
                    ||(run.finishedAt()!=null&&run.finishedAt().isBefore(run.startedAt()))
                    ||run.updatedAt().isBefore(run.createdAt()))throw invalid("执行批次时间无效。");
        }
        if(caseCount>10000||stepCount>50000)throw invalid("单次最多迁移 10000 条执行用例和 50000 条步骤。");
    }

    public Counts transfer(String organization,String project,String user,Definitions data,Map<String,String> assets) {
        int cases=0,steps=0;
        for(var run:data.runs()) {
            String runId=id();
            jdbc.sql("INSERT INTO functional_test_run(id,organization_id,project_id,plan_id,name,status,created_by,started_at,finished_at,created_at,updated_at) VALUES(:id,:org,:project,:plan,:name,:status,:user,:start,:finish,:created,:updated)")
                    .param("id",runId).param("org",organization).param("project",project).param("plan",assets.get(run.planId())).param("name",run.name()).param("status",run.status()).param("user",user)
                    .param("start",run.startedAt()).param("finish",run.finishedAt()).param("created",run.createdAt()).param("updated",run.updatedAt()).update();
            for(var c:run.cases()) {
                String caseId=id();assets.put(c.id(),caseId);cases++;
                jdbc.sql("INSERT INTO functional_test_run_case(id,run_id,case_id,status,note,executed_by,executed_at) VALUES(:id,:run,:case,:status,:note,NULL,:executed)")
                        .param("id",caseId).param("run",runId).param("case",assets.get(c.caseId())).param("status",c.status()).param("note",c.note()).param("executed",c.executedAt()).update();
                for(var step:c.steps()) {
                    steps++;
                    jdbc.sql("INSERT INTO functional_test_run_step(id,run_case_id,step_no,step_text,status,actual_result,updated_by,updated_at) VALUES(:id,:case,:number,:text,:status,:actual,NULL,:updated)")
                            .param("id",id()).param("case",caseId).param("number",step.number()).param("text",step.text()).param("status",step.status()).param("actual",step.actualResult()).param("updated",step.updatedAt()).update();
                }
            }
        }
        return new Counts(data.runs().size(),cases,steps);
    }
    public static Definitions rewrite(Definitions data,UnaryOperator<String> text) {
        if(data==null)return null;
        return new Definitions(data.runs().stream().map(r->new Run(r.id(),r.planId(),r.name(),r.status(),r.startedAt(),r.finishedAt(),r.createdAt(),r.updatedAt(),r.cases().stream().map(c->new RunCase(c.id(),c.caseId(),c.status(),text.apply(c.note()),c.executedAt(),c.steps().stream().map(s->new Step(s.id(),s.number(),text.apply(s.text()),s.status(),text.apply(s.actualResult()),s.updatedAt())).toList())).toList())).toList());
    }
    private static void timestamps(Instant... values) {
        // Both backends use millisecond precision; the enterprise schema uses MySQL TIMESTAMP(3).
        for(var value:values) if(value!=null && (value.isBefore(Instant.ofEpochSecond(1))
                || value.isAfter(Instant.ofEpochSecond(2147483647,499000000)) || value.getNano()%1000000!=0))
            throw invalid("执行历史时间超出数据库支持范围或毫秒精度。");
    }
    private static Instant time(ResultSet rs,String column)throws SQLException { var value=rs.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static String id(){return UlidCreator.getUlid().toString();}
    private static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    public record Definitions(@NotNull @Size(max=1000) List<@NotNull @Valid Run> runs){}
    public record Run(@NotBlank @Size(max=26) String id,@NotBlank @Size(max=26) String planId,@NotBlank @Size(max=200) String name,
                      @NotNull @Pattern(regexp="NOT_STARTED|RUNNING|COMPLETED") String status,Instant startedAt,Instant finishedAt,
                      @NotNull Instant createdAt,@NotNull Instant updatedAt,@NotNull @Size(min=1,max=10000) List<@NotNull @Valid RunCase> cases){}
    public record RunCase(@NotBlank @Size(max=26) String id,@NotBlank @Size(max=26) String caseId,
                          @NotNull @Pattern(regexp="NOT_RUN|PASSED|FAILED|BLOCKED") String status,@NotNull @Size(max=20000) String note,
                          Instant executedAt,@NotNull @Size(max=50000) List<@NotNull @Valid Step> steps){}
    public record Step(@NotBlank @Size(max=26) String id,@Min(1) int number,@NotBlank @Size(max=20000) String text,
                       @NotNull @Pattern(regexp="NOT_RUN|PASSED|FAILED|BLOCKED") String status,@NotNull @Size(max=20000) String actualResult,Instant updatedAt){}
    public record Counts(int runs,int cases,int steps){}
}
