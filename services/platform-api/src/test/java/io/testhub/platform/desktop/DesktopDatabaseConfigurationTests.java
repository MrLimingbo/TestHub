package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

class DesktopDatabaseConfigurationTests {
    @TempDir Path temporary;
    private final DesktopDatabaseConfiguration configuration = new DesktopDatabaseConfiguration();

    @Test
    void ownsAFileDatabaseAndIgnoresAnEnterpriseJdbcUrl() throws Exception {
        Path directory = temporary.resolve("private-workspace");
        var properties = properties();
        properties.setUrl("jdbc:mysql://127.0.0.1:1/must-not-connect");
        properties.setDriverClassName("com.mysql.cj.jdbc.Driver");
        try (var source = configuration.dataSource(properties, directory.toString()); var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            assertThat(Files.isRegularFile(directory.resolve("workspace.mv.db"))).isTrue();
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    void rejectsUnsafeLocationsAndMissingPasswordBeforeCreatingData() {
        for (String directory : new String[] { "relative-workspace", temporary.getRoot().toString(),
                temporary.resolve("workspace;INIT=RUNSCRIPT").toString(), temporary.resolve("control\ncharacter").toString() }) {
            assertThatThrownBy(() -> configuration.dataSource(properties(), directory)).isInstanceOf(IllegalArgumentException.class);
        }
        var properties = properties();
        properties.setPassword(" ");
        Path absent = temporary.resolve("missing-password");
        assertThatThrownBy(() -> configuration.dataSource(properties, absent.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(absent)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // Symlink creation requires separate privileges on Windows.
    void rejectsDatabaseSymlinksAndJdbcSeparatorsInResolvedParentPaths() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path target = Files.writeString(temporary.resolve("unrelated.txt"), "preserve");
        Files.createSymbolicLink(data.resolve("workspace.mv.db"), target);
        assertThatThrownBy(() -> configuration.dataSource(properties(), data.toString())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target)).isEqualTo("preserve");
        Path realParent = Files.createDirectory(temporary.resolve("parent;separator"));
        Path link = Files.createSymbolicLink(temporary.resolve("parent-link"), realParent);
        assertThatThrownBy(() -> configuration.dataSource(properties(), link.resolve("data").toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(realParent.resolve("data/workspace.mv.db"))).isFalse();
    }

    private static DataSourceProperties properties() {
        var properties = new DataSourceProperties();
        properties.setUsername("sa");
        properties.setPassword("isolated-configuration-test");
        return properties;
    }
}
