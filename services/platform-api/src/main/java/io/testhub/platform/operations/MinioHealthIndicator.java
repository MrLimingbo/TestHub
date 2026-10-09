package io.testhub.platform.operations;

import io.testhub.platform.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Makes object storage part of readiness without exposing its endpoint or error details to unauthenticated probes. */
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {
    private final ObjectStorage storage;
    private final String bucket;

    public MinioHealthIndicator(ObjectStorage storage, @Value("${testhub.storage.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            boolean exists = storage.bucketExists(bucket);
            // FileObjectService creates the bucket lazily on the first write, so an empty deployment is still storage-ready.
            return Health.up().withDetail("bucket", exists ? "available" : "will-be-created-on-first-write").build();
        } catch (Exception exception) {
            return Health.down().withDetail("bucket", "unavailable").build();
        }
    }
}
