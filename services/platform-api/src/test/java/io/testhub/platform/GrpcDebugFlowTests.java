package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.grpc.health.v1.HealthProto;
import io.testhub.platform.apidebug.GrpcDebugTransport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(GrpcDebugFlowTests.GrpcTransportConfiguration.class)
class GrpcDebugFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private RecordingGrpcTransport transport;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_debug_history WHERE request_url LIKE 'grpc%://8.8.8.8/%'").update();
        jdbc.sql("DELETE FROM project_parameter WHERE parameter_key IN ('GRPC_ACCEPTANCE_ENDPOINT', 'GRPC_ACCEPTANCE_SECRET')").update();
        transport.clear();
    }

    @Test
    void executesUnaryCallWithEnvironmentValuesAndStoresOnlySafeSummary() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        upsert(administrator, projectId, "parameters", Map.of("environmentCode", "test", "key", "GRPC_ACCEPTANCE_ENDPOINT", "value", "8.8.8.8:443"));
        upsert(administrator, projectId, "secrets", Map.of("environmentCode", "test", "key", "GRPC_ACCEPTANCE_SECRET", "value", "grpc-secret-value"));

        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "{{GRPC_ACCEPTANCE_ENDPOINT}}", "tls", true,
                "service", "testhub.echo.v1.EchoService", "method", "Echo",
                "requestJson", "{\"message\":\"{{GRPC_ACCEPTANCE_SECRET}}\",\"at\":\"{{$timestamp}}\"}",
                "timeoutMs", 1000, "environmentCode", "test", "metadata", List.of(Map.of("key", "x-client-trace", "value", "run-{{$timestamp}}")),
                "auth", Map.of("type", "BEARER", "token", "{{GRPC_ACCEPTANCE_SECRET}}"));
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/unary", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("OK")).andExpect(jsonPath("$.data.body").value("{\"message\":\"ok\"}"));

        GrpcDebugTransport.Request sent = transport.latest();
        assertEquals("8.8.8.8", sent.host());
        assertEquals(443, sent.port());
        assertTrue(sent.tls());
        assertEquals("testhub.echo.v1.EchoService", sent.service());
        assertEquals("Echo", sent.method());
        assertTrue(sent.requestJson().contains("grpc-secret-value"));
        assertTrue(sent.requestJson().matches(".*\\\"at\\\":\\\"[0-9]{13}\\\".*"));
        assertEquals(2, sent.metadata().size());
        assertTrue(sent.metadata().stream().anyMatch(item -> item.key().equals("x-client-trace") && item.value().matches("run-[0-9]{13}")));
        assertTrue(sent.metadata().stream().anyMatch(item -> item.key().equals("authorization") && item.value().equals("Bearer grpc-secret-value")));

        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/history", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].method").value("GRPC"))
                .andExpect(jsonPath("$.data[0].requestUrl").value("grpcs://8.8.8.8:443/testhub.echo.v1.EchoService/Echo"))
                .andExpect(jsonPath("$.data[0].responseStatus").value(0))
                .andExpect(jsonPath("$.data[0].requestUrl").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("grpc-secret-value"))));
    }

    @Test
    void rejectsLoopbackTargetsBeforeOpeningGrpcTransport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "127.0.0.1:50051", "tls", false,
                "service", "testhub.echo.v1.EchoService", "method", "Echo", "requestJson", "{}", "timeoutMs", 1000);

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/unary", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertNull(transport.latest());
    }

    @Test
    void rejectsReservedGrpcMetadataBeforeOpeningTransport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "8.8.8.8:443", "tls", true,
                "service", "testhub.echo.v1.EchoService", "method", "Echo", "requestJson", "{}", "timeoutMs", 1000,
                "metadata", List.of(Map.of("key", "grpc-timeout", "value", "1000m")));

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/unary", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertNull(transport.latest());
    }

    @Test
    void discoversServicesWithExecutionOnlyMetadataAndAuthentication() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        upsert(administrator, projectId, "parameters", Map.of("environmentCode", "test", "key", "GRPC_ACCEPTANCE_ENDPOINT", "value", "8.8.8.8:443"));
        upsert(administrator, projectId, "secrets", Map.of("environmentCode", "test", "key", "GRPC_ACCEPTANCE_SECRET", "value", "grpc-secret-value"));
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "{{GRPC_ACCEPTANCE_ENDPOINT}}", "tls", true, "timeoutMs", 1000,
                "environmentCode", "test", "metadata", List.of(Map.of("key", "x-discovery", "value", "services")),
                "auth", Map.of("type", "BEARER", "token", "{{GRPC_ACCEPTANCE_SECRET}}"));

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/discovery", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.services[0]").value("testhub.echo.v1.EchoService"))
                .andExpect(jsonPath("$.data.methods").isEmpty());
        GrpcDebugTransport.DiscoveryRequest servicesRequest = transport.latestDiscovery();
        assertEquals("8.8.8.8", servicesRequest.host());
        assertEquals(443, servicesRequest.port());
        assertTrue(servicesRequest.metadata().stream().anyMatch(item -> item.key().equals("x-discovery") && item.value().equals("services")));
        assertTrue(servicesRequest.metadata().stream().anyMatch(item -> item.key().equals("authorization") && item.value().equals("Bearer grpc-secret-value")));

        Map<String, Object> methodsRequest = Map.of("endpoint", "{{GRPC_ACCEPTANCE_ENDPOINT}}", "tls", true, "timeoutMs", 1000,
                "environmentCode", "test", "service", "testhub.echo.v1.EchoService", "metadata", List.of(Map.of("key", "x-discovery", "value", "methods")),
                "auth", Map.of("type", "BEARER", "token", "{{GRPC_ACCEPTANCE_SECRET}}"));
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/discovery", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(methodsRequest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.services").isEmpty())
                .andExpect(jsonPath("$.data.methods[0].name").value("Echo"))
                .andExpect(jsonPath("$.data.methods[0].callType").value("UNARY"));

        GrpcDebugTransport.DiscoveryRequest sent = transport.latestDiscovery();
        assertTrue(sent.metadata().stream().anyMatch(item -> item.key().equals("x-discovery") && item.value().equals("methods")));
        assertTrue(sent.metadata().stream().anyMatch(item -> item.key().equals("authorization") && item.value().equals("Bearer grpc-secret-value")));
    }

    @Test
    void rejectsReservedMetadataBeforeOpeningDiscoveryTransport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "8.8.8.8:443", "tls", true, "timeoutMs", 1000,
                "metadata", List.of(Map.of("key", "grpc-timeout", "value", "1000m")));

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/discovery", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertNull(transport.latestDiscovery());
    }

    @Test
    void importsProtoAndUsesItForOfflineDiscoveryAndUnaryCalls() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MockMultipartFile contracts = new MockMultipartFile("files", "offline_contracts.proto", "text/plain", """
                syntax = "proto3";
                package testhub.offline.v1;
                message EchoRequest { string message = 1; }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile schema = new MockMultipartFile("files", "offline_echo.proto", "text/plain", """
                syntax = "proto3";
                package testhub.offline.v1;
                import "offline_contracts.proto";
                message EchoReply { string message = 1; }
                service EchoService {
                  rpc Echo(EchoRequest) returns (EchoReply);
                  rpc Watch(EchoRequest) returns (stream EchoReply);
                }
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult imported = mvc.perform(multipart("/api/v1/projects/{projectId}/api-debug/grpc/descriptors", projectId).file(contracts).file(schema)
                        .param("name", "离线 Echo 描述符").cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("离线 Echo 描述符"))
                .andExpect(jsonPath("$.data.sourceFormat").value("PROTO3")).andExpect(jsonPath("$.data.serviceCount").value(1))
                .andExpect(jsonPath("$.data.sourceCount").value(2)).andReturn();
        String descriptorId = json(imported).at("/data/id").asText();

        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}/discovery", projectId, descriptorId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.services[0]").value("testhub.offline.v1.EchoService"));
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}/discovery", projectId, descriptorId)
                        .param("service", "testhub.offline.v1.EchoService").cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.methods[0].name").value("Echo"))
                .andExpect(jsonPath("$.data.methods[0].callType").value("UNARY"))
                .andExpect(jsonPath("$.data.methods[1].name").value("Watch"))
                .andExpect(jsonPath("$.data.methods[1].callType").value("SERVER_STREAMING"));

        Map<String, Object> request = Map.of("endpoint", "8.8.8.8:443", "tls", true, "service", "testhub.offline.v1.EchoService",
                "method", "Echo", "requestJson", "{\"message\":\"offline\"}", "timeoutMs", 1000, "descriptorId", descriptorId);
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/unary", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.success").value(true));
        assertEquals(2, transport.latest().descriptorProtos().size());
        assertTrue(transport.latest().descriptorProtos().stream().anyMatch(item -> item.getName().equals("offline_echo.proto")));

        mvc.perform(delete("/api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}", projectId, descriptorId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/descriptors", projectId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void rejectsInvalidProtoBeforeSavingDescriptor() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MockMultipartFile invalid = new MockMultipartFile("files", "broken.proto", "text/plain", "syntax = \"proto3\"; message Broken { string name = ; }"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/v1/projects/{projectId}/api-debug/grpc/descriptors", projectId).file(invalid).param("name", "损坏的描述符")
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isBadRequest());
        Integer count = jdbc.sql("SELECT COUNT(*) FROM grpc_descriptor WHERE project_id = :projectId").param("projectId", projectId)
                .query(Integer.class).single();
        assertEquals(0, count);
    }

    @Test
    void importsProtocDescriptorSetForOfflineDiscovery() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        byte[] bytes = FileDescriptorSet.newBuilder().addFile(HealthProto.getDescriptor().toProto()).build().toByteArray();
        MockMultipartFile descriptorSet = new MockMultipartFile("files", "health.desc", "application/octet-stream", bytes);

        MvcResult imported = mvc.perform(multipart("/api/v1/projects/{projectId}/api-debug/grpc/descriptors", projectId).file(descriptorSet)
                        .param("name", "Health DescriptorSet").cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceFormat").value("DESCRIPTOR_SET"))
                .andExpect(jsonPath("$.data.serviceCount").value(1)).andReturn();
        String descriptorId = json(imported).at("/data/id").asText();
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}/discovery", projectId, descriptorId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.services[0]").value("grpc.health.v1.Health"));
        mvc.perform(delete("/api/v1/projects/{projectId}/api-debug/grpc/descriptors/{descriptorId}", projectId, descriptorId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
    }

    @Test
    void startsCancelsAndBoundsServerStreamingCallsWithoutPersistingTraffic() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "8.8.8.8:443", "tls", true, "service", "testhub.echo.v1.EchoService",
                "method", "Watch", "requestJson", "{\"message\":\"stream\"}", "timeoutMs", 1000,
                "metadata", List.of(Map.of("key", "x-stream-trace", "value", "trace-123")));

        MvcResult started = mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/server-stream", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.messageCount").value(0)).andReturn();
        String streamId = json(started).at("/data/id").asText();
        assertEquals("Watch", transport.latestStream().method());
        assertTrue(transport.latestStream().metadata().stream().anyMatch(item -> item.key().equals("x-stream-trace") && item.value().equals("trace-123")));

        transport.emitStream("{\"sequence\":1}");
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/streams/{streamId}/messages", projectId, streamId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].payload").value("{\"sequence\":1}"));

        for (int index = 2; index <= 101; index++) transport.emitStream("{\"sequence\":" + index + "}");
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/streams/{streamId}", projectId, streamId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.data.messageCount").value(100));
        assertTrue(transport.streamCancelled());
    }

    @Test
    void allowsUserToCancelAnOpenServerStream() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> request = Map.of("endpoint", "8.8.8.8:443", "tls", true, "service", "testhub.echo.v1.EchoService",
                "method", "Watch", "requestJson", "{}", "timeoutMs", 1000);
        MvcResult started = mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/server-stream", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn();
        String streamId = json(started).at("/data/id").asText();

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/streams/{streamId}/cancel", projectId, streamId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertTrue(transport.streamCancelled());
    }

    @Test
    void sendsCompletesAndReadsClientStreamingCallsWithoutPersistingMessages() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> start = Map.of("endpoint", "8.8.8.8:443", "tls", true, "service", "testhub.echo.v1.EchoService",
                "method", "Collect", "timeoutMs", 30_000, "metadata", List.of(Map.of("key", "x-stream-trace", "value", "client-123")));
        MvcResult started = mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/client-streams", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(start)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.sentMessageCount").value(0)).andReturn();
        String streamId = json(started).at("/data/id").asText();
        assertEquals("Collect", transport.latestClientStream().method());

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/client-streams/{streamId}/messages", projectId, streamId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"requestJson\":\"{\\\"sequence\\\":1}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sentMessageCount").value(1));
        assertEquals(List.of("{\"sequence\":1}"), transport.clientMessages());

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/client-streams/{streamId}/complete", projectId, streamId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("HALF_CLOSED"));
        assertTrue(transport.clientCompleted());
        transport.respondClientStream("{\"received\":1}");
        transport.completeClientStream();
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/client-streams/{streamId}", projectId, streamId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.responseBody").value("{\"received\":1}"));
    }

    @Test
    void sendsAndReceivesBidirectionalStreamingCallsWithoutPersistingOutboundMessages() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        Map<String, Object> start = Map.of("endpoint", "8.8.8.8:443", "tls", true, "service", "testhub.echo.v1.EchoService",
                "method", "Chat", "timeoutMs", 30_000, "metadata", List.of(Map.of("key", "x-stream-trace", "value", "bidi-123")));
        MvcResult started = mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/bidi-streams", projectId).contentType(MediaType.APPLICATION_JSON)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(start)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.sentMessageCount").value(0)).andExpect(jsonPath("$.data.receivedMessageCount").value(0)).andReturn();
        String streamId = json(started).at("/data/id").asText();
        assertEquals("Chat", transport.latestBidiStream().method());

        transport.emitBidiStream("{\"sequence\":1}");
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/bidi-streams/{streamId}/messages", projectId, streamId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].payload").value("{\"sequence\":1}"));
        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/bidi-streams/{streamId}/messages", projectId, streamId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content("{\"requestJson\":\"{\\\"sequence\\\":2}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sentMessageCount").value(1))
                .andExpect(jsonPath("$.data.receivedMessageCount").value(1));
        assertEquals(List.of("{\"sequence\":2}"), transport.bidiMessages());

        mvc.perform(post("/api/v1/projects/{projectId}/api-debug/grpc/bidi-streams/{streamId}/complete", projectId, streamId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("HALF_CLOSED"));
        assertTrue(transport.bidiCompleted());
        transport.completeBidiStream();
        mvc.perform(get("/api/v1/projects/{projectId}/api-debug/grpc/bidi-streams/{streamId}", projectId, streamId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    private void upsert(LoginSession session, String projectId, String type, Map<String, String> value) throws Exception {
        Csrf csrf = csrf(session);
        mvc.perform(post("/api/v1/projects/{projectId}/{type}", projectId, type).contentType(MediaType.APPLICATION_JSON)
                        .cookie(session.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token())
                        .content(objectMapper.writeValueAsString(value)))
                .andExpect(status().isNoContent());
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }

    @TestConfiguration
    static class GrpcTransportConfiguration {
        @Bean
        @Primary
        RecordingGrpcTransport recordingGrpcTransport() {
            return new RecordingGrpcTransport();
        }
    }

    static final class RecordingGrpcTransport implements GrpcDebugTransport {
        private final AtomicReference<Request> latest = new AtomicReference<>();
        private final AtomicReference<DiscoveryRequest> latestDiscovery = new AtomicReference<>();
        private final AtomicReference<Request> latestStream = new AtomicReference<>();
        private final AtomicReference<StreamListener> streamListener = new AtomicReference<>();
        private final AtomicReference<RecordingSubscription> streamSubscription = new AtomicReference<>();
        private final AtomicReference<Request> latestClientStream = new AtomicReference<>();
        private final AtomicReference<ClientStreamListener> clientStreamListener = new AtomicReference<>();
        private final AtomicReference<RecordingClientSubscription> clientStreamSubscription = new AtomicReference<>();
        private final AtomicReference<Request> latestBidiStream = new AtomicReference<>();
        private final AtomicReference<BidiStreamListener> bidiStreamListener = new AtomicReference<>();
        private final AtomicReference<RecordingBidiSubscription> bidiStreamSubscription = new AtomicReference<>();

        @Override
        public Response unary(Request request) {
            latest.set(request);
            return new Response("{\"message\":\"ok\"}");
        }

        @Override
        public StreamSubscription serverStreaming(Request request, StreamListener listener) {
            latestStream.set(request);
            streamListener.set(listener);
            RecordingSubscription subscription = new RecordingSubscription();
            streamSubscription.set(subscription);
            return subscription;
        }

        @Override
        public ClientStreamSubscription clientStreaming(Request request, ClientStreamListener listener) {
            latestClientStream.set(request);
            clientStreamListener.set(listener);
            RecordingClientSubscription subscription = new RecordingClientSubscription();
            clientStreamSubscription.set(subscription);
            return subscription;
        }

        @Override
        public BidiStreamSubscription bidiStreaming(Request request, BidiStreamListener listener) {
            latestBidiStream.set(request);
            bidiStreamListener.set(listener);
            RecordingBidiSubscription subscription = new RecordingBidiSubscription();
            bidiStreamSubscription.set(subscription);
            return subscription;
        }

        @Override
        public List<String> listServices(DiscoveryRequest request) {
            latestDiscovery.set(request);
            return List.of("testhub.echo.v1.EchoService");
        }

        @Override
        public List<MethodSummary> listMethods(DiscoveryRequest request, String service) {
            latestDiscovery.set(request);
            return List.of(new MethodSummary("Echo", "UNARY"));
        }

        Request latest() {
            return latest.get();
        }

        DiscoveryRequest latestDiscovery() {
            return latestDiscovery.get();
        }

        Request latestStream() {
            return latestStream.get();
        }

        Request latestClientStream() {
            return latestClientStream.get();
        }

        Request latestBidiStream() {
            return latestBidiStream.get();
        }

        void emitStream(String payload) {
            StreamListener listener = streamListener.get();
            if (listener != null) listener.onMessage(payload);
        }

        boolean streamCancelled() {
            RecordingSubscription subscription = streamSubscription.get();
            return subscription != null && subscription.cancelled;
        }

        List<String> clientMessages() {
            RecordingClientSubscription subscription = clientStreamSubscription.get();
            return subscription == null ? List.of() : List.copyOf(subscription.messages);
        }

        boolean clientCompleted() {
            RecordingClientSubscription subscription = clientStreamSubscription.get();
            return subscription != null && subscription.completed;
        }

        void respondClientStream(String payload) {
            ClientStreamListener listener = clientStreamListener.get();
            if (listener != null) listener.onResponse(payload);
        }

        void completeClientStream() {
            ClientStreamListener listener = clientStreamListener.get();
            if (listener != null) listener.onCompleted();
        }

        List<String> bidiMessages() {
            RecordingBidiSubscription subscription = bidiStreamSubscription.get();
            return subscription == null ? List.of() : List.copyOf(subscription.messages);
        }

        boolean bidiCompleted() {
            RecordingBidiSubscription subscription = bidiStreamSubscription.get();
            return subscription != null && subscription.completed;
        }

        void emitBidiStream(String payload) {
            BidiStreamListener listener = bidiStreamListener.get();
            if (listener != null) listener.onMessage(payload);
        }

        void completeBidiStream() {
            BidiStreamListener listener = bidiStreamListener.get();
            if (listener != null) listener.onCompleted();
        }

        void clear() {
            latest.set(null);
            latestDiscovery.set(null);
            latestStream.set(null);
            streamListener.set(null);
            streamSubscription.set(null);
            latestClientStream.set(null);
            clientStreamListener.set(null);
            clientStreamSubscription.set(null);
            latestBidiStream.set(null);
            bidiStreamListener.set(null);
            bidiStreamSubscription.set(null);
        }

        private static final class RecordingSubscription implements StreamSubscription {
            private boolean cancelled;

            @Override
            public void cancel(String reason) {
                cancelled = true;
            }
        }

        private static final class RecordingClientSubscription implements ClientStreamSubscription {
            private final List<String> messages = new java.util.ArrayList<>();
            private boolean completed;
            private boolean cancelled;

            @Override
            public void send(String requestJson) {
                messages.add(requestJson);
            }

            @Override
            public void complete() {
                completed = true;
            }

            @Override
            public void cancel(String reason) {
                cancelled = true;
            }
        }

        private static final class RecordingBidiSubscription implements BidiStreamSubscription {
            private final List<String> messages = new java.util.ArrayList<>();
            private boolean completed;
            private boolean cancelled;

            @Override
            public void send(String requestJson) {
                messages.add(requestJson);
            }

            @Override
            public void complete() {
                completed = true;
            }

            @Override
            public void cancel(String reason) {
                cancelled = true;
            }
        }
    }
}
