package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserve only config.delayMillis before untyped Double decoding can discard a fractional part. */
public final class WaitStepJson {
    private WaitStepJson() { }

    public static final class Config extends JsonDeserializer<Map<String, Object>> {
        @Override public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return object(parser, context, true);
        }
    }
    public static final class Definition extends JsonDeserializer<Map<String, Object>> {
        @Override public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return object(parser, context, false);
        }
    }

    private static Map<String, Object> object(JsonParser parser, DeserializationContext context, boolean config) throws IOException {
        if (!parser.isExpectedStartObjectToken()) {
            context.reportInputMismatch(Map.class, "步骤配置必须是 JSON 对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String key = parser.currentName();
            parser.nextToken();
            Object value;
            if (config && "delayMillis".equals(key) && parser.currentToken().isNumeric()) {
                value = parser.getDecimalValue();
            } else if (parser.currentToken() == JsonToken.START_OBJECT) {
                value = object(parser, context, "config".equals(key));
            } else if (parser.currentToken() == JsonToken.START_ARRAY) {
                value = array(parser, context);
            } else {
                value = context.readValue(parser, Object.class);
            }
            result.put(key, value);
        }
        return result;
    }

    private static Object array(JsonParser parser, DeserializationContext context) throws IOException {
        var result = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == JsonToken.START_OBJECT) result.add(object(parser, context, false));
            else if (parser.currentToken() == JsonToken.START_ARRAY) result.add(array(parser, context));
            else result.add(context.readValue(parser, Object.class));
        }
        return result;
    }
}
