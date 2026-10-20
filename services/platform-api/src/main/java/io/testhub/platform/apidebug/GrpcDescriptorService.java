package io.testhub.platform.apidebug;

import com.github.f4b6a3.ulid.UlidCreator;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Descriptors;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GrpcDescriptorService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final int MAX_FILES = 16;
    private static final int MAX_IMPORT_BYTES = 512 * 1024;
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    private static final int MAX_DISCOVERY_ITEMS = 500;
    private final GrpcDescriptorRepository descriptors;
    private final AccessService access;
    private final AuditService audit;

    public GrpcDescriptorService(GrpcDescriptorRepository descriptors, AccessService access, AuditService audit) {
        this.descriptors = descriptors;
        this.access = access;
        this.audit = audit;
    }

    public List<Summary> list(AuthenticatedPrincipal principal, String projectId) {
        access.requireProjectRead(principal, projectId);
        return descriptors.findByProject(projectId).stream().map(this::summary).toList();
    }

    @org.springframework.transaction.annotation.Transactional
    public Summary importFiles(AuthenticatedPrincipal principal, String projectId, String name, List<MultipartFile> files) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        String normalizedName = displayName(name);
        CompiledImport compiled = compile(files);
        descriptors.lockProject(projectId);
        GrpcDescriptor descriptor = new GrpcDescriptor(UlidCreator.getUlid().toString(), project.organizationId(), projectId, normalizedName,
                compiled.format(), sha256(compiled.bytes()), compiled.bytes(), compiled.bytes().length, compiled.services().size(),
                compiled.sourceCount(), principal.userId(), null);
        try {
            descriptors.insert(descriptor);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前项目已存在同名 gRPC 描述符");
        }
        audit.record(principal, project.organizationId(), projectId, "GRPC_DESCRIPTOR_IMPORTED", "GRPC_DESCRIPTOR", descriptor.id(),
                Map.of("name", descriptor.name(), "format", descriptor.sourceFormat(), "sizeBytes", descriptor.sizeBytes(),
                        "sourceCount", descriptor.sourceCount(), "serviceCount", descriptor.serviceCount()));
        return summary(descriptor);
    }

    public Discovery discover(AuthenticatedPrincipal principal, String projectId, String descriptorId, String serviceName) {
        ProjectSummary project = access.requireProjectRead(principal, projectId);
        GrpcDescriptor descriptor = required(projectId, descriptorId);
        List<FileDescriptorProto> files = decode(descriptor);
        try {
            if (serviceName == null || serviceName.isBlank()) {
                List<String> services = GrpcDescriptorRuntime.services(files);
                List<String> result = services.stream().limit(MAX_DISCOVERY_ITEMS).toList();
                audit.record(principal, project.organizationId(), projectId, "GRPC_DESCRIPTOR_DISCOVERED", "GRPC_DESCRIPTOR", descriptor.id(),
                        Map.of("scope", "SERVICES", "itemCount", result.size(), "truncated", services.size() > MAX_DISCOVERY_ITEMS));
                return new Discovery(result, List.of(), services.size() > MAX_DISCOVERY_ITEMS);
            }
            Descriptors.ServiceDescriptor service = GrpcDescriptorRuntime.service(files, validatedService(serviceName));
            List<GrpcDebugTransport.MethodSummary> methods = service.getMethods().stream()
                    .map(method -> new GrpcDebugTransport.MethodSummary(method.getName(), callType(method))).sorted(java.util.Comparator.comparing(GrpcDebugTransport.MethodSummary::name))
                    .toList();
            List<GrpcDebugTransport.MethodSummary> result = methods.stream().limit(MAX_DISCOVERY_ITEMS).toList();
            audit.record(principal, project.organizationId(), projectId, "GRPC_DESCRIPTOR_DISCOVERED", "GRPC_DESCRIPTOR", descriptor.id(),
                    Map.of("scope", "METHODS", "itemCount", result.size(), "truncated", methods.size() > MAX_DISCOVERY_ITEMS));
            return new Discovery(List.of(), result, methods.size() > MAX_DISCOVERY_ITEMS);
        } catch (GrpcDescriptorRuntime.InvalidDescriptorException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "离线描述符不可用：" + exception.getMessage());
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String descriptorId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, WRITERS);
        descriptors.lockProject(projectId);
        GrpcDescriptor descriptor = required(projectId, descriptorId);
        descriptors.delete(projectId, descriptorId);
        audit.record(principal, project.organizationId(), projectId, "GRPC_DESCRIPTOR_DELETED", "GRPC_DESCRIPTOR", descriptor.id(),
                Map.of("name", descriptor.name(), "format", descriptor.sourceFormat(), "sizeBytes", descriptor.sizeBytes()));
    }

    List<FileDescriptorProto> forExecution(String projectId, String descriptorId) {
        if (descriptorId == null || descriptorId.isBlank()) return List.of();
        return decode(required(projectId, descriptorId));
    }

    private CompiledImport compile(List<MultipartFile> multipart) {
        if (multipart == null || multipart.isEmpty()) throw rejected("请至少选择一个 .proto 或 FileDescriptorSet 文件");
        if (multipart.size() > MAX_FILES) throw rejected("一次最多导入 16 个 Proto 文件");
        List<ImportFile> files = new ArrayList<>();
        int total = 0;
        for (MultipartFile file : multipart) {
            if (file == null || file.isEmpty()) throw rejected("导入文件不能为空");
            String name = safeFileName(file.getOriginalFilename());
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (Exception exception) {
                throw rejected("读取导入文件失败");
            }
            total += bytes.length;
            if (total > MAX_IMPORT_BYTES) throw rejected("导入文件总大小不能超过 512 KB");
            files.add(new ImportFile(name, bytes));
        }
        boolean proto = files.stream().allMatch(file -> file.name().toLowerCase(Locale.ROOT).endsWith(".proto"));
        boolean descriptorSet = files.size() == 1 && files.stream().allMatch(file -> isDescriptorSet(file.name()));
        if (!proto && !descriptorSet) throw rejected("请仅导入一个 FileDescriptorSet（.desc/.pb）或一组 .proto 文件");
        try {
            List<FileDescriptorProto> compiled;
            String format;
            if (proto) {
                List<GrpcProtoCompiler.ProtoSource> sources = files.stream().map(file -> new GrpcProtoCompiler.ProtoSource(file.name(),
                        new String(file.bytes(), StandardCharsets.UTF_8))).toList();
                compiled = GrpcProtoCompiler.compile(sources);
                format = "PROTO3";
            } else {
                compiled = FileDescriptorSet.parseFrom(files.getFirst().bytes()).getFileList();
                format = "DESCRIPTOR_SET";
                if (compiled.isEmpty()) throw rejected("FileDescriptorSet 不包含任何 Proto 文件");
                GrpcDescriptorRuntime.build(compiled);
            }
            byte[] bytes = FileDescriptorSet.newBuilder().addAllFile(compiled).build().toByteArray();
            if (bytes.length > MAX_DESCRIPTOR_BYTES) throw rejected("编译后的描述符不能超过 1 MB");
            List<String> services = GrpcDescriptorRuntime.services(compiled);
            if (services.isEmpty()) throw rejected("描述符中未包含 gRPC service 定义");
            return new CompiledImport(format, bytes, compiled.size(), services);
        } catch (GrpcProtoCompiler.CompilationException | GrpcDescriptorRuntime.InvalidDescriptorException | InvalidProtocolBufferException exception) {
            throw rejected("Proto 导入失败：" + exception.getMessage());
        }
    }

    private List<FileDescriptorProto> decode(GrpcDescriptor descriptor) {
        try {
            List<FileDescriptorProto> files = FileDescriptorSet.parseFrom(descriptor.descriptorBytes()).getFileList();
            GrpcDescriptorRuntime.build(files);
            return files;
        } catch (InvalidProtocolBufferException | GrpcDescriptorRuntime.InvalidDescriptorException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "离线描述符不可用，请重新导入");
        }
    }

    private Summary summary(GrpcDescriptor descriptor) {
        return new Summary(descriptor.id(), descriptor.name(), descriptor.sourceFormat(), descriptor.sizeBytes(), descriptor.serviceCount(),
                descriptor.sourceCount(), descriptor.createdAt());
    }

    private GrpcDescriptor required(String projectId, String descriptorId) {
        if (descriptorId == null || !descriptorId.matches("[0-9A-HJKMNP-TV-Z]{26}")) throw rejected("gRPC 描述符标识不合法");
        return descriptors.findById(projectId, descriptorId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "gRPC 描述符不存在"));
    }

    private String displayName(String value) {
        if (value == null || value.isBlank()) throw rejected("描述符名称不能为空");
        String result = value.trim();
        if (result.length() > 128 || result.chars().anyMatch(character -> character < 0x20)) throw rejected("描述符名称不合法");
        return result;
    }

    private String safeFileName(String value) {
        String result = value == null ? "" : value.replace('\\', '/');
        result = result.substring(result.lastIndexOf('/') + 1).trim();
        if (result.isBlank() || result.length() > 160 || result.chars().anyMatch(character -> character < 0x20)) throw rejected("Proto 文件名不合法");
        return result;
    }

    private boolean isDescriptorSet(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".desc") || normalized.endsWith(".pb") || normalized.endsWith(".bin");
    }

    private String validatedService(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*") || normalized.length() > 512) {
            throw rejected("gRPC 服务名称格式不合法");
        }
        return normalized;
    }

    private String callType(Descriptors.MethodDescriptor method) {
        if (method.isClientStreaming() && method.isServerStreaming()) return "BIDI_STREAMING";
        if (method.isClientStreaming()) return "CLIENT_STREAMING";
        if (method.isServerStreaming()) return "SERVER_STREAMING";
        return "UNARY";
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Summary(String id, String name, String sourceFormat, int sizeBytes, int serviceCount, int sourceCount,
                          java.time.Instant createdAt) {
    }

    public record Discovery(List<String> services, List<GrpcDebugTransport.MethodSummary> methods, boolean truncated) {
        public Discovery {
            services = services == null ? List.of() : List.copyOf(services);
            methods = methods == null ? List.of() : List.copyOf(methods);
        }
    }

    private record ImportFile(String name, byte[] bytes) {
    }

    private record CompiledImport(String format, byte[] bytes, int sourceCount, List<String> services) {
    }
}
