package io.testhub.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemObjectStorageTests {
    @TempDir Path directory;

    @Test
    void killedWriterLeavesRecoverablePartialWithoutAffectingOtherObjects() throws Exception {
        var storage = new FilesystemObjectStorage(directory);
        storage.put("testhub-files", "retained", new ByteArrayInputStream(new byte[]{42}), 1, "text/plain");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), InterruptedUpload.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("writer.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(directory.resolve("ready")) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(Files.exists(directory.resolve("ready"))).isTrue();
            process.destroyForcibly(); assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (var files = Files.list(directory.resolve("testhub-files"))) { assertThat(files.count()).isEqualTo(2); }
            new FilesystemObjectStorage(directory).remove("testhub-files", "interrupted");
            try (var files = Files.list(directory.resolve("testhub-files"))) { assertThat(files.count()).isEqualTo(1); }
            try (var input = storage.get("testhub-files", "retained")) { assertThat(input.readAllBytes()).containsExactly((byte)42); }
        } finally { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    public static class InterruptedUpload {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            new FilesystemObjectStorage(directory).put("testhub-files", "interrupted", new java.io.InputStream() {
                boolean written;
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (!written) { written=true; bytes[offset]=7; return 1; }
                    return read();
                }
                @Override public int read() throws IOException {
                    Files.writeString(directory.resolve("ready"), "partial written");
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    return -1;
                }
            }, 2, "application/octet-stream");
        }
    }

    @Test
    void persistsBinaryContentAcrossStorageInstancesAndDeletesIdempotently() throws Exception {
        var storage = new FilesystemObjectStorage(directory);
        byte[] bytes = { 0, 1, 2, (byte) 255, 0 };
        storage.put("testhub-files", "project/file", new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
        var reopened = new FilesystemObjectStorage(directory);
        try (var input = reopened.get("testhub-files", "project/file")) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        reopened.remove("testhub-files", "project/file");
        reopened.remove("testhub-files", "project/file");
        assertThatThrownBy(() -> reopened.get("testhub-files", "project/file")).isInstanceOf(IOException.class);
    }

    @Test
    void failedOrOversizedUploadPreservesPreviousContentAndLeavesNoTemporaryFile() throws Exception {
        var storage = new FilesystemObjectStorage(directory);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        storage.put("testhub-files", "asset", new ByteArrayInputStream(original), original.length, "text/plain");
        assertThatThrownBy(() -> storage.put("testhub-files", "asset", new ByteArrayInputStream(new byte[2]), 3, "text/plain"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.put("testhub-files", "asset", new ByteArrayInputStream(new byte[4]), 3, "text/plain"))
                .isInstanceOf(IOException.class);
        try (var input = storage.get("testhub-files", "asset")) { assertThat(input.readAllBytes()).isEqualTo(original); }
        try (var files = Files.list(directory.resolve("testhub-files"))) { assertThat(files.count()).isEqualTo(1); }
    }

    @Test
    void neverInterpretsKeysAsPathsAndSeparatesBuckets() throws Exception {
        var storage = new FilesystemObjectStorage(directory);
        for (String key : new String[] { "../../outside", "C:\\Users\\file", "/absolute/file" }) {
            storage.put("bucket-one", key, new ByteArrayInputStream(new byte[] { 1 }), 1, "application/octet-stream");
        }
        storage.put("bucket-two", "../../outside", new ByteArrayInputStream(new byte[] { 2 }), 1, "application/octet-stream");
        try (var input = storage.get("bucket-one", "../../outside")) { assertThat(input.read()).isEqualTo(1); }
        try (var input = storage.get("bucket-two", "../../outside")) { assertThat(input.read()).isEqualTo(2); }
        assertThatThrownBy(() -> storage.ensureBucket("../outside")).isInstanceOf(IOException.class);
        try (var files = Files.list(directory.resolve("bucket-one"))) {
            assertThat(files.map(path -> path.getFileName().toString())).allMatch(name -> name.matches("[a-f0-9]{64}"));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void rejectsBucketAndObjectSymlinks() throws Exception {
        // Symlink creation requires an extra OS privilege on Windows; the path-key test remains portable.
        var storage = new FilesystemObjectStorage(directory.resolve("objects"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.createSymbolicLink(directory.resolve("objects/linked-bucket"), outside);
        assertThatThrownBy(() -> storage.ensureBucket("linked-bucket")).isInstanceOf(IOException.class);
        storage.put("testhub-files", "asset", new ByteArrayInputStream(new byte[] { 1 }), 1, "text/plain");
        Path object;
        try (var files = Files.list(directory.resolve("objects/testhub-files"))) { object = files.findFirst().orElseThrow(); }
        Files.delete(object);
        Path outsideFile = Files.write(outside.resolve("private"), new byte[] { 42 });
        Files.createSymbolicLink(object, outsideFile);
        assertThatThrownBy(() -> storage.get("testhub-files", "asset")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.remove("testhub-files", "asset")).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(outsideFile)).containsExactly((byte) 42);
    }
}
