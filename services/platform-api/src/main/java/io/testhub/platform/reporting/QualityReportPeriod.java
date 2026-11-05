package io.testhub.platform.reporting;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Strict input conversion scoped to the report period, without changing stored record readers. */
public final class QualityReportPeriod {
    private QualityReportPeriod() { }

    public static final class ExactInteger extends StdDeserializer<Integer> {
        public ExactInteger() { super(Integer.class); }
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken().isNumeric()) {
                try { return parser.getDecimalValue().intValueExact(); }
                catch (ArithmeticException | NumberFormatException ignored) { }
            }
            return context.reportInputMismatch(Integer.class, "periodDays 必须是精确整数 JSON 数值");
        }
    }

    static boolean exactInteger(JsonNode value) {
        if (!value.isNumber()) return false;
        try { value.decimalValue().intValueExact(); return true; }
        catch (ArithmeticException | NumberFormatException ignored) { return false; }
    }
}
