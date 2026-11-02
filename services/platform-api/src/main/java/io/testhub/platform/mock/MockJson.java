package io.testhub.platform.mock;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Local wire contracts for Mock business fields; the application mapper remains unchanged. */
public final class MockJson {
    private MockJson() { }
    private static final Set<String> FIELDS = Set.of("priority", "expiresInDays", "responseStatus", "requestQuery",
            "requestHeaders", "responseHeaders", "requestBody", "responseBody");

    private abstract static class IntegerValue extends StdDeserializer<Integer> {
        private final int minimum, maximum;
        private final String field;
        IntegerValue(String field, int minimum, int maximum) {
            super(Integer.class); this.field = field; this.minimum = minimum; this.maximum = maximum;
        }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                try {
                    // Business range checks stay in MockService, preserving its existing error contract.
                    return parser.getDecimalValue().intValueExact();
                } catch (ArithmeticException | NumberFormatException ignored) {
                    // Fractions and overflow must be rejected before an int conversion can discard information.
                }
            }
            return context.reportInputMismatch(Integer.class,
                    "Mock %s 必须是 %d 到 %d 之间的精确整数 JSON 数值", field, minimum, maximum);
        }
    }
    public static class Priority extends IntegerValue { public Priority() { super("priority", 0, 1000); } }
    public static final class Expiry extends IntegerValue { public Expiry() { super("expiresInDays", 1, 365); } }
    public static class Status extends IntegerValue { public Status() { super("responseStatus", 200, 599); } }
    // Version-one transfer records use primitive fields. Preserve their existing absent/null defaults.
    public static final class TransferPriority extends Priority {
        @Override public Integer getNullValue(DeserializationContext context) { return 0; }
    }
    public static final class TransferStatus extends Status {
        @Override public Integer getNullValue(DeserializationContext context) { return 0; }
    }
    public static final class Text extends StdDeserializer<String> {
        public Text() { super(String.class); }
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
            return context.reportInputMismatch(String.class, "Mock 文本或条件值必须是 JSON 字符串");
        }
    }

    static String field(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof JsonMappingException mapping) {
                for (var reference : mapping.getPath()) {
                    if (reference.getFieldName() != null && FIELDS.contains(reference.getFieldName())) return reference.getFieldName();
                }
            }
        }
        return null;
    }
    static ResponseStatusException rejected(IOException error, String fallback) {
        String field = field(error);
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                field == null ? fallback : "Mock 字段 " + field + " 类型或数值不合法");
    }
}
