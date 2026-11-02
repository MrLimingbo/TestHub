package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.testhub.platform.account.PlatformUserPrincipal;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Uses only the explicitly owned Redis supplied by test-mysql-backend.mjs; never stops the Redis service. */
@EnabledIfEnvironmentVariable(named = "TESTHUB_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
class P1RedisHttpFlowTests extends P1FlowTestSupport {
    static RedisProxy proxy;
    @DynamicPropertySource static void redisProxy(DynamicPropertyRegistry registry) throws IOException {
        proxy = new RedisProxy(System.getenv("TESTHUB_REDIS_HOST"), Integer.parseInt(System.getenv("TESTHUB_REDIS_PORT")));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> proxy.port());
        registry.add("spring.session.redis.namespace", () -> "p1-qa-" + proxy.port());
    }
    @AfterAll static void cleanup() throws Exception { if (proxy != null) proxy.close(); }
    @LocalServerPort int port;
    @Autowired SessionRepository<?> sessions;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String cookie(PlatformUserPrincipal actor) { return cookie(sessions, actor); }
    <S extends Session> String cookie(SessionRepository<S> repository, PlatformUserPrincipal actor) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities())));
        repository.save(session);
        return "TESTHUB_SESSION=" + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
    }
    HttpResponse<String> get(String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(6)).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    void ready(int expected, String cookie) throws Exception {
        long started = System.nanoTime(); var response = get("/actuator/health/readiness", cookie);
        assertEquals(expected, response.statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() <= 5_000, "public probe exceeded approved five-second cutoff");
        assertFalse(response.body().contains("components")); assertFalse(response.body().contains(System.getenv("TESTHUB_REDIS_PASSWORD")));
    }

    @Test void realRedisRefusalStallRecoveryAndSessionInvalidationOverHttp() throws Exception {
        var admin = admin(); String identifier = unique(), password = UUID.randomUUID() + "Aa1!";
        var target = new PlatformUserPrincipal(registration.register(identifier, identifier + "@example.test", "Redis HTTP QA", password));
        String cookie = cookie(target);
        assertEquals(200, get("/api/v1/auth/me", cookie).statusCode()); ready(200, cookie);
        try {
            proxy.mode("REJECT");
            ready(503, cookie);
            assertEquals(503, get("/actuator/health", cookie).statusCode());
            assertEquals(200, get("/actuator/health/liveness", cookie).statusCode());
            var unavailable = get("/api/v1/auth/me", cookie);
            assertEquals(503, unavailable.statusCode()); assertTrue(unavailable.body().contains("DEPENDENCY_UNAVAILABLE"));
            assertFalse(unavailable.body().contains("127.0.0.1")); assertFalse(unavailable.body().contains(System.getenv("TESTHUB_REDIS_PASSWORD")));
            assertEquals(401, get("/api/v1/auth/me", null).statusCode());
            var csrf = get("/api/v1/auth/csrf", null); assertEquals(200, csrf.statusCode());
            var csrfData = json.readTree(csrf.body()).path("data");
            String csrfCookie = csrf.headers().allValues("set-cookie").stream().map(v -> v.split(";", 2)[0]).collect(java.util.stream.Collectors.joining("; "));
            var login = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                    .timeout(Duration.ofSeconds(6)).header("Content-Type", "application/json").header("Cookie", csrfCookie)
                    .header(csrfData.path("headerName").asText(), csrfData.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("account", identifier, "password", password)))).build();
            var failedLogin = http.send(login, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, failedLogin.statusCode()); assertTrue(failedLogin.body().contains("DEPENDENCY_UNAVAILABLE"));
            proxy.mode("STALL"); ready(503, cookie);
            assertEquals(200, get("/actuator/health/liveness", cookie).statusCode());
        } finally { proxy.mode("PASS"); }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (get("/actuator/health/readiness", null).statusCode() != 200 && System.nanoTime() < until) Thread.sleep(100);
        ready(200, cookie); assertEquals(200, get("/api/v1/auth/me", cookie).statusCode());
        accounts.updatePassword(admin, target.userId(), UUID.randomUUID() + "Aa1!");
        assertEquals(401, get("/api/v1/auth/me", cookie).statusCode());
        String fresh = cookie(new PlatformUserPrincipal(users.findById(target.userId()).orElseThrow()));
        assertEquals(200, get("/api/v1/auth/me", fresh).statusCode());
    }

    /** Private TCP fault boundary: faults affect this test client's connections, not the shared owned QA Redis. */
    static final class RedisProxy implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicBoolean closed = new AtomicBoolean();
        final Semaphore capacity = new Semaphore(32);
        final String host; final int upstreamPort; volatile String mode = "PASS";
        RedisProxy(String host, int port) throws IOException {
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) throw new IllegalArgumentException("Owned loopback Redis required");
            this.host = host; upstreamPort = port;
            workers.submit(() -> {
                while (!closed.get()) try {
                    Socket incoming = listener.accept();
                    if (!capacity.tryAcquire()) { incoming.close(); continue; }
                    if (mode.equals("REJECT")) { capacity.release(); incoming.close(); continue; }
                    sockets.add(incoming); workers.submit(() -> connect(incoming));
                } catch (IOException error) { if (!closed.get()) throw new RuntimeException(error); }
            });
        }
        int port() { return listener.getLocalPort(); }
        synchronized void mode(String value) { mode = value; for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { } }
        void connect(Socket incoming) {
            try (incoming; Socket upstream = new Socket()) {
                if (mode.equals("STALL")) { while (!closed.get() && mode.equals("STALL") && !incoming.isClosed()) Thread.sleep(20); return; }
                upstream.connect(new InetSocketAddress(host, upstreamPort), 1_000); sockets.add(upstream);
                var returnPath = workers.submit(() -> copy(upstream, incoming));
                copy(incoming, upstream); upstream.close(); returnPath.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) { /* Socket close is the injected connection fault. */ }
            finally { sockets.removeIf(Socket::isClosed); capacity.release(); }
        }
        void copy(Socket input, Socket output) {
            try { input.getInputStream().transferTo(output.getOutputStream()); }
            catch (IOException ignored) { }
            finally { try { input.close(); output.close(); } catch (IOException ignored) { } }
        }
        @Override public void close() throws IOException { closed.set(true); listener.close(); mode("REJECT"); workers.shutdownNow(); }
    }
}
