package io.testhub.platform.mobileautomation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.ObjectStorage;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Immutable original bytes, separately opted into from the sanitized history protocol. */
@Service
public class MobileAutomationArtifactSyncService {
    public static final int MAX_FILE = 25 * 1024 * 1024;
    public static final int MAX_BODY = 35 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final MobileAutomationHistorySyncService histories;
    private final MobileAutomationExecutionArtifactService nativeArtifacts;
    private final MobileAutomationArtifactTransferStorage transfer;
    private final MobileAutomationService projects;
    private final ObjectStorage storage;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    public MobileAutomationArtifactSyncService(AccessService access, MobileAutomationHistorySyncService histories,
            MobileAutomationExecutionArtifactService nativeArtifacts, MobileAutomationArtifactTransferStorage transfer,
            MobileAutomationService projects, ObjectStorage storage, JdbcClient jdbc, AuditService audit, ObjectMapper json) {
        this.access = access; this.histories = histories; this.nativeArtifacts = nativeArtifacts; this.transfer = transfer;
        this.projects = projects; this.storage = storage; this.jdbc = jdbc; this.audit = audit; this.json = json;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Inventory inventory(AuthenticatedPrincipal actor, String project, String expectedUser) {
        requireActor(actor, expectedUser); access.requireProjectRead(actor, project);
        var historyKeys = histories.inventory(actor, project, expectedUser).entries().stream().map(MobileAutomationHistorySyncService.Entry::key).sorted().toList();
        var entries = new ArrayList<>(nativeEntries(project)); entries.addAll(archiveEntries(project));
        entries.sort(Comparator.comparing(Entry::key));
        if (entries.size() > 2000 || entries.stream().map(Entry::key).distinct().count() != entries.size()) throw conflict("工件范围超过 2000 个或来源重复，暂停同步，不会截断复制");
        entries.forEach(this::validate);
        return new Inventory(1, "MOBILE_ARTIFACT_SYNC", project, actor.userId(), hash(encoded(List.of(historyKeys, entries))), historyKeys, entries);
    }
    private List<Entry> nativeEntries(String project) {
        return jdbc.sql("""
                SELECT a.id,a.execution_id,a.artifact_type,a.size_bytes,a.checksum,a.created_at
                FROM mobile_automation_execution_artifact a JOIN mobile_automation_execution e ON e.id=a.execution_id AND e.project_id=a.project_id
                WHERE a.project_id=:p AND e.status IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','REJECTED')
                ORDER BY a.execution_id,a.id LIMIT 2001
                """).param("p", project).query((rs,n) -> entry(project, rs.getString(2), rs.getString(1), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getTimestamp(6).toInstant(), true)).list();
    }
    private List<Entry> archiveEntries(String project) {
        return jdbc.sql("""
                SELECT source_project_id,source_execution_id,source_artifact_id,artifact_type,size_bytes,checksum,source_archived_at
                FROM mobile_artifact_archive WHERE project_id=:p ORDER BY source_project_id,source_execution_id,source_artifact_id LIMIT 2001
                """).param("p", project).query((rs,n) -> entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant(), false)).list();
    }
    private Entry entry(String sourceProject, String execution, String artifact, String type, long size, String sha, Instant archivedAt, boolean nativeRecord) {
        return new Entry(sourceProject + "." + execution + "." + artifact, artifact, type,
                "screenshot.png",
                "image/png", size, sha, archivedAt, nativeRecord);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Entry> list(AuthenticatedPrincipal actor, String project, String historyKey) {
        histories.read(actor, project, actor.userId(), historyKey);
        return inventory(actor, project, actor.userId()).entries().stream().filter(e -> historyKey(e.key()).equals(historyKey)).toList();
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Download download(AuthenticatedPrincipal actor, String project, String key) {
        requireKey(key); access.requireProjectRead(actor, project);
        histories.read(actor, project, actor.userId(), historyKey(key));
        Entry entry = inventory(actor, project, actor.userId()).entries().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow(() -> missing("原始工件不存在"));
        if (entry.nativeRecord()) {
            var download = nativeArtifacts.download(actor, project, key.substring(27,53), entry.sourceArtifactId());
            return verified(entry, download.content());
        }
        var object = jdbc.sql("SELECT bucket,object_key FROM mobile_artifact_archive WHERE project_id=:p AND source_project_id=:sp AND source_execution_id=:se AND source_artifact_id=:a")
                .param("p", project).param("sp", key.substring(0,26)).param("se", key.substring(27,53)).param("a", entry.sourceArtifactId())
                .query((rs,n) -> Map.entry(rs.getString(1),rs.getString(2))).single();
        try { return verified(entry, storage.get(object.getKey(), object.getValue())); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "原始工件读取失败，请核对文件存储；不会自动重建或覆盖", error); }
    }
    private Download verified(Entry entry, InputStream content) {
        try (content; var output = new ByteArrayOutputStream()) {
            byte[] block = new byte[64 * 1024]; int length;
            while ((length = content.read(block)) != -1) {
                if ((long)output.size() + length > entry.sizeBytes()) throw invalid("原始工件长度不符");
                output.write(block,0,length);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length != entry.sizeBytes() || !hash(bytes).equals(entry.checksum())) throw invalid("原始工件摘要不符");
            return new Download(entry,new ByteArrayInputStream(bytes));
        } catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"原始工件缺失、损坏或不可读，请核对文件存储；不会自动重建或覆盖",error); }
    }
    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("工件同步请求不能为空");
        if (body.length > MAX_BODY) throw tooLarge();
        try {
            var value = json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).<Request>readValue(body);
            if (value == null) throw invalid("工件同步请求不能为空"); return value;
        } catch (java.io.IOException error) { throw invalid("工件同步 JSON 不合法或包含不支持的字段"); }
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String project, Request request) {
        var context = access.requireProjectParameterWrite(actor, project, WRITERS);
        if (request == null) throw invalid("工件同步请求不能为空"); requireActor(actor, request.expectedUserId());
        if (!uuid(request.requestKey()) || !digest(request.expectedRevision()) || !request.rawFilesConfirmed()) throw invalid("请单独确认原始文件同步及有效请求标识");
        var incoming = request.artifact(); validate(incoming);
        if (incoming.nativeRecord() || incoming.key().startsWith(project + ".")) throw conflict("原端工件由 Runner 管理，不能通过同步改写");
        if (request.content() == null || request.content().length != incoming.sizeBytes() || !hash(request.content()).equals(incoming.checksum())) throw invalid("工件字节长度或 SHA-256 不匹配");
        byte[] payload = encoded(request); if (payload.length > MAX_BODY) throw tooLarge();
        String payloadHash = hash(payload); projects.lockProject(project);
        var prior = jdbc.sql("SELECT payload_sha256,artifact_id FROM mobile_artifact_sync_receipt WHERE project_id=:p AND requested_by=:u AND request_key=:k")
                .param("p", project).param("u", actor.userId()).param("k", request.requestKey()).query((rs,n) -> Map.entry(rs.getString(1),rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(payloadHash)) throw conflict("该请求标识已用于其他工件");
            if (jdbc.sql("SELECT COUNT(*) FROM mobile_artifact_archive WHERE project_id=:p AND id=:id").param("p",project).param("id",prior.get().getValue()).query(Integer.class).single() != 1)
                throw conflict("已确认的工件记录缺失，请先人工核对；不会自动重建");
            return new Receipt(request.requestKey(), incoming.key(), incoming.sourceArtifactId(), incoming.checksum());
        }
        var before = inventory(actor, project, actor.userId());
        if (!before.revision().equals(request.expectedRevision())) throw conflict("目标工件或执行历史已变化，请重新同步");
        if (!before.historyKeys().contains(historyKey(incoming.key()))) throw conflict("目标尚无对应的已结束执行历史，请先同步 移动端历史");
        if (before.entries().size() >= 2000) throw conflict("目标工件已达 2000 个，现有文件保留");
        if (before.entries().stream().anyMatch(e -> e.key().equals(incoming.key()))) throw conflict("同来源原始工件已存在，不会覆盖");
        String id = transfer.copy(actor, context.organizationId(), project, incoming, request.content());
        jdbc.sql("INSERT INTO mobile_artifact_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,artifact_id) VALUES(:id,:o,:p,:u,:k,:h,:a)")
                .param("id",UlidCreator.getUlid().toString()).param("o",context.organizationId()).param("p",project).param("u",actor.userId())
                .param("k",request.requestKey()).param("h",payloadHash).param("a",id).update();
        audit.record(actor, context.organizationId(), project, "MOBILE_ARTIFACT_SYNCED", "MOBILE_ARTIFACT", id,
                Map.of("artifactType", incoming.artifactType(), "sizeBytes", incoming.sizeBytes(), "checksum", incoming.checksum()));
        return new Receipt(request.requestKey(),incoming.key(),incoming.sourceArtifactId(),incoming.checksum());
    }
    private void validate(Entry value) {
        if (value == null) throw invalid("工件元数据不能为空"); requireKey(value.key());
        String type = value.artifactType();
        if (!id(value.sourceArtifactId()) || !value.key().substring(54).equals(value.sourceArtifactId())
                || !"SCREENSHOT".equals(type) || !"screenshot.png".equals(value.originalName()) || !"image/png".equals(value.contentType())
                || value.sizeBytes()<0 || value.sizeBytes()>MAX_FILE || !digest(value.checksum()) || value.archivedAt()==null
                ||value.archivedAt().isBefore(Instant.ofEpochSecond(1))||value.archivedAt().isAfter(Instant.ofEpochSecond(2147483647,499000000))
                ||value.archivedAt().getNano()%1000000!=0)throw invalid("工件来源、类型、大小、时间或摘要不合法");
    }
    private void requireKey(String key) { if(key==null||!key.matches("[A-Z0-9]{26}\\.[A-Z0-9]{26}\\.[A-Z0-9]{26}"))throw invalid("工件来源标识不合法"); }

    private String historyKey(String key) { return key.substring(0,53); }
    private boolean id(String value) { return value != null && value.matches("[A-Z0-9]{26}"); }
    private boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private boolean uuid(String value) { try { return value != null && value.equals(UUID.fromString(value).toString()); } catch (IllegalArgumentException error) { return false; } }
    private void requireActor(AuthenticatedPrincipal actor, String expected) { if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，工件同步已停止"); }
    public byte[] encoded(Object value) { try { return json.writeValueAsBytes(value); } catch (java.io.IOException error) { throw new IllegalStateException("工件元数据无法序列化",error); } }
    public String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    private ResponseStatusException invalid(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,value); }
    private ResponseStatusException conflict(String value) { return new ResponseStatusException(HttpStatus.CONFLICT,value); }
    private ResponseStatusException missing(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND,value); }
    private ResponseStatusException tooLarge() { return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"原始工件同步请求不能超过 35 MiB"); }
    public record Entry(String key, String sourceArtifactId, String artifactType, String originalName, String contentType, long sizeBytes, String checksum, Instant archivedAt, boolean nativeRecord) { }
    public record Inventory(int version, String scope, String projectId, String actorId, String revision, List<String> historyKeys, List<Entry> entries) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, boolean rawFilesConfirmed, Entry artifact, byte[] content) { }
    public record Receipt(String requestKey, String key, String sourceArtifactId, String checksum) { }
    public record Download(Entry artifact, InputStream content) { }
}
