package io.testhub.platform.shared.api;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiRequestBodyTests {
    @RestController static class Fixture {
        int calls;
        record Input(int timeoutSeconds) {}
        @PostMapping("/api/v1/fixture") Map<String, Integer> submit(@RequestBody Input input) {
            calls++;
            return Map.of("timeoutSeconds", input.timeoutSeconds());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"timeoutSeconds\":", "{\"timeoutSeconds\":\"invalid\"}"})
    void invalidBodiesReturnClientErrorsWithoutInvokingBusinessLogic(String body) throws Exception {
        var controller = new Fixture();
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/v1/fixture").contentType(MediaType.APPLICATION_JSON).content(body)
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "body-test"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("body-test")).andExpect(jsonPath("$.details").isEmpty());
        assertEquals(0, controller.calls);
    }

    @Test void validBodyStillInvokesTheController() throws Exception {
        var controller = new Fixture();
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/v1/fixture").contentType(MediaType.APPLICATION_JSON).content("{\"timeoutSeconds\":120}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timeoutSeconds").value(120));
        assertEquals(1, controller.calls);
    }
}
