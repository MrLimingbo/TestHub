package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.apiautomation.ApiRunnerInternalAuthenticationService;
import io.testhub.platform.apiautomation.ApiRunnerStatusService;
import io.testhub.platform.shared.runtime.ExpiringStateStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;

class DesktopRuntimeBrokerTests {
    @TempDir Path directory;
    private DriverManagerDataSource source;
    private DesktopRuntimeBroker broker;
    private final MutableClock clock = new MutableClock();
    @BeforeEach void initialize() {
        source = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("queue"), "sa", "broker-test-only");
        new ResourceDatabasePopulator(new ClassPathResource("db/desktop/V3__local_runner_broker.sql")).execute(source);
        broker = reopen();
    }
    @Test void keepsPreStartupBacklogAndPendingAcknowledgementsAcrossRestarts() {
        String first = broker.append("tasks", Map.of("taskId", "first"));
        broker.createGroup("tasks", ReadOffset.latest(), "runners");
        assertThatThrownBy(() -> broker.createGroup("tasks", ReadOffset.latest(), "runners")).hasMessageContaining("BUSYGROUP");
        var batch = broker.readGroup("tasks", "runners", "one", 20, ">");
        assertThat(batch).hasSize(1);
        broker = reopen();
        assertThat(broker.readGroup("tasks", "runners", "one", 20, ">")).isEmpty();
        assertThat(broker.readGroup("tasks", "runners", "one", 20, "0")).hasSize(1);
        assertThat(broker.readGroup("tasks", "runners", "two", 20, "0")).isEmpty();
        assertThat(broker.acknowledge("tasks", "runners", "two", List.of(first))).isZero();
        assertThat(broker.acknowledge("tasks", "runners", "one", List.of(first))).isEqualTo(1);
        assertThat(reopen().readGroup("tasks", "runners", "one", 20, "0")).isEmpty();
        assertThat(broker.append("tasks", Map.of("taskId", "next"))).isEqualTo("2-0");
    }
    @Test void serializesConcurrentWritersAndKeepsIndependentGroupCursors() throws Exception {
        try (var keeper = source.getConnection(); var threads = Executors.newFixedThreadPool(8)) {
            var writes = IntStream.range(0, 32).mapToObj(i -> (Callable<String>) () -> broker.append("events", Map.of("i", "" + i))).toList();
            var ids = new HashSet<String>();
            for (var future : threads.invokeAll(writes)) ids.add(future.get());
            assertThat(ids).hasSize(32);
            for (String group : List.of("a", "b")) {
                broker.createGroup("events", ReadOffset.from("0-0"), group);
                assertThat(broker.readGroup("events", group, "platform", 100, ">")).hasSize(32);
            }
        }
    }
    @Test void recoversBackendPendingAndRetriesOnlyAfterIdleWindow() {
        broker.createGroup("events", ReadOffset.latest(), "platform");
        broker.append("events", Map.of("event", "done"));
        var consumer = Consumer.from("platform", "api");
        var options = StreamReadOptions.empty().count(20);
        var stream = StreamOffset.create("events", ReadOffset.lastConsumed());
        assertThat(broker.read(consumer, options, stream)).hasSize(1);
        assertThat(broker.read(consumer, options, stream)).isEmpty();
        broker = reopen();
        assertThat(broker.read(consumer, options, stream)).hasSize(1);
        clock.now += 30000;
        var retried = broker.read(consumer, options, stream);
        assertThat(retried).hasSize(1);
        broker.acknowledge("platform", retried.getFirst());
        assertThat(reopen().read(consumer, options, stream)).isEmpty();
    }
    @Test void expiredRunnerMayBeReclaimedButLiveRunnerCannotAndOldOwnerCannotAck() {
        broker.createGroup("tasks", ReadOffset.latest(), "runners");
        String id = broker.append("tasks", Map.of("taskId", "one"));
        broker.readGroup("tasks", "runners", "one", 1, ">");
        broker.publish("members", "heartbeat:one", "one", "signed-body", Duration.ofSeconds(60));
        clock.now += 30000;
        assertThat(broker.claim("tasks", "runners", "two", 30000, 20, true)).isEmpty();
        clock.now += 30000;
        assertThat(broker.claim("tasks", "runners", "two", 30000, 20, true)).hasSize(1);
        assertThat(broker.acknowledge("tasks", "runners", "one", List.of(id))).isZero();
        assertThat(broker.acknowledge("tasks", "runners", "two", List.of(id))).isEqualTo(1);
    }
    @Test void heartbeatExpiryAndRemovalSurviveReopen() {
        broker.publish("members", "heartbeat:one", "one", "original", Duration.ofSeconds(60));
        broker = reopen();
        assertThat(broker.members("members")).containsExactly("one");
        assertThat(broker.get("heartbeat:one")).isEqualTo("original");
        clock.now += 60000;
        assertThat(broker.members("members")).isEmpty();
        assertThat(broker.get("heartbeat:one")).isNull();
        broker.publish("members", "heartbeat:one", "one", "renewed", Duration.ofSeconds(60));
        broker.delete("heartbeat:one");
        assertThat(reopen().members("members")).isEmpty();
        broker.cleanup();
        broker.check();
    }
    @Test void readinessMissingReadCannotEraseANewHeartbeatBeforeSignedCapabilityDiscovery() throws Exception {
        String signingKey = UUID.randomUUID() + UUID.randomUUID().toString();
        for (boolean expired : List.of(false, true)) {
            String runner = "desktop-api-" + expired;
            String members = "testhub:runner:api:members:v1", heartbeat = "testhub:runner:api:heartbeat:v1:" + runner;
            Instant started = Instant.now();
            String timestamp = started.toString();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runnerId", runner); body.put("runnerType", "api"); body.put("protocolVersion", "1");
            body.put("runnerVersion", "0.1.0-test"); body.put("executionEnabled", false);
            body.put("startedAt", timestamp); body.put("heartbeatAt", timestamp); body.put("trustVersion", "1");
            body.put("signature", sign(signingKey, String.join("\n", runner, "api", "1", "0.1.0-test", "false", timestamp, timestamp)));
            String encoded = new ObjectMapper().writeValueAsString(body);
            if (expired) {
                broker.publish(members, heartbeat, runner, "expired", Duration.ofMillis(1));
                clock.now++;
            }
            var observed = spy(broker);
            var publishOnce = new AtomicBoolean(true);
            doAnswer(call -> {
                String value = (String) call.callRealMethod();
                if (value == null && publishOnce.getAndSet(false)) broker.publish(members, heartbeat, runner, encoded, Duration.ofSeconds(60));
                return value;
            }).when(observed).get(heartbeat);
            var runners = new ApiRunnerStatusService(null, observed, new ObjectMapper(), signingKey);
            var state = mock(ExpiringStateStore.class);
            when(state.putIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
            var authentication = new ApiRunnerInternalAuthenticationService(runners, state, signingKey);
            // Deterministic ordering: the poll reads no heartbeat, then the Runner publishes before stale cleanup.
            assertThat(runners.isVerifiedSince(runner, started)).isFalse();
            String nonce = UUID.randomUUID().toString(), path = "/internal/api-runner/v1/capabilities";
            var request = new MockHttpServletRequest("GET", path);
            request.addHeader("X-TestHub-Runner-Id", runner);
            request.addHeader("X-TestHub-Runner-Timestamp", timestamp);
            request.addHeader("X-TestHub-Runner-Nonce", nonce);
            request.addHeader("X-TestHub-Runner-Signature", sign(signingKey, String.join("\n", "testhub.api-runner.internal-auth.v1", "GET", path, runner, timestamp, nonce)));
            assertThat(authentication.authenticateCapabilities(request)).isEqualTo(runner);
            assertThat(runners.isVerifiedSince(runner, started)).isTrue();
            assertThat(broker.get(heartbeat)).isEqualTo(encoded);
            assertThat(broker.members(members)).contains(runner);
        }
    }
    private static String sign(String key, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void retentionNeverDeletesPendingOrUnreadMessagesIncludingLateStartingGroups() {
        broker.createGroup("events", ReadOffset.latest(), "a");
        broker.createGroup("events", ReadOffset.latest(), "b");
        for (int i = 0; i < 3; i++) broker.append("events", Map.of("i", "" + i));
        broker.readGroup("events", "a", "one", 2, ">");
        broker.acknowledge("events", "a", "one", List.of("2-0"));
        broker.append("tasks-without-runner", Map.of("task", "preserve"));
        broker.append("testhub:runner:api:dead-letter:v1", Map.of("error", "expired diagnostic"));
        var jdbc = JdbcClient.create(source);
        // Simulate a full diagnostic tail without inserting 50,000 irrelevant rows.
        jdbc.sql("UPDATE desktop_stream SET last_id=60000").update();
        clock.now += 60001;
        broker.cleanup();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_stream_message").query(Integer.class).single()).isEqualTo(4);
        broker.readGroup("events", "b", "two", 3, ">");
        broker.acknowledge("events", "b", "two", List.of("1-0", "2-0", "3-0"));
        broker.cleanup();
        assertThat(jdbc.sql("SELECT message_id FROM desktop_stream_message WHERE stream_key='events' ORDER BY message_id").query(Long.class).list())
                .containsExactly(1L, 3L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM desktop_stream_message WHERE stream_key='tasks-without-runner'").query(Integer.class).single()).isEqualTo(1);
    }
    private DesktopRuntimeBroker reopen() { return new DesktopRuntimeBroker(source, new ObjectMapper(), clock); }
    private static class MutableClock extends Clock {
        long now = 1800000000000L;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
