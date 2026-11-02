package io.testhub.platform.shared.api;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Public probes bypass session storage. Readiness workers and their waiting time are bounded. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HealthProbeFilter extends OncePerRequestFilter {
    private static final Set<String> PATHS = Set.of("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness");
    private final ObjectProvider<HealthEndpoint> endpoint;
    private final ThreadPoolExecutor workers;

    public HealthProbeFilter(ObjectProvider<HealthEndpoint> endpoint) {
        this.endpoint = endpoint;
        AtomicInteger serial = new AtomicInteger();
        workers = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "testhub-health-probe-" + serial.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) || !PATHS.contains(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4_800);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String group = path.equals("/actuator/health") ? null : path.substring(path.lastIndexOf('/') + 1);
        String status = "DOWN";
        Future<String> pending = null;
        try {
            // Liveness contains process state only and must not queue behind failed dependency probes.
            if ("liveness".equals(group)) status = probe(group);
            else {
                pending = workers.submit(() -> probe(group));
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) status = pending.get(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (RejectedExecutionException | TimeoutException | ExecutionException ignored) {
            status = "DOWN";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
        response.setStatus(status.equals("DOWN") || status.equals("OUT_OF_SERVICE") ? 503 : 200);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (!request.getMethod().equals("HEAD")) response.getWriter().write("{\"status\":\"" + status + "\"}");
    }

    private String probe(String group) {
        var health = group == null ? endpoint.getObject().health() : endpoint.getObject().healthForPath(group);
        Status status = health == null ? Status.DOWN : health.getStatus();
        // Never expose arbitrary custom status text or dependency details on the public endpoint.
        return switch (status.getCode()) {
            case "UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN" -> status.getCode();
            default -> "DOWN";
        };
    }

    @PreDestroy void stop() { workers.shutdownNow(); }
}
