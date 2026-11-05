package io.testhub.platform.mobileautomation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Strict input boundaries, without changing the persisted record reader. */
public final class MobileInput {
    private MobileInput() { }
    public static final class IntegerValue extends StdDeserializer<Integer> {
        public IntegerValue() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser p, DeserializationContext c) throws IOException {
            if (p.currentToken().isNumeric()) {
                try { return p.getDecimalValue().intValueExact(); } catch (ArithmeticException | NumberFormatException ignored) { }
            }
            return c.reportInputMismatch(Integer.class, "移动端数字字段必须是精确整数 JSON 数值");
        }
    }
    public static final class Text extends StdDeserializer<String> {
        public Text() { super(String.class); }
        @Override public String deserialize(JsonParser p, DeserializationContext c) throws IOException {
            if (p.hasToken(JsonToken.VALUE_STRING)) return p.getText();
            return c.reportInputMismatch(String.class, "移动端文本字段必须是 JSON 字符串");
        }
    }
    public record StepFields(String id, String type, String name, String elementId,
            @JsonDeserialize(using=Text.class) String value, @JsonDeserialize(using=Text.class) String expectedText,
            @JsonDeserialize(using=IntegerValue.class) Integer timeoutMillis) { }
    public static final class Step extends StdDeserializer<MobileAutomationService.Step> {
        public Step() { super(MobileAutomationService.Step.class); }
        @Override public MobileAutomationService.Step deserialize(JsonParser p, DeserializationContext c) throws IOException {
            StepFields s = c.readValue(p, StepFields.class);
            return new MobileAutomationService.Step(s.id(),s.type(),s.name(),s.elementId(),s.value(),s.expectedText(),s.timeoutMillis());
        }
    }
    static String field(Throwable error) {
        for (int depth=0; error!=null && depth<12; depth++,error=error.getCause()) {
            if (error instanceof JsonMappingException mapping) {
                String container=null; int index=-1;
                for (var ref : mapping.getPath()) {
                    String name=ref.getFieldName();
                    if ("steps".equals(name)) { container=name; index=-1; }
                    if (ref.getIndex()>=0) index=ref.getIndex();
                    if (name!=null && Set.of("timeoutMillis","timeoutSeconds","variables","value","expectedText").contains(name))
                        return (container==null?"":container+(index<0?"":"["+index+"]")+".")+name;
                }
            }
        }
        return null;
    }
    private record Stored(@JsonDeserialize(contentUsing=Text.class) Map<String,String> variables,
                          @JsonDeserialize(contentUsing=Step.class) List<MobileAutomationService.Step> steps) { }
    private record RuntimeStep(String type, String locatorStrategy, String locatorValue,
            @JsonDeserialize(using=Text.class) String value,
            @JsonDeserialize(using=Text.class) String expectedText,
            @JsonDeserialize(using=IntegerValue.class) Integer timeoutMillis) { }
    private record RuntimeScenario(@JsonDeserialize(contentUsing=Text.class) Map<String,String> variables, List<RuntimeStep> steps) { }
    private record RuntimeDevice(String platform) { }
    private record RuntimeManifest(RuntimeDevice device, RuntimeScenario scenario) { }
    static void requireManifest(ObjectMapper json, String content) {
        try {
            RuntimeManifest manifest=json.readerFor(RuntimeManifest.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(content);
            if (manifest == null || manifest.scenario() == null || manifest.scenario().steps() == null) throw new IOException();
            RuntimeScenario scenario=manifest.scenario();
            if (scenario.variables()!=null && (scenario.variables().size()>50 || scenario.variables().entrySet().stream().anyMatch(e ->
                    e.getKey()==null || !e.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || e.getValue()==null || e.getValue().length()>4096))) throw new IOException();
            for (RuntimeStep step : scenario.steps()) {
                if (step==null || (step.timeoutMillis()!=null && (step.timeoutMillis()<1 || step.timeoutMillis()>60000))
                        || (step.value()!=null && step.value().length()>4096) || (step.expectedText()!=null && step.expectedText().length()>4096)) throw new IOException();
                MobileLocatorPolicy.requireResolved(manifest.device()==null?null:manifest.device().platform(),
                        step.type(),step.locatorStrategy(),step.locatorValue());
            }
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"已保存的移动端运行清单字段不合法"); }
    }
    static void requireStored(ObjectMapper json, String variables, String steps) {
        try {
            Stored value=json.readerFor(Stored.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue("{\"variables\":"+variables+",\"steps\":"+steps+"}");
            if (value == null || value.steps() == null) throw new IOException();
        } catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"已保存的移动端场景字段类型不合法，请先编辑修复"); }
    }
    static boolean equivalent(String kind, JsonNode supplied, JsonNode normalized) {
        JsonNode copy=supplied.deepCopy();
        if ("SCENARIO".equals(kind) && copy.path("steps").isArray() && normalized.path("steps").isArray()) {
            for (int i=0;i<Math.min(copy.path("steps").size(),normalized.path("steps").size());i++) {
                JsonNode a=copy.path("steps").get(i), b=normalized.path("steps").get(i);
                if (a instanceof ObjectNode o && a.has("timeoutMillis") && a.get("timeoutMillis").isNumber()
                        && b.has("timeoutMillis") && b.get("timeoutMillis").isIntegralNumber()
                        && a.get("timeoutMillis").decimalValue().compareTo(b.get("timeoutMillis").decimalValue())==0)
                    o.set("timeoutMillis",b.get("timeoutMillis"));
            }
        }
        return normalized.equals(copy);
    }
}
