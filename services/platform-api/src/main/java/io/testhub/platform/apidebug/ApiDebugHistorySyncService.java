package io.testhub.platform.apidebug;

import com.fasterxml.jackson.databind.*;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import io.testhub.platform.functional.FunctionalTransferFiles;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Immutable, scoped archives, separate from native requests and execution statistics. */
@Service
public class ApiDebugHistorySyncService {
    public static final int MAX_BYTES=4*1024*1024;
    private static final Set<String> MODES=Set.of("history","replay-drafts"),PROTOCOLS=Set.of("HTTP","GRAPHQL","SOAP","WEBSOCKET","GRPC"),RESULTS=Set.of("DRAFT","COMPLETED","FAILED");
    private static final Set<String> WRITERS=Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD","TESTER");
    private static final Set<String> NOTICES=Set.of("MANUAL_REVIEW","AUTH","BODY","URL","ENVIRONMENT","GRPC_DESCRIPTOR","PROJECT_FILE");
    private final ApiDebugHistoryRepository history; private final AccessService access; private final SecretCipher cipher; private final ObjectMapper json; private final ApiReplayTemplateSanitizer sanitizer; private final JdbcClient jdbc; private final AuditService audit;
    public ApiDebugHistorySyncService(ApiDebugHistoryRepository history,AccessService access,SecretCipher cipher,ObjectMapper json,ApiReplayTemplateSanitizer sanitizer,JdbcClient jdbc,AuditService audit){this.history=history;this.access=access;this.cipher=cipher;this.json=json;this.sanitizer=sanitizer;this.jdbc=jdbc;this.audit=audit;}
    private void scope(AuthenticatedPrincipal actor,String project,String expected,String mode){if(!MODES.contains(mode))throw invalid("同步范围无效");access.requireProjectParameterWrite(actor,project,WRITERS);if(!actor.userId().equals(expected))throw conflict("登录账号变化，调试历史同步已停止");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Inventory inventory(AuthenticatedPrincipal actor,String project,String expected,String mode){
        scope(actor,project,expected,mode);var entries=new ArrayList<Entry>();var size=history.transferSize(project);if(size.count()>1000||size.storedBytes()>8*1024*1024)throw conflict("原生调试历史超过 1000 条或存储估算 8 MiB，暂停同步；不会截断");
        Set<String> secrets=mode.equals("history")?Set.of():secrets(project);
        for(var row:history.findRecent(project,1001)){if(mode.equals("replay-drafts")&&row.replayDraft()==null)continue;var item=nativeItem(row,project,mode,secrets);entries.add(entry(item,row.importSourceProjectId()==null,summary(nativeItem(row,project,"history",Set.of()).payload())));}
        var archiveSize=jdbc.sql("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload_ciphertext)),0) FROM api_debug_sync_archive WHERE project_id=:p AND sync_kind=:m").param("p",project).param("m",mode).query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2)}).single();
        if(archiveSize[0]>1000||archiveSize[1]>12*1024*1024)throw conflict("同步副本超过 1000 条或加密存储 12 MiB，暂停同步");
        var archived=jdbc.sql("SELECT source_project_id,source_history_id,source_revision,method,protocol,result,request_target,occurred_at FROM api_debug_sync_archive WHERE project_id=:p AND sync_kind=:m ORDER BY source_project_id,source_history_id LIMIT 1001").param("p",project).param("m",mode).query((rs,n)->new Entry(rs.getString(1)+"."+rs.getString(2),rs.getString(3),false,rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant().toString())).list();entries.addAll(archived);
        entries.sort(Comparator.comparing(Entry::key));if(entries.size()>1000||entries.stream().map(Entry::key).distinct().count()!=entries.size())throw conflict("合并历史超过 1000 条或同源重复，暂停同步；不会覆盖或截断");
        return new Inventory(1,mode.equals("history")?"API_DEBUG_HISTORY_SYNC":"API_DEBUG_REPLAY_SYNC",project,actor.userId(),hash(encoded(entries)),entries);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Item read(AuthenticatedPrincipal actor,String project,String expected,String mode,String key){
        scope(actor,project,expected,mode);requireKey(key);var size=history.transferSize(project);if(size.count()>1000||size.storedBytes()>8*1024*1024)throw conflict("原生调试历史超过同步范围");
        var matches=history.findRecent(project,1001).stream().filter(row->origin(row,project).equals(key)&&(mode.equals("history")||row.replayDraft()!=null)).toList();
        var stored=jdbc.sql("SELECT payload_ciphertext,source_revision FROM api_debug_sync_archive WHERE project_id=:p AND sync_kind=:m AND source_project_id=:sp AND source_history_id=:id").param("p",project).param("m",mode).param("sp",key.substring(0,26)).param("id",key.substring(27)).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(matches.size()>1||(!matches.isEmpty()&&stored.isPresent()))throw conflict("同源历史重复，请核对原数据");
        if(!matches.isEmpty())return nativeItem(matches.getFirst(),project,mode,mode.equals("history")?Set.of():secrets(project));
        if(stored.isEmpty())throw missing("调试同步记录不存在；不会重建");
        try{var item=json.readValue(cipher.decrypt(stored.get().getKey()),Item.class);validate(mode,item);if(!item.key().equals(key)||!item.revision().equals(stored.get().getValue()))throw conflict("同步记录来源或摘要不符");parent(actor,project,mode,item);return item;}
        catch(java.io.IOException|IllegalStateException e){throw conflict("同步记录无法解密或校验，暂停读取");}
    }
    private Item nativeItem(ApiDebugHistory row,String project,String mode,Set<String> secrets){
        JsonNode payload;
        if(mode.equals("history"))payload=json.valueToTree(new Summary(row.method(),row.protocol(),row.result(),target(row.requestUrl(),row.protocol()),row.responseStatus(),row.durationMs(),row.responseSize(),row.createdAt().toString()));
        else{
            if(row.replayDraft()==null)throw missing("该记录没有重放草稿");
            try{String raw=cipher.decrypt(row.replayDraft());if(raw.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw conflict("原始重放草稿超过 2 MiB");var safe=sanitizer.sanitize(json.readTree(raw),secrets);var summary=nativeItem(row,project,"history",Set.of());payload=json.valueToTree(new Draft(summary.sha256(),row.protocol(),safe.template(),safe.notices()));}
            catch(java.io.IOException|IllegalStateException e){throw conflict("原始草稿无法解密或解析，暂停该范围；原稿保留");}
        }
        var item=new Item(origin(row,project),hash(encoded(payload)),hash(encoded(payload)),payload);validate(mode,item);return item;
    }
    private Set<String> secrets(String project){var result=new HashSet<String>();var encrypted=jdbc.sql("SELECT parameter_value FROM project_parameter WHERE project_id=:p AND secret=TRUE").param("p",project).query(String.class).list();if(encrypted.size()>2000)throw conflict("环境密钥数量超过模板核对范围");for(String v:encrypted)try{result.add(cipher.decrypt(v));}catch(IllegalStateException e){throw conflict("环境密钥无法解密，暂停模板同步；原数据保留");}return result;}
    private String origin(ApiDebugHistory row,String project){String p=row.importSourceProjectId(),id=row.importSourceId();if((p==null)!=(id==null))throw conflict("迁入历史来源标识不完整");String key=(p==null?project:p)+"."+(id==null?row.id():id);requireKey(key);return key;}
    private String target(String raw,String protocol){try{var uri=new java.net.URI(raw);String host=uri.getHost();if(host==null)return protocol+"://[目标已隐藏]";if(host.contains(":")&&!host.startsWith("["))host="["+host+"]";return uri.getScheme()+"://"+host+(uri.getPort()<0?"":":"+uri.getPort())+"/…";}catch(java.net.URISyntaxException e){return protocol+"://[目标已隐藏]";}}
    private Entry entry(Item item,boolean nativeRecord,Summary summary){return new Entry(item.key(),item.revision(),nativeRecord,summary.method(),summary.protocol(),summary.result(),summary.target(),summary.createdAt());}
    public Request decode(byte[] bytes){if(bytes==null||bytes.length==0)throw invalid("同步请求不能为空");if(bytes.length>MAX_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"调试同步请求不能超过 4 MiB");try{var r=json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).<Request>readValue(bytes);if(r==null)throw invalid("请求不能为空");return r;}catch(java.io.IOException e){throw invalid("调试同步 JSON 无效或含不支持字段");}}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor,String project,String mode,Request request){
        scope(actor,project,request==null?"":request.expectedUserId(),mode);if(request==null||!uuid(request.requestKey())||!digest(request.expectedRevision())||request.items()==null||request.items().isEmpty()||request.items().size()>20||encoded(request).length>MAX_BYTES)throw invalid("调试同步请求标识、大小或批次无效");
        var keys=new HashSet<String>();for(var item:request.items()){validate(mode,item);if(!keys.add(item.key())||item.key().startsWith(project+"."))throw conflict("来源重复或本项目原生记录，不允许改写");}
        jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p",project).query(String.class).single();String requestHash=hash(encoded(request));
        var prior=jdbc.sql("SELECT payload_sha256,result_json FROM api_debug_sync_receipt WHERE project_id=:p AND sync_kind=:m AND requested_by=:u AND request_key=:k").param("p",project).param("m",mode).param("u",actor.userId()).param("k",request.requestKey()).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).optional();
        if(prior.isPresent()){
            if(!prior.get().getKey().equals(requestHash))throw conflict("该请求标识已用于其他内容");
            for(var item:request.items()){var saved=read(actor,project,actor.userId(),mode,item.key());if(!saved.equals(item))throw conflict("已确认副本已变化，不会覆盖或重建");parent(actor,project,mode,item);}
            try{return json.readValue(prior.get().getValue(),Receipt.class);}catch(java.io.IOException e){throw new IllegalStateException("调试同步回执损坏",e);}
        }
        var before=inventory(actor,project,actor.userId(),mode);if(!before.revision().equals(request.expectedRevision()))throw conflict("目标调试记录变化，请核对后重试");
        long added=request.items().stream().filter(i->before.entries().stream().noneMatch(e->e.key().equals(i.key()))).count();if(before.entries().size()+added>1000)throw conflict("合并后超过 1000 条");
        var context=access.context(actor);String organization=context.projects().entrySet().stream().filter(e->e.getValue().stream().anyMatch(p->p.id().equals(project))).map(Map.Entry::getKey).findFirst().orElseThrow();
        for(var item:request.items()){
            parent(actor,project,mode,item);var old=before.entries().stream().filter(e->e.key().equals(item.key())).findFirst();if(old.isPresent()){if(!read(actor,project,actor.userId(),mode,item.key()).equals(item))throw conflict("同源调试记录不同，不会改写历史或模板");continue;}
            var summary=mode.equals("history")?summary(item.payload()):summary(read(actor,project,actor.userId(),"history",item.key()).payload());
            jdbc.sql("INSERT INTO api_debug_sync_archive(id,organization_id,project_id,sync_kind,source_project_id,source_history_id,source_revision,payload_ciphertext,method,protocol,result,request_target,occurred_at,imported_by) VALUES(:id,:o,:p,:m,:sp,:sh,:rev,:body,:method,:protocol,:result,:target,:time,:u)").param("id",UlidCreator.getUlid().toString()).param("o",organization).param("p",project).param("m",mode).param("sp",item.key().substring(0,26)).param("sh",item.key().substring(27)).param("rev",item.revision()).param("body",cipher.encrypt(new String(encoded(item),StandardCharsets.UTF_8))).param("method",summary.method()).param("protocol",summary.protocol()).param("result",summary.result()).param("target",summary.target()).param("time",Instant.parse(summary.createdAt())).param("u",actor.userId()).update();
        }
        // Recheck aggregate encrypted size inside this transaction before publishing the receipt.
        inventory(actor,project,actor.userId(),mode);
        var receipt=new Receipt(request.requestKey(),request.items().stream().map(i->new Result(i.key(),i.revision(),i.sha256())).toList());jdbc.sql("INSERT INTO api_debug_sync_receipt(id,organization_id,project_id,sync_kind,requested_by,request_key,payload_sha256,result_json) VALUES(:id,:o,:p,:m,:u,:k,:h,:r)").param("id",UlidCreator.getUlid().toString()).param("o",organization).param("p",project).param("m",mode).param("u",actor.userId()).param("k",request.requestKey()).param("h",requestHash).param("r",new String(encoded(receipt),StandardCharsets.UTF_8)).update();audit.record(actor,organization,project,"API_DEBUG_SYNCED","API_DEBUG_SYNC",request.requestKey(),Map.of("scope",mode,"items",request.items().size()));return receipt;
    }
    private void parent(AuthenticatedPrincipal actor,String project,String mode,Item item){if(mode.equals("replay-drafts")){var h=read(actor,project,actor.userId(),"history",item.key());if(!h.sha256().equals(item.payload().path("historySha256").asText()))throw conflict("对应历史缺失或摘要不同，请先完成历史同步");}}
    private Summary summary(JsonNode node){try{return json.treeToValue(node,Summary.class);}catch(java.io.IOException e){throw invalid("历史摘要格式无效");}}
    private void validate(String mode,Item item){
        if(item==null)throw invalid("调试同步项不能为空");requireKey(item.key());if(!digest(item.revision())||!item.revision().equals(item.sha256())||item.payload()==null||!hash(encoded(item.payload())).equals(item.sha256())||encoded(item).length>1024*1024)throw invalid("调试记录 SHA-256 或单项 1 MiB 校验失败");
        if(mode.equals("history")){
            if(!fields(item.payload(),Set.of("method","protocol","result","target","responseStatus","durationMs","responseSize","createdAt")))throw invalid("历史摘要字段无效");var n=item.payload();for(String f:List.of("method","protocol","result","target","createdAt"))if(!n.path(f).isTextual())throw invalid("历史文字字段无效");for(String f:List.of("durationMs","responseSize"))if(!n.path(f).isIntegralNumber()||!n.path(f).canConvertToInt())throw invalid("历史计量字段无效");if(!n.path("responseStatus").isNull()&&(!n.path("responseStatus").isIntegralNumber()||!n.path("responseStatus").canConvertToInt()))throw invalid("历史状态字段无效");var p=summary(item.payload());if(p.method()==null||p.method().isBlank()||p.method().length()>12||!p.method().matches("[A-Z_]+")||!PROTOCOLS.contains(p.protocol())||!RESULTS.contains(p.result())||p.target()==null||p.target().length()>2000||p.target().contains("?")||p.target().contains("#")||p.target().contains("@")||!p.target().equals(target(p.target(),p.protocol()))||!(p.target().endsWith("/…")||p.target().endsWith("://[目标已隐藏]"))||p.durationMs()<0||p.responseSize()<0||(p.responseStatus()!=null&&(p.responseStatus()<0||p.responseStatus()>599))||!time(p.createdAt()))throw invalid("历史摘要内容无效");
        }else{
            if(!fields(item.payload(),Set.of("historySha256","protocol","template","notices"))||!digest(item.payload().path("historySha256").asText())||!PROTOCOLS.contains(item.payload().path("protocol").asText())||!item.payload().path("protocol").equals(item.payload().path("template").path("protocol")))throw invalid("重放模板字段无效");var notices=item.payload().path("notices");if(!notices.isArray()||notices.size()>8||!notices.toString().contains("MANUAL_REVIEW"))throw invalid("模板缺少人工核对标记");var seen=new HashSet<String>();for(var n:notices)if(!n.isTextual()||!NOTICES.contains(n.asText())||!seen.add(n.asText()))throw invalid("模板核对标记无效");
            if(!sanitizer.sanitize(item.payload().path("template"),Set.of()).template().equals(item.payload().path("template")))throw invalid("重放模板仍含未脱敏字段或未清除的本端引用");
        }
    }
    private boolean fields(JsonNode n,Set<String> keys){if(!n.isObject()||n.size()!=keys.size())return false;var names=new HashSet<String>();n.fieldNames().forEachRemaining(names::add);return names.equals(keys);}
    private boolean time(String v){try{var i=Instant.parse(v);return i.toEpochMilli()>=1000&&i.toEpochMilli()<=2147483647000L&&i.getNano()%1000000==0;}catch(RuntimeException e){return false;}}
    public byte[] encoded(Object value){try{return json.writeValueAsBytes(canonical(json.valueToTree(value)));}catch(java.io.IOException e){throw new IllegalStateException("调试同步无法序列化",e);}}
    private Object canonical(JsonNode v){if(v.isObject()){var out=new TreeMap<String,Object>();v.fields().forEachRemaining(e->out.put(e.getKey(),canonical(e.getValue())));return out;}if(v.isArray()){var out=new ArrayList<Object>();for(var n:v)out.add(canonical(n));return out;}return v;}
    private String hash(byte[] bytes){return FunctionalTransferFiles.sha256(bytes);}
    private void requireKey(String v){if(v==null||!v.matches("[0-9A-HJKMNP-TV-Z]{26}\\.[0-9A-HJKMNP-TV-Z]{26}"))throw invalid("调试记录来源无效");}
    private boolean digest(String v){return v!=null&&v.matches("[a-f0-9]{64}");}
    private boolean uuid(String v){try{return v!=null&&v.equals(UUID.fromString(v).toString());}catch(IllegalArgumentException e){return false;}}
    private ResponseStatusException invalid(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
    private ResponseStatusException missing(String m){return new ResponseStatusException(HttpStatus.NOT_FOUND,m);}
    public record Summary(String method,String protocol,String result,String target,Integer responseStatus,int durationMs,int responseSize,String createdAt){}
    public record Draft(String historySha256,String protocol,JsonNode template,List<String> notices){}
    public record Entry(String key,String revision,boolean nativeRecord,String method,String protocol,String result,String target,String createdAt){}
    public record Inventory(int version,String scope,String projectId,String actorId,String revision,List<Entry> entries){}
    public record Item(String key,String revision,String sha256,JsonNode payload){}
    public record Request(String requestKey,String expectedUserId,String expectedRevision,List<Item> items){}
    public record Result(String key,String revision,String sha256){}
    public record Receipt(String requestKey,List<Result> results){}
}
