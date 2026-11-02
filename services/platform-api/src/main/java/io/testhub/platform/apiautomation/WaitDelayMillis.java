package io.testhub.platform.apiautomation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** WAIT alone uses an exact numeric contract; other step configuration stays unchanged. */
final class WaitDelayMillis {
    private WaitDelayMillis() { }

    static long require(Object value, String field) {
        if (value instanceof Number number) {
            try {
                long delay = new BigDecimal(number.toString()).longValueExact();
                if (delay >= 0 && delay <= 600_000) return delay;
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Reject fractions, non-finite numbers and overflow before any narrowing conversion.
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                field + " 必须是 0 到 600000 之间的精确整数 JSON 数值，不能使用字符串或小数部分");
    }

    static Map<String, Object> config(Map<String, Object> config, String field) {
        if (config == null || !config.keySet().equals(Set.of("delayMillis"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 只支持必填 delayMillis");
        }
        return Map.of("delayMillis", require(config.get("delayMillis"), field + ".delayMillis"));
    }
}
