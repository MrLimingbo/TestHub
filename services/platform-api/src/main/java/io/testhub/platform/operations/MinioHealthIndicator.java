package io.testhub.platform.operations;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Makes object storage part of readiness without exposing its endpoint or error details to unauthenticated probes. */
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {
    private final MinioClient minio;
    private final String bucket;

    public MinioHealthIndicator(MinioClient minio, @Value("${testhub.storage.bucket}") String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            // FileObjectService creates the bucket lazily on the first write, so an empty deployment is still storage-ready.
            return Health.up().withDetail("bucket", exists ? "available" : "will-be-created-on-first-write").build();
        } catch (Exception exception) {
            return Health.down().withDetail("bucket", "unavailable").build();
        }
    }
}
