package io.testhub.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MinioClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ObjectStorageConfigurationTests {
    @TempDir Path directory;

    @Test
    void filesystemModeCreatesNoMinioClientAndNeedsNoMinioConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(MinioConfiguration.class)
                .withPropertyValues("testhub.storage.type=filesystem", "testhub.storage.directory=" + directory)
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class).doesNotHaveBean(MinioClient.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(FilesystemObjectStorage.class);
                });
    }

    @Test
    void enterpriseModeKeepsMinioByDefault() {
        new ApplicationContextRunner().withUserConfiguration(MinioConfiguration.class)
                .withPropertyValues("testhub.storage.endpoint=http://127.0.0.1:9000",
                        "testhub.storage.access-key=test-access", "testhub.storage.secret-key=test-secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class).hasSingleBean(MinioClient.class);
                    assertThat(context.getBean(ObjectStorage.class)).isInstanceOf(MinioObjectStorage.class);
                });
    }
}
