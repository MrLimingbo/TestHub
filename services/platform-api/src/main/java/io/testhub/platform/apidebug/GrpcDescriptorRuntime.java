package io.testhub.platform.apidebug;

import com.google.protobuf.AnyProto;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DurationProto;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.FieldMaskProto;
import com.google.protobuf.StructProto;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.WrappersProto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds dynamic protobuf descriptors from a validated FileDescriptorSet. */
final class GrpcDescriptorRuntime {
    private static final Map<String, Descriptors.FileDescriptor> WELL_KNOWN = Map.ofEntries(
            Map.entry("google/protobuf/any.proto", AnyProto.getDescriptor()),
            Map.entry("google/protobuf/descriptor.proto", DescriptorProtos.getDescriptor()),
            Map.entry("google/protobuf/duration.proto", DurationProto.getDescriptor()),
            Map.entry("google/protobuf/empty.proto", EmptyProto.getDescriptor()),
            Map.entry("google/protobuf/field_mask.proto", FieldMaskProto.getDescriptor()),
            Map.entry("google/protobuf/struct.proto", StructProto.getDescriptor()),
            Map.entry("google/protobuf/timestamp.proto", TimestampProto.getDescriptor()),
            Map.entry("google/protobuf/wrappers.proto", WrappersProto.getDescriptor()));

    private GrpcDescriptorRuntime() {
    }

    static List<Descriptors.FileDescriptor> build(List<FileDescriptorProto> protos) throws InvalidDescriptorException {
        if (protos == null || protos.isEmpty()) throw new InvalidDescriptorException("描述符集不能为空");
        Map<String, FileDescriptorProto> sources = new HashMap<>();
        for (FileDescriptorProto proto : protos) {
            if (proto == null || proto.getName().isBlank()) throw new InvalidDescriptorException("描述符文件名不能为空");
            if (sources.putIfAbsent(proto.getName(), proto) != null) throw new InvalidDescriptorException("描述符中包含重复文件名：" + proto.getName());
        }
        Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
        for (String name : sources.keySet()) build(name, sources, files, new HashSet<>());
        return files.values().stream().sorted(java.util.Comparator.comparing(Descriptors.FileDescriptor::getName)).toList();
    }

    static Descriptors.ServiceDescriptor service(List<FileDescriptorProto> protos, String serviceName) throws InvalidDescriptorException {
        for (Descriptors.FileDescriptor file : build(protos)) {
            for (Descriptors.ServiceDescriptor service : file.getServices()) {
                if (service.getFullName().equals(serviceName)) return service;
            }
        }
        throw new InvalidDescriptorException("描述符中未找到服务：" + serviceName);
    }

    static List<String> services(List<FileDescriptorProto> protos) throws InvalidDescriptorException {
        return build(protos).stream().flatMap(file -> file.getServices().stream()).map(Descriptors.ServiceDescriptor::getFullName).sorted().toList();
    }

    private static Descriptors.FileDescriptor build(String name, Map<String, FileDescriptorProto> sources,
                                                     Map<String, Descriptors.FileDescriptor> files, Set<String> building)
            throws InvalidDescriptorException {
        Descriptors.FileDescriptor cached = files.get(name);
        if (cached != null) return cached;
        Descriptors.FileDescriptor wellKnown = WELL_KNOWN.get(name);
        if (wellKnown != null) return wellKnown;
        FileDescriptorProto source = sources.get(name);
        if (source == null) throw new InvalidDescriptorException("未提供导入文件：" + name);
        if (!building.add(name)) throw new InvalidDescriptorException("Proto 导入存在循环依赖：" + name);
        try {
            List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
            for (String dependency : source.getDependencyList()) dependencies.add(build(dependency, sources, files, building));
            Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(source,
                    dependencies.toArray(Descriptors.FileDescriptor[]::new));
            files.put(name, descriptor);
            return descriptor;
        } catch (Descriptors.DescriptorValidationException exception) {
            throw new InvalidDescriptorException("描述符校验失败：" + exception.getMessage());
        } finally {
            building.remove(name);
        }
    }

    static final class InvalidDescriptorException extends Exception {
        InvalidDescriptorException(String message) {
            super(message);
        }
    }
}
