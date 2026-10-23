package io.testhub.platform.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.testhub.platform.shared.runtime.RunnerRegistry;
import io.testhub.platform.shared.runtime.RuntimeStreams;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** File-backed, single-owner transport. Payload and acknowledgement commits are independent of business transactions. */
@Component
@Profile("desktop")
public class DesktopRuntimeBroker implements RuntimeStreams, RunnerRegistry {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Clock clock;
    private final Set<String> startedConsumers = new HashSet<>();

    @Autowired
    public DesktopRuntimeBroker(HikariDataSource source, ObjectMapper json) {
        this(new DriverManagerDataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword()), json, Clock.systemUTC());
    }

    DesktopRuntimeBroker(DataSource source, ObjectMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.clock = clock;
    }

    @Override public RecordId add(MapRecord<String, ?, ?> record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> fields.put(key.toString(), value.toString()));
        return RecordId.of(append(record.getStream(), fields));
    }

    public synchronized String append(String stream, Map<String, String> fields) {
        String payload;
        try { payload = json.writeValueAsString(fields); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid stream fields", exception); }
        return transaction.execute(status -> {
            ensureStream(stream);
            long next = Math.addExact(jdbc.sql("SELECT last_id FROM desktop_stream WHERE stream_key=?")
                    .param(stream).query(Long.class).single(), 1);
            jdbc.sql("INSERT INTO desktop_stream_message(stream_key,message_id,payload,created_at) VALUES(?,?,?,?)")
                    .params(stream, next, payload, clock.millis()).update();
            jdbc.sql("UPDATE desktop_stream SET last_id=? WHERE stream_key=?").params(next, stream).update();
            return next + "-0";
        });
    }

    @Override public synchronized String createGroup(String stream, ReadOffset offset, String group) {
        return transaction.execute(status -> {
            ensureStream(stream);
            if (jdbc.sql("SELECT COUNT(*) FROM desktop_stream_group WHERE stream_key=? AND group_name=?")
                    .params(stream, group).query(Integer.class).single() != 0) throw new IllegalStateException("BUSYGROUP Consumer group already exists");
            // A desktop runner may start after tasks were queued. New local groups always include that backlog.
            jdbc.sql("INSERT INTO desktop_stream_group(stream_key,group_name,last_id) VALUES(?,?,0)").params(stream, group).update();
            return "OK";
        });
    }

    @Override public synchronized List<MapRecord<String, Object, Object>> read(Consumer consumer, StreamReadOptions options, StreamOffset<String> stream) {
        String key = stream.getKey();
        int count = options.getCount() == null ? 20 : Math.toIntExact(options.getCount());
        String identity = key + "\n" + consumer.getGroup() + "\n" + consumer.getName();
        // Backend consumers run synchronously in the single owning JVM. Recover their old pending batch on restart.
        long idle = startedConsumers.contains(identity) ? 30_000 : 0;
        List<MapRecord<String, Object, Object>> recovered = claim(key, consumer.getGroup(), consumer.getName(), idle, count, false);
        startedConsumers.add(identity);
        return recovered.isEmpty() ? readGroup(key, consumer.getGroup(), consumer.getName(), count, stream.getOffset().getOffset()) : recovered;
    }

    public synchronized List<MapRecord<String, Object, Object>> readGroup(String stream, String group, String consumer, int count, String offset) {
        requireCount(count);
        return transaction.execute(status -> {
            long cursor = groupCursor(stream, group);
            if (!">".equals(offset)) {
                long after = numericId(offset);
                return messages("SELECT m.message_id,m.payload FROM desktop_stream_message m JOIN desktop_stream_pending p ON m.stream_key=p.stream_key AND m.message_id=p.message_id WHERE p.stream_key=? AND p.group_name=? AND p.consumer_name=? AND p.message_id>? ORDER BY p.message_id LIMIT ?",
                        stream, stream, group, consumer, after, count);
            }
            var result = messages("SELECT message_id,payload FROM desktop_stream_message WHERE stream_key=? AND message_id>? ORDER BY message_id LIMIT ?", stream, stream, cursor, count);
            for (var message : result) {
                long id = numericId(message.getId().getValue());
                jdbc.sql("INSERT INTO desktop_stream_pending(stream_key,group_name,message_id,consumer_name,delivered_at) VALUES(?,?,?,?,?)")
                        .params(stream, group, id, consumer, clock.millis()).update();
                cursor = id;
            }
            if (!result.isEmpty()) jdbc.sql("UPDATE desktop_stream_group SET last_id=? WHERE stream_key=? AND group_name=?")
                    .params(cursor, stream, group).update();
            return result;
        });
    }

    /** Claim idle pending records; external runners may only reclaim from owners with no live heartbeat. */
    public synchronized List<MapRecord<String, Object, Object>> claim(String stream, String group, String consumer, long idleMillis, int count, boolean protectLiveOwners) {
        requireCount(count);
        if (idleMillis < 0) throw new IllegalArgumentException("Invalid idle time");
        return transaction.execute(status -> {
            groupCursor(stream, group);
            String ownerGuard = protectLiveOwners
                    ? " AND NOT EXISTS (SELECT 1 FROM desktop_runner_heartbeat h WHERE h.member_name=p.consumer_name AND h.expires_at>?)" : "";
            List<Object> parameters = new ArrayList<>(List.of(stream, group, clock.millis() - idleMillis));
            if (protectLiveOwners) parameters.add(clock.millis());
            parameters.add(count);
            var result = messages("SELECT m.message_id,m.payload FROM desktop_stream_message m JOIN desktop_stream_pending p ON m.stream_key=p.stream_key AND m.message_id=p.message_id WHERE p.stream_key=? AND p.group_name=? AND p.delivered_at<=?" + ownerGuard + " ORDER BY p.message_id LIMIT ?", stream, parameters.toArray());
            for (var message : result) jdbc.sql("UPDATE desktop_stream_pending SET consumer_name=?,delivered_at=? WHERE stream_key=? AND group_name=? AND message_id=?")
                    .params(consumer, clock.millis(), stream, group, numericId(message.getId().getValue())).update();
            return result;
        });
    }

    @Override public Long acknowledge(String group, MapRecord<String, ?, ?> record) {
        return acknowledge(record.getStream(), group, null, List.of(record.getId().getValue()));
    }

    public synchronized long acknowledge(String stream, String group, String consumer, List<String> ids) {
        return Objects.requireNonNull(transaction.execute(status -> {
            long removed = 0;
            for (String id : ids) {
                var statement = jdbc.sql("DELETE FROM desktop_stream_pending WHERE stream_key=? AND group_name=? AND message_id=?"
                        + (consumer == null ? "" : " AND consumer_name=?")).params(stream, group, numericId(id));
                if (consumer != null) statement.param(consumer);
                removed += statement.update();
            }
            return removed;
        }));
    }

    @Override public Set<String> members(String key) {
        return new LinkedHashSet<>(jdbc.sql("SELECT member_name FROM desktop_runner_heartbeat WHERE members_key=? AND expires_at>?")
                .params(key, clock.millis()).query(String.class).list());
    }
    @Override public String get(String key) {
        return jdbc.sql("SELECT body FROM desktop_runner_heartbeat WHERE heartbeat_key=? AND expires_at>?")
                .params(key, clock.millis()).query(String.class).optional().orElse(null);
    }
    @Override public synchronized void remove(String key, String... members) {
        transaction.executeWithoutResult(status -> {
            for (String member : members) jdbc.sql("DELETE FROM desktop_runner_heartbeat WHERE members_key=? AND member_name=?")
                    .params(key, member).update();
        });
    }
    @Override public synchronized void removeIfMissing(String membersKey, String heartbeatKey, String member) {
        transaction.executeWithoutResult(status -> jdbc.sql("DELETE FROM desktop_runner_heartbeat WHERE members_key=? AND member_name=? AND heartbeat_key=? AND expires_at<=?")
                .params(membersKey, member, heartbeatKey, clock.millis()).update());
    }
    @Override public synchronized void delete(String key) {
        transaction.executeWithoutResult(status -> jdbc.sql("DELETE FROM desktop_runner_heartbeat WHERE heartbeat_key=?").param(key).update());
    }
    @Override public synchronized void publish(String membersKey, String heartbeatKey, String member, String body, Duration ttl) {
        if (ttl == null || ttl.toMillis() <= 0) throw new IllegalArgumentException("Heartbeat lifetime must be positive");
        long deadline = Math.addExact(clock.millis(), ttl.toMillis());
        transaction.executeWithoutResult(status -> jdbc.sql("MERGE INTO desktop_runner_heartbeat(members_key,member_name,heartbeat_key,body,expires_at) KEY(members_key,member_name) VALUES(?,?,?,?,?)")
                .params(membersKey, member, heartbeatKey, body, deadline).update());
    }
    @Override public String label() { return "本地持久任务队列"; }
    @Override public void check() {
        jdbc.sql("SELECT COUNT(*) FROM desktop_stream_group").query(Long.class).single();
        jdbc.sql("SELECT COUNT(*) FROM desktop_runner_heartbeat").query(Long.class).single();
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public synchronized void cleanup() {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM desktop_runner_heartbeat WHERE expires_at<=?").param(clock.millis()).update();
            // Keep a diagnostic tail, all unread records and every pending record. Streams without a group remain intact.
            jdbc.sql("DELETE FROM desktop_stream_message m WHERE m.created_at<? AND m.message_id<(SELECT s.last_id-50000 FROM desktop_stream s WHERE s.stream_key=m.stream_key) AND EXISTS(SELECT 1 FROM desktop_stream_group g WHERE g.stream_key=m.stream_key) AND NOT EXISTS(SELECT 1 FROM desktop_stream_group g WHERE g.stream_key=m.stream_key AND g.last_id<m.message_id) AND NOT EXISTS(SELECT 1 FROM desktop_stream_pending p WHERE p.stream_key=m.stream_key AND p.message_id=m.message_id)")
                    .param(clock.millis() - 60000).update();
            // Dead-letter diagnostics have no normal consumer. Bound only that explicitly named stream family.
            jdbc.sql("DELETE FROM desktop_stream_message m WHERE m.stream_key LIKE 'testhub:runner:%:dead-letter:v1' AND m.created_at<? AND m.message_id<(SELECT s.last_id-1000 FROM desktop_stream s WHERE s.stream_key=m.stream_key) AND NOT EXISTS(SELECT 1 FROM desktop_stream_group g WHERE g.stream_key=m.stream_key) AND NOT EXISTS(SELECT 1 FROM desktop_stream_pending p WHERE p.stream_key=m.stream_key AND p.message_id=m.message_id)")
                    .param(clock.millis() - 60000).update();
        });
    }

    private long groupCursor(String stream, String group) {
        return jdbc.sql("SELECT last_id FROM desktop_stream_group WHERE stream_key=? AND group_name=?")
                .params(stream, group).query(Long.class).optional().orElseThrow(() -> new IllegalStateException("NOGROUP Consumer group does not exist"));
    }
    private void ensureStream(String stream) {
        if (jdbc.sql("SELECT COUNT(*) FROM desktop_stream WHERE stream_key=?").param(stream).query(Integer.class).single() == 0)
            jdbc.sql("INSERT INTO desktop_stream(stream_key,last_id) VALUES(?,0)").param(stream).update();
    }
    private List<MapRecord<String, Object, Object>> messages(String sql, String stream, Object... params) {
        return jdbc.sql(sql).params(params).query((row, index) -> {
            try {
                Map<Object, Object> fields = new LinkedHashMap<>(json.readValue(row.getString("payload"), new TypeReference<Map<String, String>>() {}));
                return StreamRecords.mapBacked(fields).withStreamKey(stream).withId(RecordId.of(row.getLong("message_id") + "-0"));
            } catch (java.io.IOException exception) { throw new IllegalStateException("Unreadable local stream record", exception); }
        }).list();
    }
    private static long numericId(String id) {
        if ("0".equals(id)) return 0;
        if (id == null || !id.matches("[0-9]+-0")) throw new IllegalArgumentException("Invalid local stream id");
        return Long.parseLong(id.substring(0, id.length() - 2));
    }
    private static void requireCount(int count) {
        if (count < 1 || count > 1000) throw new IllegalArgumentException("Invalid stream batch size");
    }
}
