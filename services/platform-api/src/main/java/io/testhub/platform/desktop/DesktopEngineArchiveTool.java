package io.testhub.platform.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** Runs in a separate, bundled JVM; never starts the control plane. */
public final class DesktopEngineArchiveTool {
    private DesktopEngineArchiveTool() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("Expected archive, new destination and archive root");
        extract(Path.of(args[0]), Path.of(args[1]), args[2]);
    }

    static void extract(Path archive, Path destination, String root) throws IOException {
        if (!root.matches("[a-zA-Z0-9.-]+")) throw new IOException("Invalid archive root");
        // Refuse existing destinations, including symlinks. The caller owns and cleans this staging directory.
        Files.createDirectory(destination);
        Path base = destination.toRealPath();
        long total = 0;
        int count = 0;
        var names = new HashSet<String>();
        try (var zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (++count > 4096 || !name.startsWith(root + "/") || name.contains("\\") || name.contains(":")
                        || name.chars().anyMatch(c -> c < 32) || !names.add(name)
                        || java.util.Arrays.stream(name.split("/")).anyMatch(p -> p.equals("..") || p.equals(".")))
                    throw new IOException("Unsafe archive entry");
                Path target = base.resolve(name).normalize();
                if (!target.startsWith(base)) throw new IOException("Archive entry outside destination");
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                long size = 0;
                var crc = new CRC32();
                try (var input = zip.getInputStream(entry);
                     var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] bytes = new byte[65536];
                    int length;
                    while ((length = input.read(bytes)) != -1) {
                        size += length; total += length;
                        if (size > 100L * 1024 * 1024 || total > 512L * 1024 * 1024) throw new IOException("Archive exceeds size limit");
                        crc.update(bytes, 0, length); output.write(bytes, 0, length);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Archive entry checksum mismatch");
            }
            if (count == 0) throw new IOException("Empty archive");
        }
    }
}
