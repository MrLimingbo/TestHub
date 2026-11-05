package io.testhub.platform.mobileautomation;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class P2D16MobileInputTests {
    private final ObjectMapper json = new ObjectMapper();
    private MobileAutomationController.StepRequest step(String value) throws Exception {
        return json.readValue("{\"id\":\"s\",\"type\":\"LAUNCH\",\"name\":\"s\",\"timeoutMillis\":"+value+"}", MobileAutomationController.StepRequest.class);
    }
    @Test void stepNumbersAcceptExactValues() throws Exception {
        for (String value : new String[]{"1","60000","10.0","1e1"}) assertNotNull(step(value).timeoutMillis());
        assertNull(step("null").timeoutMillis());
    }
    @Test void stepNumbersRejectCoercionAndLossOfPrecision() {
        for (String value : new String[]{"1.5","1.00000000000000000001","2147483648","\"10\"","true","[]","{}"})
            assertThrows(Exception.class, () -> step(value), value);
    }
    @Test void executeNumbersRequireExactJsonNumbers() throws Exception {
        for (String v : new String[]{"10","3600","10.0","1e1","null"}) json.readValue("{\"timeoutSeconds\":"+v+"}", MobileAutomationController.ExecuteRequest.class);
        for (String v : new String[]{"10.1","10.00000000000000000001","\"10\"","true","[]","{}","999999999999999999999"})
            assertThrows(Exception.class, () -> json.readValue("{\"timeoutSeconds\":"+v+"}", MobileAutomationController.ExecuteRequest.class), v);
    }
    @Test void textFieldsAndVariablesRejectNonStrings() throws Exception {
        for (String field : new String[]{"value","expectedText"}) {
            for (String v : new String[]{"123","true","[]","{}"}) assertThrows(Exception.class, () -> json.readValue("{\""+field+"\":"+v+"}",MobileAutomationController.StepRequest.class));
            assertNotNull(json.readValue("{\""+field+"\":\"123\"}",MobileAutomationController.StepRequest.class));
            assertNotNull(json.readValue("{\""+field+"\":null}",MobileAutomationController.StepRequest.class));
        }
        for (String v : new String[]{"123","true","[]","{}"}) assertThrows(Exception.class, () -> json.readValue("{\"variables\":{\"x\":"+v+"}}",MobileAutomationController.ScenarioRequest.class));
    }
    @Test void transferAndSyncStrictWithoutChangingStoredStepReader() throws Exception {
        for (String v : new String[]{"1.5","1.00000000000000000001","\"1\"","true"}) {
            assertThrows(Exception.class, () -> json.readValue("{\"steps\":[{\"timeoutMillis\":"+v+"}]}",MobileAutomationTransferService.Scenario.class));
            assertThrows(Exception.class, () -> json.readValue("{\"steps\":[{\"timeoutMillis\":"+v+"}]}",MobileAutomationSyncService.Scenario.class));
        }
        assertEquals(1,json.readValue("{\"timeoutMillis\":1.5}",MobileAutomationService.Step.class).timeoutMillis());
    }
    @Test void storedGuardRejectsBadBeforeCoercion() {
        for (String v : new String[]{"1.5","1.00000000000000000001","\"1\"","true"})
            assertThrows(Exception.class, () -> MobileInput.requireStored(json,"{}","[{\"timeoutMillis\":"+v+"}]"));
        assertDoesNotThrow(() -> MobileInput.requireStored(json,"{\"x\":\"y\"}","[{\"timeoutMillis\":1e1}]"));
        assertThrows(Exception.class, () -> MobileInput.requireStored(json,"{\"x\":123}","[]"));
    }
    @Test void runtimeManifestGuardChecksBeforeRunnerCoercion() {
        for (String v : new String[]{"1.5","1.00000000000000000001","\"1\"","true","60001"})
            assertThrows(Exception.class, () -> MobileInput.requireManifest(json,"{\"scenario\":{\"variables\":{},\"steps\":[{\"timeoutMillis\":"+v+"}]}}"));
        assertDoesNotThrow(() -> MobileInput.requireManifest(json,"{\"scenario\":{\"variables\":{},\"steps\":[{\"timeoutMillis\":null}]}}"));
        assertThrows(Exception.class, () -> MobileInput.requireManifest(json,"{\"scenario\":{\"variables\":{\"x\":null},\"steps\":[]}}"));
    }
    @Test void syncNumericEquivalencePreservesExactnessAndOtherFields() throws Exception {
        var reader=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var normalized=json.readTree("{\"steps\":[{\"timeoutMillis\":10}]}");
        assertTrue(MobileInput.equivalent("SCENARIO",reader.readTree("{\"steps\":[{\"timeoutMillis\":1e1}]}"),normalized));
        assertFalse(MobileInput.equivalent("SCENARIO",reader.readTree("{\"steps\":[{\"timeoutMillis\":10.00000000000000000001}]}"),normalized));
        assertFalse(MobileInput.equivalent("SCENARIO",reader.readTree("{\"steps\":[{\"timeoutMillis\":10,\"extra\":1}]}"),normalized));
    }
}
