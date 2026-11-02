package io.testhub.platform.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MinioObjectStorage implements ObjectStorage, AutoCloseable {
    private final MinioClient client;
    private final MinioClient probeClient;
    private final Set<String> ready = ConcurrentHashMap.newKeySet();

    public MinioObjectStorage(MinioClient client) { this(client, client); }
    public MinioObjectStorage(MinioClient client, MinioClient probeClient) { this.client = client; this.probeClient = probeClient; }

    @Override public boolean bucketExists(String bucket) throws Exception {
        return probeClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
    }

    @Override public synchronized void ensureBucket(String bucket) throws Exception {
        if (ready.contains(bucket)) return;
        // Business initialization retains its original client/timeouts; probe failures must not change file-operation behavior.
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        ready.add(bucket);
    }

    @Override public void put(String bucket, String key, InputStream input, long size, String contentType) throws Exception {
        ensureBucket(bucket);
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input, size, -1)
                .contentType(contentType).build());
    }

    @Override public InputStream get(String bucket, String key) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Override public void remove(String bucket, String key) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Override public String label() { return "MinIO 对象存储"; }
    @Override public void close() throws Exception { if (probeClient != client) probeClient.close(); }
}
