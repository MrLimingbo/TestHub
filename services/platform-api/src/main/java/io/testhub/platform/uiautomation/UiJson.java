package io.testhub.platform.uiautomation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Write and execution boundary rules; stored domain records retain their legacy read contract. */
public final class UiJson {
    private UiJson() { }
    private static final Set<String> FIELDS = Set.of("viewportWidth", "viewportHeight", "timeoutMillis", "timeoutSeconds", "variables", "value", "expectedText");
    public static class IntegerValue extends StdDeserializer<Integer> {
        public IntegerValue() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                try { return parser.getDecimalValue().intValueExact(); }
                catch (ArithmeticException | NumberFormatException ignored) { /* Never discard the fractional part. */ }
            }
            return context.reportInputMismatch(Integer.class, "Web 数字字段必须是精确整数 JSON 数值");
        }
    }
    public static final class PrimitiveInteger extends IntegerValue {
        @Override public Integer getNullValue(DeserializationContext context) { return 0; }
    }
    public static final class Text extends StdDeserializer<String> {
        public Text() { super(String.class); }
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
            return context.reportInputMismatch(String.class, "Web 变量或步骤文本必须是 JSON 字符串");
        }
    }
    public record StepFields(String id, String type, String name, String selector, String pageObjectId,
            @JsonDeserialize(using = Text.class) String value, String url,
            @JsonDeserialize(using = Text.class) String expectedText,
            @JsonDeserialize(using = IntegerValue.class) Integer timeoutMillis) { }
    public static final class StepInput extends StdDeserializer<UiAutomationStep> {
        public StepInput() { super(UiAutomationStep.class); }
        @Override public UiAutomationStep deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            StepFields s = context.readValue(parser, StepFields.class);
            return new UiAutomationStep(s.id(), s.type(), s.name(), s.selector(), s.pageObjectId(), s.value(), s.url(), s.expectedText(), s.timeoutMillis());
        }
    }
    static String field(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof JsonMappingException mapping) {
                String container = null; int index = -1;
                for (var reference : mapping.getPath()) {
                    String name = reference.getFieldName();
                    if (name != null && Set.of("steps", "profiles", "scenarios", "schedules").contains(name)) { container = name; index = -1; }
                    if (reference.getIndex() >= 0) index = reference.getIndex();
                    if (name != null && FIELDS.contains(name)) return (container == null ? "" : container + (index < 0 ? "" : "[" + index + "]") + ".") + name;
                }
            }
        }
        return null;
    }
    static ResponseStatusException rejected(IOException error, String fallback) {
        String field = field(error);
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, field == null ? fallback : "Web 字段 " + field + " 类型或数值不合法");
    }
    private record StoredScenario(@JsonDeserialize(contentUsing = Text.class) Map<String, String> variables,
            @JsonDeserialize(contentUsing = StepInput.class) List<UiAutomationStep> steps) { }
    private record StoredBrowser(String name, String browser, String baseUrl,
            @JsonDeserialize(using = IntegerValue.class) Integer viewportWidth,
            @JsonDeserialize(using = IntegerValue.class) Integer viewportHeight) { }
    private record StoredManifest(String contract, Integer manifestVersion, StoredBrowser browserProfile, StoredScenario scenario) { }
    static void requireStoredScenario(ObjectMapper json, String variables, String steps) {
        try {
            json.readerFor(StoredScenario.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue("{\"variables\":" + (variables == null ? "{}" : variables) + ",\"steps\":" + (steps == null ? "[]" : steps) + "}");
        } catch (IOException error) { throw rejected(error, "已保存的 Web 目标字段类型不合法，请先编辑修复"); }
    }
    static void requireStoredManifest(ObjectMapper json, String content) {
        try {
            StoredManifest value = json.readerFor(StoredManifest.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(content);
            if (value == null || value.browserProfile() == null || value.scenario() == null || value.scenario().steps() == null)
                throw new IOException();
            integer(value.browserProfile().viewportWidth(), 320, 3840);
            integer(value.browserProfile().viewportHeight(), 320, 2160);
            for (UiAutomationStep step : value.scenario().steps()) {
                if (step == null) throw new IOException();
                if ("WAIT".equals(step.type())) integer(step.timeoutMillis(), 1, 60000);
                else if (step.timeoutMillis() != null) integer(step.timeoutMillis(), 100, 60000);
            }
        } catch (IOException error) { throw rejected(error, "旧 Web 运行清单目标字段不合法，无法签发运行配置"); }
    }
    private static void integer(Integer value, int minimum, int maximum) throws IOException {
        if (value == null || value < minimum || value > maximum) throw new IOException();
    }
    /** Preserve full snapshot equality, except the explicitly approved numeric spellings. */
    static boolean equivalentDefinition(String kind, JsonNode supplied, JsonNode canonical) {
        JsonNode copy = supplied.deepCopy();
        if ("PROFILE".equals(kind)) replaceNumbers(copy, canonical, "viewportWidth", "viewportHeight");
        else if ("SCHEDULE".equals(kind)) replaceNumbers(copy, canonical, "timeoutSeconds");
        else if ("SCENARIO".equals(kind) && copy.path("steps").isArray() && canonical.path("steps").isArray()) {
            for (int i = 0; i < Math.min(copy.path("steps").size(), canonical.path("steps").size()); i++)
                replaceNumbers(copy.path("steps").get(i), canonical.path("steps").get(i), "timeoutMillis");
        }
        return canonical.equals(copy);
    }
    private static void replaceNumbers(JsonNode supplied, JsonNode canonical, String... fields) {
        if (!(supplied instanceof ObjectNode object)) return;
        for (String field : fields) {
            if (supplied.has(field) && supplied.get(field).isNumber() && canonical.has(field) && canonical.get(field).isIntegralNumber()
                    && supplied.get(field).decimalValue().compareTo(canonical.get(field).decimalValue()) == 0)
                object.set(field, canonical.get(field));
        }
    }
}
