package io.testhub.platform.apidebug;

import com.github.f4b6a3.ulid.UlidCreator;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.functional.FunctionalTransferFiles;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Application boundary for transferring validated compiled descriptors, never source scripts. */
@Service
public class GrpcDescriptorTransferService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final GrpcDescriptorRepository repository;
    private final AccessService access;
    private final AuditService audit;
    public GrpcDescriptorTransferService(GrpcDescriptorRepository repository, AccessService access, AuditService audit) {
        this.repository = repository; this.access = access; this.audit = audit;
    }
    public record Item(String id, String name, String sha256, byte[] content) {
        public Item { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
    public List<Item> export(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        var items = repository.findByProject(projectId).stream()
                .map(d -> new Item(d.id(), d.name(), d.descriptorHash(), d.descriptorBytes())).toList();
        validate(items, new HashSet<>()); return items;
    }
    public void validate(List<Item> items, Set<String> ids) {
        if (items == null || items.size() > 20) throw invalid("一次最多迁移 20 个 gRPC 描述符。");
        long total = 0; Set<String> names = new HashSet<>();
        for (var item : items) {
            if (item == null || item.id() == null || !item.id().matches("[0-9A-HJKMNP-TV-Z]{26}") || !ids.add(item.id())) throw invalid("描述符 ID 无效或重复。");
            if (item.name() == null || item.name().isBlank() || item.name().trim().length() > 128
                    || item.name().chars().anyMatch(c -> c < 32) || !names.add(item.name().trim())) throw invalid("描述符名称无效或重复。");
            if (item.content == null || item.content.length == 0 || item.content.length > 1024 * 1024
                    || (total += item.content.length) > 4 * 1024 * 1024
                    || !FunctionalTransferFiles.sha256(item.content).equals(item.sha256())) throw invalid("描述符大小或 SHA-256 校验失败（单个 1 MiB、总计 4 MiB）。");
            compiled(item.content);
        }
    }
    private FileDescriptorSet compiled(byte[] bytes) {
        try {
            var set = FileDescriptorSet.parseFrom(bytes);
            if (set.getFileCount() == 0 || set.getFileCount() > 500) throw invalid("描述符文件数量无效。");
            GrpcDescriptorRuntime.build(set.getFileList());
            if (GrpcDescriptorRuntime.services(set.getFileList()).isEmpty()) throw invalid("描述符没有 gRPC service。");
            return set;
        } catch (com.google.protobuf.InvalidProtocolBufferException | GrpcDescriptorRuntime.InvalidDescriptorException error) {
            throw invalid("gRPC 描述符不可解析或存在无效依赖。");
        }
    }
    @org.springframework.transaction.annotation.Transactional
    public Map<String, String> transfer(AuthenticatedPrincipal principal, String projectId, List<Item> items) {
        var project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        validate(items, new HashSet<>());
        Map<String, String> ids = new HashMap<>();
        for (var item : items) {
            var set = compiled(item.content); String id = UlidCreator.getUlid().toString();
            repository.insert(new GrpcDescriptor(id, project.organizationId(), projectId, item.name().trim(), "DESCRIPTOR_SET",
                    item.sha256(), item.content, item.content.length, set.getFileList().stream().mapToInt(file -> file.getServiceCount()).sum(),
                    set.getFileCount(), principal.userId(), null));
            ids.put(item.id(), id);
            audit.record(principal, project.organizationId(), projectId, "GRPC_DESCRIPTOR_TRANSFERRED", "GRPC_DESCRIPTOR", id,
                    Map.of("sizeBytes", item.content.length));
        }
        return ids;
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
