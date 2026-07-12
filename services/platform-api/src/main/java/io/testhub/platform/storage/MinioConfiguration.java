package io.testhub.platform.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfiguration {
    @Bean
    MinioClient minioClient(@Value("${testhub.storage.endpoint}") String endpoint,
                            @Value("${testhub.storage.access-key}") String accessKey,
                            @Value("${testhub.storage.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
