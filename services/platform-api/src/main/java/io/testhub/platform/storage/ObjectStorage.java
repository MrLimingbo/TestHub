package io.testhub.platform.storage;

import java.io.InputStream;

/** Bytes only; project authorization, metadata and checksums remain in the application services. */
public interface ObjectStorage {
    boolean bucketExists(String bucket) throws Exception;
    void ensureBucket(String bucket) throws Exception;
    void put(String bucket, String key, InputStream input, long size, String contentType) throws Exception;
    InputStream get(String bucket, String key) throws Exception;
    void remove(String bucket, String key) throws Exception;
    String label();
}
