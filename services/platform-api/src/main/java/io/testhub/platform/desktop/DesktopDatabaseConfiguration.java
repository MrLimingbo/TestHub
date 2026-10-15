package io.testhub.platform.desktop;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Keeps the desktop SQL dialect boundary out of the shared domain services. */
@Configuration
@Profile("desktop")
public class DesktopDatabaseConfiguration {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties,
                               @Value("${testhub.desktop.data-directory}") String directory) throws IOException {
        Path data = Path.of(directory);
        validateDirectory(data);
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new IllegalArgumentException("Desktop database password must be provided by the desktop launcher");
        }
        if (Files.isSymbolicLink(data)) throw new IOException("Desktop data directory cannot be a symbolic link");
        Files.createDirectories(data);
        data = data.toRealPath();
        // A parent symlink may resolve to a path containing JDBC URL separators.
        validateDirectory(data);
        if (Files.getFileStore(data).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwx------"));
        }
        if (Files.isSymbolicLink(data.resolve("workspace.mv.db"))) {
            throw new IOException("Desktop database cannot be a symbolic link");
        }
        // The desktop profile always owns a private embedded database, regardless of enterprise DB environment variables.
        properties.setUrl("jdbc:h2:file:" + data.resolve("workspace")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        properties.setDriverClassName("org.h2.Driver");
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    private static void validateDirectory(Path directory) {
        String value = directory.toString();
        if (!directory.isAbsolute() || directory.normalize().getParent() == null
                || value.indexOf(';') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Desktop data directory must be a dedicated absolute directory without SQL URL separators");
        }
    }

    @Bean
    JdbcClient desktopJdbcClient(DataSource dataSource) {
        JdbcClient delegate = JdbcClient.create(dataSource);
        return sql -> delegate.sql(DesktopSql.translate(sql));
    }
}
