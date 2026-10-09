package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopEngineArchiveToolTests {
    @TempDir Path directory;
    private Path archive(String entry) throws Exception {
        Path file = directory.resolve("engine.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(entry)); zip.write("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return file;
    }
    @Test void extractsIntoANewDirectoryWithUnicodeAndSpaces() throws Exception {
        Path target = directory.resolve("中文 用户");
        DesktopEngineArchiveTool.extract(archive("apache-jmeter-5.6.3/bin/test.jar"), target, "apache-jmeter-5.6.3");
        assertThat(Files.readString(target.resolve("apache-jmeter-5.6.3/bin/test.jar"))).isEqualTo("fixture");
    }
    @Test void rejectsTraversalAndWrongRoots() throws Exception {
        int index = 0;
        for (String entry : new String[]{"apache-jmeter-5.6.3/../../escape", "/absolute", "other/bin/file", "apache-jmeter-5.6.3/a\\..\\escape", "apache-jmeter-5.6.3/C:/escape"}) {
            Path zip = archive(entry), target = directory.resolve("out-" + index++);
            assertThatThrownBy(() -> DesktopEngineArchiveTool.extract(zip, target, "apache-jmeter-5.6.3")).isInstanceOf(java.io.IOException.class);
        }
        assertThat(directory.resolve("escape")).doesNotExist();
    }
    @Test void neverOverwritesAnExistingDestination() throws Exception {
        Path target = Files.createDirectory(directory.resolve("existing")), zip = archive("apache-jmeter-5.6.3/file");
        Files.writeString(target.resolve("original"), "keep");
        assertThatThrownBy(() -> DesktopEngineArchiveTool.extract(zip, target, "apache-jmeter-5.6.3")).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target.resolve("original"))).isEqualTo("keep");
    }
    @Test void rejectsTruncatedArchives() throws Exception {
        Path zip = archive("apache-jmeter-5.6.3/file");
        byte[] bytes = Files.readAllBytes(zip);
        Files.write(zip, java.util.Arrays.copyOf(bytes, bytes.length - 30));
        assertThatThrownBy(() -> DesktopEngineArchiveTool.extract(zip, directory.resolve("out"), "apache-jmeter-5.6.3")).isInstanceOf(java.io.IOException.class);
    }
}
