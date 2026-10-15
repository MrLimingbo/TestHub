package io.testhub.platform.desktop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesktopBrokerControllerTests {
    private final String secret = "desktop-broker-unit-test-secret-only";
    private final DesktopRuntimeBroker broker = mock(DesktopRuntimeBroker.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    @BeforeEach void initialize() { mvc = mvc(secret); }
    // Standalone MockMvc does not load the application's preferred-json-mapper setting.
    private MockMvc mvc(String key) {
        return MockMvcBuilders.standaloneSetup(new DesktopBrokerController(broker, key))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @Test void acceptsOnlyLoopbackNativeRequestsWithMatchingEngineAndRunnerCredential() throws Exception {
        mvc.perform(request("ping", Map.of())).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("PONG"));
        mvc.perform(request("ping", Map.of()).header("Origin", "http://localhost")).andExpect(status().isForbidden());
        mvc.perform(request("ping", Map.of()).with(r -> { r.setRemoteAddr("192.168.1.5"); return r; })).andExpect(status().isForbidden());
        mvc.perform(request("ping", Map.of()).with(r -> { r.setServerName("attacker.example"); return r; })).andExpect(status().isForbidden());
        mvc.perform(request("ping", Map.of("engine", "jmeter"))).andExpect(status().isForbidden());
        mvc.perform(request("ping", Map.of("runnerId", "other"))).andExpect(status().isForbidden());
        mvc.perform(request("ping", Map.of()).with(r -> { r.removeHeader("X-TestHub-Desktop-Token"); return r; })).andExpect(status().isForbidden());
        verify(broker, times(1)).check();
    }
    @Test void engineCannotPublishTasksOrReadOtherEngineOrWrongGroup() throws Exception {
        mvc.perform(request("publish", Map.of("stream", "testhub:runner:api:tasks:v1", "fields", Map.of("task", "injected"))))
                .andExpect(status().isForbidden());
        mvc.perform(request("read", Map.of("stream", "testhub:runner:jmeter:tasks:v1", "group", "testhub-jmeter-runner-v1", "offset", ">", "count", 1)))
                .andExpect(status().isForbidden());
        mvc.perform(request("read", Map.of("stream", "testhub:runner:api:tasks:v1", "group", "other", "offset", ">", "count", 1)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(broker);
    }
    @Test void rejectsInvalidBoundsAndFailsClosedWhenKeyMissing() throws Exception {
        mvc.perform(request("heartbeat", Map.of("body", "{}", "ttlSeconds", 301))).andExpect(status().isBadRequest());
        mvc.perform(request("read", Map.of("stream", "testhub:runner:api:tasks:v1", "group", "testhub-api-runner-v1", "offset", ">", "count", 1001)))
                .andExpect(status().isBadRequest());
        mvc = mvc("");
        mvc.perform(request("ping", Map.of())).andExpect(status().isForbidden());
        verifyNoInteractions(broker);
    }
    private MockHttpServletRequestBuilder request(String operation, Map<String, Object> values) throws Exception {
        var body = new HashMap<String, Object>(Map.of("engine", "api", "runnerId", "local", "operation", operation)); body.putAll(values);
        return post("/internal/desktop-broker/v1/command").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .header("X-TestHub-Desktop-Token", DesktopBrokerController.token(secret, "api", "local"))
                .with(request -> { request.setRemoteAddr("127.0.0.1"); request.setServerName("127.0.0.1"); return request; });
    }
}
