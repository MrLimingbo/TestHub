package io.testhub.platform.functional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** A portable snapshot must not invent a title by coercing a JSON scalar. */
public final class FunctionalTitleSnapshot extends StdDeserializer<String> {
    public FunctionalTitleSnapshot() { super(String.class); }
    @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getText();
        return context.reportInputMismatch(String.class, "titleSnapshot 必须是字符串");
    }
}
