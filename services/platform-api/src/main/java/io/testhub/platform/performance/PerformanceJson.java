package io.testhub.platform.performance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Scoped wire rules: validate original JSON values before narrowing or coercion. */
public final class PerformanceJson {
    private PerformanceJson() { }
    private static final Set<String> FIELDS = Set.of("threadCount", "rampUpSeconds", "durationSeconds", "stepCount",
            "stepIntervalSeconds", "targetTps", "p95ThresholdMillis", "expectedStatusCode", "rendezvousUsers",
            "errorThresholdPercent", "requestHeaders", "queryParameters", "requestBody", "responseContains", "dataSetCsv");
    public static final class IntegerValue extends StdDeserializer<Integer> {
        public IntegerValue() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                try { return parser.getDecimalValue().intValueExact(); }
                catch (ArithmeticException | NumberFormatException ignored) { /* Reject without discarding precision. */ }
            }
            return context.reportInputMismatch(Integer.class, "性能整数字段必须是精确整数 JSON 数值");
        }
    }
    public static final class DecimalValue extends StdDeserializer<BigDecimal> {
        public DecimalValue() { super(BigDecimal.class); }
        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) return parser.getDecimalValue();
            return context.reportInputMismatch(BigDecimal.class, "错误率必须是 JSON 数值");
        }
    }
    public static final class Text extends StdDeserializer<String> {
        public Text() { super(String.class); }
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
            return context.reportInputMismatch(String.class, "性能文本或条件值必须是 JSON 字符串");
        }
    }
    static String field(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof JsonMappingException mapping) {
                int index = -1;
                for (var reference : mapping.getPath()) {
                    if (reference.getIndex() >= 0) index = reference.getIndex();
                    String field = reference.getFieldName();
                    if (field != null && FIELDS.contains(field)) return "threadGroups" + (index < 0 ? "" : "[" + index + "]") + "." + field;
                }
            }
        }
        return null;
    }
    static ResponseStatusException rejected(IOException error, String fallback) {
        String field = field(error);
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, field == null ? fallback : "性能字段 " + field + " 类型或数值不合法");
    }
    static void requireStoredTextMap(ObjectMapper json, String source, String field) {
        if (source == null || source.isBlank()) return;
        try {
            var value = json.readTree(source);
            if (!value.isObject()) throw new IOException();
            for (var item : value) if (!item.isTextual() && !item.isNull()) throw new IOException();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已保存的性能字段 " + field + " 包含非字符串值，请先编辑修复");
        }
    }
}
