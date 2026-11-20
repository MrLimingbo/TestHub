package io.testhub.platform.apidebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class JdkApiDebugTransportTests {
    @Test
    void bodyStallCannotOutliveTheRequestDeadline() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            var policy = new ApiDebugTargetPolicy(Optional::<String>empty, host -> new InetAddress[0]) {
                @Override public void assertAllowed(URI uri) { /* Only this owned loopback server is used. */ }
            };
            var transport = new JdkApiDebugTransport(policy);
            long start = System.nanoTime();
            assertThrows(java.io.IOException.class, () -> transport.execute("GET",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), List.of(), "", 200));
            org.junit.jupiter.api.Assertions.assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1200);
        } finally { server.stop(0); }
    }

    @Test
    void returnsRedirectWithoutFollowingItsLocation() throws Exception {
        ApiDebugTargetPolicy policy = new ApiDebugTargetPolicy(Optional::<String>empty,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        AtomicInteger sends = new AtomicInteger();
        JdkApiDebugTransport transport = new JdkApiDebugTransport(policy, request -> {
            sends.incrementAndGet();
            return response(request, 302, Map.of("Location", List.of("http://127.0.0.1:8080/admin")));
        });

        ApiDebugTransport.TransportResponse response = transport.execute("GET", URI.create("https://8.8.8.8/start"), List.of(), "", 1_000);

        assertEquals(302, response.statusCode());
        assertEquals(1, sends.get());
    }

    @Test
    void revalidatesTheTargetImmediatelyBeforeSending() {
        ApiDebugTargetPolicy policy = new ApiDebugTargetPolicy(Optional::<String>empty,
                host -> new InetAddress[] {uncheckedAddress("8.8.8.8")});
        AtomicInteger sends = new AtomicInteger();
        JdkApiDebugTransport transport = new JdkApiDebugTransport(policy, request -> {
            sends.incrementAndGet();
            return response(request, 200, Map.of());
        });

        assertThrows(ResponseStatusException.class,
                () -> transport.execute("GET", URI.create("http://172.27.15.153:8888/private"), List.of(), "", 1_000));
        assertEquals(0, sends.get());
    }

    private static HttpResponse<InputStream> response(HttpRequest request, int status, Map<String, List<String>> headers) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (name, value) -> true); }
            @Override public InputStream body() { return new ByteArrayInputStream(new byte[0]); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return request.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static InetAddress uncheckedAddress(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
