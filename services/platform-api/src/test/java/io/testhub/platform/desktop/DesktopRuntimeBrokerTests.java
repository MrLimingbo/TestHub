package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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
