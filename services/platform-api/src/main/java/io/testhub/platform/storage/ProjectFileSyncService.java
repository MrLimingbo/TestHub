package io.testhub.platform.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/** Files are immutable. Their origin key and a durable request receipt make a lost response safe to retry. */
@Service
public class ProjectFileSyncService {
    public static final int MAX_BODY = 29 * 1024 * 1024;
    public static final int MAX_FILE = 20 * 1024 * 1024;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final AccessService access;
    private final FileObjectService files;
    private final FileTransferStorage transfer;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProjectFileSyncService(AccessService access, FileObjectService files, FileTransferStorage transfer,
                                  AuditService audit, JdbcClient jdbc, ObjectMapper json) {
        this.access = access; this.files = files; this.transfer = transfer; this.audit = audit; this.jdbc = jdbc; this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(AuthenticatedPrincipal actor, String projectId, String expectedUserId) {
        access.requireProjectParameterWrite(actor, projectId, WRITERS);
        requireActor(actor, expectedUserId);
        return read(actor, projectId);
    }

    private Snapshot read(AuthenticatedPrincipal actor, String projectId) {
        var metadata = files.list(actor, projectId);
        if (metadata.size() > 1000) throw invalid("项目文件超过 1000 个同步范围，请先整理文件。");
        var rows = jdbc.sql("SELECT id, sync_key, content_sha256 FROM file_object WHERE project_id=:p ORDER BY id")
                .param("p", projectId).query().listOfRows();
        var items = new ArrayList<Item>();
        for (var file : metadata) {
            if (file.sizeBytes() < 1 || file.sizeBytes() > MAX_FILE)
                throw invalid("项目文件超过单文件 20 MiB 同步范围：" + file.originalName());
            var row = rows.stream().filter(value -> file.id().equals(value.get("id"))).findFirst()
                    .orElseThrow(() -> conflict("项目文件在读取期间已变化，请重新同步。"));
            String digest = (String) row.get("content_sha256");
            if (digest == null) {
                digest = digestFile(actor, projectId, file);
                int updated = jdbc.sql("UPDATE file_object SET content_sha256=:h WHERE id=:id AND project_id=:p AND content_sha256 IS NULL")
                        .param("h", digest).param("id", file.id()).param("p", projectId).update();
                if (updated != 1) throw conflict("项目文件在校验期间已变化，请重新同步。");
            }
            String origin = row.get("sync_key") == null ? file.id() : (String) row.get("sync_key");
            items.add(new Item(file.id(), origin, file.originalName(), file.contentType(), file.sizeBytes(), digest));
        }
        items.sort(Comparator.comparing(Item::id));
        if (items.stream().map(Item::key).distinct().count() != items.size()) throw conflict("项目文件来源标识重复，请先核对。");
        var revision = hash(encoded(items));
        return new Snapshot(1, "PROJECT_FILE_SYNC", projectId, actor.userId(), revision, List.copyOf(items));
    }

    private String digestFile(AuthenticatedPrincipal actor, String projectId, FileObject file) {
        try (var input = files.download(actor, projectId, file.id())) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[64 * 1024]; long count = 0; int length;
            while ((length = input.read(block)) != -1) {
                count += length;
                if (count > file.sizeBytes()) throw conflict("项目文件内容与记录大小不一致：" + file.originalName());
                digest.update(block, 0, length);
            }
            if (count != file.sizeBytes()) throw conflict("项目文件内容与记录大小不一致：" + file.originalName());
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "项目文件读取失败，请检查文件存储。", error);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public Request decode(byte[] body) {
        if (body == null || body.length == 0) throw invalid("文件同步请求不能为空。");
        if (body.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件同步请求不能超过 29 MiB。");
        try { return json.readerFor(Request.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body); }
        catch (IOException error) { throw invalid("文件同步请求格式无效。"); }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt apply(AuthenticatedPrincipal actor, String projectId, Request request) {
        var project = access.requireProjectParameterWrite(actor, projectId, WRITERS);
        if (request == null) throw invalid("文件同步请求不能为空。");
        requireActor(actor, request.expectedUserId());
        String requestKey = uuid(request.requestKey());
        if (!id(request.sourceKey()) || !hashValue(request.sha256()) || request.expectedRevision() == null
                || !hashValue(request.expectedRevision()) || request.content() == null
                || request.content().length < 1 || request.content().length > MAX_FILE
                || request.size() != request.content().length || !hash(request.content()).equals(request.sha256()))
            throw invalid("文件大小、来源标识或 SHA-256 校验失败。");
        if (request.name() == null || request.type() == null) throw invalid("文件名称或类型无效。");
        byte[] payload = encoded(request);
        if (payload.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件同步请求不能超过 29 MiB。");
        String payloadHash = hash(payload);
        jdbc.sql("SELECT id FROM project WHERE id=:p FOR UPDATE").param("p", projectId).query(String.class).single();
        var prior = jdbc.sql("SELECT payload_sha256,file_id FROM project_file_sync_receipt WHERE project_id=:p AND requested_by=:a AND request_key=:k")
                .param("p", projectId).param("a", actor.userId()).param("k", requestKey)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).optional();
        if (prior.isPresent()) {
            if (!prior.get().getKey().equals(payloadHash)) throw conflict("同步请求标识已用于其他文件。");
            return new Receipt(requestKey, request.sourceKey(), prior.get().getValue());
        }
        var current = read(actor, projectId);
        if (!current.revision().equals(request.expectedRevision())) throw conflict("目标项目文件已变化，请重新同步。");
        if (current.items().stream().anyMatch(item -> item.key().equals(request.sourceKey())))
            throw conflict("目标项目已有相同来源的文件，请核对对应关系。");
        var copied = transfer.copy(actor, projectId, request.name(), request.type(), request.content());
        jdbc.sql("UPDATE file_object SET sync_key=:key, content_sha256=:hash WHERE id=:id AND project_id=:p")
                .param("key", request.sourceKey()).param("hash", request.sha256())
                .param("id", copied.id()).param("p", projectId).update();
        jdbc.sql("INSERT INTO project_file_sync_receipt(id,organization_id,project_id,requested_by,request_key,payload_sha256,file_id) VALUES(:id,:o,:p,:a,:k,:h,:f)")
                .param("id", UlidCreator.getUlid().toString()).param("o", project.organizationId())
                .param("p", projectId).param("a", actor.userId()).param("k", requestKey)
                .param("h", payloadHash).param("f", copied.id()).update();
        audit.record(actor, project.organizationId(), projectId, "PROJECT_FILE_SYNCED", "FILE_OBJECT", copied.id(),
                Map.of("name", request.name(), "sizeBytes", request.size()));
        return new Receipt(requestKey, request.sourceKey(), copied.id());
    }

    private byte[] encoded(Object value) {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value); }
        catch (IOException error) { throw new IllegalStateException("文件同步序列化失败。", error); }
    }
    private static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Z0-9]{26}"); }
    private static boolean hashValue(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException error) { throw invalid("同步请求标识必须是 UUID。"); }
    }
    private void requireActor(AuthenticatedPrincipal actor, String expected) {
        if (!actor.userId().equals(expected)) throw conflict("登录账号已变化，文件同步已停止。");
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Snapshot(int version, String scope, String projectId, String actorId, String revision, List<Item> items) { }
    public record Item(String id, String key, String name, String type, long size, String sha256) { }
    public record Request(String requestKey, String expectedUserId, String expectedRevision, String sourceKey,
                          String name, String type, long size, String sha256, byte[] content) { }
    public record Receipt(String requestKey, String sourceKey, String fileId) { }
}
