package io.testhub.platform.functional;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.ProjectFileReferenceGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Portable editable history preserves endpoint IDs and never attributes a foreign user ID locally. */
@Service
public class FunctionalHistorySyncService {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FunctionalAssetService assets;
    private final FunctionalTransferHistory history;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectFileReferenceGuard fileLinks;
    public FunctionalHistorySyncService(AccessService access, FunctionalAssetService assets,FunctionalTransferHistory history,
            AuditService audit,JdbcClient jdbc,ObjectMapper json,ProjectFileReferenceGuard fileLinks) {
        this.access=access;this.assets=assets;this.history=history;this.audit=audit;this.jdbc=jdbc;this.json=json;this.fileLinks=fileLinks;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String project, String expectedUserId) {
        access.requireProjectParameterWrite(actor, project, WRITERS); requireActor(actor, expectedUserId);
        return read(actor, project);
    }
    private Snapshot read(AuthenticatedPrincipal actor,String project) {
        for(var entry:Map.of("functional_test_run",100,"functional_test_plan",1000,"functional_case",1000).entrySet())
            if(jdbc.sql("SELECT COUNT(*) FROM "+entry.getKey()+" WHERE project_id=:p").param("p",project).query(Long.class).single()>entry.getValue())throw invalid("执行历史或依赖资料超过同步数量范围");
        if(jdbc.sql("SELECT COUNT(*) FROM functional_test_run_case c JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p").param("p",project).query(Long.class).single()>1000
            ||jdbc.sql("SELECT COUNT(*) FROM functional_test_run_step s JOIN functional_test_run_case c ON c.id=s.run_case_id JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p").param("p",project).query(Long.class).single()>5000)throw invalid("执行历史最多包含 1000 条执行用例和 5000 条步骤");
        if(jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(c.note)),0) FROM functional_test_run_case c JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p").param("p",project).query(Long.class).single()
            +jdbc.sql("SELECT COALESCE(SUM(OCTET_LENGTH(s.step_text)+OCTET_LENGTH(s.actual_result)),0) FROM functional_test_run_step s JOIN functional_test_run_case c ON c.id=s.run_case_id JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p").param("p",project).query(Long.class).single()>SNAPSHOT_BYTES)throw invalid("执行历史正文超过 3 MiB 范围");
        var plans=assetIds(project,"functional_test_plan","plan_code");var cases=assetIds(project,"functional_case","case_code");
        var planCodes=inverse(plans);var caseCodes=inverse(cases);
        var rows=jdbc.sql("SELECT id,sync_key FROM functional_test_run WHERE project_id=:p ORDER BY id").param("p",project).query().listOfRows();
        var createdAt=new TreeMap<String,java.time.Instant>();var items=new ArrayList<Item>();var itemIds=new TreeMap<String,String>();var layouts=new TreeMap<String,Layout>();
        for(var run:history.export(project).runs()) {
            var row=rows.stream().filter(r->run.id().equals(r.get("id"))).findFirst().orElseThrow();String name=row.get("sync_key")==null?run.id():(String)row.get("sync_key");
            if(!planCodes.containsKey(run.planId())||run.cases().stream().anyMatch(c->!caseCodes.containsKey(c.caseId())))throw conflict("执行历史含项目外或失效的计划／用例，请先核对");
            var d=new Definition(planCodes.get(run.planId()),run.name(),run.status(),run.startedAt(),run.finishedAt(),run.cases().stream().map(c->new CaseResult(caseCodes.get(c.caseId()),c.status(),c.note(),c.executedAt(),c.steps().stream().map(step->new StepResult(step.number(),step.text(),step.status(),step.actualResult(),step.updatedAt())).toList())).sorted(Comparator.comparing(CaseResult::caseCode)).toList());
            var item=new Item("RUN",name,json.convertValue(d,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));validate(item);items.add(item);createdAt.put(key(item),run.createdAt());if(itemIds.put(key(item),run.id())!=null)throw conflict("执行历史来源标识重复，请先核对");
            var caseIds=new TreeMap<String,String>();var stepIds=new TreeMap<String,Map<Integer,String>>();for(var c:run.cases()){String code=caseCodes.get(c.caseId());caseIds.put(code,c.id());var map=new TreeMap<Integer,String>();for(var step:c.steps())map.put(step.number(),step.id());stepIds.put(code,map);}layouts.put(key(item),new Layout(run.planId(),caseIds,stepIds));
        }
        var raw=new ArrayList<Object>();for(var query:List.of("SELECT * FROM functional_test_run WHERE project_id=:p ORDER BY id","SELECT c.* FROM functional_test_run_case c JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p ORDER BY c.id","SELECT s.* FROM functional_test_run_step s JOIN functional_test_run_case c ON c.id=s.run_case_id JOIN functional_test_run r ON r.id=c.run_id WHERE r.project_id=:p ORDER BY s.id"))raw.add(jdbc.sql(query).param("p",project).query().listOfRows());
        var result=new Snapshot(1,"FUNCTIONAL_HISTORY_SYNC",project,actor.userId(),hash(encoded(List.of(raw,plans,cases))),items.stream().sorted(Comparator.comparing(this::key)).toList(),itemIds,plans,cases,layouts,createdAt);
        if(encoded(result).length>SNAPSHOT_BYTES)throw invalid("执行历史快照超过 3 MiB");return result;
    }
    private Map<String,String> assetIds(String project,String table,String column){var result=new TreeMap<String,String>();for(var row:jdbc.sql("SELECT id,"+column+" FROM "+table+" WHERE project_id=:p ORDER BY id").param("p",project).query().listOfRows())if(result.put((String)row.get(column),(String)row.get("id"))!=null)throw conflict("依赖编号重复，请先核对");return result;}
    private Map<String,String> inverse(Map<String,String> map){var result=new HashMap<String,String>();map.forEach((k,v)->result.put(v,k));return result;}
    public Request decode(byte[] body) {
        if(body==null||body.length==0) throw invalid("执行历史同步请求不能为空");
        if(body.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"执行历史同步请求不能超过 4 MiB。");
        return decodeValue(body,Request.class);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,Request request) {
        var context=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null) throw invalid("执行历史同步请求不能为空"); requireActor(actor,request.expectedUserId());
        var requestKey=uuid(request.requestKey());
        if(request.expectedRevision()==null||!request.expectedRevision().matches("[a-f0-9]{64}")||request.operations()==null||request.operations().isEmpty()||request.operations().size()>100)
            throw invalid("执行历史同步执行批次或操作数量无效");
        var ids=new HashSet<String>(); var keys=new HashSet<String>();
        for(var op:request.operations()) {
            if(op==null||!ids.add(uuid(op.operationId()))) throw invalid("同步操作标识重复或无效");
            validate(op.item());if(op.createdAt()==null)throw invalid("历史创建时间不能为空");time(op.createdAt()); fileLinks.validate(op.item().definition(),project); if(!keys.add(key(op.item()))) throw invalid("同一执行历史不能重复提交");
        }
        byte[] payload=encoded(request); if(payload.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"执行历史同步请求不能超过 4 MiB。");
        String payloadHash=hash(payload); assets.lockProject(project);
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM functional_history_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p",project).param("a",actor.userId()).param("k",requestKey).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()) {
            if(!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他执行历史");
            try { return json.readValue(prior.get().getValue(),Receipt.class); } catch(java.io.IOException error) { throw new IllegalStateException("执行历史同步回执损坏",error); }
        }
        var current=read(actor,project); if(!current.revision().equals(request.expectedRevision())) throw conflict("目标执行历史已变化，请重新同步并核对冲突");
        var merged=new LinkedHashMap<String,Item>();current.items().forEach(i->merged.put(key(i),i));
        for(var op:request.operations()) {
            var d=definition(op.item());if(!current.planIds().containsKey(d.planCode())||d.cases().stream().anyMatch(c->!current.caseIds().containsKey(c.caseCode())))throw conflict("计划或用例未在目标项目准备，请先同步基础资料");
            var old=merged.get(key(op.item()));if(old!=null&&!layout(definition(old)).equals(layout(d)))throw conflict("已有批次的历史用例／步骤布局不同，不会删改历史结构");merged.put(key(op.item()),op.item());
        }
        validateItems(new ArrayList<>(merged.values()));if(encoded(merged.values()).length>SNAPSHOT_BYTES)throw invalid("合并后的执行历史超过 3 MiB");
        try {
            for(var op:request.operations()) if(!current.items().contains(op.item())) write(actor,context.organizationId(),project,op.item(),current,op.createdAt());
            var written=read(actor,project);
            var receipt=new Receipt(requestKey,request.operations().stream().map(op->new Result(op.operationId(),op.item(),written.itemIds().get(key(op.item())),written.layouts().get(key(op.item())),usedAssets(op.item(),written))).toList());
            jdbc.sql("INSERT INTO functional_history_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:a,:k,:h,:r)")
                    .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("a",actor.userId()).param("k",requestKey).param("h",payloadHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();
            audit.record(actor,context.organizationId(),project,"FUNCTIONAL_HISTORY_SYNCED","FUNCTIONAL_HISTORY_SYNC",requestKey,Map.of("runs",request.operations().size()));
            return receipt;
        } catch(DataIntegrityViolationException error) {throw conflict("执行历史引用或来源标识与数据库约束冲突，请核对；整批未写入");}
    }
    private void write(AuthenticatedPrincipal actor,String organization,String project,Item item,Snapshot current,java.time.Instant createdAt) {
        var d=definition(item);String run=current.itemIds().get(key(item));var layout=current.layouts().get(key(item));var before=current.items().stream().filter(i->key(i).equals(key(item))).findFirst().map(this::definition).orElse(null);
        if(run==null){run=UlidCreator.getUlid().toString();jdbc.sql("INSERT INTO functional_test_run(id,organization_id,project_id,plan_id,name,status,created_by,started_at,finished_at,sync_key,created_at,updated_at) VALUES(:i,:o,:p,:plan,:n,:s,:u,:a,:b,:key,:created,GREATEST(CURRENT_TIMESTAMP(3),:created))").param("i",run).param("o",organization).param("p",project).param("plan",current.planIds().get(d.planCode())).param("n",d.title()).param("s",d.status()).param("u",actor.userId()).param("a",d.startedAt()).param("b",d.finishedAt()).param("key",item.name()).param("created",createdAt).update();}
        else jdbc.sql("UPDATE functional_test_run SET name=:n,status=:s,started_at=:a,finished_at=:b,updated_at=GREATEST(CURRENT_TIMESTAMP(3),created_at) WHERE id=:i AND project_id=:p").param("n",d.title()).param("s",d.status()).param("a",d.startedAt()).param("b",d.finishedAt()).param("i",run).param("p",project).update();
        for(var c:d.cases()){
            String rc=layout==null?null:layout.caseIds().get(c.caseCode());var oldCase=before==null?null:before.cases().stream().filter(v->v.caseCode().equals(c.caseCode())).findFirst().orElseThrow();
            if(rc==null){rc=UlidCreator.getUlid().toString();jdbc.sql("INSERT INTO functional_test_run_case(id,run_id,case_id,status,note,executed_at) VALUES(:i,:r,:c,:s,:n,:t)").param("i",rc).param("r",run).param("c",current.caseIds().get(c.caseCode())).param("s",c.status()).param("n",c.note()).param("t",c.executedAt()).update();}
            else if(!List.of(c.status(),c.note(),Optional.ofNullable(c.executedAt())).equals(List.of(oldCase.status(),oldCase.note(),Optional.ofNullable(oldCase.executedAt()))))jdbc.sql("UPDATE functional_test_run_case SET status=:s,note=:n,executed_by=NULL,executed_at=:t WHERE id=:i").param("s",c.status()).param("n",c.note()).param("t",c.executedAt()).param("i",rc).update();
            for(var step:c.steps()){
                String sid=layout==null?null:layout.stepIds().get(c.caseCode()).get(step.number());var old=oldCase==null?null:oldCase.steps().stream().filter(v->v.number()==step.number()).findFirst().orElseThrow();
                if(sid==null)jdbc.sql("INSERT INTO functional_test_run_step(id,run_case_id,step_no,step_text,status,actual_result,updated_at) VALUES(:i,:r,:n,:text,:s,:actual,:t)").param("i",UlidCreator.getUlid().toString()).param("r",rc).param("n",step.number()).param("text",step.text()).param("s",step.status()).param("actual",step.actualResult()).param("t",step.updatedAt()).update();
                else if(!step.equals(old))jdbc.sql("UPDATE functional_test_run_step SET status=:s,actual_result=:a,updated_by=NULL,updated_at=:t WHERE id=:i").param("s",step.status()).param("a",step.actualResult()).param("t",step.updatedAt()).param("i",sid).update();
            }
        }
    }
    private Map<String,String> usedAssets(Item item,Snapshot snapshot){var d=definition(item);var m=new TreeMap<String,String>();m.put("PLAN\n"+d.planCode(),snapshot.planIds().get(d.planCode()));for(var c:d.cases())m.put("CASE\n"+c.caseCode(),snapshot.caseIds().get(c.caseCode()));return m;}
    private List<Object> layout(Definition d){return List.of(d.planCode(),d.cases().stream().map(c->List.of(c.caseCode(),c.steps().stream().map(s->List.of(s.number(),s.text())).toList())).toList());}
    private void validateItems(List<Item> items) {
        if(items.size()>100) throw invalid("每项目最多同步 100 个执行批次");
        if(items.stream().mapToInt(i->definition(i).cases().size()).sum()>1000||items.stream().flatMap(i->definition(i).cases().stream()).mapToInt(c->c.steps().size()).sum()>5000)throw invalid("合并历史超过 1000 个用例或 5000 条步骤");
        var keys=new HashSet<String>();for(var item:items) {validate(item);if(!keys.add(key(item))) throw invalid("执行批次号存在大小写重复，请先统一名称");}
    }
    private void validate(Item item) {
        if(item==null||!"RUN".equals(item.kind())||item.name()==null||!item.name().matches("[A-Z0-9]{26}")||item.definition()==null)throw invalid("执行历史来源标识无效");
        var d=definition(item);if(!code(d.planCode())||d.title()==null||d.title().isBlank()||d.title().length()>200||!Set.of("NOT_STARTED","RUNNING","COMPLETED").contains(d.status()==null?"":d.status())||d.cases()==null||d.cases().isEmpty()||d.cases().size()>1000)throw invalid("执行历史内容无效");
        var codes=new TreeSet<String>();int terminal=0,steps=0;time(d.startedAt());time(d.finishedAt());for(var c:d.cases()){
            if(c==null||!code(c.caseCode())||!codes.add(c.caseCode())||!result(c.status())||c.note()==null||c.note().length()>20000||c.steps()==null||c.steps().size()>5000)throw invalid("历史用例无效");time(c.executedAt());if(!"NOT_RUN".equals(c.status()))terminal++;
            int previous=0;for(var step:c.steps()){if(step==null||step.number()<=previous||step.number()>9999||step.text()==null||step.text().isBlank()||step.text().length()>20000||!result(step.status())||step.actualResult()==null||step.actualResult().length()>20000||("PASSED".equals(c.status())&&!"PASSED".equals(step.status())))throw invalid("历史步骤无效或通过结果不一致");previous=step.number();time(step.updatedAt());steps++;}
        }
        if(!d.cases().stream().map(CaseResult::caseCode).toList().equals(List.copyOf(codes))||steps>5000)throw invalid("历史用例须按编号排序且步骤不能超过 5000 条");
        String expected=terminal==0?"NOT_STARTED":terminal==d.cases().size()?"COMPLETED":"RUNNING";if(!expected.equals(d.status())||(terminal>0&&d.startedAt()==null)||("COMPLETED".equals(d.status())!=(d.finishedAt()!=null))||(d.finishedAt()!=null&&d.finishedAt().isBefore(d.startedAt())))throw invalid("历史批次结果或执行时间不一致");
        if(!json.valueToTree(d).equals(json.valueToTree(item.definition())))throw invalid("执行历史字段无效或尚未规范化");
    }
    private boolean result(String s){return s!=null&&Set.of("NOT_RUN","PASSED","FAILED","BLOCKED").contains(s);}
    private boolean code(String s){return s!=null&&s.matches("[A-Z][A-Z0-9_-]{1,39}");}
    private void time(java.time.Instant t){if(t!=null&&(t.isBefore(java.time.Instant.ofEpochSecond(1))||t.isAfter(java.time.Instant.ofEpochSecond(2147483647,499000000))||t.getNano()%1000000!=0))throw invalid("历史时间超出毫秒精度或数据库范围");}
    private Definition definition(Item item){return decodeValue(encoded(item.definition()),Definition.class);}
    private String key(Item item) {return item.kind()+"\n"+item.name().toLowerCase(Locale.ROOT);}
    private void requireActor(AuthenticatedPrincipal actor,String expected) {if(!actor.userId().equals(expected)) throw conflict("登录账号已变化，同步已停止");}
    private String uuid(String v) {if(v==null||!v.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid("同步请求标识必须是 UUID"); return UUID.fromString(v).toString();}
    private <T> T decodeValue(byte[] bytes,Class<T> type) {try{return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);}catch(java.io.IOException e){throw invalid("执行历史同步 JSON 不合法或包含不支持的字段");}}
    private byte[] encoded(Object value) {try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value);}catch(java.io.IOException e){throw new IllegalStateException("执行历史同步序列化失败",e);}}
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private ResponseStatusException invalid(String message) {return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    public record Snapshot(int version,String scope,String projectId,String actorId,String revision,List<Item> items,Map<String,String> itemIds,Map<String,String> planIds,Map<String,String> caseIds,Map<String,Layout> layouts,Map<String,java.time.Instant> createdAt) { }
    public record Item(String kind,String name,Map<String,Object> definition) { }
    public record Operation(String operationId,Item item,java.time.Instant createdAt) { }
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Operation> operations) { }
    public record Result(String operationId,Item item,String itemId,Layout layout,Map<String,String> assetIds) { }
    public record Receipt(String requestKey,List<Result> results) { }
    public record Layout(String planId,Map<String,String> caseIds,Map<String,Map<Integer,String>> stepIds) { }
    public record Definition(String planCode,String title,String status,java.time.Instant startedAt,java.time.Instant finishedAt,List<CaseResult> cases) { }
    public record CaseResult(String caseCode,String status,String note,java.time.Instant executedAt,List<StepResult> steps) { }
    public record StepResult(int number,String text,String status,String actualResult,java.time.Instant updatedAt) { }
}
