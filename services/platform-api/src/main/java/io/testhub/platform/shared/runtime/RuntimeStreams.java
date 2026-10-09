package io.testhub.platform.shared.runtime;

import java.util.List;
import org.springframework.data.redis.connection.stream.*;

/** Keeps the existing stream record wire format while selecting the transport at the boundary. */
public interface RuntimeStreams {
    RecordId add(MapRecord<String, ?, ?> record);
    String createGroup(String stream, ReadOffset offset, String group);
    List<MapRecord<String, Object, Object>> read(Consumer consumer, StreamReadOptions options, StreamOffset<String> stream);
    Long acknowledge(String group, MapRecord<String, ?, ?> record);
    String label();
    void check();
}
