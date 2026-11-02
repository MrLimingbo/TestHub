package io.testhub.platform.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import org.junit.jupiter.api.Test;

class P1StorageProbeTests {
    @Test void readinessUsesItsBoundedClientWithoutTouchingTheFileClient() throws Exception {
        var business = mock(MinioClient.class); var probe = mock(MinioClient.class); when(probe.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        assertTrue(new MinioObjectStorage(business, probe).bucketExists("qa-bucket")); verifyNoInteractions(business);
    }
    @Test void failedProbeCannotChangeBusinessBucketInitialization() throws Exception {
        var business = mock(MinioClient.class); var probe = mock(MinioClient.class);
        when(probe.bucketExists(any(BucketExistsArgs.class))).thenThrow(new java.io.IOException("controlled-probe-failure"));
        when(business.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        var storage = new MinioObjectStorage(business, probe);
        assertThrows(java.io.IOException.class, () -> storage.bucketExists("qa-bucket"));
        assertDoesNotThrow(() -> storage.ensureBucket("qa-bucket")); verify(business).bucketExists(any(BucketExistsArgs.class));
    }
    @Test void lazyCreationAndItsCacheUseOnlyTheExistingBusinessClient() throws Exception {
        var business = mock(MinioClient.class); var probe = mock(MinioClient.class);
        when(business.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        var storage = new MinioObjectStorage(business, probe); storage.ensureBucket("qa-bucket"); storage.ensureBucket("qa-bucket");
        verify(business).bucketExists(any(BucketExistsArgs.class)); verify(business).makeBucket(any(MakeBucketArgs.class)); verifyNoInteractions(probe);
    }
    @Test void destroyingTheStorageClosesOnlyItsOwnedProbeClient() throws Exception {
        var business = mock(MinioClient.class); var probe = mock(MinioClient.class);
        new MinioObjectStorage(business, probe).close(); verify(probe).close(); verifyNoInteractions(business);
    }
}
