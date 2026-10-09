package io.testhub.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Per-user desktop storage. Object keys are hashed, never interpreted as filesystem paths. */
public final class FilesystemObjectStorage implements ObjectStorage {
    private final Path root;

    public FilesystemObjectStorage(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new IOException("Storage directory cannot be a symbolic link");
        Files.createDirectories(absolute);
        root = absolute.toRealPath();
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private Path bucketPath(String bucket) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Storage directory unavailable");
        }
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IOException("Invalid storage bucket");
        }
        Path path = root.resolve(bucket);
        if (Files.isSymbolicLink(path)) throw new IOException("Storage bucket cannot be a symbolic link");
        return path;
    }

    private Path objectPath(String bucket, String key) throws Exception {
        if (key == null || key.isEmpty() || key.length() > 1024) throw new IOException("Invalid storage key");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        Path target = bucketPath(bucket).resolve(hash);
        if (Files.isSymbolicLink(target)) throw new IOException("Stored object cannot be a symbolic link");
        return target;
    }

    @Override public boolean bucketExists(String bucket) throws IOException {
        return Files.isDirectory(bucketPath(bucket), LinkOption.NOFOLLOW_LINKS);
    }

    @Override public void ensureBucket(String bucket) throws IOException {
        Path directory = bucketPath(bucket);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Storage bucket unavailable");
    }

    @Override public void put(String bucket, String key, InputStream input, long size, String contentType) throws Exception {
        if (size < 0) throw new IOException("Object size is required");
        ensureBucket(bucket);
        Path target = objectPath(bucket, key);
        Path temporary = Files.createTempFile(bucketPath(bucket), ".upload-" + target.getFileName() + "-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                long remaining = size;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Object is shorter than declared size");
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                if (input.read() != -1) throw new IOException("Object exceeds declared size");
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
            // A partial write must never replace a previous object. Fail if the filesystem cannot commit atomically.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public InputStream get(String bucket, String key) throws Exception {
        return Files.newInputStream(objectPath(bucket, key), LinkOption.NOFOLLOW_LINKS);
    }

    @Override public void remove(String bucket, String key) throws Exception {
        Path target = objectPath(bucket, key);
        Files.deleteIfExists(target);
        // A killed writer cannot run finally. Its durable transfer intent identifies only this key's partial files.
        Path directory = bucketPath(bucket);
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var partials = Files.newDirectoryStream(directory, ".upload-" + target.getFileName() + "-*.tmp")) {
                for (Path partial : partials) Files.deleteIfExists(partial);
            }
        }
    }

    @Override public String label() { return "本地文件存储"; }
}
