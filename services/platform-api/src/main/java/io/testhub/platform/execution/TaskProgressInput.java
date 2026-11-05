package io.testhub.platform.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Validates the JSON number before any lossy integer conversion. */
public final class TaskProgressInput extends StdDeserializer<Integer> {
    public TaskProgressInput() { super(Integer.class); }

    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken().isNumeric()) {
            try { return parser.getDecimalValue().intValueExact(); }
            catch (ArithmeticException | NumberFormatException ignored) { }
        }
        return context.reportInputMismatch(Integer.class, "progress 必须是精确整数 JSON 数值");
    }

    static String location(IOException error) {
        if (!(error instanceof JsonMappingException mapping)) return null;
        StringBuilder path = new StringBuilder();
        for (var part : mapping.getPath()) {
            if (part.getFieldName() != null) {
                if (!java.util.Set.of("operations", "snapshot", "tasks", "progress").contains(part.getFieldName())) return null;
                if (!path.isEmpty()) path.append('.');
                path.append(part.getFieldName());
            } else if (part.getIndex() >= 0) path.append('[').append(part.getIndex()).append(']');
        }
        return path.toString().endsWith("progress") ? path.toString() : null;
    }
}
