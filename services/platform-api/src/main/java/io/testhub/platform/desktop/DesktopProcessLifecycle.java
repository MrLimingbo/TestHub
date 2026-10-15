package io.testhub.platform.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "testhub.desktop.ready-file")
@Profile("desktop")
public class DesktopProcessLifecycle {
    private final ServletWebServerApplicationContext context;
    private final ObjectMapper json;
    private final String readyFile;
    private final String instance;
    private final boolean watchParent;
    public DesktopProcessLifecycle(ServletWebServerApplicationContext context, ObjectMapper json,
            @Value("${testhub.desktop.ready-file:}") String readyFile,
            @Value("${testhub.desktop.instance:}") String instance,
            @Value("${testhub.desktop.watch-parent:false}") boolean watchParent) {
        this.context = context; this.json = json; this.readyFile = readyFile; this.instance = instance; this.watchParent = watchParent;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws IOException {
        if (readyFile.isBlank()) return;
        Path file = Path.of(readyFile);
        if (!file.isAbsolute() || !instance.matches("[0-9a-f-]{36}")) throw new IOException("Invalid desktop process handshake");
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix"))
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        else Files.createFile(file);
        Files.writeString(file, json.writeValueAsString(Map.of("port", context.getWebServer().getPort(), "instance", instance)), StandardOpenOption.WRITE);
        if (watchParent) {
            Thread.ofPlatform().daemon().name("desktop-parent-watchdog").start(() -> {
                try { while (System.in.read() != -1) { /* The owning desktop keeps this pipe open. */ } }
                catch (IOException ignored) { /* A broken parent pipe has the same meaning as EOF. */ }
                context.close();
            });
        }
    }
}
