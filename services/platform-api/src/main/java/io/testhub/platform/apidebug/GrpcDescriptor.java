package io.testhub.platform.apidebug;

import java.time.Instant;

/** Project-scoped, validated dynamic protobuf descriptor asset. */
public record GrpcDescriptor(String id, String organizationId, String projectId, String name, String sourceFormat,
                             String descriptorHash, byte[] descriptorBytes, int sizeBytes, int serviceCount,
                             int sourceCount, String createdBy, Instant createdAt) {
    public GrpcDescriptor {
        descriptorBytes = descriptorBytes == null ? new byte[0] : descriptorBytes.clone();
    }

    @Override
    public byte[] descriptorBytes() {
        return descriptorBytes.clone();
    }
}
