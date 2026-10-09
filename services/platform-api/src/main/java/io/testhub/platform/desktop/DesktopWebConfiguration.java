package io.testhub.platform.desktop;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration
@Profile("desktop")
@ConditionalOnProperty(name = "testhub.desktop.frontend-directory")
public class DesktopWebConfiguration implements WebMvcConfigurer {
    private final Path directory;
    public DesktopWebConfiguration(@Value("${testhub.desktop.frontend-directory}") String directory) throws IOException {
        this.directory = Path.of(directory).toRealPath();
        if (!Files.isRegularFile(this.directory.resolve("index.html"))) throw new IOException("Desktop frontend is missing");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations(directory.toUri().toString())
                .setCachePeriod(0).resourceChain(false).addResolver(new PathResourceResolver() {
                    @Override protected Resource getResource(String path, Resource location) throws IOException {
                        if (path.isEmpty() || path.equals("/")) return super.getResource("index.html", location);
                        Resource resource = super.getResource(path, location);
                        if (resource != null) return resource;
                        if (path.startsWith("api/") || path.startsWith("internal/") || path.startsWith("actuator/")
                                || path.startsWith("assets/") || path.contains(".")) return null;
                        return super.getResource("index.html", location);
                    }
                });
    }
}
