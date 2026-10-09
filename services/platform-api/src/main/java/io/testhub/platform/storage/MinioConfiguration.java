package io.testhub.platform.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class MinioConfiguration {
    @Bean
    @ConditionalOnProperty(name = "testhub.storage.type", havingValue = "minio", matchIfMissing = true)
    MinioClient minioClient(@Value("${testhub.storage.endpoint}") String endpoint,
                            @Value("${testhub.storage.access-key}") String accessKey,
                            @Value("${testhub.storage.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean
    @ConditionalOnProperty(name = "testhub.storage.type", havingValue = "minio", matchIfMissing = true)
    ObjectStorage minioObjectStorage(MinioClient client) {
        return new MinioObjectStorage(client);
    }

    @Bean
    @ConditionalOnProperty(name = "testhub.storage.type", havingValue = "filesystem")
    ObjectStorage filesystemObjectStorage(@Value("${testhub.storage.directory}") String directory) throws java.io.IOException {
        if (directory.isBlank() || !java.nio.file.Path.of(directory).isAbsolute()) {
            throw new IllegalArgumentException("An absolute local storage directory is required");
        }
        return new FilesystemObjectStorage(java.nio.file.Path.of(directory));
    }
}
